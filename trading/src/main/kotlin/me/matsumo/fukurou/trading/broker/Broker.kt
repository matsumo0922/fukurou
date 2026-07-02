package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.reconciler.TickSnapshot

/**
 * paper broker の読み取り・副作用境界。
 */
interface Broker {
    /**
     * 現在の残高 snapshot を返す。
     */
    suspend fun getBalance(): Result<AccountSnapshot>

    /**
     * 現在の open position 一覧を返す。
     */
    suspend fun getPositions(): Result<List<Position>>

    /**
     * 現在の open order 一覧を返す。
     */
    suspend fun getOpenOrders(): Result<List<Order>>

    /**
     * 口座と safety 状態をまとめた status を返す。
     */
    suspend fun getAccountStatus(): Result<AccountStatus>

    /**
     * paper entry 注文を受け付ける。
     */
    suspend fun placeOrder(command: PlaceOrderCommand): Result<PaperTradeResult>

    /**
     * paper position を close する。
     */
    suspend fun closePosition(command: ClosePositionCommand): Result<PaperTradeResult>

    /**
     * paper position の保護を更新する。
     */
    suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult>

    /**
     * paper order を cancel する。
     */
    suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult>

    /**
     * tick をもとに paper ledger を決定的に前進させる。
     */
    suspend fun reconcile(tickSnapshot: TickSnapshot): Result<PaperReconcileResult>
}
