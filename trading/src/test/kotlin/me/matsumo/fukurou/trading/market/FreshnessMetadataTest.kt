package me.matsumo.fukurou.trading.market

import me.matsumo.fukurou.trading.domain.CandleInterval
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FreshnessMetadata builder の鮮度計算を検証するテスト。
 */
class FreshnessMetadataTest {

    @Test
    fun build_usesSourceTimestampForStaleness() {
        val metadata = FreshnessMetadata.build(
            clock = fixedClock(),
            sourceTimestamp = fixedInstant().minusSeconds(3),
            staleAfter = FreshnessDefaults.tickerStaleAfter,
            source = FreshnessSource.GMO_PUBLIC_REST,
        )

        assertEquals(fixedInstant().toString(), metadata.fetchedAt)
        assertEquals(fixedInstant().minusSeconds(3).toString(), metadata.sourceTimestamp)
        assertEquals(3_000L, metadata.stalenessMs)
        assertEquals(5_000L, metadata.staleAfterMs)
        assertFalse(metadata.stale)
        assertEquals(FreshnessSource.GMO_PUBLIC_REST, metadata.source)
    }

    @Test
    fun build_usesFetchedAtWhenSourceTimestampIsMissing() {
        val metadata = FreshnessMetadata.build(
            clock = fixedClock(),
            sourceTimestamp = null,
            staleAfter = FreshnessDefaults.orderbookStaleAfter,
            source = FreshnessSource.GMO_PUBLIC_REST,
        )

        assertEquals(fixedInstant().toString(), metadata.fetchedAt)
        assertNull(metadata.sourceTimestamp)
        assertEquals(0L, metadata.stalenessMs)
        assertEquals(3_000L, metadata.staleAfterMs)
        assertFalse(metadata.stale)
    }

    @Test
    fun build_treatsExactThresholdAsFreshAndExceededThresholdAsStale() {
        val exactBoundary = FreshnessMetadata.build(
            clock = fixedClock(),
            sourceTimestamp = fixedInstant().minusSeconds(5),
            staleAfter = FreshnessDefaults.tickerStaleAfter,
            source = FreshnessSource.GMO_PUBLIC_REST,
        )
        val exceededBoundary = FreshnessMetadata.build(
            clock = fixedClock(),
            sourceTimestamp = fixedInstant().minusMillis(5_001),
            staleAfter = FreshnessDefaults.tickerStaleAfter,
            source = FreshnessSource.GMO_PUBLIC_REST,
        )

        assertFalse(exactBoundary.stale)
        assertTrue(exceededBoundary.stale)
    }

    @Test
    fun build_clampsFutureSourceTimestampStalenessToZero() {
        val metadata = FreshnessMetadata.build(
            clock = fixedClock(),
            sourceTimestamp = fixedInstant().plusSeconds(3),
            staleAfter = FreshnessDefaults.tickerStaleAfter,
            source = FreshnessSource.GMO_PUBLIC_REST,
        )

        assertEquals(0L, metadata.stalenessMs)
        assertFalse(metadata.stale)
    }

    @Test
    fun candleStaleAfter_usesIntervalDurationAndGrace() {
        assertEquals(
            Duration.ofMinutes(5).plusSeconds(90),
            FreshnessDefaults.candleStaleAfter(CandleInterval.FIVE_MINUTES),
        )
        assertEquals(
            Duration.ofHours(1).plusMinutes(5),
            FreshnessDefaults.candleStaleAfter(CandleInterval.ONE_HOUR),
        )
    }
}

private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-04T12:00:00Z")
}
