package me.matsumo.fukurou.trading.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.risk.HardHaltTradingRejectedException
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.sql.SQLTimeoutException
import java.time.Clock
import java.time.Instant

/**
 * tool 本体は完了したが完了監査の保存に失敗したことを表す例外。
 *
 * @param toolName 監査保存に失敗した tool 名
 * @param cause 監査保存失敗の原因
 */
class ToolCompletionAuditFailedException(
    toolName: String,
    cause: Throwable,
) : RuntimeException("Tool call completed but completion audit failed. tool=$toolName executed=true", cause) {
    /**
     * tool 本体の副作用が完了していることを呼び出し元へ伝える flag。
     */
    val executed: Boolean = true
}

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
        return runLockedTool(
            call = call,
            enforceHardHalt = true,
            block = block,
        )
    }

    /**
     * risk を減らす trade 系 tool を global lock の内側で実行する。HARD_HALT 中も通す。
     */
    suspend fun <T> runRiskReducingTradeTool(call: GuardedToolCall, block: suspend () -> T): Result<T> {
        return runLockedTool(
            call = call,
            enforceHardHalt = false,
            block = block,
        )
    }

    /**
     * decision 系 tool を global lock と audit の内側で実行する。HARD_HALT 中も decision 記録は通す。
     */
    suspend fun <T> runDecisionTool(call: GuardedToolCall, block: suspend () -> T): Result<T> {
        return runLockedTool(
            call = call,
            enforceHardHalt = false,
            block = block,
        )
    }

    /**
     * 失敗時に no-trade 終了を audit へ記録する。
     */
    suspend fun recordNoTradeExit(
        call: GuardedToolCall,
        reason: String,
        cause: Throwable? = null,
    ): Result<Unit> {
        val payload = buildNoTradeFailurePayload(
            reason = reason,
            cause = cause,
            llmProvider = call.decisionRunContext.llmProvider,
        )

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

    /**
     * 指定 decision run ID の tool call 監査イベント数を返す。
     */
    suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
        return commandEventLog.countToolCallEvents(decisionRunId, toolNames)
    }

    private suspend fun <T> runLockedTool(
        call: GuardedToolCall,
        enforceHardHalt: Boolean,
        block: suspend () -> T,
    ): Result<T> {
        return try {
            tradingLock.withLock(call.toolName) {
                if (enforceHardHalt) {
                    val rejection = hardHaltRejectionOrNull<T>(call)

                    if (rejection != null) {
                        return@withLock rejection
                    }
                }

                runAndAudit(call, block)
            }
        } catch (throwable: SQLTimeoutException) {
            val auditResult = recordNoTradeExitNonCancellable(call, "trading_lock_unavailable", throwable)

            Result.failure(throwable.withSuppressedFailure(auditResult))
        }
    }

    private suspend fun <T> hardHaltRejectionOrNull(call: GuardedToolCall): Result<T>? {
        val riskState = riskStateRepository.current()
            .getOrElse { throwable ->
                val auditResult = recordNoTradeExitNonCancellable(call, "risk_state_unavailable", throwable)

                return Result.failure(throwable.withSuppressedFailure(auditResult))
            }

        if (riskState.state != RiskHaltState.HARD_HALT) {
            return null
        }

        val exception = HardHaltTradingRejectedException("HARD_HALT is enabled in risk_state.")
        val auditResult = recordHardHaltRejectionNonCancellable(call, riskState.haltReason, exception)

        return Result.failure(exception.withSuppressedFailure(auditResult))
    }

    private suspend fun <T> runAndAudit(call: GuardedToolCall, block: suspend () -> T): Result<T> {
        return try {
            val startedAt = System.nanoTime()
            val value = block()
            val durationMillis = java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
            val auditResult = recordToolCompleted(call, durationMillis)

            auditResult.fold(
                onSuccess = { Result.success(value) },
                onFailure = { throwable -> Result.failure(ToolCompletionAuditFailedException(call.toolName, throwable)) },
            )
        } catch (throwable: CancellationException) {
            val auditResult = recordNoTradeExitNonCancellable(call, "tool_call_cancelled", throwable)
            throwable.withSuppressedFailure(auditResult)

            throw throwable
        } catch (throwable: Throwable) {
            val auditResult = recordNoTradeExitNonCancellable(call, "tool_call_failed", throwable)

            Result.failure(throwable.withSuppressedFailure(auditResult))
        }
    }

    private suspend fun recordToolCompleted(call: GuardedToolCall, durationMillis: Long): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = call.decisionRunContext,
                toolName = call.toolName,
                toolCallId = call.toolCallId,
                clientRequestId = call.clientRequestId,
                eventType = CommandEventType.TOOL_CALL_COMPLETED,
                payload = toolCompletedPayload(call.payload, durationMillis),
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
            llmProvider = call.decisionRunContext.llmProvider,
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

    private suspend fun recordNoTradeExitNonCancellable(
        call: GuardedToolCall,
        reason: String,
        cause: Throwable,
    ): Result<Unit> {
        return withContext(NonCancellable) {
            recordNoTradeExit(call, reason, cause)
        }
    }

    private suspend fun recordHardHaltRejectionNonCancellable(
        call: GuardedToolCall,
        haltReason: String?,
        cause: Throwable,
    ): Result<Unit> {
        return withContext(NonCancellable) {
            recordHardHaltRejection(call, haltReason, cause)
        }
    }
}

private fun toolCompletedPayload(payload: String, durationMillis: Long): String {
    return buildJsonObject {
        put("durationMillis", durationMillis)
        put("payload", payload)
    }.toString()
}
