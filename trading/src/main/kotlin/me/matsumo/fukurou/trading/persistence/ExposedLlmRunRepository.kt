package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_RUNNING
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunRecord
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.ResultSet
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * llm_runs の RUNNING 行を追加する SQL。
 */
private const val INSERT_LLM_RUN_RUNNING_SQL = """
    INSERT INTO llm_runs (
        invocation_id,
        mode,
        symbol,
        trigger_kind,
        status,
        started_at,
        finished_at,
        error_message,
        terminal_cause,
        runtime_config_version_id,
        runtime_config_hash
    )
    VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?)
    ON CONFLICT (invocation_id) DO NOTHING
"""

/**
 * llm_runs の終了状態を upsert する SQL。
 */
private const val UPSERT_LLM_RUN_FINISH_SQL = """
    INSERT INTO llm_runs (
        invocation_id,
        mode,
        symbol,
        trigger_kind,
        status,
        started_at,
        finished_at,
        error_message,
        terminal_cause,
        runtime_config_version_id,
        runtime_config_hash
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (invocation_id) DO UPDATE
    SET
        status = EXCLUDED.status,
        finished_at = EXCLUDED.finished_at,
        error_message = EXCLUDED.error_message,
        terminal_cause = EXCLUDED.terminal_cause,
        runtime_config_version_id = EXCLUDED.runtime_config_version_id,
        runtime_config_hash = EXCLUDED.runtime_config_hash
"""

/**
 * invocation_id で llm_runs を読む SQL。
 */
private const val SELECT_LLM_RUN_BY_INVOCATION_ID_SQL = """
    SELECT
        invocation_id,
        mode,
        symbol,
        trigger_kind,
        status,
        started_at,
        finished_at,
        error_message,
        terminal_cause,
        runtime_config_version_id,
        runtime_config_hash
    FROM llm_runs
    WHERE invocation_id = ?
"""

/**
 * started_at 範囲で llm_runs を読む SQL。
 */
private const val SELECT_LLM_RUNS_STARTED_BETWEEN_SQL = """
    SELECT
        latest_runs.invocation_id,
        latest_runs.mode,
        latest_runs.symbol,
        latest_runs.trigger_kind,
        latest_runs.status,
        latest_runs.started_at,
        latest_runs.finished_at,
        latest_runs.error_message,
        latest_runs.runtime_config_version_id,
        latest_runs.runtime_config_hash
    FROM (
        SELECT
            invocation_id,
            mode,
            symbol,
            trigger_kind,
            status,
            started_at,
        finished_at,
        error_message,
        terminal_cause,
        runtime_config_version_id,
            runtime_config_hash
        FROM llm_runs
        WHERE started_at >= ?
            AND started_at < ?
        ORDER BY started_at DESC
        LIMIT ?
    ) latest_runs
    ORDER BY latest_runs.started_at ASC
"""

/**
 * Exposed/JDBC で llm_runs を保存する repository。
 *
 * @param database Exposed database
 */
class ExposedLlmRunRepository(
    private val database: ExposedDatabase,
) : LlmRunRepository {

    override suspend fun insertRunning(start: LlmRunStart): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertRunningLlmRun(start)
                }
            }
        }
    }

    override suspend fun finish(finish: LlmRunFinish): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    finishLlmRun(finish)
                }
            }
        }
    }

    override suspend fun findByInvocationId(invocationId: String): Result<LlmRunRecord?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectLlmRun(invocationId)
                }
            }
        }
    }

    override suspend fun findRunsStartedBetween(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): Result<List<LlmRunRecord>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(limit > 0) {
                    "limit must be greater than 0."
                }

                exposedTransaction(database) {
                    selectLlmRunsStartedBetween(
                        from = from,
                        toExclusive = toExclusive,
                        limit = limit,
                    )
                }
            }
        }
    }
}

private fun JdbcTransaction.insertRunningLlmRun(start: LlmRunStart) {
    jdbcConnection().prepareStatement(INSERT_LLM_RUN_RUNNING_SQL).use { statement ->
        statement.setString(1, start.invocationId)
        statement.setString(2, start.mode.name)
        statement.setString(3, start.symbol.apiSymbol)
        statement.setString(4, start.triggerKind?.name)
        statement.setString(5, LLM_RUN_STATUS_RUNNING)
        statement.setLong(6, start.startedAt.toEpochMilli())
        statement.setString(7, start.runtimeConfigVersionId)
        statement.setString(8, start.runtimeConfigHash)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.finishLlmRun(finish: LlmRunFinish) {
    jdbcConnection().prepareStatement(UPSERT_LLM_RUN_FINISH_SQL).use { statement ->
        statement.setString(1, finish.invocationId)
        statement.setString(2, finish.mode.name)
        statement.setString(3, finish.symbol.apiSymbol)
        statement.setString(4, finish.triggerKind?.name)
        statement.setString(5, finish.status)
        statement.setLong(6, finish.startedAt.toEpochMilli())
        statement.setLong(7, finish.finishedAt.toEpochMilli())
        statement.setString(8, finish.errorMessage)
        statement.setString(9, finish.terminalCause.name)
        statement.setString(10, finish.runtimeConfigVersionId)
        statement.setString(11, finish.runtimeConfigHash)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.selectLlmRun(invocationId: String): LlmRunRecord? {
    return jdbcConnection().prepareStatement(SELECT_LLM_RUN_BY_INVOCATION_ID_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) {
                resultSet.toLlmRunRecord()
            } else {
                null
            }
        }
    }
}

private fun JdbcTransaction.selectLlmRunsStartedBetween(
    from: Instant,
    toExclusive: Instant,
    limit: Int,
): List<LlmRunRecord> {
    return jdbcConnection().prepareStatement(SELECT_LLM_RUNS_STARTED_BETWEEN_SQL).use { statement ->
        statement.setLong(1, from.toEpochMilli())
        statement.setLong(2, toExclusive.toEpochMilli())
        statement.setInt(3, limit)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toLlmRunRecord())
                }
            }
        }
    }
}

private fun ResultSet.toLlmRunRecord(): LlmRunRecord {
    val finishedAtMillis = getLong("finished_at")
    val finishedAt = if (wasNull()) null else Instant.ofEpochMilli(finishedAtMillis)

    return LlmRunRecord(
        invocationId = getString("invocation_id"),
        mode = TradingMode.valueOf(getString("mode")),
        symbol = TradingSymbol.entries.first { symbol -> symbol.apiSymbol == getString("symbol") },
        triggerKind = getString("trigger_kind")?.let { value -> LlmDaemonTriggerKind.valueOf(value) },
        status = getString("status"),
        startedAt = Instant.ofEpochMilli(getLong("started_at")),
        finishedAt = finishedAt,
        errorMessage = getString("error_message"),
        terminalCause = getString("terminal_cause")?.let(LlmRunTerminalCause::valueOf),
        runtimeConfigVersionId = getString("runtime_config_version_id"),
        runtimeConfigHash = getString("runtime_config_hash"),
    )
}
