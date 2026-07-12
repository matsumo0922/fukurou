package me.matsumo.fukurou

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.DefaultLlmDaemonPreFilter
import me.matsumo.fukurou.trading.daemon.DefaultLlmDaemonPreFilterDependencies
import me.matsumo.fukurou.trading.daemon.DefaultManualLlmLaunchService
import me.matsumo.fukurou.trading.daemon.LlmDaemonEntryFillReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonOpenRiskReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonOpenRiskSnapshot
import me.matsumo.fukurou.trading.daemon.LlmDaemonPositionsReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonScheduler
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerDependencies
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerRuntime
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickerReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickerSnapshot
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchServiceDependencies
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchServiceRuntime
import me.matsumo.fukurou.trading.daemon.asDaemonLauncher
import me.matsumo.fukurou.trading.daemon.toLlmDaemonEntryFillOrNull
import me.matsumo.fukurou.trading.exchange.gmo.CommandEventLogGmoPublicRequestAuditSink
import me.matsumo.fukurou.trading.exchange.gmo.DeferredGmoPublicRequestAuditSink
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientType
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.persistence.ExposedLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.persistence.ExposedPaperLedgerRepository
import me.matsumo.fukurou.trading.persistence.ExposedRestingOrderMaintenanceService
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.staleLlmRunRecoveryThreshold
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_JAR_PATH_ENV
import me.matsumo.fukurou.trading.runner.LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE
import me.matsumo.fukurou.trading.runner.LlmInvocationAuditor
import me.matsumo.fukurou.trading.runner.OneShotLlmRunner
import me.matsumo.fukurou.trading.runner.OneShotRunnerCliConfig
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.OneShotRunnerResult
import me.matsumo.fukurou.trading.runner.SecretRedactor
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * daemon scheduler bootstrap failure log の rate limit key。
 */
private const val DAEMON_BOOTSTRAP_FAILURE_LOG_KEY = "llm-daemon-scheduler-worker-bootstrap-failure"

/**
 * repository root の環境変数名。
 */
private const val FUKUROU_REPOSITORY_ROOT_ENV = "FUKUROU_REPOSITORY_ROOT"

/**
 * LLM CLI working directory の環境変数名。
 */
private const val FUKUROU_LLM_WORKING_DIRECTORY_ENV = "FUKUROU_LLM_WORKING_DIRECTORY"

/**
 * LlmDaemonSchedulerWorker 用 logger。
 */
private val DAEMON_WORKER_LOGGER = Logger.getLogger(LlmDaemonSchedulerWorker::class.java.name)

/**
 * Ktor backend 上で LLM daemon scheduler を常駐起動する worker。
 *
 * @param schedulerFactory scheduler 構築処理
 * @param interval loop 間隔
 * @param bootstrap scheduler loop 開始前に必要な DB schema 初期化
 * @param clock warning log の rate limit 判定に使う clock
 * @param warnLogger rate-limited warning logger
 * @param scope worker coroutine scope
 */
class LlmDaemonSchedulerWorker(
    private val schedulerFactory: () -> Result<LlmDaemonScheduler>,
    private val interval: Duration,
    private val bootstrap: () -> Result<Unit> = { Result.success(Unit) },
    clock: Clock = Clock.systemUTC(),
    private val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = DAEMON_WORKER_LOGGER,
        clock = clock,
    ),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private var job: Job? = null

    /**
     * worker loop を開始する。
     */
    fun start(): LlmDaemonSchedulerWorker {
        require(job == null) { "LlmDaemonSchedulerWorker is already started." }

        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                val loopResult = bootstrap().mapCatching {
                    schedulerFactory().getOrThrow().runLoop(interval)
                }

                if (loopResult.isSuccess) {
                    continue
                }

                val throwable = requireNotNull(loopResult.exceptionOrNull())

                if (throwable is CancellationException) {
                    throw throwable
                }

                warnLogger.warn(
                    key = DAEMON_BOOTSTRAP_FAILURE_LOG_KEY,
                    message = "LlmDaemonSchedulerWorker bootstrap or scheduler loop failed.",
                    throwable = throwable,
                )

                delay(interval.toMillis().toDuration(DurationUnit.MILLISECONDS))
            }
        }

        return this
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }
}

/**
 * DB runtime から LlmDaemonSchedulerWorker を構築して起動する。
 */
internal fun startLlmDaemonSchedulerWorker(
    dataSource: HikariDataSource,
    database: ExposedDatabase,
    tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(),
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    clock: Clock = Clock.systemUTC(),
    onStaleLlmRunsRecovered: (Int) -> Unit = {},
    latestMarketQuoteStore: LatestMarketQuoteStore = LatestMarketQuoteStore(),
): LlmDaemonSchedulerWorker? {
    val environment = System.getenv()

    if (!tradingConfig.daemon.enabled) {
        return null
    }

    return LlmDaemonSchedulerWorker(
        schedulerFactory = {
            runCatching {
                createLlmDaemonScheduler(
                    inputs = LlmLaunchRuntimeInputs(
                        dataSource = dataSource,
                        database = database,
                        clock = clock,
                        environment = environment,
                        tradingConfig = tradingConfig,
                        runtimeConfigSnapshot = runtimeConfigSnapshot,
                        requestBase = oneShotRequestFromEnvironment(environment),
                        latestMarketQuoteStore = latestMarketQuoteStore,
                    ),
                )
            }
        },
        interval = tradingConfig.daemon.pollInterval,
        bootstrap = {
            TradingPersistenceBootstrap(
                database = database,
                clock = clock,
                paperAccountConfig = tradingConfig.paperAccount,
                staleLlmRunRecoveryThreshold = tradingConfig.staleLlmRunRecoveryThreshold(),
                onStaleLlmRunsRecovered = onStaleLlmRunsRecovered,
            ).ensureSchema()
        },
        clock = clock,
    ).start()
}

private fun createLlmDaemonScheduler(inputs: LlmLaunchRuntimeInputs): LlmDaemonScheduler {
    val components = createLlmLaunchRuntimeComponents(
        inputs = inputs,
    )

    return LlmDaemonScheduler(
        tradingConfig = inputs.tradingConfig,
        runtimeConfigSnapshot = inputs.runtimeConfigSnapshot,
        dependencies = LlmDaemonSchedulerDependencies(
            riskStateRepository = components.tradingRuntime.riskStateRepository,
            commandEventLog = components.tradingRuntime.commandEventLog,
            launchReservationRepository = components.launchReservationRepository,
            openRiskReader = components.tradingRuntime.openRiskReader(),
            tickerReader = components.marketDataSource.tickerReader(inputs.tradingConfig),
            positionsReader = components.tradingRuntime.positionsReader(),
            entryFillReader = components.paperLedgerRepository.entryFillReader(),
            restingOrderMaintenanceService = ExposedRestingOrderMaintenanceService(
                database = inputs.database,
                broker = components.tradingRuntime.broker,
                tradingLock = components.tradingRuntime.tradingLock,
                latestMarketQuoteStore = inputs.latestMarketQuoteStore,
            ),
        ),
        runtime = LlmDaemonSchedulerRuntime(
            requestBase = components.requestBase,
            launchOneShot = components.launchOneShot,
            preFilter = components.preFilter,
            clock = inputs.clock,
        ),
    )
}

/**
 * DB runtime から manual LLM launch service を構築する。
 */
internal fun createManualLlmLaunchService(
    dataSource: HikariDataSource,
    database: ExposedDatabase,
    environment: Map<String, String>,
    tradingConfig: TradingBotConfig,
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    clock: Clock,
): DefaultManualLlmLaunchService? {
    val requestBase = oneShotRequestFromRequiredEnvironment(environment) ?: return null
    val components = createLlmLaunchRuntimeComponents(
        inputs = LlmLaunchRuntimeInputs(
            dataSource = dataSource,
            database = database,
            clock = clock,
            environment = environment,
            tradingConfig = tradingConfig,
            runtimeConfigSnapshot = runtimeConfigSnapshot,
            requestBase = requestBase,
        ),
    )

    return DefaultManualLlmLaunchService(
        tradingConfig = tradingConfig,
        runtimeConfigSnapshot = runtimeConfigSnapshot,
        dependencies = ManualLlmLaunchServiceDependencies(
            riskStateRepository = components.tradingRuntime.riskStateRepository,
            commandEventLog = components.tradingRuntime.commandEventLog,
            launchReservationRepository = components.launchReservationRepository,
            openRiskReader = components.tradingRuntime.openRiskReader(),
        ),
        runtime = ManualLlmLaunchServiceRuntime(
            requestBase = components.requestBase,
            launchOneShot = components.launchOneShot,
            clock = clock,
        ),
    )
}

/**
 * manual LLM launch service の必須 environment が揃っているかを返す。
 */
internal fun hasManualLlmLaunchServiceEnvironment(environment: Map<String, String>): Boolean {
    return oneShotRequestFromRequiredEnvironment(environment) != null
}

private fun createLlmLaunchRuntimeComponents(inputs: LlmLaunchRuntimeInputs): LlmLaunchRuntimeComponents {
    val requestAuditSink = DeferredGmoPublicRequestAuditSink()
    val marketDataSource = GmoPublicMarketDataSource.fromConfig(
        config = inputs.tradingConfig.gmoPublicClient,
        clock = inputs.clock,
        clientType = GmoPublicClientType.KTOR_LLM_RUNTIME,
        requestAuditSink = requestAuditSink,
    )
    val commandRendererConfig = LlmCommandRendererConfig.fromEnvironment(
        environment = inputs.environment,
    )
    val tradingRuntime = TradingRuntimeFactory.connectedPostgres(
        dataSource = inputs.dataSource,
        database = inputs.database,
        clock = inputs.clock,
        marketDataSource = marketDataSource,
        tradingConfig = inputs.tradingConfig,
    )
    requestAuditSink.bind(CommandEventLogGmoPublicRequestAuditSink(tradingRuntime.commandEventLog))
    val runner = OneShotLlmRunner(
        tradingRuntime = tradingRuntime,
        tradingConfig = inputs.tradingConfig,
        llmInvoker = ShellLlmInvoker(
            commandRenderer = DefaultLlmCommandRenderer(
                config = commandRendererConfig,
            ),
            processRunner = ShellProcessRunner(),
        ),
        runtimeConfigSnapshot = inputs.runtimeConfigSnapshot,
        parentEnvironment = inputs.environment,
        clock = inputs.clock,
    )
    val paperLedgerRepository = ExposedPaperLedgerRepository(
        database = inputs.database,
        fallbackSymbolRules = inputs.tradingConfig.paperMarket.toSymbolRules(inputs.tradingConfig.symbol),
    )
    val preFilter = createLlmDaemonPreFilter(
        inputs = inputs,
        marketDataSource = marketDataSource,
        tradingRuntime = tradingRuntime,
        commandRendererConfig = commandRendererConfig,
    )

    return LlmLaunchRuntimeComponents(
        tradingRuntime = tradingRuntime,
        marketDataSource = marketDataSource,
        paperLedgerRepository = paperLedgerRepository,
        launchReservationRepository = ExposedLlmLaunchReservationRepository(inputs.database),
        requestBase = inputs.requestBase.copy(
            proposerAssignment = inputs.tradingConfig.llmRoleAssignments.proposer,
            falsifierAssignment = inputs.tradingConfig.llmRoleAssignments.falsifier,
        ),
        launchOneShot = runner.asDaemonLauncher(),
        preFilter = preFilter,
    )
}

private fun createLlmDaemonPreFilter(
    inputs: LlmLaunchRuntimeInputs,
    marketDataSource: GmoPublicMarketDataSource,
    tradingRuntime: TradingRuntime,
    commandRendererConfig: LlmCommandRendererConfig,
): DefaultLlmDaemonPreFilter {
    return DefaultLlmDaemonPreFilter(
        tradingConfig = inputs.tradingConfig,
        runtimeConfigSnapshot = inputs.runtimeConfigSnapshot,
        dependencies = DefaultLlmDaemonPreFilterDependencies(
            marketDataSource = marketDataSource,
            decisionRepository = tradingRuntime.decisionRepository,
            llmInvoker = ShellLlmInvoker(
                commandRenderer = DefaultLlmCommandRenderer(
                    config = commandRendererConfig.copy(claudeModel = HAIKU_PRE_FILTER_MODEL),
                ),
                processRunner = ShellProcessRunner(),
            ),
            invocationAuditor = LlmInvocationAuditor(
                commandEventLog = tradingRuntime.commandEventLog,
                redactor = SecretRedactor.fromEnvironment(inputs.environment),
                clock = inputs.clock,
                authFailureMessage = LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE,
            ),
        ),
        parentEnvironment = inputs.environment,
    )
}

private fun TradingRuntime.openRiskReader(): LlmDaemonOpenRiskReader {
    return LlmDaemonOpenRiskReader {
        runCatching { openRiskSnapshot() }
    }
}

private suspend fun TradingRuntime.openRiskSnapshot(): LlmDaemonOpenRiskSnapshot {
    val positions = broker.getPositions().getOrThrow()
    val openOrders = broker.getOpenOrders().getOrThrow()
    val restingEntryOrders = openOrders.filter { order ->
        order.positionId == null && order.side == me.matsumo.fukurou.trading.domain.OrderSide.BUY
    }

    return LlmDaemonOpenRiskSnapshot(
        openPositionCount = positions.size,
        restingEntryOrders = restingEntryOrders,
        otherOpenOrderCount = openOrders.size - restingEntryOrders.size,
    )
}

private fun GmoPublicMarketDataSource.tickerReader(tradingConfig: TradingBotConfig): LlmDaemonTickerReader {
    return LlmDaemonTickerReader {
        getTicker(tradingConfig.symbol).map { ticker ->
            LlmDaemonTickerSnapshot(
                lastPriceJpy = BigDecimal(ticker.last),
                sourceTimestamp = runCatching { Instant.parse(ticker.timestamp) }.getOrNull(),
            )
        }
    }
}

private fun TradingRuntime.positionsReader(): LlmDaemonPositionsReader {
    return LlmDaemonPositionsReader {
        broker.getPositions()
    }
}

private fun ExposedPaperLedgerRepository.entryFillReader(): LlmDaemonEntryFillReader {
    return LlmDaemonEntryFillReader {
        getRecentExecutions(ENTRY_FILL_LOOKBACK_LIMIT).map { executions ->
            executions
                .asSequence()
                .mapNotNull { execution -> execution.toLlmDaemonEntryFillOrNull() }
                .maxByOrNull { entryFill -> entryFill.executedAt }
        }
    }
}

private fun oneShotRequestFromEnvironment(environment: Map<String, String>): OneShotRunnerRequest {
    val repositoryRoot = Path.of(environment[FUKUROU_REPOSITORY_ROOT_ENV] ?: ".")
        .toAbsolutePath()
        .normalize()
    val workingDirectory = Path.of(environment[FUKUROU_LLM_WORKING_DIRECTORY_ENV] ?: ".")
        .toAbsolutePath()
        .normalize()

    return OneShotRunnerRequest(
        repositoryRoot = repositoryRoot,
        workingDirectory = workingDirectory,
        mcpJarPath = environment[FUKUROU_MCP_JAR_PATH_ENV] ?: "mcp/build/libs/fukurou-mcp-all.jar",
        cliConfig = OneShotRunnerCliConfig.fromEnvironment(environment),
    )
}

private fun oneShotRequestFromRequiredEnvironment(environment: Map<String, String>): OneShotRunnerRequest? {
    val repositoryRoot = environment.requiredPath(FUKUROU_REPOSITORY_ROOT_ENV) ?: return null
    val workingDirectory = environment.requiredPath(FUKUROU_LLM_WORKING_DIRECTORY_ENV) ?: return null
    val mcpJarPath = environment.requiredString(FUKUROU_MCP_JAR_PATH_ENV) ?: return null

    return OneShotRunnerRequest(
        repositoryRoot = repositoryRoot,
        workingDirectory = workingDirectory,
        mcpJarPath = mcpJarPath,
        cliConfig = OneShotRunnerCliConfig.fromEnvironment(environment),
    )
}

private fun Map<String, String>.requiredPath(name: String): Path? {
    val rawValue = requiredString(name) ?: return null

    return Path.of(rawValue)
        .toAbsolutePath()
        .normalize()
}

private fun Map<String, String>.requiredString(name: String): String? {
    return this[name]?.trim()?.takeIf { value -> value.isNotEmpty() }
}

/**
 * LLM 起動 runtime component の構築入力。
 *
 * @param dataSource PostgreSQL data source
 * @param database Exposed database
 * @param clock scheduler と runner に渡す clock
 * @param environment runner / invoker 用 environment
 * @param tradingConfig 取引 bot 全体の typed config
 * @param runtimeConfigSnapshot 起動開始時に固定する runtime config snapshot
 * @param requestBase one-shot runner の固定 request
 */
private data class LlmLaunchRuntimeInputs(
    val dataSource: HikariDataSource,
    val database: ExposedDatabase,
    val clock: Clock,
    val environment: Map<String, String>,
    val tradingConfig: TradingBotConfig,
    val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    val requestBase: OneShotRunnerRequest,
    val latestMarketQuoteStore: LatestMarketQuoteStore = LatestMarketQuoteStore(),
)

/**
 * LLM 起動に必要な runtime component 群。
 *
 * @param tradingRuntime DB 接続済み trading runtime
 * @param marketDataSource GMO public market data source
 * @param paperLedgerRepository paper ledger 読み書き repository
 * @param launchReservationRepository 起動予約 repository
 * @param requestBase one-shot runner の固定 request
 * @param launchOneShot one-shot runner 起動境界
 * @param preFilter heartbeat 系 trigger の軽量 pre-filter
 */
private data class LlmLaunchRuntimeComponents(
    val tradingRuntime: TradingRuntime,
    val marketDataSource: GmoPublicMarketDataSource,
    val paperLedgerRepository: ExposedPaperLedgerRepository,
    val launchReservationRepository: ExposedLlmLaunchReservationRepository,
    val requestBase: OneShotRunnerRequest,
    val launchOneShot: suspend (OneShotRunnerRequest) -> Result<OneShotRunnerResult>,
    val preFilter: DefaultLlmDaemonPreFilter,
)

/**
 * ENTRY_FILL trigger が見る recent execution 件数。
 */
private const val ENTRY_FILL_LOOKBACK_LIMIT = 20

/**
 * daemon pre-filter に使う Claude Haiku model。
 */
private const val HAIKU_PRE_FILTER_MODEL = "claude-haiku-4-5-20251001"
