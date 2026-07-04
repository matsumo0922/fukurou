package me.matsumo.fukurou.mcp.gmo

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
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
import me.matsumo.fukurou.trading.market.MarketInvalidRequestException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GMO Coin MCP module の最小 contract を検証するテスト。
 */
class GmoCoinMcpServerTest {

    @Test
    fun registerGmoCoinMarketTools_exposesMarketToolsOnly() {
        val server = testServer()

        server.registerGmoCoinMarketTools(FakeMarketDataSource)

        assertEquals(
            setOf(
                "get_ticker",
                "get_candles",
                "get_orderbook",
                "get_trades",
                "get_symbol_rules",
                "calc_indicator",
            ),
            server.tools.keys,
        )
    }

    @Test
    fun standaloneServer_usesSharedMarketToolRegistration() {
        val server = GmoCoinMcpServer(FakeMarketDataSource).createServer()

        assertEquals(
            setOf(
                "get_ticker",
                "get_candles",
                "get_orderbook",
                "get_trades",
                "get_symbol_rules",
                "calc_indicator",
            ),
            server.tools.keys,
        )
    }

    @Test
    fun registerGmoCoinMarketTools_appliesInjectedKlineBudgetHook() = runBlocking {
        val server = testServer()
        server.registerGmoCoinMarketTools(
            marketDataSource = FakeMarketDataSource,
            klineRequestBudgetHook = RejectingKlineRequestBudgetHook,
        )
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_candles",
                arguments = buildJsonObject {
                    put("interval", CandleInterval.FIVE_MINUTES.apiValue)
                },
            ),
        )

        val result = server.tools.getValue("get_candles").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(result.structuredContent)

        assertTrue(result.isError == true)
        assertEquals("invalid_request", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals("permanent", structuredContent.getValue("failure_kind").jsonPrimitive.contentOrNull)
    }

    @Test
    fun getTicker_returnsFreshnessMetadataFromTickerTimestamp() = runBlocking {
        val server = testServer()
        server.registerGmoCoinMarketTools(
            marketDataSource = FreshnessMarketDataSource(
                tickerTimestamp = fixedInstant().minusSeconds(3).toString(),
            ),
            clock = fixedClock(),
        )

        val result = callTool(server, "get_ticker")
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().minusSeconds(3).toString(),
            stalenessMs = 3_000L,
            staleAfterMs = 5_000L,
            stale = false,
            source = "GMO_PUBLIC_REST",
        )
    }

    @Test
    fun getCandles_returnsFreshnessMetadataFromFinalCandleOpenTime() = runBlocking {
        val server = testServer()
        server.registerGmoCoinMarketTools(
            marketDataSource = FreshnessMarketDataSource(
                candleOpenTime = fixedInstant().minusSeconds(360).toString(),
            ),
            clock = fixedClock(),
        )
        val arguments = buildJsonObject {
            put("interval", CandleInterval.FIVE_MINUTES.apiValue)
        }

        val result = callTool(server, "get_candles", arguments)
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().minusSeconds(360).toString(),
            stalenessMs = 360_000L,
            staleAfterMs = 390_000L,
            stale = false,
            source = "GMO_PUBLIC_REST",
        )
    }

    @Test
    fun getCandles_marksFiveMinuteCandleStaleAfterIntervalAndGrace() = runBlocking {
        val server = testServer()
        server.registerGmoCoinMarketTools(
            marketDataSource = FreshnessMarketDataSource(
                candleOpenTime = fixedInstant().minusSeconds(391).toString(),
            ),
            clock = fixedClock(),
        )
        val arguments = buildJsonObject {
            put("interval", CandleInterval.FIVE_MINUTES.apiValue)
        }

        val result = callTool(server, "get_candles", arguments)
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().minusSeconds(391).toString(),
            stalenessMs = 391_000L,
            staleAfterMs = 390_000L,
            stale = true,
            source = "GMO_PUBLIC_REST",
        )
    }

    @Test
    fun getOrderbook_returnsFreshnessMetadataWithoutSourceTimestamp() = runBlocking {
        val server = testServer()
        server.registerGmoCoinMarketTools(
            marketDataSource = FreshnessMarketDataSource(),
            clock = fixedClock(),
        )

        val result = callTool(server, "get_orderbook")
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = null,
            stalenessMs = 0L,
            staleAfterMs = 3_000L,
            stale = false,
            source = "GMO_PUBLIC_REST",
        )
    }

    @Test
    fun getTrades_returnsFreshnessMetadataFromLatestTradeTimestamp() = runBlocking {
        val server = testServer()
        server.registerGmoCoinMarketTools(
            marketDataSource = FreshnessMarketDataSource(
                tradeTimestamps = listOf(
                    fixedInstant().minusSeconds(8).toString(),
                    fixedInstant().minusSeconds(7).toString(),
                ),
            ),
            clock = fixedClock(),
        )

        val result = callTool(server, "get_trades")
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().minusSeconds(7).toString(),
            stalenessMs = 7_000L,
            staleAfterMs = 10_000L,
            stale = false,
            source = "GMO_PUBLIC_REST",
        )
    }

    @Test
    fun calcIndicator_returnsFreshnessMetadataFromUnderlyingCandles() = runBlocking {
        val server = testServer()
        server.registerGmoCoinMarketTools(
            marketDataSource = FreshnessMarketDataSource(
                candleOpenTime = fixedInstant().minusSeconds(360).toString(),
            ),
            clock = fixedClock(),
        )
        val arguments = buildJsonObject {
            put("interval", CandleInterval.FIVE_MINUTES.apiValue)
            put("indicator", "SMA")
            put(
                "params",
                buildJsonObject {
                    put("period", 1)
                },
            )
        }

        val result = callTool(server, "calc_indicator", arguments)
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().minusSeconds(360).toString(),
            stalenessMs = 360_000L,
            staleAfterMs = 390_000L,
            stale = false,
            source = "GMO_PUBLIC_REST",
        )
    }
}

private fun testServer(): Server {
    return Server(
        serverInfo = Implementation(
            name = "test-gmo-coin-mcp",
            version = "0.1.0",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )
}

private suspend fun callTool(
    server: Server,
    toolName: String,
    arguments: JsonObject = buildJsonObject {},
): CallToolResult {
    val request = CallToolRequest(
        params = CallToolRequestParams(
            name = toolName,
            arguments = arguments,
        ),
    )

    return server.tools.getValue(toolName).handler.invoke(TestClientConnection, request)
}

private fun assertFreshness(
    structuredContent: JsonObject,
    fetchedAt: String,
    sourceTimestamp: String?,
    stalenessMs: Long,
    staleAfterMs: Long,
    stale: Boolean,
    source: String,
) {
    val freshness = structuredContent.getValue("freshness").jsonObject

    assertEquals(fetchedAt, freshness.getValue("fetchedAt").jsonPrimitive.contentOrNull)
    if (sourceTimestamp == null) {
        assertNull(freshness.getValue("sourceTimestamp").jsonPrimitive.contentOrNull)
    } else {
        assertEquals(sourceTimestamp, freshness.getValue("sourceTimestamp").jsonPrimitive.contentOrNull)
    }
    assertEquals(stalenessMs, freshness.getValue("stalenessMs").jsonPrimitive.longOrNull)
    assertEquals(staleAfterMs, freshness.getValue("staleAfterMs").jsonPrimitive.longOrNull)
    assertEquals(stale, freshness.getValue("stale").jsonPrimitive.booleanOrNull)
    assertEquals(source, freshness.getValue("source").jsonPrimitive.contentOrNull)
}

/**
 * kline budget hook が handler 内で呼ばれることを検証するための拒否 hook。
 */
private object RejectingKlineRequestBudgetHook : GmoCoinKlineRequestBudgetHook {
    override val dailyKlineRequestLimit: Int = 0

    override suspend fun check(request: GmoCoinKlineRequest): Result<Unit> {
        return Result.failure(
            MarketInvalidRequestException("kline request budget exhausted: ${request.toolName}"),
        )
    }
}

/**
 * tool handler の receiver を満たす test 用 connection。
 */
private object TestClientConnection : ClientConnection {
    override val sessionId: String = "test-session"

    override suspend fun notification(notification: ServerNotification, relatedRequestId: RequestId?) = Unit

    override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult {
        return unsupported()
    }

    override suspend fun createMessage(
        request: CreateMessageRequest,
        options: RequestOptions?,
    ): CreateMessageResult {
        return unsupported()
    }

    override suspend fun listRoots(request: ListRootsRequest, options: RequestOptions?): ListRootsResult {
        return unsupported()
    }

    override suspend fun createElicitation(
        message: String,
        requestedSchema: ElicitRequestParams.RequestedSchema,
        options: RequestOptions?,
    ): ElicitResult {
        return unsupported()
    }

    override suspend fun createElicitation(
        message: String,
        elicitationId: String,
        url: String,
        options: RequestOptions?,
    ): ElicitResult {
        return unsupported()
    }

    override suspend fun createElicitation(request: ElicitRequest, options: RequestOptions?): ElicitResult {
        return unsupported()
    }

    override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) = Unit

    override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) = Unit

    override suspend fun sendResourceListChanged() = Unit

    override suspend fun sendToolListChanged() = Unit

    override suspend fun sendPromptListChanged() = Unit

    override suspend fun sendElicitationComplete(notification: ElicitationCompleteNotification) = Unit

    private fun <ResultType> unsupported(): ResultType {
        error("TestClientConnection does not support client callbacks.")
    }
}

/**
 * freshness metadata 検証用の時刻を差し替えられる fake market data source。
 *
 * @param tickerTimestamp ticker source timestamp
 * @param candleOpenTime candle source timestamp
 * @param tradeTimestamps trade source timestamp 一覧
 */
private class FreshnessMarketDataSource(
    private val tickerTimestamp: String = fixedInstant().toString(),
    private val candleOpenTime: String = fixedInstant().toString(),
    private val tradeTimestamps: List<String> = listOf(fixedInstant().toString()),
) : MarketDataSource {

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
                timestamp = tickerTimestamp,
            ),
        )
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(
            listOf(
                Candle(
                    symbol = symbol.apiSymbol,
                    interval = interval,
                    openTime = candleOpenTime,
                    open = "100",
                    high = "110",
                    low = "90",
                    close = "105",
                    volume = "1.0",
                ),
            ),
        )
    }

    override suspend fun getOrderbook(
        symbol: TradingSymbol,
        depth: Int,
    ): Result<Orderbook> {
        return Result.success(
            Orderbook(
                symbol = symbol.apiSymbol,
                bids = listOf(OrderbookLevel("99", "0.1")),
                asks = listOf(OrderbookLevel("101", "0.1")),
            ),
        )
    }

    override suspend fun getTrades(
        symbol: TradingSymbol,
        limit: Int,
    ): Result<List<RecentTrade>> {
        val trades = tradeTimestamps.map { tradeTimestamp ->
            RecentTrade(
                symbol = symbol.apiSymbol,
                price = "100",
                size = "0.01",
                side = TradeSide.BUY,
                timestamp = tradeTimestamp,
            )
        }

        return Result.success(trades)
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return FakeMarketDataSource.getSymbolRules(symbol)
    }
}

/**
 * GMO Coin MCP test 用の fake market data source。
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

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(
            listOf(
                Candle(
                    symbol = symbol.apiSymbol,
                    interval = interval,
                    openTime = "2026-07-01T00:00:00Z",
                    open = "100",
                    high = "110",
                    low = "90",
                    close = "105",
                    volume = "1.0",
                ),
            ),
        )
    }

    override suspend fun getOrderbook(
        symbol: TradingSymbol,
        depth: Int,
    ): Result<Orderbook> {
        return Result.success(
            Orderbook(
                symbol = symbol.apiSymbol,
                bids = listOf(OrderbookLevel("99", "0.1")),
                asks = listOf(OrderbookLevel("101", "0.1")),
            ),
        )
    }

    override suspend fun getTrades(
        symbol: TradingSymbol,
        limit: Int,
    ): Result<List<RecentTrade>> {
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

private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-04T12:00:00Z")
}
