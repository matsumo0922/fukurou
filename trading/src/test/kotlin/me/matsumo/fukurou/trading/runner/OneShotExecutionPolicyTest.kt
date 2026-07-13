package me.matsumo.fukurou.trading.runner

import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** one-shot hard deadline / heartbeat 導出値を検証する。 */
class OneShotExecutionPolicyTest {

    @Test
    fun defaultAndMaximumPolicies_matchGuardedTimeoutContract() {
        val defaultPolicy = OneShotExecutionPolicy.from(LlmRunnerConfig())
        val maximumPolicy = OneShotExecutionPolicy.from(
            LlmRunnerConfig(
                perRunTimeout = Duration.ofSeconds(600),
                processTerminationGrace = Duration.ofSeconds(30),
                persistenceTerminalTimeout = Duration.ofSeconds(30),
            ),
        )

        assertEquals(Duration.ofSeconds(570), defaultPolicy.hardTimeout)
        assertEquals(Duration.ofMillis(28_500), defaultPolicy.heartbeatInterval)
        assertEquals(Duration.ofMillis(85_500), defaultPolicy.heartbeatMissAllowance)
        assertEquals(Duration.ofSeconds(1_890), maximumPolicy.hardTimeout)
        assertEquals(Duration.ofSeconds(30), maximumPolicy.heartbeatInterval)
        assertEquals(Duration.ofSeconds(90), maximumPolicy.heartbeatMissAllowance)
    }

    @Test
    fun phaseClassification_isExhaustiveAcrossProductionExternalProcessPhases() {
        assertTrue(LlmInvocationPhase.PRE_FILTER.isClaimedOneShotPhase())
        assertTrue(LlmInvocationPhase.PROPOSER.isClaimedOneShotPhase())
        assertTrue(LlmInvocationPhase.FALSIFIER.isClaimedOneShotPhase())
        assertFalse(LlmInvocationPhase.REFLECTION.isClaimedOneShotPhase())
        assertFalse(LlmInvocationPhase.EVALUATION_REPORT.isClaimedOneShotPhase())
        assertEquals(5, LlmInvocationPhase.entries.size)
    }
}
