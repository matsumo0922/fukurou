package me.matsumo.fukurou.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GMO_MAX_DAILY_KLINE_REQUESTS
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.market.GmoApiStatusException
import me.matsumo.fukurou.trading.market.GmoHttpException
import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.IndicatorCalculator
import me.matsumo.fukurou.trading.market.IndicatorParams
import me.matsumo.fukurou.trading.market.IndicatorResult
import me.matsumo.fukurou.trading.market.IndicatorType
import me.matsumo.fukurou.trading.market.MarketDataParseException
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.market.MarketInvalidRequestException
import me.matsumo.fukurou.trading.market.MarketNetworkException
import me.matsumo.fukurou.trading.risk.AccountStatusService
import me.matsumo.fukurou.trading.risk.HardHaltTradingRejectedException
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import me.matsumo.fukurou.trading.tool.NoTradeExitException
import me.matsumo.fukurou.trading.tool.ToolCallGuard
import me.matsumo.fukurou.trading.tool.ToolCompletionAuditFailedException
import java.math.BigDecimal
import java.util.UUID

/**
 * MCP server 名。
 */
private const val MCP_SERVER_NAME = "fukurou-gmo-coin-mcp"

/**
 * MCP server version。
 */
private const val MCP_SERVER_VERSION = "0.1.0"

/**
 * ticker 取得 tool 名。
 */
private const val GET_TICKER_TOOL = "get_ticker"

/**
 * candles 取得 tool 名。
 */
private const val GET_CANDLES_TOOL = "get_candles"

/**
 * orderbook 取得 tool 名。
 */
private const val GET_ORDERBOOK_TOOL = "get_orderbook"

/**
 * trades 取得 tool 名。
 */
private const val GET_TRADES_TOOL = "get_trades"

/**
 * symbol rules 取得 tool 名。
 */
private const val GET_SYMBOL_RULES_TOOL = "get_symbol_rules"

/**
 * indicator 計算 tool 名。
 */
private const val CALC_INDICATOR_TOOL = "calc_indicator"

/**
 * balance 取得 tool 名。
 */
private const val GET_BALANCE_TOOL = "get_balance"

/**
 * positions 取得 tool 名。
 */
private const val GET_POSITIONS_TOOL = "get_positions"

/**
 * open orders 取得 tool 名。
 */
private const val GET_OPEN_ORDERS_TOOL = "get_open_orders"

/**
 * account status 取得 tool 名。
 */
private const val GET_ACCOUNT_STATUS_TOOL = "get_account_status"

/**
 * paper entry 発注 tool 名。
 */
private const val PLACE_ORDER_TOOL = "place_order"

/**
 * paper position close tool 名。
 */
private const val CLOSE_POSITION_TOOL = "close_position"

/**
 * paper protection 更新 tool 名。
 */
private const val UPDATE_PROTECTION_TOOL = "update_protection"

/**
 * paper order cancel tool 名。
 */
private const val CANCEL_ORDER_TOOL = "cancel_order"

/**
 * trade 風 dummy 拒否 tool 名。
 */
private const val REJECT_DUMMY_TRADE_TOOL = "reject_dummy_trade"

/**
 * timeout 再現用の副作用なし tool 名。
 */
private const val SIMULATE_TOOL_TIMEOUT_TOOL = "simulate_tool_timeout"

/**
 * JSON schema の string 型。
 */
private const val JSON_TYPE_STRING = "string"

/**
 * JSON schema の integer 型。
 */
private const val JSON_TYPE_INTEGER = "integer"

/**
 * JSON schema の object 型。
 */
private const val JSON_TYPE_OBJECT = "object"

/**
 * JSON schema の null 型。
 */
private const val JSON_TYPE_NULL = "null"

/**
 * candles 取得の既定本数。
 */
private const val DEFAULT_CANDLE_LIMIT = 100

/**
 * candles 取得の最大本数。
 */
private const val MAX_CANDLE_LIMIT = 500

/**
 * orderbook 取得の既定 depth。
 */
private const val DEFAULT_ORDERBOOK_DEPTH = 10

/**
 * orderbook 取得の最大 depth。
 */
private const val MAX_ORDERBOOK_DEPTH = 100

/**
 * trades 取得の既定本数。
 */
private const val DEFAULT_TRADES_LIMIT = 50

/**
 * trades 取得の最大本数。
 */
private const val MAX_TRADES_LIMIT = 100

/**
 * indicator 計算で取得する既定 candle 本数。
 */
private const val DEFAULT_INDICATOR_CANDLE_LIMIT = 100

/**
 * indicator 計算で取得する最大 candle 本数。
 */
private const val MAX_INDICATOR_CANDLE_LIMIT = 500

/**
 * timeout 再現 tool の既定 delay。
 */
private const val DEFAULT_SIMULATED_TIMEOUT_DELAY_MS = 30_000L

/**
 * timeout 再現 tool に指定できる最小 delay。
 */
private const val MIN_SIMULATED_TIMEOUT_DELAY_MS = 1L

/**
 * timeout 再現 tool に指定できる最大 delay。
 */
private const val MAX_SIMULATED_TIMEOUT_DELAY_MS = 120_000L

/**
 * Tool response の JSON 設定。
 */
private val ToolJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * fukurou MCP stdio server のエントリポイント。
 */
fun main() {
    FukurouMcpServer().run()
}

/**
 * fukurou の MCP stdio server。業務ロジックは `:trading` へ委譲する。
 *
 * @param marketDataSource 市場データ取得元
 */
class FukurouMcpServer(
    private val marketDataSource: MarketDataSource = GmoPublicMarketDataSource(),
    private val tradingRuntime: TradingRuntime = TradingRuntimeFactory.fromEnvironment(
        marketDataSource = marketDataSource,
    ),
    private val decisionRunContext: DecisionRunContext = DecisionRunContext.fromEnvironment(),
) {

    /**
     * 標準入出力に MCP server を接続し、client から閉じられるまで待機する。
     */
    fun run() {
        val server = createServer()
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = System.out.asSink().buffered(),
        ) {
            scope = transportScope
            handlerDispatcher = Dispatchers.Default
            ioDispatcher = Dispatchers.IO
        }

        runBlocking {
            try {
                val session = server.createSession(transport)
                val done = Job()
                session.onClose {
                    done.complete()
                }
                done.join()
            } finally {
                transportScope.cancel()
                tradingRuntime.close()
            }
        }
    }

    internal fun createServer(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = MCP_SERVER_NAME,
                version = MCP_SERVER_VERSION,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
            ),
        )

        server.registerTickerTool(marketDataSource, tradingRuntime.toolCallGuard, decisionRunContext)
        server.registerCandlesTool(marketDataSource, tradingRuntime.toolCallGuard, decisionRunContext)
        server.registerOrderbookTool(marketDataSource, tradingRuntime.toolCallGuard, decisionRunContext)
        server.registerTradesTool(marketDataSource, tradingRuntime.toolCallGuard, decisionRunContext)
        server.registerSymbolRulesTool(marketDataSource, tradingRuntime.toolCallGuard, decisionRunContext)
        server.registerCalcIndicatorTool(marketDataSource, tradingRuntime.toolCallGuard, decisionRunContext)
        server.registerBalanceTool(tradingRuntime, decisionRunContext)
        server.registerPositionsTool(tradingRuntime, decisionRunContext)
        server.registerOpenOrdersTool(tradingRuntime, decisionRunContext)
        server.registerAccountStatusTool(tradingRuntime, decisionRunContext)
        server.registerPlaceOrderTool(tradingRuntime, decisionRunContext)
        server.registerClosePositionTool(tradingRuntime, decisionRunContext)
        server.registerUpdateProtectionTool(tradingRuntime, decisionRunContext)
        server.registerCancelOrderTool(tradingRuntime, decisionRunContext)
        server.registerRejectDummyTradeTool(tradingRuntime.toolCallGuard, decisionRunContext)
        server.registerSimulateToolTimeoutTool(tradingRuntime.toolCallGuard, decisionRunContext)

        return server
    }
}

private fun Server.registerPlaceOrderTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = PLACE_ORDER_TOOL,
        description = "Place a paper BTC entry order. protective_stop_price_jpy and reason are required.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putSymbolSchema()
                putJsonObject("side") {
                    put("type", JSON_TYPE_STRING)
                    put("description", "BUY entry only for BTC spot.")
                    put("enum", ToolJson.encodeToJsonElement(listOf(OrderSide.BUY.name)))
                }
                putJsonObject("type") {
                    put("type", JSON_TYPE_STRING)
                    put("description", "MARKET, LIMIT, or STOP.")
                    put("enum", ToolJson.encodeToJsonElement(OrderType.entries.map { type -> type.name }))
                }
                putDecimalStringSchema("size_btc", "BTC order size.")
                putDecimalStringSchema("price_jpy", "LIMIT or STOP price. Omit for MARKET.")
                putJsonObject("trade_group_id") {
                    put("type", JSON_TYPE_STRING)
                    put("description", "Optional trade group UUID when adding to an existing group.")
                }
                putDecimalStringSchema("protective_stop_price_jpy", "Required protective STOP price after entry fill.")
                putDecimalStringSchema("take_profit_price_jpy", "Optional virtual take-profit trigger price.")
                putDecimalStringSchema("estimated_win_probability", "Estimated win probability from 0 to 1. SafetyFloor calculates EV from this value.")
                putReasonSchema()
                putClientRequestIdSchema()
            },
            required = listOf(
                "side",
                "type",
                "size_btc",
                "protective_stop_price_jpy",
                "estimated_win_probability",
                "reason",
            ),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
    ) { request ->
        handlePlaceOrder(request, tradingRuntime, decisionRunContext)
    }
}

private fun Server.registerClosePositionTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = CLOSE_POSITION_TOOL,
        description = "Close one open paper position, or all open paper positions when all=true. reason is required.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("position_id") {
                    put("type", JSON_TYPE_STRING)
                    put("description", "Position UUID. Required unless all=true.")
                }
                putJsonObject("all") {
                    put("type", "boolean")
                    put("description", "Close all open positions.")
                    put("default", false)
                }
                putReasonSchema()
                putClientRequestIdSchema()
            },
            required = listOf("reason"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
    ) { request ->
        handleClosePosition(request, tradingRuntime, decisionRunContext)
    }
}

private fun Server.registerUpdateProtectionTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = UPDATE_PROTECTION_TOOL,
        description = "Update a paper position protective STOP and/or virtual TP. reason is required.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("position_id") {
                    put("type", JSON_TYPE_STRING)
                    put("description", "Position UUID.")
                }
                putDecimalStringSchema("new_stop_price_jpy", "New protective STOP price.")
                putNullableDecimalStringSchema("new_take_profit_price_jpy", "New virtual TP price. Use null to clear.")
                putReasonSchema()
                putClientRequestIdSchema()
            },
            required = listOf("position_id", "reason"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
    ) { request ->
        handleUpdateProtection(request, tradingRuntime, decisionRunContext)
    }
}

private fun Server.registerCancelOrderTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = CANCEL_ORDER_TOOL,
        description = "Cancel an open paper order. Protective STOP cancellation is rejected; use update_protection or close_position.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("order_id") {
                    put("type", JSON_TYPE_STRING)
                    put("description", "Order UUID.")
                }
                putReasonSchema()
                putClientRequestIdSchema()
            },
            required = listOf("order_id", "reason"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
    ) { request ->
        handleCancelOrder(request, tradingRuntime, decisionRunContext)
    }
}

private fun Server.registerTickerTool(
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = GET_TICKER_TOOL,
        description = "Get the latest GMO Coin public ticker for BTC spot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("symbol") {
                    put("type", JSON_TYPE_STRING)
                    put("description", "Spot symbol. BTC only.")
                    put("default", TradingSymbol.BTC.apiSymbol)
                }
            },
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        handleGetTicker(request, marketDataSource, toolCallGuard, decisionRunContext)
    }
}

private fun Server.registerCandlesTool(
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = GET_CANDLES_TOOL,
        description = "Get recent GMO Coin public candles for BTC spot. DAY-based intervals use GMO business dates that switch at 06:00 JST and stitch up to $GMO_MAX_DAILY_KLINE_REQUESTS dates, so long 1hour limits may return fewer candles than requested.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putSymbolSchema()
                putIntervalSchema()
                putJsonObject("limit") {
                    put("type", JSON_TYPE_INTEGER)
                    put("description", "Number of recent candles.")
                    put("default", DEFAULT_CANDLE_LIMIT)
                    put("minimum", 1)
                    put("maximum", MAX_CANDLE_LIMIT)
                }
            },
            required = listOf("interval"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        handleGetCandles(request, marketDataSource, toolCallGuard, decisionRunContext)
    }
}

private fun Server.registerOrderbookTool(
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = GET_ORDERBOOK_TOOL,
        description = "Get GMO Coin public orderbook for BTC spot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putSymbolSchema()
                putJsonObject("depth") {
                    put("type", JSON_TYPE_INTEGER)
                    put("description", "Number of bid and ask levels.")
                    put("default", DEFAULT_ORDERBOOK_DEPTH)
                    put("minimum", 1)
                    put("maximum", MAX_ORDERBOOK_DEPTH)
                }
            },
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        handleGetOrderbook(request, marketDataSource, toolCallGuard, decisionRunContext)
    }
}

private fun Server.registerTradesTool(
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = GET_TRADES_TOOL,
        description = "Get recent GMO Coin public trades for BTC spot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putSymbolSchema()
                putJsonObject("limit") {
                    put("type", JSON_TYPE_INTEGER)
                    put("description", "Number of recent trades.")
                    put("default", DEFAULT_TRADES_LIMIT)
                    put("minimum", 1)
                    put("maximum", MAX_TRADES_LIMIT)
                }
            },
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        handleGetTrades(request, marketDataSource, toolCallGuard, decisionRunContext)
    }
}

private fun Server.registerSymbolRulesTool(
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = GET_SYMBOL_RULES_TOOL,
        description = "Get cached GMO Coin public symbol rules for BTC spot.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putSymbolSchema()
            },
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        handleGetSymbolRules(request, marketDataSource, toolCallGuard, decisionRunContext)
    }
}

private fun Server.registerCalcIndicatorTool(
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = CALC_INDICATOR_TOOL,
        description = "Calculate one technical indicator from GMO Coin public candles. DAY-based intervals use GMO business dates that switch at 06:00 JST and stitch up to $GMO_MAX_DAILY_KLINE_REQUESTS dates before calculating.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putSymbolSchema()
                putIntervalSchema()
                putJsonObject("indicator") {
                    put("type", JSON_TYPE_STRING)
                    put("description", "Indicator name.")
                    put("enum", ToolJson.encodeToJsonElement(IndicatorType.entries.map { indicator -> indicator.name }))
                }
                putJsonObject("params") {
                    put("type", JSON_TYPE_OBJECT)
                    put("description", "Indicator params. Use period, fast_period, slow_period, signal_period, and limit as needed. DAY-based candle limits are capped by $GMO_MAX_DAILY_KLINE_REQUESTS stitched GMO business dates.")
                }
            },
            required = listOf("interval", "indicator"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        handleCalcIndicator(request, marketDataSource, toolCallGuard, decisionRunContext)
    }
}

private fun Server.registerBalanceTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = GET_BALANCE_TOOL,
        description = "Get paper account balance and equity snapshot.",
        inputSchema = ToolSchema(),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        handleGetBalance(request, tradingRuntime, decisionRunContext)
    }
}

private fun Server.registerPositionsTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = GET_POSITIONS_TOOL,
        description = "Get open paper positions from the bot-managed position ledger.",
        inputSchema = ToolSchema(),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        handleGetPositions(request, tradingRuntime, decisionRunContext)
    }
}

private fun Server.registerOpenOrdersTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = GET_OPEN_ORDERS_TOOL,
        description = "Get open paper orders including protective STOP orders.",
        inputSchema = ToolSchema(),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        handleGetOpenOrders(request, tradingRuntime, decisionRunContext)
    }
}

private fun Server.registerAccountStatusTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = GET_ACCOUNT_STATUS_TOOL,
        description = "Get paper account status and DB-backed risk_state.",
        inputSchema = ToolSchema(),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        handleGetAccountStatus(request, tradingRuntime, decisionRunContext)
    }
}

private fun Server.registerRejectDummyTradeTool(
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = REJECT_DUMMY_TRADE_TOOL,
        description = "Reject-only dummy trade tool for headless approval and no-trade safety checks.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("reason") {
                    put("type", JSON_TYPE_STRING)
                    put("description", "Reason text to prove the tool was called intentionally.")
                }
            },
            required = listOf("reason"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
    ) { request ->
        handleRejectDummyTrade(request, toolCallGuard, decisionRunContext)
    }
}

private fun Server.registerSimulateToolTimeoutTool(
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
) {
    addTool(
        name = SIMULATE_TOOL_TIMEOUT_TOOL,
        description = "Sleep without side effects so headless callers can verify timeout handling ends in no-trade.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("delay_ms") {
                    put("type", JSON_TYPE_INTEGER)
                    put("description", "Delay duration in milliseconds.")
                    put("default", DEFAULT_SIMULATED_TIMEOUT_DELAY_MS)
                    put("minimum", MIN_SIMULATED_TIMEOUT_DELAY_MS)
                    put("maximum", MAX_SIMULATED_TIMEOUT_DELAY_MS)
                }
            },
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        handleSimulateToolTimeout(request, toolCallGuard, decisionRunContext)
    }
}

private suspend fun handleGetCandles(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_CANDLES_TOOL, decisionRunContext)
    val candles = toolCallGuard.runReadOnlyTool(call) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val interval = parseCandleInterval(request.arguments?.get("interval")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val limit = parseIntArgument(request, "limit", DEFAULT_CANDLE_LIMIT, MAX_CANDLE_LIMIT).getOrThrow()

        marketDataSource.getCandles(symbol, interval, limit).getOrThrow()
    }

    return candles.fold(
        onSuccess = { value -> candlesResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetOrderbook(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_ORDERBOOK_TOOL, decisionRunContext)
    val orderbook = toolCallGuard.runReadOnlyTool(call) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val depth = parseIntArgument(request, "depth", DEFAULT_ORDERBOOK_DEPTH, MAX_ORDERBOOK_DEPTH).getOrThrow()

        marketDataSource.getOrderbook(symbol, depth).getOrThrow()
    }

    return orderbook.fold(
        onSuccess = { value -> orderbookResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetTrades(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_TRADES_TOOL, decisionRunContext)
    val trades = toolCallGuard.runReadOnlyTool(call) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val limit = parseIntArgument(request, "limit", DEFAULT_TRADES_LIMIT, MAX_TRADES_LIMIT).getOrThrow()

        marketDataSource.getTrades(symbol, limit).getOrThrow()
    }

    return trades.fold(
        onSuccess = { value -> tradesResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetSymbolRules(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_SYMBOL_RULES_TOOL, decisionRunContext)
    val symbolRules = toolCallGuard.runReadOnlyTool(call) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()

        marketDataSource.getSymbolRules(symbol).getOrThrow()
    }

    return symbolRules.fold(
        onSuccess = { value -> symbolRulesResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleCalcIndicator(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(CALC_INDICATOR_TOOL, decisionRunContext)
    val indicator = toolCallGuard.runReadOnlyTool(call) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val interval = parseCandleInterval(request.arguments?.get("interval")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val indicatorType = parseIndicatorType(request.arguments?.get("indicator")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val params = parseIndicatorParams(request).getOrThrow()
        val limit = parseIndicatorCandleLimit(request).getOrThrow()
        val candles = marketDataSource.getCandles(symbol, interval, limit).getOrThrow()
        val result = IndicatorCalculator.calculate(candles, indicatorType, params).getOrThrow()

        IndicatorToolOutput(
            symbol = symbol.apiSymbol,
            interval = interval,
            candleCount = candles.size,
            result = result,
        )
    }

    return indicator.fold(
        onSuccess = { value -> indicatorResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetTicker(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_TICKER_TOOL, decisionRunContext)
    val ticker = toolCallGuard.runReadOnlyTool(call) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()

        marketDataSource.getTicker(symbol).getOrThrow()
    }

    return ticker.fold(
        onSuccess = { value -> tickerResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetBalance(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_BALANCE_TOOL, decisionRunContext)
    val balance = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        tradingRuntime.broker.getBalance().getOrThrow()
    }

    return balance.fold(
        onSuccess = { value -> balanceResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetPositions(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_POSITIONS_TOOL, decisionRunContext)
    val positions = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        tradingRuntime.broker.getPositions().getOrThrow()
    }

    return positions.fold(
        onSuccess = { value -> positionsResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetOpenOrders(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_OPEN_ORDERS_TOOL, decisionRunContext)
    val openOrders = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        tradingRuntime.broker.getOpenOrders().getOrThrow()
    }

    return openOrders.fold(
        onSuccess = { value -> openOrdersResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetAccountStatus(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_ACCOUNT_STATUS_TOOL, decisionRunContext)
    val accountStatus = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        AccountStatusService(tradingRuntime.broker).getAccountStatus().getOrThrow()
    }

    return accountStatus.fold(
        onSuccess = { value -> accountStatusResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handlePlaceOrder(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(PLACE_ORDER_TOOL, decisionRunContext)
    val result = tradingRuntime.toolCallGuard.runTradeTool(call) {
        val command = parsePlaceOrderCommand(request, call).getOrThrow()

        tradingRuntime.broker.placeOrder(command).getOrThrow()
    }

    return result.fold(
        onSuccess = { value -> tradeResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleClosePosition(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(CLOSE_POSITION_TOOL, decisionRunContext)
    val result = tradingRuntime.toolCallGuard.runTradeTool(call) {
        val command = parseClosePositionCommand(request, call).getOrThrow()

        tradingRuntime.broker.closePosition(command).getOrThrow()
    }

    return result.fold(
        onSuccess = { value -> tradeResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleUpdateProtection(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(UPDATE_PROTECTION_TOOL, decisionRunContext)
    val result = tradingRuntime.toolCallGuard.runTradeTool(call) {
        val command = parseUpdateProtectionCommand(request, call).getOrThrow()

        tradingRuntime.broker.updateProtection(command).getOrThrow()
    }

    return result.fold(
        onSuccess = { value -> tradeResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleCancelOrder(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(CANCEL_ORDER_TOOL, decisionRunContext)
    val result = tradingRuntime.toolCallGuard.runTradeTool(call) {
        val command = parseCancelOrderCommand(request, call).getOrThrow()

        tradingRuntime.broker.cancelOrder(command).getOrThrow()
    }

    return result.fold(
        onSuccess = { value -> tradeResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleRejectDummyTrade(
    request: CallToolRequest,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(REJECT_DUMMY_TRADE_TOOL, decisionRunContext)
    val rejected = toolCallGuard.runTradeTool(call) {
        val reason = parseReason(request).getOrThrow()

        throw NoTradeExitException("Step1 dummy trade is reject-only: $reason")
    }

    return rejected.fold(
        onSuccess = { value -> value },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleSimulateToolTimeout(
    request: CallToolRequest,
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(SIMULATE_TOOL_TIMEOUT_TOOL, decisionRunContext)
    val result = toolCallGuard.runReadOnlyTool(call) {
        val delayMs = parseDelayMs(request).getOrThrow()

        delay(delayMs)

        CallToolResult(
            content = listOf(TextContent("Completed simulated wait without side effects.")),
            structuredContent = buildJsonObject {
                put("completed", true)
                put("delay_ms", delayMs)
                put("side_effects", false)
            },
        )
    }

    return result.getOrElse { throwable -> throwableResult(throwable) }
}

private fun toolCallGuard(tradingRuntime: TradingRuntime): ToolCallGuard {
    return tradingRuntime.toolCallGuard
}

private fun CallToolRequest.toGuardedToolCall(
    toolName: String,
    decisionRunContext: DecisionRunContext,
): GuardedToolCall {
    return GuardedToolCall(
        toolName = toolName,
        toolCallId = UUID.randomUUID().toString(),
        clientRequestId = arguments?.get("client_request_id")?.jsonPrimitive?.contentOrNull,
        decisionRunContext = decisionRunContext,
        payload = arguments?.toString() ?: "{}",
    )
}

private fun JsonObjectBuilder.putSymbolSchema() {
    putJsonObject("symbol") {
        put("type", JSON_TYPE_STRING)
        put("description", "Spot symbol. BTC only.")
        put("default", TradingSymbol.BTC.apiSymbol)
    }
}

private fun JsonObjectBuilder.putIntervalSchema() {
    putJsonObject("interval") {
        put("type", JSON_TYPE_STRING)
        put("description", "Candle interval.")
        put("enum", ToolJson.encodeToJsonElement(CandleInterval.entries.map { interval -> interval.apiValue }))
    }
}

private fun JsonObjectBuilder.putDecimalStringSchema(name: String, description: String) {
    putJsonObject(name) {
        put("type", JSON_TYPE_STRING)
        put("description", description)
    }
}

private fun JsonObjectBuilder.putNullableDecimalStringSchema(name: String, description: String) {
    putJsonObject(name) {
        put("type", ToolJson.encodeToJsonElement(listOf(JSON_TYPE_STRING, JSON_TYPE_NULL)))
        put("description", description)
    }
}

private fun JsonObjectBuilder.putReasonSchema() {
    putJsonObject("reason") {
        put("type", JSON_TYPE_STRING)
        put("description", "Required audit reason.")
    }
}

private fun JsonObjectBuilder.putClientRequestIdSchema() {
    putJsonObject("client_request_id") {
        put("type", JSON_TYPE_STRING)
        put("description", "Optional caller-provided request ID.")
    }
}

private fun parseTradingSymbol(rawSymbol: String?): Result<TradingSymbol> {
    val normalizedSymbol = rawSymbol
        ?.trim()
        ?.uppercase()
        ?: TradingSymbol.BTC.apiSymbol

    return runCatching {
        require(normalizedSymbol == TradingSymbol.BTC.apiSymbol) {
            "BTC spot is the only supported symbol: $normalizedSymbol"
        }

        TradingSymbol.BTC
    }
}

private fun parseCandleInterval(rawInterval: String?): Result<CandleInterval> {
    return runCatching {
        val intervalText = rawInterval?.trim().orEmpty()

        require(intervalText.isNotBlank()) {
            "interval is required."
        }

        val interval = CandleInterval.fromApiValue(intervalText.lowercase())
            ?: CandleInterval.entries.firstOrNull { candidate -> candidate.name == intervalText.uppercase() }

        requireNotNull(interval) {
            "interval must be one of ${CandleInterval.entries.joinToString { candidate -> candidate.apiValue }}: $intervalText"
        }
    }
}

private fun parseIndicatorType(rawIndicator: String?): Result<IndicatorType> {
    return runCatching {
        val indicatorText = rawIndicator?.trim().orEmpty()

        require(indicatorText.isNotBlank()) {
            "indicator is required."
        }

        val indicator = IndicatorType.entries.firstOrNull { candidate -> candidate.name == indicatorText.uppercase() }

        requireNotNull(indicator) {
            "indicator must be one of ${IndicatorType.entries.joinToString { candidate -> candidate.name }}: $indicatorText"
        }
    }
}

private fun parseIntArgument(
    request: CallToolRequest,
    name: String,
    defaultValue: Int,
    maxValue: Int,
): Result<Int> {
    val value = request.arguments
        ?.get(name)
        ?.jsonPrimitive
        ?.intOrNull
        ?: defaultValue

    return runCatching {
        val isInRange = value in 1..maxValue

        require(isInRange) {
            "$name must be between 1 and $maxValue: $value"
        }

        value
    }
}

private fun parseIndicatorParams(request: CallToolRequest): Result<IndicatorParams> {
    return runCatching {
        val paramsObject = request.arguments
            ?.get("params")
            ?.jsonObject

        IndicatorParams(
            period = paramsObject.readOptionalInt("period"),
            fastPeriod = paramsObject.readOptionalInt("fast_period", "fastPeriod"),
            slowPeriod = paramsObject.readOptionalInt("slow_period", "slowPeriod"),
            signalPeriod = paramsObject.readOptionalInt("signal_period", "signalPeriod"),
        )
    }
}

private fun parseIndicatorCandleLimit(request: CallToolRequest): Result<Int> {
    return runCatching {
        val paramsObject = request.arguments
            ?.get("params")
            ?.jsonObject
        val limit = paramsObject.readOptionalInt("limit") ?: DEFAULT_INDICATOR_CANDLE_LIMIT
        val isInRange = limit in 1..MAX_INDICATOR_CANDLE_LIMIT

        require(isInRange) {
            "params.limit must be between 1 and $MAX_INDICATOR_CANDLE_LIMIT: $limit"
        }

        limit
    }
}

private fun JsonObject?.readOptionalInt(
    primaryName: String,
    secondaryName: String? = null,
): Int? {
    if (this == null) {
        return null
    }

    val primaryElement = get(primaryName)

    if (primaryElement != null) {
        return primaryElement.jsonPrimitive.intOrNull
            ?: throw IllegalArgumentException("$primaryName must be an integer.")
    }

    if (secondaryName == null) {
        return null
    }

    val secondaryElement = get(secondaryName) ?: return null

    return secondaryElement.jsonPrimitive.intOrNull
        ?: throw IllegalArgumentException("$secondaryName must be an integer.")
}

private fun parseReason(request: CallToolRequest): Result<String> {
    return runCatching {
        val reason = request.arguments
            ?.get("reason")
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()

        require(reason.isNotBlank()) {
            "reason is required."
        }

        reason
    }
}

private fun parsePlaceOrderCommand(request: CallToolRequest, call: GuardedToolCall): Result<PlaceOrderCommand> {
    return runCatching {
        PlaceOrderCommand(
            commandId = UUID.randomUUID(),
            symbol = parseTradingSymbol(request.stringArgument("symbol")).getOrThrow(),
            side = parseOrderSide(request.stringArgument("side")).getOrThrow(),
            orderType = parseOrderType(request.stringArgument("type")).getOrThrow(),
            sizeBtc = parseBigDecimalArgument(request, "size_btc").getOrThrow(),
            priceJpy = parseOptionalBigDecimalArgument(request, "price_jpy").getOrThrow(),
            tradeGroupId = request.stringArgument("trade_group_id")?.let { value -> UUID.fromString(value) },
            protectiveStopPriceJpy = parseBigDecimalArgument(request, "protective_stop_price_jpy").getOrThrow(),
            takeProfitPriceJpy = parseOptionalBigDecimalArgument(request, "take_profit_price_jpy").getOrThrow(),
            estimatedWinProbability = parseBigDecimalArgument(request, "estimated_win_probability").getOrThrow(),
            reasonJa = parseReason(request).getOrThrow(),
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        )
    }
}

private fun parseClosePositionCommand(request: CallToolRequest, call: GuardedToolCall): Result<ClosePositionCommand> {
    return runCatching {
        ClosePositionCommand(
            commandId = UUID.randomUUID(),
            positionId = request.stringArgument("position_id")?.let { value -> UUID.fromString(value) },
            closeAll = parseBooleanArgument(request, "all", defaultValue = false),
            reasonJa = parseReason(request).getOrThrow(),
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        )
    }
}

private fun parseUpdateProtectionCommand(request: CallToolRequest, call: GuardedToolCall): Result<UpdateProtectionCommand> {
    return runCatching {
        val takeProfitPriceSpecified = request.arguments?.containsKey("new_take_profit_price_jpy") == true

        UpdateProtectionCommand(
            commandId = UUID.randomUUID(),
            positionId = UUID.fromString(requireNotNull(request.stringArgument("position_id")) { "position_id is required." }),
            newStopPriceJpy = parseOptionalBigDecimalArgument(request, "new_stop_price_jpy").getOrThrow(),
            takeProfitPriceSpecified = takeProfitPriceSpecified,
            newTakeProfitPriceJpy = parseOptionalBigDecimalArgument(request, "new_take_profit_price_jpy").getOrThrow(),
            reasonJa = parseReason(request).getOrThrow(),
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        )
    }
}

private fun parseCancelOrderCommand(request: CallToolRequest, call: GuardedToolCall): Result<CancelOrderCommand> {
    return runCatching {
        CancelOrderCommand(
            commandId = UUID.randomUUID(),
            orderId = UUID.fromString(requireNotNull(request.stringArgument("order_id")) { "order_id is required." }),
            reasonJa = parseReason(request).getOrThrow(),
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        )
    }
}

private fun parseOrderSide(rawSide: String?): Result<OrderSide> {
    return runCatching {
        val sideText = rawSide?.trim().orEmpty()

        require(sideText.isNotBlank()) {
            "side is required."
        }

        OrderSide.valueOf(sideText.uppercase())
    }
}

private fun parseOrderType(rawType: String?): Result<OrderType> {
    return runCatching {
        val typeText = rawType?.trim().orEmpty()

        require(typeText.isNotBlank()) {
            "type is required."
        }

        OrderType.valueOf(typeText.uppercase())
    }
}

private fun parseBigDecimalArgument(request: CallToolRequest, name: String): Result<BigDecimal> {
    return runCatching {
        val value = request.stringArgument(name).orEmpty()

        require(value.isNotBlank()) {
            "$name is required."
        }

        value.toBigDecimal()
    }
}

private fun parseOptionalBigDecimalArgument(request: CallToolRequest, name: String): Result<BigDecimal?> {
    return runCatching {
        val value = request.stringArgument(name) ?: return@runCatching null

        if (value.isBlank()) {
            return@runCatching null
        }

        value.toBigDecimal()
    }
}

private fun parseBooleanArgument(request: CallToolRequest, name: String, defaultValue: Boolean): Boolean {
    val rawValue = request.arguments
        ?.get(name)
        ?.jsonPrimitive
        ?.contentOrNull

    return rawValue?.toBooleanStrictOrNull() ?: defaultValue
}

private fun CallToolRequest.stringArgument(name: String): String? {
    return arguments
        ?.get(name)
        ?.jsonPrimitive
        ?.contentOrNull
}

private fun parseDelayMs(request: CallToolRequest): Result<Long> {
    val delayMs = request.arguments
        ?.get("delay_ms")
        ?.jsonPrimitive
        ?.longOrNull
        ?: DEFAULT_SIMULATED_TIMEOUT_DELAY_MS

    return runCatching {
        val isInRange = delayMs in MIN_SIMULATED_TIMEOUT_DELAY_MS..MAX_SIMULATED_TIMEOUT_DELAY_MS

        require(isInRange) {
            "delay_ms must be between $MIN_SIMULATED_TIMEOUT_DELAY_MS and $MAX_SIMULATED_TIMEOUT_DELAY_MS: $delayMs"
        }

        delayMs
    }
}

private fun tickerResult(ticker: Ticker): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(ticker).jsonObject

    return CallToolResult(
        content = listOf(TextContent(ToolJson.encodeToString(ticker))),
        structuredContent = structuredContent,
    )
}

private fun candlesResult(candles: List<Candle>): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("count", candles.size)
            put("candles", ToolJson.encodeToJsonElement(candles))
        },
    )
}

private fun orderbookResult(orderbook: Orderbook): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(orderbook).jsonObject

    return jsonObjectResult(structuredContent)
}

private fun tradesResult(trades: List<RecentTrade>): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("count", trades.size)
            put("trades", ToolJson.encodeToJsonElement(trades))
        },
    )
}

private fun symbolRulesResult(symbolRules: SymbolRules): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(symbolRules).jsonObject

    return jsonObjectResult(structuredContent)
}

private fun indicatorResult(output: IndicatorToolOutput): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("symbol", output.symbol)
            put("interval", output.interval.apiValue)
            put("candle_count", output.candleCount)
            put("indicator", ToolJson.encodeToJsonElement(output.result.indicator))
            put("params", ToolJson.encodeToJsonElement(output.result.params))
            put("values", ToolJson.encodeToJsonElement(output.result.values))
        },
    )
}

private fun balanceResult(balance: AccountSnapshot): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(balance).jsonObject

    return jsonObjectResult(structuredContent)
}

private fun positionsResult(positions: List<Position>): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("count", positions.size)
            put("positions", ToolJson.encodeToJsonElement(positions))
        },
    )
}

private fun openOrdersResult(openOrders: List<Order>): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("count", openOrders.size)
            put("orders", ToolJson.encodeToJsonElement(openOrders))
        },
    )
}

private fun accountStatusResult(accountStatus: AccountStatus): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(accountStatus).jsonObject

    return CallToolResult(
        content = listOf(TextContent(ToolJson.encodeToString(accountStatus))),
        structuredContent = structuredContent,
    )
}

private fun tradeResult(result: PaperTradeResult): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("accepted", result.accepted)
            put("status", result.status.name)
            put("order_ids", ToolJson.encodeToJsonElement(result.orderIds))
            put("position_ids", ToolJson.encodeToJsonElement(result.positionIds))
            put("execution_ids", ToolJson.encodeToJsonElement(result.executionIds))
            put("message", result.messageJa)
            result.safetyViolation?.let { violation ->
                putJsonObject("safety_violation") {
                    put("id", violation.id.toString())
                    put("rule", violation.rule.name)
                    put("message", violation.messageJa)
                    put("measured_value", violation.measuredValue)
                    put("limit_value", violation.limitValue)
                    put("hard_halt_required", violation.hardHaltRequired)
                }
            }
        },
    )
}

private fun jsonObjectResult(structuredContent: JsonObject): CallToolResult {
    return CallToolResult(
        content = listOf(TextContent(structuredContent.toString())),
        structuredContent = structuredContent,
    )
}

private fun throwableResult(throwable: Throwable): CallToolResult {
    val type = when (throwable) {
        is HardHaltTradingRejectedException -> "hard_halt"
        is NoTradeExitException -> "no_trade"
        is ToolCompletionAuditFailedException -> "audit_failed_after_execution"
        is MarketInvalidRequestException -> "invalid_request"
        is GmoRateLimitException -> "rate_limited"
        is GmoApiStatusException -> "gmo_status_error"
        is GmoHttpException -> "gmo_http_error"
        is MarketNetworkException -> "network_error"
        is MarketDataParseException -> "market_data_parse_error"
        is IllegalArgumentException -> "invalid_request"
        else -> "tool_call_failed"
    }
    val executed = if (throwable is ToolCompletionAuditFailedException) throwable.executed else null

    return errorResult(type, throwable.message.orEmpty(), executed)
}

private fun errorResult(type: String, message: String, executed: Boolean? = null): CallToolResult {
    val resolvedMessage = message.ifBlank { "unknown error" }

    return CallToolResult(
        content = listOf(TextContent(resolvedMessage)),
        structuredContent = buildJsonObject {
            put("error", true)
            put("type", type)
            put("message", resolvedMessage)
            if (executed != null) {
                put("executed", executed)
            }
        },
        isError = true,
    )
}

/**
 * calc_indicator の MCP handler 内部出力。
 *
 * @param symbol 取引対象 symbol
 * @param interval ローソク足 interval
 * @param candleCount 計算に使った candle 数
 * @param result indicator 計算結果
 */
@Serializable
private data class IndicatorToolOutput(
    val symbol: String,
    val interval: CandleInterval,
    val candleCount: Int,
    val result: IndicatorResult,
)
