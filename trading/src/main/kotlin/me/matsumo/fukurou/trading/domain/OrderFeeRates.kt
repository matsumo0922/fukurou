package me.matsumo.fukurou.trading.domain

import java.math.BigDecimal

/**
 * order fee / maker rebate の既定絶対値上限。
 */
internal val DEFAULT_MAX_ORDER_FEE_RATE: BigDecimal = BigDecimal("0.0010")

/**
 * entry 注文種別に対応する fee rate を返す。
 */
internal fun entryFeeRateFor(orderType: OrderType, symbolRules: SymbolRules): BigDecimal {
    val takerFeeRate = symbolRules.takerFee.toBigDecimal()

    return when (orderType) {
        OrderType.LIMIT -> symbolRules.makerFee.toBigDecimal()
        OrderType.MARKET -> takerFeeRate
        OrderType.STOP -> takerFeeRate
    }
}

/**
 * 保護 STOP / market close 前提の exit fee rate を返す。
 */
internal fun protectiveExitFeeRateFor(symbolRules: SymbolRules): BigDecimal {
    return symbolRules.takerFee.toBigDecimal()
}

/**
 * 発注時に cash として予約すべき fee を返す。maker rebate では必要 cash を notional 未満へ下げない。
 */
internal fun cashFeeReserveFor(
    notional: BigDecimal,
    orderType: OrderType,
    symbolRules: SymbolRules,
): BigDecimal {
    val fee = notional.multiply(entryFeeRateFor(orderType, symbolRules))

    return maxOf(fee, BigDecimal.ZERO)
}

/**
 * 発注時に cash として予約すべき notional + fee を返す。
 */
internal fun requiredCashFor(
    notional: BigDecimal,
    orderType: OrderType,
    symbolRules: SymbolRules,
): BigDecimal {
    return notional.add(
        cashFeeReserveFor(
            notional = notional,
            orderType = orderType,
            symbolRules = symbolRules,
        ),
    )
}

/**
 * 往復 cost reserve を返す。maker rebate と taker fee の合算が負になる場合も reserve は 0 未満にしない。
 */
internal fun roundTripCostReserveFor(
    entryNotional: BigDecimal,
    exitNotional: BigDecimal,
    entryOrderType: OrderType,
    symbolRules: SymbolRules,
    slippageRatio: BigDecimal,
): BigDecimal {
    val entryFee = entryNotional.multiply(entryFeeRateFor(entryOrderType, symbolRules))
    val exitFee = exitNotional.multiply(protectiveExitFeeRateFor(symbolRules))
    val feeReserve = entryFee.add(exitFee)
    val slippageReserve = entryNotional.add(exitNotional).multiply(slippageRatio)

    return feeReserve.add(slippageReserve).max(BigDecimal.ZERO)
}

/**
 * order fee rate が SafetyFloor で扱える範囲なら null、危険な値なら理由を返す。
 */
internal fun unsafeOrderFeeRateReasonOrNull(
    symbolRules: SymbolRules,
    maxFeeRate: BigDecimal = DEFAULT_MAX_ORDER_FEE_RATE,
): String? {
    val takerFeeRate = symbolRules.takerFee.toBigDecimalOrNull()
        ?: return "takerFee must be a decimal number."
    val makerFeeRate = symbolRules.makerFee.toBigDecimalOrNull()
        ?: return "makerFee must be a decimal number."
    val minMakerFeeRate = maxFeeRate.negate()

    if (takerFeeRate <= BigDecimal.ZERO) {
        return "takerFee must be greater than 0."
    }
    if (takerFeeRate > maxFeeRate) {
        return "takerFee must be less than or equal to $maxFeeRate."
    }
    if (makerFeeRate < minMakerFeeRate) {
        return "makerFee must be greater than or equal to $minMakerFeeRate."
    }
    if (makerFeeRate > maxFeeRate) {
        return "makerFee must be less than or equal to $maxFeeRate."
    }

    return null
}
