package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.market.MarketDataGapReason
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * health route の挙動を検証するテスト。
 */
class HealthRouteTest {

    @Test
    fun health_returns_ok() = testApplication {
        application {
            module(readinessProbe = { true })
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
        assertTrue(response.bodyAsText().contains("\"service\":\"fukurou\""))
    }

    @Test
    fun live_returns_ok() = testApplication {
        application {
            module(readinessProbe = { true })
        }

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun ready_returns_ok_when_probe_ready() = testApplication {
        application {
            module(readinessProbe = { true })
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ready\""))
    }

    @Test
    fun ready_includes_reconciler_last_reconciled_at() = testApplication {
        val reconcilerStatus = MutableReconcilerStatus()
        reconcilerStatus.markReconciled(
            reconciledAt = Instant.parse("2026-07-02T00:00:00Z"),
            startupFullReconcileCompleted = true,
            lastMarketDataAt = null,
        )

        application {
            module(
                readinessProbe = { true },
                reconcilerStatus = reconcilerStatus,
            )
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"lastReconciledAt\":\"2026-07-02T00:00:00Z\""))
    }

    @Test
    fun ready_returns_unavailable_when_probe_not_ready() = testApplication {
        application {
            module(readinessProbe = { false })
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"not_ready\""))
    }

    @Test
    fun ready_exposes_market_data_connection_gap_and_recovery() = testApplication {
        val reconcilerStatus = MutableReconcilerStatus()
        reconcilerStatus.updateMarketData(
            ReconcilerStatus(
                marketDataState = MarketDataConnectionState.DISCONNECTED,
                gapStartedAt = Instant.parse("2026-07-10T00:00:00Z"),
                recoveredAt = Instant.parse("2026-07-10T00:00:05Z"),
                gapReason = MarketDataGapReason.SEQUENCE_GAP,
            ),
        )
        application { module(readinessProbe = { false }, reconcilerStatus = reconcilerStatus) }

        val body = client.get("/health/ready").bodyAsText()

        assertTrue(body.contains("\"marketDataState\":\"DISCONNECTED\""))
        assertTrue(body.contains("\"gapReason\":\"SEQUENCE_GAP\""))
        assertTrue(body.contains("\"recoveredAt\":\"2026-07-10T00:00:05Z\""))
    }
}
