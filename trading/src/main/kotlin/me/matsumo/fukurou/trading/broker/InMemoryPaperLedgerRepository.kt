package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.config.PaperMarketConfig
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
import me.matsumo.fukurou.trading.evaluation.InMemoryEquitySnapshotRepository
import me.matsumo.fukurou.trading.evaluation.toFillEquitySnapshotRecord
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.knowledge.ClosedPaperPosition
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.reconciler.requireTicker
import me.matsumo.fukurou.trading.safety.SafetyFloorDefaults
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * unit test と明示 injection 用の mutable paper ledger repository。
 *
 * @param accountSnapshot 残高 snapshot
 * @param accountUpdatedAt paper account 更新時刻
 * @param positions position 一覧
 * @param openOrders open order 一覧
 * @param executions execution 一覧
 * @param decisionRunIdsByPositionId position ID と LLM invocation ID の対応
 * @param decisionContextsByRunId decision run ID と entry decision context の対応
 * @param equitySnapshotRepository equity snapshot 保存先
 * @param fallbackSymbolRules tick に symbol rules がない場合の fallback 取引ルール
 * @param clock paper ledger の作成時刻に使う clock
 */
class InMemoryPaperLedgerRepository private constructor(
    private val state: InMemoryPaperLedgerState,
    accountRepository: PaperLedgerAccountRepository,
    executionRepository: PaperLedgerExecutionRepository,
    historyRepository: PaperLedgerHistoryRepository,
    mutationRepository: PaperLedgerMutationRepository,
) : PaperLedgerRepository,
    PaperLedgerAccountRepository by accountRepository,
    PaperLedgerExecutionRepository by executionRepository,
    PaperLedgerHistoryRepository by historyRepository,
    PaperLedgerMutationRepository by mutationRepository {

    internal val equitySnapshotRepository: InMemoryEquitySnapshotRepository
        get() = state.equitySnapshotRepository

    private constructor(state: InMemoryPaperLedgerState) : this(
        state = state,
        accountRepository = InMemoryPaperLedgerAccountReader(state),
        executionRepository = InMemoryPaperLedgerExecutionReader(state),
        historyRepository = InMemoryPaperLedgerHistoryReader(state),
        mutationRepository = InMemoryPaperLedgerMutationWriter(state),
    )

    constructor(
        accountSnapshot: AccountSnapshot = PaperAccountConfig().toInitialAccountSnapshot(),
        accountUpdatedAt: Instant = Instant.EPOCH,
        positions: List<Position> = emptyList(),
        openOrders: List<Order> = emptyList(),
        executions: List<Execution> = emptyList(),
        decisionRunIdsByPositionId: Map<String, String?> = emptyMap(),
        decisionContextsByRunId: Map<String, ExecutionActivityDecisionContext> = emptyMap(),
        equitySnapshotRepository: InMemoryEquitySnapshotRepository = InMemoryEquitySnapshotRepository(),
        fallbackSymbolRules: SymbolRules = PaperMarketConfig().toSymbolRules(TradingSymbol.BTC),
        clock: Clock = Clock.systemUTC(),
    ) : this(
        InMemoryPaperLedgerState(
            account = InMemoryPaperLedgerAccountSeed(
                accountSnapshot = accountSnapshot,
                accountUpdatedAt = accountUpdatedAt,
            ),
            records = InMemoryPaperLedgerRecordsSeed(
                positions = positions,
                openOrders = openOrders,
                executions = executions,
                decisionRunIdsByPositionId = decisionRunIdsByPositionId,
                decisionContextsByRunId = decisionContextsByRunId,
            ),
            runtime = InMemoryPaperLedgerRuntime(
                equitySnapshotRepository = equitySnapshotRepository,
                fallbackSymbolRules = fallbackSymbolRules,
                clock = clock,
            ),
        ),
    )

    /**
     * unit test で closed position を含む watermark 確定値を検証するため、全 position を返す。
     */
    internal fun getAllPositionsForTest(): List<Position> {
        return state.read { positions.toList() }
    }
}

/**
 * InMemory paper ledger の account seed。
 *
 * @param accountSnapshot 残高 snapshot
 * @param accountUpdatedAt paper account 更新時刻
 */
private data class InMemoryPaperLedgerAccountSeed(
    val accountSnapshot: AccountSnapshot,
    val accountUpdatedAt: Instant,
)

/**
 * InMemory paper ledger の記録 seed。
 *
 * @param positions position 一覧
 * @param openOrders open order 一覧
 * @param executions execution 一覧
 * @param decisionRunIdsByPositionId position ID と LLM invocation ID の対応
 * @param decisionContextsByRunId decision run ID と entry decision context の対応
 */
private data class InMemoryPaperLedgerRecordsSeed(
    val positions: List<Position>,
    val openOrders: List<Order>,
    val executions: List<Execution>,
    val decisionRunIdsByPositionId: Map<String, String?>,
    val decisionContextsByRunId: Map<String, ExecutionActivityDecisionContext>,
)

/**
 * InMemory paper ledger の runtime 依存。
 *
 * @param equitySnapshotRepository equity snapshot 保存先
 * @param fallbackSymbolRules tick に symbol rules がない場合の fallback 取引ルール
 * @param clock paper ledger の作成時刻に使う clock
 */
private data class InMemoryPaperLedgerRuntime(
    val equitySnapshotRepository: InMemoryEquitySnapshotRepository,
    val fallbackSymbolRules: SymbolRules,
    val clock: Clock,
)

/**
 * InMemory paper ledger の共有 mutable state。
 *
 * @param account account seed
 * @param records 記録 seed
 * @param runtime runtime 依存
 */
private class InMemoryPaperLedgerState(
    account: InMemoryPaperLedgerAccountSeed,
    records: InMemoryPaperLedgerRecordsSeed,
    runtime: InMemoryPaperLedgerRuntime,
) {
    private val lock = Any()
    var accountSnapshot: AccountSnapshot = account.accountSnapshot
    var accountUpdatedAt: Instant = account.accountUpdatedAt
    val positions: MutableList<Position> = records.positions.toMutableList()
    val orders: MutableList<Order> = records.openOrders.toMutableList()
    val executions: MutableList<Execution> = records.executions.toMutableList()
    val decisionRunIdsByPositionId: MutableMap<String, String?> = records.decisionRunIdsByPositionId.toMutableMap()
    val decisionContextsByRunId: Map<String, ExecutionActivityDecisionContext> =
        records.decisionContextsByRunId
    val equitySnapshotRepository: InMemoryEquitySnapshotRepository = runtime.equitySnapshotRepository
    val fallbackSymbolRules: SymbolRules = runtime.fallbackSymbolRules
    val clock: Clock = runtime.clock

    fun <T> read(block: InMemoryPaperLedgerState.() -> T): T {
        return synchronized(lock) { block() }
    }

    fun <T> write(block: InMemoryPaperLedgerState.() -> T): T {
        return synchronized(lock) { block() }
    }
}

/**
 * InMemory paper ledger の account / position / order 読み取り boundary。
 *
 * @param state 共有 mutable state
 */
private class InMemoryPaperLedgerAccountReader(
    private val state: InMemoryPaperLedgerState,
) : PaperLedgerAccountRepository {
    override suspend fun getAccountSnapshot(): Result<AccountSnapshot> {
        return Result.success(state.read { accountSnapshot })
    }

    override suspend fun getAccountSnapshotWithUpdatedAt(): Result<AccountSnapshotWithUpdatedAt> {
        return Result.success(
            state.read {
                AccountSnapshotWithUpdatedAt(
                    accountSnapshot = accountSnapshot,
                    updatedAt = accountUpdatedAt,
                )
            },
        )
    }

    override suspend fun getOpenPositions(): Result<List<Position>> {
        return Result.success(state.read { openPositionsLocked() })
    }

    override suspend fun getOpenPositionsWithUpdatedAt(): Result<PositionsWithUpdatedAt> {
        return Result.success(
            state.read {
                PositionsWithUpdatedAt(
                    positions = openPositionsLocked(),
                    updatedAt = accountUpdatedAt,
                )
            },
        )
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return Result.success(state.read { openOrdersLocked() })
    }

    override suspend fun getOpenOrdersWithUpdatedAt(): Result<OpenOrdersWithUpdatedAt> {
        return Result.success(
            state.read {
                OpenOrdersWithUpdatedAt(
                    openOrders = openOrdersLocked(),
                    updatedAt = accountUpdatedAt,
                )
            },
        )
    }

    override suspend fun getRealizedPnlForDate(date: LocalDate): Result<BigDecimal> {
        return Result.success(
            state.read {
                executions.sumOf { execution -> execution.realizedPnlJpy.toBigDecimal() }
            },
        )
    }
}

/**
 * InMemory paper ledger の execution 読み取り boundary。
 *
 * @param state 共有 mutable state
 */
private class InMemoryPaperLedgerExecutionReader(
    private val state: InMemoryPaperLedgerState,
) : PaperLedgerExecutionRepository {
    override suspend fun getExecutions(): Result<List<Execution>> {
        return Result.success(state.read { executions.toList() })
    }

    override suspend fun getRecentExecutions(limit: Int): Result<List<Execution>> {
        return findExecutionsBefore(
            before = Instant.MAX,
            limit = limit,
        )
    }

    override suspend fun findExecutionsBefore(before: Instant, limit: Int): Result<List<Execution>> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            state.read {
                executions
                    .filter { execution -> Instant.parse(execution.executedAt) < before }
                    .sortedByDescending { execution -> Instant.parse(execution.executedAt) }
                    .take(limit)
            }
        }
    }

    override suspend fun findExecutionsForStableFeed(cursor: StableFeedCursor, limit: Int): Result<List<Execution>> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            state.read {
                executions
                    .filter { execution -> cursor.accepts(Instant.parse(execution.executedAt), execution.executionId) }
                    .sortedWith(
                        compareByDescending<Execution> { execution -> Instant.parse(execution.executedAt) }
                            .thenBy { execution -> execution.executionId },
                    )
                    .take(limit)
            }
        }
    }

    override suspend fun findExecutionActivitiesForStableFeed(
        cursor: StableFeedCursor,
        limit: Int,
    ): Result<List<ExecutionActivityRecord>> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            state.read {
                executions
                    .filter { execution -> cursor.accepts(Instant.parse(execution.executedAt), execution.executionId) }
                    .sortedWith(
                        compareByDescending<Execution> { execution -> Instant.parse(execution.executedAt) }
                            .thenBy { execution -> execution.executionId },
                    )
                    .take(limit)
                    .map { execution -> toExecutionActivityRecord(execution) }
            }
        }
    }
}

/**
 * InMemory paper ledger の履歴読み取り boundary。
 *
 * @param state 共有 mutable state
 */
private class InMemoryPaperLedgerHistoryReader(
    private val state: InMemoryPaperLedgerState,
) : PaperLedgerHistoryRepository {
    override suspend fun findClosedPositionsClosedBetween(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): Result<List<ClosedPaperPosition>> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            state.read {
                positions
                    .filter { position -> position.status == PositionStatus.CLOSED }
                    .mapNotNull { position -> position.toClosedPositionWithParsedInstantOrNull() }
                    .filter { closedPosition ->
                        val closedAt = closedPosition.closedAt

                        closedAt >= from && closedAt < toExclusive
                    }
                    .sortedByDescending { closedPosition -> closedPosition.closedAt }
                    .take(limit)
                    .sortedBy { closedPosition -> closedPosition.closedAt }
                    .map { closedPosition ->
                        val position = closedPosition.position

                        ClosedPaperPosition(
                            position = position,
                            decisionRunId = decisionRunIdsByPositionId[position.positionId],
                            executions = executions
                                .filter { execution -> execution.positionId == position.positionId }
                                .sortedBy { execution -> Instant.parse(execution.executedAt) },
                        )
                    }
            }
        }
    }

    override suspend fun findPlaceOrderResultByClientRequestId(clientRequestId: String): Result<PaperTradeResult?> {
        return Result.success(
            state.read {
                findPlaceOrderResultByClientRequestIdLocked(clientRequestId)
            },
        )
    }
}

/**
 * InMemory paper ledger の mutation boundary。
 *
 * @param state 共有 mutable state
 */
private class InMemoryPaperLedgerMutationWriter(
    private val state: InMemoryPaperLedgerState,
) : PaperLedgerMutationRepository {
    override suspend fun fillMarketEntry(request: MarketEntryFillRequest): Result<PaperTradeResult> {
        return runCatching {
            state.write {
                fillEntryLocked(
                    EntryFillWriteRequest(
                        entry = request,
                        entryOrderId = request.command.commandId,
                        insertEntryOrder = true,
                    ),
                )
            }
        }
    }

    override suspend fun createRestingEntryOrder(request: RestingEntryOrderRequest): Result<PaperTradeResult> {
        return runCatching {
            state.write {
                val recordedAt = Instant.now(clock)
                val order = request.command.toEntryOrder(
                    orderId = request.orderId,
                    positionId = null,
                    tradeGroupId = request.tradeGroupId,
                    status = OrderStatus.OPEN,
                    recordedAt = recordedAt,
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
            state.write {
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
            state.write {
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
            state.write {
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

    override suspend fun reconcile(
        tickSnapshot: TickSnapshot,
        simulator: PaperExecutionSimulator,
        simulationContext: PaperSimulationContext?,
    ): Result<PaperReconcileResult> {
        return runCatching {
            state.write {
                val ticker = tickSnapshot.requireTicker()
                val rules = tickSnapshot.symbolRules ?: fallbackSymbolRules
                val lastPrice = tickSnapshot.lastPrice?.toBigDecimal() ?: ticker.last.toBigDecimal()
                val triggeredOrderIds = mutableListOf<String>()
                val closedPositionIds = mutableListOf<String>()
                val executionIds = mutableListOf<String>()
                val reconcileContext = ReconcileMarketContext(
                    ticker = ticker,
                    rules = rules,
                    simulator = simulator,
                    simulationContext = simulationContext ?: PaperSimulationContext(
                        ticker = ticker,
                        rules = rules,
                    ),
                    lastPrice = lastPrice,
                )
                val progress = ReconcileProgress(
                    triggeredOrderIds = triggeredOrderIds,
                    closedPositionIds = closedPositionIds,
                    executionIds = executionIds,
                )

                updateMarksLocked(
                    lastPrice = lastPrice,
                    atr14Jpy = tickSnapshot.atr14Jpy?.toBigDecimal(),
                    rules = rules,
                    updatedAt = tickSnapshot.observedAt,
                )

                if (!accountSnapshot.isHardHaltDrawdownReached()) {
                    fillTriggeredEntryOrdersLocked(reconcileContext, progress)
                    triggerPositionProtectionsLocked(reconcileContext, progress)
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

    private fun InMemoryPaperLedgerState.closePositionLocked(
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
        val closeOrder = closeOrder(orderId, position, reasonJa, fill.executedAt)
        val closedPosition = position.withWatermarkPrice(realizedFill.priceJpy).copy(
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
        accountUpdatedAt = realizedFill.executedAt
        appendFillEquitySnapshot(realizedFill.executedAt)

        return PaperTradeResult(
            accepted = true,
            status = OrderStatus.FILLED,
            orderIds = listOf(closeOrder.orderId),
            positionIds = listOf(position.positionId),
            executionIds = listOf(realizedFill.executionId.toString()),
            messageJa = "position を close しました。",
        )
    }

    private fun InMemoryPaperLedgerState.fillTriggeredEntryOrdersLocked(
        context: ReconcileMarketContext,
        progress: ReconcileProgress,
    ) {
        val entryOrders = orders
            .filter { order -> order.status == OrderStatus.OPEN && order.side == OrderSide.BUY }
            .filter { order -> order.isEntryTriggered(context.lastPrice) }

        entryOrders.forEach { order ->
            val fill = order.createEntryFill(
                ticker = context.ticker,
                rules = context.rules,
                simulator = context.simulator,
                simulationContext = context.simulationContext,
            )
            val positionId = UUID.randomUUID()
            val tradeGroupId = UUID.fromString(requireNotNull(order.tradeGroupId))
            val stopOrderId = UUID.randomUUID()
            val command = order.toPlaceOrderCommand()

            if (!accountSnapshot.hasCashForBuyFill(fill)) {
                markOrderStatusLocked(order.orderId, OrderStatus.REJECTED, "reconciler entry rejected: insufficient paper cash")
                progress.triggeredOrderIds += order.orderId

                return@forEach
            }

            markOrderStatusLocked(order.orderId, OrderStatus.FILLED)
            fillEntryLocked(
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
            )
            progress.triggeredOrderIds += order.orderId
            progress.executionIds += fill.executionId.toString()
        }
    }

    private fun InMemoryPaperLedgerState.fillEntryLocked(request: EntryFillWriteRequest): PaperTradeResult {
        val command = request.entry.command
        val fill = request.entry.fill
        val recordedAt = fill.executedAt
        val entryOrder = command.toEntryOrder(
            orderId = request.entryOrderId,
            positionId = request.entry.positionId,
            tradeGroupId = request.entry.tradeGroupId,
            status = OrderStatus.FILLED,
            recordedAt = recordedAt,
        )
        val stopOrder = command.toProtectiveStopOrder(
            orderId = request.entry.stopOrderId,
            positionId = request.entry.positionId,
            tradeGroupId = request.entry.tradeGroupId,
            recordedAt = recordedAt,
        )
        val position = command.toOpenPosition(request.entry.positionId, request.entry.tradeGroupId, fill)

        if (request.insertEntryOrder) {
            orders += entryOrder
        }
        orders += stopOrder
        positions += position
        decisionRunIdsByPositionId[position.positionId] = command.auditContext.decisionRunContext.decisionRunId
        executions += fill.toExecution(entryOrder.orderId, position.positionId, command)
        accountSnapshot = accountSnapshot.afterBuyFill(fill)
        accountUpdatedAt = fill.executedAt
        appendFillEquitySnapshot(fill.executedAt)

        return PaperTradeResult(
            accepted = true,
            status = OrderStatus.FILLED,
            orderIds = listOf(entryOrder.orderId, stopOrder.orderId),
            positionIds = listOf(position.positionId),
            executionIds = listOf(fill.executionId.toString()),
            messageJa = "paper entry を約定し、保護 STOP を作成しました。",
        )
    }

    private fun InMemoryPaperLedgerState.triggerPositionProtectionsLocked(
        context: ReconcileMarketContext,
        progress: ReconcileProgress,
    ) {
        val openPositions = positions.filter { position -> position.status == PositionStatus.OPEN }

        openPositions.forEach { position ->
            val stopPrice = position.currentStopLossJpy?.toBigDecimal()
            val takeProfitPrice = position.currentTakeProfitJpy?.toBigDecimal()
            val stopTriggered = stopPrice != null && context.lastPrice <= stopPrice
            val takeProfitTriggered = takeProfitPrice != null && context.lastPrice >= takeProfitPrice

            if (stopTriggered) {
                val stopOrder = linkedStopOrder(position.positionId)
                val fill = context.simulator.stopFill(
                    OrderSide.SELL,
                    position.sizeBtc.toBigDecimal(),
                    requireNotNull(stopPrice),
                    context.simulationContext,
                )
                val result = closePositionLocked(
                    positionId = position.positionId,
                    orderId = UUID.fromString(requireNotNull(stopOrder).orderId),
                    fill = fill,
                    reasonJa = "reconciler stop trigger",
                )

                markOrderStatusLocked(stopOrder.orderId, OrderStatus.FILLED)
                progress.triggeredOrderIds += stopOrder.orderId
                progress.closedPositionIds += position.positionId
                progress.executionIds += result.executionIds

                return@forEach
            }

            if (takeProfitTriggered) {
                linkedStopOrder(position.positionId)?.let { stopOrder ->
                    markOrderStatusLocked(stopOrder.orderId, OrderStatus.CANCELED)
                    progress.triggeredOrderIds += stopOrder.orderId
                }

                val fill = context.simulator.marketFill(
                    OrderSide.SELL,
                    position.sizeBtc.toBigDecimal(),
                    context.simulationContext,
                )
                val result = closePositionLocked(
                    positionId = position.positionId,
                    orderId = UUID.randomUUID(),
                    fill = fill,
                    reasonJa = "reconciler virtual take profit trigger",
                )

                progress.closedPositionIds += position.positionId
                progress.executionIds += result.executionIds
            }
        }
    }

    private fun InMemoryPaperLedgerState.updateMarksLocked(
        lastPrice: BigDecimal,
        atr14Jpy: BigDecimal?,
        rules: SymbolRules,
        updatedAt: Instant,
    ) {
        positions.replaceAll { position ->
            if (position.status != PositionStatus.OPEN) {
                return@replaceAll position
            }

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

            if (tightenedStop != currentStop && tightenedStop != null) {
                updateLinkedStopOrderLocked(position.positionId, tightenedStop, "reconciler atr trailing floor")
            }

            position.copy(
                currentPriceJpy = lastPrice.moneyScale().toPlainString(),
                currentStopLossJpy = tightenedStop?.moneyScale()?.toPlainString(),
                unrealizedPnlJpy = unrealizedPnl.toPlainString(),
                highestPriceSinceEntryJpy = highestPrice.moneyScale().toPlainString(),
                lowestPriceSinceEntryJpy = lowestPrice.moneyScale().toPlainString(),
            )
        }

        accountSnapshot = accountSnapshot.withMarkPrice(lastPrice)
        accountUpdatedAt = updatedAt
    }

    private fun InMemoryPaperLedgerState.updateLinkedStopOrderLocked(
        positionId: String,
        stopPrice: BigDecimal,
        reasonJa: String,
    ) {
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

    private fun InMemoryPaperLedgerState.cancelOpenStopOrdersLocked(positionId: String, reasonJa: String) {
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

    private fun InMemoryPaperLedgerState.markOrderStatusLocked(
        orderId: String,
        status: OrderStatus,
        reasonJa: String? = null,
    ) {
        val orderIndex = orders.indexOfFirst { order -> order.orderId == orderId }

        if (orderIndex >= 0) {
            orders[orderIndex] = orders[orderIndex].copy(
                status = status,
                reasonJa = reasonJa ?: orders[orderIndex].reasonJa,
            )
        }
    }

    private fun InMemoryPaperLedgerState.linkedStopOrder(positionId: String): Order? {
        return orders.firstOrNull { order ->
            order.positionId == positionId && order.side == OrderSide.SELL && order.orderType == OrderType.STOP && order.status == OrderStatus.OPEN
        }
    }

    private fun InMemoryPaperLedgerState.appendFillEquitySnapshot(capturedAt: Instant) {
        val snapshot = accountSnapshot.toFillEquitySnapshotRecord(
            id = UUID.randomUUID(),
            capturedAt = capturedAt,
        )

        equitySnapshotRepository.appendSnapshot(snapshot)
    }
}

private fun Position.toClosedPositionWithParsedInstantOrNull(): ClosedPositionWithParsedInstant? {
    val closedAtText = closedAt ?: return null

    return ClosedPositionWithParsedInstant(
        position = this,
        closedAt = Instant.parse(closedAtText),
    )
}

private fun InMemoryPaperLedgerState.findPlaceOrderResultByClientRequestIdLocked(
    clientRequestId: String,
): PaperTradeResult? {
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

private fun InMemoryPaperLedgerState.openPositionsLocked(): List<Position> {
    return positions.filter { position -> position.status == PositionStatus.OPEN }
}

private fun InMemoryPaperLedgerState.openOrdersLocked(): List<Order> {
    return orders.filter { order ->
        order.status == OrderStatus.OPEN || order.status == OrderStatus.PENDING_CANCEL
    }
}

private fun InMemoryPaperLedgerState.toExecutionActivityRecord(execution: Execution): ExecutionActivityRecord {
    val directOrder = execution.orderId?.let { orderId -> orders.firstOrNull { order -> order.orderId == orderId } }
    val position = findExecutionActivityPosition(execution, directOrder)
    val orderContext = directOrder?.toExecutionActivityOrderContext()
    val positionContext = execution.toExecutionActivityPositionContext(position, directOrder)
    val decisionRunId = position?.positionId?.let { positionId -> decisionRunIdsByPositionId[positionId] }
    val decisionContext = decisionRunId?.let { runId ->
        decisionContextsByRunId[runId] ?: ExecutionActivityDecisionContext(
            decisionId = null,
            decisionRunId = runId,
            action = null,
            reasonJa = null,
        )
    }

    return ExecutionActivityRecord(
        execution = execution,
        order = orderContext,
        position = positionContext,
        entryDecision = decisionContext,
    )
}

private fun InMemoryPaperLedgerState.findExecutionActivityPosition(
    execution: Execution,
    directOrder: Order?,
): Position? {
    val linkedPositionId = execution.positionId ?: directOrder?.positionId
    val linkedPosition = linkedPositionId?.let { positionId ->
        positions.firstOrNull { position -> position.positionId == positionId }
    }

    if (linkedPosition != null) {
        return linkedPosition
    }

    val tradeGroupId = directOrder?.tradeGroupId ?: return null

    return positions.firstOrNull { position -> position.tradeGroupId == tradeGroupId }
}

private fun Order.toExecutionActivityOrderContext(): ExecutionActivityOrderContext {
    return ExecutionActivityOrderContext(
        orderId = orderId,
        orderType = orderType,
        triggerPriceJpy = triggerPriceJpy,
        takeProfitPriceJpy = takeProfitPriceJpy,
        reasonJa = reasonJa,
    )
}

private fun Execution.toExecutionActivityPositionContext(
    position: Position?,
    directOrder: Order?,
): ExecutionActivityPositionContext? {
    val contextPositionId = position?.positionId ?: positionId ?: directOrder?.positionId
    val contextTradeGroupId = position?.tradeGroupId ?: directOrder?.tradeGroupId

    if (contextPositionId == null && contextTradeGroupId == null) {
        return null
    }

    return ExecutionActivityPositionContext(
        positionId = contextPositionId,
        tradeGroupId = contextTradeGroupId,
    )
}

private fun PlaceOrderCommand.toEntryOrder(
    orderId: UUID,
    positionId: UUID?,
    tradeGroupId: UUID,
    status: OrderStatus,
    recordedAt: Instant,
): Order {
    return Order(
        orderId = orderId.toString(),
        intentId = intentId?.toString(),
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
        estimatedWinProbability = estimatedWinProbability.ratioScale().toPlainString(),
        reasonJa = reasonJa,
        clientRequestId = auditContext.clientRequestId,
        createdAt = recordedAt.toString(),
        updatedAt = recordedAt.toString(),
    )
}

private fun PlaceOrderCommand.toProtectiveStopOrder(
    orderId: UUID,
    positionId: UUID,
    tradeGroupId: UUID,
    recordedAt: Instant,
): Order {
    return Order(
        orderId = orderId.toString(),
        intentId = null,
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
        estimatedWinProbability = null,
        reasonJa = "protective stop: $reasonJa",
        clientRequestId = auditContext.clientRequestId,
        createdAt = recordedAt.toString(),
        updatedAt = recordedAt.toString(),
    )
}

private fun PlaceOrderCommand.toOpenPosition(
    positionId: UUID,
    tradeGroupId: UUID,
    fill: SimulatedFill,
): Position {
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
        lowestPriceSinceEntryJpy = fill.priceJpy.moneyScale().toPlainString(),
    )
}

private fun Order.toPlaceOrderCommand(): PlaceOrderCommand {
    val price = limitPriceJpy?.toBigDecimal() ?: triggerPriceJpy?.toBigDecimal()

    return PlaceOrderCommand(
        commandId = UUID.fromString(orderId),
        intentId = intentId?.let { value -> UUID.fromString(value) },
        symbol = TradingSymbol.BTC,
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

private fun SimulatedFill.toExecution(
    orderId: String,
    positionId: String,
    command: PlaceOrderCommand,
): Execution {
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

private fun closeOrder(
    orderId: UUID,
    position: Position,
    reasonJa: String,
    recordedAt: Instant,
): Order {
    return Order(
        orderId = orderId.toString(),
        intentId = null,
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
        createdAt = recordedAt.toString(),
        updatedAt = recordedAt.toString(),
    )
}

private fun SimulatedFill.withRealizedPnl(position: Position): SimulatedFill {
    val entryPrice = position.averageEntryPriceJpy.toBigDecimal()
    val grossPnl = priceJpy.subtract(entryPrice).multiply(sizeBtc)
    val realizedPnl = grossPnl.subtract(feeJpy).moneyScale()

    return copy(realizedPnlJpy = realizedPnl)
}

private fun Position.withWatermarkPrice(priceJpy: BigDecimal): Position {
    val highestPrice = maxOf(highestPriceSinceEntryJpy.toBigDecimal(), priceJpy)
    val currentLowestPrice = lowestPriceSinceEntryJpy?.toBigDecimal() ?: priceJpy
    val lowestPrice = minOf(currentLowestPrice, priceJpy)

    return copy(
        highestPriceSinceEntryJpy = highestPrice.moneyScale().toPlainString(),
        lowestPriceSinceEntryJpy = lowestPrice.moneyScale().toPlainString(),
    )
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

private fun AccountSnapshot.isHardHaltDrawdownReached(): Boolean {
    return drawdownRatio.toBigDecimal() <= SafetyFloorDefaults.maxDrawdownRatio
}

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
    simulator: PaperExecutionSimulator,
    simulationContext: PaperSimulationContext,
): SimulatedFill {
    return simulator.restingEntryFill(
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

/**
 * closed position と parsed closed_at の組。
 *
 * @param position closed position 本体
 * @param closedAt parsed close instant
 */
private data class ClosedPositionWithParsedInstant(
    val position: Position,
    val closedAt: Instant,
)

/**
 * resting order 復元時の既定推定勝率。
 */
private val DEFAULT_RESTORED_ESTIMATED_WIN_PROBABILITY = BigDecimal("0.60")
