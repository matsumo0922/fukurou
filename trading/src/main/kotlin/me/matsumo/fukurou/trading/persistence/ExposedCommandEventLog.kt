package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventFeedReader
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
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
        AND event_type IN ('RUNNER_PHASE_COMPLETED', 'NO_TRADE_EXIT')
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
 * command_event_log を新しい順で読む SQL。
 */
private const val SELECT_COMMAND_EVENTS_SQL = """
    SELECT
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
    FROM command_event_log
    ORDER BY ts DESC
    LIMIT ?
"""

/**
 * command_event_log を event_type で絞って新しい順で読む SQL。
 */
private const val SELECT_COMMAND_EVENTS_BY_TYPE_SQL = """
    SELECT
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
    FROM command_event_log
    WHERE event_type = ?
    ORDER BY ts DESC
    LIMIT ?
"""

/**
 * Exposed/JDBC で command_event_log を扱う repository。
 *
 * @param database Exposed database
 */
class ExposedCommandEventLog(
    private val database: ExposedDatabase,
) : CommandEventLog, CommandEventFeedReader {

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

    override suspend fun findEvents(limit: Int, eventType: CommandEventType?): Result<List<CommandEvent>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(limit > 0) {
                    "limit must be greater than 0."
                }

                exposedTransaction(database) {
                    selectEvents(limit, eventType)
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

private fun JdbcTransaction.selectEvents(limit: Int, eventType: CommandEventType?): List<CommandEvent> {
    val sql = if (eventType == null) SELECT_COMMAND_EVENTS_SQL else SELECT_COMMAND_EVENTS_BY_TYPE_SQL

    return jdbcConnection().prepareStatement(sql).use { statement ->
        if (eventType == null) {
            statement.setInt(1, limit)
        } else {
            statement.setString(1, eventType.name)
            statement.setInt(2, limit)
        }

        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toCommandEvent())
                }
            }
        }
    }
}

private fun ResultSet.toCommandEvent(): CommandEvent {
    return CommandEvent(
        id = getObject("id", UUID::class.java),
        decisionRunContext = DecisionRunContext(
            decisionRunId = getString("decision_run_id"),
            llmProvider = getString("llm_provider"),
            promptHash = getString("prompt_hash"),
            systemPromptVersion = getString("system_prompt_version"),
            marketSnapshotId = getString("market_snapshot_id"),
        ),
        toolName = getString("tool_name"),
        toolCallId = getString("tool_call_id"),
        clientRequestId = getString("client_request_id"),
        eventType = CommandEventType.valueOf(getString("event_type")),
        payload = getString("payload"),
        occurredAt = Instant.ofEpochMilli(getLong("ts")),
    )
}

/**
 * tool call 数として扱う監査イベント種別。
 */
private val TOOL_CALL_COUNTED_EVENT_TYPES = setOf(
    CommandEventType.TOOL_CALL_COMPLETED,
    CommandEventType.TOOL_CALL_REJECTED_BY_HARD_HALT,
    CommandEventType.NO_TRADE_EXIT,
)
