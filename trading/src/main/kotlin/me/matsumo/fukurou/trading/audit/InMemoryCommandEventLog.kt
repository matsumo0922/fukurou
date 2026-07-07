package me.matsumo.fukurou.trading.audit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * unit test と DB 未構成時のための in-memory command event log。
 */
class InMemoryCommandEventLog : CommandEventLog, CommandEventFeedReader {

    private val mutex = Mutex()
    private val storedEvents = mutableListOf<CommandEvent>()

    override suspend fun append(event: CommandEvent): Result<Unit> {
        mutex.withLock {
            storedEvents += event
        }

        return Result.success(Unit)
    }

    override suspend fun countDistinctDecisionRunsSince(since: Instant): Result<Int> {
        return runCatching {
            mutex.withLock {
                storedEvents
                    .filter { event -> !event.occurredAt.isBefore(since) }
                    .filter { event -> event.eventType in DECISION_RUN_COUNTED_EVENT_TYPES }
                    .mapNotNull { event -> event.decisionRunContext.decisionRunId }
                    .distinct()
                    .size
            }
        }
    }

    override suspend fun countToolCallEvents(
        decisionRunId: String,
        toolNames: Set<String>,
    ): Result<Int> {
        return runCatching {
            mutex.withLock {
                storedEvents.count { event ->
                    val decisionRunMatched = event.decisionRunContext.decisionRunId == decisionRunId
                    val toolNameMatched = event.toolName in toolNames
                    val eventTypeMatched = event.eventType in TOOL_CALL_COUNTED_EVENT_TYPES

                    decisionRunMatched && toolNameMatched && eventTypeMatched
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
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            mutex.withLock {
                storedEvents
                    .filter { event -> event.occurredAt < before }
                    .filter { event -> event.matchesEventTypeFilter(eventTypes, excludeEventTypes) }
                    .sortedByDescending { event -> event.occurredAt }
                    .take(limit)
            }
        }
    }

    /**
     * 保存済みイベントの snapshot を返す。
     */
    suspend fun events(): List<CommandEvent> {
        return mutex.withLock { storedEvents.toList() }
    }
}

private fun CommandEvent.matchesEventTypeFilter(
    eventTypes: Set<CommandEventType>?,
    excludeEventTypes: Set<CommandEventType>,
): Boolean {
    val includedByType = eventTypes == null || this.eventType in eventTypes
    val notExcluded = this.eventType !in excludeEventTypes

    return includedByType && notExcluded
}

/**
 * tool call 数として扱う監査イベント種別。
 */
private val TOOL_CALL_COUNTED_EVENT_TYPES = setOf(
    CommandEventType.TOOL_CALL_COMPLETED,
    CommandEventType.TOOL_CALL_REJECTED_BY_HARD_HALT,
    CommandEventType.NO_TRADE_EXIT,
)

/**
 * LLM 起動回数として扱う監査イベント種別。
 */
private val DECISION_RUN_COUNTED_EVENT_TYPES = setOf(
    CommandEventType.RUNNER_PHASE_COMPLETED,
    CommandEventType.NO_TRADE_EXIT,
)

/**
 * cursor 未指定時に全件を対象にするための終端時刻。
 */
private val COMMAND_EVENT_FEED_END_CURSOR: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)
