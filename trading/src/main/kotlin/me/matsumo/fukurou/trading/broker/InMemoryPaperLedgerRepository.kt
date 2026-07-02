package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * unit test と明示 injection 用の mutable paper ledger repository。
 *
 * @param accountSnapshot 残高 snapshot
 * @param positions position 一覧
 * @param openOrders open order 一覧
 * @param executions execution 一覧
 */
class InMemoryPaperLedgerRepository(
    accountSnapshot: AccountSnapshot = defaultAccountSnapshot(),
    positions: List<Position> = emptyList(),
    openOrders: List<Order> = emptyList(),
    executions: List<Execution> = emptyList(),
) : PaperLedgerRepository {

    private val lock = Any()
    private var accountSnapshot: AccountSnapshot = accountSnapshot
    private val positions = positions.toMutableList()
    private val orders = openOrders.toMutableList()
    private val executions = executions.toMutableList()

    override suspend fun getAccountSnapshot(): Result<AccountSnapshot> {
        return Result.success(synchronized(lock) { accountSnapshot })
    }

    override suspend fun getOpenPositions(): Result<List<Position>> {
        return Result.success(
            synchronized(lock) {
                positions.filter { position -> position.status == PositionStatus.OPEN }
            },
        )
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return Result.success(
            synchronized(lock) {
                orders.filter { order -> order.status == OrderStatus.OPEN || order.status == OrderStatus.PENDING_CANCEL }
            },
        )
    }

    override suspend fun getRealizedPnlForDate(date: LocalDate): Result<BigDecimal> {
        return Result.success(
            synchronized(lock) {
                executions.sumOf { execution -> execution.realizedPnlJpy.toBigDecimal() }
            },
        )
    }

    override suspend fun getExecutions(): Result<List<Execution>> {
        return Result.success(synchronized(lock) { executions.toList() })
    }

    override suspend fun findPlaceOrderResultByClientRequestId(clientRequestId: String): Result<PaperTradeResult?> {
        return Result.success(
            synchronized(lock) {
                findPlaceOrderResultByClientRequestIdLocked(clientRequestId)
            },
        )
    }

    override suspend fun fillMarketEntry(
        command: PlaceOrderCommand,
        fill: SimulatedFill,
        positionId: UUID,
        tradeGroupId: UUID,
        stopOrderId: UUID,
    ): Result<PaperTradeResult> {
        return runCatching {
            synchronized(lock) {
                fillEntryLocked(
                    command = command,
                    fill = fill,
                    positionId = positionId,
                    tradeGroupId = tradeGroupId,
                    entryOrderId = command.commandId,
                    stopOrderId = stopOrderId,
                    insertEntryOrder = true,
                )
            }
        }
    }

    override suspend fun createRestingEntryOrder(
        command: PlaceOrderCommand,
        orderId: UUID,
        tradeGroupId: UUID,
    ): Result<PaperTradeResult> {
        return runCatching {
            synchronized(lock) {
                val order = command.toEntryOrder(
                    orderId = orderId,
                    positionId = null,
                    tradeGroupId = tradeGroupId,
                    status = OrderStatus.OPEN,
                )

                orders += order

                PaperTradeResult(
                    accepted = true,
                    status = OrderStatus.OPEN,
                    orderIds = listOf(order.orderId),
                    positionIds = emptyList(),
                    executionIds = emptyList(),
                    messageJa = "resting entry intent を保存しました。",
                )
            }
        }
    }

    override suspend fun closePosition(
        command: ClosePositionCommand,
        positionId: UUID,
        orderId: UUID,
        fill: SimulatedFill,
    ): Result<PaperTradeResult> {
        return runCatching {
            synchronized(lock) {
                closePositionLocked(
                    positionId = positionId.toString(),
                    orderId = orderId,
                    fill = fill,
                    reasonJa = command.reasonJa,
                )
            }
        }
    }

    override suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult> {
        return runCatching {
            synchronized(lock) {
                val positionIndex = positions.indexOfFirst { position -> position.positionId == command.positionId.toString() }

                require(positionIndex >= 0) {
                    "position was not found."
                }
                require(positions[positionIndex].status == PositionStatus.OPEN) {
                    "position is not open."
                }

                val updatedPosition = positions[positionIndex].copy(
                    currentStopLossJpy = command.newStopPriceJpy?.toPlainString()
                        ?: positions[positionIndex].currentStopLossJpy,
                    currentTakeProfitJpy = if (command.takeProfitPriceSpecified) {
                        command.newTakeProfitPriceJpy?.toPlainString()
                    } else {
                        positions[positionIndex].currentTakeProfitJpy
                    },
                )

                positions[positionIndex] = updatedPosition

                command.newStopPriceJpy?.let { stopPrice ->
                    updateLinkedStopOrderLocked(command.positionId.toString(), stopPrice, command.reasonJa)
                }

                PaperTradeResult(
                    accepted = true,
                    status = OrderStatus.OPEN,
                    orderIds = orders
                        .filter { order -> order.positionId == command.positionId.toString() && order.orderType == OrderType.STOP }
                        .map { order -> order.orderId },
                    positionIds = listOf(command.positionId.toString()),
                    executionIds = emptyList(),
                    messageJa = "position の保護を更新しました。",
                )
            }
        }
    }

    override suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult> {
        return runCatching {
            synchronized(lock) {
                val orderIndex = orders.indexOfFirst { order -> order.orderId == command.orderId.toString() }

                require(orderIndex >= 0) {
                    "order was not found."
                }

                val order = orders[orderIndex]
                val isProtectiveStop = order.side == OrderSide.SELL && order.orderType == OrderType.STOP && order.positionId != null

                require(!isProtectiveStop) {
                    "protective STOP cannot be cancelled directly. Use update_protection or close_position."
                }
                require(order.status == OrderStatus.OPEN || order.status == OrderStatus.PENDING_CANCEL) {
                    "order is not cancelable."
                }

                orders[orderIndex] = order.copy(
                    status = OrderStatus.CANCELED,
                    reasonJa = command.reasonJa,
                )

                PaperTradeResult(
                    accepted = true,
                    status = OrderStatus.CANCELED,
                    orderIds = listOf(order.orderId),
                    positionIds = listOfNotNull(order.positionId),
                    executionIds = emptyList(),
                    messageJa = "order を cancel しました。",
                )
            }
        }
    }

    override suspend fun reconcile(tickSnapshot: TickSnapshot, simulator: FillSimulator): Result<PaperReconcileResult> {
        return runCatching {
            synchronized(lock) {
                val ticker = tickSnapshot.toTicker()
                val rules = tickSnapshot.symbolRules ?: defaultSymbolRules()
                val lastPrice = tickSnapshot.lastPrice?.toBigDecimal() ?: ticker.last.toBigDecimal()
                val triggeredOrderIds = mutableListOf<String>()
                val closedPositionIds = mutableListOf<String>()
                val executionIds = mutableListOf<String>()

                updateMarksLocked(lastPrice, tickSnapshot.atr14Jpy?.toBigDecimal(), rules)
                fillTriggeredEntryOrdersLocked(ticker, rules, simulator, lastPrice, triggeredOrderIds, executionIds)
                triggerPositionProtectionsLocked(ticker, rules, simulator, lastPrice, triggeredOrderIds, closedPositionIds, executionIds)

                PaperReconcileResult(
                    advanced = triggeredOrderIds.isNotEmpty() || closedPositionIds.isNotEmpty(),
                    triggeredOrderIds = triggeredOrderIds,
                    closedPositionIds = closedPositionIds,
                    executionIds = executionIds,
                )
            }
        }
    }

    private fun closePositionLocked(
        positionId: String,
        orderId: UUID,
        fill: SimulatedFill,
        reasonJa: String,
    ): PaperTradeResult {
        val positionIndex = positions.indexOfFirst { position -> position.positionId == positionId }

        require(positionIndex >= 0) {
            "position was not found."
        }

        val position = positions[positionIndex]

        require(position.status == PositionStatus.OPEN) {
            "position is not open."
        }

        val realizedFill = fill.withRealizedPnl(position)
        val closeOrder = closeOrder(orderId, position, reasonJa)
        val closedPosition = position.copy(
            status = PositionStatus.CLOSED,
            closedAt = realizedFill.executedAt.toString(),
            currentPriceJpy = realizedFill.priceJpy.toPlainString(),
            currentStopLossJpy = null,
            currentTakeProfitJpy = null,
            unrealizedPnlJpy = BigDecimal.ZERO.moneyScale().toPlainString(),
            unrealizedR = BigDecimal.ZERO.toPlainString(),
        )

        orders += closeOrder
        positions[positionIndex] = closedPosition
        executions += realizedFill.toExecution(
            orderId = closeOrder.orderId,
            positionId = position.positionId,
            mode = position.mode,
            side = OrderSide.SELL,
        )
        cancelOpenStopOrdersLocked(position.positionId, reasonJa)
        accountSnapshot = accountSnapshot.afterSellFill(realizedFill)

        return PaperTradeResult(
            accepted = true,
            status = OrderStatus.FILLED,
            orderIds = listOf(closeOrder.orderId),
            positionIds = listOf(position.positionId),
            executionIds = listOf(realizedFill.executionId.toString()),
            messageJa = "position を close しました。",
        )
    }

    private fun findPlaceOrderResultByClientRequestIdLocked(clientRequestId: String): PaperTradeResult? {
        val entryOrder = orders.firstOrNull { order ->
            order.clientRequestId == clientRequestId && order.side == OrderSide.BUY
        } ?: return null
        val tradeGroupId = requireNotNull(entryOrder.tradeGroupId)
        val relatedOrders = orders.filter { order -> order.tradeGroupId == tradeGroupId }
        val relatedOrderIds = relatedOrders.map { order -> order.orderId }.toSet()
        val relatedPositionIds = positions
            .filter { position -> position.tradeGroupId == tradeGroupId }
            .map { position -> position.positionId }
        val relatedExecutionIds = executions
            .filter { execution -> execution.orderId in relatedOrderIds }
            .map { execution -> execution.executionId }

        return PaperTradeResult(
            accepted = true,
            status = entryOrder.status,
            orderIds = relatedOrders.map { order -> order.orderId },
            positionIds = relatedPositionIds,
            executionIds = relatedExecutionIds,
            messageJa = "client_request_id に一致する既存 paper entry を返しました。",
        )
    }

    private fun fillTriggeredEntryOrdersLocked(
        ticker: Ticker,
        rules: SymbolRules,
        simulator: FillSimulator,
        lastPrice: BigDecimal,
        triggeredOrderIds: MutableList<String>,
        executionIds: MutableList<String>,
    ) {
        val entryOrders = orders
            .filter { order -> order.status == OrderStatus.OPEN && order.side == OrderSide.BUY }
            .filter { order -> order.isEntryTriggered(lastPrice) }

        entryOrders.forEach { order ->
            val fill = order.createEntryFill(ticker, rules, simulator)
            val positionId = UUID.randomUUID()
            val tradeGroupId = UUID.fromString(requireNotNull(order.tradeGroupId))
            val stopOrderId = UUID.randomUUID()
            val command = order.toPlaceOrderCommand()

            if (!accountSnapshot.hasCashForBuyFill(fill)) {
                markOrderStatusLocked(order.orderId, OrderStatus.REJECTED, "reconciler entry rejected: insufficient paper cash")
                triggeredOrderIds += order.orderId

                return@forEach
            }

            markOrderStatusLocked(order.orderId, OrderStatus.FILLED)
            fillEntryLocked(
                command = command,
                fill = fill,
                positionId = positionId,
                tradeGroupId = tradeGroupId,
                entryOrderId = UUID.fromString(order.orderId),
                stopOrderId = stopOrderId,
                insertEntryOrder = false,
            )
            triggeredOrderIds += order.orderId
            executionIds += fill.executionId.toString()
        }
    }

    private fun fillEntryLocked(
        command: PlaceOrderCommand,
        fill: SimulatedFill,
        positionId: UUID,
        tradeGroupId: UUID,
        entryOrderId: UUID,
        stopOrderId: UUID,
        insertEntryOrder: Boolean,
    ): PaperTradeResult {
        val entryOrder = command.toEntryOrder(
            orderId = entryOrderId,
            positionId = positionId,
            tradeGroupId = tradeGroupId,
            status = OrderStatus.FILLED,
        )
        val stopOrder = command.toProtectiveStopOrder(stopOrderId, positionId, tradeGroupId)
        val position = command.toOpenPosition(positionId, tradeGroupId, fill)

        if (insertEntryOrder) {
            orders += entryOrder
        }
        orders += stopOrder
        positions += position
        executions += fill.toExecution(entryOrder.orderId, position.positionId, command)
        accountSnapshot = accountSnapshot.afterBuyFill(fill)

        return PaperTradeResult(
            accepted = true,
            status = OrderStatus.FILLED,
            orderIds = listOf(entryOrder.orderId, stopOrder.orderId),
            positionIds = listOf(position.positionId),
            executionIds = listOf(fill.executionId.toString()),
            messageJa = "paper entry を約定し、保護 STOP を作成しました。",
        )
    }

    private fun triggerPositionProtectionsLocked(
        ticker: Ticker,
        rules: SymbolRules,
        simulator: FillSimulator,
        lastPrice: BigDecimal,
        triggeredOrderIds: MutableList<String>,
        closedPositionIds: MutableList<String>,
        executionIds: MutableList<String>,
    ) {
        val openPositions = positions.filter { position -> position.status == PositionStatus.OPEN }

        openPositions.forEach { position ->
            val stopPrice = position.currentStopLossJpy?.toBigDecimal()
            val takeProfitPrice = position.currentTakeProfitJpy?.toBigDecimal()
            val stopTriggered = stopPrice != null && lastPrice <= stopPrice
            val takeProfitTriggered = takeProfitPrice != null && lastPrice >= takeProfitPrice

            if (stopTriggered) {
                val stopOrder = linkedStopOrder(position.positionId)
                val fill = simulator.stopFill(OrderSide.SELL, position.sizeBtc.toBigDecimal(), requireNotNull(stopPrice), ticker, rules)
                val result = closePositionLocked(
                    positionId = position.positionId,
                    orderId = UUID.fromString(requireNotNull(stopOrder).orderId),
                    fill = fill,
                    reasonJa = "reconciler stop trigger",
                )

                markOrderStatusLocked(stopOrder.orderId, OrderStatus.FILLED)
                triggeredOrderIds += stopOrder.orderId
                closedPositionIds += position.positionId
                executionIds += result.executionIds

                return@forEach
            }

            if (takeProfitTriggered) {
                linkedStopOrder(position.positionId)?.let { stopOrder ->
                    markOrderStatusLocked(stopOrder.orderId, OrderStatus.CANCELED)
                    triggeredOrderIds += stopOrder.orderId
                }

                val fill = simulator.marketFill(OrderSide.SELL, position.sizeBtc.toBigDecimal(), ticker, rules)
                val result = closePositionLocked(
                    positionId = position.positionId,
                    orderId = UUID.randomUUID(),
                    fill = fill,
                    reasonJa = "reconciler virtual take profit trigger",
                )

                closedPositionIds += position.positionId
                executionIds += result.executionIds
            }
        }
    }

    private fun updateMarksLocked(lastPrice: BigDecimal, atr14Jpy: BigDecimal?, rules: SymbolRules) {
        positions.replaceAll { position ->
            if (position.status != PositionStatus.OPEN) {
                return@replaceAll position
            }

            val entryPrice = position.averageEntryPriceJpy.toBigDecimal()
            val sizeBtc = position.sizeBtc.toBigDecimal()
            val highestPrice = maxOf(position.highestPriceSinceEntryJpy.toBigDecimal(), lastPrice)
            val trailingStop = atr14Jpy?.let { atrValue ->
                highestPrice
                    .subtract(atrValue.multiply(TRAILING_ATR_MULTIPLIER))
                    .floorToStep(rules.tickSize.toBigDecimal())
            }
            val currentStop = position.currentStopLossJpy?.toBigDecimal()
            val tightenedStop = listOfNotNull(currentStop, trailingStop).maxOrNull()
            val unrealizedPnl = lastPrice.subtract(entryPrice).multiply(sizeBtc).moneyScale()

            if (tightenedStop != currentStop && tightenedStop != null) {
                updateLinkedStopOrderLocked(position.positionId, tightenedStop, "reconciler atr trailing floor")
            }

            position.copy(
                currentPriceJpy = lastPrice.moneyScale().toPlainString(),
                currentStopLossJpy = tightenedStop?.moneyScale()?.toPlainString(),
                unrealizedPnlJpy = unrealizedPnl.toPlainString(),
                highestPriceSinceEntryJpy = highestPrice.moneyScale().toPlainString(),
            )
        }

        accountSnapshot = accountSnapshot.withMarkPrice(lastPrice)
    }

    private fun updateLinkedStopOrderLocked(positionId: String, stopPrice: BigDecimal, reasonJa: String) {
        val stopOrderIndex = orders.indexOfFirst { order ->
            order.positionId == positionId && order.side == OrderSide.SELL && order.orderType == OrderType.STOP && order.status == OrderStatus.OPEN
        }

        require(stopOrderIndex >= 0) {
            "linked protective STOP order was not found."
        }

        orders[stopOrderIndex] = orders[stopOrderIndex].copy(
            triggerPriceJpy = stopPrice.moneyScale().toPlainString(),
            reasonJa = reasonJa,
        )
    }

    private fun cancelOpenStopOrdersLocked(positionId: String, reasonJa: String) {
        orders.replaceAll { order ->
            val isLinkedOpenStop = order.positionId == positionId &&
                order.side == OrderSide.SELL &&
                order.orderType == OrderType.STOP &&
                order.status == OrderStatus.OPEN

            if (!isLinkedOpenStop) {
                return@replaceAll order
            }

            order.copy(
                status = OrderStatus.CANCELED,
                reasonJa = reasonJa,
            )
        }
    }

    private fun markOrderStatusLocked(orderId: String, status: OrderStatus, reasonJa: String? = null) {
        val orderIndex = orders.indexOfFirst { order -> order.orderId == orderId }

        if (orderIndex >= 0) {
            orders[orderIndex] = orders[orderIndex].copy(
                status = status,
                reasonJa = reasonJa ?: orders[orderIndex].reasonJa,
            )
        }
    }

    private fun linkedStopOrder(positionId: String): Order? {
        return orders.firstOrNull { order ->
            order.positionId == positionId && order.side == OrderSide.SELL && order.orderType == OrderType.STOP && order.status == OrderStatus.OPEN
        }
    }
}

private fun PlaceOrderCommand.toEntryOrder(
    orderId: UUID,
    positionId: UUID?,
    tradeGroupId: UUID,
    status: OrderStatus,
): Order {
    return Order(
        orderId = orderId.toString(),
        positionId = positionId?.toString(),
        tradeGroupId = tradeGroupId.toString(),
        symbol = symbol.apiSymbol,
        mode = TradingMode.PAPER,
        side = side,
        orderType = orderType,
        status = status,
        sizeBtc = sizeBtc.btcScale().toPlainString(),
        limitPriceJpy = if (orderType == OrderType.LIMIT) priceJpy?.moneyScale()?.toPlainString() else null,
        triggerPriceJpy = if (orderType == OrderType.STOP) priceJpy?.moneyScale()?.toPlainString() else null,
        protectiveStopPriceJpy = protectiveStopPriceJpy.moneyScale().toPlainString(),
        takeProfitPriceJpy = takeProfitPriceJpy?.moneyScale()?.toPlainString(),
        reasonJa = reasonJa,
        clientRequestId = auditContext.clientRequestId,
        createdAt = fillInstantText(),
        updatedAt = fillInstantText(),
    )
}

private fun PlaceOrderCommand.toProtectiveStopOrder(orderId: UUID, positionId: UUID, tradeGroupId: UUID): Order {
    return Order(
        orderId = orderId.toString(),
        positionId = positionId.toString(),
        tradeGroupId = tradeGroupId.toString(),
        symbol = symbol.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.SELL,
        orderType = OrderType.STOP,
        status = OrderStatus.OPEN,
        sizeBtc = sizeBtc.btcScale().toPlainString(),
        limitPriceJpy = null,
        triggerPriceJpy = protectiveStopPriceJpy.moneyScale().toPlainString(),
        protectiveStopPriceJpy = null,
        takeProfitPriceJpy = null,
        reasonJa = "protective stop: $reasonJa",
        clientRequestId = auditContext.clientRequestId,
        createdAt = fillInstantText(),
        updatedAt = fillInstantText(),
    )
}

private fun PlaceOrderCommand.toOpenPosition(positionId: UUID, tradeGroupId: UUID, fill: SimulatedFill): Position {
    return Position(
        positionId = positionId.toString(),
        tradeGroupId = tradeGroupId.toString(),
        symbol = symbol.apiSymbol,
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = fill.executedAt.toString(),
        closedAt = null,
        sizeBtc = sizeBtc.btcScale().toPlainString(),
        averageEntryPriceJpy = fill.priceJpy.moneyScale().toPlainString(),
        currentPriceJpy = fill.priceJpy.moneyScale().toPlainString(),
        currentStopLossJpy = protectiveStopPriceJpy.moneyScale().toPlainString(),
        currentTakeProfitJpy = takeProfitPriceJpy?.moneyScale()?.toPlainString(),
        unrealizedPnlJpy = BigDecimal.ZERO.moneyScale().toPlainString(),
        unrealizedR = BigDecimal.ZERO.toPlainString(),
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = fill.priceJpy.moneyScale().toPlainString(),
    )
}

private fun Order.toPlaceOrderCommand(): PlaceOrderCommand {
    val price = limitPriceJpy?.toBigDecimal() ?: triggerPriceJpy?.toBigDecimal()

    return PlaceOrderCommand(
        commandId = UUID.fromString(orderId),
        symbol = TradingSymbol.BTC,
        side = side,
        orderType = orderType,
        sizeBtc = sizeBtc.toBigDecimal(),
        priceJpy = price,
        tradeGroupId = tradeGroupId?.let { value -> UUID.fromString(value) },
        protectiveStopPriceJpy = requireNotNull(protectiveStopPriceJpy).toBigDecimal(),
        takeProfitPriceJpy = takeProfitPriceJpy?.toBigDecimal(),
        expectedValueR = DEFAULT_RESTORED_EXPECTED_VALUE_R,
        expectedMoveToCostRatio = DEFAULT_RESTORED_EXPECTED_MOVE_TO_COST_RATIO,
        reasonJa = reasonJa.orEmpty(),
        auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = clientRequestId),
    )
}

private fun SimulatedFill.toExecution(orderId: String, positionId: String, command: PlaceOrderCommand): Execution {
    return toExecution(
        orderId = orderId,
        positionId = positionId,
        mode = TradingMode.PAPER,
        side = command.side,
    )
}

private fun SimulatedFill.toExecution(
    orderId: String,
    positionId: String,
    mode: TradingMode,
    side: OrderSide,
): Execution {
    return Execution(
        executionId = executionId.toString(),
        orderId = orderId,
        positionId = positionId,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = mode,
        side = side,
        priceJpy = priceJpy.moneyScale().toPlainString(),
        sizeBtc = sizeBtc.btcScale().toPlainString(),
        feeJpy = feeJpy.moneyScale().toPlainString(),
        realizedPnlJpy = realizedPnlJpy.moneyScale().toPlainString(),
        liquidity = liquidity,
        executedAt = executedAt.toString(),
    )
}

private fun closeOrder(orderId: UUID, position: Position, reasonJa: String): Order {
    return Order(
        orderId = orderId.toString(),
        positionId = position.positionId,
        tradeGroupId = position.tradeGroupId,
        symbol = position.symbol,
        mode = position.mode,
        side = OrderSide.SELL,
        orderType = OrderType.MARKET,
        status = OrderStatus.FILLED,
        sizeBtc = position.sizeBtc,
        limitPriceJpy = null,
        triggerPriceJpy = null,
        protectiveStopPriceJpy = null,
        takeProfitPriceJpy = null,
        reasonJa = reasonJa,
        clientRequestId = null,
        createdAt = fillInstantText(),
        updatedAt = fillInstantText(),
    )
}

private fun SimulatedFill.withRealizedPnl(position: Position): SimulatedFill {
    val entryPrice = position.averageEntryPriceJpy.toBigDecimal()
    val grossPnl = priceJpy.subtract(entryPrice).multiply(sizeBtc)
    val realizedPnl = grossPnl.subtract(feeJpy).moneyScale()

    return copy(realizedPnlJpy = realizedPnl)
}

private fun AccountSnapshot.afterBuyFill(fill: SimulatedFill): AccountSnapshot {
    val spentCash = fill.priceJpy.multiply(fill.sizeBtc).add(fill.feeJpy)
    val cash = cashJpy.toBigDecimal().subtract(spentCash).moneyScale()
    val btcQuantity = btcQuantity.toBigDecimal().add(fill.sizeBtc).btcScale()

    return copy(cashJpy = cash.toPlainString())
        .withBtcQuantity(btcQuantity)
        .withMarkPrice(fill.priceJpy)
}

private fun AccountSnapshot.hasCashForBuyFill(fill: SimulatedFill): Boolean {
    val spentCash = fill.priceJpy.multiply(fill.sizeBtc).add(fill.feeJpy).moneyScale()

    return spentCash <= cashJpy.toBigDecimal()
}

private fun AccountSnapshot.afterSellFill(fill: SimulatedFill): AccountSnapshot {
    val receivedCash = fill.priceJpy.multiply(fill.sizeBtc).subtract(fill.feeJpy)
    val cash = cashJpy.toBigDecimal().add(receivedCash).moneyScale()
    val btcQuantity = btcQuantity.toBigDecimal().subtract(fill.sizeBtc).btcScale()

    return copy(cashJpy = cash.toPlainString())
        .withBtcQuantity(btcQuantity)
        .withMarkPrice(fill.priceJpy)
}

private fun AccountSnapshot.withBtcQuantity(quantity: BigDecimal): AccountSnapshot {
    return copy(btcQuantity = quantity.btcScale().toPlainString())
}

private fun AccountSnapshot.withMarkPrice(markPrice: BigDecimal): AccountSnapshot {
    val btcValue = btcQuantity.toBigDecimal().multiply(markPrice)
    val totalEquity = cashJpy.toBigDecimal().add(btcValue).moneyScale()
    val equityPeak = maxOf(equityPeakJpy.toBigDecimal(), totalEquity).moneyScale()
    val drawdownRatio = if (equityPeak.compareTo(BigDecimal.ZERO) == 0) {
        BigDecimal.ZERO
    } else {
        totalEquity.subtract(equityPeak).divide(equityPeak, RATIO_SCALE, java.math.RoundingMode.HALF_UP)
    }

    return copy(
        btcMarkPriceJpy = markPrice.moneyScale().toPlainString(),
        totalEquityJpy = totalEquity.toPlainString(),
        equityPeakJpy = equityPeak.toPlainString(),
        drawdownRatio = drawdownRatio.ratioScale().toPlainString(),
    )
}

private fun Order.isEntryTriggered(lastPrice: BigDecimal): Boolean {
    return when (orderType) {
        OrderType.MARKET -> false
        OrderType.LIMIT -> limitPriceJpy?.toBigDecimal()?.let { price -> lastPrice <= price } ?: false
        OrderType.STOP -> triggerPriceJpy?.toBigDecimal()?.let { price -> lastPrice >= price } ?: false
    }
}

private fun Order.createEntryFill(ticker: Ticker, rules: SymbolRules, simulator: FillSimulator): SimulatedFill {
    return when (orderType) {
        OrderType.LIMIT -> simulator.restingLimitFill(sizeBtc.toBigDecimal(), requireNotNull(limitPriceJpy).toBigDecimal(), rules)
        OrderType.STOP -> simulator.stopFill(side, sizeBtc.toBigDecimal(), requireNotNull(triggerPriceJpy).toBigDecimal(), ticker, rules)
        OrderType.MARKET -> error("MARKET entry is not a resting order.")
    }
}

private fun TickSnapshot.toTicker(): Ticker {
    val last = lastPrice ?: bidPrice ?: askPrice ?: "0"
    val bid = bidPrice ?: last
    val ask = askPrice ?: last

    return Ticker(
        symbol = symbol,
        last = last,
        bid = bid,
        ask = ask,
        high = last,
        low = last,
        volume = "0",
        timestamp = observedAt.toString(),
    )
}

private fun defaultSymbolRules(): SymbolRules {
    return SymbolRules(
        symbol = TradingSymbol.BTC.apiSymbol,
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "1",
        takerFee = "0.0005",
        makerFee = "-0.0001",
    )
}

/**
 * 初期 paper 口座 snapshot を返す。
 */
private fun defaultAccountSnapshot(): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = "100000.00000000",
        initialCashJpy = "100000.00000000",
        btcQuantity = "0.000000000000",
        btcMarkPriceJpy = "0.00000000",
        totalEquityJpy = "100000.00000000",
        equityPeakJpy = "100000.00000000",
        drawdownRatio = "0E-10",
    )
}

private fun fillInstantText(): String {
    return java.time.Instant.EPOCH.toString()
}

/**
 * ATR trailing の既定係数。
 */
private val TRAILING_ATR_MULTIPLIER = BigDecimal("2.0")

/**
 * resting order 復元時の既定 expected value。
 */
private val DEFAULT_RESTORED_EXPECTED_VALUE_R = BigDecimal("1.0")

/**
 * resting order 復元時の既定 expected move / cost ratio。
 */
private val DEFAULT_RESTORED_EXPECTED_MOVE_TO_COST_RATIO = BigDecimal("10.0")
