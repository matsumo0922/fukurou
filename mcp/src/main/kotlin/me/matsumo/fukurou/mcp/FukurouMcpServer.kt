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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.matsumo.fukurou.mcp.gmo.DescribedGmoCoinKlineRequestBudgetHook
import me.matsumo.fukurou.mcp.gmo.GmoCoinMarketToolErrorResponse
import me.matsumo.fukurou.mcp.gmo.GmoCoinMarketToolExecutor
import me.matsumo.fukurou.mcp.gmo.registerGmoCoinMarketTools
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GMO_MAX_DAILY_KLINE_REQUESTS
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.market.GmoApiStatusException
import me.matsumo.fukurou.trading.market.GmoHttpException
import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.MarketDataException
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
private const val MCP_SERVER_NAME = "fukurou-mcp"

/**
 * MCP server version。
 */
private const val MCP_SERVER_VERSION = "0.1.0"

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
 * MCP stdio smoke で DB なし in-memory runtime を明示許可する環境変数名。
 */
private const val FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME_ENV = "FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME"

/**
 * MCP stdio smoke で DB なし in-memory runtime を明示許可する system property 名。
 */
private const val FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME_PROPERTY = "fukurou.mcp.testInMemoryRuntime"

/**
 * JSON schema の string 型。
 */
private const val JSON_TYPE_STRING = "string"

/**
 * JSON schema の integer 型。
 */
private const val JSON_TYPE_INTEGER = "integer"

/**
 * JSON schema の null 型。
 */
private const val JSON_TYPE_NULL = "null"

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
 * @param tradingConfig 取引 bot 全体の typed config
 * @param marketDataSource 市場データ取得元
 * @param tradingRuntime 取引 runtime
 * @param decisionRunContext 呼び出し元の decision run context
 */
class FukurouMcpServer(
    tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(),
    private val marketDataSource: MarketDataSource = GmoPublicMarketDataSource.fromConfig(tradingConfig.gmoPublicClient),
    private val tradingRuntime: TradingRuntime = defaultTradingRuntime(tradingConfig, marketDataSource),
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

        server.registerGmoCoinMarketTools(
            marketDataSource = marketDataSource,
            toolExecutor = AuditedGmoCoinMarketToolExecutor(
                toolCallGuard = tradingRuntime.toolCallGuard,
                decisionRunContext = decisionRunContext,
            ),
            klineRequestBudgetHook = DescribedGmoCoinKlineRequestBudgetHook(GMO_MAX_DAILY_KLINE_REQUESTS),
        )
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

/**
 * GMO Coin market tool を fukurou の audit / no-trade guard 付きで実行する adapter。
 *
 * @param toolCallGuard fukurou runtime の tool call guard
 * @param decisionRunContext 呼び出し元の decision run context
 */
private class AuditedGmoCoinMarketToolExecutor(
    private val toolCallGuard: ToolCallGuard,
    private val decisionRunContext: DecisionRunContext,
) : GmoCoinMarketToolExecutor {
    override suspend fun <T> execute(
        toolName: String,
        request: CallToolRequest,
        block: suspend () -> T,
    ): Result<T> {
        val call = request.toGuardedToolCall(toolName, decisionRunContext)

        return toolCallGuard.runReadOnlyTool(call, block)
    }

    override fun errorResponse(throwable: Throwable): GmoCoinMarketToolErrorResponse? {
        if (throwable !is ToolCompletionAuditFailedException) {
            return null
        }

        return GmoCoinMarketToolErrorResponse(
            type = "audit_failed_after_execution",
            executed = throwable.executed,
        )
    }
}

private fun defaultTradingRuntime(
    tradingConfig: TradingBotConfig,
    marketDataSource: MarketDataSource,
): TradingRuntime {
    if (useTestInMemoryRuntime()) {
        return TradingRuntimeFactory.inMemory(
            marketDataSource = marketDataSource,
            tradingConfig = tradingConfig,
        )
    }

    return TradingRuntimeFactory.fromEnvironment(
        marketDataSource = marketDataSource,
        tradingConfig = tradingConfig,
    )
}

private fun useTestInMemoryRuntime(): Boolean {
    val environmentEnabled = System.getenv(FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME_ENV)
        ?.toBooleanStrictOrNull()
        ?: false
    val propertyEnabled = System.getProperty(FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME_PROPERTY)
        ?.toBooleanStrictOrNull()
        ?: false

    return environmentEnabled && propertyEnabled
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
                putDecimalStringSchema("take_profit_price_jpy", "Required virtual take-profit trigger price for SafetyFloor EV calculation.")
                putDecimalStringSchema("estimated_win_probability", "Estimated win probability from 0 to 1. SafetyFloor calculates EV from this value.")
                putReasonSchema()
                putClientRequestIdSchema()
            },
            required = listOf(
                "side",
                "type",
                "size_btc",
                "protective_stop_price_jpy",
                "take_profit_price_jpy",
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

    val failureKind = (throwable as? MarketDataException)?.kind?.name?.lowercase()

    return errorResult(type, throwable.message.orEmpty(), executed, failureKind)
}

private fun errorResult(
    type: String,
    message: String,
    executed: Boolean? = null,
    failureKind: String? = null,
): CallToolResult {
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
            if (failureKind != null) {
                put("failure_kind", failureKind)
            }
        },
        isError = true,
    )
}
