package me.matsumo.fukurou.trading.reconciler

import me.matsumo.fukurou.trading.domain.Ticker

/**
 * TickSnapshot を paper execution 用 ticker に変換する。
 */
fun TickSnapshot.requireTicker(): Ticker {
    val referencePrice = firstAvailablePrice()
    val bid = bidPrice?.takeIf { price -> price.isNotBlank() } ?: referencePrice
    val ask = askPrice?.takeIf { price -> price.isNotBlank() } ?: referencePrice

    return Ticker(
        symbol = symbol,
        last = referencePrice,
        bid = bid,
        ask = ask,
        high = referencePrice,
        low = referencePrice,
        volume = "0",
        timestamp = observedAt.toString(),
    )
}

private fun TickSnapshot.firstAvailablePrice(): String {
    return listOf(lastPrice, bidPrice, askPrice)
        .filterNotNull()
        .firstOrNull { price -> price.isNotBlank() }
        ?: error("TickSnapshot must include lastPrice, bidPrice, or askPrice.")
}
