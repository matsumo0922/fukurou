package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
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
}
