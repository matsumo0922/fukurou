@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import me.matsumo.fukurou.trading.audit.LlmInputManifestRepository
import me.matsumo.fukurou.trading.audit.LlmPhaseInputManifest
import me.matsumo.fukurou.trading.audit.LlmPhaseObservation
import me.matsumo.fukurou.trading.audit.LlmRunInputManifest
import me.matsumo.fukurou.trading.audit.StandardMaterialSnapshotException
import me.matsumo.fukurou.trading.audit.StandardMaterialSnapshotStage
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.PaperBroker
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.config.DecisionProtocolConfig
import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonEntryFillReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonPositionsReader
import me.matsumo.fukurou.trading.daemon.LlmDaemonScheduler
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerDependencies
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerRuntime
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickResult
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickerSnapshot
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimOutcome
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
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
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialProjectionContext
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateRepository
import me.matsumo.fukurou.trading.decision.identity.DecisionIdentityGenerator
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
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunRecord
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientRole
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicRequestCorrelation
import me.matsumo.fukurou.trading.invoker.CODEX_HOME_ENV
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.DefaultLlmOutputParser
import me.matsumo.fukurou.trading.invoker.LlmCliVersionProbe
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmOutputParser
import me.matsumo.fukurou.trading.invoker.LlmProcessTreeTerminationRegistry
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.ParsedLlmOutput
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.invoker.ProcessRunner
import me.matsumo.fukurou.trading.invoker.ProcessStartAwareRunner
import me.matsumo.fukurou.trading.invoker.ProcessTreeTerminationProof
import me.matsumo.fukurou.trading.invoker.RenderedLlmCommand
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.safeCodexFailureOrNull
import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskHaltState
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OneShotLlmRunner の DB 正本 contract を検証するテスト。
 */
class OneShotLlmRunnerTest {

    @Test
    fun parallelReplay_hasOneClaimWinnerAndAtMostOneChild() = runBlocking {
        val fixture = runnerFixture { cleanExit() }
        val invocationId = "parallel-replay"
        val request = defaultRequest().copy(
            invocationId = invocationId,
            triggerKind = LlmDaemonTriggerKind.MANUAL,
        )
        fixture.runtime.launchReservationRepository.tryReserve(
            LlmLaunchReservationRequest(
                invocationId = invocationId,
                triggerKind = LlmDaemonTriggerKind.MANUAL,
                triggerKey = "test:$invocationId",
                reservedAt = fixedInstant(),
                runnerConfig = LlmRunnerConfig(),
                hourlyWindow = Duration.ofHours(1),
                dailyWindow = Duration.ofHours(24),
                activeReservationStaleAfter = Duration.ofMinutes(30),
            ),
        ).getOrThrow()

        val results = coroutineScope {
            listOf(
                async { fixture.runner.runOneShot(request) },
                async { fixture.runner.runOneShot(request) },
            ).map { it.await() }
        }

        assertEquals(1, results.count(Result<OneShotRunnerResult>::isSuccess))
        assertEquals(1, results.count { it.exceptionOrNull()?.message == "launch_reservation_already_claimed" })
        assertEquals(1, fixture.processRunner.launches.size)

        val terminalReplay = fixture.runner.runOneShot(request)
        assertEquals("launch_reservation_terminal", terminalReplay.exceptionOrNull()?.message)
        assertEquals(1, fixture.processRunner.launches.size)
    }

    @Test
    fun reservationMissing_failsBeforeChildProcess() = runBlocking {
        val fixture = runnerFixture { cleanExit() }
        val result = fixture.runner.runOneShot(
            defaultRequest().copy(invocationId = "missing-reservation", triggerKind = LlmDaemonTriggerKind.MANUAL),
        )

        assertEquals(LAUNCH_RESERVATION_MISSING, result.exceptionOrNull()?.message)
        assertEquals(0, fixture.processRunner.launches.size)
    }

    @Test
    fun reservationTriggerMismatch_failsBeforeChildProcess() = runBlocking {
        val fixture = runnerFixture { cleanExit() }
        fixture.runtime.launchReservationRepository.tryReserve(
            LlmLaunchReservationRequest(
                invocationId = "mismatch",
                triggerKind = LlmDaemonTriggerKind.MANUAL,
                triggerKey = "test:mismatch",
                reservedAt = fixedInstant(),
                runnerConfig = LlmRunnerConfig(),
                hourlyWindow = Duration.ofHours(1),
                dailyWindow = Duration.ofHours(24),
                activeReservationStaleAfter = Duration.ofMinutes(30),
            ),
        ).getOrThrow()
        val result = fixture.runner.runOneShot(
            defaultRequest().copy(invocationId = "mismatch", triggerKind = LlmDaemonTriggerKind.ENTRY_FILL),
        )

        assertEquals(LAUNCH_RESERVATION_TRIGGER_MISMATCH, result.exceptionOrNull()?.message)
        assertEquals(0, fixture.processRunner.launches.size)
    }

    @Test
    fun runnerPreview_rateLimitExhaustionFailsClosedBeforePlaceOrder() = runBlocking {
        val marketDataSource = RateLimitExhaustedOrderbookMarketDataSource()
        val fixture = runnerFixture(marketDataSource = marketDataSource) { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
        val correlation = assertNotNull(marketDataSource.correlation)
        val broker = fixture.runtime.broker as PaperBroker

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(GmoPublicClientRole.RUNNER, correlation.clientRole)
        assertEquals(result.invocationId, correlation.decisionRunContext.decisionRunId)
        assertTrue(correlation.toolCallId?.isNotBlank() == true)
        assertEquals(1, marketDataSource.orderbookAttemptCount)
        assertEquals(0, fixture.runtime.broker.getOpenOrders().getOrThrow().size)
        assertEquals(0, fixture.runtime.broker.getPositions().getOrThrow().size)
        assertEquals(0, broker.ledgerRepository.getExecutions().getOrThrow().size)
        assertEquals(null, result.tradeResult)
    }

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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
        val decisions = fixture.decisionRepository.snapshots.decisions()

        assertEquals(
            OneShotRunnerStatus.NO_TRADE_DECISION,
            result.status,
            fixture.eventLog.events().joinToString { event -> event.payload },
        )
        assertEquals(me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause.NO_TRADE, result.terminalCause)
        assertEquals(1, fixture.processRunner.launches.size)
        assertEquals(1, decisions.size)
        assertEquals(DecisionAction.NO_TRADE, decisions.single().submission.action)
    }

    @Test
    fun standardSnapshotTerminalPriorityPreservesSafetyAndExecutionBeforeRunnerFailure() {
        val noTrade = OneShotRunnerResult(
            invocationId = "no-trade",
            status = OneShotRunnerStatus.NO_TRADE_AUDITED,
            decision = null,
            intent = null,
            tradeResult = null,
            terminalCause = LlmRunTerminalCause.NO_TRADE,
        )
        val safetyDenied = noTrade.copy(terminalCause = LlmRunTerminalCause.SAFETY_DENIED)
        val executed = noTrade.copy(
            status = OneShotRunnerStatus.PAPER_EXIT_EXECUTED,
            terminalCause = LlmRunTerminalCause.NORMAL_COMPLETION,
        )

        assertEquals(
            LlmRunTerminalCause.RUNNER_FAILED,
            noTrade.withStandardSnapshotFailureTerminalCause().terminalCause,
        )
        assertEquals(
            LlmRunTerminalCause.SAFETY_DENIED,
            safetyDenied.withStandardSnapshotFailureTerminalCause().terminalCause,
        )
        assertEquals(
            LlmRunTerminalCause.NORMAL_COMPLETION,
            executed.withStandardSnapshotFailureTerminalCause().terminalCause,
        )
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

        val result = fixture.runOneShot(request).getOrThrow()
        val repository = fixture.runtime.llmRunRepository as InMemoryLlmRunRepository
        val record = repository.findByInvocationId(result.invocationId).getOrThrow()

        assertEquals(
            listOf(LLM_RUN_STATUS_RUNNING, OneShotRunnerStatus.NO_TRADE_DECISION.name),
            repository.statusHistory(result.invocationId),
        )
        assertEquals(OneShotRunnerStatus.NO_TRADE_DECISION.name, record?.status)
        assertEquals(LlmDaemonTriggerKind.MANUAL, record?.triggerKind)
        assertTrue(record?.finishedAt != null)
    }

    @Test
    fun materialManifestCapturesExactMarketFactsBeforeThesisScopedProjection() = runBlocking {
        val fixture = runnerFixture(marketDataSource = MaterialManifestMarketDataSource) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }
            cleanExit()
        }
        val request = defaultRequest().copy(invocationId = "material-facts-run")

        fixture.runOneShot(request).getOrThrow()

        val manifest = assertNotNull(
            fixture.runtime.decisionMaterialStateRepository.find("material-facts-run").getOrThrow(),
        )
        assertNotNull(manifest.bestBidJpy)
        assertNotNull(manifest.bestAskJpy)
        assertNotNull(manifest.lastPriceJpy)
        assertNotNull(manifest.sourceTimestamp)
        assertNotNull(manifest.atr14FiveMinutesJpy)
        assertEquals(2, manifest.schemaVersion)
        val marketFeatureBundle = assertNotNull(manifest.marketFeatureBundle)
        assertEquals(
            "GMO_PUBLIC_ORDERBOOK_TOP10",
            marketFeatureBundle.orderbookSummary?.metadata?.provenance,
        )
        assertTrue(manifest.canonicalContentHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(manifest.snapshotContentHash.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(manifest.canonicalContentHash, manifest.snapshotContentHash)
        assertTrue(manifest.materialProjection.isEmpty())
        assertFalse(manifest.materialProjection.contains("sourceTimestamp"))
        val runManifest = assertNotNull(
            fixture.runtime.llmInputManifestRepository.findRun("material-facts-run").getOrThrow(),
        )
        assertEquals(2, runManifest.schemaVersion)
        assertEquals(manifest.snapshotContentHash, runManifest.materialContentHash)
    }

    @Test
    fun preProposerManifestIgnoresSymbolOnlyEpisodeContext() = runBlocking {
        val config = TradingBotConfig(daemon = LlmDaemonConfig(priceMoveThresholdRatio = BigDecimal("0.50")))
        val fixture = runnerFixture(
            config = config,
            marketDataSource = MaterialManifestMarketDataSource,
            runtimeTransform = { runtime ->
                val delegate = runtime.decisionMaterialStateRepository
                runtime.copy(
                    decisionMaterialStateRepository = object : DecisionMaterialStateRepository by delegate {
                        override suspend fun findOpenEpisodeContext(symbol: String) = Result.success(
                            DecisionMaterialProjectionContext(
                                anchorPriceJpy = BigDecimal("9800000"),
                                priceMoveThresholdRatio = BigDecimal("0.02"),
                                invalidationPredicates = emptyList(),
                            ),
                        )
                    },
                )
            },
        ) { command ->
            if (command.isProposerLaunch()) submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            cleanExit()
        }

        fixture.runOneShot(defaultRequest().copy(invocationId = "fixed-threshold-run")).getOrThrow()

        val manifest = assertNotNull(
            fixture.runtime.decisionMaterialStateRepository.find("fixed-threshold-run").getOrThrow(),
        )
        assertEquals(BigDecimal("0.50"), manifest.priceMoveThresholdRatio)
        assertTrue(manifest.materialProjection.isEmpty())
    }

    @Test
    fun materialManifestPersistenceFailureRemainsTypedCoverageMiss() = runBlocking {
        val fixture = runnerFixture(
            runtimeTransform = { runtime ->
                val delegate = runtime.llmInputManifestRepository
                runtime.copy(
                    llmInputManifestRepository = object : LlmInputManifestRepository by delegate {
                        override suspend fun appendRunWithMaterial(
                            materialManifest: DecisionMaterialStateManifest,
                            runManifest: LlmRunInputManifest,
                        ): Result<Unit> {
                            return Result.failure(
                                StandardMaterialSnapshotException(
                                    StandardMaterialSnapshotStage.PERSISTENCE,
                                    IllegalStateException("manifest unavailable"),
                                ),
                            )
                        }
                    },
                )
            },
        ) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }
            cleanExit()
        }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(LlmRunTerminalCause.RUNNER_FAILED, result.terminalCause)
        assertStandardSnapshotFailureEvent(fixture, StandardMaterialSnapshotStage.PERSISTENCE)
    }

    @Test
    fun repositoryHashFailureKeepsHashSerializationAttribution() = runBlocking {
        val fixture = runnerFixture(
            marketDataSource = MaterialManifestMarketDataSource,
            runtimeTransform = { runtime ->
                val delegate = runtime.llmInputManifestRepository
                runtime.copy(
                    llmInputManifestRepository = object : LlmInputManifestRepository by delegate {
                        override suspend fun appendRunWithMaterial(
                            materialManifest: DecisionMaterialStateManifest,
                            runManifest: LlmRunInputManifest,
                        ): Result<Unit> = Result.failure(
                            StandardMaterialSnapshotException(
                                StandardMaterialSnapshotStage.HASH_SERIALIZATION,
                                IllegalArgumentException("hash fixture detail"),
                            ),
                        )
                    },
                )
            },
        ) { cleanExit() }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(LlmRunTerminalCause.RUNNER_FAILED, result.terminalCause)
        assertStandardSnapshotFailureEvent(fixture, StandardMaterialSnapshotStage.HASH_SERIALIZATION)
        assertFalse(fixture.eventLog.events().any { event -> event.payload.contains("hash fixture detail") })
    }

    @Test
    fun standardMarketFailure_launchesRiskReductionOnlyWithoutEntryCapability() = runBlocking {
        val fixture = runnerFixture(marketDataSource = FailingMaterialMarketDataSource) { command ->
            submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            cleanExit()
        }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
        val launch = fixture.processRunner.launches.single()
        val manifest = launch.mcpManifestContent()

        assertEquals(
            OneShotRunnerStatus.NO_TRADE_DECISION,
            result.status,
            fixture.eventLog.events().joinToString { event -> event.payload },
        )
        assertTrue(manifest.contains("RISK_REDUCTION_ONLY"))
        assertTrue(manifest.contains("submit_decision"))
        assertFalse(manifest.contains("get_ticker"))
        assertFalse(manifest.contains("preview_order"))
        assertEquals(LlmRunTerminalCause.RUNNER_FAILED, result.terminalCause)
        assertStandardSnapshotFailureEvent(fixture, StandardMaterialSnapshotStage.CAPTURE)
    }

    @Test
    fun standardSnapshotCancellation_doesNotCreateStageEvidenceOrMissingReason() = runBlocking {
        val marketDataSource = BlockingSnapshotMarketDataSource()
        val fixture = runnerFixture(marketDataSource = marketDataSource) { awaitCancellation() }
        val runner = async {
            fixture.runOneShot(defaultRequest().copy(invocationId = "snapshot-cancelled"))
        }

        marketDataSource.tickerEntered.await()
        runner.cancel()

        assertFailsWith<CancellationException> { runner.await() }
        val events = fixture.eventLog.events()
        assertFalse(events.any { event -> event.isRunnerPhaseCompleted("standard_material_snapshot") })
        assertFalse(events.any { event -> event.payload.contains("STANDARD_CONTEXT") })
        assertNull(fixture.runtime.decisionMaterialStateRepository.find("snapshot-cancelled").getOrThrow())
    }

    @Test
    fun standardSnapshotClaimLoss_doesNotCreateStageEvidenceOrMissingReason() = runBlocking {
        val marketDataSource = ClaimLossSnapshotMarketDataSource()
        val fixture = runnerFixture(
            marketDataSource = marketDataSource,
            runtimeTransform = { runtime ->
                runtime.copy(
                    launchReservationRepository = SnapshotClaimLossRepository(
                        delegate = runtime.launchReservationRepository,
                        captureCompleted = { marketDataSource.captureCompleted },
                    ),
                )
            },
        ) { awaitCancellation() }

        val result = fixture.runOneShot(defaultRequest().copy(invocationId = "snapshot-claim-lost"))

        assertTrue(result.isFailure)
        val events = fixture.eventLog.events()
        assertFalse(events.any { event -> event.isRunnerPhaseCompleted("standard_material_snapshot") })
        assertFalse(events.any { event -> event.payload.contains("STANDARD_CONTEXT") })
        assertNull(fixture.runtime.decisionMaterialStateRepository.find("snapshot-claim-lost").getOrThrow())
    }

    @Test
    fun standardSnapshotAdmissionQueryFailure_doesNotBecomeCaptureFailure() = runBlocking {
        val marketDataSource = ClaimLossSnapshotMarketDataSource()
        val fixture = runnerFixture(
            marketDataSource = marketDataSource,
            runtimeTransform = { runtime ->
                runtime.copy(
                    launchReservationRepository = SnapshotAdmissionQueryFailureRepository(
                        delegate = runtime.launchReservationRepository,
                        captureCompleted = { marketDataSource.captureCompleted },
                    ),
                )
            },
        ) { awaitCancellation() }

        val result = fixture.runOneShot(defaultRequest().copy(invocationId = "snapshot-admission-query-failed"))

        assertTrue(result.isFailure)
        val events = fixture.eventLog.events()
        assertFalse(events.any { event -> event.isRunnerPhaseCompleted("standard_material_snapshot") })
        assertFalse(events.any { event -> event.payload.contains("STANDARD_CONTEXT") })
        assertNull(
            fixture.runtime.decisionMaterialStateRepository
                .find("snapshot-admission-query-failed")
                .getOrThrow(),
        )
    }

    @Test
    fun expiredSnapshotClaim_doesNotCreateStageEvidenceOrMissingReason() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(
                perRunTimeout = Duration.ofSeconds(1),
                entryFillReservePerHour = 0,
                entryFillReservePerDay = 0,
                stopProximityReservePerHour = 0,
                stopProximityReservePerDay = 0,
            ),
        )
        val policy = OneShotExecutionPolicy.from(config.runner)
        val fixture = runnerFixture(
            config = config,
            runtimeTransform = { runtime ->
                runtime.copy(
                    launchReservationRepository = ExpiredSnapshotClaimRepository(
                        delegate = runtime.launchReservationRepository,
                        hardTimeout = policy.hardTimeout,
                    ),
                )
            },
        ) { awaitCancellation() }

        val result = fixture.runOneShot(defaultRequest().copy(invocationId = "snapshot-deadline"))

        assertTrue(result.isFailure)
        val events = fixture.eventLog.events()
        assertFalse(events.any { event -> event.isRunnerPhaseCompleted("standard_material_snapshot") })
        assertFalse(events.any { event -> event.payload.contains("STANDARD_CONTEXT") })
        assertNull(fixture.runtime.decisionMaterialStateRepository.find("snapshot-deadline").getOrThrow())
    }

    @Test
    fun standardSnapshotFailureDoesNotIncreaseHourlyOrDailyQuota() = runBlocking {
        listOf(
            Triple(
                "hourly",
                LlmRunnerConfig(
                    maxInvocationsPerHour = 2,
                    maxInvocationsPerDay = 3,
                    entryFillReservePerHour = 0,
                    entryFillReservePerDay = 0,
                    stopProximityReservePerHour = 0,
                    stopProximityReservePerDay = 0,
                ),
                LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR,
            ),
            Triple(
                "daily",
                LlmRunnerConfig(
                    maxInvocationsPerHour = 3,
                    maxInvocationsPerDay = 2,
                    entryFillReservePerHour = 0,
                    entryFillReservePerDay = 0,
                    stopProximityReservePerHour = 0,
                    stopProximityReservePerDay = 0,
                ),
                LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY,
            ),
        ).forEach { (quotaName, config, expectedRejection) ->
            val fixture = runnerFixture(
                config = TradingBotConfig(runner = config),
                marketDataSource = FailingMaterialMarketDataSource,
            ) { command ->
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
                cleanExit()
            }
            val firstInvocationId = "snapshot-quota-$quotaName-1"
            val reservationRequest = { invocationId: String ->
                LlmLaunchReservationRequest(
                    invocationId = invocationId,
                    triggerKind = LlmDaemonTriggerKind.MANUAL,
                    triggerKey = "test:$invocationId",
                    reservedAt = fixedInstant(),
                    runnerConfig = config,
                    hourlyWindow = Duration.ofHours(1),
                    dailyWindow = Duration.ofDays(1),
                    activeReservationStaleAfter = Duration.ofMinutes(30),
                )
            }

            assertIs<LlmLaunchReservationOutcome.Reserved>(
                fixture.runtime.launchReservationRepository.tryReserve(reservationRequest(firstInvocationId)).getOrThrow(),
            )
            val result = fixture.runner.runOneShot(
                defaultRequest().copy(
                    invocationId = firstInvocationId,
                    triggerKind = LlmDaemonTriggerKind.MANUAL,
                ),
            )
            assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertStandardSnapshotFailureEvent(fixture, StandardMaterialSnapshotStage.CAPTURE)

            val secondInvocationId = "snapshot-quota-$quotaName-2"
            assertIs<LlmLaunchReservationOutcome.Reserved>(
                fixture.runtime.launchReservationRepository.tryReserve(reservationRequest(secondInvocationId)).getOrThrow(),
            )
            fixture.runtime.launchReservationRepository.finish(
                LlmLaunchReservationFinish(
                    invocationId = secondInvocationId,
                    status = LlmLaunchReservationStatus.FINISHED,
                    reason = "test",
                    finishedAt = fixedInstant(),
                ),
            ).getOrThrow()

            val third = fixture.runtime.launchReservationRepository.tryReserve(
                reservationRequest("snapshot-quota-$quotaName-3"),
            ).getOrThrow()
            assertEquals(expectedRejection, assertIs<LlmLaunchReservationOutcome.Rejected>(third).reason)
        }
    }

    @Test
    fun standardPhaseProbeFailure_reusesPersistedMaterialAndLaunchesRiskReductionOnly() = runBlocking {
        var probeCount = 0
        val fixture = runnerFixture(
            marketDataSource = MaterialManifestMarketDataSource,
            cliVersionProbe = {
                probeCount += 1
                if (probeCount == 1) {
                    Result.failure(IllegalStateException("probe unavailable"))
                } else {
                    Result.success("fixture-cli 1.0")
                }
            },
        ) { command ->
            submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            cleanExit()
        }

        val result = fixture.runOneShot(defaultRequest().copy(invocationId = "probe-fallback-run")).getOrThrow()
        val material = fixture.runtime.decisionMaterialStateRepository.find("probe-fallback-run").getOrThrow()

        assertEquals(
            OneShotRunnerStatus.NO_TRADE_DECISION,
            result.status,
            fixture.eventLog.events().joinToString { event -> event.payload },
        )
        assertEquals(2, probeCount)
        assertEquals(1, fixture.processRunner.launches.size)
        assertTrue(fixture.processRunner.launches.single().mcpManifestContent().contains("RISK_REDUCTION_ONLY"))
        assertNotNull(material?.marketFeatureBundle?.ticker)
        Unit
    }

    @Test
    fun standardManifestLookupAndAppendFailures_reuseRunBundleForRiskReductionOnly() = runBlocking {
        val transforms: List<(TradingRuntime) -> TradingRuntime> = listOf(
            { runtime ->
                val delegate = runtime.llmInputManifestRepository
                runtime.copy(
                    llmInputManifestRepository = object : LlmInputManifestRepository by delegate {
                        private var failed = false

                        override suspend fun findRun(invocationId: String): Result<LlmRunInputManifest?> {
                            if (!failed) {
                                failed = true
                                return Result.failure(IllegalStateException("synthetic run lookup failure"))
                            }

                            return delegate.findRun(invocationId)
                        }
                    },
                )
            },
            { runtime ->
                val delegate = runtime.llmInputManifestRepository
                runtime.copy(
                    llmInputManifestRepository = object : LlmInputManifestRepository by delegate {
                        override suspend fun appendPhase(manifest: LlmPhaseInputManifest): Result<Unit> {
                            if (manifest.phase == LlmInvocationPhase.PROPOSER) {
                                return Result.failure(IllegalStateException("synthetic phase append failure"))
                            }

                            return delegate.appendPhase(manifest)
                        }
                    },
                )
            },
        )

        transforms.forEachIndexed { index, transform ->
            val invocationId = "manifest-fallback-$index"
            val fixture = runnerFixture(
                marketDataSource = MaterialManifestMarketDataSource,
                runtimeTransform = transform,
            ) { command ->
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
                cleanExit()
            }

            val result = fixture.runOneShot(defaultRequest().copy(invocationId = invocationId)).getOrThrow()
            val material = assertNotNull(
                fixture.runtime.decisionMaterialStateRepository.find(invocationId).getOrThrow(),
                fixture.eventLog.events().joinToString { event -> event.payload },
            )
            val run = assertNotNull(
                fixture.runtime.llmInputManifestRepository.findRun(invocationId).getOrThrow(),
                fixture.eventLog.events().joinToString { event -> event.payload },
            )

            assertEquals(OneShotRunnerStatus.NO_TRADE_DECISION, result.status)
            assertEquals(material.snapshotContentHash, run.materialContentHash)
            assertEquals(1, fixture.processRunner.launches.size)
            assertTrue(fixture.processRunner.launches.single().mcpManifestContent().contains("RISK_REDUCTION_ONLY"))
            assertTrue(fixture.runtime.broker.getPositions().getOrThrow().isEmpty())
        }
    }

    @Test
    fun standardPhaseBindAndGatewayStartFailures_launchOnlyRiskReductionProcess() = runBlocking {
        listOf("phase-bind", "gateway-start").forEach { failurePoint ->
            val manifestDirectory = Files.createTempDirectory("runner-$failurePoint")
            var gatewayBlocker: Path? = null
            var standardManifestPath: Path? = null
            val environment = defaultParentEnvironment() +
                ("FUKUROU_MCP_MANIFEST_DIRECTORY" to manifestDirectory.toString())
            val fixture = runnerFixture(
                parentEnvironment = environment,
                marketDataSource = MaterialManifestMarketDataSource,
                runtimeTransform = { runtime ->
                    val delegate = runtime.llmInputManifestRepository
                    runtime.copy(
                        llmInputManifestRepository = object : LlmInputManifestRepository by delegate {
                            override suspend fun appendPhase(manifest: LlmPhaseInputManifest): Result<Unit> {
                                val result = delegate.appendPhase(manifest)
                                if (result.isSuccess && manifest.phase == LlmInvocationPhase.PROPOSER) {
                                    val manifestPath = Files.list(manifestDirectory).use { paths ->
                                        paths.filter { path -> path.fileName.toString().endsWith(".json") }
                                            .findFirst()
                                            .orElseThrow()
                                    }
                                    standardManifestPath = manifestPath
                                    if (failurePoint == "phase-bind") {
                                        Files.delete(manifestPath)
                                    } else {
                                        val manifestId = manifestPath.fileName.toString().removeSuffix(".json")
                                        val socketBlocker = Path.of("/tmp", "fukurou-${manifestId.take(24)}.sock")
                                        Files.createDirectory(socketBlocker)
                                        Files.writeString(socketBlocker.resolve("child"), "block unlink")
                                        gatewayBlocker = socketBlocker
                                    }
                                }

                                return result
                            }
                        },
                    )
                },
            ) { command ->
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
                cleanExit()
            }
            val invocationId = "prelaunch-$failurePoint"

            val result = fixture.runOneShot(defaultRequest().copy(invocationId = invocationId)).getOrThrow()
            val material =
                assertNotNull(fixture.runtime.decisionMaterialStateRepository.find(invocationId).getOrThrow())
            val run = assertNotNull(fixture.runtime.llmInputManifestRepository.findRun(invocationId).getOrThrow())

            assertEquals(OneShotRunnerStatus.NO_TRADE_DECISION, result.status)
            assertEquals(material.snapshotContentHash, run.materialContentHash)
            assertEquals(1, fixture.processRunner.launches.size)
            val launchManifest = fixture.processRunner.launches.single().mcpManifestContent()
            assertTrue(launchManifest.contains("RISK_REDUCTION_ONLY"), "$failurePoint: $launchManifest")
            assertTrue(fixture.runtime.broker.getPositions().getOrThrow().isEmpty())
            assertFalse(Files.exists(requireNotNull(standardManifestPath)))
            val observation = fixture.runtime.llmInputManifestRepository
                .findObservation("$invocationId:PROPOSER")
                .getOrThrow()
            assertEquals(
                me.matsumo.fukurou.trading.audit.LlmIdentityCoverageStatus.NOT_OBSERVABLE_BEFORE_START,
                observation?.modelCoverageStatus,
            )
            gatewayBlocker?.let { blocker ->
                Files.deleteIfExists(blocker.resolve("child"))
                Files.deleteIfExists(blocker)
            }
        }
    }

    @Test
    fun malformedRequiredTickerAndCandleValues_failClosedIntoRiskReductionOnly() = runBlocking {
        listOf(MalformedTickerMarketDataSource, MalformedCandleMarketDataSource).forEachIndexed { index, source ->
            val fixture = runnerFixture(marketDataSource = source) { command ->
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
                cleanExit()
            }

            val result = fixture.runOneShot(
                defaultRequest().copy(invocationId = "malformed-market-$index"),
            ).getOrThrow()

            assertEquals(OneShotRunnerStatus.NO_TRADE_DECISION, result.status)
            assertTrue(fixture.processRunner.launches.single().mcpManifestContent().contains("RISK_REDUCTION_ONLY"))
            assertEquals(LlmRunTerminalCause.RUNNER_FAILED, result.terminalCause)
            assertStandardSnapshotFailureEvent(fixture, StandardMaterialSnapshotStage.VALIDATION)
        }
    }

    @Test
    fun phaseObservationFailure_rejectsApprovedEntry() = runBlocking {
        val fixture = runnerFixture(
            runtimeTransform = TradingRuntime::withFailingPhaseObservation,
        ) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.ENTER).getOrThrow()
            }

            cleanExit()
        }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertTrue(fixture.runtime.broker.getPositions().getOrThrow().isEmpty())
        assertTrue(fixture.eventLog.events().containsNoTradeReason("phase_observation_missing_entry_rejected"))
    }

    @Test
    fun preFilterObservationGap_rejectsApprovedEntryAfterProposer() = runBlocking {
        val fixture = runnerFixture(
            runtimeTransform = TradingRuntime::withMissingPreFilterObservation,
        ) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.ENTER).getOrThrow()
            }

            cleanExit()
        }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertTrue(fixture.runtime.broker.getPositions().getOrThrow().isEmpty())
        assertEquals(1, fixture.processRunner.launches.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("phase_observation_missing_entry_rejected"))
    }

    @Test
    fun falsifierObservationFailure_rejectsFreshApprovalWithoutEntry() = runBlocking {
        val fixture = runnerFixture(
            runtimeTransform = { runtime ->
                runtime.withFailingPhaseObservation(LlmInvocationPhase.FALSIFIER)
            },
        ) { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertTrue(fixture.runtime.broker.getPositions().getOrThrow().isEmpty())
        assertEquals(2, fixture.processRunner.launches.size)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("phase_observation_missing_entry_rejected"))
    }

    @Test
    fun enterApproved_placesDeterministicPaperEntryAndConsumesIntent() = runBlocking {
        val fixture = runnerFixture { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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
        val fixture = runnerFixture(
            config = config,
            clock = clock,
            runtimeTransform = { runtime ->
                runtime.withPaperLedger(
                    positions = emptyList(),
                    openOrders = listOf(legacyRestingEntryOrder()),
                    config = config,
                    clock = clock,
                )
            },
        ) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }

            cleanExit()
        }
        clock.advance(Duration.ofMinutes(31))

        val result = fixture.runOneShot(
            request = defaultRequest(),
            reservedAt = clock.instant(),
        ).getOrThrow()
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

        fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
        val openPositions = fixture.runtime.broker.getPositions().getOrThrow()
        val closeEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "atomic_risk_exit"
        }
        val violations = (fixture.runtime.safetyViolationRepository as InMemorySafetyViolationRepository).violations()

        assertEquals(OneShotRunnerStatus.PAPER_EXIT_EXECUTED, result.status)
        assertEquals(OrderStatus.FILLED, assertNotNull(result.tradeResult).status)
        assertEquals(0, openPositions.size)
        assertEquals(1, closeEvents.size)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun phaseObservationFailure_keepsRiskReducingExitExecutableAndMarksAttribution() = runBlocking {
        val fixture = runnerFixture(
            runtimeTransform = TradingRuntime::withFailingPhaseObservation,
        ) { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.EXIT).getOrThrow()
            }

            cleanExit()
        }
        seedApprovedEntry(fixture)

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.PAPER_EXIT_EXECUTED, result.status)
        assertTrue(fixture.runtime.broker.getPositions().getOrThrow().isEmpty())
        assertTrue(fixture.eventLog.events().any { event -> event.payload.contains("INFRASTRUCTURE_ATTRIBUTION_INCOMPLETE") })
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

        val result = runCatching { fixture.runOneShot(defaultRequest()).getOrThrow() }
        val openPositions = fixture.runtime.broker.getPositions().getOrThrow()
        val closeEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "close_position"
        }
        val hardHaltRejections = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_REJECTED_BY_HARD_HALT
        }

        assertTrue(result.isFailure)
        assertEquals(1, openPositions.size)
        assertEquals(0, closeEvents.size)
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
        val openPositions = fixture.runtime.broker.getPositions().getOrThrow()
        val openOrders = fixture.runtime.broker.getOpenOrders().getOrThrow()
        val closeEvents = fixture.eventLog.events().filter { event ->
            event.eventType == CommandEventType.TOOL_CALL_COMPLETED && event.toolName == "atomic_risk_exit"
        }

        assertEquals(OneShotRunnerStatus.PAPER_EXIT_EXECUTED, result.status)
        assertEquals(0, openPositions.size)
        assertEquals(0, openOrders.size)
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

            val result = fixture.runOneShot(defaultRequest()).getOrThrow()

            assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status, failureCase.first)
            assertNull(result.decision, failureCase.first)
            assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_missing_decision"), failureCase.first)
        }
    }

    @Test
    fun proposerNormalExitWithoutToolCalls_recordsNoToolCallsReason() = runBlocking {
        val fixture = runnerFixture { cleanExit() }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertNull(result.decision)
        assertTrue(fixture.eventLog.events().containsNoTradeReason("proposer_no_tool_calls"))
    }

    @Test
    fun providerContractFailureRejectsPersistedEntryButPreservesPersistedExit() = runBlocking {
        val entryFixture = runnerFixture { command ->
            submitDecision(fixtureRepository, command, DecisionAction.ENTER).getOrThrow()
            cleanExit(stdout = "{}")
        }
        val entryResult = entryFixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, entryResult.status)
        assertTrue(entryFixture.eventLog.events().containsNoTradeReason("provider_failure_entry_rejected"))
        assertEquals(1, entryFixture.processRunner.launches.size)

        val exitFixture = runnerFixture { command ->
            submitDecision(fixtureRepository, command, DecisionAction.EXIT).getOrThrow()
            cleanExit(stdout = "{}")
        }
        val exitResult = exitFixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(DecisionAction.EXIT, exitResult.decision?.decision?.submission?.action)
        assertEquals(1, exitFixture.processRunner.launches.size)
    }

    @Test
    fun failedFalsifierCannotApprovePersistedEntry() = runBlocking {
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.ENTER).getOrThrow()
                return@runnerFixture cleanExit()
            }
            submitFalsification(fixtureRepository, command, FalsificationVerdict.APPROVED).getOrThrow()
            cleanExit(stdout = "{}")
        }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertNull(result.tradeResult)
        assertEquals(2, fixture.processRunner.launches.size)
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

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
                stdout = """{"type":"result","subtype":"authentication_error","is_error":true,"result":"Invalid authentication credentials"}""",
            )
        }

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
        val proposerDetails = fixture.eventLog.events().singleRunnerPhaseDetails("proposer")

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals("true", proposerDetails.stringValue("authFailureSuspected"))
        assertEquals("true", proposerDetails.stringValue("cliErrorReported"))
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
        val proposerDetails = fixture.eventLog.events().singleRunnerPhaseDetails("proposer")
        val authFailureLogFound = humanLogs.any { message -> message.contains("LLM CLI authentication failure suspected.") }

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertFalse(proposerDetails.containsKey("authFailureSuspected"))
        assertEquals("true", proposerDetails.stringValue("cliErrorReported"))
        assertEquals("OUTPUT_CONTRACT", proposerDetails.stringValue("failureCategory"))
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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
            materialMarketDataSource = FakeMarketDataSource,
            parentEnvironment = defaultParentEnvironment(),
            clock = fixedClock(),
            logger = {},
            cliVersionProbe = { Result.success("fixture-cli 1.0") },
        )

        val result = runReservedOneShot(runtime, runner, defaultRequest()).getOrThrow()
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
    fun reservationBackedRunner_doesNotRecountLegacyHourlyAuditAfterAdmission() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(maxInvocationsPerHour = 1, entryFillReservePerHour = 0, stopProximityReservePerHour = 0),
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(1, fixture.processRunner.launches.size)
        assertEquals(
            OneShotRunnerStatus.NO_TRADE_AUDITED.name,
            fixture.runtime.llmRunRepository.findByInvocationId(result.invocationId).getOrThrow()?.status,
        )
    }

    @Test
    fun daemonLaunchAuditDoesNotCountAgainstRunnerInvocationCap() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(maxInvocationsPerHour = 1, entryFillReservePerHour = 0, stopProximityReservePerHour = 0),
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
        val llmRun = requireNotNull(
            fixture.runtime.llmRunRepository.findByInvocationId(result.invocationId).getOrThrow(),
        )
        val preflightPayload = fixture.eventLog.events()
            .single { event -> event.isRunnerPhaseCompleted("reservation_claim") }
            .payloadJsonObject()

        assertEquals("runtime-version-1", llmRun.runtimeConfigVersionId)
        assertEquals("runtime-hash-1", llmRun.runtimeConfigHash)
        assertEquals("runtime-version-1", preflightPayload.stringValue("runtimeConfigVersionId"))
        assertEquals("runtime-hash-1", preflightPayload.stringValue("runtimeConfigHash"))
    }

    @Test
    fun reservationBackedRunner_doesNotRecountLegacyDailyAuditAfterAdmission() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(
                maxInvocationsPerHour = 1,
                maxInvocationsPerDay = 1,
                entryFillReservePerHour = 0,
                entryFillReservePerDay = 0,
                stopProximityReservePerHour = 0,
                stopProximityReservePerDay = 0,
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()

        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED, result.status)
        assertEquals(1, fixture.processRunner.launches.size)
    }

    @Test
    fun runEnvironmentIsRecordedAndTransferredThroughMcpConfig() = runBlocking {
        val fixture = runnerFixture { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        fixture.runOneShot(defaultRequest()).getOrThrow()

        val decision = fixture.decisionRepository.snapshots.decisions().single()
        val auditEvent = fixture.eventLog.events().first { event ->
            event.decisionRunContext.decisionRunId == decision.submission.invocationId
        }
        val proposerCommand = fixture.processRunner.launches.first()
        val mcpConfigContent = proposerCommand.claudeMcpConfigContent()
        val manifestContent = proposerCommand.mcpManifestContent()

        assertEquals("claude", decision.submission.llmProvider)
        assertEquals(SystemPromptV1.VERSION, decision.submission.systemPromptVersion)
        assertNotNull(decision.submission.invocationId)
        assertNotNull(decision.submission.promptHash)
        assertNotNull(decision.submission.marketSnapshotId)
        assertEquals(decision.submission.invocationId, auditEvent.decisionRunContext.decisionRunId)
        assertFalse(mcpConfigContent.contains("DB_PASSWORD"))
        assertTrue(manifestContent.contains(decision.submission.invocationId))
        assertTrue(manifestContent.contains(decision.submission.promptHash))
        assertTrue(manifestContent.contains(decision.submission.marketSnapshotId))
    }

    @Test
    fun cliConfigControlsMcpServerNameCommandAndToolAllowlist() = runBlocking {
        val customServerName = "custom-mcp"
        val fixture = runnerFixture { command ->
            if (command.isProposerLaunch()) {
                submitDecision(fixtureRepository, command, DecisionAction.NO_TRADE).getOrThrow()
            }

            cleanExit()
        }

        fixture.runOneShot(
            defaultRequest().copy(
                cliConfig = OneShotRunnerCliConfig(
                    mcpServerName = customServerName,
                    mcpServerCommand = "custom-java",
                    mcpServerArgs = listOf("-jar", MCP_JAR_PATH_PLACEHOLDER),
                    proposerAllowedTools = defaultProposerAllowedTools(customServerName),
                    falsifierAllowedTools = defaultFalsifierAllowedTools(customServerName),
                ),
            ),
        ).getOrThrow()

        val proposerCommand = fixture.processRunner.launches.single()
        val joinedArgs = proposerCommand.args.joinToString(" ")
        val mcpConfigContent = proposerCommand.claudeMcpConfigContent()

        assertTrue(mcpConfigContent.contains(customServerName))
        assertTrue(mcpConfigContent.contains("custom-java"))
        assertTrue(joinedArgs.contains("mcp__custom-mcp__submit_decision"))
        assertFalse(joinedArgs.contains("mcp__fukurou-mcp__submit_decision"))
    }

    @Test
    fun approvedEntryRecordsDecisionToPlaceOrderLatencyPayload() = runBlocking {
        val fixture = runnerFixture { command ->
            handleEnterAndApprovedFalsifier(fixtureRepository, command)
        }

        fixture.runOneShot(defaultRequest()).getOrThrow()

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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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
        assertEquals(me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause.SAFETY_DENIED, result.terminalCause)
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

        val result = fixture.runOneShot(defaultRequest()).getOrThrow()
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
        assertEquals(me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause.SAFETY_DENIED, result.terminalCause)
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

        fixture.runOneShot(defaultRequest()).getOrThrow()

        val falsifierCommand = fixture.processRunner.launches.single { command -> command.isFalsifierLaunch() }
        val allowedToolsConfig = falsifierCommand.codexConfigContent()

        assertTrue(allowedToolsConfig.contains("submit_falsification"))
        val manifestContent = falsifierCommand.mcpManifestContent()
        assertTrue(manifestContent.contains("get_trade_intent"))
        assertTrue(manifestContent.contains("knowledge_get_recent_lessons"))
        assertTrue(manifestContent.contains("knowledge_search_similar_setups"))
        assertTrue(manifestContent.contains("preview_order"))
        assertFalse(allowedToolsConfig.contains("place_order"))
        assertFalse(allowedToolsConfig.contains("submit_decision"))
    }

    @Test
    fun runnerAutoApprovesOnlyCodexFalsifierWriteTool() = runBlocking {
        val fixture = requestCapturingRunnerFixture()

        fixture.runOneShot(defaultRequest()).getOrThrow()

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
    fun runnerRoleAssignmentWithoutModel_disablesProviderModelFallback() = runBlocking {
        val fixture = requestCapturingRunnerFixture(
            tradingConfig = TradingBotConfig(
                llmRoleAssignments = me.matsumo.fukurou.trading.config.LlmRoleAssignments(
                    proposer = me.matsumo.fukurou.trading.config.LlmRoleAssignment(provider = LlmProvider.CLAUDE),
                    falsifier = me.matsumo.fukurou.trading.config.LlmRoleAssignment(provider = LlmProvider.CODEX),
                ),
            ),
        )

        fixture.runOneShot(defaultRequest()).getOrThrow()

        assertTrue(fixture.invoker.requests.all { request -> !request.useConfiguredModelFallback })
        assertTrue(fixture.invoker.requests.all { request -> request.model == null })
    }

    @Test
    fun runnerAutoApprovesCodexProposerWriteTool() = runBlocking {
        val fixture = requestCapturingRunnerFixture(
            proposerAction = DecisionAction.NO_TRADE,
        )

        fixture.runOneShot(
            defaultRequest().copy(
                proposerProvider = LlmProvider.CODEX,
            ),
        ).getOrThrow()

        val proposerRequest = fixture.invoker.requests.single()

        assertEquals(LlmProvider.CODEX, proposerRequest.provider)
        assertEquals(listOf("submit_decision"), requireNotNull(proposerRequest.mcpServer).autoApprovedTools)
    }

    @Test
    fun cliConfigRejectsNonCanonicalFalsifierAllowlist() {
        val readOnlyFalsifierTools = defaultFalsifierAllowedTools(DEFAULT_RUNNER_MCP_SERVER_NAME)
            .filterNot { toolName -> toolName.endsWith("__submit_falsification") }
        assertFailsWith<IllegalArgumentException> {
            OneShotRunnerCliConfig(
                falsifierAllowedTools = readOnlyFalsifierTools,
            )
        }
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

        fixture.runOneShot(defaultRequest()).getOrThrow()

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
        assertFalse(codexConfigContent.contains("DB_URL"))
        assertFalse(codexConfigContent.contains("DB_USER"))
        assertFalse(codexConfigContent.contains("DB_PASSWORD"))
        assertNotNull(falsifierCommand.environment[FUKUROU_FALSIFIER_INTENT_ID_ENV])
        assertTrue(proposerCommand.mcpManifestContent().contains("\"phase\":\"PROPOSER\""))
        assertTrue(falsifierCommand.mcpManifestContent().contains("\"phase\":\"FALSIFIER\""))

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

        fixture.runOneShot(defaultRequest()).getOrThrow()

        val phaseEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.RUNNER_PHASE_COMPLETED }
        val proposerPhase = phaseEvents.single { event -> event.payload.contains("\"phase\":\"proposer\"") }
        val proposerDetails = fixture.eventLog.events().singleRunnerPhaseDetails("proposer")

        assertEquals("EXITED", proposerDetails.stringValue("status"))
        assertEquals("0", proposerDetails.stringValue("exitCode"))
        assertFalse(proposerDetails.containsKey("error"))
        assertEquals("true", proposerDetails.stringValue("rawOutputOmitted"))
        assertFalse(proposerPhase.payload.contains("test-password"))
        assertFalse(proposerPhase.payload.contains("fukurou-token"))
    }

    @Test
    fun phaseAuditStoresClaudeAndCodexStructuredUsage() = runBlocking {
        val claudeUsageStdout = """
            {
              "type": "result",
              "is_error": false,
              "result": "submitted",
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

        fixture.runOneShot(defaultRequest()).getOrThrow()

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

        val result = fixture.runOneShot(request)

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

        val result = runReservedOneShot(runtime, runner, defaultRequest().copy(invocationId = "failed-run"))
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

        val result = runReservedOneShot(runtime, runner, request)
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
            runReservedOneShot(runtime, runner, request)
        }
        val record = runtime.llmRunRepository.findByInvocationId("codex-cancelled-run").getOrThrow()
        val noTradeEvent = (runtime.commandEventLog as InMemoryCommandEventLog).events()
            .single { event -> event.eventType == CommandEventType.NO_TRADE_EXIT }

        assertEquals(cancellation.message, propagated.message)
        assertTrue(propagated.suppressed.any { it.message == cleanupFailure.message })
        assertEquals("CancellationException", propagated.safeCodexFailureOrNull()?.type)
        assertEquals("Codex invocation failure details omitted.", record?.errorMessage)
        assertFalse(noTradeEvent.payload.contains("auth-path-marker"))
        assertFalse(noTradeEvent.payload.contains("path-message-marker"))
        assertTrue(noTradeEvent.payload.contains("\"messageOmitted\":true"))
    }

    @Test
    fun llmRunRecordFailure_preventsMaterialExecutionAndTerminalizesReservation() = runBlocking {
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

        val request = defaultRequest().copy(
            invocationId = "record-failure-run",
            triggerKind = LlmDaemonTriggerKind.MANUAL,
        )
        fixture.runtime.launchReservationRepository.tryReserve(
            LlmLaunchReservationRequest(
                invocationId = "record-failure-run",
                triggerKind = LlmDaemonTriggerKind.MANUAL,
                triggerKey = "test:record-failure-run",
                reservedAt = fixedClock().instant(),
                runnerConfig = LlmRunnerConfig(),
                hourlyWindow = Duration.ofHours(1),
                dailyWindow = Duration.ofHours(24),
                activeReservationStaleAfter = Duration.ofMinutes(30),
            ),
        ).getOrThrow()

        val result = runCatching { fixture.runner.runOneShot(request) }
        val reservation = fixture.runtime.launchReservationRepository
            .findExecutionClaim("record-failure-run")
            .getOrThrow()

        assertTrue(result.isFailure)
        assertTrue(fixture.processRunner.launches.isEmpty())
        assertEquals(LlmLaunchReservationStatus.FAILED, reservation?.status)
    }

    @Test
    fun claimOutcomeUnknownRetry_retainsOriginalActiveSinceAndDoesNotLaunchStaleReservation() = runBlocking {
        val expectedActiveSince = fixedInstant().minus(Duration.ofMinutes(30))
        lateinit var claimRepository: OutcomeUnknownClaimRepository
        val fixture = runnerFixture(
            runtimeTransform = { runtime ->
                claimRepository = OutcomeUnknownClaimRepository(runtime.launchReservationRepository)
                runtime.copy(launchReservationRepository = claimRepository)
            },
        ) { cleanExit() }
        val request = defaultRequest().copy(
            invocationId = "claim-outcome-unknown-stale",
            triggerKind = LlmDaemonTriggerKind.MANUAL,
        )

        try {
            val result = fixture.runOneShot(
                request = request,
                reservedAt = expectedActiveSince.minusMillis(1),
            )

            assertTrue(result.isFailure)
            assertEquals(2, claimRepository.requests.size)
            assertEquals(expectedActiveSince, claimRepository.requests[0].activeSince)
            assertEquals(expectedActiveSince, claimRepository.requests[1].activeSince)
            assertTrue(fixture.processRunner.launches.isEmpty())
        } finally {
            LlmExecutionAdmissionHealth.resetForTest()
            LlmExecutionTerminationFenceRegistry.resetForTest()
        }
    }

    @Test
    fun cancellationAtClaimCommitBoundary_terminalizesReservationWithoutChildLaunch() = runBlocking {
        lateinit var claimRepository: ClaimBoundaryRepository
        val fixture = runnerFixture(
            runtimeTransform = { runtime ->
                claimRepository = ClaimBoundaryRepository(runtime.launchReservationRepository)
                runtime.copy(launchReservationRepository = claimRepository)
            },
        ) { cleanExit() }
        val invocationId = "cancel-at-claim-boundary"
        fixture.runtime.launchReservationRepository.tryReserve(
            LlmLaunchReservationRequest(
                invocationId = invocationId,
                triggerKind = LlmDaemonTriggerKind.MANUAL,
                triggerKey = "test:$invocationId",
                reservedAt = fixedInstant(),
                runnerConfig = LlmRunnerConfig(),
                hourlyWindow = Duration.ofHours(1),
                dailyWindow = Duration.ofDays(1),
                activeReservationStaleAfter = Duration.ofMinutes(30),
            ),
        ).getOrThrow()
        val run = async {
            fixture.runner.runOneShot(
                defaultRequest().copy(
                    invocationId = invocationId,
                    triggerKind = LlmDaemonTriggerKind.MANUAL,
                ),
            )
        }
        claimRepository.claimEntered.await()

        run.cancel()
        claimRepository.allowClaimCommit.complete(Unit)

        assertFailsWith<CancellationException> { run.await() }
        assertTrue(fixture.processRunner.launches.isEmpty())
        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            fixture.runtime.launchReservationRepository.findExecutionClaim(invocationId).getOrThrow()?.status,
        )
        assertEquals(0, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
        assertEquals(0, LlmExecutionTerminationFenceRegistry.transitionLockCountForTest())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
    }

    @Test
    fun claimAuditFailure_terminalizesAndCleansClaimRegistries() = runBlocking {
        val fixture = runnerFixture(
            runtimeTransform = { runtime ->
                runtime.copy(commandEventLog = FailFirstAppendCommandEventLog(runtime.commandEventLog))
            },
        ) { cleanExit() }
        val invocationId = "claim-audit-failure"
        val result = fixture.runOneShot(
            request = defaultRequest().copy(
                invocationId = invocationId,
                triggerKind = LlmDaemonTriggerKind.MANUAL,
            ),
        )

        assertTrue(result.isFailure)
        assertTrue(fixture.processRunner.launches.isEmpty())
        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            fixture.runtime.launchReservationRepository.findExecutionClaim(invocationId).getOrThrow()?.status,
        )
        assertEquals(0, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
        assertEquals(0, LlmExecutionTerminationFenceRegistry.transitionLockCountForTest())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
    }

    @Test
    fun preStartProcessFailures_terminalizeWithoutRecoveryBlocker() = runBlocking {
        listOf(
            "pre-start-generic" to IllegalStateException("synthetic pre-start failure"),
            "pre-start-cancellation" to CancellationException("synthetic pre-start cancellation"),
        ).forEach { (invocationId, failure) ->
            resetProcessRecoveryState(invocationId)
            val fixture = processRecoveryFixture(
                processRunner = StartAwareTestProcessRunner(
                    callbackInvoked = false,
                    result = Result.failure(failure),
                ),
            )

            runCatching {
                fixture.runOneShot(defaultRequest().copy(invocationId = invocationId)).getOrThrow()
            }

            assertNull(LlmProcessTreeTerminationRegistry.find(invocationId))
            assertTrue(LlmExecutionAdmissionHealth.isHealthy())
            assertEquals(0, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
        }
    }

    @Test
    fun postStartGenericFailure_terminalizesWithRecoveryBlocker() = runBlocking {
        val invocationId = "post-start-generic"
        resetProcessRecoveryState(invocationId)
        val fixture = processRecoveryFixture(
            processRunner = StartAwareTestProcessRunner(
                callbackInvoked = true,
                result = Result.failure(IllegalStateException("synthetic post-start failure")),
            ),
        )

        try {
            fixture.runOneShot(defaultRequest().copy(invocationId = invocationId))

            assertEquals(
                ProcessTreeTerminationProof.UNCERTAIN,
                LlmProcessTreeTerminationRegistry.find(invocationId),
            )
            assertFalse(LlmExecutionAdmissionHealth.isHealthy())
            assertEquals(0, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
        } finally {
            resetProcessRecoveryState(invocationId)
        }
    }

    @Test
    fun parserFailureAfterProvenExit_terminalizesAndReleasesProcessProof() = runBlocking {
        val invocationId = "proven-exit-parser-failure"
        resetProcessRecoveryState(invocationId)
        val fixture = processRecoveryFixture(
            processRunner = StartAwareTestProcessRunner(
                callbackInvoked = true,
                result = Result.success(
                    cleanExit().copy(processTreeTerminationProof = ProcessTreeTerminationProof.PROVEN_EXITED),
                ),
            ),
            outputParser = FailingTestOutputParser,
        )

        fixture.runOneShot(defaultRequest().copy(invocationId = invocationId))

        assertNull(LlmProcessTreeTerminationRegistry.find(invocationId))
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
        assertEquals(0, LlmExecutionTerminationFenceRegistry.fenceCountForTest())
    }

    @Test
    fun daemonTickLaunch_recordsTriggerKindInLlmRun() = runBlocking {
        val tradingConfig = TradingBotConfig(
            daemon = LlmDaemonConfig(launchEnabled = true),
        )
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
            tradingConfig = tradingConfig,
            llmInvoker = ShellLlmInvoker(
                commandRenderer = DefaultLlmCommandRenderer(),
                processRunner = processRunner,
            ),
            parentEnvironment = defaultParentEnvironment(),
            clock = fixedClock(),
            logger = {},
        )
        val scheduler = LlmDaemonScheduler(
            tradingConfig = tradingConfig,
            dependencies = LlmDaemonSchedulerDependencies(
                riskStateRepository = runtime.riskStateRepository,
                commandEventLog = runtime.commandEventLog,
                launchReservationRepository = runtime.launchReservationRepository,
                openRiskReader = {
                    Result.success(
                        me.matsumo.fukurou.trading.daemon.LlmDaemonOpenRiskSnapshot(0, emptyList(), 0),
                    )
                },
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
            fixture.runOneShot(request).getOrThrow()
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

private data class ProcessRecoveryFixture(
    val runtime: TradingRuntime,
    val runner: OneShotLlmRunner,
)

private suspend fun ProcessRecoveryFixture.runOneShot(request: OneShotRunnerRequest): Result<OneShotRunnerResult> {
    return runReservedOneShot(runtime, runner, request)
}

private suspend fun RunnerFixture.runOneShot(
    request: OneShotRunnerRequest,
    reservedAt: Instant = fixedClock().instant(),
): Result<OneShotRunnerResult> {
    return runReservedOneShot(
        runtime = runtime,
        runner = runner,
        request = request,
        reservedAt = reservedAt,
    )
}

private suspend fun runReservedOneShot(
    runtime: TradingRuntime,
    runner: OneShotLlmRunner,
    request: OneShotRunnerRequest,
    reservedAt: Instant = fixedClock().instant(),
): Result<OneShotRunnerResult> {
    val invocationId = request.invocationId ?: UUID.randomUUID().toString()
    val triggerKind = request.triggerKind ?: LlmDaemonTriggerKind.MANUAL
    val reservedRequest = request.copy(invocationId = invocationId, triggerKind = triggerKind)
    val reservation = runtime.launchReservationRepository.tryReserve(
        LlmLaunchReservationRequest(
            invocationId = invocationId,
            triggerKind = triggerKind,
            triggerKey = "test:$invocationId",
            reservedAt = reservedAt,
            runnerConfig = LlmRunnerConfig(),
            hourlyWindow = Duration.ofHours(1),
            dailyWindow = Duration.ofHours(24),
            activeReservationStaleAfter = Duration.ofMinutes(30),
        ),
    ).getOrThrow()
    assertIs<LlmLaunchReservationOutcome.Reserved>(reservation)

    return try {
        runner.runOneShot(reservedRequest)
    } finally {
        runtime.launchReservationRepository.finish(
            LlmLaunchReservationFinish(invocationId, LlmLaunchReservationStatus.FINISHED, null, fixedClock().instant()),
        ).getOrThrow()
    }
}

/**
 * request capture 用 runner fixture。
 *
 * @param invoker request capture 用 LLM invoker
 * @param runner test target runner
 */
private data class RequestCapturingRunnerFixture(
    val invoker: RequestCapturingLlmInvoker,
    val runner: OneShotLlmRunner,
    val runtime: TradingRuntime,
)

private suspend fun RequestCapturingRunnerFixture.runOneShot(
    request: OneShotRunnerRequest,
): Result<OneShotRunnerResult> {
    val delegate = RunnerFixture(
        runtime = runtime,
        decisionRepository = runtime.decisionRepository as InMemoryDecisionRepository,
        eventLog = runtime.commandEventLog as InMemoryCommandEventLog,
        processRunner = FakeProcessRunner { cleanExit() },
        runner = runner,
    )
    return delegate.runOneShot(request)
}

private fun runnerFixture(
    config: TradingBotConfig = TradingBotConfig(),
    parentEnvironment: Map<String, String> = defaultParentEnvironment(),
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    runtimeTransform: (TradingRuntime) -> TradingRuntime = { runtime -> runtime },
    logger: (String) -> Unit = {},
    clock: Clock = fixedClock(),
    marketDataSource: MarketDataSource = FakeMarketDataSource,
    cliVersionProbe: LlmCliVersionProbe = LlmCliVersionProbe { Result.success("fixture-cli 1.0") },
    launchHandler: suspend (RenderedLlmCommand) -> ProcessRunResult,
): RunnerFixture {
    val baseRuntime = TradingRuntimeFactory.inMemory(
        clock = clock,
        marketDataSource = marketDataSource,
        tradingConfig = config,
    )
    val eventLog = baseRuntime.commandEventLog as InMemoryCommandEventLog
    val runtime = runtimeTransform(baseRuntime)
    val decisionRepository = runtime.decisionRepository as InMemoryDecisionRepository
    val processRunner = FakeProcessRunner(launchHandler)
    val runner = OneShotLlmRunner(
        tradingRuntime = runtime,
        tradingConfig = config,
        materialMarketDataSource = marketDataSource,
        llmInvoker = ShellLlmInvoker(
            commandRenderer = DefaultLlmCommandRenderer(),
            processRunner = processRunner,
        ),
        runtimeConfigSnapshot = runtimeConfigSnapshot,
        parentEnvironment = parentEnvironment,
        clock = clock,
        logger = logger,
        cliVersionProbe = cliVersionProbe,
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

private fun processRecoveryFixture(
    processRunner: ProcessRunner,
    outputParser: LlmOutputParser = DefaultLlmOutputParser(),
): ProcessRecoveryFixture {
    val config = TradingBotConfig()
    val clock = fixedClock()
    val runtime = TradingRuntimeFactory.inMemory(
        clock = clock,
        marketDataSource = FakeMarketDataSource,
        tradingConfig = config,
    )
    val runner = OneShotLlmRunner(
        tradingRuntime = runtime,
        tradingConfig = config,
        materialMarketDataSource = FakeMarketDataSource,
        llmInvoker = ShellLlmInvoker(
            commandRenderer = DefaultLlmCommandRenderer(),
            processRunner = processRunner,
            outputParser = outputParser,
        ),
        parentEnvironment = defaultParentEnvironment(),
        clock = clock,
        cliVersionProbe = LlmCliVersionProbe { Result.success("fixture-cli 1.0") },
    )
    fixtureRepository = runtime.decisionRepository

    return ProcessRecoveryFixture(runtime, runner)
}

private fun resetProcessRecoveryState(invocationId: String) {
    LlmProcessTreeTerminationRegistry.resolve(invocationId)
    LlmExecutionAdmissionHealth.resetForTest()
    LlmExecutionTerminationFenceRegistry.resetForTest()
}

private class OutcomeUnknownClaimRepository(
    private val delegate: LlmLaunchReservationRepository,
) : LlmLaunchReservationRepository by delegate {
    val requests = mutableListOf<LlmExecutionClaimRequest>()

    override suspend fun claimForExecution(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
        requests += request

        return if (requests.size == 1) {
            LlmExecutionClaimOutcome.OutcomeUnknown(IllegalStateException("claim commit outcome unknown"))
        } else {
            LlmExecutionClaimOutcome.Rejected(LlmExecutionClaimRejectionReason.TERMINAL)
        }
    }
}

private class ClaimBoundaryRepository(
    private val delegate: LlmLaunchReservationRepository,
) : LlmLaunchReservationRepository by delegate {
    val claimEntered = CompletableDeferred<Unit>()
    val allowClaimCommit = CompletableDeferred<Unit>()

    override suspend fun claimForExecution(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
        claimEntered.complete(Unit)
        allowClaimCommit.await()

        return delegate.claimForExecution(request)
    }
}

private class SnapshotClaimLossRepository(
    private val delegate: LlmLaunchReservationRepository,
    private val captureCompleted: () -> Boolean,
) : LlmLaunchReservationRepository by delegate {
    private var claimLost = false

    override suspend fun validateExecutionAdmission(invocationId: String, claimantToken: String?): Result<Boolean> {
        if (!claimLost && captureCompleted()) {
            claimLost = true
            return Result.success(false)
        }

        return delegate.validateExecutionAdmission(invocationId, claimantToken)
    }
}

private class SnapshotAdmissionQueryFailureRepository(
    private val delegate: LlmLaunchReservationRepository,
    private val captureCompleted: () -> Boolean,
) : LlmLaunchReservationRepository by delegate {
    private var failed = false

    override suspend fun validateExecutionAdmission(invocationId: String, claimantToken: String?): Result<Boolean> {
        if (!failed && captureCompleted()) {
            failed = true
            return Result.failure(IllegalStateException("snapshot admission query failed"))
        }

        return delegate.validateExecutionAdmission(invocationId, claimantToken)
    }
}

private class ExpiredSnapshotClaimRepository(
    private val delegate: LlmLaunchReservationRepository,
    private val hardTimeout: Duration,
) : LlmLaunchReservationRepository by delegate {
    override suspend fun claimForExecution(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
        return when (val outcome = delegate.claimForExecution(request)) {
            is LlmExecutionClaimOutcome.Claimed -> outcome.copy(
                claimedAt = request.claimedAt.minus(hardTimeout).minusMillis(1),
            )
            else -> outcome
        }
    }
}

private class FailFirstAppendCommandEventLog(
    private val delegate: CommandEventLog,
) : CommandEventLog by delegate {
    private var failAppend = true

    override suspend fun append(event: CommandEvent): Result<Unit> {
        if (failAppend) {
            failAppend = false
            return Result.failure(IllegalStateException("claim audit append failed"))
        }

        return delegate.append(event)
    }
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

private fun TradingRuntime.withFailingPhaseObservation(): TradingRuntime {
    return withFailingPhaseObservation(*LlmInvocationPhase.entries.toTypedArray())
}

private fun TradingRuntime.withFailingPhaseObservation(vararg failingPhases: LlmInvocationPhase): TradingRuntime {
    val delegate = llmInputManifestRepository
    val phaseNames = failingPhases.mapTo(mutableSetOf()) { phase -> phase.name }

    return copy(
        llmInputManifestRepository = object : LlmInputManifestRepository by delegate {
            override suspend fun appendObservation(observation: LlmPhaseObservation): Result<Unit> {
                val phaseName = observation.phaseManifestId.substringAfterLast(':')

                return if (phaseName in phaseNames) {
                    Result.failure(IllegalStateException("phase observation unavailable"))
                } else {
                    delegate.appendObservation(observation)
                }
            }
        },
    )
}

private fun TradingRuntime.withMissingPreFilterObservation(): TradingRuntime {
    val delegate = llmInputManifestRepository

    return copy(
        llmInputManifestRepository = object : LlmInputManifestRepository by delegate {
            override suspend fun findPhase(phaseManifestId: String) =
                if (phaseManifestId.endsWith(":${LlmInvocationPhase.PRE_FILTER.name}")) {
                    val proposerPhaseId = phaseManifestId.substringBeforeLast(':') + ":${LlmInvocationPhase.PROPOSER.name}"
                    delegate.findPhase(proposerPhaseId)
                } else {
                    delegate.findPhase(phaseManifestId)
                }

            override suspend fun findObservation(phaseManifestId: String) =
                if (phaseManifestId.endsWith(":${LlmInvocationPhase.PRE_FILTER.name}")) {
                    Result.success(null)
                } else {
                    delegate.findObservation(phaseManifestId)
                }
        },
    )
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
    val drawdownRiskStateRepository = requireNotNull(riskStateRepository as? InMemoryRiskStateRepository)
    drawdownRiskStateRepository.accountStateBoundary.updateRiskState { state ->
        state.copy(
            drawdownRatio = BigDecimal("-0.1500000000"),
            equityPeak = BigDecimal("100000.0000"),
            updatedAt = fixedInstant(),
        )
    }
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
        restingEntryOrderTtl = config.decisionProtocol.restingEntryOrderTtl,
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
        restingEntryOrderTtl = config.decisionProtocol.restingEntryOrderTtl,
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
        restingEntryOrderTtl = config.decisionProtocol.restingEntryOrderTtl,
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
    tradingConfig: TradingBotConfig = TradingBotConfig(),
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
        tradingConfig = tradingConfig,
        llmInvoker = invoker,
        materialMarketDataSource = FakeMarketDataSource,
        parentEnvironment = parentEnvironment,
        clock = fixedClock(),
        logger = {},
        cliVersionProbe = { Result.success("fixture-cli 1.0") },
    )

    return RequestCapturingRunnerFixture(
        invoker = invoker,
        runner = runner,
        runtime = runtime,
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
            LlmInvocationPhase.RISK_REDUCTION_ONLY -> error("Unexpected reduction-only phase in standard fixture.")
            LlmInvocationPhase.REFLECTION -> Unit
            LlmInvocationPhase.EVALUATION_REPORT -> Unit
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
        val result = launchHandler(command)
        val successfulEmptyOutput = result.status == ProcessRunStatus.EXITED &&
            result.exitCode == 0 && result.stdout.isBlank() && result.stderr.isBlank()

        return Result.success(if (successfulEmptyOutput) result.copy(stdout = pinnedSuccessOutput(command)) else result)
    }
}

private fun pinnedSuccessOutput(command: RenderedLlmCommand): String {
    return if (command.args.contains("exec")) {
        """
            {"type":"thread.started","thread_id":"fixture-thread"}
            {"type":"item.completed","item":{"type":"agent_message","text":""}}
            {"type":"turn.completed","usage":{"input_tokens":0,"cached_input_tokens":0,"output_tokens":0,"reasoning_output_tokens":0}}
        """.trimIndent()
    } else {
        """{"type":"result","is_error":false,"result":""}"""
    }
}

private class StartAwareTestProcessRunner(
    private val callbackInvoked: Boolean,
    private val result: Result<ProcessRunResult>,
) : ProcessStartAwareRunner {
    override suspend fun run(command: RenderedLlmCommand): Result<ProcessRunResult> {
        return run(command) {}
    }

    override suspend fun run(command: RenderedLlmCommand, onStarted: () -> Unit): Result<ProcessRunResult> {
        if (callbackInvoked) onStarted()

        return result
    }
}

private object FailingTestOutputParser : LlmOutputParser {
    override fun parse(
        request: LlmInvocationRequest,
        command: RenderedLlmCommand,
        processResult: ProcessRunResult,
        startedAt: Instant,
        completedAt: Instant,
    ): ParsedLlmOutput {
        error("synthetic parser failure")
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
        invalidationPredicates = listOf(
            TradePlanInvalidationPredicate(
                type = TradePlanInvalidationType.LAST_PRICE_AT_OR_BELOW,
                decimalThresholdJpy = BigDecimal("9700000"),
            ),
        ),
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

private fun legacyRestingEntryOrder(): Order {
    return Order(
        orderId = UUID.randomUUID().toString(),
        positionId = null,
        tradeGroupId = UUID.randomUUID().toString(),
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        status = OrderStatus.OPEN,
        sizeBtc = "0.005000000000",
        limitPriceJpy = "9900000.00000000",
        triggerPriceJpy = null,
        protectiveStopPriceJpy = "9700000.00000000",
        takeProfitPriceJpy = "10500000.00000000",
        estimatedWinProbability = "0.6000000000",
        reasonJa = "legacy resting entry",
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
        canonicalThesisId = identity?.thesisId ?: DecisionIdentityGenerator.thesisId(tradePlanDraft()),
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

private fun RenderedLlmCommand.mcpManifestContent(): String {
    val manifestPath = cleanupPaths.single { path -> path.fileName.toString().matches(Regex("[0-9a-f]{48}\\.json")) }

    return Files.readString(manifestPath)
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

private suspend fun assertStandardSnapshotFailureEvent(fixture: RunnerFixture, stage: StandardMaterialSnapshotStage) {
    val event = fixture.eventLog.events().single { event ->
        event.isRunnerPhaseCompleted("standard_material_snapshot")
    }
    val details = event.payloadJsonObject().getValue("details").jsonObject

    assertEquals("failed", details.stringValue("outcome"))
    assertEquals(stage.name, details.stringValue("failureStage"))
    assertEquals("STANDARD_SNAPSHOT_${stage.name}_FAILED", details.stringValue("failureCode"))
    assertFalse(event.payload.contains("manifest unavailable"))
    assertFalse(event.payload.contains("fixture market failure"))
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

private object MaterialManifestMarketDataSource : MarketDataSource by FakeMarketDataSource {
    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(
            (0 until 64).map { index ->
                Candle(
                    symbol = symbol.apiSymbol,
                    interval = interval,
                    openTime = fixedInstant().plusSeconds(index * 300L).toString(),
                    open = "10000000",
                    high = "10100000",
                    low = "9900000",
                    close = "10000000",
                    volume = "1",
                )
            },
        )
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(
            Orderbook(
                symbol = symbol.apiSymbol,
                bids = listOf(OrderbookLevel(price = "9990000", size = "0.1")),
                asks = listOf(OrderbookLevel(price = "10000000", size = "0.2")),
            ),
        )
    }
}

private object FailingMaterialMarketDataSource : MarketDataSource by FakeMarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.failure(GmoRateLimitException("fixture market failure"))
    }
}

private class BlockingSnapshotMarketDataSource : MarketDataSource by FakeMarketDataSource {
    val tickerEntered = CompletableDeferred<Unit>()

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        tickerEntered.complete(Unit)
        awaitCancellation()
    }
}

private class ClaimLossSnapshotMarketDataSource : MarketDataSource by MaterialManifestMarketDataSource {
    var captureCompleted: Boolean = false
        private set

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return MaterialManifestMarketDataSource.getOrderbook(symbol, depth).also {
            captureCompleted = true
        }
    }
}

private object MalformedTickerMarketDataSource : MarketDataSource by MaterialManifestMarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return MaterialManifestMarketDataSource.getTicker(symbol).map { ticker -> ticker.copy(last = "not-a-number") }
    }
}

private object MalformedCandleMarketDataSource : MarketDataSource by MaterialManifestMarketDataSource {
    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return MaterialManifestMarketDataSource.getCandles(symbol, interval, limit).map { candles ->
            candles.mapIndexed { index, candle -> if (index == 0) candle.copy(high = "malformed") else candle }
        }
    }
}

private class RateLimitExhaustedOrderbookMarketDataSource : MarketDataSource by FakeMarketDataSource {
    var correlation: GmoPublicRequestCorrelation? = null
        private set
    var orderbookAttemptCount: Int = 0
        private set

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        orderbookAttemptCount += 1
        correlation = currentCoroutineContext()[GmoPublicRequestCorrelation]

        return Result.failure(GmoRateLimitException("GMO ORDERBOOK request was rate limited by HTTP_429."))
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
