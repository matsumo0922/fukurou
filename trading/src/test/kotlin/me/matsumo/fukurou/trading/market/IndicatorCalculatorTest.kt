package me.matsumo.fukurou.trading.market

import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * IndicatorCalculator の既知値計算を検証するテスト。
 */
class IndicatorCalculatorTest {

    @Test
    fun calculate_returnsAtrWithWilderSmoothing() {
        val result = IndicatorCalculator.calculate(
            candles = atrCandles(),
            indicator = IndicatorType.ATR,
            params = IndicatorParams(period = 3),
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertEquals(listOf(null, null), values.take(2))
        assertClose(2.6666666667, values[2])
        assertClose(3.1111111111, values[3])
        assertClose(2.7407407407, values[4])
    }

    @Test
    fun calculate_returnsEma() {
        val result = IndicatorCalculator.calculate(
            candles = closeCandles(1.0, 2.0, 3.0, 4.0, 5.0),
            indicator = IndicatorType.EMA,
            params = IndicatorParams(period = 3),
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertEquals(listOf(null, null), values.take(2))
        assertClose(2.0, values[2])
        assertClose(3.0, values[3])
        assertClose(4.0, values[4])
    }

    @Test
    fun calculate_returnsRsi() {
        val result = IndicatorCalculator.calculate(
            candles = closeCandles(1.0, 2.0, 3.0, 2.0, 4.0, 5.0),
            indicator = IndicatorType.RSI,
            params = IndicatorParams(period = 3),
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertEquals(listOf(null, null, null), values.take(3))
        assertClose(66.6666666667, values[3])
        assertClose(83.3333333333, values[4])
        assertClose(87.8787878788, values[5])
    }

    @Test
    fun calculate_returnsSma() {
        val result = IndicatorCalculator.calculate(
            candles = closeCandles(1.0, 2.0, 3.0, 4.0, 5.0),
            indicator = IndicatorType.SMA,
            params = IndicatorParams(period = 3),
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertEquals(listOf(null, null), values.take(2))
        assertClose(2.0, values[2])
        assertClose(3.0, values[3])
        assertClose(4.0, values[4])
    }

    @Test
    fun calculate_returnsMacd() {
        val result = IndicatorCalculator.calculate(
            candles = closeCandles(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
            indicator = IndicatorType.MACD,
            params = IndicatorParams(
                fastPeriod = 2,
                slowPeriod = 3,
                signalPeriod = 2,
            ),
        ).getOrThrow()

        val values = result.values

        assertClose(0.5, values[2].macd)
        assertClose(0.5, values[3].signal)
        assertClose(0.0, values[3].histogram)
        assertClose(0.0, values[5].histogram)
    }

    @Test
    fun calculate_returnsAtrPercentileWithFullLookbackWindow() {
        val result = IndicatorCalculator.calculate(
            candles = trueRangeCandles(2.0, 4.0, 6.0, 2.0, 8.0, 0.0),
            indicator = IndicatorType.ATR_PERCENTILE,
            params = IndicatorParams(
                period = 2,
                lookback = 3,
            ),
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertEquals(listOf(null, null, null), values.take(3))
        assertClose(2.0 / 3.0, values[3])
        assertClose(1.0, values[4])
        assertClose(1.0 / 3.0, values[5])
    }

    @Test
    fun calculate_returnsVwapSessionCumulativeValues() {
        val result = IndicatorCalculator.calculate(
            candles = listOf(
                candle(
                    openTime = "2026-07-01T21:00:00Z",
                    high = 12.0,
                    low = 6.0,
                    close = 9.0,
                    volume = 2.0,
                ),
                candle(
                    openTime = "2026-07-01T21:05:00Z",
                    high = 15.0,
                    low = 9.0,
                    close = 12.0,
                    volume = 1.0,
                ),
                candle(
                    openTime = "2026-07-01T21:10:00Z",
                    high = 18.0,
                    low = 12.0,
                    close = 15.0,
                    volume = 3.0,
                ),
            ),
            indicator = IndicatorType.VWAP_SESSION,
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertClose(9.0, values[0])
        assertClose(10.0, values[1])
        assertClose(12.5, values[2])
    }

    @Test
    fun calculate_resetsVwapSessionAcrossGmoKlineBoundary() {
        val result = IndicatorCalculator.calculate(
            candles = listOf(
                candle(
                    openTime = "2026-07-01T20:55:00Z",
                    high = 9.0,
                    low = 3.0,
                    close = 6.0,
                    volume = 2.0,
                ),
                candle(
                    openTime = "2026-07-01T21:00:00Z",
                    high = 33.0,
                    low = 27.0,
                    close = 30.0,
                    volume = 1.0,
                ),
                candle(
                    openTime = "2026-07-01T21:05:00Z",
                    high = 63.0,
                    low = 57.0,
                    close = 60.0,
                    volume = 3.0,
                ),
            ),
            indicator = IndicatorType.VWAP_SESSION,
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertClose(6.0, values[0])
        assertClose(30.0, values[1])
        assertClose(52.5, values[2])
    }

    @Test
    fun calculate_returnsMissingVwapWhileSessionVolumeIsZero() {
        val result = IndicatorCalculator.calculate(
            candles = listOf(
                candle(
                    openTime = "2026-07-01T21:00:00Z",
                    high = 12.0,
                    low = 6.0,
                    close = 9.0,
                    volume = 0.0,
                ),
                candle(
                    openTime = "2026-07-01T21:05:00Z",
                    high = 15.0,
                    low = 9.0,
                    close = 12.0,
                    volume = 2.0,
                ),
            ),
            indicator = IndicatorType.VWAP_SESSION,
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertEquals(null, values[0])
        assertClose(12.0, values[1])
    }

    @Test
    fun calculate_returnsVolumeZScoreWithPopulationStandardDeviation() {
        val result = IndicatorCalculator.calculate(
            candles = volumeCandles(1.0, 2.0, 3.0, 2.0, 1.0),
            indicator = IndicatorType.VOLUME_Z_SCORE,
            params = IndicatorParams(period = 3),
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertEquals(listOf(null, null), values.take(2))
        assertClose(1.2247448714, values[2])
        assertClose(-0.7071067812, values[3])
        assertClose(-1.2247448714, values[4])
    }

    @Test
    fun calculate_returnsMissingVolumeZScoreWhenStandardDeviationIsZero() {
        val result = IndicatorCalculator.calculate(
            candles = volumeCandles(5.0, 5.0, 5.0),
            indicator = IndicatorType.VOLUME_Z_SCORE,
            params = IndicatorParams(period = 3),
        ).getOrThrow()

        val values = result.values.map { value -> value.value }

        assertEquals(listOf(null, null, null), values)
    }

    @Test
    fun calculate_resolvesNewIndicatorDefaults() {
        val atrPercentile = IndicatorCalculator.calculate(
            candles = closeCandles(1.0),
            indicator = IndicatorType.ATR_PERCENTILE,
        ).getOrThrow()
        val volumeZScore = IndicatorCalculator.calculate(
            candles = closeCandles(1.0),
            indicator = IndicatorType.VOLUME_Z_SCORE,
        ).getOrThrow()

        assertEquals(14, atrPercentile.params.period)
        assertEquals(100, atrPercentile.params.lookback)
        assertEquals(20, volumeZScore.params.period)
    }

    @Test
    fun calculate_rejectsInvalidPeriod() {
        val result = IndicatorCalculator.calculate(
            candles = closeCandles(1.0, 2.0, 3.0),
            indicator = IndicatorType.EMA,
            params = IndicatorParams(period = 0),
        )

        assertFailsWith<MarketInvalidRequestException> {
            result.getOrThrow()
        }
    }

    @Test
    fun calculate_rejectsInvalidLookback() {
        val result = IndicatorCalculator.calculate(
            candles = closeCandles(1.0, 2.0, 3.0),
            indicator = IndicatorType.ATR_PERCENTILE,
            params = IndicatorParams(
                period = 2,
                lookback = 0,
            ),
        )

        assertFailsWith<MarketInvalidRequestException> {
            result.getOrThrow()
        }
    }

    @Test
    fun calculate_rejectsAtrPercentileWindowBeyondMcpCandleLimit() {
        val result = IndicatorCalculator.calculate(
            candles = closeCandles(1.0, 2.0, 3.0),
            indicator = IndicatorType.ATR_PERCENTILE,
            params = IndicatorParams(
                period = 400,
                lookback = 101,
            ),
        )

        assertFailsWith<MarketInvalidRequestException> {
            result.getOrThrow()
        }
    }

    @Test
    fun calculate_rejectsInvalidMacdPeriodOrder() {
        val result = IndicatorCalculator.calculate(
            candles = closeCandles(1.0, 2.0, 3.0, 4.0),
            indicator = IndicatorType.MACD,
            params = IndicatorParams(
                fastPeriod = 3,
                slowPeriod = 2,
                signalPeriod = 2,
            ),
        )

        assertFailsWith<MarketInvalidRequestException> {
            result.getOrThrow()
        }
    }

    @Test
    fun calculate_keepsMarketDataParseExceptionForInvalidCandleNumber() {
        val result = IndicatorCalculator.calculate(
            candles = listOf(invalidNumberCandle()),
            indicator = IndicatorType.EMA,
            params = IndicatorParams(period = 1),
        )

        assertFailsWith<MarketDataParseException> {
            result.getOrThrow()
        }
    }
}

private fun atrCandles(): List<Candle> {
    return listOf(
        candle(openTime = "1", high = 10.0, low = 8.0, close = 9.0),
        candle(openTime = "2", high = 12.0, low = 9.0, close = 11.0),
        candle(openTime = "3", high = 13.0, low = 10.0, close = 12.0),
        candle(openTime = "4", high = 15.0, low = 11.0, close = 14.0),
        candle(openTime = "5", high = 14.0, low = 12.0, close = 13.0),
    )
}

private fun trueRangeCandles(vararg trueRangeValues: Double): List<Candle> {
    return trueRangeValues.mapIndexed { trueRangeIndex, trueRangeValue ->
        candle(
            openTime = trueRangeIndex.toString(),
            high = 100.0 + trueRangeValue / 2.0,
            low = 100.0 - trueRangeValue / 2.0,
            close = 100.0,
        )
    }
}

private fun closeCandles(vararg closes: Double): List<Candle> {
    return closes.mapIndexed { closeIndex, close ->
        candle(
            openTime = closeIndex.toString(),
            high = close + 1.0,
            low = close - 1.0,
            close = close,
        )
    }
}

private fun volumeCandles(vararg volumes: Double): List<Candle> {
    return volumes.mapIndexed { volumeIndex, volume ->
        candle(
            openTime = volumeIndex.toString(),
            high = 11.0,
            low = 9.0,
            close = 10.0,
            volume = volume,
        )
    }
}

private fun candle(
    openTime: String,
    high: Double,
    low: Double,
    close: Double,
    volume: Double = 1.0,
): Candle {
    return Candle(
        symbol = TRADING_SYMBOL_TEXT,
        interval = CandleInterval.FIVE_MINUTES,
        openTime = openTime,
        open = close.toString(),
        high = high.toString(),
        low = low.toString(),
        close = close.toString(),
        volume = volume.toString(),
    )
}

private fun invalidNumberCandle(): Candle {
    return Candle(
        symbol = TRADING_SYMBOL_TEXT,
        interval = CandleInterval.FIVE_MINUTES,
        openTime = "invalid",
        open = "100",
        high = "101",
        low = "99",
        close = "not-a-number",
        volume = "1.0",
    )
}

private fun assertClose(expected: Double, actual: Double?) {
    requireNotNull(actual)

    assertTrue(abs(expected - actual) < 0.0000001, "expected <$expected>, actual <$actual>")
}

/**
 * indicator test 用 symbol 文字列。
 */
private const val TRADING_SYMBOL_TEXT = "BTC"
