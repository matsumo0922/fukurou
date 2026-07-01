package me.matsumo.fukurou.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.market.MarketDataSource

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
 * trade 風 dummy 拒否 tool 名。
 */
private const val REJECT_DUMMY_TRADE_TOOL = "reject_dummy_trade"

/**
 * JSON schema の string 型。
 */
private const val JSON_TYPE_STRING = "string"

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
            val session = server.createSession(transport)
            val done = Job()
            session.onClose {
                done.complete()
            }
            done.join()
            transportScope.cancel()
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

        server.registerTickerTool(marketDataSource)
        server.registerRejectDummyTradeTool()

        return server
    }
}

private fun Server.registerTickerTool(marketDataSource: MarketDataSource) {
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
        val symbolResult = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull)

        symbolResult.fold(
            onSuccess = { symbol ->
                marketDataSource.getTicker(symbol).fold(
                    onSuccess = { ticker -> tickerResult(ticker) },
                    onFailure = { throwable -> errorResult("gmo_public_error", throwable.message.orEmpty()) },
                )
            },
            onFailure = { throwable -> errorResult("invalid_symbol", throwable.message.orEmpty()) },
        )
    }
}

private fun Server.registerRejectDummyTradeTool() {
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
    ) {
        CallToolResult(
            content = listOf(TextContent("Rejected: Step1 dummy trade never performs side effects.")),
            structuredContent = buildJsonObject {
                put("accepted", false)
                put("reason", "Step1 dummy trade is reject-only.")
            },
            isError = true,
        )
    }
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

private fun tickerResult(ticker: Ticker): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(ticker).jsonObject

    return CallToolResult(
        content = listOf(TextContent(ToolJson.encodeToString(ticker))),
        structuredContent = structuredContent,
    )
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
