package me.matsumo.fukurou.trading.reflection

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 週次 PromptCandidates の低優先 LLM 起動 budget を検証するテスト。
 */
class ReflectionLaunchBudgetTest {

    @Test
    fun generate_defersWhenFreshTradingReservationIsRunning() = runBlocking {
        val fixture = reflectionPromptCandidateGeneratorFixture()
        fixture.reservationRepository.tryReserve(
            reflectionReservationRequest(
                invocationId = "trading-run",
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            ),
        ).getOrThrow()

        val generation = fixture.generator.generate(reflectionDataset(), existingState = null).getOrThrow()

        assertEquals(ReflectionPromptCandidateGenerationStatus.BUDGET_DEFERRED, generation.generatedStatus())
        assertEquals(0, generation.attemptCount())
        assertTrue(fixture.invoker.requests.isEmpty())
    }

    @Test
    fun reflectionRunningReservationDoesNotBlockTradingReservation() = runBlocking {
        val fixture = reflectionPromptCandidateGeneratorFixture()
        val reflectionOutcome = fixture.reservationRepository.tryReserve(
            reflectionReservationRequest(
                invocationId = "reflection-run",
                triggerKind = LlmDaemonTriggerKind.REFLECTION,
            ),
        ).getOrThrow()

        val tradingOutcome = fixture.reservationRepository.tryReserve(
            reflectionReservationRequest(
                invocationId = "trading-run",
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            ),
        ).getOrThrow()

        assertEquals(LlmLaunchReservationOutcome.Reserved("reflection-run"), reflectionOutcome)
        assertEquals(LlmLaunchReservationOutcome.Reserved("trading-run"), tradingOutcome)
    }

    @Test
    fun reflectionReservationRequiresHourlyAndDailyHeadroom() = runBlocking {
        val hourlyFixture = reflectionPromptCandidateGeneratorFixture()
        hourlyFixture.reservationRepository.tryReserve(
            reflectionReservationRequest(
                invocationId = "hourly-trading-run",
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                runnerConfig = LlmRunnerConfig(
                    maxInvocationsPerHour = 2,
                    entryFillReservePerHour = 0,
                    stopProximityReservePerHour = 0,
                ),
            ),
        ).getOrThrow()
        hourlyFixture.reservationRepository.finish(
            reflectionReservationFinish("hourly-trading-run"),
        ).getOrThrow()

        val hourlyOutcome = hourlyFixture.reservationRepository.tryReserve(
            reflectionReservationRequest(
                invocationId = "hourly-reflection-run",
                triggerKind = LlmDaemonTriggerKind.REFLECTION,
                runnerConfig = LlmRunnerConfig(
                    maxInvocationsPerHour = 2,
                    entryFillReservePerHour = 0,
                    stopProximityReservePerHour = 0,
                ),
            ),
        ).getOrThrow()

        val dailyFixture = reflectionPromptCandidateGeneratorFixture()
        dailyFixture.reservationRepository.tryReserve(
            reflectionReservationRequest(
                invocationId = "daily-trading-run",
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                runnerConfig = LlmRunnerConfig(
                    maxInvocationsPerDay = 5,
                    entryFillReservePerDay = 0,
                    stopProximityReservePerDay = 0,
                ),
            ),
        ).getOrThrow()
        dailyFixture.reservationRepository.finish(
            reflectionReservationFinish("daily-trading-run"),
        ).getOrThrow()

        val dailyOutcome = dailyFixture.reservationRepository.tryReserve(
            reflectionReservationRequest(
                invocationId = "daily-reflection-run",
                triggerKind = LlmDaemonTriggerKind.REFLECTION,
                runnerConfig = LlmRunnerConfig(
                    maxInvocationsPerDay = 5,
                    entryFillReservePerDay = 0,
                    stopProximityReservePerDay = 0,
                ),
            ),
        ).getOrThrow()

        assertEquals(
            LlmLaunchReservationOutcome.Rejected(
                LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_HOURLY_HEADROOM,
            ),
            hourlyOutcome,
        )
        assertEquals(
            LlmLaunchReservationOutcome.Rejected(
                LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_DAILY_HEADROOM,
            ),
            dailyOutcome,
        )
    }

    @Test
    fun hardHaltDefersReflectionWithoutCallingLlm() = runBlocking {
        val fixture = reflectionPromptCandidateGeneratorFixture()
        fixture.riskStateRepository.setHardHalt("test halt", REFLECTION_TEST_INSTANT).getOrThrow()

        val generation = fixture.generator.generate(reflectionDataset(), existingState = null).getOrThrow()

        assertEquals(ReflectionPromptCandidateGenerationStatus.BUDGET_DEFERRED, generation.generatedStatus())
        assertEquals(0, generation.attemptCount())
        assertTrue(fixture.invoker.requests.isEmpty())
    }
}

private fun reflectionReservationRequest(
    invocationId: String,
    triggerKind: LlmDaemonTriggerKind,
    reservedAt: Instant = REFLECTION_TEST_INSTANT,
    runnerConfig: LlmRunnerConfig = LlmRunnerConfig(),
): LlmLaunchReservationRequest {
    return LlmLaunchReservationRequest(
        invocationId = invocationId,
        triggerKind = triggerKind,
        triggerKey = "test:$invocationId",
        reservedAt = reservedAt,
        runnerConfig = runnerConfig,
        hourlyWindow = Duration.ofHours(1),
        dailyWindow = Duration.ofDays(1),
        activeReservationStaleAfter = Duration.ofMinutes(30),
    )
}

private fun reflectionReservationFinish(invocationId: String): LlmLaunchReservationFinish {
    return LlmLaunchReservationFinish(
        invocationId = invocationId,
        status = LlmLaunchReservationStatus.FINISHED,
        reason = "test",
        finishedAt = REFLECTION_TEST_INSTANT.plusSeconds(10),
    )
}
