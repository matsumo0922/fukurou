package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * command_event_log へ event を append する SQL。
 */
private const val INSERT_COMMAND_EVENT_SQL = """
    INSERT INTO command_event_log (
        id,
        decision_run_id,
        tool_call_id,
        client_request_id,
        tool_name,
        event_type,
        payload,
        ts,
        llm_provider,
        prompt_hash,
        system_prompt_version,
        market_snapshot_id
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * Exposed/JDBC で command_event_log を扱う repository。
 *
 * @param database Exposed database
 */
class ExposedCommandEventLog(
    private val database: ExposedDatabase,
) : CommandEventLog {

    override suspend fun append(event: CommandEvent): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertEvent(event)
                }
            }
        }
    }
}

/**
 * command_event_log へ 1 行追加する。
 */
internal fun JdbcTransaction.insertEvent(event: CommandEvent) {
    jdbcConnection().prepareStatement(INSERT_COMMAND_EVENT_SQL).use { statement ->
        statement.setObject(1, event.id)
        statement.setNullableString(2, event.decisionRunContext.decisionRunId)
        statement.setNullableString(3, event.toolCallId)
        statement.setNullableString(4, event.clientRequestId)
        statement.setString(5, event.toolName)
        statement.setString(6, event.eventType.name)
        statement.setString(7, event.payload)
        statement.setLong(8, event.occurredAt.toEpochMilli())
        statement.setNullableString(9, event.decisionRunContext.llmProvider)
        statement.setNullableString(10, event.decisionRunContext.promptHash)
        statement.setNullableString(11, event.decisionRunContext.systemPromptVersion)
        statement.setNullableString(12, event.decisionRunContext.marketSnapshotId)
        statement.executeUpdate()
    }
}
