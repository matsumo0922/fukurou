package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.DurableIngressGapSource
import me.matsumo.fukurou.trading.market.DurableMarketEventIngress
import me.matsumo.fukurou.trading.market.IngressOperationDeadline
import me.matsumo.fukurou.trading.market.MarketStreamIdentity
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

private const val DURABLE_EVENT_BUFFER_CAPACITY = 1_024

/** C1aでproduction未接続のpaused durable GMO WebSocket stream。 */
class GmoDurablePublicWebSocketMarketEventStream(
    private val ingress: DurableMarketEventIngress,
    private val config: GmoPublicWebSocketConfig = GmoPublicWebSocketConfig(),
    private val symbol: TradingSymbol = TradingSymbol.BTC,
    private val clock: Clock = Clock.systemUTC(),
    private val connector: DurableWebSocketConnector = JdkDurableWebSocketConnector(config),
) {
    /** paused openからdurable STARTINGを登録し、async subscribeを開始する。 */
    fun connect(): Result<GmoDurableMarketEventSession> = runCatching {
        val sessionId = UUID.randomUUID()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = Channel<PaperMarketTradeEvent>(DURABLE_EVENT_BUFFER_CAPACITY)
        val listener = GmoDurableWebSocketListener(
            sessionId = sessionId,
            identity = MarketStreamIdentity("GMO_COIN", "${symbol.name}_JPY", "TRADES"),
            ingress = ingress,
            decoder = GmoTradeMessageDecoder(symbol, sessionId),
            events = events,
            scope = scope,
            clock = clock,
        )
        val socket = connector.connect(URI.create(config.endpoint), listener).join()

        listener.start(socket, durableSubscribeMessage(symbol))
        GmoDurableMarketEventSession(socket, events, scope, listener)
    }
}

private fun durableSubscribeMessage(symbol: TradingSymbol): String {
    return """{"command":"subscribe","channel":"trades","symbol":"${symbol.apiSymbol}"}"""
}

/** WebSocket生成をfakeへ置換するためのasync seam。 */
fun interface DurableWebSocketConnector {
    fun connect(uri: URI, listener: WebSocket.Listener): CompletableFuture<WebSocket>
}

private class JdkDurableWebSocketConnector(config: GmoPublicWebSocketConfig) : DurableWebSocketConnector {
    private val client = HttpClient.newBuilder().connectTimeout(config.connectTimeout).build()

    override fun connect(uri: URI, listener: WebSocket.Listener): CompletableFuture<WebSocket> {
        return client.newWebSocketBuilder().buildAsync(uri, listener)
    }
}

/** durable stream 1接続分のsession。 */
class GmoDurableMarketEventSession internal constructor(
    private val socket: WebSocket,
    private val events: Channel<PaperMarketTradeEvent>,
    private val scope: CoroutineScope,
    private val listener: GmoDurableWebSocketListener,
) : AutoCloseable {
    suspend fun receive(): PaperMarketTradeEvent = events.receive()

    override fun close() {
        listener.stop(socket, DurableIngressGapSource.TRANSPORT_ERROR)
        events.close()
        scope.cancel()
    }
}

/** onOpenでdemandを増やさない有限state listener。 */
@Suppress("LongParameterList")
internal class GmoDurableWebSocketListener(
    private val sessionId: UUID,
    private val identity: MarketStreamIdentity,
    private val ingress: DurableMarketEventIngress,
    private val decoder: GmoTradeMessageDecoder,
    private val events: Channel<PaperMarketTradeEvent>,
    private val scope: CoroutineScope,
    private val clock: Clock,
) : WebSocket.Listener {
    private val terminal = AtomicBoolean(false)
    private val registrationInFlight = AtomicBoolean(false)
    private val fragments = StringBuilder()

    @Volatile
    private var startupDeadline: IngressOperationDeadline? = null

    override fun onOpen(webSocket: WebSocket) = Unit

    fun start(webSocket: WebSocket, subscription: String) {
        if (terminal.get()) return
        val deadline = IngressOperationDeadline.start()
        startupDeadline = deadline
        scope.launch {
            ingress.begin(sessionId, identity, deadline).fold(
                onSuccess = {
                    if (terminal.get()) return@fold
                    webSocket.sendText(subscription, true).whenComplete { _, failure ->
                        if (failure == null) activate(webSocket) else fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR)
                    }
                },
                onFailure = { fail(webSocket, DurableIngressGapSource.DATABASE_FAILURE) },
            )
        }
    }

    private fun activate(webSocket: WebSocket) {
        val deadline = startupDeadline ?: return webSocket.abort()
        scope.launch {
            ingress.activate(sessionId, deadline).fold(
                onSuccess = { activated ->
                    if (activated && !terminal.get()) webSocket.request(1) else webSocket.abort()
                },
                onFailure = { fail(webSocket, DurableIngressGapSource.DATABASE_FAILURE) },
            )
        }
    }

    override fun onText(
        webSocket: WebSocket,
        data: CharSequence,
        last: Boolean,
    ): CompletionStage<*>? {
        synchronized(fragments) {
            fragments.append(data)
            if (!last) return null

            val payload = fragments.toString()
            fragments.setLength(0)
            val decoded = runCatching { decoder.decode(payload, clock.instant()) }
            decoded.fold(
                onSuccess = { event ->
                    if (event == null) webSocket.request(1) else register(webSocket, event)
                },
                onFailure = { fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR) },
            )
        }

        return null
    }

    private fun register(webSocket: WebSocket, event: PaperMarketTradeEvent) {
        if (!registrationInFlight.compareAndSet(false, true)) {
            fail(webSocket, DurableIngressGapSource.SEQUENCE_GAP)
            return
        }
        scope.launch {
            try {
                ingress.registerReceived(sessionId, event.sequence, IngressOperationDeadline.start()).fold(
                    onSuccess = { registered ->
                        if (!registered) {
                            fail(webSocket, DurableIngressGapSource.SEQUENCE_GAP)
                        } else if (!events.trySend(event).isSuccess) {
                            fail(webSocket, DurableIngressGapSource.BACKPRESSURE)
                        } else if (!terminal.get()) {
                            webSocket.request(1)
                        }
                    },
                    onFailure = { fail(webSocket, DurableIngressGapSource.DATABASE_FAILURE) },
                )
            } finally {
                registrationInFlight.set(false)
            }
        }
    }

    override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
        return webSocket.sendPong(message).whenComplete { _, failure ->
            if (failure == null && !terminal.get()) webSocket.request(1)
            if (failure != null) fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR)
        }
    }

    override fun onClose(
        webSocket: WebSocket,
        statusCode: Int,
        reason: String,
    ): CompletionStage<*>? {
        fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR)
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR)
    }

    fun stop(webSocket: WebSocket, source: DurableIngressGapSource) = fail(webSocket, source)

    private fun fail(webSocket: WebSocket, source: DurableIngressGapSource) {
        if (!terminal.compareAndSet(false, true)) return
        webSocket.abort()
        if (startupDeadline != null) {
            scope.launch { ingress.disconnect(sessionId, source, IngressOperationDeadline.start()) }
        }
        events.close()
    }
}
