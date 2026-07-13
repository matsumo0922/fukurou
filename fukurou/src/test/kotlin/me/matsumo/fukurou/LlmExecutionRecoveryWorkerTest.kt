package me.matsumo.fukurou

import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.runner.OneShotExecutionPolicy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** startup recovery audit の secret-free policy snapshot を検証する。 */
class LlmExecutionRecoveryWorkerTest {
    @Test
    fun startupPayload_containsPhaseAndDerivedPolicyComponentsWithoutSecrets() {
        val payload = startupPayload(OneShotExecutionPolicy.from(LlmRunnerConfig()))

        assertContains(payload, "\"phaseId\":\"PRE_FILTER\"")
        assertContains(payload, "\"phaseId\":\"PROPOSER\"")
        assertContains(payload, "\"phaseId\":\"FALSIFIER\"")
        assertContains(payload, "\"hardTimeoutSeconds\":570")
        assertContains(payload, "\"heartbeatIntervalMillis\":28500")
        assertContains(payload, "\"processTerminationGraceSeconds\":10")
        assertContains(payload, "\"persistenceTerminalTimeoutSeconds\":10")
        assertFalse(payload.contains("password", ignoreCase = true))
        assertFalse(payload.contains("token", ignoreCase = true))
    }
}
