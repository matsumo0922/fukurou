@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.EntryFillWriteRequest
import me.matsumo.fukurou.trading.broker.IntentConsumingMarketEntryFillRequest
import me.matsumo.fukurou.trading.broker.IntentConsumingRestingEntryOrderRequest
import me.matsumo.fukurou.trading.broker.MarketEntryFillRequest
import me.matsumo.fukurou.trading.broker.PaperExecutionSimulator
import me.matsumo.fukurou.trading.broker.PaperLedgerMutationRepository
import me.matsumo.fukurou.trading.broker.PaperLedgerReconcileScope
import me.matsumo.fukurou.trading.broker.PaperOrderUpdate
import me.matsumo.fukurou.trading.broker.PaperReconcileResult
import me.matsumo.fukurou.trading.broker.PaperSimulationContext
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.PositionMarkUpdate
import me.matsumo.fukurou.trading.broker.PositionMarketEligibility
import me.matsumo.fukurou.trading.broker.ReconcileMarketContext
import me.matsumo.fukurou.trading.broker.ReconcileProgress
import me.matsumo.fukurou.trading.broker.RestingEntryFillRequest
import me.matsumo.fukurou.trading.broker.RestingEntryOrderRequest
import me.matsumo.fukurou.trading.broker.RestingOrderMarketEligibility
import me.matsumo.fukurou.trading.broker.SimulatedFill
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.broker.VIRTUAL_TAKE_PROFIT_TRIGGER_REASON
import me.matsumo.fukurou.trading.broker.btcScale
import me.matsumo.fukurou.trading.broker.emptyReconcileProgress
import me.matsumo.fukurou.trading.broker.hasTightenedStop
import me.matsumo.fukurou.trading.broker.limitOrderReached
import me.matsumo.fukurou.trading.broker.marketFill
import me.matsumo.fukurou.trading.broker.mergeEntryFill
import me.matsumo.fukurou.trading.broker.moneyScale
import me.matsumo.fukurou.trading.broker.ratioScale
import me.matsumo.fukurou.trading.broker.restingEntryUpdate
import me.matsumo.fukurou.trading.broker.stopFill
import me.matsumo.fukurou.trading.broker.toPaperReconcileResult
import me.matsumo.fukurou.trading.broker.toPositionMarkUpdate
import me.matsumo.fukurou.trading.broker.toReconcileMarketContext
import me.matsumo.fukurou.trading.broker.unrealizedPnlAt
import me.matsumo.fukurou.trading.broker.unrealizedRAt
import me.matsumo.fukurou.trading.broker.withEntryCommandContext
import me.matsumo.fukurou.trading.broker.withOrderContext
import me.matsumo.fukurou.trading.config.calculateRuntimeConfigHash
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderExpirySource
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.PAPER_EXECUTION_SEMANTICS_VERSION
import me.matsumo.fukurou.trading.domain.PaperExecutionLineage
import me.matsumo.fukurou.trading.domain.PaperOrderCancelReason
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.domain.isRestingEntryLifecycleCandidate
import me.matsumo.fukurou.trading.evaluation.toFillEquitySnapshotRecord
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
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
) : PaperLedgerMutationRepository {

    /**
     * MARKET entry を約定済みとして保存する。
     */
    override suspend fun fillMarketEntry(request: MarketEntryFillRequest): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val writeIntent = resolvePaperWriteContext(request.command.auditContext)
                        .intent(PaperWritePolicy.RISK_INCREASING)
                    insertEntryFill(
                        EntryFillWriteRequest(
                            entry = request,
                            entryOrderId = request.command.commandId,
                            insertEntryOrder = true,
                        ),
                        writeIntent,
                        clock,
                    )
                }
            }
        }
    }

    /**
     * resting entry intent を保存する。
     */
    override suspend fun createRestingEntryOrder(request: RestingEntryOrderRequest): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val writeIntent = resolvePaperWriteContext(request.command.auditContext)
                        .intent(PaperWritePolicy.RISK_INCREASING)
                    insertEntryOrder(
                        EntryOrderInsertRequest(
                            command = request.command,
                            orderId = request.orderId,
                            positionId = null,
                            tradeGroupId = request.tradeGroupId,
                            status = OrderStatus.OPEN,
                            writeIntent = writeIntent,
                            createdAt = request.createdAt,
                            expiresAt = request.expiresAt,
                            expirySource = request.expirySource,
                            effectiveTtlSeconds = request.effectiveTtlSeconds,
                            marketEligibility = request.marketEligibility,
                        ),
                        clock,
                    )

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
                    val writeIntent = resolvePaperWriteContext(request.entry.command.auditContext)
                        .intent(PaperWritePolicy.RISK_INCREASING)
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
                        writeIntent,
                        clock,
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
                    val writeIntent = resolvePaperWriteContext(request.order.command.auditContext)
                        .intent(PaperWritePolicy.RISK_INCREASING)
                    insertTradeIntentConsumption(
                        request.consumption.intentId,
                        request.order.orderId,
                        request.consumption.consumedAt,
                    )
                    insertEntryOrder(
                        EntryOrderInsertRequest(
                            command = request.order.command,
                            orderId = request.order.orderId,
                            positionId = null,
                            tradeGroupId = request.order.tradeGroupId,
                            status = OrderStatus.OPEN,
                            writeIntent = writeIntent,
                            createdAt = request.order.createdAt,
                            expiresAt = request.order.expiresAt,
                            expirySource = request.order.expirySource,
                            effectiveTtlSeconds = request.order.effectiveTtlSeconds,
                            marketEligibility = request.order.marketEligibility,
                        ),
                        clock,
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
    @Suppress("LongMethod")
    override suspend fun closePosition(
        command: ClosePositionCommand,
        positionId: UUID,
        orderId: UUID,
        fill: SimulatedFill,
    ): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val writeIntent = resolvePaperWriteContext(command.auditContext)
                        .intent(PaperWritePolicy.RISK_REDUCING)
                    val position = requireOpenPosition(positionId)
                    val closeOrderId = orderId.toString()
                    val realizedFill = fill.withRealizedPnl(position)

                    require(fill.sizeBtc <= position.sizeBtc.toBigDecimal()) {
                        "close size exceeds open position size."
                    }

                    insertCloseOrder(
                        request = CloseOrderInsertRequest(
                            orderId = orderId,
                            position = position,
                            sizeBtc = realizedFill.sizeBtc,
                            reasonJa = command.reasonJa,
                            auditContext = command.auditContext,
                            writeIntent = writeIntent,
                        ),
                        clock = clock,
                    )
                    insertExecution(
                        ExecutionInsertRequest(
                            orderId = closeOrderId,
                            positionId = position.positionId,
                            mode = position.mode,
                            side = OrderSide.SELL,
                            fill = realizedFill,
                            auditContext = command.auditContext,
                            writeIntent = writeIntent,
                        ),
                    )
                    val remainingSize = position.sizeBtc.toBigDecimal()
                        .subtract(realizedFill.sizeBtc)
                        .btcScale()

                    if (remainingSize > BigDecimal.ZERO) {
                        updatePositionAfterPartialClose(position, realizedFill, remainingSize)
                        updateLinkedStopOrderSize(position.positionId, remainingSize, command.reasonJa, clock)
                    } else {
                        closePositionRow(position, realizedFill)
                        cancelOpenStopOrders(position.positionId, command.reasonJa, clock)
                    }
                    updateAccountAfterSell(realizedFill, clock)

                    PaperTradeResult(
                        accepted = true,
                        status = OrderStatus.FILLED,
                        orderIds = listOf(closeOrderId),
                        positionIds = listOf(position.positionId),
                        executionIds = listOf(realizedFill.executionId.toString()),
                        messageJa = if (remainingSize > BigDecimal.ZERO) {
                            "position を部分 close しました。"
                        } else {
                            "position を close しました。"
                        },
                    )
                }
            }
        }
    }

    /**
     * position の保護を更新する。
     */
    override suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    requirePaperWriteAllowed(PaperWritePolicy.PROTECTION_MAINTENANCE, command.auditContext)
                    val position = requireOpenPosition(command.positionId)
                    val newStopPrice = command.newStopPriceJpy
                    val newTakeProfitPrice = if (command.takeProfitPriceSpecified) {
                        command.newTakeProfitPriceJpy
                    } else {
                        position.currentTakeProfitJpy?.toBigDecimal()
                    }

                    if (newStopPrice != null) {
                        updateLinkedStopOrder(position.positionId, newStopPrice, command.reasonJa, clock)
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
    override suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    requirePaperWriteAllowed(PaperWritePolicy.RISK_REDUCING, command.auditContext)
                    val order = requireOpenOrder(command.orderId)
                    val isProtectiveStop = order.side == OrderSide.SELL && order.orderType == OrderType.STOP && order.positionId != null

                    require(!isProtectiveStop) {
                        "protective STOP cannot be cancelled directly. Use update_protection or close_position."
                    }

                    updateOrderStatus(
                        orderId = order.orderId,
                        status = OrderStatus.CANCELED,
                        reasonJa = command.reasonJa,
                        clock = clock,
                        cancelReason = command.cancelReason,
                        canceledByDecisionRunId = command.auditContext.decisionRunContext.decisionRunId,
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
    }

    /**
     * tick に応じて [PaperLedgerReconcileScope] の範囲で ledger を保守する。
     */
    override suspend fun reconcile(
        tickSnapshot: TickSnapshot,
        simulator: PaperExecutionSimulator,
        simulationContext: PaperSimulationContext?,
        reconcileScope: PaperLedgerReconcileScope,
    ): Result<PaperReconcileResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val writeContext = resolvePaperWriteContext(PaperTradeAuditContext.EMPTY)
                    val reconcileContext = tickSnapshot.toReconcileMarketContext(
                        fallbackSymbolRules = fallbackSymbolRules,
                        simulator = simulator,
                        simulationContext = simulationContext,
                    )
                    val progress = emptyReconcileProgress()

                    updateMarks(
                        lastPrice = reconcileContext.lastPrice,
                        atr14Jpy = tickSnapshot.atr14Jpy?.toBigDecimal(),
                        rules = reconcileContext.rules,
                        clock = clock,
                    )

                    expireRestingEntryOrders(clock.instant(), progress)

                    if (!paperAccountHardHaltReached()) {
                        if (reconcileScope == PaperLedgerReconcileScope.FULL_TICK_EXECUTION) {
                            if (writeContext.baselineAligned) {
                                fillTriggeredEntryOrders(reconcileContext, progress, writeContext, clock)
                            }
                            triggerPositionProtections(reconcileContext, progress, writeContext, clock)
                        }
                    }

                    progress.toPaperReconcileResult()
                }
            }
        }
    }

    override suspend fun applyMarketEvent(
        event: PaperMarketTradeEvent,
        simulator: PaperExecutionSimulator,
    ): Result<PaperReconcileResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val writeContext = resolvePaperWriteContext(PaperTradeAuditContext.EMPTY)
                    applyPaperMarketEvent(event, simulator, fallbackSymbolRules, writeContext, clock)
                }
            }
        }
    }
}

private fun JdbcTransaction.expireRestingEntryOrders(processedAt: Instant, progress: ReconcileProgress) {
    selectOpenOrders()
        .asSequence()
        .filter(Order::isRestingEntryLifecycleCandidate)
        .mapNotNull { order -> order.expiresAt?.let(Instant::parse)?.let { expiresAt -> order to expiresAt } }
        .filter { (_, expiresAt) -> !processedAt.isBefore(expiresAt) }
        .forEach { (order, expiresAt) ->
            prepare(
                """
                    UPDATE orders
                    SET status = ?,
                        expired_at = ?,
                        canceled_at = ?,
                        cancel_reason = ?,
                        reason_ja = ?,
                        updated_at = ?
                    WHERE id = ?
                        AND status = ?
                """,
            ).use { statement ->
                statement.setString(1, OrderStatus.CANCELED.name)
                statement.setLong(2, expiresAt.toEpochMilli())
                statement.setLong(3, processedAt.toEpochMilli())
                statement.setString(4, PaperOrderCancelReason.TTL_EXPIRY.wireCode)
                statement.setString(5, "resting entry order expired")
                statement.setLong(6, processedAt.toEpochMilli())
                statement.setObject(7, UUID.fromString(order.orderId))
                statement.setString(8, order.status.name)

                if (statement.executeUpdate() == 1) progress.canceledOrderIds += order.orderId
            }
        }
}

private fun JdbcTransaction.applyPaperMarketEvent(
    event: PaperMarketTradeEvent,
    simulator: PaperExecutionSimulator,
    rules: SymbolRules,
    writeContext: PaperWriteContext,
    clock: Clock,
): PaperReconcileResult {
    val cursor = lockMarketDataCursor(event.connectionSessionId)

    if (event.sequence <= cursor) {
        return emptyReconcileProgress().toPaperReconcileResult()
    }
    require(event.sequence == cursor + 1) {
        "market-data sequence gap: expected ${cursor + 1}, received ${event.sequence}"
    }

    val ticker = Ticker(
        symbol = event.symbol.apiSymbol,
        last = event.priceJpy.toPlainString(),
        bid = event.priceJpy.toPlainString(),
        ask = event.priceJpy.toPlainString(),
        high = event.priceJpy.toPlainString(),
        low = event.priceJpy.toPlainString(),
        volume = event.sizeBtc.toPlainString(),
        timestamp = event.receivedAt.toString(),
    )
    val simulationContext = PaperSimulationContext(ticker = ticker, rules = rules)
    val progress = emptyReconcileProgress()

    updateMarks(event.priceJpy, null, rules, clock)
    expireRestingEntryOrders(clock.instant(), progress)
    bindExistingPositionsToSession(event)

    if (!paperAccountHardHaltReached()) {
        if (writeContext.baselineAligned) {
            applyEventToRestingEntries(event, simulator, simulationContext, progress, writeContext, clock)
        }
        applyEventToPositionProtections(event, simulator, simulationContext, progress, writeContext, clock)
    }

    advanceMarketDataCursor(event)

    return progress.toPaperReconcileResult()
}

private fun JdbcTransaction.lockMarketDataCursor(sessionId: UUID): Long {
    return prepare(
        """
            SELECT last_processed_sequence
            FROM market_data_sessions
            WHERE id = ? AND state = 'CONNECTED'
            FOR UPDATE
        """,
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "market-data session is not connected." }
            resultSet.getLong("last_processed_sequence")
        }
    }
}

private fun JdbcTransaction.bindExistingPositionsToSession(event: PaperMarketTradeEvent) {
    val isFirstEventAfterRecoveredGap = hasUnrecoveredGapBefore(event.connectionSessionId)
    val eligibleAfterSequence = if (isFirstEventAfterRecoveredGap) event.sequence - 1 else event.sequence

    prepare(
        """
            UPDATE positions
            SET market_data_session_id = ?,
                market_eligible_after_sequence = CASE
                    WHEN market_data_session_id IS NULL THEN ?
                    ELSE ?
                END
            WHERE status = 'OPEN'
                AND (market_data_session_id IS NULL OR market_data_session_id <> ?)
        """,
    ).use { statement ->
        statement.setObject(1, event.connectionSessionId)
        // session 未紐付けの同期 entry と gap 復旧前からの position は最初の event で保護する。
        statement.setLong(2, event.sequence - 1)
        statement.setLong(3, eligibleAfterSequence)
        statement.setObject(4, event.connectionSessionId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.hasUnrecoveredGapBefore(sessionId: UUID): Boolean {
    return prepare(
        "SELECT 1 FROM market_data_gaps WHERE recovered_at IS NULL AND session_id <> ? LIMIT 1",
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.executeQuery().use { resultSet -> resultSet.next() }
    }
}

@Suppress("LongParameterList")
private fun JdbcTransaction.applyEventToRestingEntries(
    event: PaperMarketTradeEvent,
    simulator: PaperExecutionSimulator,
    context: PaperSimulationContext,
    progress: ReconcileProgress,
    writeContext: PaperWriteContext,
    clock: Clock,
) {
    selectMarketEligibleEntryOrders(event, clock.instant()).forEach { marketOrder ->
        val order = marketOrder.order
        val shouldTrigger = when (order.orderType) {
            OrderType.LIMIT -> consumeLimitQueue(event, marketOrder)
            OrderType.STOP -> event.priceJpy >= requireNotNull(order.triggerPriceJpy).toBigDecimal()
            OrderType.MARKET -> false
        }
        if (!shouldTrigger) return@forEach

        val update = order.createEntryUpdate(
            ticker = context.ticker,
            rules = context.rules,
            simulator = simulator,
            simulationContext = context,
        )
        val fill = requireNotNull(update.fill).copy(executedAt = event.receivedAt)

        if (!hasCashForBuyFill(fill)) {
            updateOrderStatus(order.orderId, OrderStatus.REJECTED, "market event entry rejected: insufficient paper cash", clock)
            progress.rejectedOrderIds += order.orderId
            return@forEach
        }

        val positionId = UUID.randomUUID()
        val tradeGroupId = UUID.fromString(requireNotNull(order.tradeGroupId))
        insertEntryFill(
            EntryFillWriteRequest(
                entry = MarketEntryFillRequest(
                    command = order.toPlaceOrderCommand(),
                    fill = fill,
                    positionId = positionId,
                    tradeGroupId = tradeGroupId,
                    stopOrderId = UUID.randomUUID(),
                    source = event,
                ),
                entryOrderId = UUID.fromString(order.orderId),
                insertEntryOrder = false,
            ),
            writeContext.intent(PaperWritePolicy.RISK_INCREASING),
            clock,
        )
        bindPositionToEvent(positionId, event)
        progress.filledOrderIds += order.orderId
        progress.executionIds += fill.executionId.toString()
    }
}

private fun JdbcTransaction.selectMarketEligibleEntryOrders(
    event: PaperMarketTradeEvent,
    processedAt: Instant,
): List<MarketEligibleOrder> {
    return prepare(
        """
            SELECT id, queue_ahead_btc, queue_consumed_btc
            FROM orders
            WHERE status = 'OPEN'
                AND side = 'BUY'
                AND position_id IS NULL
                AND market_data_session_id = ?
                AND market_eligible_after_sequence < ?
                AND market_eligible_from < ?
                AND expires_at > ?
            ORDER BY created_at, id
        """,
    ).use { statement ->
        statement.setObject(1, event.connectionSessionId)
        statement.setLong(2, event.sequence)
        statement.setLong(3, event.receivedAt.toEpochMilli())
        statement.setLong(4, processedAt.toEpochMilli())
        statement.executeQuery().use { resultSet ->
            buildList {
                val ordersById = selectOpenOrders().associateBy(Order::orderId)
                while (resultSet.next()) {
                    val orderId = resultSet.getObject("id", UUID::class.java).toString()
                    val order = requireNotNull(ordersById[orderId])
                    add(
                        MarketEligibleOrder(
                            order = order,
                            queueAheadBtc = resultSet.getBigDecimal("queue_ahead_btc"),
                            queueConsumedBtc = resultSet.getBigDecimal("queue_consumed_btc") ?: BigDecimal.ZERO,
                        ),
                    )
                }
            }
        }
    }
}

private fun JdbcTransaction.consumeLimitQueue(event: PaperMarketTradeEvent, marketOrder: MarketEligibleOrder): Boolean {
    if (event.side != OrderSide.SELL) return false
    val limitPrice = requireNotNull(marketOrder.order.limitPriceJpy).toBigDecimal()
    if (event.priceJpy > limitPrice) return false
    val queueAhead = requireNotNull(marketOrder.queueAheadBtc) { "LIMIT queue snapshot is unavailable." }
    val consumed = marketOrder.queueConsumedBtc.add(event.sizeBtc).btcScale()

    prepare("UPDATE orders SET queue_consumed_btc = ?, updated_at = ? WHERE id = ?").use { statement ->
        statement.setBigDecimal(1, consumed)
        statement.setLong(2, event.receivedAt.toEpochMilli())
        statement.setObject(3, UUID.fromString(marketOrder.order.orderId))
        statement.executeUpdate()
    }

    return consumed >= queueAhead.add(marketOrder.order.sizeBtc.toBigDecimal())
}

private fun JdbcTransaction.bindPositionToEvent(positionId: UUID, event: PaperMarketTradeEvent) {
    prepare(
        """
            UPDATE positions
            SET market_data_session_id = ?, market_eligible_after_sequence = ?
            WHERE id = ?
        """,
    ).use { statement ->
        statement.setObject(1, event.connectionSessionId)
        statement.setLong(2, event.sequence)
        statement.setObject(3, positionId)
        statement.executeUpdate()
    }
}

@Suppress("LongParameterList")
private fun JdbcTransaction.applyEventToPositionProtections(
    event: PaperMarketTradeEvent,
    simulator: PaperExecutionSimulator,
    context: PaperSimulationContext,
    progress: ReconcileProgress,
    writeContext: PaperWriteContext,
    clock: Clock,
) {
    val protectionContext = EventProtectionContext(
        event,
        simulator,
        context,
        progress,
        writeContext.intent(PaperWritePolicy.RISK_REDUCING),
        clock,
    )

    selectEventEligiblePositions(event).forEach { position ->
        val stopPrice = position.currentStopLossJpy?.toBigDecimal()
        val takeProfitPrice = position.currentTakeProfitJpy?.toBigDecimal()

        when {
            stopPrice != null && event.priceJpy <= stopPrice -> triggerEventStop(position, stopPrice, protectionContext)
            takeProfitPrice != null && event.priceJpy >= takeProfitPrice -> triggerEventTakeProfit(position, protectionContext)
        }
    }
}

private fun JdbcTransaction.triggerEventStop(
    position: Position,
    stopPrice: BigDecimal,
    context: EventProtectionContext,
) {
    val event = context.event
    val stopOrder = requireLinkedStopOrder(position.positionId)
    val fill = context.simulator.stopFill(
        OrderSide.SELL,
        position.sizeBtc.toBigDecimal(),
        stopPrice,
        context.simulationContext,
    )
        .copy(executedAt = event.receivedAt)
    val realizedFill = fill.withRealizedPnl(position)

    updateOrderStatus(stopOrder.orderId, OrderStatus.FILLED, "market event stop trigger", context.clock)
    insertExecution(eventExecutionRequest(stopOrder.orderId, position, realizedFill, event, context.writeIntent))
    closePositionRow(position, realizedFill)
    updateAccountAfterSell(realizedFill, context.clock)
    context.progress.filledOrderIds += stopOrder.orderId
    context.progress.closedPositionIds += position.positionId
    context.progress.executionIds += realizedFill.executionId.toString()
}

private fun JdbcTransaction.triggerEventTakeProfit(position: Position, context: EventProtectionContext) {
    val event = context.event
    val stopOrder = requireLinkedStopOrder(position.positionId)
    updateOrderStatus(
        stopOrder.orderId,
        OrderStatus.CANCELED,
        "market event virtual take profit trigger",
        context.clock,
        PaperOrderCancelReason.POSITION_CLOSE,
    )
    val fill = context.simulator.marketFill(OrderSide.SELL, position.sizeBtc.toBigDecimal(), context.simulationContext)
        .copy(executedAt = event.receivedAt)
    val realizedFill = fill.withRealizedPnl(position)
    val closeOrderId = UUID.randomUUID()

    insertCloseOrder(
        CloseOrderInsertRequest(
            closeOrderId,
            position,
            realizedFill.sizeBtc,
            "market event virtual take profit trigger",
            PaperTradeAuditContext.EMPTY,
            context.writeIntent,
        ),
        context.clock,
    )
    insertExecution(eventExecutionRequest(closeOrderId.toString(), position, realizedFill, event, context.writeIntent))
    closePositionRow(position, realizedFill)
    updateAccountAfterSell(realizedFill, context.clock)
    context.progress.canceledOrderIds += stopOrder.orderId
    context.progress.filledOrderIds += closeOrderId.toString()
    context.progress.closedPositionIds += position.positionId
    context.progress.executionIds += realizedFill.executionId.toString()
}

private data class EventProtectionContext(
    val event: PaperMarketTradeEvent,
    val simulator: PaperExecutionSimulator,
    val simulationContext: PaperSimulationContext,
    val progress: ReconcileProgress,
    val writeIntent: PaperWriteIntent,
    val clock: Clock,
)

private fun eventExecutionRequest(
    orderId: String,
    position: Position,
    fill: SimulatedFill,
    event: PaperMarketTradeEvent,
    writeIntent: PaperWriteIntent,
): ExecutionInsertRequest {
    return ExecutionInsertRequest(
        orderId,
        position.positionId,
        position.mode,
        OrderSide.SELL,
        fill,
        PaperTradeAuditContext.EMPTY,
        writeIntent,
        event,
    )
}

private fun JdbcTransaction.selectEventEligiblePositions(event: PaperMarketTradeEvent): List<Position> {
    return prepare(
        """
            SELECT id FROM positions
            WHERE status = 'OPEN'
                AND market_data_session_id = ?
                AND market_eligible_after_sequence < ?
        """,
    ).use { statement ->
        statement.setObject(1, event.connectionSessionId)
        statement.setLong(2, event.sequence)
        statement.executeQuery().use { resultSet ->
            val positionsById = selectOpenPositions().associateBy(Position::positionId)
            buildList {
                while (resultSet.next()) {
                    val positionId = resultSet.getObject("id", UUID::class.java).toString()
                    add(requireNotNull(positionsById[positionId]))
                }
            }
        }
    }
}

private fun JdbcTransaction.advanceMarketDataCursor(event: PaperMarketTradeEvent) {
    prepare(
        """
            UPDATE market_data_sessions
            SET last_processed_sequence = ?,
                last_trade_at = ?,
                last_transport_activity_at = GREATEST(
                    COALESCE(last_transport_activity_at, ?),
                    ?
                )
            WHERE id = ? AND state = 'CONNECTED'
        """,
    ).use { statement ->
        statement.setLong(1, event.sequence)
        statement.setLong(2, event.receivedAt.toEpochMilli())
        statement.setLong(3, event.receivedAt.toEpochMilli())
        statement.setLong(4, event.receivedAt.toEpochMilli())
        statement.setObject(5, event.connectionSessionId)
        require(statement.executeUpdate() == 1) { "market-data cursor update failed." }
    }
    prepare(
        """
            UPDATE market_data_gaps
            SET recovered_at = ?
            WHERE recovered_at IS NULL
                AND session_id <> ?
        """,
    ).use { statement ->
        statement.setLong(1, event.receivedAt.toEpochMilli())
        statement.setObject(2, event.connectionSessionId)
        statement.executeUpdate()
    }
}

private data class MarketEligibleOrder(
    val order: Order,
    val queueAheadBtc: BigDecimal?,
    val queueConsumedBtc: BigDecimal,
)

private fun JdbcTransaction.insertEntryFill(
    request: EntryFillWriteRequest,
    writeIntent: PaperWriteIntent,
    clock: Clock,
): PaperTradeResult {
    val command = request.entry.command
    val fill = request.entry.fill
    val target = resolveEntryFillTarget(request)

    writeEntryOrderForFill(request, target.positionId, writeIntent, clock)
    val divergenceMemos = request.entry.divergenceMemo
        ?.withEntryCommandContext(
            command = command,
            orderId = request.entryOrderId,
            tradeGroupId = request.entry.tradeGroupId,
        )
        ?.let { memo -> listOf(memo) }
        .orEmpty()
    val stopOrderId = upsertPositionForEntryFill(request, target.existingPosition, writeIntent, clock)
    insertExecution(
        ExecutionInsertRequest(
            orderId = request.entryOrderId.toString(),
            positionId = target.positionId.toString(),
            mode = TradingMode.PAPER,
            side = command.side,
            fill = fill,
            auditContext = command.auditContext,
            writeIntent = writeIntent,
            source = request.entry.source,
        ),
    )
    updateAccountAfterBuy(fill, clock)

    return PaperTradeResult(
        accepted = true,
        status = OrderStatus.FILLED,
        orderIds = listOf(request.entryOrderId.toString(), stopOrderId),
        positionIds = listOf(target.positionId.toString()),
        executionIds = listOf(fill.executionId.toString()),
        messageJa = if (target.existingPosition == null) {
            "paper entry を約定し、保護 STOP を作成しました。"
        } else {
            "paper entry を既存 position に合算しました。"
        },
        divergenceMemos = divergenceMemos,
    )
}

private fun JdbcTransaction.resolveEntryFillTarget(request: EntryFillWriteRequest): EntryFillTarget {
    val existingPosition = selectOpenPositions()
        .firstOrNull { position -> position.tradeGroupId == request.entry.tradeGroupId.toString() }
    val positionId = existingPosition
        ?.positionId
        ?.let { value -> UUID.fromString(value) }
        ?: request.entry.positionId

    return EntryFillTarget(
        existingPosition = existingPosition,
        positionId = positionId,
    )
}

private fun JdbcTransaction.writeEntryOrderForFill(
    request: EntryFillWriteRequest,
    positionId: UUID,
    writeIntent: PaperWriteIntent,
    clock: Clock,
) {
    val command = request.entry.command

    if (request.insertEntryOrder) {
        insertEntryOrder(
            EntryOrderInsertRequest(
                command = command,
                orderId = request.entryOrderId,
                positionId = positionId,
                tradeGroupId = request.entry.tradeGroupId,
                status = OrderStatus.FILLED,
                writeIntent = writeIntent,
            ),
            clock,
        )

        return
    }

    updateRestingEntryOrderFill(
        orderId = request.entryOrderId.toString(),
        positionId = positionId,
        reasonJa = command.reasonJa,
        clock = clock,
    )
}

private fun JdbcTransaction.upsertPositionForEntryFill(
    request: EntryFillWriteRequest,
    existingPosition: Position?,
    writeIntent: PaperWriteIntent,
    clock: Clock,
): String {
    val command = request.entry.command
    val fill = request.entry.fill

    if (existingPosition == null) {
        insertPosition(
            command = command,
            fill = fill,
            positionId = request.entry.positionId,
            tradeGroupId = request.entry.tradeGroupId,
            marketEligibility = request.entry.positionMarketEligibility,
            writeIntent = writeIntent,
        )
        insertProtectiveStopOrder(
            command,
            request.entry.stopOrderId,
            request.entry.positionId,
            request.entry.tradeGroupId,
            writeIntent,
            clock,
        )

        return request.entry.stopOrderId.toString()
    }

    val mergedPosition = existingPosition.mergeEntryFill(command, fill)

    updatePositionAfterMergedEntry(mergedPosition)

    return updateLinkedStopOrderForMergedEntry(
        position = mergedPosition,
        stopPrice = mergedPosition.currentStopLossJpy?.toBigDecimal(),
        reasonJa = command.reasonJa,
        clock = clock,
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

private fun JdbcTransaction.fillTriggeredEntryOrders(
    context: ReconcileMarketContext,
    progress: ReconcileProgress,
    writeContext: PaperWriteContext,
    clock: Clock,
) {
    val triggeredOrders = selectOpenOrders()
        .filter { order -> order.status == OrderStatus.OPEN && order.side == OrderSide.BUY }
        .filter { order -> order.isEntryTriggered(context) }

    triggeredOrders.forEach { order ->
        val orderUpdate = order.createEntryUpdate(
            ticker = context.ticker,
            rules = context.rules,
            simulator = context.simulator,
            simulationContext = context.simulationContext,
        )
        val fill = requireNotNull(orderUpdate.fill) {
            "Triggered entry order must create a fill."
        }
        val command = order.toPlaceOrderCommand()
        val positionId = UUID.randomUUID()
        val orderTradeGroupId = order.tradeGroupId
            ?: error("Triggered entry order must have trade group ID.")
        val tradeGroupId = UUID.fromString(orderTradeGroupId)
        val stopOrderId = UUID.randomUUID()

        if (!hasCashForBuyFill(fill)) {
            updateOrderStatus(order.orderId, OrderStatus.REJECTED, "reconciler entry rejected: insufficient paper cash", clock)
            progress.rejectedOrderIds += order.orderId

            return@forEach
        }

        insertEntryFill(
            EntryFillWriteRequest(
                entry = MarketEntryFillRequest(
                    command = command,
                    fill = fill,
                    positionId = positionId,
                    tradeGroupId = tradeGroupId,
                    stopOrderId = stopOrderId,
                ),
                entryOrderId = UUID.fromString(order.orderId),
                insertEntryOrder = false,
            ),
            writeContext.intent(PaperWritePolicy.RISK_INCREASING),
            clock,
        )

        progress.filledOrderIds += order.orderId
        progress.executionIds += fill.executionId.toString()
        orderUpdate.divergenceMemo
            ?.withOrderContext(order)
            ?.let { memo -> progress.divergenceMemos += memo }
    }
}

private fun JdbcTransaction.triggerPositionProtections(
    context: ReconcileMarketContext,
    progress: ReconcileProgress,
    writeContext: PaperWriteContext,
    clock: Clock,
) {
    selectOpenPositions().forEach { position ->
        val stopPrice = position.currentStopLossJpy?.toBigDecimal()
        val takeProfitPrice = position.currentTakeProfitJpy?.toBigDecimal()
        val stopTriggered = stopPrice != null && context.lastPrice <= stopPrice
        val takeProfitTriggered = takeProfitPrice != null && context.lastPrice >= takeProfitPrice

        if (stopTriggered) {
            triggerStopProtection(position, stopPrice, context, progress, writeContext, clock)

            return@forEach
        }

        if (takeProfitTriggered) {
            triggerTakeProfitProtection(position, context, progress, writeContext, clock)
        }
    }
}

@Suppress("LongParameterList")
private fun JdbcTransaction.triggerStopProtection(
    position: Position,
    stopPrice: BigDecimal,
    context: ReconcileMarketContext,
    progress: ReconcileProgress,
    writeContext: PaperWriteContext,
    clock: Clock,
) {
    val stopOrder = requireLinkedStopOrder(position.positionId)
    val fill = context.simulator.stopFill(
        OrderSide.SELL,
        position.sizeBtc.toBigDecimal(),
        stopPrice,
        context.simulationContext,
    )
    val realizedFill = fill.withRealizedPnl(position)

    updateOrderStatus(stopOrder.orderId, OrderStatus.FILLED, "reconciler stop trigger", clock)
    insertExecution(
        ExecutionInsertRequest(
            orderId = stopOrder.orderId,
            positionId = position.positionId,
            mode = position.mode,
            side = OrderSide.SELL,
            fill = realizedFill,
            auditContext = PaperTradeAuditContext.EMPTY,
            writeIntent = writeContext.intent(PaperWritePolicy.RISK_REDUCING),
        ),
    )
    closePositionRow(position, realizedFill)
    updateAccountAfterSell(realizedFill, clock)

    progress.filledOrderIds += stopOrder.orderId
    progress.closedPositionIds += position.positionId
    progress.executionIds += realizedFill.executionId.toString()
}

private fun JdbcTransaction.triggerTakeProfitProtection(
    position: Position,
    context: ReconcileMarketContext,
    progress: ReconcileProgress,
    writeContext: PaperWriteContext,
    clock: Clock,
) {
    requireLinkedStopOrder(position.positionId).let { stopOrder ->
        updateOrderStatus(
            orderId = stopOrder.orderId,
            status = OrderStatus.CANCELED,
            reasonJa = VIRTUAL_TAKE_PROFIT_TRIGGER_REASON,
            clock = clock,
            cancelReason = PaperOrderCancelReason.POSITION_CLOSE,
        )
        progress.canceledOrderIds += stopOrder.orderId
    }

    val fill = context.simulator.marketFill(
        OrderSide.SELL,
        position.sizeBtc.toBigDecimal(),
        context.simulationContext,
    )
    val realizedFill = fill.withRealizedPnl(position)
    val closeOrderId = UUID.randomUUID()

    insertCloseOrder(
        request = CloseOrderInsertRequest(
            orderId = closeOrderId,
            position = position,
            sizeBtc = realizedFill.sizeBtc,
            reasonJa = VIRTUAL_TAKE_PROFIT_TRIGGER_REASON,
            auditContext = PaperTradeAuditContext.EMPTY,
            writeIntent = writeContext.intent(PaperWritePolicy.RISK_REDUCING),
        ),
        clock = clock,
    )
    insertExecution(
        ExecutionInsertRequest(
            orderId = closeOrderId.toString(),
            positionId = position.positionId,
            mode = position.mode,
            side = OrderSide.SELL,
            fill = realizedFill,
            auditContext = PaperTradeAuditContext.EMPTY,
            writeIntent = writeContext.intent(PaperWritePolicy.RISK_REDUCING),
        ),
    )
    closePositionRow(position, realizedFill)
    updateAccountAfterSell(realizedFill, clock)

    progress.closedPositionIds += position.positionId
    progress.executionIds += realizedFill.executionId.toString()
}

private fun JdbcTransaction.updateMarks(
    lastPrice: BigDecimal,
    atr14Jpy: BigDecimal?,
    rules: SymbolRules,
    clock: Clock,
) {
    selectOpenPositions().forEach { position ->
        val markUpdate = position.toPositionMarkUpdate(
            lastPrice = lastPrice,
            atr14Jpy = atr14Jpy,
            rules = rules,
        )

        updatePositionMark(markUpdate)

        if (position.hasTightenedStop(markUpdate)) {
            updateLinkedStopOrder(
                position.positionId,
                checkNotNull(markUpdate.tightenedStop),
                "reconciler atr trailing floor",
                clock,
            )
        }
    }

    updateAccountMark(lastPrice, clock)
}

private fun JdbcTransaction.insertEntryOrder(request: EntryOrderInsertRequest, clock: Clock) {
    request.marketEligibility?.let { eligibility -> verifyMarketEligibilitySession(eligibility) }
    val lineage = request.writeIntent.lineage

    prepare(
        """
            INSERT INTO orders (
                id, intent_id, position_id, trade_group_id, mode, symbol, side, order_type, status,
                size_btc, limit_price_jpy, trigger_price_jpy, protective_stop_price_jpy,
                take_profit_price_jpy, estimated_win_probability, reason_ja,
                decision_run_id, tool_call_id, client_request_id, llm_provider, prompt_hash,
                system_prompt_version, market_snapshot_id, expires_at, expiry_source,
                effective_ttl_seconds, expired_at, canceled_at, cancel_reason, canceled_by_decision_run_id,
                queue_ahead_btc, queue_consumed_btc, queue_snapshot_at, market_data_session_id,
                market_eligible_after_sequence, market_eligible_from, created_at, updated_at,
                account_epoch_id, execution_semantics_version, runtime_config_hash
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
    ).use { statement ->
        val command = request.command

        statement.setObject(1, request.orderId)
        statement.setObject(2, command.intentId)
        statement.setObject(3, request.positionId)
        statement.setObject(4, request.tradeGroupId)
        statement.setString(5, TradingMode.PAPER.name)
        statement.setString(6, command.symbol.apiSymbol)
        statement.setString(7, command.side.name)
        statement.setString(8, command.orderType.name)
        statement.setString(9, request.status.name)
        statement.setBigDecimal(10, command.sizeBtc.btcScale())
        statement.setNullableBigDecimal(11, command.priceJpy.takeIf { command.orderType == OrderType.LIMIT }?.moneyScale())
        statement.setNullableBigDecimal(12, command.priceJpy.takeIf { command.orderType == OrderType.STOP }?.moneyScale())
        statement.setBigDecimal(13, command.protectiveStopPriceJpy.moneyScale())
        statement.setNullableBigDecimal(14, command.takeProfitPriceJpy?.moneyScale())
        statement.setBigDecimal(15, command.estimatedWinProbability.ratioScale())
        statement.setString(16, command.reasonJa)
        statement.bindAudit(17, command.auditContext)
        statement.setNullableLong(24, request.expiresAt?.toEpochMilli())
        statement.setString(25, request.expirySource?.name)
        statement.setNullableLong(26, request.effectiveTtlSeconds)
        statement.setObject(27, null)
        statement.setObject(28, null)
        statement.setString(29, null)
        statement.setString(30, null)
        val eligibility = request.marketEligibility
        statement.setNullableBigDecimal(31, eligibility?.queueAheadBtc?.btcScale())
        statement.setNullableBigDecimal(32, eligibility?.queueAheadBtc?.let { BigDecimal.ZERO.btcScale() })
        statement.setObject(33, eligibility?.queueSnapshotAt?.toEpochMilli())
        statement.setObject(34, eligibility?.sessionId)
        statement.setObject(35, eligibility?.eligibleAfterSequence)
        statement.setObject(36, eligibility?.eligibleFrom?.toEpochMilli())
        val createdAt = request.createdAt?.toEpochMilli() ?: nowMillis(clock)
        statement.setLong(37, createdAt)
        statement.setLong(38, createdAt)
        statement.bindLineage(39, lineage)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.verifyMarketEligibilitySession(eligibility: RestingOrderMarketEligibility) {
    prepare(
        """
            SELECT last_processed_sequence
            FROM market_data_sessions
            WHERE id = ? AND state = 'CONNECTED'
            FOR UPDATE
        """,
    ).use { statement ->
        statement.setObject(1, eligibility.sessionId)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) {
                "QUEUE_SNAPSHOT_UNAVAILABLE: market-data session is not connected."
            }
            require(resultSet.getLong("last_processed_sequence") == eligibility.eligibleAfterSequence) {
                "QUEUE_SNAPSHOT_UNAVAILABLE: market-data session advanced during order creation."
            }
        }
    }
}

@Suppress("LongParameterList")
private fun JdbcTransaction.insertProtectiveStopOrder(
    command: PlaceOrderCommand,
    stopOrderId: UUID,
    positionId: UUID,
    tradeGroupId: UUID,
    writeIntent: PaperWriteIntent,
    clock: Clock,
) {
    val lineage = writeIntent.lineage
    prepare(
        """
            INSERT INTO orders (
                id, position_id, trade_group_id, mode, symbol, side, order_type, status,
                size_btc, limit_price_jpy, trigger_price_jpy, protective_stop_price_jpy,
                take_profit_price_jpy, estimated_win_probability, reason_ja,
                decision_run_id, tool_call_id, client_request_id, llm_provider, prompt_hash,
                system_prompt_version, market_snapshot_id, created_at, updated_at,
                account_epoch_id, execution_semantics_version, runtime_config_hash
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        statement.setLong(19, nowMillis(clock))
        statement.setLong(20, nowMillis(clock))
        statement.bindLineage(21, lineage)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertCloseOrder(request: CloseOrderInsertRequest, clock: Clock) {
    val lineage = request.writeIntent.lineage
    prepare(
        """
            INSERT INTO orders (
                id, position_id, trade_group_id, mode, symbol, side, order_type, status,
                size_btc, limit_price_jpy, trigger_price_jpy, protective_stop_price_jpy,
                take_profit_price_jpy, estimated_win_probability, reason_ja,
                decision_run_id, tool_call_id, client_request_id, llm_provider, prompt_hash,
                system_prompt_version, market_snapshot_id, created_at, updated_at,
                account_epoch_id, execution_semantics_version, runtime_config_hash
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
    ).use { statement ->
        statement.bindOrderId(
            request.orderId,
            UUID.fromString(request.position.positionId),
            UUID.fromString(request.position.tradeGroupId),
        )
        statement.setString(4, request.position.mode.name)
        statement.setString(5, request.position.symbol)
        statement.setString(6, OrderSide.SELL.name)
        statement.setString(7, OrderType.MARKET.name)
        statement.setString(8, OrderStatus.FILLED.name)
        statement.setBigDecimal(9, request.sizeBtc.btcScale())
        statement.setString(10, request.reasonJa)
        statement.bindAudit(11, request.auditContext)
        statement.setLong(18, nowMillis(clock))
        statement.setLong(19, nowMillis(clock))
        statement.bindLineage(20, lineage)
        statement.executeUpdate()
    }
}

@Suppress("LongParameterList")
private fun JdbcTransaction.insertPosition(
    command: PlaceOrderCommand,
    fill: SimulatedFill,
    positionId: UUID,
    tradeGroupId: UUID,
    marketEligibility: PositionMarketEligibility?,
    writeIntent: PaperWriteIntent,
) {
    val lineage = writeIntent.lineage
    prepare(
        """
            INSERT INTO positions (
                id, trade_group_id, mode, symbol, side, status, opened_at, closed_at,
                size_btc, average_entry_price_jpy, current_price_jpy, current_stop_loss_jpy,
                current_take_profit_jpy, unrealized_pnl_jpy, unrealized_r, pyramid_add_count,
                highest_price_since_entry_jpy, lowest_price_since_entry_jpy, decision_run_id, tool_call_id,
                client_request_id, llm_provider, prompt_hash, system_prompt_version,
                market_snapshot_id, market_data_session_id, market_eligible_after_sequence,
                account_epoch_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        statement.setObject(25, marketEligibility?.sessionId)
        statement.setObject(26, marketEligibility?.eligibleAfterSequence)
        statement.setObject(27, UUID.fromString(lineage.accountEpochId))
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertExecution(request: ExecutionInsertRequest) {
    val lineage = request.writeIntent.lineage
    prepare(
        """
            INSERT INTO executions (
                id, order_id, position_id, mode, symbol, side, price_jpy, size_btc,
                fee_jpy, realized_pnl_jpy, liquidity, executed_at, decision_run_id,
                tool_call_id, client_request_id, llm_provider, prompt_hash,
                system_prompt_version, market_snapshot_id, source_session_id, source_sequence,
                source_exchange_at, source_received_at, source_side, source_price_jpy, source_size_btc,
                account_epoch_id, execution_semantics_version, runtime_config_hash
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        val source = request.source
        statement.setObject(20, source?.connectionSessionId)
        statement.setObject(21, source?.sequence)
        statement.setObject(22, source?.exchangeAt?.toEpochMilli())
        statement.setObject(23, source?.receivedAt?.toEpochMilli())
        statement.setString(24, source?.side?.name)
        statement.setNullableBigDecimal(25, source?.priceJpy?.moneyScale())
        statement.setNullableBigDecimal(26, source?.sizeBtc?.btcScale())
        statement.bindLineage(27, lineage)
        statement.executeUpdate()
    }
}

/** transaction 入口で current lineage を一度だけ固定する。 */
private fun JdbcTransaction.resolvePaperWriteContext(auditContext: PaperTradeAuditContext): PaperWriteContext {
    val account = prepare(
        """
            SELECT account.current_epoch_id, account.initial_cash_jpy,
                epoch.initial_cash_jpy AS epoch_baseline
            FROM paper_account account
            JOIN paper_account_epochs epoch ON epoch.id = account.current_epoch_id
            WHERE account.id = ?
            FOR SHARE
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "PAPER_EXECUTION_LINEAGE_UNAVAILABLE: current account epoch is missing." }
            Triple(
                resultSet.getObject("current_epoch_id", UUID::class.java),
                resultSet.getBigDecimal("initial_cash_jpy"),
                resultSet.getBigDecimal("epoch_baseline"),
            )
        }
    }
    val activeValues = linkedMapOf<String, String>()
    prepare(
        "SELECT value.config_key, value.config_value FROM runtime_config_values value JOIN runtime_config_versions version ON version.id=value.version_id WHERE version.status='ACTIVE' ORDER BY value.config_key",
    ).use { statement ->
        statement.executeQuery().use { resultSet ->
            while (resultSet.next()) activeValues[resultSet.getString(1)] = resultSet.getString(2)
        }
    }
    val activeHash = calculateRuntimeConfigHash(activeValues)
    val configBaseline = activeValues["paper.initialCashJpy"]?.let(::BigDecimal)
    val accountMatchesEpoch = account.second.compareTo(account.third) == 0
    val accountMatchesConfig = configBaseline != null && account.second.compareTo(configBaseline) == 0
    val auditHash = auditContext.decisionRunContext.runtimeConfigHash
    require(auditHash == null || auditHash == activeHash) {
        "PAPER_EXECUTION_LINEAGE_MISMATCH: command and current account epoch runtime config hashes differ."
    }

    return PaperWriteContext(
        lineage = PaperExecutionLineage(
            accountEpochId = account.first.toString(),
            executionSemanticsVersion = PAPER_EXECUTION_SEMANTICS_VERSION,
            runtimeConfigHash = activeHash,
        ),
        baselineAligned = accountMatchesEpoch && accountMatchesConfig,
    )
}

private enum class PaperWritePolicy {
    RISK_INCREASING,
    RISK_REDUCING,
    PROTECTION_MAINTENANCE,
}

private data class PaperWriteContext(
    val lineage: PaperExecutionLineage,
    val baselineAligned: Boolean,
) {
    fun intent(policy: PaperWritePolicy): PaperWriteIntent {
        requireAllowed(policy)

        return PaperWriteIntent(lineage, policy)
    }

    fun requireAllowed(policy: PaperWritePolicy) {
        require(policy != PaperWritePolicy.RISK_INCREASING || baselineAligned) {
            "PAPER_ACCOUNT_BASELINE_MISMATCH: create, validate, and activate an operator runtime-config draft."
        }
    }
}

private fun JdbcTransaction.requirePaperWriteAllowed(policy: PaperWritePolicy, auditContext: PaperTradeAuditContext) {
    resolvePaperWriteContext(auditContext).requireAllowed(policy)
}

private data class PaperWriteIntent(
    val lineage: PaperExecutionLineage,
    val policy: PaperWritePolicy,
)

/** prepared statement の連続3列へ lineage を bind する。 */
private fun PreparedStatement.bindLineage(startIndex: Int, lineage: PaperExecutionLineage) {
    setObject(startIndex, UUID.fromString(lineage.accountEpochId))
    setString(startIndex + 1, lineage.executionSemanticsVersion)
    setString(startIndex + 2, lineage.runtimeConfigHash)
}

private fun JdbcTransaction.updatePositionMark(update: PositionMarkUpdate) {
    prepare(
        """
            UPDATE positions
            SET current_price_jpy = ?,
                current_stop_loss_jpy = COALESCE(?, current_stop_loss_jpy),
                unrealized_pnl_jpy = ?,
                unrealized_r = ?,
                highest_price_since_entry_jpy = ?,
                lowest_price_since_entry_jpy = ?
            WHERE id = ?
        """,
    ).use { statement ->
        statement.setBigDecimal(1, update.lastPrice.moneyScale())
        statement.setNullableBigDecimal(2, update.tightenedStop?.moneyScale())
        statement.setBigDecimal(3, update.unrealizedPnl.moneyScale())
        statement.setBigDecimal(4, update.unrealizedR.ratioScale())
        statement.setBigDecimal(5, update.highestPrice.moneyScale())
        statement.setBigDecimal(6, update.lowestPrice.moneyScale())
        statement.setObject(7, UUID.fromString(update.positionId))
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
                size_btc = 0,
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

private fun JdbcTransaction.updatePositionAfterPartialClose(
    position: Position,
    fill: SimulatedFill,
    remainingSize: BigDecimal,
) {
    val highestPrice = maxOf(position.highestPriceSinceEntryJpy.toBigDecimal(), fill.priceJpy)
    val currentLowestPrice = position.lowestPriceSinceEntryJpy?.toBigDecimal() ?: fill.priceJpy
    val lowestPrice = minOf(currentLowestPrice, fill.priceJpy)
    val unrealizedPnl = position.unrealizedPnlAt(fill.priceJpy, remainingSize).toBigDecimal()
    val unrealizedR = position.unrealizedRAt(fill.priceJpy).toBigDecimal()

    prepare(
        """
            UPDATE positions
            SET size_btc = ?,
                current_price_jpy = ?,
                unrealized_pnl_jpy = ?,
                unrealized_r = ?,
                highest_price_since_entry_jpy = ?,
                lowest_price_since_entry_jpy = ?
            WHERE id = ?
                AND status = ?
        """,
    ).use { statement ->
        statement.setBigDecimal(1, remainingSize.btcScale())
        statement.setBigDecimal(2, fill.priceJpy.moneyScale())
        statement.setBigDecimal(3, unrealizedPnl.moneyScale())
        statement.setBigDecimal(4, unrealizedR.ratioScale())
        statement.setBigDecimal(5, highestPrice.moneyScale())
        statement.setBigDecimal(6, lowestPrice.moneyScale())
        statement.setObject(7, UUID.fromString(position.positionId))
        statement.setString(8, PositionStatus.OPEN.name)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.updatePositionAfterMergedEntry(position: Position) {
    prepare(
        """
            UPDATE positions
            SET size_btc = ?,
                average_entry_price_jpy = ?,
                current_price_jpy = ?,
                current_stop_loss_jpy = ?,
                current_take_profit_jpy = ?,
                unrealized_pnl_jpy = ?,
                unrealized_r = ?,
                pyramid_add_count = ?,
                highest_price_since_entry_jpy = ?,
                lowest_price_since_entry_jpy = ?
            WHERE id = ?
                AND status = ?
        """,
    ).use { statement ->
        statement.setBigDecimal(1, position.sizeBtc.toBigDecimal().btcScale())
        statement.setBigDecimal(2, position.averageEntryPriceJpy.toBigDecimal().moneyScale())
        statement.setBigDecimal(3, position.currentPriceJpy.toBigDecimal().moneyScale())
        statement.setNullableBigDecimal(4, position.currentStopLossJpy?.toBigDecimal()?.moneyScale())
        statement.setNullableBigDecimal(5, position.currentTakeProfitJpy?.toBigDecimal()?.moneyScale())
        statement.setBigDecimal(6, position.unrealizedPnlJpy.toBigDecimal().moneyScale())
        statement.setBigDecimal(7, position.unrealizedR.toBigDecimal().ratioScale())
        statement.setInt(8, position.pyramidAddCount)
        statement.setBigDecimal(9, position.highestPriceSinceEntryJpy.toBigDecimal().moneyScale())
        statement.setNullableBigDecimal(10, position.lowestPriceSinceEntryJpy?.toBigDecimal()?.moneyScale())
        statement.setObject(11, UUID.fromString(position.positionId))
        statement.setString(12, PositionStatus.OPEN.name)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.updateLinkedStopOrder(
    positionId: String,
    stopPrice: BigDecimal,
    reasonJa: String,
    clock: Clock,
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
        statement.setLong(3, nowMillis(clock))
        statement.bindOpenStopOrderFilter(startIndex = 4, positionId = positionId)

        statement.executeUpdate()
    }
}

private fun JdbcTransaction.updateLinkedStopOrderSize(
    positionId: String,
    sizeBtc: BigDecimal,
    reasonJa: String,
    clock: Clock,
) {
    prepare(
        """
            UPDATE orders
            SET size_btc = ?,
                reason_ja = ?,
                updated_at = ?
            WHERE position_id = ?
                AND side = ?
                AND order_type = ?
                AND status = ?
        """,
    ).use { statement ->
        statement.setBigDecimal(1, sizeBtc.btcScale())
        statement.setString(2, reasonJa)
        statement.setLong(3, nowMillis(clock))
        statement.bindOpenStopOrderFilter(startIndex = 4, positionId = positionId)

        statement.executeUpdate()
    }
}

private fun JdbcTransaction.updateLinkedStopOrderForMergedEntry(
    position: Position,
    stopPrice: BigDecimal?,
    reasonJa: String,
    clock: Clock,
): String {
    val stopOrder = requireLinkedStopOrder(position.positionId)

    prepare(
        """
            UPDATE orders
            SET size_btc = ?,
                trigger_price_jpy = COALESCE(?, trigger_price_jpy),
                reason_ja = ?,
                updated_at = ?
            WHERE id = ?
        """,
    ).use { statement ->
        statement.setBigDecimal(1, position.sizeBtc.toBigDecimal().btcScale())
        statement.setNullableBigDecimal(2, stopPrice?.moneyScale())
        statement.setString(3, "merged entry protective stop: $reasonJa")
        statement.setLong(4, nowMillis(clock))
        statement.setObject(5, UUID.fromString(stopOrder.orderId))
        statement.executeUpdate()
    }

    return stopOrder.orderId
}

private fun JdbcTransaction.updateOrderStatus(
    orderId: String,
    status: OrderStatus,
    reasonJa: String,
    clock: Clock,
    cancelReason: PaperOrderCancelReason? = null,
    canceledByDecisionRunId: String? = null,
) {
    val canceledAt = if (status == OrderStatus.CANCELED) clock.instant() else null
    val cancelReasonCode = if (status == OrderStatus.CANCELED) {
        requireNotNull(cancelReason) { "cancelReason is required for CANCELED order status." }.wireCode
    } else {
        null
    }

    prepare(
        """
            UPDATE orders
            SET status = ?,
                reason_ja = ?,
                canceled_at = COALESCE(?, canceled_at),
                cancel_reason = COALESCE(?, cancel_reason),
                canceled_by_decision_run_id = COALESCE(?, canceled_by_decision_run_id),
                updated_at = ?
            WHERE id = ?
        """,
    ).use { statement ->
        statement.setString(1, status.name)
        statement.setString(2, reasonJa)
        statement.setNullableLong(3, canceledAt?.toEpochMilli())
        statement.setString(4, cancelReasonCode)
        statement.setString(5, canceledByDecisionRunId)
        statement.setLong(6, nowMillis(clock))
        statement.setObject(7, UUID.fromString(orderId))
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.updateRestingEntryOrderFill(
    orderId: String,
    positionId: UUID,
    reasonJa: String,
    clock: Clock,
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
        statement.setLong(4, nowMillis(clock))
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

private fun JdbcTransaction.cancelOpenStopOrders(
    positionId: String,
    reasonJa: String,
    clock: Clock,
) {
    prepare(
        """
            UPDATE orders
            SET status = ?,
                reason_ja = ?,
                canceled_at = ?,
                cancel_reason = ?,
                updated_at = ?
            WHERE position_id = ?
                AND side = ?
                AND order_type = ?
                AND status = ?
        """,
    ).use { statement ->
        statement.setString(1, OrderStatus.CANCELED.name)
        statement.setString(2, reasonJa)
        statement.setLong(3, nowMillis(clock))
        statement.setString(4, PaperOrderCancelReason.POSITION_CLOSE.wireCode)
        statement.setLong(5, nowMillis(clock))
        statement.bindOpenStopOrderFilter(startIndex = 6, positionId = positionId)
        statement.executeUpdate()
    }
}

private fun PreparedStatement.bindOpenStopOrderFilter(startIndex: Int, positionId: String) {
    setObject(startIndex, UUID.fromString(positionId))
    setString(startIndex + 1, OrderSide.SELL.name)
    setString(startIndex + 2, OrderType.STOP.name)
    setString(startIndex + 3, OrderStatus.OPEN.name)
}

private fun JdbcTransaction.updateAccountAfterBuy(fill: SimulatedFill, clock: Clock) {
    val account = selectPaperAccount()
    val spentCash = fill.priceJpy.multiply(fill.sizeBtc).add(fill.feeJpy)
    val cash = account.cashJpy.toBigDecimal().subtract(spentCash).moneyScale()
    val btcQuantity = account.btcQuantity.toBigDecimal().add(fill.sizeBtc).btcScale()
    val updatedAccount = updateAccount(
        cash = cash,
        btcQuantity = btcQuantity,
        markPrice = fill.priceJpy,
        clock = clock,
    )

    appendFillEquitySnapshot(updatedAccount, fill.executedAt)
}

private fun JdbcTransaction.hasCashForBuyFill(fill: SimulatedFill): Boolean {
    val account = selectPaperAccount()
    val spentCash = fill.priceJpy.multiply(fill.sizeBtc).add(fill.feeJpy).moneyScale()

    return spentCash <= account.cashJpy.toBigDecimal()
}

private fun JdbcTransaction.updateAccountAfterSell(fill: SimulatedFill, clock: Clock) {
    val account = selectPaperAccount()
    val receivedCash = fill.priceJpy.multiply(fill.sizeBtc).subtract(fill.feeJpy)
    val cash = account.cashJpy.toBigDecimal().add(receivedCash).moneyScale()
    val btcQuantity = account.btcQuantity.toBigDecimal().subtract(fill.sizeBtc).btcScale()
    val updatedAccount = updateAccount(
        cash = cash,
        btcQuantity = btcQuantity,
        markPrice = fill.priceJpy,
        clock = clock,
    )

    appendFillEquitySnapshot(updatedAccount, fill.executedAt)
}

private fun JdbcTransaction.updateAccountMark(markPrice: BigDecimal, clock: Clock) {
    val account = selectPaperAccount()

    updateAccount(
        cash = account.cashJpy.toBigDecimal(),
        btcQuantity = account.btcQuantity.toBigDecimal(),
        markPrice = markPrice,
        clock = clock,
    )
}

private fun JdbcTransaction.updateAccount(
    cash: BigDecimal,
    btcQuantity: BigDecimal,
    markPrice: BigDecimal,
    clock: Clock,
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
        statement.setLong(7, nowMillis(clock))
        statement.setInt(8, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeUpdate()
    }

    syncRiskStateEquity(equityPeak, drawdownRatio, clock)

    return updatedAccount
}

private fun JdbcTransaction.appendFillEquitySnapshot(account: AccountSnapshot, capturedAt: Instant) {
    val snapshot = account.toFillEquitySnapshotRecord(
        id = UUID.randomUUID(),
        capturedAt = capturedAt,
    )

    insertEquitySnapshot(snapshot)
}

private fun JdbcTransaction.syncRiskStateEquity(
    equityPeak: BigDecimal,
    drawdownRatio: BigDecimal,
    clock: Clock,
) {
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
        statement.setLong(3, nowMillis(clock))
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

internal fun JdbcTransaction.prepare(sql: String): PreparedStatement {
    return jdbcConnection().prepareStatement(sql.trimIndent())
}

private fun nowMillis(clock: Clock): Long {
    return clock.instant().toEpochMilli()
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
 * entry order insert の入力。
 *
 * @param command place_order command
 * @param orderId 作成する order ID
 * @param positionId 紐づく position ID
 * @param tradeGroupId entry order の trade group ID
 * @param status 作成時の order status
 */
private data class EntryOrderInsertRequest(
    val command: PlaceOrderCommand,
    val orderId: UUID,
    val positionId: UUID?,
    val tradeGroupId: UUID,
    val status: OrderStatus,
    val writeIntent: PaperWriteIntent,
    val createdAt: Instant? = null,
    val expiresAt: Instant? = null,
    val expirySource: OrderExpirySource? = null,
    val effectiveTtlSeconds: Long? = null,
    val marketEligibility: RestingOrderMarketEligibility? = null,
)

/**
 * entry fill が更新する position の解決結果。
 *
 * @param existingPosition 同じ trade group の既存 open position
 * @param positionId 約定 execution と order に紐づく position ID
 */
private data class EntryFillTarget(
    val existingPosition: Position?,
    val positionId: UUID,
)

/**
 * close order insert の入力。
 *
 * @param orderId 作成する close order ID
 * @param position close 対象 position
 * @param sizeBtc close する BTC 数量
 * @param reasonJa close 理由
 * @param auditContext audit context
 */
private data class CloseOrderInsertRequest(
    val orderId: UUID,
    val position: Position,
    val sizeBtc: BigDecimal,
    val reasonJa: String,
    val auditContext: PaperTradeAuditContext,
    val writeIntent: PaperWriteIntent,
)

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
    val writeIntent: PaperWriteIntent,
    val source: PaperMarketTradeEvent? = null,
)

private fun Order.isEntryTriggered(context: ReconcileMarketContext): Boolean {
    return when (orderType) {
        OrderType.MARKET -> false
        OrderType.LIMIT -> {
            val limitPrice = limitPriceJpy?.toBigDecimal()

            limitPrice != null && limitOrderReached(
                side = side,
                limitPriceJpy = limitPrice,
                context = context.simulationContext,
                lastPrice = context.lastPrice,
            )
        }
        OrderType.STOP -> triggerPriceJpy?.toBigDecimal()?.let { price -> context.lastPrice >= price } ?: false
    }
}

private fun Order.createEntryUpdate(
    ticker: Ticker,
    rules: SymbolRules,
    simulator: PaperExecutionSimulator,
    simulationContext: PaperSimulationContext,
): PaperOrderUpdate {
    return simulator.restingEntryUpdate(
        request = RestingEntryFillRequest(
            side = side,
            orderType = orderType,
            sizeBtc = sizeBtc.toBigDecimal(),
            limitPriceJpy = limitPriceJpy?.toBigDecimal(),
            triggerPriceJpy = triggerPriceJpy?.toBigDecimal(),
            ticker = ticker,
            rules = rules,
        ),
        context = simulationContext,
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
