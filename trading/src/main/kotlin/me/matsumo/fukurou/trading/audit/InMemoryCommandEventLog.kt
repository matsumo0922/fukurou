package me.matsumo.fukurou.trading.audit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * 保存済みイベントの snapshot を返す。
     */
    suspend fun events(): List<CommandEvent> {
        return mutex.withLock { storedEvents.toList() }
    }
}
