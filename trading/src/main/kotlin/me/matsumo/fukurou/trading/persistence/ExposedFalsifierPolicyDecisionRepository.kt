package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.config.FalsifierPolicy
import me.matsumo.fukurou.trading.decision.FALSIFIER_POLICY_EVENT_TOOL_NAME
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecision
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecisionConflictException
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecisionRepository
import me.matsumo.fukurou.trading.decision.FalsifierPolicyDecisionRequest
import me.matsumo.fukurou.trading.decision.FalsifierPolicyReasonCode
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.ResultSet
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** PostgreSQL の policy decision と canonical command event を同一 transaction で保存する。 */
class ExposedFalsifierPolicyDecisionRepository(
    private val database: ExposedDatabase,
) : FalsifierPolicyDecisionRepository {
    override suspend fun recordFalsifierPolicyDecision(request: FalsifierPolicyDecisionRequest): Result<FalsifierPolicyDecision> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val requested = request.decision
                    val byIntent = selectDecisionByIntent(requested.intentId)
                    val byId = selectDecisionById(requested.decisionId)
                    val event = selectPolicyEvent(requested.decisionId)

                    if (byIntent != null || byId != null || event != null) {
                        return@exposedTransaction exactReadbackOrConflict(requested, byIntent, byId, event)
                    }

                    insertDecision(requested)
                    insertEvent(request)
                    requested
                }
            }
        }
    }

    override suspend fun findFalsifierPolicyDecision(intentId: UUID): Result<FalsifierPolicyDecision?> = withContext(Dispatchers.IO) {
        runCatching { exposedTransaction(database) { selectDecisionByIntent(intentId) } }
    }

    private fun JdbcTransaction.exactReadbackOrConflict(
        requested: FalsifierPolicyDecision,
        byIntent: FalsifierPolicyDecision?,
        byId: FalsifierPolicyDecision?,
        event: StoredPolicyEvent?,
    ): FalsifierPolicyDecision {
        val existing = byIntent ?: throw FalsifierPolicyDecisionConflictException("policy decision ID exists without matching intent.")
        if (byId != existing || existing != requested) {
            throw FalsifierPolicyDecisionConflictException("policy decision payload conflicts with existing row.")
        }
        val storedEvent = event ?: throw FalsifierPolicyDecisionConflictException("policy decision exists without canonical event.")
        if (!storedEvent.matches(existing)) {
            throw FalsifierPolicyDecisionConflictException("policy decision event payload conflicts.")
        }

        return existing
    }

    private fun JdbcTransaction.insertDecision(decision: FalsifierPolicyDecision) {
        jdbcConnection().prepareStatement(
            """INSERT INTO falsifier_policy_decisions
                (id, intent_id, policy, required, reason_codes, runtime_config_version_id, runtime_config_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { statement ->
            statement.setObject(1, decision.decisionId)
            statement.setObject(2, decision.intentId)
            statement.setString(3, decision.policy.name)
            statement.setBoolean(4, decision.required)
            statement.setString(5, decision.reasonCodes.sortedBy(FalsifierPolicyReasonCode::name).joinToString(",") { it.name })
            statement.setString(6, decision.runtimeConfigVersionId)
            statement.setString(7, decision.runtimeConfigHash)
            statement.setLong(8, decision.createdAt.toEpochMilli())
            check(statement.executeUpdate() == 1) { "policy decision insert did not affect exactly one row." }
        }
    }

    private fun JdbcTransaction.insertEvent(request: FalsifierPolicyDecisionRequest) {
        val decision = request.decision
        val context = request.decisionRunContext
        jdbcConnection().prepareStatement(
            """INSERT INTO command_event_log
                (id, decision_run_id, tool_call_id, client_request_id, tool_name, event_type, payload, ts,
                 llm_provider, prompt_hash, system_prompt_version, market_snapshot_id, runtime_config_version_id, runtime_config_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { statement ->
            statement.setObject(1, decision.decisionId)
            statement.setString(2, context.decisionRunId)
            statement.setString(3, null)
            statement.setString(4, null)
            statement.setString(5, FALSIFIER_POLICY_EVENT_TOOL_NAME)
            statement.setString(6, CommandEventType.FALSIFIER_POLICY_EVALUATED.name)
            statement.setString(7, decision.canonicalPayload())
            statement.setLong(8, decision.createdAt.toEpochMilli())
            statement.setString(9, context.llmProvider)
            statement.setString(10, context.promptHash)
            statement.setString(11, context.systemPromptVersion)
            statement.setString(12, context.marketSnapshotId)
            statement.setString(13, context.runtimeConfigVersionId)
            statement.setString(14, context.runtimeConfigHash)
            check(statement.executeUpdate() == 1) { "policy event insert did not affect exactly one row." }
        }
    }

    private fun JdbcTransaction.selectDecisionByIntent(intentId: UUID): FalsifierPolicyDecision? =
        selectDecision("SELECT * FROM falsifier_policy_decisions WHERE intent_id = ?", intentId)

    private fun JdbcTransaction.selectDecisionById(decisionId: UUID): FalsifierPolicyDecision? =
        selectDecision("SELECT * FROM falsifier_policy_decisions WHERE id = ?", decisionId)

    private fun JdbcTransaction.selectDecision(sql: String, id: UUID): FalsifierPolicyDecision? {
        return jdbcConnection().prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { results -> if (results.next()) results.toPolicyDecision() else null }
        }
    }

    private fun JdbcTransaction.selectPolicyEvent(id: UUID): StoredPolicyEvent? {
        return jdbcConnection().prepareStatement("SELECT tool_name, event_type, payload FROM command_event_log WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { results ->
                if (!results.next()) null else StoredPolicyEvent(
                    toolName = results.getString("tool_name"),
                    eventType = results.getString("event_type"),
                    payload = results.getString("payload"),
                )
            }
        }
    }
}

private data class StoredPolicyEvent(val toolName: String, val eventType: String, val payload: String) {
    fun matches(decision: FalsifierPolicyDecision): Boolean =
        toolName == FALSIFIER_POLICY_EVENT_TOOL_NAME &&
            eventType == CommandEventType.FALSIFIER_POLICY_EVALUATED.name &&
            payload == decision.canonicalPayload()
}

private fun ResultSet.toPolicyDecision(): FalsifierPolicyDecision = FalsifierPolicyDecision(
    decisionId = getObject("id", UUID::class.java),
    intentId = getObject("intent_id", UUID::class.java),
    policy = FalsifierPolicy.valueOf(getString("policy")),
    required = getBoolean("required"),
    reasonCodes = getString("reason_codes")
        .split(',')
        .filter(String::isNotBlank)
        .map(FalsifierPolicyReasonCode::valueOf)
        .toSet(),
    runtimeConfigVersionId = getString("runtime_config_version_id"),
    runtimeConfigHash = getString("runtime_config_hash"),
    createdAt = java.time.Instant.ofEpochMilli(getLong("created_at")),
)
