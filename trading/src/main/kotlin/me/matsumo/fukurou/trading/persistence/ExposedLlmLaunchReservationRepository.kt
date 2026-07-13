@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.daemon.LlmActiveLaunchReservation
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimOutcome
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimRequest
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimSnapshot
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.daemon.executionClaimState
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
        , execution_claim_state
    )
    VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)
"""

/**
 * RUNNING の LLM 起動予約数を数える SQL。
 */
private const val SELECT_ACTIVE_LLM_LAUNCH_RESERVATION_SQL = """
    SELECT invocation_id, trigger_kind, trigger_key, reserved_at
    FROM llm_launch_reservations
    WHERE status = ?
        AND reserved_at >= ?
        AND (? OR trigger_kind <> ?)
    ORDER BY reserved_at ASC, invocation_id ASC
    LIMIT 1
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
        AND status = 'RUNNING'
        AND (? IS NULL OR execution_claim_token = ?)
        AND (? IS NULL OR execution_claim_heartbeat_at = ?)
"""

/** AVAILABLE reservation を一度だけ CLAIMED へ遷移させる SQL。 */
private const val CLAIM_LLM_LAUNCH_RESERVATION_SQL = """
    UPDATE llm_launch_reservations
    SET execution_claim_state = 'CLAIMED',
        execution_claim_token = ?,
        execution_claimed_at = ?,
        execution_claim_heartbeat_at = ?
    WHERE invocation_id = ?
        AND trigger_kind = ?
        AND status = 'RUNNING'
        AND execution_claim_state = 'AVAILABLE'
    RETURNING execution_claimed_at
"""

/** claim rejection / reconciliation 用 snapshot SQL。 */
private const val SELECT_LLM_EXECUTION_CLAIM_SQL = """
    SELECT invocation_id, trigger_kind, status, execution_claim_state, execution_claim_token,
        execution_claimed_at, execution_claim_heartbeat_at
    FROM llm_launch_reservations
    WHERE invocation_id = ?
"""

/** live claimant の heartbeat を更新する SQL。 */
private const val HEARTBEAT_LLM_EXECUTION_CLAIM_SQL = """
    UPDATE llm_launch_reservations
    SET execution_claim_heartbeat_at = ?
    WHERE invocation_id = ?
        AND status = 'RUNNING'
        AND execution_claim_state = 'CLAIMED'
        AND execution_claim_token = ?
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
private const val SELECT_LLM_LAUNCH_TRIGGER_KIND_SQL = """
    SELECT trigger_kind FROM llm_launch_reservations WHERE invocation_id = ?
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
                check(LlmExecutionAdmissionHealth.isHealthy()) { "LLM execution admission is fail-closed." }
                exposedTransaction(database) {
                    tryReserveLlmLaunchInTransaction(request)
                }
            }
        }
    }

    override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    finishLlmLaunchInTransaction(finish)
                }
            }
        }
    }

    override suspend fun claimForExecution(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
        return withContext(Dispatchers.IO) {
            try {
                exposedTransaction(database) { claimLlmLaunchInTransaction(request) }
            } catch (throwable: Throwable) {
                LlmExecutionClaimOutcome.OutcomeUnknown(throwable)
            }
        }
    }

    override suspend fun findExecutionClaim(invocationId: String): Result<LlmExecutionClaimSnapshot?> {
        return withContext(Dispatchers.IO) {
            runCatching { exposedTransaction(database) { selectExecutionClaim(invocationId) } }
        }
    }

    override suspend fun heartbeatExecutionClaim(
        invocationId: String,
        claimantToken: String,
        heartbeatAt: Instant,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            exposedTransaction(database) {
                jdbcConnection().prepareStatement(HEARTBEAT_LLM_EXECUTION_CLAIM_SQL).use { statement ->
                    statement.setLong(1, heartbeatAt.toEpochMilli())
                    statement.setString(2, invocationId)
                    statement.setString(3, claimantToken)
                    statement.executeUpdate() == 1
                }
            }
        }
    }

    override suspend fun findTriggerKind(invocationId: String): Result<LlmDaemonTriggerKind?> =
        withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    jdbcConnection().prepareStatement(SELECT_LLM_LAUNCH_TRIGGER_KIND_SQL).use { statement ->
                        statement.setString(1, invocationId)
                        statement.executeQuery().use { resultSet ->
                            if (resultSet.next()) LlmDaemonTriggerKind.valueOf(resultSet.getString(1)) else null
                        }
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

    override suspend fun findBlockingRunningReservation(
        requestTriggerKind: LlmDaemonTriggerKind,
        activeSince: Instant,
    ): Result<LlmActiveLaunchReservation?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectBlockingActiveReservation(requestTriggerKind, activeSince)
                }
            }
        }
    }
}

/** risk_state row lock と共通 quota policy を使って LLM 起動を予約する。 */
fun JdbcTransaction.tryReserveLlmLaunchInTransaction(
    request: LlmLaunchReservationRequest,
): LlmLaunchReservationOutcome {
    val riskState = selectRiskState(forUpdate = true)

    if (riskState.state == RiskHaltState.HARD_HALT) {
        return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.HARD_HALT)
    }

    val activeSince = request.reservedAt.minus(request.activeReservationStaleAfter)
    val activeReservation = selectBlockingActiveReservation(request.triggerKind, activeSince)

    if (activeReservation != null) {
        return LlmLaunchReservationOutcome.Rejected(
            LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION,
            activeReservation,
        )
    }

    val usage = aggregateLlmLaunchUsageWindows(
        hourlySince = request.reservedAt.minus(request.hourlyWindow),
        dailySince = request.reservedAt.minus(request.dailyWindow),
    )

    launchBudgetRejection(request, usage.hourly, usage.daily)?.let { rejectionReason ->
        return LlmLaunchReservationOutcome.Rejected(rejectionReason)
    }

    insertReservation(request)

    return LlmLaunchReservationOutcome.Reserved(request.invocationId)
}

private fun JdbcTransaction.selectBlockingActiveReservation(
    requestTriggerKind: LlmDaemonTriggerKind,
    activeSince: Instant,
): LlmActiveLaunchReservation? {
    val includeReflection = requestTriggerKind in setOf(
        LlmDaemonTriggerKind.REFLECTION,
        LlmDaemonTriggerKind.EVALUATION_REPORT,
    )
    return jdbcConnection().prepareStatement(SELECT_ACTIVE_LLM_LAUNCH_RESERVATION_SQL).use { statement ->
        statement.setString(1, LlmLaunchReservationStatus.RUNNING.name)
        statement.setLong(2, activeSince.toEpochMilli())
        statement.setBoolean(3, includeReflection)
        statement.setString(4, LlmDaemonTriggerKind.REFLECTION.name)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null
            LlmActiveLaunchReservation(
                invocationId = resultSet.getString("invocation_id"),
                triggerKind = LlmDaemonTriggerKind.valueOf(resultSet.getString("trigger_kind")),
                triggerKey = resultSet.getString("trigger_key"),
                reservedAt = Instant.ofEpochMilli(resultSet.getLong("reserved_at")),
            )
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
        statement.setString(7, request.triggerKind.executionClaimState().name)
        statement.executeUpdate()
    }
}

/** 既存予約を同じ caller transaction 内で terminal にする。 */
fun JdbcTransaction.finishLlmLaunchInTransaction(finish: LlmLaunchReservationFinish) {
    val updatedRows = jdbcConnection().prepareStatement(FINISH_LLM_LAUNCH_RESERVATION_SQL).use { statement ->
        statement.setString(1, finish.status.name)
        statement.setLong(2, finish.finishedAt.toEpochMilli())
        statement.setNullableString(3, finish.reason)
        statement.setString(4, finish.invocationId)
        statement.setNullableString(5, finish.claimantToken)
        statement.setNullableString(6, finish.claimantToken)
        statement.setNullableLong(7, finish.observedHeartbeatAt?.toEpochMilli())
        statement.setNullableLong(8, finish.observedHeartbeatAt?.toEpochMilli())
        statement.executeUpdate()
    }

    check(updatedRows in 0..1) { "Conditional reservation finish updated multiple rows." }
}

/** claim conditional update と stable rejection classification を同じ transaction で行う。 */
internal fun JdbcTransaction.claimLlmLaunchInTransaction(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
    val claimedAt = jdbcConnection().prepareStatement(CLAIM_LLM_LAUNCH_RESERVATION_SQL).use { statement ->
        statement.setString(1, request.claimantToken)
        statement.setLong(2, request.claimedAt.toEpochMilli())
        statement.setLong(3, request.claimedAt.toEpochMilli())
        statement.setString(4, request.invocationId)
        statement.setString(5, request.triggerKind.name)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) Instant.ofEpochMilli(resultSet.getLong("execution_claimed_at")) else null
        }
    }
    if (claimedAt != null) return LlmExecutionClaimOutcome.Claimed(claimedAt)

    val snapshot = selectExecutionClaim(request.invocationId)
        ?: return LlmExecutionClaimOutcome.Rejected(LlmExecutionClaimRejectionReason.RESERVATION_MISSING)
    val reason = when {
        snapshot.triggerKind != request.triggerKind -> LlmExecutionClaimRejectionReason.TRIGGER_MISMATCH
        snapshot.status != LlmLaunchReservationStatus.RUNNING -> LlmExecutionClaimRejectionReason.TERMINAL
        snapshot.claimState == null -> LlmExecutionClaimRejectionReason.LEGACY_UNCLAIMABLE
        snapshot.claimState == LlmExecutionClaimState.NOT_REQUIRED -> LlmExecutionClaimRejectionReason.CLAIM_NOT_REQUIRED
        else -> LlmExecutionClaimRejectionReason.ALREADY_CLAIMED
    }
    return LlmExecutionClaimOutcome.Rejected(reason)
}

private fun JdbcTransaction.selectExecutionClaim(invocationId: String): LlmExecutionClaimSnapshot? {
    return jdbcConnection().prepareStatement(SELECT_LLM_EXECUTION_CLAIM_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null
            LlmExecutionClaimSnapshot(
                invocationId = resultSet.getString("invocation_id"),
                triggerKind = LlmDaemonTriggerKind.valueOf(resultSet.getString("trigger_kind")),
                status = LlmLaunchReservationStatus.valueOf(resultSet.getString("status")),
                claimState = resultSet.getString("execution_claim_state")?.let(LlmExecutionClaimState::valueOf),
                claimantToken = resultSet.getString("execution_claim_token"),
                claimedAt = resultSet.getLong("execution_claimed_at").takeUnless { resultSet.wasNull() }
                    ?.let(Instant::ofEpochMilli),
                heartbeatAt = resultSet.getLong("execution_claim_heartbeat_at").takeUnless { resultSet.wasNull() }
                    ?.let(Instant::ofEpochMilli),
            )
        }
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
