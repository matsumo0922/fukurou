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
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

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
