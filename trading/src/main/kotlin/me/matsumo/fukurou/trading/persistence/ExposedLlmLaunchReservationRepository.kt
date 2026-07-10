package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.daemon.launchBudgetRejection
import me.matsumo.fukurou.trading.risk.RiskHaltState
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * LLM 起動予約を追加する SQL。
 */
private const val INSERT_LLM_LAUNCH_RESERVATION_SQL = """
    INSERT INTO llm_launch_reservations (
        id,
        invocation_id,
        trigger_kind,
        trigger_key,
        status,
        reserved_at,
        finished_at,
        reason
    )
    VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
"""

/**
 * RUNNING の LLM 起動予約数を数える SQL。
 */
private const val COUNT_ACTIVE_LLM_LAUNCH_RESERVATIONS_SQL = """
    SELECT COUNT(*)
    FROM llm_launch_reservations
    WHERE status = ?
        AND reserved_at >= ?
        AND (? OR trigger_kind <> ?)
"""

/**
 * LLM 起動数を reservation 優先、legacy audit fallback で数える SQL。
 */
private const val COUNT_DISTINCT_LLM_LAUNCHES_SINCE_SQL = """
    SELECT COUNT(DISTINCT launch_id)
    FROM (
        SELECT invocation_id AS launch_id
        FROM llm_launch_reservations
        WHERE reserved_at >= ?
        UNION
        SELECT decision_run_id AS launch_id
        FROM command_event_log
        WHERE decision_run_id IS NOT NULL
            AND event_type IN ('RUNNER_PHASE_COMPLETED', 'NO_TRADE_EXIT')
            AND ts >= ?
            AND NOT EXISTS (
                SELECT 1
                FROM llm_launch_reservations
                WHERE invocation_id = command_event_log.decision_run_id
            )
    ) AS launch_ids
"""

/**
 * LLM 起動予約を完了する SQL。
 */
private const val FINISH_LLM_LAUNCH_RESERVATION_SQL = """
    UPDATE llm_launch_reservations
    SET
        status = ?,
        finished_at = ?,
        reason = ?
    WHERE invocation_id = ?
"""

/**
 * trigger key ごとの最終予約時刻を読む SQL。
 */
private const val SELECT_LATEST_LLM_LAUNCH_RESERVED_AT_SQL = """
    SELECT MAX(reserved_at)
    FROM llm_launch_reservations
    WHERE trigger_key = ?
"""

/**
 * trigger key ごとの最後に正常完了した予約時刻を読む SQL。
 */
private const val SELECT_LATEST_FINISHED_LLM_LAUNCH_RESERVED_AT_SQL = """
    SELECT MAX(reserved_at)
    FROM llm_launch_reservations
    WHERE trigger_key = ?
        AND status = ?
"""

/**
 * Exposed/JDBC で LLM daemon scheduler の起動予約を扱う repository。
 *
 * @param database Exposed database
 */
class ExposedLlmLaunchReservationRepository(
    private val database: ExposedDatabase,
) : LlmLaunchReservationRepository {

    override suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    tryReserveInTransaction(request)
                }
            }
        }
    }

    override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    finishInTransaction(finish)
                }
            }
        }
    }

    override suspend fun latestReservedAt(triggerKey: String): Result<Instant?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    latestReservedAtInTransaction(triggerKey)
                }
            }
        }
    }

    override suspend fun latestFinishedReservedAt(triggerKey: String): Result<Instant?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    latestFinishedReservedAtInTransaction(triggerKey)
                }
            }
        }
    }

    override suspend fun hasFreshRunningReservation(activeSince: Instant): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    countFreshTradingReservations(activeSince) > 0
                }
            }
        }
    }
}

private fun JdbcTransaction.tryReserveInTransaction(
    request: LlmLaunchReservationRequest,
): LlmLaunchReservationOutcome {
    val riskState = selectRiskState(forUpdate = true)

    if (riskState.state == RiskHaltState.HARD_HALT) {
        return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.HARD_HALT)
    }

    val activeSince = request.reservedAt.minus(request.activeReservationStaleAfter)
    val activeCount = countBlockingActiveReservations(request, activeSince)

    if (activeCount > 0) {
        return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION)
    }

    val hourlyCount = countDistinctLaunchesSince(request.reservedAt.minus(request.hourlyWindow))
    val dailyCount = countDistinctLaunchesSince(request.reservedAt.minus(request.dailyWindow))

    launchBudgetRejection(request, hourlyCount, dailyCount)?.let { rejectionReason ->
        return LlmLaunchReservationOutcome.Rejected(rejectionReason)
    }

    insertReservation(request)

    return LlmLaunchReservationOutcome.Reserved(request.invocationId)
}

private fun JdbcTransaction.countBlockingActiveReservations(
    request: LlmLaunchReservationRequest,
    activeSince: Instant,
): Int {
    val includeReflection = request.triggerKind == LlmDaemonTriggerKind.REFLECTION

    return countActiveReservations(
        activeSince = activeSince,
        includeReflection = includeReflection,
    )
}

private fun JdbcTransaction.countFreshTradingReservations(activeSince: Instant): Int {
    return countActiveReservations(
        activeSince = activeSince,
        includeReflection = false,
    )
}

private fun JdbcTransaction.countActiveReservations(activeSince: Instant, includeReflection: Boolean): Int {
    return jdbcConnection().prepareStatement(COUNT_ACTIVE_LLM_LAUNCH_RESERVATIONS_SQL).use { statement ->
        statement.setString(1, LlmLaunchReservationStatus.RUNNING.name)
        statement.setLong(2, activeSince.toEpochMilli())
        statement.setBoolean(3, includeReflection)
        statement.setString(4, LlmDaemonTriggerKind.REFLECTION.name)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getInt(1) else 0
        }
    }
}

private fun JdbcTransaction.countDistinctLaunchesSince(since: Instant): Int {
    return jdbcConnection().prepareStatement(COUNT_DISTINCT_LLM_LAUNCHES_SINCE_SQL).use { statement ->
        statement.setLong(1, since.toEpochMilli())
        statement.setLong(2, since.toEpochMilli())
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getInt(1) else 0
        }
    }
}

private fun JdbcTransaction.insertReservation(request: LlmLaunchReservationRequest) {
    jdbcConnection().prepareStatement(INSERT_LLM_LAUNCH_RESERVATION_SQL).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setString(2, request.invocationId)
        statement.setString(3, request.triggerKind.name)
        statement.setString(4, request.triggerKey)
        statement.setString(5, LlmLaunchReservationStatus.RUNNING.name)
        statement.setLong(6, request.reservedAt.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.finishInTransaction(finish: LlmLaunchReservationFinish) {
    val updatedRows = jdbcConnection().prepareStatement(FINISH_LLM_LAUNCH_RESERVATION_SQL).use { statement ->
        statement.setString(1, finish.status.name)
        statement.setLong(2, finish.finishedAt.toEpochMilli())
        statement.setNullableString(3, finish.reason)
        statement.setString(4, finish.invocationId)
        statement.executeUpdate()
    }

    require(updatedRows == 1) {
        "LLM launch reservation was not found. invocationId=${finish.invocationId}"
    }
}

private fun JdbcTransaction.latestReservedAtInTransaction(triggerKey: String): Instant? {
    return jdbcConnection().prepareStatement(SELECT_LATEST_LLM_LAUNCH_RESERVED_AT_SQL).use { statement ->
        statement.setString(1, triggerKey)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                return@use null
            }

            val epochMillis = resultSet.getLong(1)

            if (resultSet.wasNull()) null else Instant.ofEpochMilli(epochMillis)
        }
    }
}

private fun JdbcTransaction.latestFinishedReservedAtInTransaction(triggerKey: String): Instant? {
    return jdbcConnection().prepareStatement(SELECT_LATEST_FINISHED_LLM_LAUNCH_RESERVED_AT_SQL).use { statement ->
        statement.setString(1, triggerKey)
        statement.setString(2, LlmLaunchReservationStatus.FINISHED.name)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                return@use null
            }

            val epochMillis = resultSet.getLong(1)

            if (resultSet.wasNull()) null else Instant.ofEpochMilli(epochMillis)
        }
    }
}
