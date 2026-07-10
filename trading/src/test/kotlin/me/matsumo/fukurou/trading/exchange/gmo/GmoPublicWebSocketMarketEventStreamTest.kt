package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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

        assertFailsWith<IllegalArgumentException> {
            decoder.decode(payload, Instant.parse("2026-07-10T00:00:01Z"))
        }
    }

    @Test
    fun `reconnect backoff は stream の有効設定を返す`() {
        val backoff = Duration.ofMillis(1234)
        val stream = GmoPublicWebSocketMarketEventStream(
            config = GmoPublicWebSocketConfig(reconnectBackoff = backoff),
        )

        assertEquals(backoff, stream.reconnectBackoff)
    }

    @Test
    fun `listener は complete message 受信時点で event の時刻と連番を固定する`() = runBlocking {
        val messages = Channel<Result<me.matsumo.fukurou.trading.market.PaperMarketTradeEvent>>(Channel.UNLIMITED)
        val clock = MutableWebSocketTestClock(Instant.parse("2026-07-10T00:00:01Z"))
        val listener = GmoWebSocketListener(
            messages = messages,
            decoder = GmoTradeMessageDecoder(TradingSymbol.BTC, sessionId),
            clock = clock,
        )

        listener.onText(NoOpWebSocket, tradePayload(), true)
        clock.currentInstant = clock.currentInstant.plusSeconds(1)
        listener.onText(NoOpWebSocket, tradePayload(), true)

        val first = messages.receive().getOrThrow()
        val second = messages.receive().getOrThrow()

        assertEquals(Instant.parse("2026-07-10T00:00:01Z"), first.receivedAt)
        assertEquals(Instant.parse("2026-07-10T00:00:02Z"), second.receivedAt)
        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
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
