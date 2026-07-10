package me.matsumo.fukurou.trading.activity

import me.matsumo.fukurou.trading.persistence.STALE_LLM_RUN_RECOVERY_ERROR_MESSAGE
import kotlin.test.Test
import kotlin.test.assertEquals

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

        assertEquals(DecisionRunOutcome.NO_ENTRY, outcome)
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
        val overdue = waiting.copy(overdueOpenOrderCount = 1)

        assertEquals(DecisionRunOutcome.WAITING, classifyDecisionRunOutcome(waiting))
        assertEquals(DecisionRunOutcome.ACTION_REQUIRED, classifyDecisionRunOutcome(overdue))
    }

    @Test
    fun ttlCanceledOrderIsDistinctFromProcessFailure() {
        val expired = outcomeEvidence(action = "ENTER", orderCount = 1, ttlCanceledOrderCount = 1)

        assertEquals(DecisionRunOutcome.EXPIRED, classifyDecisionRunOutcome(expired))
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
    )
}
