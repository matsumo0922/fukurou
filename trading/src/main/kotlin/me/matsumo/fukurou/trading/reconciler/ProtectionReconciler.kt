package me.matsumo.fukurou.trading.reconciler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.broker.PaperExecutionDivergenceMemo
import me.matsumo.fukurou.trading.broker.toJsonObject
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecorder
import me.matsumo.fukurou.trading.evaluation.KillCriterionEvaluator
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.market.MarketDataGapReason
import me.matsumo.fukurou.trading.market.MarketDataIntegrityRepository
import me.matsumo.fukurou.trading.market.MarketEventStream
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import me.matsumo.fukurou.trading.market.UnavailableMarketDataIntegrityRepository
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.safety.SafetyFloorDefaults
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * ProtectionReconciler の lock owner 名。
 */
private const val RECONCILER_LOCK_OWNER = "protection-reconciler"

/** market event 適用時の global lock owner 名。 */
private const val MARKET_EVENT_LOCK_OWNER = "paper-market-event"

/**
 * worker 起動 audit の payload。
 */
private const val RECONCILER_STARTED_PAYLOAD = """{"worker":"protection_reconciler","state":"started"}"""

/**
 * loop reconcile pass が回復したことを示す payload。
 */
private const val LOOP_RECOVERED_PAYLOAD = """{"pass":"loop","state":"recovered"}"""

/**
 * pass failure log の rate limit key。
 */
private const val PASS_FAILURE_LOG_KEY = "protection-reconciler-pass-failure"

/**
 * market data failure log の rate limit key。
 */
private const val MARKET_DATA_FAILURE_LOG_KEY = "protection-reconciler-market-data-failure"

/**
 * start audit failure log の rate limit key。
 */
private const val START_AUDIT_FAILURE_LOG_KEY = "protection-reconciler-start-audit-failure"

/**
 * ProtectionReconciler 用 logger。
 */
private val RECONCILER_LOGGER = Logger.getLogger(ProtectionReconciler::class.java.name)

/**
 * Reconciler が HARD_HALT 掃引を実行する理由。
 */
private const val HARD_HALT_SWEEP_REASON = "ProtectionReconciler HARD_HALT sweep"

/** gap 永続化失敗時に fail-closed のまま再試行する間隔。 */
private val DEFAULT_GAP_RETRY_BACKOFF = Duration.ofSeconds(2)

/**
 * ProtectionReconciler の pass 種別。
 */
enum class ReconcilePassKind {
    /**
     * 起動直後の full reconcile pass。
     */
    STARTUP_FULL,

    /**
     * 常駐 loop の通常 pass。
     */
    LOOP,
}

/**
 * LLM 起動の合間にも保護状態を前進させる常駐 reconciler の骨格。
 *
 * @param riskStateRepository risk_state repository
 * @param riskStateCommandService risk_state 更新と audit をまとめる command service
 * @param commandEventLog command_event_log repository
 * @param tradingLock trade 系 tool と共有する global lock
 * @param tickStream 市場データ tick stream 抽象
 * @param broker tick ごとに paper ledger を前進させる broker
 * @param killCriterionEvaluator 評価成績による HARD_HALT evaluator
 * @param equitySnapshotRecorder 日次 equity snapshot recorder
 * @param status Reconciler の状態 holder
 * @param clock pass timestamp に使う clock
 * @param warnLogger rate-limited warning logger
 */
class ProtectionReconciler(
    private val riskStateRepository: RiskStateRepository,
    private val riskStateCommandService: RiskStateCommandService? = null,
    private val commandEventLog: CommandEventLog,
    private val tradingLock: TradingLock,
    private val tickStream: TickStream = EmptyTickStream,
    private val marketEventStream: MarketEventStream? = null,
    private val marketDataIntegrityRepository: MarketDataIntegrityRepository =
        UnavailableMarketDataIntegrityRepository,
    private val broker: Broker? = null,
    private val killCriterionEvaluator: KillCriterionEvaluator? = null,
    private val equitySnapshotRecorder: EquitySnapshotRecorder? = null,
    private val status: MutableReconcilerStatus = MutableReconcilerStatus(),
    private val clock: Clock = Clock.systemUTC(),
    private val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = RECONCILER_LOGGER,
        clock = clock,
    ),
) {

    private var previousPassFailed = false

    /**
     * 起動時 full pass を実行した後、cancel されるまで loop pass を続ける。
     */
    suspend fun runLoop(interval: Duration) {
        recordStarted().onFailure { throwable ->
            warnLogger.warn(
                key = START_AUDIT_FAILURE_LOG_KEY,
                message = "ProtectionReconciler start audit failed.",
                throwable = throwable,
            )
        }

        if (marketEventStream != null) {
            runMarketEventLoop(interval)
            return
        }

        while (currentCoroutineContext().isActive) {
            val startupResult = reconcileOnce(ReconcilePassKind.STARTUP_FULL)

            if (startupResult.isSuccess) {
                break
            }

            startupResult.exceptionOrNull()?.let { throwable ->
                logPassFailure(ReconcilePassKind.STARTUP_FULL, throwable)
            }

            delay(interval.toMillis().toDuration(DurationUnit.MILLISECONDS))
        }

        while (currentCoroutineContext().isActive) {
            delay(interval.toMillis().toDuration(DurationUnit.MILLISECONDS))

            val loopResult = reconcileOnce(ReconcilePassKind.LOOP)

            loopResult.exceptionOrNull()?.let { throwable ->
                logPassFailure(ReconcilePassKind.LOOP, throwable)
            }
        }
    }

    private suspend fun runMarketEventLoop(maintenanceInterval: Duration) {
        val reconnectBackoff = requireNotNull(marketEventStream).reconnectBackoff
        while (currentCoroutineContext().isActive) {
            val session = marketEventStream.connect().getOrElse { throwable ->
                logPassFailure(ReconcilePassKind.LOOP, throwable)
                delay(reconnectBackoff.toMillis().toDuration(DurationUnit.MILLISECONDS))
                continue
            }

            val beginResult = marketDataIntegrityRepository.beginSession(session.sessionId, session.connectedAt)
            if (beginResult.isFailure) {
                session.close()
                beginResult.exceptionOrNull()?.let { throwable -> logPassFailure(ReconcilePassKind.LOOP, throwable) }
                delay(reconnectBackoff.toMillis().toDuration(DurationUnit.MILLISECONDS))
                continue
            }
            refreshMarketDataStatus()

            try {
                consumeMarketEventSession(session, maintenanceInterval)
            } finally {
                session.close()
            }

            delay(reconnectBackoff.toMillis().toDuration(DurationUnit.MILLISECONDS))
        }
    }

    private suspend fun consumeMarketEventSession(
        session: me.matsumo.fukurou.trading.market.MarketEventSession,
        maintenanceInterval: Duration,
    ) {
        var nextMaintenanceAtNanos = nextMaintenanceAtNanos(maintenanceInterval)

        while (currentCoroutineContext().isActive) {
            val eventResult = kotlinx.coroutines.withTimeoutOrNull(
                remainingMaintenanceWaitMillis(nextMaintenanceAtNanos),
            ) {
                session.receive()
            }
            if (eventResult == null) {
                runPeriodicSafetyMaintenance()
                nextMaintenanceAtNanos = nextMaintenanceAtNanos(maintenanceInterval)
                continue
            }
            val event = eventResult.getOrNull()

            if (event == null) {
                recordMarketDataGap(
                    sessionId = session.sessionId,
                    throwable = eventResult.exceptionOrNull(),
                )
                refreshMarketDataStatus()
                return
            }

            val applied = applyMarketEvent(event)
            if (applied.isFailure) {
                recordMarketDataGap(
                    sessionId = session.sessionId,
                    throwable = applied.exceptionOrNull(),
                )
                refreshMarketDataStatus()
                return
            }

            if (System.nanoTime() >= nextMaintenanceAtNanos) {
                runPeriodicSafetyMaintenance()
                nextMaintenanceAtNanos = nextMaintenanceAtNanos(maintenanceInterval)
            }
        }
    }

    /** realtime event の量によらず periodic maintenance を rate limit する次回期限を返す。 */
    private fun nextMaintenanceAtNanos(maintenanceInterval: Duration): Long {
        require(!maintenanceInterval.isNegative && !maintenanceInterval.isZero) {
            "maintenanceInterval must be positive."
        }

        return System.nanoTime() + maintenanceInterval.toNanos()
    }

    /** 次の maintenance 期限まで event を待機できるミリ秒を返す。 */
    private fun remainingMaintenanceWaitMillis(nextMaintenanceAtNanos: Long): Long {
        val remainingNanos = (nextMaintenanceAtNanos - System.nanoTime()).coerceAtLeast(0)

        return ((remainingNanos + 999_999) / 1_000_000).coerceAtLeast(1)
    }

    private suspend fun recordMarketDataGap(sessionId: UUID, throwable: Throwable?) {
        val reason = throwable.toGapReason()
        val detectedAt = Instant.now(clock)

        markMarketDataUnavailable(sessionId, reason, detectedAt)
        while (currentCoroutineContext().isActive) {
            val result = runCatching {
                marketDataIntegrityRepository.markDisconnected(
                    sessionId = sessionId,
                    reason = reason,
                    detectedAt = detectedAt,
                    detail = throwable?.javaClass?.simpleName,
                ).getOrThrow()

                tradingLock.withLock(MARKET_EVENT_LOCK_OWNER) {
                    marketDataIntegrityRepository.applyGapImpact(
                        sessionId = sessionId,
                        reason = reason,
                        detectedAt = detectedAt,
                    ).getOrThrow()
                }
            }
            if (result.isSuccess) {
                refreshMarketDataStatus()
                return
            }

            result.exceptionOrNull()?.let { failure -> logPassFailure(ReconcilePassKind.LOOP, failure) }
            val retryBackoff = marketEventStream?.reconnectBackoff ?: DEFAULT_GAP_RETRY_BACKOFF
            delay(retryBackoff.toMillis().toDuration(DurationUnit.MILLISECONDS))
        }
    }

    private suspend fun applyMarketEvent(event: PaperMarketTradeEvent): Result<Unit> {
        return try {
            tradingLock.withLock(MARKET_EVENT_LOCK_OWNER) {
                broker?.applyMarketEvent(event)?.getOrThrow()
            }
            refreshMarketDataStatus()
            Result.success(Unit)
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    /** REST は約定根拠にせず、定期的な safety / evaluation 保守だけに使う。 */
    private suspend fun runPeriodicSafetyMaintenance(): Result<Unit> {
        return try {
            tradingLock.withLock(MARKET_EVENT_LOCK_OWNER) {
                runPeriodicSafetyMaintenanceLocked()
            }

            Result.success(Unit)
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            recordPeriodicMaintenanceFailure(throwable)

            Result.failure(throwable)
        }
    }

    /** periodic maintenance の失敗を loop failure と同じ監査・ログ規約で残す。 */
    private suspend fun recordPeriodicMaintenanceFailure(throwable: Throwable) {
        val auditResult = recordFailureTransition(ReconcilePassKind.LOOP, throwable)
        if (auditResult.isSuccess) {
            previousPassFailed = true
        } else {
            auditResult.exceptionOrNull()?.let(throwable::addSuppressed)
        }

        logPassFailure(ReconcilePassKind.LOOP, throwable)
    }

    private suspend fun runPeriodicSafetyMaintenanceLocked() {
        val tickSnapshot = readTickSnapshot()
        if (tickSnapshot != null) {
            broker?.maintainProtections(tickSnapshot)?.getOrThrow()
            val swept = enforceHardHaltSweepIfNeeded(tickSnapshot)
            if (!swept) {
                killCriterionEvaluator?.evaluate(tickSnapshot)?.getOrThrow()
            }
        }
        equitySnapshotRecorder?.recordDailyIfNeeded()
    }

    private fun markMarketDataUnavailable(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
    ) {
        val current = status.snapshot()
        status.updateMarketData(
            current.copy(
                marketDataState = me.matsumo.fukurou.trading.market.MarketDataConnectionState.DISCONNECTED,
                marketDataSessionId = sessionId,
                gapStartedAt = detectedAt,
                gapReason = reason,
            ),
        )
    }

    private suspend fun refreshMarketDataStatus() {
        val integrity = marketDataIntegrityRepository.snapshot().getOrThrow()
        val reconciledAt = integrity.lastReceivedAt ?: status.snapshot().lastReconciledAt
        status.updateMarketData(
            ReconcilerStatus(
                lastReconciledAt = reconciledAt,
                startupFullReconcileCompleted = integrity.startupRecoveryCompleted,
                lastMarketDataAt = integrity.lastReceivedAt,
                marketDataState = integrity.state,
                marketDataSessionId = integrity.sessionId,
                lastProcessedSequence = integrity.lastProcessedSequence,
                gapStartedAt = integrity.gapStartedAt,
                recoveredAt = integrity.recoveredAt,
                gapReason = integrity.gapReason,
                startupRecoveryCompleted = integrity.startupRecoveryCompleted,
            ),
        )
    }

    /**
     * 1 回分の reconcile pass を実行する。
     */
    suspend fun reconcileOnce(passKind: ReconcilePassKind): Result<Unit> {
        return try {
            tradingLock.withLock(RECONCILER_LOCK_OWNER) {
                reconcileWithTransitionAudit(passKind)
            }
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    /**
     * 現在の Reconciler 状態 snapshot を返す。
     */
    fun snapshot(): ReconcilerStatus {
        return status.snapshot()
    }

    private suspend fun reconcileWithTransitionAudit(passKind: ReconcilePassKind): Result<Unit> {
        val passResult = runCatching {
            val reconciledAt = Instant.now(clock)

            val tickSnapshot = readTickSnapshot()
            var divergenceMemos = emptyList<PaperExecutionDivergenceMemo>()

            if (tickSnapshot != null) {
                val sweptBeforeReconcile = enforceHardHaltSweepIfNeeded(tickSnapshot)

                if (!sweptBeforeReconcile) {
                    divergenceMemos = broker
                        ?.reconcile(tickSnapshot)
                        ?.getOrThrow()
                        ?.divergenceMemos
                        .orEmpty()
                    val sweptAfterReconcile = enforceHardHaltSweepIfNeeded(tickSnapshot)

                    if (!sweptAfterReconcile) {
                        killCriterionEvaluator?.evaluate(tickSnapshot)?.getOrThrow()
                    }
                }
            }

            equitySnapshotRecorder?.recordDailyIfNeeded()
            markSuccessfulPass(passKind, tickSnapshot, reconciledAt, divergenceMemos).getOrThrow()
        }

        if (passResult.isSuccess) {
            previousPassFailed = false

            return Result.success(Unit)
        }

        val throwable = requireNotNull(passResult.exceptionOrNull())
        val auditResult = recordFailureTransition(passKind, throwable)

        if (auditResult.isSuccess) {
            previousPassFailed = true
        } else {
            auditResult.exceptionOrNull()?.let { auditThrowable -> throwable.addSuppressed(auditThrowable) }
        }

        return Result.failure(throwable)
    }

    private suspend fun readTickSnapshot(): TickSnapshot? {
        val tickResult = runCatching {
            tickStream.latestTick().getOrThrow()
        }

        tickResult.exceptionOrNull()?.let { throwable ->
            warnLogger.warn(
                key = MARKET_DATA_FAILURE_LOG_KEY,
                message = "ProtectionReconciler market data refresh failed.",
                throwable = throwable,
            )
        }

        return tickResult.getOrNull()
    }

    private suspend fun enforceHardHaltSweepIfNeeded(tickSnapshot: TickSnapshot): Boolean {
        val currentRiskState = riskStateRepository.current().getOrThrow()
        val hardHaltReached = currentRiskState.drawdownRatio <= SafetyFloorDefaults.maxDrawdownRatio
        val hardHaltEnabled = currentRiskState.state == RiskHaltState.HARD_HALT
        val shouldSweep = hardHaltEnabled || hardHaltReached

        if (!shouldSweep) {
            return false
        }

        val reason = currentRiskState.haltReason ?: HARD_HALT_SWEEP_REASON

        if (!hardHaltEnabled) {
            if (riskStateCommandService != null) {
                riskStateCommandService.setHardHalt(reason, DecisionRunContext.EMPTY).getOrThrow()
            } else {
                riskStateRepository.setHardHalt(reason, Instant.now(clock)).getOrThrow()
            }
        }

        broker?.sweepHardHalt(reason, tickSnapshot)?.getOrThrow()

        return true
    }

    private suspend fun recordStarted(): Result<Unit> {
        return appendReconcilerEvent(
            eventType = CommandEventType.RECONCILER_STARTED,
            payload = RECONCILER_STARTED_PAYLOAD,
            occurredAt = Instant.now(clock),
        )
    }

    private suspend fun markSuccessfulPass(
        passKind: ReconcilePassKind,
        tickSnapshot: TickSnapshot?,
        reconciledAt: Instant,
        divergenceMemos: List<PaperExecutionDivergenceMemo>,
    ): Result<Unit> {
        val isStartupFullPass = passKind == ReconcilePassKind.STARTUP_FULL
        val lastMarketDataAt = tickSnapshot?.observedAt ?: status.snapshot().lastMarketDataAt
        val auditResult = recordSuccessTransition(
            passKind = passKind,
            reconciledAt = reconciledAt,
            startupFullReconcileCompleted = isStartupFullPass,
            lastMarketDataAt = lastMarketDataAt,
            divergenceMemos = divergenceMemos,
        )

        if (auditResult.isFailure) {
            return auditResult
        }

        status.markReconciled(
            reconciledAt = reconciledAt,
            startupFullReconcileCompleted = isStartupFullPass,
            lastMarketDataAt = lastMarketDataAt,
        )

        return Result.success(Unit)
    }

    private suspend fun recordSuccessTransition(
        passKind: ReconcilePassKind,
        reconciledAt: Instant,
        startupFullReconcileCompleted: Boolean,
        lastMarketDataAt: Instant?,
        divergenceMemos: List<PaperExecutionDivergenceMemo>,
    ): Result<Unit> {
        val completedResult = appendReconcilerEvent(
            eventType = CommandEventType.RECONCILER_PASS_COMPLETED,
            payload = buildPassCompletedPayload(
                passKind = passKind,
                reconciledAt = reconciledAt,
                startupFullReconcileCompleted = startupFullReconcileCompleted,
                lastMarketDataAt = lastMarketDataAt,
                divergenceMemos = divergenceMemos,
            ),
            occurredAt = reconciledAt,
        )

        if (completedResult.isFailure) {
            return completedResult
        }

        val shouldRecordRecovery = previousPassFailed && passKind != ReconcilePassKind.STARTUP_FULL
        if (!shouldRecordRecovery) {
            return Result.success(Unit)
        }

        return appendReconcilerEvent(
            eventType = CommandEventType.RECONCILER_PASS_RECOVERED,
            payload = LOOP_RECOVERED_PAYLOAD,
            occurredAt = reconciledAt,
        )
    }

    private suspend fun recordFailureTransition(passKind: ReconcilePassKind, throwable: Throwable): Result<Unit> {
        if (previousPassFailed) {
            return Result.success(Unit)
        }

        return appendReconcilerEvent(
            eventType = CommandEventType.RECONCILER_PASS_FAILED,
            payload = buildPassFailurePayload(passKind, throwable),
            occurredAt = Instant.now(clock),
        )
    }

    private suspend fun appendReconcilerEvent(
        eventType: CommandEventType,
        payload: String,
        occurredAt: Instant,
    ): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
                toolName = RECONCILER_LOCK_OWNER,
                toolCallId = null,
                clientRequestId = null,
                eventType = eventType,
                payload = payload,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun logPassFailure(passKind: ReconcilePassKind, throwable: Throwable) {
        warnLogger.warn(
            key = "$PASS_FAILURE_LOG_KEY-${passKind.payloadName()}",
            message = "ProtectionReconciler ${passKind.payloadName()} pass failed.",
            throwable = throwable,
        )
    }
}

/**
 * 完了した reconcile pass の payload を組み立てる。
 */
private fun buildPassCompletedPayload(
    passKind: ReconcilePassKind,
    reconciledAt: Instant,
    startupFullReconcileCompleted: Boolean,
    lastMarketDataAt: Instant?,
    divergenceMemos: List<PaperExecutionDivergenceMemo>,
): String {
    return buildJsonObject {
        put("pass", passKind.payloadName())
        put("state", "completed")
        put("lastReconciledAt", reconciledAt.toString())
        put("startupFullReconcileCompleted", startupFullReconcileCompleted)
        lastMarketDataAt?.let { marketDataAt ->
            put("lastMarketDataAt", marketDataAt.toString())
        }
        if (divergenceMemos.isNotEmpty()) {
            put("paperExecutionDivergenceMemos", JsonArray(divergenceMemos.map { memo -> memo.toJsonObject() }))
        }
    }.toString()
}

/**
 * 失敗した reconcile pass の payload を組み立てる。
 */
private fun buildPassFailurePayload(passKind: ReconcilePassKind, throwable: Throwable): String {
    return buildJsonObject {
        put("pass", passKind.payloadName())
        put("state", "failed")
        put("cause", throwable.javaClass.simpleName)
        put("message", throwable.message.orEmpty())
    }.toString()
}

/**
 * audit payload に使う pass 名を返す。
 */
private fun ReconcilePassKind.payloadName(): String {
    return when (this) {
        ReconcilePassKind.STARTUP_FULL -> "startup_full"
        ReconcilePassKind.LOOP -> "loop"
    }
}

private fun Throwable?.toGapReason(): MarketDataGapReason {
    val message = this?.message.orEmpty()

    return when {
        this is kotlinx.coroutines.TimeoutCancellationException -> MarketDataGapReason.MESSAGE_STALE
        message.contains("sequence gap", ignoreCase = true) -> MarketDataGapReason.SEQUENCE_GAP
        message.contains("JSON", ignoreCase = true) || message.contains("WebSocket trade", ignoreCase = true) -> {
            MarketDataGapReason.INVALID_MESSAGE
        }
        message.contains("database", ignoreCase = true) || this is java.sql.SQLException -> {
            MarketDataGapReason.DATABASE_FAILURE
        }
        else -> MarketDataGapReason.DISCONNECTED
    }
}
