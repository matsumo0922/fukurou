@file:Suppress(
    "ImportOrdering",
    "Wrapping",
    "Indentation",
    "NoSemicolons",
    "FunctionSignature",
    "PropertyWrapping",
    "TrailingCommaOnCallSite",
    "TooManyFunctions",
)

package me.matsumo.fukurou

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRejectionReason
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationPopulationScope
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.persistence.GapPopulationScope
import me.matsumo.fukurou.trading.persistence.finishLlmLaunchInTransaction
import me.matsumo.fukurou.trading.persistence.acquireGapPopulationGenerationToken
import me.matsumo.fukurou.trading.persistence.ensureEvaluationReportGapPopulationLifecycleSchema
import me.matsumo.fukurou.trading.persistence.requireFullGapPopulationAdmission
import me.matsumo.fukurou.trading.persistence.tryReserveLlmLaunchInTransaction
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

private fun JdbcTransaction.acquireEvaluationGapPopulationToken(scope: GapPopulationScope? = null) {
    if (ensureEvaluationReportGapPopulationLifecycleSchema()) {
        if (scope == null) acquireGapPopulationGenerationToken() else acquireGapPopulationGenerationToken(scope)
    }
}

/** Evaluation report job/revision/pin と LLM reservation を同じ PostgreSQL transaction で扱う。 */
internal class EvaluationReportPersistence(
    private val database: ExposedDatabase,
    private val runnerConfig: LlmRunnerConfig,
    private val staleAfter: Duration,
    private val clock: Clock,
    private val mode: TradingMode,
    private val symbol: TradingSymbol,
    private val beforeCompleteTransaction: () -> Unit = {},
) {
    init {
        exposedTransaction(database) {
            ensureSchema()
            acquireEvaluationGapPopulationToken()
            recoverInterruptedJobs()
        }
    }

    /** request identity、revision number、共通 LLM reservation または rejection を atomic に保存する。 */
    fun admit(job: EvaluationReportJobResponse, scopeKey: String): Result<EvaluationReportAdmission> = runCatching {
        exposedTransaction(database) {
            requireFullGapPopulationAdmission("evaluation report admission")
            val populationScope = reportPopulationScope(scopeKey)
            acquireEvaluationGapPopulationToken(populationScope)
            val now = clock.instant()
            val numberedJob = job.copy(revisionNumber = nextRevisionNumberInTransaction())
            val reportRateExceeded = count(
                "SELECT COUNT(*) FROM evaluation_report_jobs WHERE requested_at >= ?",
                now.minus(Duration.ofHours(1)).toEpochMilli(),
            ) >= 3L
            val outcome = if (reportRateExceeded) {
                LlmLaunchReservationOutcome.Rejected(LlmLaunchReservationRejectionReason.REPORT_RATE_LIMIT)
            } else {
                tryReserveLlmLaunchInTransaction(
                    LlmLaunchReservationRequest(
                        invocationId = numberedJob.jobId,
                        triggerKind = LlmDaemonTriggerKind.EVALUATION_REPORT,
                        triggerKey = "evaluation-report:$scopeKey",
                        reservedAt = now,
                        runnerConfig = runnerConfig,
                        hourlyWindow = Duration.ofHours(1),
                        dailyWindow = Duration.ofDays(1),
                        activeReservationStaleAfter = staleAfter,
                        populationScope = LlmLaunchReservationPopulationScope(
                            kind = populationScope.kind,
                            mode = mode,
                            symbol = symbol,
                            accountEpochId = populationScope.accountEpochId.toString(),
                            cohort = populationScope.cohort,
                            executionSemanticsVersion = populationScope.executionSemanticsVersion,
                        ),
                    ),
                )
            }
            val persistedJob = when (outcome) {
                is LlmLaunchReservationOutcome.Reserved -> numberedJob
                is LlmLaunchReservationOutcome.Rejected -> numberedJob.copy(
                    status = "REJECTED",
                    stage = "REJECTED",
                    failureCode = outcome.reason.name,
                    failureMessage = "Report admission was rejected by the shared LLM launch policy.",
                    activeInvocationId = outcome.activeReservation?.invocationId,
                    retryAfterSeconds = retryAfterSeconds(outcome.reason),
                )
            }
            insertJob(persistedJob, scopeKey, populationScope, now.toEpochMilli())
            insertJobEvent(persistedJob, now.toEpochMilli())

            EvaluationReportAdmission(persistedJob, outcome)
        }
    }

    fun updateJob(job: EvaluationReportJobResponse): Result<Unit> = runCatching {
        exposedTransaction(database) {
            acquireEvaluationGapPopulationToken()
            jdbcConnection().prepareStatement(
                "UPDATE evaluation_report_jobs SET status=?, stage=?, failure_code=?, failure_message=?, updated_at=? WHERE job_id=?",
            ).use { statement ->
                statement.setString(1, job.status)
                statement.setString(2, job.stage)
                statement.setString(3, job.failureCode)
                statement.setString(4, job.failureMessage)
                statement.setLong(5, clock.instant().toEpochMilli())
                statement.setObject(6, UUID.fromString(job.jobId))
                check(statement.executeUpdate() == 1)
            }
            insertJobEvent(job, clock.instant().toEpochMilli())
        }
    }

    fun saveSnapshot(snapshotId: String, scopeKey: String, payload: String, inputHash: String): Result<Unit> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                "INSERT INTO evaluation_report_snapshots (snapshot_id, scope_key, canonical_payload, input_hash, created_at) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(snapshotId))
                statement.setString(2, scopeKey)
                statement.setString(3, payload)
                statement.setString(4, inputHash)
                statement.setLong(5, clock.instant().toEpochMilli())
                statement.executeUpdate()
            }
        }
    }

    fun job(jobId: String): Result<EvaluationReportJobResponse?> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                "SELECT revision_id, revision_number, status, stage, failure_code, failure_message, active_invocation_id, retry_after_seconds, scope_key FROM evaluation_report_jobs WHERE job_id=?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(jobId))
                statement.executeQuery().use { result ->
                    if (!result.next()) return@use null
                    val scopeIdentity = EvaluationReportScopeKey.decode(result.getString(9))

                    EvaluationReportJobResponse(
                        jobId = jobId,
                        revisionId = result.getObject(1).toString(),
                        revisionNumber = result.getLong(2),
                        status = result.getString(3),
                        stage = result.getString(4),
                        failureCode = result.getString(5),
                        failureMessage = result.getString(6),
                        activeInvocationId = result.getString(7),
                        retryAfterSeconds = result.getLong(8).takeUnless { result.wasNull() },
                        epochId = scopeIdentity.epochId,
                        cohort = scopeIdentity.cohort,
                    )
                }
            }
        }
    }

    fun jobEvents(jobId: String): Result<List<EvaluationReportJobEvent>> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                "SELECT status, stage, code, occurred_at FROM evaluation_report_job_events WHERE job_id=? ORDER BY event_id",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(jobId))
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(EvaluationReportJobEvent(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4)))
                    }
                }
            }
        }
    }

    fun complete(report: EvaluationReportResponse, job: EvaluationReportJobResponse): Result<Unit> = runCatching {
        beforeCompleteTransaction()
        exposedTransaction(database) {
            val populationScope = reportPopulationScope(report.scopeKey)
            acquireEvaluationGapPopulationToken(populationScope)
            val now = clock.instant().toEpochMilli()
            check(!reportPublicationBlocked(job.jobId, populationScope)) {
                "evaluation report publication is blocked by gap population."
            }
            val completed = jdbcConnection().prepareStatement(
                "UPDATE evaluation_report_jobs SET status='SUCCEEDED',stage='COMPLETE',failure_code=NULL," +
                    "failure_message=NULL,updated_at=? WHERE job_id=? AND status IN ('REQUESTED','RUNNING')",
            ).use { statement ->
                statement.setLong(1, now)
                statement.setObject(2, UUID.fromString(job.jobId))
                statement.executeUpdate()
            }
            check(completed == 1) { "evaluation report job is already terminal." }
            jdbcConnection().prepareStatement(
                """
                INSERT INTO evaluation_report_revisions (revision_id, job_id, scope_key, revision_number, report_json, generated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(report.revisionId))
                statement.setObject(2, UUID.fromString(report.jobId))
                statement.setString(3, report.scopeKey)
                statement.setLong(4, report.revisionNumber)
                statement.setString(5, PersistenceJson.encodeToString(report))
                statement.setLong(6, clock.instant().toEpochMilli())
                statement.executeUpdate()
            }
            jdbcConnection().prepareStatement(
                """
                INSERT INTO evaluation_report_pins (scope_key, revision_id, pinned_at)
                VALUES (?, ?, ?)
                ON CONFLICT (scope_key) DO UPDATE SET revision_id=EXCLUDED.revision_id, pinned_at=EXCLUDED.pinned_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, report.scopeKey)
                statement.setObject(2, UUID.fromString(report.revisionId))
                statement.setLong(3, clock.instant().toEpochMilli())
                statement.executeUpdate()
            }
            insertJobEvent(job.copy(status = "SUCCEEDED", stage = "COMPLETE"), now)
            finishLlmLaunchInTransaction(
                LlmLaunchReservationFinish(job.jobId, LlmLaunchReservationStatus.FINISHED, null, clock.instant()),
            )
        }
    }

    fun fail(job: EvaluationReportJobResponse): Result<Unit> = runCatching {
        exposedTransaction(database) {
            acquireEvaluationGapPopulationToken()
            updateJobInTransaction(job)
            insertJobEvent(job, clock.instant().toEpochMilli())
            finishLlmLaunchInTransaction(
                LlmLaunchReservationFinish(job.jobId, LlmLaunchReservationStatus.FAILED, job.failureCode, clock.instant()),
            )
        }
    }

    fun default(scopeKey: String): Result<EvaluationReportResponse?> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                """
                SELECT revision.report_json
                FROM evaluation_report_revisions revision
                LEFT JOIN evaluation_report_pins pin ON pin.revision_id=revision.revision_id AND pin.scope_key=?
                WHERE revision.scope_key=?
                ORDER BY (pin.revision_id IS NOT NULL) DESC, revision.revision_number DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, scopeKey)
                statement.setString(2, scopeKey)
                statement.executeQuery().use { result ->
                    if (result.next()) PersistenceJson.decodeFromString(result.getString(1)) else null
                }
            }
        }
    }

    fun history(scopeKey: String): Result<List<EvaluationReportHistoryItemResponse>> = runCatching {
        val scopeIdentity = EvaluationReportScopeKey.decode(scopeKey)
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                """
                SELECT job.job_id, job.revision_id, job.revision_number, job.status,
                       job.requested_at, pin.revision_id IS NOT NULL
                FROM evaluation_report_jobs job
                LEFT JOIN evaluation_report_revisions revision ON revision.revision_id=job.revision_id
                LEFT JOIN evaluation_report_pins pin ON pin.scope_key=job.scope_key AND pin.revision_id=job.revision_id
                WHERE job.scope_key=? ORDER BY job.requested_at DESC, job.job_id DESC LIMIT 50
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, scopeKey)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                EvaluationReportHistoryItemResponse(
                                    jobId = result.getObject(1).toString(),
                                    revisionId = result.getObject(2).toString(),
                                    revisionNumber = result.getLong(3),
                                    status = result.getString(4),
                                    requestedAt = java.time.Instant.ofEpochMilli(result.getLong(5)).toString(),
                                    pinned = result.getBoolean(6),
                                    epochId = scopeIdentity.epochId,
                                    cohort = scopeIdentity.cohort,
                                    scopeKey = scopeKey,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun revision(revisionId: String): Result<EvaluationReportResponse?> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                "SELECT report_json FROM evaluation_report_revisions WHERE revision_id=?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(revisionId))
                statement.executeQuery().use { result ->
                    if (result.next()) PersistenceJson.decodeFromString(result.getString(1)) else null
                }
            }
        }
    }

    fun pin(scopeKey: String, revisionId: String): Result<Unit> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                """
                INSERT INTO evaluation_report_pins (scope_key, revision_id, pinned_at) VALUES (?, ?, ?)
                ON CONFLICT (scope_key) DO UPDATE SET revision_id=EXCLUDED.revision_id, pinned_at=EXCLUDED.pinned_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, scopeKey)
                statement.setObject(2, UUID.fromString(revisionId))
                statement.setLong(3, clock.instant().toEpochMilli())
                statement.executeUpdate()
            }
        }
    }

    fun unpin(scopeKey: String): Result<Unit> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                "DELETE FROM evaluation_report_pins WHERE scope_key=?",
            ).use { statement ->
                statement.setString(1, scopeKey)
                statement.executeUpdate()
            }
        }
    }

    private fun JdbcTransaction.ensureSchema() {
        exec(
            """
            CREATE TABLE IF NOT EXISTS evaluation_report_jobs (
              job_id UUID PRIMARY KEY, revision_id UUID UNIQUE NOT NULL, scope_key VARCHAR(400) NOT NULL,
              revision_number BIGINT NOT NULL,
              status VARCHAR(32) NOT NULL, stage VARCHAR(64) NOT NULL, failure_code VARCHAR(128),
              failure_message TEXT, active_invocation_id VARCHAR(255), retry_after_seconds BIGINT,
              requested_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
            );
            CREATE SEQUENCE IF NOT EXISTS evaluation_report_revision_number_seq;
            CREATE TABLE IF NOT EXISTS evaluation_report_revisions (
              revision_id UUID PRIMARY KEY, job_id UUID UNIQUE NOT NULL REFERENCES evaluation_report_jobs(job_id),
              scope_key VARCHAR(400) NOT NULL, revision_number BIGINT NOT NULL, report_json TEXT NOT NULL, generated_at BIGINT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS evaluation_report_snapshots (
              snapshot_id UUID PRIMARY KEY, scope_key VARCHAR(400) NOT NULL,
              canonical_payload TEXT NOT NULL, input_hash VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS evaluation_report_pins (
              scope_key VARCHAR(400) PRIMARY KEY, revision_id UUID NOT NULL REFERENCES evaluation_report_revisions(revision_id), pinned_at BIGINT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS evaluation_report_job_events (
              event_id BIGSERIAL PRIMARY KEY, job_id UUID NOT NULL REFERENCES evaluation_report_jobs(job_id),
              status VARCHAR(32) NOT NULL, stage VARCHAR(64) NOT NULL, code VARCHAR(128), occurred_at BIGINT NOT NULL
            );
            """.trimIndent(),
        )
        exec("ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS revision_number BIGINT")
        exec("ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS active_invocation_id VARCHAR(255)")
        exec("ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS retry_after_seconds BIGINT")
        exec("UPDATE evaluation_report_jobs SET revision_number=nextval('evaluation_report_revision_number_seq') WHERE revision_number IS NULL")
        exec("ALTER TABLE evaluation_report_jobs ALTER COLUMN revision_number SET NOT NULL")
    }

    private fun JdbcTransaction.count(sql: String, since: Long): Long {
        return jdbcConnection().prepareStatement(sql).use { statement ->
            statement.setLong(1, since)
            statement.executeQuery().use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

    private fun JdbcTransaction.insertJob(
        job: EvaluationReportJobResponse,
        scopeKey: String,
        populationScope: GapPopulationScope,
        now: Long,
    ) {
        jdbcConnection().prepareStatement(
            "INSERT INTO evaluation_report_jobs (job_id,revision_id,scope_key,revision_number,status,stage," +
                "failure_code,failure_message,active_invocation_id,retry_after_seconds,requested_at,updated_at," +
                "population_scope_kind,population_mode,population_symbol,population_account_epoch_id," +
                "population_cohort,population_execution_semantics_version) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { statement ->
            statement.setObject(1, UUID.fromString(job.jobId))
            statement.setObject(2, UUID.fromString(job.revisionId))
            statement.setString(3, scopeKey)
            statement.setLong(4, job.revisionNumber)
            statement.setString(5, job.status)
            statement.setString(6, job.stage)
            statement.setString(7, job.failureCode)
            statement.setString(8, job.failureMessage)
            statement.setString(9, job.activeInvocationId)
            job.retryAfterSeconds?.let { statement.setLong(10, it) }
                ?: statement.setNull(10, java.sql.Types.BIGINT)
            statement.setLong(11, now)
            statement.setLong(12, now)
            statement.setString(13, populationScope.kind)
            statement.setString(14, populationScope.mode)
            statement.setString(15, populationScope.symbol)
            statement.setObject(16, populationScope.accountEpochId)
            statement.setString(17, populationScope.cohort)
            statement.setString(18, populationScope.executionSemanticsVersion)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.insertJobEvent(job: EvaluationReportJobResponse, occurredAt: Long) {
        jdbcConnection().prepareStatement(
            "INSERT INTO evaluation_report_job_events (job_id, status, stage, code, occurred_at) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, UUID.fromString(job.jobId))
            statement.setString(2, job.status)
            statement.setString(3, job.stage)
            statement.setString(4, job.failureCode)
            statement.setLong(5, occurredAt)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.nextRevisionNumberInTransaction(): Long =
        exec("SELECT nextval('evaluation_report_revision_number_seq')") { result ->
            result.next()
            result.getLong(1)
        } ?: error("revision sequence did not return a value")

    private fun JdbcTransaction.updateJobInTransaction(job: EvaluationReportJobResponse) {
        jdbcConnection().prepareStatement(
            "UPDATE evaluation_report_jobs SET status=?, stage=?, failure_code=?, failure_message=?, updated_at=? WHERE job_id=?",
        ).use { statement ->
            statement.setString(1, job.status)
            statement.setString(2, job.stage)
            statement.setString(3, job.failureCode)
            statement.setString(4, job.failureMessage)
            statement.setLong(5, clock.instant().toEpochMilli())
            statement.setObject(6, UUID.fromString(job.jobId))
            check(statement.executeUpdate() == 1)
        }
    }

    private fun JdbcTransaction.recoverInterruptedJobs() {
        val now = clock.instant().toEpochMilli()
        exec(
            "INSERT INTO evaluation_report_job_events (job_id, status, stage, code, occurred_at) " +
                "SELECT job_id, 'FAILED', 'FAILED', 'FAILED_PROCESS_INTERRUPTED', $now FROM evaluation_report_jobs " +
                "WHERE status IN ('REQUESTED','RUNNING')",
        )
        exec(
            "UPDATE evaluation_report_jobs SET status='FAILED', stage='FAILED', failure_code='FAILED_PROCESS_INTERRUPTED', failure_message='Server restarted before report generation completed.', updated_at=" +
                now + " WHERE status IN ('REQUESTED','RUNNING')",
        )
        if (relationExists("llm_launch_reservations")) {
            exec(
                "UPDATE llm_launch_reservations SET status='FAILED', reason='FAILED_PROCESS_INTERRUPTED', finished_at=" +
                    now + " WHERE trigger_kind='EVALUATION_REPORT' AND status='RUNNING'",
            )
        }
    }

    private fun reportPopulationScope(scopeKey: String): GapPopulationScope {
        val identity = EvaluationReportScopeKey.decode(scopeKey)
        val epochId = requireNotNull(identity.epochId) { "REPORT_SCOPE_INVALID: unversioned scope." }
        val cohort = requireNotNull(identity.cohort) { "REPORT_SCOPE_INVALID: cohort is missing." }
        return GapPopulationScope(
            kind = "SYMBOL",
            mode = mode.name,
            symbol = symbol.apiSymbol,
            accountEpochId = UUID.fromString(epochId),
            cohort = cohort,
            executionSemanticsVersion = if (cohort == "CURRENT") "PAPER_WS_V1" else null,
        )
    }

    private fun JdbcTransaction.reportPublicationBlocked(jobId: String, scope: GapPopulationScope): Boolean {
        return jdbcConnection().prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1 FROM evaluation_report_jobs job
                JOIN market_data_gap_work work ON work.scope_hash=?
                WHERE job.job_id=?::uuid AND job.birth_sequence<=work.birth_sequence_upper
                  AND work.state IN ('QUEUED','CAPTURING','SEALED','APPLYING','UNKNOWN')
            ) OR EXISTS (
                SELECT 1 FROM market_data_gap_population_members member
                WHERE member.entity_type='EVALUATION_REPORT_JOB' AND member.entity_id=? AND member.scope_hash=?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, scope.hash)
            statement.setString(2, jobId)
            statement.setString(3, jobId)
            statement.setString(4, scope.hash)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
    }

    private fun JdbcTransaction.relationExists(name: String): Boolean =
        jdbcConnection().prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
}

internal data class EvaluationReportAdmission(
    val job: EvaluationReportJobResponse,
    val reservationOutcome: LlmLaunchReservationOutcome,
)

internal data class EvaluationReportJobEvent(val status: String, val stage: String, val code: String?, val occurredAt: Long)

private fun retryAfterSeconds(reason: LlmLaunchReservationRejectionReason): Long = when (reason) {
    LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION -> 15
    LlmLaunchReservationRejectionReason.REPORT_RATE_LIMIT,
    LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR,
    LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_HOURLY_HEADROOM,
    LlmLaunchReservationRejectionReason.INSUFFICIENT_EVALUATION_HOURLY_HEADROOM,
    -> Duration.ofHours(1).seconds
    LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY,
    LlmLaunchReservationRejectionReason.INSUFFICIENT_REFLECTION_DAILY_HEADROOM,
    LlmLaunchReservationRejectionReason.INSUFFICIENT_EVALUATION_DAILY_HEADROOM,
    -> Duration.ofDays(1).seconds
    LlmLaunchReservationRejectionReason.HARD_HALT -> 60
}

private val PersistenceJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

private fun JdbcTransaction.jdbcConnection(): java.sql.Connection {
    return connection.connection as java.sql.Connection
}
