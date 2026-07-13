package me.matsumo.fukurou.trading.daemon

import java.time.Duration
import java.util.concurrent.TimeUnit

/** monotonic clockでrecovery page全体の終了時刻を表す。 */
data class LlmExecutionRecoveryDeadline(
    val expiresAtNanos: Long,
) {
    /** 現在時刻からdeadlineまでの残り時間を切り捨てmillisecondで返す。 */
    fun remainingMillis(nanoTime: () -> Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(expiresAtNanos - nanoTime()).coerceAtLeast(0L)
    }

    /** 新しい処理を始めるためのreserveが残っていることを検証する。 */
    fun requireStartReserve(nanoTime: () -> Long, reserveMillis: Long): Long {
        val remainingMillis = remainingMillis(nanoTime)
        if (remainingMillis < reserveMillis) {
            throw LlmExecutionRecoveryDeadlineExceededException(remainingMillis, reserveMillis)
        }

        return remainingMillis
    }

    companion object {
        /** 現在のmonotonic clockから指定時間後のdeadlineを作る。 */
        fun start(timeout: Duration, nanoTime: () -> Long): LlmExecutionRecoveryDeadline {
            require(!timeout.isNegative && !timeout.isZero) { "recovery timeout must be positive." }

            return LlmExecutionRecoveryDeadline(nanoTime() + timeout.toNanos())
        }
    }
}

/** recovery deadline内で新しい処理を安全に開始できないことを表す。 */
class LlmExecutionRecoveryDeadlineExceededException(
    remainingMillis: Long,
    reserveMillis: Long,
) : IllegalStateException(
    "LLM execution recovery deadline is exhausted. remainingMillis=$remainingMillis reserveMillis=$reserveMillis",
)
