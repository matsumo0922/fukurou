package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.Position
import java.math.BigDecimal
import java.time.LocalDate

/**
 * paper ledger の読み取り repository。
 */
interface PaperLedgerRepository {
    /**
     * paper account の残高 snapshot を返す。
     */
    suspend fun getAccountSnapshot(): Result<AccountSnapshot>

    /**
     * open position 一覧を返す。
     */
    suspend fun getOpenPositions(): Result<List<Position>>

    /**
     * open order 一覧を返す。
     */
    suspend fun getOpenOrders(): Result<List<Order>>

    /**
     * 指定日の実現損益合計を返す。
     */
    suspend fun getRealizedPnlForDate(date: LocalDate): Result<BigDecimal>

    /**
     * execution ledger の読み取りを返す。
     */
    suspend fun getExecutions(): Result<List<Execution>>
}
