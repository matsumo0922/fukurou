package me.matsumo.fukurou.trading.reconciler

import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * ProtectionReconciler が取得した最新の公開市場気配値。
 *
 * @param bidPriceJpy 最良買気配
 * @param askPriceJpy 最良売気配
 * @param observedAt 取引所 ticker の観測時刻
 */
data class LatestMarketQuote(
    val bidPriceJpy: BigDecimal,
    val askPriceJpy: BigDecimal,
    val observedAt: Instant,
)

/** reconciler と read-only API が共有する thread-safe な最新気配値 store。 */
class LatestMarketQuoteStore {
    private val latest = AtomicReference<LatestMarketQuote?>()

    /** 有効な最新気配値を保存する。 */
    fun update(quote: LatestMarketQuote) {
        latest.set(quote)
    }

    /** 現在の最新気配値を返す。 */
    fun snapshot(): LatestMarketQuote? {
        return latest.get()
    }
}
