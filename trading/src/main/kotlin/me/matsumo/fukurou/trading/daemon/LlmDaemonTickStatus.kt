package me.matsumo.fukurou.trading.daemon

import java.time.Instant

/**
 * daemon scheduler が最後に完了した tick の安定 outcome。
 */
enum class LlmDaemonTickOutcome {
    LAUNCHED,
    FAILED,
    SKIPPED,
    INFRASTRUCTURE_SUPPRESSED,
}

/**
 * daemon scheduler が最後に完了した tick。
 *
 * @param completedAt tick 完了時刻
 * @param outcome tick の安定 outcome
 */
data class LlmDaemonTickStatus(
    val completedAt: Instant,
    val outcome: LlmDaemonTickOutcome,
)

/** daemon scheduler の live tick 状態を読む境界。 */
fun interface LlmDaemonTickStatusProvider {
    /** 最後に完了した tick を返す。 */
    fun snapshot(): LlmDaemonTickStatus?
}

/** daemon scheduler と monitoring route が共有する in-process tick 状態。 */
class MutableLlmDaemonTickStatus : LlmDaemonTickStatusProvider {
    @Volatile
    private var currentStatus: LlmDaemonTickStatus? = null

    override fun snapshot(): LlmDaemonTickStatus? = currentStatus

    /** 完了した tick を更新する。 */
    fun record(status: LlmDaemonTickStatus) {
        currentStatus = status
    }
}
