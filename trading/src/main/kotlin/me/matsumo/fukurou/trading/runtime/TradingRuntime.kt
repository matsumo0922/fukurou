package me.matsumo.fukurou.trading.runtime

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.broker.DefaultPaperExecutionSimulator
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.PaperBroker
import me.matsumo.fukurou.trading.broker.toInitialAccountSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigResolution
import me.matsumo.fukurou.trading.config.RuntimeConfigResolver
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.InMemoryEvaluationRepository
import me.matsumo.fukurou.trading.evaluation.InMemoryLlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.persistence.ExposedCommandEventLog
import me.matsumo.fukurou.trading.persistence.ExposedDecisionRepository
import me.matsumo.fukurou.trading.persistence.ExposedEquitySnapshotRepository
import me.matsumo.fukurou.trading.persistence.ExposedEvaluationRepository
import me.matsumo.fukurou.trading.persistence.ExposedLlmRunRepository
import me.matsumo.fukurou.trading.persistence.ExposedPaperLedgerRepository
import me.matsumo.fukurou.trading.persistence.ExposedReconcilerStatusProvider
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateCommandService
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateRepository
import me.matsumo.fukurou.trading.persistence.ExposedRuntimeConfigRepository
import me.matsumo.fukurou.trading.persistence.ExposedSafetyViolationRepository
import me.matsumo.fukurou.trading.persistence.PostgresGlobalTradingLock
import me.matsumo.fukurou.trading.persistence.RuntimeConfigPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.reconciler.NoReconcilerStatusProvider
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatusProvider
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.safety.InMemorySafetyViolationRepository
import me.matsumo.fukurou.trading.safety.SafetyFloor
import me.matsumo.fukurou.trading.safety.SafetyViolationRepository
import me.matsumo.fukurou.trading.tool.CallerNoTradeGuard
import me.matsumo.fukurou.trading.tool.ToolCallGuard
import java.time.Clock
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * DB_URL 環境変数名。
 */
private const val DB_URL_ENV = "DB_URL"

/**
 * DB_USER 環境変数名。
 */
private const val DB_USER_ENV = "DB_USER"

/**
 * DB_PASSWORD 環境変数名。
 */
private const val DB_PASSWORD_ENV = "DB_PASSWORD"

/**
 * runtime DB pool の最大接続数。
 */
private const val MAXIMUM_POOL_SIZE = 4

/**
 * runtime DB pool の初期化失敗 timeout。
 */
private const val INITIALIZATION_FAIL_TIMEOUT = -1L

/**
 * trading module が提供する runtime repository 一式。
 *
 * @param riskStateRepository risk_state repository
 * @param riskStateCommandService risk_state 更新と audit をまとめる command service
 * @param commandEventLog command_event_log repository
 * @param llmRunRepository llm_runs repository
 * @param equitySnapshotRepository equity_snapshots repository
 * @param evaluationRepository evaluation aggregate repository
 * @param decisionRepository decision protocol repository
 * @param safetyViolationRepository SafetyFloor violation repository
 * @param broker account / position ledger 読み取り境界
 * @param tradingLock global trading lock
 * @param toolCallGuard MCP tool call guard
 * @param callerNoTradeGuard MCP caller boundary guard
 * @param close runtime resource cleanup
 */
data class TradingRuntime(
    val riskStateRepository: RiskStateRepository,
    val riskStateCommandService: RiskStateCommandService,
    val commandEventLog: CommandEventLog,
    val llmRunRepository: LlmRunRepository,
    val equitySnapshotRepository: EquitySnapshotRepository,
    val evaluationRepository: EvaluationRepository,
    val decisionRepository: DecisionRepository,
    val safetyViolationRepository: SafetyViolationRepository,
    val broker: Broker,
    val tradingLock: TradingLock,
    val toolCallGuard: ToolCallGuard,
    val callerNoTradeGuard: CallerNoTradeGuard,
    val close: () -> Unit,
) {
    /**
     * runtime resource を閉じる。
     */
    fun close() {
        close.invoke()
    }
}

/**
 * DB 接続設定。
 *
 * @param url JDBC URL
 * @param user DB user
 * @param password DB password
 */
data class TradingDatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    companion object {
        /**
         * 環境変数 DB_URL / DB_USER / DB_PASSWORD から DB 設定を読む。
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): TradingDatabaseConfig? {
            val url = environment[DB_URL_ENV]
            val user = environment[DB_USER_ENV]
            val password = environment[DB_PASSWORD_ENV]
            val missingRequiredConfig = listOf(url, user, password).any { value -> value.isNullOrBlank() }

            if (missingRequiredConfig) {
                return null
            }

            return TradingDatabaseConfig(
                url = requireNotNull(url),
                user = requireNotNull(user),
                password = requireNotNull(password),
            )
        }
    }
}

/**
 * trading runtime repository を構築する factory。
 */
object TradingRuntimeFactory {

    /**
     * DB active runtime config を環境変数の deployment / secret 値と合成して解決する。
     */
    fun resolveRuntimeConfigFromEnvironment(
        environment: Map<String, String> = System.getenv(),
        clock: Clock = Clock.systemUTC(),
    ): RuntimeConfigResolution {
        val databaseConfig = requireNotNull(TradingDatabaseConfig.fromEnvironment(environment)) {
            "DB_URL, DB_USER, and DB_PASSWORD are required for runtime config resolution."
        }
        val dataSource = createDataSource(databaseConfig)

        try {
            val database = ExposedDatabase.connect(dataSource)

            RuntimeConfigPersistenceBootstrap(
                database = database,
                clock = clock,
            ).ensureSchema().getOrThrow()

            return RuntimeConfigResolver(
                ExposedRuntimeConfigRepository(database),
            ).resolve(environment).getOrThrow()
        } finally {
            dataSource.close()
        }
    }

    /**
     * 環境変数から DB-backed runtime を構築する。DB 設定が欠けている場合は fail closed する。
     */
    fun fromEnvironment(
        environment: Map<String, String> = System.getenv(),
        clock: Clock = Clock.systemUTC(),
        reconcilerStatusProvider: ReconcilerStatusProvider? = null,
        marketDataSource: MarketDataSource? = null,
        tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(environment),
    ): TradingRuntime {
        val databaseConfig = requireNotNull(TradingDatabaseConfig.fromEnvironment(environment)) {
            "DB_URL, DB_USER, and DB_PASSWORD are required for trading runtime."
        }

        return postgres(
            config = databaseConfig,
            clock = clock,
            reconcilerStatusProvider = reconcilerStatusProvider,
            marketDataSource = marketDataSource,
            tradingConfig = tradingConfig,
        )
    }

    /**
     * in-memory runtime を構築する。
     */
    fun inMemory(
        clock: Clock = Clock.systemUTC(),
        reconcilerStatusProvider: ReconcilerStatusProvider = NoReconcilerStatusProvider,
        marketDataSource: MarketDataSource? = null,
        tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(),
    ): TradingRuntime {
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val commandEventLog = InMemoryCommandEventLog()
        val llmRunRepository = InMemoryLlmRunRepository()
        val evaluationRepository = InMemoryEvaluationRepository()
        val decisionRepository = InMemoryDecisionRepository(clock)
        val safetyViolationRepository = InMemorySafetyViolationRepository()
        val riskStateCommandService = InMemoryRiskStateCommandService(
            riskStateRepository = riskStateRepository,
            commandEventLog = commandEventLog,
            clock = clock,
        )
        val ledgerRepository = InMemoryPaperLedgerRepository(
            accountSnapshot = tradingConfig.paperAccount.toInitialAccountSnapshot(),
            accountUpdatedAt = Instant.now(clock),
            fallbackSymbolRules = tradingConfig.paperMarket.toSymbolRules(tradingConfig.symbol),
            clock = clock,
        )
        val brokerRepositories = InMemoryBrokerRepositories(
            ledgerRepository = ledgerRepository,
            riskStateRepository = riskStateRepository,
            riskStateCommandService = riskStateCommandService,
            decisionRepository = decisionRepository,
            safetyViolationRepository = safetyViolationRepository,
        )
        val broker = tradingConfig.createInMemoryBroker(
            repositories = brokerRepositories,
            marketDataSource = marketDataSource,
            reconcilerStatusProvider = reconcilerStatusProvider,
            clock = clock,
        )
        val tradingLock = InMemoryTradingLock(clock)
        val callerNoTradeGuard = CallerNoTradeGuard(commandEventLog, clock)
        val toolCallGuard = ToolCallGuard(
            riskStateRepository = riskStateRepository,
            commandEventLog = commandEventLog,
            tradingLock = tradingLock,
            clock = clock,
        )

        return TradingRuntime(
            riskStateRepository = riskStateRepository,
            riskStateCommandService = riskStateCommandService,
            commandEventLog = commandEventLog,
            llmRunRepository = llmRunRepository,
            equitySnapshotRepository = ledgerRepository.equitySnapshotRepository,
            evaluationRepository = evaluationRepository,
            decisionRepository = decisionRepository,
            safetyViolationRepository = safetyViolationRepository,
            broker = broker,
            tradingLock = tradingLock,
            toolCallGuard = toolCallGuard,
            callerNoTradeGuard = callerNoTradeGuard,
            close = {},
        )
    }

    /**
     * Postgres/Exposed runtime を構築し、backend bootstrap 済み schema を検証する。
     */
    fun postgres(
        config: TradingDatabaseConfig,
        clock: Clock = Clock.systemUTC(),
        reconcilerStatusProvider: ReconcilerStatusProvider? = null,
        marketDataSource: MarketDataSource? = null,
        tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(),
    ): TradingRuntime {
        val dataSource = createDataSource(config)

        try {
            val database = ExposedDatabase.connect(dataSource)
            return connectedPostgres(
                dataSource = dataSource,
                database = database,
                clock = clock,
                reconcilerStatusProvider = reconcilerStatusProvider,
                marketDataSource = marketDataSource,
                tradingConfig = tradingConfig,
                closeDataSource = true,
            )
        } catch (throwable: Throwable) {
            runCatching {
                dataSource.close()
            }.exceptionOrNull()?.let { closeThrowable -> throwable.addSuppressed(closeThrowable) }

            throw throwable
        }
    }

    /**
     * 既存 DataSource / Exposed database から Postgres runtime を構築する。
     */
    fun connectedPostgres(
        dataSource: HikariDataSource,
        database: ExposedDatabase,
        clock: Clock = Clock.systemUTC(),
        reconcilerStatusProvider: ReconcilerStatusProvider? = null,
        marketDataSource: MarketDataSource? = null,
        tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(),
        closeDataSource: Boolean = false,
    ): TradingRuntime {
        val connection = PostgresRuntimeConnection(dataSource, database, closeDataSource)
        val context = PostgresRuntimeContext(clock, reconcilerStatusProvider, marketDataSource, tradingConfig)

        verifyPostgresSchema(connection, context)

        val repositories = createPostgresRepositories(connection, context)
        val services = createPostgresServices(connection, context, repositories)
        val closeAction = if (connection.closeDataSource) {
            { connection.dataSource.close() }
        } else {
            {}
        }

        return TradingRuntime(
            riskStateRepository = repositories.riskStateRepository,
            riskStateCommandService = services.riskStateCommandService,
            commandEventLog = repositories.commandEventLog,
            llmRunRepository = repositories.llmRunRepository,
            equitySnapshotRepository = repositories.equitySnapshotRepository,
            evaluationRepository = repositories.evaluationRepository,
            decisionRepository = repositories.decisionRepository,
            safetyViolationRepository = services.safetyViolationRepository,
            broker = services.broker,
            tradingLock = services.tradingLock,
            toolCallGuard = services.toolCallGuard,
            callerNoTradeGuard = services.callerNoTradeGuard,
            close = closeAction,
        )
    }
}

/**
 * in-memory PaperBroker 構築に使う repository / service 群。
 *
 * @param ledgerRepository paper ledger repository
 * @param riskStateRepository risk_state repository
 * @param riskStateCommandService risk_state 更新と audit をまとめる command service
 * @param decisionRepository decision protocol repository
 * @param safetyViolationRepository SafetyFloor violation repository
 */
private data class InMemoryBrokerRepositories(
    val ledgerRepository: InMemoryPaperLedgerRepository,
    val riskStateRepository: RiskStateRepository,
    val riskStateCommandService: RiskStateCommandService,
    val decisionRepository: DecisionRepository,
    val safetyViolationRepository: SafetyViolationRepository,
)

private fun TradingBotConfig.createInMemoryBroker(
    repositories: InMemoryBrokerRepositories,
    marketDataSource: MarketDataSource?,
    reconcilerStatusProvider: ReconcilerStatusProvider,
    clock: Clock,
): PaperBroker {
    return PaperBroker(
        ledgerRepository = repositories.ledgerRepository,
        riskStateRepository = repositories.riskStateRepository,
        riskStateCommandService = repositories.riskStateCommandService,
        decisionRepository = repositories.decisionRepository,
        falsificationFreshnessWindow = decisionProtocol.falsificationFreshnessWindow,
        safetyViolationRepository = repositories.safetyViolationRepository,
        safetyFloor = SafetyFloor(
            config = safetyFloor,
            clock = clock,
            paperExecutionConfig = paperExecution,
        ),
        marketDataSource = marketDataSource,
        paperExecutionConfig = paperExecution,
        fillSimulator = DefaultPaperExecutionSimulator(paperExecution, clock),
        reconcilerStatusProvider = reconcilerStatusProvider,
        clock = clock,
    )
}

private fun verifyPostgresSchema(connection: PostgresRuntimeConnection, context: PostgresRuntimeContext) {
    TradingPersistenceBootstrap(
        database = connection.database,
        clock = context.clock,
        paperAccountConfig = context.tradingConfig.paperAccount,
    ).verifySchema().getOrThrow()
}

private fun createPostgresRepositories(
    connection: PostgresRuntimeConnection,
    context: PostgresRuntimeContext,
): PostgresRuntimeRepositories {
    return PostgresRuntimeRepositories(
        riskStateRepository = ExposedRiskStateRepository(connection.database),
        commandEventLog = ExposedCommandEventLog(connection.database),
        llmRunRepository = ExposedLlmRunRepository(connection.database),
        equitySnapshotRepository = ExposedEquitySnapshotRepository(connection.database),
        evaluationRepository = ExposedEvaluationRepository(connection.database),
        decisionRepository = ExposedDecisionRepository(connection.database, context.clock),
    )
}

private fun createPostgresServices(
    connection: PostgresRuntimeConnection,
    context: PostgresRuntimeContext,
    repositories: PostgresRuntimeRepositories,
): PostgresRuntimeServices {
    val riskStateCommandService = ExposedRiskStateCommandService(connection.database, context.clock)
    val safetyViolationRepository = ExposedSafetyViolationRepository(connection.database)
    val tradingLock = PostgresGlobalTradingLock(connection.dataSource, context.clock)
    val broker = createPostgresBroker(
        connection = connection,
        context = context,
        repositories = repositories,
        riskStateCommandService = riskStateCommandService,
        safetyViolationRepository = safetyViolationRepository,
    )
    val callerNoTradeGuard = CallerNoTradeGuard(repositories.commandEventLog, context.clock)
    val toolCallGuard = ToolCallGuard(
        riskStateRepository = repositories.riskStateRepository,
        commandEventLog = repositories.commandEventLog,
        tradingLock = tradingLock,
        clock = context.clock,
    )

    return PostgresRuntimeServices(
        riskStateCommandService = riskStateCommandService,
        safetyViolationRepository = safetyViolationRepository,
        broker = broker,
        tradingLock = tradingLock,
        callerNoTradeGuard = callerNoTradeGuard,
        toolCallGuard = toolCallGuard,
    )
}

private fun createPostgresBroker(
    connection: PostgresRuntimeConnection,
    context: PostgresRuntimeContext,
    repositories: PostgresRuntimeRepositories,
    riskStateCommandService: RiskStateCommandService,
    safetyViolationRepository: SafetyViolationRepository,
): PaperBroker {
    val resolvedReconcilerStatusProvider = context.reconcilerStatusProvider
        ?: ExposedReconcilerStatusProvider(connection.database)

    return PaperBroker(
        ledgerRepository = ExposedPaperLedgerRepository(
            database = connection.database,
            fallbackSymbolRules = context.tradingConfig.paperMarket.toSymbolRules(context.tradingConfig.symbol),
        ),
        riskStateRepository = repositories.riskStateRepository,
        riskStateCommandService = riskStateCommandService,
        decisionRepository = repositories.decisionRepository,
        falsificationFreshnessWindow = context.tradingConfig.decisionProtocol.falsificationFreshnessWindow,
        safetyViolationRepository = safetyViolationRepository,
        safetyFloor = SafetyFloor(
            config = context.tradingConfig.safetyFloor,
            clock = context.clock,
            paperExecutionConfig = context.tradingConfig.paperExecution,
        ),
        marketDataSource = context.marketDataSource,
        paperExecutionConfig = context.tradingConfig.paperExecution,
        fillSimulator = DefaultPaperExecutionSimulator(context.tradingConfig.paperExecution, context.clock),
        reconcilerStatusProvider = resolvedReconcilerStatusProvider,
        clock = context.clock,
    )
}

/**
 * Postgres runtime の接続 resource。
 *
 * @param dataSource JDBC data source
 * @param database Exposed database
 * @param closeDataSource runtime close 時に data source を閉じるなら true
 */
private data class PostgresRuntimeConnection(
    val dataSource: HikariDataSource,
    val database: ExposedDatabase,
    val closeDataSource: Boolean,
)

/**
 * Postgres runtime の構築 context。
 *
 * @param clock runtime clock
 * @param reconcilerStatusProvider injected reconciler status provider
 * @param marketDataSource injected market data source
 * @param tradingConfig trading bot config
 */
private data class PostgresRuntimeContext(
    val clock: Clock,
    val reconcilerStatusProvider: ReconcilerStatusProvider?,
    val marketDataSource: MarketDataSource?,
    val tradingConfig: TradingBotConfig,
)

/**
 * Postgres runtime の repository 群。
 *
 * @param riskStateRepository risk state repository
 * @param commandEventLog command event log
 * @param llmRunRepository LLM run repository
 * @param equitySnapshotRepository equity snapshot repository
 * @param evaluationRepository evaluation repository
 * @param decisionRepository decision repository
 */
private data class PostgresRuntimeRepositories(
    val riskStateRepository: ExposedRiskStateRepository,
    val commandEventLog: ExposedCommandEventLog,
    val llmRunRepository: ExposedLlmRunRepository,
    val equitySnapshotRepository: ExposedEquitySnapshotRepository,
    val evaluationRepository: ExposedEvaluationRepository,
    val decisionRepository: ExposedDecisionRepository,
)

/**
 * Postgres runtime の service / guard 群。
 *
 * @param riskStateCommandService risk state command service
 * @param safetyViolationRepository safety violation repository
 * @param broker broker boundary
 * @param tradingLock global trading lock
 * @param callerNoTradeGuard caller no-trade guard
 * @param toolCallGuard tool call guard
 */
private data class PostgresRuntimeServices(
    val riskStateCommandService: RiskStateCommandService,
    val safetyViolationRepository: SafetyViolationRepository,
    val broker: Broker,
    val tradingLock: TradingLock,
    val callerNoTradeGuard: CallerNoTradeGuard,
    val toolCallGuard: ToolCallGuard,
)

/**
 * HikariCP の DataSource を作る。
 */
private fun createDataSource(config: TradingDatabaseConfig): HikariDataSource {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = config.url
        username = config.user
        password = config.password
        maximumPoolSize = MAXIMUM_POOL_SIZE
        initializationFailTimeout = INITIALIZATION_FAIL_TIMEOUT
    }

    return HikariDataSource(hikariConfig)
}
