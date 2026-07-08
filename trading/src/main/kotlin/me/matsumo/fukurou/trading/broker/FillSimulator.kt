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
 */
interface PaperExecutionSimulator {
    /**
     * 即時 taker 約定を計算する。
     */
    fun simulateImmediate(
        request: ImmediateExecutionRequest,
        context: PaperSimulationContext,
    ): SimulatedFill

    /**
     * 未約定 LIMIT 注文の更新を計算する。
     */
    fun simulatePendingLimit(
        request: PendingLimitExecutionRequest,
        context: PaperSimulationContext,
    ): PaperOrderUpdate
}

/**
 * 即時 taker 約定の入力。
 *
 * @param side 注文 side
 * @param orderType 注文種別。MARKET / STOP を扱う
 * @param sizeBtc 注文数量
 * @param triggerPriceJpy STOP trigger 価格
 */
data class ImmediateExecutionRequest(
    val side: OrderSide,
    val orderType: OrderType,
    val sizeBtc: BigDecimal,
    val triggerPriceJpy: BigDecimal? = null,
)

/**
 * 未約定 LIMIT 注文の更新入力。
 *
 * @param side 注文 side
 * @param sizeBtc 注文数量
 * @param limitPriceJpy LIMIT 価格
 */
data class PendingLimitExecutionRequest(
    val side: OrderSide,
    val sizeBtc: BigDecimal,
    val limitPriceJpy: BigDecimal,
)

/**
 * paper 約定計算に必要な市場 context。
 *
 * @param ticker 最新 ticker
 * @param rules symbol rule
 * @param volatilitySlippageJpy ボラティリティ由来の追加 slippage
 * @param queueFillRatio maker queue の約定率。現行実装では all-or-none のため将来拡張用
 */
data class PaperSimulationContext(
    val ticker: Ticker,
    val rules: SymbolRules,
    val volatilitySlippageJpy: BigDecimal = BigDecimal.ZERO,
    val queueFillRatio: BigDecimal = BigDecimal.ONE,
)

/**
 * 未約定 LIMIT 注文の更新結果。
 *
 * @param fill 発生した約定。未約定なら null
 * @param remainingSizeBtc 残数量
 * @param expired 注文が失効したか
 */
data class PaperOrderUpdate(
    val fill: SimulatedFill?,
    val remainingSizeBtc: BigDecimal,
    val expired: Boolean,
)

/**
 * paper execution の価格・手数料を決定する既定 simulator。
 *
 * @param config paper execution 設定
 * @param clock 約定時刻に使う clock
 */
class DefaultPaperExecutionSimulator(
    private val config: PaperExecutionConfig = PaperExecutionConfig(),
    private val clock: Clock = Clock.systemUTC(),
) : PaperExecutionSimulator {

    override fun simulateImmediate(
        request: ImmediateExecutionRequest,
        context: PaperSimulationContext,
    ): SimulatedFill {
        val price = when (request.orderType) {
            OrderType.MARKET -> marketPrice(request.side, context.ticker)
            OrderType.STOP -> stopPrice(request, context.ticker)
            OrderType.LIMIT -> error("LIMIT order is not an immediate taker order.")
        }

        return fill(
            sizeBtc = request.sizeBtc,
            priceJpy = price,
            feeRate = entryFeeRateFor(request.orderType, context.rules),
            liquidity = ExecutionLiquidity.TAKER,
        )
    }

    override fun simulatePendingLimit(
        request: PendingLimitExecutionRequest,
        context: PaperSimulationContext,
    ): PaperOrderUpdate {
        val fill = fill(
            sizeBtc = request.sizeBtc,
            priceJpy = request.limitPriceJpy,
            feeRate = entryFeeRateFor(OrderType.LIMIT, context.rules),
            liquidity = ExecutionLiquidity.MAKER,
        )

        return PaperOrderUpdate(
            fill = fill,
            remainingSizeBtc = BigDecimal.ZERO.btcScale(),
            expired = false,
        )
    }

    private fun marketPrice(side: OrderSide, ticker: Ticker): BigDecimal {
        return when (side) {
            OrderSide.BUY -> applyPositiveSlippage(ticker.ask.toBigDecimal())
            OrderSide.SELL -> applyNegativeSlippage(ticker.bid.toBigDecimal())
        }
    }

    private fun stopPrice(request: ImmediateExecutionRequest, ticker: Ticker): BigDecimal {
        val triggerPriceJpy = requireNotNull(request.triggerPriceJpy) {
            "STOP order requires triggerPriceJpy."
        }
        val slippagePrice = marketPrice(request.side, ticker)

        return when (request.side) {
            OrderSide.BUY -> maxOf(triggerPriceJpy, slippagePrice)
            OrderSide.SELL -> minOf(triggerPriceJpy, slippagePrice)
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
 * 従来名との互換 alias。
 */
typealias FillSimulator = DefaultPaperExecutionSimulator

/**
 * MARKET 約定を計算する。
 */
internal fun PaperExecutionSimulator.marketFill(
    side: OrderSide,
    sizeBtc: BigDecimal,
    ticker: Ticker,
    rules: SymbolRules,
): SimulatedFill {
    return marketFill(
        side = side,
        sizeBtc = sizeBtc,
        context = PaperSimulationContext(
            ticker = ticker,
            rules = rules,
        ),
    )
}

/**
 * MARKET 約定を計算する。
 */
internal fun PaperExecutionSimulator.marketFill(
    side: OrderSide,
    sizeBtc: BigDecimal,
    context: PaperSimulationContext,
): SimulatedFill {
    return simulateImmediate(
        request = ImmediateExecutionRequest(
            side = side,
            orderType = OrderType.MARKET,
            sizeBtc = sizeBtc,
        ),
        context = context,
    )
}

/**
 * STOP 約定を計算する。
 */
internal fun PaperExecutionSimulator.stopFill(
    side: OrderSide,
    sizeBtc: BigDecimal,
    triggerPriceJpy: BigDecimal,
    ticker: Ticker,
    rules: SymbolRules,
): SimulatedFill {
    return stopFill(
        side = side,
        sizeBtc = sizeBtc,
        triggerPriceJpy = triggerPriceJpy,
        context = PaperSimulationContext(
            ticker = ticker,
            rules = rules,
        ),
    )
}

/**
 * STOP 約定を計算する。
 */
internal fun PaperExecutionSimulator.stopFill(
    side: OrderSide,
    sizeBtc: BigDecimal,
    triggerPriceJpy: BigDecimal,
    context: PaperSimulationContext,
): SimulatedFill {
    return simulateImmediate(
        request = ImmediateExecutionRequest(
            side = side,
            orderType = OrderType.STOP,
            sizeBtc = sizeBtc,
            triggerPriceJpy = triggerPriceJpy,
        ),
        context = context,
    )
}

/**
 * resting LIMIT 約定を計算する。
 */
internal fun PaperExecutionSimulator.restingLimitFill(
    sizeBtc: BigDecimal,
    limitPriceJpy: BigDecimal,
    rules: SymbolRules,
): SimulatedFill {
    return requireNotNull(
        simulatePendingLimit(
            request = PendingLimitExecutionRequest(
                side = OrderSide.BUY,
                sizeBtc = sizeBtc,
                limitPriceJpy = limitPriceJpy,
            ),
            context = PaperSimulationContext(
                ticker = limitOnlyTicker(limitPriceJpy),
                rules = rules,
            ),
        ).fill,
    ) {
        "Triggered LIMIT order must create a fill."
    }
}

/**
 * resting entry order の約定を注文種別に応じて計算する。
 */
internal fun PaperExecutionSimulator.restingEntryFill(request: RestingEntryFillRequest): SimulatedFill {
    return when (request.orderType) {
        OrderType.LIMIT -> requireNotNull(
            simulatePendingLimit(
                request = PendingLimitExecutionRequest(
                    side = request.side,
                    sizeBtc = request.sizeBtc,
                    limitPriceJpy = requireNotNull(request.limitPriceJpy) {
                        "LIMIT entry order requires limitPriceJpy."
                    },
                ),
                context = PaperSimulationContext(
                    ticker = request.ticker,
                    rules = request.rules,
                ),
            ).fill,
        ) {
            "Triggered LIMIT order must create a fill."
        }
        OrderType.STOP -> stopFill(
            side = request.side,
            sizeBtc = request.sizeBtc,
            triggerPriceJpy = requireNotNull(request.triggerPriceJpy) {
                "STOP entry order requires triggerPriceJpy."
            },
            ticker = request.ticker,
            rules = request.rules,
        )
        OrderType.MARKET -> error("MARKET entry is not a resting order.")
    }
}

/**
 * resting entry order の約定計算入力。
 *
 * @param side 注文 side
 * @param orderType 注文種別
 * @param sizeBtc 注文数量
 * @param limitPriceJpy LIMIT 価格
 * @param triggerPriceJpy STOP trigger 価格
 * @param ticker 現在 ticker
 * @param rules symbol rule
 */
data class RestingEntryFillRequest(
    val side: OrderSide,
    val orderType: OrderType,
    val sizeBtc: BigDecimal,
    val limitPriceJpy: BigDecimal?,
    val triggerPriceJpy: BigDecimal?,
    val ticker: Ticker,
    val rules: SymbolRules,
)

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

private fun limitOnlyTicker(priceJpy: BigDecimal): Ticker {
    val priceText = priceJpy.toPlainString()

    return Ticker(
        symbol = "",
        last = priceText,
        bid = priceText,
        ask = priceText,
        high = priceText,
        low = priceText,
        volume = BigDecimal.ZERO.toPlainString(),
        timestamp = "",
    )
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
