package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant
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
 * audit log に現れた distinct decision run ID 数を数える SQL。
 */
private const val COUNT_DISTINCT_DECISION_RUNS_SINCE_SQL = """
    SELECT COUNT(DISTINCT decision_run_id)
    FROM command_event_log
    WHERE decision_run_id IS NOT NULL
        AND ts >= ?
"""

/**
 * tool call 監査イベントを数える SQL の前半。
 */
private const val COUNT_TOOL_CALL_EVENTS_SQL_PREFIX = """
    SELECT COUNT(*)
    FROM command_event_log
    WHERE decision_run_id = ?
        AND event_type IN (
"""

/**
 * tool call 監査イベントを数える SQL の tool 名条件。
 */
private const val COUNT_TOOL_CALL_EVENTS_SQL_TOOL_CONDITION = """
        )
        AND tool_name IN (
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

    override suspend fun countDistinctDecisionRunsSince(since: Instant): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    jdbcConnection().prepareStatement(COUNT_DISTINCT_DECISION_RUNS_SINCE_SQL).use { statement ->
                        statement.setLong(1, since.toEpochMilli())
                        statement.executeQuery().use { resultSet ->
                            if (resultSet.next()) resultSet.getInt(1) else 0
                        }
                    }
                }
            }
        }
    }

    override suspend fun countToolCallEvents(
        decisionRunId: String,
        toolNames: Set<String>,
    ): Result<Int> {
        if (toolNames.isEmpty()) {
            return Result.success(0)
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val eventTypeNames = TOOL_CALL_COUNTED_EVENT_TYPES.map { eventType -> eventType.name }
                    val sortedToolNames = toolNames.sorted()
                    val sql = countToolCallEventsSql(
                        eventTypeCount = eventTypeNames.size,
                        toolNameCount = sortedToolNames.size,
                    )

                    jdbcConnection().prepareStatement(sql).use { statement ->
                        var parameterIndex = 1

                        statement.setString(parameterIndex, decisionRunId)
                        parameterIndex += 1

                        eventTypeNames.forEach { eventTypeName ->
                            statement.setString(parameterIndex, eventTypeName)
                            parameterIndex += 1
                        }
                        sortedToolNames.forEach { toolName ->
                            statement.setString(parameterIndex, toolName)
                            parameterIndex += 1
                        }

                        statement.executeQuery().use { resultSet ->
                            if (resultSet.next()) resultSet.getInt(1) else 0
                        }
                    }
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

private fun countToolCallEventsSql(eventTypeCount: Int, toolNameCount: Int): String {
    val eventTypePlaceholders = placeholders(eventTypeCount)
    val toolNamePlaceholders = placeholders(toolNameCount)

    return COUNT_TOOL_CALL_EVENTS_SQL_PREFIX +
        eventTypePlaceholders +
        COUNT_TOOL_CALL_EVENTS_SQL_TOOL_CONDITION +
        toolNamePlaceholders +
        ")"
}

private fun placeholders(count: Int): String {
    return List(count) { "?" }.joinToString(", ")
}

/**
 * tool call 数として扱う監査イベント種別。
 */
private val TOOL_CALL_COUNTED_EVENT_TYPES = setOf(
    CommandEventType.TOOL_CALL_COMPLETED,
    CommandEventType.TOOL_CALL_REJECTED_BY_HARD_HALT,
    CommandEventType.NO_TRADE_EXIT,
)
