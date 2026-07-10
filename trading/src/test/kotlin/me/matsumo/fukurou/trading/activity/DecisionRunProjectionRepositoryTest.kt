package me.matsumo.fukurou.trading.activity

import me.matsumo.fukurou.trading.persistence.STALE_LLM_RUN_RECOVERY_ERROR_MESSAGE
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionRunProjectionRepositoryTest {

    @Test
    fun outcomePrecedenceKeepsVerifierAndSafetyAsSeparateLayers() {
        val outcome = classifyDecisionRunOutcome(
            DecisionRunOutcomeEvidence(
                status = "SUCCEEDED",
                errorMessage = null,
                action = "ENTER",
                safetyRule = "EXPECTED_VALUE_GATE",
                orderCount = 0,
                filledOrderCount = 0,
                executionCount = 0,
                hasNoTradeExit = false,
            ),
        )

        assertEquals(DecisionRunOutcome.DENIED, outcome)
        assertFalse(outcome.matches(DecisionRunFilter.ACTION_REQUIRED))
        assertTrue(outcome.matches(DecisionRunFilter.DENIED))
    }

    @Test
    fun bootstrapRecoveryRemainsAProcessFailureWithoutLifecycleEvidence() {
        val interrupted = classifyDecisionRunOutcome(
            DecisionRunOutcomeEvidence(
                status = "FAILED",
                errorMessage = STALE_LLM_RUN_RECOVERY_ERROR_MESSAGE,
                action = null,
                safetyRule = null,
                orderCount = 0,
                filledOrderCount = 0,
                executionCount = 0,
                hasNoTradeExit = false,
            ),
        )
        val ordinaryFailure = classifyDecisionRunOutcome(
            DecisionRunOutcomeEvidence(
                status = "FAILED",
                errorMessage = "provider authentication failed",
                action = null,
                safetyRule = null,
                orderCount = 0,
                filledOrderCount = 0,
                executionCount = 0,
                hasNoTradeExit = false,
            ),
        )

        assertEquals(DecisionRunOutcome.FAILED, interrupted)
        assertEquals(DecisionRunOutcome.FAILED, ordinaryFailure)
    }

    @Test
    fun outcomeDoesNotTreatSimilarFailureTextAsBootstrapRecovery() {
        val outcome = classifyDecisionRunOutcome(
            outcomeEvidence(
                status = "FAILED",
                errorMessage = "provider failed after previous process container shutdown",
            ),
        )

        assertEquals(DecisionRunOutcome.FAILED, outcome)
    }

    @Test
    fun outcomeCoversRunningNoTradeAndExecutedStates() {
        assertEquals(
            DecisionRunOutcome.RUNNING,
            classifyDecisionRunOutcome(outcomeEvidence(status = "RUNNING")),
        )
        assertEquals(
            DecisionRunOutcome.NO_ENTRY,
            classifyDecisionRunOutcome(outcomeEvidence(action = "NO_TRADE", hasNoTradeExit = true)),
        )
        assertEquals(
            DecisionRunOutcome.FILLED,
            classifyDecisionRunOutcome(outcomeEvidence(action = "ENTER", orderCount = 1, filledOrderCount = 1)),
        )
    }

    @Test
    fun outcomeRequiresFilledOrderOrExecutionEvidenceForExecuted() {
        val rejectedOnly = outcomeEvidence(action = "ENTER", orderCount = 1)
        val canceledWithNoTradeExit = outcomeEvidence(
            action = "EXIT",
            orderCount = 1,
            hasNoTradeExit = true,
        )

        assertEquals(DecisionRunOutcome.FAILED, classifyDecisionRunOutcome(rejectedOnly))
        assertEquals(DecisionRunOutcome.NO_ENTRY, classifyDecisionRunOutcome(canceledWithNoTradeExit))
    }

    @Test
    fun terminalRunWithoutDecisionOrExecutionEvidenceIsFailed() {
        val outcome = classifyDecisionRunOutcome(outcomeEvidence())

        assertEquals(DecisionRunOutcome.FAILED, outcome)
    }

    @Test
    fun openOrderLifecycleOverridesLateProcessFailure() {
        val waiting = outcomeEvidence(status = "FAILED", action = "ENTER", openOrderCount = 1)
        val expiring = waiting.copy(expiringOpenOrderCount = 1)
        val overdue = waiting.copy(overdueOpenOrderCount = 1)

        assertEquals(DecisionRunOutcome.WAITING, classifyDecisionRunOutcome(waiting))
        assertEquals(DecisionRunOutcome.EXPIRING, classifyDecisionRunOutcome(expiring))
        assertEquals(DecisionRunOutcome.ACTION_REQUIRED, classifyDecisionRunOutcome(overdue))
    }

    @Test
    fun ttlCanceledOrderIsDistinctFromProcessFailure() {
        val expired = outcomeEvidence(action = "ENTER", orderCount = 1, ttlCanceledOrderCount = 1)

        assertEquals(DecisionRunOutcome.EXPIRED, classifyDecisionRunOutcome(expired))
    }

    @Test
    fun normalCancellationIsDistinctFromProcessFailureForTargetAndActorRuns() {
        val target = outcomeEvidence(action = "ENTER", orderCount = 1, canceledEntryOrderCount = 1)
        val actor = outcomeEvidence(status = "FAILED", action = "EXIT", actorCanceledOrderCount = 1)
        val executedActor = actor.copy(executionCount = 1)

        assertEquals(DecisionRunOutcome.CANCELED, classifyDecisionRunOutcome(target))
        assertEquals(DecisionRunOutcome.CANCELED, classifyDecisionRunOutcome(actor))
        assertEquals(DecisionRunOutcome.FILLED, classifyDecisionRunOutcome(executedActor))
    }

    @Test
    fun actionRequiredFilterIncludesIndependentProcessFailureMarker() {
        val summary = DecisionRunSummary(
            invocationId = "run-waiting-failed",
            mode = "PAPER",
            symbol = "BTC_JPY",
            triggerKind = "SCHEDULED",
            status = "FAILED",
            startedAt = Instant.parse("2026-07-10T00:00:00Z"),
            finishedAt = Instant.parse("2026-07-10T00:00:01Z"),
            errorMessage = "provider failed after order creation",
            action = "ENTER",
            reasonJa = "waiting",
            falsificationVerdict = null,
            safetyRule = null,
            safetyMessageJa = null,
            finalReason = null,
            orderCount = 1,
            executionCount = 0,
            outcome = DecisionRunOutcome.WAITING,
            hasProcessFailure = true,
        )

        assertTrue(summary.matches(DecisionRunFilter.ACTION_REQUIRED))
        assertTrue(summary.matches(DecisionRunFilter.WAITING))
    }
}

private fun outcomeEvidence(
    status: String = "SUCCEEDED",
    errorMessage: String? = null,
    action: String? = null,
    orderCount: Int = 0,
    filledOrderCount: Int = 0,
    hasNoTradeExit: Boolean = false,
    openOrderCount: Int = 0,
    ttlCanceledOrderCount: Int = 0,
    canceledEntryOrderCount: Int = 0,
    actorCanceledOrderCount: Int = 0,
): DecisionRunOutcomeEvidence {
    return DecisionRunOutcomeEvidence(
        status = status,
        errorMessage = errorMessage,
        action = action,
        safetyRule = null,
        orderCount = orderCount,
        filledOrderCount = filledOrderCount,
        executionCount = 0,
        hasNoTradeExit = hasNoTradeExit,
        openOrderCount = openOrderCount,
        ttlCanceledOrderCount = ttlCanceledOrderCount,
        canceledEntryOrderCount = canceledEntryOrderCount,
        actorCanceledOrderCount = actorCanceledOrderCount,
    )
}
