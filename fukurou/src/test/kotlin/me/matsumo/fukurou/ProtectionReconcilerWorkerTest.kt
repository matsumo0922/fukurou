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
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
            withTimeout(500) {
                while (status.snapshot().lastReconciledAt == null) {
                    delay(10)
                }
            }
        } finally {
            worker.close()
        }

        assertNotNull(status.snapshot().lastReconciledAt)
        assertTrue(status.snapshot().startupFullReconcileCompleted)
    }
}
