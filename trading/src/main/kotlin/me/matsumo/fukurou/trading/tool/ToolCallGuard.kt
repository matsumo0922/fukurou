package me.matsumo.fukurou.trading.tool

import kotlinx.coroutines.CancellationException
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.risk.HardHaltTradingRejectedException
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.time.Clock
import java.time.Instant

/**
 * trade 系 tool と read 系 tool の no-trade / HARD_HALT / audit 契約をまとめる guard。
 *
 * @param riskStateRepository DB risk_state repository
 * @param commandEventLog command_event_log repository
 * @param tradingLock trade 系 tool と reconciler が共有する global lock
 * @param clock audit timestamp に使う clock
 */
class ToolCallGuard(
    private val riskStateRepository: RiskStateRepository,
    private val commandEventLog: CommandEventLog,
    private val tradingLock: TradingLock,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * read 系 tool を audit 付きで実行する。HARD_HALT 中も read は通す。
     */
    suspend fun <T> runReadOnlyTool(call: GuardedToolCall, block: suspend () -> T): Result<T> {
        return runAndAudit(call, block)
    }

    /**
     * trade 系 tool を global lock と HARD_HALT gate の内側で実行する。
     */
    suspend fun <T> runTradeTool(call: GuardedToolCall, block: suspend () -> T): Result<T> {
        return tradingLock.withLock(call.toolName) {
            val riskState = riskStateRepository.current()
                .getOrElse { throwable ->
                    recordNoTradeExit(call, "risk_state_unavailable", throwable).getOrThrow()

                    return@withLock Result.failure(throwable)
                }

            if (riskState.hardHalt) {
                val exception = HardHaltTradingRejectedException("HARD_HALT is enabled in risk_state.")
                recordHardHaltRejection(call, riskState.haltReason, exception).getOrThrow()

                return@withLock Result.failure(exception)
            }

            runAndAudit(call, block)
        }
    }

    /**
     * 失敗時に no-trade 終了を audit へ記録する。
     */
    suspend fun recordNoTradeExit(
        call: GuardedToolCall,
        reason: String,
        cause: Throwable? = null,
    ): Result<Unit> {
        val payload = buildNoTradeFailurePayload(reason, cause)

        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = call.decisionRunContext,
                toolName = call.toolName,
                toolCallId = call.toolCallId,
                clientRequestId = call.clientRequestId,
                eventType = CommandEventType.NO_TRADE_EXIT,
                payload = payload,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private suspend fun <T> runAndAudit(call: GuardedToolCall, block: suspend () -> T): Result<T> {
        return try {
            val value = block()
            recordToolCompleted(call).getOrThrow()

            Result.success(value)
        } catch (throwable: CancellationException) {
            recordNoTradeExit(call, "tool_call_cancelled", throwable).getOrThrow()

            throw throwable
        } catch (throwable: Throwable) {
            recordNoTradeExit(call, "tool_call_failed", throwable).getOrThrow()

            Result.failure(throwable)
        }
    }

    private suspend fun recordToolCompleted(call: GuardedToolCall): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = call.decisionRunContext,
                toolName = call.toolName,
                toolCallId = call.toolCallId,
                clientRequestId = call.clientRequestId,
                eventType = CommandEventType.TOOL_CALL_COMPLETED,
                payload = call.payload,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private suspend fun recordHardHaltRejection(
        call: GuardedToolCall,
        haltReason: String?,
        cause: Throwable,
    ): Result<Unit> {
        val payload = buildNoTradeFailurePayload(
            reason = haltReason ?: "hard_halt",
            cause = cause,
        )

        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = call.decisionRunContext,
                toolName = call.toolName,
                toolCallId = call.toolCallId,
                clientRequestId = call.clientRequestId,
                eventType = CommandEventType.TOOL_CALL_REJECTED_BY_HARD_HALT,
                payload = payload,
                occurredAt = Instant.now(clock),
            ),
        )
    }
}
