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
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonOpenRiskReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonScheduler
import me.matsumo.fukurou.trading.daemon.asDaemonLauncher
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.persistence.ExposedLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_JAR_PATH_ENV
import me.matsumo.fukurou.trading.runner.OneShotLlmRunner
import me.matsumo.fukurou.trading.runner.OneShotRunnerCliConfig
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.logging.Logger
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

                delay(interval.toMillis())
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
    clock: Clock = Clock.systemUTC(),
): LlmDaemonSchedulerWorker? {
    val environment = System.getenv()
    val tradingConfig = TradingBotConfig.fromEnvironment(environment)

    if (!tradingConfig.daemon.enabled) {
        return null
    }

    return LlmDaemonSchedulerWorker(
        schedulerFactory = {
            runCatching {
                createLlmDaemonScheduler(
                    dataSource = dataSource,
                    database = database,
                    environment = environment,
                    tradingConfig = tradingConfig,
                    clock = clock,
                )
            }
        },
        interval = tradingConfig.daemon.pollInterval,
        bootstrap = {
            TradingPersistenceBootstrap(
                database = database,
                clock = clock,
                paperAccountConfig = tradingConfig.paperAccount,
            ).ensureSchema()
        },
        clock = clock,
    ).start()
}

private fun createLlmDaemonScheduler(
    dataSource: HikariDataSource,
    database: ExposedDatabase,
    environment: Map<String, String>,
    tradingConfig: TradingBotConfig,
    clock: Clock,
): LlmDaemonScheduler {
    val marketDataSource = GmoPublicMarketDataSource.fromConfig(
        config = tradingConfig.gmoPublicClient,
        clock = clock,
    )
    val tradingRuntime = TradingRuntimeFactory.connectedPostgres(
        dataSource = dataSource,
        database = database,
        clock = clock,
        marketDataSource = marketDataSource,
        tradingConfig = tradingConfig,
    )
    val runner = OneShotLlmRunner(
        tradingRuntime = tradingRuntime,
        tradingConfig = tradingConfig,
        llmInvoker = ShellLlmInvoker(
            commandRenderer = DefaultLlmCommandRenderer(
                config = LlmCommandRendererConfig.fromEnvironment(environment),
            ),
            processRunner = ShellProcessRunner(),
        ),
        parentEnvironment = environment,
        clock = clock,
    )

    return LlmDaemonScheduler(
        tradingConfig = tradingConfig,
        riskStateRepository = tradingRuntime.riskStateRepository,
        commandEventLog = tradingRuntime.commandEventLog,
        launchReservationRepository = ExposedLlmLaunchReservationRepository(database),
        openRiskReader = tradingRuntime.openRiskReader(),
        requestBase = oneShotRequestFromEnvironment(environment),
        launchOneShot = runner.asDaemonLauncher(),
        clock = clock,
    )
}

private fun me.matsumo.fukurou.trading.runtime.TradingRuntime.openRiskReader(): LlmDaemonOpenRiskReader {
    return LlmDaemonOpenRiskReader {
        runCatching { hasOpenRisk() }
    }
}

private suspend fun me.matsumo.fukurou.trading.runtime.TradingRuntime.hasOpenRisk(): Boolean {
    val positions = broker.getPositions().getOrThrow()
    val openOrders = broker.getOpenOrders().getOrThrow()

    return positions.isNotEmpty() || openOrders.isNotEmpty()
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
