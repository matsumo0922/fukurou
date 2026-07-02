package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.TradingMode
import java.math.BigDecimal

/**
 * paper 初期残高の環境変数名。
 */
private const val FUKUROU_PAPER_INITIAL_CASH_JPY_ENV = "FUKUROU_PAPER_INITIAL_CASH_JPY"

/**
 * trading mode の環境変数名。
 */
private const val FUKUROU_TRADING_MODE_ENV = "FUKUROU_TRADING_MODE"

/**
 * paper 初期残高の既定値。
 */
private val DEFAULT_INITIAL_CASH_JPY = BigDecimal("100000")

/**
 * paper account 初期化に使う設定。
 *
 * @param initialCashJpy 初期 JPY 残高
 * @param mode 取引 mode
 */
data class PaperAccountConfig(
    val initialCashJpy: BigDecimal = DEFAULT_INITIAL_CASH_JPY,
    val mode: TradingMode = TradingMode.PAPER,
) {
    init {
        require(initialCashJpy > BigDecimal.ZERO) {
            "$FUKUROU_PAPER_INITIAL_CASH_JPY_ENV must be greater than 0."
        }
    }

    companion object {
        /**
         * 環境変数から paper account 設定を読む。
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): PaperAccountConfig {
            val initialCashJpy = environment[FUKUROU_PAPER_INITIAL_CASH_JPY_ENV]
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value -> BigDecimal(value) }
                ?: DEFAULT_INITIAL_CASH_JPY
            val mode = environment[FUKUROU_TRADING_MODE_ENV]
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value -> TradingMode.valueOf(value.uppercase()) }
                ?: TradingMode.PAPER

            require(initialCashJpy > BigDecimal.ZERO) {
                "$FUKUROU_PAPER_INITIAL_CASH_JPY_ENV must be greater than 0."
            }

            return PaperAccountConfig(
                initialCashJpy = initialCashJpy,
                mode = mode,
            )
        }
    }
}

/**
 * paper account config から初期 account snapshot を作る。
 */
internal fun PaperAccountConfig.toInitialAccountSnapshot(): AccountSnapshot {
    val initialCash = initialCashJpy.moneyScale().toPlainString()

    return AccountSnapshot(
        mode = mode,
        cashJpy = initialCash,
        initialCashJpy = initialCash,
        btcQuantity = BigDecimal.ZERO.btcScale().toPlainString(),
        btcMarkPriceJpy = BigDecimal.ZERO.moneyScale().toPlainString(),
        totalEquityJpy = initialCash,
        equityPeakJpy = initialCash,
        drawdownRatio = BigDecimal.ZERO.ratioScale().toPlainString(),
    )
}
