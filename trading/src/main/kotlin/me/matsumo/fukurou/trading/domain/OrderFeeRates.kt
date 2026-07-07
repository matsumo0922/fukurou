package me.matsumo.fukurou.trading.domain

import java.math.BigDecimal

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
