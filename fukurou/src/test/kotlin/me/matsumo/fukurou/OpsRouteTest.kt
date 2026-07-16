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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.broker.ExecutionActivityDecisionContext
import me.matsumo.fukurou.trading.broker.ExecutionActivityOrderContext
import me.matsumo.fukurou.trading.broker.ExecutionActivityPositionContext
import me.matsumo.fukurou.trading.broker.ExecutionActivityRecord
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.PaperLedgerRepository
import me.matsumo.fukurou.trading.config.PaperAccountEpochSwitchRejectedException
import me.matsumo.fukurou.trading.config.RuntimeConfigActivationResult
import me.matsumo.fukurou.trading.config.RuntimeConfigAdminService
import me.matsumo.fukurou.trading.config.RuntimeConfigCandidateValidator
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.RuntimeConfigDraftCreation
import me.matsumo.fukurou.trading.config.RuntimeConfigValidationRejectedException
import me.matsumo.fukurou.trading.config.RuntimeConfigVersionDetail
import me.matsumo.fukurou.trading.config.RuntimeConfigVersionSummary
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.config.calculateRuntimeConfigHash
import me.matsumo.fukurou.trading.daemon.LLM_LAUNCH_DISABLED
import me.matsumo.fukurou.trading.daemon.LlmDaemonLaunchSuppressionReason
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
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import me.matsumo.fukurou.trading.evaluation.LlmTokenUsage
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.persistence.ExposedLlmRunRepository
import me.matsumo.fukurou.trading.persistence.RuntimeConfigPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatus
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.runner.LlmInvocationAuditor
import me.matsumo.fukurou.trading.runner.SecretRedactor
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.math.BigDecimal
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.sql.Connection
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
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * ops route の HTTP contract を検証するテスト。
 */
@Suppress("LargeClass")
class OpsRouteTest {

    @Test
    fun sharedPersistenceBootstrap_recoversStaleRunThroughProductionFactory() = runBlocking {
        if (!isDockerAvailable()) {
            println("Skipping shared persistence bootstrap test because Docker is unavailable.")
            return@runBlocking
        }

        val container = FukurouPostgresContainer()
        container.start()

        try {
            val database = ExposedDatabase.connect(
                url = container.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = container.username,
                password = container.password,
            )
            val clock = fixedClock()
            TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
            val runRepository = ExposedLlmRunRepository(database)
            runRepository.insertRunning(
                LlmRunStart(
                    invocationId = "production-bootstrap-stale-run",
                    mode = TradingMode.PAPER,
                    symbol = TradingSymbol.BTC,
                    triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                    startedAt = fixedInstant().minus(Duration.ofHours(1)),
                ),
            ).getOrThrow()
            val recoveredCounts = mutableListOf<Int>()
            val bootstrap = sharedTradingPersistenceBootstrap(
                database = database,
                tradingConfig = TradingBotConfig(),
                clock = clock,
                onStaleLlmRunsRecovered = recoveredCounts::add,
            )

            bootstrap().getOrThrow()

            assertEquals(listOf(1), recoveredCounts)
            assertEquals(
                LlmRunTerminalCause.RESTART_INTERRUPTED,
                runRepository.findByInvocationId("production-bootstrap-stale-run").getOrThrow()?.terminalCause,
            )
        } finally {
            container.stop()
        }
    }

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
            ManualLlmLaunchResult.Rejected(LLM_LAUNCH_DISABLED),
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
        assertTrue(response.bodyAsText().contains(LLM_LAUNCH_DISABLED))
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
    fun opsRoutes_runtimeConfigReturnsReadOnlyCatalogWithoutSecretValues() = testApplication {
        val runtimeConfigEnvironment = mapOf(
            "FUKUROU_TRADING_SYMBOL" to "BTC",
            "FUKUROU_GMO_PUBLIC_BASE_URL" to "https://example.test/public",
            "FUKUROU_CLAUDE_COMMAND_TEMPLATE" to "docker run claude",
            "DB_PASSWORD" to "super-secret-password",
        )

        application {
            module(
                readinessProbe = { true },
                tradingConfig = TradingBotConfig.fromEnvironment(runtimeConfigEnvironment),
                runtimeConfigEnvironment = runtimeConfigEnvironment,
            )
        }

        val response = client.get("/ops/runtime-config")
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject
        val groups = responseBody.getValue("groups").jsonArray
        val deploymentGroup = groups
            .map { group -> group.jsonObject }
            .single { group -> group.getValue("id").jsonPrimitive.content == "deployment" }
        val secretsGroup = groups
            .map { group -> group.jsonObject }
            .single { group -> group.getValue("id").jsonPrimitive.content == "secrets" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(responseText.contains("super-secret-password"))
        assertConfigItem(
            group = deploymentGroup,
            key = "gmoPublic.baseUrl",
            sourceKind = "DEPLOYMENT",
            effectiveValue = "https://example.test/public",
        )
        assertConfigItem(
            group = deploymentGroup,
            key = "llm.claudeCommandTemplate",
            sourceKind = "DEPLOYMENT",
            effectiveValue = "docker run claude",
        )
        assertSecretItemConfigured(
            group = secretsGroup,
            key = "database.password",
        )
    }

    @Test
    fun opsRoutes_runtimeConfigReturnsCatalogWarningWhenVersionHistoryFails() = testApplication {
        val adminService = FakeRuntimeConfigAdminService(
            listVersionsFailure = IllegalStateException("version history unavailable"),
        )

        application {
            module(
                readinessProbe = { true },
                opsRuntimeConfigAdminService = adminService,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/runtime-config")
        val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val warnings = responseBody.getValue("warnings").jsonArray

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(responseBody.getValue("groups").jsonArray.isNotEmpty())
        assertTrue(responseBody.getValue("versions").jsonArray.isEmpty())
        assertEquals(
            "runtimeConfig.warning.versionHistoryUnavailable",
            warnings.single().jsonObject.getValue("code").jsonPrimitive.content,
        )
    }

    @Test
    fun opsRoutes_runtimeConfigDerivesExpiryWarningFromCachedCalendarAtResponseTime() = testApplication {
        val validThrough = Instant.parse("2026-07-13T01:00:00Z")
        val clock = MutableClock(validThrough)
        val calendarRaw =
            """[{"eventId":"fomc-boundary","eventName":"FOMC","eventAt":"2026-07-13T00:00:00Z","blackoutBeforeSeconds":3600,"blackoutAfterSeconds":3600}]"""
        val tradingConfig = TradingBotConfig.fromEnvironment(
            mapOf("FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC" to calendarRaw),
        )

        application {
            module(
                readinessProbe = { true },
                clock = clock,
                tradingConfig = tradingConfig,
            )
        }

        val boundaryResponse = client.get("/ops/runtime-config")
        val boundaryReadyResponse = client.get("/health/ready")
        clock.advance(Duration.ofMillis(1))
        val expiredResponse = client.get("/ops/runtime-config")
        val expiredReadyResponse = client.get("/health/ready")
        val boundaryWarnings = Json.parseToJsonElement(boundaryResponse.bodyAsText()).jsonObject
            .getValue("warnings").jsonArray
        val expiredWarnings = Json.parseToJsonElement(expiredResponse.bodyAsText()).jsonObject
            .getValue("warnings").jsonArray

        assertEquals(HttpStatusCode.OK, boundaryResponse.status)
        assertEquals(emptyList(), boundaryWarnings)
        assertEquals(HttpStatusCode.OK, boundaryReadyResponse.status)
        assertEquals(HttpStatusCode.OK, expiredResponse.status)
        assertEquals(
            "runtimeConfig.warning.fomcCalendarExpired",
            expiredWarnings.single().jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertEquals(HttpStatusCode.OK, expiredReadyResponse.status)
    }

    @Test
    fun opsRoutes_runtimeConfigDraftActivationReturnsMachineReadableValidationErrors() = testApplication {
        val adminService = FakeRuntimeConfigAdminService()

        application {
            module(
                readinessProbe = { true },
                opsRuntimeConfigAdminService = adminService,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val draftResponse = client.post("/ops/runtime-config/drafts") {
            contentType(ContentType.Application.Json)
            setBody("""{"values":{"runner.maxToolCallsPerRun":"49"},"note":"unsafe cap"}""")
        }
        val draftBody = Json.parseToJsonElement(draftResponse.bodyAsText()).jsonObject
        val versionId = draftBody.getValue("version").jsonObject.getValue("id").jsonPrimitive.content
        val activateResponse = client.post("/ops/runtime-config/drafts/$versionId/activate") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        val activateBody = Json.parseToJsonElement(activateResponse.bodyAsText()).jsonObject
        val validationError = activateBody.getValue("errors").jsonArray.single().jsonObject

        assertEquals(HttpStatusCode.Created, draftResponse.status)
        assertEquals("false", draftBody.getValue("validation").jsonObject.getValue("valid").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, activateResponse.status)
        assertEquals("false", activateBody.getValue("valid").jsonPrimitive.content)
        assertEquals("runtimeConfig.validation.typedBetweenInclusive", validationError.getValue("code").jsonPrimitive.content)
        assertEquals("runner.maxToolCallsPerRun", validationError.getValue("key").jsonPrimitive.content)
        assertEquals("48", validationError.getValue("params").jsonObject.getValue("max").jsonPrimitive.content)
    }

    @Test
    fun opsRoutes_runtimeConfigActivationReturnsMachineReadableEpochConflict() = testApplication {
        val adminService = FakeRuntimeConfigAdminService(
            activationFailure = PaperAccountEpochSwitchRejectedException(
                openPositionCount = 1,
                openOrderCount = 2,
                btcQuantity = "0.010000000000",
            ),
        )
        application {
            module(
                readinessProbe = { true },
                opsRuntimeConfigAdminService = adminService,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }
        val draft = client.post("/ops/runtime-config/drafts") {
            contentType(ContentType.Application.Json)
            setBody("""{"values":{"paper.initialCashJpy":"900000"},"note":"epoch conflict"}""")
        }
        val versionId = Json.parseToJsonElement(draft.bodyAsText()).jsonObject
            .getValue("version").jsonObject.getValue("id").jsonPrimitive.content

        val response = client.post("/ops/runtime-config/drafts/$versionId/activate") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("PAPER_ACCOUNT_EPOCH_SWITCH_REJECTED", body.getValue("code").jsonPrimitive.content)
        assertEquals(1, body.getValue("openPositionCount").jsonPrimitive.content.toInt())
        assertEquals(2, body.getValue("openOrderCount").jsonPrimitive.content.toInt())
        assertEquals("0.010000000000", body.getValue("btcQuantity").jsonPrimitive.content)
    }

    @Test
    fun moduleKeepsRuntimeConfigRecoveryApiAvailableWhenActiveConfigIsInvalid() = testApplication {
        if (!isDockerAvailable()) {
            println("Skipping module runtime config recovery test because Docker is unavailable.")
            return@testApplication
        }

        val container = FukurouPostgresContainer()
        container.start()

        try {
            val databaseConfig = DatabaseConfig(
                url = container.jdbcUrl,
                user = container.username,
                password = container.password,
            )
            val database = ExposedDatabase.connect(
                url = databaseConfig.url,
                driver = "org.postgresql.Driver",
                user = databaseConfig.user,
                password = databaseConfig.password,
            )
            RuntimeConfigPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()
            updateRuntimeConfigValue(database = database, key = "runner.maxToolCallsPerRun", value = "49")

            val manualService = CapturingManualLlmLaunchService(
                ManualLlmLaunchResult.Accepted(
                    invocationId = "manual-recovered",
                    triggerKind = LlmDaemonTriggerKind.MANUAL,
                ),
            )
            val reconcilerStatus = MutableReconcilerStatus()
            reconcilerStatus.markReconciled(
                startupFullReconcileCompleted = true,
                lastMaintenanceAt = fixedInstant(),
            )

            application {
                module(
                    clock = fixedClock(),
                    reconcilerStatus = reconcilerStatus,
                    opsManualLlmLaunchService = manualService,
                    databaseConfig = databaseConfig,
                )
            }

            val configResponse = client.get("/ops/runtime-config")
            val triggerResponse = client.post("/ops/trigger") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"operator recovery check"}""")
            }
            val readyResponse = client.get("/health/ready")
            val responseBody = Json.parseToJsonElement(configResponse.bodyAsText()).jsonObject
            val warning = responseBody.getValue("warnings").jsonArray.single().jsonObject
            val validationError = warning
                .getValue("validation")
                .jsonObject
                .getValue("errors")
                .jsonArray
                .single()
                .jsonObject

            assertEquals(HttpStatusCode.OK, configResponse.status)
            assertEquals("runtimeConfig.warning.activeValidationFailed", warning.getValue("code").jsonPrimitive.content)
            assertEquals("runtimeConfig.validation.typedBetweenInclusive", validationError.getValue("code").jsonPrimitive.content)
            assertEquals(HttpStatusCode.ServiceUnavailable, triggerResponse.status)
            assertEquals(HttpStatusCode.ServiceUnavailable, readyResponse.status)
            assertEquals(emptyList(), manualService.reasons)

            val draftResponse = client.post("/ops/runtime-config/drafts") {
                contentType(ContentType.Application.Json)
                setBody("""{"values":{"runner.maxToolCallsPerRun":"12"},"note":"restore runtime key"}""")
            }
            val draftBody = Json.parseToJsonElement(draftResponse.bodyAsText()).jsonObject
            val versionId = draftBody.getValue("version").jsonObject.getValue("id").jsonPrimitive.content
            val activateResponse = client.post("/ops/runtime-config/drafts/$versionId/activate") {
                contentType(ContentType.Application.Json)
                setBody("""{}""")
            }
            val recoveredConfigResponse = client.get("/ops/runtime-config")
            val recoveredTriggerResponse = client.post("/ops/trigger") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"operator restored runtime config"}""")
            }
            val recoveredReadyResponse = client.get("/health/ready")
            val recoveredBody = Json.parseToJsonElement(recoveredConfigResponse.bodyAsText()).jsonObject
            val recoveredWarnings = recoveredBody.getValue("warnings").jsonArray.map { element ->
                element.jsonObject.getValue("code").jsonPrimitive.content
            }

            assertEquals(HttpStatusCode.Created, draftResponse.status)
            assertEquals(HttpStatusCode.OK, activateResponse.status, activateResponse.bodyAsText())
            assertEquals(HttpStatusCode.OK, recoveredConfigResponse.status)
            assertFalse(recoveredWarnings.contains("runtimeConfig.warning.activeValidationFailed"))
            assertEquals(HttpStatusCode.Accepted, recoveredTriggerResponse.status)
            assertEquals(HttpStatusCode.OK, recoveredReadyResponse.status)
            assertEquals(listOf("operator restored runtime config"), manualService.reasons)
        } finally {
            container.stop()
        }
    }

    @Test
    fun moduleKeepsReadinessAndManualRecoveryAvailableWhenActiveFomcCalendarIsMissing() = testApplication {
        if (!isDockerAvailable()) {
            println("Skipping FOMC calendar composition test because Docker is unavailable.")
            return@testApplication
        }

        val container = FukurouPostgresContainer()
        container.start()

        try {
            val databaseConfig = DatabaseConfig(
                url = container.jdbcUrl,
                user = container.username,
                password = container.password,
            )
            val database = ExposedDatabase.connect(
                url = databaseConfig.url,
                driver = "org.postgresql.Driver",
                user = databaseConfig.user,
                password = databaseConfig.password,
            )
            RuntimeConfigPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()
            updateRuntimeConfigValue(
                database = database,
                key = "safety.economicEventBlackouts",
                value = "[]",
            )
            val manualService = CapturingManualLlmLaunchService(
                ManualLlmLaunchResult.Accepted(
                    invocationId = "manual-calendar-recovery",
                    triggerKind = LlmDaemonTriggerKind.MANUAL,
                ),
            )
            val reconcilerStatus = MutableReconcilerStatus()
            reconcilerStatus.markReconciled(
                startupFullReconcileCompleted = true,
                lastMaintenanceAt = fixedInstant(),
            )

            reconcilerStatus.updateMarketData(
                ReconcilerStatus(
                    startupFullReconcileCompleted = true,
                    startupRecoveryCompleted = true,
                    marketDataState = MarketDataConnectionState.CONNECTED,
                    lastTransportActivityAt = fixedInstant(),
                    lastMaintenanceAt = fixedInstant(),
                ),
            )

            application {
                module(
                    clock = fixedClock(),
                    reconcilerStatus = reconcilerStatus,
                    opsManualLlmLaunchService = manualService,
                    databaseConfig = databaseConfig,
                )
            }

            val configResponse = client.get("/ops/runtime-config")
            val readyResponse = client.get("/health/ready")
            val triggerResponse = client.post("/ops/trigger") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"calendar recovery"}""")
            }
            val warningCodes = Json.parseToJsonElement(configResponse.bodyAsText()).jsonObject
                .getValue("warnings").jsonArray.map { warning ->
                    warning.jsonObject.getValue("code").jsonPrimitive.content
                }

            assertEquals(HttpStatusCode.OK, configResponse.status)
            assertTrue(warningCodes.contains("runtimeConfig.warning.fomcCalendarMissing"))
            assertEquals(HttpStatusCode.OK, readyResponse.status, readyResponse.bodyAsText())
            assertEquals(HttpStatusCode.Accepted, triggerResponse.status, triggerResponse.bodyAsText())
            assertEquals(listOf("calendar recovery"), manualService.reasons)
        } finally {
            container.stop()
        }
    }

    @Test
    fun moduleRetriesRuntimeConfigSnapshotAfterTransientResolveFailure() = testApplication {
        if (!isDockerAvailable()) {
            println("Skipping module runtime config transient recovery test because Docker is unavailable.")
            return@testApplication
        }

        val container = FukurouPostgresContainer()
        container.start()

        try {
            val databaseConfig = DatabaseConfig(
                url = container.jdbcUrl,
                user = container.username,
                password = container.password,
            )
            val database = ExposedDatabase.connect(
                url = databaseConfig.url,
                driver = "org.postgresql.Driver",
                user = databaseConfig.user,
                password = databaseConfig.password,
            )
            RuntimeConfigPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()
            deleteRuntimeConfigValues(database)

            val manualService = CapturingManualLlmLaunchService(
                ManualLlmLaunchResult.Accepted(
                    invocationId = "manual-after-transient-recovery",
                    triggerKind = LlmDaemonTriggerKind.MANUAL,
                ),
            )

            application {
                module(
                    clock = fixedClock(),
                    opsManualLlmLaunchService = manualService,
                    databaseConfig = databaseConfig,
                )
            }

            val unavailableReadyResponse = client.get("/health/ready")
            val unavailableTriggerResponse = client.post("/ops/trigger") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"operator checks transient failure"}""")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, unavailableReadyResponse.status)
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailableTriggerResponse.status)
            assertEquals(emptyList(), manualService.reasons)

            insertRuntimeConfigDefaultValues(database)

            val recoveredReadyResponse = client.get("/health/ready")
            val recoveredTriggerResponse = client.post("/ops/trigger") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"operator confirms transient recovery"}""")
            }

            assertEquals(HttpStatusCode.OK, recoveredReadyResponse.status)
            assertEquals(HttpStatusCode.Accepted, recoveredTriggerResponse.status)
            assertEquals(listOf("operator confirms transient recovery"), manualService.reasons)
        } finally {
            container.stop()
        }
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
            executions = listOf(
                execution(
                    executionId = "partial-sell",
                    executedAt = fixedInstant().plusSeconds(1),
                    realizedPnlJpy = "120",
                    side = OrderSide.SELL,
                    positionId = "position-1",
                ),
                execution(
                    executionId = "entry-buy",
                    executedAt = fixedInstant().plusSeconds(2),
                    realizedPnlJpy = "0",
                    side = OrderSide.BUY,
                    positionId = "position-1",
                ),
                execution(
                    executionId = "closed-position-sell",
                    executedAt = fixedInstant().plusSeconds(3),
                    realizedPnlJpy = "80",
                    side = OrderSide.SELL,
                    positionId = "position-closed",
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

        val response = client.get("/ops/positions")
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject
        val positions = responseBody.getValue("positions").jsonArray
        val openOrders = responseBody.getValue("openOrders").jsonArray
        val sellExecutions = responseBody.getValue("sellExecutions").jsonArray

        assertEquals(HttpStatusCode.OK, response.status)
        assertNoSecretLikeText(responseText)
        assertEquals("position-1", positions.single().jsonObject.getValue("positionId").jsonPrimitive.content)
        assertEquals("order-1", openOrders.single().jsonObject.getValue("orderId").jsonPrimitive.content)
        assertEquals("partial-sell", sellExecutions.single().jsonObject.getValue("executionId").jsonPrimitive.content)
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
        eventLog.append(
            auditEvent(
                eventType = CommandEventType.GMO_PUBLIC_REST_REQUEST_COMPLETED,
                occurredAt = fixedInstant().plusSeconds(7),
                toolName = "gmo_public_rest",
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
        val gmoFilterResponse = client.get(
            "/ops/activity?source=audit&auditEventType=GMO_PUBLIC_REST_REQUEST_COMPLETED&limit=10",
        )
        val gmoFilterEvents = Json.parseToJsonElement(gmoFilterResponse.bodyAsText())
            .jsonObject
            .getValue("events")
            .jsonArray
        val rawGmoResponse = client.get("/ops/audit?eventType=GMO_PUBLIC_REST_REQUEST_COMPLETED")
        val rawGmoEvents = Json.parseToJsonElement(rawGmoResponse.bodyAsText())
            .jsonObject
            .getValue("events")
            .jsonArray

        assertEquals(HttpStatusCode.OK, latestResponse.status)
        assertNoSecretLikeText(latestResponseText)
        assertFalse(latestResponseText.contains("token"))
        assertEquals(
            listOf(
                "HARD_HALT_SET",
                "BTC entry fill",
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
        assertEquals(HttpStatusCode.OK, gmoFilterResponse.status)
        assertEquals(1, gmoFilterEvents.size)
        assertEquals(HttpStatusCode.OK, rawGmoResponse.status)
        assertEquals(1, rawGmoEvents.size)
    }

    @Test
    fun opsRoutes_activityExecutionDetailsExposeLinkedContextWithoutNoisyTimelineMetadata() = testApplication {
        val entryReason = "entry reason stays inside the execution detail dialog"
        val orderReason = "protective stop from risk floor"
        val ledgerRepository = ActivityExecutionContextRepository(
            activityRecords = listOf(
                ExecutionActivityRecord(
                    execution = execution(
                        executionId = "execution-stop",
                        executedAt = fixedInstant().plusSeconds(5),
                        realizedPnlJpy = "-1200",
                        side = OrderSide.SELL,
                        orderId = "order-stop",
                        positionId = "position-linked",
                    ),
                    order = ExecutionActivityOrderContext(
                        orderId = "order-stop",
                        orderType = OrderType.STOP,
                        triggerPriceJpy = "9800000",
                        takeProfitPriceJpy = null,
                        reasonJa = orderReason,
                    ),
                    position = ExecutionActivityPositionContext(
                        positionId = "position-linked",
                        tradeGroupId = "trade-group-linked",
                    ),
                    entryDecision = ExecutionActivityDecisionContext(
                        decisionId = "decision-entry",
                        decisionRunId = "entry-run-1",
                        action = DecisionAction.ENTER,
                        reasonJa = entryReason,
                    ),
                ),
                ExecutionActivityRecord(
                    execution = execution(
                        executionId = "execution-unlinked",
                        executedAt = fixedInstant().plusSeconds(4),
                        realizedPnlJpy = "0",
                        side = OrderSide.SELL,
                        orderId = null,
                        positionId = null,
                    ),
                    order = null,
                    position = null,
                    entryDecision = null,
                ),
            ),
        )

        application {
            module(
                readinessProbe = { true },
                clock = Clock.fixed(fixedInstant().plusSeconds(10), ZoneOffset.UTC),
                opsPaperLedgerRepository = ledgerRepository,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/activity?source=execution&limit=10")
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject
        val events = responseBody.getValue("events").jsonArray.map { element -> element.jsonObject }
        val stopEvent = events.first()
        val stopTimelineMetadata = stopEvent.getValue("metadata").toString()
        val stopDetails = stopEvent.getValue("details").jsonObject
        val missingLinkEvent = events.last()
        val missingLinkDetails = missingLinkEvent.getValue("details").jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertNoSecretLikeText(responseText)
        assertEquals("STOP_TRIGGER", stopEvent.getValue("kind").jsonPrimitive.content)
        assertEquals("BTC STOP trigger", stopEvent.getValue("title").jsonPrimitive.content)
        assertFalse(stopTimelineMetadata.contains(entryReason))
        assertFalse(stopTimelineMetadata.contains(orderReason))
        assertEquals(orderReason, metadataValue(stopDetails, "order reason"))
        assertEquals(entryReason, metadataValue(stopDetails, "decision reason"))
        assertEquals("ENTER", metadataValue(stopDetails, "decision action"))
        assertEquals("entry-run-1", metadataValue(stopDetails, "decision run"))
        assertEquals("trade-group-linked", metadataValue(stopDetails, "trade group"))
        assertEquals("SELL", missingLinkEvent.getValue("kind").jsonPrimitive.content)
        assertEquals("SELL BTC execution", missingLinkEvent.getValue("title").jsonPrimitive.content)
        assertEquals("not linked", metadataValue(missingLinkDetails, "order"))
        assertEquals("not linked", metadataValue(missingLinkDetails, "decision reason"))
    }

    @Test
    fun opsRoutes_activityCatalogMatchesSharedGoldenWithoutRepositories() = testApplication {
        application {
            module(
                readinessProbe = { true },
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/activity/catalog")
        val responseText = response.bodyAsText()
        val expectedJson = Json.parseToJsonElement(readSharedTestdata("ops-activity-catalog.golden.json"))
        val actualJson = Json.parseToJsonElement(responseText).jsonObject
        val auditEventTypes = actualJson.getValue("auditEventTypes").jsonArray
        val recoveryEvent = auditEventTypes.single { element ->
            element.jsonObject.getValue("value").jsonPrimitive.content == "LLM_INVOCATION_RECOVERED"
        }
        val recoveryStartedEvent = auditEventTypes.single { element ->
            element.jsonObject.getValue("value").jsonPrimitive.content == "LLM_EXECUTION_RECOVERY_STARTED"
        }
        val normalizedActual = JsonObject(
            actualJson + mapOf(
                "auditEventTypes" to JsonArray(
                    auditEventTypes.filter { element ->
                        element != recoveryEvent && element != recoveryStartedEvent
                    },
                ),
            ),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertNoSecretLikeText(responseText)
        assertEquals("activity.catalog.audit.llmInvocationRecovered.label", recoveryEvent.jsonObject.getValue("labelKey").jsonPrimitive.content)
        assertEquals(
            "activity.catalog.audit.llmExecutionRecoveryStarted.label",
            recoveryStartedEvent.jsonObject.getValue("labelKey").jsonPrimitive.content,
        )
        assertEquals(expectedJson, normalizedActual)
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
    fun opsRoutes_activityProjectsTypedInfrastructureSuppressionReason() = testApplication {
        val eventLog = InMemoryCommandEventLog()
        LlmDaemonLaunchSuppressionReason.entries.forEachIndexed { index, reason ->
            eventLog.append(
                auditEvent(
                    eventType = CommandEventType.DAEMON_LAUNCH_SUPPRESSED,
                    occurredAt = fixedInstant().plusSeconds(index.toLong()),
                    toolName = "llm_daemon_scheduler",
                    payload = """{"reason":"${reason.name}","triggerKind":"FLAT_HEARTBEAT"}""",
                ),
            ).getOrThrow()
        }
        eventLog.append(
            auditEvent(
                eventType = CommandEventType.DAEMON_LAUNCH_SUPPRESSED,
                occurredAt = fixedInstant().plusSeconds(LlmDaemonLaunchSuppressionReason.entries.size.toLong()),
                toolName = "llm_daemon_scheduler",
                payload = """{"reason":"STATUS_FUTURE","triggerKind":"FLAT_HEARTBEAT"}""",
            ),
        ).getOrThrow()

        application {
            module(
                readinessProbe = { true },
                opsCommandEventFeedReader = eventLog,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get(
            "/ops/activity?source=audit&auditEventType=DAEMON_LAUNCH_SUPPRESSED&limit=10",
        )
        val event = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("events")
            .jsonArray
            .map { element -> element.jsonObject }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            LlmDaemonLaunchSuppressionReason.entries.map { reason -> reason.name }.toSet() + "STATUS_FUTURE",
            event.map { item -> item.getValue("detail").jsonPrimitive.content }.toSet(),
        )
        event.forEach { item ->
            assertEquals("DAEMON_LAUNCH_SUPPRESSED", item.getValue("kind").jsonPrimitive.content)
            assertEquals(
                item.getValue("detail").jsonPrimitive.content,
                metadataValue(item, "infrastructure reason"),
            )
        }
    }

    @Test
    fun opsRoutes_auditDoesNotExposeCodexRawOutputAndKeepsStructuredUsage() = testApplication {
        val eventLog = InMemoryCommandEventLog()
        val request = codexAuditRequest()
        val invocationResult = codexAuditInvocationResult(request)
        val auditor = LlmInvocationAuditor(
            commandEventLog = eventLog,
            redactor = SecretRedactor(emptySet()),
            clock = fixedClock(),
        )

        auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = StaticOpsAuditLlmInvoker(invocationResult),
        ).getOrThrow()

        application {
            module(
                readinessProbe = { true },
                opsCommandEventFeedReader = eventLog,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/audit?eventType=RUNNER_PHASE_COMPLETED")
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject
        val event = responseBody.getValue("events").jsonArray.single().jsonObject
        val payload = Json.parseToJsonElement(event.getValue("payload").jsonPrimitive.content).jsonObject
        val details = payload.getValue("details").jsonObject
        val inputTokens = details
            .getValue("usage").jsonObject
            .getValue("usage").jsonObject
            .getValue("inputTokens").jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("codex", details.getValue("provider").jsonPrimitive.content)
        assertEquals("true", details.getValue("rawOutputOmitted").jsonPrimitive.content)
        assertEquals("42", inputTokens)
        assertFalse(details.containsKey("stdout"))
        assertFalse(details.containsKey("stderr"))
        assertFalse(responseText.contains("private trading strategy"))
        assertFalse(responseText.contains("submit_decision"))
        assertFalse(responseText.contains("private/session/path"))
    }

    @Test
    fun opsRoutes_auditClassifiesCodexFailureWithoutExposingExceptionPath() = testApplication {
        val eventLog = InMemoryCommandEventLog()
        val request = codexAuditRequest()
        val auditor = LlmInvocationAuditor(
            commandEventLog = eventLog,
            redactor = SecretRedactor(emptySet()),
            clock = fixedClock(),
        )
        val failure = FileSystemException(
            "/temporary/codex-home/auth-path-marker.json",
            null,
            "cleanup path-message-marker",
        )

        val invocation = auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = FailingOpsAuditLlmInvoker(failure),
        )

        application {
            module(
                readinessProbe = { true },
                opsCommandEventFeedReader = eventLog,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/ops/audit?eventType=RUNNER_PHASE_COMPLETED")
        val responseText = response.bodyAsText()
        val responseBody = Json.parseToJsonElement(responseText).jsonObject
        val event = responseBody.getValue("events").jsonArray.single().jsonObject
        val payload = Json.parseToJsonElement(event.getValue("payload").jsonPrimitive.content).jsonObject
        val details = payload.getValue("details").jsonObject

        assertTrue(invocation.isFailure)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("FAILED_TO_START", details.getValue("status").jsonPrimitive.content)
        assertEquals("null", details.getValue("exitCode").jsonPrimitive.content)
        assertEquals(
            "INVOCATION_RESULT_UNAVAILABLE",
            details.getValue("failureCategory").jsonPrimitive.content,
        )
        assertEquals("FileSystemException", details.getValue("failureType").jsonPrimitive.content)
        assertFalse(details.containsKey("error"))
        assertFalse(responseText.contains("auth-path-marker"))
        assertFalse(responseText.contains("path-message-marker"))
        assertFalse(responseText.contains("temporary/codex-home"))
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
    side: OrderSide = OrderSide.BUY,
    orderId: String? = "order-$executionId",
    positionId: String? = "position-$executionId",
): Execution {
    return Execution(
        executionId = executionId,
        orderId = orderId,
        positionId = positionId,
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = side,
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

private fun codexAuditRequest(): LlmInvocationRequest {
    return LlmInvocationRequest(
        invocationId = "codex-audit-run",
        provider = LlmProvider.CODEX,
        phase = LlmInvocationPhase.FALSIFIER,
        prompt = "synthetic prompt",
        timeout = Duration.ofSeconds(60),
        workingDirectory = Path.of("."),
        decisionRunContext = DecisionRunContext.EMPTY,
        mcpServer = null,
        environment = emptyMap(),
        allowedTools = emptyList(),
    )
}

private fun codexAuditInvocationResult(request: LlmInvocationRequest): LlmInvocationResult {
    val usage = LlmUsageDetails(
        totalCostUsd = null,
        numTurns = null,
        durationMs = null,
        usage = LlmTokenUsage(
            inputTokens = 42,
            outputTokens = 12,
            reasoningOutputTokens = 5,
            cacheCreationInputTokens = null,
            cacheReadInputTokens = 7,
        ),
        modelUsages = emptyList(),
    )

    return LlmInvocationResult(
        request = request,
        processResult = ProcessRunResult(
            status = ProcessRunStatus.EXITED,
            exitCode = 0,
            stdout = """
                {"type":"thread.started","prompt":"private trading strategy"}
                {"type":"item.completed","item":{"type":"tool_call","name":"submit_decision","arguments":{"path":"/private/session/path"}}}
            """.trimIndent(),
            stderr = "diagnostic path=/private/session/path",
        ),
        responseText = "semantic response",
        usage = usage,
    )
}

/**
 * ops audit route test 用の固定 LLM invoker。
 */
private class StaticOpsAuditLlmInvoker(
    private val invocationResult: LlmInvocationResult,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.success(invocationResult)
    }
}

/**
 * ops audit route test 用の失敗する LLM invoker。
 */
private class FailingOpsAuditLlmInvoker(
    private val failure: Throwable,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.failure(failure)
    }
}

private fun readSharedTestdata(fileName: String): String {
    val candidates = listOf(
        File("testdata", fileName),
        File("../testdata", fileName),
    )
    val testdataFile = candidates.firstOrNull { candidate -> candidate.isFile }
        ?: error("shared testdata not found: $fileName")

    return testdataFile.readText()
}

private fun assertNoSecretLikeText(responseText: String) {
    SECRET_FIXTURE_TEXTS.forEach { secretText ->
        assertFalse(responseText.contains(secretText), secretText)
    }
}

private fun assertConfigItem(
    group: JsonObject,
    key: String,
    sourceKind: String,
    effectiveValue: String,
) {
    val item = configItem(group, key)

    assertEquals(sourceKind, item.getValue("sourceKind").jsonPrimitive.content)
    assertEquals(effectiveValue, item.getValue("effectiveValue").jsonPrimitive.content)
    assertEquals("false", item.getValue("editable").jsonPrimitive.content)
}

private fun assertSecretItemConfigured(group: JsonObject, key: String) {
    val item = configItem(group, key)

    assertEquals("SECRET", item.getValue("sourceKind").jsonPrimitive.content)
    assertEquals("true", item.getValue("valueConfigured").jsonPrimitive.content)
    assertEquals("null", item.getValue("currentValue").toString())
    assertEquals("null", item.getValue("effectiveValue").toString())
}

private fun configItem(group: JsonObject, key: String): JsonObject {
    return group
        .getValue("items")
        .jsonArray
        .map { item -> item.jsonObject }
        .single { item -> item.getValue("key").jsonPrimitive.content == key }
}

private fun metadataValue(container: JsonObject, label: String): String {
    return container
        .getValue("metadata")
        .jsonArray
        .map { item -> item.jsonObject }
        .single { item -> item.getValue("label").jsonPrimitive.content == label }
        .getValue("value")
        .jsonPrimitive
        .content
}

private class FakeRuntimeConfigAdminService(
    private val listVersionsFailure: Throwable? = null,
    private val activationFailure: Throwable? = null,
) : RuntimeConfigAdminService {
    private val versions = mutableMapOf<String, RuntimeConfigVersionDetail>()
    private var activeVersionId: String = "active-runtime-config"
    private var nextDraftId = 1

    init {
        val values = RuntimeConfigCatalog.runtimeDefaultValues()
        val activeVersion = RuntimeConfigVersionDetail(
            version = versionSummary(
                id = activeVersionId,
                status = "ACTIVE",
                values = values,
            ),
            values = values,
            validation = RuntimeConfigCandidateValidator.validate(values, emptyMap()).validation,
        )
        versions[activeVersionId] = activeVersion
    }

    override fun listVersions(limit: Int): Result<List<RuntimeConfigVersionSummary>> {
        listVersionsFailure?.let { failure -> return Result.failure(failure) }

        return Result.success(
            versions.values
                .map { detail -> detail.version }
                .take(limit),
        )
    }

    override fun createDraft(request: RuntimeConfigDraftCreation): Result<RuntimeConfigVersionDetail> {
        val baseValues = versions.getValue(request.baseVersionId ?: activeVersionId).values
        val values = baseValues + request.values
        val versionId = "draft-${nextDraftId++}"
        val detail = RuntimeConfigVersionDetail(
            version = versionSummary(
                id = versionId,
                status = "DRAFT",
                values = values,
                note = request.note,
            ),
            values = values,
            validation = RuntimeConfigCandidateValidator.validate(values, emptyMap()).validation,
        )
        versions[versionId] = detail

        return Result.success(detail)
    }

    override fun validateVersion(versionId: String): Result<RuntimeConfigVersionDetail> {
        return Result.success(versions.getValue(versionId))
    }

    override fun activateDraft(versionId: String): Result<RuntimeConfigActivationResult> {
        activationFailure?.let { failure -> return Result.failure(failure) }
        val detail = versions.getValue(versionId)

        if (!detail.validation.valid) {
            return Result.failure(RuntimeConfigValidationRejectedException(detail.validation))
        }

        val previousActiveVersionId = activeVersionId
        activeVersionId = versionId
        val activeDetail = detail.copy(
            version = detail.version.copy(
                status = "ACTIVE",
                activatedAt = fixedInstant().toString(),
            ),
        )
        versions[previousActiveVersionId] = versions.getValue(previousActiveVersionId).copy(
            version = versions.getValue(previousActiveVersionId).version.copy(status = "INACTIVE"),
        )
        versions[versionId] = activeDetail

        return Result.success(
            RuntimeConfigActivationResult(
                activeVersion = activeDetail.version,
                previousActiveVersionId = previousActiveVersionId,
                validation = detail.validation,
            ),
        )
    }

    override fun rollbackToVersion(versionId: String): Result<RuntimeConfigActivationResult> {
        return activateDraft(versionId)
    }

    private fun versionSummary(
        id: String,
        status: String,
        values: Map<String, String>,
        note: String? = null,
    ): RuntimeConfigVersionSummary {
        return RuntimeConfigVersionSummary(
            id = id,
            status = status,
            createdAt = fixedInstant().toString(),
            activatedAt = if (status == "ACTIVE") fixedInstant().toString() else null,
            createdBy = "test",
            note = note,
            hash = calculateRuntimeConfigHash(values),
        )
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
 * Activity execution details route test 用 fake repository。
 *
 * @param activityRecords Activity execution read model として返す record
 * @param delegate その他の paper ledger API を処理する delegate
 */
private class ActivityExecutionContextRepository(
    private val activityRecords: List<ExecutionActivityRecord>,
    delegate: PaperLedgerRepository = InMemoryPaperLedgerRepository(),
) : PaperLedgerRepository by delegate {

    override suspend fun findExecutionActivitiesForStableFeed(
        cursor: StableFeedCursor,
        limit: Int,
    ): Result<List<ExecutionActivityRecord>> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            activityRecords
                .filter { record -> cursor.accepts(Instant.parse(record.execution.executedAt), record.execution.executionId) }
                .sortedWith(
                    compareByDescending<ExecutionActivityRecord> { record -> Instant.parse(record.execution.executedAt) }
                        .thenBy { record -> record.execution.executionId },
                )
                .take(limit)
        }
    }
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

/**
 * fukurou module integration test 用 Postgres image。
 */
private const val POSTGRES_IMAGE = "postgres:16-alpine"

/**
 * fukurou module test 用 Postgres container。
 */
private class FukurouPostgresContainer : PostgreSQLContainer<FukurouPostgresContainer>(POSTGRES_IMAGE) {
    init {
        configureBoundedTestJdbcConnections()
    }
}

private fun isDockerAvailable(): Boolean {
    return runCatching {
        DockerClientFactory.instance().client().pingCmd().exec()
        true
    }.getOrDefault(false)
}

private fun deleteRuntimeConfigValues(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(
            """
                DELETE FROM runtime_config_values
            """.trimIndent(),
        ).use { statement ->
            statement.executeUpdate()
        }
    }
}

private fun insertRuntimeConfigDefaultValues(database: ExposedDatabase) {
    exposedTransaction(database) {
        val activeVersionId = requireActiveRuntimeConfigVersionId()

        jdbcConnection().prepareStatement(
            """
                INSERT INTO runtime_config_values (
                    version_id,
                    config_key,
                    config_value
                )
                VALUES (?, ?, ?)
                ON CONFLICT (version_id, config_key) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            RuntimeConfigCatalog.runtimeDefaultValues().toSortedMap().forEach { (key, value) ->
                statement.setObject(1, activeVersionId)
                statement.setString(2, key)
                statement.setString(3, value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}

private fun JdbcTransaction.requireActiveRuntimeConfigVersionId(): UUID {
    jdbcConnection().prepareStatement(
        """
            SELECT id
            FROM runtime_config_versions
            WHERE status = 'ACTIVE'
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Active runtime config version was not found." }

            return resultSet.getObject(1, UUID::class.java)
        }
    }
}

private fun updateRuntimeConfigValue(
    database: ExposedDatabase,
    key: String,
    value: String,
) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(
            """
                UPDATE runtime_config_values
                SET config_value = ?
                WHERE config_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, value)
            statement.setString(2, key)
            statement.executeUpdate()
        }
    }
}

private fun JdbcTransaction.jdbcConnection(): Connection {
    return connection.connection as Connection
}
