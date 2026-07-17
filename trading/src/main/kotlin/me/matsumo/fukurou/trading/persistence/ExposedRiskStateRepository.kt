package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.risk.HardHaltCleanupIncompleteException
import me.matsumo.fukurou.trading.risk.HardHaltCleanupState
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.risk.SoftHaltDowngradeRejectedException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * risk_state single row を読む SQL。
 */
private const val SELECT_RISK_STATE_SQL = """
    SELECT
        state,
        drawdown_ratio,
        equity_peak,
        halt_reason,
        halt_at,
        resumed_at,
        resumed_reason,
        hard_halt_cleanup_state,
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
        state = ?,
        hard_halt = ?,
        halt_reason = ?,
        halt_at = ?,
        hard_halt_cleanup_state = CASE
            WHEN state = 'HARD_HALT' THEN hard_halt_cleanup_state
            ELSE 'UNKNOWN'
        END,
        updated_at = ?
    WHERE id = ?
"""

/**
 * SOFT_HALT を更新する SQL。
 */
private const val UPDATE_SOFT_HALT_SQL = """
    UPDATE risk_state
    SET
        state = ?,
        hard_halt = ?,
        halt_reason = ?,
        halt_at = ?,
        hard_halt_cleanup_state = NULL,
        updated_at = ?
    WHERE id = ?
"""

/**
 * 手動再開を更新する SQL。
 */
private const val UPDATE_RESUME_SQL = """
    UPDATE risk_state
    SET
        state = ?,
        hard_halt = ?,
        resumed_at = ?,
        resumed_reason = ?,
        hard_halt_cleanup_state = NULL,
        updated_at = ?
    WHERE id = ?
"""

/**
 * Exposed/JDBC で risk_state single row を扱う repository。
 *
 * @param database Exposed database
 */
class ExposedRiskStateRepository(
    private val database: ExposedDatabase,
) : RiskStateRepository {

    override suspend fun current(): Result<RiskState> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
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

    override suspend fun setSoftHalt(reason: String, at: Instant): Result<RiskState> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(reason.isNotBlank()) { "SOFT_HALT reason is required." }

                exposedTransaction(database) {
                    ensureRiskStateRow(at)
                    val currentState = selectRiskState(forUpdate = true)

                    if (currentState.state == RiskHaltState.HARD_HALT) {
                        throw SoftHaltDowngradeRejectedException()
                    }

                    updateSoftHalt(reason, at)
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
                    val currentState = selectRiskState(forUpdate = true)
                    val hasOpenRisk = hasOpenPaperRisk()

                    if (currentState.state == RiskHaltState.HARD_HALT) {
                        val cleanupIsSafe = currentState.hardHaltCleanupState == HardHaltCleanupState.SAFE

                        if (!cleanupIsSafe || hasOpenRisk) {
                            if (cleanupIsSafe && hasOpenRisk) updateHardHaltCleanupState(HardHaltCleanupState.UNKNOWN)

                            return@exposedTransaction ResumeRiskStateResult.Rejected
                        }
                    }

                    updateResume(reason, at)
                    ResumeRiskStateResult.Resumed(selectRiskState(forUpdate = true))
                }.let { result ->
                    when (result) {
                        is ResumeRiskStateResult.Resumed -> result.state
                        ResumeRiskStateResult.Rejected -> throw HardHaltCleanupIncompleteException()
                    }
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
        statement.setString(1, RiskHaltState.HARD_HALT.name)
        statement.setBoolean(2, true)
        statement.setString(3, reason)
        statement.setLong(4, at.toEpochMilli())
        statement.setLong(5, at.toEpochMilli())
        statement.setInt(6, RISK_STATE_SINGLE_ROW_ID)
        statement.executeUpdate()
    }
}

/**
 * SOFT_HALT を reason 付きで更新する。
 */
internal fun JdbcTransaction.updateSoftHalt(reason: String, at: Instant) {
    jdbcConnection().prepareStatement(UPDATE_SOFT_HALT_SQL).use { statement ->
        statement.setString(1, RiskHaltState.SOFT_HALT.name)
        statement.setBoolean(2, false)
        statement.setString(3, reason)
        statement.setLong(4, at.toEpochMilli())
        statement.setLong(5, at.toEpochMilli())
        statement.setInt(6, RISK_STATE_SINGLE_ROW_ID)
        statement.executeUpdate()
    }
}

/**
 * 手動再開を reason 付きで更新する。
 */
internal fun JdbcTransaction.updateResume(reason: String, at: Instant) {
    jdbcConnection().prepareStatement(UPDATE_RESUME_SQL).use { statement ->
        statement.setString(1, RiskHaltState.RUNNING.name)
        statement.setBoolean(2, false)
        statement.setLong(3, at.toEpochMilli())
        statement.setString(4, reason)
        statement.setLong(5, at.toEpochMilli())
        statement.setInt(6, RISK_STATE_SINGLE_ROW_ID)
        statement.executeUpdate()
    }
}

/**
 * ResultSet の現在行を RiskState へ変換する。
 */
private fun ResultSet.toRiskState(): RiskState {
    val state = RiskHaltState.valueOf(getString("state"))
    val cleanupState = getString("hard_halt_cleanup_state")
        ?.let(HardHaltCleanupState::valueOf)
        ?: HardHaltCleanupState.UNKNOWN.takeIf { state == RiskHaltState.HARD_HALT }

    return RiskState(
        state = state,
        drawdownRatio = getBigDecimal("drawdown_ratio") ?: BigDecimal.ZERO,
        equityPeak = getBigDecimal("equity_peak") ?: BigDecimal.ZERO,
        haltReason = getString("halt_reason"),
        haltAt = nullableInstant("halt_at"),
        resumedAt = nullableInstant("resumed_at"),
        resumedReason = getString("resumed_reason"),
        hardHaltCleanupState = cleanupState,
        updatedAt = Instant.ofEpochMilli(getLong("updated_at")),
    )
}

internal fun JdbcTransaction.hasOpenPaperRisk(): Boolean {
    return prepare(
        """
            SELECT EXISTS (
                SELECT 1 FROM positions WHERE status = 'OPEN'
                UNION ALL
                SELECT 1 FROM orders
                WHERE status IN ('OPEN', 'PENDING_CANCEL') AND side = 'BUY'
            )
        """,
    ).use { statement ->
        statement.executeQuery().use { rows ->
            check(rows.next())
            rows.getBoolean(1)
        }
    }
}

internal fun JdbcTransaction.updateHardHaltCleanupState(state: HardHaltCleanupState?) {
    prepare("UPDATE risk_state SET hard_halt_cleanup_state = ? WHERE id = ?").use { statement ->
        statement.setString(1, state?.name)
        statement.setInt(2, RISK_STATE_SINGLE_ROW_ID)
        check(statement.executeUpdate() == 1)
    }
}

private sealed interface ResumeRiskStateResult {
    data class Resumed(val state: RiskState) : ResumeRiskStateResult
    data object Rejected : ResumeRiskStateResult
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
