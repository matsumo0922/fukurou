@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.decodeFromString
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuote
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** current context WebSocket の snapshot authority を検証する。 */
class CurrentContextWebSocketRouteTest {

    @Test
    fun websocket_usesConnectionScopedSessionAndRestartsSequence() = testApplication {
        application { module(clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC)) }
        val client = createClient { install(WebSockets) }
        suspend fun snapshot(): CurrentContextEnvelopeResponse {
            var result: CurrentContextEnvelopeResponse? = null
            client.webSocket("/ops/current-context/ws", request = { headers.append(HttpHeaders.Origin, "http://localhost") }) {
                result = ApiJson.decodeFromString((incoming.receive() as Frame.Text).readText())
            }
            return requireNotNull(result)
        }
        val first = snapshot()
        val second = snapshot()
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(1, first.sequence)
        assertEquals(1, second.sequence)
    }

    @Test
    fun websocket_sendsReadOnlySnapshotWithQuoteFreshness() = testApplication {
        val observedAt = Instant.parse("2026-07-12T00:00:00Z")
        val store = LatestMarketQuoteStore().apply {
            update(LatestMarketQuote(BigDecimal("100"), BigDecimal("102"), observedAt))
        }
        application {
            module(
                latestMarketQuoteStore = store,
                clock = Clock.fixed(observedAt.plusSeconds(2), ZoneOffset.UTC),
            )
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ops/current-context/ws", request = { headers.append(HttpHeaders.Origin, "http://localhost") }) {
            val frame = incoming.receive() as Frame.Text
            val envelope = ApiJson.decodeFromString<CurrentContextEnvelopeResponse>(frame.readText())

            assertEquals("SNAPSHOT", envelope.type)
            assertEquals("FRESH", envelope.sources.first { source -> source.source == "MARKET_QUOTE" }.freshness)
            assertEquals("100", envelope.sources.first().value?.get("bidPriceJpy"))
            assertEquals(15_000, envelope.sources.first().staleAfterMillis)
            assertEquals(observedAt.plusSeconds(2).toString(), envelope.sources.first().receivedAt)
        }
    }

    @Test
    fun websocket_marksQuoteStaleFromObservedTimestamp() = testApplication {
        val observedAt = Instant.parse("2026-07-12T00:00:00Z")
        val store = LatestMarketQuoteStore().apply {
            update(LatestMarketQuote(BigDecimal("100"), BigDecimal("102"), observedAt))
        }
        application {
            module(
                latestMarketQuoteStore = store,
                clock = Clock.fixed(observedAt.plusSeconds(16), ZoneOffset.UTC),
            )
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ops/current-context/ws", request = { headers.append(HttpHeaders.Origin, "http://localhost") }) {
            val envelope = ApiJson.decodeFromString<CurrentContextEnvelopeResponse>(
                (incoming.receive() as Frame.Text).readText(),
            )

            assertEquals("STALE", envelope.sources.first { source -> source.source == "MARKET_QUOTE" }.freshness)
            assertEquals("UNAVAILABLE", envelope.sources.first { source -> source.source == "PAPER_ACCOUNT" }.freshness)
        }
    }
}
