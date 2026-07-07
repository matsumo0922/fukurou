package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.TradeSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.GmoApiStatusException
import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.MarketDataFailureKind
import me.matsumo.fukurou.trading.market.MarketDataParseException
import me.matsumo.fukurou.trading.market.MarketInvalidRequestException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * GMO Public market data source の parser と stitching を検証するテスト。
 */
class GmoPublicMarketDataSourceTest {

    @Test
    fun parseTickerResponse_returnsTicker() {
        val ticker = parseTickerResponse(TICKER_SUCCESS_RESPONSE, TradingSymbol.BTC)

        assertEquals("BTC", ticker.symbol)
        assertEquals("9748750", ticker.last)
        assertEquals("9747754", ticker.bid)
        assertEquals("9749399", ticker.ask)
        assertEquals("9820000", ticker.high)
        assertEquals("9400004", ticker.low)
        assertEquals("210.82216", ticker.volume)
        assertEquals("2026-07-01T16:26:57.323Z", ticker.timestamp)
    }

    @Test
    fun parseTickerResponse_rejectsMissingSymbol() {
        assertFailsWith<MarketDataParseException> {
            parseTickerResponse(MISSING_SYMBOL_RESPONSE, TradingSymbol.BTC)
        }
    }

    @Test
    fun parseTickerResponse_rejectsNonZeroStatus() {
        assertFailsWith<GmoApiStatusException> {
            parseTickerResponse(ERROR_RESPONSE, TradingSymbol.BTC)
        }
    }

    @Test
    fun parseTickerResponse_mapsRateLimitMessage() {
        assertFailsWith<GmoRateLimitException> {
            parseTickerResponse(RATE_LIMIT_RESPONSE, TradingSymbol.BTC)
        }
    }

    @Test
    fun marketDataExceptions_exposeFailureKind() {
        assertEquals(MarketDataFailureKind.TEMPORARY, GmoRateLimitException("rate limited").kind)
        assertEquals(MarketDataFailureKind.PERMANENT, MarketInvalidRequestException("bad request").kind)
    }

    @Test
    fun parseKlinesResponse_returnsCandles() {
        val candles = parseKlinesResponse(KLINES_SUCCESS_RESPONSE, TradingSymbol.BTC, CandleInterval.FIVE_MINUTES)
        val firstCandle = candles.first()

        assertEquals(2, candles.size)
        assertEquals("BTC", firstCandle.symbol)
        assertEquals(CandleInterval.FIVE_MINUTES, firstCandle.interval)
        assertEquals("2026-07-01T00:00:00Z", firstCandle.openTime)
        assertEquals("100", firstCandle.open)
        assertEquals("110", firstCandle.high)
        assertEquals("90", firstCandle.low)
        assertEquals("105", firstCandle.close)
        assertEquals("1.5", firstCandle.volume)
    }

    @Test
    fun parseOrderbookResponse_trimsDepth() {
        val orderbook = parseOrderbookResponse(ORDERBOOK_SUCCESS_RESPONSE, TradingSymbol.BTC, depth = 1)

        assertEquals("BTC", orderbook.symbol)
        assertEquals(1, orderbook.bids.size)
        assertEquals(1, orderbook.asks.size)
        assertEquals("9748000", orderbook.bids.first().price)
        assertEquals("9749000", orderbook.asks.first().price)
    }

    @Test
    fun parseTradesResponse_returnsRecentTrades() {
        val trades = parseTradesResponse(TRADES_SUCCESS_RESPONSE, TradingSymbol.BTC)
        val firstTrade = trades.first()

        assertEquals(2, trades.size)
        assertEquals("BTC", firstTrade.symbol)
        assertEquals("9748750", firstTrade.price)
        assertEquals("0.01", firstTrade.size)
        assertEquals(TradeSide.BUY, firstTrade.side)
        assertEquals("2026-07-01T16:26:58.000Z", firstTrade.timestamp)
    }

    @Test
    fun parseTradesResponse_rejectsNonZeroStatus() {
        assertFailsWith<GmoApiStatusException> {
            parseTradesResponse(TRADES_ERROR_RESPONSE, TradingSymbol.BTC)
        }
    }

    @Test
    fun parseSymbolsResponse_returnsRules() {
        val rules = parseSymbolsResponse(SYMBOLS_SUCCESS_RESPONSE, TradingSymbol.BTC)

        assertEquals("BTC", rules.symbol)
        assertEquals("0.0001", rules.minOrderSize)
        assertEquals("0.0001", rules.sizeStep)
        assertEquals("1", rules.tickSize)
        assertEquals("0.0005", rules.takerFee)
        assertEquals("-0.0001", rules.makerFee)
    }

    @Test
    fun parseSymbolsResponse_rejectsUnsafeNegativeTakerFee() {
        val response = SYMBOLS_SUCCESS_RESPONSE.replace(
            oldValue = "\"takerFee\": \"0.0005\"",
            newValue = "\"takerFee\": \"-0.0010\"",
        )

        assertFailsWith<MarketDataParseException> {
            parseSymbolsResponse(response, TradingSymbol.BTC)
        }
    }

    @Test
    fun getCandles_fetchesPreviousDayOnlyWhenTodayIsShort() = runBlocking {
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC&interval=5min&date=20260102" to klineResponse("2026-01-02T00:00:00Z"),
                "symbol=BTC&interval=5min&date=20260101" to klineResponse(
                    "2026-01-01T00:00:00Z",
                    "2026-01-01T00:05:00Z",
                ),
            ),
        )
        val marketDataSource = fakeMarketDataSource(httpClient)

        val candles = marketDataSource.getCandles(TradingSymbol.BTC, CandleInterval.FIVE_MINUTES, limit = 2).getOrThrow()

        assertEquals(
            listOf(
                "symbol=BTC&interval=5min&date=20260102",
                "symbol=BTC&interval=5min&date=20260101",
            ),
            httpClient.requestQueries,
        )
        assertEquals(listOf("2026-01-01T00:05:00Z", "2026-01-02T00:00:00Z"), candles.map { candle -> candle.openTime })
    }

    @Test
    fun getCandles_usesGmoBusinessDateBeforeSixAmJst() = runBlocking {
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC&interval=5min&date=20260101" to klineResponse("2026-01-01T18:00:00Z"),
            ),
        )
        val marketDataSource = fakeMarketDataSource(
            httpClient = httpClient,
            clock = Clock.fixed(Instant.parse("2026-01-01T18:00:00Z"), ZoneOffset.UTC),
        )

        marketDataSource.getCandles(TradingSymbol.BTC, CandleInterval.FIVE_MINUTES, limit = 1).getOrThrow()

        assertEquals(listOf("symbol=BTC&interval=5min&date=20260101"), httpClient.requestQueries)
    }

    @Test
    fun getCandles_treatsKlinesNotFoundAsEmptyAndContinuesStitching() = runBlocking {
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC&interval=5min&date=20260102" to KLINES_NOT_FOUND_RESPONSE,
                "symbol=BTC&interval=5min&date=20260101" to klineResponse("2026-01-01T00:00:00Z"),
                "symbol=BTC&interval=5min&date=20251231" to klineResponse("2025-12-31T23:55:00Z"),
            ),
        )
        val marketDataSource = fakeMarketDataSource(httpClient)

        val candles = marketDataSource.getCandles(TradingSymbol.BTC, CandleInterval.FIVE_MINUTES, limit = 2).getOrThrow()

        assertEquals(
            listOf(
                "symbol=BTC&interval=5min&date=20260102",
                "symbol=BTC&interval=5min&date=20260101",
                "symbol=BTC&interval=5min&date=20251231",
            ),
            httpClient.requestQueries,
        )
        assertEquals(listOf("2025-12-31T23:55:00Z", "2026-01-01T00:00:00Z"), candles.map { candle -> candle.openTime })
    }

    @Test
    fun getCandles_fetchesMultiplePreviousDaysUntilLimit() = runBlocking {
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC&interval=1hour&date=20260102" to klineResponse("2026-01-02T00:00:00Z"),
                "symbol=BTC&interval=1hour&date=20260101" to klineResponse("2026-01-01T00:00:00Z"),
                "symbol=BTC&interval=1hour&date=20251231" to klineResponse("2025-12-31T00:00:00Z"),
            ),
        )
        val marketDataSource = fakeMarketDataSource(httpClient)

        val candles = marketDataSource.getCandles(TradingSymbol.BTC, CandleInterval.ONE_HOUR, limit = 3).getOrThrow()

        assertEquals(
            listOf(
                "symbol=BTC&interval=1hour&date=20260102",
                "symbol=BTC&interval=1hour&date=20260101",
                "symbol=BTC&interval=1hour&date=20251231",
            ),
            httpClient.requestQueries,
        )
        assertEquals(
            listOf("2025-12-31T00:00:00Z", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"),
            candles.map { candle -> candle.openTime },
        )
    }

    @Test
    fun getCandles_stopsAtInjectedDailyKlineRequestBudget() = runBlocking {
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC&interval=1hour&date=20260102" to klineResponse("2026-01-02T00:00:00Z"),
                "symbol=BTC&interval=1hour&date=20260101" to klineResponse("2026-01-01T00:00:00Z"),
            ),
        )
        val marketDataSource = fakeMarketDataSource(
            httpClient = httpClient,
            dailyKlineRequestBudget = GmoFixedDailyKlineRequestBudget(maxRequests = 1),
        )

        val candles = marketDataSource.getCandles(TradingSymbol.BTC, CandleInterval.ONE_HOUR, limit = 2).getOrThrow()

        assertEquals(listOf("symbol=BTC&interval=1hour&date=20260102"), httpClient.requestQueries)
        assertEquals(listOf("2026-01-02T00:00:00Z"), candles.map { candle -> candle.openTime })
    }

    @Test
    fun getCandles_allowsUnlimitedDailyKlineRequestBudget() = runBlocking {
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC&interval=1hour&date=20260102" to klineResponse("2026-01-02T00:00:00Z"),
                "symbol=BTC&interval=1hour&date=20260101" to klineResponse("2026-01-01T00:00:00Z"),
                "symbol=BTC&interval=1hour&date=20251231" to klineResponse("2025-12-31T00:00:00Z"),
            ),
        )
        val marketDataSource = fakeMarketDataSource(
            httpClient = httpClient,
            dailyKlineRequestBudget = GmoUnlimitedDailyKlineRequestBudget,
        )

        val candles = marketDataSource.getCandles(TradingSymbol.BTC, CandleInterval.ONE_HOUR, limit = 3).getOrThrow()

        assertEquals(
            listOf(
                "symbol=BTC&interval=1hour&date=20260102",
                "symbol=BTC&interval=1hour&date=20260101",
                "symbol=BTC&interval=1hour&date=20251231",
            ),
            httpClient.requestQueries,
        )
        assertEquals(
            listOf("2025-12-31T00:00:00Z", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"),
            candles.map { candle -> candle.openTime },
        )
    }

    @Test
    fun getCandles_fetchesPreviousYearForYearRangeIntervals() = runBlocking {
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC&interval=1day&date=2026" to klineResponse("2026-01-01T00:00:00Z"),
                "symbol=BTC&interval=1day&date=2025" to klineResponse(
                    "2025-12-30T00:00:00Z",
                    "2025-12-31T00:00:00Z",
                ),
            ),
        )
        val marketDataSource = fakeMarketDataSource(httpClient)

        val candles = marketDataSource.getCandles(TradingSymbol.BTC, CandleInterval.ONE_DAY, limit = 2).getOrThrow()

        assertEquals(
            listOf(
                "symbol=BTC&interval=1day&date=2026",
                "symbol=BTC&interval=1day&date=2025",
            ),
            httpClient.requestQueries,
        )
        assertEquals(listOf("2025-12-31T00:00:00Z", "2026-01-01T00:00:00Z"), candles.map { candle -> candle.openTime })
    }

    @Test
    fun getCandles_usesPreviousYearBeforeSixAmJstOnNewYear() = runBlocking {
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC&interval=1day&date=2025" to klineResponse("2025-12-31T00:00:00Z"),
            ),
        )
        val marketDataSource = fakeMarketDataSource(
            httpClient = httpClient,
            clock = Clock.fixed(Instant.parse("2025-12-31T18:00:00Z"), ZoneOffset.UTC),
        )

        marketDataSource.getCandles(TradingSymbol.BTC, CandleInterval.ONE_DAY, limit = 1).getOrThrow()

        assertEquals(listOf("symbol=BTC&interval=1day&date=2025"), httpClient.requestQueries)
    }

    @Test
    fun getSymbolRules_usesTtlCache() = runBlocking {
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "" to SYMBOLS_SUCCESS_RESPONSE,
            ),
        )
        val marketDataSource = fakeMarketDataSource(httpClient)

        marketDataSource.getSymbolRules(TradingSymbol.BTC).getOrThrow()
        marketDataSource.getSymbolRules(TradingSymbol.BTC).getOrThrow()

        assertEquals(listOf(""), httpClient.requestQueries)
    }

    @Test
    fun getTicker_acquiresRateLimitPermitBeforeRequest() = runBlocking {
        val rateLimiter = RecordingRateLimiter()
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC" to TICKER_SUCCESS_RESPONSE,
            ),
        )
        val marketDataSource = fakeMarketDataSource(
            httpClient = httpClient,
            requestRateLimiter = rateLimiter,
        )

        marketDataSource.getTicker(TradingSymbol.BTC).getOrThrow()

        assertEquals(listOf("ticker"), rateLimiter.endpointNames)
        assertEquals(listOf("symbol=BTC"), httpClient.requestQueries)
    }

    @Test
    fun getTicker_retriesTemporaryHttpFailure() = runBlocking {
        val sleeper = RecordingSleeper()
        val httpClient = FakeHttpClient(
            responses = mapOf(
                "symbol=BTC" to TICKER_SUCCESS_RESPONSE,
            ),
            statusCodes = mapOf(
                "symbol=BTC" to listOf(500, 200),
            ),
        )
        val marketDataSource = fakeMarketDataSource(
            httpClient = httpClient,
            retryConfig = GmoRetryConfig(
                maxAttempts = 2,
                initialBackoff = Duration.ofMillis(25),
                maxBackoff = Duration.ofMillis(100),
                backoffMultiplier = 2,
            ),
            sleeper = sleeper,
        )

        val ticker = marketDataSource.getTicker(TradingSymbol.BTC).getOrThrow()

        assertEquals("BTC", ticker.symbol)
        assertEquals(listOf("symbol=BTC", "symbol=BTC"), httpClient.requestQueries)
        assertEquals(listOf(Duration.ofMillis(25)), sleeper.durations)
    }

    @Test
    fun getCandles_rejectsInvalidLimit() = runBlocking {
        val httpClient = FakeHttpClient(responses = emptyMap())
        val marketDataSource = fakeMarketDataSource(httpClient)
        val result = marketDataSource.getCandles(TradingSymbol.BTC, CandleInterval.FIVE_MINUTES, limit = 0)

        assertFailsWith<MarketInvalidRequestException> {
            result.getOrThrow()
        }
        assertEquals(emptyList(), httpClient.requestQueries)
    }
}

/**
 * ticker parse 成功 fixture。
 */
private const val TICKER_SUCCESS_RESPONSE = """
{
  "status": 0,
  "data": [
    {
      "ask": "9749399",
      "bid": "9747754",
      "high": "9820000",
      "last": "9748750",
      "low": "9400004",
      "symbol": "BTC",
      "timestamp": "2026-07-01T16:26:57.323Z",
      "volume": "210.82216"
    }
  ],
  "responsetime": "2026-07-01T16:26:57.410Z"
}
"""

/**
 * 対象 symbol が存在しない ticker fixture。
 */
private const val MISSING_SYMBOL_RESPONSE = """
{
  "status": 0,
  "data": []
}
"""

/**
 * GMO status error fixture。
 */
private const val ERROR_RESPONSE = """
{
  "status": 1,
  "data": []
}
"""

/**
 * rate limit message fixture。
 */
private const val RATE_LIMIT_RESPONSE = """
{
  "status": 9,
  "messages": [
    {
      "message_code": "ERR-5003",
      "message_string": "Requests are too many."
    }
  ],
  "data": []
}
"""

/**
 * klines parse 成功 fixture。
 */
private const val KLINES_SUCCESS_RESPONSE = """
{
  "status": 0,
  "data": [
    {
      "openTime": "2026-07-01T00:00:00Z",
      "open": "100",
      "high": "110",
      "low": "90",
      "close": "105",
      "volume": "1.5"
    },
    {
      "openTime": "2026-07-01T00:05:00Z",
      "open": "105",
      "high": "115",
      "low": "95",
      "close": "110",
      "volume": "1.2"
    }
  ]
}
"""

/**
 * klines not found fixture。
 */
private const val KLINES_NOT_FOUND_RESPONSE = """
{
  "status": 2,
  "messages": [
    {
      "message_code": "ERR-5207",
      "message_string": "Not found"
    }
  ],
  "data": []
}
"""

/**
 * orderbook parse 成功 fixture。
 */
private const val ORDERBOOK_SUCCESS_RESPONSE = """
{
  "status": 0,
  "data": {
    "symbol": "BTC",
    "bids": [
      {
        "price": "9748000",
        "size": "0.2"
      },
      {
        "price": "9747000",
        "size": "0.3"
      }
    ],
    "asks": [
      {
        "price": "9749000",
        "size": "0.1"
      },
      {
        "price": "9750000",
        "size": "0.4"
      }
    ]
  }
}
"""

/**
 * trades parse 成功 fixture。
 */
private const val TRADES_SUCCESS_RESPONSE = """
{
  "status": 0,
  "data": {
    "list": [
      {
        "price": "9748750",
        "size": "0.01",
        "side": "BUY",
        "timestamp": "2026-07-01T16:26:58.000Z"
      },
      {
        "price": "9748740",
        "size": "0.02",
        "side": "SELL",
        "timestamp": "2026-07-01T16:26:59.000Z"
      }
    ]
  },
  "responsetime": "2026-07-01T16:27:00.000Z"
}
"""

/**
 * trades status error fixture。
 */
private const val TRADES_ERROR_RESPONSE = """
{
  "status": 1,
  "data": {
    "list": []
  }
}
"""

/**
 * symbols parse 成功 fixture。
 */
private const val SYMBOLS_SUCCESS_RESPONSE = """
{
  "status": 0,
  "data": [
    {
      "symbol": "BTC",
      "minOrderSize": "0.0001",
      "sizeStep": "0.0001",
      "tickSize": "1",
      "takerFee": "0.0005",
      "makerFee": "-0.0001"
    }
  ]
}
"""

private fun klineResponse(vararg openTimes: String): String {
    val candles = openTimes.joinToString(separator = ",") { openTime ->
        """
        {
          "openTime": "$openTime",
          "open": "100",
          "high": "110",
          "low": "90",
          "close": "105",
          "volume": "1.0"
        }
        """.trimIndent()
    }

    return """
    {
      "status": 0,
      "data": [
        $candles
      ]
    }
    """.trimIndent()
}

private fun fakeMarketDataSource(
    httpClient: FakeHttpClient,
    clock: Clock = Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC),
    requestRateLimiter: GmoRequestRateLimiter = NoopGmoRequestRateLimiter,
    retryConfig: GmoRetryConfig = GmoRetryConfig(),
    dailyKlineRequestBudget: GmoDailyKlineRequestBudget = GmoFixedDailyKlineRequestBudget(
        maxRequests = GMO_MAX_DAILY_KLINE_REQUESTS,
    ),
    sleeper: GmoSleeper = RecordingSleeper(),
): GmoPublicMarketDataSource {
    return GmoPublicMarketDataSource(
        httpClient = httpClient,
        baseUrl = "https://example.test/public",
        clock = clock,
        symbolRulesCacheTtl = Duration.ofMinutes(10),
        requestRateLimiter = requestRateLimiter,
        retryConfig = retryConfig,
        dailyKlineRequestBudget = dailyKlineRequestBudget,
        sleeper = sleeper,
    )
}

/**
 * request query に応じて固定 response を返す HTTP client。
 *
 * @param responses raw query と response body の対応
 * @param statusCodes raw query と status code sequence の対応
 */
private class FakeHttpClient(
    private val responses: Map<String, String>,
    private val statusCodes: Map<String, List<Int>> = emptyMap(),
) : HttpClient() {

    /**
     * 呼び出された query の一覧。
     */
    val requestQueries = mutableListOf<String>()
    private val responseIndexes = mutableMapOf<String, Int>()

    override fun cookieHandler(): Optional<CookieHandler> {
        return Optional.empty()
    }

    override fun connectTimeout(): Optional<Duration> {
        return Optional.empty()
    }

    override fun followRedirects(): Redirect {
        return Redirect.NEVER
    }

    override fun proxy(): Optional<ProxySelector> {
        return Optional.empty()
    }

    override fun sslContext(): SSLContext {
        return SSLContext.getDefault()
    }

    override fun sslParameters(): SSLParameters {
        return SSLParameters()
    }

    override fun authenticator(): Optional<Authenticator> {
        return Optional.empty()
    }

    override fun version(): Version {
        return Version.HTTP_1_1
    }

    override fun executor(): Optional<Executor> {
        return Optional.empty()
    }

    override fun <ResponseBody> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<ResponseBody>,
    ): HttpResponse<ResponseBody> {
        val query = request.uri().rawQuery.orEmpty()
        val body = requireNotNull(responses[query]) {
            "No fake response for query: $query"
        }
        val responseIndex = responseIndexes.getOrDefault(query, 0)
        val queryStatusCodes = statusCodes[query]
        val statusCode = queryStatusCodes?.getOrElse(responseIndex) { queryStatusCodes.last() } ?: 200

        requestQueries += query
        responseIndexes[query] = responseIndex + 1

        @Suppress("UNCHECKED_CAST")
        return FakeHttpResponse(request, body, statusCode) as HttpResponse<ResponseBody>
    }

    override fun <ResponseBody> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<ResponseBody>,
    ): CompletableFuture<HttpResponse<ResponseBody>> {
        error("sendAsync is not used in tests.")
    }

    override fun <ResponseBody> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<ResponseBody>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<ResponseBody>,
    ): CompletableFuture<HttpResponse<ResponseBody>> {
        error("sendAsync is not used in tests.")
    }
}

/**
 * 文字列 body を返す fake HTTP response。
 *
 * @param request 元 request
 * @param body response body
 * @param statusCode response status code
 */
private class FakeHttpResponse(
    private val request: HttpRequest,
    private val body: String,
    private val statusCode: Int,
) : HttpResponse<String> {

    override fun statusCode(): Int {
        return statusCode
    }

    override fun request(): HttpRequest {
        return request
    }

    override fun previousResponse(): Optional<HttpResponse<String>> {
        return Optional.empty()
    }

    override fun headers(): HttpHeaders {
        return HttpHeaders.of(emptyMap()) { headerName, headerValue -> headerName.isNotBlank() && headerValue.isNotBlank() }
    }

    override fun body(): String {
        return body
    }

    override fun sslSession(): Optional<SSLSession> {
        return Optional.empty()
    }

    override fun uri(): URI {
        return request.uri()
    }

    override fun version(): HttpClient.Version {
        return HttpClient.Version.HTTP_1_1
    }
}

/**
 * rate limiter 呼び出しを記録する fake。
 */
private class RecordingRateLimiter : GmoRequestRateLimiter {
    /**
     * permit を要求された endpoint 名。
     */
    val endpointNames = mutableListOf<String>()

    override fun acquirePermit(endpointName: String) {
        endpointNames += endpointName
    }
}

/**
 * retry sleep を記録する fake。
 */
private class RecordingSleeper : GmoSleeper {
    /**
     * sleep を要求された duration。
     */
    val durations = mutableListOf<Duration>()

    override fun sleep(duration: Duration) {
        durations += duration
    }
}
