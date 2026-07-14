package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.CompletableDeferred
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
    private val raceSeam: DurableListenerRaceSeam = DurableListenerRaceSeam.NONE,
) : WebSocket.Listener {
    private val lifecycleLock = Any()
    private var state = DurableListenerState.CREATED
    private var beginSettled = false
    private var disconnectStarted = false
    private var terminalSource: DurableIngressGapSource? = null
    private val termination = CompletableDeferred<Unit>()
    private val fragments = StringBuilder()
    private var startupDeadline: IngressOperationDeadline? = null

    override fun onOpen(webSocket: WebSocket) = Unit

    fun start(webSocket: WebSocket, subscription: String) {
        val deadline = IngressOperationDeadline.start()
        synchronized(lifecycleLock) {
            if (state != DurableListenerState.CREATED) return
            state = DurableListenerState.BEGINNING
            raceSeam.before(DurableListenerRacePoint.F1_BEGIN)
            if (state != DurableListenerState.BEGINNING) return
            startupDeadline = deadline
            scope.launch { begin(webSocket, subscription, deadline) }
        }
    }

    private suspend fun begin(
        webSocket: WebSocket,
        subscription: String,
        deadline: IngressOperationDeadline,
    ) {
        ingress.begin(sessionId, identity, deadline).fold(
            onSuccess = { beginSucceeded(webSocket, subscription) },
            onFailure = {
                val terminalClaimed = synchronized(lifecycleLock) {
                    beginSettled = true
                    state == DurableListenerState.TERMINATING
                }
                if (terminalClaimed) {
                    ensureDisconnect(webSocket)
                } else {
                    fail(webSocket, DurableIngressGapSource.DATABASE_FAILURE)
                }
            },
        )
    }

    private fun beginSucceeded(webSocket: WebSocket, subscription: String) {
        var disconnectRequired = false
        synchronized(lifecycleLock) {
            beginSettled = true
            if (state == DurableListenerState.BEGINNING) {
                raceSeam.before(DurableListenerRacePoint.F2_SUBSCRIBE)
                if (state == DurableListenerState.BEGINNING) {
                    state = DurableListenerState.SUBSCRIBING
                    webSocket.sendText(subscription, true).whenComplete { _, failure ->
                        if (failure == null) {
                            activate(webSocket)
                        } else {
                            fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR)
                        }
                    }
                }
            } else {
                disconnectRequired = true
            }
        }
        if (disconnectRequired) ensureDisconnect(webSocket)
    }

    private fun activate(webSocket: WebSocket) {
        val deadline = synchronized(lifecycleLock) {
            if (state != DurableListenerState.SUBSCRIBING) return
            state = DurableListenerState.ACTIVATING
            startupDeadline
        } ?: return fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR)
        scope.launch {
            ingress.activate(sessionId, deadline).fold(
                onSuccess = { activated ->
                    if (!activated) {
                        fail(webSocket, DurableIngressGapSource.SEQUENCE_GAP)
                    } else {
                        synchronized(lifecycleLock) {
                            if (state != DurableListenerState.ACTIVATING) return@fold
                            raceSeam.before(DurableListenerRacePoint.F3_ACTIVATE)
                            if (state == DurableListenerState.ACTIVATING) {
                                state = DurableListenerState.CONNECTED
                                webSocket.request(1)
                            }
                        }
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
        var event: PaperMarketTradeEvent? = null
        synchronized(lifecycleLock) {
            if (state != DurableListenerState.CONNECTED) return null
            fragments.append(data)
            if (!last) {
                raceSeam.before(DurableListenerRacePoint.F4_DECODE)
                if (state == DurableListenerState.CONNECTED) webSocket.request(1)
                return null
            }

            val payload = fragments.toString()
            fragments.setLength(0)
            raceSeam.before(DurableListenerRacePoint.F4_DECODE)
            if (state != DurableListenerState.CONNECTED) return null
            val decoded = runCatching { decoder.decode(payload, clock.instant()) }
            decoded.fold(
                onSuccess = { decodedEvent ->
                    if (decodedEvent == null) {
                        if (state == DurableListenerState.CONNECTED) webSocket.request(1)
                    } else {
                        state = DurableListenerState.REGISTERING
                        event = decodedEvent
                    }
                },
                onFailure = { fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR) },
            )
        }
        event?.let { decodedEvent -> register(webSocket, decodedEvent) }

        return null
    }

    private fun register(webSocket: WebSocket, event: PaperMarketTradeEvent) {
        scope.launch {
            ingress.registerReceived(sessionId, event.sequence, IngressOperationDeadline.start()).fold(
                onSuccess = { registered ->
                    if (!registered) {
                        fail(webSocket, DurableIngressGapSource.SEQUENCE_GAP)
                    } else {
                        synchronized(lifecycleLock) {
                            if (state != DurableListenerState.REGISTERING) return@fold
                            raceSeam.before(DurableListenerRacePoint.F5_REGISTER)
                            if (state != DurableListenerState.REGISTERING) return@fold
                            if (!events.trySend(event).isSuccess) {
                                fail(webSocket, DurableIngressGapSource.BACKPRESSURE)
                            } else {
                                state = DurableListenerState.CONNECTED
                                webSocket.request(1)
                            }
                        }
                    }
                },
                onFailure = { fail(webSocket, DurableIngressGapSource.DATABASE_FAILURE) },
            )
        }
    }

    override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
        return synchronized(lifecycleLock) {
            if (state != DurableListenerState.CONNECTED) return CompletableFuture.completedFuture(webSocket)
            raceSeam.before(DurableListenerRacePoint.F6_PING)
            if (state != DurableListenerState.CONNECTED) return CompletableFuture.completedFuture(webSocket)
            webSocket.sendPong(message).whenComplete { _, failure ->
                if (failure != null) {
                    fail(webSocket, DurableIngressGapSource.TRANSPORT_ERROR)
                } else {
                    synchronized(lifecycleLock) {
                        if (state != DurableListenerState.CONNECTED) return@whenComplete
                        raceSeam.before(DurableListenerRacePoint.F6_PING)
                        if (state == DurableListenerState.CONNECTED) webSocket.request(1)
                    }
                }
            }
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
        var disconnectRequired = false
        synchronized(lifecycleLock) {
            if (!claimTerminal(source)) return
            webSocket.abort()
            if (beginSettled) {
                disconnectRequired = true
            } else if (startupDeadline == null) {
                finishTerminal()
            }
        }
        if (disconnectRequired) ensureDisconnect(webSocket)
    }

    private fun claimTerminal(source: DurableIngressGapSource): Boolean {
        if (state == DurableListenerState.TERMINATING || state == DurableListenerState.TERMINATED) return false
        terminalSource = source
        state = DurableListenerState.TERMINATING
        return true
    }

    private fun ensureDisconnect(webSocket: WebSocket) {
        val source = synchronized(lifecycleLock) {
            if (disconnectStarted) return
            disconnectStarted = true
            terminalSource ?: DurableIngressGapSource.TRANSPORT_ERROR
        }
        scope.launch {
            try {
                ingress.disconnect(sessionId, source, IngressOperationDeadline.start())
            } finally {
                synchronized(lifecycleLock) {
                    webSocket.abort()
                    finishTerminal()
                }
            }
        }
    }

    private fun finishTerminal() {
        raceSeam.before(DurableListenerRacePoint.F8_TERMINATION)
        if (state != DurableListenerState.TERMINATING) return
        state = DurableListenerState.TERMINATED
        events.close()
        termination.complete(Unit)
    }
}

/** async lifecycle raceをside-effect直前へ決定的に注入するtest seam。 */
internal fun interface DurableListenerRaceSeam {
    fun before(point: DurableListenerRacePoint)

    companion object {
        val NONE = DurableListenerRaceSeam { }
    }
}

/** finite inventory F1-F6/F8に対応するrace注入点。 */
internal enum class DurableListenerRacePoint {
    F1_BEGIN,
    F2_SUBSCRIBE,
    F3_ACTIVATE,
    F4_DECODE,
    F5_REGISTER,
    F6_PING,
    F8_TERMINATION,
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
