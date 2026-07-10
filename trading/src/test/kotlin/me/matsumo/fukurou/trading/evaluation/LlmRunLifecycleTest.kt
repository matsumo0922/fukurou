package me.matsumo.fukurou.trading.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmRunLifecycleTest {

    @Test
    fun terminalCauseWireCodesAreStable() {
        assertEquals(
            listOf(
                "NORMAL_COMPLETION",
                "NO_TRADE",
                "SAFETY_DENIED",
                "TIMED_OUT",
                "RUNNER_FAILED",
                "CALLER_CANCELLED",
                "RESTART_INTERRUPTED",
                "LEGACY_UNCLASSIFIED",
            ),
            LlmRunTerminalCause.entries.map { it.name },
        )
    }
}
