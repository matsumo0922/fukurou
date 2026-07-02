package me.matsumo.fukurou.trading.persistence

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.math.BigDecimal
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * risk_state 初期行を作る SQL。
 */
private const val INSERT_DEFAULT_RISK_STATE_SQL = """
    INSERT INTO risk_state (
        id,
        hard_halt,
        drawdown_ratio,
        equity_peak,
        updated_at
    )
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT (id) DO NOTHING
"""

/**
 * command_event_log schema の存在を確認する SQL。
 */
private const val VERIFY_COMMAND_EVENT_LOG_SCHEMA_SQL = """
    SELECT
        id,
        decision_run_id,
        tool_call_id,
        client_request_id,
        tool_name,
        event_type,
        payload,
        ts,
        llm_provider,
        prompt_hash,
        system_prompt_version,
        market_snapshot_id
    FROM command_event_log
    LIMIT 0
"""

/**
 * trading persistence の最小 schema を起動時に用意する bootstrapper。
 *
 * @param database Exposed database
 * @param clock 初期 risk_state の updatedAt に使う clock
 */
class TradingPersistenceBootstrap(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Exposed table と risk_state single row を用意する。
     */
    fun ensureSchema(): Result<Unit> {
        return runCatching {
            exposedTransaction(database) {
                @Suppress("DEPRECATION")
                SchemaUtils.createMissingTablesAndColumns(
                    RiskStateTable,
                    CommandEventLogTable,
                    withLogs = false,
                )
                ensureRiskStateRow(Instant.now(clock))
            }
        }
    }

    /**
     * schema と risk_state single row が backend bootstrap 済みであることだけを確認する。
     */
    fun verifySchema(): Result<Unit> {
        return runCatching {
            exposedTransaction(database) {
                verifyCommandEventLogSchema()
                selectRiskState(forUpdate = false)
            }
        }
    }
}

/**
 * command_event_log schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyCommandEventLogSchema() {
    jdbcConnection().prepareStatement(VERIFY_COMMAND_EVENT_LOG_SCHEMA_SQL).use { statement ->
        statement.executeQuery().use { resultSet ->
            requireNotNull(resultSet.metaData) { "command_event_log schema was not initialized." }
        }
    }
}

/**
 * risk_state single row がなければ作成する。
 */
internal fun JdbcTransaction.ensureRiskStateRow(now: Instant) {
    jdbcConnection().prepareStatement(INSERT_DEFAULT_RISK_STATE_SQL).use { statement ->
        statement.setInt(1, RISK_STATE_SINGLE_ROW_ID)
        statement.setBoolean(2, false)
        statement.setBigDecimal(3, BigDecimal.ZERO)
        statement.setBigDecimal(4, BigDecimal.ZERO)
        statement.setLong(5, now.toEpochMilli())
        statement.executeUpdate()
    }
}

/**
 * Exposed transaction が持つ JDBC connection を返す。
 */
internal fun JdbcTransaction.jdbcConnection(): Connection {
    return connection.connection as Connection
}
