package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.knowledge.ClosedPaperPosition
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import java.math.BigDecimal
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
 */
data class MarketEntryFillRequest(
    val command: PlaceOrderCommand,
    val fill: SimulatedFill,
    val positionId: UUID,
    val tradeGroupId: UUID,
    val stopOrderId: UUID,
)

/**
 * resting entry order の保存入力。
 *
 * @param command place_order command
 * @param orderId 作成する order ID
 * @param tradeGroupId entry order を束ねる trade group ID
 */
data class RestingEntryOrderRequest(
    val command: PlaceOrderCommand,
    val orderId: UUID,
    val tradeGroupId: UUID,
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
 * @param triggeredOrderIds trigger した order ID
 * @param closedPositionIds close した position ID
 * @param executionIds 作成した execution ID
 */
data class ReconcileProgress(
    val triggeredOrderIds: MutableList<String>,
    val closedPositionIds: MutableList<String>,
    val executionIds: MutableList<String>,
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
