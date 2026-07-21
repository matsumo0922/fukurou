package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.persistence.EVALUATION_GAP_INTERVAL_CTE_V1
import me.matsumo.fukurou.trading.persistence.jdbcConnection
import me.matsumo.fukurou.trading.persistence.setEvaluationRequestBounds
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant

/** millis 半開区間で表した gap 1 件。`closedAtMs` は未回復なら snapshot 時刻。 */
data class ReplayGapInterval(
    val openedAtMs: Long,
    val closedAtMs: Long,
)

/**
 * 2 系統の gap を別々に投影した結果。対象の生存区間と交差する gap を UNKNOWN 判定に使う。
 *
 * `infrastructure_gap_events` は既存 `EVALUATION_GAP_INTERVAL_CTE_V1` で投影し、`market_data_gaps` は
 * `started_at`/`recovered_at` として別途投影する。
 */
class ReplayGapProjection(
    private val infrastructureGaps: List<ReplayGapInterval>,
    private val marketDataGaps: List<ReplayGapInterval>,
) {
    /** 生存区間 `[causeMs, terminalMs)` と交差する gap 系統を返す。交差しなければ null。 */
    fun intersectingReason(causeMs: Long, terminalMs: Long): ReplayUnknownReason? {
        if (intersects(infrastructureGaps, causeMs, terminalMs)) return ReplayUnknownReason.INFRASTRUCTURE_GAP
        if (intersects(marketDataGaps, causeMs, terminalMs)) return ReplayUnknownReason.MARKET_DATA_GAP

        return null
    }

    private fun intersects(
        gaps: List<ReplayGapInterval>,
        causeMs: Long,
        terminalMs: Long,
    ): Boolean {
        return gaps.any { gap -> causeMs < gap.closedAtMs && gap.openedAtMs < terminalMs }
    }

    companion object {
        private const val INFRASTRUCTURE_GAP_INTERVALS_SQL =
            "WITH $EVALUATION_GAP_INTERVAL_CTE_V1 " +
                "SELECT opened_at_ms, closed_at_ms FROM infrastructure_gap_intervals"

        private const val MARKET_DATA_GAP_INTERVALS_SQL = """
            SELECT
                started_at AS opened_at_ms,
                COALESCE(recovered_at, ?) AS closed_at_ms
            FROM market_data_gaps
            WHERE started_at < ?
                AND COALESCE(recovered_at, ?) > ?
        """

        /**
         * snapshot 内で両 gap 系統を投影する。infrastructure gap の件数上限超過は部分結果を出さず run 全体を失敗させる。
         */
        fun project(
            transaction: JdbcTransaction,
            window: ReplayWindow,
            snapshotAtMs: Long,
        ): ReplayGapProjection {
            transaction.setEvaluationRequestBounds(
                EvaluationPeriod(
                    from = Instant.ofEpochMilli(window.fromMs),
                    toExclusive = Instant.ofEpochMilli(window.toExclusiveMs),
                ),
            )

            val infrastructureGaps = projectInfrastructureGaps(transaction)
            val marketDataGaps = projectMarketDataGaps(transaction, window, snapshotAtMs)

            return ReplayGapProjection(infrastructureGaps, marketDataGaps)
        }

        private fun projectInfrastructureGaps(transaction: JdbcTransaction): List<ReplayGapInterval> {
            return runCatching {
                transaction.jdbcConnection().prepareStatement(INFRASTRUCTURE_GAP_INTERVALS_SQL).use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(ReplayGapInterval(rows.getLong("opened_at_ms"), rows.getLong("closed_at_ms")))
                            }
                        }
                    }
                }
            }.getOrElse { failure ->
                throw ReplayRunFailedException(
                    "infrastructure gap projection failed; the run is failed without partial results.",
                    failure,
                )
            }
        }

        private fun projectMarketDataGaps(
            transaction: JdbcTransaction,
            window: ReplayWindow,
            snapshotAtMs: Long,
        ): List<ReplayGapInterval> {
            return transaction.jdbcConnection().prepareStatement(MARKET_DATA_GAP_INTERVALS_SQL).use { statement ->
                statement.setLong(1, snapshotAtMs)
                statement.setLong(2, window.toExclusiveMs)
                statement.setLong(3, snapshotAtMs)
                statement.setLong(4, window.fromMs)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(ReplayGapInterval(rows.getLong("opened_at_ms"), rows.getLong("closed_at_ms")))
                        }
                    }
                }
            }
        }
    }
}
