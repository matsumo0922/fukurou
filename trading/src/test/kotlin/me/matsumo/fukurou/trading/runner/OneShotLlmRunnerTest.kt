package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.FUKUROU_INVOCATION_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_LLM_PROVIDER_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_MARKET_SNAPSHOT_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_PROMPT_HASH_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_SYSTEM_PROMPT_VERSION_ENV
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.InMemoryLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmDaemonOpenRiskReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonScheduler
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickResult
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.asDaemonLauncher
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.SystemPromptV1
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.InMemoryLlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_CANCELLED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_RUNNING
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunRecord
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.invoker.CODEX_HOME_ENV
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.invoker.ProcessRunner
import me.matsumo.fukurou.trading.invoker.RenderedLlmCommand
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OneShotLlmRunner の DB 正本 contract を検証するテスト。
 */
class OneShotLlmRunnerTest {

    @Test
    fun cliConfigFromEnvironment_splitsQuotedMcpServerArgs() {
        val config = OneShotRunnerCliConfig.fromEnvironment(
            mapOf(
                FUKUROU_MCP_SERVER_ARGS_ENV to """-jar "/app/fukurou mcp.jar"""",
            ),
        )

        assertEquals(listOf("-jar", "/app/fukurou mcp.jar"), config.mcpServerArgs)
    }

    @Test
    fun proposerNoTrade_savesDecisionAndDoesNotLaunchFalsifier() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }

            cleanExit()
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val decisions = fixture.decisionRepository.decisions()

        assertEquals(OneShotRunnerStatus.NO_TRADE_DECISION, result.status)
        assertEquals(1, fixture.processRunner.launches.size)
        assertEquals(1, decisions.size)
        assertEquals(DecisionAction.NO_TRADE, decisions.single().submission.action)
    }

    @Test
    fun manualRun_recordsRunningThenFinalLlmRunWithoutTriggerKind() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }

            cleanExit()
        }
        val request = defaultRequest().copy(invocationId = "manual-run")

        val result = fixture.runner.runOneShot(request).getOrThrow()
        val repository = fixture.runtime.llmRunRepository as InMemoryLlmRunRepository
        val record = repository.findByInvocationId(result.invocationId).getOrThrow()

        assertEquals(
            listOf(LLM_RUN_STATUS_RUNNING, OneShotRunnerStatus.NO_TRADE_DECISION.name),
            repository.statusHistory(result.invocationId),
        )
        assertEquals(OneShotRunnerStatus.NO_TRADE_DECISION.name, record?.status)
        assertNull(record?.triggerKind)
        assertTrue(record?.finishedAt != null)
    }

    @Test
    fun enterApproved_placesDeterministicPaperEntryAndConsumesIntent() = runBlocking {
        val fixture = runnerFixture { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val positions = fixture.runtime.broker.getPositions().getOrThrow()
        val consumptions = fixture.decisionRepository.intentConsumptions()

        assertEquals(OneShotRunnerStatus.PAPER_ENTRY_PLACED, result.status)
        assertPaperEntryAccepted(assertNotNull(result.tradeResult))
        assertEquals(2, fixture.processRunner.launches.size)
        assertEquals(1, positions.size)
        assertEquals(1, consumptions.size)
        assertEquals(result.intent?.intentId, consumptions.single().intentId)
    }

    @Test
    fun falsifierRejected_recordsNoTradeWithoutEntry() = runBlocking {
        val fixture = runnerFixture { command ->
            handleEnterAndRejectedFalsifier(fixtureRepository, command)
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val positions = fixture.runtime.broker.getPositions().getOrThrow()
        val events = fixture.eventLog.events()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(0, positions.size)
        assertTrue(events.containsNoTradeReason("falsifier_rejected"))
    }

    @Test
    fun falsifierTimeout_recordsNoTradeWithoutEntry() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.ENTER).getOrThrow()

                return@runnerFixture cleanExit()
            }

            timeoutExit()
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val positions = fixture.runtime.broker.getPositions().getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(0, positions.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("falsifier_missing_verdict"))
    }

    @Test
    fun proposerMissingDecisionForExitFailures_recordsCallerNoTrade() = runBlocking {
        val cases = listOf(
            "normal_exit" to cleanExit(),
            "nonzero_exit" to nonZeroExit(),
            "timeout" to timeoutExit(),
        )

        cases.forEach { failureCase ->
            val fixture = runnerFixture { failureCase.second }

            val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()

            assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status, failureCase.first)
            assertNull(result.decision, failureCase.first)
            assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_missing_decision"), failureCase.first)
        }
    }

    @Test
    fun maxInvocationsPerHourExceeded_rejectsLaunchAndAuditsNoTrade() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(maxInvocationsPerHour = 1),
        )
        val fixture = runnerFixture(config = config) { cleanExit() }
        fixture.eventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext(
                    decisionRunId = "existing-run",
                    llmProvider = "claude",
                    promptHash = "hash",
                    systemPromptVersion = SystemPromptV1.VERSION,
                    marketSnapshotId = "snapshot",
                ),
                toolName = "one_shot_runner",
                toolCallId = null,
                clientRequestId = "existing-run",
                eventType = CommandEventType.RUNNER_PHASE_COMPLETED,
                payload = "{}",
                occurredAt = fixedInstant(),
            ),
        ).getOrThrow()

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.LAUNCH_REJECTED, result.status)
        assertEquals(0, fixture.processRunner.launches.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("max_invocations_per_hour_exceeded"))
        assertEquals(
            OneShotRunnerStatus.LAUNCH_REJECTED.name,
            fixture.runtime.llmRunRepository.findByInvocationId(result.invocationId).getOrThrow()?.status,
        )
    }

    @Test
    fun daemonLaunchAuditDoesNotCountAgainstRunnerInvocationCap() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(maxInvocationsPerHour = 1),
        )
        val fixture = runnerFixture(config = config) { cleanExit() }
        fixture.eventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext(
                    decisionRunId = "daemon-reservation",
                    llmProvider = "claude",
                    promptHash = "hash",
                    systemPromptVersion = SystemPromptV1.VERSION,
                    marketSnapshotId = "snapshot",
                ),
                toolName = "llm-daemon-scheduler",
                toolCallId = null,
                clientRequestId = "flat-heartbeat",
                eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
                payload = "{}",
                occurredAt = fixedInstant(),
            ),
        ).getOrThrow()

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(1, fixture.processRunner.launches.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_missing_decision"))
    }

    @Test
    fun maxInvocationsPerDayExceeded_rejectsLaunchAndAuditsNoTrade() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(
                maxInvocationsPerHour = 1,
                maxInvocationsPerDay = 1,
            ),
        )
        val fixture = runnerFixture(config = config) { cleanExit() }
        fixture.eventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext(
                    decisionRunId = "existing-run",
                    llmProvider = "claude",
                    promptHash = "hash",
                    systemPromptVersion = SystemPromptV1.VERSION,
                    marketSnapshotId = "snapshot",
                ),
                toolName = "one_shot_runner",
                toolCallId = null,
                clientRequestId = "existing-run",
                eventType = CommandEventType.RUNNER_PHASE_COMPLETED,
                payload = "{}",
                occurredAt = fixedInstant().minusSeconds(7_200),
            ),
        ).getOrThrow()

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.LAUNCH_REJECTED, result.status)
        assertEquals(0, fixture.processRunner.launches.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("max_invocations_per_day_exceeded"))
    }

    @Test
    fun runEnvironmentIsRecordedAndTransferredThroughMcpConfig() = runBlocking {
        val fixture = runnerFixture { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        val decision = fixture.decisionRepository.decisions().single()
        val auditEvent = fixture.eventLog.events().first { event ->
            event.decisionRunContext.decisionRunId == decision.submission.invocationId
        }
        val proposerCommand = fixture.processRunner.launches.first()
        val mcpConfigContent = proposerCommand.claudeMcpConfigContent()

        assertEquals("claude", decision.submission.llmProvider)
        assertEquals(SystemPromptV1.VERSION, decision.submission.systemPromptVersion)
        assertNotNull(decision.submission.invocationId)
        assertNotNull(decision.submission.promptHash)
        assertNotNull(decision.submission.marketSnapshotId)
        assertEquals(decision.submission.invocationId, auditEvent.decisionRunContext.decisionRunId)
        assertTrue(mcpConfigContent.contains(FUKUROU_INVOCATION_ID_ENV))
        assertTrue(mcpConfigContent.contains(FUKUROU_LLM_PROVIDER_ENV))
        assertTrue(mcpConfigContent.contains(FUKUROU_PROMPT_HASH_ENV))
        assertTrue(mcpConfigContent.contains(FUKUROU_SYSTEM_PROMPT_VERSION_ENV))
        assertTrue(mcpConfigContent.contains(FUKUROU_MARKET_SNAPSHOT_ID_ENV))
    }

    @Test
    fun cliConfigControlsMcpServerNameCommandAndToolAllowlist() = runBlocking {
        val customServerName = "custom-mcp"
        val customSubmitDecisionTool = "mcp__custom-mcp__submit_decision"
        val customSubmitFalsificationTool = "mcp__custom-mcp__submit_falsification"
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }

            cleanExit()
        }

        fixture.runner.runOneShot(
            defaultRequest().copy(
                cliConfig = OneShotRunnerCliConfig(
                    mcpServerName = customServerName,
                    mcpServerCommand = "custom-java",
                    mcpServerArgs = listOf("-jar", MCP_JAR_PATH_PLACEHOLDER),
                    proposerAllowedTools = listOf(customSubmitDecisionTool),
                    falsifierAllowedTools = listOf(customSubmitFalsificationTool),
                ),
            ),
        ).getOrThrow()

        val proposerCommand = fixture.processRunner.launches.single()
        val joinedArgs = proposerCommand.args.joinToString(" ")
        val mcpConfigContent = proposerCommand.claudeMcpConfigContent()

        assertTrue(mcpConfigContent.contains(customServerName))
        assertTrue(mcpConfigContent.contains("custom-java"))
        assertTrue(joinedArgs.contains(customSubmitDecisionTool))
        assertFalse(joinedArgs.contains("mcp__fukurou-mcp__submit_decision"))
    }

    @Test
    fun approvedEntryRecordsDecisionToPlaceOrderLatencyPayload() = runBlocking {
        val fixture = runnerFixture { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        val latencyEvent = fixture.eventLog.events().single { event ->
            val runnerPhaseCompleted = event.eventType == CommandEventType.RUNNER_PHASE_COMPLETED
            val decisionToPlaceOrderPhase = event.payload.contains("decision_to_place_order")

            runnerPhaseCompleted && decisionToPlaceOrderPhase
        }

        assertTrue(latencyEvent.payload.contains("durationMillis"))
        assertTrue(latencyEvent.payload.contains("placeOrderExecutionMillis"))
    }

    @Test
    fun falsifierMcpConfigCarriesServerSideAllowlistWithoutTradeTools() = runBlocking {
        val fixture = runnerFixture { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        val falsifierCommand = fixture.processRunner.launches.single { command -> command.isFalsifierLaunch() }
        val allowedToolsConfig = falsifierCommand.codexConfigContent()

        assertTrue(allowedToolsConfig.contains("submit_falsification"))
        assertTrue(allowedToolsConfig.contains("get_trade_intent"))
        assertFalse(allowedToolsConfig.contains("place_order"))
        assertFalse(allowedToolsConfig.contains("submit_decision"))
    }

    @Test
    fun cliConfigRejectsFalsifierTradeToolOverride() {
        assertFailsWith<IllegalArgumentException> {
            OneShotRunnerCliConfig(
                falsifierAllowedTools = listOf("mcp__fukurou-mcp__place_order"),
            )
        }
    }

    @Test
    fun cliConfigRejectsProposerFalsifierOrTradeToolOverride() {
        assertFailsWith<IllegalArgumentException> {
            OneShotRunnerCliConfig(
                proposerAllowedTools = listOf(
                    "mcp__fukurou-mcp__submit_decision",
                    "mcp__fukurou-mcp__submit_falsification",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OneShotRunnerCliConfig(
                proposerAllowedTools = listOf(
                    "mcp__fukurou-mcp__submit_decision",
                    "mcp__fukurou-mcp__place_order",
                ),
            )
        }
    }

    @Test
    fun cliConfigRejectsNonMcpToolOverride() {
        assertFailsWith<IllegalArgumentException> {
            OneShotRunnerCliConfig(
                proposerAllowedTools = listOf(
                    "Bash",
                    "mcp__fukurou-mcp__submit_decision",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OneShotRunnerCliConfig(
                falsifierAllowedTools = listOf(
                    "Read",
                    "mcp__fukurou-mcp__submit_falsification",
                ),
            )
        }
    }

    @Test
    fun falsifierProcessEnvironmentExcludesDatabaseGmoAndLlmSecretVariables() = runBlocking {
        val fixture = runnerFixture(
            parentEnvironment = defaultParentEnvironment() + mapOf(
                "GMO_API_KEY" to "gmo-key",
                "GMO_SECRET_KEY" to "gmo-secret",
                "ANTHROPIC_API_KEY" to "anthropic-secret",
                "OPENAI_API_KEY" to "openai-secret",
                "FUKUROU_GMO_API_KEY" to "fukurou-gmo-key",
                "FUKUROU_GMO_SECRET" to "fukurou-gmo-secret",
                "FUKUROU_LLM_ACCESS_TOKEN" to "fukurou-token",
            ),
        ) { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        val falsifierCommand = fixture.processRunner.launches.single { command -> command.isFalsifierLaunch() }
        val proposerCommand = fixture.processRunner.launches.single { command -> command.isProposerLaunch() }
        val joinedArgs = falsifierCommand.args.joinToString(" ")
        val codexConfigContent = falsifierCommand.codexConfigContent()
        val forbiddenNames = listOf(
            "DB_URL",
            "DB_USER",
            "DB_PASSWORD",
            "GMO_API_KEY",
            "GMO_SECRET_KEY",
            "ANTHROPIC_API_KEY",
            "OPENAI_API_KEY",
            "FUKUROU_GMO_API_KEY",
            "FUKUROU_GMO_SECRET",
            "FUKUROU_LLM_ACCESS_TOKEN",
        )
        val secretNames = forbiddenNames - listOf("DB_URL", "DB_USER", "DB_PASSWORD")

        forbiddenNames.forEach { forbiddenName ->
            assertFalse(falsifierCommand.environment.containsKey(forbiddenName), forbiddenName)
            assertFalse(proposerCommand.environment.containsKey(forbiddenName), forbiddenName)
        }
        secretNames.forEach { secretName ->
            assertFalse(joinedArgs.contains(secretName), secretName)
        }
        assertFalse(joinedArgs.contains("DB_PASSWORD"))
        assertTrue(codexConfigContent.contains("DB_URL"))
        assertTrue(codexConfigContent.contains("DB_USER"))
        assertTrue(codexConfigContent.contains("DB_PASSWORD"))
        assertNotNull(falsifierCommand.environment[FUKUROU_FALSIFIER_INTENT_ID_ENV])

        Unit
    }

    @Test
    fun processOutputAuditRedactsSecretValues() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                return@runnerFixture cleanExit(
                    stdout = "token=test-password",
                    stderr = "credential=fukurou-token",
                )
            }

            cleanExit()
        }

        fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        val phaseEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.RUNNER_PHASE_COMPLETED }
        val proposerPhase = phaseEvents.single { event -> event.payload.contains("\"phase\":\"proposer\"") }

        assertTrue(proposerPhase.payload.contains("[REDACTED]"))
        assertFalse(proposerPhase.payload.contains("test-password"))
        assertFalse(proposerPhase.payload.contains("fukurou-token"))
    }

    @Test
    fun claudePhaseAuditStoresUsageButCodexPhaseDoesNot() = runBlocking {
        val usageStdout = """
            {
              "total_cost_usd": 0.02,
              "num_turns": 2,
              "duration_ms": 1000,
              "usage": {
                "input_tokens": 100,
                "output_tokens": 50
              }
            }
        """.trimIndent()
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.ENTER).getOrThrow()

                return@runnerFixture cleanExit(stdout = usageStdout)
            }

            submitFalsification(fixtureRepository, command, FalsificationVerdict.APPROVED).getOrThrow()

            cleanExit(stdout = usageStdout)
        }

        fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        val phaseEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.RUNNER_PHASE_COMPLETED }
        val proposerPhase = phaseEvents.single { event -> event.payload.contains("\"phase\":\"proposer\"") }
        val falsifierPhase = phaseEvents.single { event -> event.payload.contains("\"phase\":\"falsifier\"") }

        assertTrue(proposerPhase.payload.contains("\"usage\""))
        assertTrue(proposerPhase.payload.contains("\"totalCostUsd\":\"0.02\""))
        assertFalse(falsifierPhase.payload.contains("\"usage\""))
    }

    @Test
    fun unexpectedRunnerFailure_recordsCallerNoTradeAudit() = runBlocking {
        val missingPromptRoot = Files.createTempDirectory("fukurou-missing-prompt")
        val fixture = runnerFixture { cleanExit() }
        val request = defaultRequest().copy(
            invocationId = "missing-prompt-run",
            repositoryRoot = missingPromptRoot,
        )

        val result = fixture.runner.runOneShot(request)

        assertTrue(result.isFailure)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("caller_failed"))
        assertEquals(0, fixture.processRunner.launches.size)
        assertEquals(
            LLM_RUN_STATUS_FAILED,
            fixture.runtime.llmRunRepository.findByInvocationId("missing-prompt-run").getOrThrow()?.status,
        )
    }

    @Test
    fun invokerException_recordsFailedLlmRunWithRedactedErrorMessage() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = FakeMarketDataSource,
        )
        val runner = OneShotLlmRunner(
            tradingRuntime = runtime,
            tradingConfig = TradingBotConfig(),
            llmInvoker = ThrowingLlmInvoker("phase failed with test-password"),
            parentEnvironment = defaultParentEnvironment(),
            clock = fixedClock(),
            logger = {},
        )

        val result = runner.runOneShot(defaultRequest().copy(invocationId = "failed-run"))
        val record = runtime.llmRunRepository.findByInvocationId("failed-run").getOrThrow()
        val errorMessage = requireNotNull(record?.errorMessage)

        assertTrue(result.isFailure)
        assertEquals(LLM_RUN_STATUS_FAILED, record.status)
        assertTrue(errorMessage.contains("[REDACTED]"))
        assertFalse(errorMessage.contains("test-password"))
    }

    @Test
    fun llmRunRecordFailure_doesNotPreventRunnerCompletion() = runBlocking {
        val fixture = runnerFixture(
            runtimeTransform = { runtime ->
                runtime.copy(llmRunRepository = FailingLlmRunRepository)
            },
        ) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }

            cleanExit()
        }

        val result = fixture.runner.runOneShot(defaultRequest().copy(invocationId = "record-failure-run"))

        assertTrue(result.isSuccess)
        assertEquals(OneShotRunnerStatus.NO_TRADE_DECISION, result.getOrThrow().status)
    }

    @Test
    fun daemonTickLaunch_recordsTriggerKindInLlmRun() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = FakeMarketDataSource,
        )
        val decisionRepository = runtime.decisionRepository as InMemoryDecisionRepository
        val processRunner = FakeProcessRunner { command ->
            if (command.isProposerLaunch()) {
                submitDecision(decisionRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }

            cleanExit()
        }
        val runner = OneShotLlmRunner(
            tradingRuntime = runtime,
            tradingConfig = TradingBotConfig(),
            llmInvoker = ShellLlmInvoker(
                commandRenderer = DefaultLlmCommandRenderer(),
                processRunner = processRunner,
            ),
            parentEnvironment = defaultParentEnvironment(),
            clock = fixedClock(),
            logger = {},
        )
        val scheduler = LlmDaemonScheduler(
            tradingConfig = TradingBotConfig(),
            riskStateRepository = runtime.riskStateRepository,
            commandEventLog = runtime.commandEventLog,
            launchReservationRepository = InMemoryLlmLaunchReservationRepository(runtime.riskStateRepository),
            openRiskReader = LlmDaemonOpenRiskReader { Result.success(false) },
            requestBase = defaultRequest(),
            launchOneShot = runner.asDaemonLauncher(),
            clock = fixedClock(),
            idGenerator = { UUID(0L, 42L) },
        )

        val tickResult = assertIs<LlmDaemonTickResult.Launched>(scheduler.tick())
        val record = runtime.llmRunRepository.findByInvocationId(tickResult.invocationId).getOrThrow()

        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, record?.triggerKind)
        assertEquals(OneShotRunnerStatus.NO_TRADE_DECISION.name, record?.status)
    }

    @Test
    fun callerCancellation_recordsNoTradeAudit() = runBlocking {
        val launchStarted = CompletableDeferred<Unit>()
        val fixture = runnerFixture {
            launchStarted.complete(Unit)

            awaitCancellation()
        }
        val request = defaultRequest().copy(invocationId = "cancelled-run")
        val runnerDeferred = async {
            fixture.runner.runOneShot(request).getOrThrow()
        }

        launchStarted.await()
        runnerDeferred.cancel()

        assertFailsWith<CancellationException> {
            runnerDeferred.await()
        }
        assertTrue(fixture.eventLog.events().containsNoTradeReason("caller_cancelled"))
        assertEquals(
            LLM_RUN_STATUS_CANCELLED,
            fixture.runtime.llmRunRepository.findByInvocationId("cancelled-run").getOrThrow()?.status,
        )
    }
}

private lateinit var fixtureRepository: DecisionRepository

/**
 * runner test fixture。
 *
 * @param runtime trading runtime
 * @param decisionRepository in-memory decision repository
 * @param eventLog in-memory audit log
 * @param processRunner fake process runner
 * @param runner test target runner
 */
private data class RunnerFixture(
    val runtime: TradingRuntime,
    val decisionRepository: InMemoryDecisionRepository,
    val eventLog: InMemoryCommandEventLog,
    val processRunner: FakeProcessRunner,
    val runner: OneShotLlmRunner,
)

private fun runnerFixture(
    config: TradingBotConfig = TradingBotConfig(),
    parentEnvironment: Map<String, String> = defaultParentEnvironment(),
    runtimeTransform: (TradingRuntime) -> TradingRuntime = { runtime -> runtime },
    launchHandler: suspend (RenderedLlmCommand) -> ProcessRunResult,
): RunnerFixture {
    val runtime = runtimeTransform(
        TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = FakeMarketDataSource,
            tradingConfig = config,
        ),
    )
    val decisionRepository = runtime.decisionRepository as InMemoryDecisionRepository
    val eventLog = runtime.commandEventLog as InMemoryCommandEventLog
    val processRunner = FakeProcessRunner(launchHandler)
    val runner = OneShotLlmRunner(
        tradingRuntime = runtime,
        tradingConfig = config,
        llmInvoker = ShellLlmInvoker(
            commandRenderer = DefaultLlmCommandRenderer(),
            processRunner = processRunner,
        ),
        parentEnvironment = parentEnvironment,
        clock = fixedClock(),
        logger = {},
    )
    fixtureRepository = decisionRepository

    return RunnerFixture(
        runtime = runtime,
        decisionRepository = decisionRepository,
        eventLog = eventLog,
        processRunner = processRunner,
        runner = runner,
    )
}

/**
 * LLM 起動時に例外を投げる test double。
 *
 * @param message 例外 message
 */
private class ThrowingLlmInvoker(
    private val message: String,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        require(request.invocationId.isNotBlank()) {
            "invocationId must not be blank."
        }

        throw IllegalStateException(message)
    }
}

/**
 * llm_runs 書き込みに必ず失敗する test repository。
 */
private object FailingLlmRunRepository : LlmRunRepository {
    override suspend fun insertRunning(start: LlmRunStart): Result<Unit> {
        return Result.failure(IllegalStateException("llm run insert failed"))
    }

    override suspend fun finish(finish: LlmRunFinish): Result<Unit> {
        return Result.failure(IllegalStateException("llm run finish failed"))
    }

    override suspend fun findByInvocationId(invocationId: String): Result<LlmRunRecord?> {
        return Result.failure(IllegalStateException("llm run read failed"))
    }
}

/**
 * ProcessRunner test double。
 *
 * @param launchHandler launch hook
 */
private class FakeProcessRunner(
    private val launchHandler: suspend (RenderedLlmCommand) -> ProcessRunResult,
) : ProcessRunner {
    val launches = mutableListOf<RenderedLlmCommand>()

    override suspend fun run(command: RenderedLlmCommand): Result<ProcessRunResult> {
        launches += command

        return Result.success(launchHandler(command))
    }
}

private suspend fun handleEnterAndApprovedFalsifier(
    repository: DecisionRepository,
    command: RenderedLlmCommand,
): ProcessRunResult {
    if (command.isProposerLaunch()) {
        submitDecision(repository, command, DecisionAction.ENTER).getOrThrow()

        return cleanExit()
    }

    submitFalsification(repository, command, FalsificationVerdict.APPROVED).getOrThrow()

    return cleanExit()
}

private suspend fun handleEnterAndRejectedFalsifier(
    repository: DecisionRepository,
    command: RenderedLlmCommand,
): ProcessRunResult {
    if (command.isProposerLaunch()) {
        submitDecision(repository, command, DecisionAction.ENTER).getOrThrow()

        return cleanExit()
    }

    submitFalsification(repository, command, FalsificationVerdict.REJECTED).getOrThrow()

    return cleanExit()
}

private suspend fun submitDecision(
    repository: DecisionRepository,
    command: RenderedLlmCommand,
    action: DecisionAction,
): Result<Unit> {
    return repository.submitDecision(decisionSubmission(command, action)).fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { throwable -> Result.failure(throwable) },
    )
}

private suspend fun submitFalsification(
    repository: DecisionRepository,
    command: RenderedLlmCommand,
    verdict: FalsificationVerdict,
): Result<Unit> {
    return repository.submitFalsification(
        FalsificationSubmission(
            intentId = UUID.fromString(requireNotNull(command.environment[FUKUROU_FALSIFIER_INTENT_ID_ENV])),
            verdict = verdict,
            llmProvider = command.environment[FUKUROU_LLM_PROVIDER_ENV],
            reasonJa = "runner test falsifier verdict",
        ),
    ).fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { throwable -> Result.failure(throwable) },
    )
}

private fun decisionSubmission(command: RenderedLlmCommand, action: DecisionAction): DecisionSubmission {
    return DecisionSubmission(
        invocationId = command.environment[FUKUROU_INVOCATION_ID_ENV],
        llmProvider = command.environment[FUKUROU_LLM_PROVIDER_ENV],
        promptHash = command.environment[FUKUROU_PROMPT_HASH_ENV],
        systemPromptVersion = command.environment[FUKUROU_SYSTEM_PROMPT_VERSION_ENV],
        marketSnapshotId = command.environment[FUKUROU_MARKET_SNAPSHOT_ID_ENV],
        action = action,
        setupTags = if (action == DecisionAction.ENTER) listOf("runner-test") else emptyList(),
        estimatedWinProbability = BigDecimal("0.60"),
        expectedRMultiple = if (action == DecisionAction.ENTER) BigDecimal("2.0") else null,
        roundTripCostR = if (action == DecisionAction.ENTER) BigDecimal("0.1") else null,
        toolEvidenceIds = listOf("tool-1"),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = "runner test decision",
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = if (action == DecisionAction.ENTER) entryIntentDraft() else null,
        tradePlan = if (action == DecisionAction.ENTER) tradePlanDraft() else null,
    )
}

private fun entryIntentDraft(): EntryIntentDraft {
    return EntryIntentDraft(
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.MARKET,
        sizeBtc = BigDecimal("0.0050"),
        priceJpy = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("10500000"),
    )
}

private fun tradePlanDraft(): TradePlanDraft {
    return TradePlanDraft(
        parentTradePlanId = null,
        revisionCount = 0,
        symbol = TradingSymbol.BTC,
        thesisJa = "runner test thesis",
        invalidationConditionsJa = listOf("runner test invalidation"),
        targetPriceJpy = BigDecimal("10500000"),
        timeStopAt = null,
        setupTags = listOf("runner-test"),
    )
}

private fun RenderedLlmCommand.isProposerLaunch(): Boolean {
    return environment[FUKUROU_LLM_PROVIDER_ENV] == "claude"
}

private fun RenderedLlmCommand.isFalsifierLaunch(): Boolean {
    return environment[FUKUROU_FALSIFIER_INTENT_ID_ENV] != null
}

private fun RenderedLlmCommand.claudeMcpConfigContent(): String {
    val configPath = Path.of(args[args.indexOf("--mcp-config") + 1])

    return Files.readString(configPath)
}

private fun RenderedLlmCommand.codexConfigContent(): String {
    val codexHome = Path.of(requireNotNull(environment[CODEX_HOME_ENV]))

    return Files.readString(codexHome.resolve("config.toml"))
}

private fun cleanExit(
    stdout: String = "",
    stderr: String = "",
): ProcessRunResult {
    return ProcessRunResult(
        status = ProcessRunStatus.EXITED,
        exitCode = 0,
        stdout = stdout,
        stderr = stderr,
    )
}

private fun nonZeroExit(): ProcessRunResult {
    return ProcessRunResult(
        status = ProcessRunStatus.EXITED,
        exitCode = 1,
        stdout = "",
        stderr = "failed",
    )
}

private fun timeoutExit(): ProcessRunResult {
    return ProcessRunResult(
        status = ProcessRunStatus.TIMED_OUT,
        exitCode = null,
        stdout = "",
        stderr = "timeout",
    )
}

private fun assertPaperEntryAccepted(result: PaperTradeResult) {
    assertTrue(result.accepted)
    assertEquals(1, result.positionIds.size)
    assertEquals(1, result.executionIds.size)
}

private fun List<CommandEvent>.containsNoTradeReason(reason: String): Boolean {
    return any { event ->
        val noTradeExit = event.eventType == CommandEventType.NO_TRADE_EXIT
        val reasonMatched = event.payload.contains(reason)

        noTradeExit && reasonMatched
    }
}

private fun defaultRequest(): OneShotRunnerRequest {
    return OneShotRunnerRequest(
        repositoryRoot = repositoryRoot(),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        mcpJarPath = "mcp/build/libs/fukurou-mcp-all.jar",
    )
}

private fun repositoryRoot(): Path {
    var currentPath = Path.of(".").toAbsolutePath().normalize()

    repeat(MAX_REPOSITORY_ROOT_SEARCH_DEPTH) {
        if (Files.exists(currentPath.resolve(SystemPromptV1.RELATIVE_PATH))) {
            return currentPath
        }

        currentPath = requireNotNull(currentPath.parent) {
            "repository root was not found."
        }
    }

    error("repository root was not found from ${Path.of(".").toAbsolutePath().normalize()}.")
}

/**
 * test repository root 探索の最大深さ。
 */
private const val MAX_REPOSITORY_ROOT_SEARCH_DEPTH = 6

private fun defaultParentEnvironment(): Map<String, String> {
    return mapOf(
        "PATH" to "/usr/bin:/bin",
        "HOME" to "/tmp/fukurou-runner-test",
        "DB_URL" to "jdbc:postgresql://localhost:5432/fukurou",
        "DB_USER" to "fukurou",
        "DB_PASSWORD" to "test-password",
        "FUKUROU_LLM_ACCESS_TOKEN" to "fukurou-token",
    )
}

/**
 * runner test 用の固定 market data source。
 */
private object FakeMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.success(
            Ticker(
                symbol = symbol.apiSymbol,
                last = "10000000",
                bid = "9990000",
                ask = "10000000",
                high = "10100000",
                low = "9900000",
                volume = "1.0",
                timestamp = fixedInstant().toString(),
            ),
        )
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(emptyList())
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        return Result.success(emptyList())
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.success(
            SymbolRules(
                symbol = symbol.apiSymbol,
                minOrderSize = "0.0001",
                sizeStep = "0.0001",
                tickSize = "1",
                takerFee = "0.0005",
                makerFee = "-0.0001",
            ),
        )
    }
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
