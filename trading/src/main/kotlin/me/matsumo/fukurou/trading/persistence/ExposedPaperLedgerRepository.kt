package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.broker.PaperLedgerRepository
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * paper account single row を読む SQL。
 */
private const val SELECT_PAPER_ACCOUNT_SQL = """
    SELECT
        mode,
        initial_cash_jpy,
        cash_jpy,
        btc_quantity,
        btc_mark_price_jpy,
        total_equity_jpy,
        equity_peak_jpy,
        drawdown_ratio
    FROM paper_account
    WHERE id = ?
"""

/**
 * open positions を読む SQL。
 */
private const val SELECT_OPEN_POSITIONS_SQL = """
    SELECT
        id,
        trade_group_id,
        mode,
        symbol,
        side,
        status,
        opened_at,
        closed_at,
        size_btc,
        average_entry_price_jpy,
        current_price_jpy,
        current_stop_loss_jpy,
        current_take_profit_jpy,
        unrealized_pnl_jpy,
        unrealized_r,
        pyramid_add_count,
        highest_price_since_entry_jpy
    FROM positions
    WHERE status = ?
        AND mode = (
            SELECT mode
            FROM paper_account
            WHERE id = ?
        )
    ORDER BY opened_at ASC
"""

/**
 * open orders を読む SQL。
 */
private const val SELECT_OPEN_ORDERS_SQL = """
    SELECT
        id,
        position_id,
        trade_group_id,
        mode,
        symbol,
        side,
        order_type,
        status,
        size_btc,
        limit_price_jpy,
        trigger_price_jpy,
        reason_ja,
        created_at,
        updated_at
    FROM orders
    WHERE status IN (?, ?)
        AND mode = (
            SELECT mode
            FROM paper_account
            WHERE id = ?
        )
    ORDER BY created_at ASC
"""

/**
 * execution ledger を読む SQL。
 */
private const val SELECT_EXECUTIONS_SQL = """
    SELECT
        id,
        order_id,
        position_id,
        mode,
        symbol,
        side,
        price_jpy,
        size_btc,
        fee_jpy,
        realized_pnl_jpy,
        liquidity,
        executed_at
    FROM executions
    WHERE mode = (
        SELECT mode
        FROM paper_account
        WHERE id = ?
    )
    ORDER BY executed_at ASC
"""

/**
 * 指定日実現損益を集計する SQL。
 */
private const val SELECT_REALIZED_PNL_FOR_RANGE_SQL = """
    SELECT COALESCE(SUM(realized_pnl_jpy), 0)
    FROM executions
    WHERE executed_at >= ?
        AND executed_at < ?
        AND mode = (
            SELECT mode
            FROM paper_account
            WHERE id = ?
        )
"""

/**
 * 取引日判定に使う timezone。
 */
private val TradingDateZone = ZoneId.of("Asia/Tokyo")

/**
 * Exposed/JDBC で paper ledger を読む repository。
 *
 * @param database Exposed database
 */
class ExposedPaperLedgerRepository(
    private val database: ExposedDatabase,
) : PaperLedgerRepository {

    override suspend fun getAccountSnapshot(): Result<AccountSnapshot> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectPaperAccount()
                }
            }
        }
    }

    override suspend fun getOpenPositions(): Result<List<Position>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectOpenPositions()
                }
            }
        }
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectOpenOrders()
                }
            }
        }
    }

    override suspend fun getRealizedPnlForDate(date: LocalDate): Result<BigDecimal> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectRealizedPnlForDate(date)
                }
            }
        }
    }

    override suspend fun getExecutions(): Result<List<Execution>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectExecutions()
                }
            }
        }
    }
}

/**
 * paper_account single row を SELECT する。
 */
internal fun JdbcTransaction.selectPaperAccount(): AccountSnapshot {
    return jdbcConnection().prepareStatement(SELECT_PAPER_ACCOUNT_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "paper_account single row was not initialized." }

            resultSet.toAccountSnapshot()
        }
    }
}

private fun JdbcTransaction.selectOpenPositions(): List<Position> {
    return jdbcConnection().prepareStatement(SELECT_OPEN_POSITIONS_SQL).use { statement ->
        statement.setString(1, PositionStatus.OPEN.name)
        statement.setInt(2, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toPosition())
                }
            }
        }
    }
}

private fun JdbcTransaction.selectOpenOrders(): List<Order> {
    return jdbcConnection().prepareStatement(SELECT_OPEN_ORDERS_SQL).use { statement ->
        statement.setString(1, OrderStatus.OPEN.name)
        statement.setString(2, OrderStatus.PENDING_CANCEL.name)
        statement.setInt(3, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toOrder())
                }
            }
        }
    }
}

private fun JdbcTransaction.selectExecutions(): List<Execution> {
    return jdbcConnection().prepareStatement(SELECT_EXECUTIONS_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toExecution())
                }
            }
        }
    }
}

private fun JdbcTransaction.selectRealizedPnlForDate(date: LocalDate): BigDecimal {
    val startAt = date.atStartOfDay(TradingDateZone).toInstant().toEpochMilli()
    val endAt = date.plusDays(1).atStartOfDay(TradingDateZone).toInstant().toEpochMilli()

    return jdbcConnection().prepareStatement(SELECT_REALIZED_PNL_FOR_RANGE_SQL).use { statement ->
        statement.setLong(1, startAt)
        statement.setLong(2, endAt)
        statement.setInt(3, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "realized pnl aggregate did not return a row." }

            resultSet.getBigDecimal(1) ?: BigDecimal.ZERO
        }
    }
}

private fun ResultSet.toAccountSnapshot(): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.valueOf(getString("mode")),
        cashJpy = getBigDecimal("cash_jpy").toPlainString(),
        initialCashJpy = getBigDecimal("initial_cash_jpy").toPlainString(),
        btcQuantity = getBigDecimal("btc_quantity").toPlainString(),
        btcMarkPriceJpy = getBigDecimal("btc_mark_price_jpy").toPlainString(),
        totalEquityJpy = getBigDecimal("total_equity_jpy").toPlainString(),
        equityPeakJpy = getBigDecimal("equity_peak_jpy").toPlainString(),
        drawdownRatio = getBigDecimal("drawdown_ratio").toPlainString(),
    )
}

private fun ResultSet.toPosition(): Position {
    return Position(
        positionId = getUuid("id").toString(),
        tradeGroupId = getUuid("trade_group_id").toString(),
        symbol = getString("symbol"),
        mode = TradingMode.valueOf(getString("mode")),
        side = PositionSide.valueOf(getString("side")),
        status = PositionStatus.valueOf(getString("status")),
        openedAt = getInstant("opened_at").toString(),
        closedAt = getNullableInstant("closed_at")?.toString(),
        sizeBtc = getBigDecimal("size_btc").toPlainString(),
        averageEntryPriceJpy = getBigDecimal("average_entry_price_jpy").toPlainString(),
        currentPriceJpy = getBigDecimal("current_price_jpy").toPlainString(),
        currentStopLossJpy = getNullableBigDecimal("current_stop_loss_jpy")?.toPlainString(),
        currentTakeProfitJpy = getNullableBigDecimal("current_take_profit_jpy")?.toPlainString(),
        unrealizedPnlJpy = getBigDecimal("unrealized_pnl_jpy").toPlainString(),
        unrealizedR = getBigDecimal("unrealized_r").toPlainString(),
        pyramidAddCount = getInt("pyramid_add_count"),
        highestPriceSinceEntryJpy = getBigDecimal("highest_price_since_entry_jpy").toPlainString(),
    )
}

private fun ResultSet.toOrder(): Order {
    return Order(
        orderId = getUuid("id").toString(),
        positionId = getNullableUuid("position_id")?.toString(),
        tradeGroupId = getNullableUuid("trade_group_id")?.toString(),
        symbol = getString("symbol"),
        mode = TradingMode.valueOf(getString("mode")),
        side = OrderSide.valueOf(getString("side")),
        orderType = OrderType.valueOf(getString("order_type")),
        status = OrderStatus.valueOf(getString("status")),
        sizeBtc = getBigDecimal("size_btc").toPlainString(),
        limitPriceJpy = getNullableBigDecimal("limit_price_jpy")?.toPlainString(),
        triggerPriceJpy = getNullableBigDecimal("trigger_price_jpy")?.toPlainString(),
        reasonJa = getString("reason_ja"),
        createdAt = getInstant("created_at").toString(),
        updatedAt = getInstant("updated_at").toString(),
    )
}

private fun ResultSet.toExecution(): Execution {
    return Execution(
        executionId = getUuid("id").toString(),
        orderId = getNullableUuid("order_id")?.toString(),
        positionId = getNullableUuid("position_id")?.toString(),
        symbol = getString("symbol"),
        mode = TradingMode.valueOf(getString("mode")),
        side = OrderSide.valueOf(getString("side")),
        priceJpy = getBigDecimal("price_jpy").toPlainString(),
        sizeBtc = getBigDecimal("size_btc").toPlainString(),
        feeJpy = getBigDecimal("fee_jpy").toPlainString(),
        realizedPnlJpy = getBigDecimal("realized_pnl_jpy").toPlainString(),
        liquidity = ExecutionLiquidity.valueOf(getString("liquidity")),
        executedAt = getInstant("executed_at").toString(),
    )
}

private fun ResultSet.getUuid(columnName: String): UUID {
    return getObject(columnName, UUID::class.java)
}

private fun ResultSet.getNullableUuid(columnName: String): UUID? {
    val value = getObject(columnName, UUID::class.java)

    return if (wasNull()) null else value
}

private fun ResultSet.getInstant(columnName: String): Instant {
    return Instant.ofEpochMilli(getLong(columnName))
}

private fun ResultSet.getNullableInstant(columnName: String): Instant? {
    val epochMillis = getLong(columnName)

    return if (wasNull()) null else Instant.ofEpochMilli(epochMillis)
}

private fun ResultSet.getNullableBigDecimal(columnName: String): BigDecimal? {
    val value = getBigDecimal(columnName)

    return if (wasNull()) null else value
}
