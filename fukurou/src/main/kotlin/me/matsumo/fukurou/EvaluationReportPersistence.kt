@file:Suppress(
    "ImportOrdering",
    "Wrapping",
    "Indentation",
    "NoSemicolons",
    "FunctionSignature",
    "PropertyWrapping",
    "TrailingCommaOnCallSite",
)

package me.matsumo.fukurou

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** Evaluation report job/revision/pin と LLM reservation を同じ PostgreSQL transaction で扱う。 */
internal class EvaluationReportPersistence(
    private val database: ExposedDatabase,
    private val runnerConfig: LlmRunnerConfig,
    private val staleAfter: Duration,
    private val clock: Clock,
) {
    init {
        exposedTransaction(database) { ensureSchema() }
    }

    /** quota/concurrency check、job identity、LLM reservation を atomic に作成する。 */
    fun admit(job: EvaluationReportJobResponse, scopeKey: String): Result<Unit> = runCatching {
        exposedTransaction(database) {
            val now = clock.instant().toEpochMilli()
            advisoryLock()
            requireNoConcurrentInvocation(now)
            requireReportRateLimit(now)
            requireLlmHeadroom(now)
            insertJob(job, scopeKey, now)
            insertReservation(job, now)
        }
    }

    fun updateJob(job: EvaluationReportJobResponse): Result<Unit> = runCatching {
        exposedTransaction(database) {
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
            if (job.status == "SUCCEEDED" || job.status == "FAILED") finishReservation(job)
        }
    }

    fun job(jobId: String): Result<EvaluationReportJobResponse?> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                "SELECT revision_id, status, stage, failure_code, failure_message FROM evaluation_report_jobs WHERE job_id=?",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(jobId))
                statement.executeQuery().use { result ->
                    if (!result.next()) return@use null

                    EvaluationReportJobResponse(
                        jobId = jobId,
                        revisionId = result.getObject(1).toString(),
                        status = result.getString(2),
                        stage = result.getString(3),
                        failureCode = result.getString(4),
                        failureMessage = result.getString(5),
                    )
                }
            }
        }
    }

    fun nextRevisionNumber(): Result<Long> = runCatching {
        exposedTransaction(database) {
            advisoryLock()
            jdbcConnection().prepareStatement(
                "SELECT COALESCE(MAX(revision_number), 0) + 1 FROM evaluation_report_revisions",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }
    }

    fun saveReport(report: EvaluationReportResponse): Result<Unit> = runCatching {
        exposedTransaction(database) {
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
        }
    }

    fun default(scopeKey: String): Result<EvaluationReportResponse?> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                """
                SELECT revision.report_json
                FROM evaluation_report_pins pin
                JOIN evaluation_report_revisions revision ON revision.revision_id=pin.revision_id
                WHERE pin.scope_key=?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, scopeKey)
                statement.executeQuery().use { result ->
                    if (result.next()) PersistenceJson.decodeFromString(result.getString(1)) else null
                }
            }
        }
    }

    fun history(scopeKey: String): Result<List<EvaluationReportHistoryItemResponse>> = runCatching {
        exposedTransaction(database) {
            jdbcConnection().prepareStatement(
                """
                SELECT job.job_id, job.revision_id, COALESCE(revision.revision_number, 0), job.status,
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
              status VARCHAR(32) NOT NULL, stage VARCHAR(64) NOT NULL, failure_code VARCHAR(128),
              failure_message TEXT, requested_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS evaluation_report_revisions (
              revision_id UUID PRIMARY KEY, job_id UUID UNIQUE NOT NULL REFERENCES evaluation_report_jobs(job_id),
              scope_key VARCHAR(400) NOT NULL, revision_number BIGINT NOT NULL, report_json TEXT NOT NULL, generated_at BIGINT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS evaluation_report_pins (
              scope_key VARCHAR(400) PRIMARY KEY, revision_id UUID NOT NULL REFERENCES evaluation_report_revisions(revision_id), pinned_at BIGINT NOT NULL
            );
            """.trimIndent(),
        )
    }

    private fun JdbcTransaction.advisoryLock() {
        exec("SELECT pg_advisory_xact_lock(177)")
    }

    private fun JdbcTransaction.requireNoConcurrentInvocation(now: Long) {
        val activeSince = now - staleAfter.toMillis()
        val count = count(
            "SELECT COUNT(*) FROM llm_launch_reservations WHERE status='RUNNING' AND reserved_at >= ?",
            activeSince,
        )
        check(count == 0L) { "CONCURRENT_INVOCATION" }
    }

    private fun JdbcTransaction.requireReportRateLimit(now: Long) {
        val count = count(
            "SELECT COUNT(*) FROM evaluation_report_jobs WHERE requested_at >= ?",
            now - Duration.ofHours(1).toMillis(),
        )
        check(count < 3L) { "REPORT_RATE_LIMIT" }
    }

    private fun JdbcTransaction.requireLlmHeadroom(now: Long) {
        val hourly = count(
            "SELECT COUNT(*) FROM llm_launch_reservations WHERE reserved_at >= ?",
            now - Duration.ofHours(1).toMillis(),
        )
        val daily = count(
            "SELECT COUNT(*) FROM llm_launch_reservations WHERE reserved_at >= ?",
            now - Duration.ofDays(1).toMillis(),
        )
        check(hourly < (runnerConfig.maxInvocationsPerHour - 1).coerceAtLeast(0)) { "QUOTA_EXHAUSTED" }
        check(daily < (runnerConfig.maxInvocationsPerDay - 1).coerceAtLeast(0)) { "QUOTA_EXHAUSTED" }
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
        now: Long,
    ) {
        jdbcConnection().prepareStatement(
            "INSERT INTO evaluation_report_jobs VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?)",
        ).use { statement ->
            statement.setObject(1, UUID.fromString(job.jobId))
            statement.setObject(2, UUID.fromString(job.revisionId))
            statement.setString(3, scopeKey)
            statement.setString(4, job.status)
            statement.setString(5, job.stage)
            statement.setLong(6, now)
            statement.setLong(7, now)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.insertReservation(job: EvaluationReportJobResponse, now: Long) {
        jdbcConnection().prepareStatement(
            "INSERT INTO llm_launch_reservations (id, invocation_id, trigger_kind, trigger_key, status, reserved_at) VALUES (?, ?, 'EVALUATION_REPORT', ?, 'RUNNING', ?)",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setString(2, job.jobId)
            statement.setString(3, "evaluation-report:${job.revisionId}")
            statement.setLong(4, now)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.finishReservation(job: EvaluationReportJobResponse) {
        jdbcConnection().prepareStatement(
            "UPDATE llm_launch_reservations SET status=?, finished_at=?, reason=? WHERE invocation_id=? AND status='RUNNING'",
        ).use { statement ->
            statement.setString(1, if (job.status == "SUCCEEDED") "FINISHED" else "FAILED")
            statement.setLong(2, clock.instant().toEpochMilli())
            statement.setString(3, job.failureCode)
            statement.setString(4, job.jobId)
            statement.executeUpdate()
        }
    }
}

private val PersistenceJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

private fun JdbcTransaction.jdbcConnection(): java.sql.Connection {
    return connection.connection as java.sql.Connection
}
