package me.matsumo.fukurou.mcp

import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradeSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * MCP server の最小 contract を検証するテスト。
 */
class FukurouMcpServerTest {

    @Test
    fun constructor_acceptsInjectedMarketDataSource() {
        val server = FukurouMcpServer(
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        )

        assertNotNull(server)
    }

    @Test
    fun tradingRuntimeFactory_failsClosedWhenDatabaseEnvironmentIsMissing() {
        assertFailsWith<IllegalArgumentException> {
            TradingRuntimeFactory.fromEnvironment(environment = emptyMap())
        }
    }
}

/**
 * MCP server unit test 用の fake market data source。
 */
private object FakeMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.success(
            Ticker(
                symbol = symbol.apiSymbol,
                last = "100",
                bid = "99",
                ask = "101",
                high = "110",
                low = "90",
                volume = "1.0",
                timestamp = "2026-07-01T00:00:00Z",
            ),
        )
    }

    override suspend fun getRecentTrades(symbol: TradingSymbol): Result<List<RecentTrade>> {
        return Result.success(
            listOf(
                RecentTrade(
                    symbol = symbol.apiSymbol,
                    price = "100",
                    size = "0.01",
                    side = TradeSide.BUY,
                    timestamp = "2026-07-01T00:00:00Z",
                ),
            ),
        )
    }
}
