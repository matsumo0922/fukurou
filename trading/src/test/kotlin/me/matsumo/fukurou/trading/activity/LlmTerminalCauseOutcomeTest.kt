package me.matsumo.fukurou.trading.activity

import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmTerminalCauseOutcomeTest {

    @Test
    fun restartInterruptionIsFailedWithoutNoTradeEvent() {
        val outcome = classifyDecisionRunOutcome(
            DecisionRunOutcomeEvidence(
                status = LLM_RUN_STATUS_FAILED,
                errorMessage = null,
                terminalCause = LlmRunTerminalCause.RESTART_INTERRUPTED,
                action = null,
                safetyRule = null,
                orderCount = 0,
                filledOrderCount = 0,
                executionCount = 0,
                hasNoTradeExit = false,
            ),
        )

        assertEquals(DecisionRunOutcome.FAILED, outcome)
    }
}
