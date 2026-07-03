package me.matsumo.fukurou.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
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
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import me.matsumo.fukurou.trading.tool.ToolCallGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun createServer_exposesGmoCoinAndFukurouToolsOnSingleServer() {
        val server = FukurouMcpServer(
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        ).createServer()

        assertEquals(
            setOf(
                "get_ticker",
                "get_candles",
                "get_orderbook",
                "get_trades",
                "get_symbol_rules",
                "calc_indicator",
                "get_balance",
                "get_positions",
                "get_open_orders",
                "get_account_status",
                "submit_decision",
                "submit_falsification",
                "place_order",
                "close_position",
                "update_protection",
                "cancel_order",
                "reject_dummy_trade",
                "simulate_tool_timeout",
            ),
            server.tools.keys,
        )
    }

    @Test
    fun tradingRuntimeFactory_failsClosedWhenDatabaseEnvironmentIsMissing() {
        assertFailsWith<IllegalArgumentException> {
            TradingRuntimeFactory.fromEnvironment(environment = emptyMap())
        }
    }

    @Test
    fun updateProtectionTool_allowsNullTakeProfitClearInSchema() {
        val server = FukurouMcpServer(
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        ).createServer()
        val tool = requireNotNull(server.tools["update_protection"]?.tool)
        val takeProfitSchema = requireNotNull(tool.inputSchema.properties?.get("new_take_profit_price_jpy"))
            .jsonObject
        val typeNames = takeProfitSchema.getValue("type")
            .jsonArray
            .map { typeElement -> typeElement.jsonPrimitive.contentOrNull }

        assertEquals(listOf("string", "null"), typeNames)
    }

    @Test
    fun submitDecisionTool_recordsNoTradeInMemoryRuntime() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_decision",
                arguments = noTradeDecisionArguments(),
            ),
        )

        val result = server.tools.getValue("submit_decision").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(result.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository
        val decisions = repository.decisions()

        assertTrue(result.isError != true)
        assertEquals("NO_TRADE", structuredContent.getValue("action").jsonPrimitive.contentOrNull)
        assertEquals(1, decisions.size)
        assertEquals("材料不足のため見送ります。", decisions.single().submission.reasonJa)
    }

    @Test
    fun submitFalsificationTool_recordsVerdictForEnterIntentInMemoryRuntime() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val decisionRequest = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_decision",
                arguments = enterDecisionArguments(),
            ),
        )

        val decisionResult = server.tools.getValue("submit_decision").handler.invoke(TestClientConnection, decisionRequest)
        val intentId = assertNotNull(decisionResult.structuredContent)
            .getValue("intent_id")
            .jsonPrimitive
            .contentOrNull
        val falsificationRequest = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_falsification",
                arguments = buildJsonObject {
                    put("intent_id", intentId)
                    put("verdict", FalsificationVerdict.APPROVED.name)
                    put("llm_provider", "codex")
                    put("reason_ja", "反証しても拒否理由が不足しています。")
                },
            ),
        )

        val falsificationResult = server.tools.getValue("submit_falsification")
            .handler
            .invoke(TestClientConnection, falsificationRequest)
        val duplicateResult = server.tools.getValue("submit_falsification")
            .handler
            .invoke(TestClientConnection, falsificationRequest)
        val structuredContent = assertNotNull(falsificationResult.structuredContent)
        val duplicateContent = assertNotNull(duplicateResult.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertTrue(falsificationResult.isError != true)
        assertEquals(FalsificationVerdict.APPROVED.name, structuredContent.getValue("verdict").jsonPrimitive.contentOrNull)
        assertEquals(1, repository.tradeIntents().size)
        assertEquals(1, repository.falsifications().size)
        assertTrue(duplicateResult.isError == true)
        assertEquals("invalid_request", duplicateContent.getValue("type").jsonPrimitive.contentOrNull)
    }

    @Test
    fun submitFalsificationTool_rejectsMissingIntentId() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_falsification",
                arguments = buildJsonObject {
                    put("verdict", FalsificationVerdict.APPROVED.name)
                    put("llm_provider", "codex")
                    put("reason_ja", "intent がないため拒否されます。")
                },
            ),
        )

        val result = server.tools.getValue("submit_falsification").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(result.structuredContent)

        assertTrue(result.isError == true)
        assertEquals("invalid_request", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
    }

    @Test
    fun embeddedMarketTool_preservesAuditCompletionFailureResponse() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val failingRuntime = runtime.copy(
            commandEventLog = FailingCommandEventLog,
            toolCallGuard = ToolCallGuard(
                riskStateRepository = runtime.riskStateRepository,
                commandEventLog = FailingCommandEventLog,
                tradingLock = runtime.tradingLock,
            ),
        )
        val server = FukurouMcpServer(
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = failingRuntime,
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_ticker",
                arguments = buildJsonObject {
                    put("symbol", TradingSymbol.BTC.apiSymbol)
                },
            ),
        )

        val result = server.tools.getValue("get_ticker").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(result.structuredContent)

        assertTrue(result.isError == true)
        assertEquals("audit_failed_after_execution", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals("true", structuredContent.getValue("executed").jsonPrimitive.contentOrNull)
    }
}

/**
 * NO_TRADE decision tool request の引数を作る。
 */
private fun noTradeDecisionArguments() = buildJsonObject {
    put("action", "NO_TRADE")
    put("estimated_win_probability", "0.12")
    put("tool_evidence_ids", stringArray("tool-1"))
    put("fact_check", """{"ticker":true}""")
    put("self_review", """{"reasonsNotToTrade":["出来高不足"]}""")
    put("reason_ja", "材料不足のため見送ります。")
    put("missing_data_ja", stringArray("orderbook"))
    put("no_trade_conditions_ja", stringArray("出来高が戻るまで待つ"))
}

/**
 * ENTER decision tool request の引数を作る。
 */
private fun enterDecisionArguments() = buildJsonObject {
    put("action", "ENTER")
    put("setup_tags", stringArray("breakout", "trend-follow"))
    put("estimated_win_probability", "0.73")
    put("expected_r_multiple", "1.80")
    put("round_trip_cost_r", "0.05")
    put("tool_evidence_ids", stringArray("tool-1", "tool-2"))
    put("fact_check", """{"ticker":true}""")
    put("self_review", """{"reasonsNotToTrade":["spread"]}""")
    put("reason_ja", "ブレイク継続を狙います。")
    put("symbol", TradingSymbol.BTC.apiSymbol)
    put("side", "BUY")
    put("type", "MARKET")
    put("size_btc", "0.0050")
    put("protective_stop_price_jpy", "9700000")
    put("take_profit_price_jpy", "10500000")
    put("trade_plan_revision_count", 0)
    put("trade_plan_thesis_ja", "1時間足の上昇継続に乗る。")
    put("trade_plan_invalidation_conditions_ja", stringArray("直近安値割れ", "出来高急減"))
    put("trade_plan_target_price_jpy", "10500000")
    put("trade_plan_time_stop_at", "2026-07-02T01:00:00Z")
}

/**
 * JSON string array を作る。
 */
private fun stringArray(vararg values: String) = buildJsonArray {
    values.forEach { value -> add(value) }
}

/**
 * append に必ず失敗する command_event_log。
 */
private object FailingCommandEventLog : CommandEventLog {
    override suspend fun append(event: CommandEvent): Result<Unit> {
        return Result.failure(IllegalStateException("audit append failed"))
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
