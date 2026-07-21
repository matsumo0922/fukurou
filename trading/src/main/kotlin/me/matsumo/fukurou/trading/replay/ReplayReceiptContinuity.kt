package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.persistence.jdbcConnection
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.util.UUID

/**
 * 対象 order の receipt 生存区間を `(session_id, source_sequence)` で駆動して読んだ結果。
 *
 * `admission_ordinal` の欠番は欠落と解釈せず、session 内 source sequence の連続性だけで欠落を判定する。
 */
data class ReplayReceiptWindowCheck(
    val observedCount: Int,
    val expectedCount: Long,
    val hasSequenceGap: Boolean,
    val anchorBeforeEligibility: Boolean,
)

/**
 * receipt を indexed な `(session_id, source_sequence)` 範囲で読み、欠落と eligibility 境界を検査する reader。
 *
 * 全 receipt の無索引 time scan を発行しない。
 */
object ReplayReceiptContinuity {

    private const val RECEIPT_SEQUENCE_RANGE_SQL = """
        SELECT COUNT(*) AS observed_count
        FROM paper_market_event_receipts
        WHERE session_id = ?
            AND source_sequence > ?
            AND source_sequence <= ?
    """

    /**
     * `afterSequence` (排他) から `anchorSequence` (包含) までの source sequence が連続するかを検査する。
     *
     * @param afterSequence eligibility 境界 sequence。この値以下の receipt を約定 anchor に選ばない
     * @param anchorSequence 約定を発火させた receipt の source sequence
     */
    fun check(
        transaction: JdbcTransaction,
        sessionId: UUID,
        afterSequence: Long,
        anchorSequence: Long,
    ): ReplayReceiptWindowCheck {
        val anchorBeforeEligibility = anchorSequence <= afterSequence
        if (anchorBeforeEligibility) {
            return ReplayReceiptWindowCheck(
                observedCount = 0,
                expectedCount = 0,
                hasSequenceGap = false,
                anchorBeforeEligibility = true,
            )
        }

        val expectedCount = anchorSequence - afterSequence
        val observedCount = countReceiptsInRange(transaction, sessionId, afterSequence, anchorSequence)

        return ReplayReceiptWindowCheck(
            observedCount = observedCount,
            expectedCount = expectedCount,
            hasSequenceGap = observedCount.toLong() < expectedCount,
            anchorBeforeEligibility = false,
        )
    }

    private fun countReceiptsInRange(
        transaction: JdbcTransaction,
        sessionId: UUID,
        afterSequence: Long,
        anchorSequence: Long,
    ): Int {
        return transaction.jdbcConnection().prepareStatement(RECEIPT_SEQUENCE_RANGE_SQL).use { statement ->
            statement.setObject(1, sessionId)
            statement.setLong(2, afterSequence)
            statement.setLong(3, anchorSequence)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "receipt range count returned no rows." }
                rows.getInt("observed_count")
            }
        }
    }
}
