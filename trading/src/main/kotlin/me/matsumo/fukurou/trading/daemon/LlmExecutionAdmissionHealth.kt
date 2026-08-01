package me.matsumo.fukurou.trading.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** claim / heartbeat / recovery failure を admission と readiness へ伝播する process-local health。 */
@Suppress("TooManyFunctions")
object LlmExecutionAdmissionHealth {
    private val admissionLock = ReentrantReadWriteLock(true)
    private val ambiguousClaims = ConcurrentHashMap.newKeySet<ClaimHealthKey>()
    private val recoveryBlockers = ConcurrentHashMap.newKeySet<ClaimHealthKey>()
    private val heartbeatFailures = ConcurrentHashMap.newKeySet<ClaimHealthKey>()
    private val heartbeatHealthy = AtomicBoolean(true)
    private val recoveryScanHealthy = AtomicBoolean(true)
    private val recoveryScanInProgress = AtomicBoolean(false)

    /** new admission と readiness を許可できるか返す。 */
    fun isHealthy(): Boolean = admissionLock.read { isHealthyLocked() }

    /**
     * terminal submission を確定させてよいか返す。
     *
     * 実行中の recovery scan は無視し、最後に完了した scan の成否だけを見る。scan は
     * 周期的に走るため、正常な実行中まで fail-closed にすると、run ごとに 1 回だけ
     * 起きる submission がその窓に当たって失われる。一方 scan の実障害は stale claim
     * を発見できない状態を意味するので fail-closed のままにする。
     */
    fun allowsTerminalSubmission(): Boolean = admissionLock.read {
        recoveryScanHealthy.get() && noBlockersLocked()
    }

    /** recovery scan の開始を記録する。直前の scan の成否はそのまま保つ。 */
    fun beginRecoveryScan() {
        admissionLock.write { recoveryScanInProgress.set(true) }
    }

    /** recovery scan の終了を成否とともに記録する。成否によらず実行中 flag を下ろす。 */
    fun completeRecoveryScan(successful: Boolean) {
        admissionLock.write {
            recoveryScanHealthy.set(successful)
            recoveryScanInProgress.set(false)
        }
    }

    /** health 判定と admission の永続化境界を blocker transition に対して atomic にする。 */
    fun <T> withHealthyAdmission(block: () -> T): T = admissionLock.read {
        check(isHealthyLocked()) { "LLM execution admission is fail-closed." }
        block()
    }

    /** outcome-unknown claim を unresolved として登録する。 */
    fun registerAmbiguous(invocationId: String, claimantToken: String = UNKNOWN_TOKEN) {
        admissionLock.write { ambiguousClaims += ClaimHealthKey(invocationId, claimantToken) }
    }

    /** terminal 確認済み claim を registry から除く。 */
    fun resolveAmbiguous(invocationId: String, claimantToken: String = UNKNOWN_TOKEN) {
        admissionLock.write { ambiguousClaims -= ClaimHealthKey(invocationId, claimantToken) }
    }

    /** heartbeat persistence の状態を更新する。 */
    fun setHeartbeatHealthy(healthy: Boolean) {
        admissionLock.write { heartbeatHealthy.set(healthy) }
    }

    /** claimant token 単位で heartbeat persistence failure を追跡する。 */
    fun recordHeartbeatResult(
        invocationId: String,
        claimantToken: String,
        healthy: Boolean,
    ) {
        val key = ClaimHealthKey(invocationId, claimantToken)
        admissionLock.write {
            if (healthy) heartbeatFailures -= key else heartbeatFailures += key
        }
    }

    /** periodic DB scan の成功状態を readiness / admission へ反映する。 */
    fun setRecoveryScanHealthy(healthy: Boolean) {
        admissionLock.write { recoveryScanHealthy.set(healthy) }
    }

    /** termination fence 不明または recovery race 中の claim を fail-closed blocker にする。 */
    fun registerRecoveryBlocker(invocationId: String, claimantToken: String = UNKNOWN_TOKEN) {
        admissionLock.write { recoveryBlockers += ClaimHealthKey(invocationId, claimantToken) }
    }

    /** live heartbeat または terminal 確認後だけ recovery blocker を解除する。 */
    fun resolveRecoveryBlocker(invocationId: String, claimantToken: String = UNKNOWN_TOKEN) {
        admissionLock.write { recoveryBlockers -= ClaimHealthKey(invocationId, claimantToken) }
    }

    /**
     * terminal 照合の対象になる unresolved blocker を snapshot として返す。
     *
     * 呼び出し側の iterate 中に registry が変化しないよう、lock 内でコピーを作る。
     */
    fun unresolvedBlockers(): Set<LlmExecutionAdmissionBlocker> = admissionLock.read {
        (recoveryBlockers + heartbeatFailures).mapTo(mutableSetOf()) { key ->
            LlmExecutionAdmissionBlocker(key.invocationId, key.claimantToken)
        }
    }

    /** terminal confirmation 後に同じ claim token の全 blocker を解除する。 */
    fun resolveClaim(invocationId: String, claimantToken: String) {
        val key = ClaimHealthKey(invocationId, claimantToken)
        admissionLock.write {
            ambiguousClaims -= key
            recoveryBlockers -= key
            heartbeatFailures -= key
        }
    }

    /** test process 内の状態を初期化する。 */
    internal fun resetForTest() {
        admissionLock.write {
            ambiguousClaims.clear()
            recoveryBlockers.clear()
            heartbeatFailures.clear()
            heartbeatHealthy.set(true)
            recoveryScanHealthy.set(true)
            recoveryScanInProgress.set(false)
        }
    }

    private fun isHealthyLocked(): Boolean = heartbeatHealthy.get() &&
        recoveryScanHealthy.get() &&
        !recoveryScanInProgress.get() &&
        noBlockersLocked()

    private fun noBlockersLocked(): Boolean = ambiguousClaims.isEmpty() &&
        recoveryBlockers.isEmpty() &&
        heartbeatFailures.isEmpty()
}

/**
 * DB terminal 照合の対象になる admission blocker の識別子。
 *
 * @param invocationId blocker が指す LLM 起動 ID
 * @param claimantToken blocker 登録時の claimant token
 */
data class LlmExecutionAdmissionBlocker(
    val invocationId: String,
    val claimantToken: String,
)

private data class ClaimHealthKey(val invocationId: String, val claimantToken: String)
private const val UNKNOWN_TOKEN = "<unknown>"
