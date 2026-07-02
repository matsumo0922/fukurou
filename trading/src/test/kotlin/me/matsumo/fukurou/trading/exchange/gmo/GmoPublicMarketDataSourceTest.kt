package me.matsumo.fukurou.trading.exchange.gmo

import me.matsumo.fukurou.trading.domain.TradeSide
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

    @Test
    fun parseTradesResponse_returnsRecentTrades() {
        val trades = parseTradesResponse(TRADES_SUCCESS_RESPONSE, TradingSymbol.BTC)
        val firstTrade = trades.first()

        assertEquals(2, trades.size)
        assertEquals("BTC", firstTrade.symbol)
        assertEquals("9748750", firstTrade.price)
        assertEquals("0.01", firstTrade.size)
        assertEquals(TradeSide.BUY, firstTrade.side)
        assertEquals("2026-07-01T16:26:58.000Z", firstTrade.timestamp)
    }

    @Test
    fun parseTradesResponse_rejectsNonZeroStatus() {
        assertFailsWith<IllegalArgumentException> {
            parseTradesResponse(TRADES_ERROR_RESPONSE, TradingSymbol.BTC)
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

private const val TRADES_SUCCESS_RESPONSE = """
{
  "status": 0,
  "data": {
    "list": [
      {
        "price": "9748750",
        "size": "0.01",
        "side": "BUY",
        "timestamp": "2026-07-01T16:26:58.000Z"
      },
      {
        "price": "9748740",
        "size": "0.02",
        "side": "SELL",
        "timestamp": "2026-07-01T16:26:59.000Z"
      }
    ]
  },
  "responsetime": "2026-07-01T16:27:00.000Z"
}
"""

private const val TRADES_ERROR_RESPONSE = """
{
  "status": 1,
  "data": {
    "list": []
  }
}
"""
