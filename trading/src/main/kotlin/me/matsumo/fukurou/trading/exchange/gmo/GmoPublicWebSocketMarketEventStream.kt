package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.InvalidMarketDataMessageException
import me.matsumo.fukurou.trading.market.MarketDataBackpressureException
import me.matsumo.fukurou.trading.market.MarketDataSubscriptionException
import me.matsumo.fukurou.trading.market.MarketEventReceiptIntegrityConflictException
import me.matsumo.fukurou.trading.market.MarketEventReceiptPersistenceException
import me.matsumo.fukurou.trading.market.MarketEventSession
import me.matsumo.fukurou.trading.market.MarketEventSessionSignal
import me.matsumo.fukurou.trading.market.MarketEventStream
import me.matsumo.fukurou.trading.market.PaperMarketEventReceiptAuthority
import me.matsumo.fukurou.trading.market.PaperMarketEventReceiptCommit
import me.matsumo.fukurou.trading.market.PaperMarketEventReceiptRepository
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import me.matsumo.fukurou.trading.market.TransportActivityKind
import me.matsumo.fukurou.trading.market.UnavailablePaperMarketEventReceiptRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

private const val MARKET_EVENT_BUFFER_CAPACITY = 1_024

private val RECEIPT_LOGGER = Logger.getLogger(GmoPublicWebSocketMarketEventStream::class.java.name)

/**
 * GMO Public WebSocket `trades` channel を paper execution event に変換する stream。
 *
 * @param config WebSocket endpoint / timeout 設定
 * @param symbol subscribe 対象 symbol
 * @param clock local receive time に使う clock
 * @param receiptRepository application queue より先に commit する durable receipt repository
 * @param httpClient JDK WebSocket client
 */
class GmoPublicWebSocketMarketEventStream(
    private val config: GmoPublicWebSocketConfig = GmoPublicWebSocketConfig(),
    private val symbol: TradingSymbol = TradingSymbol.BTC,
    private val clock: Clock = Clock.systemUTC(),
    private val receiptRepository: PaperMarketEventReceiptRepository =
        UnavailablePaperMarketEventReceiptRepository,
    private val receiptExecutor: Executor = ForkJoinPool.commonPool(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .build(),
) : MarketEventStream {

    override val reconnectBackoff: java.time.Duration
        get() = config.reconnectBackoff

    override val transportLivenessTimeout: java.time.Duration
        get() = config.transportLivenessTimeout

    override suspend fun connect(): Result<MarketEventSession> {
        var socket: WebSocket? = null
        var messages: Channel<Result<MarketEventSessionSignal>>? = null

        return runCatching {
            val sessionId = UUID.randomUUID()
            val connectedAt = clock.instant()
            val sessionMessages = Channel<Result<MarketEventSessionSignal>>(capacity = MARKET_EVENT_BUFFER_CAPACITY)
            messages = sessionMessages
            val listener = GmoWebSocketListener(
                messages = sessionMessages,
                decoder = GmoTradeMessageDecoder(symbol, sessionId),
                clock = clock,
                receiptRepository = receiptRepository,
                receiptExecutor = receiptExecutor,
            )
            val connectedSocket = httpClient.newWebSocketBuilder()
                .connectTimeout(config.connectTimeout)
                .buildAsync(URI.create(config.endpoint), listener)
                .join()
            socket = connectedSocket

            connectedSocket.sendText(subscribeMessage(symbol), true).join()

            GmoMarketEventSession(
                sessionId = sessionId,
                connectedAt = connectedAt,
                socket = connectedSocket,
                messages = sessionMessages,
            )
        }.onFailure { throwable ->
            socket?.abort()
            messages?.close(throwable)
        }
    }
}

/**
 * GMO Public WebSocket 接続設定。
 *
 * @param endpoint Public WebSocket endpoint
 * @param connectTimeout 接続 timeout
 * @param transportLivenessTimeout transport activity からgap判定までの時間
 * @param reconnectBackoff 再接続前の待機時間
 */
data class GmoPublicWebSocketConfig(
    val endpoint: String = "wss://api.coin.z.com/ws/public/v1",
    val connectTimeout: java.time.Duration = java.time.Duration.ofSeconds(5),
    val transportLivenessTimeout: java.time.Duration = java.time.Duration.ofSeconds(150),
    val reconnectBackoff: java.time.Duration = java.time.Duration.ofSeconds(2),
) {
    init {
        require(endpoint.startsWith("wss://") || endpoint.startsWith("ws://")) {
            "endpoint must use ws or wss."
        }
        require(!connectTimeout.isNegative && !connectTimeout.isZero) {
            "connectTimeout must be greater than 0."
        }
        require(!transportLivenessTimeout.isNegative && !transportLivenessTimeout.isZero) {
            "transportLivenessTimeout must be greater than 0."
        }
        require(reconnectBackoff >= java.time.Duration.ofSeconds(1)) {
            "reconnectBackoff must be at least 1 second."
        }
    }
}

/** GMO WebSocket 1接続分のevent受信session。 */
internal class GmoMarketEventSession(
    override val sessionId: UUID,
    override val connectedAt: Instant,
    private val socket: WebSocket,
    private val messages: Channel<Result<MarketEventSessionSignal>>,
) : MarketEventSession {
    override suspend fun receive(): Result<MarketEventSessionSignal> {
        return try {
            messages.receive()
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    override fun close() {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "session closed")
        messages.close()
    }
}

/** GMO JSON message を session-local sequence 付き event に変換する decoder。 */
internal class GmoTradeMessageDecoder(
    private val symbol: TradingSymbol,
    private val sessionId: UUID,
) {
    private val sequence = AtomicLong(0)

    fun decode(payload: String, receivedAt: Instant): PaperMarketTradeEvent? {
        val jsonObject = try {
            Json.parseToJsonElement(payload) as? JsonObject
                ?: throw InvalidMarketDataMessageException("GMO WebSocket message must be a JSON object.")
        } catch (throwable: InvalidMarketDataMessageException) {
            throw throwable
        } catch (throwable: Throwable) {
            throw InvalidMarketDataMessageException("GMO WebSocket message is not valid JSON.", throwable)
        }
        jsonObject.optionalString("error")?.let { error ->
            throw MarketDataSubscriptionException(error)
        }
        val channel = jsonObject["channel"]?.jsonPrimitive?.content

        if (channel == null && jsonObject["status"] != null) {
            val status = jsonObject.optionalString("status")
            if (status != "0") {
                throw MarketDataSubscriptionException("GMO WebSocket subscription failed: status=$status")
            }
            return null
        }
        if (channel != "trades") {
            throw InvalidMarketDataMessageException("Unexpected GMO WebSocket channel: $channel")
        }
        if (jsonObject.requiredString("symbol") != symbol.apiSymbol) {
            throw InvalidMarketDataMessageException("Unexpected GMO WebSocket symbol.")
        }

        return try {
            PaperMarketTradeEvent(
                symbol = symbol,
                side = OrderSide.valueOf(jsonObject.requiredString("side")),
                priceJpy = jsonObject.requiredString("price").toBigDecimal(),
                sizeBtc = jsonObject.requiredString("size").toBigDecimal(),
                exchangeAt = Instant.parse(jsonObject.requiredString("timestamp")),
                receivedAt = receivedAt,
                connectionSessionId = sessionId,
                sequence = sequence.incrementAndGet(),
            )
        } catch (throwable: InvalidMarketDataMessageException) {
            throw throwable
        } catch (throwable: Throwable) {
            throw InvalidMarketDataMessageException("GMO WebSocket trade message contains an invalid value.", throwable)
        }
    }
}

/** GMO WebSocket callbackを順序付きmarket eventへ変換するlistener。 */
internal class GmoWebSocketListener(
    private val messages: Channel<Result<MarketEventSessionSignal>>,
    private val decoder: GmoTradeMessageDecoder,
    private val clock: Clock,
    private val receiptRepository: PaperMarketEventReceiptRepository =
        UnavailablePaperMarketEventReceiptRepository,
    private val receiptExecutor: Executor = ForkJoinPool.commonPool(),
    private val terminalFailure: AtomicReference<Throwable?> = AtomicReference(),
    private val afterTerminalClaim: () -> Unit = {},
) : WebSocket.Listener {
    private val fragments = StringBuilder()
    private val dispatchLock = Any()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(
        webSocket: WebSocket,
        data: CharSequence,
        last: Boolean,
    ): CompletionStage<*>? {
        val dispatch = synchronized(fragments) {
            fragments.append(data)

            if (!last) {
                webSocket.request(1)
                return@synchronized null
            }

            val socketObservedAt = clock.instant()
            val payload = fragments.toString()
            fragments.setLength(0)
            val decoded = runCatching { decoder.decode(payload, socketObservedAt) }
            decoded.exceptionOrNull()?.let { throwable -> sendTerminalFailure(throwable) }

            if (decoded.isFailure) return@synchronized null

            decoded.getOrNull()?.let { event ->
                persistReceiptThenDispatch(webSocket, event)
            } ?: run {
                sendResult(
                    Result.success(
                        MarketEventSessionSignal.TransportActivity(
                            observedAt = socketObservedAt,
                            kind = TransportActivityKind.SUBSCRIPTION_ACKNOWLEDGED,
                        ),
                    ),
                )
                webSocket.request(1)
                null
            }
        }

        return dispatch
    }

    private fun persistReceiptThenDispatch(
        webSocket: WebSocket,
        event: PaperMarketTradeEvent,
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync(
            {
                val result = runCatching { runBlocking { receiptRepository.commit(event) } }
                    .getOrElse { throwable ->
                        val typedFailure = when (throwable) {
                            is MarketEventReceiptIntegrityConflictException,
                            is MarketEventReceiptPersistenceException,
                            -> throwable

                            else -> MarketEventReceiptPersistenceException(throwable)
                        }
                        Result.failure(typedFailure)
                    }
                result.fold(
                    onSuccess = { commit ->
                        logReceiptCommit(commit)
                        val committedEvent = event.copy(
                            receivedAt = commit.socketObservedAt,
                            receiptAuthority = PaperMarketEventReceiptAuthority(
                                receiptId = commit.receiptId,
                                admissionOrdinal = commit.admissionOrdinal,
                                payloadHash = commit.payloadHash,
                                socketObservedAt = commit.socketObservedAt,
                            ),
                        )
                        sendResult(Result.success(MarketEventSessionSignal.Trade(committedEvent)))
                        webSocket.request(1)
                    },
                    onFailure = ::sendTerminalFailure,
                )
            },
            receiptExecutor,
        )
    }

    override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
        webSocket.request(1)

        return webSocket.sendPong(message).whenComplete { _, throwable ->
            if (throwable == null) {
                sendResult(
                    Result.success(
                        MarketEventSessionSignal.TransportActivity(
                            observedAt = clock.instant(),
                            kind = TransportActivityKind.PING_PONG_COMPLETED,
                        ),
                    ),
                )
            } else {
                sendTerminalFailure(throwable)
            }
        }
    }

    override fun onClose(
        webSocket: WebSocket,
        statusCode: Int,
        reason: String,
    ): CompletionStage<*>? {
        sendTerminalFailure(IllegalStateException("GMO WebSocket closed: $statusCode"))

        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        sendTerminalFailure(error)
    }

    private fun sendResult(result: Result<MarketEventSessionSignal>) {
        synchronized(dispatchLock) {
            if (terminalFailure.get() != null) return

            val sendResult = messages.trySend(result)
            if (sendResult.isSuccess || sendResult.isClosed) return

            sendTerminalFailureLocked(MarketDataBackpressureException("GMO WebSocket market event buffer overflowed."))
        }
    }

    private fun sendTerminalFailure(throwable: Throwable) {
        synchronized(dispatchLock) {
            sendTerminalFailureLocked(throwable)
        }
    }

    private fun sendTerminalFailureLocked(throwable: Throwable) {
        if (!terminalFailure.compareAndSet(null, throwable)) return

        afterTerminalClaim()
        messages.trySend(Result.failure(throwable))
        messages.close(throwable)
    }
}

private fun logReceiptCommit(commit: PaperMarketEventReceiptCommit) {
    RECEIPT_LOGGER.fine(paperMarketReceiptCommitLogMessage(commit))
}

internal fun paperMarketReceiptCommitLogMessage(commit: PaperMarketEventReceiptCommit): String {
    return "paper market-event receipt committed " +
        "duplicate=${commit.duplicate} transactionNanos=${commit.transactionDurationNanos} " +
        "advisoryWaitNanos=${commit.advisoryWaitNanos}"
}

private fun subscribeMessage(symbol: TradingSymbol): String {
    return """{"command":"subscribe","channel":"trades","symbol":"${symbol.apiSymbol}"}"""
}

private fun JsonObject.requiredString(key: String): String {
    return optionalString(key)
        ?: throw InvalidMarketDataMessageException("GMO WebSocket trade message is missing $key.")
}

private fun JsonObject.optionalString(key: String): String? {
    val value = this[key] ?: return null

    return try {
        value.jsonPrimitive.contentOrNull
    } catch (throwable: Throwable) {
        throw InvalidMarketDataMessageException("GMO WebSocket message field $key must be a primitive.", throwable)
    }
}
