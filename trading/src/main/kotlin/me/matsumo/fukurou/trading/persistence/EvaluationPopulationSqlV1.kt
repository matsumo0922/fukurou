package me.matsumo.fukurou.trading.persistence

/** immutable gap fact を同じ transaction clockでintervalへ投影する共通 SQL。 */
internal const val EVALUATION_GAP_INTERVAL_CTE_V1 = """
    query_clock AS MATERIALIZED (
        SELECT transaction_timestamp() AS query_now
    ),
    opened_gap_candidates AS MATERIALIZED (
        SELECT event_id, gap_id, deployment_id, reason, occurred_at
        FROM infrastructure_gap_events
        WHERE boundary = 'OPEN'
        ORDER BY occurred_at, gap_id
        LIMIT 1001
    ),
    gap_population_bound AS MATERIALIZED (
        SELECT 1 / CASE WHEN COUNT(*) > 1000 THEN 0 ELSE 1 END AS verified
        FROM opened_gap_candidates
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
        FROM opened_gap_candidates opened
        CROSS JOIN query_clock
        CROSS JOIN gap_population_bound
        LEFT JOIN infrastructure_gap_events closed
          ON closed.gap_id = opened.gap_id AND closed.boundary = 'CLOSE'
    )
"""

/** millis causal interval と gap の交差をentity rowを増やさず判定する。 */
internal fun evaluationGapExistsSql(causeMillisSql: String, terminalMillisSql: String): String = """
    EXISTS (
        SELECT 1 FROM infrastructure_gap_intervals gap
        WHERE $causeMillisSql < gap.closed_at_ms AND gap.opened_at_ms < $terminalMillisSql
    )
""".trimIndent()
