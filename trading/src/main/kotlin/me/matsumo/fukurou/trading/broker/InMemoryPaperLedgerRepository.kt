package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.TradingMode
import java.math.BigDecimal
import java.time.LocalDate

/**
 * unit test と明示 injection 用の paper ledger repository。
 *
 * @param accountSnapshot 残高 snapshot
 * @param positions open position 一覧
 * @param openOrders open order 一覧
 * @param executions execution 一覧
 */
class InMemoryPaperLedgerRepository(
    private val accountSnapshot: AccountSnapshot = defaultAccountSnapshot(),
    private val positions: List<Position> = emptyList(),
    private val openOrders: List<Order> = emptyList(),
    private val executions: List<Execution> = emptyList(),
) : PaperLedgerRepository {

    override suspend fun getAccountSnapshot(): Result<AccountSnapshot> {
        return Result.success(accountSnapshot)
    }

    override suspend fun getOpenPositions(): Result<List<Position>> {
        return Result.success(positions)
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return Result.success(openOrders)
    }

    override suspend fun getRealizedPnlForDate(date: LocalDate): Result<BigDecimal> {
        return Result.success(BigDecimal.ZERO)
    }

    override suspend fun getExecutions(): Result<List<Execution>> {
        return Result.success(executions)
    }
}

/**
 * 初期 paper 口座 snapshot を返す。
 */
private fun defaultAccountSnapshot(): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = "100000.00000000",
        initialCashJpy = "100000.00000000",
        btcQuantity = "0.000000000000",
        btcMarkPriceJpy = "0.00000000",
        totalEquityJpy = "100000.00000000",
        equityPeakJpy = "100000.00000000",
        drawdownRatio = "0E-10",
    )
}
