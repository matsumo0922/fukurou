package me.matsumo.fukurou.trading.risk

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.time.Clock
import java.time.Instant

/**
 * risk_state の状態変更と監査ログ追記を同じ command として扱う service。
 */
interface RiskStateCommandService {
    /**
     * HARD_HALT を有効化し、reason 付きで audit log に残す。
     */
    suspend fun setHardHalt(
        reason: String,
        decisionRunContext: DecisionRunContext = DecisionRunContext.fromEnvironment(),
    ): Result<RiskState>

    /**
     * SOFT_HALT を有効化し、reason 付きで audit log に残す。
     */
    suspend fun setSoftHalt(
        reason: String,
        decisionRunContext: DecisionRunContext = DecisionRunContext.fromEnvironment(),
    ): Result<RiskState>

    /**
     * 手動再開を reason 付きで記録し、audit log に残す。
     */
    suspend fun resume(
        reason: String,
        decisionRunContext: DecisionRunContext = DecisionRunContext.fromEnvironment(),
    ): Result<RiskState>
}

/**
 * unit test と DB 未構成時のための in-memory risk_state command service。
 *
 * @param riskStateRepository 共有する in-memory risk_state repository
 * @param commandEventLog command_event_log repository
 * @param clock 状態変更と audit timestamp に使う clock
 */
class InMemoryRiskStateCommandService(
    private val riskStateRepository: InMemoryRiskStateRepository,
    private val commandEventLog: CommandEventLog,
    private val clock: Clock = Clock.systemUTC(),
) : RiskStateCommandService {

    override suspend fun setHardHalt(
        reason: String,
        decisionRunContext: DecisionRunContext,
    ): Result<RiskState> {
        return mutateAndAudit(
            reason = reason,
            decisionRunContext = decisionRunContext,
            eventType = CommandEventType.HARD_HALT_SET,
        ) { commandReason, occurredAt ->
            riskStateRepository.setHardHalt(commandReason, occurredAt)
        }
    }

    override suspend fun setSoftHalt(
        reason: String,
        decisionRunContext: DecisionRunContext,
    ): Result<RiskState> {
        return mutateAndAudit(
            reason = reason,
            decisionRunContext = decisionRunContext,
            eventType = CommandEventType.SOFT_HALT_SET,
        ) { commandReason, occurredAt ->
            riskStateRepository.setSoftHalt(commandReason, occurredAt)
        }
    }

    override suspend fun resume(
        reason: String,
        decisionRunContext: DecisionRunContext,
    ): Result<RiskState> {
        return mutateAndAudit(
            reason = reason,
            decisionRunContext = decisionRunContext,
            eventType = CommandEventType.MANUAL_RESUME_REQUESTED,
        ) { commandReason, occurredAt ->
            riskStateRepository.resume(commandReason, occurredAt)
        }
    }

    private suspend fun mutateAndAudit(
        reason: String,
        decisionRunContext: DecisionRunContext,
        eventType: CommandEventType,
        mutation: suspend (String, Instant) -> Result<RiskState>,
    ): Result<RiskState> {
        return runCatching {
            val previousState = riskStateRepository.current().getOrThrow()
            val occurredAt = clock.instant()
            val riskState = mutation(reason, occurredAt).getOrThrow()

            try {
                commandEventLog.append(
                    CommandEvent(
                        decisionRunContext = decisionRunContext,
                        toolName = RISK_STATE_COMMAND_NAME,
                        toolCallId = null,
                        clientRequestId = null,
                        eventType = eventType,
                        payload = buildRiskStateCommandPayload(reason, previousState),
                        occurredAt = occurredAt,
                    ),
                ).getOrThrow()
            } catch (throwable: Throwable) {
                riskStateRepository.restore(previousState).getOrThrow()

                throw throwable
            }

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
private fun buildRiskStateCommandPayload(reason: String, previousState: RiskState): String {
    return buildJsonObject {
        put("reason", reason)
        put("previousState", previousState.state.name)
    }.toString()
}
