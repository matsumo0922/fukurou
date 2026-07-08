package me.matsumo.fukurou.trading.safety

import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.moneyScale
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.requiredCashFor
import me.matsumo.fukurou.trading.domain.roundTripCostReserveFor
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * SafetyFloor の金額、exposure、EV 計算を担当する。
 *
 * @param config 安全床しきい値
 * @param clock 市場データ鮮度判定に使う clock
 * @param paperExecutionConfig paper 約定近似設定
 */
internal class SafetyFloorRiskCalculator(
    private val config: SafetyFloorConfig,
    private val clock: Clock,
    private val paperExecutionConfig: PaperExecutionConfig,
) {

    /**
     * place_order / preview_order の主要な risk 計算詳細を返す。
     */
    fun placeOrderRiskDetails(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
    ): SafetyFloorPlaceOrderRiskDetails {
        val targetTradeGroupId = command.tradeGroupId?.toString()
        val estimatedEntryPrice = estimatedEntryPrice(command, context)
        val groupRiskBeforeOrder = groupRiskBeforeOrder(context, targetTradeGroupId)
        val orderRisk = orderRisk(command, context)
        val groupRiskAfterOrder = groupRiskBeforeOrder.add(orderRisk).safetyScale()
        val maxRiskPerTrade = context.account.totalEquityJpy.toBigDecimal()
            .multiply(config.maxRiskPerTradeRatio)
            .safetyScale()
        val currentExposure = currentExposure(context)
        val orderExposure = orderExposure(command, context)
        val totalExposureAfterOrder = currentExposure.add(orderExposure).safetyScale()
        val maxTotalExposure = context.account.totalEquityJpy.toBigDecimal()
            .multiply(config.maxTotalExposureRatio)
            .safetyScale()
        val expectedValueDetails = expectedValueDetailsOrNull(command, context)

        return SafetyFloorPlaceOrderRiskDetails(
            estimatedEntryPriceJpy = estimatedEntryPrice.toPlainString(),
            orderRiskJpy = orderRisk.toPlainString(),
            groupRiskBeforeOrderJpy = groupRiskBeforeOrder.toPlainString(),
            groupRiskAfterOrderJpy = groupRiskAfterOrder.toPlainString(),
            maxRiskPerTradeJpy = maxRiskPerTrade.toPlainString(),
            currentExposureJpy = currentExposure.toPlainString(),
            orderExposureJpy = orderExposure.toPlainString(),
            totalExposureAfterOrderJpy = totalExposureAfterOrder.toPlainString(),
            maxTotalExposureJpy = maxTotalExposure.toPlainString(),
            availableCashJpy = availableCash(context).toPlainString(),
            requiredCashJpy = orderRequiredCash(command, context).toPlainString(),
            expectedValueR = expectedValueDetails?.expectedValueR?.toPlainString(),
            expectedMoveToCostRatio = expectedMoveToCostRatioOrNull(command, context)?.toPlainString(),
            probabilityUsedForExpectedValue = expectedValueDetails
                ?.probabilityUsed
                ?.toPlainString()
                ?: command.estimatedWinProbability.toPlainString(),
            probabilityCapApplied = expectedValueDetails?.probabilityCapApplied ?: false,
        )
    }

    /**
     * place_order command の entry 価格見積もりを返す。
     */
    fun estimatedEntryPrice(command: PlaceOrderCommand, context: SafetyFloorContext): BigDecimal {
        val askPrice = context.ticker.ask.toBigDecimal()

        return when (command.orderType) {
            OrderType.MARKET -> applyPositiveSlippage(askPrice).add(volatilitySlippageJpy(context))
            OrderType.LIMIT -> requireNotNull(command.priceJpy)
            OrderType.STOP -> applyPositiveSlippage(requireNotNull(command.priceJpy)).add(volatilitySlippageJpy(context))
        }.safetyScale()
    }

    /**
     * 対象 trade group の注文前 risk を返す。
     */
    fun groupRiskBeforeOrder(context: SafetyFloorContext, tradeGroupId: String?): BigDecimal {
        val positionRisk = context.positions
            .filterTargetPositions(tradeGroupId)
            .sumOf { position -> positionRisk(position, context) }
        val openOrderRisk = context.openOrders
            .filterTargetOrders(tradeGroupId)
            .filter { order -> order.side == OrderSide.BUY && order.status == OrderStatus.OPEN }
            .sumOf { order -> orderRisk(order, context) }

        return positionRisk.add(openOrderRisk).safetyScale()
    }

    /**
     * place_order command 単体の最大損失見積もりを返す。
     */
    fun orderRisk(command: PlaceOrderCommand, context: SafetyFloorContext): BigDecimal {
        val entryPrice = estimatedEntryPrice(command, context)
        val stopPrice = command.protectiveStopPriceJpy
        val priceRisk = entryPrice.subtract(stopPrice).maxZero().multiply(command.sizeBtc)
        val costReserve = roundTripCost(
            sizeBtc = command.sizeBtc,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            entryOrderType = command.orderType,
            context = context,
        )

        return priceRisk.add(costReserve).safetyScale()
    }

    /**
     * 現在の建玉と未約定買い注文の exposure を返す。
     */
    fun currentExposure(context: SafetyFloorContext): BigDecimal {
        val positionExposure = context.positions
            .filter { position -> position.status == PositionStatus.OPEN }
            .sumOf { position ->
                position.sizeBtc.toBigDecimal().multiply(position.currentPriceJpy.toBigDecimal())
            }
        val buyOrderExposure = context.openOrders
            .filter { order -> order.side == OrderSide.BUY && order.status == OrderStatus.OPEN }
            .sumOf { order -> orderExposure(order, context) }

        return positionExposure.add(buyOrderExposure).safetyScale()
    }

    /**
     * place_order command 単体の exposure を返す。
     */
    fun orderExposure(command: PlaceOrderCommand, context: SafetyFloorContext): BigDecimal {
        return estimatedEntryPrice(command, context).multiply(command.sizeBtc).safetyScale()
    }

    /**
     * 未約定買い注文の予約額を引いた利用可能 cash を返す。
     */
    fun availableCash(context: SafetyFloorContext): BigDecimal {
        val reservedCash = context.openOrders
            .filter { order -> order.side == OrderSide.BUY && order.status == OrderStatus.OPEN }
            .sumOf { order -> orderRequiredCash(order, context) }

        return context.account.cashJpy.toBigDecimal()
            .subtract(reservedCash)
            .safetyScale()
    }

    /**
     * place_order command に必要な cash 見積もりを返す。
     */
    fun orderRequiredCash(command: PlaceOrderCommand, context: SafetyFloorContext): BigDecimal {
        val entryPrice = estimatedEntryPrice(command, context)
        val notional = entryPrice.multiply(command.sizeBtc)
        val requiredCash = requiredCashFor(
            notional = notional,
            orderType = command.orderType,
            symbolRules = context.symbolRules,
        )

        return requiredCash.safetyScale()
    }

    /**
     * EV 計算に必要な入力が揃っている場合だけ途中結果を返す。
     */
    private fun expectedValueDetailsOrNull(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
    ): ExpectedValueDetails? {
        val takeProfitPrice = command.takeProfitPriceJpy ?: return null
        val probability = command.estimatedWinProbability
        val probabilityInRange = probability >= BigDecimal.ZERO && probability <= BigDecimal.ONE

        if (!probabilityInRange) {
            return null
        }

        return expectedValueDetails(command, context, takeProfitPrice)
    }

    /**
     * EV 計算の途中結果を返す。
     */
    fun expectedValueDetails(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        takeProfitPrice: BigDecimal,
    ): ExpectedValueDetails {
        val probability = command.estimatedWinProbability
        val cappedProbability = cappedProbabilityOrNull(probability, context)
        val probabilityForExpectedValue = cappedProbability ?: probability
        val entryPrice = estimatedEntryPrice(command, context)
        val riskAmount = entryRiskAmount(command.sizeBtc, entryPrice, command.protectiveStopPriceJpy)
        val expectedRMultiple = expectedRMultiple(command.sizeBtc, entryPrice, takeProfitPrice, riskAmount)
        val roundTripCostR = roundTripCost(
            sizeBtc = command.sizeBtc,
            entryPrice = entryPrice,
            stopPrice = command.protectiveStopPriceJpy,
            entryOrderType = command.orderType,
            context = context,
        ).divideOrZero(riskAmount)
        val expectedValueR = probabilityForExpectedValue
            .multiply(expectedRMultiple)
            .subtract(BigDecimal.ONE.subtract(probabilityForExpectedValue))
            .subtract(roundTripCostR)
            .safetyScale()

        return ExpectedValueDetails(
            expectedValueR = expectedValueR,
            probabilityUsed = probabilityForExpectedValue,
            probabilityCapApplied = cappedProbability != null,
        )
    }

    /**
     * 想定値幅 / 往復 cost 比を返す。
     */
    fun expectedMoveToCostRatioOrNull(command: PlaceOrderCommand, context: SafetyFloorContext): BigDecimal? {
        val takeProfitPrice = command.takeProfitPriceJpy ?: return null
        val entryPrice = estimatedEntryPrice(command, context)
        val expectedMove = takeProfitPrice.subtract(entryPrice).maxZero().multiply(command.sizeBtc)
        val roundTripCost = roundTripCost(
            sizeBtc = command.sizeBtc,
            entryPrice = entryPrice,
            stopPrice = command.protectiveStopPriceJpy,
            entryOrderType = command.orderType,
            context = context,
        )

        return expectedMove.divideOrZero(roundTripCost)
    }

    private fun estimatedEntryPrice(order: Order, context: SafetyFloorContext): BigDecimal {
        val askPrice = context.ticker.ask.toBigDecimal()

        return when (order.orderType) {
            OrderType.MARKET -> applyPositiveSlippage(askPrice).add(volatilitySlippageJpy(context))
            OrderType.LIMIT -> order.limitPriceJpy?.toBigDecimal() ?: askPrice
            // STOP の未約定買い予約は trigger 到達時の不利約定を見込む。
            OrderType.STOP -> {
                val triggerPrice = order.triggerPriceJpy?.toBigDecimal() ?: askPrice

                applyPositiveSlippage(triggerPrice).add(volatilitySlippageJpy(context))
            }
        }.safetyScale()
    }

    private fun positionRisk(position: Position, context: SafetyFloorContext): BigDecimal {
        val sizeBtc = position.sizeBtc.toBigDecimal()
        val entryPrice = position.averageEntryPriceJpy.toBigDecimal()
        val stopPrice = position.currentStopLossJpy?.toBigDecimal() ?: BigDecimal.ZERO
        val priceRisk = entryPrice.subtract(stopPrice).maxZero().multiply(sizeBtc)
        // Position は entry order type を保持しないため、既存 position の entry leg は taker 近似で保守的に評価する。
        val costReserve = roundTripCost(
            sizeBtc = sizeBtc,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            entryOrderType = OrderType.MARKET,
            context = context,
        )

        return priceRisk.add(costReserve).safetyScale()
    }

    private fun orderRisk(order: Order, context: SafetyFloorContext): BigDecimal {
        val entryPrice = estimatedEntryPrice(order, context)
        val stopPrice = order.protectiveStopPriceJpy?.toBigDecimal() ?: BigDecimal.ZERO
        val sizeBtc = order.sizeBtc.toBigDecimal()
        val priceRisk = entryPrice.subtract(stopPrice).maxZero().multiply(sizeBtc)
        val costReserve = roundTripCost(
            sizeBtc = sizeBtc,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            entryOrderType = order.orderType,
            context = context,
        )

        return priceRisk.add(costReserve).safetyScale()
    }

    private fun orderExposure(order: Order, context: SafetyFloorContext): BigDecimal {
        val price = estimatedEntryPrice(order, context)

        return price.multiply(order.sizeBtc.toBigDecimal()).safetyScale()
    }

    private fun orderRequiredCash(order: Order, context: SafetyFloorContext): BigDecimal {
        val price = estimatedEntryPrice(order, context)
        val notional = price.multiply(order.sizeBtc.toBigDecimal())
        val requiredCash = requiredCashFor(
            notional = notional,
            orderType = order.orderType,
            symbolRules = context.symbolRules,
        )

        return requiredCash.safetyScale()
    }

    private fun entryRiskAmount(
        sizeBtc: BigDecimal,
        entryPrice: BigDecimal,
        stopPrice: BigDecimal,
    ): BigDecimal {
        return entryPrice.subtract(stopPrice)
            .maxZero()
            .multiply(sizeBtc)
            .safetyScale()
    }

    private fun expectedRMultiple(
        sizeBtc: BigDecimal,
        entryPrice: BigDecimal,
        takeProfitPrice: BigDecimal,
        riskAmount: BigDecimal,
    ): BigDecimal {
        val expectedReward = takeProfitPrice.subtract(entryPrice)
            .maxZero()
            .multiply(sizeBtc)
            .safetyScale()

        return expectedReward.divideOrZero(riskAmount)
    }

    private fun roundTripCost(
        sizeBtc: BigDecimal,
        entryPrice: BigDecimal,
        stopPrice: BigDecimal,
        entryOrderType: OrderType,
        context: SafetyFloorContext,
    ): BigDecimal {
        val entryNotional = entryPrice.multiply(sizeBtc)
        val exitNotional = stopPrice.multiply(sizeBtc)
        val costReserve = roundTripCostReserveFor(
            entryNotional = entryNotional,
            exitNotional = exitNotional,
            entryOrderType = entryOrderType,
            symbolRules = context.symbolRules,
            slippageRatio = slippageRatio(),
        )
        val volatilityReserve = volatilitySlippageJpy(context)
            .multiply(sizeBtc)
            .multiply(ROUND_TRIP_VOLATILITY_RESERVE_MULTIPLIER)

        return costReserve.add(volatilityReserve).safetyScale()
    }

    private fun applyPositiveSlippage(price: BigDecimal): BigDecimal {
        return price.multiply(BigDecimal.ONE.add(slippageRatio())).safetyScale()
    }

    private fun volatilitySlippageJpy(context: SafetyFloorContext): BigDecimal {
        val atr14Jpy = context.atr14Jpy ?: return BigDecimal.ZERO

        return atr14Jpy
            .multiply(paperExecutionConfig.volatilitySlippageMultiplier)
            .moneyScale()
    }

    private fun slippageRatio(): BigDecimal {
        return config.marketSlippageReserveBps.divide(BPS_DIVISOR, SAFETY_SCALE, RoundingMode.HALF_UP)
    }

    private fun cappedProbabilityOrNull(probability: BigDecimal, context: SafetyFloorContext): BigDecimal? {
        val marketDataObservedAt = context.marketDataObservedAt
        val stale = if (marketDataObservedAt == null) {
            true
        } else {
            val staleDuration = Duration.between(marketDataObservedAt, Instant.now(clock))

            staleDuration > config.dataQualityCap.staleAfter
        }
        val capWouldReduceProbability = probability > config.dataQualityCap.cappedProbability

        if (!stale || !capWouldReduceProbability) {
            return null
        }

        return config.dataQualityCap.cappedProbability
    }
}

/**
 * EV 計算の途中結果。
 *
 * @param expectedValueR EV の R 倍
 * @param probabilityUsed EV 計算に使った勝率
 * @param probabilityCapApplied データ鮮度 cap が適用されたか
 */
internal data class ExpectedValueDetails(
    val expectedValueR: BigDecimal,
    val probabilityUsed: BigDecimal,
    val probabilityCapApplied: Boolean,
)

/**
 * entry と protective exit の 2 leg 分の volatility slippage reserve。
 */
private val ROUND_TRIP_VOLATILITY_RESERVE_MULTIPLIER = BigDecimal("2")
