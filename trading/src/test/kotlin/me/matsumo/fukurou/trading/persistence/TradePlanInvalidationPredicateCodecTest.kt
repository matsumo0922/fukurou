package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** TradePlanInvalidationPredicateCodec の strict storage contract を検証する。 */
class TradePlanInvalidationPredicateCodecTest {
    @Test
    fun roundTripPreservesDecimalAndInstantThresholds() {
        val predicates = listOf(
            TradePlanInvalidationPredicate(
                type = TradePlanInvalidationType.LAST_PRICE_AT_OR_BELOW,
                decimalThresholdJpy = BigDecimal("9700000.125"),
            ),
            TradePlanInvalidationPredicate(
                type = TradePlanInvalidationType.TIME_AT_OR_AFTER,
                instantThreshold = Instant.parse("2026-07-12T12:34:56.789Z"),
            ),
            TradePlanInvalidationPredicate(type = TradePlanInvalidationType.MATERIAL_STATE_CHANGED),
        )

        val encoded = TradePlanInvalidationPredicateCodec.toStorageText(predicates)

        assertEquals(predicates, TradePlanInvalidationPredicateCodec.decode(encoded))
        assertEquals(emptyList(), TradePlanInvalidationPredicateCodec.decode(""))
    }

    @Test
    fun malformedStorageFailsWithoutDroppingInvalidPredicates() {
        listOf(
            "UNKNOWN|9700000|",
            "LAST_PRICE_AT_OR_BELOW|not-a-decimal|",
            "TIME_AT_OR_AFTER||not-an-instant",
            "LAST_PRICE_AT_OR_BELOW||",
            "MATERIAL_STATE_CHANGED|1|",
            "LAST_PRICE_AT_OR_BELOW|9700000",
            "LAST_PRICE_AT_OR_BELOW|9700000|;broken",
        ).forEach { malformed ->
            assertFailsWith<IllegalArgumentException>(malformed) {
                TradePlanInvalidationPredicateCodec.decode(malformed)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            TradePlanInvalidationPredicateCodec.toStorageText(
                listOf(TradePlanInvalidationPredicate(type = TradePlanInvalidationType.TIME_AT_OR_AFTER)),
            )
        }
    }
}
