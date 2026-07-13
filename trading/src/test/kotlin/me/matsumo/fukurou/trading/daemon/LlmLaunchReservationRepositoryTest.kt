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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * LLM 起動予約の既定 hourly / daily 境界を検証するテスト。
 */
class LlmLaunchReservationRepositoryTest {

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

private fun launchBudgetFixedInstant(): Instant {
    return Instant.parse("2026-07-10T00:00:00Z")
}
