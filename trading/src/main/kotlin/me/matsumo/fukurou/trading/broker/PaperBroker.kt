package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.ProtectionStatus
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.reconciler.NoReconcilerStatusProvider
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatusProvider
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * paper ledger を読み取る Broker 実装。
 *
 * @param ledgerRepository paper ledger repository
 * @param riskStateRepository risk_state repository
 * @param marketDataSource paper 約定に使う市場データ source
 * @param fillSimulator paper 約定 simulator
 * @param reconcilerStatusProvider ProtectionReconciler 状態 provider
 * @param clock 当日実現損益の対象日算出に使う clock
 * @param tradingDateZone 当日判定に使う timezone
 */
class PaperBroker(
    internal val ledgerRepository: PaperLedgerRepository,
    private val riskStateRepository: RiskStateRepository,
    internal val marketDataSource: MarketDataSource? = null,
    internal val fillSimulator: FillSimulator = FillSimulator(),
    private val reconcilerStatusProvider: ReconcilerStatusProvider = NoReconcilerStatusProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val tradingDateZone: ZoneId = TRADING_DATE_ZONE,
) : Broker {

    override suspend fun getBalance(): Result<AccountSnapshot> {
        return ledgerRepository.getAccountSnapshot()
    }

    override suspend fun getPositions(): Result<List<Position>> {
        return ledgerRepository.getOpenPositions()
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return ledgerRepository.getOpenOrders()
    }

    override suspend fun getAccountStatus(): Result<AccountStatus> {
        return runCatching {
            val accountSnapshot = ledgerRepository.getAccountSnapshot().getOrThrow()
            val riskState = riskStateRepository.current().getOrThrow()
            val positions = ledgerRepository.getOpenPositions().getOrThrow()
            val openOrders = ledgerRepository.getOpenOrders().getOrThrow()
            val reconcilerStatus = reconcilerStatusProvider.snapshot()
            val today = LocalDate.now(clock.withZone(tradingDateZone))
            val todayRealizedPnlJpy = ledgerRepository.getRealizedPnlForDate(today).getOrThrow()

            AccountStatus(
                mode = accountSnapshot.mode,
                riskState = if (riskState.hardHalt) "HARD_HALT" else "RUNNING",
                drawdownRatio = riskState.drawdownRatio.toPlainString(),
                hardHalt = riskState.hardHalt,
                currentEquityJpy = accountSnapshot.totalEquityJpy,
                todayRealizedPnlJpy = todayRealizedPnlJpy.toPlainString(),
                protectionStatus = protectionStatus(positions, openOrders, reconcilerStatus),
            )
        }
    }

    override suspend fun placeOrder(command: PlaceOrderCommand): Result<PaperTradeResult> {
        return runCatching {
            validatePlaceOrderCommand(command)

            findExistingPlaceOrderResult(command)?.let { existingResult ->
                return@runCatching existingResult
            }

            val ticker = tickerFor(command.symbol).getOrThrow()
            val symbolRules = symbolRulesFor(command.symbol).getOrThrow()

            validateSymbolRules(command, symbolRules)
            validateEntryPriceContract(command, ticker)
            validateCashAvailability(command, ticker, symbolRules)

            if (command.orderType == OrderType.MARKET) {
                val fill = fillSimulator.marketFill(command.side, command.sizeBtc, ticker, symbolRules)

                return@runCatching ledgerRepository.fillMarketEntry(
                    command = command,
                    fill = fill,
                    positionId = UUID.randomUUID(),
                    tradeGroupId = UUID.randomUUID(),
                    stopOrderId = UUID.randomUUID(),
                ).getOrThrow()
            }

            ledgerRepository.createRestingEntryOrder(
                command = command,
                orderId = UUID.randomUUID(),
                tradeGroupId = UUID.randomUUID(),
            ).getOrThrow()
        }
    }

    override suspend fun closePosition(command: ClosePositionCommand): Result<PaperTradeResult> {
        return runCatching {
            validateReason(command.reasonJa)

            val openPositions = ledgerRepository.getOpenPositions().getOrThrow()
            val targetPositions = resolveCloseTargets(command, openPositions)
            val ticker = tickerFor(TradingSymbol.BTC).getOrThrow()
            val symbolRules = symbolRulesFor(TradingSymbol.BTC).getOrThrow()
            val results = targetPositions.map { position ->
                val fill = fillSimulator.marketFill(
                    side = OrderSide.SELL,
                    sizeBtc = position.sizeBtc.toBigDecimal(),
                    ticker = ticker,
                    rules = symbolRules,
                )

                ledgerRepository.closePosition(
                    command = command,
                    positionId = UUID.fromString(position.positionId),
                    orderId = UUID.randomUUID(),
                    fill = fill,
                ).getOrThrow()
            }

            mergeTradeResults(results, "position を close しました。")
        }
    }

    override suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult> {
        return runCatching {
            validateReason(command.reasonJa)
            validateProtectionUpdateHasChange(command)
            validateStopPriceIfPresent(command)

            ledgerRepository.updateProtection(command).getOrThrow()
        }
    }

    override suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult> {
        return runCatching {
            validateReason(command.reasonJa)

            ledgerRepository.cancelOrder(command).getOrThrow()
        }
    }

    override suspend fun reconcile(tickSnapshot: TickSnapshot): Result<PaperReconcileResult> {
        return ledgerRepository.reconcile(tickSnapshot, fillSimulator)
    }

    private fun protectionStatus(
        positions: List<Position>,
        openOrders: List<Order>,
        reconcilerStatus: ReconcilerStatus,
    ): ProtectionStatus {
        val openPositions = positions.filter { position -> position.status == PositionStatus.OPEN }
        val activeStopOrderPositionIds = openOrders
            .filter { order -> order.isActiveProtectionStop() }
            .mapNotNull { order -> order.positionId }
            .toSet()
        val protectedPositionCount = openPositions.count { position ->
            position.currentStopLossJpy != null && position.positionId in activeStopOrderPositionIds
        }
        val unprotectedPositionCount = openPositions.size - protectedPositionCount
        val orphanStopCount = openOrders.count { order -> order.orderType == OrderType.STOP && order.positionId == null }
        val orphanTakeProfitCount = openOrders.count { order -> order.isTakeProfitCandidate() && order.positionId == null }
        val pendingCancelCount = openOrders.count { order -> order.status == OrderStatus.PENDING_CANCEL }

        return ProtectionStatus(
            protectedPositionCount = protectedPositionCount,
            unprotectedPositionCount = unprotectedPositionCount,
            orphanStopCount = orphanStopCount,
            orphanTakeProfitCount = orphanTakeProfitCount,
            pendingCancelCount = pendingCancelCount,
            lastReconciledAt = reconcilerStatus.lastReconciledAt?.toString(),
            lastMarketDataAt = reconcilerStatus.lastMarketDataAt?.toString(),
            tradingLockOwner = null,
        )
    }
}

private suspend fun PaperBroker.findExistingPlaceOrderResult(command: PlaceOrderCommand): PaperTradeResult? {
    val clientRequestId = command.auditContext.clientRequestId?.takeIf { requestId -> requestId.isNotBlank() }
        ?: return null

    return ledgerRepository.findPlaceOrderResultByClientRequestId(clientRequestId).getOrThrow()
}

private suspend fun PaperBroker.tickerFor(symbol: TradingSymbol): Result<Ticker> {
    val marketData = requireNotNull(marketDataSource) {
        "MarketDataSource is required for paper execution."
    }

    return marketData.getTicker(symbol)
}

private suspend fun PaperBroker.symbolRulesFor(symbol: TradingSymbol): Result<SymbolRules> {
    val marketData = requireNotNull(marketDataSource) {
        "MarketDataSource is required for paper execution."
    }

    return marketData.getSymbolRules(symbol)
}

private fun validatePlaceOrderCommand(command: PlaceOrderCommand) {
    validateReason(command.reasonJa)

    require(command.symbol == TradingSymbol.BTC) {
        "BTC spot is the only supported symbol."
    }
    require(command.side == OrderSide.BUY) {
        "place_order supports BUY entry only. Use close_position for SELL."
    }
    require(command.sizeBtc > BigDecimal.ZERO) {
        "sizeBtc must be greater than zero."
    }
}

private fun validateSymbolRules(command: PlaceOrderCommand, rules: SymbolRules) {
    val minOrderSize = rules.minOrderSize.toBigDecimal()
    val sizeStep = rules.sizeStep.toBigDecimal()
    val tickSize = rules.tickSize.toBigDecimal()

    require(command.sizeBtc >= minOrderSize) {
        "sizeBtc must be at least GMO minOrderSize $minOrderSize."
    }
    require(command.sizeBtc.isMultipleOf(sizeStep)) {
        "sizeBtc must be aligned to GMO sizeStep $sizeStep."
    }

    command.priceJpy?.let { price ->
        require(price.isMultipleOf(tickSize)) {
            "priceJpy must be aligned to GMO tickSize $tickSize."
        }
    }
    require(command.protectiveStopPriceJpy.isMultipleOf(tickSize)) {
        "protectiveStopPriceJpy must be aligned to GMO tickSize $tickSize."
    }
    command.takeProfitPriceJpy?.let { takeProfitPrice ->
        require(takeProfitPrice.isMultipleOf(tickSize)) {
            "takeProfitPriceJpy must be aligned to GMO tickSize $tickSize."
        }
    }
}

private fun validateEntryPriceContract(command: PlaceOrderCommand, ticker: Ticker) {
    val price = command.priceJpy
    val ask = ticker.ask.toBigDecimal()

    when (command.orderType) {
        OrderType.MARKET -> require(price == null) {
            "MARKET order must not include priceJpy."
        }
        OrderType.LIMIT -> requireNotNull(price) {
            "LIMIT order requires priceJpy."
        }
        OrderType.STOP -> validateStopEntryPrice(price, ask)
    }

    val referenceEntryPrice = price ?: ask

    require(command.protectiveStopPriceJpy < referenceEntryPrice) {
        "protectiveStopPriceJpy must be below entry price for long BTC spot."
    }
    command.takeProfitPriceJpy?.let { takeProfitPrice ->
        require(takeProfitPrice > referenceEntryPrice) {
            "takeProfitPriceJpy must be above entry price for long BTC spot."
        }
    }
}

private fun validateStopEntryPrice(price: BigDecimal?, ask: BigDecimal) {
    val stopPrice = requireNotNull(price) {
        "STOP order requires priceJpy."
    }

    require(stopPrice > ask) {
        "BUY STOP price would trigger immediately."
    }
}

private suspend fun PaperBroker.validateCashAvailability(
    command: PlaceOrderCommand,
    ticker: Ticker,
    rules: SymbolRules,
) {
    val balance = ledgerRepository.getAccountSnapshot().getOrThrow()
    val openOrders = ledgerRepository.getOpenOrders().getOrThrow()
    val cashJpy = balance.cashJpy.toBigDecimal()
    val reservedCashJpy = openOrders
        .filter { order -> order.side == OrderSide.BUY && order.status == OrderStatus.OPEN }
        .sumOf { order -> order.estimatedBuyReservationJpy(rules) }
    val requiredCash = command.estimatedRequiredCash(ticker, rules, fillSimulator)
    val availableCash = cashJpy.subtract(reservedCashJpy).moneyScale()

    require(requiredCash <= availableCash) {
        "Insufficient JPY cash for paper order. required=$requiredCash available=$availableCash."
    }
}

private fun PlaceOrderCommand.estimatedRequiredCash(
    ticker: Ticker,
    rules: SymbolRules,
    fillSimulator: FillSimulator,
): BigDecimal {
    if (orderType == OrderType.MARKET) {
        val fill = fillSimulator.marketFill(side, sizeBtc, ticker, rules)

        return fill.priceJpy.multiply(sizeBtc).add(fill.feeJpy).moneyScale()
    }

    val estimatedPrice = requireNotNull(priceJpy) {
        "$orderType order requires priceJpy."
    }
    val estimatedNotional = estimatedPrice.multiply(sizeBtc)
    val estimatedFee = estimatedNotional.multiply(rules.takerFee.toBigDecimal())

    return estimatedNotional.add(estimatedFee).moneyScale()
}

private fun Order.estimatedBuyReservationJpy(rules: SymbolRules): BigDecimal {
    val price = limitPriceJpy?.toBigDecimal()
        ?: triggerPriceJpy?.toBigDecimal()
        ?: BigDecimal.ZERO
    val notional = price.multiply(sizeBtc.toBigDecimal())
    val fee = notional.multiply(rules.takerFee.toBigDecimal())

    return notional.add(fee).moneyScale()
}

private fun validateProtectionUpdateHasChange(command: UpdateProtectionCommand) {
    val hasStopChange = command.newStopPriceJpy != null
    val hasTakeProfitChange = command.takeProfitPriceSpecified

    require(hasStopChange || hasTakeProfitChange) {
        "update_protection requires newStopPriceJpy or newTakeProfitPriceJpy."
    }
}

private suspend fun PaperBroker.validateStopPriceIfPresent(command: UpdateProtectionCommand) {
    val newStopPrice = command.newStopPriceJpy ?: return
    val ticker = tickerFor(TradingSymbol.BTC).getOrThrow()
    val symbolRules = symbolRulesFor(TradingSymbol.BTC).getOrThrow()
    val bid = ticker.bid.toBigDecimal()
    val tickSize = symbolRules.tickSize.toBigDecimal()

    require(newStopPrice.isMultipleOf(tickSize)) {
        "newStopPriceJpy must be aligned to GMO tickSize $tickSize."
    }
    require(newStopPrice < bid) {
        "SELL STOP price would trigger immediately."
    }
}

private fun resolveCloseTargets(command: ClosePositionCommand, openPositions: List<Position>): List<Position> {
    val targetPositions = if (command.closeAll) {
        openPositions
    } else {
        val positionId = requireNotNull(command.positionId) {
            "positionId is required unless closeAll is true."
        }

        openPositions.filter { position -> position.positionId == positionId.toString() }
    }

    require(targetPositions.isNotEmpty()) {
        "No open position matched close_position command."
    }

    return targetPositions
}

private fun mergeTradeResults(results: List<PaperTradeResult>, messageJa: String): PaperTradeResult {
    return PaperTradeResult(
        accepted = true,
        status = OrderStatus.FILLED,
        orderIds = results.flatMap { result -> result.orderIds },
        positionIds = results.flatMap { result -> result.positionIds },
        executionIds = results.flatMap { result -> result.executionIds },
        messageJa = messageJa,
    )
}

private fun validateReason(reasonJa: String) {
    require(reasonJa.isNotBlank()) {
        "reason is required."
    }
}

private fun BigDecimal.isMultipleOf(step: BigDecimal): Boolean {
    if (step.compareTo(BigDecimal.ZERO) == 0) {
        return true
    }

    return remainder(step).abs().compareTo(BigDecimal.ZERO) == 0
}

private fun Order.isActiveProtectionStop(): Boolean {
    return side == OrderSide.SELL && orderType == OrderType.STOP && status == OrderStatus.OPEN
}

private fun Order.isTakeProfitCandidate(): Boolean {
    return orderType == OrderType.LIMIT && side == OrderSide.SELL
}

/**
 * 取引日判定に使う timezone。
 */
private val TRADING_DATE_ZONE = ZoneId.of("Asia/Tokyo")
