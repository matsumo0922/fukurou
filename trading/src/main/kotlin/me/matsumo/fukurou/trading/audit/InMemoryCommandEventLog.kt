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
                    .mapNotNull { event -> event.decisionRunContext.decisionRunId }
                    .distinct()
                    .size
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
