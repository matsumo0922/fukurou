package me.matsumo.fukurou

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ProtectionReconciler
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Ktor backend worker が ProtectionReconciler loop を起動することを検証するテスト。
 */
class ProtectionReconcilerWorkerTest {

    @Test
    fun worker_starts_reconciler_loop() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC)
        val status = MutableReconcilerStatus()
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = clock),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = InMemoryTradingLock(clock),
            status = status,
            clock = clock,
        )
        val worker = ProtectionReconcilerWorker(
            reconciler = reconciler,
            interval = Duration.ofMillis(10),
        )

        try {
            worker.start()
            withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
                while (status.snapshot().lastReconciledAt == null) {
                    delay(10.toDuration(DurationUnit.MILLISECONDS))
                }
            }
        } finally {
            worker.close()
        }

        assertNotNull(status.snapshot().lastReconciledAt)
        assertTrue(status.snapshot().startupFullReconcileCompleted)
    }

    @Test
    fun worker_retries_bootstrap_before_reconciler_loop() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC)
        val status = MutableReconcilerStatus()
        val attempts = AtomicInteger(0)
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = clock),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = InMemoryTradingLock(clock),
            status = status,
            clock = clock,
        )
        val worker = ProtectionReconcilerWorker(
            reconciler = reconciler,
            interval = Duration.ofMillis(10),
            bootstrap = {
                if (attempts.incrementAndGet() == 1) {
                    Result.failure(IllegalStateException("database is not ready"))
                } else {
                    Result.success(Unit)
                }
            },
        )

        try {
            worker.start()
            withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
                while (status.snapshot().lastReconciledAt == null) {
                    delay(10.toDuration(DurationUnit.MILLISECONDS))
                }
            }
        } finally {
            worker.close()
        }

        assertTrue(attempts.get() >= 2)
        assertTrue(status.snapshot().startupFullReconcileCompleted)
    }
}
