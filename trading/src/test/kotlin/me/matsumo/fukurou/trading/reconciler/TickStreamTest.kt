package me.matsumo.fukurou.trading.reconciler

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradeSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TickStream の REST polling contract を検証するテスト。
 */
class TickStreamTest {

    @Test
    fun rest_polling_tick_stream_reads_ticker_and_recent_trades() = runBlocking {
        val marketDataSource = RecordingMarketDataSource()
        val tickStream = RestPollingTickStream(
            marketDataSource = marketDataSource,
            clock = fixedClock(),
        )

        val tickSnapshot = tickStream.latestTick().getOrThrow()

        requireNotNull(tickSnapshot)
        assertEquals(1, marketDataSource.tickerCallCount)
        assertEquals(1, marketDataSource.tradesCallCount)
        assertEquals("BTC", tickSnapshot.symbol)
        assertEquals("100", tickSnapshot.lastPrice)
        assertEquals(2, tickSnapshot.recentTradeCount)
        assertEquals(fixedInstant(), tickSnapshot.observedAt)
    }
}

/**
 * 呼び出し回数を記録する market data source。
 */
private class RecordingMarketDataSource : MarketDataSource {

    /**
     * ticker 呼び出し回数。
     */
    var tickerCallCount: Int = 0
        private set

    /**
     * trades 呼び出し回数。
     */
    var tradesCallCount: Int = 0
        private set

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        tickerCallCount += 1

        return Result.success(
            Ticker(
                symbol = symbol.apiSymbol,
                last = "100",
                bid = "99",
                ask = "101",
                high = "110",
                low = "90",
                volume = "1.0",
                timestamp = "2026-07-02T00:00:00Z",
            ),
        )
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(emptyList())
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(
            Orderbook(
                symbol = symbol.apiSymbol,
                bids = listOf(OrderbookLevel("99", "0.1")),
                asks = listOf(OrderbookLevel("101", "0.1")),
            ),
        )
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        tradesCallCount += 1

        return Result.success(
            listOf(
                createRecentTrade(symbol, "100"),
                createRecentTrade(symbol, "101"),
            ),
        )
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.success(
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
}

/**
 * TickStream test 用の recent trade を作る。
 */
private fun createRecentTrade(symbol: TradingSymbol, price: String): RecentTrade {
    return RecentTrade(
        symbol = symbol.apiSymbol,
        price = price,
        size = "0.01",
        side = TradeSide.BUY,
        timestamp = "2026-07-02T00:00:00Z",
    )
}

/**
 * TickStream test 用の固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

/**
 * TickStream test 用の固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
