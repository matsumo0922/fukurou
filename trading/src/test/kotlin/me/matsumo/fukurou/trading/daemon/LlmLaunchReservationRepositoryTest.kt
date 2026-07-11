package me.matsumo.fukurou.trading.daemon

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
        repeat(6) { index -> reserveAndFinishLaunchBudget(repository, "decision-$index", LlmRunnerConfig(), now.plusSeconds(10 + index.toLong())) }
        val headroom = repository.tryReserve(
            launchBudgetRequest("report-headroom", LlmRunnerConfig(), now.plusSeconds(20), LlmDaemonTriggerKind.EVALUATION_REPORT),
        ).getOrThrow()
        assertEquals(LlmLaunchReservationRejectionReason.INSUFFICIENT_EVALUATION_HOURLY_HEADROOM, assertIs<LlmLaunchReservationOutcome.Rejected>(headroom).reason)
    }

    @Test
    fun defaultHourlyCap_allowsSeventhAndRejectsEighthReservation() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val config = LlmRunnerConfig()

        repeat(7) { index ->
            val reservedAt = launchBudgetFixedInstant().plus(Duration.ofMinutes(index.toLong()))

            reserveAndFinishLaunchBudget(
                repository = repository,
                invocationId = "hourly-$index",
                config = config,
                reservedAt = reservedAt,
            )
        }

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
    fun defaultDailyCap_allowsHundredTwentiethAndRejectsHundredTwentyFirstReservation() = runBlocking {
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository())
        val config = LlmRunnerConfig()

        repeat(120) { index ->
            val reservedAt = launchBudgetFixedInstant().plus(Duration.ofMinutes(index * 12L))

            reserveAndFinishLaunchBudget(
                repository = repository,
                invocationId = "daily-$index",
                config = config,
                reservedAt = reservedAt,
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

private suspend fun reserveAndFinishLaunchBudget(
    repository: InMemoryLlmLaunchReservationRepository,
    invocationId: String,
    config: LlmRunnerConfig,
    reservedAt: Instant,
) {
    val outcome = repository.tryReserve(
        launchBudgetRequest(
            invocationId = invocationId,
            config = config,
            reservedAt = reservedAt,
        ),
    ).getOrThrow()

    assertIs<LlmLaunchReservationOutcome.Reserved>(outcome)
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
