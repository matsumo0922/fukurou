package me.matsumo.fukurou.trading.evaluation

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.EvaluationCohort
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** EvaluationRepository の scope-aware default contract を検証する。 */
class EvaluationRepositoryTest {
    @Test
    fun deduplicationMetricsAreIsolatedToCurrentEpochLifecycle() = runBlocking {
        val queriedPeriods = mutableListOf<EvaluationPeriod>()
        val repository = RecordingDeduplicationRepository(queriedPeriods)
        val requested = EvaluationPeriod(
            from = Instant.parse("2026-07-01T00:00:00Z"),
            toExclusive = Instant.parse("2026-07-03T00:00:00Z"),
        )
        val currentScope = scope(
            cohort = EvaluationCohort.CURRENT,
            lifecycleFrom = Instant.parse("2026-07-02T00:00:00Z"),
        )

        val partial = repository.fetchDeduplicationMetrics(requested, currentScope).getOrThrow()
        val empty = repository.fetchDeduplicationMetrics(
            requested.copy(toExclusive = Instant.parse("2026-07-02T00:00:00Z")),
            currentScope,
        ).getOrThrow()
        val notAttributable = repository.fetchDeduplicationMetrics(
            requested,
            currentScope.copy(cohort = EvaluationCohort.UNSUPPORTED_EXECUTION_SEMANTICS),
        ).getOrThrow()

        assertEquals(1, partial.decisionEligible)
        assertEquals(DeduplicationMetrics(), empty)
        assertEquals(DeduplicationMetrics(), notAttributable)
        assertEquals(
            listOf(
                EvaluationPeriod(
                    from = Instant.parse("2026-07-02T00:00:00Z"),
                    toExclusive = Instant.parse("2026-07-03T00:00:00Z"),
                ),
            ),
            queriedPeriods,
        )
    }

    private fun scope(cohort: EvaluationCohort, lifecycleFrom: Instant) = EvaluationScope(
        accountEpochId = UUID.fromString("00000000-0000-0000-0000-000000000185"),
        cohort = cohort,
        executionSemanticsVersion = "PAPER_WS_V1",
        initialCashJpy = BigDecimal("1000000"),
        lifecycleFromInclusive = lifecycleFrom,
    )
}

private class RecordingDeduplicationRepository(
    private val queriedPeriods: MutableList<EvaluationPeriod>,
) : EvaluationRepository {
    override suspend fun fetchDeduplicationMetrics(period: EvaluationPeriod): Result<DeduplicationMetrics> {
        queriedPeriods += period
        return Result.success(DeduplicationMetrics(decisionEligible = 1, decisionComplete = 1))
    }

    override suspend fun fetchClosedTrades(period: EvaluationPeriod, limit: Int) =
        unsupported<EvaluationTradeQueryResult>()
    override suspend fun countDecisionRuns(period: EvaluationPeriod) = unsupported<Int>()
    override suspend fun countDecisionsByAction(period: EvaluationPeriod) = unsupported<List<DecisionActionCount>>()
    override suspend fun fetchDailyTradePnl(period: EvaluationPeriod) = unsupported<List<DailyTradePnlFact>>()
    override suspend fun sumTradePnlBefore(instant: Instant) = unsupported<BigDecimal>()
    override suspend fun fetchInitialCashJpy() = unsupported<BigDecimal>()
    override suspend fun fetchLlmPhaseUsages(period: EvaluationPeriod, limit: Int) =
        unsupported<EvaluationLlmUsageQueryResult>()

    override suspend fun fetchKillCriterionStats() = unsupported<KillCriterionStats>()

    private fun <T> unsupported(): Result<T> = Result.failure(UnsupportedOperationException("not used"))
}
