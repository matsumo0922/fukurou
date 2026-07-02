package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
            openOrders = listOf(orphanStopOrder(), orphanTakeProfitOrder(), pendingCancelOrder()),
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
        orderType = OrderType.STOP,
        side = OrderSide.SELL,
        status = OrderStatus.OPEN,
    )
}

private fun orphanTakeProfitOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000002",
        orderType = OrderType.LIMIT,
        side = OrderSide.SELL,
        status = OrderStatus.OPEN,
    )
}

private fun pendingCancelOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000003",
        orderType = OrderType.LIMIT,
        side = OrderSide.BUY,
        status = OrderStatus.PENDING_CANCEL,
    )
}

private fun order(
    orderId: String,
    orderType: OrderType,
    side: OrderSide,
    status: OrderStatus,
): Order {
    return Order(
        orderId = orderId,
        positionId = null,
        tradeGroupId = null,
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = side,
        orderType = orderType,
        status = status,
        sizeBtc = "0.010000000000",
        limitPriceJpy = "10200000.00000000",
        triggerPriceJpy = null,
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
