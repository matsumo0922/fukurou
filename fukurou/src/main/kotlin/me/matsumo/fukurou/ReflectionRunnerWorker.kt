package me.matsumo.fukurou

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
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.persistence.ExposedCommandEventLog
import me.matsumo.fukurou.trading.persistence.ExposedDecisionRepository
import me.matsumo.fukurou.trading.persistence.ExposedEvaluationRepository
import me.matsumo.fukurou.trading.persistence.ExposedLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.persistence.ExposedLlmRunRepository
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.staleLlmRunRecoveryThreshold
import me.matsumo.fukurou.trading.reflection.ReflectionDataCollector
import me.matsumo.fukurou.trading.reflection.ReflectionPromptCandidateGenerator
import me.matsumo.fukurou.trading.reflection.ReflectionPromptCandidateGeneratorRuntime
import me.matsumo.fukurou.trading.reflection.ReflectionPromptCandidateLlmRuntime
import me.matsumo.fukurou.trading.reflection.ReflectionPromptCandidatePersistence
import me.matsumo.fukurou.trading.reflection.ReflectionReportBuilder
import me.matsumo.fukurou.trading.reflection.ReflectionRunner
import me.matsumo.fukurou.trading.reflection.ReflectionVaultWriter
import me.matsumo.fukurou.trading.runner.LlmInvocationAuditor
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.logging.Logger
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * Reflection runner failure log の rate limit key。
 */
private const val REFLECTION_RUNNER_FAILURE_LOG_KEY = "reflection-runner-worker-failure"

/**
 * LLM CLI working directory の環境変数名。
 */
private const val FUKUROU_LLM_WORKING_DIRECTORY_ENV = "FUKUROU_LLM_WORKING_DIRECTORY"

/**
 * ReflectionRunnerWorker 用 logger。
 */
private val REFLECTION_WORKER_LOGGER = Logger.getLogger(ReflectionRunnerWorker::class.java.name)

/**
 * Ktor backend 上で deterministic reflection runner を常駐起動する worker。
 *
 * @param runnerFactory runner 構築処理
 * @param interval loop 間隔
 * @param bootstrap runner loop 開始前に必要な DB schema 初期化
 * @param clock warning log の rate limit 判定に使う clock
 * @param warnLogger rate-limited warning logger
 * @param scope worker coroutine scope
 */
class ReflectionRunnerWorker(
    private val runnerFactory: () -> Result<ReflectionRunner>,
    private val interval: Duration,
    private val bootstrap: () -> Result<Unit> = { Result.success(Unit) },
    clock: Clock = Clock.systemUTC(),
    private val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = REFLECTION_WORKER_LOGGER,
        clock = clock,
    ),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    private var job: Job? = null

    /**
     * worker loop を開始する。
     */
    fun start(): ReflectionRunnerWorker {
        require(job == null) { "ReflectionRunnerWorker is already started." }

        job = scope.launch {
            awaitBootstrap()

            while (currentCoroutineContext().isActive) {
                val loopResult = runnerFactory().mapCatching { runner ->
                    runner.runOnce().getOrThrow()
                }

                if (loopResult.isFailure) {
                    warnLoopFailure(requireNotNull(loopResult.exceptionOrNull()))
                }

                delay(interval.toMillis().toDuration(DurationUnit.MILLISECONDS))
            }
        }

        return this
    }

    private suspend fun awaitBootstrap() {
        while (currentCoroutineContext().isActive) {
            val bootstrapResult = bootstrap()

            if (bootstrapResult.isSuccess) {
                return
            }

            warnLoopFailure(requireNotNull(bootstrapResult.exceptionOrNull()))
            delay(interval.toMillis().toDuration(DurationUnit.MILLISECONDS))
        }
    }

    private fun warnLoopFailure(throwable: Throwable) {
        if (throwable is CancellationException) {
            throw throwable
        }

        warnLogger.warn(
            key = REFLECTION_RUNNER_FAILURE_LOG_KEY,
            message = "ReflectionRunnerWorker loop failed.",
            throwable = throwable,
        )
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }
}

/**
 * DB runtime から ReflectionRunnerWorker を構築して起動する。
 */
internal fun startReflectionRunnerWorker(
    database: ExposedDatabase,
    environment: Map<String, String> = System.getenv(),
    tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(environment),
    clock: Clock = Clock.systemUTC(),
    bootstrap: (() -> Result<Unit>)? = null,
): ReflectionRunnerWorker? {
    if (!tradingConfig.obsidian.enabled) {
        return null
    }

    return ReflectionRunnerWorker(
        runnerFactory = {
            runCatching {
                createReflectionRunner(
                    database = database,
                    environment = environment,
                    tradingConfig = tradingConfig,
                    clock = clock,
                )
            }
        },
        interval = maxDuration(
            tradingConfig.obsidian.writeInterval,
            tradingConfig.reflection.minInterval,
        ),
        bootstrap = bootstrap ?: {
            TradingPersistenceBootstrap(
                database = database,
                clock = clock,
                paperAccountConfig = tradingConfig.paperAccount,
                staleLlmRunRecoveryThreshold = tradingConfig.staleLlmRunRecoveryThreshold(),
            ).ensureSchema()
        },
        clock = clock,
    ).start()
}

private fun createReflectionRunner(
    database: ExposedDatabase,
    environment: Map<String, String>,
    tradingConfig: TradingBotConfig,
    clock: Clock,
): ReflectionRunner {
    val vaultPath = Path.of(tradingConfig.obsidian.vaultPath)
        .toAbsolutePath()
        .normalize()
    val workingDirectory = Path.of(environment[FUKUROU_LLM_WORKING_DIRECTORY_ENV] ?: ".")
        .toAbsolutePath()
        .normalize()
    val redactor = SecretRedactor.fromEnvironment(environment)
    val commandEventLog = ExposedCommandEventLog(database)
    val llmRunRepository = ExposedLlmRunRepository(database)

    return ReflectionRunner(
        dataCollector = ReflectionDataCollector(
            decisionRepository = ExposedDecisionRepository(database, clock),
            llmRunRepository = llmRunRepository,
            evaluationRepository = ExposedEvaluationRepository(database),
            clock = clock,
            queryLimit = tradingConfig.reflection.queryLimit,
            calibrationLookbackDays = tradingConfig.reflection.calibrationLookbackDays,
        ),
        reportBuilder = ReflectionReportBuilder(
            tradingConfig = tradingConfig,
            sampleWarningTradeCount = tradingConfig.reflection.sampleWarningTradeCount,
            recentDecisionLimit = tradingConfig.reflection.recentDecisionLimit,
        ),
        vaultWriter = ReflectionVaultWriter(
            vaultPath = vaultPath,
            redactor = redactor,
        ),
        promptCandidateGenerator = ReflectionPromptCandidateGenerator(
            tradingConfig = tradingConfig,
            runtime = ReflectionPromptCandidateGeneratorRuntime(
                llm = ReflectionPromptCandidateLlmRuntime(
                    llmInvoker = ShellLlmInvoker(
                        commandRenderer = DefaultLlmCommandRenderer(
                            config = LlmCommandRendererConfig.fromEnvironment(environment, tradingConfig.llmModels),
                        ),
                        processRunner = ShellProcessRunner(),
                    ),
                    auditor = LlmInvocationAuditor(
                        commandEventLog = commandEventLog,
                        redactor = redactor,
                        clock = clock,
                        humanLogger = { message -> REFLECTION_WORKER_LOGGER.info(message) },
                    ),
                    workingDirectory = workingDirectory,
                    parentEnvironment = environment,
                    redactor = redactor,
                ),
                persistence = ReflectionPromptCandidatePersistence(
                    llmRunRepository = llmRunRepository,
                    launchReservationRepository = ExposedLlmLaunchReservationRepository(database),
                ),
                clock = clock,
                logger = { message -> REFLECTION_WORKER_LOGGER.warning(message) },
            ),
        ),
    )
}

private fun maxDuration(first: Duration, second: Duration): Duration {
    return if (first >= second) first else second
}
