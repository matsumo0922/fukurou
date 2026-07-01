package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource

/**
 * GMO コイン Public API の base URL。
 */
private const val GMO_PUBLIC_API_BASE_URL = "https://api.coin.z.com/public"

/**
 * GMO コイン Public ticker endpoint。
 */
private const val GMO_TICKER_PATH = "/v1/ticker"

/**
 * HTTP 成功 status code。
 */
private const val HTTP_OK = 200

/**
 * GMO API が成功時に返す status。
 */
private const val GMO_STATUS_OK = 0

/**
 * GMO Public API から ticker を取得する `MarketDataSource`。
 */
class GmoPublicMarketDataSource(
    private val httpClient: java.net.http.HttpClient = java.net.http.HttpClient.newHttpClient(),
    private val baseUrl: String = GMO_PUBLIC_API_BASE_URL,
    private val json: Json = GmoPublicApiJson,
) : MarketDataSource {

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> = withContext(Dispatchers.IO) {
        runCatching {
            val request = buildTickerRequest(symbol)
            val response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

            require(response.statusCode() == HTTP_OK) {
                "GMO ticker request failed with HTTP ${response.statusCode()}"
            }

            parseTickerResponse(response.body(), symbol, json)
        }
    }

    private fun buildTickerRequest(symbol: TradingSymbol): java.net.http.HttpRequest {
        val uri = java.net.URI.create("$baseUrl$GMO_TICKER_PATH?symbol=${symbol.apiSymbol}")

        return java.net.http.HttpRequest
            .newBuilder(uri)
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
