package me.matsumo.fukurou.trading.safety

import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.EntryIntentSafetySnapshot
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
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
import kotlin.test.assertTrue

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
                    EconomicEventBlackout(
                        eventId = "fomc-20261209",
                        eventName = "FOMC",
                        eventAt = Instant.parse("2026-12-09T19:00:00Z"),
                        blackoutBefore = Duration.ofMinutes(60),
                        blackoutAfter = Duration.ofMinutes(60),
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
    fun fomcCalendar_missingInvalidAndExpiredBlockOnlyNewEntry() {
        val observedAt = Instant.parse("2026-07-13T00:00:00Z")
        val configs = listOf(
            SafetyFloorConfig(economicEventBlackouts = emptyList()),
            SafetyFloorConfig(
                economicEventBlackouts = emptyList(),
                economicEventBlackoutsRaw = "not-json",
            ),
            SafetyFloorConfig(
                economicEventBlackouts = listOf(
                    EconomicEventBlackout(
                        eventId = "fomc-past",
                        eventName = "FOMC",
                        eventAt = Instant.parse("2026-01-01T19:00:00Z"),
                        blackoutBefore = Duration.ofMinutes(60),
                        blackoutAfter = Duration.ofMinutes(60),
                    ),
                ),
            ),
        )
        val expectedRules = listOf(
            SafetyFloorRule.FOMC_CALENDAR_MISSING,
            SafetyFloorRule.FOMC_CALENDAR_INVALID,
            SafetyFloorRule.FOMC_CALENDAR_EXPIRED,
        )

        configs.zip(expectedRules).forEach { (config, expectedRule) ->
            val floor = SafetyFloor(
                config = config,
                clock = Clock.fixed(observedAt, ZoneOffset.UTC),
            )
            val context = safetyContext(positions = emptyList(), atr14Jpy = null)
            val entryVerdict = floor.evaluatePlaceOrder(entryCommand(), context)

            assertEquals(expectedRule, assertIs<SafetyFloorVerdict.Rejected>(entryVerdict).violation.rule)
            assertIs<SafetyFloorVerdict.Accepted>(
                floor.evaluateClosePosition(
                    command = ClosePositionCommand(
                        commandId = UUID.randomUUID(),
                        positionId = UUID.randomUUID(),
                        closeAll = true,
                        reasonJa = "calendar fail-closed does not block close",
                        auditContext = PaperTradeAuditContext.EMPTY,
                    ),
                    context = context,
                ),
            )
        }
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

    @Test
    fun place_order_capsProbabilityForStaleMarketDataBeforeEvGate() {
        val command = entryCommand(
            sizeBtc = BigDecimal("0.0040"),
            takeProfitPriceJpy = BigDecimal("10300000"),
            estimatedWinProbability = BigDecimal("0.95"),
        )
        val verdict = SafetyFloor(
            config = SafetyFloorConfig(
                dataQualityCap = DataQualityCapConfig(
                    staleAfter = Duration.ofSeconds(60),
                    cappedProbability = BigDecimal("0.50"),
                ),
            ),
            clock = fixedClock(),
        ).evaluatePlaceOrder(
            command = command,
            context = safetyContext(
                positions = emptyList(),
                atr14Jpy = null,
                entryIntent = approvedIntentSnapshot(command),
                marketDataObservedAt = fixedInstant().minusSeconds(61),
            ),
        )

        val rejected = assertIs<SafetyFloorVerdict.Rejected>(verdict)

        assertTrue(rejected.violation.messageJa.contains("データ鮮度劣化により p を 0.50 に cap"))
    }

    @Test
    fun place_order_doesNotCapProbabilityForFreshMarketData() {
        val command = entryCommand(
            sizeBtc = BigDecimal("0.0040"),
            takeProfitPriceJpy = BigDecimal("10300000"),
            estimatedWinProbability = BigDecimal("0.95"),
        )
        val verdict = SafetyFloor(
            config = SafetyFloorConfig(
                dataQualityCap = DataQualityCapConfig(
                    staleAfter = Duration.ofSeconds(60),
                    cappedProbability = BigDecimal("0.50"),
                ),
            ),
            clock = fixedClock(),
        ).evaluatePlaceOrder(
            command = command,
            context = safetyContext(
                positions = emptyList(),
                atr14Jpy = null,
                entryIntent = approvedIntentSnapshot(command),
                marketDataObservedAt = fixedInstant(),
            ),
        )

        assertIs<SafetyFloorVerdict.Accepted>(verdict)
    }

    @Test
    fun placeOrderRiskDetails_usesOrderTypeAwareFeesForRequiredCashAndRoundTripCost() {
        val floor = SafetyFloor(clock = fixedClock())
        val context = safetyContext(
            positions = emptyList(),
            atr14Jpy = null,
        )

        val limitDetails = floor.placeOrderRiskDetails(
            command = entryCommand(
                orderType = OrderType.LIMIT,
                priceJpy = BigDecimal("10000000"),
            ),
            context = context,
        )
        val marketDetails = floor.placeOrderRiskDetails(
            command = entryCommand(),
            context = context,
        )
        val stopDetails = floor.placeOrderRiskDetails(
            command = entryCommand(
                orderType = OrderType.STOP,
                priceJpy = BigDecimal("10000000"),
            ),
            context = context,
        )

        assertEquals("50000.00000000", limitDetails.requiredCashJpy)
        assertEquals("1543.50000000", limitDetails.orderRiskJpy)
        assertEquals("50600.56263750", marketDetails.requiredCashJpy)
        assertEquals("2174.35027500", marketDetails.orderRiskJpy)
        assertEquals("50050.01250000", stopDetails.requiredCashJpy)
        assertEquals("1623.52500000", stopDetails.orderRiskJpy)
    }

    @Test
    fun placeOrderRiskDetails_addsAtrVolatilitySlippageForMarketRisk() {
        val details = SafetyFloor(clock = fixedClock()).placeOrderRiskDetails(
            command = entryCommand(),
            context = safetyContext(
                positions = emptyList(),
                atr14Jpy = BigDecimal("4000"),
            ),
        )

        assertEquals("10115455.00000000", details.estimatedEntryPriceJpy)
        assertEquals("50602.56363750", details.requiredCashJpy)
        assertEquals("2180.35227500", details.orderRiskJpy)
    }

    @Test
    fun placeOrderRiskDetails_usesPaperExecutionSlippageWhenHigherThanSafetyReserve() {
        val details = SafetyFloor(
            config = SafetyFloorConfig(marketSlippageReserveBps = BigDecimal("5")),
            clock = fixedClock(),
            paperExecutionConfig = PaperExecutionConfig(marketSlippageBps = BigDecimal("20")),
        ).placeOrderRiskDetails(
            command = entryCommand(),
            context = safetyContext(
                positions = emptyList(),
                atr14Jpy = null,
            ),
        )

        assertEquals("10130220.00000000", details.estimatedEntryPriceJpy)
        assertEquals("50676.42555000", details.requiredCashJpy)
        assertEquals("2398.97775000", details.orderRiskJpy)
    }

    @Test
    fun placeOrderRiskDetails_reservesOpenBuyOrdersWithOrderTypeAwareFees() {
        val details = SafetyFloor(clock = fixedClock()).placeOrderRiskDetails(
            command = entryCommand(
                orderType = OrderType.LIMIT,
                sizeBtc = BigDecimal("0.0010"),
                priceJpy = BigDecimal("9900000"),
            ),
            context = safetyContext(
                positions = emptyList(),
                openOrders = listOf(
                    openBuyOrder(
                        orderType = OrderType.LIMIT,
                        sizeBtc = "0.001000000000",
                        limitPriceJpy = "10000000.00000000",
                    ),
                    openBuyOrder(
                        orderType = OrderType.MARKET,
                        sizeBtc = "0.001000000000",
                    ),
                    openBuyOrder(
                        orderType = OrderType.STOP,
                        sizeBtc = "0.001000000000",
                        triggerPriceJpy = "10000000.00000000",
                    ),
                ),
                atr14Jpy = null,
            ),
        )

        // reserved cash:
        // LIMIT = 10,000,000 * 0.001 + maker rebate clamped to 0 = 10,000
        // MARKET = 10,110,000 ask * 1.0005 slippage * 0.001 * (1 + 0.0005 taker) = 10,120.11252750
        // STOP = 10,000,000 trigger * 1.0005 slippage * 0.001 * (1 + 0.0005 taker) = 10,010.00250000
        assertEquals("69869.88497250", details.availableCashJpy)
    }

    @Test
    fun place_order_acceptsLimitBoundaryThatTakerOnlyCostWouldReject() {
        val command = entryCommand(
            orderType = OrderType.LIMIT,
            priceJpy = BigDecimal("10000000"),
            takeProfitPriceJpy = BigDecimal("10050000"),
            estimatedWinProbability = BigDecimal.ONE,
        )
        val floor = SafetyFloor(clock = fixedClock())
        val context = safetyContext(
            account = accountSnapshot(
                cashJpy = "50000.00000000",
                totalEquityJpy = "200000.00000000",
            ),
            positions = emptyList(),
            atr14Jpy = null,
            entryIntent = approvedIntentSnapshot(command),
            marketDataObservedAt = fixedInstant(),
        )

        val verdict = floor.evaluatePlaceOrder(
            command = command,
            context = context,
        )
        val details = floor.placeOrderRiskDetails(
            command = command,
            context = context,
        )

        assertIs<SafetyFloorVerdict.Accepted>(verdict)
        assertEquals("50000.00000000", details.requiredCashJpy)
        // maker LIMIT cost:
        // (-5.00000000 entry rebate + 24.25000000 exit taker) + 24.25000000 exit slippage = 43.50000000
        assertEquals("5.74712644", details.expectedMoveToCostRatio)
    }

    @Test
    fun place_order_usesMakerLimitCostForMoveToCostBoundary() {
        val passingCommand = entryCommand(
            orderType = OrderType.LIMIT,
            priceJpy = BigDecimal("10000000"),
            protectiveStopPriceJpy = BigDecimal("9870000"),
            takeProfitPriceJpy = BigDecimal("10022175"),
            estimatedWinProbability = BigDecimal.ONE,
        )
        val failingCommand = passingCommand.copy(
            commandId = UUID.randomUUID(),
            takeProfitPriceJpy = BigDecimal("10022174"),
        )
        val floor = SafetyFloor(clock = fixedClock())
        val context = safetyContext(
            positions = emptyList(),
            atr14Jpy = null,
            entryIntent = approvedIntentSnapshot(passingCommand),
            marketDataObservedAt = fixedInstant(),
        )

        val passingVerdict = floor.evaluatePlaceOrder(
            command = passingCommand,
            context = context,
        )
        val failingVerdict = floor.evaluatePlaceOrder(
            command = failingCommand,
            context = context.copy(entryIntent = approvedIntentSnapshot(failingCommand)),
        )
        val rejected = assertIs<SafetyFloorVerdict.Rejected>(failingVerdict)

        assertIs<SafetyFloorVerdict.Accepted>(passingVerdict)
        assertEquals(SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO, rejected.violation.rule)
        assertEquals("2.49988726", rejected.violation.measuredValue)
    }

    @Test
    fun place_order_usesTakerCostForCrossingLimitCostBoundary() {
        val passingCommand = entryCommand(
            orderType = OrderType.LIMIT,
            priceJpy = BigDecimal("10110000"),
            protectiveStopPriceJpy = BigDecimal("10000000"),
            takeProfitPriceJpy = BigDecimal("10160275"),
            estimatedWinProbability = BigDecimal.ONE,
        )
        val failingCommand = passingCommand.copy(
            commandId = UUID.randomUUID(),
            takeProfitPriceJpy = BigDecimal("10160274"),
        )
        val floor = SafetyFloor(clock = fixedClock())
        val context = safetyContext(
            positions = emptyList(),
            atr14Jpy = null,
            entryIntent = approvedIntentSnapshot(passingCommand),
            marketDataObservedAt = fixedInstant(),
            orderbook = orderbook(
                bid = "10100000",
                ask = "10110000",
            ),
        )

        val passingVerdict = floor.evaluatePlaceOrder(
            command = passingCommand,
            context = context,
        )
        val failingVerdict = floor.evaluatePlaceOrder(
            command = failingCommand,
            context = context.copy(entryIntent = approvedIntentSnapshot(failingCommand)),
        )
        val details = floor.placeOrderRiskDetails(
            command = passingCommand,
            context = context,
        )
        val rejected = assertIs<SafetyFloorVerdict.Rejected>(failingVerdict)

        assertIs<SafetyFloorVerdict.Accepted>(passingVerdict)
        assertEquals(SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO, rejected.violation.rule)
        assertEquals("2.49995027", rejected.violation.measuredValue)
        assertEquals("50575.27500000", details.requiredCashJpy)
    }

    @Test
    fun place_order_usesTakerCostForCrossingLimitMaxRiskBoundary() {
        val passingCommand = entryCommand(
            orderType = OrderType.LIMIT,
            sizeBtc = BigDecimal("0.01537"),
            priceJpy = BigDecimal("10110000"),
            protectiveStopPriceJpy = BigDecimal("10000000"),
            takeProfitPriceJpy = BigDecimal("10500000"),
            estimatedWinProbability = BigDecimal.ONE,
        )
        val failingCommand = passingCommand.copy(
            commandId = UUID.randomUUID(),
            sizeBtc = BigDecimal("0.01538"),
        )
        val floor = SafetyFloor(
            config = SafetyFloorConfig(maxRiskPerTradeRatio = BigDecimal("0.01")),
            clock = fixedClock(),
        )
        val context = safetyContext(
            account = accountSnapshot(
                cashJpy = "250000.00000000",
                totalEquityJpy = "200000.00000000",
            ),
            positions = emptyList(),
            atr14Jpy = null,
            entryIntent = approvedIntentSnapshot(passingCommand),
            marketDataObservedAt = fixedInstant(),
            orderbook = orderbook(
                bid = "10100000",
                ask = "10110000",
            ),
        )

        val passingVerdict = floor.evaluatePlaceOrder(
            command = passingCommand,
            context = context,
        )
        val failingVerdict = floor.evaluatePlaceOrder(
            command = failingCommand,
            context = context.copy(entryIntent = approvedIntentSnapshot(failingCommand)),
        )
        val failingDetails = floor.placeOrderRiskDetails(
            command = failingCommand,
            context = context.copy(entryIntent = approvedIntentSnapshot(failingCommand)),
        )
        val rejected = assertIs<SafetyFloorVerdict.Rejected>(failingVerdict)

        assertIs<SafetyFloorVerdict.Accepted>(passingVerdict)
        assertEquals(SafetyFloorRule.MAX_RISK_PER_TRADE, rejected.violation.rule)
        assertEquals("2001.09180000", rejected.violation.measuredValue)
        assertEquals("2001.09180000", failingDetails.orderRiskJpy)
    }

    @Test
    fun place_order_keepsTakerAndMarketSlippageCostForMoveToCostBoundary() {
        val passingCommand = entryCommand(
            orderType = OrderType.MARKET,
            protectiveStopPriceJpy = BigDecimal("9950000"),
            takeProfitPriceJpy = BigDecimal("10165218"),
            estimatedWinProbability = BigDecimal.ONE,
        )
        val failingCommand = passingCommand.copy(
            commandId = UUID.randomUUID(),
            takeProfitPriceJpy = BigDecimal("10165217"),
        )
        val floor = SafetyFloor(clock = fixedClock())
        val context = safetyContext(
            positions = emptyList(),
            atr14Jpy = null,
            entryIntent = approvedIntentSnapshot(passingCommand),
            marketDataObservedAt = fixedInstant(),
        )

        val passingVerdict = floor.evaluatePlaceOrder(
            command = passingCommand,
            context = context,
        )
        val failingVerdict = floor.evaluatePlaceOrder(
            command = failingCommand,
            context = context.copy(entryIntent = approvedIntentSnapshot(failingCommand)),
        )
        val rejected = assertIs<SafetyFloorVerdict.Rejected>(failingVerdict)

        assertIs<SafetyFloorVerdict.Accepted>(passingVerdict)
        assertEquals(SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO, rejected.violation.rule)
        assertEquals("2.49996823", rejected.violation.measuredValue)
    }

    @Test
    fun place_order_rejectsUnsafeNegativeTakerFeeBeforeCostGates() {
        val command = entryCommand(
            orderType = OrderType.LIMIT,
            priceJpy = BigDecimal("10000000"),
        )
        val verdict = SafetyFloor(clock = fixedClock()).evaluatePlaceOrder(
            command = command,
            context = safetyContext(
                positions = emptyList(),
                atr14Jpy = null,
                entryIntent = approvedIntentSnapshot(command),
                marketDataObservedAt = fixedInstant(),
                symbolRules = symbolRules(takerFee = "-0.0010"),
            ),
        )
        val rejected = assertIs<SafetyFloorVerdict.Rejected>(verdict)

        assertEquals(SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT, rejected.violation.rule)
        val measuredValue = requireNotNull(rejected.violation.measuredValue)

        assertTrue(measuredValue.contains("takerFee must be greater than 0."))
    }
}

private fun entryCommand(
    orderType: OrderType = OrderType.MARKET,
    sizeBtc: BigDecimal = BigDecimal("0.0050"),
    priceJpy: BigDecimal? = null,
    protectiveStopPriceJpy: BigDecimal = BigDecimal("9700000"),
    takeProfitPriceJpy: BigDecimal = BigDecimal("10500000"),
    estimatedWinProbability: BigDecimal = BigDecimal("0.60"),
): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        intentId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = orderType,
        sizeBtc = sizeBtc,
        priceJpy = priceJpy,
        tradeGroupId = null,
        protectiveStopPriceJpy = protectiveStopPriceJpy,
        takeProfitPriceJpy = takeProfitPriceJpy,
        estimatedWinProbability = estimatedWinProbability,
        reasonJa = "test entry during blackout",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun safetyContext(
    account: AccountSnapshot = accountSnapshot(),
    positions: List<Position>,
    openOrders: List<Order> = emptyList(),
    atr14Jpy: BigDecimal?,
    entryIntent: EntryIntentSafetySnapshot? = null,
    marketDataObservedAt: Instant? = null,
    orderbook: Orderbook? = null,
    symbolRules: SymbolRules = symbolRules(),
): SafetyFloorContext {
    return SafetyFloorContext(
        account = account,
        riskState = RiskState(updatedAt = fixedInstant()),
        positions = positions,
        openOrders = openOrders,
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
        orderbook = orderbook,
        orderbookLookupAttempted = orderbook != null,
        symbolRules = symbolRules,
        entryIntent = entryIntent,
        atr14Jpy = atr14Jpy,
        marketDataObservedAt = marketDataObservedAt,
    )
}

private fun orderbook(bid: String, ask: String): Orderbook {
    return Orderbook(
        symbol = "BTC",
        bids = listOf(OrderbookLevel(price = bid, size = "1.0")),
        asks = listOf(OrderbookLevel(price = ask, size = "1.0")),
    )
}

private fun symbolRules(takerFee: String = "0.0005", makerFee: String = "-0.0001"): SymbolRules {
    return SymbolRules(
        symbol = "BTC",
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "1",
        takerFee = takerFee,
        makerFee = makerFee,
    )
}

private fun accountSnapshot(
    cashJpy: String = "100000.00000000",
    totalEquityJpy: String = "100000.00000000",
): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = cashJpy,
        initialCashJpy = "100000.00000000",
        btcQuantity = "0.000000000000",
        btcMarkPriceJpy = "10000000.00000000",
        totalEquityJpy = totalEquityJpy,
        equityPeakJpy = totalEquityJpy,
        drawdownRatio = "0",
    )
}

private fun openBuyOrder(
    orderType: OrderType,
    sizeBtc: String,
    limitPriceJpy: String? = null,
    triggerPriceJpy: String? = null,
): Order {
    return Order(
        orderId = UUID.randomUUID().toString(),
        intentId = null,
        positionId = null,
        tradeGroupId = null,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        orderType = orderType,
        status = OrderStatus.OPEN,
        sizeBtc = sizeBtc,
        limitPriceJpy = limitPriceJpy,
        triggerPriceJpy = triggerPriceJpy,
        protectiveStopPriceJpy = "9700000.00000000",
        takeProfitPriceJpy = "10500000.00000000",
        estimatedWinProbability = "0.60",
        reasonJa = "test open buy order",
        clientRequestId = null,
        createdAt = fixedInstant().toString(),
        updatedAt = fixedInstant().toString(),
    )
}

private fun approvedIntentSnapshot(command: PlaceOrderCommand): EntryIntentSafetySnapshot {
    val intentId = requireNotNull(command.intentId)

    return EntryIntentSafetySnapshot(
        tradeIntent = TradeIntentRecord(
            intentId = intentId,
            decisionId = UUID.randomUUID(),
            tradePlanId = UUID.randomUUID(),
            draft = EntryIntentDraft(
                symbol = command.symbol,
                side = command.side,
                orderType = command.orderType,
                sizeBtc = command.sizeBtc,
                priceJpy = command.priceJpy,
                protectiveStopPriceJpy = command.protectiveStopPriceJpy,
                takeProfitPriceJpy = command.takeProfitPriceJpy,
            ),
            estimatedWinProbability = command.estimatedWinProbability,
            createdAt = fixedInstant(),
        ),
        falsification = FalsificationRecord(
            falsificationId = UUID.randomUUID(),
            intentId = intentId,
            verdict = FalsificationVerdict.APPROVED,
            llmProvider = "codex",
            reasonJa = "test approved",
            createdAt = fixedInstant(),
        ),
        consumed = false,
        freshApproved = true,
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
        lowestPriceSinceEntryJpy = "10000000.00000000",
    )
}

private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}
