package me.matsumo.fukurou.trading.reconciler

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * TickSnapshot から ticker への変換を検証するテスト。
 */
class TickSnapshotTickerTest {

    @Test
    fun require_ticker_rejects_snapshot_without_any_price() {
        val tickSnapshot = TickSnapshot(
            symbol = "BTC",
            observedAt = Instant.parse("2026-07-02T00:00:00Z"),
            lastPrice = null,
            bidPrice = null,
            askPrice = null,
        )

        assertFailsWith<IllegalStateException> {
            tickSnapshot.requireTicker()
        }
    }
}
