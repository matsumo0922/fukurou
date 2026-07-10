package me.matsumo.fukurou.trading.exchange.gmo

import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.time.Instant
import java.util.UUID
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
