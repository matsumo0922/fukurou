package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import me.matsumo.fukurou.trading.domain.unsafeOrderFeeRateReasonOrNull
import me.matsumo.fukurou.trading.market.GmoApiStatusException
import me.matsumo.fukurou.trading.market.GmoHttpException
import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.GmoRequestAuditException
import me.matsumo.fukurou.trading.market.MarketDataException
import me.matsumo.fukurou.trading.market.MarketDataFailureKind
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
import java.time.LocalDateTime
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
 * GMO コイン Public status endpoint。
 */
private const val GMO_STATUS_PATH = "/v1/status"

/**
 * GMO trades request の既定 page。
 */
private const val DEFAULT_TRADES_PAGE = 1

/**
 * HTTP 成功 status code。
 */
private const val HTTP_OK = 200

/**
 * GMO klines openTime の epoch millisecond wire value を判定する pattern。
 */
private val GmoEpochMillisOpenTimeRegex = Regex("^\\d+$")

/**
 * HTTP rate limit status code。
 */
private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * HTTP request timeout status code。
 */
private const val HTTP_REQUEST_TIMEOUT = 408

/**
 * HTTP server error status code の下限。
 */
private const val HTTP_SERVER_ERROR_MIN = 500

/**
 * GMO API が成功時に返す status。
 */
private const val GMO_STATUS_OK = 0

/**
 * GMO API が rate limit 時に返す message_code。
 */
private const val GMO_RATE_LIMIT_MESSAGE_CODE = "ERR-5003"

/**
 * GMO klines が未生成の日付に返す message_code。
 */
private const val GMO_KLINES_NOT_FOUND_MESSAGE_CODE = "ERR-5207"

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
 * fukurou 埋め込み時に短期足 stitching で 1 回の呼び出し中に許可する最大 request 数。
 */
const val GMO_MAX_DAILY_KLINE_REQUESTS = 7

/**
 * GMO の営業日が切り替わる JST 時刻。
 */
private const val GMO_BUSINESS_DAY_SWITCH_HOUR = 6

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
 * @param rateLimitConfig Public REST の token bucket 設定
 * @param retryConfig 一時的な失敗に対する retry 設定
 * @param requestRateLimiter request 前に呼び出す rate limiter
 * @param dailyKlineRequestBudget 短期足 stitching の request 予算
 * @param sleeper retry / rate-limit 待機に使う sleeper
 * @param clientType request audit で識別する client 発生主体
 * @param requestAuditSink 実 HTTP attempt を保存する監査境界
 */
class GmoPublicMarketDataSource(
    private val clientType: GmoPublicClientType,
    private val requestAuditSink: GmoPublicRequestAuditSink,
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
    rateLimitConfig: GmoRateLimitConfig = GmoRateLimitConfig(),
    private val retryConfig: GmoRetryConfig = GmoRetryConfig(),
    private val requestRateLimiter: GmoRequestRateLimiter = GmoTokenBucketRateLimiter(
        config = rateLimitConfig,
        clock = clock,
    ),
    private val dailyKlineRequestBudget: GmoDailyKlineRequestBudget = GmoFixedDailyKlineRequestBudget(
        maxRequests = GMO_MAX_DAILY_KLINE_REQUESTS,
    ),
    private val sleeper: GmoSleeper = ThreadSleepingGmoSleeper,
    private val monotonicTimeSource: GmoMonotonicTimeSource = SystemGmoMonotonicTimeSource,
) : MarketDataSource, GmoExchangeStatusReader {

    private val clientInstanceId = newGmoAuditId()

    @Volatile
    private var cachedSymbolRules: CachedSymbolRules? = null

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> =
        runMarketRequest(GmoPublicOperation.GET_TICKER) {
            val request = buildTickerRequest(symbol)
            val responseBody = sendRequest(request, GmoPublicEndpoint.TICKER)

            parseTickerResponse(responseBody, symbol, json)
        }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> = runMarketRequest(GmoPublicOperation.GET_CANDLES) {
        validateLimit(limit, MAX_CANDLE_LIMIT, "limit")

        when (interval.dateRange) {
            CandleDateRange.DAY -> fetchDayRangeCandles(symbol, interval, limit)
            CandleDateRange.YEAR -> fetchYearRangeCandles(symbol, interval, limit)
        }
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> =
        runMarketRequest(GmoPublicOperation.GET_ORDERBOOK) {
            validateLimit(depth, MAX_ORDERBOOK_DEPTH, "depth")

            val request = buildOrderbookRequest(symbol)
            val responseBody = sendRequest(request, GmoPublicEndpoint.ORDERBOOK)

            parseOrderbookResponse(responseBody, symbol, depth, json)
        }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> =
        runMarketRequest(GmoPublicOperation.GET_TRADES) {
            validateLimit(limit, MAX_TRADES_LIMIT, "limit")

            val request = buildTradesRequest(symbol, limit)
            val responseBody = sendRequest(request, GmoPublicEndpoint.TRADES)

            parseTradesResponse(responseBody, symbol, json).take(limit)
        }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> =
        runMarketRequest(GmoPublicOperation.GET_SYMBOL_RULES) {
            readCachedSymbolRules(symbol) ?: fetchAndCacheSymbolRules(symbol)
        }

    override suspend fun readStatus(): Result<GmoExchangeStatus> = runMarketRequest(
        operation = GmoPublicOperation.GET_STATUS,
        maxAttempts = 1,
    ) {
        val request = buildStatusRequest()
        val responseBody = sendRequest(request, GmoPublicEndpoint.STATUS)

        parseStatusResponse(responseBody, json)
    }

    private suspend fun <T> runMarketRequest(
        operation: GmoPublicOperation,
        maxAttempts: Int = retryConfig.maxAttempts,
        block: suspend GmoRequestScope.() -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val scope = GmoRequestScope(operation, maxAttempts)

        runCatching {
            executeWithRetry(scope, block)
        }.recoverCatching { throwable ->
            throw throwable.toMarketDataException()
        }
    }

    private suspend fun <T> executeWithRetry(scope: GmoRequestScope, block: suspend GmoRequestScope.() -> T): T {
        var attemptNumber = 1
        var nextBackoff = retryConfig.initialBackoff
        var lastFailure: Throwable? = null

        while (attemptNumber <= scope.maxAttempts) {
            scope.attempt = attemptNumber
            try {
                return scope.block()
            } catch (exception: MarketDataException) {
                if (!shouldRetry(exception, attemptNumber, scope.maxAttempts)) {
                    throw exception
                }

                lastFailure = exception
                sleeper.sleep(nextBackoff)
                nextBackoff = nextRetryBackoff(nextBackoff)
                attemptNumber += 1
            } catch (exception: IllegalArgumentException) {
                throw MarketInvalidRequestException(exception.message.orEmpty(), exception)
            }
        }

        throw requireNotNull(lastFailure) {
            "retry exhausted without captured failure."
        }
    }

    private fun shouldRetry(
        throwable: Throwable,
        attemptNumber: Int,
        maxAttempts: Int,
    ): Boolean {
        val hasAttemptLeft = attemptNumber < maxAttempts
        val isTemporary = throwable is MarketDataException && throwable.kind == MarketDataFailureKind.TEMPORARY

        return hasAttemptLeft && isTemporary
    }

    private fun nextRetryBackoff(currentBackoff: Duration): Duration {
        val multipliedMillis = currentBackoff.toMillis().multiplyExactOrMax(retryConfig.backoffMultiplier)
        val cappedMillis = minOf(multipliedMillis, retryConfig.maxBackoff.toMillis())

        return Duration.ofMillis(cappedMillis)
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

    private fun buildStatusRequest(): HttpRequest {
        return buildGetRequest(GMO_STATUS_PATH)
    }

    private fun buildGetRequest(pathAndQuery: String): HttpRequest {
        val uri = URI.create("$baseUrl$pathAndQuery")

        return HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .GET()
            .build()
    }

    private suspend fun GmoRequestScope.sendRequest(request: HttpRequest, endpoint: GmoPublicEndpoint): String {
        val permitWait = measurePermitWait(endpoint)
        val requestId = newGmoAuditId()
        val requestStartedAt = clock.instant()
        val startedNanos = System.nanoTime()
        requestSequence += 1
        val attemptContext = GmoRequestAttemptContext(
            requestId = requestId,
            requestStartedAt = requestStartedAt,
            startedNanos = startedNanos,
            permitWait = permitWait,
        )
        val response = sendHttpRequest(request, endpoint, attemptContext)

        val statusCode = response.statusCode()
        val hasRateLimitMessage = statusCode == HTTP_OK && response.body().hasGmoRateLimitMessage()
        val outcome = when {
            statusCode == HTTP_TOO_MANY_REQUESTS -> GmoPublicRequestOutcome.HTTP_429
            hasRateLimitMessage -> GmoPublicRequestOutcome.JSON_ERR_5003
            else -> GmoPublicRequestOutcome.HTTP_RESPONSE
        }

        appendAudit(
            requestId = requestId,
            endpoint = endpoint,
            requestStartedAt = requestStartedAt,
            startedNanos = startedNanos,
            permitWait = permitWait,
            outcome = outcome,
            statusCode = statusCode,
            gmoMessageCode = GMO_RATE_LIMIT_MESSAGE_CODE.takeIf { hasRateLimitMessage },
        )

        if (statusCode == HTTP_TOO_MANY_REQUESTS) {
            throw GmoRateLimitException("GMO ${endpoint.name} request was rate limited by HTTP_429.")
        }

        if (hasRateLimitMessage) {
            throw GmoRateLimitException("GMO ${endpoint.name} request was rate limited by JSON_ERR_5003.")
        }

        if (statusCode != HTTP_OK) {
            throw GmoHttpException(
                statusCode = statusCode,
                kind = statusCode.toFailureKind(),
                message = "GMO ${endpoint.name} request failed with HTTP $statusCode.",
            )
        }

        return response.body()
    }

    private suspend fun GmoRequestScope.sendHttpRequest(
        request: HttpRequest,
        endpoint: GmoPublicEndpoint,
        attemptContext: GmoRequestAttemptContext,
    ): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            val networkException = MarketNetworkException(
                "GMO ${endpoint.name} request failed by network error.",
                exception,
            )
            appendAuditPreservingFailure(
                requestId = attemptContext.requestId,
                endpoint = endpoint,
                requestStartedAt = attemptContext.requestStartedAt,
                startedNanos = attemptContext.startedNanos,
                permitWait = attemptContext.permitWait,
                outcome = GmoPublicRequestOutcome.NETWORK_ERROR,
                requestFailure = networkException,
            )
            throw networkException
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()

            val networkException = MarketNetworkException("GMO ${endpoint.name} request was interrupted.", exception)
            appendAuditPreservingFailure(
                requestId = attemptContext.requestId,
                endpoint = endpoint,
                requestStartedAt = attemptContext.requestStartedAt,
                startedNanos = attemptContext.startedNanos,
                permitWait = attemptContext.permitWait,
                outcome = GmoPublicRequestOutcome.INTERRUPTED,
                requestFailure = networkException,
            )
            throw networkException
        }
    }

    private fun measurePermitWait(endpoint: GmoPublicEndpoint): Duration {
        val startedNanos = monotonicTimeSource.nanoTime()
        requestRateLimiter.acquirePermit(endpoint.name.lowercase())

        return Duration.ofNanos(monotonicTimeSource.nanoTime() - startedNanos)
    }

    @Suppress("LongParameterList")
    private suspend fun GmoRequestScope.appendAuditPreservingFailure(
        requestId: String,
        endpoint: GmoPublicEndpoint,
        requestStartedAt: Instant,
        startedNanos: Long,
        permitWait: Duration,
        outcome: GmoPublicRequestOutcome,
        requestFailure: MarketNetworkException,
    ) {
        try {
            appendAudit(
                requestId = requestId,
                endpoint = endpoint,
                requestStartedAt = requestStartedAt,
                startedNanos = startedNanos,
                permitWait = permitWait,
                outcome = outcome,
            )
        } catch (auditException: GmoRequestAuditException) {
            auditException.addSuppressed(requestFailure)
            throw auditException
        }
    }

    @Suppress("LongParameterList")
    private suspend fun GmoRequestScope.appendAudit(
        requestId: String,
        endpoint: GmoPublicEndpoint,
        requestStartedAt: Instant,
        startedNanos: Long,
        permitWait: Duration,
        outcome: GmoPublicRequestOutcome,
        statusCode: Int? = null,
        gmoMessageCode: String? = null,
    ) {
        val completedAt = clock.instant()
        val correlation = currentCoroutineContext()[GmoPublicRequestCorrelation]
        val event = GmoPublicRequestAuditEvent(
            requestId = requestId,
            operationId = operationId,
            clientInstanceId = clientInstanceId,
            processId = GmoProcessId,
            decisionRunId = correlation?.decisionRunContext?.decisionRunId,
            toolCallId = correlation?.toolCallId,
            clientType = clientType,
            clientRole = correlation?.clientRole ?: GmoPublicClientRole.UNSPECIFIED,
            operation = operation,
            endpoint = endpoint,
            attempt = attempt,
            maxAttempts = maxAttempts,
            requestSequence = requestSequence,
            requestStartedAt = requestStartedAt.toString(),
            completedAt = completedAt.toString(),
            requestDurationMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
            permitWaitMillis = permitWait.toMillis(),
            outcome = outcome,
            httpStatusCode = statusCode,
            gmoMessageCode = gmoMessageCode,
        )

        requestAuditSink.append(event).getOrElse { throw GmoRequestAuditException() }
    }

    private suspend fun GmoRequestScope.fetchDayRangeCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): List<Candle> {
        val candles = mutableListOf<Candle>()
        var date = currentGmoBusinessDate()
        var requestCount = 0

        while (shouldFetchDay(candles, limit, requestCount)) {
            val dailyCandles = fetchKlines(symbol, interval, date.format(KlineDayFormatter))
            candles.addAll(0, dailyCandles)
            date = date.minusDays(1)
            requestCount += 1
        }

        return stitchCandles(candles, limit)
    }

    private suspend fun GmoRequestScope.fetchYearRangeCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): List<Candle> {
        val currentYear = currentGmoBusinessDate().year
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

    private fun shouldFetchDay(
        candles: List<Candle>,
        limit: Int,
        requestCount: Int,
    ): Boolean {
        val needsMoreCandles = candles.size < limit
        val canRequestMore = dailyKlineRequestBudget.canRequestMore(requestCount)

        return needsMoreCandles && canRequestMore
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

    private fun currentGmoBusinessDate(): LocalDate {
        return LocalDateTime.now(clock.withZone(marketDateZone))
            .minusHours(GMO_BUSINESS_DAY_SWITCH_HOUR.toLong())
            .toLocalDate()
    }

    private suspend fun GmoRequestScope.fetchKlines(
        symbol: TradingSymbol,
        interval: CandleInterval,
        date: String,
    ): List<Candle> {
        val request = buildKlinesRequest(symbol, interval, date)
        val responseBody = sendRequest(request, GmoPublicEndpoint.KLINES)

        return parseKlinesResponse(
            responseBody = responseBody,
            symbol = symbol,
            interval = interval,
            json = json,
            notFoundAsEmpty = true,
        )
    }

    private fun readCachedSymbolRules(symbol: TradingSymbol): SymbolRules? {
        val cachedRules = cachedSymbolRules ?: return null
        val isSameSymbol = cachedRules.rules.symbol == symbol.apiSymbol
        val isFresh = clock.instant().isBefore(cachedRules.expiresAt)

        return if (isSameSymbol && isFresh) cachedRules.rules else null
    }

    private suspend fun GmoRequestScope.fetchAndCacheSymbolRules(symbol: TradingSymbol): SymbolRules {
        val request = buildSymbolsRequest()
        val responseBody = sendRequest(request, GmoPublicEndpoint.SYMBOLS)
        val rules = parseSymbolsResponse(responseBody, symbol, json)

        cachedSymbolRules = CachedSymbolRules(
            rules = rules,
            expiresAt = clock.instant().plus(symbolRulesCacheTtl),
        )

        return rules
    }

    companion object {
        /**
         * typed config から GMO Public market data source を構築する。
         */
        fun fromConfig(
            config: GmoPublicClientConfig,
            clientType: GmoPublicClientType,
            requestAuditSink: GmoPublicRequestAuditSink,
            clock: Clock = Clock.systemUTC(),
            dailyKlineRequestBudget: GmoDailyKlineRequestBudget = GmoFixedDailyKlineRequestBudget(
                maxRequests = GMO_MAX_DAILY_KLINE_REQUESTS,
            ),
        ): GmoPublicMarketDataSource {
            return GmoPublicMarketDataSource(
                connectTimeout = config.connectTimeout,
                requestTimeout = config.requestTimeout,
                baseUrl = config.baseUrl,
                clock = clock,
                symbolRulesCacheTtl = config.symbolRulesCacheTtl,
                rateLimitConfig = config.rateLimit,
                retryConfig = config.retry,
                dailyKlineRequestBudget = dailyKlineRequestBudget,
                clientType = clientType,
                requestAuditSink = requestAuditSink,
            )
        }
    }

    private inner class GmoRequestScope(val operation: GmoPublicOperation, val maxAttempts: Int) {
        val operationId: String = newGmoAuditId()
        var attempt: Int = 1
        var requestSequence: Int = 0
    }

    /** 1 回の HTTP attempt の監査計測値。 */
    private data class GmoRequestAttemptContext(
        val requestId: String,
        val requestStartedAt: Instant,
        val startedNanos: Long,
        val permitWait: Duration,
    )
}

internal fun hasPotentialGmoRateLimitMessage(responseBody: String): Boolean {
    return responseBody.contains(GMO_RATE_LIMIT_MESSAGE_CODE)
}

internal fun String.hasGmoRateLimitMessage(): Boolean {
    if (!hasPotentialGmoRateLimitMessage(this)) return false

    return runCatching {
        GmoPublicApiJson.parseToJsonElement(this).jsonObject["messages"]?.jsonArray?.any { message ->
            message.jsonObject["message_code"]?.jsonPrimitive?.content == GMO_RATE_LIMIT_MESSAGE_CODE
        } == true
    }.getOrDefault(false)
}

/**
 * 短期足 stitching の日次 kline request 予算。
 */
interface GmoDailyKlineRequestBudget {
    /**
     * 次の kline request を許可するかを返す。
     */
    fun canRequestMore(requestCount: Int): Boolean
}

/**
 * 上限なしで短期足 stitching の kline request を許可する予算。
 */
object GmoUnlimitedDailyKlineRequestBudget : GmoDailyKlineRequestBudget {
    override fun canRequestMore(requestCount: Int): Boolean {
        return true
    }
}

/**
 * 固定回数まで短期足 stitching の kline request を許可する予算。
 *
 * @param maxRequests 許可する最大 request 数
 */
data class GmoFixedDailyKlineRequestBudget(
    val maxRequests: Int,
) : GmoDailyKlineRequestBudget {
    init {
        require(maxRequests >= 0) {
            "maxRequests must be non-negative: $maxRequests"
        }
    }

    override fun canRequestMore(requestCount: Int): Boolean {
        return requestCount < maxRequests
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
    notFoundAsEmpty: Boolean = false,
): List<Candle> {
    val response = decodeResponse<GmoKlinesResponse>(responseBody, "klines", json)

    if (notFoundAsEmpty && response.isKlinesNotFound()) {
        return emptyList()
    }

    validateGmoStatus(response.status, response.messages, "klines")

    return response.data.map { candle ->
        val normalizedOpenTime = normalizeGmoKlineOpenTime(candle.openTime)

        Candle(
            symbol = symbol.apiSymbol,
            interval = interval,
            openTime = normalizedOpenTime,
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

    val symbolRules = SymbolRules(
        symbol = rules.symbol,
        minOrderSize = rules.minOrderSize,
        sizeStep = rules.sizeStep,
        tickSize = rules.tickSize,
        takerFee = rules.takerFee,
        makerFee = rules.makerFee,
    )
    val unsafeFeeReason = unsafeOrderFeeRateReasonOrNull(symbolRules)

    if (unsafeFeeReason != null) {
        throw MarketDataParseException("GMO symbols response included unsafe fee rates: $unsafeFeeReason")
    }

    return symbolRules
}

/** GMO Public API が返す取引所 status。 */
enum class GmoExchangeStatus {
    /** 通常稼働中。 */
    OPEN,

    /** メンテナンス中。 */
    MAINTENANCE,

    /** メンテナンス後の注文取消だけを受け付ける状態。 */
    PREOPEN,
}

/** GMO status を読み取る境界。 */
fun interface GmoExchangeStatusReader {
    /** 現在の取引所 status を返す。 */
    suspend fun readStatus(): Result<GmoExchangeStatus>
}

/** GMO status response を typed status へ変換する。 */
fun parseStatusResponse(responseBody: String, json: Json = GmoPublicApiJson): GmoExchangeStatus {
    val response = decodeResponse<GmoStatusResponse>(responseBody, "status", json)

    validateGmoStatus(response.status, response.messages, "status")

    return GmoExchangeStatus.entries.firstOrNull { status -> status.name == response.data.status }
        ?: throw MarketDataParseException("GMO status response included an unknown exchange status.")
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

    val isRateLimit = messages.hasMessageCode(GMO_RATE_LIMIT_MESSAGE_CODE)

    if (isRateLimit) {
        throw GmoRateLimitException("GMO $endpointName response was rate limited by JSON_ERR_5003.")
    }

    throw GmoApiStatusException(status, "GMO $endpointName response returned a non-success status.")
}

private fun GmoKlinesResponse.isKlinesNotFound(): Boolean {
    val isStatusError = status != GMO_STATUS_OK
    val isNotFound = messages.hasMessageCode(GMO_KLINES_NOT_FOUND_MESSAGE_CODE)

    return isStatusError && isNotFound
}

/**
 * GMO kline wire の openTime を domain 前提の ISO-8601 instant 文字列へ正規化する。
 *
 * @param openTime GMO Public API が返した openTime
 */
private fun normalizeGmoKlineOpenTime(openTime: String): String {
    val isEpochMillis = GmoEpochMillisOpenTimeRegex.matches(openTime)

    if (isEpochMillis) {
        return runCatching {
            Instant.ofEpochMilli(openTime.toLong()).toString()
        }.getOrElse { throwable ->
            throw MarketDataParseException(
                message = "GMO kline openTime must be epoch milliseconds or ISO-8601 instant: $openTime",
                cause = throwable,
            )
        }
    }

    return runCatching {
        Instant.parse(openTime)
        openTime
    }.getOrElse { throwable ->
        throw MarketDataParseException(
            message = "GMO kline openTime must be epoch milliseconds or ISO-8601 instant: $openTime",
            cause = throwable,
        )
    }
}

private fun validateLimit(
    limit: Int,
    maxLimit: Int,
    name: String,
) {
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
        is IllegalArgumentException -> MarketInvalidRequestException(message.orEmpty(), this)
        else -> this
    }
}

private fun Int.toFailureKind(): MarketDataFailureKind {
    val isTemporaryStatus = this == HTTP_REQUEST_TIMEOUT || this >= HTTP_SERVER_ERROR_MIN

    return if (isTemporaryStatus) {
        MarketDataFailureKind.TEMPORARY
    } else {
        MarketDataFailureKind.PERMANENT
    }
}

private fun Long.multiplyExactOrMax(multiplier: Long): Long {
    return runCatching {
        Math.multiplyExact(this, multiplier)
    }.getOrDefault(Long.MAX_VALUE)
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
        else -> throw MarketDataParseException("GMO trade response contained an unsupported side.")
    }
}

private fun List<GmoMessage>.hasMessageCode(messageCode: String): Boolean {
    return any { message -> message.messageCode == messageCode }
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

/** GMO status response。 */
@Serializable
private data class GmoStatusResponse(
    val status: Int,
    val data: GmoStatusData = GmoStatusData(),
    val messages: List<GmoMessage> = emptyList(),
)

/** GMO status response の data。 */
@Serializable
private data class GmoStatusData(
    val status: String = "",
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
