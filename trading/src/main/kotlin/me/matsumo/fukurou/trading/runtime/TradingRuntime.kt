package me.matsumo.fukurou.trading.runtime

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.persistence.ExposedCommandEventLog
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateCommandService
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateRepository
import me.matsumo.fukurou.trading.persistence.PostgresGlobalTradingLock
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.tool.CallerNoTradeGuard
import me.matsumo.fukurou.trading.tool.ToolCallGuard
import java.time.Clock
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
 * @param tradingLock global trading lock
 * @param toolCallGuard MCP tool call guard
 * @param callerNoTradeGuard MCP caller boundary guard
 * @param close runtime resource cleanup
 */
data class TradingRuntime(
    val riskStateRepository: RiskStateRepository,
    val riskStateCommandService: RiskStateCommandService,
    val commandEventLog: CommandEventLog,
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

            if (url.isNullOrBlank() || user.isNullOrBlank() || password.isNullOrBlank()) {
                return null
            }

            return TradingDatabaseConfig(
                url = url,
                user = user,
                password = password,
            )
        }
    }
}

/**
 * trading runtime repository を構築する factory。
 */
object TradingRuntimeFactory {

    /**
     * 環境変数から DB-backed runtime を構築する。DB 設定が欠けている場合は fail closed する。
     */
    fun fromEnvironment(
        environment: Map<String, String> = System.getenv(),
        clock: Clock = Clock.systemUTC(),
    ): TradingRuntime {
        val databaseConfig = requireNotNull(TradingDatabaseConfig.fromEnvironment(environment)) {
            "DB_URL, DB_USER, and DB_PASSWORD are required for trading runtime."
        }

        return postgres(databaseConfig, clock)
    }

    /**
     * in-memory runtime を構築する。
     */
    fun inMemory(clock: Clock = Clock.systemUTC()): TradingRuntime {
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val commandEventLog = InMemoryCommandEventLog()
        val riskStateCommandService = InMemoryRiskStateCommandService(
            riskStateRepository = riskStateRepository,
            commandEventLog = commandEventLog,
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
            tradingLock = tradingLock,
            toolCallGuard = toolCallGuard,
            callerNoTradeGuard = callerNoTradeGuard,
            close = {},
        )
    }

    /**
     * Postgres/Exposed runtime を構築し、backend bootstrap 済み schema を検証する。
     */
    fun postgres(config: TradingDatabaseConfig, clock: Clock = Clock.systemUTC()): TradingRuntime {
        val dataSource = createDataSource(config)

        try {
            val database = ExposedDatabase.connect(dataSource)
            TradingPersistenceBootstrap(database, clock).verifySchema().getOrThrow()

            val riskStateRepository = ExposedRiskStateRepository(database)
            val commandEventLog = ExposedCommandEventLog(database)
            val riskStateCommandService = ExposedRiskStateCommandService(database, clock)
            val tradingLock = PostgresGlobalTradingLock(dataSource, clock)
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
                tradingLock = tradingLock,
                toolCallGuard = toolCallGuard,
                callerNoTradeGuard = callerNoTradeGuard,
                close = { dataSource.close() },
            )
        } catch (throwable: Throwable) {
            runCatching {
                dataSource.close()
            }.exceptionOrNull()?.let { closeThrowable -> throwable.addSuppressed(closeThrowable) }

            throw throwable
        }
    }
}

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
