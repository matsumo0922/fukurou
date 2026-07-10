package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderExpirySource
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.knowledge.ClosedPaperPosition
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.reconciler.requireTicker
import me.matsumo.fukurou.trading.safety.SafetyFloorDefaults
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * paper ledger の account / position / order 読み取り repository。
 */
interface PaperLedgerAccountRepository {
    /**
     * paper account の残高 snapshot を返す。
     */
    suspend fun getAccountSnapshot(): Result<AccountSnapshot>

    /**
     * paper account の残高 snapshot と更新時刻を同一読み取り由来で返す。
     */
    suspend fun getAccountSnapshotWithUpdatedAt(): Result<AccountSnapshotWithUpdatedAt>

    /**
     * open position 一覧を返す。
     */
    suspend fun getOpenPositions(): Result<List<Position>>

    /**
     * open position 一覧と paper account 更新時刻を同一読み取り由来で返す。
     */
    suspend fun getOpenPositionsWithUpdatedAt(): Result<PositionsWithUpdatedAt>

    /**
     * open order 一覧を返す。
     */
    suspend fun getOpenOrders(): Result<List<Order>>

    /**
     * open order 一覧と paper account 更新時刻を同一読み取り由来で返す。
     */
    suspend fun getOpenOrdersWithUpdatedAt(): Result<OpenOrdersWithUpdatedAt>

    /**
     * 指定日の実現損益合計を返す。
     */
    suspend fun getRealizedPnlForDate(date: LocalDate): Result<BigDecimal>
}

/**
 * paper ledger の execution 読み取り repository。
 */
interface PaperLedgerExecutionRepository {
    /**
     * execution ledger の読み取りを返す。
     */
    suspend fun getExecutions(): Result<List<Execution>>

    /**
     * execution ledger の最新行を指定上限で返す。
     */
    suspend fun getRecentExecutions(limit: Int): Result<List<Execution>>

    /**
     * 指定時刻より古い execution ledger の行を新しい順で返す。
     */
    suspend fun findExecutionsBefore(before: Instant, limit: Int): Result<List<Execution>>

    /**
     * 安定 cursor 条件に一致する execution ledger の行を Activity timeline 用に新しい順で取得する。
     */
    suspend fun findExecutionsForStableFeed(cursor: StableFeedCursor, limit: Int): Result<List<Execution>>

    /**
     * 指定 position に紐づく SELL execution を新しい順で返す。
     */
    suspend fun findSellExecutionsByPositionIds(positionIds: List<String>): Result<List<Execution>>

    /**
     * 安定 cursor 条件に一致する execution Activity 行を、関連 context と一緒に新しい順で取得する。
     */
    suspend fun findExecutionActivitiesForStableFeed(
        cursor: StableFeedCursor,
        limit: Int,
    ): Result<List<ExecutionActivityRecord>>
}

/**
 * paper ledger の order 履歴読み取り repository。
 */
interface PaperLedgerOrderRepository {
    /**
     * 指定 trade group に紐づく order 履歴を作成順で返す。
     */
    suspend fun findOrdersByTradeGroupId(tradeGroupId: UUID): Result<List<Order>>
}

/**
 * Activity timeline の execution 表示に必要な関連 context。
 *
 * @param execution 約定本体
 * @param order 直接紐づく order context
 * @param position 約定または order から解決した position context
 * @param entryDecision 同じ position / trade group の entry decision context
 */
data class ExecutionActivityRecord(
    val execution: Execution,
    val order: ExecutionActivityOrderContext?,
    val position: ExecutionActivityPositionContext?,
    val entryDecision: ExecutionActivityDecisionContext?,
)

/**
 * Activity timeline の execution 詳細に表示する order context。
 *
 * @param orderId 注文 ID
 * @param orderType 注文種別
 * @param triggerPriceJpy STOP trigger 価格
 * @param takeProfitPriceJpy take-profit 価格
 * @param reasonJa order に保存された理由
 */
data class ExecutionActivityOrderContext(
    val orderId: String,
    val orderType: OrderType,
    val triggerPriceJpy: String?,
    val takeProfitPriceJpy: String?,
    val reasonJa: String?,
)

/**
 * Activity timeline の execution 詳細に表示する position context。
 *
 * @param positionId position ID
 * @param tradeGroupId trade group ID
 */
data class ExecutionActivityPositionContext(
    val positionId: String?,
    val tradeGroupId: String?,
)

/**
 * Activity timeline の execution 詳細に表示する entry decision context。
 *
 * @param decisionId decision ID
 * @param decisionRunId daemon / CLI 起動単位の ID
 * @param action LLM が提出した action
 * @param reasonJa 判断理由
 */
data class ExecutionActivityDecisionContext(
    val decisionId: String?,
    val decisionRunId: String?,
    val action: DecisionAction?,
    val reasonJa: String?,
)

/**
 * paper ledger の closed position / 冪等 result 読み取り repository。
 */
interface PaperLedgerHistoryRepository {
    /**
     * 指定範囲に close された position と関連 executions を返す。
     */
    suspend fun findClosedPositionsClosedBetween(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): Result<List<ClosedPaperPosition>>

    /**
     * client_request_id に対応する既存 place_order 結果を返す。
     */
    suspend fun findPlaceOrderResultByClientRequestId(clientRequestId: String): Result<PaperTradeResult?>
}

/**
 * paper ledger の mutation repository。
 */
interface PaperLedgerMutationRepository {
    /**
     * MARKET entry を約定済みとして保存し、保護 STOP を作成する。
     */
    suspend fun fillMarketEntry(request: MarketEntryFillRequest): Result<PaperTradeResult>

    /**
     * resting entry intent を未約定 order として保存する。
     */
    suspend fun createRestingEntryOrder(request: RestingEntryOrderRequest): Result<PaperTradeResult>

    /**
     * position を成行相当で close する。
     */
    suspend fun closePosition(
        command: ClosePositionCommand,
        positionId: UUID,
        orderId: UUID,
        fill: SimulatedFill,
    ): Result<PaperTradeResult>

    /**
     * position の STOP / virtual TP を更新する。
     */
    suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult>

    /**
     * open order を cancel する。
     */
    suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult>

    /**
     * tick をもとに resting order / protection を決定的に前進させる。
     */
    suspend fun reconcile(
        tickSnapshot: TickSnapshot,
        simulator: PaperExecutionSimulator,
        simulationContext: PaperSimulationContext? = null,
    ): Result<PaperReconcileResult>
}

/**
 * paper ledger の読み書き repository。
 */
interface PaperLedgerRepository :
    PaperLedgerAccountRepository,
    PaperLedgerExecutionRepository,
    PaperLedgerOrderRepository,
    PaperLedgerHistoryRepository,
    PaperLedgerMutationRepository

/**
 * entry order と intent consumption を同一 commit 境界で保存できる paper ledger repository。
 */
interface IntentConsumingPaperLedgerRepository : PaperLedgerRepository {
    /**
     * MARKET entry と intent consumption を同一 commit 境界で保存する。
     */
    suspend fun fillMarketEntryAndConsumeIntent(
        request: IntentConsumingMarketEntryFillRequest,
    ): Result<PaperTradeResult>

    /**
     * resting entry order と intent consumption を同一 commit 境界で保存する。
     */
    suspend fun createRestingEntryOrderAndConsumeIntent(
        request: IntentConsumingRestingEntryOrderRequest,
    ): Result<PaperTradeResult>
}

/**
 * MARKET entry の約定保存入力。
 *
 * @param command place_order command
 * @param fill paper 約定
 * @param positionId 作成する position ID
 * @param tradeGroupId entry / stop / position を束ねる trade group ID
 * @param stopOrderId 作成する protective STOP order ID
 * @param divergenceMemo paper/live 乖離を audit に渡す structured memo
 */
data class MarketEntryFillRequest(
    val command: PlaceOrderCommand,
    val fill: SimulatedFill,
    val positionId: UUID,
    val tradeGroupId: UUID,
    val stopOrderId: UUID,
    val divergenceMemo: PaperExecutionDivergenceMemo? = null,
)

/**
 * resting entry order の保存入力。
 *
 * @param command place_order command
 * @param orderId 作成する order ID
 * @param tradeGroupId entry order を束ねる trade group ID
 * @param createdAt order 作成時刻
 * @param expiresAt 作成時に固定した実効期限
 * @param expirySource 実効期限を決めた入力
 * @param effectiveTtlSeconds 作成時刻から実効期限までの秒数
 */
data class RestingEntryOrderRequest(
    val command: PlaceOrderCommand,
    val orderId: UUID,
    val tradeGroupId: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val expirySource: OrderExpirySource,
    val effectiveTtlSeconds: Long,
)

/**
 * intent consumption の保存入力。
 *
 * @param intentId 消費する trade intent ID
 * @param consumedAt 消費時刻
 */
data class TradeIntentConsumptionRequest(
    val intentId: UUID,
    val consumedAt: Instant,
)

/**
 * MARKET entry と intent consumption の同時保存入力。
 *
 * @param entry MARKET entry の保存入力
 * @param consumption intent consumption の保存入力
 */
data class IntentConsumingMarketEntryFillRequest(
    val entry: MarketEntryFillRequest,
    val consumption: TradeIntentConsumptionRequest,
)

/**
 * resting entry order と intent consumption の同時保存入力。
 *
 * @param order resting entry order の保存入力
 * @param consumption intent consumption の保存入力
 */
data class IntentConsumingRestingEntryOrderRequest(
    val order: RestingEntryOrderRequest,
    val consumption: TradeIntentConsumptionRequest,
)

/**
 * entry fill を ledger に反映する内部入力。
 *
 * @param entry MARKET entry の保存入力
 * @param entryOrderId 約定済み entry order ID
 * @param insertEntryOrder entry order 行も作成するなら true
 */
data class EntryFillWriteRequest(
    val entry: MarketEntryFillRequest,
    val entryOrderId: UUID,
    val insertEntryOrder: Boolean,
)

/**
 * reconcile 中に共有する市場入力。
 *
 * @param ticker 最新 ticker
 * @param rules symbol rule
 * @param simulator paper execution simulator
 * @param simulationContext paper execution simulator に渡す市場 context
 * @param lastPrice mark / trigger 判定価格
 */
data class ReconcileMarketContext(
    val ticker: Ticker,
    val rules: SymbolRules,
    val simulator: PaperExecutionSimulator,
    val simulationContext: PaperSimulationContext,
    val lastPrice: BigDecimal,
)

/**
 * reconcile 中に蓄積する更新結果。
 *
 * @param filledOrderIds 約定した order ID
 * @param canceledOrderIds 取消した order ID
 * @param rejectedOrderIds 拒否した order ID
 * @param closedPositionIds close した position ID
 * @param executionIds 作成した execution ID
 * @param divergenceMemos paper/live 乖離を audit に渡す structured memo
 */
data class ReconcileProgress(
    val filledOrderIds: MutableList<String>,
    val canceledOrderIds: MutableList<String>,
    val rejectedOrderIds: MutableList<String>,
    val closedPositionIds: MutableList<String>,
    val executionIds: MutableList<String>,
    val divergenceMemos: MutableList<PaperExecutionDivergenceMemo> = mutableListOf(),
)

/**
 * position mark 更新入力。
 *
 * @param positionId 更新する position ID
 * @param lastPrice mark 価格
 * @param highestPrice entry 後最高価格
 * @param lowestPrice entry 後最安価格
 * @param unrealizedPnl 未実現損益
 * @param unrealizedR 未実現 R
 * @param tightenedStop tighten 後の stop 価格
 */
data class PositionMarkUpdate(
    val positionId: String,
    val lastPrice: BigDecimal,
    val highestPrice: BigDecimal,
    val lowestPrice: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedR: BigDecimal,
    val tightenedStop: BigDecimal?,
)

internal fun TickSnapshot.toReconcileMarketContext(
    fallbackSymbolRules: SymbolRules,
    simulator: PaperExecutionSimulator,
    simulationContext: PaperSimulationContext?,
): ReconcileMarketContext {
    val ticker = requireTicker()
    val rules = symbolRules ?: fallbackSymbolRules
    val lastPrice = (lastPrice ?: ticker.last).toBigDecimal()

    return ReconcileMarketContext(
        ticker = ticker,
        rules = rules,
        simulator = simulator,
        simulationContext = simulationContext ?: PaperSimulationContext(
            ticker = ticker,
            rules = rules,
        ),
        lastPrice = lastPrice,
    )
}

internal fun emptyReconcileProgress(): ReconcileProgress {
    return ReconcileProgress(
        filledOrderIds = mutableListOf(),
        canceledOrderIds = mutableListOf(),
        rejectedOrderIds = mutableListOf(),
        closedPositionIds = mutableListOf(),
        executionIds = mutableListOf(),
    )
}

internal fun ReconcileProgress.toPaperReconcileResult(): PaperReconcileResult {
    return PaperReconcileResult(
        advanced = filledOrderIds.isNotEmpty() ||
            canceledOrderIds.isNotEmpty() ||
            rejectedOrderIds.isNotEmpty() ||
            closedPositionIds.isNotEmpty(),
        filledOrderIds = filledOrderIds,
        canceledOrderIds = canceledOrderIds,
        rejectedOrderIds = rejectedOrderIds,
        closedPositionIds = closedPositionIds,
        executionIds = executionIds,
        divergenceMemos = divergenceMemos.toList(),
    )
}

internal fun Position.toPositionMarkUpdate(
    lastPrice: BigDecimal,
    atr14Jpy: BigDecimal?,
    rules: SymbolRules,
): PositionMarkUpdate {
    val entryPrice = averageEntryPriceJpy.toBigDecimal()
    val sizeBtc = sizeBtc.toBigDecimal()
    val highestPrice = maxOf(highestPriceSinceEntryJpy.toBigDecimal(), lastPrice)
    val currentLowestPrice = lowestPriceSinceEntryJpy?.toBigDecimal() ?: lastPrice
    val lowestPrice = minOf(currentLowestPrice, lastPrice)
    val trailingStop = atr14Jpy?.let { atrValue ->
        highestPrice
            .subtract(atrValue.multiply(SafetyFloorDefaults.trailingAtrMultiplier))
            .floorToStep(rules.tickSize.toBigDecimal())
    }
    val currentStop = currentStopLossJpy?.toBigDecimal()
    val tightenedStop = listOfNotNull(currentStop, trailingStop).maxOrNull()
    val unrealizedPnl = lastPrice.subtract(entryPrice).multiply(sizeBtc).moneyScale()
    val unrealizedR = unrealizedRAt(lastPrice).toBigDecimal()

    return PositionMarkUpdate(
        positionId = positionId,
        lastPrice = lastPrice,
        highestPrice = highestPrice,
        lowestPrice = lowestPrice,
        unrealizedPnl = unrealizedPnl,
        unrealizedR = unrealizedR,
        tightenedStop = tightenedStop,
    )
}

internal fun Position.hasTightenedStop(update: PositionMarkUpdate): Boolean {
    val currentStop = currentStopLossJpy?.toBigDecimal()

    return update.tightenedStop != null && update.tightenedStop != currentStop
}

internal fun Position.mergeEntryFill(command: PlaceOrderCommand, fill: SimulatedFill): Position {
    val currentSize = sizeBtc.toBigDecimal()
    val addedSize = fill.sizeBtc
    val mergedSize = currentSize.add(addedSize).btcScale()
    val mergedEntryPrice = averageEntryPriceJpy.toBigDecimal()
        .multiply(currentSize)
        .add(fill.priceJpy.multiply(addedSize))
        .divide(mergedSize, MONEY_SCALE, RoundingMode.HALF_UP)
        .moneyScale()
    val currentStop = currentStopLossJpy?.toBigDecimal()
    val requestedStop = command.protectiveStopPriceJpy
    val mergedStop = listOfNotNull(currentStop, requestedStop).maxOrNull()
    val mergedTakeProfit = command.takeProfitPriceJpy
        ?.moneyScale()
        ?.toPlainString()
        ?: currentTakeProfitJpy
    val highestPrice = maxOf(highestPriceSinceEntryJpy.toBigDecimal(), fill.priceJpy)
    val currentLowestPrice = lowestPriceSinceEntryJpy?.toBigDecimal() ?: fill.priceJpy
    val lowestPrice = minOf(currentLowestPrice, fill.priceJpy)
    val markedPosition = copy(
        sizeBtc = mergedSize.toPlainString(),
        averageEntryPriceJpy = mergedEntryPrice.toPlainString(),
        currentPriceJpy = fill.priceJpy.moneyScale().toPlainString(),
        currentStopLossJpy = mergedStop?.moneyScale()?.toPlainString(),
        currentTakeProfitJpy = mergedTakeProfit,
        pyramidAddCount = pyramidAddCount + 1,
        highestPriceSinceEntryJpy = highestPrice.moneyScale().toPlainString(),
        lowestPriceSinceEntryJpy = lowestPrice.moneyScale().toPlainString(),
    )

    return markedPosition.copy(
        unrealizedPnlJpy = markedPosition.unrealizedPnlAt(fill.priceJpy, mergedSize),
        unrealizedR = markedPosition.unrealizedRAt(fill.priceJpy),
    )
}

internal fun Position.unrealizedPnlAt(priceJpy: BigDecimal, sizeBtc: BigDecimal): String {
    return priceJpy
        .subtract(averageEntryPriceJpy.toBigDecimal())
        .multiply(sizeBtc)
        .moneyScale()
        .toPlainString()
}

internal fun Position.unrealizedRAt(priceJpy: BigDecimal): String {
    val entryPrice = averageEntryPriceJpy.toBigDecimal()
    val stopPrice = currentStopLossJpy?.toBigDecimal() ?: return BigDecimal.ZERO.ratioScale().toPlainString()
    val riskWidth = entryPrice.subtract(stopPrice)

    if (riskWidth <= BigDecimal.ZERO) {
        return BigDecimal.ZERO.ratioScale().toPlainString()
    }

    return priceJpy
        .subtract(entryPrice)
        .divide(riskWidth, RATIO_SCALE, RoundingMode.HALF_UP)
        .ratioScale()
        .toPlainString()
}
