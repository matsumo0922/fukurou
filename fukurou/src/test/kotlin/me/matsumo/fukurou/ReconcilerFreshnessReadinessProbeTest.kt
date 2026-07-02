package me.matsumo.fukurou

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ReconcilerFreshnessReadinessProbe の freshness 判定を検証するテスト。
 */
class ReconcilerFreshnessReadinessProbeTest {

    @Test
    fun ready_when_dependency_ready_and_reconciler_fresh() = runBlocking {
        val status = MutableReconcilerStatus()
        status.markReconciled(
            reconciledAt = Instant.parse("2026-07-02T00:00:00Z"),
            startupFullReconcileCompleted = true,
            lastMarketDataAt = Instant.parse("2026-07-02T00:00:00Z"),
        )
        val probe = createProbe(status)

        assertTrue(probe.isReady())
    }

    @Test
    fun not_ready_when_reconciler_is_stale() = runBlocking {
        val status = MutableReconcilerStatus()
        status.markReconciled(
            reconciledAt = Instant.parse("2026-07-01T23:59:00Z"),
            startupFullReconcileCompleted = true,
            lastMarketDataAt = Instant.parse("2026-07-02T00:00:00Z"),
        )
        val probe = createProbe(status)

        assertFalse(probe.isReady())
    }

    @Test
    fun not_ready_when_market_data_is_stale() = runBlocking {
        val status = MutableReconcilerStatus()
        status.markReconciled(
            reconciledAt = Instant.parse("2026-07-02T00:00:00Z"),
            startupFullReconcileCompleted = true,
            lastMarketDataAt = Instant.parse("2026-07-01T23:59:00Z"),
        )
        val probe = createProbe(status)

        assertFalse(probe.isReady())
    }
}

/**
 * freshness test 用の probe を作る。
 */
private fun createProbe(status: MutableReconcilerStatus): ReconcilerFreshnessReadinessProbe {
    return ReconcilerFreshnessReadinessProbe(
        delegate = ReadinessProbe { true },
        reconcilerStatusProvider = status,
        clock = Clock.fixed(Instant.parse("2026-07-02T00:00:10Z"), ZoneOffset.UTC),
        staleAfter = Duration.ofSeconds(30),
    )
}
