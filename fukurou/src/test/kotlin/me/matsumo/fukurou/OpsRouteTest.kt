package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ops route の HTTP contract を検証するテスト。
 */
@Suppress("LargeClass")
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
    fun opsRoutes_llmAuthReturnsProviderStatusesWithoutSecrets() = testApplication {
        val service = CapturingLlmAuthService(
            snapshot = LlmAuthSnapshot(
                providers = listOf(
                    LlmAuthProviderStatus(
                        provider = LlmAuthProvider.CLAUDE,
                        status = LlmAuthStatus.LOGGED_IN,
                        detail = "credential marker present",
                        homePath = "/tmp/fukurou-cli-home/.claude",
                        checkedAt = fixedInstant(),
                    ),
                    LlmAuthProviderStatus(
                        provider = LlmAuthProvider.CODEX,
                        status = LlmAuthStatus.LOGGED_OUT,
                        detail = "credential marker not found",
                        homePath = "/tmp/fukurou-cli-home/.codex",
                        checkedAt = fixedInstant(),
                    ),
                ),
                checkedAt = fixedInstant(),
            ),
        )

        application {
            module(
                readinessProbe = { true },
                opsLlmAuthService = service,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/llm-auth")
        val responseBody = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(responseBody.contains(""""provider":"claude""""))
        assertTrue(responseBody.contains(""""status":"logged_in""""))
        assertTrue(responseBody.contains(""""provider":"codex""""))
        assertTrue(responseBody.contains(""""status":"logged_out""""))
        assertNoSecretLikeText(responseBody)
    }

    @Test
    fun opsRoutes_llmAuthLoginStartsAndPollsSessionWithoutSecrets() = testApplication {
        val session = LlmAuthLoginSessionSnapshot(
            provider = LlmAuthProvider.CODEX,
            sessionId = "session-1",
            status = LlmAuthLoginStatus.RUNNING,
            authorizationUrl = "https://auth.example.com/device",
            userCode = "ABCD-EFGH",
            tokenSubmitAvailable = false,
            tokenSubmitted = false,
            detail = "authorization challenge emitted",
            startedAt = fixedInstant(),
            expiresAt = fixedInstant().plusSeconds(600),
            completedAt = null,
        )
        val service = CapturingLlmAuthService(
            startResult = LlmAuthLoginStartResult.Accepted(session),
            sessions = mapOf("session-1" to session),
        )

        application {
            module(
                readinessProbe = { true },
                opsLlmAuthService = service,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val startResponse = client.post("/ops/llm-auth/codex/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"  operator requested Codex re-auth  "}""")
        }
        val pollResponse = client.get("/ops/llm-auth/codex/login/session-1")
        val startBody = startResponse.bodyAsText()
        val pollBody = pollResponse.bodyAsText()

        assertEquals(HttpStatusCode.Accepted, startResponse.status)
        assertEquals(HttpStatusCode.OK, pollResponse.status)
        assertTrue(startBody.contains(""""provider":"codex""""))
        assertTrue(startBody.contains(""""authorizationUrl":"https://auth.example.com/device""""))
        assertTrue(startBody.contains(""""userCode":"ABCD-EFGH""""))
        assertEquals(listOf(LlmAuthProvider.CODEX to "operator requested Codex re-auth"), service.loginRequests)
        assertNoSecretLikeText(startBody)
        assertNoSecretLikeText(pollBody)
    }

    @Test
    fun opsRoutes_llmAuthLoginRejectsInvalidInputAndConcurrentSession() = testApplication {
        val service = CapturingLlmAuthService(
            startResult = LlmAuthLoginStartResult.Rejected("login already in progress"),
        )

        application {
            module(
                readinessProbe = { true },
                opsLlmAuthService = service,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val blankReasonResponse = client.post("/ops/llm-auth/claude/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"   "}""")
        }
        val invalidProviderResponse = client.post("/ops/llm-auth/unknown/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"operator requested auth"}""")
        }
        val conflictResponse = client.post("/ops/llm-auth/claude/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"operator requested auth"}""")
        }
        val missingSessionResponse = client.get("/ops/llm-auth/claude/login/missing-session")

        assertEquals(HttpStatusCode.BadRequest, blankReasonResponse.status)
        assertEquals(HttpStatusCode.BadRequest, invalidProviderResponse.status)
        assertEquals(HttpStatusCode.Conflict, conflictResponse.status)
        assertEquals(HttpStatusCode.NotFound, missingSessionResponse.status)
    }

    @Test
    fun opsRoutes_llmAuthClaudeTokenSubmitAcceptsOnceWithoutSecrets() = testApplication {
        val submittedSession = LlmAuthLoginSessionSnapshot(
            provider = LlmAuthProvider.CLAUDE,
            sessionId = "session-1",
            status = LlmAuthLoginStatus.RUNNING,
            authorizationUrl = "https://auth.example.com/oauth",
            userCode = null,
            tokenSubmitAvailable = false,
            tokenSubmitted = true,
            detail = "authorization token/code submitted; waiting for CLI completion",
            startedAt = fixedInstant(),
            expiresAt = fixedInstant().plusSeconds(600),
            completedAt = null,
        )
        val service = CapturingLlmAuthService(
            tokenSubmitResults = mutableListOf(
                LlmAuthLoginTokenSubmitResult.Accepted(submittedSession),
            ),
        )

        application {
            module(
                readinessProbe = { true },
                opsLlmAuthService = service,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.post("/ops/llm-auth/claude/login/session-1/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$DUMMY_AUTH_CODE"}""")
        }
        val responseBody = response.bodyAsText()

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(responseBody.contains(""""provider":"claude""""))
        assertTrue(responseBody.contains(""""tokenSubmitted":true"""))
        assertEquals(
            listOf(Triple(LlmAuthProvider.CLAUDE, "session-1", DUMMY_AUTH_CODE)),
            service.tokenSubmitRequests,
        )
        assertNoSecretLikeText(responseBody)
    }

    @Test
    fun opsRoutes_llmAuthTokenSubmitRejectsInvalidProviderAndBody() = testApplication {
        val service = CapturingLlmAuthService(
            tokenSubmitResults = mutableListOf(
                LlmAuthLoginTokenSubmitResult.Rejected(
                    rejection = LlmAuthLoginTokenSubmitRejection.UNSUPPORTED_PROVIDER,
                    reason = "provider does not accept token/code submit",
                ),
            ),
        )

        application {
            module(
                readinessProbe = { true },
                opsLlmAuthService = service,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val blankCodeResponse = client.post("/ops/llm-auth/claude/login/session-1/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"   "}""")
        }
        val ambiguousBodyResponse = client.post("/ops/llm-auth/claude/login/session-1/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$DUMMY_AUTH_CODE","code":"$DUMMY_AUTH_CODE"}""")
        }
        val invalidProviderResponse = client.post("/ops/llm-auth/unknown/login/session-1/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$DUMMY_AUTH_CODE"}""")
        }
        val codexResponse = client.post("/ops/llm-auth/codex/login/session-1/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$DUMMY_AUTH_CODE"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, blankCodeResponse.status)
        assertEquals(HttpStatusCode.BadRequest, ambiguousBodyResponse.status)
        assertEquals(HttpStatusCode.BadRequest, invalidProviderResponse.status)
        assertEquals(HttpStatusCode.BadRequest, codexResponse.status)
        assertNoSecretLikeText(codexResponse.bodyAsText())
    }

    @Test
    fun opsRoutes_llmAuthTokenSubmitRejectsMissingTerminalAndDuplicateSession() = testApplication {
        val service = CapturingLlmAuthService(
            tokenSubmitResults = mutableListOf(
                LlmAuthLoginTokenSubmitResult.Rejected(
                    rejection = LlmAuthLoginTokenSubmitRejection.SESSION_NOT_FOUND,
                    reason = "login session not found",
                ),
                LlmAuthLoginTokenSubmitResult.Rejected(
                    rejection = LlmAuthLoginTokenSubmitRejection.SESSION_NOT_RUNNING,
                    reason = "login session is not running",
                ),
                LlmAuthLoginTokenSubmitResult.Rejected(
                    rejection = LlmAuthLoginTokenSubmitRejection.ALREADY_SUBMITTED,
                    reason = "login token/code already submitted",
                ),
            ),
        )

        application {
            module(
                readinessProbe = { true },
                opsLlmAuthService = service,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val missingSessionResponse = client.post("/ops/llm-auth/claude/login/missing/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$DUMMY_AUTH_CODE"}""")
        }
        val terminalSessionResponse = client.post("/ops/llm-auth/claude/login/session-1/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$DUMMY_AUTH_CODE"}""")
        }
        val duplicateSubmitResponse = client.post("/ops/llm-auth/claude/login/session-1/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$DUMMY_AUTH_CODE"}""")
        }

        assertEquals(HttpStatusCode.NotFound, missingSessionResponse.status)
        assertEquals(HttpStatusCode.Conflict, terminalSessionResponse.status)
        assertEquals(HttpStatusCode.Conflict, duplicateSubmitResponse.status)
        assertNoSecretLikeText(missingSessionResponse.bodyAsText())
        assertNoSecretLikeText(terminalSessionResponse.bodyAsText())
        assertNoSecretLikeText(duplicateSubmitResponse.bodyAsText())
    }

    @Test
    fun opsRoutes_accountReturnsCurrentSnapshotWithUpdatedAt() = testApplication {
        val ledgerRepository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshot(),
            accountUpdatedAt = fixedInstant().plusSeconds(30),
        )

        application {
            module(
                readinessProbe = { true },
                opsPaperLedgerRepository = ledgerRepository,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/account")
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertNoSecretLikeText(responseText)
        assertEquals("PAPER", responseBody.getValue("mode").jsonPrimitive.content)
        assertEquals("94000", responseBody.getValue("cashJpy").jsonPrimitive.content)
        assertEquals("100000", responseBody.getValue("initialCashJpy").jsonPrimitive.content)
        assertEquals("0.01000000", responseBody.getValue("btcQuantity").jsonPrimitive.content)
        assertEquals("10100000", responseBody.getValue("btcMarkPriceJpy").jsonPrimitive.content)
        assertEquals("195000", responseBody.getValue("totalEquityJpy").jsonPrimitive.content)
        assertEquals("200000", responseBody.getValue("equityPeakJpy").jsonPrimitive.content)
        assertEquals("0.025", responseBody.getValue("drawdownRatio").jsonPrimitive.content)
        assertEquals("2026-07-02T01:00:30Z", responseBody.getValue("updatedAt").jsonPrimitive.content)
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
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject
        val decisions = responseBody.getValue("decisions").jsonArray
        val decision = decisions.single().jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertNoSecretLikeText(responseText)
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
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject
        val positions = responseBody.getValue("positions").jsonArray
        val openOrders = responseBody.getValue("openOrders").jsonArray

        assertEquals(HttpStatusCode.OK, response.status)
        assertNoSecretLikeText(responseText)
        assertEquals("position-1", positions.single().jsonObject.getValue("positionId").jsonPrimitive.content)
        assertEquals("order-1", openOrders.single().jsonObject.getValue("orderId").jsonPrimitive.content)
    }

    @Test
    fun opsRoutes_executionsReturnsLimitedDescendingRawFeedAndRejectsInvalidLimit() = testApplication {
        val ledgerRepository = InMemoryPaperLedgerRepository(
            executions = listOf(
                execution(
                    executionId = "execution-old",
                    executedAt = fixedInstant(),
                    realizedPnlJpy = "0",
                ),
                execution(
                    executionId = "execution-new",
                    executedAt = fixedInstant().plusSeconds(5),
                    realizedPnlJpy = "1200",
                ),
            ),
        )

        application {
            module(
                readinessProbe = { true },
                opsPaperLedgerRepository = ledgerRepository,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/executions?limit=1")
        val invalidLimitResponse = client.get("/ops/executions?limit=0")
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject
        val execution = responseBody.getValue("executions").jsonArray.single().jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertNoSecretLikeText(responseText)
        assertEquals("execution-new", execution.getValue("executionId").jsonPrimitive.content)
        assertEquals("order-execution-new", execution.getValue("orderId").jsonPrimitive.content)
        assertEquals("position-execution-new", execution.getValue("positionId").jsonPrimitive.content)
        assertEquals("PAPER", execution.getValue("mode").jsonPrimitive.content)
        assertEquals("BTC", execution.getValue("symbol").jsonPrimitive.content)
        assertEquals("BUY", execution.getValue("side").jsonPrimitive.content)
        assertEquals("10100000", execution.getValue("priceJpy").jsonPrimitive.content)
        assertEquals("0.01000000", execution.getValue("sizeBtc").jsonPrimitive.content)
        assertEquals("10", execution.getValue("feeJpy").jsonPrimitive.content)
        assertEquals("1200", execution.getValue("realizedPnlJpy").jsonPrimitive.content)
        assertEquals("TAKER", execution.getValue("liquidity").jsonPrimitive.content)
        assertEquals("2026-07-02T01:00:05Z", execution.getValue("executedAt").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, invalidLimitResponse.status)
    }

    @Test
    fun opsRoutes_activityReturnsFilteredCursorPagedTimelineWithoutAuditPayload() = testApplication {
        val clock = MutableClock(fixedInstant())
        val decisionRepository = InMemoryDecisionRepository(clock)
        decisionRepository.submitDecision(noTradeSubmission("decision-old", "old reason")).getOrThrow()
        clock.advance(Duration.ofSeconds(1))
        decisionRepository.submitDecision(noTradeSubmission("decision-new", "new reason")).getOrThrow()
        clock.advance(Duration.ofSeconds(9))

        val eventLog = InMemoryCommandEventLog()
        eventLog.append(
            auditEvent(
                eventType = CommandEventType.MANUAL_RESUME_REQUESTED,
                occurredAt = fixedInstant().plusSeconds(2),
                toolName = "operator",
                payload = """{"token":"anthropic-secret-token"}""",
            ),
        ).getOrThrow()
        eventLog.append(auditEvent(CommandEventType.HARD_HALT_SET, fixedInstant().plusSeconds(4), "risk")).getOrThrow()
        eventLog.append(
            auditEvent(
                eventType = CommandEventType.RECONCILER_PASS_COMPLETED,
                occurredAt = fixedInstant().plusSeconds(6),
                toolName = "reconciler",
            ),
        ).getOrThrow()
        val ledgerRepository = InMemoryPaperLedgerRepository(
            executions = listOf(
                execution(
                    executionId = "execution-1",
                    executedAt = fixedInstant().plusSeconds(3),
                    realizedPnlJpy = "1200",
                ),
            ),
        )

        application {
            module(
                readinessProbe = { true },
                clock = clock,
                opsDecisionRepository = decisionRepository,
                opsPaperLedgerRepository = ledgerRepository,
                opsCommandEventFeedReader = eventLog,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val latestResponse = client.get("/ops/activity?limit=3")
        val latestResponseText = latestResponse.bodyAsText()
        val latestBody = Json.parseToJsonElement(latestResponseText).jsonObject
        val latestEvents = latestBody.getValue("events").jsonArray.map { element -> element.jsonObject }
        val latestTitles = latestEvents.map { event -> event.getValue("title").jsonPrimitive.content }
        val nextBefore = latestBody.getValue("nextBefore").jsonPrimitive.content
        val olderResponse = client.get("/ops/activity") {
            parameter("limit", "10")
            parameter("before", nextBefore)
        }
        val olderBody = Json.parseToJsonElement(olderResponse.bodyAsText()).jsonObject
        val olderEvents = olderBody.getValue("events").jsonArray.map { element -> element.jsonObject }
        val olderDetails = olderEvents.map { event -> event.getValue("detail").jsonPrimitive.content }
        val auditFilterResponse = client.get("/ops/activity?source=audit&auditEventType=HARD_HALT_SET&limit=10")
        val auditFilterBody = Json.parseToJsonElement(auditFilterResponse.bodyAsText()).jsonObject
        val auditFilterEvents = auditFilterBody.getValue("events").jsonArray.map { element -> element.jsonObject }

        assertEquals(HttpStatusCode.OK, latestResponse.status)
        assertNoSecretLikeText(latestResponseText)
        assertFalse(latestResponseText.contains("token"))
        assertEquals(
            listOf(
                "HARD_HALT_SET",
                "BUY BTC execution",
                "MANUAL_RESUME_REQUESTED",
            ),
            latestTitles,
        )
        assertTrue(nextBefore.startsWith("2026-07-02T01:00:02Z|audit|audit:"))
        assertEquals(HttpStatusCode.OK, olderResponse.status)
        assertEquals(listOf("new reason", "old reason"), olderDetails)
        assertEquals(HttpStatusCode.OK, auditFilterResponse.status)
        assertEquals(1, auditFilterEvents.size)
        assertEquals("HARD_HALT_SET", auditFilterEvents.single().getValue("kind").jsonPrimitive.content)
    }

    @Test
    fun opsRoutes_activityCursorKeepsUnshownEventsWithSameTimestamp() = testApplication {
        val eventLog = InMemoryCommandEventLog()
        val occurredAt = fixedInstant().plusSeconds(7)
        val auditEvents = listOf(
            "00000000-0000-0000-0000-000000000001" to "first",
            "00000000-0000-0000-0000-000000000002" to "second",
            "00000000-0000-0000-0000-000000000003" to "third",
            "00000000-0000-0000-0000-000000000004" to "fourth",
            "00000000-0000-0000-0000-000000000005" to "fifth",
        )

        for ((rawEventId, toolName) in auditEvents) {
            eventLog.append(
                auditEvent(
                    id = UUID.fromString(rawEventId),
                    eventType = CommandEventType.HARD_HALT_SET,
                    occurredAt = occurredAt,
                    toolName = toolName,
                ),
            ).getOrThrow()
        }

        application {
            module(
                readinessProbe = { true },
                opsCommandEventFeedReader = eventLog,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val firstPageResponse = client.get("/ops/activity?source=audit&auditEventType=HARD_HALT_SET&limit=2")
        val firstPageBody = Json.parseToJsonElement(firstPageResponse.bodyAsText()).jsonObject
        val firstPageEvents = firstPageBody.getValue("events").jsonArray.map { element -> element.jsonObject }
        val nextBefore = firstPageBody.getValue("nextBefore").jsonPrimitive.content
        val secondPageResponse = client.get("/ops/activity") {
            parameter("source", "audit")
            parameter("auditEventType", "HARD_HALT_SET")
            parameter("limit", "2")
            parameter("before", nextBefore)
        }
        val secondPageBody = Json.parseToJsonElement(secondPageResponse.bodyAsText()).jsonObject
        val secondPageEvents = secondPageBody.getValue("events").jsonArray.map { element -> element.jsonObject }
        val secondNextBefore = secondPageBody.getValue("nextBefore").jsonPrimitive.content
        val thirdPageResponse = client.get("/ops/activity") {
            parameter("source", "audit")
            parameter("auditEventType", "HARD_HALT_SET")
            parameter("limit", "2")
            parameter("before", secondNextBefore)
        }
        val thirdPageBody = Json.parseToJsonElement(thirdPageResponse.bodyAsText()).jsonObject
        val thirdPageEvents = thirdPageBody.getValue("events").jsonArray.map { element -> element.jsonObject }

        assertEquals(HttpStatusCode.OK, firstPageResponse.status)
        assertEquals(listOf("first", "second"), firstPageEvents.map { event -> event.getValue("detail").jsonPrimitive.content })
        assertEquals("2026-07-02T01:00:07Z|audit|audit:00000000-0000-0000-0000-000000000002", nextBefore)
        assertEquals(HttpStatusCode.OK, secondPageResponse.status)
        assertEquals(listOf("third", "fourth"), secondPageEvents.map { event -> event.getValue("detail").jsonPrimitive.content })
        assertEquals("2026-07-02T01:00:07Z|audit|audit:00000000-0000-0000-0000-000000000004", secondNextBefore)
        assertEquals(HttpStatusCode.OK, thirdPageResponse.status)
        assertEquals(listOf("fifth"), thirdPageEvents.map { event -> event.getValue("detail").jsonPrimitive.content })
    }

    @Test
    fun opsRoutes_activityRejectsInvalidFilters() = testApplication {
        application {
            module(
                readinessProbe = { true },
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val invalidSourceResponse = client.get("/ops/activity?source=unknown")
        val invalidBeforeResponse = client.get("/ops/activity?before=not-an-instant")
        val invalidAuditEventTypeResponse = client.get("/ops/activity?source=audit&auditEventType=UNKNOWN")

        assertEquals(HttpStatusCode.BadRequest, invalidSourceResponse.status)
        assertEquals(HttpStatusCode.BadRequest, invalidBeforeResponse.status)
        assertEquals(HttpStatusCode.BadRequest, invalidAuditEventTypeResponse.status)
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
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject
        val event = responseBody.getValue("events").jsonArray.single().jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertNoSecretLikeText(responseText)
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
        val accountResponse = client.get("/ops/account")
        val decisionsResponse = client.get("/ops/decisions")
        val positionsResponse = client.get("/ops/positions")
        val executionsResponse = client.get("/ops/executions")
        val auditResponse = client.get("/ops/audit")
        val activityResponse = client.get("/ops/activity")

        assertEquals(HttpStatusCode.ServiceUnavailable, getResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, haltResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, resumeResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, triggerResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, accountResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, decisionsResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, positionsResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, executionsResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, auditResponse.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, activityResponse.status)
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

private fun accountSnapshot(): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = "94000",
        initialCashJpy = "100000",
        btcQuantity = "0.01000000",
        btcMarkPriceJpy = "10100000",
        totalEquityJpy = "195000",
        equityPeakJpy = "200000",
        drawdownRatio = "0.025",
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

private fun execution(
    executionId: String,
    executedAt: Instant,
    realizedPnlJpy: String,
): Execution {
    return Execution(
        executionId = executionId,
        orderId = "order-$executionId",
        positionId = "position-$executionId",
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        priceJpy = "10100000",
        sizeBtc = "0.01000000",
        feeJpy = "10",
        realizedPnlJpy = realizedPnlJpy,
        liquidity = ExecutionLiquidity.TAKER,
        executedAt = executedAt.toString(),
    )
}

private fun auditEvent(
    eventType: CommandEventType,
    occurredAt: Instant,
    toolName: String,
    payload: String = """{"toolName":"$toolName"}""",
    id: UUID = UUID.randomUUID(),
): CommandEvent {
    return CommandEvent(
        id = id,
        decisionRunContext = DecisionRunContext.EMPTY,
        toolName = toolName,
        toolCallId = null,
        clientRequestId = null,
        eventType = eventType,
        payload = payload,
        occurredAt = occurredAt,
    )
}

private fun assertNoSecretLikeText(responseText: String) {
    SECRET_FIXTURE_TEXTS.forEach { secretText ->
        assertFalse(responseText.contains(secretText), secretText)
    }
}

/**
 * read API response に混入してはいけない secret-like fixture。
 */
private val SECRET_FIXTURE_TEXTS = setOf(
    "postgres://secret-db-host/fukurou",
    "gmo-secret-api-key",
    "cloudflare-secret-token",
    "anthropic-secret-token",
    DUMMY_AUTH_CODE,
)

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
 * ops route test 用 CLI auth fake service。
 *
 * @param snapshot status API で返す snapshot
 * @param startResult login start API で返す結果
 * @param sessions poll API で返す session map
 * @param tokenSubmitResults token/code submit API で返す結果 queue
 */
private class CapturingLlmAuthService(
    private val snapshot: LlmAuthSnapshot = LlmAuthSnapshot(
        providers = emptyList(),
        checkedAt = fixedInstant(),
    ),
    private val startResult: LlmAuthLoginStartResult = LlmAuthLoginStartResult.Rejected("login not configured"),
    private val sessions: Map<String, LlmAuthLoginSessionSnapshot> = emptyMap(),
    private val tokenSubmitResults: MutableList<LlmAuthLoginTokenSubmitResult> = mutableListOf(
        LlmAuthLoginTokenSubmitResult.Rejected(
            rejection = LlmAuthLoginTokenSubmitRejection.STDIN_UNAVAILABLE,
            reason = "login process stdin is unavailable",
        ),
    ),
) : LlmAuthService {

    val loginRequests = mutableListOf<Pair<LlmAuthProvider, String>>()
    val tokenSubmitRequests = mutableListOf<Triple<LlmAuthProvider, String, String>>()

    override suspend fun snapshot(): Result<LlmAuthSnapshot> {
        return Result.success(snapshot)
    }

    override suspend fun startLogin(provider: LlmAuthProvider, reason: String): Result<LlmAuthLoginStartResult> {
        loginRequests += provider to reason

        return Result.success(startResult)
    }

    override suspend fun loginSession(
        provider: LlmAuthProvider,
        sessionId: String,
    ): Result<LlmAuthLoginSessionSnapshot?> {
        val session = sessions[sessionId]?.takeIf { candidate -> candidate.provider == provider }

        return Result.success(session)
    }

    override suspend fun submitLoginTokenCode(
        provider: LlmAuthProvider,
        sessionId: String,
        tokenCode: String,
    ): Result<LlmAuthLoginTokenSubmitResult> {
        tokenSubmitRequests += Triple(provider, sessionId, tokenCode)

        return Result.success(tokenSubmitResults.removeFirst())
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

private const val DUMMY_AUTH_CODE = "DUMMY-CODE"
