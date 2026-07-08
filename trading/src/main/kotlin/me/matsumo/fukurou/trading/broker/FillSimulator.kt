package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.entryFeeRateFor
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.UUID
import java.util.logging.Logger

/**
 * paper 約定の既定設定。
 *
 * @param marketSlippageBps MARKET / STOP の悲観 slippage。5bps は paper 初期の保守的な近似値
 * @param volatilitySlippageMultiplier ATR(5m, 14) に掛けるボラティリティ slippage 係数
 */
data class PaperExecutionConfig(
    val marketSlippageBps: BigDecimal = DEFAULT_MARKET_SLIPPAGE_BPS,
    val volatilitySlippageMultiplier: BigDecimal = DEFAULT_VOLATILITY_SLIPPAGE_MULTIPLIER,
) {
    init {
        require(marketSlippageBps >= BigDecimal.ZERO) {
            "marketSlippageBps must be greater than or equal to 0."
        }
        require(volatilitySlippageMultiplier >= BigDecimal.ZERO) {
            "volatilitySlippageMultiplier must be greater than or equal to 0."
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
    fun simulateImmediate(request: ImmediateExecutionRequest, context: PaperSimulationContext): SimulatedFill

    /**
     * 未約定 LIMIT 注文の更新を計算する。
     */
    fun simulatePendingLimit(request: PendingLimitExecutionRequest, context: PaperSimulationContext): PaperOrderUpdate
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
 * @param orderbook 約定時点の板情報
 * @param orderbookLookupAttempted 板取得を試みたなら true
 * @param volatilitySlippageJpy ボラティリティ由来の追加 slippage
 * @param queueFillRatio maker queue の約定率。現行実装では all-or-none のため将来拡張用
 */
data class PaperSimulationContext(
    val ticker: Ticker,
    val rules: SymbolRules,
    val orderbook: Orderbook? = null,
    val orderbookLookupAttempted: Boolean = false,
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
 * @param warnLogger fallback を記録する WARN logger
 */
class DefaultPaperExecutionSimulator(
    private val config: PaperExecutionConfig = PaperExecutionConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = paperExecutionLogger,
        clock = clock,
    ),
) : PaperExecutionSimulator {

    override fun simulateImmediate(
        request: ImmediateExecutionRequest,
        context: PaperSimulationContext,
    ): SimulatedFill {
        val price = when (request.orderType) {
            OrderType.MARKET -> marketPrice(request.side, request.sizeBtc, context)
            OrderType.STOP -> stopPrice(request, context)
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

    private fun marketPrice(
        side: OrderSide,
        sizeBtc: BigDecimal,
        context: PaperSimulationContext,
    ): BigDecimal {
        val basePrice = walkedOrderbookPrice(side, sizeBtc, context)
            ?: fallbackTickerPrice(side, context)

        return applyAdverseSlippage(side, basePrice, context.volatilitySlippageJpy)
    }

    private fun fallbackTickerPrice(side: OrderSide, context: PaperSimulationContext): BigDecimal {
        if (context.orderbookLookupAttempted && context.orderbook == null) {
            warnLogger.warn(
                key = ORDERBOOK_FALLBACK_LOG_KEY,
                message = "Paper execution orderbook is unavailable; falling back to ticker price.",
            )
        }

        return when (side) {
            OrderSide.BUY -> context.ticker.ask.toBigDecimal()
            OrderSide.SELL -> context.ticker.bid.toBigDecimal()
        }
    }

    private fun stopPrice(request: ImmediateExecutionRequest, context: PaperSimulationContext): BigDecimal {
        val triggerPriceJpy = requireNotNull(request.triggerPriceJpy) {
            "STOP order requires triggerPriceJpy."
        }
        val slippagePrice = marketPrice(request.side, request.sizeBtc, context)

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
            .max(BigDecimal.ZERO)
            .moneyScale()
    }

    private fun applyAdverseSlippage(
        side: OrderSide,
        price: BigDecimal,
        volatilitySlippageJpy: BigDecimal,
    ): BigDecimal {
        return when (side) {
            OrderSide.BUY -> applyPositiveSlippage(price).add(volatilitySlippageJpy).moneyScale()
            OrderSide.SELL -> applyNegativeSlippage(price).subtract(volatilitySlippageJpy).max(BigDecimal.ZERO).moneyScale()
        }
    }

    private fun walkedOrderbookPrice(
        side: OrderSide,
        requestedSizeBtc: BigDecimal,
        context: PaperSimulationContext,
    ): BigDecimal? {
        val orderbook = context.orderbook ?: return null
        val levels = when (side) {
            OrderSide.BUY -> orderbook.asks.toAdverseAskLevels()
            OrderSide.SELL -> orderbook.bids.toAdverseBidLevels()
        }

        if (levels.invalidLevelFound) {
            warnLogger.warn(
                key = ORDERBOOK_INVALID_LEVEL_LOG_KEY,
                message = "Paper execution ignored invalid orderbook levels.",
            )
        }
        if (levels.usableLevels.isEmpty()) {
            warnLogger.warn(
                key = ORDERBOOK_FALLBACK_LOG_KEY,
                message = "Paper execution orderbook has no usable levels; falling back to ticker price.",
            )

            return null
        }

        val walkResult = walkLevels(
            side = side,
            levels = levels.usableLevels,
            requestedSizeBtc = requestedSizeBtc,
        )

        if (walkResult.depthExhausted) {
            warnLogger.warn(
                key = ORDERBOOK_DEPTH_EXHAUSTED_LOG_KEY,
                message = "Paper execution orderbook depth is insufficient; applying conservative residual price.",
            )
        }

        return walkResult.priceJpy
    }

    private fun slippageRatio(): BigDecimal {
        return config.marketSlippageBps.divide(BPS_DIVISOR, PRICE_CALCULATION_SCALE, RoundingMode.HALF_UP)
    }

    private fun walkLevels(
        side: OrderSide,
        levels: List<ParsedOrderbookLevel>,
        requestedSizeBtc: BigDecimal,
    ): OrderbookWalkResult {
        require(requestedSizeBtc > BigDecimal.ZERO) {
            "requestedSizeBtc must be greater than zero."
        }

        var remainingSizeBtc = requestedSizeBtc
        var notionalJpy = BigDecimal.ZERO
        var lastLevelPrice = levels.first().priceJpy

        levels.forEach { level ->
            if (remainingSizeBtc <= BigDecimal.ZERO) {
                return@forEach
            }

            val fillSizeBtc = minOf(level.sizeBtc, remainingSizeBtc)

            notionalJpy = notionalJpy.add(level.priceJpy.multiply(fillSizeBtc))
            remainingSizeBtc = remainingSizeBtc.subtract(fillSizeBtc)
            lastLevelPrice = level.priceJpy
        }

        val depthExhausted = remainingSizeBtc > BigDecimal.ZERO

        if (depthExhausted) {
            notionalJpy = notionalJpy.add(residualDepthPrice(side, lastLevelPrice).multiply(remainingSizeBtc))
        }

        return OrderbookWalkResult(
            priceJpy = notionalJpy.divide(requestedSizeBtc, PRICE_CALCULATION_SCALE, RoundingMode.HALF_UP),
            depthExhausted = depthExhausted,
        )
    }

    private fun residualDepthPrice(side: OrderSide, lastLevelPrice: BigDecimal): BigDecimal {
        val penaltyRatio = maxOf(slippageRatio(), MIN_DEPTH_EXHAUSTION_PENALTY_RATIO)

        return when (side) {
            OrderSide.BUY -> lastLevelPrice.multiply(BigDecimal.ONE.add(penaltyRatio))
            OrderSide.SELL -> lastLevelPrice.multiply(BigDecimal.ONE.subtract(penaltyRatio)).max(BigDecimal.ZERO)
        }
    }
}

/**
 * 約定計算に使える板 level。
 *
 * @param priceJpy 価格
 * @param sizeBtc 数量
 */
private data class ParsedOrderbookLevel(
    val priceJpy: BigDecimal,
    val sizeBtc: BigDecimal,
)

/**
 * parse 済み板 level と invalid level の有無。
 *
 * @param usableLevels 約定計算に使える level
 * @param invalidLevelFound invalid level が含まれていたか
 */
private data class ParsedOrderbookLevels(
    val usableLevels: List<ParsedOrderbookLevel>,
    val invalidLevelFound: Boolean,
)

/**
 * 板歩きの価格結果。
 *
 * @param priceJpy 数量加重平均価格
 * @param depthExhausted 板深さが不足したか
 */
private data class OrderbookWalkResult(
    val priceJpy: BigDecimal,
    val depthExhausted: Boolean,
)

private fun List<OrderbookLevel>.toAdverseAskLevels(): ParsedOrderbookLevels {
    return toParsedOrderbookLevels().let { levels ->
        levels.copy(usableLevels = levels.usableLevels.sortedBy { level -> level.priceJpy })
    }
}

private fun List<OrderbookLevel>.toAdverseBidLevels(): ParsedOrderbookLevels {
    return toParsedOrderbookLevels().let { levels ->
        levels.copy(usableLevels = levels.usableLevels.sortedByDescending { level -> level.priceJpy })
    }
}

private fun List<OrderbookLevel>.toParsedOrderbookLevels(): ParsedOrderbookLevels {
    var invalidLevelFound = false
    val usableLevels = mapNotNull { level ->
        val parsedLevel = level.toParsedOrderbookLevel()

        if (parsedLevel == null) {
            invalidLevelFound = true
        }

        parsedLevel
    }

    return ParsedOrderbookLevels(
        usableLevels = usableLevels,
        invalidLevelFound = invalidLevelFound,
    )
}

private fun OrderbookLevel.toParsedOrderbookLevel(): ParsedOrderbookLevel? {
    val priceJpy = price.toBigDecimalOrNull() ?: return null
    val sizeBtc = size.toBigDecimalOrNull() ?: return null

    if (priceJpy <= BigDecimal.ZERO || sizeBtc <= BigDecimal.ZERO) {
        return null
    }

    return ParsedOrderbookLevel(
        priceJpy = priceJpy,
        sizeBtc = sizeBtc,
    )
}

/**
 * 従来名との互換 alias。
 */
typealias FillSimulator = DefaultPaperExecutionSimulator

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

/**
 * ATR 由来 slippage の既定係数。
 */
private val DEFAULT_VOLATILITY_SLIPPAGE_MULTIPLIER = BigDecimal("0.1")

/**
 * 板深さ不足時に最後の level へ最低限乗せる不利方向 penalty。
 */
private val MIN_DEPTH_EXHAUSTION_PENALTY_RATIO = BigDecimal("0.0001")

/**
 * orderbook fallback log の key。
 */
private const val ORDERBOOK_FALLBACK_LOG_KEY = "paper-execution-orderbook-fallback"

/**
 * invalid orderbook level log の key。
 */
private const val ORDERBOOK_INVALID_LEVEL_LOG_KEY = "paper-execution-orderbook-invalid-level"

/**
 * orderbook depth exhaustion log の key。
 */
private const val ORDERBOOK_DEPTH_EXHAUSTED_LOG_KEY = "paper-execution-orderbook-depth-exhausted"

private val paperExecutionLogger: Logger = Logger.getLogger(DefaultPaperExecutionSimulator::class.java.name)
