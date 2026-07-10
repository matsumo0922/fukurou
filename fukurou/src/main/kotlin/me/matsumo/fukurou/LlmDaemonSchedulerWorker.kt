package me.matsumo.fukurou

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.DefaultLlmDaemonPreFilter
import me.matsumo.fukurou.trading.daemon.DefaultLlmDaemonPreFilterDependencies
import me.matsumo.fukurou.trading.daemon.DefaultManualLlmLaunchService
import me.matsumo.fukurou.trading.daemon.LlmDaemonEntryFillReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonOpenRiskReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonPositionsReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonScheduler
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerDependencies
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerObserver
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerRuntime
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickerReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickerSnapshot
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchServiceDependencies
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchServiceRuntime
import me.matsumo.fukurou.trading.daemon.asDaemonLauncher
import me.matsumo.fukurou.trading.daemon.toLlmDaemonEntryFillOrNull
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.persistence.ExposedLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.persistence.ExposedPaperLedgerRepository
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
 * daemon worker process lifecycle の通知先。
 */
internal interface LlmDaemonWorkerLifecycleListener {
    /** scheduler session が開始したことを通知する。 */
    fun onStarted() = Unit

    /** worker が失敗終了したことを通知する。 */
    fun onFailed(error: Throwable) = Unit
}

/**
 * daemon worker の graceful stop 結果。
 */
internal enum class LlmDaemonWorkerStopResult {
    /** in-flight invocation を含めて通常終了した。 */
    DRAINED,

    /** drain 上限を超えたため worker job を cancel した。 */
    TIMED_OUT,

    /** cancel 後も bounded termination wait 内に worker job が終了しなかった。 */
    TERMINATION_PENDING,
}

/**
 * supervisor が所有する worker lifecycle 境界。
 */
internal interface LlmDaemonWorkerHandle {
    /** worker loop を開始する。 */
    fun start(): LlmDaemonWorkerHandle

    /** 新規 tick を同期的に禁止する。 */
    fun requestStop()

    /** 新規 tick を止め、現在処理を bounded drain する。 */
    suspend fun stopGracefully(timeout: Duration): LlmDaemonWorkerStopResult

    /** worker が所有する coroutine scope を停止する。 */
    suspend fun shutdown()
}

/**
 * worker が駆動する scheduler loop の最小境界。
 */
internal interface LlmDaemonWorkerLoop {
    /** session start を監査して初期化する。 */
    suspend fun startSession()

    /** scheduler tick を1回実行する。 */
    suspend fun tick()
}

/**
 * Ktor backend 上で LLM daemon scheduler を常駐起動する worker。
 *
 * @param schedulerFactory scheduler 構築処理
 * @param interval loop 間隔
 * @param bootstrap scheduler loop 開始前に必要な DB schema 初期化
 * @param clock warning log の rate limit 判定に使う clock
 * @param warnLogger rate-limited warning logger
 * @param lifecycleListener worker lifecycle の通知先
 * @param scope worker coroutine scope
 */
internal class LlmDaemonSchedulerWorker(
    private val schedulerFactory: () -> Result<LlmDaemonWorkerLoop>,
    private val interval: Duration,
    private val bootstrap: () -> Result<Unit> = { Result.success(Unit) },
    clock: Clock = Clock.systemUTC(),
    private val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = DAEMON_WORKER_LOGGER,
        clock = clock,
    ),
    private val lifecycleListener: LlmDaemonWorkerLifecycleListener = object : LlmDaemonWorkerLifecycleListener {},
    private val cancellationJoinTimeout: Duration = DEFAULT_WORKER_CANCELLATION_JOIN_TIMEOUT,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : LlmDaemonWorkerHandle {

    private var job: Job? = null
    private val stopRequested = CompletableDeferred<Unit>()

    @Volatile
    private var cancellationRequested = false

    /**
     * worker loop を開始する。
     */
    override fun start(): LlmDaemonSchedulerWorker {
        require(job == null) { "LlmDaemonSchedulerWorker is already started." }

        job = scope.launch {
            try {
                bootstrap().getOrThrow()
                val scheduler = schedulerFactory().getOrThrow()

                scheduler.startSession()
                lifecycleListener.onStarted()

                while (currentCoroutineContext().isActive && !stopRequested.isCompleted) {
                    scheduler.tick()

                    if (!stopRequested.isCompleted) {
                        withTimeoutOrNull(interval.toMillis()) {
                            stopRequested.await()
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                warnLogger.warn(
                    key = DAEMON_BOOTSTRAP_FAILURE_LOG_KEY,
                    message = "LlmDaemonSchedulerWorker bootstrap or scheduler loop failed.",
                    throwable = error,
                )
                lifecycleListener.onFailed(error)
            }
        }

        return this
    }

    override fun requestStop() {
        stopRequested.complete(Unit)
    }

    /**
     * 新規 tick を止め、現在の tick を timeout まで drain する。
     */
    override suspend fun stopGracefully(timeout: Duration): LlmDaemonWorkerStopResult {
        requestStop()
        val runningJob = job ?: return LlmDaemonWorkerStopResult.DRAINED
        if (cancellationRequested) {
            return runningJob.awaitCancelledTermination()
        }
        val completed = withTimeoutOrNull(timeout.toMillis().coerceAtLeast(1L)) {
            runningJob.join()
            true
        } ?: false

        if (completed) {
            return LlmDaemonWorkerStopResult.DRAINED
        }

        cancellationRequested = true
        runningJob.cancel()

        return runningJob.awaitCancelledTermination()
    }

    override suspend fun shutdown() {
        stopGracefully(Duration.ZERO)
        scope.cancel()
    }

    private suspend fun Job.awaitCancelledTermination(): LlmDaemonWorkerStopResult {
        val completed = withTimeoutOrNull(cancellationJoinTimeout.toMillis().coerceAtLeast(1L)) {
            join()
            true
        } ?: false

        return if (completed) LlmDaemonWorkerStopResult.TIMED_OUT else LlmDaemonWorkerStopResult.TERMINATION_PENDING
    }
}

/**
 * DB runtime から未起動の LlmDaemonSchedulerWorker を構築する。
 */
internal fun createLlmDaemonSchedulerWorker(
    dataSource: HikariDataSource,
    database: ExposedDatabase,
    tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(),
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    clock: Clock = Clock.systemUTC(),
    observer: LlmDaemonSchedulerObserver = object : LlmDaemonSchedulerObserver {},
    lifecycleListener: LlmDaemonWorkerLifecycleListener = object : LlmDaemonWorkerLifecycleListener {},
): LlmDaemonSchedulerWorker {
    val environment = System.getenv()

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
                        observer = observer,
                    ),
                ).asWorkerLoop()
            }
        },
        interval = tradingConfig.daemon.pollInterval,
        clock = clock,
        lifecycleListener = lifecycleListener,
    )
}

private val DEFAULT_WORKER_CANCELLATION_JOIN_TIMEOUT: Duration = Duration.ofSeconds(5)

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
        ),
        runtime = LlmDaemonSchedulerRuntime(
            requestBase = components.requestBase,
            launchOneShot = components.launchOneShot,
            preFilter = components.preFilter,
            clock = inputs.clock,
            observer = inputs.observer,
        ),
    )
}

private fun LlmDaemonScheduler.asWorkerLoop(): LlmDaemonWorkerLoop {
    return object : LlmDaemonWorkerLoop {
        override suspend fun startSession() {
            this@asWorkerLoop.startSession()
        }

        override suspend fun tick() {
            this@asWorkerLoop.tick()
        }
    }
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
    val marketDataSource = GmoPublicMarketDataSource.fromConfig(
        config = inputs.tradingConfig.gmoPublicClient,
        clock = inputs.clock,
    )
    val commandRendererConfig = LlmCommandRendererConfig.fromEnvironment(
        environment = inputs.environment,
        runtimeModels = inputs.tradingConfig.llmModels,
    )
    val tradingRuntime = TradingRuntimeFactory.connectedPostgres(
        dataSource = inputs.dataSource,
        database = inputs.database,
        clock = inputs.clock,
        marketDataSource = marketDataSource,
        tradingConfig = inputs.tradingConfig,
    )
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
        requestBase = inputs.requestBase,
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
        runCatching { hasOpenRisk() }
    }
}

private suspend fun TradingRuntime.hasOpenRisk(): Boolean {
    val positions = broker.getPositions().getOrThrow()
    val openOrders = broker.getOpenOrders().getOrThrow()

    return positions.isNotEmpty() || openOrders.isNotEmpty()
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
 * @param observer scheduler の実行状況 observer
 */
private data class LlmLaunchRuntimeInputs(
    val dataSource: HikariDataSource,
    val database: ExposedDatabase,
    val clock: Clock,
    val environment: Map<String, String>,
    val tradingConfig: TradingBotConfig,
    val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    val requestBase: OneShotRunnerRequest,
    val observer: LlmDaemonSchedulerObserver = object : LlmDaemonSchedulerObserver {},
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
