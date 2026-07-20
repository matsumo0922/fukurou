package me.matsumo.fukurou.trading.evaluation

import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.TradingMode
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerScoreBenchmarkTest {
    @Test
    fun openBtcLossIncludesUnrealizedMoveAndExitFee() {
        val fixture = fixture(prices = List(89) { "1000" } + "500")
        val result = OwnerScoreMath.benchmark(fixture.request)

        assertEquals(BigDecimal("999.5000000000"), result.points.first().botLiquidationEquityJpy)
        assertEquals(BigDecimal("499.7500000000"), result.points.last().botLiquidationEquityJpy)
        assertTrue(requireNotNull(result.returns).botReturn < BigDecimal.ZERO)
    }

    @Test
    fun constantPriceBuyAndHoldKeepsEntryAndExitFeeWithCommonStartingCapital() {
        val fixture = fixture(prices = List(90) { "1000" })
        val result = OwnerScoreMath.benchmark(fixture.request)
        val returns = assertNotNull(result.returns)

        assertEquals(BigDecimal.ZERO.setScale(10), returns.botReturn)
        assertTrue(returns.buyAndHoldReturn < BigDecimal.ZERO)
        assertEquals(result.commonStartingCapitalJpy, result.points.first().cashEquityJpy)
    }

    @Test
    fun missingExpectedCandleRemainsUnknownWithoutUsingOlderCandle() {
        val fixture = fixture(prices = List(90) { "1000" })
        val missingSlot = fixture.window.expectedCloseSlots[40]
        val older = candle(fixture.window.fromInclusive, "900")
        val request = fixture.request.copy(
            candles = fixture.request.candles.filterNot { candle -> candle.openTime == missingSlot.minus(Duration.ofDays(1)).toString() } + older,
        )
        val result = OwnerScoreMath.benchmark(request)

        assertEquals(90, result.points.size)
        assertEquals(OwnerScoreDayState.UNKNOWN, result.points[40].state)
        assertTrue(OwnerScoreUnknownReason.MISSING_CANDLE in result.points[40].reasons)
        assertEquals(89, result.coverage.validDays)
    }

    @Test
    fun cutoffBeforeSixAndSnapshotCarryForwardUseExpectedBoundaries() {
        val cutoff = Instant.parse("2026-07-01T15:30:00Z") // 2026-07-02 00:30 JST
        val fixture = fixture(cutoff = cutoff, prices = List(90) { "1000" })
        val changeAt = fixture.window.expectedCloseSlots[10].plusSeconds(60)
        val changed = snapshot(fixture.epochId, changeAt, cash = "100", btc = "1")
        val result = OwnerScoreMath.benchmark(fixture.request.copy(snapshots = fixture.request.snapshots + changed))

        assertEquals(Instant.parse("2026-06-30T21:00:00Z"), result.window.lastCloseAt)
        assertEquals(BigDecimal("999.5000000000"), result.points[10].botLiquidationEquityJpy)
        assertEquals(BigDecimal("1099.5000000000"), result.points[11].botLiquidationEquityJpy)
    }

    @Test
    fun shortGapStaysValidButMaterialGapBecomesUnknown() {
        val fixture = fixture(prices = List(90) { "1000" })
        val shortSlot = fixture.window.expectedCloseSlots[20]
        val materialSlot = fixture.window.expectedCloseSlots[21]
        val gaps = listOf(
            gap(shortSlot.minusSeconds(1_800), shortSlot),
            gap(materialSlot.minusSeconds(3_600), materialSlot),
        )
        val result = OwnerScoreMath.benchmark(fixture.request.copy(marketDataGaps = gaps))

        assertEquals(OwnerScoreDayState.VALID, result.points[20].state)
        assertEquals(1_800, result.points[20].gapSeconds)
        assertEquals(OwnerScoreDayState.UNKNOWN, result.points[21].state)
        assertEquals(1, result.coverage.gapDays)
    }

    @Test
    fun eightyOneValidDaysNeedBothValidBoundaries() {
        val fixture = fixture(prices = List(90) { "1000" })
        val internalGaps = (1..9).map { index ->
            val slot = fixture.window.expectedCloseSlots[index]
            gap(slot.minusSeconds(3_600), slot)
        }
        val conclusive = OwnerScoreMath.benchmark(fixture.request.copy(marketDataGaps = internalGaps))
        val boundaryGap = gap(fixture.window.firstCloseAt.minusSeconds(3_600), fixture.window.firstCloseAt)
        val inconclusive = OwnerScoreMath.benchmark(
            fixture.request.copy(marketDataGaps = internalGaps.drop(1) + boundaryGap),
        )

        assertEquals(81, conclusive.coverage.validDays)
        assertNotNull(conclusive.ownerScore)
        assertEquals(81, inconclusive.coverage.validDays)
        assertNull(inconclusive.ownerScore)
        assertNull(inconclusive.winner)
    }

    private fun fixture(cutoff: Instant = Instant.parse("2026-07-01T21:00:00Z"), prices: List<String>): Fixture {
        val window = OwnerScoreWindow.fromCutoff(cutoff, ZoneId.of("Asia/Tokyo"))
        val epochId = UUID.fromString("00000000-0000-0000-0000-000000000197")
        val candles = window.expectedCloseSlots.zip(prices).map { (slot, price) -> candle(slot, price) }
        val initialSnapshot = snapshot(epochId, window.fromInclusive, cash = "0", btc = "1")
        return Fixture(
            epochId = epochId,
            window = window,
            request = OwnerScoreCalculationRequest(
                cutoff = cutoff,
                cutoffMode = OwnerScoreCutoffMode.FIXED_CUTOFF,
                accountEpochId = epochId,
                accountEpochStartedAt = window.fromInclusive,
                candles = candles,
                snapshots = listOf(initialSnapshot),
                marketDataGaps = emptyList(),
                zoneId = ZoneId.of("Asia/Tokyo"),
            ),
        )
    }
}

private data class Fixture(
    val epochId: UUID,
    val window: OwnerScoreWindow,
    val request: OwnerScoreCalculationRequest,
)

private fun candle(closeAt: Instant, price: String): Candle = Candle(
    symbol = "BTC",
    interval = CandleInterval.ONE_DAY,
    openTime = closeAt.minus(Duration.ofDays(1)).toString(),
    open = price,
    high = price,
    low = price,
    close = price,
    volume = "1",
)

private fun snapshot(
    epochId: UUID,
    capturedAt: Instant,
    cash: String,
    btc: String,
) = EquitySnapshotRecord(
    id = UUID.randomUUID(),
    mode = TradingMode.PAPER,
    reason = EquitySnapshotReason.EPOCH_START,
    tradingDate = LocalDate.ofInstant(capturedAt, ZoneId.of("Asia/Tokyo")),
    capturedAt = capturedAt,
    cashJpy = BigDecimal(cash),
    btcQuantity = BigDecimal(btc),
    btcMarkPriceJpy = BigDecimal("1000"),
    totalEquityJpy = BigDecimal("1000"),
    equityPeakJpy = BigDecimal("1000"),
    drawdownRatio = BigDecimal.ZERO,
    accountEpochId = epochId,
)

private fun gap(startedAt: Instant, recoveredAt: Instant) = OwnerScoreMarketDataGap(
    id = UUID.randomUUID(),
    startedAt = startedAt,
    recoveredAt = recoveredAt,
)
