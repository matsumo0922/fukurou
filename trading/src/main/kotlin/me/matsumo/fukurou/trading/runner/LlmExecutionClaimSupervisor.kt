@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimSnapshot
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryRequest
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryScan
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** recovery が認める child process tree の終了証跡。 */
enum class LlmExecutionTerminationFenceKind {
    /** claim 後に child start 境界へ到達していない。 */
    NO_CHILD_STARTED,

    /** TERM/KILL/wait 後に process tree exit を確認した。 */
    PROCESS_TREE_EXITED,
}

/** claimant token に紐づく termination fence。 */
data class LlmExecutionTerminationFence(
    val claimantToken: String,
    val kind: LlmExecutionTerminationFenceKind,
    val observedAt: java.time.Instant,
)

/** current process が観測した child lifecycle fence registry。 */
object LlmExecutionTerminationFenceRegistry {
    private val fences = ConcurrentHashMap<String, LlmExecutionTerminationFence?>()

    /** claim 直後の child 0 状態を登録する。 */
    fun registerNoChildStarted(
        invocationId: String,
        claimantToken: String,
        observedAt: java.time.Instant,
    ) {
        fences[invocationId] = LlmExecutionTerminationFence(
            claimantToken = claimantToken,
            kind = LlmExecutionTerminationFenceKind.NO_CHILD_STARTED,
            observedAt = observedAt,
        )
    }

    /** child start の可能性が生じた時点で NO_CHILD_STARTED fence を無効化する。 */
    fun markChildMayBeRunning(invocationId: String, claimantToken: String) {
        fences.computeIfPresent(invocationId) { _, current ->
            current.takeUnless { fence -> fence.claimantToken == claimantToken }
        }
    }

    /** process runner から戻り process tree exit が成立した状態を登録する。 */
    fun markProcessTreeExited(
        invocationId: String,
        claimantToken: String,
        observedAt: java.time.Instant,
    ) {
        fences[invocationId] = LlmExecutionTerminationFence(
            claimantToken = claimantToken,
            kind = LlmExecutionTerminationFenceKind.PROCESS_TREE_EXITED,
            observedAt = observedAt,
        )
    }

    /** token が一致する termination fence だけを返す。 */
    fun find(invocationId: String, claimantToken: String): LlmExecutionTerminationFence? {
        return fences[invocationId]?.takeIf { fence -> fence.claimantToken == claimantToken }
    }

    /** terminal 確認後に registry entry を削除する。 */
    fun resolve(invocationId: String, claimantToken: String) {
        fences.computeIfPresent(invocationId) { _, current ->
            current.takeUnless { fence -> fence.claimantToken == claimantToken }
        }
    }

    internal fun resetForTest() {
        fences.clear()
    }
}

/** bounded DB scan と termination fence を結合する current-process recovery。 */
class LlmExecutionRecoveryService(
    private val repository: LlmLaunchReservationRepository,
    private val policy: OneShotExecutionPolicy,
    private val clock: Clock,
) {
    /** 1 bounded scan を実行し、競合安全に stale claim を回収する。 */
    suspend fun tick(): Result<Int> = runCatching {
        val now = clock.instant()
        val hardDeadlineCutoff = now.minus(policy.hardTimeout).minus(policy.processTerminationGrace)
        val heartbeatCutoff = now.minus(policy.heartbeatMissAllowance)
        val candidates = repository.scanStaleExecutionClaims(
            LlmExecutionRecoveryScan(
                availableReservedBefore = hardDeadlineCutoff,
                claimedBefore = hardDeadlineCutoff,
                heartbeatBefore = heartbeatCutoff,
                limit = EXECUTION_RECOVERY_SCAN_LIMIT,
            ),
        ).getOrElse { throwable ->
            LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
            throw throwable
        }
        LlmExecutionAdmissionHealth.setRecoveryScanHealthy(true)

        candidates.count { candidate -> recoverCandidate(candidate, now) }
    }

    private suspend fun recoverCandidate(candidate: LlmExecutionClaimSnapshot, now: java.time.Instant): Boolean {
        val fenceKind = when (candidate.claimState) {
            LlmExecutionClaimState.AVAILABLE -> LlmExecutionTerminationFenceKind.NO_CHILD_STARTED
            LlmExecutionClaimState.CLAIMED -> {
                val claimantToken = candidate.claimantToken
                val fence = claimantToken?.let { token ->
                    LlmExecutionTerminationFenceRegistry.find(candidate.invocationId, token)
                }
                if (fence == null) {
                    LlmExecutionAdmissionHealth.registerRecoveryBlocker(candidate.invocationId)
                    return false
                }
                fence.kind
            }
            LlmExecutionClaimState.NOT_REQUIRED,
            null,
            -> return false
        }
        val recovered = repository.recoverStaleExecutionClaim(
            LlmExecutionRecoveryRequest(
                invocationId = candidate.invocationId,
                claimState = candidate.claimState,
                claimantToken = candidate.claimantToken,
                observedHeartbeatAt = candidate.heartbeatAt,
                observedReservedAt = candidate.reservedAt,
                finishedAt = now,
                reason = when (candidate.claimState) {
                    LlmExecutionClaimState.AVAILABLE -> STALE_AVAILABLE_RESERVATION_RECOVERED
                    LlmExecutionClaimState.CLAIMED -> STALE_CLAIMED_RESERVATION_RECOVERED
                    LlmExecutionClaimState.NOT_REQUIRED -> error("NOT_REQUIRED is not recoverable.")
                },
                terminationFence = fenceKind.name,
            ),
        ).getOrElse { throwable ->
            LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
            throw throwable
        }
        if (!recovered) {
            LlmExecutionAdmissionHealth.registerRecoveryBlocker(candidate.invocationId)
            return false
        }

        LlmExecutionAdmissionHealth.resolveRecoveryBlocker(candidate.invocationId)
        if (candidate.claimState == LlmExecutionClaimState.CLAIMED) {
            LlmExecutionAdmissionHealth.setHeartbeatHealthy(true)
        }
        candidate.claimantToken?.let { token ->
            LlmExecutionTerminationFenceRegistry.resolve(candidate.invocationId, token)
        }
        return true
    }
}

/** commit outcome unknown を current process 内で DB 復旧後まで照合する supervisor。 */
class LlmExecutionClaimSupervisor(
    private val repository: LlmLaunchReservationRepository,
    private val clock: Clock,
    private val interval: Duration,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val ambiguousClaims = ConcurrentHashMap<String, String>()
    private var reconciliationJob: Job? = null

    /** unresolved claim を登録し、bounded periodic reconciliation を開始する。 */
    fun register(invocationId: String, claimantToken: String) {
        ambiguousClaims[invocationId] = claimantToken
        LlmExecutionAdmissionHealth.registerAmbiguous(invocationId)
        if (reconciliationJob?.isActive == true) return
        reconciliationJob = scope.launch { reconcileLoop() }
    }

    private suspend fun reconcileLoop() {
        while (currentCoroutineContext().isActive && ambiguousClaims.isNotEmpty()) {
            ambiguousClaims.entries.toList().forEach { (invocationId, claimantToken) ->
                reconcile(invocationId, claimantToken)
            }
            if (ambiguousClaims.isNotEmpty()) delay(interval.toMillis())
        }
    }

    private suspend fun reconcile(invocationId: String, claimantToken: String) {
        val snapshot = repository.findExecutionClaim(invocationId).getOrNull() ?: return
        val sameTokenClaim = snapshot.claimState == LlmExecutionClaimState.CLAIMED &&
            snapshot.claimantToken == claimantToken
        if (sameTokenClaim && snapshot.status == LlmLaunchReservationStatus.RUNNING) {
            repository.finish(
                LlmLaunchReservationFinish(
                    invocationId = invocationId,
                    status = LlmLaunchReservationStatus.FAILED,
                    reason = LAUNCH_RESERVATION_CLAIM_OUTCOME_UNKNOWN,
                    finishedAt = clock.instant(),
                    claimantToken = claimantToken,
                    observedHeartbeatAt = snapshot.heartbeatAt,
                ),
            ).getOrNull() ?: return
        }
        val terminalSnapshot = repository.findExecutionClaim(invocationId).getOrNull() ?: return
        if (terminalSnapshot.status == LlmLaunchReservationStatus.RUNNING) return

        ambiguousClaims.remove(invocationId, claimantToken)
        LlmExecutionAdmissionHealth.resolveAmbiguous(invocationId)
    }
}

/** stale AVAILABLE reservation の recovery reason。 */
const val STALE_AVAILABLE_RESERVATION_RECOVERED = "launch_reservation_available_stale_recovered"

/** fenced stale CLAIMED reservation の recovery reason。 */
const val STALE_CLAIMED_RESERVATION_RECOVERED = "launch_reservation_claimed_stale_recovered"

private const val EXECUTION_RECOVERY_SCAN_LIMIT = 100
