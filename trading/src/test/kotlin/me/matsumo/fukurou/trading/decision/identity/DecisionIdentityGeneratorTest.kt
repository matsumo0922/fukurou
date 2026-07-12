package me.matsumo.fukurou.trading.decision.identity

import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** DecisionIdentityGenerator の canonicalization contract を検証する。 */
class DecisionIdentityGeneratorTest {
    @Test
    fun semanticEquivalentInputs_generateSameIdentityHashes() {
        val episodeId = UUID.randomUUID()
        val first = DecisionIdentityGenerator.generate(
            episodeId = episodeId,
            tradePlan = tradePlan("  押し目　買い ", listOf("trend", "breakout", "trend")),
            intent = intent(size = "0.0100"),
            materialProjection = "risk=normal\npriceBand=1",
        )
        val second = DecisionIdentityGenerator.generate(
            episodeId = episodeId,
            tradePlan = tradePlan("押し目 買い", listOf("breakout", "trend")),
            intent = intent(size = "0.01"),
            materialProjection = "risk=normal\npriceBand=1",
        )

        assertEquals(first, second)
    }

    @Test
    fun geometryAndMaterialChanges_areSeparated() {
        val episodeId = UUID.randomUUID()
        val baseline = DecisionIdentityGenerator.generate(
            episodeId,
            tradePlan("押し目", listOf("trend")),
            intent("0.01"),
            "priceBand=0",
        )
        val changed = DecisionIdentityGenerator.generate(
            episodeId,
            tradePlan("押し目", listOf("trend")),
            intent("0.02"),
            "priceBand=1",
        )

        assertEquals(baseline.thesisId, changed.thesisId)
        assertNotEquals(baseline.geometryHash, changed.geometryHash)
        assertNotEquals(baseline.materialStateHash, changed.materialStateHash)
    }

    private fun tradePlan(thesis: String, tags: List<String>) = TradePlanDraft(
        parentTradePlanId = null,
        revisionCount = 0,
        symbol = TradingSymbol.BTC,
        thesisJa = thesis,
        invalidationConditionsJa = listOf("安値割れ"),
        targetPriceJpy = null,
        timeStopAt = null,
        setupTags = tags,
    )

    private fun intent(size: String) = EntryIntentDraft(
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        sizeBtc = BigDecimal(size),
        priceJpy = BigDecimal("10000000"),
        protectiveStopPriceJpy = BigDecimal("9900000"),
        takeProfitPriceJpy = BigDecimal("10200000"),
    )
}
