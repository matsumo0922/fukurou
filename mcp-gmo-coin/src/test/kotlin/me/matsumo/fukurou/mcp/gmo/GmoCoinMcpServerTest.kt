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
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
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
import me.matsumo.fukurou.trading.exchange.gmo.parseKlinesResponse
import me.matsumo.fukurou.trading.market.IndicatorType
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.market.MarketInvalidRequestException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
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
    fun calcIndicatorSchema_exposesIndicatorTypeEntries() {
        val server = testServer()

        server.registerGmoCoinMarketTools(FakeMarketDataSource)

        val tool = server.tools.getValue("calc_indicator").tool
        val properties = assertNotNull(tool.inputSchema.properties)
        val indicatorSchema = properties.getValue("indicator").jsonObject
        val enumValues = indicatorSchema.getValue("enum")
            .jsonArray
            .map { enumValue -> enumValue.jsonPrimitive.contentOrNull }

        assertEquals(IndicatorType.entries.map { indicator -> indicator.name }, enumValues)
        assertTrue(enumValues.contains("ATR_PERCENTILE"))
        assertTrue(enumValues.contains("VWAP_SESSION"))
        assertTrue(enumValues.contains("VOLUME_Z_SCORE"))
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
        val textContent = assertTextJsonObject(result)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().minusSeconds(3).toString(),
            stalenessMs = 3_000L,
            staleAfterMs = 5_000L,
            stale = false,
            source = "GMO_PUBLIC_REST",
        )
        assertFreshness(
            structuredContent = textContent,
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
    fun calcIndicator_returnsExpandedIndicatorResults() = runBlocking {
        val server = testServer()

        server.registerGmoCoinMarketTools(IndicatorMarketDataSource(expandedIndicatorCandles()))

        val atrPercentile = callTool(
            server = server,
            toolName = "calc_indicator",
            arguments = indicatorArguments(
                indicator = "ATR_PERCENTILE",
                period = 2,
                lookback = 3,
                limit = 6,
            ),
        )
        val vwapSession = callTool(
            server = server,
            toolName = "calc_indicator",
            arguments = indicatorArguments(
                indicator = "VWAP_SESSION",
                limit = 6,
            ),
        )
        val volumeZScore = callTool(
            server = server,
            toolName = "calc_indicator",
            arguments = indicatorArguments(
                indicator = "VOLUME_Z_SCORE",
                period = 3,
                limit = 6,
            ),
        )

        val atrContent = assertNotNull(atrPercentile.structuredContent)
        val atrParams = atrContent.getValue("params").jsonObject
        val vwapContent = assertNotNull(vwapSession.structuredContent)
        val volumeContent = assertNotNull(volumeZScore.structuredContent)

        assertEquals("ATR_PERCENTILE", atrContent.getValue("indicator").jsonPrimitive.contentOrNull)
        assertEquals(2, atrParams.getValue("period").jsonPrimitive.longOrNull?.toInt())
        assertEquals(3, atrParams.getValue("lookback").jsonPrimitive.longOrNull?.toInt())
        assertClose(1.0 / 3.0, indicatorValueAt(atrContent, 5))

        assertEquals("VWAP_SESSION", vwapContent.getValue("indicator").jsonPrimitive.contentOrNull)
        assertClose(100.0, indicatorValueAt(vwapContent, 1))

        assertEquals("VOLUME_Z_SCORE", volumeContent.getValue("indicator").jsonPrimitive.contentOrNull)
        assertClose(1.2247448714, indicatorValueAt(volumeContent, 2))
    }

    @Test
    fun calcIndicator_returnsVwapSessionForEpochMillisKlineOpenTime() = runBlocking {
        val server = testServer()
        val responseBody = epochMillisIndicatorKlineResponse()

        server.registerGmoCoinMarketTools(
            marketDataSource = ParsedKlineMarketDataSource(responseBody),
            clock = fixedClock(),
        )

        val result = callTool(
            server = server,
            toolName = "calc_indicator",
            arguments = indicatorArguments(
                indicator = "VWAP_SESSION",
                limit = 6,
            ),
        )
        val structuredContent = assertNotNull(result.structuredContent)

        assertTrue(result.isError != true)
        assertEquals("VWAP_SESSION", structuredContent.getValue("indicator").jsonPrimitive.contentOrNull)
        assertClose(100.0, indicatorValueAt(structuredContent, 1))
        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = "2026-07-01T21:20:00Z",
            stalenessMs = 225_600_000L,
            staleAfterMs = 390_000L,
            stale = true,
            source = "GMO_PUBLIC_REST",
        )
    }

    @Test
    fun calcIndicator_returnsMarketDataParseErrorForInvalidKlineOpenTime() = runBlocking {
        val server = testServer()
        val responseBody = indicatorKlineResponse(
            openTimes = listOf("not-an-instant"),
        )

        server.registerGmoCoinMarketTools(
            marketDataSource = ParsedKlineMarketDataSource(responseBody),
            clock = fixedClock(),
        )

        val result = callTool(
            server = server,
            toolName = "calc_indicator",
            arguments = indicatorArguments(
                indicator = "VWAP_SESSION",
                limit = 1,
            ),
        )
        val structuredContent = assertNotNull(result.structuredContent)

        assertTrue(result.isError == true)
        assertEquals("market_data_parse_error", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals("permanent", structuredContent.getValue("failure_kind").jsonPrimitive.contentOrNull)
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

private fun indicatorArguments(
    indicator: String,
    period: Int? = null,
    lookback: Int? = null,
    limit: Int,
): JsonObject {
    return buildJsonObject {
        put("interval", CandleInterval.FIVE_MINUTES.apiValue)
        put("indicator", indicator)
        put(
            "params",
            buildJsonObject {
                if (period != null) {
                    put("period", period)
                }
                if (lookback != null) {
                    put("lookback", lookback)
                }
                put("limit", limit)
            },
        )
    }
}

private fun indicatorValueAt(structuredContent: JsonObject, valueIndex: Int): Double? {
    return structuredContent.getValue("values")
        .jsonArray[valueIndex]
        .jsonObject
        .getValue("value")
        .jsonPrimitive
        .doubleOrNull
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

private fun assertTextJsonObject(result: CallToolResult): JsonObject {
    val textContent = assertNotNull(result.content.singleOrNull() as? TextContent)

    return Json.parseToJsonElement(textContent.text).jsonObject
}

private fun assertClose(expected: Double, actual: Double?) {
    requireNotNull(actual)

    assertTrue(abs(expected - actual) < 0.0000001, "expected <$expected>, actual <$actual>")
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
 * indicator handler 検証用に任意の candle を返す fake market data source。
 *
 * @param candles 返却する candle 一覧
 */
private class IndicatorMarketDataSource(
    private val candles: List<Candle>,
) : MarketDataSource {

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return FakeMarketDataSource.getTicker(symbol)
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(candles.take(limit))
    }

    override suspend fun getOrderbook(
        symbol: TradingSymbol,
        depth: Int,
    ): Result<Orderbook> {
        return FakeMarketDataSource.getOrderbook(symbol, depth)
    }

    override suspend fun getTrades(
        symbol: TradingSymbol,
        limit: Int,
    ): Result<List<RecentTrade>> {
        return FakeMarketDataSource.getTrades(symbol, limit)
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return FakeMarketDataSource.getSymbolRules(symbol)
    }
}

/**
 * GMO kline parser 経由の candle を返す fake market data source。
 *
 * @param responseBody GMO klines response body
 */
private class ParsedKlineMarketDataSource(
    private val responseBody: String,
) : MarketDataSource {

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return FakeMarketDataSource.getTicker(symbol)
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return runCatching {
            val candles = parseKlinesResponse(
                responseBody = responseBody,
                symbol = symbol,
                interval = interval,
            )

            candles.take(limit)
        }
    }

    override suspend fun getOrderbook(
        symbol: TradingSymbol,
        depth: Int,
    ): Result<Orderbook> {
        return FakeMarketDataSource.getOrderbook(symbol, depth)
    }

    override suspend fun getTrades(
        symbol: TradingSymbol,
        limit: Int,
    ): Result<List<RecentTrade>> {
        return FakeMarketDataSource.getTrades(symbol, limit)
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

private fun epochMillisIndicatorKlineResponse(): String {
    return indicatorKlineResponse(
        openTimes = listOf(
            "1782939300000",
            "1782939600000",
            "1782939900000",
            "1782940200000",
            "1782940500000",
            "1782940800000",
        ),
    )
}

private fun indicatorKlineResponse(openTimes: List<String>): String {
    val candleEntries = openTimes.indices.joinToString(separator = ",") { openTimeIndex ->
        indicatorKlineEntryForIndex(openTimes, openTimeIndex)
    }

    return """
    {
      "status": 0,
      "data": [
        $candleEntries
      ]
    }
    """.trimIndent()
}

private fun indicatorKlineEntryForIndex(openTimes: List<String>, openTimeIndex: Int): String {
    val volume = (openTimeIndex + 1).toString()

    return indicatorKlineEntry(
        openTime = openTimes[openTimeIndex],
        volume = volume,
    )
}

private fun indicatorKlineEntry(openTime: String, volume: String): String {
    return """
    {
      "openTime": "$openTime",
      "open": "100.0",
      "high": "101.0",
      "low": "99.0",
      "close": "100.0",
      "volume": "$volume"
    }
    """.trimIndent()
}

private fun expandedIndicatorCandles(): List<Candle> {
    val trueRangeValues = listOf(2.0, 4.0, 6.0, 2.0, 8.0, 0.0)
    val volumeValues = listOf(1.0, 2.0, 3.0, 2.0, 1.0, 4.0)
    val openTimes = listOf(
        "2026-07-01T20:55:00Z",
        "2026-07-01T21:00:00Z",
        "2026-07-01T21:05:00Z",
        "2026-07-01T21:10:00Z",
        "2026-07-01T21:15:00Z",
        "2026-07-01T21:20:00Z",
    )

    return trueRangeValues.mapIndexed { candleIndex, trueRangeValue ->
        Candle(
            symbol = TradingSymbol.BTC.apiSymbol,
            interval = CandleInterval.FIVE_MINUTES,
            openTime = openTimes[candleIndex],
            open = "100.0",
            high = (100.0 + trueRangeValue / 2.0).toString(),
            low = (100.0 - trueRangeValue / 2.0).toString(),
            close = "100.0",
            volume = volumeValues[candleIndex].toString(),
        )
    }
}

private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-04T12:00:00Z")
}
