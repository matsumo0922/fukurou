@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.reflection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 週次 PromptCandidates の低優先 LLM 起動 budget を検証するテスト。
 */
class ReflectionLaunchBudgetTest {
    @BeforeTest
    fun setUpAdmissionHealth() {
        LlmExecutionAdmissionHealth.resetForTest()
    }

    @AfterTest
    fun tearDownAdmissionHealth() {
        LlmExecutionAdmissionHealth.resetForTest()
    }

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

    @Test
    fun llmRunStartFailure_terminalizesNotRequiredReservationWithoutRestart() = runBlocking {
        val fixture = reflectionPromptCandidateGeneratorFixture(
            llmRunRepositoryTransform = { repository -> FailOnceLlmRunRepository(repository, failInsert = true) },
        )

        fixture.generator.generate(reflectionDataset(), existingState = null).getOrThrow()

        val invocationId = fixture.reservationRepository.findBlockingRunningReservation(
            requestTriggerKind = LlmDaemonTriggerKind.REFLECTION,
            activeSince = REFLECTION_TEST_INSTANT.minusSeconds(1),
        ).getOrThrow()?.invocationId
        assertEquals(null, invocationId)
        assertTrue(fixture.invoker.requests.isEmpty())
    }

    @Test
    fun transientRunAndReservationFinishFailures_recoverWithoutRestart() = runBlocking {
        val fixture = reflectionPromptCandidateGeneratorFixture(
            llmRunRepositoryTransform = { repository -> FailOnceLlmRunRepository(repository, failFinish = true) },
            reservationRepositoryTransform = { repository -> FailOnceReservationFinishRepository(repository) },
        )

        fixture.generator.generate(reflectionDataset(), existingState = null).getOrThrow()

        val run = fixture.llmRunRepository.records().single()
        assertEquals(LLM_RUN_STATUS_FAILED, run.status)
        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            fixture.reservationRepository.findExecutionClaim(run.invocationId).getOrThrow()?.status,
        )
    }

    @Test
    fun repeatedReservationFinishFailures_recoverThroughSupervisorWithoutRestart() = runBlocking {
        val fixture = reflectionPromptCandidateGeneratorFixture(
            reservationRepositoryTransform = { repository ->
                FailCountReservationFinishRepository(repository, failuresRemaining = 9)
            },
        )

        val generation = fixture.generator.generate(reflectionDataset(), existingState = null)
        assertTrue(generation.isFailure)

        val run = fixture.llmRunRepository.records().single()
        repeat(100) {
            val reservation = fixture.reservationRepository.findExecutionClaim(run.invocationId).getOrThrow()
            if (reservation?.status == LlmLaunchReservationStatus.FAILED && LlmExecutionAdmissionHealth.isHealthy()) {
                return@runBlocking
            }
            delay(20)
        }

        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            fixture.reservationRepository.findExecutionClaim(run.invocationId).getOrThrow()?.status,
        )
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
    }
}

private class FailOnceLlmRunRepository(
    private val delegate: LlmRunRepository,
    private var failInsert: Boolean = false,
    private var failFinish: Boolean = false,
) : LlmRunRepository by delegate {
    override suspend fun insertRunning(start: LlmRunStart): Result<Unit> {
        if (failInsert) {
            failInsert = false
            return Result.failure(IllegalStateException("insert failed"))
        }

        return delegate.insertRunning(start)
    }

    override suspend fun finish(finish: LlmRunFinish): Result<Unit> {
        if (failFinish) {
            failFinish = false
            return Result.failure(IllegalStateException("run finish failed"))
        }

        return delegate.finish(finish)
    }
}

private class FailOnceReservationFinishRepository(
    private val delegate: LlmLaunchReservationRepository,
) : LlmLaunchReservationRepository by delegate {
    private var failFinish = true

    override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> {
        if (failFinish) {
            failFinish = false
            return Result.failure(IllegalStateException("reservation finish failed"))
        }

        return delegate.finish(finish)
    }
}

private class FailCountReservationFinishRepository(
    private val delegate: LlmLaunchReservationRepository,
    private var failuresRemaining: Int,
) : LlmLaunchReservationRepository by delegate {
    override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> {
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            return Result.failure(IllegalStateException("reservation finish failed"))
        }

        return delegate.finish(finish)
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
