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

        assertEquals(DecisionRunOutcome.DENIED, outcome)
    }

    @Test
    fun outcomeOnlyClassifiesExplicitBootstrapRecoveryAsInterrupted() {
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

        assertEquals(DecisionRunOutcome.INTERRUPTED, interrupted)
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
            DecisionRunOutcome.NO_TRADE,
            classifyDecisionRunOutcome(outcomeEvidence(action = "NO_TRADE", hasNoTradeExit = true)),
        )
        assertEquals(
            DecisionRunOutcome.EXECUTED,
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
        assertEquals(DecisionRunOutcome.NO_TRADE, classifyDecisionRunOutcome(canceledWithNoTradeExit))
    }

    @Test
    fun terminalRunWithoutDecisionOrExecutionEvidenceIsFailed() {
        val outcome = classifyDecisionRunOutcome(outcomeEvidence())

        assertEquals(DecisionRunOutcome.FAILED, outcome)
    }
}

private fun outcomeEvidence(
    status: String = "SUCCEEDED",
    errorMessage: String? = null,
    action: String? = null,
    orderCount: Int = 0,
    filledOrderCount: Int = 0,
    hasNoTradeExit: Boolean = false,
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
    )
}
