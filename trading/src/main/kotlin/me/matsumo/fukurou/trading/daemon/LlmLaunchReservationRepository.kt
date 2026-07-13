package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
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
    val populationScope: LlmLaunchReservationPopulationScope,
)

/** reservation creationに必須のtyped population provenance。 */
data class LlmLaunchReservationPopulationScope(
    val kind: String,
    val mode: TradingMode,
    val symbol: TradingSymbol?,
    val accountEpochId: String? = null,
    val cohort: String = "CURRENT",
    val executionSemanticsVersion: String? = "PAPER_WS_V1",
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
interface LlmLaunchReservationRepository {
    /**
     * HARD_HALT / 同時起動 / 起動予算を見て、起動予約を原子的に確保する。
     */
    suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome>

    /**
     * 起動予約を完了状態へ更新する。
     */
    suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit>

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
class InMemoryLlmLaunchReservationRepository(
    private val riskStateRepository: RiskStateRepository,
) : LlmLaunchReservationRepository {

    private val mutex = Mutex()
    private val reservations = mutableListOf<LlmLaunchReservationRecord>()

    override suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome> {
        return runCatching {
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
                reservations[index] = currentReservation.copy(
                    status = finish.status,
                    finishedAt = finish.finishedAt,
                    reason = finish.reason,
                )
            }
        }
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

        val hourlyCount = countReservationsSince(request.reservedAt.minus(request.hourlyWindow))
        val dailyCount = countReservationsSince(request.reservedAt.minus(request.dailyWindow))

        launchBudgetRejection(request, hourlyCount, dailyCount)?.let { rejectionReason ->
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
        )

        return LlmLaunchReservationOutcome.Reserved(request.invocationId)
    }

    private fun countReservationsSince(since: Instant): Int {
        return reservations
            .filter { reservation -> !reservation.reservedAt.isBefore(since) }
            .map { reservation -> reservation.invocationId }
            .distinct()
            .size
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
) {
    fun toActive(): LlmActiveLaunchReservation =
        LlmActiveLaunchReservation(invocationId, triggerKind, triggerKey, reservedAt)

    /**
     * stale 判定込みで RUNNING か返す。
     */
    fun isFreshRunning(activeSince: Instant): Boolean {
        val activeStatus = status == LlmLaunchReservationStatus.RUNNING
        val freshEnough = !reservedAt.isBefore(activeSince)

        return activeStatus && freshEnough
    }

    /**
     * stale 判定込みで trading RUNNING か返す。
     */
    fun isFreshTradingRunning(activeSince: Instant): Boolean {
        return triggerKind != LlmDaemonTriggerKind.REFLECTION && isFreshRunning(activeSince)
    }
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
    hourlyCount: Int,
    dailyCount: Int,
): LlmLaunchReservationRejectionReason? {
    val hourlyRemaining = request.runnerConfig.maxInvocationsPerHour - hourlyCount
    val dailyRemaining = request.runnerConfig.maxInvocationsPerDay - dailyCount
    val reflectionRequest = request.triggerKind == LlmDaemonTriggerKind.REFLECTION
    val evaluationRequest = request.triggerKind == LlmDaemonTriggerKind.EVALUATION_REPORT
    val hourlyExceeded = hourlyRemaining <= 0
    val dailyExceeded = dailyRemaining <= 0

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

    if (hourlyExceeded) {
        return LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR
    }
    if (dailyExceeded) {
        return LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY
    }

    return null
}
