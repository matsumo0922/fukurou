package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchResult
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchService
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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
    fun opsRoutes_triggerReturnsAcceptedBodyAndBadRequestForBlankReason() = testApplication {
        val manualService = CapturingManualLlmLaunchService(
            ManualLlmLaunchResult.Accepted(
                invocationId = "manual-invocation-1",
                triggerKind = LlmDaemonTriggerKind.MANUAL,
            ),
        )

        application {
            module(
                readinessProbe = { true },
                opsManualLlmLaunchService = manualService,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val acceptedResponse = client.post("/ops/trigger") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"operator check"}""")
        }
        val blankReasonResponse = client.post("/ops/trigger") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"   "}""")
        }
        val acceptedBody = Json.parseToJsonElement(acceptedResponse.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.Accepted, acceptedResponse.status)
        assertEquals("manual-invocation-1", acceptedBody.getValue("invocationId").jsonPrimitive.content)
        assertEquals("MANUAL", acceptedBody.getValue("triggerKind").jsonPrimitive.content)
        assertEquals(listOf("operator check"), manualService.reasons)
        assertEquals(HttpStatusCode.BadRequest, blankReasonResponse.status)
    }

    @Test
    fun opsRoutes_triggerReturnsConflictWhenManualLaunchIsRejected() = testApplication {
        val manualService = CapturingManualLlmLaunchService(
            ManualLlmLaunchResult.Rejected("concurrent_invocation"),
        )

        application {
            module(
                readinessProbe = { true },
                opsManualLlmLaunchService = manualService,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.post("/ops/trigger") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"operator check"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("concurrent_invocation"))
    }

    @Test
    fun opsRoutes_decisionsReturnsLimitedDescendingRawFeed() = testApplication {
        val clock = MutableClock(fixedInstant())
        val decisionRepository = InMemoryDecisionRepository(clock)
        decisionRepository.submitDecision(noTradeSubmission("decision-old", "old reason")).getOrThrow()
        clock.advance(Duration.ofSeconds(1))
        decisionRepository.submitDecision(noTradeSubmission("decision-new", "new reason")).getOrThrow()
        clock.advance(Duration.ofSeconds(1))

        application {
            module(
                readinessProbe = { true },
                clock = clock,
                opsDecisionRepository = decisionRepository,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/decisions?limit=1")
        val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val decisions = responseBody.getValue("decisions").jsonArray
        val decision = decisions.single().jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("NO_TRADE", decision.getValue("action").jsonPrimitive.content)
        assertEquals("new reason", decision.getValue("reasonJa").jsonPrimitive.content)
        assertEquals("0.42", decision.getValue("estimatedWinProbability").jsonPrimitive.content)
        assertEquals("range", decision.getValue("setupTags").jsonArray.single().jsonPrimitive.content)
        assertEquals("ボラティリティが不足しています。", decision.getValue("noTradeConditionsJa").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun opsRoutes_positionsReturnsOpenPositionsAndOpenOrdersTogether() = testApplication {
        val ledgerRepository = InMemoryPaperLedgerRepository(
            positions = listOf(openPosition()),
            openOrders = listOf(openOrder()),
        )

        application {
            module(
                readinessProbe = { true },
                opsPaperLedgerRepository = ledgerRepository,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/positions")
        val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val positions = responseBody.getValue("positions").jsonArray
        val openOrders = responseBody.getValue("openOrders").jsonArray

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("position-1", positions.single().jsonObject.getValue("positionId").jsonPrimitive.content)
        assertEquals("order-1", openOrders.single().jsonObject.getValue("orderId").jsonPrimitive.content)
    }

    @Test
    fun opsRoutes_auditReturnsLimitedFilteredDescendingRawFeedAndRejectsInvalidEventType() = testApplication {
        val eventLog = InMemoryCommandEventLog()
        eventLog.append(auditEvent(CommandEventType.TOOL_CALL_COMPLETED, fixedInstant(), "tool-old")).getOrThrow()
        eventLog.append(
            auditEvent(
                eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
                occurredAt = fixedInstant().plusSeconds(1),
                toolName = "daemon-new",
            ),
        ).getOrThrow()
        eventLog.append(
            auditEvent(
                eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
                occurredAt = fixedInstant().plusSeconds(2),
                toolName = "daemon-latest",
            ),
        ).getOrThrow()

        application {
            module(
                readinessProbe = { true },
                opsCommandEventFeedReader = eventLog,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/audit?limit=1&eventType=DAEMON_TRIGGER_LAUNCHED")
        val invalidEventTypeResponse = client.get("/ops/audit?eventType=UNKNOWN")
        val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val event = responseBody.getValue("events").jsonArray.single().jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("DAEMON_TRIGGER_LAUNCHED", event.getValue("eventType").jsonPrimitive.content)
        assertEquals("daemon-latest", event.getValue("toolName").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, invalidEventTypeResponse.status)
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
        val triggerResponse = client.post("/ops/trigger") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"operator check"}""")
        }
        val decisionsResponse = client.get("/ops/decisions")
        val positionsResponse = client.get("/ops/positions")
        val auditResponse = client.get("/ops/audit")

        assertEquals(HttpStatusCode.ServiceUnavailable, getResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, haltResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, resumeResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, triggerResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, decisionsResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, positionsResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, auditResponse.status)
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

private fun noTradeSubmission(invocationId: String, reasonJa: String): DecisionSubmission {
    return DecisionSubmission(
        invocationId = invocationId,
        llmProvider = "claude",
        promptHash = "prompt-hash",
        systemPromptVersion = "v1",
        marketSnapshotId = "snapshot-$invocationId",
        action = DecisionAction.NO_TRADE,
        setupTags = listOf("range"),
        estimatedWinProbability = BigDecimal("0.42"),
        expectedRMultiple = null,
        roundTripCostR = null,
        toolEvidenceIds = emptyList(),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = reasonJa,
        missingDataJa = emptyList(),
        noTradeConditionsJa = listOf("ボラティリティが不足しています。"),
        entryIntent = null,
        tradePlan = null,
    )
}

private fun openPosition(): Position {
    return Position(
        positionId = "position-1",
        tradeGroupId = "trade-group-1",
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = fixedInstant().toString(),
        closedAt = null,
        sizeBtc = "0.01000000",
        averageEntryPriceJpy = "10000000",
        currentPriceJpy = "10100000",
        currentStopLossJpy = "9800000",
        currentTakeProfitJpy = null,
        unrealizedPnlJpy = "1000",
        unrealizedR = "0.50",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "10100000",
        lowestPriceSinceEntryJpy = "9950000",
    )
}

private fun openOrder(): Order {
    return Order(
        orderId = "order-1",
        intentId = "intent-1",
        positionId = "position-1",
        tradeGroupId = "trade-group-1",
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = OrderSide.SELL,
        orderType = OrderType.STOP,
        status = OrderStatus.OPEN,
        sizeBtc = "0.01000000",
        limitPriceJpy = null,
        triggerPriceJpy = "9800000",
        protectiveStopPriceJpy = "9800000",
        takeProfitPriceJpy = null,
        estimatedWinProbability = "0.42",
        reasonJa = "保護 STOP です。",
        clientRequestId = "client-request-1",
        createdAt = fixedInstant().toString(),
        updatedAt = fixedInstant().toString(),
    )
}

private fun auditEvent(
    eventType: CommandEventType,
    occurredAt: Instant,
    toolName: String,
): CommandEvent {
    return CommandEvent(
        decisionRunContext = DecisionRunContext.EMPTY,
        toolName = toolName,
        toolCallId = null,
        clientRequestId = null,
        eventType = eventType,
        payload = """{"toolName":"$toolName"}""",
        occurredAt = occurredAt,
    )
}

/**
 * manual trigger route test 用 fake service。
 *
 * @param result launch 呼び出しで返す結果
 */
private class CapturingManualLlmLaunchService(
    private val result: ManualLlmLaunchResult,
) : ManualLlmLaunchService {

    val reasons = mutableListOf<String>()

    override suspend fun launch(reason: String): Result<ManualLlmLaunchResult> {
        reasons += reason

        return Result.success(result)
    }
}

/**
 * ops route test 用 fake clock。
 *
 * @param currentInstant 現在時刻
 */
private class MutableClock(
    private var currentInstant: Instant,
) : Clock() {

    override fun instant(): Instant {
        return currentInstant
    }

    override fun getZone(): ZoneId {
        return ZoneOffset.UTC
    }

    override fun withZone(zone: ZoneId): Clock {
        return this
    }

    /**
     * 現在時刻を進める。
     */
    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}
