package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.time.Duration
import java.time.Instant

/**
 * LLM daemon scheduler が起動する trigger 種別。
 */
enum class LlmDaemonTriggerKind {
    /**
     * flat 状態で event 条件がない場合の heartbeat。
     */
    FLAT_HEARTBEAT,

    /**
     * 経済イベント calendar による発火。
     */
    ECONOMIC_EVENT,

    /**
     * 短時間の価格急変による発火。
     */
    PRICE_MOVE,

    /**
     * 保有中 position の STOP 接近による発火。
     */
    STOP_PROXIMITY,

    /**
     * paper entry order の約定直後に thesis を再評価する発火。
     */
    ENTRY_FILL,

    /**
     * 建玉または open order がある場合の密な確認。
     */
    HOLDING_DENSE_CHECK,

    /**
     * 運用者の手動 API による即時起動。
     */
    MANUAL,

    /**
     * 週次 reflection による prompt candidate 生成。
     */
    REFLECTION,

    /**
     * Evaluation Report Console の手動 report 生成。
     */
    EVALUATION_REPORT,
}

/**
 * LLM 起動予約の状態。
 */
enum class LlmLaunchReservationStatus {
    /**
     * 起動予約済みで runner が完了していない。
     */
    RUNNING,

    /**
     * runner が正常系の Result として完了した。
     */
    FINISHED,

    /**
     * runner 起動または実行が例外で失敗した。
     */
    FAILED,
}

/** LLM 起動予約の実行権 claim 状態。 */
enum class LlmExecutionClaimState {
    /** one-shot runner が実行権を取得できる。 */
    AVAILABLE,

    /** one-shot runner が実行権を取得済みである。 */
    CLAIMED,

    /** reflection / evaluation の caller-owned lifecycle で claim を使わない。 */
    NOT_REQUIRED,
}

/** 実行権 claim の拒否理由。 */
enum class LlmExecutionClaimRejectionReason(val wireCode: String) {
    /** invocation ID に対応する予約が存在しない。 */
    RESERVATION_MISSING("launch_reservation_missing"),

    /** 予約と request の trigger が一致しない。 */
    TRIGGER_MISMATCH("launch_reservation_trigger_mismatch"),

    /** 予約が既に terminal である。 */
    TERMINAL("launch_reservation_terminal"),

    /** 別 runner または replay が実行権を取得済みである。 */
    ALREADY_CLAIMED("launch_reservation_already_claimed"),

    /** migration 前の NULL claim state なので実行できない。 */
    LEGACY_UNCLAIMABLE("launch_reservation_legacy_unclaimable"),

    /** caller-owned lifecycle なので one-shot claim を許可しない。 */
    CLAIM_NOT_REQUIRED("launch_reservation_claim_not_required"),
}

/** one-shot runner の実行権 claim 要求。 */
data class LlmExecutionClaimRequest(
    val invocationId: String,
    val triggerKind: LlmDaemonTriggerKind,
    val claimantToken: String,
    val claimedAt: Instant,
    val activeSince: Instant = Instant.ofEpochMilli(Long.MIN_VALUE),
)

/** 実行権 claim の結果。 */
sealed interface LlmExecutionClaimOutcome {
    /** conditional update の commit により実行権を取得した。 */
    data class Claimed(val claimedAt: Instant) : LlmExecutionClaimOutcome

    /** stable reason で claim を拒否した。 */
    data class Rejected(val reason: LlmExecutionClaimRejectionReason) : LlmExecutionClaimOutcome

    /** commit 成否を応答から確定できない。 */
    data class OutcomeUnknown(val cause: Throwable) : LlmExecutionClaimOutcome
}

/** outcome-unknown reconciliation に使う予約 snapshot。 */
data class LlmExecutionClaimSnapshot(
    val invocationId: String,
    val triggerKind: LlmDaemonTriggerKind,
    val status: LlmLaunchReservationStatus,
    val claimState: LlmExecutionClaimState?,
    val claimantToken: String?,
    val claimedAt: Instant?,
    val heartbeatAt: Instant?,
    val reservedAt: Instant,
)

/** stale execution claim の bounded scan 条件。 */
data class LlmExecutionRecoveryScan(
    val availableReservedBefore: Instant,
    val claimedBefore: Instant,
    val heartbeatBefore: Instant,
    val limit: Int,
    val afterHeartbeatAt: Instant? = null,
    val afterClaimedAt: Instant? = null,
    val afterInvocationId: String? = null,
) {
    init {
        require(limit > 0) { "execution recovery scan limit must be positive." }
        val cursorParts = listOf(afterHeartbeatAt, afterClaimedAt, afterInvocationId)
        require(cursorParts.all { it == null } || cursorParts.all { it != null }) {
            "execution recovery cursor must be complete."
        }
    }
}

/** stale execution claim を競合安全に FAILED へ遷移させる要求。 */
data class LlmExecutionRecoveryRequest(
    val invocationId: String,
    val claimState: LlmExecutionClaimState,
    val claimantToken: String?,
    val observedHeartbeatAt: Instant?,
    val observedReservedAt: Instant,
    val finishedAt: Instant,
    val reason: String,
    val terminationFence: String,
)

/**
 * LLM 起動予約要求。
 *
 * @param invocationId runner と audit に使う起動 ID
 * @param triggerKind 起動理由の種別
 * @param triggerKey cadence 判定に使う trigger 固有 key
 * @param reservedAt 予約時刻
 * @param runnerConfig 起動上限設定
 * @param hourlyWindow 1 時間上限の集計 window
 * @param dailyWindow 1 日上限の集計 window
 * @param activeReservationStaleAfter 異常終了した RUNNING 予約を同時起動扱いから外す時間
 */
data class LlmLaunchReservationRequest(
    val invocationId: String,
    val triggerKind: LlmDaemonTriggerKind,
    val triggerKey: String,
    val reservedAt: Instant,
    val runnerConfig: LlmRunnerConfig,
    val hourlyWindow: Duration,
    val dailyWindow: Duration,
    val activeReservationStaleAfter: Duration,
)

/**
 * LLM 起動予約完了要求。
 *
 * @param invocationId 完了させる起動 ID
 * @param status 完了状態
 * @param reason 失敗や no-trade などの補助理由
 * @param finishedAt 完了時刻
 */
data class LlmLaunchReservationFinish(
    val invocationId: String,
    val status: LlmLaunchReservationStatus,
    val reason: String?,
    val finishedAt: Instant,
    val claimantToken: String? = null,
    val observedHeartbeatAt: Instant? = null,
)

/** 同時起動を阻止している RUNNING reservation の監査用 identity。 */
data class LlmActiveLaunchReservation(
    val invocationId: String,
    val triggerKind: LlmDaemonTriggerKind,
    val triggerKey: String,
    val reservedAt: Instant,
)

/**
 * 起動予約の試行結果。
 */
sealed interface LlmLaunchReservationOutcome {
    /**
     * 起動予約を取得できた。
     *
     * @param invocationId 予約済み起動 ID
     */
    data class Reserved(
        val invocationId: String,
    ) : LlmLaunchReservationOutcome

    /**
     * 起動予約を拒否した。
     *
     * @param reason 監査に保存する拒否理由
     */
    data class Rejected(
        val reason: LlmLaunchReservationRejectionReason,
        val activeReservation: LlmActiveLaunchReservation? = null,
    ) : LlmLaunchReservationOutcome
}

/**
 * LLM 起動予約を拒否した理由。
 */
enum class LlmLaunchReservationRejectionReason {
    /**
     * Evaluation report 固有の 1 時間 request rate を超過した。
     */
    REPORT_RATE_LIMIT,

    /**
     * sticky HARD_HALT 中だった。
     */
    HARD_HALT,

    /**
     * 別の daemon 起動予約がまだ RUNNING だった。
     */
    CONCURRENT_INVOCATION,

    /**
     * 直近 1 時間の起動上限に達していた。
     */
    MAX_INVOCATIONS_PER_HOUR,

    /**
     * 直近 24 時間の起動上限に達していた。
     */
    MAX_INVOCATIONS_PER_DAY,

    /** ENTRY_FILL の未使用 1 時間 reserve を保護した。 */
    ENTRY_FILL_HOURLY_RESERVE,

    /** ENTRY_FILL の未使用 24 時間 reserve を保護した。 */
    ENTRY_FILL_DAILY_RESERVE,

    /** STOP_PROXIMITY の未使用 1 時間 reserve を保護した。 */
    STOP_PROXIMITY_HOURLY_RESERVE,

    /** STOP_PROXIMITY の未使用 24 時間 reserve を保護した。 */
    STOP_PROXIMITY_DAILY_RESERVE,

    /**
     * reflection 用に残すべき 1 時間 headroom を下回っていた。
     */
    INSUFFICIENT_REFLECTION_HOURLY_HEADROOM,

    /**
     * reflection 用に残すべき 24 時間 headroom を下回っていた。
     */
    INSUFFICIENT_REFLECTION_DAILY_HEADROOM,

    /** evaluation report より高 priority の trading decision 用 1 時間 headroom が不足した。 */
    INSUFFICIENT_EVALUATION_HOURLY_HEADROOM,

    /** evaluation report より高 priority の trading decision 用 24 時間 headroom が不足した。 */
    INSUFFICIENT_EVALUATION_DAILY_HEADROOM,
}

/**
 * daemon scheduler の起動予約 repository。
 *
 * daemon 経路の起動予算と同時起動の正本であり、DB 実装では予約時刻を起動時刻の正本とする。
 * 予約のない legacy run だけ runner 完了 audit の時刻へ fallback する。
 * runner preflight は手動 one-shot も含む最後の防衛線として残す。
 */
@Suppress("TooManyFunctions")
interface LlmLaunchReservationRepository {
    /**
     * HARD_HALT / 同時起動 / 起動予算を見て、起動予約を原子的に確保する。
     */
    suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome>

    /**
     * 起動予約を完了状態へ更新する。
     */
    suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit>

    /** AVAILABLE / RUNNING reservation を一度だけ CLAIMED へ遷移させる。 */
    suspend fun claimForExecution(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
        return LlmExecutionClaimOutcome.OutcomeUnknown(
            UnsupportedOperationException("Execution claim is not implemented."),
        )
    }

    /** outcome unknown を claimant token で照合する。 */
    suspend fun findExecutionClaim(invocationId: String): Result<LlmExecutionClaimSnapshot?> {
        return Result.failure(UnsupportedOperationException("Execution claim lookup is not implemented."))
    }

    /** live owner の lease heartbeat を conditional update する。 */
    suspend fun heartbeatExecutionClaim(
        invocationId: String,
        claimantToken: String,
        heartbeatAt: Instant,
    ): Result<Boolean> = Result.failure(UnsupportedOperationException("Execution claim heartbeat is not implemented."))

    /** stale AVAILABLE / CLAIMED reservation を bounded scan する。 */
    suspend fun scanStaleExecutionClaims(scan: LlmExecutionRecoveryScan): Result<List<LlmExecutionClaimSnapshot>> {
        return Result.failure(UnsupportedOperationException("Execution recovery scan is not implemented."))
    }

    /** scan 時点の state / token / heartbeat を fence に conditional FAILED へ遷移させる。 */
    suspend fun recoverStaleExecutionClaim(request: LlmExecutionRecoveryRequest): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("Execution claim recovery is not implemented."))
    }

    /** scan page を一括 recovery し、conditional update に成功した invocation ID を返す。 */
    suspend fun recoverStaleExecutionClaims(requests: List<LlmExecutionRecoveryRequest>): Result<Set<String>> {
        return runCatching {
            requests.mapNotNullTo(mutableSetOf()) { request ->
                request.invocationId.takeIf { recoverStaleExecutionClaim(request).getOrThrow() }
            }
        }
    }

    /** runner preflight 用に予約の trigger identity を返す。 */
    suspend fun findTriggerKind(invocationId: String): Result<LlmDaemonTriggerKind?> {
        return Result.failure(UnsupportedOperationException("Reservation identity lookup is not implemented."))
    }

    /**
     * trigger key ごとの最後の予約時刻を返す。
     */
    suspend fun latestReservedAt(triggerKey: String): Result<Instant?>

    /**
     * trigger key ごとの最後に正常完了した予約時刻を返す。
     */
    suspend fun latestFinishedReservedAt(triggerKey: String): Result<Instant?>

    /**
     * stale ではない trading RUNNING 予約が存在するか返す。
     */
    suspend fun findBlockingRunningReservation(
        requestTriggerKind: LlmDaemonTriggerKind,
        activeSince: Instant,
    ): Result<LlmActiveLaunchReservation?>

    /** 既存 caller 互換用の blocker 有無判定。 */
    suspend fun hasFreshRunningReservation(activeSince: Instant): Result<Boolean> {
        return findBlockingRunningReservation(LlmDaemonTriggerKind.FLAT_HEARTBEAT, activeSince)
            .map { it != null }
    }
}

/**
 * unit test 用の in-memory 起動予約 repository。
 *
 * @param riskStateRepository HARD_HALT 判定に使う repository
 */
@Suppress("TooManyFunctions")
class InMemoryLlmLaunchReservationRepository(
    private val riskStateRepository: RiskStateRepository,
) : LlmLaunchReservationRepository {

    private val mutex = Mutex()
    private val reservations = mutableListOf<LlmLaunchReservationRecord>()

    override suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome> {
        return runCatching {
            check(LlmExecutionAdmissionHealth.isHealthy()) { "LLM execution admission is fail-closed." }
            mutex.withLock {
                tryReserveLocked(request)
            }
        }
    }

    override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> {
        return runCatching {
            mutex.withLock {
                val index = reservations.indexOfFirst { reservation -> reservation.invocationId == finish.invocationId }

                require(index >= 0) {
                    "LLM launch reservation was not found. invocationId=${finish.invocationId}"
                }

                val currentReservation = reservations[index]
                val tokenMatches = when (currentReservation.claimState) {
                    LlmExecutionClaimState.CLAIMED -> {
                        finish.claimantToken != null && currentReservation.claimantToken == finish.claimantToken
                    }
                    LlmExecutionClaimState.AVAILABLE,
                    LlmExecutionClaimState.NOT_REQUIRED,
                    null,
                    -> {
                        finish.claimantToken == null || currentReservation.claimantToken == finish.claimantToken
                    }
                }
                val heartbeatMatches = finish.observedHeartbeatAt == null ||
                    currentReservation.heartbeatAt == finish.observedHeartbeatAt
                val finishOwnsRunningReservation = currentReservation.status == LlmLaunchReservationStatus.RUNNING &&
                    tokenMatches && heartbeatMatches
                if (!finishOwnsRunningReservation) {
                    return@withLock
                }

                reservations[index] = currentReservation.copy(
                    status = finish.status,
                    finishedAt = finish.finishedAt,
                    reason = finish.reason,
                )
            }
        }
    }

    override suspend fun claimForExecution(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
        return try {
            check(LlmExecutionAdmissionHealth.isHealthy()) { "LLM execution claim is fail-closed." }
            mutex.withLock {
                val index = reservations.indexOfFirst { it.invocationId == request.invocationId }
                if (index < 0) {
                    return@withLock LlmExecutionClaimOutcome.Rejected(
                        LlmExecutionClaimRejectionReason.RESERVATION_MISSING,
                    )
                }
                val reservation = reservations[index]
                val rejection = reservation.claimRejection(request.triggerKind)
                    ?: LlmExecutionClaimRejectionReason.TERMINAL.takeIf {
                        reservation.reservedAt.isBefore(request.activeSince)
                    }
                if (rejection != null) return@withLock LlmExecutionClaimOutcome.Rejected(rejection)

                reservations[index] = reservation.copy(
                    claimState = LlmExecutionClaimState.CLAIMED,
                    claimantToken = request.claimantToken,
                    claimedAt = request.claimedAt,
                    heartbeatAt = request.claimedAt,
                )
                LlmExecutionClaimOutcome.Claimed(request.claimedAt)
            }
        } catch (throwable: Throwable) {
            LlmExecutionClaimOutcome.OutcomeUnknown(throwable)
        }
    }

    override suspend fun findExecutionClaim(invocationId: String): Result<LlmExecutionClaimSnapshot?> = runCatching {
        mutex.withLock { reservations.firstOrNull { it.invocationId == invocationId }?.toClaimSnapshot() }
    }

    override suspend fun heartbeatExecutionClaim(
        invocationId: String,
        claimantToken: String,
        heartbeatAt: Instant,
    ): Result<Boolean> = runCatching {
        mutex.withLock {
            val index = reservations.indexOfFirst { it.invocationId == invocationId }
            if (index < 0) return@withLock false
            val reservation = reservations[index]
            val heartbeatOwnsRunningClaim = reservation.status == LlmLaunchReservationStatus.RUNNING &&
                reservation.claimState == LlmExecutionClaimState.CLAIMED &&
                reservation.claimantToken == claimantToken
            if (!heartbeatOwnsRunningClaim) {
                return@withLock false
            }
            reservations[index] = reservation.copy(heartbeatAt = heartbeatAt)
            true
        }
    }

    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
    ): Result<List<LlmExecutionClaimSnapshot>> = runCatching {
        mutex.withLock {
            reservations.asSequence()
                .filter { reservation -> reservation.status == LlmLaunchReservationStatus.RUNNING }
                .filter { reservation ->
                    when (reservation.claimState) {
                        LlmExecutionClaimState.AVAILABLE -> !reservation.reservedAt.isAfter(scan.availableReservedBefore)
                        LlmExecutionClaimState.CLAIMED -> {
                            val claimedAt = reservation.claimedAt
                            val heartbeatAt = reservation.heartbeatAt
                            claimedAt != null && heartbeatAt != null &&
                                !claimedAt.isAfter(scan.claimedBefore) &&
                                !heartbeatAt.isAfter(scan.heartbeatBefore)
                        }
                        LlmExecutionClaimState.NOT_REQUIRED,
                        null,
                        -> false
                    }
                }
                .filter { reservation -> reservation.isAfterRecoveryCursor(scan) }
                .sortedWith(
                    compareBy<LlmLaunchReservationRecord> { it.recoverySortHeartbeatAt() }
                        .thenBy { it.recoverySortClaimedAt() }
                        .thenBy { it.invocationId },
                )
                .take(scan.limit)
                .map(LlmLaunchReservationRecord::toClaimSnapshot)
                .toList()
        }
    }

    override suspend fun recoverStaleExecutionClaim(request: LlmExecutionRecoveryRequest): Result<Boolean> {
        return runCatching {
            mutex.withLock {
                val index = reservations.indexOfFirst { reservation -> reservation.invocationId == request.invocationId }
                if (index < 0) return@withLock false

                val reservation = reservations[index]
                val ownsObservedState = reservation.status == LlmLaunchReservationStatus.RUNNING &&
                    reservation.claimState == request.claimState &&
                    reservation.reservedAt == request.observedReservedAt &&
                    reservation.claimantToken == request.claimantToken &&
                    reservation.heartbeatAt == request.observedHeartbeatAt
                if (!ownsObservedState) return@withLock false

                reservations[index] = reservation.copy(
                    status = LlmLaunchReservationStatus.FAILED,
                    finishedAt = request.finishedAt,
                    reason = request.reason,
                )
                true
            }
        }
    }

    override suspend fun recoverStaleExecutionClaims(
        requests: List<LlmExecutionRecoveryRequest>,
    ): Result<Set<String>> = runCatching {
        mutex.withLock {
            requests.mapNotNullTo(mutableSetOf()) { request ->
                val index = reservations.indexOfFirst { reservation -> reservation.invocationId == request.invocationId }
                if (index < 0) return@mapNotNullTo null

                val reservation = reservations[index]
                val ownsObservedState = reservation.status == LlmLaunchReservationStatus.RUNNING &&
                    reservation.claimState == request.claimState &&
                    reservation.reservedAt == request.observedReservedAt &&
                    reservation.claimantToken == request.claimantToken &&
                    reservation.heartbeatAt == request.observedHeartbeatAt
                if (!ownsObservedState) return@mapNotNullTo null

                reservations[index] = reservation.copy(
                    status = LlmLaunchReservationStatus.FAILED,
                    finishedAt = request.finishedAt,
                    reason = request.reason,
                )
                request.invocationId
            }
        }
    }

    override suspend fun findTriggerKind(invocationId: String): Result<LlmDaemonTriggerKind?> = runCatching {
        mutex.withLock { reservations.firstOrNull { it.invocationId == invocationId }?.triggerKind }
    }

    override suspend fun latestReservedAt(triggerKey: String): Result<Instant?> {
        return runCatching {
            mutex.withLock {
                reservations
                    .filter { reservation -> reservation.triggerKey == triggerKey }
                    .maxOfOrNull { reservation -> reservation.reservedAt }
            }
        }
    }

    override suspend fun latestFinishedReservedAt(triggerKey: String): Result<Instant?> {
        return runCatching {
            mutex.withLock {
                reservations
                    .filter { reservation -> reservation.triggerKey == triggerKey }
                    .filter { reservation -> reservation.status == LlmLaunchReservationStatus.FINISHED }
                    .maxOfOrNull { reservation -> reservation.reservedAt }
            }
        }
    }

    override suspend fun findBlockingRunningReservation(
        requestTriggerKind: LlmDaemonTriggerKind,
        activeSince: Instant,
    ): Result<LlmActiveLaunchReservation?> {
        return runCatching {
            mutex.withLock {
                reservations.asSequence()
                    .filter { reservation -> reservation.isFreshRunning(activeSince) }
                    .filter { reservation -> requestTriggerKind in setOf(LlmDaemonTriggerKind.REFLECTION, LlmDaemonTriggerKind.EVALUATION_REPORT) || reservation.triggerKind != LlmDaemonTriggerKind.REFLECTION }
                    .sortedWith(compareBy<LlmLaunchReservationRecord> { it.reservedAt }.thenBy { it.invocationId })
                    .firstOrNull()
                    ?.toActive()
            }
        }
    }

    private fun activeReservation(request: LlmLaunchReservationRequest): LlmLaunchReservationRecord? {
        val activeSince = request.reservedAt.minus(request.activeReservationStaleAfter)

        return reservations.firstOrNull { reservation ->
            val freshRunning = reservation.isFreshRunning(activeSince)
            val blockingForRequest = when (request.triggerKind) {
                LlmDaemonTriggerKind.REFLECTION,
                LlmDaemonTriggerKind.EVALUATION_REPORT,
                -> true
                else -> reservation.triggerKind != LlmDaemonTriggerKind.REFLECTION
            }

            freshRunning && blockingForRequest
        }
    }

    private suspend fun tryReserveLocked(request: LlmLaunchReservationRequest): LlmLaunchReservationOutcome {
        val riskState = riskStateRepository.current().getOrThrow()

        if (riskState.state == RiskHaltState.HARD_HALT) {
            return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.HARD_HALT)
        }

        activeReservation(request)?.let { active ->
            return LlmLaunchReservationOutcome.Rejected(
                LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION,
                active.toActive(),
            )
        }

        val hourlyUsage = usageSince(request.reservedAt.minus(request.hourlyWindow))
        val dailyUsage = usageSince(request.reservedAt.minus(request.dailyWindow))

        launchBudgetRejection(request, hourlyUsage, dailyUsage)?.let { rejectionReason ->
            return LlmLaunchReservationOutcome.Rejected(rejectionReason)
        }

        reservations += LlmLaunchReservationRecord(
            invocationId = request.invocationId,
            triggerKind = request.triggerKind,
            triggerKey = request.triggerKey,
            status = LlmLaunchReservationStatus.RUNNING,
            reservedAt = request.reservedAt,
            finishedAt = null,
            reason = null,
            claimState = request.triggerKind.executionClaimState(),
            claimantToken = null,
            claimedAt = null,
            heartbeatAt = null,
        )

        return LlmLaunchReservationOutcome.Reserved(request.invocationId)
    }

    private fun usageSince(since: Instant): LlmLaunchUsage {
        val current = reservations.filter { reservation -> !reservation.reservedAt.isBefore(since) }
        return LlmLaunchUsage(
            total = current.distinctBy { it.invocationId }.size,
            entryFill = current.filter { it.triggerKind == LlmDaemonTriggerKind.ENTRY_FILL }.distinctBy { it.invocationId }.size,
            stopProximity = current.filter {
                it.triggerKind == LlmDaemonTriggerKind.STOP_PROXIMITY
            }.distinctBy { it.invocationId }.size,
        )
    }
}

private fun LlmLaunchReservationRecord.recoverySortHeartbeatAt(): Instant = heartbeatAt ?: claimedAt ?: reservedAt

private fun LlmLaunchReservationRecord.recoverySortClaimedAt(): Instant = claimedAt ?: reservedAt

private fun LlmLaunchReservationRecord.isAfterRecoveryCursor(scan: LlmExecutionRecoveryScan): Boolean {
    val cursorHeartbeat = scan.afterHeartbeatAt ?: return true
    val cursorClaimed = requireNotNull(scan.afterClaimedAt)
    val cursorInvocationId = requireNotNull(scan.afterInvocationId)
    val sortHeartbeat = recoverySortHeartbeatAt()
    val sortClaimed = recoverySortClaimedAt()

    return when {
        sortHeartbeat != cursorHeartbeat -> sortHeartbeat > cursorHeartbeat
        sortClaimed != cursorClaimed -> sortClaimed > cursorClaimed
        else -> invocationId > cursorInvocationId
    }
}

/**
 * in-memory 起動予約の内部 record。
 *
 * @param invocationId runner と audit に使う起動 ID
 * @param triggerKind 起動理由の種別
 * @param triggerKey cadence 判定に使う trigger 固有 key
 * @param status 予約状態
 * @param reservedAt 予約時刻
 * @param finishedAt 完了時刻
 * @param reason 補助理由
 */
private data class LlmLaunchReservationRecord(
    val invocationId: String,
    val triggerKind: LlmDaemonTriggerKind,
    val triggerKey: String,
    val status: LlmLaunchReservationStatus,
    val reservedAt: Instant,
    val finishedAt: Instant?,
    val reason: String?,
    val claimState: LlmExecutionClaimState?,
    val claimantToken: String?,
    val claimedAt: Instant?,
    val heartbeatAt: Instant?,
) {
    fun toActive(): LlmActiveLaunchReservation =
        LlmActiveLaunchReservation(invocationId, triggerKind, triggerKey, reservedAt)

    /**
     * stale 判定込みで RUNNING か返す。
     */
    fun isFreshRunning(activeSince: Instant): Boolean {
        val activeStatus = status == LlmLaunchReservationStatus.RUNNING
        val freshEnough = claimState == LlmExecutionClaimState.CLAIMED || !reservedAt.isBefore(activeSince)

        return activeStatus && freshEnough
    }

    /**
     * stale 判定込みで trading RUNNING か返す。
     */
    fun isFreshTradingRunning(activeSince: Instant): Boolean {
        return triggerKind != LlmDaemonTriggerKind.REFLECTION && isFreshRunning(activeSince)
    }

    fun claimRejection(requestTriggerKind: LlmDaemonTriggerKind): LlmExecutionClaimRejectionReason? = when {
        triggerKind != requestTriggerKind -> LlmExecutionClaimRejectionReason.TRIGGER_MISMATCH
        status != LlmLaunchReservationStatus.RUNNING -> LlmExecutionClaimRejectionReason.TERMINAL
        claimState == null -> LlmExecutionClaimRejectionReason.LEGACY_UNCLAIMABLE
        claimState == LlmExecutionClaimState.NOT_REQUIRED -> LlmExecutionClaimRejectionReason.CLAIM_NOT_REQUIRED
        claimState == LlmExecutionClaimState.CLAIMED -> LlmExecutionClaimRejectionReason.ALREADY_CLAIMED
        else -> null
    }

    fun toClaimSnapshot(): LlmExecutionClaimSnapshot = LlmExecutionClaimSnapshot(
        invocationId = invocationId,
        triggerKind = triggerKind,
        status = status,
        claimState = claimState,
        claimantToken = claimantToken,
        claimedAt = claimedAt,
        heartbeatAt = heartbeatAt,
        reservedAt = reservedAt,
    )
}

/** trigger の lifecycle ownership に対応する初期 claim state。 */
fun LlmDaemonTriggerKind.executionClaimState(): LlmExecutionClaimState = when (this) {
    LlmDaemonTriggerKind.REFLECTION,
    LlmDaemonTriggerKind.EVALUATION_REPORT,
    -> LlmExecutionClaimState.NOT_REQUIRED
    LlmDaemonTriggerKind.FLAT_HEARTBEAT,
    LlmDaemonTriggerKind.ECONOMIC_EVENT,
    LlmDaemonTriggerKind.PRICE_MOVE,
    LlmDaemonTriggerKind.STOP_PROXIMITY,
    LlmDaemonTriggerKind.ENTRY_FILL,
    LlmDaemonTriggerKind.HOLDING_DENSE_CHECK,
    LlmDaemonTriggerKind.MANUAL,
    -> LlmExecutionClaimState.AVAILABLE
}

/**
 * reflection が開始前に残す 1 時間 LLM 起動 headroom。
 */
const val REFLECTION_MIN_REMAINING_HOURLY_INVOCATIONS = 1

/**
 * reflection が開始前に残す 24 時間 LLM 起動 headroom。
 */
const val REFLECTION_MIN_REMAINING_DAILY_INVOCATIONS = 4

internal fun launchBudgetRejection(
    request: LlmLaunchReservationRequest,
    hourlyUsage: LlmLaunchUsage,
    dailyUsage: LlmLaunchUsage,
): LlmLaunchReservationRejectionReason? {
    val hourlyRemaining = request.runnerConfig.maxInvocationsPerHour.toLong() - hourlyUsage.total.toLong()
    val dailyRemaining = request.runnerConfig.maxInvocationsPerDay.toLong() - dailyUsage.total.toLong()
    val reflectionRequest = request.triggerKind == LlmDaemonTriggerKind.REFLECTION
    val evaluationRequest = request.triggerKind == LlmDaemonTriggerKind.EVALUATION_REPORT
    val hourlyExceeded = hourlyRemaining <= 0
    val dailyExceeded = dailyRemaining <= 0

    if (hourlyExceeded) return LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR
    if (dailyExceeded) return LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY

    reserveRejection(request, hourlyUsage, hourly = true)?.let { return it }
    reserveRejection(request, dailyUsage, hourly = false)?.let { return it }

    if (reflectionRequest && hourlyRemaining <= REFLECTION_MIN_REMAINING_HOURLY_INVOCATIONS) {
        return LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_HOURLY_HEADROOM
    }
    if (reflectionRequest && dailyRemaining <= REFLECTION_MIN_REMAINING_DAILY_INVOCATIONS) {
        return LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_DAILY_HEADROOM
    }
    if (evaluationRequest && hourlyRemaining <= REFLECTION_MIN_REMAINING_HOURLY_INVOCATIONS) {
        return LlmLaunchReservationRejectionReason.INSUFFICIENT_EVALUATION_HOURLY_HEADROOM
    }
    if (evaluationRequest && dailyRemaining <= REFLECTION_MIN_REMAINING_DAILY_INVOCATIONS) {
        return LlmLaunchReservationRejectionReason.INSUFFICIENT_EVALUATION_DAILY_HEADROOM
    }

    return null
}

/** rolling window 内の total と critical trigger 別 usage。 */
data class LlmLaunchUsage(val total: Int, val entryFill: Int, val stopProximity: Int)

@Suppress("CyclomaticComplexMethod")
private fun reserveRejection(
    request: LlmLaunchReservationRequest,
    usage: LlmLaunchUsage,
    hourly: Boolean,
): LlmLaunchReservationRejectionReason? {
    val config = request.runnerConfig
    val hardCap = (if (hourly) config.maxInvocationsPerHour else config.maxInvocationsPerDay).toLong()
    val entryReserve = (if (hourly) config.entryFillReservePerHour else config.entryFillReservePerDay).toLong()
    val stopReserve = (if (hourly) config.stopProximityReservePerHour else config.stopProximityReservePerDay).toLong()
    val totalUsage = usage.total.toLong()
    val unusedEntry = (entryReserve - usage.entryFill.toLong()).coerceAtLeast(0L)
    val unusedStop = (stopReserve - usage.stopProximity.toLong()).coerceAtLeast(0L)
    val entryLimit = hardCap - unusedEntry - if (request.triggerKind == LlmDaemonTriggerKind.STOP_PROXIMITY) 0L else unusedStop
    val stopLimit = hardCap - unusedStop - if (request.triggerKind == LlmDaemonTriggerKind.ENTRY_FILL) 0L else unusedEntry
    val protectedEntry = unusedEntry > 0L &&
        request.triggerKind != LlmDaemonTriggerKind.ENTRY_FILL &&
        totalUsage >= entryLimit
    val protectedStop = unusedStop > 0L &&
        request.triggerKind != LlmDaemonTriggerKind.STOP_PROXIMITY &&
        totalUsage >= stopLimit

    return when {
        protectedEntry && hourly -> LlmLaunchReservationRejectionReason.ENTRY_FILL_HOURLY_RESERVE
        protectedEntry -> LlmLaunchReservationRejectionReason.ENTRY_FILL_DAILY_RESERVE
        protectedStop && hourly -> LlmLaunchReservationRejectionReason.STOP_PROXIMITY_HOURLY_RESERVE
        protectedStop -> LlmLaunchReservationRejectionReason.STOP_PROXIMITY_DAILY_RESERVE
        else -> null
    }
}
