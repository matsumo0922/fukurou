package me.matsumo.fukurou.mcp

import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import kotlin.test.Test
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
}
