package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.domain.EvaluationCohort
import me.matsumo.fukurou.trading.persistence.jdbcConnection
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.ResultSet
import java.util.UUID

/**
 * 対象 closed long position を選択し、fill-weighted の average entry と protective stop、台帳の最安値を読む query。
 *
 * cohort は既存 evaluation と同じ lineage 規則で position 粒度へ導出する。fill-weighted 値は BUY execution の
 * size 加重で、close 時に NULL 化される `positions.current_stop_loss_jpy` は使わない。
 */
object TailReplayQuery {

    private const val POSITION_PREDICATE = """
        p.mode = 'PAPER'
            AND p.status = 'CLOSED'
            AND p.closed_at >= ?
            AND p.closed_at < ?
    """

    private const val COUNT_SQL = "SELECT COUNT(*) AS target_count FROM positions p WHERE $POSITION_PREDICATE"

    private const val SELECT_SQL = """
        WITH target_positions AS (
            SELECT
                p.id,
                p.opened_at,
                p.closed_at,
                p.pyramid_add_count,
                p.highest_price_since_entry_jpy,
                p.lowest_price_since_entry_jpy
            FROM positions p
            WHERE $POSITION_PREDICATE
        ),
        entry_fills AS (
            SELECT
                e.position_id,
                SUM(e.size_btc) AS entry_size_btc,
                SUM(e.price_jpy * e.size_btc) / NULLIF(SUM(e.size_btc), 0) AS average_entry_price_jpy,
                SUM(o.protective_stop_price_jpy * e.size_btc) / NULLIF(SUM(e.size_btc), 0) AS protective_stop_price_jpy
            FROM executions e
            JOIN orders o ON o.id = e.order_id
            JOIN target_positions p ON p.id = e.position_id
            WHERE e.side = 'BUY'
            GROUP BY e.position_id
        ),
        sell_counts AS (
            SELECT e.position_id, COUNT(*) AS sell_count
            FROM executions e
            JOIN target_positions p ON p.id = e.position_id
            WHERE e.side = 'SELL'
            GROUP BY e.position_id
        ),
        execution_lineage AS (
            SELECT
                e.position_id,
                CASE
                    WHEN COUNT(*) FILTER (WHERE
                        COALESCE(e.execution_semantics_version, '') NOT IN ('', 'PAPER_WS_V1') OR
                        COALESCE(o.execution_semantics_version, '') NOT IN ('', 'PAPER_WS_V1')
                    ) > 0 THEN 'UNSUPPORTED_EXECUTION_SEMANTICS'
                    WHEN COUNT(*) FILTER (WHERE
                        e.execution_semantics_version IS NULL OR
                        o.id IS NULL OR o.execution_semantics_version IS NULL OR
                        e.account_epoch_id IS NULL OR o.account_epoch_id IS NULL
                    ) = 0
                        AND COUNT(DISTINCT e.account_epoch_id) = 1
                        AND COUNT(DISTINCT o.account_epoch_id) = 1
                        AND MIN(e.account_epoch_id::text) = MIN(o.account_epoch_id::text)
                        AND BOOL_AND(e.execution_semantics_version = 'PAPER_WS_V1')
                        AND BOOL_AND(o.execution_semantics_version = 'PAPER_WS_V1') THEN 'CURRENT'
                    ELSE 'LEGACY_PRE_WS'
                END AS cohort
            FROM executions e
            LEFT JOIN orders o ON o.id = e.order_id
            JOIN target_positions p ON p.id = e.position_id
            GROUP BY e.position_id
        )
        SELECT
            p.id AS position_id,
            p.opened_at,
            p.closed_at,
            p.pyramid_add_count,
            p.highest_price_since_entry_jpy,
            p.lowest_price_since_entry_jpy,
            ef.entry_size_btc,
            ef.average_entry_price_jpy,
            ef.protective_stop_price_jpy,
            COALESCE(sc.sell_count, 0) AS sell_count,
            el.cohort,
            EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = p.id::text
            ) AS is_excluded
        FROM target_positions p
        LEFT JOIN entry_fills ef ON ef.position_id = p.id
        LEFT JOIN sell_counts sc ON sc.position_id = p.id
        LEFT JOIN execution_lineage el ON el.position_id = p.id
        ORDER BY p.closed_at ASC, p.id ASC
    """

    /** 指定期間の対象件数を数える。上限判定に使う。 */
    fun countTargets(transaction: JdbcTransaction, window: ReplayWindow): Long {
        return transaction.jdbcConnection().prepareStatement(COUNT_SQL).use { statement ->
            statement.setLong(1, window.fromMs)
            statement.setLong(2, window.toExclusiveMs)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "tail target count query returned no rows." }
                rows.getLong("target_count")
            }
        }
    }

    /** 指定期間の対象 position を読み込む。 */
    fun selectTargets(transaction: JdbcTransaction, window: ReplayWindow): List<TailPositionRow> {
        return transaction.jdbcConnection().prepareStatement(SELECT_SQL).use { statement ->
            statement.setLong(1, window.fromMs)
            statement.setLong(2, window.toExclusiveMs)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(rows.toPositionRow())
                    }
                }
            }
        }
    }

    private fun ResultSet.toPositionRow(): TailPositionRow {
        return TailPositionRow(
            positionId = getObject("position_id", UUID::class.java),
            openedAtMs = getLong("opened_at"),
            closedAtMs = getLong("closed_at"),
            cohort = cohortOf(getString("cohort")),
            entrySizeBtc = getBigDecimal("entry_size_btc"),
            averageEntryPriceJpy = getBigDecimal("average_entry_price_jpy"),
            fillWeightedStopPriceJpy = getBigDecimal("protective_stop_price_jpy"),
            highestPriceSinceEntryJpy = getBigDecimal("highest_price_since_entry_jpy"),
            lowestPriceSinceEntryJpy = getBigDecimal("lowest_price_since_entry_jpy"),
            pyramidAddCount = getInt("pyramid_add_count"),
            sellExecutionCount = getInt("sell_count"),
            isEvaluationExcluded = getBoolean("is_excluded"),
        )
    }

    /** lineage cohort 文字列を enum へ写像する。解決できない lineage は CURRENT へ混ぜず LEGACY_PRE_WS とする。 */
    private fun cohortOf(raw: String?): EvaluationCohort {
        return when (raw) {
            "CURRENT" -> EvaluationCohort.CURRENT
            "UNSUPPORTED_EXECUTION_SEMANTICS" -> EvaluationCohort.UNSUPPORTED_EXECUTION_SEMANTICS
            else -> EvaluationCohort.LEGACY_PRE_WS
        }
    }
}
