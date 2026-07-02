package me.matsumo.fukurou.trading.safety

import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.risk.RiskState
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * override 不可の安全床ルール。
 */
enum class SafetyFloorRule {
    /**
     * 1 trade group の最大損失が equity の 2% を超えた。
     */
    MAX_RISK_PER_TRADE,

    /**
     * long entry に保護 STOP がない、または entry より下にない。
     */
    STOP_LOSS_REQUIRED,

    /**
     * 含み損の long position に買い増そうとした。
     */
    NO_AVERAGING_DOWN,

    /**
     * equity peak からの drawdown が HARD_HALT 閾値へ到達した。
     */
    MAX_DRAWDOWN_HALT,

    /**
     * 合計 BTC exposure が equity の 80% を超えた。
     */
    MAX_TOTAL_EXPOSURE,

    /**
     * 残高、取引所 rule、または cost 上限に違反した。
     */
    BALANCE_RATE_AND_COST_LIMIT,

    /**
     * EV 計算に必要な目標価格がない。
     */
    MISSING_TARGET_PRICE,

    /**
     * コード計算した EV が 0 以下だった。
     */
    NON_POSITIVE_EXPECTED_VALUE,

    /**
     * 推定勝率 p が確率の範囲外だった。
     */
    INVALID_WIN_PROBABILITY,

    /**
     * コード計算した EV が保守的な正の最小値を満たさなかった。
     */
    EXPECTED_VALUE_GATE,

    /**
     * 想定値幅が往復 cost に対して不足した。
     */
    EXPECTED_MOVE_TO_COST_RATIO,

    /**
     * STOP を損失拡大方向へ緩めようとした。
     */
    STOP_LOSS_LOOSENING,

    /**
     * STOP を ATR trailing 床より下へ置こうとした。
     */
    ATR_TRAILING_FLOOR,

    /**
     * STOP が即時約定方向の価格だった。
     */
    IMMEDIATE_STOP_TRIGGER,
}

/**
 * SafetyFloor の保守的な初期しきい値。
 *
 * @param maxRiskPerTradeRatio 1 trade group の最大損失割合
 * @param maxDrawdownRatio HARD_HALT を立てる drawdown
 * @param maxTotalExposureRatio 合計 exposure 上限
 * @param minExpectedValueR コード計算した entry plan に求める最小 EV
 * @param minExpectedMoveToCostRatio 想定値幅と往復 cost の最小比率
 * @param maxTakerFeeRatio paper で許容する taker fee 上限
 * @param marketSlippageReserveBps MARKET / STOP の片道 slippage reserve
 */
data class SafetyFloorConfig(
    val maxRiskPerTradeRatio: BigDecimal = DEFAULT_MAX_RISK_PER_TRADE_RATIO,
    val maxDrawdownRatio: BigDecimal = SafetyFloorDefaults.maxDrawdownRatio,
    val maxTotalExposureRatio: BigDecimal = DEFAULT_MAX_TOTAL_EXPOSURE_RATIO,
    val minExpectedValueR: BigDecimal = DEFAULT_MIN_EXPECTED_VALUE_R,
    val minExpectedMoveToCostRatio: BigDecimal = DEFAULT_MIN_EXPECTED_MOVE_TO_COST_RATIO,
    val maxTakerFeeRatio: BigDecimal = DEFAULT_MAX_TAKER_FEE_RATIO,
    val marketSlippageReserveBps: BigDecimal = DEFAULT_MARKET_SLIPPAGE_RESERVE_BPS,
) {
    init {
        val maxRiskPerTradeIsPositive = maxRiskPerTradeRatio > BigDecimal.ZERO
        val maxRiskPerTradeIsAtOrBelowDefault = maxRiskPerTradeRatio <= DEFAULT_MAX_RISK_PER_TRADE_RATIO
        val maxRiskPerTradeIsConservative = maxRiskPerTradeIsPositive && maxRiskPerTradeIsAtOrBelowDefault
        val maxDrawdownIsAtOrAboveDefault = maxDrawdownRatio >= SafetyFloorDefaults.maxDrawdownRatio
        val maxDrawdownIsNegative = maxDrawdownRatio < BigDecimal.ZERO
        val maxDrawdownIsConservative = maxDrawdownIsAtOrAboveDefault && maxDrawdownIsNegative
        val maxTotalExposureIsPositive = maxTotalExposureRatio > BigDecimal.ZERO
        val maxTotalExposureIsAtOrBelowDefault = maxTotalExposureRatio <= DEFAULT_MAX_TOTAL_EXPOSURE_RATIO
        val maxTotalExposureIsConservative = maxTotalExposureIsPositive && maxTotalExposureIsAtOrBelowDefault
        val maxTakerFeeIsNonNegative = maxTakerFeeRatio >= BigDecimal.ZERO
        val maxTakerFeeIsAtOrBelowDefault = maxTakerFeeRatio <= DEFAULT_MAX_TAKER_FEE_RATIO
        val maxTakerFeeIsConservative = maxTakerFeeIsNonNegative && maxTakerFeeIsAtOrBelowDefault

        require(maxRiskPerTradeIsConservative) {
            "maxRiskPerTradeRatio must be greater than 0 and less than or equal to 0.02."
        }
        require(maxDrawdownIsConservative) {
            "maxDrawdownRatio must be greater than or equal to -0.15 and less than 0."
        }
        require(maxTotalExposureIsConservative) {
            "maxTotalExposureRatio must be greater than 0 and less than or equal to 0.80."
        }
        require(minExpectedValueR >= DEFAULT_MIN_EXPECTED_VALUE_R) {
            "minExpectedValueR must be greater than or equal to 0.10."
        }
        require(minExpectedMoveToCostRatio >= DEFAULT_MIN_EXPECTED_MOVE_TO_COST_RATIO) {
            "minExpectedMoveToCostRatio must be greater than or equal to 3.0."
        }
        require(maxTakerFeeIsConservative) {
            "maxTakerFeeRatio must be greater than or equal to 0 and less than or equal to 0.0010."
        }
        require(marketSlippageReserveBps >= DEFAULT_MARKET_SLIPPAGE_RESERVE_BPS) {
            "marketSlippageReserveBps must be greater than or equal to 5."
        }
    }
}

/**
 * SafetyFloor 検証に必要な最新状態。
 *
 * @param account paper account snapshot
 * @param riskState DB risk_state snapshot
 * @param positions open position 一覧
 * @param openOrders open order 一覧
 * @param ticker 最新 ticker
 * @param symbolRules 取引所 symbol rule
 * @param atr14Jpy 5分足 ATR(14)
 */
data class SafetyFloorContext(
    val account: AccountSnapshot,
    val riskState: RiskState,
    val positions: List<Position>,
    val openOrders: List<Order>,
    val ticker: Ticker,
    val symbolRules: SymbolRules,
    val atr14Jpy: BigDecimal? = null,
)

/**
 * SafetyFloor の検証結果。
 */
sealed interface SafetyFloorVerdict {
    /**
     * 安全床を通過した。
     */
    data object Accepted : SafetyFloorVerdict

    /**
     * 安全床により拒否された。
     *
     * @param violation 拒否内容
     */
    data class Rejected(
        val violation: SafetyViolation,
    ) : SafetyFloorVerdict
}

/**
 * SafetyFloor 拒否の監査内容。
 *
 * @param id violation ID
 * @param rule 違反した rule
 * @param messageJa 呼び出し元向け日本語 message
 * @param measuredValue 実測または申告された値
 * @param limitValue 安全床の上限または下限
 * @param commandName tool / command 名
 * @param commandId command ID
 * @param orderId 関連 order ID
 * @param decisionRunId decision run ID
 * @param toolCallId tool call ID
 * @param clientRequestId client request ID
 * @param hardHaltRequired HARD_HALT と掃引が必要か
 * @param payloadJson 監査用 JSON payload
 * @param createdAt 作成時刻
 */
data class SafetyViolation(
    val id: UUID = UUID.randomUUID(),
    val rule: SafetyFloorRule,
    val messageJa: String,
    val measuredValue: String,
    val limitValue: String,
    val commandName: String,
    val commandId: UUID?,
    val orderId: UUID?,
    val decisionRunId: String?,
    val toolCallId: String?,
    val clientRequestId: String?,
    val hardHaltRequired: Boolean,
    val payloadJson: String,
    val createdAt: Instant,
)

/**
 * Broker 副作用の手前で強制する安全床。
 *
 * @param config 安全床しきい値
 * @param clock violation timestamp に使う clock
 */
class SafetyFloor(
    private val config: SafetyFloorConfig = SafetyFloorConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * place_order の entry intent を検証する。
     */
    fun evaluatePlaceOrder(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        detectHardHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateStopLoss(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateNoAveragingDown(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateGroupRisk(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateTotalExposure(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateBalanceAndCost(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateExpectedValue(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }
        validateExpectedMoveToCost(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }

        return SafetyFloorVerdict.Accepted
    }

    /**
     * update_protection の STOP 更新だけを硬く検証する。TP は soft 領域なので制限しない。
     */
    fun evaluateUpdateProtection(command: UpdateProtectionCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        detectHardHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }

        val newStopPrice = command.newStopPriceJpy ?: return SafetyFloorVerdict.Accepted
        val position = context.positions.firstOrNull { position ->
            position.positionId == command.positionId.toString()
        }
        if (position == null) {
            return SafetyFloorVerdict.Accepted
        }

        validateStopTightening(command, position, newStopPrice)?.let { violation ->
            return SafetyFloorVerdict.Rejected(violation)
        }
        validateTrailingFloor(command, position, newStopPrice, context)?.let { violation ->
            return SafetyFloorVerdict.Rejected(violation)
        }
        validateImmediateStop(command, context, newStopPrice)?.let { violation ->
            return SafetyFloorVerdict.Rejected(violation)
        }

        return SafetyFloorVerdict.Accepted
    }

    /**
     * close_position の実行前に HARD_HALT 到達だけを検証する。
     */
    fun evaluateClosePosition(command: ClosePositionCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        detectHardHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }

        return SafetyFloorVerdict.Accepted
    }

    /**
     * cancel_order の実行前に HARD_HALT 到達だけを検証する。
     */
    fun evaluateCancelOrder(command: CancelOrderCommand, context: SafetyFloorContext): SafetyFloorVerdict {
        detectHardHalt(command, context)?.let { violation -> return SafetyFloorVerdict.Rejected(violation) }

        return SafetyFloorVerdict.Accepted
    }

    private fun detectHardHalt(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        return detectHardHalt(commandName = "place_order", command = command, context = context)
    }

    private fun detectHardHalt(command: UpdateProtectionCommand, context: SafetyFloorContext): SafetyViolation? {
        return detectHardHalt(commandName = "update_protection", command = command, context = context)
    }

    private fun detectHardHalt(command: ClosePositionCommand, context: SafetyFloorContext): SafetyViolation? {
        return detectHardHalt(commandName = "close_position", command = command, context = context)
    }

    private fun detectHardHalt(command: CancelOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        return detectHardHalt(commandName = "cancel_order", command = command, context = context)
    }

    private fun detectHardHalt(commandName: String, command: Any, context: SafetyFloorContext): SafetyViolation? {
        val accountDrawdown = context.account.drawdownRatio.toBigDecimal()
        val riskStateDrawdown = context.riskState.drawdownRatio
        val measuredDrawdown = minOf(accountDrawdown, riskStateDrawdown)
        val hardHaltReached = context.riskState.hardHalt || measuredDrawdown <= config.maxDrawdownRatio

        if (!hardHaltReached) {
            return null
        }

        return violation(
            commandName = commandName,
            command = command,
            rule = SafetyFloorRule.MAX_DRAWDOWN_HALT,
            messageJa = "最大 DD が HARD_HALT 閾値に到達したため、全注文取消と全建玉 close を実行して取引を停止します。",
            measuredValue = measuredDrawdown.toPlainString(),
            limitValue = config.maxDrawdownRatio.toPlainString(),
            hardHaltRequired = true,
        )
    }

    private fun validateStopLoss(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val entryPrice = estimatedEntryPrice(command, context)
        val stopPrice = command.protectiveStopPriceJpy
        val stopLossValid = stopPrice > BigDecimal.ZERO && stopPrice < entryPrice

        if (stopLossValid) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            rule = SafetyFloorRule.STOP_LOSS_REQUIRED,
            messageJa = "protective_stop_price_jpy は long entry の想定 entry 価格より下に必須です。",
            measuredValue = stopPrice.toPlainString(),
            limitValue = "entry_price_below_${entryPrice.toPlainString()}",
        )
    }

    private fun validateNoAveragingDown(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val targetTradeGroupId = command.tradeGroupId?.toString()
        val targetPositions = context.positions.filterTargetPositions(targetTradeGroupId)
        val losingPosition = targetPositions.firstOrNull { position -> position.isLosingLong() }

        if (losingPosition == null) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            rule = SafetyFloorRule.NO_AVERAGING_DOWN,
            messageJa = "含み損の BTC long に対する買い増しはナンピンとして拒否します。",
            measuredValue = losingPosition.unrealizedPnlJpy,
            limitValue = ">=0",
        )
    }

    private fun validateGroupRisk(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val targetTradeGroupId = command.tradeGroupId?.toString()
        val groupRisk = groupRiskBeforeOrder(context, targetTradeGroupId)
            .add(orderRisk(command, context))
            .safetyScale()
        val limit = context.account.totalEquityJpy.toBigDecimal()
            .multiply(config.maxRiskPerTradeRatio)
            .safetyScale()

        if (groupRisk <= limit) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            rule = SafetyFloorRule.MAX_RISK_PER_TRADE,
            messageJa = "trade group の最大損失見積もりが equity の 2% を超えています。",
            measuredValue = groupRisk.toPlainString(),
            limitValue = limit.toPlainString(),
        )
    }

    private fun validateTotalExposure(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val exposureAfterOrder = currentExposure(context)
            .add(orderExposure(command, context))
            .safetyScale()
        val limit = context.account.totalEquityJpy.toBigDecimal()
            .multiply(config.maxTotalExposureRatio)
            .safetyScale()

        if (exposureAfterOrder <= limit) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            rule = SafetyFloorRule.MAX_TOTAL_EXPOSURE,
            messageJa = "合計 exposure が equity の 80% 上限を超えています。",
            measuredValue = exposureAfterOrder.toPlainString(),
            limitValue = limit.toPlainString(),
        )
    }

    private fun validateBalanceAndCost(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val takerFee = context.symbolRules.takerFee.toBigDecimal().abs()
        if (takerFee > config.maxTakerFeeRatio) {
            return violation(
                commandName = "place_order",
                command = command,
                rule = SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT,
                messageJa = "taker fee が SafetyFloor の cost 上限を超えています。",
                measuredValue = takerFee.toPlainString(),
                limitValue = config.maxTakerFeeRatio.toPlainString(),
            )
        }

        val availableCash = availableCash(context)
        val requiredCash = orderRequiredCash(command, context)
        if (requiredCash <= availableCash) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            rule = SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT,
            messageJa = "paper JPY 残高と未約定買い予約を超える発注は拒否します。",
            measuredValue = requiredCash.toPlainString(),
            limitValue = availableCash.toPlainString(),
        )
    }

    private fun validateExpectedValue(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val probability = command.estimatedWinProbability
        val probabilityInRange = probability >= BigDecimal.ZERO && probability <= BigDecimal.ONE

        if (!probabilityInRange) {
            return violation(
                commandName = "place_order",
                command = command,
                rule = SafetyFloorRule.INVALID_WIN_PROBABILITY,
                messageJa = "estimated_win_probability は 0 以上 1 以下である必要があります。",
                measuredValue = probability.toPlainString(),
                limitValue = "0..1",
            )
        }

        val takeProfitPrice = command.takeProfitPriceJpy
            ?: return violation(
                commandName = "place_order",
                command = command,
                rule = SafetyFloorRule.MISSING_TARGET_PRICE,
                messageJa = "take_profit_price_jpy がないため、EV を計算できない entry は拒否します。",
                measuredValue = "null",
                limitValue = "required",
            )
        val entryPrice = estimatedEntryPrice(command, context)
        val riskAmount = entryRiskAmount(command.sizeBtc, entryPrice, command.protectiveStopPriceJpy)
        val expectedRMultiple = expectedRMultiple(command.sizeBtc, entryPrice, takeProfitPrice, riskAmount)
        val roundTripCostR = roundTripCost(
            sizeBtc = command.sizeBtc,
            entryPrice = entryPrice,
            stopPrice = command.protectiveStopPriceJpy,
            context = context,
        ).divideOrZero(riskAmount)
        val expectedValueR = probability
            .multiply(expectedRMultiple)
            .subtract(BigDecimal.ONE.subtract(probability))
            .subtract(roundTripCostR)
            .safetyScale()

        if (expectedValueR <= BigDecimal.ZERO) {
            return violation(
                commandName = "place_order",
                command = command,
                rule = SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE,
                messageJa = "推定勝率、目標価格、STOP、往復 cost から計算した EV が 0 以下です。",
                measuredValue = expectedValueR.toPlainString(),
                limitValue = ">0",
            )
        }

        if (expectedValueR >= config.minExpectedValueR) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            rule = SafetyFloorRule.EXPECTED_VALUE_GATE,
            messageJa = "推定勝率、目標価格、STOP、往復 cost から計算した EV が保守的な初期しきい値を下回っています。",
            measuredValue = expectedValueR.toPlainString(),
            limitValue = config.minExpectedValueR.toPlainString(),
        )
    }

    private fun validateExpectedMoveToCost(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyViolation? {
        val takeProfitPrice = command.takeProfitPriceJpy ?: return null
        val entryPrice = estimatedEntryPrice(command, context)
        val expectedMove = takeProfitPrice.subtract(entryPrice).maxZero().multiply(command.sizeBtc)
        val roundTripCost = roundTripCost(command.sizeBtc, entryPrice, command.protectiveStopPriceJpy, context)
        val impliedRatio = expectedMove.divideOrZero(roundTripCost)

        if (impliedRatio >= config.minExpectedMoveToCostRatio) {
            return null
        }

        return violation(
            commandName = "place_order",
            command = command,
            rule = SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO,
            messageJa = "TP から逆算した想定値幅 / 往復 cost 比が不足しています。",
            measuredValue = impliedRatio.toPlainString(),
            limitValue = config.minExpectedMoveToCostRatio.toPlainString(),
        )
    }

    private fun validateStopTightening(
        command: UpdateProtectionCommand,
        position: Position,
        newStopPrice: BigDecimal,
    ): SafetyViolation? {
        val currentStop = position.currentStopLossJpy?.toBigDecimal() ?: return null

        if (newStopPrice >= currentStop) {
            return null
        }

        return violation(
            commandName = "update_protection",
            command = command,
            rule = SafetyFloorRule.STOP_LOSS_LOOSENING,
            messageJa = "STOP を損失拡大方向へ緩める update_protection は拒否します。",
            measuredValue = newStopPrice.toPlainString(),
            limitValue = ">=${currentStop.toPlainString()}",
        )
    }

    private fun validateTrailingFloor(
        command: UpdateProtectionCommand,
        position: Position,
        newStopPrice: BigDecimal,
        context: SafetyFloorContext,
    ): SafetyViolation? {
        val atrValue = context.atr14Jpy ?: return null
        val atrFloor = position.highestPriceSinceEntryJpy.toBigDecimal()
            .subtract(atrValue.multiply(SafetyFloorDefaults.trailingAtrMultiplier))
            .floorToStep(context.symbolRules.tickSize.toBigDecimal())

        if (newStopPrice >= atrFloor) {
            return null
        }

        return violation(
            commandName = "update_protection",
            command = command,
            rule = SafetyFloorRule.ATR_TRAILING_FLOOR,
            messageJa = "STOP を ATR trailing 床より下へ置く update_protection は拒否します。",
            measuredValue = newStopPrice.toPlainString(),
            limitValue = ">=${atrFloor.toPlainString()}",
        )
    }

    private fun validateImmediateStop(
        command: UpdateProtectionCommand,
        context: SafetyFloorContext,
        newStopPrice: BigDecimal,
    ): SafetyViolation? {
        val bidPrice = context.ticker.bid.toBigDecimal()

        if (newStopPrice < bidPrice) {
            return null
        }

        return violation(
            commandName = "update_protection",
            command = command,
            rule = SafetyFloorRule.IMMEDIATE_STOP_TRIGGER,
            messageJa = "SELL STOP が即時約定方向の価格なので拒否します。",
            measuredValue = newStopPrice.toPlainString(),
            limitValue = "<${bidPrice.toPlainString()}",
        )
    }

    private fun violation(
        commandName: String,
        command: Any,
        rule: SafetyFloorRule,
        messageJa: String,
        measuredValue: String,
        limitValue: String,
        hardHaltRequired: Boolean = false,
    ): SafetyViolation {
        val auditContext = command.auditContextOrNull()

        return SafetyViolation(
            rule = rule,
            messageJa = messageJa,
            measuredValue = measuredValue,
            limitValue = limitValue,
            commandName = commandName,
            commandId = command.commandIdOrNull(),
            orderId = command.orderIdOrNull(),
            decisionRunId = auditContext?.decisionRunContext?.decisionRunId,
            toolCallId = auditContext?.toolCallId,
            clientRequestId = auditContext?.clientRequestId,
            hardHaltRequired = hardHaltRequired,
            payloadJson = violationPayload(rule, measuredValue, limitValue, hardHaltRequired),
            createdAt = Instant.now(clock),
        )
    }

    private fun estimatedEntryPrice(command: PlaceOrderCommand, context: SafetyFloorContext): BigDecimal {
        val askPrice = context.ticker.ask.toBigDecimal()

        return when (command.orderType) {
            OrderType.MARKET -> applyPositiveSlippage(askPrice)
            OrderType.LIMIT -> requireNotNull(command.priceJpy)
            OrderType.STOP -> applyPositiveSlippage(requireNotNull(command.priceJpy))
        }.safetyScale()
    }

    private fun groupRiskBeforeOrder(context: SafetyFloorContext, tradeGroupId: String?): BigDecimal {
        val positionRisk = context.positions
            .filterTargetPositions(tradeGroupId)
            .sumOf { position -> positionRisk(position, context) }
        val openOrderRisk = context.openOrders
            .filterTargetOrders(tradeGroupId)
            .filter { order -> order.side == OrderSide.BUY && order.status == OrderStatus.OPEN }
            .sumOf { order -> orderRisk(order, context) }

        return positionRisk.add(openOrderRisk).safetyScale()
    }

    private fun positionRisk(position: Position, context: SafetyFloorContext): BigDecimal {
        val sizeBtc = position.sizeBtc.toBigDecimal()
        val entryPrice = position.averageEntryPriceJpy.toBigDecimal()
        val stopPrice = position.currentStopLossJpy?.toBigDecimal() ?: BigDecimal.ZERO
        val priceRisk = entryPrice.subtract(stopPrice).maxZero().multiply(sizeBtc)
        val costReserve = roundTripCost(sizeBtc, entryPrice, stopPrice, context)

        return priceRisk.add(costReserve).safetyScale()
    }

    private fun orderRisk(command: PlaceOrderCommand, context: SafetyFloorContext): BigDecimal {
        val entryPrice = estimatedEntryPrice(command, context)
        val stopPrice = command.protectiveStopPriceJpy
        val priceRisk = entryPrice.subtract(stopPrice).maxZero().multiply(command.sizeBtc)
        val costReserve = roundTripCost(command.sizeBtc, entryPrice, stopPrice, context)

        return priceRisk.add(costReserve).safetyScale()
    }

    private fun orderRisk(order: Order, context: SafetyFloorContext): BigDecimal {
        val entryPrice = order.limitPriceJpy
            ?.toBigDecimal()
            ?: order.triggerPriceJpy?.toBigDecimal()
            ?: context.ticker.ask.toBigDecimal()
        val stopPrice = order.protectiveStopPriceJpy?.toBigDecimal() ?: BigDecimal.ZERO
        val sizeBtc = order.sizeBtc.toBigDecimal()
        val priceRisk = entryPrice.subtract(stopPrice).maxZero().multiply(sizeBtc)
        val costReserve = roundTripCost(sizeBtc, entryPrice, stopPrice, context)

        return priceRisk.add(costReserve).safetyScale()
    }

    private fun currentExposure(context: SafetyFloorContext): BigDecimal {
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

    private fun orderExposure(command: PlaceOrderCommand, context: SafetyFloorContext): BigDecimal {
        return estimatedEntryPrice(command, context).multiply(command.sizeBtc).safetyScale()
    }

    private fun orderExposure(order: Order, context: SafetyFloorContext): BigDecimal {
        val price = order.limitPriceJpy
            ?.toBigDecimal()
            ?: order.triggerPriceJpy?.toBigDecimal()
            ?: context.ticker.ask.toBigDecimal()

        return price.multiply(order.sizeBtc.toBigDecimal()).safetyScale()
    }

    private fun availableCash(context: SafetyFloorContext): BigDecimal {
        val reservedCash = context.openOrders
            .filter { order -> order.side == OrderSide.BUY && order.status == OrderStatus.OPEN }
            .sumOf { order -> orderRequiredCash(order, context) }

        return context.account.cashJpy.toBigDecimal()
            .subtract(reservedCash)
            .safetyScale()
    }

    private fun orderRequiredCash(command: PlaceOrderCommand, context: SafetyFloorContext): BigDecimal {
        val entryPrice = estimatedEntryPrice(command, context)
        val notional = entryPrice.multiply(command.sizeBtc)
        val fee = notional.multiply(context.symbolRules.takerFee.toBigDecimal().abs())

        return notional.add(fee).safetyScale()
    }

    private fun orderRequiredCash(order: Order, context: SafetyFloorContext): BigDecimal {
        val price = order.limitPriceJpy
            ?.toBigDecimal()
            ?: order.triggerPriceJpy?.toBigDecimal()
            ?: context.ticker.ask.toBigDecimal()
        val notional = price.multiply(order.sizeBtc.toBigDecimal())
        val fee = notional.multiply(context.symbolRules.takerFee.toBigDecimal().abs())

        return notional.add(fee).safetyScale()
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
        context: SafetyFloorContext,
    ): BigDecimal {
        val entryNotional = entryPrice.multiply(sizeBtc)
        val exitNotional = stopPrice.multiply(sizeBtc)
        val takerFee = context.symbolRules.takerFee.toBigDecimal().abs()
        val feeReserve = entryNotional.add(exitNotional).multiply(takerFee)
        val slippageReserve = entryNotional.add(exitNotional).multiply(slippageRatio())

        return feeReserve.add(slippageReserve).safetyScale()
    }

    private fun applyPositiveSlippage(price: BigDecimal): BigDecimal {
        return price.multiply(BigDecimal.ONE.add(slippageRatio())).safetyScale()
    }

    private fun slippageRatio(): BigDecimal {
        return config.marketSlippageReserveBps.divide(BPS_DIVISOR, SAFETY_SCALE, RoundingMode.HALF_UP)
    }
}

private fun List<Position>.filterTargetPositions(tradeGroupId: String?): List<Position> {
    if (tradeGroupId != null) {
        return filter { position -> position.tradeGroupId == tradeGroupId && position.status == PositionStatus.OPEN }
    }

    return filter { position -> position.status == PositionStatus.OPEN }
}

private fun List<Order>.filterTargetOrders(tradeGroupId: String?): List<Order> {
    if (tradeGroupId != null) {
        return filter { order -> order.tradeGroupId == tradeGroupId }
    }

    return this
}

private fun Position.isLosingLong(): Boolean {
    val unrealizedLoss = unrealizedPnlJpy.toBigDecimal() < BigDecimal.ZERO
    val belowEntry = currentPriceJpy.toBigDecimal() < averageEntryPriceJpy.toBigDecimal()

    return unrealizedLoss || belowEntry
}

private fun BigDecimal.maxZero(): BigDecimal {
    return maxOf(this, BigDecimal.ZERO)
}

private fun BigDecimal.divideOrZero(divisor: BigDecimal): BigDecimal {
    if (divisor.compareTo(BigDecimal.ZERO) == 0) {
        return BigDecimal.ZERO.safetyScale()
    }

    return divide(divisor, SAFETY_SCALE, RoundingMode.HALF_UP).safetyScale()
}

private fun BigDecimal.safetyScale(): BigDecimal {
    return setScale(SAFETY_SCALE, RoundingMode.HALF_UP)
}

private fun BigDecimal.floorToStep(step: BigDecimal): BigDecimal {
    if (step.compareTo(BigDecimal.ZERO) == 0) {
        return this
    }

    return divide(step, 0, RoundingMode.DOWN)
        .multiply(step)
        .safetyScale()
}

private fun Any.auditContextOrNull() = when (this) {
    is PlaceOrderCommand -> auditContext
    is UpdateProtectionCommand -> auditContext
    is ClosePositionCommand -> auditContext
    is CancelOrderCommand -> auditContext
    else -> null
}

private fun Any.commandIdOrNull(): UUID? = when (this) {
    is PlaceOrderCommand -> commandId
    is UpdateProtectionCommand -> commandId
    is ClosePositionCommand -> commandId
    is CancelOrderCommand -> commandId
    else -> null
}

private fun Any.orderIdOrNull(): UUID? = when (this) {
    is CancelOrderCommand -> orderId
    else -> null
}

private fun violationPayload(
    rule: SafetyFloorRule,
    measuredValue: String,
    limitValue: String,
    hardHaltRequired: Boolean,
): String {
    return """
        {
          "rule": "${rule.name}",
          "measuredValue": "$measuredValue",
          "limitValue": "$limitValue",
          "hardHaltRequired": $hardHaltRequired
        }
    """.trimIndent()
}

/**
 * SafetyFloor 計算 scale。
 */
private const val SAFETY_SCALE = 8

/**
 * bps 分母。
 */
private val BPS_DIVISOR = BigDecimal("10000")

/**
 * 1 trade group の既定最大損失割合。
 */
private val DEFAULT_MAX_RISK_PER_TRADE_RATIO = BigDecimal("0.02")

/**
 * 既定 exposure 上限。
 */
private val DEFAULT_MAX_TOTAL_EXPOSURE_RATIO = BigDecimal("0.80")

/**
 * entry plan の既定最小 EV。
 */
private val DEFAULT_MIN_EXPECTED_VALUE_R = BigDecimal("0.10")

/**
 * 想定値幅 / 往復 cost の既定最小比率。
 */
private val DEFAULT_MIN_EXPECTED_MOVE_TO_COST_RATIO = BigDecimal("3.0")

/**
 * 既定 taker fee 上限。
 */
private val DEFAULT_MAX_TAKER_FEE_RATIO = BigDecimal("0.0010")

/**
 * 片道 slippage reserve。
 */
private val DEFAULT_MARKET_SLIPPAGE_RESERVE_BPS = BigDecimal("5")
