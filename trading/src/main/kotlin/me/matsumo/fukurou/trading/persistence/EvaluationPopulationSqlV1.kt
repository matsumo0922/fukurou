package me.matsumo.fukurou.trading.persistence

/** immutable gap fact を同じ transaction clockでintervalへ投影する共通 SQL。 */
internal const val EVALUATION_GAP_INTERVAL_CTE_V1 = """
    query_clock AS MATERIALIZED (
        SELECT transaction_timestamp() AS query_now
    ),
    infrastructure_gap_intervals AS MATERIALIZED (
        SELECT
            opened.gap_id,
            opened.deployment_id,
            opened.reason,
            opened.occurred_at AS opened_at,
            closed.occurred_at AS closed_at,
            (extract(epoch FROM opened.occurred_at) * 1000)::bigint AS opened_at_ms,
            (extract(epoch FROM COALESCE(closed.occurred_at, query_clock.query_now)) * 1000)::bigint AS closed_at_ms
        FROM infrastructure_gap_events opened
        CROSS JOIN query_clock
        LEFT JOIN infrastructure_gap_events closed
          ON closed.gap_id = opened.gap_id AND closed.boundary = 'CLOSE'
        WHERE opened.boundary = 'OPEN'
    )
"""

/** millis causal interval と gap の交差をentity rowを増やさず判定する。 */
internal fun evaluationGapExistsSql(causeMillisSql: String, terminalMillisSql: String): String = """
    EXISTS (
        SELECT 1 FROM infrastructure_gap_intervals gap
        WHERE $causeMillisSql < gap.closed_at_ms AND gap.opened_at_ms < $terminalMillisSql
    )
""".trimIndent()
