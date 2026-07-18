package me.matsumo.fukurou.trading.daemon

/** test method 間で process-local admission health を完全初期化する fixture。 */
object LlmExecutionAdmissionHealthTestFixture {
    /** production API を公開せず、test variant から全 health state を初期化する。 */
    fun reset() {
        LlmExecutionAdmissionHealth.resetForTest()
    }
}
