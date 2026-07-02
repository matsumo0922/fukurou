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

private fun candle(
    openTime: String,
    high: Double,
    low: Double,
    close: Double,
): Candle {
    return Candle(
        symbol = TRADING_SYMBOL_TEXT,
        interval = CandleInterval.FIVE_MINUTES,
        openTime = openTime,
        open = close.toString(),
        high = high.toString(),
        low = low.toString(),
        close = close.toString(),
        volume = "1.0",
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
