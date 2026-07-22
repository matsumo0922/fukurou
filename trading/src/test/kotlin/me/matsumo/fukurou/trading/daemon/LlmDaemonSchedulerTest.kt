package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
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
import me.matsumo.fukurou.trading.risk.HardHaltCleanupState
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.OneShotRunnerResult
import me.matsumo.fukurou.trading.runner.OneShotRunnerStatus
import me.matsumo.fukurou.trading.safety.EconomicEventBlackout
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
import me.matsumo.fukurou.trading.shadow.GateShadowObservation
import me.matsumo.fukurou.trading.shadow.GateShadowRepository
import me.matsumo.fukurou.trading.shadow.GateShadowResolver
import me.matsumo.fukurou.trading.shadow.InMemoryGateShadowRepository
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * LlmDaemonScheduler の cadence / reservation contract を検証するテスト。
 */
class LlmDaemonSchedulerTest {
    @BeforeTest
    fun setUpAdmissionHealth() {
        LlmExecutionAdmissionHealth.resetForTest()
    }

    @AfterTest
    fun tearDownAdmissionHealth() {
        LlmExecutionAdmissionHealth.resetForTest()
    }

    // 8.10: resolver の wall-time 予算超過は fail-open し、同じ tick の launch を継続する。
    @Test
    fun gateShadowBudgetExpiryDoesNotPreventLaunch() = runBlocking {
        val durableRepository = InMemoryGateShadowRepository()
        val delayedRepository = object : GateShadowRepository by durableRepository {
            override suspend fun findUnresolvedObservations(limit: Int): Result<List<GateShadowObservation>> {
                delay(100)
                return durableRepository.findUnresolvedObservations(limit)
            }

            override suspend fun isReceiptScanIndexReady(): Result<Boolean> = Result.success(true)
        }
        val resolver = GateShadowResolver(
            repository = delayedRepository,
            horizon = Duration.ofHours(24),
            settlementGrace = Duration.ofSeconds(300),
            wallTimeBudget = Duration.ofMillis(20),
            maxObservationsPerTick = 10,
            maxReceiptsPerObservation = 10,
        )
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    launchEnabled = true,
                    gateShadowReconciliationBaseline = fixedInstant().minusSeconds(1),
                ),
            ),
            gateShadowRepository = delayedRepository,
            gateShadowResolver = resolver,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(1, fixture.launches.size)
    }

    @Test
    fun gateShadowRunsBeforeLaunchDisabledReturn() = runBlocking {
        val durableRepository = InMemoryGateShadowRepository()
        var resolverCallCount = 0
        val recordingRepository = object : GateShadowRepository by durableRepository {
            override suspend fun findUnresolvedObservations(limit: Int): Result<List<GateShadowObservation>> {
                resolverCallCount += 1
                return durableRepository.findUnresolvedObservations(limit)
            }

            override suspend fun isReceiptScanIndexReady(): Result<Boolean> = Result.success(true)
        }
        val resolver = GateShadowResolver(
            repository = recordingRepository,
            horizon = Duration.ofHours(24),
            settlementGrace = Duration.ofSeconds(300),
            maxObservationsPerTick = 10,
            maxReceiptsPerObservation = 10,
        )
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    launchEnabled = false,
                    gateShadowReconciliationBaseline = fixedInstant().minusSeconds(1),
                ),
            ),
            gateShadowRepository = recordingRepository,
            gateShadowResolver = resolver,
        )

        val result = fixture.scheduler.tick()

        assertEquals(1, resolverCallCount)
        assertEquals(LLM_LAUNCH_DISABLED, assertIs<LlmDaemonTickResult.Skipped>(result).reason)
    }

    @Test
    fun gateShadowIndexProbeFailureDoesNotPreventLaunch() = runBlocking {
        val durableRepository = InMemoryGateShadowRepository()
        val failingProbeRepository = object : GateShadowRepository by durableRepository {
            override suspend fun isReceiptScanIndexReady(): Result<Boolean> {
                return Result.failure(IllegalStateException("synthetic index probe failure"))
            }
        }
        val resolver = GateShadowResolver(
            repository = failingProbeRepository,
            horizon = Duration.ofHours(24),
            settlementGrace = Duration.ofSeconds(300),
            maxObservationsPerTick = 10,
            maxReceiptsPerObservation = 10,
        )
        val fixture = schedulerFixture(
            gateShadowRepository = failingProbeRepository,
            gateShadowResolver = resolver,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(1, fixture.launches.size)
    }

    @Test
    fun completedTick_advancesLiveStatusWithoutChangingInvocationTerminal() = runBlocking {
        val fixture = schedulerFixture()

        fixture.scheduler.tick()
        val firstTerminalCount = fixture.eventLog.events()
            .count { event -> event.eventType == CommandEventType.DAEMON_INVOCATION_COMPLETED }
        fixture.clock.advance(Duration.ofSeconds(1))

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Skipped>(result)
        assertEquals(LlmDaemonTickOutcome.SKIPPED, fixture.tickStatus.snapshot()?.outcome)
        assertEquals(fixture.clock.instant(), fixture.tickStatus.snapshot()?.completedAt)
        assertEquals(
            firstTerminalCount,
            fixture.eventLog.events().count { event -> event.eventType == CommandEventType.DAEMON_INVOCATION_COMPLETED },
        )
    }

    @Test
    fun scheduledMaintenance_suppressesAfterEpisodeAndRestingMaintenanceWithoutReservationOrNoTrade() = runBlocking {
        val observedAt = Instant.parse("2026-07-11T00:00:00Z")
        val availability = RecordingLaunchAvailability(
            scheduledReason = LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
        )
        val deterministicCalls = mutableListOf<String>()
        val resting = LlmDaemonOpenRiskSnapshot(0, listOf(restingEntryOrder()), 0)
        val holding = LlmDaemonOpenRiskSnapshot(1, emptyList(), 0)
        var openRiskReads = 0
        val fixture = schedulerFixture(
            clock = MutableClock(observedAt),
            openRiskReader = {
                openRiskReads += 1
                Result.success(if (openRiskReads == 1) resting else holding)
            },
            restingOrderMaintenanceService = RestingOrderMaintenanceService { _, _ ->
                deterministicCalls += "resting"
                Result.success(RestingSuppressionReason.RESTING_ORDER_STATE_RACE)
            },
            episodeLifecycleObserver = OpportunityEpisodeLifecycleObserver {
                deterministicCalls += "episode"
                Result.success(Unit)
            },
            launchAvailability = availability,
        )

        val result = fixture.scheduler.tick()
        val eventTypes = fixture.eventLog.events().map { event -> event.eventType }

        assertEquals(
            LlmDaemonTickResult.InfrastructureSuppressed(
                LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
                null,
            ),
            result,
        )
        assertEquals(listOf("episode", "resting"), deterministicCalls)
        assertEquals(0, availability.statusCallCount)
        assertTrue(fixture.launches.isEmpty())
        assertEquals(0, eventTypes.count { type -> type == CommandEventType.DAEMON_TRIGGER_LAUNCHED })
        assertEquals(0, eventTypes.count { type -> type == CommandEventType.NO_TRADE_EXIT })
        assertEquals(1, eventTypes.count { type -> type == CommandEventType.DAEMON_LAUNCH_SUPPRESSED })
    }

    @Test
    fun scheduledGate_refreshesWallTimeAfterDeterministicWorkAtWindowStart() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-07-10T23:59:59.999Z"))
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val reservations = RecordingReservationRepository(
            InMemoryLlmLaunchReservationRepository(riskStateRepository),
        )
        val statusReader = SchedulerStatusReader()
        val fixture = schedulerFixture(
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            episodeLifecycleObserver = OpportunityEpisodeLifecycleObserver {
                clock.advance(Duration.ofMillis(1))
                Result.success(Unit)
            },
            launchAvailability = GmoLlmDaemonLaunchAvailability(statusReader),
        )

        val result = fixture.scheduler.tick()

        assertEquals(
            LlmDaemonTickResult.InfrastructureSuppressed(
                LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
                null,
            ),
            result,
        )
        assertEquals(0, statusReader.callCount)
        assertEquals(0, reservations.admissionCallCount)
        assertTrue(fixture.launches.isEmpty())
        assertEquals(0, fixture.eventLog.events().count { event -> event.eventType == CommandEventType.NO_TRADE_EXIT })
    }

    @Test
    fun scheduledGate_refreshesWallTimeAfterDeterministicWorkAtWindowEnd() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-07-11T01:59:59.999Z"))
        val statusReader = SchedulerStatusReader()
        val fixture = schedulerFixture(
            clock = clock,
            episodeLifecycleObserver = OpportunityEpisodeLifecycleObserver {
                clock.advance(Duration.ofMillis(1))
                Result.success(Unit)
            },
            launchAvailability = GmoLlmDaemonLaunchAvailability(statusReader),
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(1, statusReader.callCount)
        assertEquals(1, fixture.launches.size)
    }

    @Test
    fun statusRequestCrossingIntoScheduledWindowSuppressesBeforeReservationOrChild() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-07-10T23:59:59.999Z"))
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val reservations = RecordingReservationRepository(
            InMemoryLlmLaunchReservationRepository(riskStateRepository),
        )
        val statusReader = SchedulerStatusReader(
            onRead = { clock.advance(Duration.ofMillis(1)) },
        )
        val fixture = schedulerFixture(
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            launchAvailability = GmoLlmDaemonLaunchAvailability(statusReader),
        )

        val result = fixture.scheduler.tick()

        assertEquals(
            LlmDaemonTickResult.InfrastructureSuppressed(
                LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
                LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            ),
            result,
        )
        assertEquals(1, statusReader.callCount)
        assertEquals(0, reservations.admissionCallCount)
        assertTrue(fixture.launches.isEmpty())
        assertEquals(0, fixture.eventLog.events().count { event -> event.eventType == CommandEventType.NO_TRADE_EXIT })
    }

    @Test
    fun blockingReservationLookupCrossingIntoScheduledWindowSuppressesBeforeTryReserve() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-07-10T23:59:59.999Z"))
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val reservations = RecordingReservationRepository(
            delegate = InMemoryLlmLaunchReservationRepository(riskStateRepository),
            onFindBlocking = { clock.advance(Duration.ofMillis(1)) },
        )
        val fixture = schedulerFixture(
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            launchAvailability = GmoLlmDaemonLaunchAvailability(SchedulerStatusReader()),
        )

        val result = fixture.scheduler.tick()

        assertEquals(
            LlmDaemonTickResult.InfrastructureSuppressed(
                LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
                LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            ),
            result,
        )
        assertEquals(1, reservations.blockingLookupCallCount)
        assertEquals(0, reservations.tryReserveCallCount)
        assertTrue(reservations.finishRequests.isEmpty())
        assertTrue(fixture.launches.isEmpty())
        assertEquals(0, fixture.eventLog.events().count { event -> event.eventType == CommandEventType.NO_TRADE_EXIT })
    }

    @Test
    fun blockingReservationLookupDelayKeepsTriggerSnapshotAtSelectionTimeAndReservesAtFreshTime() = runBlocking {
        val selectedAt = Instant.parse("2026-07-10T12:00:00Z")
        val clock = MutableClock(selectedAt)
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val reservations = RecordingReservationRepository(
            delegate = InMemoryLlmLaunchReservationRepository(riskStateRepository),
            onFindBlocking = { clock.advance(Duration.ofSeconds(2)) },
        )
        val preFilter = FakePreFilter()
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    launchEnabled = true,
                    preFilterEnabled = true,
                ),
            ),
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            preFilter = preFilter,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        val launchSnapshot = requireNotNull(fixture.launches.single().triggerSnapshot)
        val preFilterSnapshot = requireNotNull(preFilter.requests.single().runnerRequest.triggerSnapshot)
        val reservation = reservations.tryReserveRequests.single()
        val launchedPayload = Json.parseToJsonElement(
            fixture.eventLog.events().single { event ->
                event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED
            }.payload,
        ).jsonObject
        assertEquals(selectedAt, launchSnapshot.observedAt)
        assertEquals(selectedAt.plusSeconds(2), reservation.reservedAt)
        assertTrue(launchSnapshot === preFilterSnapshot)
        assertEquals(
            selectedAt.toString(),
            launchedPayload.getValue("typedTriggerObservedAt").jsonPrimitive.content,
        )
    }

    @Test
    fun tryReserveCrossingIntoScheduledWindowFinishesReservationWithoutChildOrNoTrade() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-07-10T23:59:59.999Z"))
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val repository = InMemoryLlmLaunchReservationRepository(riskStateRepository)
        val reservations = RecordingReservationRepository(
            delegate = repository,
            onTryReserve = { clock.advance(Duration.ofMillis(1)) },
        )
        val fixture = schedulerFixture(
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            launchAvailability = GmoLlmDaemonLaunchAvailability(SchedulerStatusReader()),
        )

        val result = fixture.scheduler.tick()
        val finish = reservations.finishRequests.single()
        val activeReservation = repository.findBlockingRunningReservation(
            requestTriggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            activeSince = clock.instant().minusSeconds(60),
        ).getOrThrow()

        assertEquals(
            LlmDaemonTickResult.InfrastructureSuppressed(
                LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
                LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            ),
            result,
        )
        assertEquals(1, reservations.tryReserveCallCount)
        assertEquals(LlmLaunchReservationStatus.FINISHED, finish.status)
        assertEquals(LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE.name, finish.reason)
        assertEquals(null, activeReservation)
        assertTrue(fixture.launches.isEmpty())
        assertEquals(
            1,
            fixture.eventLog.events().count { event ->
                event.eventType == CommandEventType.DAEMON_LAUNCH_SUPPRESSED &&
                    event.payload.contains("\"reason\":\"SCHEDULED_MAINTENANCE\"")
            },
        )
        assertEquals(
            0,
            fixture.eventLog.events().count { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED },
        )
        assertEquals(0, fixture.eventLog.events().count { event -> event.eventType == CommandEventType.NO_TRADE_EXIT })
    }

    @Test
    fun finalLaunchBoundarySuppressesClockChangeAfterPostReservationAdmission() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-07-10T23:59:59.999Z"))
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val repository = InMemoryLlmLaunchReservationRepository(riskStateRepository)
        val reservations = RecordingReservationRepository(repository)
        val delegate = GmoLlmDaemonLaunchAvailability(SchedulerStatusReader())
        var scheduledCheckCount = 0
        val availability = object : LlmDaemonLaunchAvailability {
            override fun scheduledSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason? {
                scheduledCheckCount += 1
                val reason = delegate.scheduledSuppressionAt(observedAt)
                if (scheduledCheckCount == 4) clock.advance(Duration.ofMillis(1))

                return reason
            }

            override suspend fun statusSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason? {
                return delegate.statusSuppressionAt(observedAt)
            }
        }
        val fixture = schedulerFixture(
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            launchAvailability = availability,
        )

        val result = fixture.scheduler.tick()
        val finish = reservations.finishRequests.single()
        val eventTypes = fixture.eventLog.events().map { event -> event.eventType }
        val activeReservation = repository.findBlockingRunningReservation(
            requestTriggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            activeSince = clock.instant().minusSeconds(60),
        ).getOrThrow()

        assertEquals(
            LlmDaemonTickResult.InfrastructureSuppressed(
                LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE,
                LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            ),
            result,
        )
        assertEquals(LlmLaunchReservationStatus.FINISHED, finish.status)
        assertEquals(LlmDaemonLaunchSuppressionReason.SCHEDULED_MAINTENANCE.name, finish.reason)
        assertEquals(null, activeReservation)
        assertTrue(fixture.launches.isEmpty())
        assertEquals(0, eventTypes.count { type -> type == CommandEventType.DAEMON_TRIGGER_LAUNCHED })
        assertEquals(0, eventTypes.count { type -> type == CommandEventType.NO_TRADE_EXIT })
        assertEquals(1, eventTypes.count { type -> type == CommandEventType.DAEMON_LAUNCH_SUPPRESSED })
    }

    @Test
    fun statusMaintenance_suppressesSelectedCandidateBeforeReservationAndUsesTypedAudit() = runBlocking {
        val availability = RecordingLaunchAvailability(
            statusReason = LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE,
        )
        val fixture = schedulerFixture(launchAvailability = availability)

        val result = fixture.scheduler.tick()
        val suppressionEvent = fixture.eventLog.events().single { event ->
            event.eventType == CommandEventType.DAEMON_LAUNCH_SUPPRESSED
        }

        assertEquals(
            LlmDaemonTickResult.InfrastructureSuppressed(
                LlmDaemonLaunchSuppressionReason.STATUS_MAINTENANCE,
                LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            ),
            result,
        )
        assertEquals(1, availability.statusCallCount)
        assertTrue(fixture.launches.isEmpty())
        assertTrue(suppressionEvent.payload.contains("\"reason\":\"STATUS_MAINTENANCE\""))
        assertTrue(suppressionEvent.payload.contains("\"triggerKind\":\"FLAT_HEARTBEAT\""))
        assertEquals(0, fixture.eventLog.events().count { event -> event.eventType == CommandEventType.NO_TRADE_EXIT })
    }

    @Test
    fun openStatus_allowsAutomaticCandidateLaunch() = runBlocking {
        val availability = RecordingLaunchAvailability()
        val fixture = schedulerFixture(launchAvailability = availability)

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(1, availability.statusCallCount)
        assertEquals(1, fixture.launches.size)
    }

    @Test
    fun globalLaunchGateSkipsBeforeReservationOrChildLaunch() = runBlocking {
        val fixture = schedulerFixture(tradingConfig = TradingBotConfig())

        val result = fixture.scheduler.tick()

        assertEquals(LlmDaemonTickResult.Skipped(LLM_LAUNCH_DISABLED, null), result)
        assertTrue(fixture.launches.isEmpty())
        assertTrue(fixture.eventLog.events().any { event -> event.payload.contains(LLM_LAUNCH_DISABLED) })
    }

    @Test
    fun restingEntryOnly_runsDeterministicMaintenanceWithoutLlmOrPreFilter() = runBlocking {
        val preFilter = FakePreFilter()
        val maintenanceCalls = mutableListOf<LlmDaemonOpenRiskSnapshot>()
        val snapshot = LlmDaemonOpenRiskSnapshot(
            openPositionCount = 0,
            restingEntryOrders = listOf(restingEntryOrder("resting-order-1"), restingEntryOrder("resting-order-2")),
            otherOpenOrderCount = 0,
        )
        val fixture = schedulerFixture(
            openRiskReader = { Result.success(snapshot) },
            preFilter = preFilter,
            restingOrderMaintenanceService = RestingOrderMaintenanceService { observed, _ ->
                maintenanceCalls += observed
                Result.success(RestingSuppressionReason.RESTING_ORDER_UNCHANGED)
            },
        )

        val result = fixture.scheduler.tick()
        fixture.scheduler.tick()

        assertEquals(
            LlmDaemonTickResult.Skipped("resting_order_unchanged", null),
            result,
        )
        assertEquals(listOf(snapshot, snapshot), maintenanceCalls)
        assertTrue(fixture.launches.isEmpty())
        assertTrue(preFilter.requests.isEmpty())
        assertEquals(
            1,
            fixture.eventLog.events().count { event ->
                event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED &&
                    event.payload.contains("resting_order_unchanged")
            },
        )
    }

    @Test
    fun leavingRestingState_resetsMirrorThrottleBeforeSameReasonReentry() = runBlocking {
        val resting = LlmDaemonOpenRiskSnapshot(0, listOf(restingEntryOrder()), 0)
        val flat = LlmDaemonOpenRiskSnapshot(0, emptyList(), 0)
        var reads = 0
        val fixture = schedulerFixture(
            openRiskReader = {
                val snapshot = when (reads++) {
                    0 -> resting
                    1 -> flat
                    else -> resting
                }
                Result.success(snapshot)
            },
            restingOrderMaintenanceService = RestingOrderMaintenanceService { _, _ ->
                Result.success(RestingSuppressionReason.RESTING_ORDER_UNCHANGED)
            },
        )

        fixture.scheduler.tick()
        fixture.scheduler.tick()
        fixture.scheduler.tick()

        assertEquals(
            2,
            fixture.eventLog.events().count { event ->
                event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED &&
                    event.payload.contains("resting_order_unchanged")
            },
        )
    }

    @Test
    fun positionAppearingDuringRestingMaintenanceDoesNotSuppressSafetyFullRun() = runBlocking {
        var reads = 0
        val resting = LlmDaemonOpenRiskSnapshot(0, listOf(restingEntryOrder()), 0)
        val holding = LlmDaemonOpenRiskSnapshot(1, emptyList(), 0)
        val fixture = schedulerFixture(
            openRiskReader = {
                reads += 1
                Result.success(if (reads == 1) resting else holding)
            },
            restingOrderMaintenanceService = RestingOrderMaintenanceService { _, _ ->
                Result.success(RestingSuppressionReason.RESTING_ORDER_STATE_RACE)
            },
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, result.triggerKind)
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
    fun launchThreadsImmutableTriggerAndOpenRiskSnapshotsThroughPreFilterRunAndPostSpawnAudit() = runBlocking {
        var brokerReads = 0
        val eventLog = InMemoryCommandEventLog()
        val preFilter = FakePreFilter()
        val snapshot = LlmDaemonOpenRiskSnapshot(
            openPositionCount = 0,
            restingEntryOrders = emptyList(),
            otherOpenOrderCount = 0,
        )
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    launchEnabled = true,
                    preFilterEnabled = true,
                ),
            ),
            eventLog = eventLog,
            preFilter = preFilter,
            openRiskReader = {
                brokerReads += 1
                Result.success(snapshot)
            },
            launchHandler = { request ->
                assertTrue(
                    eventLog.events().none { event ->
                        event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED
                    },
                )
                successfulRunnerResult(request)
            },
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(1, brokerReads)
        assertEquals(1, fixture.launches.size)
        assertEquals(1, preFilter.requests.size)
        val launchSnapshot = requireNotNull(fixture.launches.single().triggerSnapshot)
        val preFilterSnapshot = requireNotNull(preFilter.requests.single().runnerRequest.triggerSnapshot)
        assertTrue(launchSnapshot === preFilterSnapshot)
        val launched = fixture.eventLog.events().single { event ->
            event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED
        }
        val launchedPayload = Json.parseToJsonElement(launched.payload).jsonObject
        assertEquals(launchSnapshot.kind, launchedPayload.getValue("typedTriggerKind").jsonPrimitive.content)
        assertEquals(
            launchSnapshot.observedAt.toString(),
            launchedPayload.getValue("typedTriggerObservedAt").jsonPrimitive.content,
        )
        assertTrue(launched.payload.contains("\"restingOnly\":false"))
        assertTrue(launched.payload.contains("\"openPositionCount\":0"))
        assertTrue(launched.payload.contains("\"restingEntryOrderCount\":0"))
    }

    @Test
    fun postSpawnLaunchAuditFailureFinishesReservationWithoutRereadingBroker() = runBlocking {
        var brokerReads = 0
        val eventLog = InMemoryCommandEventLog()
        val failingLaunchedAudit = object : CommandEventLog by eventLog {
            override suspend fun append(event: CommandEvent): Result<Unit> {
                return if (event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED) {
                    Result.failure(IllegalStateException("launch audit unavailable"))
                } else {
                    eventLog.append(event)
                }
            }
        }
        val fixture = schedulerFixture(
            eventLog = eventLog,
            commandEventLog = failingLaunchedAudit,
            openRiskReader = {
                brokerReads += 1
                Result.success(LlmDaemonOpenRiskSnapshot(0, emptyList(), 0))
            },
        )

        val result = fixture.scheduler.tick()
        val active = fixture.reservations.findBlockingRunningReservation(
            requestTriggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            activeSince = fixedInstant().minus(Duration.ofHours(1)),
        ).getOrThrow()

        assertEquals(LlmDaemonTickResult.Skipped("tick_failed", null), result)
        assertEquals(1, brokerReads)
        assertEquals(1, fixture.launches.size)
        assertEquals(null, active)
        assertTrue(
            eventLog.events().any { event ->
                event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED && event.payload.contains("tick_failed")
            },
        )
    }

    @Test
    fun holdingEventTriggerLaunchesWithinFifteenMinuteBudget() = runBlocking {
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
            hasOpenRisk = true,
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
    fun holdingEventTriggerUsesSameHourlyBudgetWhenCapIsLowered() = runBlocking {
        val eventAt = fixedInstant().plus(Duration.ofMinutes(10))
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                runner = LlmRunnerConfig(maxInvocationsPerHour = 1, entryFillReservePerHour = 0, stopProximityReservePerHour = 0),
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
            hasOpenRisk = true,
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
    fun holdingEconomicEventRetriesAfterReservationPrecheckRejection() = runBlocking {
        val riskStateRepository = InMemoryRiskStateRepository()
        val backingRepository = InMemoryLlmLaunchReservationRepository(riskStateRepository)
        val reservations = RejectFirstEconomicReservationRepository(backingRepository)
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                events = listOf(
                    EconomicEventBlackout(
                        eventId = "fomc-20260729",
                        eventName = "FOMC",
                        eventAt = fixedInstant(),
                        blackoutBefore = Duration.ZERO,
                        blackoutAfter = Duration.ofHours(1),
                    ),
                ),
            ),
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            idGenerator = { UUID.randomUUID() },
            hasOpenRisk = true,
        )

        val rejected = fixture.scheduler.tick()
        val retried = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Skipped>(rejected)
        assertEquals("max_invocations_per_hour_exceeded", rejected.reason)
        assertIs<LlmDaemonTickResult.Launched>(retried)
        assertEquals(LlmDaemonTriggerKind.ECONOMIC_EVENT, retried.triggerKind)
        assertEquals(1, fixture.launches.size)
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
            tradingConfig = tradingConfig(
                events = listOf(
                    EconomicEventBlackout(
                        eventId = "fomc-20260729",
                        eventName = "FOMC",
                        eventAt = fixedInstant(),
                        blackoutBefore = Duration.ZERO,
                        blackoutAfter = Duration.ofHours(1),
                    ),
                ),
            ),
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
        assertEquals(LlmDaemonTriggerKind.ECONOMIC_EVENT, duplicateResult.triggerKind)
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
                runner = LlmRunnerConfig(maxInvocationsPerHour = 1, entryFillReservePerHour = 0, stopProximityReservePerHour = 0),
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
                    launchEnabled = true,
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
    fun closedReleaseBarrierStartsNoPreFilterChildAndRunsFullHeartbeat() = runBlocking {
        val preFilter = FakePreFilter().apply { decision = LlmDaemonPreFilterDecision.SKIP_NO_CHANGE }
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    launchEnabled = true,
                    priceMoveTriggerEnabled = false,
                    preFilterEnabled = true,
                    stopProximityTriggerEnabled = false,
                ),
            ),
            preFilter = preFilter,
            preFilterReleaseBarrierOpen = false,
        )

        val result = fixture.scheduler.tick()

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, result.triggerKind)
        assertEquals(1, fixture.launches.size)
        assertTrue(preFilter.requests.isEmpty())
    }

    @Test
    fun preFilterNoSkipsFlatHeartbeatWithAuditReason() = runBlocking {
        val preFilter = FakePreFilter()
        preFilter.decision = LlmDaemonPreFilterDecision.SKIP_NO_CHANGE
        val fixture = schedulerFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(
                    enabled = true,
                    launchEnabled = true,
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
        assertEquals(1, fixture.launches.size)
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
                    launchEnabled = true,
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
                    launchEnabled = true,
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
            hasOpenRisk = true,
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
                    launchEnabled = true,
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
        fixture.riskStateRepository.accountStateBoundary.updateRiskState { state ->
            state.copy(hardHaltCleanupState = HardHaltCleanupState.SAFE)
        }
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
    fun freshRunningReservationAuditsBlockedSelectedTrigger() = runBlocking {
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
            hasOpenRisk = true,
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
        assertEquals("concurrent_invocation", secondResult.reason)
        assertEquals(1, fixture.launches.size)
        assertTrue(
            skipEvents.any { event ->
                event.payload.contains("concurrent_invocation") &&
                    event.payload.contains("activeInvocationId") &&
                    event.decisionRunContext.decisionRunId != null
            },
        )
    }

    @Test
    fun reserveRaceAuditsBlockingReservationIdentity() = runBlocking {
        val activeReservation = LlmActiveLaunchReservation(
            invocationId = "already-running",
            triggerKind = LlmDaemonTriggerKind.HOLDING_DENSE_CHECK,
            triggerKey = "holding-dense-check",
            reservedAt = fixedInstant(),
        )
        val fixture = schedulerFixture(
            reservations = RaceRejectingReservationRepository(activeReservation),
        )

        val result = fixture.scheduler.tick()
        val skippedEvent = fixture.eventLog.events().single { event ->
            event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED
        }

        assertIs<LlmDaemonTickResult.Skipped>(result)
        assertEquals("concurrent_invocation", result.reason)
        assertEquals("already-running", Json.parseToJsonElement(skippedEvent.payload).jsonObject.stringValue("activeInvocationId"))
        assertEquals("already-running", skippedEvent.decisionRunContext.decisionRunId)
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
        assertEquals("RUNNER_FAILED", failedResult.reason)
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
                    Result.success(LlmDaemonOpenRiskSnapshot(0, emptyList(), 0))
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
    fun failedEconomicEventLaunchDoesNotRetryWithinSameWindow() = runBlocking {
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
            hasOpenRisk = true,
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
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, retriedResult.triggerKind)
        assertEquals(
            1,
            fixture.launches.count { request -> request.triggerKind == LlmDaemonTriggerKind.ECONOMIC_EVENT },
        )
    }

    @Test
    fun cancelledEconomicEventLaunchDoesNotRetryAfterSchedulerRestart() = runBlocking {
        val clock = MutableClock(fixedInstant())
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val reservations = InMemoryLlmLaunchReservationRepository(riskStateRepository)
        val config = tradingConfig(
            events = listOf(
                EconomicEventBlackout(
                    eventId = "fomc-cancel",
                    eventName = "FOMC",
                    eventAt = fixedInstant(),
                    blackoutBefore = Duration.ZERO,
                    blackoutAfter = Duration.ofHours(1),
                ),
            ),
        )
        val fixture = schedulerFixture(
            tradingConfig = config,
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            idGenerator = { UUID.randomUUID() },
            hasOpenRisk = true,
            launchHandler = { throw CancellationException("scheduler cancelled") },
        )

        val cancellation = runCatching { fixture.scheduler.tick() }.exceptionOrNull()
        val restartedFixture = schedulerFixture(
            tradingConfig = config,
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            idGenerator = { UUID.randomUUID() },
            hasOpenRisk = true,
        )
        val restartedResult = restartedFixture.scheduler.tick()
        val triggerKey = "economic-event:fomc-cancel:${fixedInstant()}"
        val duplicate = reservations.tryReserve(
            LlmLaunchReservationRequest(
                invocationId = "cancel-retry",
                triggerKind = LlmDaemonTriggerKind.ECONOMIC_EVENT,
                triggerKey = triggerKey,
                reservedAt = clock.instant(),
                runnerConfig = config.runner,
                hourlyWindow = Duration.ofHours(1),
                dailyWindow = Duration.ofHours(24),
                activeReservationStaleAfter = config.daemon.launchReservationStaleAfter,
                singleAttemptKey = "ECONOMIC_EVENT:$triggerKey",
            ),
        ).getOrThrow()

        assertIs<CancellationException>(cancellation)
        assertIs<LlmDaemonTickResult.Launched>(restartedResult)
        assertEquals(LlmDaemonTriggerKind.HOLDING_DENSE_CHECK, restartedResult.triggerKind)
        assertEquals(
            LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.TRIGGER_ALREADY_ATTEMPTED),
            duplicate,
        )
        assertEquals(1, fixture.launches.count { request -> request.triggerKind == LlmDaemonTriggerKind.ECONOMIC_EVENT })
        assertEquals(0, restartedFixture.launches.count { request -> request.triggerKind == LlmDaemonTriggerKind.ECONOMIC_EVENT })
    }

    @Test
    fun priceMoveTriggerLaunchesForUpAndDownMovesWithAuditDetails() = runBlocking {
        val daemonConfig = LlmDaemonConfig(
            enabled = true,
            launchEnabled = true,
            priceMoveThresholdRatio = BigDecimal("0.009"),
        )
        val upFixture = schedulerFixture(tradingConfig = tradingConfig(daemon = daemonConfig))
        upFixture.scheduler.tick()
        upFixture.clock.advance(Duration.ofSeconds(300))
        upFixture.tickerReader.currentPriceJpy = BigDecimal("10100000")

        val upResult = upFixture.scheduler.tick()
        val upPayload = upFixture.launchedPayload(LlmDaemonTriggerKind.PRICE_MOVE)
        val upDetails = upPayload.getValue("details").jsonObject
        val upMeasurement = upPayload.getValue("typedTriggerMeasurements").jsonArray.single().jsonObject

        assertIs<LlmDaemonTickResult.Launched>(upResult)
        assertEquals(LlmDaemonTriggerKind.PRICE_MOVE, upResult.triggerKind)
        assertEquals("price-move", upPayload.stringValue("triggerKey"))
        assertEquals("0.01000000", upDetails.stringValue("changeRatio"))
        assertEquals("300", upDetails.stringValue("windowSeconds"))
        assertEquals("10000000", upDetails.stringValue("basePriceJpy"))
        assertEquals("10100000", upDetails.stringValue("currentPriceJpy"))
        assertEquals("absolute_price_change_ratio", upMeasurement.stringValue("metric"))
        assertEquals("0.01000000", upMeasurement.stringValue("measuredValue"))
        assertEquals("GREATER_THAN_OR_EQUAL", upMeasurement.stringValue("comparator"))
        assertEquals("0.009", upMeasurement.stringValue("threshold"))
        assertEquals("0.00100000", upMeasurement.stringValue("signedMargin"))
        assertEquals("RATIO", upMeasurement.stringValue("unit"))

        val downFixture = schedulerFixture(tradingConfig = tradingConfig(daemon = daemonConfig))
        downFixture.scheduler.tick()
        downFixture.clock.advance(Duration.ofSeconds(300))
        downFixture.tickerReader.currentPriceJpy = BigDecimal("9900000")

        val downResult = downFixture.scheduler.tick()
        val downPayload = downFixture.launchedPayload(LlmDaemonTriggerKind.PRICE_MOVE)
        val downDetails = downPayload.getValue("details").jsonObject
        val downMeasurement = downPayload.getValue("typedTriggerMeasurements").jsonArray.single().jsonObject

        assertIs<LlmDaemonTickResult.Launched>(downResult)
        assertEquals(LlmDaemonTriggerKind.PRICE_MOVE, downResult.triggerKind)
        assertEquals("price-move", downPayload.stringValue("triggerKey"))
        assertEquals("-0.01000000", downDetails.stringValue("changeRatio"))
        assertEquals("10000000", downDetails.stringValue("basePriceJpy"))
        assertEquals("9900000", downDetails.stringValue("currentPriceJpy"))
        assertEquals("0.01000000", downMeasurement.stringValue("measuredValue"))
        assertEquals("GREATER_THAN_OR_EQUAL", downMeasurement.stringValue("comparator"))
        assertEquals("0.009", downMeasurement.stringValue("threshold"))
        assertEquals("0.00100000", downMeasurement.stringValue("signedMargin"))
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
            tradingConfig = tradingConfig(
                events = listOf(
                    EconomicEventBlackout(
                        eventId = "fomc-20260729",
                        eventName = "FOMC",
                        eventAt = fixedInstant(),
                        blackoutBefore = Duration.ZERO,
                        blackoutAfter = Duration.ofHours(1),
                    ),
                ),
                daemon = LlmDaemonConfig(
                    enabled = true,
                    launchEnabled = true,
                    stopProximityRemainingRThreshold = BigDecimal("0.4"),
                ),
            ),
            hasOpenRisk = true,
            positionsReader = positionsReader,
        )
        fixture.tickerReader.currentPriceJpy = BigDecimal("93")

        val result = fixture.scheduler.tick()
        val payload = fixture.launchedPayload(LlmDaemonTriggerKind.STOP_PROXIMITY)
        val details = payload.getValue("details").jsonObject
        val measurement = payload.getValue("typedTriggerMeasurements").jsonArray.single().jsonObject

        assertIs<LlmDaemonTickResult.Launched>(result)
        assertEquals(LlmDaemonTriggerKind.STOP_PROXIMITY, result.triggerKind)
        assertEquals("stop-proximity", payload.stringValue("triggerKey"))
        assertEquals("position-close-to-stop", details.stringValue("positionId"))
        assertEquals("0.30000000", details.stringValue("remainingR"))
        assertEquals("90", details.stringValue("stopLossJpy"))
        assertEquals("93", details.stringValue("currentPriceJpy"))
        assertEquals("remaining_distance_to_stop", measurement.stringValue("metric"))
        assertEquals("0.30000000", measurement.stringValue("measuredValue"))
        assertEquals("LESS_THAN_OR_EQUAL", measurement.stringValue("comparator"))
        assertEquals("0.4", measurement.stringValue("threshold"))
        assertEquals("0.10000000", measurement.stringValue("signedMargin"))
        assertEquals("R", measurement.stringValue("unit"))
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
                    launchEnabled = true,
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
    fun flatBlackoutSkipsEconomicEventAndHeartbeatBeforeReservation() = runBlocking {
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

        assertIs<LlmDaemonTickResult.Skipped>(result)
        assertEquals("economic_event_blackout_flat", result.reason)
        assertEquals(LlmDaemonTriggerKind.ECONOMIC_EVENT, result.triggerKind)
        assertEquals(1, fixture.launches.size)
        assertTrue(
            fixture.eventLog.events().any { event ->
                event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED &&
                    event.payload.contains("economic_event_blackout_flat")
            },
        )
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
                    launchEnabled = true,
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
                runner = LlmRunnerConfig(maxInvocationsPerDay = 1, entryFillReservePerDay = 0, stopProximityReservePerDay = 0),
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
    commandEventLog: CommandEventLog = eventLog,
    reservations: LlmLaunchReservationRepository = InMemoryLlmLaunchReservationRepository(riskStateRepository),
    launches: MutableList<OneShotRunnerRequest> = mutableListOf(),
    idGenerator: () -> UUID = deterministicIds(),
    hasOpenRisk: Boolean = false,
    openRiskReader: LlmDaemonOpenRiskReader = {
        Result.success(
            LlmDaemonOpenRiskSnapshot(
                openPositionCount = if (hasOpenRisk) 1 else 0,
                restingEntryOrders = emptyList(),
                otherOpenOrderCount = 0,
            ),
        )
    },
    tickerReader: FakeTickerReader = FakeTickerReader(clock),
    positionsReader: FakePositionsReader = FakePositionsReader(),
    entryFillReader: FakeEntryFillReader = FakeEntryFillReader(),
    preFilter: FakePreFilter = FakePreFilter(),
    preFilterReleaseBarrierOpen: Boolean = true,
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    restingOrderMaintenanceService: RestingOrderMaintenanceService = RestingOrderMaintenanceService { _, _ ->
        Result.success(RestingSuppressionReason.RESTING_ORDER_IDENTITY_UNAVAILABLE)
    },
    episodeLifecycleObserver: OpportunityEpisodeLifecycleObserver = OpportunityEpisodeLifecycleObserver {
        Result.success(Unit)
    },
    gateShadowRepository: GateShadowRepository? = null,
    gateShadowResolver: GateShadowResolver? = null,
    launchAvailability: LlmDaemonLaunchAvailability = AlwaysAvailableLlmDaemonLaunchAvailability,
    launchHandler: suspend (OneShotRunnerRequest) -> OneShotRunnerResult = { request -> successfulRunnerResult(request) },
): SchedulerFixture {
    val tickStatus = MutableLlmDaemonTickStatus()
    val scheduler = LlmDaemonScheduler(
        tradingConfig = tradingConfig,
        runtimeConfigSnapshot = runtimeConfigSnapshot,
        preFilterReleaseBarrierOpen = preFilterReleaseBarrierOpen,
        dependencies = LlmDaemonSchedulerDependencies(
            riskStateRepository = riskStateRepository,
            commandEventLog = commandEventLog,
            launchReservationRepository = reservations,
            openRiskReader = openRiskReader,
            tickerReader = tickerReader,
            positionsReader = positionsReader,
            entryFillReader = entryFillReader,
            restingOrderMaintenanceService = restingOrderMaintenanceService,
            episodeLifecycleObserver = episodeLifecycleObserver,
            gateShadowRepository = gateShadowRepository,
            gateShadowResolver = gateShadowResolver,
            launchAvailability = launchAvailability,
        ),
        runtime = LlmDaemonSchedulerRuntime(
            requestBase = OneShotRunnerRequest(
                repositoryRoot = Path.of(".").toAbsolutePath().normalize(),
                workingDirectory = Path.of(".").toAbsolutePath().normalize(),
                mcpJarPath = "mcp/build/libs/fukurou-mcp-all.jar",
            ),
            launchOneShot = { request ->
                launches += request
                val invocationId = requireNotNull(request.invocationId)
                val triggerKind = requireNotNull(request.triggerKind)
                val claimantToken = "scheduler-test:$invocationId"
                val claim = reservations.claimForExecution(
                    LlmExecutionClaimRequest(
                        invocationId = invocationId,
                        triggerKind = triggerKind,
                        claimantToken = claimantToken,
                        claimedAt = clock.instant(),
                    ),
                )
                assertIs<LlmExecutionClaimOutcome.Claimed>(claim)
                try {
                    val result = if (request.preFilter?.invoke() == LlmDaemonPreFilterDecision.SKIP_NO_CHANGE) {
                        OneShotRunnerResult(
                            invocationId = invocationId,
                            status = OneShotRunnerStatus.PRE_FILTER_SKIPPED,
                            decision = null,
                            intent = null,
                            tradeResult = null,
                        )
                    } else {
                        launchHandler(request)
                    }
                    reservations.finish(
                        LlmLaunchReservationFinish(
                            invocationId = invocationId,
                            status = LlmLaunchReservationStatus.FINISHED,
                            reason = result.terminalCause.name,
                            finishedAt = clock.instant(),
                            claimantToken = claimantToken,
                        ),
                    ).getOrThrow()
                    Result.success(result)
                } catch (throwable: Throwable) {
                    reservations.finish(
                        LlmLaunchReservationFinish(
                            invocationId = invocationId,
                            status = LlmLaunchReservationStatus.FAILED,
                            reason = throwable.javaClass.simpleName,
                            finishedAt = clock.instant(),
                            claimantToken = claimantToken,
                        ),
                    ).getOrThrow()
                    throw throwable
                }
            },
            preFilter = preFilter,
            clock = clock,
            idGenerator = idGenerator,
            tickStatus = tickStatus,
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
        tickStatus = tickStatus,
    )
}

private fun restingEntryOrder(orderId: String = "resting-order-1"): Order {
    val now = fixedInstant().toString()

    return Order(
        orderId = orderId,
        intentId = "intent-1",
        positionId = null,
        tradeGroupId = null,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        status = OrderStatus.OPEN,
        sizeBtc = "0.01",
        limitPriceJpy = "10000000",
        triggerPriceJpy = null,
        protectiveStopPriceJpy = "9900000",
        takeProfitPriceJpy = "10200000",
        reasonJa = "押し目待ち",
        clientRequestId = null,
        createdAt = now,
        updatedAt = now,
    )
}

private fun tradingConfig(
    runner: LlmRunnerConfig = LlmRunnerConfig(),
    events: List<EconomicEventBlackout> = emptyList(),
    daemon: LlmDaemonConfig = LlmDaemonConfig(
        enabled = true,
        launchEnabled = true,
    ),
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
    val tickStatus: MutableLlmDaemonTickStatus,
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

/** pre-read 後に atomic reserve が競合で拒否した状態を再現する repository。 */
private class RaceRejectingReservationRepository(
    private val activeReservation: LlmActiveLaunchReservation,
) : LlmLaunchReservationRepository {

    override suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome> {
        return Result.success(
            LlmLaunchReservationOutcome.Rejected(
                reason = LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION,
                activeReservation = activeReservation,
            ),
        )
    }

    override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> = Result.success(Unit)

    override suspend fun latestReservedAt(triggerKey: String): Result<Instant?> = Result.success(null)

    override suspend fun latestFinishedReservedAt(triggerKey: String): Result<Instant?> = Result.success(null)

    override suspend fun findBlockingRunningReservation(
        requestTriggerKind: LlmDaemonTriggerKind,
        activeSince: Instant,
    ): Result<LlmActiveLaunchReservation?> = Result.success(null)
}

/** ECONOMIC_EVENT の最初の admission だけ reservation 前に拒否する repository。 */
private class RejectFirstEconomicReservationRepository(
    private val delegate: LlmLaunchReservationRepository,
) : LlmLaunchReservationRepository by delegate {

    private var rejected = false

    override suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome> {
        if (request.triggerKind == LlmDaemonTriggerKind.ECONOMIC_EVENT && !rejected) {
            rejected = true

            return Result.success(
                LlmLaunchReservationOutcome.Rejected(
                    LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR,
                ),
            )
        }

        return delegate.tryReserve(request)
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

/** scheduler gate の呼び出し位置を記録する availability。 */
private class RecordingLaunchAvailability(
    private val scheduledReason: LlmDaemonLaunchSuppressionReason? = null,
    private val statusReason: LlmDaemonLaunchSuppressionReason? = null,
) : LlmDaemonLaunchAvailability {
    var statusCallCount: Int = 0

    override fun scheduledSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason? {
        return scheduledReason
    }

    override suspend fun statusSuppressionAt(observedAt: Instant): LlmDaemonLaunchSuppressionReason? {
        statusCallCount += 1

        return statusReason
    }
}

/** scheduler 時刻境界 test 用 status reader。 */
private class SchedulerStatusReader(
    private val onRead: () -> Unit = {},
) : me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatusReader {
    var callCount: Int = 0

    override suspend fun readStatus(): Result<me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatus> {
        callCount += 1
        onRead()

        return Result.success(me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatus.OPEN)
    }
}

/** reservation admission の呼び出し有無を記録する repository。 */
private class RecordingReservationRepository(
    private val delegate: LlmLaunchReservationRepository,
    private val onFindBlocking: () -> Unit = {},
    private val onTryReserve: () -> Unit = {},
) : LlmLaunchReservationRepository by delegate {
    var admissionCallCount: Int = 0
    var blockingLookupCallCount: Int = 0
    var tryReserveCallCount: Int = 0
    val finishRequests: MutableList<LlmLaunchReservationFinish> = mutableListOf()
    val tryReserveRequests: MutableList<LlmLaunchReservationRequest> = mutableListOf()

    override suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome> {
        admissionCallCount += 1
        tryReserveCallCount += 1
        tryReserveRequests += request
        onTryReserve()

        return delegate.tryReserve(request)
    }

    override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> {
        finishRequests += finish

        return delegate.finish(finish)
    }

    override suspend fun findBlockingRunningReservation(
        requestTriggerKind: LlmDaemonTriggerKind,
        activeSince: Instant,
    ): Result<LlmActiveLaunchReservation?> {
        admissionCallCount += 1
        blockingLookupCallCount += 1
        onFindBlocking()

        return delegate.findBlockingRunningReservation(requestTriggerKind, activeSince)
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
