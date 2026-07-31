package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StableRequestMutexRegistryTest {
    @Test
    fun `lease close is idempotent`() = runBlocking {
        val registry = StableRequestMutexRegistry()
        val lease = registry.acquire("runner-place-v2-double-close")

        lease.close()
        lease.close()

        assertEquals(0, registry.entryCount())
    }

    @Test
    fun `same request waits until holder terminal and then cleans entry`() = runBlocking {
        val registry = StableRequestMutexRegistry()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val waiterEntered = CompletableDeferred<Unit>()

        coroutineScope {
            val holder = launch {
                registry.withLock("runner-place-v2-same") {
                    holderEntered.complete(Unit)
                    releaseHolder.await()
                }
            }
            holderEntered.await()
            val waiter = launch {
                registry.withLock("runner-place-v2-same") {
                    waiterEntered.complete(Unit)
                }
            }

            assertFalse(waiterEntered.isCompleted)
            assertEquals(1, registry.entryCount())
            releaseHolder.complete(Unit)
            holder.join()
            waiter.join()
        }

        assertEquals(0, registry.entryCount())
    }

    @Test
    fun `different requests enter concurrently`() = runBlocking {
        val registry = StableRequestMutexRegistry()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        coroutineScope {
            val calls = listOf(
                async {
                    registry.withLock("runner-place-v2-first") {
                        firstEntered.complete(Unit)
                        release.await()
                    }
                },
                async {
                    registry.withLock("runner-place-v2-second") {
                        secondEntered.complete(Unit)
                        release.await()
                    }
                },
            )

            withTimeout(1_000) {
                firstEntered.await()
                secondEntered.await()
            }
            assertEquals(2, registry.entryCount())
            release.complete(Unit)
            calls.forEach { call -> call.await() }
        }

        assertEquals(0, registry.entryCount())
    }

    @Test
    fun `cancelled waiter releases only its reference`() = runBlocking {
        val registry = StableRequestMutexRegistry()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()

        coroutineScope {
            val holder = launch {
                registry.withLock("runner-place-v2-cancel") {
                    holderEntered.complete(Unit)
                    releaseHolder.await()
                }
            }
            holderEntered.await()
            val waiter = launch {
                registry.withLock("runner-place-v2-cancel") {
                    error("cancelled waiter entered")
                }
            }

            waiter.cancelAndJoin()
            assertEquals(1, registry.entryCount())
            releaseHolder.complete(Unit)
            holder.join()
        }

        assertEquals(0, registry.entryCount())
    }
}
