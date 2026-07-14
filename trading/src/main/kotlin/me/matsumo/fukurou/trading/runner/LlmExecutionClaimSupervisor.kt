@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimSnapshot
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryRequest
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryOutcome
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryDeadline
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryRetryPermit
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
    private val transitionLocks = mutableMapOf<ClaimTransitionKey, ClaimTransitionLock>()
    private val transitionLocksGuard = Any()

    /** child start と recovery terminal decision を claimant token 単位で直列化する。 */
    suspend fun <T> withClaimTransition(
        invocationId: String,
        claimantToken: String,
        block: suspend () -> T,
    ): T = withClaimTransitions(
        transitions = listOf(
            LlmExecutionClaimTransition(
                invocationId = invocationId,
                claimantToken = claimantToken,
            ),
        ),
        block = block,
    )

    /** 複数claimのchild startとbatch recoveryをstable orderで直列化する。 */
    suspend fun <T> withClaimTransitions(
        transitions: Collection<LlmExecutionClaimTransition>,
        block: suspend () -> T,
    ): T {
        val keys = transitions
            .map { transition -> ClaimTransitionKey(transition.invocationId, transition.claimantToken) }
            .distinct()
            .sortedWith(compareBy(ClaimTransitionKey::invocationId, ClaimTransitionKey::claimantToken))
        val locks = synchronized(transitionLocksGuard) {
            keys.map { key ->
                transitionLocks.getOrPut(key) { ClaimTransitionLock(Mutex()) }
                    .also { lock -> lock.referenceCount += 1 }
            }
        }

        return try {
            withTransitionLocks(
                locks = locks,
                index = 0,
                block = block,
            )
        } finally {
            synchronized(transitionLocksGuard) {
                keys.zip(locks).forEach { (key, lock) ->
                    lock.referenceCount -= 1
                    if (lock.referenceCount == 0) transitionLocks.remove(key, lock)
                }
            }
        }
    }

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

    internal fun fenceCountForTest(): Int = fences.size

    internal fun transitionLockCountForTest(): Int = synchronized(transitionLocksGuard) { transitionLocks.size }

    internal fun resetForTest() {
        fences.clear()
        synchronized(transitionLocksGuard) { transitionLocks.clear() }
    }
}

/** claimant transition lockを一意に識別する。 */
data class LlmExecutionClaimTransition(
    val invocationId: String,
    val claimantToken: String,
)

/** bounded DB scan と termination fence を結合する current-process recovery。 */
class LlmExecutionRecoveryService(
    private val repository: LlmLaunchReservationRepository,
    private val policy: OneShotExecutionPolicy,
    private val clock: Clock,
    private val availableStaleAfter: Duration = Duration.ofMinutes(30),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var cursor: LlmExecutionRecoveryCursor? = null
    private val pendingRecoveries = LinkedHashMap<String, PendingExecutionRecovery>()

    /** 1 bounded scan を実行し、競合安全に stale claim を回収する。 */
    suspend fun tick(): Result<Int> {
        LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
        val deadline = LlmExecutionRecoveryDeadline.start(EXECUTION_RECOVERY_TICK_TIMEOUT, nanoTime)

        val result = runCatching {
            withTimeout(EXECUTION_RECOVERY_TICK_TIMEOUT.toMillis()) { tickWithinBudget(deadline) }
        }
        if (result.isSuccess) LlmExecutionAdmissionHealth.setRecoveryScanHealthy(true)

        return result
    }

    private suspend fun tickWithinBudget(deadline: LlmExecutionRecoveryDeadline): Int {
        requireRecoveryStartReserve(deadline)

        var recoveredCount = reconcilePendingRecoveries(deadline)

        val now = clock.instant()
        val hardDeadlineCutoff = now.minus(policy.hardTimeout).minus(policy.processTerminationGrace)
        val heartbeatCutoff = now.minus(policy.heartbeatMissAllowance)
        val currentCursor = cursor
        val candidates = repository.scanStaleExecutionClaims(
            LlmExecutionRecoveryScan(
                availableReservedBefore = now.minus(availableStaleAfter),
                claimedBefore = hardDeadlineCutoff,
                heartbeatBefore = heartbeatCutoff,
                limit = EXECUTION_RECOVERY_SCAN_LIMIT,
                afterHeartbeatAt = currentCursor?.heartbeatAt,
                afterClaimedAt = currentCursor?.claimedAt,
                afterInvocationId = currentCursor?.invocationId,
            ),
            deadline,
        ).getOrElse { throwable ->
            LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
            throw throwable
        }
        val transitions = candidates.map { candidate ->
            LlmExecutionClaimTransition(
                invocationId = candidate.invocationId,
                claimantToken = candidate.claimantToken ?: MISSING_CLAIMANT_TOKEN,
            )
        }
        recoveredCount += LlmExecutionTerminationFenceRegistry.withClaimTransitions(
            transitions = transitions,
        ) {
            val candidatesByInvocationId = candidates.associateBy(LlmExecutionClaimSnapshot::invocationId)
            val requestsByInvocationId = candidates.mapNotNull { candidate ->
                toRecoveryRequestOrNull(candidate, now)?.let { request -> candidate.invocationId to request }
            }
            var pageRecoveredCount = 0
            requestsByInvocationId.forEach { (invocationId, request) ->
                requireRecoveryStartReserve(deadline)

                val candidate = requireNotNull(candidatesByInvocationId[invocationId])
                stagePendingRecovery(candidate, request)
                val outcome = recoverPending(request, deadline)
                pageRecoveredCount += applyRecoveryOutcome(candidate, outcome)
            }

            pageRecoveredCount
        }
        check(pendingRecoveries.isEmpty()) { "Recovery page completed with unresolved attempts." }
        cursor = if (candidates.size < EXECUTION_RECOVERY_SCAN_LIMIT) {
            null
        } else {
            candidates.last().toRecoveryCursor()
        }

        return recoveredCount
    }

    private fun stagePendingRecovery(candidate: LlmExecutionClaimSnapshot, request: LlmExecutionRecoveryRequest) {
        pendingRecoveries[candidate.invocationId] = PendingExecutionRecovery(
            candidate = candidate,
            request = request,
            retryPermit = LlmExecutionRecoveryRetryPermit(),
        )
    }

    private suspend fun recoverPending(request: LlmExecutionRecoveryRequest, deadline: LlmExecutionRecoveryDeadline) =
        repository.recoverStaleExecutionClaim(
            request = request,
            deadline = deadline,
            retryPermit = requireNotNull(pendingRecoveries[request.invocationId]).retryPermit,
        ).getOrElse { throwable ->
            LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
            throw throwable
        }

    private suspend fun reconcilePendingRecoveries(deadline: LlmExecutionRecoveryDeadline): Int {
        if (pendingRecoveries.isEmpty()) return 0

        val transitions = pendingRecoveries.values.map { pending ->
            LlmExecutionClaimTransition(
                invocationId = pending.candidate.invocationId,
                claimantToken = pending.candidate.claimantToken ?: MISSING_CLAIMANT_TOKEN,
            )
        }
        return LlmExecutionTerminationFenceRegistry.withClaimTransitions(transitions) {
            var recoveredCount = 0
            pendingRecoveries.values.toList().forEach { pending ->
                requireRecoveryStartReserve(deadline)
                val outcome = repository.reconcileStaleExecutionRecovery(
                    request = pending.request,
                    deadline = deadline,
                    retryPermit = pending.retryPermit,
                )
                    .getOrElse { throwable ->
                        LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
                        throw throwable
                    }
                recoveredCount += applyRecoveryOutcome(pending.candidate, outcome)
            }
            recoveredCount
        }
    }

    private suspend fun requireRecoveryStartReserve(deadline: LlmExecutionRecoveryDeadline) {
        currentCoroutineContext().ensureActive()
        deadline.requireStartReserve(nanoTime, EXECUTION_RECOVERY_START_RESERVE_MILLIS)
    }

    private fun toRecoveryRequestOrNull(
        candidate: LlmExecutionClaimSnapshot,
        now: java.time.Instant,
    ): LlmExecutionRecoveryRequest? {
        val fenceKind = when (candidate.claimState) {
            LlmExecutionClaimState.AVAILABLE -> LlmExecutionTerminationFenceKind.NO_CHILD_STARTED
            LlmExecutionClaimState.CLAIMED -> {
                val claimantToken = candidate.claimantToken
                val fence = claimantToken?.let { token ->
                    LlmExecutionTerminationFenceRegistry.find(candidate.invocationId, token)
                }
                if (fence == null) {
                    LlmExecutionAdmissionHealth.registerRecoveryBlocker(
                        candidate.invocationId,
                        claimantToken ?: MISSING_CLAIMANT_TOKEN,
                    )
                    return null
                }
                fence.kind
            }
            LlmExecutionClaimState.NOT_REQUIRED,
            null,
            -> return null
        }

        return LlmExecutionRecoveryRequest(
            invocationId = candidate.invocationId,
            claimState = candidate.claimState,
            claimantToken = candidate.claimantToken,
            observedHeartbeatAt = candidate.heartbeatAt,
            observedReservedAt = candidate.reservedAt,
            finishedAt = java.time.Instant.ofEpochMilli(now.toEpochMilli()),
            reason = when (candidate.claimState) {
                LlmExecutionClaimState.AVAILABLE -> STALE_AVAILABLE_RESERVATION_RECOVERED
                LlmExecutionClaimState.CLAIMED -> STALE_CLAIMED_RESERVATION_RECOVERED
                LlmExecutionClaimState.NOT_REQUIRED -> error("NOT_REQUIRED is not recoverable.")
            },
            terminationFence = fenceKind.name,
        )
    }

    private fun applyRecoveryOutcome(candidate: LlmExecutionClaimSnapshot, outcome: LlmExecutionRecoveryOutcome): Int {
        when (outcome) {
            LlmExecutionRecoveryOutcome.Recovered -> {
                pendingRecoveries.remove(candidate.invocationId)
                completeRecoveryHealth(candidate)
                return 1
            }
            LlmExecutionRecoveryOutcome.TerminalObserved -> {
                pendingRecoveries.remove(candidate.invocationId)
                completeRecoveryHealth(candidate)
                return 0
            }
            LlmExecutionRecoveryOutcome.PreconditionChanged -> {
                pendingRecoveries.remove(candidate.invocationId)
                LlmExecutionAdmissionHealth.registerRecoveryBlocker(
                    candidate.invocationId,
                    candidate.claimantToken ?: MISSING_CLAIMANT_TOKEN,
                )
                return 0
            }
            is LlmExecutionRecoveryOutcome.OutcomeUnknown -> {
                LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
                throw outcome.cause
            }
        }
    }

    private fun completeRecoveryHealth(candidate: LlmExecutionClaimSnapshot) {
        candidate.claimantToken?.let { token ->
            LlmExecutionAdmissionHealth.resolveClaim(candidate.invocationId, token)
        } ?: LlmExecutionAdmissionHealth.resolveRecoveryBlocker(
            candidate.invocationId,
            MISSING_CLAIMANT_TOKEN,
        )
        LlmExecutionTerminationFenceRegistry.resolve(
            invocationId = candidate.invocationId,
            claimantToken = candidate.claimantToken ?: MISSING_CLAIMANT_TOKEN,
        )
    }
}

private data class PendingExecutionRecovery(
    val candidate: LlmExecutionClaimSnapshot,
    val request: LlmExecutionRecoveryRequest,
    val retryPermit: LlmExecutionRecoveryRetryPermit,
)

private data class LlmExecutionRecoveryCursor(
    val heartbeatAt: java.time.Instant,
    val claimedAt: java.time.Instant,
    val invocationId: String,
)

private data class ClaimTransitionKey(val invocationId: String, val claimantToken: String)

private data class ClaimTransitionLock(
    val mutex: Mutex,
    var referenceCount: Int = 0,
)

private suspend fun <T> withTransitionLocks(
    locks: List<ClaimTransitionLock>,
    index: Int,
    block: suspend () -> T,
): T {
    if (index == locks.size) return block()

    return locks[index].mutex.withLock {
        withTransitionLocks(
            locks = locks,
            index = index + 1,
            block = block,
        )
    }
}

private fun LlmExecutionClaimSnapshot.toRecoveryCursor(): LlmExecutionRecoveryCursor {
    return LlmExecutionRecoveryCursor(
        heartbeatAt = heartbeatAt ?: claimedAt ?: reservedAt,
        claimedAt = claimedAt ?: reservedAt,
        invocationId = invocationId,
    )
}

/** commit outcome unknown を current process 内で DB 復旧後まで照合する supervisor。 */
class LlmExecutionClaimSupervisor(
    private val repository: LlmLaunchReservationRepository,
    private val clock: Clock,
    private val interval: Duration,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val ambiguousClaims = ConcurrentHashMap<String, String>()
    private val lifecycleLock = Any()
    private var reconciliationJob: Job? = null

    /** unresolved claim を登録し、bounded periodic reconciliation を開始する。 */
    fun register(invocationId: String, claimantToken: String) {
        ambiguousClaims[invocationId] = claimantToken
        LlmExecutionAdmissionHealth.registerAmbiguous(invocationId, claimantToken)
        synchronized(lifecycleLock) {
            if (reconciliationJob?.isActive != true) startReconciliationLocked()
        }
    }

    private suspend fun reconcileLoop() {
        try {
            while (currentCoroutineContext().isActive && ambiguousClaims.isNotEmpty()) {
                ambiguousClaims.entries.toList().forEach { (invocationId, claimantToken) ->
                    reconcile(invocationId, claimantToken)
                }
                if (ambiguousClaims.isNotEmpty()) delay(interval.toMillis())
            }
        } finally {
            synchronized(lifecycleLock) {
                reconciliationJob = null
                if (ambiguousClaims.isNotEmpty()) startReconciliationLocked()
            }
        }
    }

    private fun startReconciliationLocked() {
        reconciliationJob = scope.launch { reconcileLoop() }
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
        LlmExecutionAdmissionHealth.resolveAmbiguous(invocationId, claimantToken)
    }
}

/** stale AVAILABLE reservation の recovery reason。 */
const val STALE_AVAILABLE_RESERVATION_RECOVERED = "launch_reservation_available_stale_recovered"

/** fenced stale CLAIMED reservation の recovery reason。 */
const val STALE_CLAIMED_RESERVATION_RECOVERED = "launch_reservation_claimed_stale_recovered"

private const val EXECUTION_RECOVERY_SCAN_LIMIT = 100
internal const val MISSING_CLAIMANT_TOKEN = "<missing-claimant-token>"
private const val EXECUTION_RECOVERY_START_RESERVE_MILLIS = 750L
private val EXECUTION_RECOVERY_TICK_TIMEOUT: Duration = Duration.ofSeconds(5)
