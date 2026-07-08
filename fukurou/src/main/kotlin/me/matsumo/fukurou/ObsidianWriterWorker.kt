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
import me.matsumo.fukurou.trading.knowledge.ObsidianVaultWriter
import me.matsumo.fukurou.trading.knowledge.ObsidianWriter
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.persistence.ExposedDecisionRepository
import me.matsumo.fukurou.trading.persistence.ExposedLlmRunRepository
import me.matsumo.fukurou.trading.persistence.ExposedPaperLedgerRepository
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.logging.Logger
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * Obsidian writer failure log の rate limit key。
 */
private const val OBSIDIAN_WRITER_FAILURE_LOG_KEY = "obsidian-writer-worker-failure"

/**
 * ObsidianWriterWorker 用 logger。
 */
private val OBSIDIAN_WORKER_LOGGER = Logger.getLogger(ObsidianWriterWorker::class.java.name)

/**
 * Ktor backend 上で Obsidian vault writer を常駐起動する worker。
 *
 * @param writerFactory writer 構築処理
 * @param interval loop 間隔
 * @param bootstrap writer loop 開始前に必要な DB schema 初期化
 * @param clock warning log の rate limit 判定に使う clock
 * @param warnLogger rate-limited warning logger
 * @param scope worker coroutine scope
 */
class ObsidianWriterWorker(
    private val writerFactory: () -> Result<ObsidianWriter>,
    private val interval: Duration,
    private val bootstrap: () -> Result<Unit> = { Result.success(Unit) },
    clock: Clock = Clock.systemUTC(),
    private val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = OBSIDIAN_WORKER_LOGGER,
        clock = clock,
    ),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    private var job: Job? = null

    /**
     * worker loop を開始する。
     */
    fun start(): ObsidianWriterWorker {
        require(job == null) { "ObsidianWriterWorker is already started." }

        job = scope.launch {
            awaitBootstrap()

            while (currentCoroutineContext().isActive) {
                val loopResult = writerFactory().mapCatching { writer ->
                    writer.writeOnce().getOrThrow()
                }

                if (loopResult.isFailure) {
                    warnLoopFailure(requireNotNull(loopResult.exceptionOrNull()))
                }

                delay(interval.toMillis())
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
            delay(interval.toMillis())
        }
    }

    private fun warnLoopFailure(throwable: Throwable) {
        if (throwable is CancellationException) {
            throw throwable
        }

        warnLogger.warn(
            key = OBSIDIAN_WRITER_FAILURE_LOG_KEY,
            message = "ObsidianWriterWorker write loop failed.",
            throwable = throwable,
        )
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }
}

/**
 * DB runtime から ObsidianWriterWorker を構築して起動する。
 */
internal fun startObsidianWriterWorker(
    database: ExposedDatabase,
    environment: Map<String, String> = System.getenv(),
    tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(environment),
    clock: Clock = Clock.systemUTC(),
    bootstrap: (() -> Result<Unit>)? = null,
): ObsidianWriterWorker? {
    if (!tradingConfig.obsidian.enabled) {
        return null
    }

    return ObsidianWriterWorker(
        writerFactory = {
            runCatching {
                createObsidianVaultWriter(
                    database = database,
                    environment = environment,
                    tradingConfig = tradingConfig,
                    clock = clock,
                )
            }
        },
        interval = tradingConfig.obsidian.writeInterval,
        bootstrap = bootstrap ?: {
            TradingPersistenceBootstrap(
                database = database,
                clock = clock,
                paperAccountConfig = tradingConfig.paperAccount,
            ).ensureSchema()
        },
        clock = clock,
    ).start()
}

private fun createObsidianVaultWriter(
    database: ExposedDatabase,
    environment: Map<String, String>,
    tradingConfig: TradingBotConfig,
    clock: Clock,
): ObsidianVaultWriter {
    val vaultPath = Path.of(tradingConfig.obsidian.vaultPath)
        .toAbsolutePath()
        .normalize()

    return ObsidianVaultWriter(
        vaultPath = vaultPath,
        decisionRepository = ExposedDecisionRepository(database, clock),
        llmRunRepository = ExposedLlmRunRepository(database),
        paperLedgerRepository = ExposedPaperLedgerRepository(database),
        tradingConfig = tradingConfig,
        redactor = SecretRedactor.fromEnvironment(environment),
        clock = clock,
    )
}
