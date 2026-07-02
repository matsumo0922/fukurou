package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * risk_state single row を読む SQL。
 */
private const val SELECT_RISK_STATE_SQL = """
    SELECT
        hard_halt,
        drawdown_ratio,
        equity_peak,
        halt_reason,
        halt_at,
        resumed_at,
        resumed_reason,
        updated_at
    FROM risk_state
    WHERE id = ?
"""

/**
 * risk_state single row を row lock 付きで読む SQL。
 */
private const val SELECT_RISK_STATE_FOR_UPDATE_SQL = "$SELECT_RISK_STATE_SQL FOR UPDATE"

/**
 * HARD_HALT を更新する SQL。
 */
private const val UPDATE_HARD_HALT_SQL = """
    UPDATE risk_state
    SET
        hard_halt = ?,
        halt_reason = ?,
        halt_at = ?,
        updated_at = ?
    WHERE id = ?
"""

/**
 * 手動再開を更新する SQL。
 */
private const val UPDATE_RESUME_SQL = """
    UPDATE risk_state
    SET
        hard_halt = ?,
        resumed_at = ?,
        resumed_reason = ?,
        updated_at = ?
    WHERE id = ?
"""

/**
 * Exposed/JDBC で risk_state single row を扱う repository。
 *
 * @param database Exposed database
 * @param clock row が存在しない場合の初期 updatedAt に使う clock
 */
class ExposedRiskStateRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : RiskStateRepository {

    override suspend fun current(): Result<RiskState> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    ensureRiskStateRow(Instant.now(clock))
                    selectRiskState(forUpdate = false)
                }
            }
        }
    }

    override suspend fun setHardHalt(reason: String, at: Instant): Result<RiskState> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(reason.isNotBlank()) { "HARD_HALT reason is required." }

                exposedTransaction(database) {
                    ensureRiskStateRow(at)
                    selectRiskState(forUpdate = true)
                    updateHardHalt(reason, at)
                    selectRiskState(forUpdate = true)
                }
            }
        }
    }

    override suspend fun resume(reason: String, at: Instant): Result<RiskState> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(reason.isNotBlank()) { "manual resume reason is required." }

                exposedTransaction(database) {
                    ensureRiskStateRow(at)
                    selectRiskState(forUpdate = true)
                    updateResume(reason, at)
                    selectRiskState(forUpdate = true)
                }
            }
        }
    }
}

/**
 * risk_state を SELECT する。
 */
internal fun JdbcTransaction.selectRiskState(forUpdate: Boolean): RiskState {
    val sql = if (forUpdate) SELECT_RISK_STATE_FOR_UPDATE_SQL else SELECT_RISK_STATE_SQL

    return jdbcConnection().prepareStatement(sql).use { statement ->
        statement.setInt(1, RISK_STATE_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "risk_state single row was not initialized." }

            resultSet.toRiskState()
        }
    }
}

/**
 * HARD_HALT を reason 付きで更新する。
 */
internal fun JdbcTransaction.updateHardHalt(reason: String, at: Instant) {
    jdbcConnection().prepareStatement(UPDATE_HARD_HALT_SQL).use { statement ->
        statement.setBoolean(1, true)
        statement.setString(2, reason)
        statement.setLong(3, at.toEpochMilli())
        statement.setLong(4, at.toEpochMilli())
        statement.setInt(5, RISK_STATE_SINGLE_ROW_ID)
        statement.executeUpdate()
    }
}

/**
 * 手動再開を reason 付きで更新する。
 */
internal fun JdbcTransaction.updateResume(reason: String, at: Instant) {
    jdbcConnection().prepareStatement(UPDATE_RESUME_SQL).use { statement ->
        statement.setBoolean(1, false)
        statement.setLong(2, at.toEpochMilli())
        statement.setString(3, reason)
        statement.setLong(4, at.toEpochMilli())
        statement.setInt(5, RISK_STATE_SINGLE_ROW_ID)
        statement.executeUpdate()
    }
}

/**
 * ResultSet の現在行を RiskState へ変換する。
 */
private fun ResultSet.toRiskState(): RiskState {
    return RiskState(
        hardHalt = getBoolean("hard_halt"),
        drawdownRatio = getBigDecimal("drawdown_ratio") ?: BigDecimal.ZERO,
        equityPeak = getBigDecimal("equity_peak") ?: BigDecimal.ZERO,
        haltReason = getString("halt_reason"),
        haltAt = nullableInstant("halt_at"),
        resumedAt = nullableInstant("resumed_at"),
        resumedReason = getString("resumed_reason"),
        updatedAt = Instant.ofEpochMilli(getLong("updated_at")),
    )
}

/**
 * nullable epoch millis column を Instant へ変換する。
 */
private fun ResultSet.nullableInstant(columnName: String): Instant? {
    val epochMillis = getLong(columnName)

    if (wasNull()) {
        return null
    }

    return Instant.ofEpochMilli(epochMillis)
}
