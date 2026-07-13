package me.matsumo.fukurou.trading.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** claim / heartbeat / recovery failure を admission と readiness へ伝播する process-local health。 */
object LlmExecutionAdmissionHealth {
    private val ambiguousClaims = ConcurrentHashMap.newKeySet<String>()
    private val heartbeatHealthy = AtomicBoolean(true)

    /** new admission と readiness を許可できるか返す。 */
    fun isHealthy(): Boolean = heartbeatHealthy.get() && ambiguousClaims.isEmpty()

    /** outcome-unknown claim を unresolved として登録する。 */
    fun registerAmbiguous(invocationId: String) {
        ambiguousClaims += invocationId
    }

    /** terminal 確認済み claim を registry から除く。 */
    fun resolveAmbiguous(invocationId: String) {
        ambiguousClaims -= invocationId
    }

    /** heartbeat persistence の状態を更新する。 */
    fun setHeartbeatHealthy(healthy: Boolean) {
        heartbeatHealthy.set(healthy)
    }

    /** test process 内の状態を初期化する。 */
    internal fun resetForTest() {
        ambiguousClaims.clear()
        heartbeatHealthy.set(true)
    }
}
