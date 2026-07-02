package me.matsumo.fukurou.trading.tool

import kotlinx.coroutines.CancellationException
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.time.Clock

/**
 * MCP 子プロセス呼び出しなど caller boundary の no-trade audit を行う guard。
 *
 * @param commandEventLog command_event_log repository
 * @param clock audit timestamp に使う clock
 */
class CallerNoTradeGuard(
    private val commandEventLog: CommandEventLog,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * caller 側の処理を実行し、startup/connect/auth/timeout 失敗を no-trade として記録する。
     */
    suspend fun <T> run(invocation: CallerInvocation, block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (throwable: CancellationException) {
            recordNoTradeExit(invocation, "caller_cancelled", throwable).getOrThrow()

            throw throwable
        } catch (throwable: Throwable) {
            recordNoTradeExit(invocation, "caller_failed", throwable).getOrThrow()

            Result.failure(throwable)
        }
    }

    /**
     * caller boundary failure を no-trade として audit log に残す。
     */
    suspend fun recordNoTradeExit(
        invocation: CallerInvocation,
        reason: String,
        cause: Throwable? = null,
    ): Result<Unit> {
        val payload = buildNoTradeFailurePayload(reason, cause)

        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = invocation.decisionRunContext,
                toolName = invocation.operationName,
                toolCallId = null,
                clientRequestId = invocation.clientRequestId,
                eventType = CommandEventType.NO_TRADE_EXIT,
                payload = payload,
                occurredAt = clock.instant(),
            ),
        )
    }
}

/**
 * caller boundary の 1 回分の呼び出し情報。
 *
 * @param operationName MCP process 起動や tool 呼び出しを表す論理名
 * @param clientRequestId 呼び出し元 request ID
 * @param decisionRunContext LLM 起動と prompt 系の監査コンテキスト
 */
data class CallerInvocation(
    val operationName: String,
    val clientRequestId: String?,
    val decisionRunContext: DecisionRunContext,
)
