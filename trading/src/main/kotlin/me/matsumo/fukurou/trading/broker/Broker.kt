package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import java.time.Instant

/**
 * paper account snapshot と同一読み取りで取得した更新時刻。
 *
 * @param accountSnapshot paper account snapshot
 * @param updatedAt paper_account.updated_at
 */
data class AccountSnapshotWithUpdatedAt(
    val accountSnapshot: AccountSnapshot,
    val updatedAt: Instant,
)

/**
 * account status と、その算出に使った paper account snapshot の更新時刻。
 *
 * @param accountStatus account status
 * @param updatedAt accountStatus 算出に使った paper_account.updated_at
 */
data class AccountStatusWithUpdatedAt(
    val accountStatus: AccountStatus,
    val updatedAt: Instant,
)

/**
 * paper broker の読み取り・副作用境界。
 */
interface Broker {
    /**
     * 現在の残高 snapshot を返す。
     */
    suspend fun getBalance(): Result<AccountSnapshot>

    /**
     * 現在の残高 snapshot と、その snapshot の更新時刻を同一読み取り由来で返す。
     */
    suspend fun getBalanceWithUpdatedAt(): Result<AccountSnapshotWithUpdatedAt>

    /**
     * paper account single row の更新時刻を返す。
     */
    suspend fun getAccountUpdatedAt(): Result<Instant>

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
     * 口座 status と、その算出に使った paper account snapshot の更新時刻を返す。
     */
    suspend fun getAccountStatusWithUpdatedAt(): Result<AccountStatusWithUpdatedAt>

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

    /**
     * HARD_HALT 到達時の内部掃引を実行する。
     */
    suspend fun sweepHardHalt(reasonJa: String, tickSnapshot: TickSnapshot): Result<PaperTradeResult>
}
