package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.knowledge.ClosedPaperPosition
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * paper ledger の読み書き repository。
 */
interface PaperLedgerRepository {
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
    suspend fun findExecutionsBefore(before: Instant, limit: Int,): Result<List<Execution>>

    /**
     * 安定 cursor 条件に一致する execution ledger の行を Activity timeline 用に新しい順で取得する。
     */
    suspend fun findExecutionsForStableFeed(cursor: StableFeedCursor, limit: Int,): Result<List<Execution>>

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

    /**
     * MARKET entry を約定済みとして保存し、保護 STOP を作成する。
     */
    suspend fun fillMarketEntry(
        command: PlaceOrderCommand,
        fill: SimulatedFill,
        positionId: UUID,
        tradeGroupId: UUID,
        stopOrderId: UUID,
    ): Result<PaperTradeResult>

    /**
     * resting entry intent を未約定 order として保存する。
     */
    suspend fun createRestingEntryOrder(
        command: PlaceOrderCommand,
        orderId: UUID,
        tradeGroupId: UUID,
    ): Result<PaperTradeResult>

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
    suspend fun reconcile(tickSnapshot: TickSnapshot, simulator: FillSimulator): Result<PaperReconcileResult>
}

/**
 * entry order と intent consumption を同一 commit 境界で保存できる paper ledger repository。
 */
interface IntentConsumingPaperLedgerRepository : PaperLedgerRepository {
    /**
     * MARKET entry と intent consumption を同一 commit 境界で保存する。
     */
    suspend fun fillMarketEntryAndConsumeIntent(
        command: PlaceOrderCommand,
        fill: SimulatedFill,
        positionId: UUID,
        tradeGroupId: UUID,
        stopOrderId: UUID,
        intentId: UUID,
        consumedAt: Instant,
    ): Result<PaperTradeResult>

    /**
     * resting entry order と intent consumption を同一 commit 境界で保存する。
     */
    suspend fun createRestingEntryOrderAndConsumeIntent(
        command: PlaceOrderCommand,
        orderId: UUID,
        tradeGroupId: UUID,
        intentId: UUID,
        consumedAt: Instant,
    ): Result<PaperTradeResult>
}
