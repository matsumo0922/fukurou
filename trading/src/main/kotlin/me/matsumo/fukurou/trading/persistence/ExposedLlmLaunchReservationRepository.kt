@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.daemon.LlmActiveLaunchReservation
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimOutcome
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimRequest
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimSnapshot
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryRequest
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryOutcome
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryDeadline
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryRetryPermit
import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryScan
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.daemon.executionClaimState
import me.matsumo.fukurou.trading.daemon.launchBudgetRejection
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_RUNNING
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
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
        single_attempt_key,
        status,
        reserved_at,
        finished_at,
        reason,
        execution_claim_state,
        population_scope_kind,
        population_mode,
        population_symbol,
        population_account_epoch_id,
        population_cohort,
        population_execution_semantics_version
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?,
        COALESCE(?::uuid,(SELECT scope_account_epoch_id FROM gap_population_control WHERE id=1)), ?, ?)
    ON CONFLICT (single_attempt_key) WHERE single_attempt_key IS NOT NULL DO NOTHING
    RETURNING invocation_id
"""

/** single-attempt key の予約取得済み判定 SQL。 */
private const val SELECT_SINGLE_ATTEMPT_EXISTS_SQL = """
    SELECT 1
    FROM llm_launch_reservations
    WHERE single_attempt_key = ?
    LIMIT 1
"""

private const val INSERT_LLM_PID_REGISTRATION_SQL = """
    INSERT INTO llm_pid_registrations (
        registration_id, invocation_id, reservation_id, role, container_instance_id, state
    ) VALUES (md5('fukurou-pid-registration-v1:' || ?)::uuid, ?, ?, 'PROVIDER', ?, 'SPAWN_RESERVED')
"""

/**
 * RUNNING の LLM 起動予約数を数える SQL。
 */
internal const val SELECT_ACTIVE_LLM_LAUNCH_RESERVATION_SQL = """
    SELECT candidate.invocation_id, candidate.trigger_kind, candidate.trigger_key, candidate.reserved_at,
        EXISTS (
            SELECT 1 FROM llm_launch_reservations
            WHERE status = 'RUNNING' AND execution_claim_state = 'CLAIMED'
                AND (? OR trigger_kind <> ?)
        ) OR EXISTS (
            SELECT 1 FROM llm_launch_reservations
            WHERE status = 'RUNNING'
                AND (execution_claim_state IS NULL OR execution_claim_state IN ('AVAILABLE', 'NOT_REQUIRED'))
                AND reserved_at >= ?
                AND (? OR trigger_kind <> ?)
        ) AS has_active_reservation
    FROM (SELECT 1) AS anchor
    LEFT JOIN LATERAL (
        SELECT invocation_id, trigger_kind, trigger_key, reserved_at
        FROM llm_launch_reservations
        WHERE status = 'RUNNING'
            AND (
                execution_claim_state = 'CLAIMED'
                OR (
                    (execution_claim_state IS NULL OR execution_claim_state IN ('AVAILABLE', 'NOT_REQUIRED'))
                    AND reserved_at >= ?
                )
            )
            AND (? OR trigger_kind <> ?)
        ORDER BY (execution_claim_state = 'CLAIMED') DESC, reserved_at ASC, invocation_id ASC
        LIMIT 1
    ) candidate ON TRUE
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
        AND (
            (execution_claim_state = 'CLAIMED' AND ? IS NOT NULL AND execution_claim_token = ?)
            OR (
                execution_claim_state IS DISTINCT FROM 'CLAIMED'
                AND (? IS NULL OR execution_claim_token = ?)
            )
        )
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
        AND reserved_at >= ?
    RETURNING execution_claimed_at
"""

/** claim rejection / reconciliation 用 snapshot SQL。 */
private const val SELECT_LLM_EXECUTION_CLAIM_SQL = """
    SELECT invocation_id, trigger_kind, status, execution_claim_state, execution_claim_token,
        execution_claimed_at, execution_claim_heartbeat_at, reserved_at
    FROM llm_launch_reservations
    WHERE invocation_id = ?
"""

/** stale AVAILABLE / CLAIMED reservation の bounded scan SQL。 */
private const val SELECT_STALE_LLM_EXECUTION_CLAIMS_SQL = """
    WITH candidates AS (
        SELECT invocation_id, trigger_kind, status, execution_claim_state, execution_claim_token,
            execution_claimed_at, execution_claim_heartbeat_at, reserved_at,
            COALESCE(execution_claim_heartbeat_at, execution_claimed_at) AS sort_heartbeat_at,
            execution_claimed_at AS sort_claimed_at
        FROM llm_launch_reservations
        WHERE status = 'RUNNING'
            AND execution_claim_state = 'CLAIMED'
            AND execution_claimed_at <= ?
            AND COALESCE(execution_claim_heartbeat_at, execution_claimed_at) <= ?
        UNION ALL
        SELECT invocation_id, trigger_kind, status, execution_claim_state, execution_claim_token,
            execution_claimed_at, execution_claim_heartbeat_at, reserved_at,
            reserved_at AS sort_heartbeat_at, reserved_at AS sort_claimed_at
        FROM llm_launch_reservations
        WHERE status = 'RUNNING'
            AND execution_claim_state = 'AVAILABLE'
            AND reserved_at <= ?
    )
    SELECT invocation_id, trigger_kind, status, execution_claim_state, execution_claim_token,
        execution_claimed_at, execution_claim_heartbeat_at, reserved_at
    FROM candidates
    WHERE ? OR (sort_heartbeat_at, sort_claimed_at, invocation_id) > (?, ?, ?)
    ORDER BY sort_heartbeat_at ASC, sort_claimed_at ASC, invocation_id ASC
    LIMIT ?
"""

/** scan で観測した state / token / heartbeat が変わっていない場合だけ FAILED にする SQL。 */
private const val RECOVER_STALE_LLM_EXECUTION_CLAIM_SQL = """
    UPDATE llm_launch_reservations
    SET status = 'FAILED', finished_at = ?, reason = ?
    WHERE invocation_id = ?
        AND status = 'RUNNING'
        AND execution_claim_state = ?
        AND reserved_at = ?
        AND execution_claim_token IS NOT DISTINCT FROM ?
        AND execution_claim_heartbeat_at IS NOT DISTINCT FROM ?
"""

/** recovery attempt の reservation exact readback SQL。 */
private const val SELECT_RECOVERY_RESERVATION_SQL = """
    SELECT invocation_id, status, execution_claim_state, execution_claim_token, reserved_at,
        execution_claim_heartbeat_at, finished_at, reason
    FROM llm_launch_reservations
    WHERE invocation_id = ?
"""

/** recovery attempt UUID に対応する audit exact readback SQL。 */
private const val SELECT_RECOVERY_AUDIT_SQL = """
    SELECT id, decision_run_id, tool_call_id, client_request_id, tool_name, event_type, payload, ts
    FROM command_event_log
    WHERE id = ?
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

private const val FINISH_LLM_PID_REGISTRATION_SQL = """
    UPDATE llm_pid_registrations
    SET state = 'TERMINAL', terminal_reason = ?, terminal_at = clock_timestamp(), updated_at = clock_timestamp()
    WHERE invocation_id = ? AND state IN ('SPAWN_RESERVED', 'ACTIVE')
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
@Suppress("TooManyFunctions")
class ExposedLlmLaunchReservationRepository(
    private val database: ExposedDatabase,
    private val nanoTime: () -> Long = System::nanoTime,
) : LlmLaunchReservationRepository {

    override suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome> {
        return withContext(Dispatchers.IO) {
            runCatching {
                LlmExecutionAdmissionHealth.withHealthyAdmission {
                    exposedTransaction(database) {
                        requireFullGapPopulationAdmission("LLM launch reservation")
                        val scope = request.populationScope
                        val epochId = scope.accountEpochId
                        if (epochId == null) {
                            acquireGapPopulationGenerationToken()
                        } else {
                            acquireGapPopulationGenerationToken(
                                GapPopulationScope(
                                    kind = scope.kind,
                                    mode = scope.mode.name,
                                    symbol = scope.symbol?.apiSymbol,
                                    accountEpochId = UUID.fromString(epochId),
                                    cohort = scope.cohort,
                                    executionSemanticsVersion = scope.executionSemanticsVersion,
                                ),
                            )
                        }
                        tryReserveLlmLaunchInTransaction(request)
                    }
                }
            }
        }
    }

    override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    acquireGapPopulationGenerationTokenForEntity("LLM_RESERVATION", reservationId(finish.invocationId))
                    finishLlmLaunchInTransaction(finish)
                }
            }
        }
    }

    override suspend fun claimForExecution(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
        return withContext(Dispatchers.IO) {
            try {
                LlmExecutionAdmissionHealth.withHealthyAdmission {
                    exposedTransaction(database) { claimLlmLaunchInTransaction(request) }
                }
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

    override suspend fun validateExecutionAdmission(invocationId: String, claimantToken: String?): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            runCatching {
                LlmExecutionAdmissionHealth.withHealthyAdmission {
                    exposedTransaction(database) {
                        val snapshot = selectExecutionClaim(invocationId)
                        if (snapshot?.status != LlmLaunchReservationStatus.RUNNING) return@exposedTransaction false

                        if (claimantToken == null) {
                            snapshot.claimState == LlmExecutionClaimState.NOT_REQUIRED
                        } else {
                            snapshot.claimState == LlmExecutionClaimState.CLAIMED &&
                                snapshot.claimantToken == claimantToken
                        }
                    }
                }
            }
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

    override suspend fun scanStaleExecutionClaims(
        scan: LlmExecutionRecoveryScan,
        deadline: LlmExecutionRecoveryDeadline,
    ): Result<List<LlmExecutionClaimSnapshot>> = withContext(Dispatchers.IO) {
        runCatching {
            exposedTransaction(database) {
                val candidates = prepareRecoveryStatement(
                    SELECT_STALE_LLM_EXECUTION_CLAIMS_SQL,
                    deadline,
                    nanoTime,
                ).use { statement ->
                    statement.setLong(1, scan.claimedBefore.toEpochMilli())
                    statement.setLong(2, scan.heartbeatBefore.toEpochMilli())
                    statement.setLong(3, scan.availableReservedBefore.toEpochMilli())
                    statement.setBoolean(4, scan.afterHeartbeatAt == null)
                    statement.setNullableLong(5, scan.afterHeartbeatAt?.toEpochMilli())
                    statement.setNullableLong(6, scan.afterClaimedAt?.toEpochMilli())
                    statement.setNullableString(7, scan.afterInvocationId)
                    statement.setInt(8, scan.limit)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) add(resultSet.toExecutionClaimSnapshot())
                        }
                    }
                }
                armRecoveryCommitDeadline(deadline, nanoTime)

                candidates
            }
        }
    }

    override suspend fun recoverStaleExecutionClaim(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> {
        return withContext(Dispatchers.IO) {
            val firstAttempt = runRecoveryMutation(request, deadline)
            if (firstAttempt == LlmExecutionRecoveryOutcome.Recovered) {
                return@withContext Result.success(readRecoveryOutcome(request, deadline).toPublicOutcome())
            }

            val firstReadback = readRecoveryOutcome(request, deadline)
            if (firstReadback != RecoveryReadbackOutcome.Uncommitted) {
                return@withContext Result.success(firstReadback.toPublicOutcome())
            }
            if (!retryPermit.isAvailable) return@withContext Result.success(firstReadback.toPublicOutcome())

            runRecoveryMutation(request, deadline, retryPermit)

            Result.success(readRecoveryOutcome(request, deadline).toPublicOutcome())
        }
    }

    override suspend fun reconcileStaleExecutionRecovery(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit,
    ): Result<LlmExecutionRecoveryOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val readback = readRecoveryOutcome(request, deadline)
            if (readback != RecoveryReadbackOutcome.Uncommitted) {
                return@runCatching readback.toPublicOutcome()
            }
            if (!retryPermit.isAvailable) return@runCatching readback.toPublicOutcome()

            runRecoveryMutation(request, deadline, retryPermit)

            readRecoveryOutcome(request, deadline).toPublicOutcome()
        }
    }

    private fun runRecoveryMutation(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
        retryPermit: LlmExecutionRecoveryRetryPermit? = null,
    ): LlmExecutionRecoveryOutcome? {
        return runCatching {
            exposedTransaction(database) {
                maxAttempts = 1
                acquireGapPopulationGenerationTokenForEntity(
                    "LLM_RESERVATION",
                    reservationId(request.invocationId),
                    deadline,
                    nanoTime,
                )
                if (retryPermit != null && !retryPermit.tryConsume()) return@exposedTransaction null

                val recovered = recoverStaleExecutionClaimInTransaction(request, deadline, nanoTime)
                armRecoveryCommitDeadline(deadline, nanoTime)

                if (recovered) LlmExecutionRecoveryOutcome.Recovered else null
            }
        }.getOrNull()
    }

    private fun readRecoveryOutcome(
        request: LlmExecutionRecoveryRequest,
        deadline: LlmExecutionRecoveryDeadline,
    ): RecoveryReadbackOutcome {
        return runCatching {
            exposedTransaction(database) {
                maxAttempts = 1
                val reservation = selectRecoveryReservation(request.invocationId, deadline, nanoTime)
                val audit = selectRecoveryAudit(request.recoveryAttemptId, deadline, nanoTime)
                armRecoveryCommitDeadline(deadline, nanoTime)

                classifyRecoveryReadback(request, reservation, audit)
            }
        }.getOrDefault(RecoveryReadbackOutcome.Unknown)
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

private fun JdbcTransaction.recoverStaleExecutionClaimInTransaction(
    request: LlmExecutionRecoveryRequest,
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
): Boolean {
    return prepareRecoveryStatement(RECOVER_STALE_LLM_EXECUTION_CLAIM_SQL, deadline, nanoTime).use { statement ->
        statement.setLong(1, request.finishedAt.toEpochMilli())
        statement.setString(2, request.reason)
        statement.setString(3, request.invocationId)
        statement.setString(4, request.claimState.name)
        statement.setLong(5, request.observedReservedAt.toEpochMilli())
        statement.setNullableString(6, request.claimantToken)
        statement.setNullableLong(7, request.observedHeartbeatAt?.toEpochMilli())
        val recovered = statement.executeUpdate() == 1
        if (recovered) {
            val runRecovered = recoverCurrentProcessLlmRun(request, deadline, nanoTime)
            insertRecoveryEvent(request, runRecovered, deadline, nanoTime)
        }
        recovered
    }
}

private fun JdbcTransaction.selectRecoveryReservation(
    invocationId: String,
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
): RecoveryReservationSnapshot? {
    return prepareRecoveryStatement(SELECT_RECOVERY_RESERVATION_SQL, deadline, nanoTime).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null

            RecoveryReservationSnapshot(
                invocationId = resultSet.getString("invocation_id"),
                status = LlmLaunchReservationStatus.valueOf(resultSet.getString("status")),
                claimState = resultSet.getString("execution_claim_state")?.let(LlmExecutionClaimState::valueOf),
                claimantToken = resultSet.getString("execution_claim_token"),
                reservedAt = Instant.ofEpochMilli(resultSet.getLong("reserved_at")),
                heartbeatAt = resultSet.getNullableInstant("execution_claim_heartbeat_at"),
                finishedAt = resultSet.getNullableInstant("finished_at"),
                reason = resultSet.getString("reason"),
            )
        }
    }
}

private fun JdbcTransaction.selectRecoveryAudit(
    recoveryAttemptId: UUID,
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
): RecoveryAuditSnapshot? {
    return prepareRecoveryStatement(SELECT_RECOVERY_AUDIT_SQL, deadline, nanoTime).use { statement ->
        statement.setObject(1, recoveryAttemptId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null

            RecoveryAuditSnapshot(
                id = resultSet.getObject("id", UUID::class.java),
                decisionRunId = resultSet.getString("decision_run_id"),
                toolCallId = resultSet.getString("tool_call_id"),
                clientRequestId = resultSet.getString("client_request_id"),
                toolName = resultSet.getString("tool_name"),
                eventType = resultSet.getString("event_type"),
                payload = resultSet.getString("payload"),
                occurredAt = Instant.ofEpochMilli(resultSet.getLong("ts")),
            )
        }
    }
}

private fun JdbcTransaction.recoverCurrentProcessLlmRun(
    request: LlmExecutionRecoveryRequest,
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
): Boolean {
    if (request.claimState != LlmExecutionClaimState.CLAIMED) return false

    val sql = """
        UPDATE llm_runs
        SET status = ?, finished_at = ?, error_message = ?, terminal_cause = ?
        WHERE invocation_id = ? AND status = ? AND finished_at IS NULL
    """.trimIndent()

    return prepareRecoveryStatement(sql, deadline, nanoTime).use { statement ->
        statement.setString(1, LLM_RUN_STATUS_FAILED)
        statement.setLong(2, request.finishedAt.toEpochMilli())
        statement.setString(3, request.reason)
        statement.setString(4, LlmRunTerminalCause.RUNNER_FAILED.name)
        statement.setString(5, request.invocationId)
        statement.setString(6, LLM_RUN_STATUS_RUNNING)
        statement.executeUpdate() == 1
    }
}

private fun JdbcTransaction.insertRecoveryEvent(
    request: LlmExecutionRecoveryRequest,
    runRecovered: Boolean,
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
) {
    val llmRunExists = prepareRecoveryStatement(
        "SELECT EXISTS (SELECT 1 FROM llm_runs WHERE invocation_id = ?)",
        deadline,
        nanoTime,
    ).use { statement ->
        statement.setString(1, request.invocationId)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next())
            resultSet.getBoolean(1)
        }
    }
    insertRecoveryEvent(
        CommandEvent(
            id = request.recoveryAttemptId,
            decisionRunContext = DecisionRunContext(
                decisionRunId = request.invocationId,
                llmProvider = null,
                promptHash = null,
                systemPromptVersion = null,
                marketSnapshotId = null,
            ),
            toolName = "llm_execution_recovery",
            toolCallId = null,
            clientRequestId = request.invocationId,
            eventType = CommandEventType.LLM_INVOCATION_RECOVERED,
            payload = buildJsonObject {
                put("source", "current_process_periodic_scan")
                put("recoveryAttemptId", request.recoveryAttemptId.toString())
                put("invocationId", request.invocationId)
                put("executionClaimState", request.claimState.name)
                put("claimantTokenFingerprint", request.claimantToken?.takeLast(8))
                put("observedHeartbeatAt", request.observedHeartbeatAt?.toString())
                put("terminationFence", request.terminationFence)
                put("llmRunExists", llmRunExists)
                put("runRecovered", runRecovered)
                put("reservationRecovered", true)
                put("terminalCause", LlmRunTerminalCause.RUNNER_FAILED.name)
                put("recoveredAt", request.finishedAt.toString())
            }.toString(),
            occurredAt = request.finishedAt,
        ),
        deadline,
        nanoTime,
    )
}

private fun classifyRecoveryReadback(
    request: LlmExecutionRecoveryRequest,
    reservation: RecoveryReservationSnapshot?,
    audit: RecoveryAuditSnapshot?,
): RecoveryReadbackOutcome {
    val exactReservation = reservation?.matchesRecovered(request) == true
    val exactAudit = audit?.matches(request) == true
    if (exactReservation && exactAudit) return RecoveryReadbackOutcome.Recovered
    if (exactReservation || audit != null) return RecoveryReadbackOutcome.Unknown
    if (reservation == null) return RecoveryReadbackOutcome.Unknown
    if (reservation.status != LlmLaunchReservationStatus.RUNNING) return RecoveryReadbackOutcome.TerminalObserved

    return if (reservation.matchesPrecondition(request)) {
        RecoveryReadbackOutcome.Uncommitted
    } else {
        RecoveryReadbackOutcome.PreconditionChanged
    }
}

private fun RecoveryReservationSnapshot.matchesRecovered(request: LlmExecutionRecoveryRequest): Boolean {
    return matchesIdentity(request) &&
        status == LlmLaunchReservationStatus.FAILED &&
        finishedAt == request.finishedAt &&
        reason == request.reason
}

private fun RecoveryReservationSnapshot.matchesPrecondition(request: LlmExecutionRecoveryRequest): Boolean {
    return status == LlmLaunchReservationStatus.RUNNING && matchesIdentity(request)
}

private fun RecoveryReservationSnapshot.matchesIdentity(request: LlmExecutionRecoveryRequest): Boolean {
    return invocationId == request.invocationId &&
        claimState == request.claimState &&
        claimantToken == request.claimantToken &&
        reservedAt == request.observedReservedAt &&
        heartbeatAt == request.observedHeartbeatAt
}

private fun RecoveryAuditSnapshot.matches(request: LlmExecutionRecoveryRequest): Boolean {
    val attemptPayload = "\"recoveryAttemptId\":\"${request.recoveryAttemptId}\""

    return id == request.recoveryAttemptId &&
        decisionRunId == request.invocationId &&
        toolCallId == null &&
        clientRequestId == request.invocationId &&
        toolName == "llm_execution_recovery" &&
        eventType == CommandEventType.LLM_INVOCATION_RECOVERED.name &&
        attemptPayload in payload &&
        occurredAt == request.finishedAt
}

private sealed interface RecoveryReadbackOutcome {
    data object Recovered : RecoveryReadbackOutcome
    data object TerminalObserved : RecoveryReadbackOutcome
    data object PreconditionChanged : RecoveryReadbackOutcome
    data object Uncommitted : RecoveryReadbackOutcome
    data object Unknown : RecoveryReadbackOutcome
}

private fun RecoveryReadbackOutcome.toPublicOutcome(): LlmExecutionRecoveryOutcome {
    return when (this) {
        RecoveryReadbackOutcome.Recovered -> LlmExecutionRecoveryOutcome.Recovered
        RecoveryReadbackOutcome.TerminalObserved -> LlmExecutionRecoveryOutcome.TerminalObserved
        RecoveryReadbackOutcome.PreconditionChanged -> LlmExecutionRecoveryOutcome.PreconditionChanged
        RecoveryReadbackOutcome.Uncommitted,
        RecoveryReadbackOutcome.Unknown,
        -> LlmExecutionRecoveryOutcome.OutcomeUnknown(
            IllegalStateException("Recovery exact readback could not determine commit outcome: $this"),
        )
    }
}

private data class RecoveryReservationSnapshot(
    val invocationId: String,
    val status: LlmLaunchReservationStatus,
    val claimState: LlmExecutionClaimState?,
    val claimantToken: String?,
    val reservedAt: Instant,
    val heartbeatAt: Instant?,
    val finishedAt: Instant?,
    val reason: String?,
)

private data class RecoveryAuditSnapshot(
    val id: UUID,
    val decisionRunId: String?,
    val toolCallId: String?,
    val clientRequestId: String?,
    val toolName: String,
    val eventType: String,
    val payload: String,
    val occurredAt: Instant,
)

/** risk_state row lock と共通 quota policy を使って LLM 起動を予約する。 */
fun JdbcTransaction.tryReserveLlmLaunchInTransaction(
    request: LlmLaunchReservationRequest,
): LlmLaunchReservationOutcome {
    check(LlmExecutionAdmissionHealth.isHealthy()) { "LLM execution admission is fail-closed." }
    requireFullGapPopulationAdmission("LLM launch reservation")
    val riskState = selectRiskState(forUpdate = true)

    if (riskState.state == RiskHaltState.HARD_HALT) {
        return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.HARD_HALT)
    }

    if (request.singleAttemptKey != null && singleAttemptExists(request.singleAttemptKey)) {
        return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.TRIGGER_ALREADY_ATTEMPTED)
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

    if (!insertReservation(request)) {
        return LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.TRIGGER_ALREADY_ATTEMPTED)
    }

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
        statement.setBoolean(1, includeReflection)
        statement.setString(2, LlmDaemonTriggerKind.REFLECTION.name)
        statement.setLong(3, activeSince.toEpochMilli())
        statement.setBoolean(4, includeReflection)
        statement.setString(5, LlmDaemonTriggerKind.REFLECTION.name)
        statement.setLong(6, activeSince.toEpochMilli())
        statement.setBoolean(7, includeReflection)
        statement.setString(8, LlmDaemonTriggerKind.REFLECTION.name)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next())
            if (!resultSet.getBoolean("has_active_reservation")) return@use null
            LlmActiveLaunchReservation(
                invocationId = resultSet.getString("invocation_id"),
                triggerKind = LlmDaemonTriggerKind.valueOf(resultSet.getString("trigger_kind")),
                triggerKey = resultSet.getString("trigger_key"),
                reservedAt = Instant.ofEpochMilli(resultSet.getLong("reserved_at")),
            )
        }
    }
}

private fun JdbcTransaction.singleAttemptExists(singleAttemptKey: String): Boolean {
    return jdbcConnection().prepareStatement(SELECT_SINGLE_ATTEMPT_EXISTS_SQL).use { statement ->
        statement.setString(1, singleAttemptKey)
        statement.executeQuery().use { resultSet -> resultSet.next() }
    }
}

private fun JdbcTransaction.insertReservation(request: LlmLaunchReservationRequest): Boolean {
    val reservationId = UUID.randomUUID()
    val inserted = jdbcConnection().prepareStatement(INSERT_LLM_LAUNCH_RESERVATION_SQL).use { statement ->
        statement.setObject(1, reservationId)
        statement.setString(2, request.invocationId)
        statement.setString(3, request.triggerKind.name)
        statement.setString(4, request.triggerKey)
        statement.setNullableString(5, request.singleAttemptKey)
        statement.setString(6, LlmLaunchReservationStatus.RUNNING.name)
        statement.setLong(7, request.reservedAt.toEpochMilli())
        statement.setString(8, request.triggerKind.executionClaimState().name)
        statement.setString(9, request.populationScope.kind)
        statement.setString(10, request.populationScope.mode.name)
        statement.setString(11, request.populationScope.symbol?.apiSymbol)
        statement.setString(12, request.populationScope.accountEpochId)
        statement.setString(13, request.populationScope.cohort)
        statement.setString(14, request.populationScope.executionSemanticsVersion)
        statement.executeQuery().use { resultSet -> resultSet.next() }
    }
    if (!inserted) return false

    jdbcConnection().prepareStatement(INSERT_LLM_PID_REGISTRATION_SQL).use { statement ->
        statement.setString(1, request.invocationId)
        statement.setString(2, request.invocationId)
        statement.setObject(3, reservationId)
        statement.setString(4, System.getenv("HOSTNAME") ?: "unknown-container")
        check(statement.executeUpdate() == 1)
    }

    return true
}

private fun JdbcTransaction.reservationId(invocationId: String): String {
    return jdbcConnection().prepareStatement(
        "SELECT id::text FROM llm_launch_reservations WHERE invocation_id=?",
    ).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { rows ->
            require(rows.next()) { "LLM launch reservation was not found." }
            rows.getString(1)
        }
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
        statement.setNullableString(7, finish.claimantToken)
        statement.setNullableString(8, finish.claimantToken)
        statement.setNullableLong(9, finish.observedHeartbeatAt?.toEpochMilli())
        statement.setNullableLong(10, finish.observedHeartbeatAt?.toEpochMilli())
        statement.executeUpdate()
    }

    check(updatedRows in 0..1) { "Conditional reservation finish updated multiple rows." }
    if (updatedRows == 0) return

    val pidUpdatedRows = jdbcConnection().prepareStatement(FINISH_LLM_PID_REGISTRATION_SQL).use { statement ->
        statement.setString(1, finish.reason ?: finish.status.name)
        statement.setString(2, finish.invocationId)
        statement.executeUpdate()
    }
    require(pidUpdatedRows == 1) {
        "LLM PID registration was not found. invocationId=${finish.invocationId}"
    }
}

/** claim conditional update と stable rejection classification を同じ transaction で行う。 */
internal fun JdbcTransaction.claimLlmLaunchInTransaction(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
    val claimedAt = jdbcConnection().prepareStatement(CLAIM_LLM_LAUNCH_RESERVATION_SQL).use { statement ->
        statement.setString(1, request.claimantToken)
        statement.setLong(2, request.claimedAt.toEpochMilli())
        statement.setLong(3, request.claimedAt.toEpochMilli())
        statement.setString(4, request.invocationId)
        statement.setString(5, request.triggerKind.name)
        statement.setLong(6, request.activeSince.toEpochMilli())
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
            resultSet.toExecutionClaimSnapshot()
        }
    }
}

private fun java.sql.ResultSet.toExecutionClaimSnapshot(): LlmExecutionClaimSnapshot {
    return LlmExecutionClaimSnapshot(
        invocationId = getString("invocation_id"),
        triggerKind = LlmDaemonTriggerKind.valueOf(getString("trigger_kind")),
        status = LlmLaunchReservationStatus.valueOf(getString("status")),
        claimState = getString("execution_claim_state")?.let(LlmExecutionClaimState::valueOf),
        claimantToken = getString("execution_claim_token"),
        claimedAt = getLong("execution_claimed_at").takeUnless { wasNull() }?.let(Instant::ofEpochMilli),
        heartbeatAt = getLong("execution_claim_heartbeat_at").takeUnless { wasNull() }?.let(Instant::ofEpochMilli),
        reservedAt = Instant.ofEpochMilli(getLong("reserved_at")),
    )
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
