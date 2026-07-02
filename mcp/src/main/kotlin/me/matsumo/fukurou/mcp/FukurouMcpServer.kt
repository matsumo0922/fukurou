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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.risk.AccountStatus
import me.matsumo.fukurou.trading.risk.AccountStatusService
import me.matsumo.fukurou.trading.risk.HardHaltTradingRejectedException
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import me.matsumo.fukurou.trading.tool.NoTradeExitException
import me.matsumo.fukurou.trading.tool.ToolCallGuard
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
 * account status 取得 tool 名。
 */
private const val GET_ACCOUNT_STATUS_TOOL = "get_account_status"

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
    private val tradingRuntime: TradingRuntime = TradingRuntimeFactory.fromEnvironmentOrInMemory(),
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

    private fun createServer(): Server {
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
        server.registerAccountStatusTool(tradingRuntime, decisionRunContext)
        server.registerRejectDummyTradeTool(tradingRuntime.toolCallGuard, decisionRunContext)
        server.registerSimulateToolTimeoutTool(tradingRuntime.toolCallGuard, decisionRunContext)

        return server
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
                    put("description", "Spot symbol. Step1 supports BTC only.")
                    put("default", TradingSymbol.BTC.apiSymbol)
                }
            },
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        handleGetTicker(request, marketDataSource, toolCallGuard, decisionRunContext)
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

private suspend fun handleGetAccountStatus(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
): CallToolResult {
    val call = request.toGuardedToolCall(GET_ACCOUNT_STATUS_TOOL, decisionRunContext)
    val accountStatus = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        AccountStatusService(tradingRuntime.riskStateRepository).getAccountStatus().getOrThrow()
    }

    return accountStatus.fold(
        onSuccess = { value -> accountStatusResult(value) },
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

private fun parseTradingSymbol(rawSymbol: String?): Result<TradingSymbol> {
    val normalizedSymbol = rawSymbol
        ?.trim()
        ?.uppercase()
        ?: TradingSymbol.BTC.apiSymbol

    return runCatching {
        require(normalizedSymbol == TradingSymbol.BTC.apiSymbol) {
            "Step1 supports BTC only: $normalizedSymbol"
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

private fun accountStatusResult(accountStatus: AccountStatus): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(accountStatus).jsonObject

    return CallToolResult(
        content = listOf(TextContent(ToolJson.encodeToString(accountStatus))),
        structuredContent = structuredContent,
    )
}

private fun throwableResult(throwable: Throwable): CallToolResult {
    val type = when (throwable) {
        is HardHaltTradingRejectedException -> "hard_halt"
        is NoTradeExitException -> "no_trade"
        is IllegalArgumentException -> "invalid_request"
        else -> "tool_call_failed"
    }

    return errorResult(type, throwable.message.orEmpty())
}

private fun errorResult(type: String, message: String): CallToolResult {
    val resolvedMessage = message.ifBlank { "unknown error" }

    return CallToolResult(
        content = listOf(TextContent(resolvedMessage)),
        structuredContent = buildJsonObject {
            put("error", true)
            put("type", type)
            put("message", resolvedMessage)
        },
        isError = true,
    )
}
