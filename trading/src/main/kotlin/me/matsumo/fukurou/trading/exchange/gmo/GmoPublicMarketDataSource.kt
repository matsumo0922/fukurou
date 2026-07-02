package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleDateRange
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradeSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.GmoApiStatusException
import me.matsumo.fukurou.trading.market.GmoHttpException
import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.MarketDataParseException
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.market.MarketInvalidRequestException
import me.matsumo.fukurou.trading.market.MarketNetworkException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * GMO コイン Public API の base URL。
 */
private const val GMO_PUBLIC_API_BASE_URL = "https://api.coin.z.com/public"

/**
 * GMO コイン Public ticker endpoint。
 */
private const val GMO_TICKER_PATH = "/v1/ticker"

/**
 * GMO コイン Public orderbooks endpoint。
 */
private const val GMO_ORDERBOOKS_PATH = "/v1/orderbooks"

/**
 * GMO コイン Public trades endpoint。
 */
private const val GMO_TRADES_PATH = "/v1/trades"

/**
 * GMO コイン Public klines endpoint。
 */
private const val GMO_KLINES_PATH = "/v1/klines"

/**
 * GMO コイン Public symbols endpoint。
 */
private const val GMO_SYMBOLS_PATH = "/v1/symbols"

/**
 * GMO trades request の既定 page。
 */
private const val DEFAULT_TRADES_PAGE = 1

/**
 * HTTP 成功 status code。
 */
private const val HTTP_OK = 200

/**
 * HTTP rate limit status code。
 */
private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * GMO API が成功時に返す status。
 */
private const val GMO_STATUS_OK = 0

/**
 * trades tool で許可する最大取得数。
 */
private const val MAX_TRADES_LIMIT = 100

/**
 * orderbook tool で許可する最大 depth。
 */
private const val MAX_ORDERBOOK_DEPTH = 100

/**
 * candles tool で許可する最大取得本数。
 */
private const val MAX_CANDLE_LIMIT = 500

/**
 * GMO klines の最古想定年。
 */
private const val GMO_KLINES_MIN_YEAR = 2021

/**
 * 長期足 stitching で 1 回の呼び出し中に許可する最大 request 数。
 */
private const val MAX_YEARLY_KLINE_REQUESTS = 6

/**
 * GMO Public API への接続 timeout。
 */
private val DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5)

/**
 * GMO public request 全体の timeout。
 */
private val DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10)

/**
 * symbol rules cache の既定 TTL。
 */
private val DEFAULT_SYMBOL_RULES_CACHE_TTL = Duration.ofMinutes(10)

/**
 * GMO klines date parameter の日付 formatter。
 */
private val KlineDayFormatter = DateTimeFormatter.BASIC_ISO_DATE

/**
 * GMO Public API の営業日判定に使う timezone。
 */
private val GmoMarketZone = ZoneId.of("Asia/Tokyo")

/**
 * GMO Public API から相場読み取り用データを取得する `MarketDataSource`。
 *
 * @param connectTimeout API 接続 timeout
 * @param requestTimeout public request 全体の timeout
 * @param httpClient API 呼び出しに使う HTTP client
 * @param baseUrl GMO Public API base URL
 * @param json API response の JSON parser
 * @param clock cache 期限と date stitching に使う clock
 * @param marketDateZone klines の date parameter に使う timezone
 * @param symbolRulesCacheTtl symbol rules の in-memory cache TTL
 */
class GmoPublicMarketDataSource(
    connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build(),
    private val baseUrl: String = GMO_PUBLIC_API_BASE_URL,
    private val json: Json = GmoPublicApiJson,
    private val clock: Clock = Clock.systemUTC(),
    private val marketDateZone: ZoneId = GmoMarketZone,
    private val symbolRulesCacheTtl: Duration = DEFAULT_SYMBOL_RULES_CACHE_TTL,
) : MarketDataSource {

    private var cachedSymbolRules: CachedSymbolRules? = null

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> = runMarketRequest {
        val request = buildTickerRequest(symbol)
        val responseBody = sendRequest(request, "ticker")

        parseTickerResponse(responseBody, symbol, json)
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> = runMarketRequest {
        validateLimit(limit, MAX_CANDLE_LIMIT, "limit")

        when (interval.dateRange) {
            CandleDateRange.DAY -> fetchDayRangeCandles(symbol, interval, limit)
            CandleDateRange.YEAR -> fetchYearRangeCandles(symbol, interval, limit)
        }
    }

    override suspend fun getOrderbook(
        symbol: TradingSymbol,
        depth: Int,
    ): Result<Orderbook> = runMarketRequest {
        validateLimit(depth, MAX_ORDERBOOK_DEPTH, "depth")

        val request = buildOrderbookRequest(symbol)
        val responseBody = sendRequest(request, "orderbook")

        parseOrderbookResponse(responseBody, symbol, depth, json)
    }

    override suspend fun getTrades(
        symbol: TradingSymbol,
        limit: Int,
    ): Result<List<RecentTrade>> = runMarketRequest {
        validateLimit(limit, MAX_TRADES_LIMIT, "limit")

        val request = buildTradesRequest(symbol, limit)
        val responseBody = sendRequest(request, "trades")

        parseTradesResponse(responseBody, symbol, json).take(limit)
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> = runMarketRequest {
        readCachedSymbolRules(symbol) ?: fetchAndCacheSymbolRules(symbol)
    }

    private suspend fun <T> runMarketRequest(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            block()
        }.recoverCatching { throwable ->
            throw throwable.toMarketDataException()
        }
    }

    private fun buildTickerRequest(symbol: TradingSymbol): HttpRequest {
        return buildGetRequest("$GMO_TICKER_PATH?symbol=${symbol.apiSymbol}")
    }

    private fun buildOrderbookRequest(symbol: TradingSymbol): HttpRequest {
        return buildGetRequest("$GMO_ORDERBOOKS_PATH?symbol=${symbol.apiSymbol}")
    }

    private fun buildTradesRequest(symbol: TradingSymbol, limit: Int): HttpRequest {
        return buildGetRequest("$GMO_TRADES_PATH?symbol=${symbol.apiSymbol}&page=$DEFAULT_TRADES_PAGE&count=$limit")
    }

    private fun buildKlinesRequest(
        symbol: TradingSymbol,
        interval: CandleInterval,
        date: String,
    ): HttpRequest {
        return buildGetRequest("$GMO_KLINES_PATH?symbol=${symbol.apiSymbol}&interval=${interval.apiValue}&date=$date")
    }

    private fun buildSymbolsRequest(): HttpRequest {
        return buildGetRequest(GMO_SYMBOLS_PATH)
    }

    private fun buildGetRequest(pathAndQuery: String): HttpRequest {
        val uri = URI.create("$baseUrl$pathAndQuery")

        return HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .GET()
            .build()
    }

    private fun sendRequest(request: HttpRequest, endpointName: String): String {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MarketNetworkException("GMO $endpointName request failed by network error.", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()

            throw MarketNetworkException("GMO $endpointName request was interrupted.", exception)
        }

        val statusCode = response.statusCode()

        if (statusCode == HTTP_TOO_MANY_REQUESTS) {
            throw GmoRateLimitException("GMO $endpointName request was rate limited.")
        }

        if (statusCode != HTTP_OK) {
            throw GmoHttpException(statusCode, "GMO $endpointName request failed with HTTP $statusCode.")
        }

        return response.body()
    }

    private fun fetchDayRangeCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): List<Candle> {
        val today = LocalDate.now(clock.withZone(marketDateZone))
        val todayCandles = fetchKlines(symbol, interval, today.format(KlineDayFormatter))

        if (todayCandles.size >= limit) {
            return todayCandles.takeLast(limit)
        }

        val previousDay = today.minusDays(1)
        val previousDayCandles = fetchKlines(symbol, interval, previousDay.format(KlineDayFormatter))

        return stitchCandles(previousDayCandles + todayCandles, limit)
    }

    private fun fetchYearRangeCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): List<Candle> {
        val currentYear = LocalDate.now(clock.withZone(marketDateZone)).year
        val candles = mutableListOf<Candle>()
        var year = currentYear
        var requestCount = 0

        while (shouldFetchYear(candles, limit, year, requestCount)) {
            val yearlyCandles = fetchKlines(symbol, interval, year.toString())
            candles.addAll(0, yearlyCandles)
            year -= 1
            requestCount += 1
        }

        return stitchCandles(candles, limit)
    }

    private fun shouldFetchYear(
        candles: List<Candle>,
        limit: Int,
        year: Int,
        requestCount: Int,
    ): Boolean {
        val needsMoreCandles = candles.size < limit
        val isSupportedYear = year >= GMO_KLINES_MIN_YEAR
        val canRequestMore = requestCount < MAX_YEARLY_KLINE_REQUESTS

        return needsMoreCandles && isSupportedYear && canRequestMore
    }

    private fun fetchKlines(
        symbol: TradingSymbol,
        interval: CandleInterval,
        date: String,
    ): List<Candle> {
        val request = buildKlinesRequest(symbol, interval, date)
        val responseBody = sendRequest(request, "klines")

        return parseKlinesResponse(responseBody, symbol, interval, json)
    }

    private fun readCachedSymbolRules(symbol: TradingSymbol): SymbolRules? {
        val cachedRules = cachedSymbolRules ?: return null
        val isSameSymbol = cachedRules.rules.symbol == symbol.apiSymbol
        val isFresh = clock.instant().isBefore(cachedRules.expiresAt)

        return if (isSameSymbol && isFresh) cachedRules.rules else null
    }

    private fun fetchAndCacheSymbolRules(symbol: TradingSymbol): SymbolRules {
        val request = buildSymbolsRequest()
        val responseBody = sendRequest(request, "symbols")
        val rules = parseSymbolsResponse(responseBody, symbol, json)

        cachedSymbolRules = CachedSymbolRules(
            rules = rules,
            expiresAt = clock.instant().plus(symbolRulesCacheTtl),
        )

        return rules
    }
}

/**
 * symbol rules cache entry。
 *
 * @param rules cache した symbol rules
 * @param expiresAt cache 期限
 */
private data class CachedSymbolRules(
    val rules: SymbolRules,
    val expiresAt: Instant,
)

/**
 * GMO Public API の ticker response を domain model へ変換する。
 */
fun parseTickerResponse(
    responseBody: String,
    symbol: TradingSymbol,
    json: Json = GmoPublicApiJson,
): Ticker {
    val response = decodeResponse<GmoTickerResponse>(responseBody, "ticker", json)

    validateGmoStatus(response.status, response.messages, "ticker")

    val ticker = response.data.firstOrNull { data -> data.symbol == symbol.apiSymbol }
        ?: throw MarketDataParseException("GMO ticker response did not include ${symbol.apiSymbol}.")

    return Ticker(
        symbol = ticker.symbol,
        last = ticker.last,
        bid = ticker.bid,
        ask = ticker.ask,
        high = ticker.high,
        low = ticker.low,
        volume = ticker.volume,
        timestamp = ticker.timestamp,
    )
}

/**
 * GMO Public API の klines response を domain model へ変換する。
 */
fun parseKlinesResponse(
    responseBody: String,
    symbol: TradingSymbol,
    interval: CandleInterval,
    json: Json = GmoPublicApiJson,
): List<Candle> {
    val response = decodeResponse<GmoKlinesResponse>(responseBody, "klines", json)

    validateGmoStatus(response.status, response.messages, "klines")

    return response.data.map { candle ->
        Candle(
            symbol = symbol.apiSymbol,
            interval = interval,
            openTime = candle.openTime,
            open = candle.open,
            high = candle.high,
            low = candle.low,
            close = candle.close,
            volume = candle.volume,
        )
    }
}

/**
 * GMO Public API の orderbooks response を domain model へ変換する。
 */
fun parseOrderbookResponse(
    responseBody: String,
    symbol: TradingSymbol,
    depth: Int,
    json: Json = GmoPublicApiJson,
): Orderbook {
    validateLimit(depth, MAX_ORDERBOOK_DEPTH, "depth")

    val response = decodeResponse<GmoOrderbookResponse>(responseBody, "orderbook", json)

    validateGmoStatus(response.status, response.messages, "orderbook")

    return Orderbook(
        symbol = response.data.symbol ?: symbol.apiSymbol,
        bids = response.data.bids.take(depth).map { level -> level.toDomain() },
        asks = response.data.asks.take(depth).map { level -> level.toDomain() },
    )
}

/**
 * GMO Public API の trades response を domain model へ変換する。
 */
fun parseTradesResponse(
    responseBody: String,
    symbol: TradingSymbol,
    json: Json = GmoPublicApiJson,
): List<RecentTrade> {
    val response = decodeResponse<GmoTradesResponse>(responseBody, "trades", json)

    validateGmoStatus(response.status, response.messages, "trades")

    return response.data.list.map { trade ->
        RecentTrade(
            symbol = symbol.apiSymbol,
            price = trade.price,
            size = trade.size,
            side = parseTradeSide(trade.side),
            timestamp = trade.timestamp,
        )
    }
}

/**
 * GMO Public API の symbols response から指定 symbol の rules を取り出す。
 */
fun parseSymbolsResponse(
    responseBody: String,
    symbol: TradingSymbol,
    json: Json = GmoPublicApiJson,
): SymbolRules {
    val response = decodeResponse<GmoSymbolsResponse>(responseBody, "symbols", json)

    validateGmoStatus(response.status, response.messages, "symbols")

    val rules = response.data.firstOrNull { data -> data.symbol == symbol.apiSymbol }
        ?: throw MarketDataParseException("GMO symbols response did not include ${symbol.apiSymbol}.")

    return SymbolRules(
        symbol = rules.symbol,
        minOrderSize = rules.minOrderSize,
        sizeStep = rules.sizeStep,
        tickSize = rules.tickSize,
        takerFee = rules.takerFee,
        makerFee = rules.makerFee,
    )
}

private inline fun <reified T> decodeResponse(
    responseBody: String,
    endpointName: String,
    json: Json,
): T {
    return try {
        json.decodeFromString<T>(responseBody)
    } catch (exception: SerializationException) {
        throw MarketDataParseException("GMO $endpointName response could not be parsed.", exception)
    }
}

private fun validateGmoStatus(
    status: Int,
    messages: List<GmoMessage>,
    endpointName: String,
) {
    if (status == GMO_STATUS_OK) {
        return
    }

    val messageText = messages.toMessageText()
    val isRateLimit = messageText.contains("rate", ignoreCase = true) || messageText.contains("limit", ignoreCase = true)

    if (isRateLimit) {
        throw GmoRateLimitException("GMO $endpointName response was rate limited: $messageText")
    }

    throw GmoApiStatusException(status, "GMO $endpointName response status was $status: $messageText")
}

private fun validateLimit(limit: Int, maxLimit: Int, name: String) {
    val isInRange = limit in 1..maxLimit

    if (!isInRange) {
        throw MarketInvalidRequestException("$name must be between 1 and $maxLimit: $limit")
    }
}

private fun stitchCandles(candles: List<Candle>, limit: Int): List<Candle> {
    return candles.distinctBy { candle -> candle.openTime }.takeLast(limit)
}

private fun Throwable.toMarketDataException(): Throwable {
    return when (this) {
        is MarketInvalidRequestException,
        is MarketNetworkException,
        is GmoHttpException,
        is GmoRateLimitException,
        is GmoApiStatusException,
        is MarketDataParseException,
        -> this
        is IllegalArgumentException -> MarketInvalidRequestException(message.orEmpty())
        else -> this
    }
}

private fun GmoOrderbookLevel.toDomain(): OrderbookLevel {
    return OrderbookLevel(
        price = price,
        size = size,
    )
}

/**
 * GMO trades response の side を domain model へ変換する。
 */
private fun parseTradeSide(side: String): TradeSide {
    return when (side.uppercase()) {
        "BUY" -> TradeSide.BUY
        "SELL" -> TradeSide.SELL
        else -> throw MarketDataParseException("Unsupported GMO trade side: $side")
    }
}

private fun List<GmoMessage>.toMessageText(): String {
    if (isEmpty()) {
        return "no message"
    }

    return joinToString(separator = "; ") { message ->
        listOfNotNull(message.messageCode, message.messageString)
            .joinToString(separator = " ")
    }
}

/**
 * GMO Public API の JSON 設定。
 */
private val GmoPublicApiJson = Json {
    ignoreUnknownKeys = true
}

/**
 * GMO error message。
 *
 * @param messageCode message code
 * @param messageString message text
 */
@Serializable
private data class GmoMessage(
    @SerialName("message_code")
    val messageCode: String? = null,
    @SerialName("message_string")
    val messageString: String? = null,
)

/**
 * GMO ticker response。
 *
 * @param status API status。0 が成功
 * @param data ticker 配列
 * @param messages error message 配列
 */
@Serializable
private data class GmoTickerResponse(
    val status: Int,
    val data: List<GmoTicker> = emptyList(),
    val messages: List<GmoMessage> = emptyList(),
)

/**
 * GMO ticker の wire model。
 *
 * @param symbol 取引対象 symbol
 * @param last 最終約定価格
 * @param bid 最良買気配
 * @param ask 最良売気配
 * @param high 当日高値
 * @param low 当日安値
 * @param volume 出来高
 * @param timestamp ticker 時刻
 */
@Serializable
private data class GmoTicker(
    val symbol: String,
    val last: String,
    val bid: String,
    val ask: String,
    val high: String,
    val low: String,
    val volume: String,
    val timestamp: String,
)

/**
 * GMO klines response。
 *
 * @param status API status。0 が成功
 * @param data ローソク足配列
 * @param messages error message 配列
 */
@Serializable
private data class GmoKlinesResponse(
    val status: Int,
    val data: List<GmoKline> = emptyList(),
    val messages: List<GmoMessage> = emptyList(),
)

/**
 * GMO kline の wire model。
 *
 * @param openTime 始値時刻
 * @param open 始値
 * @param high 高値
 * @param low 安値
 * @param close 終値
 * @param volume 出来高
 */
@Serializable
private data class GmoKline(
    val openTime: String,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String,
)

/**
 * GMO orderbook response。
 *
 * @param status API status。0 が成功
 * @param data 板情報
 * @param messages error message 配列
 */
@Serializable
private data class GmoOrderbookResponse(
    val status: Int,
    val data: GmoOrderbookData = GmoOrderbookData(),
    val messages: List<GmoMessage> = emptyList(),
)

/**
 * GMO orderbook response の data。
 *
 * @param symbol 取引対象 symbol
 * @param bids 買い板
 * @param asks 売り板
 */
@Serializable
private data class GmoOrderbookData(
    val symbol: String? = null,
    val bids: List<GmoOrderbookLevel> = emptyList(),
    val asks: List<GmoOrderbookLevel> = emptyList(),
)

/**
 * GMO orderbook price level。
 *
 * @param price 価格
 * @param size 数量
 */
@Serializable
private data class GmoOrderbookLevel(
    val price: String,
    val size: String,
)

/**
 * GMO trades response。
 *
 * @param status API status。0 が成功
 * @param data trades ページ情報
 * @param messages error message 配列
 */
@Serializable
private data class GmoTradesResponse(
    val status: Int,
    val data: GmoTradesData = GmoTradesData(),
    val messages: List<GmoMessage> = emptyList(),
)

/**
 * GMO trades response の data。
 *
 * @param list 約定リスト
 */
@Serializable
private data class GmoTradesData(
    val list: List<GmoTrade> = emptyList(),
)

/**
 * GMO trades の wire model。
 *
 * @param price 約定価格
 * @param size 約定数量
 * @param side 売買方向
 * @param timestamp 約定時刻
 */
@Serializable
private data class GmoTrade(
    val price: String,
    val size: String,
    val side: String,
    val timestamp: String,
)

/**
 * GMO symbols response。
 *
 * @param status API status。0 が成功
 * @param data symbol ルール配列
 * @param messages error message 配列
 */
@Serializable
private data class GmoSymbolsResponse(
    val status: Int,
    val data: List<GmoSymbolRules> = emptyList(),
    val messages: List<GmoMessage> = emptyList(),
)

/**
 * GMO symbol rules の wire model。
 *
 * @param symbol 取引対象 symbol
 * @param minOrderSize 最小発注数量
 * @param sizeStep 数量刻み
 * @param tickSize 価格刻み
 * @param takerFee taker 手数料率
 * @param makerFee maker 手数料率
 */
@Serializable
private data class GmoSymbolRules(
    val symbol: String,
    val minOrderSize: String,
    val sizeStep: String,
    val tickSize: String,
    val takerFee: String,
    val makerFee: String,
)
