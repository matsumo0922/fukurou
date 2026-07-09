package me.matsumo.fukurou.trading.domain

import java.math.BigDecimal

/**
 * order fee / maker rebate の既定絶対値上限。
 */
internal val DEFAULT_MAX_ORDER_FEE_RATE: BigDecimal = BigDecimal("0.0010")

/**
 * entry 注文種別に対応する fee rate を返す。
 */
internal fun entryFeeRateFor(
    orderType: OrderType,
    symbolRules: SymbolRules,
    entryLiquidity: ExecutionLiquidity = defaultEntryLiquidityFor(orderType),
): BigDecimal {
    val takerFeeRate = symbolRules.takerFee.toBigDecimal()

    return when (entryLiquidity) {
        ExecutionLiquidity.MAKER -> symbolRules.makerFee.toBigDecimal()
        ExecutionLiquidity.TAKER -> takerFeeRate
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
    entryLiquidity: ExecutionLiquidity = defaultEntryLiquidityFor(orderType),
): BigDecimal {
    val fee = notional.multiply(
        entryFeeRateFor(
            orderType = orderType,
            symbolRules = symbolRules,
            entryLiquidity = entryLiquidity,
        ),
    )

    return maxOf(fee, BigDecimal.ZERO)
}

/**
 * 発注時に cash として予約すべき notional + fee を返す。
 */
internal fun requiredCashFor(
    notional: BigDecimal,
    orderType: OrderType,
    symbolRules: SymbolRules,
    entryLiquidity: ExecutionLiquidity = defaultEntryLiquidityFor(orderType),
): BigDecimal {
    return notional.add(
        cashFeeReserveFor(
            notional = notional,
            orderType = orderType,
            symbolRules = symbolRules,
            entryLiquidity = entryLiquidity,
        ),
    )
}

/**
 * 往復 cost reserve を返す。maker rebate と taker fee の合算が負になる場合も reserve は 0 未満にしない。
 * LIMIT entry は resting maker 前提で entry 側の fixed market slippage reserve を乗せず、保護 exit 側だけに残す。
 */
internal fun roundTripCostReserveFor(
    entryNotional: BigDecimal,
    exitNotional: BigDecimal,
    entryOrderType: OrderType,
    symbolRules: SymbolRules,
    slippageRatio: BigDecimal,
    entryLiquidity: ExecutionLiquidity = defaultEntryLiquidityFor(entryOrderType),
): BigDecimal {
    val entryFee = entryNotional.multiply(
        entryFeeRateFor(
            orderType = entryOrderType,
            symbolRules = symbolRules,
            entryLiquidity = entryLiquidity,
        ),
    )
    val exitFee = exitNotional.multiply(protectiveExitFeeRateFor(symbolRules))
    val feeReserve = entryFee.add(exitFee)
    val slippageReserve = roundTripSlippageNotional(
        entryNotional = entryNotional,
        exitNotional = exitNotional,
        entryLiquidity = entryLiquidity,
    ).multiply(slippageRatio)

    return feeReserve.add(slippageReserve).max(BigDecimal.ZERO)
}

private fun defaultEntryLiquidityFor(orderType: OrderType): ExecutionLiquidity {
    return when (orderType) {
        OrderType.LIMIT -> ExecutionLiquidity.MAKER
        OrderType.MARKET,
        OrderType.STOP,
        -> ExecutionLiquidity.TAKER
    }
}

private fun roundTripSlippageNotional(
    entryNotional: BigDecimal,
    exitNotional: BigDecimal,
    entryLiquidity: ExecutionLiquidity,
): BigDecimal {
    return when (entryLiquidity) {
        ExecutionLiquidity.MAKER -> exitNotional
        ExecutionLiquidity.TAKER -> entryNotional.add(exitNotional)
    }
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
