package me.matsumo.fukurou.trading.evaluation

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** InfrastructureGapProjection の causal interval contract を検証するテスト。 */
class InfrastructureGapProjectionTest {
    @Test
    fun `position opened before gap and closed after gap remains affected`() {
        val affected = InfrastructureGapProjection.affectedGapIds(
            interval = EvaluationCausalInterval(Instant.parse("2026-07-13T00:00:00Z"), Instant.parse("2026-07-13T00:30:00Z")),
            gaps = listOf(
                InfrastructureGap("deploy-1", "DEPLOY_MAINTENANCE", Instant.parse("2026-07-13T00:10:00Z"), Instant.parse("2026-07-13T00:20:00Z")),
            ),
            queryNow = Instant.parse("2026-07-13T01:00:00Z"),
        )

        assertEquals(setOf("deploy-1"), affected)
    }

    @Test
    fun `half-open boundary without overlap is not affected`() {
        val affected = InfrastructureGapProjection.affectedGapIds(
            interval = EvaluationCausalInterval(Instant.parse("2026-07-13T00:00:00Z"), Instant.parse("2026-07-13T00:10:00Z")),
            gaps = listOf(
                InfrastructureGap("deploy-1", "DEPLOY_MAINTENANCE", Instant.parse("2026-07-13T00:10:00Z"), null),
            ),
            queryNow = Instant.parse("2026-07-13T01:00:00Z"),
        )

        assertEquals(emptySet(), affected)
    }

    @Test
    fun `gap affected and attribution missing trades stay in population but leave strategy cohort`() {
        val attributed = closedTrade(
            attributionStatus = EvaluationAttributionStatus.ATTRIBUTED,
            infrastructureGapIds = emptySet(),
        )
        val gapAffected = closedTrade(
            attributionStatus = EvaluationAttributionStatus.ATTRIBUTED,
            infrastructureGapIds = setOf("deploy-1"),
        )
        val attributionMissing = closedTrade(
            attributionStatus = EvaluationAttributionStatus.MISSING,
            infrastructureGapIds = emptySet(),
        )
        val population = listOf(attributed, gapAffected, attributionMissing)

        val eligible = EvaluationPopulationFilter.strategyEligibleTrades(population)

        assertEquals(3, population.size)
        assertEquals(listOf(attributed), eligible)
    }
}

private fun closedTrade(
    attributionStatus: EvaluationAttributionStatus,
    infrastructureGapIds: Set<String>,
): ClosedTradeFact {
    return ClosedTradeFact(
        positionId = UUID.randomUUID(),
        openedAt = Instant.parse("2026-07-13T00:00:00Z"),
        closedAt = Instant.parse("2026-07-13T00:30:00Z"),
        sizeBtc = BigDecimal.ONE,
        averageEntryPriceJpy = BigDecimal.TEN,
        entryWeightedProtectiveStopPriceJpy = BigDecimal.ONE,
        highestPriceSinceEntryJpy = BigDecimal.TEN,
        lowestPriceSinceEntryJpy = BigDecimal.ONE,
        tradePnlJpy = BigDecimal.ONE,
        estimatedWinProbability = null,
        setupTags = emptyList(),
        llmProvider = null,
        attributionStatus = attributionStatus,
        infrastructureGapIds = infrastructureGapIds,
    )
}
