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
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ProtectionReconciler の lock owner 名。
 */
private const val RECONCILER_LOCK_OWNER = "protection-reconciler"

/**
 * startup full reconcile pass の payload。
 */
private const val STARTUP_FULL_COMPLETED_PAYLOAD = """{"pass":"startup_full","state":"completed"}"""

/**
 * loop reconcile pass が回復したことを示す payload。
 */
private const val LOOP_RECOVERED_PAYLOAD = """{"pass":"loop","state":"recovered"}"""

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
 * @param commandEventLog command_event_log repository
 * @param tradingLock trade 系 tool と共有する global lock
 * @param tickStream 市場データ tick stream 抽象
 * @param status Reconciler の状態 holder
 * @param clock pass timestamp に使う clock
 */
class ProtectionReconciler(
    private val riskStateRepository: RiskStateRepository,
    private val commandEventLog: CommandEventLog,
    private val tradingLock: TradingLock,
    private val tickStream: TickStream = EmptyTickStream,
    private val status: MutableReconcilerStatus = MutableReconcilerStatus(),
    private val clock: Clock = Clock.systemUTC(),
) {

    private var previousPassFailed = false

    /**
     * 起動時 full pass を実行した後、cancel されるまで loop pass を続ける。
     */
    suspend fun runLoop(interval: Duration) {
        while (currentCoroutineContext().isActive) {
            val startupResult = reconcileOnce(ReconcilePassKind.STARTUP_FULL)

            if (startupResult.isSuccess) {
                break
            }

            delay(interval.toMillis())
        }

        while (currentCoroutineContext().isActive) {
            delay(interval.toMillis())
            reconcileOnce(ReconcilePassKind.LOOP)
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
            riskStateRepository.current().getOrThrow()

            val tickSnapshot = tickStream.latestTick().getOrThrow()
            val reconciledAt = Instant.now(clock)

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

    private suspend fun markSuccessfulPass(
        passKind: ReconcilePassKind,
        tickSnapshot: TickSnapshot?,
        reconciledAt: Instant,
    ): Result<Unit> {
        val isStartupFullPass = passKind == ReconcilePassKind.STARTUP_FULL
        val auditResult = recordSuccessTransition(passKind, reconciledAt)

        if (auditResult.isFailure) {
            return auditResult
        }

        status.markReconciled(
            reconciledAt = reconciledAt,
            startupFullReconcileCompleted = isStartupFullPass,
            lastMarketDataAt = tickSnapshot?.observedAt,
        )

        return Result.success(Unit)
    }

    private suspend fun recordSuccessTransition(passKind: ReconcilePassKind, occurredAt: Instant): Result<Unit> {
        if (passKind == ReconcilePassKind.STARTUP_FULL) {
            return appendReconcilerEvent(
                eventType = CommandEventType.RECONCILER_PASS_COMPLETED,
                payload = STARTUP_FULL_COMPLETED_PAYLOAD,
                occurredAt = occurredAt,
            )
        }

        if (!previousPassFailed) {
            return Result.success(Unit)
        }

        return appendReconcilerEvent(
            eventType = CommandEventType.RECONCILER_PASS_RECOVERED,
            payload = LOOP_RECOVERED_PAYLOAD,
            occurredAt = occurredAt,
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
                decisionRunContext = DecisionRunContext.fromEnvironment(emptyMap()),
                toolName = RECONCILER_LOCK_OWNER,
                toolCallId = null,
                clientRequestId = null,
                eventType = eventType,
                payload = payload,
                occurredAt = occurredAt,
            ),
        )
    }
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
