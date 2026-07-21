package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.persistence.jdbcConnection
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.ResultSet
import java.util.UUID

/**
 * 対象 resting LIMIT entry order を選択し、`trade_plans` の time stop と entry execution を join して読む query。
 *
 * entry execution は最早の BUY execution 1 件だけを LATERAL で取り、join による行増殖を避ける。
 */
object TtlReplayQuery {

    private const val ORDER_PREDICATE = """
        o.side = 'BUY'
            AND o.order_type = 'LIMIT'
            AND o.expires_at IS NOT NULL
            AND o.created_at >= ?
            AND o.created_at < ?
    """

    private const val COUNT_SQL = "SELECT COUNT(*) AS target_count FROM orders o WHERE $ORDER_PREDICATE"

    private const val SELECT_SQL = """
        SELECT
            o.id AS order_id,
            o.created_at,
            o.expires_at,
            o.effective_ttl_seconds,
            o.expired_at,
            o.canceled_at,
            o.cancel_reason,
            o.updated_at,
            o.limit_price_jpy,
            o.size_btc,
            o.market_data_session_id,
            o.market_eligible_after_sequence,
            o.execution_semantics_version AS order_semantics,
            tp.id AS plan_id,
            tp.time_stop_at,
            e.executed_at,
            e.source_session_id,
            e.source_sequence,
            e.price_jpy AS exec_price,
            e.fee_jpy AS exec_fee,
            e.execution_semantics_version AS exec_semantics
        FROM orders o
        LEFT JOIN trade_intents ti ON ti.id = o.intent_id
        LEFT JOIN trade_plans tp ON tp.id = ti.trade_plan_id
        LEFT JOIN LATERAL (
            SELECT
                ex.executed_at,
                ex.source_session_id,
                ex.source_sequence,
                ex.price_jpy,
                ex.fee_jpy,
                ex.execution_semantics_version
            FROM executions ex
            WHERE ex.order_id = o.id AND ex.side = 'BUY'
            ORDER BY ex.executed_at ASC, ex.id ASC
            LIMIT 1
        ) e ON TRUE
        WHERE $ORDER_PREDICATE
        ORDER BY o.created_at ASC, o.id ASC
    """

    /** 指定期間の対象件数を数える。上限判定に使う。 */
    fun countTargets(transaction: JdbcTransaction, window: ReplayWindow): Long {
        return transaction.jdbcConnection().prepareStatement(COUNT_SQL).use { statement ->
            statement.setLong(1, window.fromMs)
            statement.setLong(2, window.toExclusiveMs)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "target count query returned no rows." }
                rows.getLong("target_count")
            }
        }
    }

    /** 指定期間の対象 order を読み込む。 */
    fun selectTargets(transaction: JdbcTransaction, window: ReplayWindow): List<TtlReplayOrderRow> {
        return transaction.jdbcConnection().prepareStatement(SELECT_SQL).use { statement ->
            statement.setLong(1, window.fromMs)
            statement.setLong(2, window.toExclusiveMs)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(rows.toOrderRow())
                    }
                }
            }
        }
    }

    private fun ResultSet.toOrderRow(): TtlReplayOrderRow {
        return TtlReplayOrderRow(
            orderId = getObject("order_id", UUID::class.java),
            createdAtMs = getLong("created_at"),
            expiresAtMs = getLong("expires_at"),
            effectiveTtlSeconds = nullableLong("effective_ttl_seconds"),
            expiredAtMs = nullableLong("expired_at"),
            canceledAtMs = nullableLong("canceled_at"),
            cancelReason = getString("cancel_reason"),
            updatedAtMs = getLong("updated_at"),
            limitPriceJpy = getBigDecimal("limit_price_jpy"),
            sizeBtc = getBigDecimal("size_btc"),
            marketDataSessionId = getObject("market_data_session_id", UUID::class.java),
            marketEligibleAfterSequence = nullableLong("market_eligible_after_sequence"),
            orderSemanticsVersion = getString("order_semantics"),
            hasTradePlan = getObject("plan_id", UUID::class.java) != null,
            timeStopAtMs = nullableLong("time_stop_at"),
            executedAtMs = nullableLong("executed_at"),
            executionSourceSessionId = getObject("source_session_id", UUID::class.java),
            executionSourceSequence = nullableLong("source_sequence"),
            executionPriceJpy = getBigDecimal("exec_price"),
            executionFeeJpy = getBigDecimal("exec_fee"),
            executionSemanticsVersion = getString("exec_semantics"),
        )
    }

    private fun ResultSet.nullableLong(column: String): Long? {
        val value = getLong(column)

        return if (wasNull()) null else value
    }
}
