package me.matsumo.fukurou.trading.decision.identity

import me.matsumo.fukurou.trading.decision.TradePlanInvalidationState
import java.math.BigDecimal
import java.math.RoundingMode

/** decision identity と resting maintenance が共有する bounded material projection。 */
data class DecisionMaterialProjection(
    val riskState: String,
    val freshness: MaterialFreshness,
    val hasOpenPosition: Boolean,
    val hasOpenOrder: Boolean,
    val anchorPriceJpy: BigDecimal?,
    val currentPriceJpy: BigDecimal?,
    val atr14Jpy: BigDecimal?,
    val bestBidJpy: BigDecimal?,
    val bestAskJpy: BigDecimal?,
    val invalidationState: TradePlanInvalidationState,
) {
    /** identity hash の唯一の canonical input を返す。 */
    fun canonical(thresholdRatio: BigDecimal): String {
        return listOf(
            "risk=$riskState",
            "freshness=${freshness.name}",
            "openPosition=$hasOpenPosition",
            "openOrder=$hasOpenOrder",
            "priceMoveBand=${relativeBand(anchorPriceJpy, currentPriceJpy, thresholdRatio)}",
            "atrPriceBand=${ratioBand(atr14Jpy, currentPriceJpy, thresholdRatio)}",
            "spreadBand=${ratioBand(bestAskJpy?.subtract(bestBidJpy ?: bestAskJpy), currentPriceJpy, thresholdRatio)}",
            "invalidation=${invalidationState.name}",
        ).joinToString("\n")
    }
}

private fun relativeBand(
    anchor: BigDecimal?,
    current: BigDecimal?,
    threshold: BigDecimal,
): String {
    val reference = anchor?.takeUnless { it.signum() == 0 } ?: return "UNKNOWN"
    val value = current ?: return "UNKNOWN"
    val move = value.subtract(reference).divide(reference.abs(), 12, RoundingMode.HALF_UP)
    return move.divide(threshold, 0, RoundingMode.FLOOR).toPlainString()
}

private fun ratioBand(
    numerator: BigDecimal?,
    denominator: BigDecimal?,
    threshold: BigDecimal,
): String {
    val reference = denominator?.takeUnless { it.signum() == 0 } ?: return "UNKNOWN"
    val value = numerator ?: return "UNKNOWN"
    val ratio = value.abs().divide(reference.abs(), 12, RoundingMode.HALF_UP)
    return ratio.divide(threshold, 0, RoundingMode.FLOOR).toPlainString()
}
