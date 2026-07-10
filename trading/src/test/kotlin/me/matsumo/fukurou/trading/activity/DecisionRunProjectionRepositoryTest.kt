package me.matsumo.fukurou.trading.activity

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
                errorMessage = "previous process/container shutdown recovery",
                action = null,
                safetyRule = null,
                orderCount = 0,
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
                executionCount = 0,
                hasNoTradeExit = false,
            ),
        )

        assertEquals(DecisionRunOutcome.INTERRUPTED, interrupted)
        assertEquals(DecisionRunOutcome.FAILED, ordinaryFailure)
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
            classifyDecisionRunOutcome(outcomeEvidence(action = "ENTER", orderCount = 1)),
        )
    }
}

private fun outcomeEvidence(
    status: String = "SUCCEEDED",
    action: String? = null,
    orderCount: Int = 0,
    hasNoTradeExit: Boolean = false,
): DecisionRunOutcomeEvidence {
    return DecisionRunOutcomeEvidence(
        status = status,
        errorMessage = null,
        action = action,
        safetyRule = null,
        orderCount = orderCount,
        executionCount = 0,
        hasNoTradeExit = hasNoTradeExit,
    )
}
