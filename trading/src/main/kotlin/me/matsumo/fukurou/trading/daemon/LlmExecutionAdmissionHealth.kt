package me.matsumo.fukurou.trading.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** claim / heartbeat / recovery failure を admission と readiness へ伝播する process-local health。 */
object LlmExecutionAdmissionHealth {
    private val ambiguousClaims = ConcurrentHashMap.newKeySet<String>()
    private val recoveryBlockers = ConcurrentHashMap.newKeySet<String>()
    private val heartbeatHealthy = AtomicBoolean(true)
    private val recoveryScanHealthy = AtomicBoolean(true)

    /** new admission と readiness を許可できるか返す。 */
    fun isHealthy(): Boolean = heartbeatHealthy.get() &&
        recoveryScanHealthy.get() &&
        ambiguousClaims.isEmpty() &&
        recoveryBlockers.isEmpty()

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

    /** periodic DB scan の成功状態を readiness / admission へ反映する。 */
    fun setRecoveryScanHealthy(healthy: Boolean) {
        recoveryScanHealthy.set(healthy)
    }

    /** termination fence 不明または recovery race 中の claim を fail-closed blocker にする。 */
    fun registerRecoveryBlocker(invocationId: String) {
        recoveryBlockers += invocationId
    }

    /** live heartbeat または terminal 確認後だけ recovery blocker を解除する。 */
    fun resolveRecoveryBlocker(invocationId: String) {
        recoveryBlockers -= invocationId
    }

    /** test process 内の状態を初期化する。 */
    internal fun resetForTest() {
        ambiguousClaims.clear()
        recoveryBlockers.clear()
        heartbeatHealthy.set(true)
        recoveryScanHealthy.set(true)
    }
}
