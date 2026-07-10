package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.FUKUROU_INVOCATION_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_LLM_PROVIDER_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_MARKET_SNAPSHOT_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_PROMPT_HASH_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_SYSTEM_PROMPT_VERSION_ENV
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.PaperBroker
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.config.DecisionProtocolConfig
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.InMemoryLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmDaemonEntryFillReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonPositionsReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonScheduler
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerDependencies
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerRuntime
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickResult
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickerSnapshot
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.asDaemonLauncher
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.SystemPromptV1
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.requiresEntryIntent
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
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
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.invoker.ProcessRunner
import me.matsumo.fukurou.trading.invoker.RenderedLlmCommand
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.safeCodexFailureOrNull
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import me.matsumo.fukurou.trading.safety.InMemorySafetyViolationRepository
import me.matsumo.fukurou.trading.safety.SafetyFloor
import me.matsumo.fukurou.trading.safety.SafetyFloorRule
import me.matsumo.fukurou.trading.tool.ToolCallGuard
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        val decisions = fixture.decisionRepository.snapshots.decisions()

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
        val consumptions = fixture.decisionRepository.snapshots.intentConsumptions()

        assertEquals(OneShotRunnerStatus.PAPER_ENTRY_PLACED, result.status)
        assertPaperEntryAccepted(assertNotNull(result.tradeResult))
        assertEquals(2, fixture.processRunner.launches.size)
        assertEquals(1, positions.size)
        assertEquals(1, consumptions.size)
        assertEquals(result.intent?.intentId, consumptions.single().intentId)
    }

    @Test
    fun enterApproved_recordsCrossingLimitDivergenceMemoInRunnerLifecycleAudit() = runBlocking {
        val config = TradingBotConfig()
        val fixture = runnerFixture(
            config = config,
            runtimeTransform = { runtime ->
                runtime.withMarketDataSource(CrossingLimitPartialOrderbookMarketDataSource, config)
            },
        ) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(
                    repository = fixtureRepository,
                    command = command,
                    action = DecisionAction.ENTER,
                    entryIntent = entryIntentDraft(
                        orderType = OrderType.LIMIT,
                        priceJpy = BigDecimal("10000000"),
                    ),
                ).getOrThrow()

                return@runnerFixture cleanExit()
            }

            submitFalsification(fixtureRepository, command, FalsificationVerdict.APPROVED).getOrThrow()

            cleanExit()
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val placeOrderDetails = fixture.eventLog.events().singleRunnerPhaseDetails("decision_to_place_order")
        val memo = placeOrderDetails
            .getValue("paperExecutionDivergenceMemos")
            .jsonArray
            .single()
            .jsonObject

        assertEquals(OneShotRunnerStatus.PAPER_ENTRY_PLACED, result.status)
        assertEquals("LIMIT_PARTIAL_FAK_DIVERGENCE", memo.stringValue("kind"))
        assertEquals(result.intent?.intentId.toString(), memo.stringValue("intentId"))
        assertEquals(TradingSymbol.BTC.apiSymbol, memo.stringValue("symbol"))
        assertEquals("0.005000000000", memo.stringValue("requestedSizeBtc"))
        assertEquals("0.002000000000", memo.stringValue("hypotheticalFilledSizeBtc"))
        assertEquals("0.003000000000", memo.stringValue("hypotheticalRemainingSizeBtc"))
    }

    @Test
    fun addLongApproved_mergesIntoExistingPositionThroughRunnerEntryFlow() = runBlocking {
        lateinit var parentTradePlanId: UUID
        val config = TradingBotConfig()
        val fixture = addLongRunnerFixture(config = config) { command ->
            handleAddLongAndApprovedFalsifier(
                repository = fixtureRepository,
                command = command,
                parentTradePlanId = parentTradePlanId,
            )
        }
        parentTradePlanId = requireNotNull(
            fixture.decisionRepository
                .submitDecision(seedEntryDecisionSubmission(entryIntentDraft()))
                .getOrThrow()
                .tradePlan,
        ).tradePlanId

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val position = fixture.runtime.broker.getPositions().getOrThrow().single()
        val stopOrder = fixture.runtime.broker.getOpenOrders().getOrThrow().single()
        val violations = (fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository).violations()

        assertEquals(OneShotRunnerStatus.PAPER_ENTRY_PLACED, result.status)
        assertTrue(assertNotNull(result.tradeResult).accepted)
        assertEquals(2, fixture.processRunner.launches.size)
        assertEquals(DecisionAction.ADD_LONG, result.decision?.decision?.submission?.action)
        assertEquals("0.011000000000", position.sizeBtc)
        assertEquals(1, position.pyramidAddCount)
        assertEquals("9900000.00000000", position.currentStopLossJpy)
        assertEquals("0.011000000000", stopOrder.sizeBtc)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun addLongSafetyRejection_recordsViolationAndNoTradeAuditThroughRunnerEntryFlow() = runBlocking {
        lateinit var parentTradePlanId: UUID
        val config = TradingBotConfig()
        val fixture = addLongRunnerFixture(
            config = config,
            existingPosition = addLongTargetPosition().copy(
                pyramidAddCount = 2,
                unrealizedR = "3.000000",
            ),
        ) { command ->
            handleAddLongAndApprovedFalsifier(
                repository = fixtureRepository,
                command = command,
                parentTradePlanId = parentTradePlanId,
            )
        }
        parentTradePlanId = requireNotNull(
            fixture.decisionRepository
                .submitDecision(seedEntryDecisionSubmission(entryIntentDraft()))
                .getOrThrow()
                .tradePlan,
        ).tradePlanId

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val tradeResult = assertNotNull(result.tradeResult)
        val position = fixture.runtime.broker.getPositions().getOrThrow().single()
        val violationRepository = fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository
        val violations = violationRepository.violations()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertFalse(tradeResult.accepted)
        assertEquals(OrderStatus.REJECTED, tradeResult.status)
        assertEquals(SafetyFloorRule.PYRAMID_ADD_LIMIT, tradeResult.safetyViolation?.rule)
        assertEquals("0.010000000000", position.sizeBtc)
        assertEquals(listOf(SafetyFloorRule.PYRAMID_ADD_LIMIT), violations.map { violation -> violation.rule })
        assertTrue(fixture.eventLog.events().containsNoTradeReason("preview_order_rejected"))
    }

    @Test
    fun addLongWithoutExistingPosition_recordsNoTradeAuditBeforeOrderPreview() = runBlocking {
        lateinit var parentTradePlanId: UUID
        val config = TradingBotConfig()
        val fixture = runnerFixture(
            config = config,
            runtimeTransform = { runtime ->
                runtime.withPaperLedger(
                    positions = emptyList(),
                    openOrders = emptyList(),
                    accountSnapshot = highEquityAccountSnapshotWithBtc(),
                    config = config,
                )
            },
        ) { command ->
            handleAddLongAndApprovedFalsifier(
                repository = fixtureRepository,
                command = command,
                parentTradePlanId = parentTradePlanId,
            )
        }
        parentTradePlanId = requireNotNull(
            fixture.decisionRepository
                .submitDecision(seedEntryDecisionSubmission(entryIntentDraft()))
                .getOrThrow()
                .tradePlan,
        ).tradePlanId

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val violations = (fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository).violations()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertNull(result.tradeResult)
        assertEquals(2, fixture.processRunner.launches.size)
        assertTrue(fixture.runtime.broker.getPositions().getOrThrow().isEmpty())
        assertTrue(violations.isEmpty())
        assertTrue(fixture.eventLog.events().containsNoTradeReason("add_long_target_position_missing"))
    }

    @Test
    fun staleRestingEntryOrderTtl_cancelsBeforeProposerDecision() = runBlocking {
        val clock = MutableTestClock(fixedInstant())
        val config = TradingBotConfig(
            decisionProtocol = DecisionProtocolConfig(restingEntryOrderTtl = Duration.ofMinutes(30)),
        )
        val fixture = runnerFixture(config = config, clock = clock) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }

            cleanExit()
        }
        seedApprovedEntry(
            fixture = fixture,
            entryIntent = entryIntentDraft(
                orderType = OrderType.LIMIT,
                priceJpy = BigDecimal("9900000"),
            ),
        )
        clock.advance(Duration.ofMinutes(31))

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val openOrders = fixture.runtime.broker.getOpenOrders().getOrThrow()
        val cancelEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "cancel_order"
        }
        val violations = (fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository).violations()

        assertEquals(OneShotRunnerStatus.NO_TRADE_DECISION, result.status)
        assertEquals(0, openOrders.size)
        assertEquals(1, cancelEvents.size)
        assertTrue(cancelEvents.single().payload.contains("resting_entry_order_ttl_exceeded"))
        assertTrue(
            fixture.eventLog.events().any { event ->
                event.eventType == CommandEventType.DECISION_LIFECYCLE_COMPLETED &&
                    event.payload.contains("stale_resting_entry_ttl_sweep")
            },
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun lifecycleAudit_recordsElapsedDurationMillis() = runBlocking {
        val clock = TickingTestClock(
            currentInstant = fixedInstant(),
            tick = Duration.ofMillis(25),
        )
        val fixture = runnerFixture(clock = clock) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }

            cleanExit()
        }

        fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val lifecyclePayload = fixture.eventLog.events()
            .singleLifecyclePayload("stale_resting_entry_ttl_sweep")
        val durationMillis = lifecyclePayload.stringValue("durationMillis").toLong()

        assertTrue(durationMillis > 0)
    }

    @Test
    fun exitDecision_closesSingleOpenPositionDeterministically() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.EXIT).getOrThrow()
            }

            cleanExit()
        }
        seedApprovedEntry(fixture)

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val openPositions = fixture.runtime.broker.getPositions().getOrThrow()
        val closeEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "close_position"
        }
        val violations = (fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository).violations()

        assertEquals(OneShotRunnerStatus.PAPER_EXIT_EXECUTED, result.status)
        assertEquals(OrderStatus.FILLED, assertNotNull(result.tradeResult).status)
        assertEquals(0, openPositions.size)
        assertEquals(1, closeEvents.size)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun exitDecision_closesSingleOpenPositionDuringHardHalt() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.EXIT).getOrThrow()
            }

            cleanExit()
        }
        seedApprovedEntry(fixture)
        fixture.runtime.riskStateRepository.setHardHalt("test hard halt", fixedInstant()).getOrThrow()

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val openPositions = fixture.runtime.broker.getPositions().getOrThrow()
        val closeEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "close_position"
        }
        val hardHaltRejections = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_REJECTED_BY_HARD_HALT
        }

        assertEquals(OneShotRunnerStatus.PAPER_EXIT_EXECUTED, result.status)
        assertEquals(0, openPositions.size)
        assertEquals(1, closeEvents.size)
        assertEquals(0, hardHaltRejections.size)
    }

    @Test
    fun reduceDecision_partiallyClosesSingleOpenPositionAndKeepsProtection() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(
                    repository = fixtureRepository,
                    command = command,
                    action = DecisionAction.REDUCE,
                    closeRatio = BigDecimal("0.50"),
                ).getOrThrow()
            }

            cleanExit()
        }
        seedApprovedEntry(fixture)

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val remainingPosition = fixture.runtime.broker.getPositions().getOrThrow().single()
        val stopOrder = fixture.runtime.broker.getOpenOrders().getOrThrow().single()
        val closeEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "close_position"
        }
        val closeRequestPayload = closeEvents.single().payloadJsonObject()
            .getValue("payload")
            .jsonPrimitive
            .content
        val violations = (fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository).violations()

        assertEquals(OneShotRunnerStatus.PAPER_REDUCE_EXECUTED, result.status)
        assertEquals(OrderStatus.FILLED, assertNotNull(result.tradeResult).status)
        assertEquals("0.002500000000", remainingPosition.sizeBtc)
        assertEquals("0.002500000000", stopOrder.sizeBtc)
        assertEquals("9700000.00000000", remainingPosition.currentStopLossJpy)
        assertEquals(1, closeEvents.size)
        assertTrue(closeRequestPayload.contains("\"action\":\"REDUCE\""))
        assertTrue(closeRequestPayload.contains("\"close_ratio\":\"0.50\""))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun exitDecision_closesSingleOpenPositionWhenRestingEntryOrderAlsoExists() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.EXIT).getOrThrow()
            }

            cleanExit()
        }
        seedApprovedEntry(fixture)
        seedApprovedEntry(
            fixture = fixture,
            entryIntent = entryIntentDraft(
                orderType = OrderType.LIMIT,
                priceJpy = BigDecimal("9900000"),
                sizeBtc = BigDecimal("0.0010"),
            ),
            tradeGroupId = UUID.randomUUID(),
        )

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val openPositions = fixture.runtime.broker.getPositions().getOrThrow()
        val openOrders = fixture.runtime.broker.getOpenOrders().getOrThrow()
        val closeEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "close_position"
        }

        assertEquals(OneShotRunnerStatus.PAPER_EXIT_EXECUTED, result.status)
        assertEquals(0, openPositions.size)
        assertEquals(1, openOrders.size)
        assertEquals(1, closeEvents.size)
    }

    @Test
    fun exitDecision_cancelsSingleRestingEntryOrderDeterministically() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.EXIT).getOrThrow()
            }

            cleanExit()
        }
        seedApprovedEntry(
            fixture = fixture,
            entryIntent = entryIntentDraft(
                orderType = OrderType.LIMIT,
                priceJpy = BigDecimal("9900000"),
            ),
        )

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val openOrders = fixture.runtime.broker.getOpenOrders().getOrThrow()
        val cancelEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "cancel_order"
        }
        val violations = (fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository).violations()

        assertEquals(OneShotRunnerStatus.PAPER_EXIT_EXECUTED, result.status)
        assertEquals(OrderStatus.CANCELED, assertNotNull(result.tradeResult).status)
        assertEquals(0, openOrders.size)
        assertEquals(1, cancelEvents.size)
        assertTrue(cancelEvents.single().payload.contains("exit_decision_cancel_resting_entry_order"))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun exitDecision_multipleOpenPositionsFailsClosed() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.EXIT).getOrThrow()
            }

            cleanExit()
        }
        seedApprovedEntry(
            fixture = fixture,
            entryIntent = entryIntentDraft(sizeBtc = BigDecimal("0.0010")),
            tradeGroupId = UUID.randomUUID(),
        )
        seedApprovedEntry(
            fixture = fixture,
            entryIntent = entryIntentDraft(sizeBtc = BigDecimal("0.0010")),
            tradeGroupId = UUID.randomUUID(),
        )

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val openPositions = fixture.runtime.broker.getPositions().getOrThrow()
        val closeEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "close_position"
        }

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(2, openPositions.size)
        assertEquals(0, closeEvents.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("exit_target_ambiguous"))
    }

    @Test
    fun adjustProtectionDecision_updatesTakeProfitWithoutStopWidening() = runBlocking {
        lateinit var parentTradePlanId: UUID
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(
                    repository = fixtureRepository,
                    command = command,
                    action = DecisionAction.ADJUST_PROTECTION,
                    tradePlan = tradePlanDraft(
                        parentTradePlanId = parentTradePlanId,
                        revisionCount = 1,
                        targetPriceJpy = BigDecimal("10600000"),
                    ),
                ).getOrThrow()
            }

            cleanExit()
        }
        val seedDecision = seedApprovedEntry(fixture)
        parentTradePlanId = requireNotNull(seedDecision.tradePlan).tradePlanId
        val stopBefore = fixture.runtime.broker.getPositions().getOrThrow().single().currentStopLossJpy

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val position = fixture.runtime.broker.getPositions().getOrThrow().single()
        val updateEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "update_protection"
        }
        val violations = (fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository).violations()

        assertEquals(OneShotRunnerStatus.PAPER_PROTECTION_UPDATED, result.status)
        assertEquals(OrderStatus.OPEN, assertNotNull(result.tradeResult).status)
        assertEquals(stopBefore, position.currentStopLossJpy)
        assertEquals("10600000", position.currentTakeProfitJpy)
        assertEquals(1, updateEvents.size)
        assertTrue(updateEvents.single().payload.contains("newStopPriceJpy"))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun adjustProtectionDecision_missingTargetPriceFailsClosed() = runBlocking {
        lateinit var parentTradePlanId: UUID
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(
                    repository = fixtureRepository,
                    command = command,
                    action = DecisionAction.ADJUST_PROTECTION,
                    tradePlan = tradePlanDraft(
                        parentTradePlanId = parentTradePlanId,
                        revisionCount = 1,
                        targetPriceJpy = null,
                    ),
                ).getOrThrow()
            }

            cleanExit()
        }
        val seedDecision = seedApprovedEntry(fixture)
        parentTradePlanId = requireNotNull(seedDecision.tradePlan).tradePlanId

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val updateEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "update_protection"
        }

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(0, updateEvents.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("adjust_protection_missing_target_price"))
    }

    @Test
    fun adjustProtectionDecision_unprotectedPositionFailsClosed() = runBlocking {
        lateinit var parentTradePlanId: UUID
        val config = TradingBotConfig()
        val fixture = runnerFixture(
            config = config,
            runtimeTransform = { runtime ->
                runtime.withPaperLedger(
                    positions = listOf(openPosition(currentStopLossJpy = null)),
                    config = config,
                )
            },
        ) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(
                    repository = fixtureRepository,
                    command = command,
                    action = DecisionAction.ADJUST_PROTECTION,
                    tradePlan = tradePlanDraft(
                        parentTradePlanId = parentTradePlanId,
                        revisionCount = 1,
                        targetPriceJpy = BigDecimal("10600000"),
                    ),
                ).getOrThrow()
            }

            cleanExit()
        }
        val seedDecision = fixture.decisionRepository.submitDecision(
            seedEntryDecisionSubmission(entryIntentDraft()),
        ).getOrThrow()
        parentTradePlanId = requireNotNull(seedDecision.tradePlan).tradePlanId

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val updateEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "update_protection"
        }

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(0, updateEvents.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("adjust_protection_unprotected_position"))
    }

    @Test
    fun adjustProtectionDecision_takeProfitAtOrBelowCurrentPriceFailsClosed() = runBlocking {
        lateinit var parentTradePlanId: UUID
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(
                    repository = fixtureRepository,
                    command = command,
                    action = DecisionAction.ADJUST_PROTECTION,
                    tradePlan = tradePlanDraft(
                        parentTradePlanId = parentTradePlanId,
                        revisionCount = 1,
                        targetPriceJpy = BigDecimal("9900000"),
                    ),
                ).getOrThrow()
            }

            cleanExit()
        }
        val seedDecision = seedApprovedEntry(fixture)
        parentTradePlanId = requireNotNull(seedDecision.tradePlan).tradePlanId

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val position = fixture.runtime.broker.getPositions().getOrThrow().single()
        val updateEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "update_protection"
        }
        val currentTakeProfitPrice = requireNotNull(position.currentTakeProfitJpy).toBigDecimal()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(0, BigDecimal("10500000").compareTo(currentTakeProfitPrice))
        assertEquals(0, updateEvents.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("adjust_protection_invalid_take_profit_price"))
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
    fun proposerMissingDecisionForProcessFailures_recordsCallerNoTrade() = runBlocking {
        val cases = listOf(
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
    fun proposerNormalExitWithoutToolCalls_recordsNoToolCallsReason() = runBlocking {
        val fixture = runnerFixture { cleanExit() }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertNull(result.decision)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_no_tool_calls"))
    }

    @Test
    fun proposerCliErrorExitZeroWithoutAuth_recordsMissingDecisionReasonAndCliErrorSignal() = runBlocking {
        val humanLogs = mutableListOf<String>()
        val fixture = runnerFixture(
            logger = { message -> humanLogs += message },
        ) {
            cleanExit(
                stdout = "MCP transport returned an application error",
                stderr = """{"type":"result","is_error":true,"result":"MCP server disconnected"}""",
            )
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val proposerDetails = fixture.eventLog.events().singleRunnerPhaseDetails("proposer")

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertNull(result.decision)
        assertEquals("true", proposerDetails.stringValue("cliErrorReported"))
        assertFalse(proposerDetails.containsKey("authFailureSuspected"))
        assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_missing_decision"))
        assertFalse(fixture.eventLog.events().containsNoTradeReason("proposer_no_tool_calls"))
        assertFalse(humanLogs.any { message -> message.isAuthFailureRunbookLog() })
    }

    @Test
    fun proposerNormalExitWithToolCallAndNoDecision_recordsMissingDecisionReason() = runBlocking {
        lateinit var eventLog: InMemoryCommandEventLog
        val fixture = runnerFixture(
            runtimeTransform = { runtime ->
                eventLog = runtime.commandEventLog as InMemoryCommandEventLog

                runtime
            },
        ) { command ->
            if (command.isProposerLaunch()) {
                eventLog.append(proposerToolCallCompletedEvent(command)).getOrThrow()
            }

            cleanExit()
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertNull(result.decision)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_missing_decision"))
        assertFalse(fixture.eventLog.events().containsNoTradeReason("proposer_no_tool_calls"))
    }

    @Test
    fun proposerToolCallCountFailure_recordsMissingDecisionReason() = runBlocking {
        val fixture = runnerFixture(
            runtimeTransform = { runtime ->
                runtime.copy(commandEventLog = CountFailureCommandEventLog(runtime.commandEventLog))
            },
        ) { cleanExit() }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertNull(result.decision)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_missing_decision"))
        assertFalse(fixture.eventLog.events().containsNoTradeReason("proposer_no_tool_calls"))
    }

    @Test
    fun proposerAuthFailureExit_addsOperationalSignalAndKeepsNoTradeAudit() = runBlocking {
        val humanLogs = mutableListOf<String>()
        val fixture = runnerFixture(
            logger = { message -> humanLogs += message },
        ) {
            nonZeroExit(
                stdout = "API Error: 401 Invalid authentication credentials",
            )
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val proposerDetails = fixture.eventLog.events().singleRunnerPhaseDetails("proposer")

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals("true", proposerDetails.stringValue("authFailureSuspected"))
        assertFalse(proposerDetails.containsKey("cliErrorReported"))
        assertEquals("1", proposerDetails.stringValue("exitCode"))
        assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_missing_decision"))
        assertTrue(humanLogs.any { message -> message.isAuthFailureRunbookLog() })
    }

    @Test
    fun proposerUnrelatedExitFailure_omitsAuthFailureSignal() = runBlocking {
        val humanLogs = mutableListOf<String>()
        val fixture = runnerFixture(
            logger = { message -> humanLogs += message },
        ) {
            nonZeroExit(stdout = "network retry exhausted")
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val proposerDetails = fixture.eventLog.events().singleRunnerPhaseDetails("proposer")
        val authFailureLogFound = humanLogs.any { message -> message.contains("LLM CLI authentication failure suspected.") }

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertFalse(proposerDetails.containsKey("authFailureSuspected"))
        assertFalse(proposerDetails.containsKey("cliErrorReported"))
        assertEquals("1", proposerDetails.stringValue("exitCode"))
        assertFalse(authFailureLogFound)
    }

    @Test
    fun proposerAuthFailureExitZero_recordsOperationalSignalAndMissingDecisionReason() = runBlocking {
        val humanLogs = mutableListOf<String>()
        val fixture = runnerFixture(
            logger = { message -> humanLogs += message },
        ) {
            cleanExit(stdout = """{"type":"result","is_error":true,"result":"Not logged in"}""")
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val proposerDetails = fixture.eventLog.events().singleRunnerPhaseDetails("proposer")

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals("true", proposerDetails.stringValue("authFailureSuspected"))
        assertEquals("true", proposerDetails.stringValue("cliErrorReported"))
        assertEquals("0", proposerDetails.stringValue("exitCode"))
        assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_missing_decision"))
        assertFalse(fixture.eventLog.events().containsNoTradeReason("proposer_no_tool_calls"))
        assertTrue(humanLogs.any { message -> message.isAuthFailureRunbookLog() })
    }

    @Test
    fun proposerSuccess_omitsAuthFailureSignalEvenWhenOutputMentionsAuthText() = runBlocking {
        val fixture = runnerFixture {
            cleanExit(stdout = """{"type":"result","is_error":false,"result":"Not logged in"}""")
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val proposerDetails = fixture.eventLog.events().singleRunnerPhaseDetails("proposer")

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertFalse(proposerDetails.containsKey("authFailureSuspected"))
        assertFalse(proposerDetails.containsKey("cliErrorReported"))
        assertEquals("0", proposerDetails.stringValue("exitCode"))
        assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_no_tool_calls"))
    }

    @Test
    fun proposerFailedToStart_auditsRedactedErrorAndRecordsNoTradeExit() = runBlocking {
        val failure = IOException(
            "Cannot run program \"claude\" (in directory \"/tmp/fukurou-llm\"): " +
                "error=2, No such file or directory, token=test-password",
        )
        val runtime = TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = FakeMarketDataSource,
        )
        val runner = OneShotLlmRunner(
            tradingRuntime = runtime,
            tradingConfig = TradingBotConfig(),
            llmInvoker = FailureResultLlmInvoker(failure),
            parentEnvironment = defaultParentEnvironment(),
            clock = fixedClock(),
            logger = {},
        )

        val result = runner.runOneShot(defaultRequest()).getOrThrow()
        val events = (runtime.commandEventLog as InMemoryCommandEventLog).events()
        val proposerDetails = events.singleRunnerPhaseDetails("proposer")
        val auditError = proposerDetails.stringValue("error")
        val noTradePayload = events.singleNoTradePayload()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals("FAILED_TO_START", proposerDetails.stringValue("status"))
        assertEquals("null", proposerDetails.stringValue("exitCode"))
        assertTrue(auditError.contains("java.io.IOException"))
        assertTrue(auditError.contains("Cannot run program \"claude\""))
        assertTrue(auditError.contains("/tmp/fukurou-llm"))
        assertTrue(auditError.contains("[REDACTED]"))
        assertFalse(auditError.contains("test-password"))
        assertFalse(proposerDetails.containsKey("stdout"))
        assertFalse(proposerDetails.containsKey("stderr"))
        assertEquals("proposer_missing_decision", noTradePayload.stringValue("reason"))
        assertEquals("true", noTradePayload.stringValue("noTrade"))
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
    }

    @Test
    fun decisionRunAuditIncludesRuntimeConfigSnapshot() = runBlocking {
        val runtimeConfigSnapshot = RuntimeConfigAuditSnapshot(
            versionId = "runtime-version-1",
            hash = "runtime-hash-1",
        )
        val fixture = runnerFixture(runtimeConfigSnapshot = runtimeConfigSnapshot) { cleanExit() }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val llmRun = requireNotNull(
            fixture.runtime.llmRunRepository.findByInvocationId(result.invocationId).getOrThrow(),
        )
        val preflightPayload = fixture.eventLog.events()
            .single { event -> event.isRunnerPhaseCompleted("preflight") }
            .payloadJsonObject()

        assertEquals("runtime-version-1", llmRun.runtimeConfigVersionId)
        assertEquals("runtime-hash-1", llmRun.runtimeConfigHash)
        assertEquals("runtime-version-1", preflightPayload.stringValue("runtimeConfigVersionId"))
        assertEquals("runtime-hash-1", preflightPayload.stringValue("runtimeConfigHash"))
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

        val decision = fixture.decisionRepository.snapshots.decisions().single()
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

        val toolCompletionOrder = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.TOOL_CALL_COMPLETED }
            .map { event -> event.toolName }
            .filter { toolName -> toolName == "preview_order" || toolName == "place_order" }
        val latencyEvent = fixture.eventLog.events().single { event ->
            val runnerPhaseCompleted = event.eventType == CommandEventType.RUNNER_PHASE_COMPLETED
            val decisionToPlaceOrderPhase = event.payload.contains("decision_to_place_order")

            runnerPhaseCompleted && decisionToPlaceOrderPhase
        }
        val latencyDetails = fixture.eventLog.events().singleRunnerPhaseDetails("decision_to_place_order")

        assertEquals(listOf("preview_order", "place_order"), toolCompletionOrder)
        assertTrue(latencyEvent.payload.contains("durationMillis"))
        assertTrue(latencyEvent.payload.contains("previewExecutionMillis"))
        assertTrue(latencyEvent.payload.contains("placeOrderExecutionMillis"))
        assertEquals("true", latencyDetails.stringValue("previewAccepted"))
        assertEquals(64, latencyDetails.stringValue("previewHash").length)
        assertEquals(64, latencyDetails.stringValue("placeOrderHash").length)
        assertFalse(latencyDetails.containsKey("previewHashMismatchWarning"))
    }

    @Test
    fun previewRejectedHardHaltTriggersAuthoritativePlaceOrderSideEffectWithoutEntry() = runBlocking {
        val config = TradingBotConfig()
        val fixture = runnerFixture(
            config = config,
            runtimeTransform = { runtime -> runtime.withHardHaltDrawdown(config) },
        ) { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val tradeResult = assertNotNull(result.tradeResult)
        val positions = fixture.runtime.broker.getPositions().getOrThrow()
        val riskState = fixture.runtime.riskStateRepository.current().getOrThrow()
        val toolCompletionOrder = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.TOOL_CALL_COMPLETED }
            .map { event -> event.toolName }
            .filter { toolName -> toolName == "preview_order" || toolName == "place_order" }
        val latencyDetails = fixture.eventLog.events().singleRunnerPhaseDetails("decision_to_place_order")

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertFalse(tradeResult.accepted)
        assertEquals(OrderStatus.REJECTED, tradeResult.status)
        assertEquals(SafetyFloorRule.MAX_DRAWDOWN_HALT, tradeResult.safetyViolation?.rule)
        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
        assertTrue(requireNotNull(riskState.haltReason).contains("MAX_DRAWDOWN_HALT"))
        assertEquals(0, positions.size)
        assertEquals(listOf("preview_order", "place_order"), toolCompletionOrder)
        assertEquals("false", latencyDetails.stringValue("previewAccepted"))
        assertEquals("MAX_DRAWDOWN_HALT", latencyDetails.stringValue("previewSafetyViolationRule"))
        assertEquals("false", latencyDetails.stringValue("accepted"))
        assertTrue(tradeResult.executionIds.isEmpty())
        assertEquals(64, latencyDetails.stringValue("placeOrderHash").length)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("preview_order_rejected"))
    }

    @Test
    fun previewRejectedSoftSafetyViolationIsPersistedByAuthoritativePlaceOrder() = runBlocking {
        val fixture = runnerFixture { command ->
            handleEnterAndApprovedFalsifier(
                repository = fixtureRepository,
                command = command,
                estimatedWinProbability = BigDecimal("0.20"),
            )
        }

        val result = fixture.runner.runOneShot(defaultRequest()).getOrThrow()
        val tradeResult = assertNotNull(result.tradeResult)
        val positions = fixture.runtime.broker.getPositions().getOrThrow()
        val violationRepository = fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository
        val violations = violationRepository.violations()
        val toolCompletionOrder = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.TOOL_CALL_COMPLETED }
            .map { event -> event.toolName }
            .filter { toolName -> toolName == "preview_order" || toolName == "place_order" }
        val latencyDetails = fixture.eventLog.events().singleRunnerPhaseDetails("decision_to_place_order")

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertFalse(tradeResult.accepted)
        assertEquals(OrderStatus.REJECTED, tradeResult.status)
        assertEquals(SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE, tradeResult.safetyViolation?.rule)
        assertEquals(0, positions.size)
        assertEquals(listOf("preview_order", "place_order"), toolCompletionOrder)
        assertEquals(listOf(SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE), violations.map { violation -> violation.rule })
        assertEquals("false", latencyDetails.stringValue("previewAccepted"))
        assertEquals("NON_POSITIVE_EXPECTED_VALUE", latencyDetails.stringValue("previewSafetyViolationRule"))
        assertEquals("false", latencyDetails.stringValue("accepted"))
        assertTrue(fixture.eventLog.events().containsNoTradeReason("preview_order_rejected"))
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
        assertTrue(allowedToolsConfig.contains("knowledge_get_recent_lessons"))
        assertTrue(allowedToolsConfig.contains("knowledge_search_similar_setups"))
        assertTrue(allowedToolsConfig.contains("preview_order"))
        assertFalse(allowedToolsConfig.contains("place_order"))
        assertFalse(allowedToolsConfig.contains("submit_decision"))
    }

    @Test
    fun runnerAutoApprovesOnlyCodexFalsifierWriteTool() = runBlocking {
        val fixture = requestCapturingRunnerFixture()

        fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        val proposerRequest = fixture.invoker.requests.single { request ->
            request.phase == LlmInvocationPhase.PROPOSER
        }
        val falsifierRequest = fixture.invoker.requests.single { request ->
            request.phase == LlmInvocationPhase.FALSIFIER
        }

        assertEquals(emptyList(), requireNotNull(proposerRequest.mcpServer).autoApprovedTools)
        assertEquals(listOf("submit_falsification"), requireNotNull(falsifierRequest.mcpServer).autoApprovedTools)
        assertTrue(proposerRequest.allowedTools.contains("mcp__fukurou-mcp__get_trade_intent"))
        assertTrue(proposerRequest.allowedTools.contains("mcp__fukurou-mcp__knowledge_get_recent_lessons"))
        assertTrue(proposerRequest.allowedTools.contains("mcp__fukurou-mcp__knowledge_search_similar_setups"))
        assertTrue(falsifierRequest.allowedTools.contains("mcp__fukurou-mcp__knowledge_get_recent_lessons"))
        assertTrue(falsifierRequest.allowedTools.contains("mcp__fukurou-mcp__knowledge_search_similar_setups"))
    }

    @Test
    fun runnerAutoApprovesCodexProposerWriteTool() = runBlocking {
        val fixture = requestCapturingRunnerFixture(
            proposerAction = DecisionAction.NO_TRADE,
        )

        fixture.runner.runOneShot(
            defaultRequest().copy(
                proposerProvider = LlmProvider.CODEX,
            ),
        ).getOrThrow()

        val proposerRequest = fixture.invoker.requests.single()

        assertEquals(LlmProvider.CODEX, proposerRequest.provider)
        assertEquals(listOf("submit_decision"), requireNotNull(proposerRequest.mcpServer).autoApprovedTools)
    }

    @Test
    fun runnerDoesNotAutoApproveWhenFalsifierWriteToolIsNotAllowed() = runBlocking {
        val readOnlyFalsifierTools = defaultFalsifierAllowedTools(DEFAULT_RUNNER_MCP_SERVER_NAME)
            .filterNot { toolName -> toolName.endsWith("__submit_falsification") }
        val fixture = requestCapturingRunnerFixture()

        fixture.runner.runOneShot(
            defaultRequest().copy(
                cliConfig = OneShotRunnerCliConfig(
                    falsifierAllowedTools = readOnlyFalsifierTools,
                ),
            ),
        ).getOrThrow()

        val falsifierRequest = fixture.invoker.requests.single { request ->
            request.phase == LlmInvocationPhase.FALSIFIER
        }

        assertEquals(emptyList(), requireNotNull(falsifierRequest.mcpServer).autoApprovedTools)
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
        val proposerDetails = fixture.eventLog.events().singleRunnerPhaseDetails("proposer")

        assertEquals("EXITED", proposerDetails.stringValue("status"))
        assertEquals("0", proposerDetails.stringValue("exitCode"))
        assertFalse(proposerDetails.containsKey("error"))
        assertTrue(proposerPhase.payload.contains("[REDACTED]"))
        assertFalse(proposerPhase.payload.contains("test-password"))
        assertFalse(proposerPhase.payload.contains("fukurou-token"))
    }

    @Test
    fun phaseAuditStoresClaudeAndCodexStructuredUsage() = runBlocking {
        val claudeUsageStdout = """
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
        val codexUsageStdout = """
            {"type":"thread.started","thread_id":"synthetic-thread"}
            {"type":"item.completed","item":{"type":"agent_message","text":"approved"}}
            {"type":"turn.completed","usage":{"input_tokens":80,"cached_input_tokens":20,"output_tokens":30,"reasoning_output_tokens":10}}
        """.trimIndent()
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.ENTER).getOrThrow()

                return@runnerFixture cleanExit(stdout = claudeUsageStdout)
            }

            submitFalsification(fixtureRepository, command, FalsificationVerdict.APPROVED).getOrThrow()

            cleanExit(stdout = codexUsageStdout)
        }

        fixture.runner.runOneShot(defaultRequest()).getOrThrow()

        val phaseEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.RUNNER_PHASE_COMPLETED }
        val proposerPhase = phaseEvents.single { event -> event.payload.contains("\"phase\":\"proposer\"") }
        val falsifierPhase = phaseEvents.single { event -> event.payload.contains("\"phase\":\"falsifier\"") }

        assertTrue(proposerPhase.payload.contains("\"usage\""))
        assertTrue(proposerPhase.payload.contains("\"totalCostUsd\":\"0.02\""))
        assertTrue(falsifierPhase.payload.contains("\"usage\""))
        assertTrue(falsifierPhase.payload.contains("\"reasoningOutputTokens\":10"))
        assertFalse(falsifierPhase.payload.contains("\"totalCostUsd\""))
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
    fun codexInvokerFailure_omitsExceptionPathFromLlmRunAndNoTradeAudit() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = FakeMarketDataSource,
        )
        val failure = FileSystemException(
            "/temporary/codex-home/auth-path-marker.json",
            null,
            "cleanup path-message-marker",
        )
        val runner = OneShotLlmRunner(
            tradingRuntime = runtime,
            tradingConfig = TradingBotConfig(),
            llmInvoker = ThrowingLlmInvoker(failure),
            parentEnvironment = defaultParentEnvironment(),
            clock = fixedClock(),
            logger = {},
        )
        val request = defaultRequest().copy(
            invocationId = "codex-failed-run",
            proposerProvider = LlmProvider.CODEX,
        )

        val result = runner.runOneShot(request)
        val record = runtime.llmRunRepository.findByInvocationId("codex-failed-run").getOrThrow()
        val eventLog = runtime.commandEventLog as InMemoryCommandEventLog
        val noTradeEvent = eventLog.events()
            .single { event -> event.eventType == CommandEventType.NO_TRADE_EXIT }
        val failureDisclosure = requireNotNull(result.exceptionOrNull()).safeCodexFailureOrNull()

        assertTrue(result.isFailure)
        assertEquals(failure, result.exceptionOrNull())
        assertEquals("FileSystemException", failureDisclosure?.type)
        assertEquals("Codex invocation failure details omitted.", record?.errorMessage)
        assertFalse(noTradeEvent.payload.contains("auth-path-marker"))
        assertFalse(noTradeEvent.payload.contains("path-message-marker"))
        assertTrue(noTradeEvent.payload.contains("\"messageOmitted\":true"))
    }

    @Test
    fun codexInvokerCancellation_preservesOriginalAndOmitsSuppressedCleanupPathFromAudit() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = FakeMarketDataSource,
        )
        val cancellation = CancellationException("cancellation path-message-marker")
        val cleanupFailure = FileSystemException(
            "/temporary/codex-home/auth-path-marker.json",
            null,
            "cleanup path-message-marker",
        )
        cancellation.addSuppressed(cleanupFailure)
        val runner = OneShotLlmRunner(
            tradingRuntime = runtime,
            tradingConfig = TradingBotConfig(),
            llmInvoker = ThrowingLlmInvoker(cancellation),
            parentEnvironment = defaultParentEnvironment(),
            clock = fixedClock(),
            logger = {},
        )
        val request = defaultRequest().copy(
            invocationId = "codex-cancelled-run",
            proposerProvider = LlmProvider.CODEX,
        )

        val propagated = assertFailsWith<CancellationException> {
            runner.runOneShot(request)
        }
        val record = runtime.llmRunRepository.findByInvocationId("codex-cancelled-run").getOrThrow()
        val noTradeEvent = (runtime.commandEventLog as InMemoryCommandEventLog).events()
            .single { event -> event.eventType == CommandEventType.NO_TRADE_EXIT }

        assertSame(cancellation, propagated)
        assertTrue(propagated.suppressed.contains(cleanupFailure))
        assertEquals("CancellationException", propagated.safeCodexFailureOrNull()?.type)
        assertEquals("Codex invocation failure details omitted.", record?.errorMessage)
        assertFalse(noTradeEvent.payload.contains("auth-path-marker"))
        assertFalse(noTradeEvent.payload.contains("path-message-marker"))
        assertTrue(noTradeEvent.payload.contains("\"messageOmitted\":true"))
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
            dependencies = LlmDaemonSchedulerDependencies(
                riskStateRepository = runtime.riskStateRepository,
                commandEventLog = runtime.commandEventLog,
                launchReservationRepository = InMemoryLlmLaunchReservationRepository(runtime.riskStateRepository),
                openRiskReader = { Result.success(false) },
                tickerReader = {
                    Result.success(
                        LlmDaemonTickerSnapshot(
                            lastPriceJpy = BigDecimal("10000000"),
                            sourceTimestamp = fixedClock().instant(),
                        ),
                    )
                },
                positionsReader = LlmDaemonPositionsReader { Result.success(emptyList()) },
                entryFillReader = LlmDaemonEntryFillReader { Result.success(null) },
            ),
            runtime = LlmDaemonSchedulerRuntime(
                requestBase = defaultRequest(),
                launchOneShot = runner.asDaemonLauncher(),
                clock = fixedClock(),
                idGenerator = { UUID(0L, 42L) },
            ),
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

/**
 * request capture 用 runner fixture。
 *
 * @param invoker request capture 用 LLM invoker
 * @param runner test target runner
 */
private data class RequestCapturingRunnerFixture(
    val invoker: RequestCapturingLlmInvoker,
    val runner: OneShotLlmRunner,
)

private fun runnerFixture(
    config: TradingBotConfig = TradingBotConfig(),
    parentEnvironment: Map<String, String> = defaultParentEnvironment(),
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    runtimeTransform: (TradingRuntime) -> TradingRuntime = { runtime -> runtime },
    logger: (String) -> Unit = {},
    clock: Clock = fixedClock(),
    launchHandler: suspend (RenderedLlmCommand) -> ProcessRunResult,
): RunnerFixture {
    val baseRuntime = TradingRuntimeFactory.inMemory(
        clock = clock,
        marketDataSource = FakeMarketDataSource,
        tradingConfig = config,
    )
    val eventLog = baseRuntime.commandEventLog as InMemoryCommandEventLog
    val runtime = runtimeTransform(baseRuntime)
    val decisionRepository = runtime.decisionRepository as InMemoryDecisionRepository
    val processRunner = FakeProcessRunner(launchHandler)
    val runner = OneShotLlmRunner(
        tradingRuntime = runtime,
        tradingConfig = config,
        llmInvoker = ShellLlmInvoker(
            commandRenderer = DefaultLlmCommandRenderer(),
            processRunner = processRunner,
        ),
        runtimeConfigSnapshot = runtimeConfigSnapshot,
        parentEnvironment = parentEnvironment,
        clock = clock,
        logger = logger,
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
 * tool call count だけ失敗させる command event log。
 */
private class CountFailureCommandEventLog(
    private val delegate: CommandEventLog,
) : CommandEventLog {

    override suspend fun append(event: CommandEvent): Result<Unit> {
        return delegate.append(event)
    }

    override suspend fun countDistinctLlmLaunchesSince(since: Instant, excludedInvocationId: String?): Result<Int> {
        return delegate.countDistinctLlmLaunchesSince(since, excludedInvocationId)
    }

    override suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
        return Result.failure(IllegalStateException("tool call count failed"))
    }
}

private fun addLongRunnerFixture(
    config: TradingBotConfig,
    existingPosition: Position = addLongTargetPosition(),
    launchHandler: suspend (RenderedLlmCommand) -> ProcessRunResult,
): RunnerFixture {
    val existingStopOrder = linkedStopOrderFor(existingPosition)

    return runnerFixture(
        config = config,
        runtimeTransform = { runtime ->
            runtime.withPaperLedger(
                positions = listOf(existingPosition),
                openOrders = listOf(existingStopOrder),
                accountSnapshot = highEquityAccountSnapshotWithBtc(),
                config = config,
            )
        },
        launchHandler = launchHandler,
    )
}

private fun TradingRuntime.withHardHaltDrawdown(config: TradingBotConfig): TradingRuntime {
    val baseBroker = broker as PaperBroker
    val drawdownRiskStateRepository = InMemoryRiskStateRepository(
        clock = fixedClock(),
        initialState = RiskState(
            state = RiskHaltState.RUNNING,
            drawdownRatio = BigDecimal("-0.1500000000"),
            equityPeak = BigDecimal("100000.0000"),
            updatedAt = fixedInstant(),
        ),
    )
    val drawdownRiskStateCommandService = InMemoryRiskStateCommandService(
        riskStateRepository = drawdownRiskStateRepository,
        commandEventLog = commandEventLog,
        clock = fixedClock(),
    )
    val drawdownBroker = PaperBroker(
        ledgerRepository = baseBroker.ledgerRepository,
        riskStateRepository = drawdownRiskStateRepository,
        riskStateCommandService = drawdownRiskStateCommandService,
        decisionRepository = decisionRepository,
        falsificationFreshnessWindow = config.decisionProtocol.falsificationFreshnessWindow,
        safetyViolationRepository = safetyViolationRepository,
        safetyFloor = SafetyFloor(config.safetyFloor, fixedClock()),
        marketDataSource = baseBroker.marketDataSource,
        fillSimulator = baseBroker.fillSimulator,
        clock = fixedClock(),
    )
    val drawdownToolCallGuard = ToolCallGuard(
        riskStateRepository = drawdownRiskStateRepository,
        commandEventLog = commandEventLog,
        tradingLock = tradingLock,
        clock = fixedClock(),
    )

    return copy(
        riskStateRepository = drawdownRiskStateRepository,
        riskStateCommandService = drawdownRiskStateCommandService,
        broker = drawdownBroker,
        toolCallGuard = drawdownToolCallGuard,
    )
}

private fun TradingRuntime.withPaperLedger(
    positions: List<Position>,
    openOrders: List<Order> = emptyList(),
    accountSnapshot: AccountSnapshot = accountSnapshotWithBtc(),
    config: TradingBotConfig,
    clock: Clock = fixedClock(),
): TradingRuntime {
    val baseBroker = broker as PaperBroker
    val ledgerRepository = InMemoryPaperLedgerRepository(
        accountSnapshot = accountSnapshot,
        accountUpdatedAt = clock.instant(),
        positions = positions,
        openOrders = openOrders,
        fallbackSymbolRules = config.paperMarket.toSymbolRules(config.symbol),
        clock = clock,
    )
    val paperBroker = PaperBroker(
        ledgerRepository = ledgerRepository,
        riskStateRepository = riskStateRepository,
        riskStateCommandService = riskStateCommandService,
        decisionRepository = decisionRepository,
        falsificationFreshnessWindow = config.decisionProtocol.falsificationFreshnessWindow,
        safetyViolationRepository = safetyViolationRepository,
        safetyFloor = SafetyFloor(config.safetyFloor, clock),
        marketDataSource = baseBroker.marketDataSource,
        fillSimulator = baseBroker.fillSimulator,
        clock = clock,
    )

    return copy(
        equitySnapshotRepository = ledgerRepository.equitySnapshotRepository,
        broker = paperBroker,
    )
}

private fun TradingRuntime.withMarketDataSource(
    marketDataSource: MarketDataSource,
    config: TradingBotConfig,
    clock: Clock = fixedClock(),
): TradingRuntime {
    val baseBroker = broker as PaperBroker
    val paperBroker = PaperBroker(
        ledgerRepository = baseBroker.ledgerRepository,
        riskStateRepository = riskStateRepository,
        riskStateCommandService = riskStateCommandService,
        decisionRepository = decisionRepository,
        falsificationFreshnessWindow = config.decisionProtocol.falsificationFreshnessWindow,
        safetyViolationRepository = safetyViolationRepository,
        safetyFloor = SafetyFloor(config.safetyFloor, clock),
        marketDataSource = marketDataSource,
        fillSimulator = baseBroker.fillSimulator,
        clock = clock,
    )

    return copy(broker = paperBroker)
}

private fun accountSnapshotWithBtc(): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = "94000.00000000",
        initialCashJpy = "100000.00000000",
        btcQuantity = "0.010000000000",
        btcMarkPriceJpy = "10100000.00000000",
        totalEquityJpy = "195000.00000000",
        equityPeakJpy = "200000.00000000",
        drawdownRatio = "-0.0250000000",
    )
}

private fun highEquityAccountSnapshotWithBtc(): AccountSnapshot {
    return accountSnapshotWithBtc().copy(
        totalEquityJpy = "200000.00000000",
        equityPeakJpy = "200000.00000000",
        drawdownRatio = "0.0000000000",
    )
}

private fun requestCapturingRunnerFixture(
    proposerAction: DecisionAction = DecisionAction.ENTER,
    parentEnvironment: Map<String, String> = defaultParentEnvironment(),
): RequestCapturingRunnerFixture {
    val runtime = TradingRuntimeFactory.inMemory(
        clock = fixedClock(),
        marketDataSource = FakeMarketDataSource,
    )
    val decisionRepository = runtime.decisionRepository as InMemoryDecisionRepository
    val invoker = RequestCapturingLlmInvoker(
        repository = decisionRepository,
        proposerAction = proposerAction,
    )
    val runner = OneShotLlmRunner(
        tradingRuntime = runtime,
        tradingConfig = TradingBotConfig(),
        llmInvoker = invoker,
        parentEnvironment = parentEnvironment,
        clock = fixedClock(),
        logger = {},
    )

    return RequestCapturingRunnerFixture(
        invoker = invoker,
        runner = runner,
    )
}

/**
 * LLM 起動時に例外を投げる test double。
 *
 * @param failure LLM 起動時に投げる例外
 */
private class ThrowingLlmInvoker(
    private val failure: Throwable,
) : LlmInvoker {

    constructor(message: String) : this(IllegalStateException(message))

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        require(request.invocationId.isNotBlank()) {
            "invocationId must not be blank."
        }

        throw failure
    }
}

/**
 * LLM 起動結果として失敗を返す test double。
 *
 * @param failure 起動境界から返す失敗
 */
private class FailureResultLlmInvoker(
    private val failure: Throwable,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        require(request.invocationId.isNotBlank()) {
            "invocationId must not be blank."
        }

        return Result.failure(failure)
    }
}

/**
 * 起動 request を保存しつつ in-memory repository に判断結果を入れる test double。
 *
 * @param repository 判断保存先 repository
 * @param proposerAction Proposer が保存する action
 */
private class RequestCapturingLlmInvoker(
    private val repository: DecisionRepository,
    private val proposerAction: DecisionAction,
) : LlmInvoker {
    val requests = mutableListOf<LlmInvocationRequest>()

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        requests += request

        when (request.phase) {
            LlmInvocationPhase.PRE_FILTER -> Unit
            LlmInvocationPhase.PROPOSER -> submitDecisionFromRequest(repository, request, proposerAction).getOrThrow()
            LlmInvocationPhase.FALSIFIER -> {
                submitFalsificationFromRequest(repository, request, FalsificationVerdict.APPROVED).getOrThrow()
            }
            LlmInvocationPhase.REFLECTION -> Unit
        }

        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = cleanExit(),
                responseText = "",
            ),
        )
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

    override suspend fun findRunsStartedBetween(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): Result<List<LlmRunRecord>> {
        return Result.failure(IllegalStateException("llm run range read failed"))
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
    estimatedWinProbability: BigDecimal = BigDecimal("0.60"),
): ProcessRunResult {
    if (command.isProposerLaunch()) {
        submitDecision(
            repository = repository,
            command = command,
            action = DecisionAction.ENTER,
            estimatedWinProbability = estimatedWinProbability,
        ).getOrThrow()

        return cleanExit()
    }

    submitFalsification(repository, command, FalsificationVerdict.APPROVED).getOrThrow()

    return cleanExit()
}

private suspend fun handleAddLongAndApprovedFalsifier(
    repository: DecisionRepository,
    command: RenderedLlmCommand,
    parentTradePlanId: UUID,
): ProcessRunResult {
    if (command.isProposerLaunch()) {
        submitDecision(
            repository = repository,
            command = command,
            action = DecisionAction.ADD_LONG,
            entryIntent = entryIntentDraft(
                sizeBtc = BigDecimal("0.0010"),
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10600000"),
            ),
            tradePlan = tradePlanDraft(
                parentTradePlanId = parentTradePlanId,
                revisionCount = 1,
                targetPriceJpy = BigDecimal("10600000"),
            ),
        ).getOrThrow()

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
    estimatedWinProbability: BigDecimal = BigDecimal("0.60"),
    tradePlan: TradePlanDraft? = null,
    entryIntent: EntryIntentDraft? = null,
    closeRatio: BigDecimal? = null,
): Result<Unit> {
    return repository.submitDecision(
        decisionSubmission(
            command = command,
            action = action,
            estimatedWinProbability = estimatedWinProbability,
            tradePlan = tradePlan,
            entryIntent = entryIntent,
            closeRatio = closeRatio,
        ),
    ).fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { throwable -> Result.failure(throwable) },
    )
}

private suspend fun submitDecisionFromRequest(
    repository: DecisionRepository,
    request: LlmInvocationRequest,
    action: DecisionAction,
): Result<Unit> {
    return repository.submitDecision(decisionSubmissionFromRequest(request, action)).fold(
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

private suspend fun submitFalsificationFromRequest(
    repository: DecisionRepository,
    request: LlmInvocationRequest,
    verdict: FalsificationVerdict,
): Result<Unit> {
    return repository.submitFalsification(
        FalsificationSubmission(
            intentId = UUID.fromString(requireNotNull(request.environment[FUKUROU_FALSIFIER_INTENT_ID_ENV])),
            verdict = verdict,
            llmProvider = request.decisionRunContext.llmProvider,
            reasonJa = "runner test falsifier verdict",
        ),
    ).fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { throwable -> Result.failure(throwable) },
    )
}

private fun decisionSubmission(
    command: RenderedLlmCommand,
    action: DecisionAction,
    estimatedWinProbability: BigDecimal = BigDecimal("0.60"),
    tradePlan: TradePlanDraft? = null,
    entryIntent: EntryIntentDraft? = null,
    closeRatio: BigDecimal? = null,
): DecisionSubmission {
    val requiresEntryIntent = action.requiresEntryIntent()

    return DecisionSubmission(
        invocationId = command.environment[FUKUROU_INVOCATION_ID_ENV],
        llmProvider = command.environment[FUKUROU_LLM_PROVIDER_ENV],
        promptHash = command.environment[FUKUROU_PROMPT_HASH_ENV],
        systemPromptVersion = command.environment[FUKUROU_SYSTEM_PROMPT_VERSION_ENV],
        marketSnapshotId = command.environment[FUKUROU_MARKET_SNAPSHOT_ID_ENV],
        action = action,
        closeRatio = closeRatio,
        setupTags = if (requiresEntryIntent) listOf("runner-test") else emptyList(),
        estimatedWinProbability = estimatedWinProbability,
        expectedRMultiple = if (requiresEntryIntent) BigDecimal("2.0") else BigDecimal.ZERO,
        roundTripCostR = if (requiresEntryIntent) BigDecimal("0.1") else null,
        toolEvidenceIds = listOf("tool-1"),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = "runner test decision",
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = entryIntent ?: if (requiresEntryIntent) entryIntentDraft() else null,
        tradePlan = tradePlan ?: if (requiresEntryIntent) tradePlanDraft() else null,
    )
}

private fun decisionSubmissionFromRequest(request: LlmInvocationRequest, action: DecisionAction): DecisionSubmission {
    val context = request.decisionRunContext
    val requiresEntryIntent = action.requiresEntryIntent()

    return DecisionSubmission(
        invocationId = context.decisionRunId,
        llmProvider = context.llmProvider,
        promptHash = context.promptHash,
        systemPromptVersion = context.systemPromptVersion,
        marketSnapshotId = context.marketSnapshotId,
        action = action,
        setupTags = if (requiresEntryIntent) listOf("runner-test") else emptyList(),
        estimatedWinProbability = BigDecimal("0.60"),
        expectedRMultiple = if (requiresEntryIntent) BigDecimal("2.0") else BigDecimal.ZERO,
        roundTripCostR = if (requiresEntryIntent) BigDecimal("0.1") else null,
        toolEvidenceIds = listOf("tool-1"),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = "runner test decision",
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = if (requiresEntryIntent) entryIntentDraft() else null,
        tradePlan = if (requiresEntryIntent) tradePlanDraft() else null,
    )
}

private fun entryIntentDraft(
    orderType: OrderType = OrderType.MARKET,
    priceJpy: BigDecimal? = null,
    sizeBtc: BigDecimal = BigDecimal("0.0050"),
    protectiveStopPriceJpy: BigDecimal = BigDecimal("9700000"),
    takeProfitPriceJpy: BigDecimal? = BigDecimal("10500000"),
): EntryIntentDraft {
    return EntryIntentDraft(
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = orderType,
        sizeBtc = sizeBtc,
        priceJpy = priceJpy,
        protectiveStopPriceJpy = protectiveStopPriceJpy,
        takeProfitPriceJpy = takeProfitPriceJpy,
    )
}

private fun tradePlanDraft(
    parentTradePlanId: UUID? = null,
    revisionCount: Int = 0,
    targetPriceJpy: BigDecimal? = BigDecimal("10500000"),
): TradePlanDraft {
    return TradePlanDraft(
        parentTradePlanId = parentTradePlanId,
        revisionCount = revisionCount,
        symbol = TradingSymbol.BTC,
        thesisJa = "runner test thesis",
        invalidationConditionsJa = listOf("runner test invalidation"),
        targetPriceJpy = targetPriceJpy,
        timeStopAt = null,
        setupTags = listOf("runner-test"),
    )
}

private fun openPosition(
    positionId: UUID = UUID.randomUUID(),
    tradeGroupId: UUID = UUID.randomUUID(),
    sizeBtc: String = "0.0050",
    currentPriceJpy: String = "10000000",
    currentStopLossJpy: String? = "9700000",
    currentTakeProfitJpy: String? = "10500000",
    unrealizedPnlJpy: String = "0",
    unrealizedR: String = "0",
): Position {
    return Position(
        positionId = positionId.toString(),
        tradeGroupId = tradeGroupId.toString(),
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = fixedInstant().toString(),
        closedAt = null,
        sizeBtc = sizeBtc,
        averageEntryPriceJpy = "10000000",
        currentPriceJpy = currentPriceJpy,
        currentStopLossJpy = currentStopLossJpy,
        currentTakeProfitJpy = currentTakeProfitJpy,
        unrealizedPnlJpy = unrealizedPnlJpy,
        unrealizedR = unrealizedR,
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "10000000",
        lowestPriceSinceEntryJpy = "10000000",
    )
}

private fun addLongTargetPosition(): Position {
    return openPosition(
        positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        tradeGroupId = UUID.fromString("10000000-0000-0000-0000-000000000001"),
        sizeBtc = "0.010000000000",
        currentPriceJpy = "10100000.00000000",
        currentStopLossJpy = "9800000.00000000",
        currentTakeProfitJpy = "10500000.00000000",
        unrealizedPnlJpy = "1000.00000000",
        unrealizedR = "1.200000",
    )
}

private fun linkedStopOrderFor(position: Position): Order {
    return Order(
        orderId = "20000000-0000-0000-0000-000000000004",
        intentId = null,
        positionId = position.positionId,
        tradeGroupId = position.tradeGroupId,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.SELL,
        orderType = OrderType.STOP,
        status = OrderStatus.OPEN,
        sizeBtc = position.sizeBtc,
        limitPriceJpy = null,
        triggerPriceJpy = position.currentStopLossJpy,
        protectiveStopPriceJpy = null,
        takeProfitPriceJpy = null,
        reasonJa = "runner add long test stop",
        clientRequestId = null,
        createdAt = fixedInstant().toString(),
        updatedAt = fixedInstant().toString(),
    )
}

private suspend fun seedApprovedEntry(
    fixture: RunnerFixture,
    entryIntent: EntryIntentDraft = entryIntentDraft(),
    tradeGroupId: UUID? = null,
): DecisionSubmissionResult {
    val decision = fixture.decisionRepository.submitDecision(
        seedEntryDecisionSubmission(entryIntent),
    ).getOrThrow()
    val intent = requireNotNull(decision.tradeIntent)
    fixture.decisionRepository.submitFalsification(
        FalsificationSubmission(
            intentId = intent.intentId,
            verdict = FalsificationVerdict.APPROVED,
            llmProvider = "codex",
            reasonJa = "runner seed falsifier verdict",
        ),
    ).getOrThrow()

    fixture.runtime.broker.placeOrder(intent.toSeedPlaceOrderCommand(tradeGroupId)).getOrThrow()

    return decision
}

private fun seedEntryDecisionSubmission(entryIntent: EntryIntentDraft): DecisionSubmission {
    return DecisionSubmission(
        invocationId = "seed-entry-${UUID.randomUUID()}",
        llmProvider = "seed",
        promptHash = "seed-prompt-hash",
        systemPromptVersion = SystemPromptV1.VERSION,
        marketSnapshotId = "seed-market-snapshot",
        action = DecisionAction.ENTER,
        setupTags = listOf("runner-seed"),
        estimatedWinProbability = BigDecimal("0.60"),
        expectedRMultiple = BigDecimal("2.0"),
        roundTripCostR = BigDecimal("0.1"),
        toolEvidenceIds = listOf("seed-tool"),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = "runner seed entry",
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = entryIntent,
        tradePlan = tradePlanDraft(),
    )
}

private fun TradeIntentRecord.toSeedPlaceOrderCommand(tradeGroupId: UUID? = null): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        intentId = intentId,
        symbol = draft.symbol,
        side = draft.side,
        orderType = draft.orderType,
        sizeBtc = draft.sizeBtc,
        priceJpy = draft.priceJpy,
        tradeGroupId = tradeGroupId,
        protectiveStopPriceJpy = draft.protectiveStopPriceJpy,
        takeProfitPriceJpy = draft.takeProfitPriceJpy,
        estimatedWinProbability = estimatedWinProbability,
        reasonJa = "runner seed paper entry",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun RenderedLlmCommand.isProposerLaunch(): Boolean {
    return environment[FUKUROU_LLM_PROVIDER_ENV] == "claude"
}

private fun RenderedLlmCommand.isFalsifierLaunch(): Boolean {
    return environment[FUKUROU_FALSIFIER_INTENT_ID_ENV] != null
}

private fun proposerToolCallCompletedEvent(command: RenderedLlmCommand): CommandEvent {
    return CommandEvent(
        decisionRunContext = DecisionRunContext(
            decisionRunId = command.environment.getValue(FUKUROU_INVOCATION_ID_ENV),
            llmProvider = command.environment.getValue(FUKUROU_LLM_PROVIDER_ENV),
            promptHash = command.environment.getValue(FUKUROU_PROMPT_HASH_ENV),
            systemPromptVersion = command.environment.getValue(FUKUROU_SYSTEM_PROMPT_VERSION_ENV),
            marketSnapshotId = command.environment.getValue(FUKUROU_MARKET_SNAPSHOT_ID_ENV),
        ),
        toolName = "get_ticker",
        toolCallId = "tool-call-1",
        clientRequestId = "client-request-1",
        eventType = CommandEventType.TOOL_CALL_COMPLETED,
        payload = "{}",
        occurredAt = fixedInstant(),
    )
}

private fun RenderedLlmCommand.claudeMcpConfigContent(): String {
    val configPath = Path.of(args[args.indexOf("--mcp-config") + 1])

    return Files.readString(configPath)
}

private fun RenderedLlmCommand.codexConfigContent(): String {
    val codexHome = Path.of(requireNotNull(environment[CODEX_HOME_ENV]))

    return Files.readString(codexHome.resolve("config.toml"))
}

private fun cleanExit(stdout: String = "", stderr: String = ""): ProcessRunResult {
    return ProcessRunResult(
        status = ProcessRunStatus.EXITED,
        exitCode = 0,
        stdout = stdout,
        stderr = stderr,
    )
}

private fun nonZeroExit(stdout: String = "", stderr: String = "failed"): ProcessRunResult {
    return ProcessRunResult(
        status = ProcessRunStatus.EXITED,
        exitCode = 1,
        stdout = stdout,
        stderr = stderr,
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

private fun String.isAuthFailureRunbookLog(): Boolean {
    val hasAuthFailureMessage = contains("LLM CLI authentication failure suspected.")
    val hasCodexRunbook = contains("codex login --device-auth")

    return hasAuthFailureMessage && hasCodexRunbook
}

private fun List<CommandEvent>.singleRunnerPhaseDetails(phase: String): JsonObject {
    val event = single { event -> event.isRunnerPhaseCompleted(phase) }
    val payload = event.payloadJsonObject()

    assertEquals(phase, payload.stringValue("phase"))

    return payload.getValue("details").jsonObject
}

private fun List<CommandEvent>.singleLifecyclePayload(phase: String): JsonObject {
    val event = single { event ->
        val lifecycleCompleted = event.eventType == CommandEventType.DECISION_LIFECYCLE_COMPLETED
        val phaseMatched = event.payload.contains("\"phase\":\"$phase\"")

        lifecycleCompleted && phaseMatched
    }
    val payload = event.payloadJsonObject()

    assertEquals(phase, payload.stringValue("phase"))

    return payload
}

private fun List<CommandEvent>.singleNoTradePayload(): JsonObject {
    val event = single { event -> event.eventType == CommandEventType.NO_TRADE_EXIT }

    return event.payloadJsonObject()
}

private fun CommandEvent.isRunnerPhaseCompleted(phase: String): Boolean {
    val phaseCompleted = eventType == CommandEventType.RUNNER_PHASE_COMPLETED
    val phaseMatched = payload.contains("\"phase\":\"$phase\"")

    return phaseCompleted && phaseMatched
}

private fun CommandEvent.payloadJsonObject(): JsonObject {
    return Json.parseToJsonElement(payload).jsonObject
}

private fun JsonObject.stringValue(key: String): String {
    return getValue(key).jsonPrimitive.content
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

/**
 * crossing LIMIT が FAK 部分約定相当になる runner test 用 market data source。
 */
private object CrossingLimitPartialOrderbookMarketDataSource : MarketDataSource by FakeMarketDataSource {
    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(
            Orderbook(
                symbol = symbol.apiSymbol,
                bids = listOf(OrderbookLevel(price = "9990000", size = "0.0100")),
                asks = listOf(OrderbookLevel(price = "10000000", size = "0.0020")),
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

/**
 * runner test 内で時刻を明示的に進める Clock。
 *
 * @param currentInstant 現在時刻
 * @param currentZone clock zone
 */
private class MutableTestClock(
    private var currentInstant: Instant,
    private val currentZone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    override fun getZone(): ZoneId {
        return currentZone
    }

    override fun withZone(zone: ZoneId): Clock {
        return MutableTestClock(currentInstant, zone)
    }

    override fun instant(): Instant {
        return currentInstant
    }

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}

/**
 * instant() 呼び出しごとに一定量進む Clock。
 *
 * @param currentInstant 現在時刻
 * @param tick 1 回の instant() で進める時間
 * @param currentZone clock zone
 */
private class TickingTestClock(
    private var currentInstant: Instant,
    private val tick: Duration,
    private val currentZone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    override fun getZone(): ZoneId {
        return currentZone
    }

    override fun withZone(zone: ZoneId): Clock {
        return TickingTestClock(
            currentInstant = currentInstant,
            tick = tick,
            currentZone = zone,
        )
    }

    override fun instant(): Instant {
        val instant = currentInstant
        currentInstant = currentInstant.plus(tick)

        return instant
    }
}
