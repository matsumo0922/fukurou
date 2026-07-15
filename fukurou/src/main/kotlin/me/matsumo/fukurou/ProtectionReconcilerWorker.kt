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
import me.matsumo.fukurou.trading.domain.PaperOrderLifecyclePolicy
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecorder
import me.matsumo.fukurou.trading.evaluation.KillCriterionEvaluator
import me.matsumo.fukurou.trading.exchange.gmo.CommandEventLogGmoPublicRequestAuditSink
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientType
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicWebSocketMarketEventStream
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.market.PaperMarketEventReceiptRepository
import me.matsumo.fukurou.trading.market.UnavailablePaperMarketEventReceiptRepository
import me.matsumo.fukurou.trading.persistence.ExposedCommandEventLog
import me.matsumo.fukurou.trading.persistence.ExposedEquitySnapshotRepository
import me.matsumo.fukurou.trading.persistence.ExposedEvaluationRepository
import me.matsumo.fukurou.trading.persistence.ExposedMarketDataIntegrityRepository
import me.matsumo.fukurou.trading.persistence.ExposedPaperLedgerRepository
import me.matsumo.fukurou.trading.persistence.ExposedPaperMarketEventReceiptRepository
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateCommandService
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateRepository
import me.matsumo.fukurou.trading.persistence.ExposedSafetyViolationRepository
import me.matsumo.fukurou.trading.persistence.PostgresGlobalTradingLock
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.staleLlmRunRecoveryThreshold
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ProtectionReconciler
import me.matsumo.fukurou.trading.reconciler.RestPollingTickStream
import me.matsumo.fukurou.trading.safety.SafetyFloor
import java.time.Clock
import java.time.Duration
import java.util.logging.Logger
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * ProtectionReconciler loop の既定実行間隔。
 */
private val DEFAULT_RECONCILER_INTERVAL = PaperOrderLifecyclePolicy.reconcilerInterval

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
    private val bootstrap: suspend () -> Result<Unit> = { Result.success(Unit) },
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
 * DB runtime から ProtectionReconcilerWorker を構築して起動する。
 */
internal fun startProtectionReconcilerWorker(
    dataSource: HikariDataSource,
    database: ExposedDatabase,
    tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(),
    status: MutableReconcilerStatus,
    clock: Clock = Clock.systemUTC(),
    onStaleLlmRunsRecovered: (Int) -> Unit = {},
    latestMarketQuoteStore: LatestMarketQuoteStore = LatestMarketQuoteStore(),
): ProtectionReconcilerWorker {
    val inputs = ProtectionReconcilerWorkerInputs(
        dataSource = dataSource,
        database = database,
        status = status,
        clock = clock,
        tradingConfig = tradingConfig,
        latestMarketQuoteStore = latestMarketQuoteStore,
    )
    val runtimeComponents = inputs.createRuntimeComponents()
    val reconciler = runtimeComponents.createReconciler()

    return ProtectionReconcilerWorker(
        reconciler = reconciler,
        bootstrap = {
            val schemaResult = TradingPersistenceBootstrap(
                database = database,
                clock = clock,
                paperAccountConfig = tradingConfig.paperAccount,
                staleLlmRunRecoveryThreshold = tradingConfig.staleLlmRunRecoveryThreshold(),
                onStaleLlmRunsRecovered = onStaleLlmRunsRecovered,
            ).ensureSchema()
            if (schemaResult.isFailure) {
                schemaResult
            } else {
                runtimeComponents.repositories.marketDataIntegrityRepository
                    .recoverStaleSession(clock.instant())
            }
        },
        clock = clock,
    ).start()
}

private fun ProtectionReconcilerWorkerInputs.createRuntimeComponents(): ProtectionReconcilerRuntimeComponents {
    val repositories = createRepositories()
    val marketDataSource = GmoPublicMarketDataSource.fromConfig(
        config = tradingConfig.gmoPublicClient,
        clock = clock,
        clientType = GmoPublicClientType.KTOR_RECONCILER,
        requestAuditSink = CommandEventLogGmoPublicRequestAuditSink(repositories.commandEventLog),
    )
    val broker = createBroker(
        repositories = repositories,
        marketDataSource = marketDataSource,
    )

    return ProtectionReconcilerRuntimeComponents(
        inputs = this,
        repositories = repositories,
        tradingLock = PostgresGlobalTradingLock(dataSource, clock),
        marketDataSource = marketDataSource,
        broker = broker,
        marketEventStream = createGmoMarketEventStream(
            tradingConfig = tradingConfig,
            clock = clock,
            receiptRepository = repositories.marketEventReceiptRepository,
        ),
    )
}

/** runtime config を WebSocket transport へ投影する。 */
internal fun createGmoMarketEventStream(
    tradingConfig: TradingBotConfig,
    clock: Clock,
    receiptRepository: PaperMarketEventReceiptRepository = UnavailablePaperMarketEventReceiptRepository,
): GmoPublicWebSocketMarketEventStream {
    return GmoPublicWebSocketMarketEventStream(
        config = tradingConfig.gmoPublicWebSocket,
        symbol = tradingConfig.symbol,
        clock = clock,
        receiptRepository = receiptRepository,
    )
}

private fun ProtectionReconcilerWorkerInputs.createRepositories(): ProtectionReconcilerRepositories {
    return ProtectionReconcilerRepositories(
        riskStateRepository = ExposedRiskStateRepository(database),
        commandEventLog = ExposedCommandEventLog(database),
        evaluationRepository = ExposedEvaluationRepository(database),
        riskStateCommandService = ExposedRiskStateCommandService(database, clock),
        safetyViolationRepository = ExposedSafetyViolationRepository(database),
        ledgerRepository = ExposedPaperLedgerRepository(database),
        marketDataIntegrityRepository = ExposedMarketDataIntegrityRepository(database),
        marketEventReceiptRepository = ExposedPaperMarketEventReceiptRepository(database),
    )
}

private fun ProtectionReconcilerWorkerInputs.createBroker(
    repositories: ProtectionReconcilerRepositories,
    marketDataSource: GmoPublicMarketDataSource,
): PaperBroker {
    return PaperBroker(
        ledgerRepository = repositories.ledgerRepository,
        riskStateRepository = repositories.riskStateRepository,
        riskStateCommandService = repositories.riskStateCommandService,
        safetyViolationRepository = repositories.safetyViolationRepository,
        restingEntryOrderTtl = tradingConfig.decisionProtocol.restingEntryOrderTtl,
        safetyFloor = SafetyFloor(tradingConfig.safetyFloor, clock),
        marketDataSource = marketDataSource,
        fillSimulator = FillSimulator(tradingConfig.paperExecution, clock),
        reconcilerStatusProvider = status,
        requireRealtimeIntegrityForRestingOrders = true,
        clock = clock,
    )
}

private fun ProtectionReconcilerRuntimeComponents.createReconciler(): ProtectionReconciler {
    return ProtectionReconciler(
        riskStateRepository = repositories.riskStateRepository,
        riskStateCommandService = repositories.riskStateCommandService,
        commandEventLog = repositories.commandEventLog,
        tradingLock = tradingLock,
        tickStream = RestPollingTickStream(
            marketDataSource = marketDataSource,
            latestMarketQuoteStore = inputs.latestMarketQuoteStore,
            clock = inputs.clock,
        ),
        marketEventStream = marketEventStream,
        marketDataIntegrityRepository = repositories.marketDataIntegrityRepository,
        broker = broker,
        killCriterionEvaluator = KillCriterionEvaluator(
            config = inputs.tradingConfig.killCriterion,
            riskStateRepository = repositories.riskStateRepository,
            riskStateCommandService = repositories.riskStateCommandService,
            commandEventLog = repositories.commandEventLog,
            broker = broker,
            statsSource = { repositories.evaluationRepository.fetchKillCriterionStats() },
            clock = inputs.clock,
        ),
        equitySnapshotRecorder = EquitySnapshotRecorder(
            accountSource = { repositories.ledgerRepository.getAccountSnapshot() },
            repository = ExposedEquitySnapshotRepository(inputs.database),
            clock = inputs.clock,
        ),
        status = inputs.status,
        clock = inputs.clock,
    )
}

/**
 * ProtectionReconcilerWorker を構築する入力。
 *
 * @param dataSource PostgreSQL data source
 * @param database Exposed database
 * @param status reconciler status holder
 * @param clock worker と repository に渡す clock
 * @param tradingConfig 取引 bot 全体の typed config
 * @param latestMarketQuoteStore Activity API と共有する最新気配値 store
 */
private data class ProtectionReconcilerWorkerInputs(
    val dataSource: HikariDataSource,
    val database: ExposedDatabase,
    val status: MutableReconcilerStatus,
    val clock: Clock,
    val tradingConfig: TradingBotConfig,
    val latestMarketQuoteStore: LatestMarketQuoteStore,
)

/**
 * ProtectionReconciler が参照する repository 群。
 *
 * @param riskStateRepository risk_state repository
 * @param commandEventLog command_event_log repository
 * @param evaluationRepository evaluation repository
 * @param riskStateCommandService risk_state command service
 * @param safetyViolationRepository safety violation repository
 * @param ledgerRepository paper ledger repository
 * @param marketEventReceiptRepository durable market-event receipt repository
 */
private data class ProtectionReconcilerRepositories(
    val riskStateRepository: ExposedRiskStateRepository,
    val commandEventLog: ExposedCommandEventLog,
    val evaluationRepository: ExposedEvaluationRepository,
    val riskStateCommandService: ExposedRiskStateCommandService,
    val safetyViolationRepository: ExposedSafetyViolationRepository,
    val ledgerRepository: ExposedPaperLedgerRepository,
    val marketDataIntegrityRepository: ExposedMarketDataIntegrityRepository,
    val marketEventReceiptRepository: ExposedPaperMarketEventReceiptRepository,
)

/**
 * ProtectionReconciler の組み立て済み runtime component。
 *
 * @param inputs worker 構築入力
 * @param repositories repository 群
 * @param tradingLock PostgreSQL backed trading lock
 * @param marketDataSource GMO public market data source
 * @param broker paper broker
 */
private data class ProtectionReconcilerRuntimeComponents(
    val inputs: ProtectionReconcilerWorkerInputs,
    val repositories: ProtectionReconcilerRepositories,
    val tradingLock: PostgresGlobalTradingLock,
    val marketDataSource: GmoPublicMarketDataSource,
    val broker: PaperBroker,
    val marketEventStream: GmoPublicWebSocketMarketEventStream,
)
