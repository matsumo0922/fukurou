package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ops route の HTTP contract を検証するテスト。
 */
class OpsRouteTest {

    @Test
    fun opsRoutes_haltResumeAndReadRiskState() = testApplication {
        val clock = fixedClock()
        val eventLog = InMemoryCommandEventLog()
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val commandService = InMemoryRiskStateCommandService(
            riskStateRepository = riskStateRepository,
            commandEventLog = eventLog,
            clock = clock,
        )

        application {
            module(
                readinessProbe = { true },
                clock = clock,
                evaluationRiskStateRepository = riskStateRepository,
                opsRiskStateCommandService = commandService,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val softResponse = client.post("/ops/halt") {
            contentType(ContentType.Application.Json)
            setBody("""{"level":"SOFT","reason":"operator pause"}""")
        }
        val stateResponse = client.get("/ops/risk-state")
        val hardResponse = client.post("/ops/halt") {
            contentType(ContentType.Application.Json)
            setBody("""{"level":"HARD","reason":"max drawdown"}""")
        }
        val conflictResponse = client.post("/ops/halt") {
            contentType(ContentType.Application.Json)
            setBody("""{"level":"SOFT","reason":"downgrade"}""")
        }
        val resumeResponse = client.post("/ops/resume") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"operator confirmed recovery"}""")
        }
        val resumeResponseBody = resumeResponse.bodyAsText()
        val events = eventLog.events()
        val eventTypes = events.map { event -> event.eventType }

        assertEquals(HttpStatusCode.OK, softResponse.status)
        assertTrue(softResponse.bodyAsText().contains(""""state":"SOFT_HALT""""))
        assertEquals(HttpStatusCode.OK, stateResponse.status)
        assertTrue(stateResponse.bodyAsText().contains("operator pause"))
        assertEquals(HttpStatusCode.OK, hardResponse.status)
        assertTrue(hardResponse.bodyAsText().contains(""""state":"HARD_HALT""""))
        assertEquals(HttpStatusCode.Conflict, conflictResponse.status)
        assertEquals(HttpStatusCode.OK, resumeResponse.status)
        assertTrue(resumeResponseBody.contains(""""state":"RUNNING""""))
        assertTrue(resumeResponseBody.contains("operator confirmed recovery"))
        assertEquals(
            listOf(
                CommandEventType.SOFT_HALT_SET,
                CommandEventType.HARD_HALT_SET,
                CommandEventType.MANUAL_RESUME_REQUESTED,
            ),
            eventTypes,
        )
        assertTrue(events.last().payload.contains("HARD_HALT"))
    }

    @Test
    fun opsRoutes_returnBadRequestForMissingOrBlankReason() = testApplication {
        val clock = fixedClock()
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val commandService = InMemoryRiskStateCommandService(
            riskStateRepository = riskStateRepository,
            commandEventLog = InMemoryCommandEventLog(),
            clock = clock,
        )

        application {
            module(
                readinessProbe = { true },
                clock = clock,
                evaluationRiskStateRepository = riskStateRepository,
                opsRiskStateCommandService = commandService,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val missingReasonResponse = client.post("/ops/halt") {
            contentType(ContentType.Application.Json)
            setBody("""{"level":"SOFT"}""")
        }
        val blankHaltReasonResponse = client.post("/ops/halt") {
            contentType(ContentType.Application.Json)
            setBody("""{"level":"SOFT","reason":"   "}""")
        }
        val blankResumeReasonResponse = client.post("/ops/resume") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, missingReasonResponse.status)
        assertEquals(HttpStatusCode.BadRequest, blankHaltReasonResponse.status)
        assertEquals(HttpStatusCode.BadRequest, blankResumeReasonResponse.status)
    }

    @Test
    fun opsRoutes_returnServiceUnavailableWhenDbServicesAreNotConfigured() = testApplication {
        application {
            module(
                readinessProbe = { true },
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val getResponse = client.get("/ops/risk-state")
        val haltResponse = client.post("/ops/halt") {
            contentType(ContentType.Application.Json)
            setBody("""{"level":"SOFT","reason":"operator pause"}""")
        }
        val resumeResponse = client.post("/ops/resume") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"operator confirmed recovery"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, getResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, haltResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, resumeResponse.status)
    }
}

/**
 * ops route test 用の固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T01:00:00Z")
}

/**
 * ops route test 用の固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
