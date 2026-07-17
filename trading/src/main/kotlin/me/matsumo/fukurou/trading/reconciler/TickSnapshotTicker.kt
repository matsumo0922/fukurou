package me.matsumo.fukurou.trading.reconciler

import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.market.FreshnessDefaults
import java.time.Clock
import java.time.Duration
import java.time.Instant

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
        timestamp = (sourceTimestamp ?: observedAt).toString(),
    )
}

/**
 * TickSnapshot を position close の execution authority として検証して ticker に変換する。
 *
 * REST ticker は source timestamp が必須で、現在時刻との差が既定の ticker 鮮度以内でなければ拒否する。
 * realtime market event は causal event 自体が execution authority なので、この REST freshness gate の対象外とする。
 */
fun TickSnapshot.requireExecutionTicker(clock: Clock): Ticker {
    if (source == TickSnapshotSource.GMO_PUBLIC_REST) {
        requireFreshRestSourceTimestamp(clock.instant())
    }

    return requireTicker()
}

private fun TickSnapshot.requireFreshRestSourceTimestamp(now: Instant) {
    val restSourceTimestamp = requireNotNull(sourceTimestamp) {
        "REST ticker source timestamp is missing or invalid."
    }
    val sourceAge = Duration.between(restSourceTimestamp, now)
    val futureSkew = Duration.between(now, restSourceTimestamp)

    require(sourceAge <= FreshnessDefaults.tickerStaleAfter) {
        "REST ticker source timestamp is stale."
    }
    require(futureSkew <= FreshnessDefaults.tickerMaxFutureSkew) {
        "REST ticker source timestamp is too far in the future."
    }
}

private fun TickSnapshot.firstAvailablePrice(): String {
    return listOfNotNull(lastPrice, bidPrice, askPrice)
        .firstOrNull { price -> price.isNotBlank() }
        ?: error("TickSnapshot must include lastPrice, bidPrice, or askPrice.")
}
