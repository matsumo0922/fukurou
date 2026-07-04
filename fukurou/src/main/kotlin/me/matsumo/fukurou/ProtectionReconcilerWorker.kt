package me.matsumo.fukurou

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.matsumo.fukurou.trading.broker.FillSimulator
import me.matsumo.fukurou.trading.broker.PaperBroker
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.evaluation.KillCriterionEvaluator
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.persistence.ExposedCommandEventLog
import me.matsumo.fukurou.trading.persistence.ExposedEvaluationRepository
import me.matsumo.fukurou.trading.persistence.ExposedPaperLedgerRepository
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateCommandService
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateRepository
import me.matsumo.fukurou.trading.persistence.ExposedSafetyViolationRepository
import me.matsumo.fukurou.trading.persistence.PostgresGlobalTradingLock
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ProtectionReconciler
import me.matsumo.fukurou.trading.reconciler.RestPollingTickStream
import me.matsumo.fukurou.trading.safety.SafetyFloor
import java.time.Clock
import java.time.Duration
import java.util.logging.Logger
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * ProtectionReconciler loop の既定実行間隔。
 */
private val DEFAULT_RECONCILER_INTERVAL = Duration.ofSeconds(5)

/**
 * bootstrap failure log の rate limit key。
 */
private const val BOOTSTRAP_FAILURE_LOG_KEY = "protection-reconciler-worker-bootstrap-failure"

/**
 * ProtectionReconcilerWorker 用 logger。
 */
private val WORKER_LOGGER = Logger.getLogger(ProtectionReconcilerWorker::class.java.name)

/**
 * Ktor backend 上で ProtectionReconciler を常駐起動する worker。
 *
 * @param reconciler 実行する ProtectionReconciler
 * @param interval loop 間隔
 * @param bootstrap reconciler loop 開始前に必要な DB schema 初期化
 * @param clock warning log の rate limit 判定に使う clock
 * @param warnLogger rate-limited warning logger
 * @param scope worker coroutine scope
 */
class ProtectionReconcilerWorker(
    private val reconciler: ProtectionReconciler,
    private val interval: Duration = DEFAULT_RECONCILER_INTERVAL,
    private val bootstrap: () -> Result<Unit> = { Result.success(Unit) },
    clock: Clock = Clock.systemUTC(),
    private val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = WORKER_LOGGER,
        clock = clock,
    ),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private var job: Job? = null

    /**
     * worker loop を開始する。
     */
    fun start(): ProtectionReconcilerWorker {
        require(job == null) { "ProtectionReconcilerWorker is already started." }

        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                val bootstrapResult = bootstrap()

                if (bootstrapResult.isSuccess) {
                    reconciler.runLoop(interval)
                    return@launch
                }

                bootstrapResult.exceptionOrNull()?.let { throwable ->
                    warnLogger.warn(
                        key = BOOTSTRAP_FAILURE_LOG_KEY,
                        message = "ProtectionReconcilerWorker bootstrap failed.",
                        throwable = throwable,
                    )
                }

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
 * DB runtime から ProtectionReconcilerWorker を構築して起動する。
 */
internal fun startProtectionReconcilerWorker(
    dataSource: HikariDataSource,
    database: ExposedDatabase,
    status: MutableReconcilerStatus,
    clock: Clock = Clock.systemUTC(),
): ProtectionReconcilerWorker {
    val tradingConfig = TradingBotConfig.fromEnvironment()
    val riskStateRepository = ExposedRiskStateRepository(database)
    val commandEventLog = ExposedCommandEventLog(database)
    val evaluationRepository = ExposedEvaluationRepository(database)
    val riskStateCommandService = ExposedRiskStateCommandService(database, clock)
    val safetyViolationRepository = ExposedSafetyViolationRepository(database)
    val tradingLock = PostgresGlobalTradingLock(dataSource, clock)
    val marketDataSource = GmoPublicMarketDataSource.fromConfig(
        config = tradingConfig.gmoPublicClient,
        clock = clock,
    )
    val broker = PaperBroker(
        ledgerRepository = ExposedPaperLedgerRepository(database),
        riskStateRepository = riskStateRepository,
        riskStateCommandService = riskStateCommandService,
        safetyViolationRepository = safetyViolationRepository,
        safetyFloor = SafetyFloor(tradingConfig.safetyFloor, clock),
        marketDataSource = marketDataSource,
        fillSimulator = FillSimulator(tradingConfig.paperExecution, clock),
        reconcilerStatusProvider = status,
        clock = clock,
    )
    val reconciler = ProtectionReconciler(
        riskStateRepository = riskStateRepository,
        riskStateCommandService = riskStateCommandService,
        commandEventLog = commandEventLog,
        tradingLock = tradingLock,
        tickStream = RestPollingTickStream(
            marketDataSource = marketDataSource,
            clock = clock,
        ),
        broker = broker,
        killCriterionEvaluator = KillCriterionEvaluator(
            config = tradingConfig.killCriterion,
            riskStateRepository = riskStateRepository,
            riskStateCommandService = riskStateCommandService,
            commandEventLog = commandEventLog,
            broker = broker,
            statsSource = { evaluationRepository.fetchKillCriterionStats() },
            clock = clock,
        ),
        status = status,
        clock = clock,
    )

    return ProtectionReconcilerWorker(
        reconciler = reconciler,
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
