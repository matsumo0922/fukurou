package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.broker.AccountSnapshotWithUpdatedAt
import me.matsumo.fukurou.trading.broker.ExecutionActivityDecisionContext
import me.matsumo.fukurou.trading.broker.ExecutionActivityOrderContext
import me.matsumo.fukurou.trading.broker.ExecutionActivityPositionContext
import me.matsumo.fukurou.trading.broker.ExecutionActivityRecord
import me.matsumo.fukurou.trading.broker.IntentConsumingMarketEntryFillRequest
import me.matsumo.fukurou.trading.broker.IntentConsumingPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.IntentConsumingRestingEntryOrderRequest
import me.matsumo.fukurou.trading.broker.OpenOrdersWithUpdatedAt
import me.matsumo.fukurou.trading.broker.PaperLedgerAccountRepository
import me.matsumo.fukurou.trading.broker.PaperLedgerExecutionRepository
import me.matsumo.fukurou.trading.broker.PaperLedgerHistoryRepository
import me.matsumo.fukurou.trading.broker.PaperLedgerMutationRepository
import me.matsumo.fukurou.trading.broker.PaperLedgerOrderRepository
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PositionsWithUpdatedAt
import me.matsumo.fukurou.trading.config.PaperMarketConfig
import me.matsumo.fukurou.trading.decision.DecisionAction
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
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.knowledge.ClosedPaperPosition
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
        drawdown_ratio,
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
 * open positions と paper_account updated_at を同一結果で読む SQL。
 */
private const val SELECT_OPEN_POSITIONS_WITH_ACCOUNT_UPDATED_AT_SQL = """
    SELECT
        paper_account.updated_at AS account_updated_at,
        positions.id,
        positions.trade_group_id,
        positions.mode,
        positions.symbol,
        positions.side,
        positions.status,
        positions.opened_at,
        positions.closed_at,
        positions.size_btc,
        positions.average_entry_price_jpy,
        positions.current_price_jpy,
        positions.current_stop_loss_jpy,
        positions.current_take_profit_jpy,
        positions.unrealized_pnl_jpy,
        positions.unrealized_r,
        positions.pyramid_add_count,
        positions.highest_price_since_entry_jpy,
        positions.lowest_price_since_entry_jpy
    FROM paper_account
    LEFT JOIN positions
        ON positions.status = ?
        AND positions.mode = paper_account.mode
    WHERE paper_account.id = ?
    ORDER BY positions.opened_at ASC NULLS LAST
"""

/**
 * 指定範囲で close された positions を読む SQL。
 */
private const val SELECT_CLOSED_POSITIONS_CLOSED_BETWEEN_SQL = """
    SELECT
        latest_positions.id,
        latest_positions.trade_group_id,
        latest_positions.mode,
        latest_positions.symbol,
        latest_positions.side,
        latest_positions.status,
        latest_positions.opened_at,
        latest_positions.closed_at,
        latest_positions.size_btc,
        latest_positions.average_entry_price_jpy,
        latest_positions.current_price_jpy,
        latest_positions.current_stop_loss_jpy,
        latest_positions.current_take_profit_jpy,
        latest_positions.unrealized_pnl_jpy,
        latest_positions.unrealized_r,
        latest_positions.pyramid_add_count,
        latest_positions.highest_price_since_entry_jpy,
        latest_positions.lowest_price_since_entry_jpy,
        latest_positions.decision_run_id
    FROM (
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
            lowest_price_since_entry_jpy,
            decision_run_id
        FROM positions
        WHERE status = ?
            AND closed_at >= ?
            AND closed_at < ?
            AND mode = (
                SELECT mode
                FROM paper_account
                WHERE id = ?
            )
        ORDER BY closed_at DESC
        LIMIT ?
    ) latest_positions
    ORDER BY latest_positions.closed_at ASC
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
 * open orders と paper_account updated_at を同一結果で読む SQL。
 */
private const val SELECT_OPEN_ORDERS_WITH_ACCOUNT_UPDATED_AT_SQL = """
    SELECT
        paper_account.updated_at AS account_updated_at,
        orders.id,
        orders.intent_id,
        orders.position_id,
        orders.trade_group_id,
        orders.mode,
        orders.symbol,
        orders.side,
        orders.order_type,
        orders.status,
        orders.size_btc,
        orders.limit_price_jpy,
        orders.trigger_price_jpy,
        orders.protective_stop_price_jpy,
        orders.take_profit_price_jpy,
        orders.estimated_win_probability,
        orders.reason_ja,
        orders.client_request_id,
        orders.created_at,
        orders.updated_at
    FROM paper_account
    LEFT JOIN orders
        ON orders.status IN (?, ?)
        AND orders.mode = paper_account.mode
    WHERE paper_account.id = ?
    ORDER BY orders.created_at ASC NULLS LAST
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
 * position IDs に対応する executions を読む SQL prefix。
 */
private const val SELECT_EXECUTIONS_BY_POSITION_IDS_PREFIX = """
    SELECT
        bounded_executions.id,
        bounded_executions.order_id,
        bounded_executions.position_id,
        bounded_executions.mode,
        bounded_executions.symbol,
        bounded_executions.side,
        bounded_executions.price_jpy,
        bounded_executions.size_btc,
        bounded_executions.fee_jpy,
        bounded_executions.realized_pnl_jpy,
        bounded_executions.liquidity,
        bounded_executions.executed_at
    FROM (
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
            executed_at,
            ROW_NUMBER() OVER (PARTITION BY position_id ORDER BY executed_at ASC) AS position_execution_number
        FROM executions
        WHERE position_id IN (
"""

/**
 * position IDs に対応する executions を読む SQL suffix。
 */
private const val SELECT_EXECUTIONS_BY_POSITION_IDS_SUFFIX = """
        )
            AND mode = (
                SELECT mode
                FROM paper_account
                WHERE id = ?
            )
    ) bounded_executions
    WHERE bounded_executions.position_execution_number <= ?
    ORDER BY bounded_executions.executed_at ASC
"""

/**
 * position IDs に対応する SELL executions を読む SQL prefix。
 */
private const val SELECT_SELL_EXECUTIONS_BY_POSITION_IDS_PREFIX = """
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
    WHERE position_id IN (
"""

/**
 * position IDs に対応する SELL executions を読む SQL suffix。
 */
private const val SELECT_SELL_EXECUTIONS_BY_POSITION_IDS_SUFFIX = """
    )
        AND side = ?
        AND mode = (
            SELECT mode
            FROM paper_account
            WHERE id = ?
        )
    ORDER BY executed_at DESC
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
 * execution ledger の最新行を指定上限で読む SQL。
 */
private const val SELECT_RECENT_EXECUTIONS_SQL = """
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
    ORDER BY executed_at DESC
    LIMIT ?
"""

/**
 * execution ledger の指定時刻より古い行を指定上限で読む SQL。
 */
private const val SELECT_EXECUTIONS_BEFORE_SQL = """
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
        AND executed_at < ?
    ORDER BY executed_at DESC
    LIMIT ?
"""

/**
 * Activity timeline 用 execution stable feed SELECT の列部分。
 */
private const val SELECT_EXECUTIONS_FOR_STABLE_FEED_SQL_PREFIX = """
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
        AND
"""

/**
 * Activity timeline 用 execution stable feed SELECT の並び順と件数制限。
 */
private const val SELECT_EXECUTIONS_FOR_STABLE_FEED_SQL_SUFFIX = """
    ORDER BY executed_at DESC, CAST(id AS TEXT) ASC
    LIMIT ?
"""

/**
 * Activity timeline 用 execution context stable feed SELECT の列と join。
 */
private const val SELECT_EXECUTION_ACTIVITIES_FOR_STABLE_FEED_SQL_PREFIX = """
    SELECT
        e.id AS execution_id,
        e.order_id AS execution_order_id,
        e.position_id AS execution_position_id,
        e.mode AS execution_mode,
        e.symbol AS execution_symbol,
        e.side AS execution_side,
        e.price_jpy AS execution_price_jpy,
        e.size_btc AS execution_size_btc,
        e.fee_jpy AS execution_fee_jpy,
        e.realized_pnl_jpy AS execution_realized_pnl_jpy,
        e.liquidity AS execution_liquidity,
        e.executed_at AS execution_executed_at,
        direct_order.id AS context_order_id,
        direct_order.order_type AS context_order_type,
        direct_order.trigger_price_jpy AS context_trigger_price_jpy,
        direct_order.take_profit_price_jpy AS context_take_profit_price_jpy,
        direct_order.reason_ja AS context_order_reason_ja,
        COALESCE(linked_position.id, e.position_id, direct_order.position_id) AS context_position_id,
        COALESCE(linked_position.trade_group_id, direct_order.trade_group_id) AS context_trade_group_id,
        entry_order.decision_run_id AS context_entry_decision_run_id,
        entry_decision.id AS context_entry_decision_id,
        entry_decision.action AS context_entry_decision_action,
        entry_decision.reason_ja AS context_entry_decision_reason_ja
    FROM executions e
    LEFT JOIN orders direct_order
        ON direct_order.id = e.order_id
        AND direct_order.mode = e.mode
    LEFT JOIN positions linked_position
        ON linked_position.id = COALESCE(e.position_id, direct_order.position_id)
        AND linked_position.mode = e.mode
    LEFT JOIN LATERAL (
        SELECT
            candidate_entry_order.id,
            candidate_entry_order.decision_run_id
        FROM orders candidate_entry_order
        WHERE candidate_entry_order.mode = e.mode
            AND candidate_entry_order.side = 'BUY'
            AND candidate_entry_order.decision_run_id IS NOT NULL
            AND (
                (
                    linked_position.trade_group_id IS NOT NULL
                    AND candidate_entry_order.trade_group_id = linked_position.trade_group_id
                )
                OR (
                    direct_order.trade_group_id IS NOT NULL
                    AND candidate_entry_order.trade_group_id = direct_order.trade_group_id
                )
                OR (
                    e.position_id IS NOT NULL
                    AND candidate_entry_order.position_id = e.position_id
                )
                OR (
                    direct_order.position_id IS NOT NULL
                    AND candidate_entry_order.position_id = direct_order.position_id
                )
            )
        ORDER BY candidate_entry_order.created_at ASC, CAST(candidate_entry_order.id AS TEXT) ASC
        LIMIT 1
    ) entry_order ON TRUE
    LEFT JOIN LATERAL (
        SELECT
            decisions.id,
            decisions.action,
            decisions.reason_ja
        FROM decisions
        WHERE decisions.invocation_id = entry_order.decision_run_id
        ORDER BY decisions.created_at DESC
        LIMIT 1
    ) entry_decision ON TRUE
    WHERE e.mode = (
        SELECT mode
        FROM paper_account
        WHERE id = ?
    )
        AND
"""

/**
 * Activity timeline 用 execution context stable feed SELECT の並び順と件数制限。
 */
private const val SELECT_EXECUTION_ACTIVITIES_FOR_STABLE_FEED_SQL_SUFFIX = """
    ORDER BY e.executed_at DESC, CAST(e.id AS TEXT) ASC
    LIMIT ?
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
 * closed position 1 件から読む executions の保守的な上限。
 */
private const val MAX_EXECUTIONS_PER_CLOSED_POSITION = 32

/**
 * Exposed/JDBC で paper ledger の account / position / order を読む repository。
 *
 * @param database Exposed database
 */
private class ExposedPaperLedgerAccountReader(
    private val database: ExposedDatabase,
) : PaperLedgerAccountRepository {
    override suspend fun getAccountSnapshot(): Result<AccountSnapshot> {
        return readLedgerResult(database) { selectPaperAccount() }
    }

    override suspend fun getAccountSnapshotWithUpdatedAt(): Result<AccountSnapshotWithUpdatedAt> {
        return readLedgerResult(database) { selectPaperAccountWithUpdatedAt() }
    }

    override suspend fun getOpenPositions(): Result<List<Position>> {
        return readLedgerResult(database) { selectOpenPositions() }
    }

    override suspend fun getOpenPositionsWithUpdatedAt(): Result<PositionsWithUpdatedAt> {
        return readLedgerResult(database) { selectOpenPositionsWithUpdatedAt() }
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return readLedgerResult(database) { selectOpenOrders() }
    }

    override suspend fun getOpenOrdersWithUpdatedAt(): Result<OpenOrdersWithUpdatedAt> {
        return readLedgerResult(database) { selectOpenOrdersWithUpdatedAt() }
    }

    override suspend fun getRealizedPnlForDate(date: LocalDate): Result<BigDecimal> {
        return readLedgerResult(database) { selectRealizedPnlForDate(date) }
    }
}

/**
 * Exposed/JDBC で paper ledger の execution を読む repository。
 *
 * @param database Exposed database
 */
private class ExposedPaperLedgerExecutionReader(
    private val database: ExposedDatabase,
) : PaperLedgerExecutionRepository {
    override suspend fun getExecutions(): Result<List<Execution>> {
        return readLedgerResult(database) { selectExecutions() }
    }

    override suspend fun getRecentExecutions(limit: Int): Result<List<Execution>> {
        return readLedgerResult(
            database = database,
            beforeRead = {
                require(limit > 0) {
                    "limit must be greater than 0."
                }
            },
        ) { selectRecentExecutions(limit) }
    }

    override suspend fun findExecutionsBefore(before: Instant, limit: Int): Result<List<Execution>> {
        return readLedgerResult(
            database = database,
            beforeRead = {
                require(limit > 0) {
                    "limit must be greater than 0."
                }
            },
        ) { selectExecutionsBefore(before, limit) }
    }

    override suspend fun findExecutionsForStableFeed(cursor: StableFeedCursor, limit: Int): Result<List<Execution>> {
        return readLedgerResult(
            database = database,
            beforeRead = {
                require(limit > 0) {
                    "limit must be greater than 0."
                }
            },
        ) { selectExecutionsForStableFeed(cursor, limit) }
    }

    override suspend fun findSellExecutionsByPositionIds(positionIds: List<String>): Result<List<Execution>> {
        return readLedgerResult(database) { selectSellExecutionsByPositionIds(positionIds) }
    }

    override suspend fun findExecutionActivitiesForStableFeed(
        cursor: StableFeedCursor,
        limit: Int,
    ): Result<List<ExecutionActivityRecord>> {
        return readLedgerResult(
            database = database,
            beforeRead = {
                require(limit > 0) {
                    "limit must be greater than 0."
                }
            },
        ) { selectExecutionActivitiesForStableFeed(cursor, limit) }
    }
}

/**
 * Exposed/JDBC で paper ledger の order 履歴を読む repository。
 *
 * @param database Exposed database
 */
private class ExposedPaperLedgerOrderReader(
    private val database: ExposedDatabase,
) : PaperLedgerOrderRepository {
    override suspend fun findOrdersByTradeGroupId(tradeGroupId: UUID): Result<List<Order>> {
        return readLedgerResult(database) { selectOrdersByTradeGroupId(tradeGroupId.toString()) }
    }
}

/**
 * Exposed/JDBC で paper ledger の履歴系読み取りを行う repository。
 *
 * @param database Exposed database
 */
private class ExposedPaperLedgerHistoryReader(
    private val database: ExposedDatabase,
) : PaperLedgerHistoryRepository {
    override suspend fun findClosedPositionsClosedBetween(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): Result<List<ClosedPaperPosition>> {
        return readLedgerResult(
            database = database,
            beforeRead = {
                require(limit > 0) {
                    "limit must be greater than 0."
                }
            },
        ) {
            selectClosedPositionsClosedBetween(
                from = from,
                toExclusive = toExclusive,
                limit = limit,
            )
        }
    }

    override suspend fun findPlaceOrderResultByClientRequestId(clientRequestId: String): Result<PaperTradeResult?> {
        return readLedgerResult(database) { findPlaceOrderResultByClientRequestId(clientRequestId) }
    }
}

/**
 * Exposed/JDBC で paper ledger を読む repository。
 *
 * @param database Exposed database
 * @param fallbackSymbolRules tick に symbol rules がない場合の fallback 取引ルール
 */
class ExposedPaperLedgerRepository private constructor(
    private val writer: ExposedPaperLedgerWriter,
    accountRepository: PaperLedgerAccountRepository,
    executionRepository: PaperLedgerExecutionRepository,
    orderRepository: PaperLedgerOrderRepository,
    historyRepository: PaperLedgerHistoryRepository,
) : IntentConsumingPaperLedgerRepository,
    PaperLedgerAccountRepository by accountRepository,
    PaperLedgerExecutionRepository by executionRepository,
    PaperLedgerOrderRepository by orderRepository,
    PaperLedgerHistoryRepository by historyRepository,
    PaperLedgerMutationRepository by writer {

    constructor(
        database: ExposedDatabase,
        fallbackSymbolRules: SymbolRules = PaperMarketConfig().toSymbolRules(TradingSymbol.BTC),
    ) : this(
        writer = ExposedPaperLedgerWriter(database, fallbackSymbolRules = fallbackSymbolRules),
        accountRepository = ExposedPaperLedgerAccountReader(database),
        executionRepository = ExposedPaperLedgerExecutionReader(database),
        orderRepository = ExposedPaperLedgerOrderReader(database),
        historyRepository = ExposedPaperLedgerHistoryReader(database),
    )

    override suspend fun fillMarketEntryAndConsumeIntent(
        request: IntentConsumingMarketEntryFillRequest,
    ): Result<PaperTradeResult> {
        return writer.fillMarketEntryAndConsumeIntent(request)
    }

    override suspend fun createRestingEntryOrderAndConsumeIntent(
        request: IntentConsumingRestingEntryOrderRequest,
    ): Result<PaperTradeResult> {
        return writer.createRestingEntryOrderAndConsumeIntent(request)
    }
}

private suspend fun <T> readLedgerResult(
    database: ExposedDatabase,
    beforeRead: () -> Unit = {},
    read: JdbcTransaction.() -> T,
): Result<T> {
    return withContext(Dispatchers.IO) {
        runCatching {
            beforeRead()

            exposedTransaction(database) {
                read()
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

/**
 * paper_account single row と updated_at を同一 SELECT で取得する。
 */
internal fun JdbcTransaction.selectPaperAccountWithUpdatedAt(): AccountSnapshotWithUpdatedAt {
    return jdbcConnection().prepareStatement(SELECT_PAPER_ACCOUNT_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "paper_account single row was not initialized." }

            AccountSnapshotWithUpdatedAt(
                accountSnapshot = resultSet.toAccountSnapshot(),
                updatedAt = resultSet.getInstant("updated_at"),
            )
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

internal fun JdbcTransaction.selectOpenPositionsWithUpdatedAt(): PositionsWithUpdatedAt {
    return jdbcConnection().prepareStatement(SELECT_OPEN_POSITIONS_WITH_ACCOUNT_UPDATED_AT_SQL).use { statement ->
        statement.setString(1, PositionStatus.OPEN.name)
        statement.setInt(2, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "paper_account single row was not initialized." }

            val updatedAt = resultSet.getInstant("account_updated_at")
            val positions = resultSet.toNullablePositions()

            PositionsWithUpdatedAt(
                positions = positions,
                updatedAt = updatedAt,
            )
        }
    }
}

private fun JdbcTransaction.selectClosedPositionsClosedBetween(
    from: Instant,
    toExclusive: Instant,
    limit: Int,
): List<ClosedPaperPosition> {
    val positionRows = selectClosedPositionRows(from, toExclusive, limit)
    val positionIds = positionRows.map { row -> row.first.positionId }
    val executionsByPositionId = selectExecutionsByPositionIds(positionIds)
        .groupBy { execution -> execution.positionId }

    return positionRows.map { row ->
        val position = row.first

        ClosedPaperPosition(
            position = position,
            decisionRunId = row.second,
            executions = executionsByPositionId[position.positionId].orEmpty(),
        )
    }
}

private fun JdbcTransaction.selectClosedPositionRows(
    from: Instant,
    toExclusive: Instant,
    limit: Int,
): List<Pair<Position, String?>> {
    return jdbcConnection().prepareStatement(SELECT_CLOSED_POSITIONS_CLOSED_BETWEEN_SQL).use { statement ->
        statement.setString(1, PositionStatus.CLOSED.name)
        statement.setLong(2, from.toEpochMilli())
        statement.setLong(3, toExclusive.toEpochMilli())
        statement.setInt(4, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setInt(5, limit)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toPosition() to resultSet.getString("decision_run_id"))
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

internal fun JdbcTransaction.selectOpenOrdersWithUpdatedAt(): OpenOrdersWithUpdatedAt {
    return jdbcConnection().prepareStatement(SELECT_OPEN_ORDERS_WITH_ACCOUNT_UPDATED_AT_SQL).use { statement ->
        statement.setString(1, OrderStatus.OPEN.name)
        statement.setString(2, OrderStatus.PENDING_CANCEL.name)
        statement.setInt(3, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "paper_account single row was not initialized." }

            val updatedAt = resultSet.getInstant("account_updated_at")
            val openOrders = resultSet.toNullableOrders()

            OpenOrdersWithUpdatedAt(
                openOrders = openOrders,
                updatedAt = updatedAt,
            )
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

private fun JdbcTransaction.selectExecutionsByPositionIds(positionIds: List<String>): List<Execution> {
    if (positionIds.isEmpty()) {
        return emptyList()
    }

    val placeholders = positionIds.joinToString(separator = ",") { "?" }
    val sql = "$SELECT_EXECUTIONS_BY_POSITION_IDS_PREFIX$placeholders$SELECT_EXECUTIONS_BY_POSITION_IDS_SUFFIX"

    return jdbcConnection().prepareStatement(sql).use { statement ->
        positionIds.forEachIndexed { index, positionId ->
            statement.setObject(index + 1, UUID.fromString(positionId))
        }
        statement.setInt(positionIds.size + 1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setInt(positionIds.size + 2, MAX_EXECUTIONS_PER_CLOSED_POSITION)
        statement.executeQuery().use { resultSet -> resultSet.toExecutions() }
    }
}

private fun JdbcTransaction.selectSellExecutionsByPositionIds(positionIds: List<String>): List<Execution> {
    if (positionIds.isEmpty()) {
        return emptyList()
    }

    val placeholders = positionIds.joinToString(separator = ",") { "?" }
    val sql = "$SELECT_SELL_EXECUTIONS_BY_POSITION_IDS_PREFIX$placeholders$SELECT_SELL_EXECUTIONS_BY_POSITION_IDS_SUFFIX"

    return jdbcConnection().prepareStatement(sql).use { statement ->
        positionIds.forEachIndexed { index, positionId ->
            statement.setObject(index + 1, UUID.fromString(positionId))
        }
        statement.setString(positionIds.size + 1, OrderSide.SELL.name)
        statement.setInt(positionIds.size + 2, PAPER_ACCOUNT_SINGLE_ROW_ID)
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

private fun JdbcTransaction.selectRecentExecutions(limit: Int): List<Execution> {
    return jdbcConnection().prepareStatement(SELECT_RECENT_EXECUTIONS_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setInt(2, limit)
        statement.executeQuery().use { resultSet -> resultSet.toExecutions() }
    }
}

private fun JdbcTransaction.selectExecutionsBefore(before: Instant, limit: Int): List<Execution> {
    return jdbcConnection().prepareStatement(SELECT_EXECUTIONS_BEFORE_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setLong(2, before.toEpochMilli())
        statement.setInt(3, limit)
        statement.executeQuery().use { resultSet -> resultSet.toExecutions() }
    }
}

private fun JdbcTransaction.selectExecutionsForStableFeed(cursor: StableFeedCursor, limit: Int): List<Execution> {
    val sql = SELECT_EXECUTIONS_FOR_STABLE_FEED_SQL_PREFIX +
        stableFeedCursorCondition("executed_at", "id", cursor) +
        SELECT_EXECUTIONS_FOR_STABLE_FEED_SQL_SUFFIX

    return jdbcConnection().prepareStatement(sql).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        val limitParameterIndex = statement.bindStableFeedCursor(
            startIndex = 2,
            cursor = cursor,
        )
        statement.setInt(limitParameterIndex, limit)
        statement.executeQuery().use { resultSet -> resultSet.toExecutions() }
    }
}

private fun JdbcTransaction.selectExecutionActivitiesForStableFeed(
    cursor: StableFeedCursor,
    limit: Int,
): List<ExecutionActivityRecord> {
    val sql = SELECT_EXECUTION_ACTIVITIES_FOR_STABLE_FEED_SQL_PREFIX +
        stableFeedCursorCondition("e.executed_at", "e.id", cursor) +
        SELECT_EXECUTION_ACTIVITIES_FOR_STABLE_FEED_SQL_SUFFIX

    return jdbcConnection().prepareStatement(sql).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        val limitParameterIndex = statement.bindStableFeedCursor(
            startIndex = 2,
            cursor = cursor,
        )
        statement.setInt(limitParameterIndex, limit)
        statement.executeQuery().use { resultSet -> resultSet.toExecutionActivities() }
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

private fun ResultSet.toExecutionActivities(): List<ExecutionActivityRecord> {
    return buildList {
        while (next()) {
            add(toExecutionActivityRecord())
        }
    }
}

private fun ResultSet.toNullablePositions(): List<Position> {
    return buildList {
        do {
            val hasPositionRow = getObject("id") != null

            if (hasPositionRow) {
                add(toPosition())
            }
        } while (next())
    }
}

private fun ResultSet.toNullableOrders(): List<Order> {
    return buildList {
        do {
            val hasOrderRow = getObject("id") != null

            if (hasOrderRow) {
                add(toOrder())
            }
        } while (next())
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

private fun ResultSet.toExecutionActivityRecord(): ExecutionActivityRecord {
    return ExecutionActivityRecord(
        execution = toActivityExecution(),
        order = toExecutionActivityOrderContext(),
        position = toExecutionActivityPositionContext(),
        entryDecision = toExecutionActivityDecisionContext(),
    )
}

private fun ResultSet.toActivityExecution(): Execution {
    return Execution(
        executionId = getUuid("execution_id").toString(),
        orderId = getNullableUuid("execution_order_id")?.toString(),
        positionId = getNullableUuid("execution_position_id")?.toString(),
        symbol = getString("execution_symbol"),
        mode = TradingMode.valueOf(getString("execution_mode")),
        side = OrderSide.valueOf(getString("execution_side")),
        priceJpy = getBigDecimal("execution_price_jpy").toPlainString(),
        sizeBtc = getBigDecimal("execution_size_btc").toPlainString(),
        feeJpy = getBigDecimal("execution_fee_jpy").toPlainString(),
        realizedPnlJpy = getBigDecimal("execution_realized_pnl_jpy").toPlainString(),
        liquidity = ExecutionLiquidity.valueOf(getString("execution_liquidity")),
        executedAt = getInstant("execution_executed_at").toString(),
    )
}

private fun ResultSet.toExecutionActivityOrderContext(): ExecutionActivityOrderContext? {
    val orderId = getNullableUuid("context_order_id")?.toString() ?: return null

    return ExecutionActivityOrderContext(
        orderId = orderId,
        orderType = OrderType.valueOf(getString("context_order_type")),
        triggerPriceJpy = getNullableBigDecimal("context_trigger_price_jpy")?.toPlainString(),
        takeProfitPriceJpy = getNullableBigDecimal("context_take_profit_price_jpy")?.toPlainString(),
        reasonJa = getNullableString("context_order_reason_ja"),
    )
}

private fun ResultSet.toExecutionActivityPositionContext(): ExecutionActivityPositionContext? {
    val positionId = getNullableUuid("context_position_id")?.toString()
    val tradeGroupId = getNullableUuid("context_trade_group_id")?.toString()

    if (positionId == null && tradeGroupId == null) {
        return null
    }

    return ExecutionActivityPositionContext(
        positionId = positionId,
        tradeGroupId = tradeGroupId,
    )
}

private fun ResultSet.toExecutionActivityDecisionContext(): ExecutionActivityDecisionContext? {
    val decisionRunId = getNullableString("context_entry_decision_run_id")
    val decisionId = getNullableUuid("context_entry_decision_id")?.toString()
    val rawAction = getNullableString("context_entry_decision_action")
    val reasonJa = getNullableString("context_entry_decision_reason_ja")
    val contextValues = listOf(decisionRunId, decisionId, rawAction, reasonJa)

    if (contextValues.all { value -> value == null }) {
        return null
    }

    return ExecutionActivityDecisionContext(
        decisionId = decisionId,
        decisionRunId = decisionRunId,
        action = rawAction?.let { action -> DecisionAction.valueOf(action) },
        reasonJa = reasonJa,
    )
}

private fun ResultSet.getUuid(columnName: String): UUID {
    return getObject(columnName, UUID::class.java)
}

private fun ResultSet.getInstant(columnName: String): Instant {
    return Instant.ofEpochMilli(getLong(columnName))
}

private fun ResultSet.getNullableString(columnName: String): String? {
    val value = getString(columnName)

    return if (wasNull()) null else value
}
