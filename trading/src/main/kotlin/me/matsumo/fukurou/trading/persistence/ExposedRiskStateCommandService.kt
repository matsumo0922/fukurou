package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Clock
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * risk_state 更新と command_event_log 追記を同一 transaction で実行する service。
 *
 * @param database Exposed database
 * @param clock 状態変更と audit timestamp に使う clock
 */
class ExposedRiskStateCommandService(
    private val database: ExposedDatabase,
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
            blankReasonMessage = "HARD_HALT reason is required.",
        ) { commandReason, occurredAt ->
            updateHardHalt(commandReason, occurredAt)
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
            blankReasonMessage = "manual resume reason is required.",
        ) { commandReason, occurredAt ->
            updateResume(commandReason, occurredAt)
        }
    }

    private suspend fun mutateAndAudit(
        reason: String,
        decisionRunContext: DecisionRunContext,
        eventType: CommandEventType,
        blankReasonMessage: String,
        mutation: JdbcTransaction.(String, Instant) -> Unit,
    ): Result<RiskState> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(reason.isNotBlank()) { blankReasonMessage }

                val occurredAt = clock.instant()

                exposedTransaction(database) {
                    ensureRiskStateRow(occurredAt)
                    selectRiskState(forUpdate = true)
                    mutation(reason, occurredAt)

                    val riskState = selectRiskState(forUpdate = true)
                    insertEvent(
                        CommandEvent(
                            decisionRunContext = decisionRunContext,
                            toolName = RISK_STATE_COMMAND_NAME,
                            toolCallId = null,
                            clientRequestId = null,
                            eventType = eventType,
                            payload = buildRiskStateCommandPayload(reason),
                            occurredAt = occurredAt,
                        ),
                    )

                    riskState
                }
            }
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
