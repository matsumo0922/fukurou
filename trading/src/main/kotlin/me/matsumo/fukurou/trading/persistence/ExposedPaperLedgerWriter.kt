package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.EntryFillWriteRequest
import me.matsumo.fukurou.trading.broker.FillSimulator
import me.matsumo.fukurou.trading.broker.IntentConsumingMarketEntryFillRequest
import me.matsumo.fukurou.trading.broker.IntentConsumingRestingEntryOrderRequest
import me.matsumo.fukurou.trading.broker.MarketEntryFillRequest
import me.matsumo.fukurou.trading.broker.PaperReconcileResult
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.PositionMarkUpdate
import me.matsumo.fukurou.trading.broker.ReconcileMarketContext
import me.matsumo.fukurou.trading.broker.ReconcileProgress
import me.matsumo.fukurou.trading.broker.RestingEntryFillRequest
import me.matsumo.fukurou.trading.broker.RestingEntryOrderRequest
import me.matsumo.fukurou.trading.broker.SimulatedFill
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.broker.btcScale
import me.matsumo.fukurou.trading.broker.floorToStep
import me.matsumo.fukurou.trading.broker.moneyScale
import me.matsumo.fukurou.trading.broker.ratioScale
import me.matsumo.fukurou.trading.domain.AccountSnapshot
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
import me.matsumo.fukurou.trading.evaluation.toFillEquitySnapshotRecord
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.reconciler.requireTicker
import me.matsumo.fukurou.trading.safety.SafetyFloorDefaults
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.PreparedStatement
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * paper ledger mutation 用 writer。
 *
 * @param database Exposed database
 * @param fallbackSymbolRules tick に symbol rules がない場合の fallback 取引ルール
 * @param clock DB 更新時刻に使う clock
 */
internal class ExposedPaperLedgerWriter(
    private val database: ExposedDatabase,
    private val fallbackSymbolRules: SymbolRules,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * MARKET entry を約定済みとして保存する。
     */
    suspend fun fillMarketEntry(request: MarketEntryFillRequest): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertEntryFill(
                        EntryFillWriteRequest(
                            entry = request,
                            entryOrderId = request.command.commandId,
                            insertEntryOrder = true,
                        ),
                    )
                }
            }
        }
    }

    /**
     * resting entry intent を保存する。
     */
    suspend fun createRestingEntryOrder(request: RestingEntryOrderRequest): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertEntryOrder(request.command, request.orderId, null, request.tradeGroupId, OrderStatus.OPEN)

                    PaperTradeResult(
                        accepted = true,
                        status = OrderStatus.OPEN,
                        orderIds = listOf(request.orderId.toString()),
                        positionIds = emptyList(),
                        executionIds = emptyList(),
                        messageJa = "resting entry intent を保存しました。",
                    )
                }
            }
        }
    }

    /**
     * MARKET entry と intent consumption を同一 transaction で保存する。
     */
    suspend fun fillMarketEntryAndConsumeIntent(
        request: IntentConsumingMarketEntryFillRequest,
    ): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertTradeIntentConsumption(
                        request.consumption.intentId,
                        request.entry.command.commandId,
                        request.consumption.consumedAt,
                    )
                    insertEntryFill(
                        EntryFillWriteRequest(
                            entry = request.entry,
                            entryOrderId = request.entry.command.commandId,
                            insertEntryOrder = true,
                        ),
                    )
                }
            }
        }
    }

    /**
     * resting entry order と intent consumption を同一 transaction で保存する。
     */
    suspend fun createRestingEntryOrderAndConsumeIntent(
        request: IntentConsumingRestingEntryOrderRequest,
    ): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertTradeIntentConsumption(
                        request.consumption.intentId,
                        request.order.orderId,
                        request.consumption.consumedAt,
                    )
                    insertEntryOrder(
                        request.order.command,
                        request.order.orderId,
                        null,
                        request.order.tradeGroupId,
                        OrderStatus.OPEN,
                    )

                    PaperTradeResult(
                        accepted = true,
                        status = OrderStatus.OPEN,
                        orderIds = listOf(request.order.orderId.toString()),
                        positionIds = emptyList(),
                        executionIds = emptyList(),
                        messageJa = "resting entry intent を保存しました。",
                    )
                }
            }
        }
    }

    /**
     * position を close する。
     */
    suspend fun closePosition(
        command: ClosePositionCommand,
        positionId: UUID,
        orderId: UUID,
        fill: SimulatedFill,
    ): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val position = requireOpenPosition(positionId)
                    val closeOrderId = orderId.toString()
                    val realizedFill = fill.withRealizedPnl(position)

                    insertCloseOrder(orderId, position, command.reasonJa, command.auditContext)
                    insertExecution(
                        ExecutionInsertRequest(
                            orderId = closeOrderId,
                            positionId = position.positionId,
                            mode = position.mode,
                            side = OrderSide.SELL,
                            fill = realizedFill,
                            auditContext = command.auditContext,
                        ),
                    )
                    closePositionRow(position, realizedFill)
                    cancelOpenStopOrders(position.positionId, command.reasonJa)
                    updateAccountAfterSell(realizedFill)

                    PaperTradeResult(
                        accepted = true,
                        status = OrderStatus.FILLED,
                        orderIds = listOf(closeOrderId),
                        positionIds = listOf(position.positionId),
                        executionIds = listOf(realizedFill.executionId.toString()),
                        messageJa = "position を close しました。",
                    )
                }
            }
        }
    }

    /**
     * position の保護を更新する。
     */
    suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val position = requireOpenPosition(command.positionId)
                    val newStopPrice = command.newStopPriceJpy
                    val newTakeProfitPrice = if (command.takeProfitPriceSpecified) {
                        command.newTakeProfitPriceJpy
                    } else {
                        position.currentTakeProfitJpy?.toBigDecimal()
                    }

                    if (newStopPrice != null) {
                        updateLinkedStopOrder(position.positionId, newStopPrice, command.reasonJa)
                    }
                    updatePositionProtection(position.positionId, newStopPrice, newTakeProfitPrice, command.takeProfitPriceSpecified)

                    PaperTradeResult(
                        accepted = true,
                        status = OrderStatus.OPEN,
                        orderIds = selectOpenOrders()
                            .filter { order -> order.positionId == position.positionId && order.orderType == OrderType.STOP }
                            .map { order -> order.orderId },
                        positionIds = listOf(position.positionId),
                        executionIds = emptyList(),
                        messageJa = "position の保護を更新しました。",
                    )
                }
            }
        }
    }

    /**
     * open order を cancel する。
     */
    suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val order = requireOpenOrder(command.orderId)
                    val isProtectiveStop = order.side == OrderSide.SELL && order.orderType == OrderType.STOP && order.positionId != null

                    require(!isProtectiveStop) {
                        "protective STOP cannot be cancelled directly. Use update_protection or close_position."
                    }

                    updateOrderStatus(order.orderId, OrderStatus.CANCELED, command.reasonJa)

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
    }

    /**
     * tick に応じて resting order / protection を前進させる。
     */
    suspend fun reconcile(tickSnapshot: TickSnapshot, simulator: FillSimulator): Result<PaperReconcileResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val ticker = tickSnapshot.requireTicker()
                    val rules = tickSnapshot.symbolRules ?: fallbackSymbolRules
                    val lastPrice = (tickSnapshot.lastPrice ?: ticker.last).toBigDecimal()
                    val triggeredOrderIds = mutableListOf<String>()
                    val closedPositionIds = mutableListOf<String>()
                    val executionIds = mutableListOf<String>()
                    val reconcileContext = ReconcileMarketContext(
                        ticker = ticker,
                        rules = rules,
                        simulator = simulator,
                        lastPrice = lastPrice,
                    )
                    val progress = ReconcileProgress(
                        triggeredOrderIds = triggeredOrderIds,
                        closedPositionIds = closedPositionIds,
                        executionIds = executionIds,
                    )

                    updateMarks(lastPrice, tickSnapshot.atr14Jpy?.toBigDecimal(), rules)

                    if (!paperAccountHardHaltReached()) {
                        fillTriggeredEntryOrders(reconcileContext, progress)
                        triggerPositionProtections(reconcileContext, progress)
                    }

                    PaperReconcileResult(
                        advanced = triggeredOrderIds.isNotEmpty() || closedPositionIds.isNotEmpty(),
                        triggeredOrderIds = triggeredOrderIds,
                        closedPositionIds = closedPositionIds,
                        executionIds = executionIds,
                    )
                }
            }
        }
    }

    private fun JdbcTransaction.insertEntryFill(request: EntryFillWriteRequest): PaperTradeResult {
        val command = request.entry.command
        val fill = request.entry.fill

        if (request.insertEntryOrder) {
            insertEntryOrder(
                command,
                request.entryOrderId,
                request.entry.positionId,
                request.entry.tradeGroupId,
                OrderStatus.FILLED,
            )
        }
        insertPosition(command, fill, request.entry.positionId, request.entry.tradeGroupId)
        insertExecution(
            ExecutionInsertRequest(
                orderId = request.entryOrderId.toString(),
                positionId = request.entry.positionId.toString(),
                mode = TradingMode.PAPER,
                side = command.side,
                fill = fill,
                auditContext = command.auditContext,
            ),
        )
        insertProtectiveStopOrder(
            command,
            request.entry.stopOrderId,
            request.entry.positionId,
            request.entry.tradeGroupId,
        )
        updateAccountAfterBuy(fill)

        return PaperTradeResult(
            accepted = true,
            status = OrderStatus.FILLED,
            orderIds = listOf(request.entryOrderId.toString(), request.entry.stopOrderId.toString()),
            positionIds = listOf(request.entry.positionId.toString()),
            executionIds = listOf(fill.executionId.toString()),
            messageJa = "paper entry を約定し、保護 STOP を作成しました。",
        )
    }

    private fun JdbcTransaction.insertTradeIntentConsumption(
        intentId: UUID,
        orderId: UUID,
        consumedAt: Instant,
    ) {
        require(tradeIntentExists(intentId)) {
            "trade intent was not found."
        }
        require(!tradeIntentConsumed(intentId)) {
            "trade intent was already consumed."
        }

        prepare(
            """
                INSERT INTO trade_intent_consumptions (
                    id,
                    intent_id,
                    order_id,
                    consumed_at
                )
                VALUES (?, ?, ?, ?)
            """,
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, intentId)
            statement.setObject(3, orderId)
            statement.setLong(4, consumedAt.toEpochMilli())
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.tradeIntentExists(intentId: UUID): Boolean {
        return prepare("SELECT 1 FROM trade_intents WHERE id = ?").use { statement ->
            statement.setObject(1, intentId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }

    private fun JdbcTransaction.tradeIntentConsumed(intentId: UUID): Boolean {
        return prepare("SELECT 1 FROM trade_intent_consumptions WHERE intent_id = ?").use { statement ->
            statement.setObject(1, intentId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }

    private fun JdbcTransaction.fillTriggeredEntryOrders(context: ReconcileMarketContext, progress: ReconcileProgress) {
        val triggeredOrders = selectOpenOrders()
            .filter { order -> order.status == OrderStatus.OPEN && order.side == OrderSide.BUY }
            .filter { order -> order.isEntryTriggered(context.lastPrice) }

        triggeredOrders.forEach { order ->
            val fill = order.createEntryFill(context.ticker, context.rules, context.simulator)
            val command = order.toPlaceOrderCommand()
            val positionId = UUID.randomUUID()
            val tradeGroupId = UUID.fromString(requireNotNull(order.tradeGroupId))
            val stopOrderId = UUID.randomUUID()

            if (!hasCashForBuyFill(fill)) {
                updateOrderStatus(order.orderId, OrderStatus.REJECTED, "reconciler entry rejected: insufficient paper cash")
                progress.triggeredOrderIds += order.orderId

                return@forEach
            }

            insertPosition(command, fill, positionId, tradeGroupId)
            updateRestingEntryOrderFill(
                orderId = order.orderId,
                positionId = positionId,
                reasonJa = order.reasonJa.orEmpty(),
            )
            insertExecution(
                ExecutionInsertRequest(
                    orderId = order.orderId,
                    positionId = positionId.toString(),
                    mode = TradingMode.PAPER,
                    side = order.side,
                    fill = fill,
                    auditContext = command.auditContext,
                ),
            )
            insertProtectiveStopOrder(command, stopOrderId, positionId, tradeGroupId)
            updateAccountAfterBuy(fill)

            progress.triggeredOrderIds += order.orderId
            progress.executionIds += fill.executionId.toString()
        }
    }

    private fun JdbcTransaction.triggerPositionProtections(
        context: ReconcileMarketContext,
        progress: ReconcileProgress,
    ) {
        selectOpenPositions().forEach { position ->
            val stopPrice = position.currentStopLossJpy?.toBigDecimal()
            val takeProfitPrice = position.currentTakeProfitJpy?.toBigDecimal()
            val stopTriggered = stopPrice != null && context.lastPrice <= stopPrice
            val takeProfitTriggered = takeProfitPrice != null && context.lastPrice >= takeProfitPrice

            if (stopTriggered) {
                triggerStopProtection(position, requireNotNull(stopPrice), context, progress)

                return@forEach
            }

            if (takeProfitTriggered) {
                triggerTakeProfitProtection(position, context, progress)
            }
        }
    }

    private fun JdbcTransaction.triggerStopProtection(
        position: Position,
        stopPrice: BigDecimal,
        context: ReconcileMarketContext,
        progress: ReconcileProgress,
    ) {
        val stopOrder = requireLinkedStopOrder(position.positionId)
        val fill = context.simulator.stopFill(
            OrderSide.SELL,
            position.sizeBtc.toBigDecimal(),
            stopPrice,
            context.ticker,
            context.rules,
        )
        val realizedFill = fill.withRealizedPnl(position)

        updateOrderStatus(stopOrder.orderId, OrderStatus.FILLED, "reconciler stop trigger")
        insertExecution(
            ExecutionInsertRequest(
                orderId = stopOrder.orderId,
                positionId = position.positionId,
                mode = position.mode,
                side = OrderSide.SELL,
                fill = realizedFill,
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        )
        closePositionRow(position, realizedFill)
        updateAccountAfterSell(realizedFill)

        progress.triggeredOrderIds += stopOrder.orderId
        progress.closedPositionIds += position.positionId
        progress.executionIds += realizedFill.executionId.toString()
    }

    private fun JdbcTransaction.triggerTakeProfitProtection(
        position: Position,
        context: ReconcileMarketContext,
        progress: ReconcileProgress,
    ) {
        requireLinkedStopOrder(position.positionId).let { stopOrder ->
            updateOrderStatus(stopOrder.orderId, OrderStatus.CANCELED, "reconciler virtual take profit trigger")
            progress.triggeredOrderIds += stopOrder.orderId
        }

        val fill = context.simulator.marketFill(
            OrderSide.SELL,
            position.sizeBtc.toBigDecimal(),
            context.ticker,
            context.rules,
        )
        val realizedFill = fill.withRealizedPnl(position)
        val closeOrderId = UUID.randomUUID()

        insertCloseOrder(closeOrderId, position, "reconciler virtual take profit trigger", PaperTradeAuditContext.EMPTY)
        insertExecution(
            ExecutionInsertRequest(
                orderId = closeOrderId.toString(),
                positionId = position.positionId,
                mode = position.mode,
                side = OrderSide.SELL,
                fill = realizedFill,
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        )
        closePositionRow(position, realizedFill)
        updateAccountAfterSell(realizedFill)

        progress.closedPositionIds += position.positionId
        progress.executionIds += realizedFill.executionId.toString()
    }

    private fun JdbcTransaction.updateMarks(
        lastPrice: BigDecimal,
        atr14Jpy: BigDecimal?,
        rules: SymbolRules,
    ) {
        selectOpenPositions().forEach { position ->
            val entryPrice = position.averageEntryPriceJpy.toBigDecimal()
            val sizeBtc = position.sizeBtc.toBigDecimal()
            val highestPrice = maxOf(position.highestPriceSinceEntryJpy.toBigDecimal(), lastPrice)
            val currentLowestPrice = position.lowestPriceSinceEntryJpy?.toBigDecimal() ?: lastPrice
            val lowestPrice = minOf(currentLowestPrice, lastPrice)
            val trailingStop = atr14Jpy?.let { atrValue ->
                highestPrice
                    .subtract(atrValue.multiply(SafetyFloorDefaults.trailingAtrMultiplier))
                    .floorToStep(rules.tickSize.toBigDecimal())
            }
            val currentStop = position.currentStopLossJpy?.toBigDecimal()
            val tightenedStop = listOfNotNull(currentStop, trailingStop).maxOrNull()
            val unrealizedPnl = lastPrice.subtract(entryPrice).multiply(sizeBtc).moneyScale()

            updatePositionMark(
                PositionMarkUpdate(
                    positionId = position.positionId,
                    lastPrice = lastPrice,
                    highestPrice = highestPrice,
                    lowestPrice = lowestPrice,
                    unrealizedPnl = unrealizedPnl,
                    tightenedStop = tightenedStop,
                ),
            )

            if (tightenedStop != null && tightenedStop != currentStop) {
                updateLinkedStopOrder(position.positionId, tightenedStop, "reconciler atr trailing floor")
            }
        }

        updateAccountMark(lastPrice)
    }

    private fun JdbcTransaction.insertEntryOrder(
        command: PlaceOrderCommand,
        orderId: UUID,
        positionId: UUID?,
        tradeGroupId: UUID,
        status: OrderStatus,
    ) {
        prepare(
            """
                INSERT INTO orders (
                    id, intent_id, position_id, trade_group_id, mode, symbol, side, order_type, status,
                    size_btc, limit_price_jpy, trigger_price_jpy, protective_stop_price_jpy,
                    take_profit_price_jpy, estimated_win_probability, reason_ja,
                    decision_run_id, tool_call_id, client_request_id, llm_provider, prompt_hash,
                    system_prompt_version, market_snapshot_id, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        ).use { statement ->
            statement.setObject(1, orderId)
            statement.setObject(2, command.intentId)
            statement.setObject(3, positionId)
            statement.setObject(4, tradeGroupId)
            statement.setString(5, TradingMode.PAPER.name)
            statement.setString(6, command.symbol.apiSymbol)
            statement.setString(7, command.side.name)
            statement.setString(8, command.orderType.name)
            statement.setString(9, status.name)
            statement.setBigDecimal(10, command.sizeBtc.btcScale())
            statement.setNullableBigDecimal(11, command.priceJpy.takeIf { command.orderType == OrderType.LIMIT }?.moneyScale())
            statement.setNullableBigDecimal(12, command.priceJpy.takeIf { command.orderType == OrderType.STOP }?.moneyScale())
            statement.setBigDecimal(13, command.protectiveStopPriceJpy.moneyScale())
            statement.setNullableBigDecimal(14, command.takeProfitPriceJpy?.moneyScale())
            statement.setBigDecimal(15, command.estimatedWinProbability.ratioScale())
            statement.setString(16, command.reasonJa)
            statement.bindAudit(17, command.auditContext)
            statement.setLong(24, nowMillis())
            statement.setLong(25, nowMillis())
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.insertProtectiveStopOrder(
        command: PlaceOrderCommand,
        stopOrderId: UUID,
        positionId: UUID,
        tradeGroupId: UUID,
    ) {
        prepare(
            """
                INSERT INTO orders (
                    id, position_id, trade_group_id, mode, symbol, side, order_type, status,
                    size_btc, limit_price_jpy, trigger_price_jpy, protective_stop_price_jpy,
                    take_profit_price_jpy, estimated_win_probability, reason_ja,
                    decision_run_id, tool_call_id, client_request_id, llm_provider, prompt_hash,
                    system_prompt_version, market_snapshot_id, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        ).use { statement ->
            val protectiveStopAuditContext = command.auditContext.copy(clientRequestId = null)

            statement.bindOrderId(stopOrderId, positionId, tradeGroupId)
            statement.setString(4, TradingMode.PAPER.name)
            statement.setString(5, command.symbol.apiSymbol)
            statement.setString(6, OrderSide.SELL.name)
            statement.setString(7, OrderType.STOP.name)
            statement.setString(8, OrderStatus.OPEN.name)
            statement.setBigDecimal(9, command.sizeBtc.btcScale())
            statement.setBigDecimal(10, command.protectiveStopPriceJpy.moneyScale())
            statement.setString(11, "protective stop: ${command.reasonJa}")
            statement.bindAudit(12, protectiveStopAuditContext)
            statement.setLong(19, nowMillis())
            statement.setLong(20, nowMillis())
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.insertCloseOrder(
        orderId: UUID,
        position: Position,
        reasonJa: String,
        auditContext: PaperTradeAuditContext,
    ) {
        prepare(
            """
                INSERT INTO orders (
                    id, position_id, trade_group_id, mode, symbol, side, order_type, status,
                    size_btc, limit_price_jpy, trigger_price_jpy, protective_stop_price_jpy,
                    take_profit_price_jpy, estimated_win_probability, reason_ja,
                    decision_run_id, tool_call_id, client_request_id, llm_provider, prompt_hash,
                    system_prompt_version, market_snapshot_id, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        ).use { statement ->
            statement.bindOrderId(orderId, UUID.fromString(position.positionId), UUID.fromString(position.tradeGroupId))
            statement.setString(4, position.mode.name)
            statement.setString(5, position.symbol)
            statement.setString(6, OrderSide.SELL.name)
            statement.setString(7, OrderType.MARKET.name)
            statement.setString(8, OrderStatus.FILLED.name)
            statement.setBigDecimal(9, position.sizeBtc.toBigDecimal().btcScale())
            statement.setString(10, reasonJa)
            statement.bindAudit(11, auditContext)
            statement.setLong(18, nowMillis())
            statement.setLong(19, nowMillis())
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.insertPosition(
        command: PlaceOrderCommand,
        fill: SimulatedFill,
        positionId: UUID,
        tradeGroupId: UUID,
    ) {
        prepare(
            """
                INSERT INTO positions (
                    id, trade_group_id, mode, symbol, side, status, opened_at, closed_at,
                    size_btc, average_entry_price_jpy, current_price_jpy, current_stop_loss_jpy,
                    current_take_profit_jpy, unrealized_pnl_jpy, unrealized_r, pyramid_add_count,
                    highest_price_since_entry_jpy, lowest_price_since_entry_jpy, decision_run_id, tool_call_id,
                    client_request_id, llm_provider, prompt_hash, system_prompt_version,
                    market_snapshot_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        ).use { statement ->
            statement.setObject(1, positionId)
            statement.setObject(2, tradeGroupId)
            statement.setString(3, TradingMode.PAPER.name)
            statement.setString(4, command.symbol.apiSymbol)
            statement.setString(5, PositionSide.LONG.name)
            statement.setString(6, PositionStatus.OPEN.name)
            statement.setLong(7, fill.executedAt.toEpochMilli())
            statement.setBigDecimal(8, command.sizeBtc.btcScale())
            statement.setBigDecimal(9, fill.priceJpy.moneyScale())
            statement.setBigDecimal(10, fill.priceJpy.moneyScale())
            statement.setBigDecimal(11, command.protectiveStopPriceJpy.moneyScale())
            statement.setNullableBigDecimal(12, command.takeProfitPriceJpy?.moneyScale())
            statement.setBigDecimal(13, BigDecimal.ZERO.moneyScale())
            statement.setBigDecimal(14, BigDecimal.ZERO)
            statement.setInt(15, 0)
            statement.setBigDecimal(16, fill.priceJpy.moneyScale())
            statement.setBigDecimal(17, fill.priceJpy.moneyScale())
            statement.bindAudit(18, command.auditContext)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.insertExecution(request: ExecutionInsertRequest) {
        prepare(
            """
                INSERT INTO executions (
                    id, order_id, position_id, mode, symbol, side, price_jpy, size_btc,
                    fee_jpy, realized_pnl_jpy, liquidity, executed_at, decision_run_id,
                    tool_call_id, client_request_id, llm_provider, prompt_hash,
                    system_prompt_version, market_snapshot_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        ).use { statement ->
            statement.setObject(1, request.fill.executionId)
            statement.setObject(2, UUID.fromString(request.orderId))
            statement.setObject(3, UUID.fromString(request.positionId))
            statement.setString(4, request.mode.name)
            statement.setString(5, TradingSymbol.BTC.apiSymbol)
            statement.setString(6, request.side.name)
            statement.setBigDecimal(7, request.fill.priceJpy.moneyScale())
            statement.setBigDecimal(8, request.fill.sizeBtc.btcScale())
            statement.setBigDecimal(9, request.fill.feeJpy.moneyScale())
            statement.setBigDecimal(10, request.fill.realizedPnlJpy.moneyScale())
            statement.setString(11, request.fill.liquidity.name)
            statement.setLong(12, request.fill.executedAt.toEpochMilli())
            statement.bindAudit(13, request.auditContext)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.updatePositionMark(update: PositionMarkUpdate) {
        prepare(
            """
                UPDATE positions
                SET current_price_jpy = ?,
                    current_stop_loss_jpy = COALESCE(?, current_stop_loss_jpy),
                    unrealized_pnl_jpy = ?,
                    highest_price_since_entry_jpy = ?,
                    lowest_price_since_entry_jpy = ?
                WHERE id = ?
            """,
        ).use { statement ->
            statement.setBigDecimal(1, update.lastPrice.moneyScale())
            statement.setNullableBigDecimal(2, update.tightenedStop?.moneyScale())
            statement.setBigDecimal(3, update.unrealizedPnl.moneyScale())
            statement.setBigDecimal(4, update.highestPrice.moneyScale())
            statement.setBigDecimal(5, update.lowestPrice.moneyScale())
            statement.setObject(6, UUID.fromString(update.positionId))
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.updatePositionProtection(
        positionId: String,
        newStopPrice: BigDecimal?,
        newTakeProfitPrice: BigDecimal?,
        takeProfitSpecified: Boolean,
    ) {
        val stopExpression = if (newStopPrice == null) "current_stop_loss_jpy" else "?"
        val takeProfitExpression = if (takeProfitSpecified) "?" else "current_take_profit_jpy"
        val sql = """
            UPDATE positions
            SET current_stop_loss_jpy = $stopExpression,
                current_take_profit_jpy = $takeProfitExpression
            WHERE id = ?
                AND status = ?
        """

        prepare(sql).use { statement ->
            var parameterIndex = 1

            if (newStopPrice != null) {
                statement.setBigDecimal(parameterIndex, newStopPrice.moneyScale())
                parameterIndex += 1
            }
            if (takeProfitSpecified) {
                statement.setNullableBigDecimal(parameterIndex, newTakeProfitPrice?.moneyScale())
                parameterIndex += 1
            }

            statement.setObject(parameterIndex, UUID.fromString(positionId))
            statement.setString(parameterIndex + 1, PositionStatus.OPEN.name)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.closePositionRow(position: Position, fill: SimulatedFill) {
        val highestPrice = maxOf(position.highestPriceSinceEntryJpy.toBigDecimal(), fill.priceJpy)
        val currentLowestPrice = position.lowestPriceSinceEntryJpy?.toBigDecimal() ?: fill.priceJpy
        val lowestPrice = minOf(currentLowestPrice, fill.priceJpy)

        prepare(
            """
                UPDATE positions
                SET status = ?,
                    closed_at = ?,
                    current_price_jpy = ?,
                    current_stop_loss_jpy = NULL,
                    current_take_profit_jpy = NULL,
                    unrealized_pnl_jpy = 0,
                    unrealized_r = 0,
                    highest_price_since_entry_jpy = ?,
                    lowest_price_since_entry_jpy = ?
                WHERE id = ?
                    AND status = ?
            """,
        ).use { statement ->
            statement.setString(1, PositionStatus.CLOSED.name)
            statement.setLong(2, fill.executedAt.toEpochMilli())
            statement.setBigDecimal(3, fill.priceJpy.moneyScale())
            statement.setBigDecimal(4, highestPrice.moneyScale())
            statement.setBigDecimal(5, lowestPrice.moneyScale())
            statement.setObject(6, UUID.fromString(position.positionId))
            statement.setString(7, PositionStatus.OPEN.name)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.updateLinkedStopOrder(
        positionId: String,
        stopPrice: BigDecimal,
        reasonJa: String,
    ) {
        prepare(
            """
                UPDATE orders
                SET trigger_price_jpy = ?,
                    reason_ja = ?,
                    updated_at = ?
                WHERE position_id = ?
                    AND side = ?
                    AND order_type = ?
                    AND status = ?
            """,
        ).use { statement ->
            statement.setBigDecimal(1, stopPrice.moneyScale())
            statement.setString(2, reasonJa)
            statement.setLong(3, nowMillis())
            statement.setObject(4, UUID.fromString(positionId))
            statement.setString(5, OrderSide.SELL.name)
            statement.setString(6, OrderType.STOP.name)
            statement.setString(7, OrderStatus.OPEN.name)

            require(statement.executeUpdate() > 0) {
                "linked protective STOP order was not found."
            }
        }
    }

    private fun JdbcTransaction.updateOrderStatus(
        orderId: String,
        status: OrderStatus,
        reasonJa: String,
    ) {
        prepare(
            """
                UPDATE orders
                SET status = ?,
                    reason_ja = ?,
                    updated_at = ?
                WHERE id = ?
            """,
        ).use { statement ->
            statement.setString(1, status.name)
            statement.setString(2, reasonJa)
            statement.setLong(3, nowMillis())
            statement.setObject(4, UUID.fromString(orderId))
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.updateRestingEntryOrderFill(
        orderId: String,
        positionId: UUID,
        reasonJa: String,
    ) {
        prepare(
            """
                UPDATE orders
                SET status = ?,
                    position_id = ?,
                    reason_ja = ?,
                    updated_at = ?
                WHERE id = ?
                    AND side = ?
                    AND order_type IN (?, ?)
                    AND status = ?
            """,
        ).use { statement ->
            statement.setString(1, OrderStatus.FILLED.name)
            statement.setObject(2, positionId)
            statement.setString(3, reasonJa)
            statement.setLong(4, nowMillis())
            statement.setObject(5, UUID.fromString(orderId))
            statement.setString(6, OrderSide.BUY.name)
            statement.setString(7, OrderType.LIMIT.name)
            statement.setString(8, OrderType.STOP.name)
            statement.setString(9, OrderStatus.OPEN.name)

            require(statement.executeUpdate() == 1) {
                "resting entry order was not found."
            }
        }
    }

    private fun JdbcTransaction.cancelOpenStopOrders(positionId: String, reasonJa: String) {
        prepare(
            """
                UPDATE orders
                SET status = ?,
                    reason_ja = ?,
                    updated_at = ?
                WHERE position_id = ?
                    AND side = ?
                    AND order_type = ?
                    AND status = ?
            """,
        ).use { statement ->
            statement.setString(1, OrderStatus.CANCELED.name)
            statement.setString(2, reasonJa)
            statement.setLong(3, nowMillis())
            statement.setObject(4, UUID.fromString(positionId))
            statement.setString(5, OrderSide.SELL.name)
            statement.setString(6, OrderType.STOP.name)
            statement.setString(7, OrderStatus.OPEN.name)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.updateAccountAfterBuy(fill: SimulatedFill) {
        val account = selectPaperAccount()
        val spentCash = fill.priceJpy.multiply(fill.sizeBtc).add(fill.feeJpy)
        val cash = account.cashJpy.toBigDecimal().subtract(spentCash).moneyScale()
        val btcQuantity = account.btcQuantity.toBigDecimal().add(fill.sizeBtc).btcScale()
        val updatedAccount = updateAccount(
            cash = cash,
            btcQuantity = btcQuantity,
            markPrice = fill.priceJpy,
        )

        appendFillEquitySnapshot(updatedAccount, fill.executedAt)
    }

    private fun JdbcTransaction.hasCashForBuyFill(fill: SimulatedFill): Boolean {
        val account = selectPaperAccount()
        val spentCash = fill.priceJpy.multiply(fill.sizeBtc).add(fill.feeJpy).moneyScale()

        return spentCash <= account.cashJpy.toBigDecimal()
    }

    private fun JdbcTransaction.updateAccountAfterSell(fill: SimulatedFill) {
        val account = selectPaperAccount()
        val receivedCash = fill.priceJpy.multiply(fill.sizeBtc).subtract(fill.feeJpy)
        val cash = account.cashJpy.toBigDecimal().add(receivedCash).moneyScale()
        val btcQuantity = account.btcQuantity.toBigDecimal().subtract(fill.sizeBtc).btcScale()
        val updatedAccount = updateAccount(
            cash = cash,
            btcQuantity = btcQuantity,
            markPrice = fill.priceJpy,
        )

        appendFillEquitySnapshot(updatedAccount, fill.executedAt)
    }

    private fun JdbcTransaction.updateAccountMark(markPrice: BigDecimal) {
        val account = selectPaperAccount()

        updateAccount(
            cash = account.cashJpy.toBigDecimal(),
            btcQuantity = account.btcQuantity.toBigDecimal(),
            markPrice = markPrice,
        )
    }

    private fun JdbcTransaction.updateAccount(
        cash: BigDecimal,
        btcQuantity: BigDecimal,
        markPrice: BigDecimal,
    ): AccountSnapshot {
        val account = selectPaperAccount()
        val scaledCash = cash.moneyScale()
        val scaledBtcQuantity = btcQuantity.btcScale()
        val scaledMarkPrice = markPrice.moneyScale()
        val totalEquity = scaledCash.add(scaledBtcQuantity.multiply(scaledMarkPrice)).moneyScale()
        val equityPeak = maxOf(account.equityPeakJpy.toBigDecimal(), totalEquity).moneyScale()
        val drawdownRatio = drawdownRatio(totalEquity, equityPeak)
        val updatedAccount = account.copy(
            cashJpy = scaledCash.toPlainString(),
            btcQuantity = scaledBtcQuantity.toPlainString(),
            btcMarkPriceJpy = scaledMarkPrice.toPlainString(),
            totalEquityJpy = totalEquity.toPlainString(),
            equityPeakJpy = equityPeak.toPlainString(),
            drawdownRatio = drawdownRatio.toPlainString(),
        )

        prepare(
            """
                UPDATE paper_account
                SET cash_jpy = ?,
                    btc_quantity = ?,
                    btc_mark_price_jpy = ?,
                    total_equity_jpy = ?,
                    equity_peak_jpy = ?,
                    drawdown_ratio = ?,
                    updated_at = ?
                WHERE id = ?
            """,
        ).use { statement ->
            statement.setBigDecimal(1, scaledCash)
            statement.setBigDecimal(2, scaledBtcQuantity)
            statement.setBigDecimal(3, scaledMarkPrice)
            statement.setBigDecimal(4, totalEquity)
            statement.setBigDecimal(5, equityPeak)
            statement.setBigDecimal(6, drawdownRatio)
            statement.setLong(7, nowMillis())
            statement.setInt(8, PAPER_ACCOUNT_SINGLE_ROW_ID)
            statement.executeUpdate()
        }

        syncRiskStateEquity(equityPeak, drawdownRatio)

        return updatedAccount
    }

    private fun JdbcTransaction.appendFillEquitySnapshot(account: AccountSnapshot, capturedAt: Instant) {
        val snapshot = account.toFillEquitySnapshotRecord(
            id = UUID.randomUUID(),
            capturedAt = capturedAt,
        )

        insertEquitySnapshot(snapshot, INSERT_EQUITY_SNAPSHOT_SQL)
    }

    private fun JdbcTransaction.syncRiskStateEquity(equityPeak: BigDecimal, drawdownRatio: BigDecimal) {
        prepare(
            """
                UPDATE risk_state
                SET equity_peak = ?,
                    drawdown_ratio = ?,
                    hard_halt = CASE
                        WHEN state = 'HARD_HALT' THEN TRUE
                        ELSE FALSE
                    END,
                    updated_at = ?
                WHERE id = ?
            """,
        ).use { statement ->
            statement.setBigDecimal(1, equityPeak.moneyScale())
            statement.setBigDecimal(2, drawdownRatio)
            statement.setLong(3, nowMillis())
            statement.setInt(4, RISK_STATE_SINGLE_ROW_ID)
            statement.executeUpdate()
        }
    }

    private fun JdbcTransaction.paperAccountHardHaltReached(): Boolean {
        return selectPaperAccount().drawdownRatio.toBigDecimal() <= SafetyFloorDefaults.maxDrawdownRatio
    }

    private fun JdbcTransaction.requireOpenPosition(positionId: UUID): Position {
        return selectOpenPositions()
            .firstOrNull { position -> position.positionId == positionId.toString() }
            ?: throw IllegalArgumentException("position was not found.")
    }

    private fun JdbcTransaction.requireOpenOrder(orderId: UUID): Order {
        return selectOpenOrders()
            .firstOrNull { order -> order.orderId == orderId.toString() }
            ?: throw IllegalArgumentException("order was not found.")
    }

    private fun JdbcTransaction.requireLinkedStopOrder(positionId: String): Order {
        return selectOpenOrders()
            .firstOrNull { order ->
                order.positionId == positionId &&
                    order.side == OrderSide.SELL &&
                    order.orderType == OrderType.STOP &&
                    order.status == OrderStatus.OPEN
            }
            ?: throw IllegalArgumentException("linked protective STOP order was not found.")
    }

    private fun JdbcTransaction.prepare(sql: String): PreparedStatement {
        return jdbcConnection().prepareStatement(sql.trimIndent())
    }

    private fun nowMillis(): Long {
        return clock.instant().toEpochMilli()
    }
}

private fun PreparedStatement.bindOrderId(
    orderId: UUID,
    positionId: UUID?,
    tradeGroupId: UUID?,
) {
    setObject(1, orderId)
    setObject(2, positionId)
    setObject(3, tradeGroupId)
}

private fun PreparedStatement.bindAudit(startIndex: Int, auditContext: PaperTradeAuditContext) {
    setString(startIndex, auditContext.decisionRunContext.decisionRunId)
    setString(startIndex + 1, auditContext.toolCallId)
    setString(startIndex + 2, auditContext.clientRequestId)
    setString(startIndex + 3, auditContext.decisionRunContext.llmProvider)
    setString(startIndex + 4, auditContext.decisionRunContext.promptHash)
    setString(startIndex + 5, auditContext.decisionRunContext.systemPromptVersion)
    setString(startIndex + 6, auditContext.decisionRunContext.marketSnapshotId)
}

/**
 * execution insert の入力。
 *
 * @param orderId execution が紐づく order ID
 * @param positionId execution が紐づく position ID
 * @param mode trading mode
 * @param side execution side
 * @param fill paper 約定
 * @param auditContext audit context
 */
private data class ExecutionInsertRequest(
    val orderId: String,
    val positionId: String,
    val mode: TradingMode,
    val side: OrderSide,
    val fill: SimulatedFill,
    val auditContext: PaperTradeAuditContext,
)

private fun Order.isEntryTriggered(lastPrice: BigDecimal): Boolean {
    return when (orderType) {
        OrderType.MARKET -> false
        OrderType.LIMIT -> limitPriceJpy?.toBigDecimal()?.let { price -> lastPrice <= price } ?: false
        OrderType.STOP -> triggerPriceJpy?.toBigDecimal()?.let { price -> lastPrice >= price } ?: false
    }
}

private fun Order.createEntryFill(
    ticker: Ticker,
    rules: SymbolRules,
    simulator: FillSimulator,
): SimulatedFill {
    return simulator.restingEntryFill(
        RestingEntryFillRequest(
            side = side,
            orderType = orderType,
            sizeBtc = sizeBtc.toBigDecimal(),
            limitPriceJpy = limitPriceJpy?.toBigDecimal(),
            triggerPriceJpy = triggerPriceJpy?.toBigDecimal(),
            ticker = ticker,
            rules = rules,
        ),
    )
}

private fun Order.toPlaceOrderCommand(): PlaceOrderCommand {
    val price = limitPriceJpy?.toBigDecimal() ?: triggerPriceJpy?.toBigDecimal()

    return PlaceOrderCommand(
        commandId = UUID.fromString(orderId),
        symbol = TradingSymbol.BTC,
        intentId = intentId?.let { value -> UUID.fromString(value) },
        side = side,
        orderType = orderType,
        sizeBtc = sizeBtc.toBigDecimal(),
        priceJpy = price,
        tradeGroupId = tradeGroupId?.let { value -> UUID.fromString(value) },
        protectiveStopPriceJpy = requireNotNull(protectiveStopPriceJpy).toBigDecimal(),
        takeProfitPriceJpy = takeProfitPriceJpy?.toBigDecimal(),
        estimatedWinProbability = estimatedWinProbability?.toBigDecimal()
            ?: DEFAULT_RESTORED_ESTIMATED_WIN_PROBABILITY,
        reasonJa = reasonJa.orEmpty(),
        auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = clientRequestId),
    )
}

private fun SimulatedFill.withRealizedPnl(position: Position): SimulatedFill {
    val entryPrice = position.averageEntryPriceJpy.toBigDecimal()
    val grossPnl = priceJpy.subtract(entryPrice).multiply(sizeBtc)
    val realizedPnl = grossPnl.subtract(feeJpy).moneyScale()

    return copy(realizedPnlJpy = realizedPnl)
}

private fun drawdownRatio(totalEquity: BigDecimal, equityPeak: BigDecimal): BigDecimal {
    if (equityPeak.compareTo(BigDecimal.ZERO) == 0) {
        return BigDecimal.ZERO.ratioScale()
    }

    return totalEquity
        .subtract(equityPeak)
        .divide(equityPeak, DRAW_DOWN_SCALE, RoundingMode.HALF_UP)
        .ratioScale()
}

/**
 * drawdown 計算 scale。
 */
private const val DRAW_DOWN_SCALE = 10

/**
 * resting order 復元時の既定推定勝率。
 */
private val DEFAULT_RESTORED_ESTIMATED_WIN_PROBABILITY = BigDecimal("0.60")
