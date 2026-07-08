package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventFeedReader
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.PreparedStatement
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
        market_snapshot_id,
        runtime_config_version_id,
        runtime_config_hash
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
 * command_event_log を読む SELECT の列部分。WHERE 条件は動的に組み立てる。
 */
private const val SELECT_COMMAND_EVENTS_SQL_PREFIX = """
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
        market_snapshot_id,
        runtime_config_version_id,
        runtime_config_hash
    FROM command_event_log
"""

/**
 * command_event_log を読む SELECT の並び順と件数制限。
 */
private const val SELECT_COMMAND_EVENTS_SQL_SUFFIX = """
    ORDER BY ts DESC
    LIMIT ?
"""

/**
 * Activity timeline 用 command_event_log stable feed SELECT の並び順と件数制限。
 */
private const val SELECT_COMMAND_EVENTS_STABLE_FEED_SQL_SUFFIX = """
    ORDER BY ts DESC, CAST(id AS TEXT) ASC
    LIMIT ?
"""

/**
 * cursor 未指定時に全件を対象にするための DB 保存可能な終端時刻。
 */
private val COMMAND_EVENT_FEED_END_CURSOR: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)

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

    override suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
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

    override suspend fun findEvents(
        limit: Int,
        eventType: CommandEventType?,
        excludeEventTypes: Set<CommandEventType>,
    ): Result<List<CommandEvent>> {
        val eventTypes = eventType?.let { setOf(it) }

        return findEventsBefore(
            limit = limit,
            before = COMMAND_EVENT_FEED_END_CURSOR,
            eventTypes = eventTypes,
            excludeEventTypes = excludeEventTypes,
        )
    }

    override suspend fun findEventsBefore(
        limit: Int,
        before: Instant,
        eventTypes: Set<CommandEventType>?,
        excludeEventTypes: Set<CommandEventType>,
    ): Result<List<CommandEvent>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(limit > 0) {
                    "limit must be greater than 0."
                }
                require(eventTypes == null || eventTypes.isNotEmpty()) {
                    "eventTypes must be null or not empty."
                }

                exposedTransaction(database) {
                    selectEventsBefore(
                        limit = limit,
                        before = before,
                        eventTypes = eventTypes,
                        excludeEventTypes = excludeEventTypes,
                    )
                }
            }
        }
    }

    override suspend fun findEventsForStableFeed(
        cursor: StableFeedCursor,
        limit: Int,
        eventTypes: Set<CommandEventType>?,
        excludeEventTypes: Set<CommandEventType>,
    ): Result<List<CommandEvent>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(limit > 0) {
                    "limit must be greater than 0."
                }
                require(eventTypes == null || eventTypes.isNotEmpty()) {
                    "eventTypes must be null or not empty."
                }

                exposedTransaction(database) {
                    selectEventsForStableFeed(
                        cursor = cursor,
                        limit = limit,
                        eventTypes = eventTypes,
                        excludeEventTypes = excludeEventTypes,
                    )
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
        statement.setNullableString(13, event.decisionRunContext.runtimeConfigVersionId)
        statement.setNullableString(14, event.decisionRunContext.runtimeConfigHash)
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

private fun JdbcTransaction.selectEventsBefore(
    limit: Int,
    before: Instant,
    eventTypes: Set<CommandEventType>?,
    excludeEventTypes: Set<CommandEventType>,
): List<CommandEvent> {
    val sortedEventTypes = eventTypes?.sortedBy { eventType -> eventType.name }
    val sortedExcludeEventTypes = excludeEventTypes.sortedBy { eventType -> eventType.name }
    val sql = buildSelectEventsSql(
        eventTypeCount = sortedEventTypes?.size ?: 0,
        excludeEventTypeCount = sortedExcludeEventTypes.size,
    )

    return jdbcConnection().prepareStatement(sql).use { statement ->
        val beforeParameterIndex = bindEventFilterParameters(
            statement = statement,
            eventTypes = sortedEventTypes,
            excludeEventTypes = sortedExcludeEventTypes,
        )
        val limitParameterIndex = beforeParameterIndex + 1
        statement.setLong(beforeParameterIndex, before.toEpochMilli())
        statement.setInt(limitParameterIndex, limit)

        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toCommandEvent())
                }
            }
        }
    }
}

private fun JdbcTransaction.selectEventsForStableFeed(
    cursor: StableFeedCursor,
    limit: Int,
    eventTypes: Set<CommandEventType>?,
    excludeEventTypes: Set<CommandEventType>,
): List<CommandEvent> {
    val sortedEventTypes = eventTypes?.sortedBy { eventType -> eventType.name }
    val sortedExcludeEventTypes = excludeEventTypes.sortedBy { eventType -> eventType.name }
    val sql = buildSelectEventsForStableFeedSql(
        cursor = cursor,
        eventTypeCount = sortedEventTypes?.size ?: 0,
        excludeEventTypeCount = sortedExcludeEventTypes.size,
    )

    return jdbcConnection().prepareStatement(sql).use { statement ->
        val cursorParameterIndex = bindEventFilterParameters(
            statement = statement,
            eventTypes = sortedEventTypes,
            excludeEventTypes = sortedExcludeEventTypes,
        )
        val limitParameterIndex = statement.bindStableFeedCursor(cursorParameterIndex, cursor)
        statement.setInt(limitParameterIndex, limit)

        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toCommandEvent())
                }
            }
        }
    }
}

/**
 * event_type 絞り込みと除外条件から SELECT 文を組み立てる。
 */
private fun buildSelectEventsSql(eventTypeCount: Int, excludeEventTypeCount: Int): String {
    val conditions = buildList {
        if (eventTypeCount > 0) {
            add("event_type IN (" + placeholders(eventTypeCount) + ")")
        }

        if (excludeEventTypeCount > 0) {
            add("event_type NOT IN (" + placeholders(excludeEventTypeCount) + ")")
        }

        add("ts < ?")
    }

    val whereClause = if (conditions.isEmpty()) {
        ""
    } else {
        "\n    WHERE " + conditions.joinToString(" AND ")
    }

    return SELECT_COMMAND_EVENTS_SQL_PREFIX + whereClause + SELECT_COMMAND_EVENTS_SQL_SUFFIX
}

/**
 * event_type 絞り込み、除外条件、安定 cursor 条件から SELECT 文を組み立てる。
 */
private fun buildSelectEventsForStableFeedSql(
    cursor: StableFeedCursor,
    eventTypeCount: Int,
    excludeEventTypeCount: Int,
): String {
    val conditions = buildList {
        if (eventTypeCount > 0) {
            add("event_type IN (" + placeholders(eventTypeCount) + ")")
        }

        if (excludeEventTypeCount > 0) {
            add("event_type NOT IN (" + placeholders(excludeEventTypeCount) + ")")
        }

        add(stableFeedCursorCondition("ts", "id", cursor))
    }
    val whereClause = "\n    WHERE " + conditions.joinToString(" AND ")

    return SELECT_COMMAND_EVENTS_SQL_PREFIX + whereClause + SELECT_COMMAND_EVENTS_STABLE_FEED_SQL_SUFFIX
}

/**
 * event_type 絞り込みと除外条件の placeholder を束縛し、次に束縛すべき limit の parameter index を返す。
 */
private fun bindEventFilterParameters(
    statement: PreparedStatement,
    eventTypes: List<CommandEventType>?,
    excludeEventTypes: List<CommandEventType>,
): Int {
    var parameterIndex = 1

    eventTypes?.forEach { eventType ->
        statement.setString(parameterIndex, eventType.name)
        parameterIndex += 1
    }

    excludeEventTypes.forEach { excludedEventType ->
        statement.setString(parameterIndex, excludedEventType.name)
        parameterIndex += 1
    }

    return parameterIndex
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
            runtimeConfigVersionId = getString("runtime_config_version_id"),
            runtimeConfigHash = getString("runtime_config_hash"),
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
