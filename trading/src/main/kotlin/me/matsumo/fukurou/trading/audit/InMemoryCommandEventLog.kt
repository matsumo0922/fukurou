package me.matsumo.fukurou.trading.audit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * unit test と DB 未構成時のための in-memory command event log。
 */
class InMemoryCommandEventLog : CommandEventLog {

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

    /**
     * 保存済みイベントの snapshot を返す。
     */
    suspend fun events(): List<CommandEvent> {
        return mutex.withLock { storedEvents.toList() }
    }
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
