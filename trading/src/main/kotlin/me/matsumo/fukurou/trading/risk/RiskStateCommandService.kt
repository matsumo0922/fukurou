package me.matsumo.fukurou.trading.risk

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.time.Clock

/**
 * risk_state の状態変更と監査ログ追記をまとめる service。
 *
 * @param riskStateRepository risk_state single row repository
 * @param commandEventLog command_event_log repository
 * @param clock 状態変更と audit timestamp に使う clock
 */
class RiskStateCommandService(
    private val riskStateRepository: RiskStateRepository,
    private val commandEventLog: CommandEventLog,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * HARD_HALT を有効化し、reason 付きで audit log に残す。
     */
    suspend fun setHardHalt(
        reason: String,
        decisionRunContext: DecisionRunContext = DecisionRunContext.fromEnvironment(),
    ): Result<RiskState> {
        return runCatching {
            val occurredAt = clock.instant()
            val riskState = riskStateRepository.setHardHalt(reason, occurredAt).getOrThrow()

            commandEventLog.append(
                CommandEvent(
                    decisionRunContext = decisionRunContext,
                    toolName = RISK_STATE_COMMAND_NAME,
                    toolCallId = null,
                    clientRequestId = null,
                    eventType = CommandEventType.HARD_HALT_SET,
                    payload = buildRiskStateCommandPayload(reason),
                    occurredAt = occurredAt,
                ),
            ).getOrThrow()

            riskState
        }
    }

    /**
     * 手動再開を reason 付きで記録し、audit log に残す。
     */
    suspend fun resume(
        reason: String,
        decisionRunContext: DecisionRunContext = DecisionRunContext.fromEnvironment(),
    ): Result<RiskState> {
        return runCatching {
            val occurredAt = clock.instant()
            val riskState = riskStateRepository.resume(reason, occurredAt).getOrThrow()

            commandEventLog.append(
                CommandEvent(
                    decisionRunContext = decisionRunContext,
                    toolName = RISK_STATE_COMMAND_NAME,
                    toolCallId = null,
                    clientRequestId = null,
                    eventType = CommandEventType.MANUAL_RESUME_REQUESTED,
                    payload = buildRiskStateCommandPayload(reason),
                    occurredAt = occurredAt,
                ),
            ).getOrThrow()

            riskState
        }
    }
}

/**
 * risk_state 状態変更イベントの論理 tool 名。
 */
private const val RISK_STATE_COMMAND_NAME = "risk_state"

/**
 * reason 付きの risk_state 状態変更 payload を組み立てる。
 */
private fun buildRiskStateCommandPayload(reason: String): String {
    return buildJsonObject {
        put("reason", reason)
    }.toString()
}
