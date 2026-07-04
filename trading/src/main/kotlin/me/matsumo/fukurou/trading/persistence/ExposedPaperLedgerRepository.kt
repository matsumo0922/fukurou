package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.FillSimulator
import me.matsumo.fukurou.trading.broker.IntentConsumingPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.PaperReconcileResult
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.SimulatedFill
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.config.PaperMarketConfig
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
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
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
 * paper account single row の updated_at を読む SQL。
 */
private const val SELECT_PAPER_ACCOUNT_UPDATED_AT_SQL = """
    SELECT
        updated_at
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
        highest_price_since_entry_jpy,
        lowest_price_since_entry_jpy
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
        intent_id,
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
        protective_stop_price_jpy,
        take_profit_price_jpy,
        estimated_win_probability,
        reason_ja,
        client_request_id,
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
 * client_request_id に対応する orders を読む SQL。
 */
private const val SELECT_ORDERS_BY_CLIENT_REQUEST_ID_SQL = """
    SELECT
        id,
        intent_id,
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
        protective_stop_price_jpy,
        take_profit_price_jpy,
        estimated_win_probability,
        reason_ja,
        client_request_id,
        created_at,
        updated_at
    FROM orders
    WHERE client_request_id = ?
        AND mode = (
            SELECT mode
            FROM paper_account
            WHERE id = ?
        )
    ORDER BY created_at ASC
"""

/**
 * trade_group_id に対応する orders を読む SQL。
 */
private const val SELECT_ORDERS_BY_TRADE_GROUP_ID_SQL = """
    SELECT
        id,
        intent_id,
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
        protective_stop_price_jpy,
        take_profit_price_jpy,
        estimated_win_probability,
        reason_ja,
        client_request_id,
        created_at,
        updated_at
    FROM orders
    WHERE trade_group_id = ?
        AND mode = (
            SELECT mode
            FROM paper_account
            WHERE id = ?
        )
    ORDER BY created_at ASC
"""

/**
 * trade_group_id に対応する positions を読む SQL。
 */
private const val SELECT_POSITIONS_BY_TRADE_GROUP_ID_SQL = """
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
        highest_price_since_entry_jpy,
        lowest_price_since_entry_jpy
    FROM positions
    WHERE trade_group_id = ?
        AND mode = (
            SELECT mode
            FROM paper_account
            WHERE id = ?
        )
    ORDER BY opened_at ASC
"""

/**
 * order IDs に対応する executions を読む SQL prefix。
 */
private const val SELECT_EXECUTIONS_BY_ORDER_IDS_PREFIX = """
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
    WHERE order_id IN (
"""

/**
 * order IDs に対応する executions を読む SQL suffix。
 */
private const val SELECT_EXECUTIONS_BY_ORDER_IDS_SUFFIX = """
    )
        AND mode = (
            SELECT mode
            FROM paper_account
            WHERE id = ?
        )
    ORDER BY executed_at ASC
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
 * @param fallbackSymbolRules tick に symbol rules がない場合の fallback 取引ルール
 */
class ExposedPaperLedgerRepository(
    private val database: ExposedDatabase,
    fallbackSymbolRules: SymbolRules = PaperMarketConfig().toSymbolRules(TradingSymbol.BTC),
) : IntentConsumingPaperLedgerRepository {

    private val writer = ExposedPaperLedgerWriter(database, fallbackSymbolRules = fallbackSymbolRules)

    override suspend fun getAccountSnapshot(): Result<AccountSnapshot> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectPaperAccount()
                }
            }
        }
    }

    override suspend fun getAccountUpdatedAt(): Result<Instant> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectPaperAccountUpdatedAt()
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

    override suspend fun findPlaceOrderResultByClientRequestId(clientRequestId: String): Result<PaperTradeResult?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    findPlaceOrderResultByClientRequestId(clientRequestId)
                }
            }
        }
    }

    override suspend fun fillMarketEntry(
        command: PlaceOrderCommand,
        fill: SimulatedFill,
        positionId: UUID,
        tradeGroupId: UUID,
        stopOrderId: UUID,
    ): Result<PaperTradeResult> {
        return writer.fillMarketEntry(command, fill, positionId, tradeGroupId, stopOrderId)
    }

    override suspend fun createRestingEntryOrder(
        command: PlaceOrderCommand,
        orderId: UUID,
        tradeGroupId: UUID,
    ): Result<PaperTradeResult> {
        return writer.createRestingEntryOrder(command, orderId, tradeGroupId)
    }

    override suspend fun fillMarketEntryAndConsumeIntent(
        command: PlaceOrderCommand,
        fill: SimulatedFill,
        positionId: UUID,
        tradeGroupId: UUID,
        stopOrderId: UUID,
        intentId: UUID,
        consumedAt: Instant,
    ): Result<PaperTradeResult> {
        return writer.fillMarketEntryAndConsumeIntent(
            command = command,
            fill = fill,
            positionId = positionId,
            tradeGroupId = tradeGroupId,
            stopOrderId = stopOrderId,
            intentId = intentId,
            consumedAt = consumedAt,
        )
    }

    override suspend fun createRestingEntryOrderAndConsumeIntent(
        command: PlaceOrderCommand,
        orderId: UUID,
        tradeGroupId: UUID,
        intentId: UUID,
        consumedAt: Instant,
    ): Result<PaperTradeResult> {
        return writer.createRestingEntryOrderAndConsumeIntent(
            command = command,
            orderId = orderId,
            tradeGroupId = tradeGroupId,
            intentId = intentId,
            consumedAt = consumedAt,
        )
    }

    override suspend fun closePosition(
        command: ClosePositionCommand,
        positionId: UUID,
        orderId: UUID,
        fill: SimulatedFill,
    ): Result<PaperTradeResult> {
        return writer.closePosition(command, positionId, orderId, fill)
    }

    override suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult> {
        return writer.updateProtection(command)
    }

    override suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult> {
        return writer.cancelOrder(command)
    }

    override suspend fun reconcile(tickSnapshot: TickSnapshot, simulator: FillSimulator): Result<PaperReconcileResult> {
        return writer.reconcile(tickSnapshot, simulator)
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

internal fun JdbcTransaction.selectPaperAccountUpdatedAt(): Instant {
    return jdbcConnection().prepareStatement(SELECT_PAPER_ACCOUNT_UPDATED_AT_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "paper_account single row was not initialized." }

            resultSet.getInstant("updated_at")
        }
    }
}

internal fun JdbcTransaction.selectOpenPositions(): List<Position> {
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

internal fun JdbcTransaction.selectOpenOrders(): List<Order> {
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

private fun JdbcTransaction.findPlaceOrderResultByClientRequestId(clientRequestId: String): PaperTradeResult? {
    val ordersByClientRequestId = selectOrdersByClientRequestId(clientRequestId)
    val entryOrder = ordersByClientRequestId.firstOrNull { order -> order.side == OrderSide.BUY }
        ?: return null
    val tradeGroupId = requireNotNull(entryOrder.tradeGroupId)
    val relatedOrders = selectOrdersByTradeGroupId(tradeGroupId)
    val relatedOrderIds = relatedOrders.map { order -> order.orderId }
    val relatedPositionIds = selectPositionsByTradeGroupId(tradeGroupId)
        .map { position -> position.positionId }
    val relatedExecutionIds = selectExecutionsByOrderIds(relatedOrderIds)
        .map { execution -> execution.executionId }

    return PaperTradeResult(
        accepted = true,
        status = entryOrder.status,
        orderIds = relatedOrderIds,
        positionIds = relatedPositionIds,
        executionIds = relatedExecutionIds,
        messageJa = "client_request_id に一致する既存 paper entry を返しました。",
    )
}

private fun JdbcTransaction.selectOrdersByClientRequestId(clientRequestId: String): List<Order> {
    return jdbcConnection().prepareStatement(SELECT_ORDERS_BY_CLIENT_REQUEST_ID_SQL).use { statement ->
        statement.setString(1, clientRequestId)
        statement.setInt(2, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet -> resultSet.toOrders() }
    }
}

private fun JdbcTransaction.selectOrdersByTradeGroupId(tradeGroupId: String): List<Order> {
    return jdbcConnection().prepareStatement(SELECT_ORDERS_BY_TRADE_GROUP_ID_SQL).use { statement ->
        statement.setObject(1, UUID.fromString(tradeGroupId))
        statement.setInt(2, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet -> resultSet.toOrders() }
    }
}

private fun JdbcTransaction.selectPositionsByTradeGroupId(tradeGroupId: String): List<Position> {
    return jdbcConnection().prepareStatement(SELECT_POSITIONS_BY_TRADE_GROUP_ID_SQL).use { statement ->
        statement.setObject(1, UUID.fromString(tradeGroupId))
        statement.setInt(2, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet -> resultSet.toPositions() }
    }
}

private fun JdbcTransaction.selectExecutionsByOrderIds(orderIds: List<String>): List<Execution> {
    if (orderIds.isEmpty()) {
        return emptyList()
    }

    val placeholders = orderIds.joinToString(separator = ",") { "?" }
    val sql = "$SELECT_EXECUTIONS_BY_ORDER_IDS_PREFIX$placeholders$SELECT_EXECUTIONS_BY_ORDER_IDS_SUFFIX"

    return jdbcConnection().prepareStatement(sql).use { statement ->
        orderIds.forEachIndexed { index, orderId ->
            statement.setObject(index + 1, UUID.fromString(orderId))
        }
        statement.setInt(orderIds.size + 1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet -> resultSet.toExecutions() }
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

private fun ResultSet.toOrders(): List<Order> {
    return buildList {
        while (next()) {
            add(toOrder())
        }
    }
}

private fun ResultSet.toPositions(): List<Position> {
    return buildList {
        while (next()) {
            add(toPosition())
        }
    }
}

private fun ResultSet.toExecutions(): List<Execution> {
    return buildList {
        while (next()) {
            add(toExecution())
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
        lowestPriceSinceEntryJpy = getNullableBigDecimal("lowest_price_since_entry_jpy")?.toPlainString(),
    )
}

private fun ResultSet.toOrder(): Order {
    return Order(
        orderId = getUuid("id").toString(),
        intentId = getNullableUuid("intent_id")?.toString(),
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
        protectiveStopPriceJpy = getNullableBigDecimal("protective_stop_price_jpy")?.toPlainString(),
        takeProfitPriceJpy = getNullableBigDecimal("take_profit_price_jpy")?.toPlainString(),
        estimatedWinProbability = getNullableBigDecimal("estimated_win_probability")?.toPlainString(),
        reasonJa = getString("reason_ja"),
        clientRequestId = getString("client_request_id"),
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

private fun ResultSet.getInstant(columnName: String): Instant {
    return Instant.ofEpochMilli(getLong(columnName))
}
