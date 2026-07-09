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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.matsumo.fukurou.mcp.gmo.DescribedGmoCoinKlineRequestBudgetHook
import me.matsumo.fukurou.mcp.gmo.GmoCoinMarketToolErrorResponse
import me.matsumo.fukurou.mcp.gmo.GmoCoinMarketToolExecutor
import me.matsumo.fukurou.mcp.gmo.registerGmoCoinMarketTools
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.AccountSnapshotWithUpdatedAt
import me.matsumo.fukurou.trading.broker.AccountStatusWithUpdatedAt
import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.OpenOrdersWithUpdatedAt
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.PositionsWithUpdatedAt
import me.matsumo.fukurou.trading.broker.PreviewOrderResult
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.broker.toJsonObject
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.TradeIntentReviewSnapshot
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.requiresEntryIntent
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GMO_MAX_DAILY_KLINE_REQUESTS
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.knowledge.DEFAULT_KNOWLEDGE_RECENT_LESSONS_LIMIT
import me.matsumo.fukurou.trading.knowledge.DEFAULT_KNOWLEDGE_RECENT_LESSONS_LOOKBACK_DAYS
import me.matsumo.fukurou.trading.knowledge.DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LIMIT
import me.matsumo.fukurou.trading.knowledge.DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LOOKBACK_DAYS
import me.matsumo.fukurou.trading.knowledge.KnowledgeDecisionOutcome
import me.matsumo.fukurou.trading.knowledge.KnowledgeFailurePattern
import me.matsumo.fukurou.trading.knowledge.KnowledgeFalsificationSummary
import me.matsumo.fukurou.trading.knowledge.KnowledgeLesson
import me.matsumo.fukurou.trading.knowledge.KnowledgeRecentLessonsQuery
import me.matsumo.fukurou.trading.knowledge.KnowledgeRecentLessonsResult
import me.matsumo.fukurou.trading.knowledge.KnowledgeRunSummary
import me.matsumo.fukurou.trading.knowledge.KnowledgeService
import me.matsumo.fukurou.trading.knowledge.KnowledgeSetupPerformanceSummary
import me.matsumo.fukurou.trading.knowledge.KnowledgeSimilarSetupHit
import me.matsumo.fukurou.trading.knowledge.KnowledgeSimilarSetupsQuery
import me.matsumo.fukurou.trading.knowledge.KnowledgeSimilarSetupsResult
import me.matsumo.fukurou.trading.knowledge.KnowledgeTradePlanSummary
import me.matsumo.fukurou.trading.knowledge.MAX_KNOWLEDGE_LOOKBACK_DAYS
import me.matsumo.fukurou.trading.knowledge.MAX_KNOWLEDGE_RECENT_LESSONS_LIMIT
import me.matsumo.fukurou.trading.knowledge.MAX_KNOWLEDGE_SIMILAR_SETUPS_LIMIT
import me.matsumo.fukurou.trading.market.FreshnessDefaults
import me.matsumo.fukurou.trading.market.FreshnessMetadata
import me.matsumo.fukurou.trading.market.FreshnessSource
import me.matsumo.fukurou.trading.market.GmoApiStatusException
import me.matsumo.fukurou.trading.market.GmoHttpException
import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.MarketDataException
import me.matsumo.fukurou.trading.market.MarketDataParseException
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.market.MarketInvalidRequestException
import me.matsumo.fukurou.trading.market.MarketNetworkException
import me.matsumo.fukurou.trading.market.withFreshness
import me.matsumo.fukurou.trading.risk.AccountStatusService
import me.matsumo.fukurou.trading.risk.HardHaltTradingRejectedException
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import me.matsumo.fukurou.trading.tool.NoTradeExitException
import me.matsumo.fukurou.trading.tool.ToolCallGuard
import me.matsumo.fukurou.trading.tool.ToolCompletionAuditFailedException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

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
 * trade intent review 取得 tool 名。
 */
private const val GET_TRADE_INTENT_TOOL = "get_trade_intent"

/**
 * recent lessons 取得 tool 名。
 */
private const val KNOWLEDGE_GET_RECENT_LESSONS_TOOL = "knowledge_get_recent_lessons"

/**
 * similar setup search tool 名。
 */
private const val KNOWLEDGE_SEARCH_SIMILAR_SETUPS_TOOL = "knowledge_search_similar_setups"

/**
 * paper entry 発注 preview tool 名。
 */
private const val PREVIEW_ORDER_TOOL = "preview_order"

/**
 * paper entry 発注 tool 名。
 */
private const val PLACE_ORDER_TOOL = "place_order"

/**
 * LLM 判断提出 tool 名。
 */
private const val SUBMIT_DECISION_TOOL = "submit_decision"

/**
 * LLM 判断提出 tool の説明。
 */
private const val SUBMIT_DECISION_DESCRIPTION = "Submit the structured LLM decision. " +
    "ENTER and ADD_LONG create a trade intent and TradePlan; REDUCE requires close_ratio."

/**
 * submit_decision.close_ratio schema の説明。
 */
private const val SUBMIT_DECISION_CLOSE_RATIO_DESCRIPTION = "Position close ratio for REDUCE. " +
    "Decimal string with 0 < close_ratio <= 1.00."

/**
 * submit_decision.expected_r_multiple schema の説明。
 */
private const val SUBMIT_DECISION_EXPECTED_R_DESCRIPTION = "Required expected R for every action. " +
    "Submit 0 when no setup or managed-plan residual R is unavailable; negative values are valid."

/**
 * Falsifier verdict 提出 tool 名。
 */
private const val SUBMIT_FALSIFICATION_TOOL = "submit_falsification"

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
 * MCP server が公開する tool 名一覧。
 */
private val MCP_TOOL_NAMES = setOf(
    "get_ticker",
    "get_candles",
    "get_orderbook",
    "get_trades",
    "get_symbol_rules",
    "calc_indicator",
    GET_BALANCE_TOOL,
    GET_POSITIONS_TOOL,
    GET_OPEN_ORDERS_TOOL,
    GET_ACCOUNT_STATUS_TOOL,
    GET_TRADE_INTENT_TOOL,
    KNOWLEDGE_GET_RECENT_LESSONS_TOOL,
    KNOWLEDGE_SEARCH_SIMILAR_SETUPS_TOOL,
    SUBMIT_DECISION_TOOL,
    SUBMIT_FALSIFICATION_TOOL,
    PREVIEW_ORDER_TOOL,
    PLACE_ORDER_TOOL,
    CLOSE_POSITION_TOOL,
    UPDATE_PROTECTION_TOOL,
    CANCEL_ORDER_TOOL,
    REJECT_DUMMY_TRADE_TOOL,
    SIMULATE_TOOL_TIMEOUT_TOOL,
)

/**
 * act tool call 上限として数える tool 名一覧。
 */
private val MCP_ACT_TOOL_NAMES = setOf(
    PLACE_ORDER_TOOL,
    CLOSE_POSITION_TOOL,
    UPDATE_PROTECTION_TOOL,
    CANCEL_ORDER_TOOL,
    REJECT_DUMMY_TRADE_TOOL,
)

/**
 * MCP stdio smoke で DB なし in-memory runtime を明示許可する環境変数名。
 */
private const val FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME_ENV = "FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME"

/**
 * MCP stdio smoke で DB なし in-memory runtime を明示許可する system property 名。
 */
private const val FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME_PROPERTY = "fukurou.mcp.testInMemoryRuntime"

/**
 * MCP server instance 内で許可する tool 名 allowlist の環境変数名。
 */
private const val FUKUROU_MCP_ALLOWED_TOOLS_ENV = "FUKUROU_MCP_ALLOWED_TOOLS"

/**
 * place_order / preview_order の必須引数。
 */
private val PLACE_ORDER_REQUIRED_ARGUMENTS = listOf(
    "intent_id",
    "side",
    "type",
    "size_btc",
    "protective_stop_price_jpy",
    "take_profit_price_jpy",
    "estimated_win_probability",
    "reason",
)

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
 * MCP error response の type に写像する例外型一覧。
 */
private val ToolErrorTypes: List<Pair<KClass<out Throwable>, String>> = listOf(
    HardHaltTradingRejectedException::class to "hard_halt",
    NoTradeExitException::class to "no_trade",
    ToolCallLimitExceededException::class to "tool_call_limit_exceeded",
    ToolCallLimitUnavailableException::class to "tool_call_limit_unavailable",
    ToolCallNotAllowedException::class to "tool_call_not_allowed",
    ToolCompletionAuditFailedException::class to "audit_failed_after_execution",
    MarketInvalidRequestException::class to "invalid_request",
    GmoRateLimitException::class to "rate_limited",
    GmoApiStatusException::class to "gmo_status_error",
    GmoHttpException::class to "gmo_http_error",
    MarketNetworkException::class to "network_error",
    MarketDataParseException::class to "market_data_parse_error",
    IllegalArgumentException::class to "invalid_request",
)

/**
 * MCP tool の登録情報。
 *
 * @param name tool 名
 * @param description tool description
 * @param inputSchema tool input schema
 * @param toolAnnotations MCP tool annotations
 * @param kind tool call 上限で扱う種別
 */
private data class LimitedToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: ToolSchema,
    val toolAnnotations: ToolAnnotations,
    val kind: McpToolCallKind,
)

/**
 * MCP tool の上限制御で共有する実行文脈。
 *
 * @param decisionRunContext 呼び出し元の decision run context
 * @param toolCallLimiter tool call 上限制御
 */
private data class LimitedToolContext(
    val decisionRunContext: DecisionRunContext,
    val toolCallLimiter: McpToolCallLimiter,
)

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
 * @param clock response 鮮度 metadata と default runtime を作る clock
 * @param tradingRuntime 取引 runtime
 * @param knowledgeService Knowledge read-only tool 用 service
 * @param decisionRunContext 呼び出し元の decision run context
 */
class FukurouMcpServer(
    tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(),
    private val marketDataSource: MarketDataSource = GmoPublicMarketDataSource.fromConfig(tradingConfig.gmoPublicClient),
    private val clock: Clock = Clock.systemUTC(),
    private val tradingRuntime: TradingRuntime = defaultTradingRuntime(
        tradingConfig = tradingConfig,
        marketDataSource = marketDataSource,
        clock = clock,
    ),
    private val knowledgeService: KnowledgeService = KnowledgeService(
        decisionRepository = tradingRuntime.decisionRepository,
        llmRunRepository = tradingRuntime.llmRunRepository,
        evaluationRepository = tradingRuntime.evaluationRepository,
        clock = clock,
    ),
    private val decisionRunContext: DecisionRunContext = DecisionRunContext.fromEnvironment(),
    private val toolCallLimiter: McpToolCallLimiter = McpToolCallLimiter(
        config = tradingConfig.runner,
        toolCallGuard = tradingRuntime.toolCallGuard,
        allowedToolNames = mcpAllowedToolNamesFromEnvironment(),
        countedToolNames = MCP_TOOL_NAMES,
        actToolNames = MCP_ACT_TOOL_NAMES,
    ),
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
        val server = createMcpServer()

        registerTools(server)

        return server
    }

    private fun createMcpServer(): Server {
        return Server(
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
    }

    private fun registerTools(server: Server) {
        registerMarketDataTools(server)
        registerReadOnlyTools(server)
        registerDecisionTools(server)
        registerTradeTools(server)
        registerDiagnosticTools(server)
    }

    private fun registerMarketDataTools(server: Server) {
        server.registerGmoCoinMarketTools(
            marketDataSource = marketDataSource,
            toolExecutor = AuditedGmoCoinMarketToolExecutor(
                toolCallGuard = tradingRuntime.toolCallGuard,
                decisionRunContext = decisionRunContext,
                toolCallLimiter = toolCallLimiter,
            ),
            klineRequestBudgetHook = DescribedGmoCoinKlineRequestBudgetHook(GMO_MAX_DAILY_KLINE_REQUESTS),
            clock = clock,
        )
    }

    private fun registerReadOnlyTools(server: Server) {
        server.registerBalanceTool(
            tradingRuntime = tradingRuntime,
            decisionRunContext = decisionRunContext,
            toolCallLimiter = toolCallLimiter,
            clock = clock,
        )
        server.registerPositionsTool(
            tradingRuntime = tradingRuntime,
            decisionRunContext = decisionRunContext,
            toolCallLimiter = toolCallLimiter,
            clock = clock,
        )
        server.registerOpenOrdersTool(
            tradingRuntime = tradingRuntime,
            decisionRunContext = decisionRunContext,
            toolCallLimiter = toolCallLimiter,
            clock = clock,
        )
        server.registerAccountStatusTool(
            tradingRuntime = tradingRuntime,
            decisionRunContext = decisionRunContext,
            toolCallLimiter = toolCallLimiter,
            clock = clock,
        )
        server.registerKnowledgeRecentLessonsTool(
            tradingRuntime = tradingRuntime,
            knowledgeService = knowledgeService,
            decisionRunContext = decisionRunContext,
            toolCallLimiter = toolCallLimiter,
        )
        server.registerKnowledgeSimilarSetupsTool(
            tradingRuntime = tradingRuntime,
            knowledgeService = knowledgeService,
            decisionRunContext = decisionRunContext,
            toolCallLimiter = toolCallLimiter,
        )
        server.registerTradeIntentTool(tradingRuntime, decisionRunContext, toolCallLimiter)
    }

    private fun registerDecisionTools(server: Server) {
        server.registerSubmitDecisionTool(tradingRuntime, decisionRunContext, toolCallLimiter)
        server.registerSubmitFalsificationTool(tradingRuntime, decisionRunContext, toolCallLimiter)
    }

    private fun registerTradeTools(server: Server) {
        server.registerPreviewOrderTool(tradingRuntime, decisionRunContext, toolCallLimiter)
        server.registerPlaceOrderTool(tradingRuntime, decisionRunContext, toolCallLimiter)
        server.registerClosePositionTool(tradingRuntime, decisionRunContext, toolCallLimiter)
        server.registerUpdateProtectionTool(tradingRuntime, decisionRunContext, toolCallLimiter)
        server.registerCancelOrderTool(tradingRuntime, decisionRunContext, toolCallLimiter)
    }

    private fun registerDiagnosticTools(server: Server) {
        server.registerRejectDummyTradeTool(tradingRuntime.toolCallGuard, decisionRunContext, toolCallLimiter)
        server.registerSimulateToolTimeoutTool(tradingRuntime.toolCallGuard, decisionRunContext, toolCallLimiter)
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
    private val toolCallLimiter: McpToolCallLimiter,
) : GmoCoinMarketToolExecutor {
    override suspend fun <T> execute(
        toolName: String,
        request: CallToolRequest,
        block: suspend () -> T,
    ): Result<T> {
        val call = request.toGuardedToolCall(toolName, decisionRunContext)
        toolCallLimiter.acquire(call, McpToolCallKind.READ_ONLY).getOrElse { throwable ->
            return Result.failure(throwable)
        }

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
    clock: Clock,
): TradingRuntime {
    if (useTestInMemoryRuntime()) {
        return TradingRuntimeFactory.inMemory(
            marketDataSource = marketDataSource,
            tradingConfig = tradingConfig,
            clock = clock,
        )
    }

    return TradingRuntimeFactory.fromEnvironment(
        marketDataSource = marketDataSource,
        tradingConfig = tradingConfig,
        clock = clock,
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

private fun mcpAllowedToolNamesFromEnvironment(): Set<String>? {
    return System.getenv(FUKUROU_MCP_ALLOWED_TOOLS_ENV)
        ?.split(",")
        ?.map { toolName -> toolName.trim() }
        ?.filter { toolName -> toolName.isNotBlank() }
        ?.toSet()
        ?.takeIf { toolNames -> toolNames.isNotEmpty() }
}

private fun Server.addLimitedTool(
    definition: LimitedToolDefinition,
    context: LimitedToolContext,
    handler: suspend (CallToolRequest, GuardedToolCall) -> CallToolResult,
) {
    addTool(
        name = definition.name,
        description = definition.description,
        inputSchema = definition.inputSchema,
        toolAnnotations = definition.toolAnnotations,
    ) { request ->
        handleLimitedTool(
            request = request,
            definition = definition,
            context = context,
            handler = handler,
        )
    }
}

private suspend fun handleLimitedTool(
    request: CallToolRequest,
    definition: LimitedToolDefinition,
    context: LimitedToolContext,
    handler: suspend (CallToolRequest, GuardedToolCall) -> CallToolResult,
): CallToolResult {
    val call = request.toGuardedToolCall(definition.name, context.decisionRunContext)
    val limitError = limitErrorOrNull(context.toolCallLimiter, call, definition.kind)

    if (limitError != null) {
        return limitError
    }

    return handler(request, call)
}

private fun limitedToolContext(
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
): LimitedToolContext {
    return LimitedToolContext(
        decisionRunContext = decisionRunContext,
        toolCallLimiter = toolCallLimiter,
    )
}

private fun Server.registerSubmitDecisionTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = SUBMIT_DECISION_TOOL,
            description = SUBMIT_DECISION_DESCRIPTION,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("action") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "ENTER, EXIT, REDUCE, ADD_LONG, ADJUST_PROTECTION, or NO_TRADE.")
                        put("enum", ToolJson.encodeToJsonElement(DecisionAction.entries.map { action -> action.name }))
                    }
                    putDecimalStringSchema(
                        name = "close_ratio",
                        description = SUBMIT_DECISION_CLOSE_RATIO_DESCRIPTION,
                    )
                    putStringArraySchema("setup_tags", "Setup taxonomy tags. Required for ENTER and ADD_LONG.")
                    putDecimalStringSchema("estimated_win_probability", "Estimated win probability from 0 to 1.")
                    putDecimalStringSchema("expected_r_multiple", SUBMIT_DECISION_EXPECTED_R_DESCRIPTION)
                    putDecimalStringSchema("round_trip_cost_r", "Round-trip cost expressed in R.")
                    putStringArraySchema("tool_evidence_ids", "Tool call IDs used as decision evidence.")
                    putJsonObject("fact_check") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "Fact-check JSON string.")
                    }
                    putJsonObject("self_review") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "Self-review JSON string.")
                    }
                    putJsonObject("reason_ja") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "Decision reason in Japanese.")
                    }
                    putStringArraySchema("missing_data_ja", "Missing data list for NO_TRADE and calibration.")
                    putStringArraySchema("no_trade_conditions_ja", "Conditions to wait for before trading.")
                    putEntryIntentDecisionSchemas()
                    putTradePlanDecisionSchemas()
                    putClientRequestIdSchema()
                },
                required = listOf(
                    "action",
                    "estimated_win_probability",
                    "expected_r_multiple",
                    "fact_check",
                    "self_review",
                    "reason_ja",
                ),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
            kind = McpToolCallKind.DECISION,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleSubmitDecision(request, tradingRuntime, decisionRunContext, call)
    }
}

private fun Server.registerSubmitFalsificationTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = SUBMIT_FALSIFICATION_TOOL,
            description = "Submit an APPROVED or REJECTED Falsifier verdict for one trade intent.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("intent_id") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "Trade intent UUID.")
                    }
                    putJsonObject("verdict") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "APPROVED or REJECTED.")
                        put("enum", ToolJson.encodeToJsonElement(FalsificationVerdict.entries.map { verdict -> verdict.name }))
                    }
                    putJsonObject("llm_provider") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "Falsifier provider name.")
                    }
                    putJsonObject("reason_ja") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "Falsifier reason in Japanese.")
                    }
                    putClientRequestIdSchema()
                },
                required = listOf("intent_id", "verdict", "reason_ja"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
            kind = McpToolCallKind.DECISION,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleSubmitFalsification(request, tradingRuntime, decisionRunContext, call)
    }
}

private fun Server.registerPreviewOrderTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = PREVIEW_ORDER_TOOL,
            description = "Dry-run a paper BTC entry order before place_order. Uses the same input shape and SafetyFloor path, but creates no orders or executions.",
            inputSchema = ToolSchema(
                properties = buildPlaceOrderToolProperties(),
                required = PLACE_ORDER_REQUIRED_ARGUMENTS,
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            kind = McpToolCallKind.READ_ONLY,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handlePreviewOrder(request, tradingRuntime, call)
    }
}

private fun Server.registerPlaceOrderTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = PLACE_ORDER_TOOL,
            description = "Place a paper BTC entry order with a fresh approved intent. intent_id, protective_stop_price_jpy, and reason are required.",
            inputSchema = ToolSchema(
                properties = buildPlaceOrderToolProperties(),
                required = PLACE_ORDER_REQUIRED_ARGUMENTS,
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
            kind = McpToolCallKind.TRADE,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handlePlaceOrder(request, tradingRuntime, call)
    }
}

private fun buildPlaceOrderToolProperties(): JsonObject {
    return buildJsonObject {
        putJsonObject("intent_id") {
            put("type", JSON_TYPE_STRING)
            put("description", "Trade intent UUID approved by submit_falsification.")
        }
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
    }
}

private fun Server.registerClosePositionTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = CLOSE_POSITION_TOOL,
            description = "Close one open paper position partially or fully, or all open paper positions when all=true. reason is required.",
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
                    putDecimalStringSchema(
                        name = "close_ratio",
                        description = "Optional decimal string ratio of remaining position size to close. Defaults to 1.00.",
                    )
                    putReasonSchema()
                    putClientRequestIdSchema()
                },
                required = listOf("reason"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
            kind = McpToolCallKind.TRADE,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleClosePosition(request, tradingRuntime, call)
    }
}

private fun Server.registerUpdateProtectionTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
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
            kind = McpToolCallKind.TRADE,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleUpdateProtection(request, tradingRuntime, call)
    }
}

private fun Server.registerCancelOrderTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
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
            kind = McpToolCallKind.TRADE,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleCancelOrder(request, tradingRuntime, call)
    }
}

private fun Server.registerBalanceTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
    clock: Clock,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = GET_BALANCE_TOOL,
            description = "Get paper account balance and equity snapshot. Response includes paper ledger freshness metadata.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            kind = McpToolCallKind.READ_ONLY,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { _, call ->
        handleGetBalance(
            tradingRuntime = tradingRuntime,
            call = call,
            clock = clock,
        )
    }
}

private fun Server.registerPositionsTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
    clock: Clock,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = GET_POSITIONS_TOOL,
            description = "Get open paper positions from the bot-managed position ledger. Response includes paper ledger freshness metadata.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            kind = McpToolCallKind.READ_ONLY,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { _, call ->
        handleGetPositions(
            tradingRuntime = tradingRuntime,
            call = call,
            clock = clock,
        )
    }
}

private fun Server.registerOpenOrdersTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
    clock: Clock,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = GET_OPEN_ORDERS_TOOL,
            description = "Get open paper orders including protective STOP orders. Response includes paper ledger freshness metadata.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            kind = McpToolCallKind.READ_ONLY,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { _, call ->
        handleGetOpenOrders(
            tradingRuntime = tradingRuntime,
            call = call,
            clock = clock,
        )
    }
}

private fun Server.registerAccountStatusTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
    clock: Clock,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = GET_ACCOUNT_STATUS_TOOL,
            description = "Get paper account status and DB-backed risk_state. Response includes paper ledger freshness metadata.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            kind = McpToolCallKind.READ_ONLY,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { _, call ->
        handleGetAccountStatus(
            tradingRuntime = tradingRuntime,
            call = call,
            clock = clock,
        )
    }
}

private fun Server.registerKnowledgeRecentLessonsTool(
    tradingRuntime: TradingRuntime,
    knowledgeService: KnowledgeService,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = KNOWLEDGE_GET_RECENT_LESSONS_TOOL,
            description = "Return bounded DB-backed recent lessons, failure patterns, and reflection summaries.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putSymbolSchema()
                    putJsonObject("limit") {
                        put("type", JSON_TYPE_INTEGER)
                        put("description", "Maximum number of lessons to return.")
                        put("default", DEFAULT_KNOWLEDGE_RECENT_LESSONS_LIMIT)
                        put("minimum", 1)
                        put("maximum", MAX_KNOWLEDGE_RECENT_LESSONS_LIMIT)
                    }
                    putJsonObject("lookback_days") {
                        put("type", JSON_TYPE_INTEGER)
                        put("description", "Number of past days to inspect.")
                        put("default", DEFAULT_KNOWLEDGE_RECENT_LESSONS_LOOKBACK_DAYS)
                        put("minimum", 1)
                        put("maximum", MAX_KNOWLEDGE_LOOKBACK_DAYS)
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            kind = McpToolCallKind.READ_ONLY,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleKnowledgeRecentLessons(request, tradingRuntime, knowledgeService, call)
    }
}

private fun Server.registerKnowledgeSimilarSetupsTool(
    tradingRuntime: TradingRuntime,
    knowledgeService: KnowledgeService,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = KNOWLEDGE_SEARCH_SIMILAR_SETUPS_TOOL,
            description = "Search bounded DB-backed past decisions and outcomes by setup tags or signal context.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putSymbolSchema()
                    putStringArraySchema("setup_tags", "Setup taxonomy tags to match.")
                    putJsonObject("regime") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "Short market regime label or description.")
                    }
                    putJsonObject("signal_summary") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "Short signal summary for text matching.")
                    }
                    putJsonObject("limit") {
                        put("type", JSON_TYPE_INTEGER)
                        put("description", "Maximum number of similar decisions to return.")
                        put("default", DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LIMIT)
                        put("minimum", 1)
                        put("maximum", MAX_KNOWLEDGE_SIMILAR_SETUPS_LIMIT)
                    }
                    putJsonObject("lookback_days") {
                        put("type", JSON_TYPE_INTEGER)
                        put("description", "Number of past days to inspect.")
                        put("default", DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LOOKBACK_DAYS)
                        put("minimum", 1)
                        put("maximum", MAX_KNOWLEDGE_LOOKBACK_DAYS)
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            kind = McpToolCallKind.READ_ONLY,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleKnowledgeSimilarSetups(request, tradingRuntime, knowledgeService, call)
    }
}

private fun Server.registerTradeIntentTool(
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
            name = GET_TRADE_INTENT_TOOL,
            description = "Get a persisted trade intent and its TradePlan by intent_id for Falsifier review.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("intent_id") {
                        put("type", JSON_TYPE_STRING)
                        put("description", "Trade intent UUID.")
                    }
                },
                required = listOf("intent_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            kind = McpToolCallKind.READ_ONLY,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleGetTradeIntent(request, tradingRuntime, call)
    }
}

private fun Server.registerRejectDummyTradeTool(
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
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
            kind = McpToolCallKind.TRADE,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleRejectDummyTrade(request, toolCallGuard, call)
    }
}

private fun Server.registerSimulateToolTimeoutTool(
    toolCallGuard: ToolCallGuard,
    decisionRunContext: DecisionRunContext,
    toolCallLimiter: McpToolCallLimiter,
) {
    addLimitedTool(
        definition = LimitedToolDefinition(
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
            kind = McpToolCallKind.READ_ONLY,
        ),
        context = limitedToolContext(decisionRunContext, toolCallLimiter),
    ) { request, call ->
        handleSimulateToolTimeout(request, toolCallGuard, call)
    }
}

private suspend fun handleGetBalance(
    tradingRuntime: TradingRuntime,
    call: GuardedToolCall,
    clock: Clock,
): CallToolResult {
    val balance = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        tradingRuntime.broker.getBalanceWithUpdatedAt().getOrThrow()
    }

    return balance.fold(
        onSuccess = { value ->
            balanceResult(
                output = value,
                clock = clock,
            )
        },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetPositions(
    tradingRuntime: TradingRuntime,
    call: GuardedToolCall,
    clock: Clock,
): CallToolResult {
    val positions = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        tradingRuntime.broker.getPositionsWithUpdatedAt().getOrThrow()
    }

    return positions.fold(
        onSuccess = { value ->
            positionsResult(
                output = value,
                clock = clock,
            )
        },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetOpenOrders(
    tradingRuntime: TradingRuntime,
    call: GuardedToolCall,
    clock: Clock,
): CallToolResult {
    val openOrders = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        tradingRuntime.broker.getOpenOrdersWithUpdatedAt().getOrThrow()
    }

    return openOrders.fold(
        onSuccess = { value ->
            openOrdersResult(
                output = value,
                clock = clock,
            )
        },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetAccountStatus(
    tradingRuntime: TradingRuntime,
    call: GuardedToolCall,
    clock: Clock,
): CallToolResult {
    val accountStatus = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        AccountStatusService(tradingRuntime.broker).getAccountStatusWithUpdatedAt().getOrThrow()
    }

    return accountStatus.fold(
        onSuccess = { value ->
            accountStatusResult(
                output = value,
                clock = clock,
            )
        },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleKnowledgeRecentLessons(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    knowledgeService: KnowledgeService,
    call: GuardedToolCall,
): CallToolResult {
    val result = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        val query = parseKnowledgeRecentLessonsQuery(request).getOrThrow()

        knowledgeService.getRecentLessons(query).getOrThrow()
    }

    return result.fold(
        onSuccess = { value -> knowledgeRecentLessonsResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleKnowledgeSimilarSetups(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    knowledgeService: KnowledgeService,
    call: GuardedToolCall,
): CallToolResult {
    val result = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        val query = parseKnowledgeSimilarSetupsQuery(request).getOrThrow()

        knowledgeService.searchSimilarSetups(query).getOrThrow()
    }

    return result.fold(
        onSuccess = { value -> knowledgeSimilarSetupsResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleGetTradeIntent(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    call: GuardedToolCall,
): CallToolResult {
    val snapshot = toolCallGuard(tradingRuntime).runReadOnlyTool(call) {
        val intentId = parseUuidArgument(request, "intent_id").getOrThrow()

        requireNotNull(tradingRuntime.decisionRepository.tradeIntentReviewSnapshot(intentId).getOrThrow()) {
            "trade intent was not found."
        }
    }

    return snapshot.fold(
        onSuccess = { value -> tradeIntentResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleSubmitDecision(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    call: GuardedToolCall,
): CallToolResult {
    val result = tradingRuntime.toolCallGuard.runDecisionTool(call) {
        val submission = parseDecisionSubmission(request, decisionRunContext).getOrThrow()

        tradingRuntime.decisionRepository.submitDecision(submission).getOrThrow()
    }

    return result.fold(
        onSuccess = { value -> decisionSubmissionResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handleSubmitFalsification(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    decisionRunContext: DecisionRunContext,
    call: GuardedToolCall,
): CallToolResult {
    val result = tradingRuntime.toolCallGuard.runDecisionTool(call) {
        val submission = parseFalsificationSubmission(request, decisionRunContext).getOrThrow()

        tradingRuntime.decisionRepository.submitFalsification(submission).getOrThrow()
    }

    return result.fold(
        onSuccess = { value -> falsificationResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handlePreviewOrder(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    call: GuardedToolCall,
): CallToolResult {
    val result = tradingRuntime.toolCallGuard.runReadOnlyTool(call) {
        val command = parsePlaceOrderCommand(request, call).getOrThrow()

        tradingRuntime.broker.previewOrder(command).getOrThrow()
    }

    return result.fold(
        onSuccess = { value -> previewOrderResult(value) },
        onFailure = { throwable -> throwableResult(throwable) },
    )
}

private suspend fun handlePlaceOrder(
    request: CallToolRequest,
    tradingRuntime: TradingRuntime,
    call: GuardedToolCall,
): CallToolResult {
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
    call: GuardedToolCall,
): CallToolResult {
    val result = tradingRuntime.toolCallGuard.runRiskReducingTradeTool(call) {
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
    call: GuardedToolCall,
): CallToolResult {
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
    call: GuardedToolCall,
): CallToolResult {
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
    call: GuardedToolCall,
): CallToolResult {
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
    call: GuardedToolCall,
): CallToolResult {
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

private suspend fun limitErrorOrNull(
    toolCallLimiter: McpToolCallLimiter,
    call: GuardedToolCall,
    kind: McpToolCallKind,
): CallToolResult? {
    return toolCallLimiter.acquire(call, kind).fold(
        onSuccess = { null },
        onFailure = { throwable -> throwableResult(throwable) },
    )
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

private fun JsonObjectBuilder.putStringArraySchema(name: String, description: String) {
    putJsonObject(name) {
        put("type", "array")
        put("description", description)
        putJsonObject("items") {
            put("type", JSON_TYPE_STRING)
        }
    }
}

private fun JsonObjectBuilder.putEntryIntentDecisionSchemas() {
    putSymbolSchema()
    putJsonObject("side") {
        put("type", JSON_TYPE_STRING)
        put("description", "BUY entry only for ENTER and ADD_LONG.")
        put("enum", ToolJson.encodeToJsonElement(listOf(OrderSide.BUY.name)))
    }
    putJsonObject("type") {
        put("type", JSON_TYPE_STRING)
        put("description", "MARKET, LIMIT, or STOP for ENTER and ADD_LONG.")
        put("enum", ToolJson.encodeToJsonElement(OrderType.entries.map { type -> type.name }))
    }
    putDecimalStringSchema("size_btc", "BTC intent size.")
    putDecimalStringSchema("price_jpy", "LIMIT or STOP intent price. Omit for MARKET.")
    putDecimalStringSchema("protective_stop_price_jpy", "Protective STOP price for ENTER and ADD_LONG.")
    putDecimalStringSchema("take_profit_price_jpy", "Virtual take-profit price for ENTER and ADD_LONG.")
}

private fun JsonObjectBuilder.putTradePlanDecisionSchemas() {
    putJsonObject("parent_trade_plan_id") {
        put("type", JSON_TYPE_STRING)
        put("description", "Parent TradePlan UUID when revising.")
    }
    putJsonObject("trade_plan_revision_count") {
        put("type", JSON_TYPE_INTEGER)
        put("description", "TradePlan revision count. ENTER starts at 0.")
        put("default", 0)
    }
    putJsonObject("trade_plan_thesis_ja") {
        put("type", JSON_TYPE_STRING)
        put("description", "TradePlan thesis in Japanese.")
    }
    putStringArraySchema("trade_plan_invalidation_conditions_ja", "TradePlan invalidation conditions.")
    putDecimalStringSchema("trade_plan_target_price_jpy", "TradePlan target price.")
    putJsonObject("trade_plan_time_stop_at") {
        put("type", JSON_TYPE_STRING)
        put("description", "Optional ISO-8601 time stop.")
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

private fun parseDecisionSubmission(
    request: CallToolRequest,
    decisionRunContext: DecisionRunContext,
): Result<DecisionSubmission> {
    return runCatching {
        val action = parseDecisionAction(request.stringArgument("action")).getOrThrow()

        DecisionSubmission(
            invocationId = request.stringArgument("invocation_id") ?: decisionRunContext.decisionRunId,
            llmProvider = request.stringArgument("llm_provider") ?: decisionRunContext.llmProvider,
            promptHash = request.stringArgument("prompt_hash") ?: decisionRunContext.promptHash,
            systemPromptVersion = request.stringArgument("system_prompt_version")
                ?: decisionRunContext.systemPromptVersion,
            marketSnapshotId = request.stringArgument("market_snapshot_id") ?: decisionRunContext.marketSnapshotId,
            action = action,
            closeRatio = parseDecisionCloseRatio(request, action).getOrThrow(),
            setupTags = request.stringListArgument("setup_tags"),
            estimatedWinProbability = parseBigDecimalArgument(request, "estimated_win_probability").getOrThrow(),
            expectedRMultiple = parseBigDecimalArgument(request, "expected_r_multiple").getOrThrow(),
            roundTripCostR = parseOptionalBigDecimalArgument(request, "round_trip_cost_r").getOrThrow(),
            toolEvidenceIds = request.stringListArgument("tool_evidence_ids"),
            factCheckJson = requiredStringArgument(request, "fact_check"),
            selfReviewJson = requiredStringArgument(request, "self_review"),
            reasonJa = requiredStringArgument(request, "reason_ja"),
            missingDataJa = request.stringListArgument("missing_data_ja"),
            noTradeConditionsJa = request.stringListArgument("no_trade_conditions_ja"),
            entryIntent = parseEntryIntentDraft(request, action).getOrThrow(),
            tradePlan = parseTradePlanDraft(request, action).getOrThrow(),
        )
    }
}

private fun parseFalsificationSubmission(
    request: CallToolRequest,
    decisionRunContext: DecisionRunContext,
): Result<FalsificationSubmission> {
    return runCatching {
        FalsificationSubmission(
            intentId = request.stringArgument("intent_id")?.let { value -> UUID.fromString(value) },
            verdict = parseFalsificationVerdict(request.stringArgument("verdict")).getOrThrow(),
            llmProvider = request.stringArgument("llm_provider") ?: decisionRunContext.llmProvider,
            reasonJa = requiredStringArgument(request, "reason_ja"),
        )
    }
}

private fun parseEntryIntentDraft(request: CallToolRequest, action: DecisionAction): Result<EntryIntentDraft?> {
    return runCatching {
        if (!action.requiresEntryIntent()) {
            return@runCatching null
        }

        EntryIntentDraft(
            symbol = parseTradingSymbol(request.stringArgument("symbol")).getOrThrow(),
            side = parseOrderSide(request.stringArgument("side")).getOrThrow(),
            orderType = parseOrderType(request.stringArgument("type")).getOrThrow(),
            sizeBtc = parseBigDecimalArgument(request, "size_btc").getOrThrow(),
            priceJpy = parseOptionalBigDecimalArgument(request, "price_jpy").getOrThrow(),
            protectiveStopPriceJpy = parseBigDecimalArgument(request, "protective_stop_price_jpy").getOrThrow(),
            takeProfitPriceJpy = parseOptionalBigDecimalArgument(request, "take_profit_price_jpy").getOrThrow(),
        )
    }
}

private fun parseTradePlanDraft(request: CallToolRequest, action: DecisionAction): Result<TradePlanDraft?> {
    return runCatching {
        val tradePlanRequired = action.requiresEntryIntent()
        val tradePlanSpecified = request.hasAnyArgument(
            "parent_trade_plan_id",
            "trade_plan_revision_count",
            "trade_plan_thesis_ja",
            "trade_plan_invalidation_conditions_ja",
            "trade_plan_target_price_jpy",
            "trade_plan_time_stop_at",
        )

        if (!tradePlanRequired && !tradePlanSpecified) {
            return@runCatching null
        }

        TradePlanDraft(
            parentTradePlanId = request.stringArgument("parent_trade_plan_id")?.let { value -> UUID.fromString(value) },
            revisionCount = request.intArgument("trade_plan_revision_count", defaultValue = 0),
            symbol = parseTradingSymbol(request.stringArgument("symbol")).getOrThrow(),
            thesisJa = requiredStringArgument(request, "trade_plan_thesis_ja"),
            invalidationConditionsJa = request.stringListArgument("trade_plan_invalidation_conditions_ja"),
            targetPriceJpy = parseOptionalBigDecimalArgument(request, "trade_plan_target_price_jpy").getOrThrow(),
            timeStopAt = request.stringArgument("trade_plan_time_stop_at")?.let { value -> Instant.parse(value) },
            setupTags = request.stringListArgument("setup_tags"),
        )
    }
}

private fun parseDecisionCloseRatio(request: CallToolRequest, action: DecisionAction): Result<BigDecimal?> {
    return runCatching {
        val closeRatio = parseOptionalBigDecimalArgument(request, "close_ratio").getOrThrow()

        require(closeRatio == null || action == DecisionAction.REDUCE) {
            "close_ratio is only supported for REDUCE decisions."
        }

        if (action == DecisionAction.REDUCE) {
            require(closeRatio != null) {
                "REDUCE decision requires close_ratio."
            }
        }

        closeRatio
    }
}

private fun parseKnowledgeRecentLessonsQuery(request: CallToolRequest): Result<KnowledgeRecentLessonsQuery> {
    return runCatching {
        KnowledgeRecentLessonsQuery(
            symbol = parseTradingSymbol(request.stringArgument("symbol")).getOrThrow(),
            limit = request.intArgument(
                name = "limit",
                defaultValue = DEFAULT_KNOWLEDGE_RECENT_LESSONS_LIMIT,
            ),
            lookbackDays = request.intArgument(
                name = "lookback_days",
                defaultValue = DEFAULT_KNOWLEDGE_RECENT_LESSONS_LOOKBACK_DAYS,
            ),
        )
    }
}

private fun parseKnowledgeSimilarSetupsQuery(request: CallToolRequest): Result<KnowledgeSimilarSetupsQuery> {
    return runCatching {
        KnowledgeSimilarSetupsQuery(
            symbol = parseTradingSymbol(request.stringArgument("symbol")).getOrThrow(),
            setupTags = request.stringListArgument("setup_tags"),
            regime = request.stringArgument("regime"),
            signalSummary = request.stringArgument("signal_summary"),
            limit = request.intArgument(
                name = "limit",
                defaultValue = DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LIMIT,
            ),
            lookbackDays = request.intArgument(
                name = "lookback_days",
                defaultValue = DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LOOKBACK_DAYS,
            ),
        )
    }
}

private fun parsePlaceOrderCommand(request: CallToolRequest, call: GuardedToolCall): Result<PlaceOrderCommand> {
    return runCatching {
        PlaceOrderCommand(
            commandId = UUID.randomUUID(),
            intentId = UUID.fromString(requireNotNull(request.stringArgument("intent_id")) { "intent_id is required." }),
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
        val closeAll = parseBooleanArgument(request, "all", defaultValue = false)
        val closeRatioSpecified = request.arguments?.containsKey("close_ratio") == true
        val closeRatio = parseOptionalBigDecimalArgument(request, "close_ratio")
            .getOrThrow()
            ?: BigDecimal.ONE

        require(!(closeAll && closeRatioSpecified)) {
            "all=true cannot be combined with close_ratio."
        }

        ClosePositionCommand(
            commandId = UUID.randomUUID(),
            positionId = request.stringArgument("position_id")?.let { value -> UUID.fromString(value) },
            closeAll = closeAll,
            closeRatio = closeRatio,
            reasonJa = parseReason(request).getOrThrow(),
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        )
    }
}

private fun parseUpdateProtectionCommand(
    request: CallToolRequest,
    call: GuardedToolCall,
): Result<UpdateProtectionCommand> {
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

private fun parseDecisionAction(rawAction: String?): Result<DecisionAction> {
    return runCatching {
        val actionText = rawAction?.trim().orEmpty()

        require(actionText.isNotBlank()) {
            "action is required."
        }

        DecisionAction.valueOf(actionText.uppercase())
    }
}

private fun parseFalsificationVerdict(rawVerdict: String?): Result<FalsificationVerdict> {
    return runCatching {
        val verdictText = rawVerdict?.trim().orEmpty()

        require(verdictText.isNotBlank()) {
            "verdict is required."
        }

        FalsificationVerdict.valueOf(verdictText.uppercase())
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

private fun parseUuidArgument(request: CallToolRequest, name: String): Result<UUID> {
    return runCatching {
        UUID.fromString(requiredStringArgument(request, name))
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

private fun parseBooleanArgument(
    request: CallToolRequest,
    name: String,
    defaultValue: Boolean,
): Boolean {
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

private fun CallToolRequest.hasAnyArgument(vararg names: String): Boolean {
    val requestArguments = arguments ?: return false

    return names.any { name ->
        val value = requestArguments[name] ?: return@any false

        if (value is JsonPrimitive) {
            value.contentOrNull?.isNotBlank() ?: true
        } else {
            true
        }
    }
}

private fun requiredStringArgument(request: CallToolRequest, name: String): String {
    val value = request.stringArgument(name).orEmpty()

    require(value.isNotBlank()) {
        "$name is required."
    }

    return value
}

private fun CallToolRequest.stringListArgument(name: String): List<String> {
    val value = arguments?.get(name) ?: return emptyList()

    return value.jsonArray.map { element ->
        val text = element.jsonPrimitive.contentOrNull.orEmpty()

        require(text.isNotBlank()) {
            "$name must not contain blank values."
        }

        text
    }
}

private fun CallToolRequest.intArgument(name: String, defaultValue: Int): Int {
    return arguments
        ?.get(name)
        ?.jsonPrimitive
        ?.intOrNull
        ?: defaultValue
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

private fun balanceResult(output: AccountSnapshotWithUpdatedAt, clock: Clock): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(output.accountSnapshot)
        .jsonObject
        .withFreshness(
            paperLedgerFreshness(
                clock = clock,
                sourceTimestamp = output.updatedAt,
            ),
        )

    return jsonObjectResult(structuredContent)
}

private fun positionsResult(output: PositionsWithUpdatedAt, clock: Clock): CallToolResult {
    val structuredContent = buildJsonObject {
        put("count", output.positions.size)
        put("positions", ToolJson.encodeToJsonElement(output.positions))
    }.withFreshness(
        paperLedgerFreshness(
            clock = clock,
            sourceTimestamp = output.updatedAt,
        ),
    )

    return jsonObjectResult(structuredContent)
}

private fun openOrdersResult(output: OpenOrdersWithUpdatedAt, clock: Clock): CallToolResult {
    val structuredContent = buildJsonObject {
        put("count", output.openOrders.size)
        put("orders", ToolJson.encodeToJsonElement(output.openOrders))
    }.withFreshness(
        paperLedgerFreshness(
            clock = clock,
            sourceTimestamp = output.updatedAt,
        ),
    )

    return jsonObjectResult(structuredContent)
}

private fun accountStatusResult(output: AccountStatusWithUpdatedAt, clock: Clock): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(output.accountStatus)
        .jsonObject
        .withFreshness(
            paperLedgerFreshness(
                clock = clock,
                sourceTimestamp = output.updatedAt,
            ),
        )

    return jsonObjectResult(structuredContent)
}

private fun tradeIntentResult(snapshot: TradeIntentReviewSnapshot): CallToolResult {
    val intent = snapshot.tradeIntent
    val intentDraft = intent.draft
    val tradePlan = snapshot.tradePlan

    return jsonObjectResult(
        buildJsonObject {
            put("intent_id", intent.intentId.toString())
            put("decision_id", intent.decisionId.toString())
            put("trade_plan_id", intent.tradePlanId.toString())
            put("symbol", intentDraft.symbol.apiSymbol)
            put("side", intentDraft.side.name)
            put("type", intentDraft.orderType.name)
            put("size_btc", intentDraft.sizeBtc.toPlainString())
            putNullableDecimal("price_jpy", intentDraft.priceJpy)
            put("protective_stop_price_jpy", intentDraft.protectiveStopPriceJpy.toPlainString())
            putNullableDecimal("take_profit_price_jpy", intentDraft.takeProfitPriceJpy)
            put("estimated_win_probability", intent.estimatedWinProbability.toPlainString())
            put("created_at", intent.createdAt.toString())
            if (tradePlan == null) {
                put("trade_plan", JsonNull)
            } else {
                putJsonObject("trade_plan") {
                    put("trade_plan_id", tradePlan.tradePlanId.toString())
                    put("decision_id", tradePlan.decisionId.toString())
                    putNullableString("parent_trade_plan_id", tradePlan.draft.parentTradePlanId?.toString())
                    put("revision_count", tradePlan.draft.revisionCount)
                    put("symbol", tradePlan.draft.symbol.apiSymbol)
                    put("thesis_ja", tradePlan.draft.thesisJa)
                    put("invalidation_conditions_ja", ToolJson.encodeToJsonElement(tradePlan.draft.invalidationConditionsJa))
                    putNullableDecimal("target_price_jpy", tradePlan.draft.targetPriceJpy)
                    putNullableString("time_stop_at", tradePlan.draft.timeStopAt?.toString())
                    put("setup_tags", ToolJson.encodeToJsonElement(tradePlan.draft.setupTags))
                    put("created_at", tradePlan.createdAt.toString())
                }
            }
        },
    )
}

private fun knowledgeRecentLessonsResult(result: KnowledgeRecentLessonsResult): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("schema_version", "knowledge.recent_lessons.v1")
            put("source", "postgres")
            put("symbol", result.symbol.apiSymbol)
            put("lookback_days", result.lookbackDays)
            put("limit", result.limit)
            put("item_count", result.lessons.size)
            put("evaluation_truncated", result.evaluationTruncated)
            putJsonArray("lessons") {
                result.lessons.forEach { lesson -> add(lesson.toJsonObject()) }
            }
            putJsonArray("failure_patterns") {
                result.failurePatterns.forEach { pattern -> add(pattern.toJsonObject()) }
            }
            putJsonArray("run_summaries") {
                result.runSummaries.forEach { summary -> add(summary.toJsonObject()) }
            }
            putJsonArray("setup_performance") {
                result.setupPerformance.forEach { performance -> add(performance.toJsonObject()) }
            }
        },
    )
}

private fun knowledgeSimilarSetupsResult(result: KnowledgeSimilarSetupsResult): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("schema_version", "knowledge.similar_setups.v1")
            put("source", "postgres")
            put("symbol", result.symbol.apiSymbol)
            put("setup_tags", ToolJson.encodeToJsonElement(result.setupTags))
            putNullableString("regime", result.regime)
            putNullableString("signal_summary", result.signalSummary)
            put("lookback_days", result.lookbackDays)
            put("limit", result.limit)
            put("item_count", result.hits.size)
            put("evaluation_truncated", result.evaluationTruncated)
            putJsonArray("hits") {
                result.hits.forEach { hit -> add(hit.toJsonObject()) }
            }
            putJsonArray("setup_performance") {
                result.setupPerformance.forEach { performance -> add(performance.toJsonObject()) }
            }
        },
    )
}

private fun KnowledgeSimilarSetupHit.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("score", score)
        put("matched_terms", ToolJson.encodeToJsonElement(matchedTerms))
        put("lesson", lesson.toJsonObject())
        put("outcome", outcome.toJsonObject())
    }
}

private fun KnowledgeLesson.toJsonObject(): JsonObject {
    val tradePlan = tradePlanSummary
    val falsification = falsificationSummary

    return buildJsonObject {
        put("decision_id", decisionId)
        put("created_at", createdAt.toString())
        putNullableString("invocation_id", invocationId)
        put("action", action)
        put("setup_tags", ToolJson.encodeToJsonElement(setupTags))
        putNullableString("estimated_win_probability", estimatedWinProbability)
        putNullableString("expected_r_multiple", expectedRMultiple)
        put("reason_ja", reasonJa)
        put("self_review_summary", selfReviewSummary)
        put("missing_data_ja", ToolJson.encodeToJsonElement(missingDataJa))
        put("no_trade_conditions_ja", ToolJson.encodeToJsonElement(noTradeConditionsJa))
        if (tradePlan == null) {
            put("trade_plan", JsonNull)
        } else {
            put("trade_plan", tradePlan.toJsonObject())
        }
        if (falsification == null) {
            put("falsification", JsonNull)
        } else {
            put("falsification", falsification.toJsonObject())
        }
    }
}

private fun KnowledgeTradePlanSummary.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("trade_plan_id", tradePlanId)
        put("thesis_ja", thesisJa)
        put("invalidation_conditions_ja", ToolJson.encodeToJsonElement(invalidationConditionsJa))
        put("revision_count", revisionCount)
    }
}

private fun KnowledgeFalsificationSummary.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("verdict", verdict)
        put("reason_ja", reasonJa)
        put("created_at", createdAt.toString())
    }
}

private fun KnowledgeFailurePattern.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("source", source)
        put("source_id", sourceId)
        putNullableString("occurred_at", occurredAt?.toString())
        put("summary", summary)
        put("evidence", evidence)
    }
}

private fun KnowledgeRunSummary.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("invocation_id", invocationId)
        put("mode", mode)
        put("symbol", symbol)
        put("status", status)
        putNullableString("trigger_kind", triggerKind)
        put("started_at", startedAt.toString())
        putNullableString("finished_at", finishedAt?.toString())
        putNullableString("error_message", errorMessage)
    }
}

private fun KnowledgeSetupPerformanceSummary.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("setup_tag", setupTag)
        put("trade_count", tradeCount)
        put("total_pnl_jpy", totalPnlJpy)
        putNullableString("profit_factor", profitFactor)
        putNullableString("win_rate", winRate)
        putNullableString("expected_r", expectedR)
        putNullableString("average_mae_r", averageMaeR)
        putNullableString("average_mfe_r", averageMfeR)
    }
}

private fun KnowledgeDecisionOutcome.toJsonObject(): JsonObject {
    return buildJsonObject {
        putNullableString("run_status", runStatus)
        putNullableString("falsification_verdict", falsificationVerdict)
        put("has_trade_intent", hasTradeIntent)
        putNullableString("expected_r_multiple", expectedRMultiple)
    }
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
            if (result.divergenceMemos.isNotEmpty()) {
                put(
                    "paper_execution_divergence_memos",
                    JsonArray(result.divergenceMemos.map { memo -> memo.toJsonObject() }),
                )
            }
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

private fun previewOrderResult(result: PreviewOrderResult): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("accepted", result.accepted)
            put("preview_hash", result.previewHash)
            put("message", result.messageJa)
            putJsonObject("normalized_order_content") {
                putNullableString("intent_id", result.normalizedOrderContent.intentId)
                put("symbol", result.normalizedOrderContent.symbol)
                put("side", result.normalizedOrderContent.side)
                put("type", result.normalizedOrderContent.orderType)
                put("size_btc", result.normalizedOrderContent.sizeBtc)
                putNullableString("price_jpy", result.normalizedOrderContent.priceJpy)
                putNullableString("trade_group_id", result.normalizedOrderContent.tradeGroupId)
                put("protective_stop_price_jpy", result.normalizedOrderContent.protectiveStopPriceJpy)
                putNullableString("take_profit_price_jpy", result.normalizedOrderContent.takeProfitPriceJpy)
                put("estimated_win_probability", result.normalizedOrderContent.estimatedWinProbability)
            }
            putJsonObject("risk_details") {
                put("estimated_entry_price_jpy", result.riskDetails.estimatedEntryPriceJpy)
                put("order_risk_jpy", result.riskDetails.orderRiskJpy)
                put("group_risk_before_order_jpy", result.riskDetails.groupRiskBeforeOrderJpy)
                put("group_risk_after_order_jpy", result.riskDetails.groupRiskAfterOrderJpy)
                put("max_risk_per_trade_jpy", result.riskDetails.maxRiskPerTradeJpy)
                put("current_exposure_jpy", result.riskDetails.currentExposureJpy)
                put("order_exposure_jpy", result.riskDetails.orderExposureJpy)
                put("total_exposure_after_order_jpy", result.riskDetails.totalExposureAfterOrderJpy)
                put("max_total_exposure_jpy", result.riskDetails.maxTotalExposureJpy)
                put("available_cash_jpy", result.riskDetails.availableCashJpy)
                put("required_cash_jpy", result.riskDetails.requiredCashJpy)
                putNullableString("expected_value_r", result.riskDetails.expectedValueR)
                putNullableString("expected_move_to_cost_ratio", result.riskDetails.expectedMoveToCostRatio)
                put("probability_used_for_expected_value", result.riskDetails.probabilityUsedForExpectedValue)
                put("probability_cap_applied", result.riskDetails.probabilityCapApplied)
            }
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

private fun decisionSubmissionResult(result: DecisionSubmissionResult): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("accepted", true)
            put("decision_id", result.decision.decisionId.toString())
            put("action", result.decision.submission.action.name)
            result.decision.submission.closeRatio?.let { closeRatio ->
                put("close_ratio", closeRatio.toPlainString())
            }
            result.tradeIntent?.let { intent ->
                put("intent_id", intent.intentId.toString())
            }
            result.tradePlan?.let { tradePlan ->
                put("trade_plan_id", tradePlan.tradePlanId.toString())
                put("revision_count", tradePlan.draft.revisionCount)
            }
        },
    )
}

private fun falsificationResult(result: FalsificationRecord): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("accepted", true)
            put("falsification_id", result.falsificationId.toString())
            put("intent_id", result.intentId.toString())
            put("verdict", result.verdict.name)
        },
    )
}

private fun jsonObjectResult(structuredContent: JsonObject): CallToolResult {
    return CallToolResult(
        content = listOf(TextContent(structuredContent.toString())),
        structuredContent = structuredContent,
    )
}

private fun paperLedgerFreshness(clock: Clock, sourceTimestamp: Instant?): FreshnessMetadata {
    return FreshnessMetadata.build(
        clock = clock,
        sourceTimestamp = sourceTimestamp,
        staleAfter = FreshnessDefaults.paperLedgerStaleAfter,
        source = FreshnessSource.PAPER_LEDGER,
    )
}

private fun JsonObjectBuilder.putNullableDecimal(name: String, value: BigDecimal?) {
    if (value == null) {
        put(name, JsonNull)
    } else {
        put(name, value.toPlainString())
    }
}

private fun JsonObjectBuilder.putNullableString(name: String, value: String?) {
    if (value == null) {
        put(name, JsonNull)
    } else {
        put(name, value)
    }
}

private fun throwableResult(throwable: Throwable): CallToolResult {
    val type = toolErrorType(throwable)
    val executed = if (throwable is ToolCompletionAuditFailedException) throwable.executed else null

    val failureKind = (throwable as? MarketDataException)?.kind?.name?.lowercase()

    return errorResult(type, throwable.message.orEmpty(), executed, failureKind)
}

private fun toolErrorType(throwable: Throwable): String {
    return ToolErrorTypes
        .firstOrNull { (errorClass, _) -> errorClass.isInstance(throwable) }
        ?.second
        ?: "tool_call_failed"
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
