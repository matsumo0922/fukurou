package me.matsumo.fukurou.trading.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** claim / heartbeat / recovery failure を admission と readiness へ伝播する process-local health。 */
@Suppress("TooManyFunctions")
object LlmExecutionAdmissionHealth {
    private val admissionLock = Any()
    private val ambiguousClaims = ConcurrentHashMap.newKeySet<ClaimHealthKey>()
    private val recoveryBlockers = ConcurrentHashMap.newKeySet<ClaimHealthKey>()
    private val heartbeatFailures = ConcurrentHashMap.newKeySet<ClaimHealthKey>()
    private val heartbeatHealthy = AtomicBoolean(true)
    private val recoveryScanHealthy = AtomicBoolean(true)

    /** new admission と readiness を許可できるか返す。 */
    fun isHealthy(): Boolean = synchronized(admissionLock) { isHealthyLocked() }

    /** health 判定と admission の永続化境界を blocker transition に対して atomic にする。 */
    fun <T> withHealthyAdmission(block: () -> T): T = synchronized(admissionLock) {
        check(isHealthyLocked()) { "LLM execution admission is fail-closed." }
        block()
    }

    /** outcome-unknown claim を unresolved として登録する。 */
    fun registerAmbiguous(invocationId: String, claimantToken: String = UNKNOWN_TOKEN) {
        synchronized(admissionLock) { ambiguousClaims += ClaimHealthKey(invocationId, claimantToken) }
    }

    /** terminal 確認済み claim を registry から除く。 */
    fun resolveAmbiguous(invocationId: String, claimantToken: String = UNKNOWN_TOKEN) {
        synchronized(admissionLock) { ambiguousClaims -= ClaimHealthKey(invocationId, claimantToken) }
    }

    /** heartbeat persistence の状態を更新する。 */
    fun setHeartbeatHealthy(healthy: Boolean) {
        synchronized(admissionLock) { heartbeatHealthy.set(healthy) }
    }

    /** claimant token 単位で heartbeat persistence failure を追跡する。 */
    fun recordHeartbeatResult(
        invocationId: String,
        claimantToken: String,
        healthy: Boolean,
    ) {
        val key = ClaimHealthKey(invocationId, claimantToken)
        synchronized(admissionLock) {
            if (healthy) heartbeatFailures -= key else heartbeatFailures += key
        }
    }

    /** periodic DB scan の成功状態を readiness / admission へ反映する。 */
    fun setRecoveryScanHealthy(healthy: Boolean) {
        synchronized(admissionLock) { recoveryScanHealthy.set(healthy) }
    }

    /** termination fence 不明または recovery race 中の claim を fail-closed blocker にする。 */
    fun registerRecoveryBlocker(invocationId: String, claimantToken: String = UNKNOWN_TOKEN) {
        synchronized(admissionLock) { recoveryBlockers += ClaimHealthKey(invocationId, claimantToken) }
    }

    /** live heartbeat または terminal 確認後だけ recovery blocker を解除する。 */
    fun resolveRecoveryBlocker(invocationId: String, claimantToken: String = UNKNOWN_TOKEN) {
        synchronized(admissionLock) { recoveryBlockers -= ClaimHealthKey(invocationId, claimantToken) }
    }

    /** terminal confirmation 後に同じ claim token の全 blocker を解除する。 */
    fun resolveClaim(invocationId: String, claimantToken: String) {
        val key = ClaimHealthKey(invocationId, claimantToken)
        synchronized(admissionLock) {
            ambiguousClaims -= key
            recoveryBlockers -= key
            heartbeatFailures -= key
        }
    }

    /** test process 内の状態を初期化する。 */
    internal fun resetForTest() {
        synchronized(admissionLock) {
            ambiguousClaims.clear()
            recoveryBlockers.clear()
            heartbeatFailures.clear()
            heartbeatHealthy.set(true)
            recoveryScanHealthy.set(true)
        }
    }

    private fun isHealthyLocked(): Boolean = heartbeatHealthy.get() &&
        recoveryScanHealthy.get() &&
        ambiguousClaims.isEmpty() &&
        recoveryBlockers.isEmpty() &&
        heartbeatFailures.isEmpty()
}

private data class ClaimHealthKey(val invocationId: String, val claimantToken: String)
private const val UNKNOWN_TOKEN = "<unknown>"
