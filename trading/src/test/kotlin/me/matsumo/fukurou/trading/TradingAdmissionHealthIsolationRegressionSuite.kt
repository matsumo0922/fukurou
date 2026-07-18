package me.matsumo.fukurou.trading

import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerTest
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchServiceTest
import me.matsumo.fukurou.trading.runner.OneShotRunnerMainTest
import org.junit.runner.RunWith
import org.junit.runners.Suite
import kotlin.test.Test
import kotlin.test.assertFalse

/** admission-dependent trading tests の直前に unhealthy predecessor を固定する回帰 suite。 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    TradingAdmissionHealthIsolationRegressionSuite.BeforeScheduler::class,
    LlmDaemonSchedulerTest::class,
    TradingAdmissionHealthIsolationRegressionSuite.BeforeManualLaunch::class,
    ManualLlmLaunchServiceTest::class,
    TradingAdmissionHealthIsolationRegressionSuite.BeforeOneShotMain::class,
    OneShotRunnerMainTest::class,
)
class TradingAdmissionHealthIsolationRegressionSuite {
    /** scheduler test の直前に process-global health を fail-closed にする。 */
    class BeforeScheduler {
        @Test
        fun leavesAdmissionUnhealthy() {
            leaveAdmissionUnhealthy()
        }
    }

    /** manual launch test の直前に process-global health を fail-closed にする。 */
    class BeforeManualLaunch {
        @Test
        fun leavesAdmissionUnhealthy() {
            leaveAdmissionUnhealthy()
        }
    }

    /** standalone runner test の直前に process-global health を fail-closed にする。 */
    class BeforeOneShotMain {
        @Test
        fun leavesAdmissionUnhealthy() {
            leaveAdmissionUnhealthy()
        }
    }
}

private fun leaveAdmissionUnhealthy() {
    LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)

    assertFalse(LlmExecutionAdmissionHealth.isHealthy())
}
