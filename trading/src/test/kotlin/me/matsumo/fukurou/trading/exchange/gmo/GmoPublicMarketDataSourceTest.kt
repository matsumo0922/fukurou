package me.matsumo.fukurou.trading.exchange.gmo

import me.matsumo.fukurou.trading.domain.TradingSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * GMO Public ticker parser の挙動を検証するテスト。
 */
class GmoPublicMarketDataSourceTest {

    @Test
    fun parseTickerResponse_returnsTicker() {
        val ticker = parseTickerResponse(SUCCESS_RESPONSE, TradingSymbol.BTC)

        assertEquals("BTC", ticker.symbol)
        assertEquals("9748750", ticker.last)
        assertEquals("9747754", ticker.bid)
        assertEquals("9749399", ticker.ask)
        assertEquals("9820000", ticker.high)
        assertEquals("9400004", ticker.low)
        assertEquals("210.82216", ticker.volume)
        assertEquals("2026-07-01T16:26:57.323Z", ticker.timestamp)
    }

    @Test
    fun parseTickerResponse_rejectsMissingSymbol() {
        assertFailsWith<IllegalArgumentException> {
            parseTickerResponse(MISSING_SYMBOL_RESPONSE, TradingSymbol.BTC)
        }
    }

    @Test
    fun parseTickerResponse_rejectsNonZeroStatus() {
        assertFailsWith<IllegalArgumentException> {
            parseTickerResponse(ERROR_RESPONSE, TradingSymbol.BTC)
        }
    }
}

private const val SUCCESS_RESPONSE = """
{
  "status": 0,
  "data": [
    {
      "ask": "9749399",
      "bid": "9747754",
      "high": "9820000",
      "last": "9748750",
      "low": "9400004",
      "symbol": "BTC",
      "timestamp": "2026-07-01T16:26:57.323Z",
      "volume": "210.82216"
    }
  ],
  "responsetime": "2026-07-01T16:26:57.410Z"
}
"""

private const val MISSING_SYMBOL_RESPONSE = """
{
  "status": 0,
  "data": []
}
"""

private const val ERROR_RESPONSE = """
{
  "status": 1,
  "data": []
}
"""
