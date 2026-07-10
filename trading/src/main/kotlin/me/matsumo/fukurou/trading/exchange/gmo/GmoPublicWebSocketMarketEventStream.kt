package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketEventSession
import me.matsumo.fukurou.trading.market.MarketEventStream
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

/**
 * GMO Public WebSocket `trades` channel を paper execution event に変換する stream。
 *
 * @param config WebSocket endpoint / timeout 設定
 * @param symbol subscribe 対象 symbol
 * @param clock local receive time に使う clock
 * @param httpClient JDK WebSocket client
 */
class GmoPublicWebSocketMarketEventStream(
    private val config: GmoPublicWebSocketConfig = GmoPublicWebSocketConfig(),
    private val symbol: TradingSymbol = TradingSymbol.BTC,
    private val clock: Clock = Clock.systemUTC(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .build(),
) : MarketEventStream {

    override suspend fun connect(): Result<MarketEventSession> {
        return runCatching {
            val sessionId = UUID.randomUUID()
            val connectedAt = clock.instant()
            val messages = Channel<Result<String>>(capacity = Channel.UNLIMITED)
            val listener = GmoWebSocketListener(messages)
            val socket = httpClient.newWebSocketBuilder()
                .connectTimeout(config.connectTimeout)
                .buildAsync(URI.create(config.endpoint), listener)
                .join()

            socket.sendText(subscribeMessage(symbol), true).join()

            GmoMarketEventSession(
                metadata = MarketEventSessionMetadata(sessionId, connectedAt),
                symbol = symbol,
                socket = socket,
                messages = messages,
                clock = clock,
                messageStaleTimeoutMillis = config.messageStaleTimeout.toMillis(),
            )
        }
    }
}

/**
 * GMO Public WebSocket 接続設定。
 *
 * @param endpoint Public WebSocket endpoint
 * @param connectTimeout 接続 timeout
 * @param messageStaleTimeout trade message 待機 timeout
 * @param reconnectBackoff 再接続前の待機時間
 */
data class GmoPublicWebSocketConfig(
    val endpoint: String = "wss://api.coin.z.com/ws/public/v1",
    val connectTimeout: java.time.Duration = java.time.Duration.ofSeconds(5),
    val messageStaleTimeout: java.time.Duration = java.time.Duration.ofSeconds(30),
    val reconnectBackoff: java.time.Duration = java.time.Duration.ofSeconds(2),
) {
    init {
        require(endpoint.startsWith("wss://") || endpoint.startsWith("ws://")) {
            "endpoint must use ws or wss."
        }
        require(!connectTimeout.isNegative && !connectTimeout.isZero) {
            "connectTimeout must be greater than 0."
        }
        require(!messageStaleTimeout.isNegative && !messageStaleTimeout.isZero) {
            "messageStaleTimeout must be greater than 0."
        }
        require(!reconnectBackoff.isNegative && !reconnectBackoff.isZero) {
            "reconnectBackoff must be greater than 0."
        }
    }
}

private class GmoMarketEventSession(
    private val metadata: MarketEventSessionMetadata,
    private val symbol: TradingSymbol,
    private val socket: WebSocket,
    private val messages: Channel<Result<String>>,
    private val clock: Clock,
    private val messageStaleTimeoutMillis: Long,
) : MarketEventSession {
    override val sessionId: UUID = metadata.sessionId
    override val connectedAt: Instant = metadata.connectedAt
    private val decoder = GmoTradeMessageDecoder(symbol, sessionId)

    override suspend fun receive(): Result<PaperMarketTradeEvent> {
        return runCatching {
            while (true) {
                val payload = withTimeout(messageStaleTimeoutMillis) {
                    messages.receive().getOrThrow()
                }
                val receivedAt = clock.instant()
                decoder.decode(payload, receivedAt)?.let { event -> return@runCatching event }
            }

            error("unreachable")
        }
    }

    override fun close() {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "session closed")
        messages.close()
    }
}

private data class MarketEventSessionMetadata(
    val sessionId: UUID,
    val connectedAt: Instant,
)

/** GMO JSON message を session-local sequence 付き event に変換する decoder。 */
internal class GmoTradeMessageDecoder(
    private val symbol: TradingSymbol,
    private val sessionId: UUID,
) {
    private val sequence = AtomicLong(0)

    fun decode(payload: String, receivedAt: Instant): PaperMarketTradeEvent? {
        val jsonObject = Json.parseToJsonElement(payload) as? JsonObject
            ?: error("GMO WebSocket message must be a JSON object.")
        val channel = jsonObject["channel"]?.jsonPrimitive?.content

        if (channel == null && jsonObject["status"] != null) {
            return null
        }
        require(channel == "trades") { "Unexpected GMO WebSocket channel: $channel" }
        require(jsonObject.requiredString("symbol") == symbol.apiSymbol) {
            "Unexpected GMO WebSocket symbol."
        }

        return PaperMarketTradeEvent(
            symbol = symbol,
            side = OrderSide.valueOf(jsonObject.requiredString("side")),
            priceJpy = jsonObject.requiredString("price").toBigDecimal(),
            sizeBtc = jsonObject.requiredString("size").toBigDecimal(),
            exchangeAt = Instant.parse(jsonObject.requiredString("timestamp")),
            receivedAt = receivedAt,
            connectionSessionId = sessionId,
            sequence = sequence.incrementAndGet(),
        )
    }
}

private class GmoWebSocketListener(
    private val messages: Channel<Result<String>>,
) : WebSocket.Listener {
    private val fragments = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(
        webSocket: WebSocket,
        data: CharSequence,
        last: Boolean,
    ): CompletionStage<*>? {
        synchronized(fragments) {
            fragments.append(data)

            if (last) {
                messages.trySend(Result.success(fragments.toString()))
                fragments.setLength(0)
            }
        }
        webSocket.request(1)

        return null
    }

    override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
        webSocket.request(1)

        return webSocket.sendPong(message)
    }

    override fun onClose(
        webSocket: WebSocket,
        statusCode: Int,
        reason: String,
    ): CompletionStage<*>? {
        messages.trySend(Result.failure(IllegalStateException("GMO WebSocket closed: $statusCode")))
        messages.close()

        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        messages.trySend(Result.failure(error))
        messages.close(error)
    }
}

private fun subscribeMessage(symbol: TradingSymbol): String {
    return """{"command":"subscribe","channel":"trades","symbol":"${symbol.apiSymbol}"}"""
}

private fun JsonObject.requiredString(key: String): String {
    return requireNotNull(this[key]?.jsonPrimitive?.content) {
        "GMO WebSocket trade message is missing $key."
    }
}
