package me.matsumo.fukurou.mcp.gmo

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.matsumo.fukurou.mcp.runtime.mcpErrorResult
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.FreshnessDefaults
import me.matsumo.fukurou.trading.market.FreshnessMetadata
import me.matsumo.fukurou.trading.market.FreshnessSource
import me.matsumo.fukurou.trading.market.GmoApiStatusException
import me.matsumo.fukurou.trading.market.GmoHttpException
import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.GmoRequestAuditException
import me.matsumo.fukurou.trading.market.IndicatorCalculator
import me.matsumo.fukurou.trading.market.IndicatorParams
import me.matsumo.fukurou.trading.market.IndicatorResult
import me.matsumo.fukurou.trading.market.IndicatorType
import me.matsumo.fukurou.trading.market.MarketDataException
import me.matsumo.fukurou.trading.market.MarketDataParseException
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.market.MarketInvalidRequestException
import me.matsumo.fukurou.trading.market.MarketNetworkException
import me.matsumo.fukurou.trading.market.withFreshness
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

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
 * Tool response の JSON 設定。
 */
private val ToolJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * market tool の実行境界。
 */
interface GmoCoinMarketToolExecutor {
    /**
     * market tool を実行する。
     */
    suspend fun <T> execute(
        toolName: String,
        request: CallToolRequest,
        block: suspend () -> T,
    ): Result<T>

    /**
     * executor 境界で発生した例外の MCP error 応答を上書きする。
     */
    fun errorResponse(throwable: Throwable): GmoCoinMarketToolErrorResponse? {
        return null
    }
}

/**
 * executor 境界で上書きする MCP error 応答。
 *
 * @param type MCP structuredContent の error type
 * @param executed tool 本体の副作用または読み取りが実行済みかどうか
 */
data class GmoCoinMarketToolErrorResponse(
    val type: String,
    val executed: Boolean? = null,
)

/**
 * 監査や追加制約なしで market tool を実行する executor。
 */
object DirectGmoCoinMarketToolExecutor : GmoCoinMarketToolExecutor {
    override suspend fun <T> execute(
        toolName: String,
        request: CallToolRequest,
        block: suspend () -> T,
    ): Result<T> {
        return runCatching {
            block()
        }
    }
}

/**
 * calc_indicator の計算境界。
 */
interface GmoCoinIndicatorCalculator {
    /**
     * indicator が少なくとも 1 つの非 null 値を返すために必要な最小 candle 本数を返す。
     */
    fun requiredCandleCount(indicatorType: IndicatorType, params: IndicatorParams): Result<Int>

    /**
     * indicator を計算する。
     */
    fun calculate(
        candles: List<Candle>,
        indicatorType: IndicatorType,
        params: IndicatorParams,
    ): Result<IndicatorResult>
}

/**
 * `:trading` の標準 calculator に委譲する calculator。
 */
object DefaultGmoCoinIndicatorCalculator : GmoCoinIndicatorCalculator {
    override fun requiredCandleCount(indicatorType: IndicatorType, params: IndicatorParams): Result<Int> {
        return IndicatorCalculator.requiredCandleCount(indicatorType, params)
    }

    override fun calculate(
        candles: List<Candle>,
        indicatorType: IndicatorType,
        params: IndicatorParams,
    ): Result<IndicatorResult> {
        return IndicatorCalculator.calculate(candles, indicatorType, params)
    }
}

/**
 * kline を取得する tool 呼び出しの予算 hook。
 */
interface GmoCoinKlineRequestBudgetHook {
    /**
     * tool description に表示する短期足 kline request 上限。
     */
    val dailyKlineRequestLimit: Int?

    /**
     * kline 取得を伴う tool 呼び出しを許可するか検証する。
     */
    suspend fun check(request: GmoCoinKlineRequest): Result<Unit>
}

/**
 * fukurou 固有の kline 予算制約を注入しない hook。
 */
object NoopGmoCoinKlineRequestBudgetHook : GmoCoinKlineRequestBudgetHook {
    override val dailyKlineRequestLimit: Int? = null

    override suspend fun check(request: GmoCoinKlineRequest): Result<Unit> {
        return Result.success(Unit)
    }
}

/**
 * 短期足 kline request 上限を tool description と hook 境界に渡す hook。
 *
 * 実際の stitching request 数の強制は `GmoPublicMarketDataSource` に注入する
 * `GmoDailyKlineRequestBudget` が担う。この hook は MCP tool 登録境界で
 * fukurou 埋め込み時の上限表示と将来の呼び出し前検証を差し込むために使う。
 *
 * @param dailyKlineRequestLimit 短期足 stitching の request 上限
 */
class DescribedGmoCoinKlineRequestBudgetHook(
    override val dailyKlineRequestLimit: Int,
) : GmoCoinKlineRequestBudgetHook {
    init {
        require(dailyKlineRequestLimit >= 0) {
            "dailyKlineRequestLimit must be non-negative: $dailyKlineRequestLimit"
        }
    }

    override suspend fun check(request: GmoCoinKlineRequest): Result<Unit> {
        return Result.success(Unit)
    }
}

/**
 * kline を取得する tool 呼び出しの情報。
 *
 * @param toolName 呼び出される tool 名
 * @param interval ローソク足 interval
 * @param limit 要求された candle 数
 */
data class GmoCoinKlineRequest(
    val toolName: String,
    val interval: CandleInterval,
    val limit: Int,
)

/**
 * calc_indicator tool の登録と実行に使う依存関係。
 *
 * @param marketDataSource 市場データ取得元
 * @param toolExecutor market tool の実行境界
 * @param indicatorCalculator indicator 計算境界
 * @param klineRequestBudgetHook kline 取得を伴う tool 呼び出しの予算 hook
 * @param clock response 鮮度 metadata を作る clock
 */
private data class GmoCoinCalcIndicatorToolDependencies(
    val marketDataSource: MarketDataSource,
    val toolExecutor: GmoCoinMarketToolExecutor,
    val indicatorCalculator: GmoCoinIndicatorCalculator,
    val klineRequestBudgetHook: GmoCoinKlineRequestBudgetHook,
    val clock: Clock,
)

/**
 * GMO Coin market tools を任意の MCP server に登録する。
 *
 * @param marketDataSource 市場データ取得元
 * @param toolExecutor market tool の実行境界
 * @param indicatorCalculator indicator 計算境界
 * @param klineRequestBudgetHook kline 取得を伴う tool 呼び出しの予算 hook
 * @param clock response 鮮度 metadata を作る clock
 */
fun Server.registerGmoCoinMarketTools(
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor = DirectGmoCoinMarketToolExecutor,
    indicatorCalculator: GmoCoinIndicatorCalculator = DefaultGmoCoinIndicatorCalculator,
    klineRequestBudgetHook: GmoCoinKlineRequestBudgetHook = NoopGmoCoinKlineRequestBudgetHook,
    clock: Clock = Clock.systemUTC(),
) {
    val dailyKlineRequestLimit = klineRequestBudgetHook.dailyKlineRequestLimit

    registerTickerTool(
        marketDataSource = marketDataSource,
        toolExecutor = toolExecutor,
        clock = clock,
    )
    registerCandlesTool(
        marketDataSource = marketDataSource,
        toolExecutor = toolExecutor,
        klineRequestBudgetHook = klineRequestBudgetHook,
        dailyKlineRequestLimit = dailyKlineRequestLimit,
        clock = clock,
    )
    registerOrderbookTool(
        marketDataSource = marketDataSource,
        toolExecutor = toolExecutor,
        clock = clock,
    )
    registerTradesTool(
        marketDataSource = marketDataSource,
        toolExecutor = toolExecutor,
        clock = clock,
    )
    registerSymbolRulesTool(marketDataSource, toolExecutor)
    registerCalcIndicatorTool(
        dependencies = GmoCoinCalcIndicatorToolDependencies(
            marketDataSource = marketDataSource,
            toolExecutor = toolExecutor,
            indicatorCalculator = indicatorCalculator,
            klineRequestBudgetHook = klineRequestBudgetHook,
            clock = clock,
        ),
    )
}

private fun Server.registerTickerTool(
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
    clock: Clock,
) {
    addTool(
        name = GET_TICKER_TOOL,
        description = "Get the latest GMO Coin public ticker for BTC spot. Response includes freshness metadata.",
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
        handleGetTicker(
            request = request,
            marketDataSource = marketDataSource,
            toolExecutor = toolExecutor,
            clock = clock,
        )
    }
}

private fun Server.registerCandlesTool(
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
    klineRequestBudgetHook: GmoCoinKlineRequestBudgetHook,
    dailyKlineRequestLimit: Int?,
    clock: Clock,
) {
    addTool(
        name = GET_CANDLES_TOOL,
        description = candlesDescription(dailyKlineRequestLimit),
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
        handleGetCandles(
            request = request,
            marketDataSource = marketDataSource,
            toolExecutor = toolExecutor,
            klineRequestBudgetHook = klineRequestBudgetHook,
            clock = clock,
        )
    }
}

private fun Server.registerOrderbookTool(
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
    clock: Clock,
) {
    addTool(
        name = GET_ORDERBOOK_TOOL,
        description = "Get GMO Coin public orderbook for BTC spot. Response includes freshness metadata.",
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
        handleGetOrderbook(
            request = request,
            marketDataSource = marketDataSource,
            toolExecutor = toolExecutor,
            clock = clock,
        )
    }
}

private fun Server.registerTradesTool(
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
    clock: Clock,
) {
    addTool(
        name = GET_TRADES_TOOL,
        description = "Get recent GMO Coin public trades for BTC spot. Response includes freshness metadata.",
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
        handleGetTrades(
            request = request,
            marketDataSource = marketDataSource,
            toolExecutor = toolExecutor,
            clock = clock,
        )
    }
}

private fun Server.registerSymbolRulesTool(
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
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
        handleGetSymbolRules(request, marketDataSource, toolExecutor)
    }
}

private fun Server.registerCalcIndicatorTool(dependencies: GmoCoinCalcIndicatorToolDependencies) {
    val dailyKlineRequestLimit = dependencies.klineRequestBudgetHook.dailyKlineRequestLimit

    addTool(
        name = CALC_INDICATOR_TOOL,
        description = indicatorDescription(dailyKlineRequestLimit),
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
                    put("description", indicatorParamsDescription(dailyKlineRequestLimit))
                }
            },
            required = listOf("interval", "indicator"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        handleCalcIndicator(
            request = request,
            dependencies = dependencies,
        )
    }
}

private suspend fun handleGetCandles(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
    klineRequestBudgetHook: GmoCoinKlineRequestBudgetHook,
    clock: Clock,
): CallToolResult {
    var requestedInterval = CandleInterval.FIVE_MINUTES
    val candles = toolExecutor.execute(GET_CANDLES_TOOL, request) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val interval = parseCandleInterval(request.arguments?.get("interval")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val limit = parseIntArgument(request, "limit", DEFAULT_CANDLE_LIMIT, MAX_CANDLE_LIMIT).getOrThrow()
        val klineRequest = GmoCoinKlineRequest(
            toolName = GET_CANDLES_TOOL,
            interval = interval,
            limit = limit,
        )

        requestedInterval = interval
        klineRequestBudgetHook.check(klineRequest).getOrThrow()

        marketDataSource.getCandles(symbol, interval, limit).getOrThrow()
    }

    return candles.fold(
        onSuccess = { value ->
            candlesResult(
                candles = value,
                interval = requestedInterval,
                clock = clock,
            )
        },
        onFailure = { throwable -> throwableResult(throwable, toolExecutor) },
    )
}

private suspend fun handleGetOrderbook(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
    clock: Clock,
): CallToolResult {
    val orderbook = toolExecutor.execute(GET_ORDERBOOK_TOOL, request) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val depth = parseIntArgument(request, "depth", DEFAULT_ORDERBOOK_DEPTH, MAX_ORDERBOOK_DEPTH).getOrThrow()

        marketDataSource.getOrderbook(symbol, depth).getOrThrow()
    }

    return orderbook.fold(
        onSuccess = { value ->
            orderbookResult(
                orderbook = value,
                clock = clock,
            )
        },
        onFailure = { throwable -> throwableResult(throwable, toolExecutor) },
    )
}

private suspend fun handleGetTrades(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
    clock: Clock,
): CallToolResult {
    val trades = toolExecutor.execute(GET_TRADES_TOOL, request) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val limit = parseIntArgument(request, "limit", DEFAULT_TRADES_LIMIT, MAX_TRADES_LIMIT).getOrThrow()

        marketDataSource.getTrades(symbol, limit).getOrThrow()
    }

    return trades.fold(
        onSuccess = { value ->
            tradesResult(
                trades = value,
                clock = clock,
            )
        },
        onFailure = { throwable -> throwableResult(throwable, toolExecutor) },
    )
}

private suspend fun handleGetSymbolRules(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
): CallToolResult {
    val symbolRules = toolExecutor.execute(GET_SYMBOL_RULES_TOOL, request) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()

        marketDataSource.getSymbolRules(symbol).getOrThrow()
    }

    return symbolRules.fold(
        onSuccess = { value -> symbolRulesResult(value) },
        onFailure = { throwable -> throwableResult(throwable, toolExecutor) },
    )
}

private suspend fun handleCalcIndicator(
    request: CallToolRequest,
    dependencies: GmoCoinCalcIndicatorToolDependencies,
): CallToolResult {
    val indicator = dependencies.toolExecutor.execute(CALC_INDICATOR_TOOL, request) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val interval = parseCandleInterval(request.arguments?.get("interval")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val indicatorType = parseIndicatorType(request.arguments?.get("indicator")?.jsonPrimitive?.contentOrNull).getOrThrow()
        val params = parseIndicatorParams(request).getOrThrow()
        val requestedLimit = parseIndicatorCandleLimit(request).getOrThrow()
        val requiredCandleCount = dependencies.indicatorCalculator
            .requiredCandleCount(indicatorType, params)
            .getOrThrow()
        val limit = resolveIndicatorCandleLimit(requestedLimit, requiredCandleCount).getOrThrow()
        val klineRequest = GmoCoinKlineRequest(
            toolName = CALC_INDICATOR_TOOL,
            interval = interval,
            limit = limit,
        )

        dependencies.klineRequestBudgetHook.check(klineRequest).getOrThrow()

        val candles = dependencies.marketDataSource.getCandles(symbol, interval, limit).getOrThrow()
        val result = dependencies.indicatorCalculator.calculate(candles, indicatorType, params).getOrThrow()

        IndicatorToolOutput(
            symbol = symbol.apiSymbol,
            interval = interval,
            candleCount = candles.size,
            candleRequirement = IndicatorCandleRequirementMetadata(
                requiredCandleCount = requiredCandleCount,
                resolvedCandleLimit = limit,
                sourceCandlesSufficient = candles.size >= requiredCandleCount,
            ),
            result = result,
            freshness = marketFreshness(
                clock = dependencies.clock,
                sourceTimestamp = finalCandleSourceTimestamp(candles),
                staleAfter = FreshnessDefaults.candleStaleAfter(interval),
            ),
        )
    }

    return indicator.fold(
        onSuccess = { value -> indicatorResult(value) },
        onFailure = { throwable -> throwableResult(throwable, dependencies.toolExecutor) },
    )
}

private suspend fun handleGetTicker(
    request: CallToolRequest,
    marketDataSource: MarketDataSource,
    toolExecutor: GmoCoinMarketToolExecutor,
    clock: Clock,
): CallToolResult {
    val ticker = toolExecutor.execute(GET_TICKER_TOOL, request) {
        val symbol = parseTradingSymbol(request.arguments?.get("symbol")?.jsonPrimitive?.contentOrNull).getOrThrow()

        marketDataSource.getTicker(symbol).getOrThrow()
    }

    return ticker.fold(
        onSuccess = { value ->
            tickerResult(
                ticker = value,
                clock = clock,
            )
        },
        onFailure = { throwable -> throwableResult(throwable, toolExecutor) },
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
            lookback = paramsObject.readOptionalInt("lookback"),
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

private fun resolveIndicatorCandleLimit(requestedLimit: Int, requiredCandleCount: Int): Result<Int> {
    return runCatching {
        val isRequiredCountInRange = requiredCandleCount in 1..MAX_INDICATOR_CANDLE_LIMIT

        require(isRequiredCountInRange) {
            "required candle count must be between 1 and $MAX_INDICATOR_CANDLE_LIMIT: $requiredCandleCount"
        }

        maxOf(requestedLimit, requiredCandleCount).coerceAtMost(MAX_INDICATOR_CANDLE_LIMIT)
    }
}

private fun JsonObject?.readOptionalInt(primaryName: String, secondaryName: String? = null): Int? {
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

private fun tickerResult(ticker: Ticker, clock: Clock): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(ticker)
        .jsonObject
        .withFreshness(
            marketFreshness(
                clock = clock,
                sourceTimestamp = parseInstantOrNull(ticker.timestamp),
                staleAfter = FreshnessDefaults.tickerStaleAfter,
            ),
        )

    return jsonObjectResult(structuredContent)
}

private fun candlesResult(
    candles: List<Candle>,
    interval: CandleInterval,
    clock: Clock,
): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("count", candles.size)
            put("candles", ToolJson.encodeToJsonElement(candles))
            put(
                "freshness",
                ToolJson.encodeToJsonElement(
                    marketFreshness(
                        clock = clock,
                        sourceTimestamp = finalCandleSourceTimestamp(candles),
                        staleAfter = FreshnessDefaults.candleStaleAfter(interval),
                    ),
                ),
            )
        },
    )
}

private fun orderbookResult(orderbook: Orderbook, clock: Clock): CallToolResult {
    val structuredContent = ToolJson.encodeToJsonElement(orderbook)
        .jsonObject
        .withFreshness(
            marketFreshness(
                clock = clock,
                sourceTimestamp = null,
                staleAfter = FreshnessDefaults.orderbookStaleAfter,
            ),
        )

    return jsonObjectResult(structuredContent)
}

private fun tradesResult(trades: List<RecentTrade>, clock: Clock): CallToolResult {
    return jsonObjectResult(
        buildJsonObject {
            put("count", trades.size)
            put("trades", ToolJson.encodeToJsonElement(trades))
            put(
                "freshness",
                ToolJson.encodeToJsonElement(
                    marketFreshness(
                        clock = clock,
                        sourceTimestamp = latestTradeSourceTimestamp(trades),
                        staleAfter = FreshnessDefaults.tradesStaleAfter,
                    ),
                ),
            )
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
            putJsonObject("candle_requirement") {
                put("required_candle_count", output.candleRequirement.requiredCandleCount)
                put("resolved_candle_limit", output.candleRequirement.resolvedCandleLimit)
                put("source_candles_sufficient", output.candleRequirement.sourceCandlesSufficient)
            }
            put("indicator", ToolJson.encodeToJsonElement(output.result.indicator))
            put("params", ToolJson.encodeToJsonElement(output.result.params))
            put("values", ToolJson.encodeToJsonElement(output.result.values))
            put("freshness", ToolJson.encodeToJsonElement(output.freshness))
        },
    )
}

private fun marketFreshness(
    clock: Clock,
    sourceTimestamp: Instant?,
    staleAfter: Duration,
): FreshnessMetadata {
    return FreshnessMetadata.build(
        clock = clock,
        sourceTimestamp = sourceTimestamp,
        staleAfter = staleAfter,
        source = FreshnessSource.GMO_PUBLIC_REST,
    )
}

private fun finalCandleSourceTimestamp(candles: List<Candle>): Instant? {
    return parseInstantOrNull(candles.lastOrNull()?.openTime)
}

private fun latestTradeSourceTimestamp(trades: List<RecentTrade>): Instant? {
    return trades
        .mapNotNull { trade -> parseInstantOrNull(trade.timestamp) }
        .maxOrNull()
}

private fun parseInstantOrNull(value: String?): Instant? {
    if (value == null) {
        return null
    }

    return try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun jsonObjectResult(structuredContent: JsonObject): CallToolResult {
    return CallToolResult(
        content = listOf(TextContent(structuredContent.toString())),
        structuredContent = structuredContent,
    )
}

private fun throwableResult(throwable: Throwable, toolExecutor: GmoCoinMarketToolExecutor): CallToolResult {
    val mappedError = toolExecutor.errorResponse(throwable)
    val type = mappedError?.type ?: when (throwable) {
        is MarketInvalidRequestException -> "invalid_request"
        is GmoRateLimitException -> "rate_limited"
        is GmoRequestAuditException -> "audit_failed_after_execution"
        is GmoApiStatusException -> "gmo_status_error"
        is GmoHttpException -> "gmo_http_error"
        is MarketNetworkException -> "network_error"
        is MarketDataParseException -> "market_data_parse_error"
        is IllegalArgumentException -> "invalid_request"
        else -> "tool_call_failed"
    }
    val executed = when {
        mappedError != null -> mappedError.executed
        throwable is GmoRequestAuditException -> true
        else -> null
    }

    val failureKind = (throwable as? MarketDataException)?.kind?.name?.lowercase()

    return mcpErrorResult(type, throwable.message.orEmpty(), executed, failureKind)
}

private fun candlesDescription(dailyKlineRequestLimit: Int?): String {
    return if (dailyKlineRequestLimit == null) {
        "Get recent GMO Coin public candles for BTC spot. DAY-based intervals use GMO business dates that switch at 06:00 JST. Response includes freshness metadata."
    } else {
        "Get recent GMO Coin public candles for BTC spot. DAY-based intervals use GMO business dates that switch at 06:00 JST and stitch up to $dailyKlineRequestLimit dates, so long 1hour limits may return fewer candles than requested. Response includes freshness metadata."
    }
}

private fun indicatorDescription(dailyKlineRequestLimit: Int?): String {
    return if (dailyKlineRequestLimit == null) {
        "Calculate one technical indicator from GMO Coin public candles. The handler expands the candle limit to each indicator's minimum required count before calculating. DAY-based intervals use GMO business dates that switch at 06:00 JST. Response includes candle requirement and freshness metadata."
    } else {
        "Calculate one technical indicator from GMO Coin public candles. The handler expands the candle limit to each indicator's minimum required count before calculating. DAY-based intervals use GMO business dates that switch at 06:00 JST and stitch up to $dailyKlineRequestLimit dates, so long 1hour windows may still return fewer candles than required. Response includes candle requirement and freshness metadata."
    }
}

private fun indicatorParamsDescription(dailyKlineRequestLimit: Int?): String {
    return if (dailyKlineRequestLimit == null) {
        "Indicator params. Use period, lookback, fast_period, slow_period, signal_period, and limit as needed."
    } else {
        "Indicator params. Use period, lookback, fast_period, slow_period, signal_period, and limit as needed. DAY-based candle limits are capped by $dailyKlineRequestLimit stitched GMO business dates."
    }
}

/**
 * calc_indicator の MCP handler 内部出力。
 *
 * @param symbol 取引対象 symbol
 * @param interval ローソク足 interval
 * @param candleCount 計算に使った candle 数
 * @param candleRequirement indicator 計算に必要な candle 本数 metadata
 * @param result indicator 計算結果
 * @param freshness 計算に使った candle data の鮮度
 */
@Serializable
private data class IndicatorToolOutput(
    val symbol: String,
    val interval: CandleInterval,
    val candleCount: Int,
    val candleRequirement: IndicatorCandleRequirementMetadata,
    val result: IndicatorResult,
    val freshness: FreshnessMetadata,
)

/**
 * calc_indicator の candle 必要本数 metadata。
 *
 * @param requiredCandleCount indicator が非 null 値を返すために必要な最小 candle 本数
 * @param resolvedCandleLimit 実際に data source へ要求した取得 candle 上限（必要本数を満たすため呼び出し元指定の limit を超えることがある）
 * @param sourceCandlesSufficient 供給側が必要本数以上の candle を返したかどうか
 */
@Serializable
private data class IndicatorCandleRequirementMetadata(
    val requiredCandleCount: Int,
    val resolvedCandleLimit: Int,
    val sourceCandlesSufficient: Boolean,
)
