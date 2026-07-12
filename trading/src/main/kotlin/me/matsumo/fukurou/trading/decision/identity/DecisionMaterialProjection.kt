package me.matsumo.fukurou.trading.decision.identity

import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationState
import me.matsumo.fukurou.trading.decision.evaluateInvalidationPredicates
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

/** exact manifest を thesis 確定後の episode context で一度だけ canonicalize する。 */
fun DecisionMaterialStateManifest.canonicalProjection(
    anchorPriceJpy: BigDecimal?,
    thresholdRatio: BigDecimal,
    predicates: List<TradePlanInvalidationPredicate>,
    observedAt: java.time.Instant,
): String {
    val invalidation = evaluateInvalidationPredicates(
        predicates = predicates,
        lastPriceJpy = lastPriceJpy,
        bestBidJpy = bestBidJpy,
        bestAskJpy = bestAskJpy,
        observedAt = observedAt,
        materialStateChanged = null,
    )
    return DecisionMaterialProjection(
        riskState = riskState,
        freshness = freshness,
        hasOpenPosition = openPositionFacts.isNotEmpty(),
        hasOpenOrder = openOrderFacts.isNotEmpty(),
        anchorPriceJpy = anchorPriceJpy ?: lastPriceJpy,
        currentPriceJpy = lastPriceJpy,
        atr14Jpy = atr14FiveMinutesJpy,
        bestBidJpy = bestBidJpy,
        bestAskJpy = bestAskJpy,
        invalidationState = invalidation,
    ).canonical(thresholdRatio)
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
