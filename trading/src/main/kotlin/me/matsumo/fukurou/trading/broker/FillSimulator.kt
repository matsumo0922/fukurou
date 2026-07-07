package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.entryFeeRateFor
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.UUID

/**
 * paper 約定の既定設定。
 *
 * @param marketSlippageBps MARKET / STOP の悲観 slippage。5bps は paper 初期の保守的な近似値
 */
data class PaperExecutionConfig(
    val marketSlippageBps: BigDecimal = DEFAULT_MARKET_SLIPPAGE_BPS,
) {
    init {
        require(marketSlippageBps >= BigDecimal.ZERO) {
            "marketSlippageBps must be greater than or equal to 0."
        }
    }
}

/**
 * paper execution の価格・手数料を決定する simulator。
 *
 * @param config paper execution 設定
 * @param clock 約定時刻に使う clock
 */
class FillSimulator(
    private val config: PaperExecutionConfig = PaperExecutionConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * MARKET 約定を計算する。
     */
    fun marketFill(side: OrderSide, sizeBtc: BigDecimal, ticker: Ticker, rules: SymbolRules): SimulatedFill {
        val price = when (side) {
            OrderSide.BUY -> applyPositiveSlippage(ticker.ask.toBigDecimal())
            OrderSide.SELL -> applyNegativeSlippage(ticker.bid.toBigDecimal())
        }

        return fill(
            sizeBtc = sizeBtc,
            priceJpy = price,
            feeRate = entryFeeRateFor(OrderType.MARKET, rules),
            liquidity = ExecutionLiquidity.TAKER,
        )
    }

    /**
     * STOP 約定を計算する。
     */
    fun stopFill(
        side: OrderSide,
        sizeBtc: BigDecimal,
        triggerPriceJpy: BigDecimal,
        ticker: Ticker,
        rules: SymbolRules,
    ): SimulatedFill {
        val slippagePrice = when (side) {
            OrderSide.BUY -> applyPositiveSlippage(ticker.ask.toBigDecimal())
            OrderSide.SELL -> applyNegativeSlippage(ticker.bid.toBigDecimal())
        }
        val price = when (side) {
            OrderSide.BUY -> maxOf(triggerPriceJpy, slippagePrice)
            OrderSide.SELL -> minOf(triggerPriceJpy, slippagePrice)
        }

        return fill(
            sizeBtc = sizeBtc,
            priceJpy = price,
            feeRate = entryFeeRateFor(OrderType.STOP, rules),
            liquidity = ExecutionLiquidity.TAKER,
        )
    }

    /**
     * resting LIMIT 約定を計算する。
     */
    fun restingLimitFill(
        sizeBtc: BigDecimal,
        limitPriceJpy: BigDecimal,
        rules: SymbolRules,
    ): SimulatedFill {
        return fill(
            sizeBtc = sizeBtc,
            priceJpy = limitPriceJpy,
            feeRate = entryFeeRateFor(OrderType.LIMIT, rules),
            liquidity = ExecutionLiquidity.MAKER,
        )
    }

    /**
     * resting entry order の約定を注文種別に応じて計算する。
     */
    fun restingEntryFill(
        side: OrderSide,
        orderType: OrderType,
        sizeBtc: BigDecimal,
        limitPriceJpy: BigDecimal?,
        triggerPriceJpy: BigDecimal?,
        ticker: Ticker,
        rules: SymbolRules,
    ): SimulatedFill {
        return when (orderType) {
            OrderType.LIMIT -> restingLimitFill(
                sizeBtc = sizeBtc,
                limitPriceJpy = requireNotNull(limitPriceJpy) {
                    "LIMIT entry order requires limitPriceJpy."
                },
                rules = rules,
            )
            OrderType.STOP -> stopFill(
                side = side,
                sizeBtc = sizeBtc,
                triggerPriceJpy = requireNotNull(triggerPriceJpy) {
                    "STOP entry order requires triggerPriceJpy."
                },
                ticker = ticker,
                rules = rules,
            )
            OrderType.MARKET -> error("MARKET entry is not a resting order.")
        }
    }

    private fun fill(
        sizeBtc: BigDecimal,
        priceJpy: BigDecimal,
        feeRate: BigDecimal,
        liquidity: ExecutionLiquidity,
    ): SimulatedFill {
        val notional = priceJpy.multiply(sizeBtc)
        val fee = notional.multiply(feeRate).moneyScale()

        return SimulatedFill(
            executionId = UUID.randomUUID(),
            priceJpy = priceJpy.moneyScale(),
            sizeBtc = sizeBtc.btcScale(),
            feeJpy = fee,
            realizedPnlJpy = BigDecimal.ZERO.moneyScale(),
            liquidity = liquidity,
            executedAt = clock.instant(),
        )
    }

    private fun applyPositiveSlippage(price: BigDecimal): BigDecimal {
        return price
            .multiply(BigDecimal.ONE.add(slippageRatio()))
            .moneyScale()
    }

    private fun applyNegativeSlippage(price: BigDecimal): BigDecimal {
        return price
            .multiply(BigDecimal.ONE.subtract(slippageRatio()))
            .moneyScale()
    }

    private fun slippageRatio(): BigDecimal {
        return config.marketSlippageBps.divide(BPS_DIVISOR, PRICE_CALCULATION_SCALE, RoundingMode.HALF_UP)
    }
}

/**
 * JPY 金額を DB scale に丸める。
 */
internal fun BigDecimal.moneyScale(): BigDecimal {
    return setScale(MONEY_SCALE, RoundingMode.HALF_UP)
}

/**
 * BTC 数量を DB scale に丸める。
 */
internal fun BigDecimal.btcScale(): BigDecimal {
    return setScale(BTC_SCALE, RoundingMode.HALF_UP)
}

/**
 * ratio を DB scale に丸める。
 */
internal fun BigDecimal.ratioScale(): BigDecimal {
    return setScale(RATIO_SCALE, RoundingMode.HALF_UP)
}

/**
 * 取引所 tick / step に対して下方向へ丸める。
 */
internal fun BigDecimal.floorToStep(step: BigDecimal): BigDecimal {
    require(step > BigDecimal.ZERO) {
        "step must be greater than zero."
    }

    val stepCount = divide(step, 0, RoundingMode.DOWN)

    return stepCount.multiply(step)
}

/**
 * JPY 金額 scale。
 */
internal const val MONEY_SCALE = 8

/**
 * BTC 数量 scale。
 */
internal const val BTC_SCALE = 12

/**
 * ratio scale。
 */
internal const val RATIO_SCALE = 10

/**
 * 計算途中の価格 scale。
 */
private const val PRICE_CALCULATION_SCALE = 16

/**
 * bps 分母。
 */
private val BPS_DIVISOR = BigDecimal("10000")

/**
 * MARKET / STOP の既定 slippage。
 */
private val DEFAULT_MARKET_SLIPPAGE_BPS = BigDecimal("5")
