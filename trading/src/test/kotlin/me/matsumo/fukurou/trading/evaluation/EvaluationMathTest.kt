package me.matsumo.fukurou.trading.evaluation

import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * EvaluationMath の数式 contract を検証するテスト。
 */
class EvaluationMathTest {

    @Test
    fun summarizeTrades_calculatesPfWinRateAndExpectedR() {
        val stats = EvaluationMath.summarizeTrades(
            listOf(
                trade("20", setupTags = listOf("breakout")),
                trade("-10", setupTags = listOf("breakout")),
                trade("0", setupTags = listOf("breakout")),
            ),
        )

        assertEquals(3, stats.tradeCount)
        assertEquals("10.0000000000", stats.totalPnlJpy.toPlainString())
        assertEquals("2.0000000000", stats.profitFactor?.toPlainString())
        assertEquals("0.3333333333", stats.winRate?.toPlainString())
        assertEquals("0.3333333333", stats.expectedR?.toPlainString())
    }

    @Test
    fun summarizeTrades_returnsNullProfitFactorWhenNoLosingTrade() {
        val stats = EvaluationMath.summarizeTrades(
            listOf(
                trade("10"),
                trade("0"),
            ),
        )

        assertNull(stats.profitFactor)
        assertEquals("0.5000000000", stats.winRate?.toPlainString())
    }

    @Test
    fun evaluateTrade_calculatesMaeMfeAndTracksUnavailableCounts() {
        val normal = trade(
            pnlJpy = "20",
            highestPriceSinceEntryJpy = BigDecimal("130"),
            lowestPriceSinceEntryJpy = BigDecimal("95"),
        )
        val noLowest = trade(
            pnlJpy = "20",
            highestPriceSinceEntryJpy = BigDecimal("130"),
            lowestPriceSinceEntryJpy = null,
        )
        val invalidRisk = trade(
            pnlJpy = "20",
            initialProtectiveStopPriceJpy = BigDecimal("100"),
            highestPriceSinceEntryJpy = BigDecimal("130"),
            lowestPriceSinceEntryJpy = BigDecimal("95"),
        )
        val normalTrade = EvaluationMath.evaluateTrade(normal)
        val stats = EvaluationMath.summarizeTrades(listOf(normal, noLowest, invalidRisk))

        assertEquals("2.0000000000", normalTrade.realizedR?.toPlainString())
        assertEquals("0.5000000000", normalTrade.maeR?.toPlainString())
        assertEquals("3.0000000000", normalTrade.mfeR?.toPlainString())
        assertEquals(1, stats.rUnavailableCount)
        assertEquals(2, stats.maeUnavailableCount)
        assertEquals(1, stats.mfeUnavailableCount)
    }

    @Test
    fun calibrationBinsHandlesEdgesAndDuplicateSetupTags() {
        val calibration = EvaluationMath.calibrationBySetup(
            listOf(
                trade("10", probability = BigDecimal("0.0"), setupTags = listOf("breakout", "trend")),
                trade("-10", probability = BigDecimal("0.95"), setupTags = listOf("breakout")),
                trade("10", probability = BigDecimal("1.0"), setupTags = listOf("breakout")),
            ),
        )
        val breakout = calibration.single { group -> group.groupKey == "breakout" }
        val zeroBin = breakout.bins[0]
        val topBin = breakout.bins[9]
        val trend = calibration.single { group -> group.groupKey == "trend" }

        assertEquals(1, zeroBin.tradeCount)
        assertEquals("0.0000000000", zeroBin.averageEstimatedProbability?.toPlainString())
        assertEquals(2, topBin.tradeCount)
        assertEquals("0.5000000000", topBin.realizedWinRate?.toPlainString())
        assertEquals(1, trend.bins[0].tradeCount)
    }

    @Test
    fun benchmarkCalculatesBuyHoldNoTradeAndBotSeries() {
        val result = EvaluationMath.benchmark(
            candles = listOf(
                dailyCandle("2026-07-01", "100"),
                dailyCandle("2026-07-02", "110"),
                dailyCandle("2026-07-03", "120"),
            ),
            dailyPnlFacts = listOf(
                DailyTradePnlFact(
                    closedAt = Instant.parse("2026-07-02T12:00:00Z"),
                    pnlJpy = BigDecimal("10"),
                ),
            ),
            baselineEquityJpy = BigDecimal("1000"),
            fromDate = LocalDate.parse("2026-07-01"),
            toDateInclusive = LocalDate.parse("2026-07-03"),
            zoneId = ZoneId.of("Asia/Tokyo"),
        )

        assertEquals(3, result.points.size)
        assertEquals("1200.0000000000", result.points.last().buyAndHoldEquityJpy.toPlainString())
        assertEquals("1000.0000000000", result.points.last().noTradeEquityJpy.toPlainString())
        assertEquals("1010.0000000000", result.points.last().botEquityJpy.toPlainString())
        assertEquals("0.2000000000", result.buyAndHoldReturn?.toPlainString())
        assertEquals("0.0000000000", result.noTradeReturn?.toPlainString())
        assertEquals("0.0100000000", result.botReturn?.toPlainString())
    }

    @Test
    fun classifyMarketRegimesProducesTrendVolatilityAndUnknown() {
        val candles = (1..25).map { day ->
            val close = BigDecimal("100").add(BigDecimal(day))
            val range = if (day <= 14) BigDecimal("2") else BigDecimal("8")

            dailyCandle(
                date = "2026-07-${day.toString().padStart(2, '0')}",
                close = close.toPlainString(),
                high = close.add(range).toPlainString(),
                low = close.subtract(range).toPlainString(),
            )
        }
        val labels = EvaluationMath.classifyMarketRegimes(candles, ZoneId.of("Asia/Tokyo"))

        assertEquals(TrendRegime.UNKNOWN, labels.first().trend)
        assertEquals(VolatilityRegime.UNKNOWN, labels.first().volatility)
        assertEquals(TrendRegime.TREND_UP, labels.last().trend)
        assertEquals(VolatilityRegime.HIGH_VOL, labels.last().volatility)
    }
}

private fun trade(
    pnlJpy: String,
    probability: BigDecimal = BigDecimal("0.50"),
    setupTags: List<String> = listOf("setup"),
    initialProtectiveStopPriceJpy: BigDecimal? = BigDecimal("90"),
    highestPriceSinceEntryJpy: BigDecimal = BigDecimal("120"),
    lowestPriceSinceEntryJpy: BigDecimal? = BigDecimal("90"),
): ClosedTradeFact {
    return ClosedTradeFact(
        positionId = UUID.randomUUID(),
        openedAt = Instant.parse("2026-07-01T00:00:00Z"),
        closedAt = Instant.parse("2026-07-02T00:00:00Z"),
        sizeBtc = BigDecimal.ONE,
        averageEntryPriceJpy = BigDecimal("100"),
        initialProtectiveStopPriceJpy = initialProtectiveStopPriceJpy,
        highestPriceSinceEntryJpy = highestPriceSinceEntryJpy,
        lowestPriceSinceEntryJpy = lowestPriceSinceEntryJpy,
        tradePnlJpy = BigDecimal(pnlJpy),
        estimatedWinProbability = probability,
        setupTags = setupTags,
        llmProvider = "claude",
    )
}

private fun dailyCandle(
    date: String,
    close: String,
    high: String = close,
    low: String = close,
): Candle {
    return Candle(
        symbol = "BTC",
        interval = CandleInterval.ONE_DAY,
        openTime = "${date}T00:00:00Z",
        open = close,
        high = high,
        low = low,
        close = close,
        volume = "1.0",
    )
}
