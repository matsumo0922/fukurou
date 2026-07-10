package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.OneShotRunnerResult
import me.matsumo.fukurou.trading.runner.OneShotRunnerStatus
import me.matsumo.fukurou.trading.safety.EconomicEventBlackout
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * LlmDaemonScheduler の cadence / reservation contract を検証するテスト。
 */
class LlmDaemonSchedulerTest {

    @Test
    fun invocationObserverFinishesWhenReservationPersistenceFails() = runBlocking {
        val baseReservations = InMemoryLlmLaunchReservationRepository(
            InMemoryRiskStateRepository(MutableClock(fixedInstant())),
        )
        val failingReservations = object : LlmLaunchReservationRepository by baseReservations {
            override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> {
                return Result.failure(IllegalStateException("reservation finish failed"))
            }
        }
        val finishedInvocations = mutableListOf<String>()
        val fixture = schedulerFixture(
            reservations = failingReservations,
            observer = object : LlmDaemonSchedulerObserver {
                override fun onInvocationFinished(invocationId: String, finishedAt: Instant) {
                    finishedInvocations += invocationId
                }
            },
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Skipped>(result)
        assertEquals(listOf("00000000-0000-0000-0000-000000000001"), finishedInvocations)
    }

    @Test
    fun flatState_launchesOnlyOnFifteenMinuteHeartbeatWhenNoEventExists() = runBlocking {
        val fixture = schedulerFixture()

        val firstResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(14))
        val waitingResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(1))
        val secondResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(firstResult)
        assertIs<LlmDaemonTickResult.Skipped>(waitingResult)
        assertIs<LlmDaemonTickResult.Launched>(secondResult)
        assertEquals(2, fixture.launches.size)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, firstResult.triggerKind)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, secondResult.triggerKind)
    }

    @Test
    fun eventTriggerLaunchesWithinFifteenMinuteBudget() = runBlocking {
        val eventAt = fixedInstant().plus(Duration.ofMinutes(10))
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                events = listOf(
                    EconomicEventBlackout(
                        eventId = "cpi-20260703",
                        eventName = "CPI",
                        eventAt = eventAt,
                        blackoutBefore = Duration.ZERO,
                        blackoutAfter = Duration.ofMinutes(60),
                    ),
                ),
            ),
        )

        val heartbeatResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(10))
        val eventResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(heartbeatResult)
        assertIs<LlmDaemonTickResult.Launched>(eventResult)
        assertEquals(2, fixture.launches.size)
        assertEquals(LlmDaemonTriggerKind.ECONOMIC_EVENT, eventResult.triggerKind)
    }

    @Test
    fun eventTriggerUsesSameHourlyBudgetWhenCapIsLowered() = runBlocking {
        val eventAt = fixedInstant().plus(Duration.ofMinutes(10))
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                runner = LlmRunnerConfig(maxInvocationsPerHour = 1),
                events = listOf(
                    EconomicEventBlackout(
                        eventId = "cpi-20260703",
                        eventName = "CPI",
                        eventAt = eventAt,
                        blackoutBefore = Duration.ZERO,
                        blackoutAfter = Duration.ofMinutes(60),
                    ),
                ),
            ),
        )

        val heartbeatResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(10))
        val eventResult = fixture.scheduler.tick()
        val skipEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED }

        assertIs<LlmDaemonTickResult.Launched>(heartbeatResult)
        assertIs<LlmDaemonTickResult.Skipped>(eventResult)
        assertEquals("max_invocations_per_hour_exceeded", eventResult.reason)
        assertEquals(1, fixture.launches.size)
        assertTrue(skipEvents.any { event -> event.payload.contains("max_invocations_per_hour_exceeded") })
    }

    @Test
    fun holdingStateUsesDenseFifteenMinuteCadence() = runBlocking {
        val fixture = schedulerFixture(hasOpenRisk = true)

        val firstResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(14))
        val waitingResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(1))
        val secondResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(firstResult)
        assertIs<LlmDaemonTickResult.Skipped>(waitingResult)
        assertIs<LlmDaemonTickResult.Launched>(secondResult)
        assertEquals(2, fixture.launches.size)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, firstResult.triggerKind)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, secondResult.triggerKind)
    }

    @Test
    fun entryFillTriggerLaunchesOnceWithAuditDetails() = runBlocking {
        val entryFillReader = FakeEntryFillReader()
        entryFillReader.currentEntryFill = entryFill(
            executionId = "execution-entry-1",
            orderId = "order-entry-1",
            positionId = "position-entry-1",
            executedAt = fixedInstant(),
        )
        val fixture = schedulerFixture(
            hasOpenRisk = true,
            entryFillReader = entryFillReader,
        )

        val firstResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(15))
        val duplicateResult = fixture.scheduler.tick()
        val payload = fixture.launchedPayload(LlmDaemonTriggerKind.ENTRY_FILL)
        val details = payload.getValue("details").jsonObject

        assertIs<LlmDaemonTickResult.Launched>(firstResult)
        assertEquals(LlmDaemonTriggerKind.ENTRY_FILL, firstResult.triggerKind)
        assertIs<LlmDaemonTickResult.Launched>(duplicateResult)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, duplicateResult.triggerKind)
        assertEquals(2, fixture.launches.size)
        assertEquals(1, fixture.entryFillLaunchCount())
        assertEquals("entry-fill", payload.stringValue("triggerKey"))
        assertEquals("execution-entry-1", details.stringValue("executionId"))
        assertEquals("order-entry-1", details.stringValue("orderId"))
        assertEquals("position-entry-1", details.stringValue("positionId"))
        assertEquals(fixedInstant().toString(), details.stringValue("executedAt"))
    }

    @Test
    fun entryFillTriggerDoesNotBypassHourlyLaunchCap() = runBlocking {
        val entryFillReader = FakeEntryFillReader()
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                runner = LlmRunnerConfig(maxInvocationsPerHour = 1),
            ),
            hasOpenRisk = true,
            entryFillReader = entryFillReader,
        )

        val holdingResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(1))
        entryFillReader.currentEntryFill = entryFill(
            executionId = "execution-capped",
            executedAt = fixture.clock.instant(),
        )
        val cappedResult = fixture.scheduler.tick()
        val skipEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED }

        assertIs<LlmDaemonTickResult.Launched>(holdingResult)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, holdingResult.triggerKind)
        assertIs<LlmDaemonTickResult.Skipped>(cappedResult)
        assertEquals(LlmDaemonTriggerKind.ENTRY_FILL, cappedResult.triggerKind)
        assertEquals("max_invocations_per_hour_exceeded", cappedResult.reason)
        assertEquals(1, fixture.launches.size)
        assertTrue(skipEvents.any { event -> event.payload.contains("max_invocations_per_hour_exceeded") })
        assertTrue(skipEvents.any { event -> event.payload.contains("ENTRY_FILL") })
    }

    @Test
    fun entryFillCooldownSuppressesBurstFillsAfterLaunch() = runBlocking {
        val entryFillReader = FakeEntryFillReader()
        entryFillReader.currentEntryFill = entryFill(
            executionId = "execution-entry-1",
            executedAt = fixedInstant(),
        )
        val fixture = schedulerFixture(
            hasOpenRisk = true,
            entryFillReader = entryFillReader,
        )

        val firstResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofSeconds(30))
        entryFillReader.currentEntryFill = entryFill(
            executionId = "execution-entry-burst",
            executedAt = fixture.clock.instant(),
        )
        fixture.clock.advance(Duration.ofMinutes(10))
        val burstResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(firstResult)
        assertEquals(LlmDaemonTriggerKind.ENTRY_FILL, firstResult.triggerKind)
        assertIs<LlmDaemonTickResult.Launched>(burstResult)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, burstResult.triggerKind)
        assertEquals(2, fixture.launches.size)
        assertEquals(1, fixture.entryFillLaunchCount())
    }

    @Test
    fun preFilterYesRunsFullFlatHeartbeat() = runBlocking {
        val preFilter = FakePreFilter()
        preFilter.decision = LlmDaemonPreFilterDecision.RUN_FULL
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    priceMoveTriggerEnabled = false,
                    preFilterEnabled = true,
                    stopProximityTriggerEnabled = false,
                ),
            ),
            preFilter = preFilter,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
        assertEquals(1, fixture.launches.size)
        assertEquals(1, preFilter.requests.size)
    }

    @Test
    fun preFilterNoSkipsFlatHeartbeatWithAuditReason() = runBlocking {
        val preFilter = FakePreFilter()
        preFilter.decision = LlmDaemonPreFilterDecision.SKIP_NO_CHANGE
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    priceMoveTriggerEnabled = false,
                    preFilterEnabled = true,
                    stopProximityTriggerEnabled = false,
                ),
            ),
            preFilter = preFilter,
        )

        val result = fixture.scheduler.tick()
        val skipEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED }

        assertIs<LlmDaemonTickResult.Skipped>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
        assertEquals("pre_filter_no_change", result.reason)
        assertEquals(0, fixture.launches.size)
        assertEquals(1, preFilter.requests.size)
        assertTrue(skipEvents.any { event -> event.payload.contains("pre_filter_no_change") })
    }

    @Test
    fun preFilterFailureFailsOpenToFullHoldingCheck() = runBlocking {
        val preFilter = FakePreFilter()
        preFilter.forcedThrowable = IllegalStateException("haiku timeout")
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    priceMoveTriggerEnabled = false,
                    preFilterEnabled = true,
                    stopProximityTriggerEnabled = false,
                ),
            ),
            hasOpenRisk = true,
            preFilter = preFilter,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, result.triggerKind)
        assertEquals(1, fixture.launches.size)
        assertEquals(1, preFilter.requests.size)
    }

    @Test
    fun preFilterDoesNotRunForEventTriggers() = runBlocking {
        val preFilter = FakePreFilter()
        preFilter.decision = LlmDaemonPreFilterDecision.SKIP_NO_CHANGE
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    priceMoveTriggerEnabled = false,
                    preFilterEnabled = true,
                    stopProximityTriggerEnabled = false,
                ),
                events = listOf(
                    EconomicEventBlackout(
                        eventId = "cpi-20260703",
                        eventName = "CPI",
                        eventAt = fixedInstant(),
                        blackoutBefore = Duration.ZERO,
                        blackoutAfter = Duration.ofMinutes(60),
                    ),
                ),
            ),
            preFilter = preFilter,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.ECONOMIC_EVENT, result.triggerKind)
        assertEquals(1, fixture.launches.size)
        assertEquals(0, preFilter.requests.size)
    }

    @Test
    fun disabledPreFilterKeepsHeartbeatBehavior() = runBlocking {
        val preFilter = FakePreFilter()
        preFilter.decision = LlmDaemonPreFilterDecision.SKIP_NO_CHANGE
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    priceMoveTriggerEnabled = false,
                    preFilterEnabled = false,
                    stopProximityTriggerEnabled = false,
                ),
            ),
            preFilter = preFilter,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
        assertEquals(1, fixture.launches.size)
        assertEquals(0, preFilter.requests.size)
    }

    @Test
    fun hardHaltStopsLaunchAndResumeAllowsLaunchAgain() = runBlocking {
        val fixture = schedulerFixture()

        fixture.riskStateRepository.setHardHalt("test halt", fixedInstant()).getOrThrow()
        val haltedResult = fixture.scheduler.tick()
        fixture.riskStateRepository.resume("operator confirmed recovery", fixedInstant()).getOrThrow()
        val resumedResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Skipped>(haltedResult)
        assertEquals("hard_halt", haltedResult.reason)
        assertIs<LlmDaemonTickResult.Launched>(resumedResult)
        assertEquals(1, fixture.launches.size)
        assertTrue(
            fixture.eventLog.events()
                .any { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED },
        )
    }

    @Test
    fun launchAuditIncludesRuntimeConfigSnapshot() = runBlocking {
        val runtimeConfigSnapshot = RuntimeConfigAuditSnapshot(
            versionId = "runtime-version-1",
            hash = "runtime-hash-1",
        )
        val fixture = schedulerFixture(runtimeConfigSnapshot = runtimeConfigSnapshot)

        val result = fixture.scheduler.tick()
        val launchedEvent = fixture.eventLog.events()
            .single { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED }

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals("runtime-version-1", launchedEvent.decisionRunContext.runtimeConfigVersionId)
        assertEquals("runtime-hash-1", launchedEvent.decisionRunContext.runtimeConfigHash)
    }

    @Test
    fun softHaltSkipsFlatLaunchWithAuditReason() = runBlocking {
        val fixture = schedulerFixture()

        fixture.riskStateRepository.setSoftHalt("operator pause", fixedInstant()).getOrThrow()

        val result = fixture.scheduler.tick()
        val skipEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED }

        assertIs<LlmDaemonTickResult.Skipped>(result)
        assertEquals("soft_halt_flat", result.reason)
        assertEquals(0, fixture.launches.size)
        assertTrue(skipEvents.single().payload.contains("soft_halt_flat"))
    }

    @Test
    fun softHaltAllowsHoldingLaunch() = runBlocking {
        val fixture = schedulerFixture(hasOpenRisk = true)

        fixture.riskStateRepository.setSoftHalt("operator pause", fixedInstant()).getOrThrow()

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, result.triggerKind)
        assertEquals(1, fixture.launches.size)
    }

    @Test
    fun freshRunningReservationSuppressesRepeatedTriggerAudit() = runBlocking {
        val eventAt = fixedInstant().plus(Duration.ofMinutes(5))
        val launchStarted = CompletableDeferred<Unit>()
        val releaseLaunch = CompletableDeferred<Unit>()
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                events = listOf(
                    EconomicEventBlackout(
                        eventId = "cpi-20260703",
                        eventName = "CPI",
                        eventAt = eventAt,
                        blackoutBefore = Duration.ZERO,
                        blackoutAfter = Duration.ofMinutes(60),
                    ),
                ),
            ),
            launchHandler = { request ->
                launchStarted.complete(Unit)
                releaseLaunch.await()
                successfulRunnerResult(request)
            },
        )

        val firstTick = async { fixture.scheduler.tick() }
        launchStarted.await()
        fixture.clock.advance(Duration.ofMinutes(5))
        val secondResult = fixture.scheduler.tick()
        val skipEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED }

        releaseLaunch.complete(Unit)
        firstTick.await()

        assertIs<LlmDaemonTickResult.Skipped>(secondResult)
        assertEquals("no_trigger_due", secondResult.reason)
        assertEquals(1, fixture.launches.size)
        assertTrue(skipEvents.none { event -> event.payload.contains("concurrent_invocation") })
    }

    @Test
    fun noDecisionNoTradeAuditEquivalentContinuesToNextCycle() = runBlocking {
        val fixture = schedulerFixture { request ->
            successfulRunnerResult(request, OneShotRunnerStatus.NO_TRADE_AUDITED)
        }

        val firstResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(15))
        val secondResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(firstResult)
        assertIs<LlmDaemonTickResult.Launched>(secondResult)
        assertEquals(2, fixture.launches.size)
        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED.name, firstResult.status)
        assertEquals(OneShotRunnerStatus.NO_TRADE_AUDITED.name, secondResult.status)
    }

    @Test
    fun oneShotFailureIsAuditedAndNextCycleContinues() = runBlocking {
        var runnerCallCount = 0
        val fixture = schedulerFixture { request ->
            runnerCallCount += 1

            if (runnerCallCount == 1) {
                error("cli auth expired")
            }

            successfulRunnerResult(request)
        }

        val failedResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(15))
        val resumedResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Failed>(failedResult)
        assertEquals("IllegalStateException", failedResult.reason)
        assertIs<LlmDaemonTickResult.Launched>(resumedResult)
        assertEquals(2, fixture.launches.size)
    }

    @Test
    fun transientTickFailureIsAuditedAndNextCycleContinues() = runBlocking {
        var readCount = 0
        val fixture = schedulerFixture(
            openRiskReader = {
                readCount += 1

                if (readCount == 1) {
                    Result.failure(IllegalStateException("temporary broker read failure"))
                } else {
                    Result.success(false)
                }
            },
        )

        val failedResult = fixture.scheduler.tick()
        val resumedResult = fixture.scheduler.tick()
        val skippedEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED }

        assertIs<LlmDaemonTickResult.Skipped>(failedResult)
        assertEquals("tick_failed", failedResult.reason)
        assertIs<LlmDaemonTickResult.Launched>(resumedResult)
        assertEquals(1, fixture.launches.size)
        assertTrue(skippedEvents.any { event -> event.payload.contains("tick_failed") })
    }

    @Test
    fun failedEconomicEventLaunchCanRetryWithinSameWindow() = runBlocking {
        var runnerCallCount = 0
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                events = listOf(
                    EconomicEventBlackout(
                        eventId = "cpi-20260703",
                        eventName = "CPI",
                        eventAt = fixedInstant(),
                        blackoutBefore = Duration.ZERO,
                        blackoutAfter = Duration.ofHours(2),
                    ),
                ),
            ),
            launchHandler = { request ->
                runnerCallCount += 1

                if (runnerCallCount == 1) {
                    error("temporary cli failure")
                }

                successfulRunnerResult(request)
            },
        )

        val failedResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofMinutes(15))
        val retriedResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Failed>(failedResult)
        assertIs<LlmDaemonTickResult.Launched>(retriedResult)
        assertEquals(2, fixture.launches.size)
        assertEquals(LlmDaemonTriggerKind.ECONOMIC_EVENT, retriedResult.triggerKind)
    }

    @Test
    fun priceMoveTriggerLaunchesForUpAndDownMovesWithAuditDetails() = runBlocking {
        val upFixture = schedulerFixture()
        upFixture.scheduler.tick()
        upFixture.clock.advance(Duration.ofSeconds(300))
        upFixture.tickerReader.currentPriceJpy = BigDecimal("10100000")

        val upResult = upFixture.scheduler.tick()
        val upPayload = upFixture.launchedPayload(LlmDaemonTriggerKind.PRICE_MOVE)
        val upDetails = upPayload.getValue("details").jsonObject

        assertIs<LlmDaemonTickResult.Launched>(upResult)
        assertEquals(LlmDaemonTriggerKind.PRICE_MOVE, upResult.triggerKind)
        assertEquals("price-move", upPayload.stringValue("triggerKey"))
        assertEquals("0.01000000", upDetails.stringValue("changeRatio"))
        assertEquals("300", upDetails.stringValue("windowSeconds"))
        assertEquals("10000000", upDetails.stringValue("basePriceJpy"))
        assertEquals("10100000", upDetails.stringValue("currentPriceJpy"))

        val downFixture = schedulerFixture()
        downFixture.scheduler.tick()
        downFixture.clock.advance(Duration.ofSeconds(300))
        downFixture.tickerReader.currentPriceJpy = BigDecimal("9900000")

        val downResult = downFixture.scheduler.tick()
        val downPayload = downFixture.launchedPayload(LlmDaemonTriggerKind.PRICE_MOVE)
        val downDetails = downPayload.getValue("details").jsonObject

        assertIs<LlmDaemonTickResult.Launched>(downResult)
        assertEquals(LlmDaemonTriggerKind.PRICE_MOVE, downResult.triggerKind)
        assertEquals("price-move", downPayload.stringValue("triggerKey"))
        assertEquals("-0.01000000", downDetails.stringValue("changeRatio"))
        assertEquals("10000000", downDetails.stringValue("basePriceJpy"))
        assertEquals("9900000", downDetails.stringValue("currentPriceJpy"))
    }

    @Test
    fun priceMoveKeepsWindowAgedBaseSampleAcrossTickJitter() = runBlocking {
        val fixture = schedulerFixture()

        fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofSeconds(301))
        fixture.tickerReader.currentPriceJpy = BigDecimal("10100000")
        val result = fixture.scheduler.tick()
        val payload = fixture.launchedPayload(LlmDaemonTriggerKind.PRICE_MOVE)
        val details = payload.getValue("details").jsonObject

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.PRICE_MOVE, result.triggerKind)
        assertEquals("0.01000000", details.stringValue("changeRatio"))
        assertEquals("10000000", details.stringValue("basePriceJpy"))
        assertEquals("10100000", details.stringValue("currentPriceJpy"))
    }

    @Test
    fun priceMoveBelowThresholdFallsBackToFlatHeartbeatWhenHeartbeatIsDue() = runBlocking {
        val fixture = schedulerFixture()

        fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofSeconds(300))
        fixture.tickerReader.currentPriceJpy = BigDecimal("10090000")
        val belowThresholdResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofSeconds(300))
        val waitingResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofSeconds(300))
        val heartbeatResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Skipped>(belowThresholdResult)
        assertIs<LlmDaemonTickResult.Skipped>(waitingResult)
        assertIs<LlmDaemonTickResult.Launched>(heartbeatResult)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, heartbeatResult.triggerKind)
        assertEquals(2, fixture.launches.size)
    }

    @Test
    fun priceMoveDoesNotFireUntilRestartedSchedulerAccumulatesWindowAgedSamples() = runBlocking {
        val firstFixture = schedulerFixture()
        firstFixture.scheduler.tick()
        firstFixture.clock.advance(Duration.ofSeconds(60))
        firstFixture.tickerReader.currentPriceJpy = BigDecimal("12000000")
        val restartedFixture = schedulerFixture(
            clock = firstFixture.clock,
            riskStateRepository = firstFixture.riskStateRepository,
            eventLog = firstFixture.eventLog,
            reservations = firstFixture.reservations,
            launches = firstFixture.launches,
            idGenerator = firstFixture.idGenerator,
        )
        restartedFixture.tickerReader.currentPriceJpy = BigDecimal("12000000")

        val restartedResult = restartedFixture.scheduler.tick()
        restartedFixture.clock.advance(Duration.ofSeconds(240))
        restartedFixture.tickerReader.currentPriceJpy = BigDecimal("12100000")
        val beforeWindowResult = restartedFixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Skipped>(restartedResult)
        assertIs<LlmDaemonTickResult.Skipped>(beforeWindowResult)
        assertEquals(1, restartedFixture.launches.size)
    }

    @Test
    fun priceMoveCooldownUsesPersistedReservationsAcrossSchedulerInstances() = runBlocking {
        val fixture = schedulerFixture()
        fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofSeconds(300))
        fixture.tickerReader.currentPriceJpy = BigDecimal("10100000")
        val firstPriceMoveResult = fixture.scheduler.tick()
        val restartedFixture = schedulerFixture(
            clock = fixture.clock,
            riskStateRepository = fixture.riskStateRepository,
            eventLog = fixture.eventLog,
            reservations = fixture.reservations,
            launches = fixture.launches,
            idGenerator = fixture.idGenerator,
        )

        restartedFixture.tickerReader.currentPriceJpy = BigDecimal("10000000")
        restartedFixture.scheduler.tick()
        restartedFixture.clock.advance(Duration.ofSeconds(300))
        restartedFixture.tickerReader.currentPriceJpy = BigDecimal("10200000")
        val withinCooldownResult = restartedFixture.scheduler.tick()
        restartedFixture.clock.advance(Duration.ofSeconds(300))
        restartedFixture.tickerReader.currentPriceJpy = BigDecimal("10400000")
        val afterCooldownResult = restartedFixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(firstPriceMoveResult)
        assertEquals(LlmDaemonTriggerKind.PRICE_MOVE, firstPriceMoveResult.triggerKind)
        assertIs<LlmDaemonTickResult.Skipped>(withinCooldownResult)
        assertIs<LlmDaemonTickResult.Launched>(afterCooldownResult)
        assertEquals(LlmDaemonTriggerKind.PRICE_MOVE, afterCooldownResult.triggerKind)
        assertEquals(3, restartedFixture.launches.size)
    }

    @Test
    fun stopProximityTriggerLaunchesForHoldingPositionWithAuditDetails() = runBlocking {
        val positionsReader = FakePositionsReader()
        positionsReader.currentPositions = listOf(
            position(
                positionId = "position-close-to-stop",
                averageEntryPriceJpy = "100",
                currentStopLossJpy = "90",
            ),
        )
        val fixture = schedulerFixture(
            hasOpenRisk = true,
            positionsReader = positionsReader,
        )
        fixture.tickerReader.currentPriceJpy = BigDecimal("93")

        val result = fixture.scheduler.tick()
        val payload = fixture.launchedPayload(LlmDaemonTriggerKind.STOP_PROXIMITY)
        val details = payload.getValue("details").jsonObject

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.STOP_PROXIMITY, result.triggerKind)
        assertEquals("stop-proximity", payload.stringValue("triggerKey"))
        assertEquals("position-close-to-stop", details.stringValue("positionId"))
        assertEquals("0.30000000", details.stringValue("remainingR"))
        assertEquals("90", details.stringValue("stopLossJpy"))
        assertEquals("93", details.stringValue("currentPriceJpy"))
    }

    @Test
    fun stopProximityAboveThresholdFallsBackToHoldingDenseCheck() = runBlocking {
        val positionsReader = FakePositionsReader()
        positionsReader.currentPositions = listOf(
            position(
                averageEntryPriceJpy = "100",
                currentStopLossJpy = "90",
            ),
        )
        val fixture = schedulerFixture(
            hasOpenRisk = true,
            positionsReader = positionsReader,
        )
        fixture.tickerReader.currentPriceJpy = BigDecimal("94")

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, result.triggerKind)
    }

    @Test
    fun flatStateDoesNotEvaluateStopProximity() = runBlocking {
        val positionsReader = FakePositionsReader()
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    priceMoveTriggerEnabled = false,
                    stopProximityTriggerEnabled = true,
                ),
            ),
            hasOpenRisk = false,
            positionsReader = positionsReader,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
        assertEquals(0, positionsReader.callCount)
        assertEquals(0, fixture.tickerReader.callCount)
    }

    @Test
    fun stopProximitySkipsMissingStopAndInvalidOneR() = runBlocking {
        val positionsReader = FakePositionsReader()
        positionsReader.currentPositions = listOf(
            position(
                positionId = "no-stop",
                averageEntryPriceJpy = "100",
                currentStopLossJpy = null,
            ),
            position(
                positionId = "invalid-one-r",
                averageEntryPriceJpy = "100",
                currentStopLossJpy = "100",
            ),
        )
        val fixture = schedulerFixture(
            hasOpenRisk = true,
            positionsReader = positionsReader,
        )
        fixture.tickerReader.currentPriceJpy = BigDecimal("100")

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, result.triggerKind)
    }

    @Test
    fun holdingPriorityChoosesStopProximityBeforePriceMove() = runBlocking {
        val positionsReader = FakePositionsReader()
        positionsReader.currentPositions = listOf(
            position(
                averageEntryPriceJpy = "100",
                currentStopLossJpy = "80",
            ),
        )
        val fixture = schedulerFixture(
            hasOpenRisk = true,
            positionsReader = positionsReader,
        )
        fixture.tickerReader.currentPriceJpy = BigDecimal("100")
        fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofSeconds(300))
        positionsReader.currentPositions = listOf(
            position(
                averageEntryPriceJpy = "100",
                currentStopLossJpy = "90",
            ),
        )
        fixture.tickerReader.currentPriceJpy = BigDecimal("93")

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.STOP_PROXIMITY, result.triggerKind)
    }

    @Test
    fun flatPriorityChoosesEconomicEventBeforePriceMove() = runBlocking {
        val eventAt = fixedInstant().plus(Duration.ofSeconds(300))
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                events = listOf(
                    EconomicEventBlackout(
                        eventId = "cpi-20260703",
                        eventName = "CPI",
                        eventAt = eventAt,
                        blackoutBefore = Duration.ZERO,
                        blackoutAfter = Duration.ofMinutes(60),
                    ),
                ),
            ),
        )
        fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofSeconds(300))
        fixture.tickerReader.currentPriceJpy = BigDecimal("10100000")

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.ECONOMIC_EVENT, result.triggerKind)
    }

    @Test
    fun tickerFetchFailureFallsBackToFlatHeartbeat() = runBlocking {
        val fixture = schedulerFixture()
        fixture.tickerReader.forcedResult = Result.failure(IllegalStateException("ticker unavailable"))

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
        assertEquals(1, fixture.tickerReader.callCount)
    }

    @Test
    fun tickerReaderThrowFallsBackToFlatHeartbeat() = runBlocking {
        val fixture = schedulerFixture()
        fixture.tickerReader.forcedThrowable = IllegalStateException("ticker reader crashed")

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
        assertEquals(1, fixture.tickerReader.callCount)
    }

    @Test
    fun positionsReaderThrowFallsBackToHoldingDenseCheck() = runBlocking {
        val positionsReader = FakePositionsReader()
        positionsReader.forcedThrowable = IllegalStateException("positions reader crashed")
        val fixture = schedulerFixture(
            hasOpenRisk = true,
            positionsReader = positionsReader,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, result.triggerKind)
        assertEquals(1, positionsReader.callCount)
    }

    @Test
    fun tickerTimestampParseFailureFallsBackToFlatHeartbeat() = runBlocking {
        val fixture = schedulerFixture()
        fixture.tickerReader.sourceTimestamp = null
        fixture.tickerReader.forcedResult = Result.success(
            LlmDaemonTickerSnapshot(
                lastPriceJpy = BigDecimal("10000000"),
                sourceTimestamp = null,
            ),
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
    }

    @Test
    fun staleTickerFallsBackToFlatHeartbeat() = runBlocking {
        val fixture = schedulerFixture()
        fixture.tickerReader.sourceTimestamp = fixedInstant().minus(Duration.ofSeconds(6))

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
    }

    @Test
    fun disabledMarketTriggersDoNotFetchTicker() = runBlocking {
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    priceMoveTriggerEnabled = false,
                    stopProximityTriggerEnabled = false,
                ),
            ),
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
        assertEquals(0, fixture.tickerReader.callCount)
    }

    @Test
    fun priceMoveDoesNotBypassDailyLaunchCap() = runBlocking {
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                runner = LlmRunnerConfig(maxInvocationsPerDay = 1),
            ),
        )
        fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofSeconds(300))
        fixture.tickerReader.currentPriceJpy = BigDecimal("10100000")

        val result = fixture.scheduler.tick()
        val skipEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED }

        assertIs<LlmDaemonTickResult.Skipped>(result)
        assertEquals(LlmDaemonTriggerKind.PRICE_MOVE, result.triggerKind)
        assertEquals("max_invocations_per_day_exceeded", result.reason)
        assertTrue(skipEvents.any { event -> event.payload.contains("max_invocations_per_day_exceeded") })
    }
}

private suspend fun SchedulerFixture.launchedPayload(triggerKind: LlmDaemonTriggerKind): JsonObject {
    return eventLog.events()
        .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED }
        .map { event -> Json.parseToJsonElement(event.payload).jsonObject }
        .last { payload -> payload.stringValue("triggerKind") == triggerKind.name }
}

private suspend fun SchedulerFixture.entryFillLaunchCount(): Int {
    return eventLog.events()
        .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED }
        .map { event -> Json.parseToJsonElement(event.payload).jsonObject }
        .count { payload -> payload.stringValue("triggerKind") == LlmDaemonTriggerKind.ENTRY_FILL.name }
}

private fun JsonObject.stringValue(fieldName: String): String {
    return getValue(fieldName).jsonPrimitive.content
}

private fun entryFill(
    executionId: String = "execution-entry-1",
    orderId: String? = "order-entry-1",
    positionId: String? = "position-entry-1",
    executedAt: Instant = fixedInstant(),
): LlmDaemonEntryFill {
    val execution = Execution(
        executionId = executionId,
        orderId = orderId,
        positionId = positionId,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        priceJpy = "10000000",
        sizeBtc = "0.01",
        feeJpy = "0",
        realizedPnlJpy = "0",
        liquidity = ExecutionLiquidity.MAKER,
        executedAt = executedAt.toString(),
    )

    return requireNotNull(execution.toLlmDaemonEntryFillOrNull())
}

private fun position(
    positionId: String = "position-1",
    averageEntryPriceJpy: String,
    currentStopLossJpy: String?,
): Position {
    return Position(
        positionId = positionId,
        tradeGroupId = "trade-group-1",
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = fixedInstant().toString(),
        closedAt = null,
        sizeBtc = "0.01",
        averageEntryPriceJpy = averageEntryPriceJpy,
        currentPriceJpy = averageEntryPriceJpy,
        currentStopLossJpy = currentStopLossJpy,
        currentTakeProfitJpy = null,
        unrealizedPnlJpy = "0",
        unrealizedR = "0",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = averageEntryPriceJpy,
        lowestPriceSinceEntryJpy = averageEntryPriceJpy,
    )
}

private fun schedulerFixture(
    tradingConfig: TradingBotConfig = tradingConfig(),
    clock: MutableClock = MutableClock(fixedInstant()),
    riskStateRepository: InMemoryRiskStateRepository = InMemoryRiskStateRepository(clock),
    eventLog: InMemoryCommandEventLog = InMemoryCommandEventLog(),
    reservations: LlmLaunchReservationRepository = InMemoryLlmLaunchReservationRepository(riskStateRepository),
    launches: MutableList<OneShotRunnerRequest> = mutableListOf(),
    idGenerator: () -> UUID = deterministicIds(),
    hasOpenRisk: Boolean = false,
    openRiskReader: LlmDaemonOpenRiskReader = { Result.success(hasOpenRisk) },
    tickerReader: FakeTickerReader = FakeTickerReader(clock),
    positionsReader: FakePositionsReader = FakePositionsReader(),
    entryFillReader: FakeEntryFillReader = FakeEntryFillReader(),
    preFilter: FakePreFilter = FakePreFilter(),
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    observer: LlmDaemonSchedulerObserver = object : LlmDaemonSchedulerObserver {},
    launchHandler: suspend (OneShotRunnerRequest) -> OneShotRunnerResult = { request -> successfulRunnerResult(request) },
): SchedulerFixture {
    val scheduler = LlmDaemonScheduler(
        tradingConfig = tradingConfig,
        runtimeConfigSnapshot = runtimeConfigSnapshot,
        dependencies = LlmDaemonSchedulerDependencies(
            riskStateRepository = riskStateRepository,
            commandEventLog = eventLog,
            launchReservationRepository = reservations,
            openRiskReader = openRiskReader,
            tickerReader = tickerReader,
            positionsReader = positionsReader,
            entryFillReader = entryFillReader,
        ),
        runtime = LlmDaemonSchedulerRuntime(
            requestBase = OneShotRunnerRequest(
                repositoryRoot = Path.of(".").toAbsolutePath().normalize(),
                workingDirectory = Path.of(".").toAbsolutePath().normalize(),
                mcpJarPath = "mcp/build/libs/fukurou-mcp-all.jar",
            ),
            launchOneShot = { request ->
                launches += request
                Result.success(launchHandler(request))
            },
            preFilter = preFilter,
            clock = clock,
            idGenerator = idGenerator,
            observer = observer,
        ),
    )

    return SchedulerFixture(
        scheduler = scheduler,
        clock = clock,
        riskStateRepository = riskStateRepository,
        eventLog = eventLog,
        reservations = reservations,
        launches = launches,
        idGenerator = idGenerator,
        tickerReader = tickerReader,
        positionsReader = positionsReader,
        entryFillReader = entryFillReader,
        preFilter = preFilter,
    )
}

private fun tradingConfig(
    runner: LlmRunnerConfig = LlmRunnerConfig(),
    events: List<EconomicEventBlackout> = emptyList(),
    daemon: LlmDaemonConfig = LlmDaemonConfig(enabled = true),
): TradingBotConfig {
    return TradingBotConfig(
        runner = runner,
        safetyFloor = SafetyFloorConfig(economicEventBlackouts = events),
        daemon = daemon,
    )
}

private fun successfulRunnerResult(
    request: OneShotRunnerRequest,
    status: OneShotRunnerStatus = OneShotRunnerStatus.NO_TRADE_DECISION,
): OneShotRunnerResult {
    return OneShotRunnerResult(
        invocationId = requireNotNull(request.invocationId),
        status = status,
        decision = null,
        intent = null,
        tradeResult = null,
    )
}

private fun deterministicIds(): () -> UUID {
    var nextId = 0L

    return {
        nextId += 1
        UUID(0L, nextId)
    }
}

/**
 * scheduler test fixture。
 *
 * @param scheduler test target
 * @param clock 可変 clock
 * @param riskStateRepository risk_state repository
 * @param eventLog command event log
 * @param reservations LLM 起動予約 repository
 * @param launches one-shot 起動 request 一覧
 * @param idGenerator deterministic invocation ID generator
 * @param tickerReader fake ticker reader
 * @param positionsReader fake positions reader
 * @param entryFillReader fake entry fill reader
 * @param preFilter fake pre-filter
 */
private data class SchedulerFixture(
    val scheduler: LlmDaemonScheduler,
    val clock: MutableClock,
    val riskStateRepository: InMemoryRiskStateRepository,
    val eventLog: InMemoryCommandEventLog,
    val reservations: LlmLaunchReservationRepository,
    val launches: MutableList<OneShotRunnerRequest>,
    val idGenerator: () -> UUID,
    val tickerReader: FakeTickerReader,
    val positionsReader: FakePositionsReader,
    val entryFillReader: FakeEntryFillReader,
    val preFilter: FakePreFilter,
)

/**
 * scheduler test 用 fake ticker reader。
 *
 * @param clock source timestamp の既定値に使う clock
 */
private class FakeTickerReader(
    private val clock: Clock,
) : LlmDaemonTickerReader {

    var currentPriceJpy: BigDecimal = BigDecimal("10000000")
    var sourceTimestamp: Instant? = null
    var forcedResult: Result<LlmDaemonTickerSnapshot>? = null
    var forcedThrowable: Throwable? = null
    var callCount: Int = 0

    override suspend fun latestTicker(): Result<LlmDaemonTickerSnapshot> {
        callCount += 1
        forcedThrowable?.let { throwable -> throw throwable }

        return forcedResult ?: Result.success(
            LlmDaemonTickerSnapshot(
                lastPriceJpy = currentPriceJpy,
                sourceTimestamp = sourceTimestamp ?: clock.instant(),
            ),
        )
    }
}

/**
 * scheduler test 用 fake position reader。
 */
private class FakePositionsReader : LlmDaemonPositionsReader {

    var currentPositions: List<Position> = emptyList()
    var forcedThrowable: Throwable? = null
    var callCount: Int = 0

    override suspend fun positions(): Result<List<Position>> {
        callCount += 1
        forcedThrowable?.let { throwable -> throw throwable }

        return Result.success(currentPositions)
    }
}

/**
 * scheduler test 用 fake entry fill reader。
 */
private class FakeEntryFillReader : LlmDaemonEntryFillReader {

    var currentEntryFill: LlmDaemonEntryFill? = null
    var forcedThrowable: Throwable? = null
    var callCount: Int = 0

    override suspend fun latestEntryFill(): Result<LlmDaemonEntryFill?> {
        callCount += 1
        forcedThrowable?.let { throwable -> throw throwable }

        return Result.success(currentEntryFill)
    }
}

/**
 * scheduler test 用 fake pre-filter。
 */
private class FakePreFilter : LlmDaemonPreFilter {

    val requests: MutableList<LlmDaemonPreFilterRequest> = mutableListOf()
    var decision: LlmDaemonPreFilterDecision = LlmDaemonPreFilterDecision.RUN_FULL
    var forcedThrowable: Throwable? = null

    override suspend fun evaluate(request: LlmDaemonPreFilterRequest): Result<LlmDaemonPreFilterDecision> {
        requests += request
        forcedThrowable?.let { throwable -> return Result.failure(throwable) }

        return Result.success(decision)
    }
}

/**
 * fake clock。
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

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-03T00:00:00Z")
}
