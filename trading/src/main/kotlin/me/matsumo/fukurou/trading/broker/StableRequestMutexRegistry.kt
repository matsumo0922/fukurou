package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.sync.Mutex

/**
 * stable request ID ごとの cancellable mutex を共有する registry。
 *
 * holder と waiter の参照がなくなった entry だけを除去する。
 */
internal class StableRequestMutexRegistry {
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(clientRequestId: String, block: suspend () -> T): T {
        val lease = acquire(clientRequestId)

        return try {
            block()
        } finally {
            lease.close()
        }
    }

    suspend fun acquire(clientRequestId: String): Lease {
        val entry = retain(clientRequestId)
        var acquired = false

        try {
            entry.mutex.lock()
            acquired = true

            return Lease {
                entry.mutex.unlock()
                release(clientRequestId, entry)
            }
        } finally {
            if (!acquired) release(clientRequestId, entry)
        }
    }

    internal fun entryCount(): Int = synchronized(entries) { entries.size }

    private fun retain(clientRequestId: String): Entry {
        return synchronized(entries) {
            entries.getOrPut(clientRequestId) { Entry() }
                .also { entry -> entry.references += 1 }
        }
    }

    private fun release(clientRequestId: String, entry: Entry) {
        synchronized(entries) {
            entry.references -= 1
            check(entry.references >= 0)

            if (entry.references == 0 && entries[clientRequestId] === entry) {
                entries.remove(clientRequestId)
            }
        }
    }

    private class Entry(
        val mutex: Mutex = Mutex(),
        var references: Int = 0,
    )

    internal class Lease(
        private var release: (() -> Unit)?,
    ) : AutoCloseable {
        override fun close() {
            val releaseAction = synchronized(this) {
                release.also { release = null }
            }
            releaseAction?.invoke()
        }
    }
}
