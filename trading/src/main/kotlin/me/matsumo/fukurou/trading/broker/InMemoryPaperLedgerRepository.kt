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
    orderRepository: PaperLedgerOrderRepository,
    historyRepository: PaperLedgerHistoryRepository,
    mutationRepository: PaperLedgerMutationRepository,
) : PaperLedgerRepository,
    PaperLedgerAccountRepository by accountRepository,
    PaperLedgerExecutionRepository by executionRepository,
    PaperLedgerOrderRepository by orderRepository,
    PaperLedgerHistoryRepository by historyRepository,
    PaperLedgerMutationRepository by mutationRepository {

    internal val equitySnapshotRepository: InMemoryEquitySnapshotRepository
        get() = state.equitySnapshotRepository

    private constructor(state: InMemoryPaperLedgerState) : this(
        state = state,
        accountRepository = InMemoryPaperLedgerAccountReader(state),
        executionRepository = InMemoryPaperLedgerExecutionReader(state),
        orderRepository = InMemoryPaperLedgerOrderReader(state),
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
 * InMemory close_position 後の position 更新結果。
 *
 * @param position 更新後の position
 * @param remainingSize close 後に残る BTC 数量
 */
private data class InMemoryClosePositionUpdate(
    val position: Position,
    val remainingSize: BigDecimal,
) {

    val isPartialClose: Boolean = remainingSize > BigDecimal.ZERO

    val messageJa: String = if (isPartialClose) {
        "position を部分 close しました。"
    } else {
        "position を close しました。"
    }
}

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
 * InMemory paper ledger の order 履歴読み取り boundary。
 *
 * @param state 共有 mutable state
 */
private class InMemoryPaperLedgerOrderReader(
    private val state: InMemoryPaperLedgerState,
) : PaperLedgerOrderRepository {
    override suspend fun findOrdersByTradeGroupId(tradeGroupId: UUID): Result<List<Order>> {
        return Result.success(
            state.read {
                orders.filter { order -> order.tradeGroupId == tradeGroupId.toString() }
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
                val sortedExecutions = executions
                    .filter { execution -> Instant.parse(execution.executedAt) < before }
                    .sortedByDescending { execution -> Instant.parse(execution.executedAt) }

                sortedExecutions.take(limit)
            }
        }
    }

    override suspend fun findExecutionsForStableFeed(cursor: StableFeedCursor, limit: Int): Result<List<Execution>> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            state.read {
                val sortedExecutions = executions
                    .filter { execution -> cursor.accepts(Instant.parse(execution.executedAt), execution.executionId) }
                    .sortedWith(
                        compareByDescending<Execution> { execution -> Instant.parse(execution.executedAt) }
                            .thenBy { execution -> execution.executionId },
                    )

                sortedExecutions.take(limit)
            }
        }
    }

    override suspend fun findSellExecutionsByPositionIds(positionIds: List<String>): Result<List<Execution>> {
        if (positionIds.isEmpty()) return Result.success(emptyList())

        val positionIdSet = positionIds.toSet()

        return Result.success(
            state.read {
                executions
                    .filter { execution -> execution.side == OrderSide.SELL && execution.positionId in positionIdSet }
                    .sortedByDescending { execution -> Instant.parse(execution.executedAt) }
            },
        )
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
                    .asSequence()
                    .filter { position -> position.status == PositionStatus.CLOSED }
                    .mapNotNull { position -> position.toClosedPositionWithParsedInstantOrNull() }
                    .filter { closedPosition ->
                        val closedAt = closedPosition.closedAt

                        closedAt in from..<toExclusive
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
                    .toList()
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
                val reconcileContext = tickSnapshot.toReconcileMarketContext(
                    fallbackSymbolRules = fallbackSymbolRules,
                    simulator = simulator,
                    simulationContext = simulationContext,
                )
                val progress = emptyReconcileProgress()

                updateMarksLocked(
                    lastPrice = reconcileContext.lastPrice,
                    atr14Jpy = tickSnapshot.atr14Jpy?.toBigDecimal(),
                    rules = reconcileContext.rules,
                    updatedAt = tickSnapshot.observedAt,
                )

                if (!accountSnapshot.isHardHaltDrawdownReached()) {
                    fillTriggeredEntryOrdersLocked(reconcileContext, progress)
                    triggerPositionProtectionsLocked(reconcileContext, progress)
                }

                progress.toPaperReconcileResult()
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
        require(fill.sizeBtc <= position.sizeBtc.toBigDecimal()) {
            "close size exceeds open position size."
        }

        val realizedFill = fill.withRealizedPnl(position)
        val closeOrder = closeOrder(
            orderId = orderId,
            position = position,
            sizeBtc = realizedFill.sizeBtc,
            reasonJa = reasonJa,
            recordedAt = fill.executedAt,
        )
        val remainingSize = position.sizeBtc.toBigDecimal()
            .subtract(realizedFill.sizeBtc)
            .btcScale()
        val positionWithWatermark = position.withWatermarkPrice(realizedFill.priceJpy)
        val closeUpdate = positionWithWatermark.toClosePositionUpdate(realizedFill, remainingSize)

        orders += closeOrder
        positions[positionIndex] = closeUpdate.position
        executions += realizedFill.toExecution(
            orderId = closeOrder.orderId,
            positionId = position.positionId,
            mode = position.mode,
            side = OrderSide.SELL,
        )
        if (closeUpdate.isPartialClose) {
            updateLinkedStopOrderSizeLocked(position.positionId, closeUpdate.remainingSize, reasonJa)
        } else {
            cancelOpenStopOrdersLocked(position.positionId, reasonJa)
        }
        accountSnapshot = accountSnapshot.afterSellFill(realizedFill)
        accountUpdatedAt = realizedFill.executedAt
        appendFillEquitySnapshot(realizedFill.executedAt)

        return PaperTradeResult(
            accepted = true,
            status = OrderStatus.FILLED,
            orderIds = listOf(closeOrder.orderId),
            positionIds = listOf(position.positionId),
            executionIds = listOf(realizedFill.executionId.toString()),
            messageJa = closeUpdate.messageJa,
        )
    }

    private fun InMemoryPaperLedgerState.fillTriggeredEntryOrdersLocked(
        context: ReconcileMarketContext,
        progress: ReconcileProgress,
    ) {
        val entryOrders = orders
            .filter { order -> order.status == OrderStatus.OPEN && order.side == OrderSide.BUY }
            .filter { order -> order.isEntryTriggered(context) }

        entryOrders.forEach { order ->
            val orderUpdate = order.createEntryUpdate(
                ticker = context.ticker,
                rules = context.rules,
                simulator = context.simulator,
                simulationContext = context.simulationContext,
            )
            val fill = requireNotNull(orderUpdate.fill) {
                "Triggered entry order must create a fill."
            }
            val positionId = UUID.randomUUID()
            val orderTradeGroupId = order.tradeGroupId
                ?: error("Triggered entry order must have trade group ID.")
            val tradeGroupId = UUID.fromString(orderTradeGroupId)
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
            orderUpdate.divergenceMemo
                ?.withOrderContext(order)
                ?.let { memo -> progress.divergenceMemos += memo }
        }
    }

    private fun InMemoryPaperLedgerState.fillEntryLocked(request: EntryFillWriteRequest): PaperTradeResult {
        val command = request.entry.command
        val fill = request.entry.fill
        val recordedAt = fill.executedAt
        val existingPositionIndex = positions.indexOfFirst { position ->
            position.status == PositionStatus.OPEN && position.tradeGroupId == request.entry.tradeGroupId.toString()
        }
        val existingPosition = positions.getOrNull(existingPositionIndex)
        val targetPositionId = existingPosition
            ?.positionId
            ?.let { value -> UUID.fromString(value) }
            ?: request.entry.positionId
        val entryOrder = command.toEntryOrder(
            orderId = request.entryOrderId,
            positionId = targetPositionId,
            tradeGroupId = request.entry.tradeGroupId,
            status = OrderStatus.FILLED,
            recordedAt = recordedAt,
        )
        val divergenceMemos = request.entry.divergenceMemo
            ?.withOrderContext(entryOrder)
            ?.let { memo -> listOf(memo) }
            .orEmpty()

        if (request.insertEntryOrder) {
            orders += entryOrder
        } else {
            updateRestingEntryOrderFillLocked(entryOrder.orderId, targetPositionId.toString(), command.reasonJa)
        }
        val updatedStopOrderId = upsertPositionForEntryFillLocked(
            request = request,
            existingPosition = existingPosition,
            existingPositionIndex = existingPositionIndex,
        )
        executions += fill.toExecution(entryOrder.orderId, targetPositionId.toString(), command)
        accountSnapshot = accountSnapshot.afterBuyFill(fill)
        accountUpdatedAt = fill.executedAt
        appendFillEquitySnapshot(fill.executedAt)

        return PaperTradeResult(
            accepted = true,
            status = OrderStatus.FILLED,
            orderIds = listOf(entryOrder.orderId, updatedStopOrderId),
            positionIds = listOf(targetPositionId.toString()),
            executionIds = listOf(fill.executionId.toString()),
            messageJa = if (existingPosition == null) {
                "paper entry を約定し、保護 STOP を作成しました。"
            } else {
                "paper entry を既存 position に合算しました。"
            },
            divergenceMemos = divergenceMemos,
        )
    }

    private fun InMemoryPaperLedgerState.upsertPositionForEntryFillLocked(
        request: EntryFillWriteRequest,
        existingPosition: Position?,
        existingPositionIndex: Int,
    ): String {
        val command = request.entry.command
        val fill = request.entry.fill

        if (existingPosition == null) {
            val stopOrder = command.toProtectiveStopOrder(
                orderId = request.entry.stopOrderId,
                positionId = request.entry.positionId,
                tradeGroupId = request.entry.tradeGroupId,
                recordedAt = fill.executedAt,
            )
            val position = command.toOpenPosition(request.entry.positionId, request.entry.tradeGroupId, fill)

            orders += stopOrder
            positions += position
            decisionRunIdsByPositionId[position.positionId] = command.auditContext.decisionRunContext.decisionRunId

            return stopOrder.orderId
        }

        val mergedPosition = existingPosition.mergeEntryFill(command, fill)

        positions[existingPositionIndex] = mergedPosition

        return updateLinkedStopOrderForMergedEntryLocked(
            position = mergedPosition,
            stopPrice = mergedPosition.currentStopLossJpy?.toBigDecimal(),
            reasonJa = command.reasonJa,
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
                    stopPrice,
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

            val markUpdate = position.toPositionMarkUpdate(
                lastPrice = lastPrice,
                atr14Jpy = atr14Jpy,
                rules = rules,
            )

            if (position.hasTightenedStop(markUpdate)) {
                updateLinkedStopOrderLocked(
                    position.positionId,
                    checkNotNull(markUpdate.tightenedStop),
                    "reconciler atr trailing floor",
                )
            }

            position.copy(
                currentPriceJpy = markUpdate.lastPrice.moneyScale().toPlainString(),
                currentStopLossJpy = markUpdate.tightenedStop?.moneyScale()?.toPlainString(),
                unrealizedPnlJpy = markUpdate.unrealizedPnl.toPlainString(),
                unrealizedR = markUpdate.unrealizedR.toPlainString(),
                highestPriceSinceEntryJpy = markUpdate.highestPrice.moneyScale().toPlainString(),
                lowestPriceSinceEntryJpy = markUpdate.lowestPrice.moneyScale().toPlainString(),
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

        if (stopOrderIndex < 0) return

        orders[stopOrderIndex] = orders[stopOrderIndex].copy(
            triggerPriceJpy = stopPrice.moneyScale().toPlainString(),
            reasonJa = reasonJa,
        )
    }

    private fun InMemoryPaperLedgerState.updateLinkedStopOrderSizeLocked(
        positionId: String,
        sizeBtc: BigDecimal,
        reasonJa: String,
    ) {
        val stopOrderIndex = orders.indexOfFirst { order ->
            order.positionId == positionId && order.side == OrderSide.SELL && order.orderType == OrderType.STOP && order.status == OrderStatus.OPEN
        }

        if (stopOrderIndex < 0) return

        orders[stopOrderIndex] = orders[stopOrderIndex].copy(
            sizeBtc = sizeBtc.btcScale().toPlainString(),
            reasonJa = reasonJa,
        )
    }

    private fun InMemoryPaperLedgerState.updateLinkedStopOrderForMergedEntryLocked(
        position: Position,
        stopPrice: BigDecimal?,
        reasonJa: String,
    ): String {
        val stopOrderIndex = orders.indexOfFirst { order ->
            order.positionId == position.positionId &&
                order.side == OrderSide.SELL &&
                order.orderType == OrderType.STOP &&
                order.status == OrderStatus.OPEN
        }

        require(stopOrderIndex >= 0) {
            "linked protective STOP order was not found."
        }

        orders[stopOrderIndex] = orders[stopOrderIndex].copy(
            sizeBtc = position.sizeBtc,
            triggerPriceJpy = stopPrice?.moneyScale()?.toPlainString() ?: orders[stopOrderIndex].triggerPriceJpy,
            reasonJa = "merged entry protective stop: $reasonJa",
        )

        return orders[stopOrderIndex].orderId
    }

    private fun InMemoryPaperLedgerState.updateRestingEntryOrderFillLocked(
        orderId: String,
        positionId: String,
        reasonJa: String,
    ) {
        val orderIndex = orders.indexOfFirst { order -> order.orderId == orderId }

        require(orderIndex >= 0) {
            "resting entry order was not found."
        }

        orders[orderIndex] = orders[orderIndex].copy(
            positionId = positionId,
            status = OrderStatus.FILLED,
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

private fun Position.toClosePositionUpdate(
    fill: SimulatedFill,
    remainingSize: BigDecimal,
): InMemoryClosePositionUpdate {
    val updatedPosition = if (remainingSize > BigDecimal.ZERO) {
        copy(
            sizeBtc = remainingSize.toPlainString(),
            currentPriceJpy = fill.priceJpy.toPlainString(),
            unrealizedPnlJpy = unrealizedPnlAt(fill.priceJpy, remainingSize),
            unrealizedR = unrealizedRAt(fill.priceJpy),
        )
    } else {
        copy(
            status = PositionStatus.CLOSED,
            closedAt = fill.executedAt.toString(),
            sizeBtc = BigDecimal.ZERO.btcScale().toPlainString(),
            currentPriceJpy = fill.priceJpy.toPlainString(),
            currentStopLossJpy = null,
            currentTakeProfitJpy = null,
            unrealizedPnlJpy = BigDecimal.ZERO.moneyScale().toPlainString(),
            unrealizedR = BigDecimal.ZERO.ratioScale().toPlainString(),
        )
    }

    return InMemoryClosePositionUpdate(
        position = updatedPosition,
        remainingSize = remainingSize,
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
    sizeBtc: BigDecimal,
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
        sizeBtc = sizeBtc.btcScale().toPlainString(),
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
