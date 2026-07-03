package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.OneShotRunnerResult
import me.matsumo.fukurou.trading.runner.OneShotRunnerStatus
import me.matsumo.fukurou.trading.safety.EconomicEventBlackout
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
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
    fun flatState_launchesOnlyOnSixHourHeartbeatWhenNoEventExists() = runBlocking {
        val fixture = schedulerFixture()

        val firstResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofHours(5))
        val waitingResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofHours(1))
        val secondResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(firstResult)
        assertIs<LlmDaemonTickResult.Skipped>(waitingResult)
        assertIs<LlmDaemonTickResult.Launched>(secondResult)
        assertEquals(2, fixture.launches.size)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, firstResult.triggerKind)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, secondResult.triggerKind)
    }

    @Test
    fun eventTriggerLaunchesAndBudgetSkipIsAudited() = runBlocking {
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
        val skipEvents = fixture.eventLog.events()
            .filter { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED }

        assertIs<LlmDaemonTickResult.Launched>(heartbeatResult)
        assertIs<LlmDaemonTickResult.Skipped>(eventResult)
        assertEquals("max_invocations_per_hour_exceeded", eventResult.reason)
        assertEquals(1, fixture.launches.size)
        assertTrue(skipEvents.any { event -> event.payload.contains("max_invocations_per_hour_exceeded") })
    }

    @Test
    fun holdingStateUsesDenseThreeHourCadence() = runBlocking {
        val fixture = schedulerFixture(hasOpenRisk = true)

        val firstResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofHours(2))
        val waitingResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofHours(1))
        val secondResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(firstResult)
        assertIs<LlmDaemonTickResult.Skipped>(waitingResult)
        assertIs<LlmDaemonTickResult.Launched>(secondResult)
        assertEquals(2, fixture.launches.size)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, firstResult.triggerKind)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, secondResult.triggerKind)
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
    fun concurrentTriggerResultsInExactlyOneInvocation() = runBlocking {
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

        releaseLaunch.complete(Unit)
        firstTick.await()

        assertIs<LlmDaemonTickResult.Skipped>(secondResult)
        assertEquals("concurrent_invocation", secondResult.reason)
        assertEquals(1, fixture.launches.size)
    }

    @Test
    fun noDecisionNoTradeAuditEquivalentContinuesToNextCycle() = runBlocking {
        val fixture = schedulerFixture { request ->
            successfulRunnerResult(request, OneShotRunnerStatus.NO_TRADE_AUDITED)
        }

        val firstResult = fixture.scheduler.tick()
        fixture.clock.advance(Duration.ofHours(6))
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
        fixture.clock.advance(Duration.ofHours(6))
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
            openRiskReader = LlmDaemonOpenRiskReader {
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
        fixture.clock.advance(Duration.ofMinutes(61))
        val retriedResult = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Failed>(failedResult)
        assertIs<LlmDaemonTickResult.Launched>(retriedResult)
        assertEquals(2, fixture.launches.size)
        assertEquals(LlmDaemonTriggerKind.ECONOMIC_EVENT, retriedResult.triggerKind)
    }
}

private fun schedulerFixture(
    tradingConfig: TradingBotConfig = tradingConfig(),
    hasOpenRisk: Boolean = false,
    openRiskReader: LlmDaemonOpenRiskReader = LlmDaemonOpenRiskReader { Result.success(hasOpenRisk) },
    launchHandler: suspend (OneShotRunnerRequest) -> OneShotRunnerResult = { request -> successfulRunnerResult(request) },
): SchedulerFixture {
    val clock = MutableClock(fixedInstant())
    val riskStateRepository = InMemoryRiskStateRepository(clock)
    val eventLog = InMemoryCommandEventLog()
    val reservations = InMemoryLlmLaunchReservationRepository(riskStateRepository)
    val launches = mutableListOf<OneShotRunnerRequest>()
    val scheduler = LlmDaemonScheduler(
        tradingConfig = tradingConfig,
        riskStateRepository = riskStateRepository,
        commandEventLog = eventLog,
        launchReservationRepository = reservations,
        openRiskReader = openRiskReader,
        requestBase = OneShotRunnerRequest(
            repositoryRoot = Path.of(".").toAbsolutePath().normalize(),
            workingDirectory = Path.of(".").toAbsolutePath().normalize(),
            mcpJarPath = "mcp/build/libs/fukurou-mcp-all.jar",
        ),
        launchOneShot = { request ->
            launches += request
            Result.success(launchHandler(request))
        },
        clock = clock,
        idGenerator = deterministicIds(),
    )

    return SchedulerFixture(
        scheduler = scheduler,
        clock = clock,
        riskStateRepository = riskStateRepository,
        eventLog = eventLog,
        launches = launches,
    )
}

private fun tradingConfig(
    runner: LlmRunnerConfig = LlmRunnerConfig(),
    events: List<EconomicEventBlackout> = emptyList(),
): TradingBotConfig {
    return TradingBotConfig(
        runner = runner,
        safetyFloor = SafetyFloorConfig(economicEventBlackouts = events),
        daemon = LlmDaemonConfig(enabled = true),
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
 * @param launches one-shot 起動 request 一覧
 */
private data class SchedulerFixture(
    val scheduler: LlmDaemonScheduler,
    val clock: MutableClock,
    val riskStateRepository: InMemoryRiskStateRepository,
    val eventLog: InMemoryCommandEventLog,
    val launches: MutableList<OneShotRunnerRequest>,
)

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
