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
        error_message
    )
    VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
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
        error_message
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (invocation_id) DO UPDATE
    SET
        status = EXCLUDED.status,
        finished_at = EXCLUDED.finished_at,
        error_message = EXCLUDED.error_message
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
        error_message
    FROM llm_runs
    WHERE invocation_id = ?
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
}

private fun JdbcTransaction.insertRunningLlmRun(start: LlmRunStart) {
    jdbcConnection().prepareStatement(INSERT_LLM_RUN_RUNNING_SQL).use { statement ->
        statement.setString(1, start.invocationId)
        statement.setString(2, start.mode.name)
        statement.setString(3, start.symbol.apiSymbol)
        statement.setString(4, start.triggerKind?.name)
        statement.setString(5, LLM_RUN_STATUS_RUNNING)
        statement.setLong(6, start.startedAt.toEpochMilli())
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
    )
}
