package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.Position

/**
 * 口座・position ledger の読み取り境界。
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
}
