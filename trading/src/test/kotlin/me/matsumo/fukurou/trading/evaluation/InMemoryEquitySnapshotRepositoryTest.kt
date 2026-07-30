@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.evaluation

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.TradingMode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryEquitySnapshotRepositoryTest {
    @Test
    fun `exclusive transaction restores the snapshot collection`() = runBlocking {
        val repository = InMemoryEquitySnapshotRepository()
        val initial = snapshot(1)
        repository.appendSnapshot(initial)

        repository.withExclusiveTransaction {
            val before = snapshot()
            append(snapshot(2))
            replace(before)
        }

        assertEquals(listOf(initial), repository.findAll().getOrThrow())
    }

    @Test
    fun `exclusive transaction shares the public repository lock`() = runBlocking {
        val repository = InMemoryEquitySnapshotRepository()

        repository.withExclusiveTransaction {
            append(snapshot(1))
        }
        repository.appendDailySnapshotIfAbsent(snapshot(2, EquitySnapshotReason.DAILY))

        val snapshots = repository.findAll().getOrThrow()
        assertEquals(2, snapshots.size)
        assertTrue(snapshots.any { it.reason == EquitySnapshotReason.DAILY })
    }

    @Test
    fun `exclusive transaction blocks public append until it releases the shared lock`() {
        val repository = InMemoryEquitySnapshotRepository()
        val enteredExclusive = CountDownLatch(1)
        val releaseExclusive = CountDownLatch(1)
        val appendStarted = CountDownLatch(1)
        val appendThread = AtomicReference<Thread>()
        val exclusiveExecutor = Executors.newSingleThreadExecutor()
        val appendExecutor = Executors.newSingleThreadExecutor()

        try {
            val exclusive = exclusiveExecutor.submit<Unit> {
                repository.withExclusiveTransaction {
                    enteredExclusive.countDown()
                    check(releaseExclusive.await(1, TimeUnit.SECONDS)) { "exclusive transaction was not released." }
                }
            }
            assertTrue(enteredExclusive.await(1, TimeUnit.SECONDS))

            val append = appendExecutor.submit<Unit> {
                appendThread.set(Thread.currentThread())
                appendStarted.countDown()
                repository.appendSnapshot(snapshot(2))
            }
            assertTrue(appendStarted.await(1, TimeUnit.SECONDS))
            assertTrue(appendThread.awaitState(Thread.State.BLOCKED, 1, TimeUnit.SECONDS))
            assertFalse(append.isDone)

            releaseExclusive.countDown()
            exclusive.get(1, TimeUnit.SECONDS)
            append.get(1, TimeUnit.SECONDS)
        } finally {
            releaseExclusive.countDown()
            exclusiveExecutor.shutdownNow()
            appendExecutor.shutdownNow()
        }

        assertEquals(listOf(snapshot(2)), runBlocking { repository.findAll().getOrThrow() })
    }

    @Test
    fun `exclusive transaction rejects escaped receiver after unlock`() {
        val repository = InMemoryEquitySnapshotRepository()
        var escaped: InMemoryEquitySnapshotTransaction? = null

        repository.withExclusiveTransaction {
            escaped = this
        }

        assertFailsWith<IllegalStateException> {
            requireNotNull(escaped).snapshot()
        }
    }
}

private fun AtomicReference<Thread>.awaitState(
    expected: Thread.State,
    timeout: Long,
    unit: TimeUnit,
): Boolean {
    val deadline = System.nanoTime() + unit.toNanos(timeout)

    while (System.nanoTime() < deadline) {
        if (get()?.state == expected) return true
        Thread.onSpinWait()
    }

    return get()?.state == expected
}

private fun snapshot(index: Long, reason: EquitySnapshotReason = EquitySnapshotReason.FILL): EquitySnapshotRecord {
    return EquitySnapshotRecord(
        id = UUID(index, index),
        mode = TradingMode.PAPER,
        reason = reason,
        tradingDate = LocalDate.of(2026, 7, 31),
        capturedAt = Instant.ofEpochSecond(index),
        cashJpy = BigDecimal("1000000"),
        btcQuantity = BigDecimal.ZERO,
        btcMarkPriceJpy = BigDecimal("10000000"),
        totalEquityJpy = BigDecimal("1000000"),
        equityPeakJpy = BigDecimal("1000000"),
        drawdownRatio = BigDecimal.ZERO,
    )
}
