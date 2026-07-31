package me.matsumo.fukurou.trading.daemon

import java.time.Instant
import java.util.UUID
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
    private val recoveryBlockers = ConcurrentHashMap<ClaimHealthKey, RecoveryBlockerRecord>()
    private val heartbeatFailures = ConcurrentHashMap.newKeySet<ClaimHealthKey>()
    private val heartbeatHealthy = AtomicBoolean(true)
    private val recoveryScanHealthy = AtomicBoolean(true)

    /** new admission と readiness を許可できるか返す。 */
    fun isHealthy(): Boolean = admissionLock.read { isHealthyLocked() }

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
    fun registerRecoveryBlocker(
        invocationId: String,
        claimantToken: String = UNKNOWN_TOKEN,
        registeredAt: Instant,
        registeredAtNanos: Long,
    ) {
        val key = ClaimHealthKey(invocationId, claimantToken)
        val record = RecoveryBlockerRecord(
            registeredAt = registeredAt,
            registeredAtNanos = registeredAtNanos,
            resolutionAttemptId = UUID.randomUUID(),
        )
        admissionLock.write { recoveryBlockers.putIfAbsent(key, record) }
    }

    /** recovery blocker を安定順の bounded snapshot として返す。 */
    fun snapshotRecoveryBlockers(after: ClaimHealthKey?, limit: Int): List<RecoveryBlockerSnapshot> {
        require(limit > 0) { "recovery blocker snapshot limit must be positive." }

        return admissionLock.read {
            recoveryBlockers.entries.asSequence()
                .filter { (key) -> after == null || key > after }
                .sortedBy { (key) -> key }
                .take(limit)
                .map { (key, record) -> RecoveryBlockerSnapshot(key, record) }
                .toList()
        }
    }

    /** live heartbeat または terminal 確認後だけ recovery blocker を解除する。 */
    fun resolveRecoveryBlocker(invocationId: String, claimantToken: String = UNKNOWN_TOKEN) {
        admissionLock.write { recoveryBlockers.remove(ClaimHealthKey(invocationId, claimantToken)) }
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
        }
    }

    private fun isHealthyLocked(): Boolean = heartbeatHealthy.get() &&
        recoveryScanHealthy.get() &&
        ambiguousClaims.isEmpty() &&
        recoveryBlockers.isEmpty() &&
        heartbeatFailures.isEmpty()
}

/** recovery blocker の安定 cursor key。 */
data class ClaimHealthKey(val invocationId: String, val claimantToken: String) : Comparable<ClaimHealthKey> {
    override fun compareTo(other: ClaimHealthKey): Int {
        val invocationComparison = invocationId.compareTo(other.invocationId)
        return if (invocationComparison != 0) invocationComparison else claimantToken.compareTo(other.claimantToken)
    }
}

/** recovery blocker の初回観測情報。 */
data class RecoveryBlockerRecord(
    val registeredAt: Instant,
    val registeredAtNanos: Long,
    val resolutionAttemptId: UUID,
)

/** recovery scan が読む blocker snapshot。 */
data class RecoveryBlockerSnapshot(
    val key: ClaimHealthKey,
    val record: RecoveryBlockerRecord,
)

private const val UNKNOWN_TOKEN = "<unknown>"
