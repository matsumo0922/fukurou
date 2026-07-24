@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LLM 起動予約の既定 hourly / daily 境界を検証するテスト。
 */
class LlmLaunchReservationRepositoryTest {
    @BeforeTest
    fun setUpAdmissionHealth() {
        LlmExecutionAdmissionHealth.resetForTest()
    }

    @AfterTest
    fun tearDownAdmissionHealth() {
        LlmExecutionAdmissionHealth.resetForTest()
    }

    @Test
    fun parallelEconomicEventReservation_hasOneAttemptAndFailureDoesNotReopenIt() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val now = launchBudgetFixedInstant()
        val triggerKey = "economic-event:fomc-20260729:$now"

        val outcomes = coroutineScope {
            (0 until 100).map { index ->
                async {
                    repository.tryReserve(
                        economicEventRequest(
                            invocationId = "event-race-$index",
                            triggerKey = triggerKey,
                            reservedAt = now,
                        ),
                    ).getOrThrow()
                }
            }.awaitAll()
        }
        val winner = assertIs<LlmLaunchReservationOutcome.Reserved>(
            outcomes.single { it is LlmLaunchReservationOutcome.Reserved },
        )

        assertEquals(
            99,
            outcomes.count { outcome ->
                outcome == LlmLaunchReservationOutcome.Rejected(
                    LlmLaunchReservationRejectionReason.TRIGGER_ALREADY_ATTEMPTED,
                )
            },
        )

        repository.finish(
            LlmLaunchReservationFinish(
                invocationId = winner.invocationId,
                status = LlmLaunchReservationStatus.FAILED,
                reason = "runner_failed",
                finishedAt = now.plusSeconds(1),
            ),
        ).getOrThrow()
        val retry = repository.tryReserve(
            economicEventRequest("event-retry", triggerKey, now.plusSeconds(2)),
        ).getOrThrow()

        assertEquals(
            LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.TRIGGER_ALREADY_ATTEMPTED),
            retry,
        )
    }

    @Test
    fun parallelExecutionClaim_hasOneWinnerAndLoserCannotFinishWinner() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val now = launchBudgetFixedInstant()
        repository.tryReserve(launchBudgetRequest("claim-race", LlmRunnerConfig(), now)).getOrThrow()

        val outcomes = coroutineScope {
            (0 until 100).map { index ->
                async {
                    repository.claimForExecution(
                        LlmExecutionClaimRequest(
                            invocationId = "claim-race",
                            triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                            claimantToken = "token-$index",
                            claimedAt = now.plusMillis(index.toLong()),
                        ),
                    )
                }
            }.awaitAll()
        }
        val winnerIndex = outcomes.indexOfFirst { it is LlmExecutionClaimOutcome.Claimed }
        assertEquals(1, outcomes.count { it is LlmExecutionClaimOutcome.Claimed })
        assertEquals(
            99,
            outcomes.count { outcome ->
                outcome == LlmExecutionClaimOutcome.Rejected(
                    LlmExecutionClaimRejectionReason.ALREADY_CLAIMED,
                )
            },
        )

        repository.finish(
            LlmLaunchReservationFinish(
                invocationId = "claim-race",
                status = LlmLaunchReservationStatus.FAILED,
                reason = "loser",
                finishedAt = now.plusSeconds(1),
                claimantToken = "token-${(winnerIndex + 1) % 100}",
            ),
        ).getOrThrow()
        assertEquals(
            LlmLaunchReservationStatus.RUNNING,
            repository.findExecutionClaim("claim-race").getOrThrow()?.status,
        )

        repository.finish(
            LlmLaunchReservationFinish(
                invocationId = "claim-race",
                status = LlmLaunchReservationStatus.FINISHED,
                reason = "winner",
                finishedAt = now.plusSeconds(2),
                claimantToken = "token-$winnerIndex",
            ),
        ).getOrThrow()
        assertEquals(
            LlmLaunchReservationStatus.FINISHED,
            repository.findExecutionClaim("claim-race").getOrThrow()?.status,
        )
    }

    @Test
    fun claimedReservation_nullTokenCannotFinishWinner() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val now = launchBudgetFixedInstant()
        repository.tryReserve(launchBudgetRequest("null-token-winner", LlmRunnerConfig(), now)).getOrThrow()
        repository.claimForExecution(
            LlmExecutionClaimRequest(
                invocationId = "null-token-winner",
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                claimantToken = "winner-token",
                claimedAt = now,
            ),
        )

        repository.finish(
            LlmLaunchReservationFinish(
                invocationId = "null-token-winner",
                status = LlmLaunchReservationStatus.FAILED,
                reason = "null-token-loser",
                finishedAt = now.plusSeconds(1),
                claimantToken = null,
            ),
        ).getOrThrow()

        assertEquals(
            LlmLaunchReservationStatus.RUNNING,
            repository.findExecutionClaim("null-token-winner").getOrThrow()?.status,
        )
    }

    @Test
    fun staleAvailableReservationRejectsClaimBeforeInclusiveActiveCutoff() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val now = launchBudgetFixedInstant()
        repository.tryReserve(launchBudgetRequest("stale-available", LlmRunnerConfig(), now)).getOrThrow()

        val outcome = repository.claimForExecution(
            LlmExecutionClaimRequest(
                invocationId = "stale-available",
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                claimantToken = "claim-token",
                claimedAt = now.plus(Duration.ofMinutes(30)),
                activeSince = now.plusMillis(1),
            ),
        )

        assertEquals(
            LlmExecutionClaimOutcome.Rejected(LlmExecutionClaimRejectionReason.TERMINAL),
            outcome,
        )
    }

    @Test
    fun terminalAndNotRequiredReservationsRejectExecutionClaim() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val now = launchBudgetFixedInstant()
        repository.tryReserve(launchBudgetRequest("terminal", LlmRunnerConfig(), now)).getOrThrow()
        repository.finish(
            LlmLaunchReservationFinish("terminal", LlmLaunchReservationStatus.FINISHED, null, now.plusSeconds(1)),
        ).getOrThrow()
        repository.tryReserve(
            launchBudgetRequest("reflection-claim", LlmRunnerConfig(), now.plusSeconds(2), LlmDaemonTriggerKind.REFLECTION),
        ).getOrThrow()

        val terminal = repository.claimForExecution(
            LlmExecutionClaimRequest("terminal", LlmDaemonTriggerKind.FLAT_HEARTBEAT, "token", now.plusSeconds(3)),
        )
        val notRequired = repository.claimForExecution(
            LlmExecutionClaimRequest("reflection-claim", LlmDaemonTriggerKind.REFLECTION, "token", now.plusSeconds(3)),
        )

        assertEquals(
            LlmExecutionClaimOutcome.Rejected(LlmExecutionClaimRejectionReason.TERMINAL),
            terminal,
        )
        assertEquals(
            LlmExecutionClaimOutcome.Rejected(LlmExecutionClaimRejectionReason.CLAIM_NOT_REQUIRED),
            notRequired,
        )
    }

    @Test
    fun staleRecoveryFinish_losesWhenClaimHeartbeatAdvances() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val claimedAt = launchBudgetFixedInstant()
        val heartbeatAt = claimedAt.plusSeconds(10)
        repository.tryReserve(launchBudgetRequest("heartbeat-race", LlmRunnerConfig(), claimedAt)).getOrThrow()
        repository.claimForExecution(
            LlmExecutionClaimRequest(
                invocationId = "heartbeat-race",
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                claimantToken = "winner-token",
                claimedAt = claimedAt,
            ),
        )
        repository.heartbeatExecutionClaim(
            invocationId = "heartbeat-race",
            claimantToken = "winner-token",
            heartbeatAt = heartbeatAt,
        ).getOrThrow()

        repository.finish(
            LlmLaunchReservationFinish(
                invocationId = "heartbeat-race",
                status = LlmLaunchReservationStatus.FAILED,
                reason = "stale_recovery",
                finishedAt = heartbeatAt.plusSeconds(1),
                claimantToken = "winner-token",
                observedHeartbeatAt = claimedAt,
            ),
        ).getOrThrow()

        val snapshot = assertNotNull(repository.findExecutionClaim("heartbeat-race").getOrThrow())
        assertEquals(LlmLaunchReservationStatus.RUNNING, snapshot.status)
        assertEquals(heartbeatAt, snapshot.heartbeatAt)
    }

    @Test
    fun evaluationReport_blocksOnReflectionAndPreservesTradingHeadroom() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val now = launchBudgetFixedInstant()
        val reflection = launchBudgetRequest("reflection", LlmRunnerConfig(), now, LlmDaemonTriggerKind.REFLECTION)
        assertIs<LlmLaunchReservationOutcome.Reserved>(repository.tryReserve(reflection).getOrThrow())
        val blocked = repository.tryReserve(
            launchBudgetRequest("report", LlmRunnerConfig(), now.plusSeconds(1), LlmDaemonTriggerKind.EVALUATION_REPORT),
        ).getOrThrow()
        assertEquals("reflection", assertIs<LlmLaunchReservationOutcome.Rejected>(blocked).activeReservation?.invocationId)

        repository.finish(LlmLaunchReservationFinish("reflection", LlmLaunchReservationStatus.FINISHED, null, now.plusSeconds(2))).getOrThrow()
        repeat(4) { index -> reserveAndFinishLaunchBudget(repository, "decision-$index", LlmRunnerConfig(), now.plusSeconds(10 + index.toLong())) }
        val headroom = repository.tryReserve(
            launchBudgetRequest("report-headroom", LlmRunnerConfig(), now.plusSeconds(20), LlmDaemonTriggerKind.EVALUATION_REPORT),
        ).getOrThrow()
        assertEquals(LlmLaunchReservationRejectionReason.ENTRY_FILL_HOURLY_RESERVE, assertIs<LlmLaunchReservationOutcome.Rejected>(headroom).reason)
    }

    @Test
    fun defaultHourlyCap_preservesBothCriticalReserves() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val config = LlmRunnerConfig()

        repeat(5) { index ->
            val reservedAt = launchBudgetFixedInstant().plus(Duration.ofMinutes(index.toLong()))

            reserveAndFinishLaunchBudget(
                repository = repository,
                invocationId = "hourly-$index",
                config = config,
                reservedAt = reservedAt,
            )
        }
        reserveAndFinishLaunchBudget(repository, "entry", config, launchBudgetFixedInstant().plusSeconds(301), LlmDaemonTriggerKind.ENTRY_FILL)
        reserveAndFinishLaunchBudget(repository, "stop", config, launchBudgetFixedInstant().plusSeconds(302), LlmDaemonTriggerKind.STOP_PROXIMITY)

        val eighthOutcome = repository.tryReserve(
            launchBudgetRequest(
                invocationId = "hourly-7",
                config = config,
                reservedAt = launchBudgetFixedInstant().plus(Duration.ofMinutes(7)),
            ),
        ).getOrThrow()

        assertEquals(
            LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR),
            eighthOutcome,
        )
    }

    @Test
    fun manualReservation_bypassesHourlyAndDailyCriticalReserves() = runBlocking {
        assertManualBypassesHourlyCriticalReserves()
        assertManualBypassesDailyCriticalReserves()
    }

    @Test
    fun manualReservation_respectsHourlyAndDailyHardCaps() = runBlocking {
        assertManualRespectsHourlyHardCap()
        assertManualRespectsDailyHardCap()
    }

    @Test
    fun criticalHourlyOverGuaranteeCannotConsumeTheOtherCriticalReserve() = runBlocking {
        listOf(
            LlmDaemonTriggerKind.ENTRY_FILL to LlmLaunchReservationRejectionReason.STOP_PROXIMITY_HOURLY_RESERVE,
            LlmDaemonTriggerKind.STOP_PROXIMITY to LlmLaunchReservationRejectionReason.ENTRY_FILL_HOURLY_RESERVE,
        ).forEach { (overGuaranteeKind, expectedReason) ->
            val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
            val now = launchBudgetFixedInstant()
            repeat(5) { index ->
                reserveAndFinishLaunchBudget(
                    repository = repository,
                    invocationId = "normal-$overGuaranteeKind-$index",
                    config = LlmRunnerConfig(),
                    reservedAt = now.plusSeconds(index.toLong()),
                )
            }
            reserveAndFinishLaunchBudget(
                repository = repository,
                invocationId = "guaranteed-$overGuaranteeKind",
                config = LlmRunnerConfig(),
                reservedAt = now.plusSeconds(5),
                triggerKind = overGuaranteeKind,
            )

            val outcome = repository.tryReserve(
                launchBudgetRequest(
                    invocationId = "over-guarantee-$overGuaranteeKind",
                    config = LlmRunnerConfig(),
                    reservedAt = now.plusSeconds(6),
                    triggerKind = overGuaranteeKind,
                ),
            ).getOrThrow()

            assertEquals(expectedReason, assertIs<LlmLaunchReservationOutcome.Rejected>(outcome).reason)
        }
    }

    @Test
    fun defaultHourlyGuaranteesHoldForEveryCompleteCriticalOrderPermutation() = runBlocking {
        val now = launchBudgetFixedInstant()
        for (entryPosition in 0 until 7) {
            for (stopPosition in 0 until 7) {
                if (entryPosition == stopPosition) continue
                val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
                repeat(7) { index ->
                    val triggerKind = when (index) {
                        entryPosition -> LlmDaemonTriggerKind.ENTRY_FILL
                        stopPosition -> LlmDaemonTriggerKind.STOP_PROXIMITY
                        else -> LlmDaemonTriggerKind.FLAT_HEARTBEAT
                    }
                    reserveAndFinishLaunchBudget(
                        repository = repository,
                        invocationId = "permutation-$entryPosition-$stopPosition-$index",
                        config = LlmRunnerConfig(),
                        reservedAt = now.plusSeconds(index.toLong()),
                        triggerKind = triggerKind,
                    )
                }
            }
        }
    }

    @Test
    fun healthBlockerAfterReservationRejectsClaimAndNotRequiredChildAdmission() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val reflectionRepository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val now = launchBudgetFixedInstant()
        repository.tryReserve(
            launchBudgetRequest(
                invocationId = "health-claimed",
                config = LlmRunnerConfig(),
                reservedAt = now,
            ),
        ).getOrThrow()
        reflectionRepository.tryReserve(
            launchBudgetRequest(
                invocationId = "health-reflection",
                config = LlmRunnerConfig(),
                reservedAt = now,
                triggerKind = LlmDaemonTriggerKind.REFLECTION,
            ),
        ).getOrThrow()

        try {
            LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
            val claim = repository.claimForExecution(
                LlmExecutionClaimRequest(
                    invocationId = "health-claimed",
                    triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                    claimantToken = "health-token",
                    claimedAt = now,
                ),
            )

            assertIs<LlmExecutionClaimOutcome.OutcomeUnknown>(claim)
            assertTrue(reflectionRepository.validateExecutionAdmission("health-reflection", claimantToken = null).isFailure)
        } finally {
            LlmExecutionAdmissionHealth.resetForTest()
        }
    }

    @Test
    fun normalHourlyRejection_namesTheOnlyUnusedCriticalReserve() = runBlocking {
        assertOnlyUnusedHourlyReserve(
            usedCritical = LlmDaemonTriggerKind.STOP_PROXIMITY,
            expected = LlmLaunchReservationRejectionReason.ENTRY_FILL_HOURLY_RESERVE,
        )
        assertOnlyUnusedHourlyReserve(
            usedCritical = LlmDaemonTriggerKind.ENTRY_FILL,
            expected = LlmLaunchReservationRejectionReason.STOP_PROXIMITY_HOURLY_RESERVE,
        )
    }

    @Test
    fun normalDailyRejection_namesTheOnlyUnusedCriticalReserve() = runBlocking {
        assertOnlyUnusedDailyReserve(
            usedCritical = LlmDaemonTriggerKind.STOP_PROXIMITY,
            expected = LlmLaunchReservationRejectionReason.ENTRY_FILL_DAILY_RESERVE,
        )
        assertOnlyUnusedDailyReserve(
            usedCritical = LlmDaemonTriggerKind.ENTRY_FILL,
            expected = LlmLaunchReservationRejectionReason.STOP_PROXIMITY_DAILY_RESERVE,
        )
    }

    @Test
    fun defaultDailyCap_preservesBothCriticalReserves() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val config = LlmRunnerConfig()

        repeat(120) { index ->
            val reservedAt = launchBudgetFixedInstant().plusSeconds(index * 721L)
            val triggerKind = when (index) {
                in 0..3 -> LlmDaemonTriggerKind.ENTRY_FILL
                in 4..7 -> LlmDaemonTriggerKind.STOP_PROXIMITY
                else -> LlmDaemonTriggerKind.FLAT_HEARTBEAT
            }

            reserveAndFinishLaunchBudget(
                repository = repository,
                invocationId = "daily-$index",
                config = config,
                reservedAt = reservedAt,
                triggerKind = triggerKind,
            )
        }

        val hundredTwentyFirstOutcome = repository.tryReserve(
            launchBudgetRequest(
                invocationId = "daily-120",
                config = config,
                reservedAt = launchBudgetFixedInstant().plus(Duration.ofHours(24)),
            ),
        ).getOrThrow()

        assertEquals(
            LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY),
            hundredTwentyFirstOutcome,
        )
    }

    @Test
    fun defaultDailyCapAllowsNormal112ThenFourInvocationsForEachCriticalTrigger() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val config = LlmRunnerConfig()
        val now = launchBudgetFixedInstant()

        repeat(112) { index ->
            reserveAndFinishLaunchBudget(
                repository = repository,
                invocationId = "normal-daily-$index",
                config = config,
                reservedAt = now.plusSeconds(index * 721L),
            )
        }
        repeat(4) { index ->
            reserveAndFinishLaunchBudget(
                repository = repository,
                invocationId = "entry-daily-$index",
                config = config,
                reservedAt = now.plusSeconds((112L + index) * 721L),
                triggerKind = LlmDaemonTriggerKind.ENTRY_FILL,
            )
        }
        repeat(4) { index ->
            reserveAndFinishLaunchBudget(
                repository = repository,
                invocationId = "stop-daily-$index",
                config = config,
                reservedAt = now.plusSeconds((116L + index) * 721L),
                triggerKind = LlmDaemonTriggerKind.STOP_PROXIMITY,
            )
        }

        val exhausted = repository.tryReserve(
            launchBudgetRequest(
                invocationId = "daily-exhausted",
                config = config,
                reservedAt = now.plusSeconds(119L * 721L + 1L),
            ),
        ).getOrThrow()

        assertEquals(
            LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY),
            exhausted,
        )
    }

    @Test
    fun legacyBooleanHelperDerivesFromBlockingReservationIdentity() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val reservedAt = launchBudgetFixedInstant()
        repository.tryReserve(launchBudgetRequest("active", LlmRunnerConfig(), reservedAt)).getOrThrow()

        val blocker = repository.findBlockingRunningReservation(
            LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            reservedAt.minus(Duration.ofMinutes(1)),
        ).getOrThrow()
        val active = repository.hasFreshRunningReservation(reservedAt.minus(Duration.ofMinutes(1))).getOrThrow()

        assertEquals("active", assertNotNull(blocker).invocationId)
        assertEquals(true, active)
    }
}

private suspend fun assertManualBypassesHourlyCriticalReserves() {
    val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
    val config = LlmRunnerConfig()
    val now = launchBudgetFixedInstant()
    repeat(5) { index ->
        reserveAndFinishLaunchBudget(
            repository = repository,
            invocationId = "manual-hour-normal-$index",
            config = config,
            reservedAt = now.plusSeconds(index.toLong()),
        )
    }

    val heartbeat = repository.tryReserve(
        launchBudgetRequest("manual-hour-heartbeat", config, now.plusSeconds(5)),
    ).getOrThrow()
    val manual = repository.tryReserve(
        launchBudgetRequest(
            invocationId = "manual-hour-reserved",
            config = config,
            reservedAt = now.plusSeconds(6),
            triggerKind = LlmDaemonTriggerKind.MANUAL,
        ),
    ).getOrThrow()

    assertEquals(
        LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.ENTRY_FILL_HOURLY_RESERVE),
        heartbeat,
    )
    assertIs<LlmLaunchReservationOutcome.Reserved>(manual)
}

private suspend fun assertManualBypassesDailyCriticalReserves() {
    val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
    val config = LlmRunnerConfig(
        maxInvocationsPerDay = 10,
        entryFillReservePerDay = 1,
        stopProximityReservePerDay = 1,
    )
    val now = launchBudgetFixedInstant()
    repeat(8) { index ->
        reserveAndFinishLaunchBudget(
            repository = repository,
            invocationId = "manual-day-normal-$index",
            config = config,
            reservedAt = now.plus(Duration.ofHours(index.toLong())),
        )
    }

    val heartbeat = repository.tryReserve(
        launchBudgetRequest("manual-day-heartbeat", config, now.plus(Duration.ofHours(8))),
    ).getOrThrow()
    val manual = repository.tryReserve(
        launchBudgetRequest(
            invocationId = "manual-day-reserved",
            config = config,
            reservedAt = now.plus(Duration.ofHours(8)).plusSeconds(1),
            triggerKind = LlmDaemonTriggerKind.MANUAL,
        ),
    ).getOrThrow()

    assertEquals(
        LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.ENTRY_FILL_DAILY_RESERVE),
        heartbeat,
    )
    assertIs<LlmLaunchReservationOutcome.Reserved>(manual)
}

private suspend fun assertManualRespectsHourlyHardCap() {
    val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
    val config = LlmRunnerConfig()
    val now = launchBudgetFixedInstant()
    repeat(5) { index ->
        reserveAndFinishLaunchBudget(
            repository = repository,
            invocationId = "manual-hour-cap-normal-$index",
            config = config,
            reservedAt = now.plusSeconds(index.toLong()),
        )
    }
    reserveAndFinishLaunchBudget(
        repository = repository,
        invocationId = "manual-hour-cap-entry",
        config = config,
        reservedAt = now.plusSeconds(5),
        triggerKind = LlmDaemonTriggerKind.ENTRY_FILL,
    )
    reserveAndFinishLaunchBudget(
        repository = repository,
        invocationId = "manual-hour-cap-stop",
        config = config,
        reservedAt = now.plusSeconds(6),
        triggerKind = LlmDaemonTriggerKind.STOP_PROXIMITY,
    )

    val manual = repository.tryReserve(
        launchBudgetRequest(
            invocationId = "manual-hour-cap-rejected",
            config = config,
            reservedAt = now.plusSeconds(7),
            triggerKind = LlmDaemonTriggerKind.MANUAL,
        ),
    ).getOrThrow()

    assertEquals(
        LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR),
        manual,
    )
}

private suspend fun assertManualRespectsDailyHardCap() {
    val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
    val config = LlmRunnerConfig(
        maxInvocationsPerDay = 5,
        entryFillReservePerDay = 1,
        stopProximityReservePerDay = 1,
    )
    val now = launchBudgetFixedInstant()
    repeat(3) { index ->
        reserveAndFinishLaunchBudget(
            repository = repository,
            invocationId = "manual-day-cap-normal-$index",
            config = config,
            reservedAt = now.plus(Duration.ofHours(index.toLong())),
        )
    }
    reserveAndFinishLaunchBudget(
        repository = repository,
        invocationId = "manual-day-cap-entry",
        config = config,
        reservedAt = now.plus(Duration.ofHours(3)),
        triggerKind = LlmDaemonTriggerKind.ENTRY_FILL,
    )
    reserveAndFinishLaunchBudget(
        repository = repository,
        invocationId = "manual-day-cap-stop",
        config = config,
        reservedAt = now.plus(Duration.ofHours(4)),
        triggerKind = LlmDaemonTriggerKind.STOP_PROXIMITY,
    )

    val manual = repository.tryReserve(
        launchBudgetRequest(
            invocationId = "manual-day-cap-rejected",
            config = config,
            reservedAt = now.plus(Duration.ofHours(5)),
            triggerKind = LlmDaemonTriggerKind.MANUAL,
        ),
    ).getOrThrow()

    assertEquals(
        LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY),
        manual,
    )
}

private suspend fun assertOnlyUnusedHourlyReserve(
    usedCritical: LlmDaemonTriggerKind,
    expected: LlmLaunchReservationRejectionReason,
) {
    val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
    val config = LlmRunnerConfig()
    val now = launchBudgetFixedInstant()
    reserveAndFinishLaunchBudget(repository, "hour-critical-$usedCritical", config, now, usedCritical)
    repeat(5) { index ->
        reserveAndFinishLaunchBudget(repository, "hour-normal-$usedCritical-$index", config, now.plusSeconds(index + 1L))
    }

    val outcome = repository.tryReserve(
        launchBudgetRequest("hour-rejected-$usedCritical", config, now.plusSeconds(10)),
    ).getOrThrow()
    assertEquals(expected, assertIs<LlmLaunchReservationOutcome.Rejected>(outcome).reason)
}

private suspend fun assertOnlyUnusedDailyReserve(
    usedCritical: LlmDaemonTriggerKind,
    expected: LlmLaunchReservationRejectionReason,
) {
    val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
    val config = LlmRunnerConfig(
        maxInvocationsPerDay = 10,
        entryFillReservePerDay = 1,
        stopProximityReservePerDay = 1,
    )
    val now = launchBudgetFixedInstant()
    reserveAndFinishLaunchBudget(repository, "day-critical-$usedCritical", config, now, usedCritical)
    repeat(8) { index ->
        reserveAndFinishLaunchBudget(
            repository = repository,
            invocationId = "day-normal-$usedCritical-$index",
            config = config,
            reservedAt = now.plus(Duration.ofHours(index + 1L)),
        )
    }

    val outcome = repository.tryReserve(
        launchBudgetRequest("day-rejected-$usedCritical", config, now.plus(Duration.ofHours(10))),
    ).getOrThrow()
    assertEquals(expected, assertIs<LlmLaunchReservationOutcome.Rejected>(outcome).reason)
}

private suspend fun reserveAndFinishLaunchBudget(
    repository: InMemoryLlmLaunchReservationRepository,
    invocationId: String,
    config: LlmRunnerConfig,
    reservedAt: Instant,
    triggerKind: LlmDaemonTriggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
) {
    val outcome = repository.tryReserve(
        launchBudgetRequest(
            invocationId = invocationId,
            config = config,
            reservedAt = reservedAt,
            triggerKind = triggerKind,
        ),
    ).getOrThrow()

    assertIs<LlmLaunchReservationOutcome.Reserved>(outcome, "invocation=$invocationId outcome=$outcome")
    repository.finish(
        LlmLaunchReservationFinish(
            invocationId = invocationId,
            status = LlmLaunchReservationStatus.FINISHED,
            reason = "NO_TRADE_DECISION",
            finishedAt = reservedAt.plusSeconds(1),
        ),
    ).getOrThrow()
}

private fun launchBudgetRequest(
    invocationId: String,
    config: LlmRunnerConfig,
    reservedAt: Instant,
    triggerKind: LlmDaemonTriggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
): LlmLaunchReservationRequest {
    return LlmLaunchReservationRequest(
        invocationId = invocationId,
        triggerKind = triggerKind,
        triggerKey = "test:$invocationId",
        reservedAt = reservedAt,
        runnerConfig = config,
        hourlyWindow = Duration.ofHours(1),
        dailyWindow = Duration.ofHours(24),
        activeReservationStaleAfter = Duration.ofMinutes(30),
    )
}

private fun economicEventRequest(
    invocationId: String,
    triggerKey: String,
    reservedAt: Instant,
): LlmLaunchReservationRequest {
    return LlmLaunchReservationRequest(
        invocationId = invocationId,
        triggerKind = LlmDaemonTriggerKind.ECONOMIC_EVENT,
        triggerKey = triggerKey,
        reservedAt = reservedAt,
        runnerConfig = LlmRunnerConfig(),
        hourlyWindow = Duration.ofHours(1),
        dailyWindow = Duration.ofHours(24),
        activeReservationStaleAfter = Duration.ofMinutes(30),
        singleAttemptKey = "ECONOMIC_EVENT:$triggerKey",
    )
}

private fun launchBudgetFixedInstant(): Instant {
    return Instant.parse("2026-07-10T00:00:00Z")
}
