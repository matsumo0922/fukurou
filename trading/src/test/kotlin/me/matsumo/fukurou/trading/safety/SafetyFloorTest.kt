package me.matsumo.fukurou.trading.safety

import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.risk.RiskState
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * SafetyFloor の rule 判定を検証するテスト。
 */
class SafetyFloorTest {

    @Test
    fun place_order_rejects_entry_during_economic_event_blackout() {
        val eventAt = Instant.parse("2026-07-03T13:30:00Z")
        val verdict = SafetyFloor(
            config = SafetyFloorConfig(
                economicEventBlackouts = listOf(
                    EconomicEventBlackout(
                        eventId = "cpi-20260703",
                        eventName = "CPI",
                        eventAt = eventAt,
                        blackoutBefore = Duration.ofMinutes(30),
                        blackoutAfter = Duration.ofMinutes(30),
                    ),
                ),
            ),
            clock = Clock.fixed(Instant.parse("2026-07-03T13:00:00Z"), ZoneOffset.UTC),
        ).evaluatePlaceOrder(
            command = entryCommand(),
            context = safetyContext(
                positions = emptyList(),
                atr14Jpy = null,
            ),
        )

        val rejected = assertIs<SafetyFloorVerdict.Rejected>(verdict)

        assertEquals(SafetyFloorRule.ECONOMIC_EVENT_BLACKOUT, rejected.violation.rule)
        assertEquals("2026-07-03T13:00:00Z", rejected.violation.measuredValue)
    }

    @Test
    fun economicEventBlackout_usesUtcInstantForJstBoundary() {
        val event = EconomicEventBlackout(
            eventId = "fomc-20260703",
            eventName = "FOMC",
            eventAt = Instant.parse("2026-07-03T13:30:00Z"),
            blackoutBefore = Duration.ofMinutes(30),
            blackoutAfter = Duration.ofMinutes(30),
        )

        assertEquals(false, event.contains(Instant.parse("2026-07-03T12:59:59Z")))
        assertEquals(true, event.contains(Instant.parse("2026-07-03T13:00:00Z")))
        assertEquals(true, event.contains(Instant.parse("2026-07-03T14:00:00Z")))
        assertEquals(false, event.contains(Instant.parse("2026-07-03T14:00:01Z")))
    }

    @Test
    fun economicEventBlackout_doesNotBlockExitOrProtection() {
        val positionId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val floor = SafetyFloor(
            config = SafetyFloorConfig(
                economicEventBlackouts = listOf(
                    EconomicEventBlackout(
                        eventId = "cpi-20260703",
                        eventName = "CPI",
                        eventAt = fixedInstant(),
                        blackoutBefore = Duration.ofMinutes(30),
                        blackoutAfter = Duration.ofMinutes(30),
                    ),
                ),
            ),
            clock = fixedClock(),
        )
        val context = safetyContext(
            positions = listOf(protectedPosition(positionId)),
            atr14Jpy = null,
        )

        val protectionVerdict = floor.evaluateUpdateProtection(
            command = UpdateProtectionCommand(
                commandId = UUID.randomUUID(),
                positionId = positionId,
                newStopPriceJpy = BigDecimal("10020000"),
                takeProfitPriceSpecified = false,
                newTakeProfitPriceJpy = null,
                reasonJa = "test protection during blackout",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
            context = context,
        )
        val closeVerdict = floor.evaluateClosePosition(
            command = ClosePositionCommand(
                commandId = UUID.randomUUID(),
                positionId = positionId,
                closeAll = false,
                reasonJa = "test close during blackout",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
            context = context,
        )

        assertIs<SafetyFloorVerdict.Accepted>(protectionVerdict)
        assertIs<SafetyFloorVerdict.Accepted>(closeVerdict)
    }

    @Test
    fun update_protection_rejects_stop_below_atr_trailing_floor() {
        val positionId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val verdict = SafetyFloor(clock = fixedClock()).evaluateUpdateProtection(
            command = UpdateProtectionCommand(
                commandId = UUID.randomUUID(),
                positionId = positionId,
                newStopPriceJpy = BigDecimal("10030000"),
                takeProfitPriceSpecified = false,
                newTakeProfitPriceJpy = null,
                reasonJa = "test atr trailing floor",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
            context = safetyContext(
                positions = listOf(protectedPosition(positionId)),
                atr14Jpy = BigDecimal("20000"),
            ),
        )

        val rejected = assertIs<SafetyFloorVerdict.Rejected>(verdict)

        assertEquals(SafetyFloorRule.ATR_TRAILING_FLOOR, rejected.violation.rule)
        assertEquals("10030000", rejected.violation.measuredValue)
        assertEquals(">=10040000.00000000", rejected.violation.limitValue)
    }
}

private fun entryCommand(): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        intentId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.MARKET,
        sizeBtc = BigDecimal("0.0050"),
        priceJpy = null,
        tradeGroupId = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("10500000"),
        estimatedWinProbability = BigDecimal("0.60"),
        reasonJa = "test entry during blackout",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun safetyContext(positions: List<Position>, atr14Jpy: BigDecimal?): SafetyFloorContext {
    return SafetyFloorContext(
        account = AccountSnapshot(
            mode = TradingMode.PAPER,
            cashJpy = "100000.00000000",
            initialCashJpy = "100000.00000000",
            btcQuantity = "0.000000000000",
            btcMarkPriceJpy = "10000000.00000000",
            totalEquityJpy = "100000.00000000",
            equityPeakJpy = "100000.00000000",
            drawdownRatio = "0",
        ),
        riskState = RiskState(updatedAt = fixedInstant()),
        positions = positions,
        openOrders = emptyList<Order>(),
        ticker = Ticker(
            symbol = "BTC",
            last = "10100000",
            bid = "10100000",
            ask = "10110000",
            high = "10100000",
            low = "10000000",
            volume = "1.0",
            timestamp = fixedInstant().toString(),
        ),
        symbolRules = SymbolRules(
            symbol = "BTC",
            minOrderSize = "0.0001",
            sizeStep = "0.0001",
            tickSize = "1",
            takerFee = "0.0005",
            makerFee = "-0.0001",
        ),
        atr14Jpy = atr14Jpy,
    )
}

private fun protectedPosition(positionId: UUID): Position {
    return Position(
        positionId = positionId.toString(),
        tradeGroupId = "00000000-0000-0000-0000-000000000101",
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = fixedInstant().toString(),
        closedAt = null,
        sizeBtc = "0.005000000000",
        averageEntryPriceJpy = "10000000.00000000",
        currentPriceJpy = "10100000.00000000",
        currentStopLossJpy = "10000000.00000000",
        currentTakeProfitJpy = "10500000.00000000",
        unrealizedPnlJpy = "500.00000000",
        unrealizedR = "0.5000000000",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "10080000.00000000",
    )
}

private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}
