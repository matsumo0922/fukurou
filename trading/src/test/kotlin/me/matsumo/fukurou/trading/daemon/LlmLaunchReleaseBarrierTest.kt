package me.matsumo.fukurou.trading.daemon

import kotlin.test.Test
import kotlin.test.assertFalse

/** LlmLaunchReleaseBarrier の DB override 防止 contract を検証するテスト。 */
class LlmLaunchReleaseBarrierTest {
    @Test
    fun `active runtime config cannot bypass release barrier`() {
        assertFalse(LlmLaunchReleaseBarrier.PREFILTER_ACTIVATION_RELEASED)
        assertFalse(LlmLaunchReleaseBarrier.isPreFilterAllowed(configured = true))
    }
}
