package me.matsumo.fukurou

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** DeploymentPreflightMain の allowlisted hook contract を検証するテスト。 */
class DeploymentPreflightMainTest {
    @Test
    fun `unknown hook is rejected before reading candidate inputs`() {
        assertFailsWith<IllegalArgumentException> {
            DeploymentPreflightMain.main(arrayOf("arbitrary-command"))
        }
    }
}
