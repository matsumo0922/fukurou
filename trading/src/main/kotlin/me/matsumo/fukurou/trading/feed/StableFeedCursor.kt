package me.matsumo.fukurou.trading.feed

import java.time.Instant

/**
 * 同一 timestamp の行でも欠落しない feed paging cursor。
 *
 * @param occurredAt cursor 境界の発生時刻
 * @param includesSameTimestamp 境界と同じ timestamp の行を候補に含めるか
 * @param afterId 同じ timestamp でこの ID より後ろの行だけを返す。null の場合は同じ timestamp の全行を含める
 */
data class StableFeedCursor(
    val occurredAt: Instant,
    val includesSameTimestamp: Boolean,
    val afterId: String?,
) {
    fun accepts(candidateOccurredAt: Instant, candidateId: String): Boolean {
        if (candidateOccurredAt.isBefore(occurredAt)) {
            return true
        }

        if (!includesSameTimestamp) {
            return false
        }

        val hasSameTimestamp = candidateOccurredAt == occurredAt

        if (!hasSameTimestamp) {
            return false
        }

        val cursorId = afterId ?: return true

        return candidateId > cursorId
    }
}
