package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PaperBroker の読み取り集計 contract を検証するテスト。
 */
class PaperBrokerTest {

    @Test
    fun get_account_status_returns_initial_empty_account() = runBlocking {
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            clock = fixedClock(),
        )

        val accountStatus = broker.getAccountStatus().getOrThrow()

        assertEquals(TradingMode.PAPER, accountStatus.mode)
        assertEquals("RUNNING", accountStatus.riskState)
        assertEquals("100000.00000000", accountStatus.currentEquityJpy)
        assertEquals("0", accountStatus.todayRealizedPnlJpy)
        assertFalse(accountStatus.hardHalt)
        assertEquals(0, accountStatus.protectionStatus.protectedPositionCount)
        assertEquals(0, accountStatus.protectionStatus.unprotectedPositionCount)
        assertEquals(0, accountStatus.protectionStatus.orphanStopCount)
    }

    @Test
    fun get_account_status_counts_protection_gaps() = runBlocking {
        val ledgerRepository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshot(),
            positions = listOf(protectedPosition(), unprotectedPosition()),
            openOrders = listOf(
                linkedStopOrder(),
                orphanStopOrder(),
                orphanTakeProfitOrder(),
                pendingCancelOrder(),
            ),
        )
        val broker = PaperBroker(
            ledgerRepository = ledgerRepository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            clock = fixedClock(),
        )

        val protectionStatus = broker.getAccountStatus().getOrThrow().protectionStatus

        assertEquals(1, protectionStatus.protectedPositionCount)
        assertEquals(1, protectionStatus.unprotectedPositionCount)
        assertEquals(1, protectionStatus.orphanStopCount)
        assertEquals(1, protectionStatus.orphanTakeProfitCount)
        assertEquals(1, protectionStatus.pendingCancelCount)
    }

    @Test
    fun get_account_status_requires_active_stop_order_to_count_position_as_protected() = runBlocking {
        val ledgerRepository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshot(),
            positions = listOf(protectedPosition()),
            openOrders = emptyList(),
        )
        val broker = PaperBroker(
            ledgerRepository = ledgerRepository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            clock = fixedClock(),
        )

        val protectionStatus = broker.getAccountStatus().getOrThrow().protectionStatus

        assertEquals(0, protectionStatus.protectedPositionCount)
        assertEquals(1, protectionStatus.unprotectedPositionCount)
    }

    @Test
    fun get_account_status_includes_reconciler_freshness() = runBlocking {
        val reconcilerStatus = MutableReconcilerStatus()
        reconcilerStatus.markReconciled(
            reconciledAt = fixedInstant(),
            startupFullReconcileCompleted = true,
            lastMarketDataAt = fixedInstant(),
        )
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            reconcilerStatusProvider = reconcilerStatus,
            clock = fixedClock(),
        )

        val protectionStatus = broker.getAccountStatus().getOrThrow().protectionStatus

        assertEquals(fixedInstant().toString(), protectionStatus.lastReconciledAt)
        assertEquals(fixedInstant().toString(), protectionStatus.lastMarketDataAt)
    }

    @Test
    fun place_order_market_entry_creates_position_stop_execution_and_updates_account() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.placeOrder(marketEntryCommand()).getOrThrow()
        val balance = broker.getBalance().getOrThrow()
        val positions = broker.getPositions().getOrThrow()
        val orders = broker.getOpenOrders().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertTrue(result.accepted)
        assertEquals(OrderStatus.FILLED, result.status)
        assertEquals("0.005000000000", balance.btcQuantity)
        assertEquals(1, positions.size)
        assertEquals("9700000.00000000", positions.single().currentStopLossJpy)
        assertEquals("10500000.00000000", positions.single().currentTakeProfitJpy)
        assertEquals(listOf(OrderType.STOP), orders.map { order -> order.orderType })
        assertEquals(1, executions.size)
    }

    @Test
    fun update_protection_updates_stop_and_virtual_take_profit() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(marketEntryCommand()).getOrThrow()
        val positionId = UUID.fromString(broker.getPositions().getOrThrow().single().positionId)

        val result = broker.updateProtection(
            UpdateProtectionCommand(
                commandId = UUID.randomUUID(),
                positionId = positionId,
                newStopPriceJpy = BigDecimal("9800000"),
                takeProfitPriceSpecified = true,
                newTakeProfitPriceJpy = BigDecimal("10600000"),
                reasonJa = "test update",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val position = broker.getPositions().getOrThrow().single()
        val stopOrder = broker.getOpenOrders().getOrThrow().single { order -> order.orderType == OrderType.STOP }

        assertEquals(OrderStatus.OPEN, result.status)
        assertEquals("9800000", position.currentStopLossJpy)
        assertEquals("10600000", position.currentTakeProfitJpy)
        assertEquals("9800000.00000000", stopOrder.triggerPriceJpy)
    }

    @Test
    fun cancel_order_rejects_protective_stop_and_allows_resting_entry_cancel() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(restingLimitCommand()).getOrThrow()
        val restingOrderId = UUID.fromString(broker.getOpenOrders().getOrThrow().single().orderId)
        broker.placeOrder(marketEntryCommand()).getOrThrow()
        val stopOrderId = UUID.fromString(
            broker.getOpenOrders()
                .getOrThrow()
                .single { order -> order.orderType == OrderType.STOP }
                .orderId,
        )

        val cancelRestingResult = broker.cancelOrder(cancelCommand(restingOrderId)).getOrThrow()
        val cancelStopResult = broker.cancelOrder(cancelCommand(stopOrderId))

        assertEquals(OrderStatus.CANCELED, cancelRestingResult.status)
        assertTrue(cancelStopResult.isFailure)
    }
}

private fun marketEntryCommand(): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.MARKET,
        sizeBtc = BigDecimal("0.0050"),
        priceJpy = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("10500000"),
        reasonJa = "test entry",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun restingLimitCommand(): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        sizeBtc = BigDecimal("0.0050"),
        priceJpy = BigDecimal("9900000"),
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = null,
        reasonJa = "test resting entry",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun cancelCommand(orderId: UUID): CancelOrderCommand {
    return CancelOrderCommand(
        commandId = UUID.randomUUID(),
        orderId = orderId,
        reasonJa = "test cancel",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

/**
 * PaperBroker execution test 用 fake market data。
 */
private object FakeMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.success(
            Ticker(
                symbol = symbol.apiSymbol,
                last = "10000000",
                bid = "9990000",
                ask = "10000000",
                high = "10100000",
                low = "9900000",
                volume = "1.0",
                timestamp = fixedInstant().toString(),
            ),
        )
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(emptyList())
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        return Result.success(emptyList())
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.success(
            SymbolRules(
                symbol = symbol.apiSymbol,
                minOrderSize = "0.0001",
                sizeStep = "0.0001",
                tickSize = "1",
                takerFee = "0.0005",
                makerFee = "-0.0001",
            ),
        )
    }
}

private fun accountSnapshot(): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = "100000.00000000",
        initialCashJpy = "100000.00000000",
        btcQuantity = "0.000000000000",
        btcMarkPriceJpy = "0.00000000",
        totalEquityJpy = "100000.00000000",
        equityPeakJpy = "100000.00000000",
        drawdownRatio = "0",
    )
}

private fun protectedPosition(): Position {
    return position(
        positionId = "00000000-0000-0000-0000-000000000001",
        currentStopLossJpy = "9800000.00000000",
    )
}

private fun unprotectedPosition(): Position {
    return position(
        positionId = "00000000-0000-0000-0000-000000000002",
        currentStopLossJpy = null,
    )
}

private fun position(positionId: String, currentStopLossJpy: String?): Position {
    return Position(
        positionId = positionId,
        tradeGroupId = "10000000-0000-0000-0000-000000000001",
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = fixedInstant().toString(),
        closedAt = null,
        sizeBtc = "0.010000000000",
        averageEntryPriceJpy = "10000000.00000000",
        currentPriceJpy = "10100000.00000000",
        currentStopLossJpy = currentStopLossJpy,
        currentTakeProfitJpy = null,
        unrealizedPnlJpy = "1000.00000000",
        unrealizedR = "0.500000",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "10100000.00000000",
    )
}

private fun orphanStopOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000001",
        positionId = null,
        orderType = OrderType.STOP,
        side = OrderSide.SELL,
        status = OrderStatus.OPEN,
    )
}

private fun orphanTakeProfitOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000002",
        positionId = null,
        orderType = OrderType.LIMIT,
        side = OrderSide.SELL,
        status = OrderStatus.OPEN,
    )
}

private fun pendingCancelOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000003",
        positionId = null,
        orderType = OrderType.LIMIT,
        side = OrderSide.BUY,
        status = OrderStatus.PENDING_CANCEL,
    )
}

private fun linkedStopOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000004",
        positionId = "00000000-0000-0000-0000-000000000001",
        orderType = OrderType.STOP,
        side = OrderSide.SELL,
        status = OrderStatus.OPEN,
    )
}

private fun order(
    orderId: String,
    positionId: String?,
    orderType: OrderType,
    side: OrderSide,
    status: OrderStatus,
): Order {
    return Order(
        orderId = orderId,
        positionId = positionId,
        tradeGroupId = null,
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = side,
        orderType = orderType,
        status = status,
        sizeBtc = "0.010000000000",
        limitPriceJpy = "10200000.00000000",
        triggerPriceJpy = null,
        protectiveStopPriceJpy = null,
        takeProfitPriceJpy = null,
        reasonJa = "test",
        createdAt = fixedInstant().toString(),
        updatedAt = fixedInstant().toString(),
    )
}

/**
 * test 用固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

/**
 * test 用固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
