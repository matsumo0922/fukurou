package me.matsumo.fukurou

import org.junit.runner.RunWith
import org.junit.runners.Suite

/** admission health が漏れたときの historical class order を固定する回帰 suite。 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    OpsRouteTest::class,
    DatabaseColdStartTest::class,
    DatabaseRecoveryPoolCompositionTest::class,
    EvaluationReportPersistenceTest::class,
)
class AdmissionHealthIsolationRegressionSuite
