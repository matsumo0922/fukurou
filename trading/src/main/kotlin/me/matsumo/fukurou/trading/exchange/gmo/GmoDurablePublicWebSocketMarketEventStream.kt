package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicReference

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
        scope.launch {
            listener.awaitTermination()
            scope.cancel()
        }
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
    private val state = AtomicReference(DurableListenerState.CREATED)
    private val beginSettled = AtomicBoolean(false)
    private val disconnectStarted = AtomicBoolean(false)
    private val terminalSource = AtomicReference<DurableIngressGapSource?>()
    private val termination = CompletableDeferred<Unit>()
    private val fragments = StringBuilder()

    @Volatile
    private var startupDeadline: IngressOperationDeadline? = null

    override fun onOpen(webSocket: WebSocket) = Unit

    fun start(webSocket: WebSocket, subscription: String) {
        if (!state.compareAndSet(DurableListenerState.CREATED, DurableListenerState.BEGINNING)) return
        val deadline = IngressOperationDeadline.start()
        startupDeadline = deadline
        scope.launch {
            ingress.begin(sessionId, identity, deadline).fold(
                onSuccess = {
                    beginSettled.set(true)
                    if (!state.compareAndSet(DurableListenerState.BEGINNING, DurableListenerState.SUBSCRIBING)) {
                        ensureDisconnect(webSocket)
                        return@fold
                    }
                    webSocket.sendText(subscription, true).whenComplete { _, failure ->
                        if (failure == null) {
                            activate(webSocket)
                        } else {
                            fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR)
                        }
                    }
                },
                onFailure = {
                    beginSettled.set(true)
                    fail(webSocket, DurableIngressGapSource.DATABASE_FAILURE)
                },
            )
        }
    }

    private fun activate(webSocket: WebSocket) {
        if (!state.compareAndSet(DurableListenerState.SUBSCRIBING, DurableListenerState.ACTIVATING)) return
        val deadline = startupDeadline ?: return webSocket.abort()
        scope.launch {
            ingress.activate(sessionId, deadline).fold(
                onSuccess = { activated ->
                    if (activated && state.compareAndSet(DurableListenerState.ACTIVATING, DurableListenerState.CONNECTED)) {
                        webSocket.request(1)
                    } else if (!activated) {
                        fail(webSocket, DurableIngressGapSource.SEQUENCE_GAP)
                    }
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
        if (state.get() != DurableListenerState.CONNECTED) return null
        synchronized(fragments) {
            if (state.get() != DurableListenerState.CONNECTED) return null
            fragments.append(data)
            if (!last) {
                webSocket.request(1)
                return null
            }

            val payload = fragments.toString()
            fragments.setLength(0)
            val decoded = runCatching { decoder.decode(payload, clock.instant()) }
            decoded.fold(
                onSuccess = { event ->
                    if (event == null) {
                        if (state.get() == DurableListenerState.CONNECTED) webSocket.request(1)
                    } else {
                        register(webSocket, event)
                    }
                },
                onFailure = { fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR) },
            )
        }

        return null
    }

    private fun register(webSocket: WebSocket, event: PaperMarketTradeEvent) {
        if (!state.compareAndSet(DurableListenerState.CONNECTED, DurableListenerState.REGISTERING)) {
            fail(webSocket, DurableIngressGapSource.SEQUENCE_GAP)
            return
        }
        scope.launch {
            ingress.registerReceived(sessionId, event.sequence, IngressOperationDeadline.start()).fold(
                onSuccess = { registered ->
                    when {
                        !registered -> fail(webSocket, DurableIngressGapSource.SEQUENCE_GAP)
                        !state.compareAndSet(DurableListenerState.REGISTERING, DurableListenerState.CONNECTED) -> Unit
                        !events.trySend(event).isSuccess -> fail(webSocket, DurableIngressGapSource.BACKPRESSURE)
                        else -> webSocket.request(1)
                    }
                },
                onFailure = { fail(webSocket, DurableIngressGapSource.DATABASE_FAILURE) },
            )
        }
    }

    override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
        if (state.get() != DurableListenerState.CONNECTED) return CompletableFuture.completedFuture(webSocket)
        return webSocket.sendPong(message).whenComplete { _, failure ->
            if (failure == null && state.get() == DurableListenerState.CONNECTED) webSocket.request(1)
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

    suspend fun awaitTermination() = termination.await()

    private fun fail(webSocket: WebSocket, source: DurableIngressGapSource) {
        if (!claimTerminal(source)) return
        webSocket.abort()
        if (state.get() == DurableListenerState.TERMINATING && beginSettled.get()) {
            ensureDisconnect(webSocket)
        } else if (startupDeadline == null) {
            finishTerminal()
        }
    }

    @Synchronized
    private fun claimTerminal(source: DurableIngressGapSource): Boolean {
        while (true) {
            val current = state.get()
            if (current == DurableListenerState.TERMINATING || current == DurableListenerState.TERMINATED) return false
            terminalSource.set(source)
            if (state.compareAndSet(current, DurableListenerState.TERMINATING)) {
                return true
            }
        }
    }

    private fun ensureDisconnect(webSocket: WebSocket) {
        if (!disconnectStarted.compareAndSet(false, true)) return
        val source = terminalSource.get() ?: DurableIngressGapSource.TRANSPORT_ERROR
        scope.launch {
            try {
                ingress.disconnect(sessionId, source, IngressOperationDeadline.start())
            } finally {
                webSocket.abort()
                finishTerminal()
            }
        }
    }

    private fun finishTerminal() {
        state.set(DurableListenerState.TERMINATED)
        events.close()
        termination.complete(Unit)
    }
}

/** durable listenerのasync callbackを直列化する有限状態。 */
private enum class DurableListenerState {
    CREATED,
    BEGINNING,
    SUBSCRIBING,
    ACTIVATING,
    CONNECTED,
    REGISTERING,
    TERMINATING,
    TERMINATED,
}
