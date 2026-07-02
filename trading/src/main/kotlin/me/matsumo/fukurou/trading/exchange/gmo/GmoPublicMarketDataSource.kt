package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradeSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * GMO コイン Public API の base URL。
 */
private const val GMO_PUBLIC_API_BASE_URL = "https://api.coin.z.com/public"

/**
 * GMO コイン Public ticker endpoint。
 */
private const val GMO_TICKER_PATH = "/v1/ticker"

/**
 * GMO コイン Public trades endpoint。
 */
private const val GMO_TRADES_PATH = "/v1/trades"

/**
 * GMO trades request の既定 page。
 */
private const val DEFAULT_TRADES_PAGE = 1

/**
 * GMO trades request の既定 count。
 */
private const val DEFAULT_TRADES_COUNT = 100

/**
 * HTTP 成功 status code。
 */
private const val HTTP_OK = 200

/**
 * GMO API が成功時に返す status。
 */
private const val GMO_STATUS_OK = 0

/**
 * GMO Public API への接続 timeout。
 */
private val DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5)

/**
 * GMO public request 全体の timeout。
 */
private val DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10)

/**
 * GMO Public API から ticker を取得する `MarketDataSource`。
 *
 * @param connectTimeout API 接続 timeout
 * @param requestTimeout public request 全体の timeout
 * @param httpClient API 呼び出しに使う HTTP client
 * @param baseUrl GMO Public API base URL
 * @param json API response の JSON parser
 */
class GmoPublicMarketDataSource(
    connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build(),
    private val baseUrl: String = GMO_PUBLIC_API_BASE_URL,
    private val json: Json = GmoPublicApiJson,
) : MarketDataSource {

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> = withContext(Dispatchers.IO) {
        runCatching {
            val request = buildTickerRequest(symbol)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            require(response.statusCode() == HTTP_OK) {
                "GMO ticker request failed with HTTP ${response.statusCode()}"
            }

            parseTickerResponse(response.body(), symbol, json)
        }
    }

    override suspend fun getRecentTrades(symbol: TradingSymbol): Result<List<RecentTrade>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = buildTradesRequest(symbol)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            require(response.statusCode() == HTTP_OK) {
                "GMO trades request failed with HTTP ${response.statusCode()}"
            }

            parseTradesResponse(response.body(), symbol, json)
        }
    }

    private fun buildTickerRequest(symbol: TradingSymbol): HttpRequest {
        val uri = URI.create("$baseUrl$GMO_TICKER_PATH?symbol=${symbol.apiSymbol}")

        return HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .GET()
            .build()
    }

    private fun buildTradesRequest(symbol: TradingSymbol): HttpRequest {
        val uri = URI.create(
            "$baseUrl$GMO_TRADES_PATH?symbol=${symbol.apiSymbol}&page=$DEFAULT_TRADES_PAGE&count=$DEFAULT_TRADES_COUNT",
        )

        return HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .GET()
            .build()
    }
}

/**
 * GMO Public API の ticker response を domain model へ変換する。
 */
fun parseTickerResponse(
    responseBody: String,
    symbol: TradingSymbol,
    json: Json = GmoPublicApiJson,
): Ticker {
    val response = json.decodeFromString<GmoTickerResponse>(responseBody)

    require(response.status == GMO_STATUS_OK) {
        "GMO ticker response status was ${response.status}"
    }

    val ticker = requireNotNull(response.data.firstOrNull { data -> data.symbol == symbol.apiSymbol }) {
        "GMO ticker response did not include ${symbol.apiSymbol}"
    }

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
 * GMO Public API の trades response を domain model へ変換する。
 */
fun parseTradesResponse(
    responseBody: String,
    symbol: TradingSymbol,
    json: Json = GmoPublicApiJson,
): List<RecentTrade> {
    val response = json.decodeFromString<GmoTradesResponse>(responseBody)

    require(response.status == GMO_STATUS_OK) {
        "GMO trades response status was ${response.status}"
    }

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
 * GMO trades response の side を domain model へ変換する。
 */
private fun parseTradeSide(side: String): TradeSide {
    return when (side.uppercase()) {
        "BUY" -> TradeSide.BUY
        "SELL" -> TradeSide.SELL
        else -> error("Unsupported GMO trade side: $side")
    }
}

/**
 * GMO Public API の JSON 設定。
 */
private val GmoPublicApiJson = Json {
    ignoreUnknownKeys = true
}

/**
 * GMO ticker response。
 *
 * @param status API status。0 が成功
 * @param data ticker 配列
 */
@Serializable
private data class GmoTickerResponse(
    val status: Int,
    val data: List<GmoTicker>,
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
    @SerialName("timestamp")
    val timestamp: String,
)

/**
 * GMO trades response。
 *
 * @param status API status。0 が成功
 * @param data trades ページ情報
 */
@Serializable
private data class GmoTradesResponse(
    val status: Int,
    val data: GmoTradesData,
)

/**
 * GMO trades response の data。
 *
 * @param list 約定リスト
 */
@Serializable
private data class GmoTradesData(
    val list: List<GmoTrade>,
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
