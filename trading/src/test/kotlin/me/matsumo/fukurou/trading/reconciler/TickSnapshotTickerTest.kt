package me.matsumo.fukurou.trading.reconciler

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun requireExecutionTicker_acceptsRestAgeAndFutureSkewBoundaries() {
        val ageBoundary = fixedInstant().minusSeconds(5)
        val futureSkewBoundary = fixedInstant().plusSeconds(5)
        val ageBoundaryTicker = restTickSnapshot(ageBoundary).requireExecutionTicker(fixedClock())
        val futureBoundaryTicker = restTickSnapshot(futureSkewBoundary).requireExecutionTicker(fixedClock())

        assertEquals(ageBoundary.toString(), ageBoundaryTicker.timestamp)
        assertEquals(futureSkewBoundary.toString(), futureBoundaryTicker.timestamp)
    }

    @Test
    fun requireExecutionTicker_rejectsStaleMissingAndExcessivelyFutureRestTimestamps() {
        val rejectedSourceTimestamps = listOf(
            fixedInstant().minusMillis(5_001),
            null,
            fixedInstant().plusMillis(5_001),
        )

        rejectedSourceTimestamps.forEach { sourceTimestamp ->
            assertFailsWith<IllegalArgumentException> {
                restTickSnapshot(sourceTimestamp).requireExecutionTicker(fixedClock())
            }
        }
    }

    @Test
    fun requireExecutionTicker_doesNotApplyRestFreshnessGateToRealtimeCausalEvent() {
        val causalSourceTimestamp = fixedInstant().minusSeconds(30)
        val ticker = restTickSnapshot(causalSourceTimestamp).copy(
            source = TickSnapshotSource.REALTIME_MARKET_EVENT,
        ).requireExecutionTicker(fixedClock())

        assertEquals(causalSourceTimestamp.toString(), ticker.timestamp)
    }
}

private fun restTickSnapshot(sourceTimestamp: Instant?): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "100",
        bidPrice = "99",
        askPrice = "101",
        sourceTimestamp = sourceTimestamp,
        source = TickSnapshotSource.GMO_PUBLIC_REST,
    )
}

private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}
