package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.daemon.LlmLaunchUsage
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant

/**
 * LLM 起動数を reservation 優先、legacy audit fallback で数える SQL。
 */
private const val AGGREGATE_LLM_LAUNCH_USAGE_WINDOWS_SQL = """
    SELECT
        COUNT(DISTINCT launch_id) FILTER (WHERE launched_at >= ?) AS hour_total,
        COUNT(DISTINCT launch_id) FILTER (WHERE launched_at >= ? AND trigger_kind = 'ENTRY_FILL') AS hour_entry_fill,
        COUNT(DISTINCT launch_id) FILTER (WHERE launched_at >= ? AND trigger_kind = 'STOP_PROXIMITY') AS hour_stop_proximity,
        COUNT(DISTINCT launch_id) AS day_total,
        COUNT(DISTINCT launch_id) FILTER (WHERE trigger_kind = 'ENTRY_FILL') AS day_entry_fill,
        COUNT(DISTINCT launch_id) FILTER (WHERE trigger_kind = 'STOP_PROXIMITY') AS day_stop_proximity
    FROM (
        SELECT reservations.invocation_id AS launch_id, reservations.trigger_kind AS trigger_kind,
            reservations.reserved_at AS launched_at
        FROM llm_launch_reservations AS reservations
        WHERE reservations.reserved_at >= ?
            AND (? IS NULL OR reservations.invocation_id <> ?)
        UNION
        SELECT events.decision_run_id AS launch_id, NULL AS trigger_kind, events.ts AS launched_at
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
    return aggregateLlmLaunchUsageWindows(since, since, excludedInvocationId).hourly.total
}

/** hour/day の total と critical trigger 別 usage。 */
internal data class LlmLaunchUsageWindows(val hourly: LlmLaunchUsage, val daily: LlmLaunchUsage)

/** rolling hour/day usage を1 aggregate queryで返す。 */
internal fun JdbcTransaction.aggregateLlmLaunchUsageWindows(
    hourlySince: Instant,
    dailySince: Instant,
    excludedInvocationId: String? = null,
): LlmLaunchUsageWindows {
    return jdbcConnection().prepareStatement(AGGREGATE_LLM_LAUNCH_USAGE_WINDOWS_SQL).use { statement ->
        statement.setLong(1, hourlySince.toEpochMilli())
        statement.setLong(2, hourlySince.toEpochMilli())
        statement.setLong(3, hourlySince.toEpochMilli())
        statement.setLong(4, dailySince.toEpochMilli())
        statement.setNullableString(5, excludedInvocationId)
        statement.setNullableString(6, excludedInvocationId)
        statement.setLong(7, dailySince.toEpochMilli())
        statement.setNullableString(8, excludedInvocationId)
        statement.setNullableString(9, excludedInvocationId)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "LLM launch aggregate query returned no row." }
            LlmLaunchUsageWindows(
                hourly = LlmLaunchUsage(
                    total = resultSet.getInt("hour_total"),
                    entryFill = resultSet.getInt("hour_entry_fill"),
                    stopProximity = resultSet.getInt("hour_stop_proximity"),
                ),
                daily = LlmLaunchUsage(
                    total = resultSet.getInt("day_total"),
                    entryFill = resultSet.getInt("day_entry_fill"),
                    stopProximity = resultSet.getInt("day_stop_proximity"),
                ),
            )
        }
    }
}
