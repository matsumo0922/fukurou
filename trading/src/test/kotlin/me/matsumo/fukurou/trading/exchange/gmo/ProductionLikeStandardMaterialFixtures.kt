package me.matsumo.fukurou.trading.exchange.gmo

import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource

/** Secret を含まない synthetic GMO response を production parser に通す standard material fixture。 */
object ProductionLikeStandardMaterialMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> = runCatching {
        parseTickerResponse(TICKER_RESPONSE, symbol)
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> = runCatching {
        parseKlinesResponse(klinesResponse(limit), symbol, interval)
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> = runCatching {
        parseOrderbookResponse(ORDERBOOK_RESPONSE, symbol, depth)
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> =
        Result.success(emptyList())

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> = Result.success(
        SymbolRules(
            symbol = symbol.apiSymbol,
            minOrderSize = "0.0001",
            sizeStep = "0.0001",
            tickSize = "1",
            takerFee = "0.0005",
            makerFee = "-0.0001",
        ),
    )
}

private fun klinesResponse(limit: Int): String {
    val rows = (0 until limit).joinToString(",") { index ->
        val minute = (index % 12) * 5
        val hour = index / 12
        val timestamp = "2026-07-01T${hour.toString().padStart(2, '0')}:" +
            "${minute.toString().padStart(2, '0')}:00Z"

        """
            {
              "openTime":"$timestamp",
              "open":"9748000",
              "high":"9751000",
              "low":"9746000",
              "close":"9748750",
              "volume":"1.5"
            }
        """.trimIndent()
    }

    return """{"status":0,"data":[$rows]}"""
}

private const val TICKER_RESPONSE = """
{
  "status": 0,
  "data": [{
    "ask":"9749399",
    "bid":"9747754",
    "high":"9820000",
    "last":"9748750",
    "low":"9400004",
    "symbol":"BTC",
    "timestamp":"2026-07-01T16:26:57.323Z",
    "volume":"210.82216"
  }]
}
"""

private const val ORDERBOOK_RESPONSE = """
{
  "status": 0,
  "data": {
    "symbol":"BTC",
    "bids":[
      {"price":"9748000","size":"0.2"},
      {"price":"9747000","size":"0.3"}
    ],
    "asks":[
      {"price":"9749000","size":"0.1"},
      {"price":"9750000","size":"0.4"}
    ]
  }
}
"""
