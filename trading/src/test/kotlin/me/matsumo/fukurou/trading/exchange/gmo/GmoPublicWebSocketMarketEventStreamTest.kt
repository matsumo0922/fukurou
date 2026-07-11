package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.InvalidMarketDataMessageException
import me.matsumo.fukurou.trading.market.MarketDataBackpressureException
import me.matsumo.fukurou.trading.market.MarketDataSubscriptionException
import me.matsumo.fukurou.trading.market.MarketEventSessionSignal
import me.matsumo.fukurou.trading.market.TransportActivityKind
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GmoPublicWebSocketMarketEventStreamTest {
    private val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000163")
    private val decoder = GmoTradeMessageDecoder(TradingSymbol.BTC, sessionId)

    @Test
    fun `同値 payload も別 market event として連番を付ける`() {
        val payload = tradePayload()

        val first = requireNotNull(decoder.decode(payload, Instant.parse("2026-07-10T00:00:01Z")))
        val second = requireNotNull(decoder.decode(payload, Instant.parse("2026-07-10T00:00:02Z")))

        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
        assertEquals(OrderSide.SELL, first.side)
        assertEquals(sessionId, second.connectionSessionId)
    }

    @Test
    fun `subscribe acknowledgement は event sequence を消費しない`() {
        assertNull(decoder.decode("""{"status":0}""", Instant.EPOCH))

        val event = requireNotNull(decoder.decode(tradePayload(), Instant.parse("2026-07-10T00:00:01Z")))

        assertEquals(1, event.sequence)
    }

    @Test
    fun `不正 channel は fail closed にする`() {
        val payload = tradePayload().replace("trades", "ticker")

        assertFailsWith<InvalidMarketDataMessageException> {
            decoder.decode(payload, Instant.parse("2026-07-10T00:00:01Z"))
        }
    }

    @Test
    fun `不正なtrade値はinvalid messageとして分類できる`() {
        val invalidSide = tradePayload().replace("SELL", "UNKNOWN")
        val invalidPrice = tradePayload().replace("10000000", "invalid")
        val invalidTimestamp = tradePayload().replace("2026-07-10T00:00:00.000Z", "invalid")

        listOf(invalidSide, invalidPrice, invalidTimestamp).forEach { payload ->
            assertFailsWith<InvalidMarketDataMessageException> {
                decoder.decode(payload, Instant.parse("2026-07-10T00:00:01Z"))
            }
        }
    }

    @Test
    fun `subscription error応答はprotocol failureにする`() {
        val exception = assertFailsWith<MarketDataSubscriptionException> {
            decoder.decode("""{"error":"ERR-5106 Invalid request parameter. channel"}""", Instant.EPOCH)
        }

        assertTrue(exception.message.orEmpty().contains("ERR-5106"))
    }

    @Test
    fun `subscribe送信失敗時は確立済みsocketをabortする`() = runBlocking {
        val socket = FailingSubscribeWebSocket()
        val stream = GmoPublicWebSocketMarketEventStream(httpClient = FakeWebSocketHttpClient(socket))

        val result = stream.connect()

        assertTrue(result.isFailure)
        assertTrue(socket.aborted)
    }

    @Test
    fun `reconnect backoff は stream の有効設定を返す`() {
        val backoff = Duration.ofMillis(1234)
        val staleTimeout = Duration.ofMillis(2345)
        val stream = GmoPublicWebSocketMarketEventStream(
            config = GmoPublicWebSocketConfig(
                transportLivenessTimeout = staleTimeout,
                reconnectBackoff = backoff,
            ),
        )

        assertEquals(backoff, stream.reconnectBackoff)
        assertEquals(staleTimeout, stream.transportLivenessTimeout)
    }

    @Test
    fun `reconnect backoff は1秒未満を拒否する`() {
        assertFailsWith<IllegalArgumentException> {
            GmoPublicWebSocketConfig(reconnectBackoff = Duration.ofMillis(999))
        }
    }

    @Test
    fun `listener は complete message 受信時点で event の時刻と連番を固定する`() = runBlocking {
        val messages = Channel<Result<MarketEventSessionSignal>>(Channel.UNLIMITED)
        val clock = MutableWebSocketTestClock(Instant.parse("2026-07-10T00:00:01Z"))
        val listener = GmoWebSocketListener(
            messages = messages,
            decoder = GmoTradeMessageDecoder(TradingSymbol.BTC, sessionId),
            clock = clock,
        )

        listener.onText(NoOpWebSocket, tradePayload(), true)
        clock.currentInstant = clock.currentInstant.plusSeconds(1)
        listener.onText(NoOpWebSocket, tradePayload(), true)

        val first = (messages.receive().getOrThrow() as MarketEventSessionSignal.Trade).event
        val second = (messages.receive().getOrThrow() as MarketEventSessionSignal.Trade).event

        assertEquals(Instant.parse("2026-07-10T00:00:01Z"), first.receivedAt)
        assertEquals(Instant.parse("2026-07-10T00:00:02Z"), second.receivedAt)
        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
    }

    @Test
    fun `listener buffer overflowはqueued tradeの後にterminal failureにする`() = runBlocking {
        val messages = Channel<Result<MarketEventSessionSignal>>(capacity = 1)
        val session = GmoMarketEventSession(sessionId, Instant.EPOCH, NoOpWebSocket, messages)
        val listener = GmoWebSocketListener(
            messages = messages,
            decoder = GmoTradeMessageDecoder(TradingSymbol.BTC, sessionId),
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )

        listener.onText(NoOpWebSocket, tradePayload(), true)
        listener.onText(NoOpWebSocket, tradePayload(), true)

        assertTrue(session.receive().getOrThrow() is MarketEventSessionSignal.Trade)
        assertTrue(session.receive().exceptionOrNull() is MarketDataBackpressureException)
    }

    @Test
    fun `subscription acknowledgement と Pong 完了は transport activity として通知する`() = runBlocking {
        val messages = Channel<Result<MarketEventSessionSignal>>(Channel.UNLIMITED)
        val observedAt = Instant.parse("2026-07-10T00:00:01Z")
        val listener = GmoWebSocketListener(
            messages = messages,
            decoder = GmoTradeMessageDecoder(TradingSymbol.BTC, sessionId),
            clock = Clock.fixed(observedAt, ZoneOffset.UTC),
        )

        listener.onText(NoOpWebSocket, """{"status":0}""", true)
        listener.onPing(NoOpWebSocket, ByteBuffer.wrap(byteArrayOf(1)))

        val acknowledgement = messages.receive().getOrThrow() as MarketEventSessionSignal.TransportActivity
        val pong = messages.receive().getOrThrow() as MarketEventSessionSignal.TransportActivity

        assertEquals(TransportActivityKind.SUBSCRIPTION_ACKNOWLEDGED, acknowledgement.kind)
        assertEquals(TransportActivityKind.PING_PONG_COMPLETED, pong.kind)
        assertEquals(observedAt, pong.observedAt)
    }

    @Test
    fun `closeはqueued tradeとPongの後にterminal failureとして届く`() = runBlocking {
        val messages = Channel<Result<MarketEventSessionSignal>>(Channel.UNLIMITED)
        val session = GmoMarketEventSession(sessionId, Instant.EPOCH, NoOpWebSocket, messages)
        val listener = GmoWebSocketListener(
            messages = messages,
            decoder = GmoTradeMessageDecoder(TradingSymbol.BTC, sessionId),
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )

        listener.onText(NoOpWebSocket, tradePayload(), true)
        listener.onPing(NoOpWebSocket, ByteBuffer.wrap(byteArrayOf(1)))
        listener.onClose(NoOpWebSocket, WebSocket.NORMAL_CLOSURE, "closed")

        assertTrue(session.receive().getOrThrow() is MarketEventSessionSignal.Trade)
        assertEquals(
            TransportActivityKind.PING_PONG_COMPLETED,
            (session.receive().getOrThrow() as MarketEventSessionSignal.TransportActivity).kind,
        )
        assertTrue(session.receive().exceptionOrNull()?.message.orEmpty().contains("closed"))
    }

    @Test
    fun `errorはqueued tradeとPongの後にterminal failureとして届く`() = runBlocking {
        val messages = Channel<Result<MarketEventSessionSignal>>(Channel.UNLIMITED)
        val session = GmoMarketEventSession(sessionId, Instant.EPOCH, NoOpWebSocket, messages)
        val listener = GmoWebSocketListener(
            messages = messages,
            decoder = GmoTradeMessageDecoder(TradingSymbol.BTC, sessionId),
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )
        val error = IllegalStateException("network error")

        listener.onText(NoOpWebSocket, tradePayload(), true)
        listener.onPing(NoOpWebSocket, ByteBuffer.wrap(byteArrayOf(1)))
        listener.onError(NoOpWebSocket, error)

        assertTrue(session.receive().getOrThrow() is MarketEventSessionSignal.Trade)
        assertEquals(
            TransportActivityKind.PING_PONG_COMPLETED,
            (session.receive().getOrThrow() as MarketEventSessionSignal.TransportActivity).kind,
        )
        assertEquals(error, session.receive().exceptionOrNull())
    }

    @Test
    fun `terminal確定後に未完了Pongが完了してもactivityをenqueueしない`() = runBlocking {
        val messages = Channel<Result<MarketEventSessionSignal>>(Channel.UNLIMITED)
        val session = GmoMarketEventSession(sessionId, Instant.EPOCH, NoOpWebSocket, messages)
        val terminalClaimed = CountDownLatch(1)
        val allowTerminalDispatch = CountDownLatch(1)
        val pongSocket = DeferredPongWebSocket()
        val listener = GmoWebSocketListener(
            messages = messages,
            decoder = GmoTradeMessageDecoder(TradingSymbol.BTC, sessionId),
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            afterTerminalClaim = {
                terminalClaimed.countDown()
                check(allowTerminalDispatch.await(1, TimeUnit.SECONDS))
            },
        )

        listener.onPing(pongSocket, ByteBuffer.wrap(byteArrayOf(1)))
        val closeThread = Thread {
            listener.onClose(pongSocket, WebSocket.NORMAL_CLOSURE, "closed")
        }
        closeThread.start()
        assertTrue(terminalClaimed.await(1, TimeUnit.SECONDS))

        val pongThread = Thread { pongSocket.completePong() }
        pongThread.start()
        allowTerminalDispatch.countDown()
        closeThread.join()
        pongThread.join()

        assertTrue(session.receive().exceptionOrNull()?.message.orEmpty().contains("closed"))
    }

    private fun tradePayload(): String {
        return """
            {
              "channel":"trades",
              "symbol":"BTC",
              "side":"SELL",
              "price":"10000000",
              "size":"0.001",
              "timestamp":"2026-07-10T00:00:00.000Z"
            }
        """.trimIndent()
    }
}

private class MutableWebSocketTestClock(
    var currentInstant: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = currentInstant
}

private object NoOpWebSocket : WebSocket {
    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> = completed()

    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = completed()

    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = completed()

    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = completed()

    override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> = completed()

    override fun request(n: Long) = Unit

    override fun getSubprotocol(): String = ""

    override fun isOutputClosed(): Boolean = false

    override fun isInputClosed(): Boolean = false

    override fun abort() = Unit

    private fun completed(): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
}

private class FailingSubscribeWebSocket : WebSocket by NoOpWebSocket {
    var aborted = false
        private set

    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
        return CompletableFuture.failedFuture(IllegalStateException("subscribe failed"))
    }

    override fun abort() {
        aborted = true
    }
}

private class DeferredPongWebSocket : WebSocket by NoOpWebSocket {
    private val pongFuture = CompletableFuture<WebSocket>()

    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = pongFuture

    fun completePong() {
        pongFuture.complete(this)
    }
}

private class FakeWebSocketHttpClient(
    private val socket: WebSocket,
) : HttpClient() {
    override fun newWebSocketBuilder(): WebSocket.Builder {
        return object : WebSocket.Builder {
            override fun header(name: String, value: String): WebSocket.Builder = this

            override fun connectTimeout(timeout: Duration): WebSocket.Builder = this

            override fun subprotocols(mostPreferred: String, vararg lesserPreferred: String): WebSocket.Builder = this

            override fun buildAsync(uri: URI, listener: WebSocket.Listener): CompletableFuture<WebSocket> {
                listener.onOpen(socket)

                return CompletableFuture.completedFuture(socket)
            }
        }
    }

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()

    override fun <T> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        error("send is not used in tests.")
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        error("sendAsync is not used in tests.")
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        error("sendAsync is not used in tests.")
    }
}
