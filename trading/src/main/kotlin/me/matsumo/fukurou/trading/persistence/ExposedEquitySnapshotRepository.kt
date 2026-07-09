package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotReason
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecord
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRepository
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * equity_snapshots を append-only で追加する SQL。
 */
private const val INSERT_EQUITY_SNAPSHOT_SQL = """
    INSERT INTO equity_snapshots (
        id,
        mode,
        reason,
        trading_date,
        captured_at,
        cash_jpy,
        btc_quantity,
        btc_mark_price_jpy,
        total_equity_jpy,
        equity_peak_jpy,
        drawdown_ratio
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * DAILY equity snapshot を日次一意で追加する SQL。
 */
private const val INSERT_DAILY_EQUITY_SNAPSHOT_SQL = """
    INSERT INTO equity_snapshots (
        id,
        mode,
        reason,
        trading_date,
        captured_at,
        cash_jpy,
        btc_quantity,
        btc_mark_price_jpy,
        total_equity_jpy,
        equity_peak_jpy,
        drawdown_ratio
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (mode, trading_date) WHERE reason = 'DAILY' DO NOTHING
"""

/**
 * equity_snapshots を captured_at 昇順で読む SQL。
 */
private const val SELECT_EQUITY_SNAPSHOTS_SQL = """
    SELECT
        id,
        mode,
        reason,
        trading_date,
        captured_at,
        cash_jpy,
        btc_quantity,
        btc_mark_price_jpy,
        total_equity_jpy,
        equity_peak_jpy,
        drawdown_ratio
    FROM equity_snapshots
    ORDER BY captured_at ASC, id ASC
"""

/**
 * Exposed/JDBC で equity_snapshots を保存する repository。
 *
 * @param database Exposed database
 */
class ExposedEquitySnapshotRepository(
    private val database: ExposedDatabase,
) : EquitySnapshotRepository {

    override suspend fun append(snapshot: EquitySnapshotRecord): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertEquitySnapshot(snapshot)
                }
            }
        }
    }

    override suspend fun appendDailyIfAbsent(snapshot: EquitySnapshotRecord): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertDailyEquitySnapshotIfAbsent(snapshot)
                }
            }
        }
    }

    override suspend fun findAll(): Result<List<EquitySnapshotRecord>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectEquitySnapshots()
                }
            }
        }
    }
}

/**
 * equity snapshot を追加する。
 */
internal fun JdbcTransaction.insertEquitySnapshot(snapshot: EquitySnapshotRecord) {
    jdbcConnection().prepareStatement(INSERT_EQUITY_SNAPSHOT_SQL).use { statement ->
        statement.bindEquitySnapshot(snapshot)
        statement.executeUpdate()
    }
}

/**
 * DAILY equity snapshot を日次一意で追加する。
 */
private fun JdbcTransaction.insertDailyEquitySnapshotIfAbsent(snapshot: EquitySnapshotRecord) {
    jdbcConnection().prepareStatement(INSERT_DAILY_EQUITY_SNAPSHOT_SQL).use { statement ->
        statement.bindEquitySnapshot(snapshot)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.selectEquitySnapshots(): List<EquitySnapshotRecord> {
    return jdbcConnection().prepareStatement(SELECT_EQUITY_SNAPSHOTS_SQL).use { statement ->
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toEquitySnapshotRecord())
                }
            }
        }
    }
}

private fun PreparedStatement.bindEquitySnapshot(snapshot: EquitySnapshotRecord) {
    setObject(1, snapshot.id)
    setString(2, snapshot.mode.name)
    setString(3, snapshot.reason.name)
    setString(4, snapshot.tradingDate.toString())
    setLong(5, snapshot.capturedAt.toEpochMilli())
    setBigDecimal(6, snapshot.cashJpy)
    setBigDecimal(7, snapshot.btcQuantity)
    setBigDecimal(8, snapshot.btcMarkPriceJpy)
    setBigDecimal(9, snapshot.totalEquityJpy)
    setBigDecimal(10, snapshot.equityPeakJpy)
    setBigDecimal(11, snapshot.drawdownRatio)
}

private fun ResultSet.toEquitySnapshotRecord(): EquitySnapshotRecord {
    return EquitySnapshotRecord(
        id = getObject("id", UUID::class.java),
        mode = TradingMode.valueOf(getString("mode")),
        reason = EquitySnapshotReason.valueOf(getString("reason")),
        tradingDate = LocalDate.parse(getString("trading_date")),
        capturedAt = Instant.ofEpochMilli(getLong("captured_at")),
        cashJpy = getBigDecimal("cash_jpy"),
        btcQuantity = getBigDecimal("btc_quantity"),
        btcMarkPriceJpy = getBigDecimal("btc_mark_price_jpy"),
        totalEquityJpy = getBigDecimal("total_equity_jpy"),
        equityPeakJpy = getBigDecimal("equity_peak_jpy"),
        drawdownRatio = getBigDecimal("drawdown_ratio"),
    )
}
