package me.matsumo.fukurou.trading.reconciler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger

/**
 * ProtectionReconciler の lock owner 名。
 */
private const val RECONCILER_LOCK_OWNER = "protection-reconciler"

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
 * HARD_HALT を立てる drawdown。
 */
private val HARD_HALT_DRAWDOWN_RATIO = BigDecimal("-0.15")

/**
 * Reconciler が HARD_HALT 掃引を実行する理由。
 */
private const val HARD_HALT_SWEEP_REASON = "ProtectionReconciler HARD_HALT sweep"

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
    private val broker: Broker? = null,
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

        while (currentCoroutineContext().isActive) {
            val startupResult = reconcileOnce(ReconcilePassKind.STARTUP_FULL)

            if (startupResult.isSuccess) {
                break
            }

            startupResult.exceptionOrNull()?.let { throwable ->
                logPassFailure(ReconcilePassKind.STARTUP_FULL, throwable)
            }

            delay(interval.toMillis())
        }

        while (currentCoroutineContext().isActive) {
            delay(interval.toMillis())

            val loopResult = reconcileOnce(ReconcilePassKind.LOOP)

            loopResult.exceptionOrNull()?.let { throwable ->
                logPassFailure(ReconcilePassKind.LOOP, throwable)
            }
        }
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

            if (tickSnapshot != null) {
                val sweptBeforeReconcile = enforceHardHaltSweepIfNeeded(tickSnapshot)

                if (!sweptBeforeReconcile) {
                    broker?.reconcile(tickSnapshot)?.getOrThrow()
                    enforceHardHaltSweepIfNeeded(tickSnapshot)
                }
            }

            markSuccessfulPass(passKind, tickSnapshot, reconciledAt).getOrThrow()
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
        val hardHaltReached = currentRiskState.drawdownRatio <= HARD_HALT_DRAWDOWN_RATIO
        val shouldSweep = currentRiskState.hardHalt || hardHaltReached

        if (!shouldSweep) {
            return false
        }

        val reason = currentRiskState.haltReason ?: HARD_HALT_SWEEP_REASON

        if (!currentRiskState.hardHalt) {
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
    ): Result<Unit> {
        val isStartupFullPass = passKind == ReconcilePassKind.STARTUP_FULL
        val lastMarketDataAt = tickSnapshot?.observedAt ?: status.snapshot().lastMarketDataAt
        val auditResult = recordSuccessTransition(
            passKind = passKind,
            reconciledAt = reconciledAt,
            startupFullReconcileCompleted = isStartupFullPass,
            lastMarketDataAt = lastMarketDataAt,
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
    ): Result<Unit> {
        val completedResult = appendReconcilerEvent(
            eventType = CommandEventType.RECONCILER_PASS_COMPLETED,
            payload = buildPassCompletedPayload(
                passKind = passKind,
                reconciledAt = reconciledAt,
                startupFullReconcileCompleted = startupFullReconcileCompleted,
                lastMarketDataAt = lastMarketDataAt,
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
): String {
    return buildJsonObject {
        put("pass", passKind.payloadName())
        put("state", "completed")
        put("lastReconciledAt", reconciledAt.toString())
        put("startupFullReconcileCompleted", startupFullReconcileCompleted)
        lastMarketDataAt?.let { marketDataAt ->
            put("lastMarketDataAt", marketDataAt.toString())
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
