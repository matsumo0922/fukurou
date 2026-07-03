package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
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
     * 建玉または open order がある場合の密な確認。
     */
    HOLDING_DENSE_CHECK,
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
    ) : LlmLaunchReservationOutcome
}

/**
 * LLM 起動予約を拒否した理由。
 */
enum class LlmLaunchReservationRejectionReason {
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
}

/**
 * daemon scheduler の起動予約 repository。
 *
 * daemon 経路の起動予算と同時起動の正本であり、DB 実装では予約行と runner 完了 audit を合算して数える。
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
     * stale ではない RUNNING 予約が存在するか返す。
     */
    suspend fun hasFreshRunningReservation(activeSince: Instant): Result<Boolean>
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

    override suspend fun hasFreshRunningReservation(activeSince: Instant): Result<Boolean> {
        return runCatching {
            mutex.withLock {
                reservations.any { reservation ->
                    val activeStatus = reservation.status == LlmLaunchReservationStatus.RUNNING
                    val freshEnough = !reservation.reservedAt.isBefore(activeSince)

                    activeStatus && freshEnough
                }
            }
        }
    }

    private fun activeReservation(request: LlmLaunchReservationRequest): LlmLaunchReservationRecord? {
        val activeSince = request.reservedAt.minus(request.activeReservationStaleAfter)

        return reservations.firstOrNull { reservation ->
            val activeStatus = reservation.status == LlmLaunchReservationStatus.RUNNING
            val freshEnough = !reservation.reservedAt.isBefore(activeSince)

            activeStatus && freshEnough
        }
    }

    private suspend fun tryReserveLocked(request: LlmLaunchReservationRequest): LlmLaunchReservationOutcome {
        val riskState = riskStateRepository.current().getOrThrow()

        if (riskState.hardHalt) {
            return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.HARD_HALT)
        }

        activeReservation(request)?.let {
            return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION)
        }

        val hourlyCount = countReservationsSince(request.reservedAt.minus(request.hourlyWindow))
        val dailyCount = countReservationsSince(request.reservedAt.minus(request.dailyWindow))
        val hourlyExceeded = hourlyCount >= request.runnerConfig.maxInvocationsPerHour
        val dailyExceeded = dailyCount >= request.runnerConfig.maxInvocationsPerDay

        if (hourlyExceeded) {
            return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR)
        }
        if (dailyExceeded) {
            return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY)
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
)
