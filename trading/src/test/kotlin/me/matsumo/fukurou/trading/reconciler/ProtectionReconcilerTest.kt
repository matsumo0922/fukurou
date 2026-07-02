package me.matsumo.fukurou.trading.reconciler

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.lock.TradingLockLease
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ProtectionReconciler の startup full pass と loop contract を検証するテスト。
 */
class ProtectionReconcilerTest {

    @Test
    fun startup_full_pass_takes_global_lock_and_updates_status() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val lock = CountingTradingLock(fixedClock())
        val status = MutableReconcilerStatus()
        val reconciler = createReconciler(
            eventLog = eventLog,
            lock = lock,
            status = status,
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.STARTUP_FULL)

        assertTrue(result.isSuccess)
        assertEquals(1, lock.acquisitionCount)
        assertEquals(fixedInstant(), status.snapshot().lastReconciledAt)
        assertTrue(status.snapshot().startupFullReconcileCompleted)
        assertTrue(eventLog.events().any { event -> event.eventType == CommandEventType.RECONCILER_PASS_COMPLETED })
    }

    @Test
    fun run_loop_performs_startup_full_pass_before_loop_passes() = runBlocking {
        val lock = CountingTradingLock(fixedClock())
        val status = MutableReconcilerStatus()
        val reconciler = createReconciler(
            lock = lock,
            status = status,
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(500) {
            while (lock.acquisitionCount < 2) {
                delay(10)
            }
        }
        job.cancelAndJoin()

        assertTrue(status.snapshot().startupFullReconcileCompleted)
        assertTrue(lock.acquisitionCount >= 2)
    }
}

/**
 * lock 取得回数を数える test lock。
 */
private class CountingTradingLock(
    clock: Clock,
) : TradingLock {

    private val delegate = InMemoryTradingLock(clock)

    /**
     * lock 取得回数。
     */
    var acquisitionCount: Int = 0
        private set

    override suspend fun <T> withLock(owner: String, block: suspend (TradingLockLease) -> T): T {
        return delegate.withLock(owner) { lease ->
            acquisitionCount += 1

            block(lease)
        }
    }
}

/**
 * ProtectionReconciler test 用の reconciler を作る。
 */
private fun createReconciler(
    eventLog: InMemoryCommandEventLog = InMemoryCommandEventLog(),
    lock: TradingLock = InMemoryTradingLock(fixedClock()),
    status: MutableReconcilerStatus = MutableReconcilerStatus(),
): ProtectionReconciler {
    return ProtectionReconciler(
        riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
        commandEventLog = eventLog,
        tradingLock = lock,
        status = status,
        clock = fixedClock(),
    )
}

/**
 * ProtectionReconciler test 用の固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

/**
 * ProtectionReconciler test 用の固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
