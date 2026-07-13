package me.matsumo.fukurou.trading.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** LlmLaunchReleaseBarrier の DB override 防止 contract を検証するテスト。 */
class LlmLaunchReleaseBarrierTest {
    @Test
    fun `active runtime config cannot bypass release barrier`() {
        assertFalse(LlmLaunchReleaseBarrier.PREFILTER_ACTIVATION_RELEASED)
        assertFalse(LlmLaunchReleaseBarrier.isPreFilterAllowed(configured = true))
    }

    @Test
    fun `production pre-filter gate does not invoke child while release is blocked`() = kotlinx.coroutines.runBlocking {
        var invocationCount = 0
        val gate = LlmDaemonPreFilterGate(
            daemonConfig = me.matsumo.fukurou.trading.config.LlmDaemonConfig(preFilterEnabled = true),
            preFilter = LlmDaemonPreFilter {
                invocationCount += 1
                Result.success(LlmDaemonPreFilterDecision.RUN_FULL)
            },
            requestBase = me.matsumo.fukurou.trading.runner.OneShotRunnerRequest(
                repositoryRoot = java.nio.file.Path.of(".").toAbsolutePath().normalize(),
                workingDirectory = java.nio.file.Path.of(".").toAbsolutePath().normalize(),
                mcpJarPath = "mcp/build/libs/fukurou-mcp-all.jar",
            ),
            warnLogger = me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger(
                logger = java.util.logging.Logger.getAnonymousLogger(),
            ),
        )

        val decision = gate.decisionIfNeeded(
            triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            triggerKey = "release-barrier-contract",
            invocationId = "release-barrier-contract",
            observedAt = java.time.Instant.EPOCH,
        )

        assertEquals(LlmDaemonPreFilterDecision.RUN_FULL, decision)
        assertEquals(0, invocationCount)
    }
}
