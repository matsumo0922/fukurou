package me.matsumo.fukurou.trading.persistence

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant

/**
 * LLM 起動数を reservation 優先、legacy audit fallback で数える SQL。
 */
private const val COUNT_DISTINCT_LLM_LAUNCHES_SINCE_SQL = """
    SELECT COUNT(DISTINCT launch_id)
    FROM (
        SELECT reservations.invocation_id AS launch_id
        FROM llm_launch_reservations AS reservations
        WHERE reservations.reserved_at >= ?
            AND (? IS NULL OR reservations.invocation_id <> ?)
        UNION
        SELECT events.decision_run_id AS launch_id
        FROM command_event_log AS events
        WHERE events.decision_run_id IS NOT NULL
            AND events.event_type IN ('RUNNER_PHASE_COMPLETED', 'NO_TRADE_EXIT')
            AND events.ts >= ?
            AND (? IS NULL OR events.decision_run_id <> ?)
            AND NOT EXISTS (
                SELECT 1
                FROM llm_launch_reservations AS reservations
                WHERE reservations.invocation_id = events.decision_run_id
            )
    ) AS launch_ids
"""

/**
 * reservation を正本とし、reservation のない legacy run だけ audit 時刻へ fallback して起動数を返す。
 *
 * @param since 集計開始時刻
 * @param excludedInvocationId 集計から除外する invocation ID
 */
internal fun JdbcTransaction.countDistinctLlmLaunchesSince(since: Instant, excludedInvocationId: String? = null): Int {
    return jdbcConnection().prepareStatement(COUNT_DISTINCT_LLM_LAUNCHES_SINCE_SQL).use { statement ->
        statement.setLong(1, since.toEpochMilli())
        statement.setNullableString(2, excludedInvocationId)
        statement.setNullableString(3, excludedInvocationId)
        statement.setLong(4, since.toEpochMilli())
        statement.setNullableString(5, excludedInvocationId)
        statement.setNullableString(6, excludedInvocationId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getInt(1) else 0
        }
    }
}
