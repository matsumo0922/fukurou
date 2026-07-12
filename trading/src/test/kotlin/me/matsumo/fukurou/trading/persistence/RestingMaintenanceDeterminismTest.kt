package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationState
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import me.matsumo.fukurou.trading.decision.evaluateInvalidationPredicates
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** resting maintenance の deterministic predicate / distance 判定を検証する。 */
class RestingMaintenanceDeterminismTest {
    @Test
    fun priceApproachUsesEpisodeBaselineAndReturnsUnknownWithoutIt() {
        assertEquals(
            true,
            hasPriceApproached(
                entryPrice = BigDecimal("10000000"),
                baselineDistance = BigDecimal("200000"),
                currentDistance = BigDecimal("90000"),
                thresholdRatio = BigDecimal("0.01"),
            ),
        )
        assertNull(
            hasPriceApproached(
                entryPrice = BigDecimal("10000000"),
                baselineDistance = null,
                currentDistance = BigDecimal("90000"),
                thresholdRatio = BigDecimal("0.01"),
            ),
        )
    }

    @Test
    fun typedPredicateNeverTreatsMissingQuoteAsValid() {
        val predicate = TradePlanInvalidationPredicate(
            type = TradePlanInvalidationType.BEST_BID_AT_OR_BELOW,
            decimalThresholdJpy = BigDecimal("9900000"),
        )

        assertEquals(
            TradePlanInvalidationState.INVALIDATED,
            evaluateInvalidationPredicates(
                predicates = listOf(predicate),
                lastPriceJpy = null,
                bestBidJpy = BigDecimal("9800000"),
                bestAskJpy = BigDecimal("9810000"),
                observedAt = Instant.parse("2026-07-12T00:00:00Z"),
                materialStateChanged = false,
            ),
        )
        assertEquals(
            TradePlanInvalidationState.UNKNOWN_DATA,
            evaluateInvalidationPredicates(
                predicates = listOf(predicate),
                lastPriceJpy = null,
                bestBidJpy = null,
                bestAskJpy = null,
                observedAt = Instant.parse("2026-07-12T00:00:00Z"),
                materialStateChanged = false,
            ),
        )
    }

    @Test
    fun volatilityChangeUsesAtrToExecutableQuoteRatioAndPreservesUnknown() {
        assertEquals(
            true,
            hasVolatilityChanged(
                baselineAtrRatio = BigDecimal("0.0100"),
                currentAtrRatio = BigDecimal("0.0111"),
                thresholdRatio = BigDecimal("0.10"),
            ),
        )
        assertNull(
            hasVolatilityChanged(
                baselineAtrRatio = null,
                currentAtrRatio = BigDecimal("0.0111"),
                thresholdRatio = BigDecimal("0.10"),
            ),
        )
    }
}
