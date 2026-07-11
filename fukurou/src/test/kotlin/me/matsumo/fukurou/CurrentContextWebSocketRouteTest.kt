@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.delay
import me.matsumo.fukurou.trading.config.TradingBotConfig
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
    fun websocket_acceptsConfiguredExternalHttpsOriginOverInternalHttpRoute() = testApplication {
        application {
            module(
                clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
                evaluationPublicOrigin = "https://fukurou.example.com",
            )
        }
        val client = createClient { install(WebSockets) }
        client.webSocket(
            "/ops/current-context/ws",
            request = { headers.append(HttpHeaders.Origin, "https://fukurou.example.com") },
        ) {
            val envelope = ApiJson.decodeFromString<CurrentContextEnvelopeResponse>((incoming.receive() as Frame.Text).readText())
            assertEquals("SNAPSHOT", envelope.type)
        }
    }

    @Test
    fun websocket_rejectsMissingForeignAndCrossSchemeOrigins() = testApplication {
        application {
            module(
                clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
                evaluationPublicOrigin = "http://localhost",
            )
        }
        val client = createClient { install(WebSockets) }
        listOf(null, "https://localhost", "http://foreign.example").forEach { origin ->
            client.webSocket("/ops/current-context/ws", request = { origin?.let { headers.append(HttpHeaders.Origin, it) } }) {
                assertEquals(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
            }
        }
        assertEquals(true, originAllowed("https://example.com", "https://example.com"))
        assertEquals(false, originAllowed("http://example.com", "https://example.com"))
        assertEquals(false, originAllowed("https://example.com", null))
    }

    @Test
    fun websocket_closesSlowClientWithTryAgainLater() = testApplication {
        application {
            install(ServerWebSockets)
            routing {
                currentContextWebSocketRoutes(
                    EvaluationRouteDependencies(
                        repository = null,
                        riskStateRepository = null,
                        marketDataSource = null,
                        tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
                        clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
                        currentContextSendTimeoutMillis = 1,
                        currentContextSendOverride = { delay(100) },
                        currentContextPublicOrigin = "http://localhost",
                    ),
                )
            }
        }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ops/current-context/ws", request = { headers.append(HttpHeaders.Origin, "http://localhost") }) {
            assertEquals(io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER.code, closeReason.await()?.code)
        }
    }

    @Test
    fun websocket_usesConnectionScopedSessionAndRestartsSequence() = testApplication {
        application {
            module(
                clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
                evaluationPublicOrigin = "http://localhost",
            )
        }
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
                evaluationPublicOrigin = "http://localhost",
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
                evaluationPublicOrigin = "http://localhost",
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
