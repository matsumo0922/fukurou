package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.market.DurableIngressGapSource
import me.matsumo.fukurou.trading.market.DurableMarketEventIngress
import me.matsumo.fukurou.trading.market.IngressOperationDeadline
import me.matsumo.fukurou.trading.market.MarketStreamIdentity
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GmoDurablePublicWebSocketMarketEventStreamTest {
    @Test
    fun openStaysPausedUntilStartingSubscribeAndConnectedCommits() = runBlocking {
        val ingress = RecordingDurableIngress()
        val socket = RecordingWebSocket()
        val stream = GmoDurablePublicWebSocketMarketEventStream(
            ingress = ingress,
            connector = DurableWebSocketConnector { _, listener ->
                listener.onOpen(socket)
                CompletableFuture.completedFuture(socket)
            },
        )

        stream.connect().getOrThrow()
        await { socket.requestCount == 1L }

        assertEquals(listOf("begin", "activate"), ingress.calls)
        assertEquals(1, socket.sentTexts.size)
        assertFalse(socket.aborted)
    }

    @Test
    fun databaseFailurePublishesNoDemandAndAbortsSocket() = runBlocking {
        val ingress = RecordingDurableIngress(beginResult = Result.failure(IllegalStateException("db unavailable")))
        val socket = RecordingWebSocket()
        val stream = GmoDurablePublicWebSocketMarketEventStream(
            ingress = ingress,
            connector = DurableWebSocketConnector { _, listener ->
                listener.onOpen(socket)
                CompletableFuture.completedFuture(socket)
            },
        )

        stream.connect().getOrThrow()
        await { socket.aborted && "disconnect:DATABASE_FAILURE" in ingress.calls }

        assertEquals(0, socket.requestCount)
        assertTrue("disconnect:DATABASE_FAILURE" in ingress.calls)
    }

    @Test
    fun errorBeforeStartingClaimsTerminalWithoutDatabaseBegin() {
        val ingress = RecordingDurableIngress()
        val socket = RecordingWebSocket()
        val listener = GmoDurableWebSocketListener(
            sessionId = UUID.randomUUID(),
            identity = MarketStreamIdentity("GMO_COIN", "BTC_JPY", "TRADES"),
            ingress = ingress,
            decoder = GmoTradeMessageDecoder(me.matsumo.fukurou.trading.domain.TradingSymbol.BTC, UUID.randomUUID()),
            events = kotlinx.coroutines.channels.Channel(1),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            clock = java.time.Clock.systemUTC(),
        )

        listener.onError(socket, IllegalStateException("early error"))
        listener.start(socket, "subscription")

        assertTrue(socket.aborted)
        assertEquals(emptyList(), ingress.calls)
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(10)
        }
    }
}

private class RecordingDurableIngress(
    private val beginResult: Result<Unit> = Result.success(Unit),
) : DurableMarketEventIngress {
    val calls = mutableListOf<String>()

    override suspend fun begin(
        sessionId: UUID,
        identity: MarketStreamIdentity,
        deadline: IngressOperationDeadline,
    ): Result<Unit> {
        calls += "begin"
        return beginResult
    }

    override suspend fun activate(sessionId: UUID, deadline: IngressOperationDeadline): Result<Boolean> {
        calls += "activate"
        return Result.success(true)
    }

    override suspend fun registerReceived(
        sessionId: UUID,
        sequence: Long,
        deadline: IngressOperationDeadline,
    ): Result<Boolean> {
        calls += "register:$sequence"
        return Result.success(true)
    }

    override suspend fun disconnect(
        sessionId: UUID,
        source: DurableIngressGapSource,
        deadline: IngressOperationDeadline,
    ): Result<Unit> {
        calls += "disconnect:$source"
        return Result.success(Unit)
    }
}

private class RecordingWebSocket : WebSocket {
    val sentTexts = mutableListOf<String>()
    var requestCount = 0L
    var aborted = false

    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
        sentTexts += data.toString()
        return CompletableFuture.completedFuture(this)
    }

    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = completed()

    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = completed()

    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = completed()

    override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> = completed()

    override fun request(n: Long) {
        requestCount += n
    }

    override fun getSubprotocol(): String = ""

    override fun isOutputClosed(): Boolean = aborted

    override fun isInputClosed(): Boolean = aborted

    override fun abort() {
        aborted = true
    }

    private fun completed(): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
}
