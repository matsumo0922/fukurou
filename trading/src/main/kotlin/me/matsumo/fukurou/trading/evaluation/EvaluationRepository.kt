@file:Suppress("TooManyFunctions")

package me.matsumo.fukurou.trading.evaluation

import java.math.BigDecimal
import java.time.Instant

/**
 * 評価系が参照する DB 読み取り repository。
 */
interface EvaluationRepository {
    /** request query を current epoch + CURRENT 既定へ解決する。 */
    suspend fun resolveScope(epochId: String?, cohort: String?): Result<EvaluationScope> = runCatching {
        EvaluationScope(
            accountEpochId = epochId?.let(java.util.UUID::fromString) ?: java.util.UUID(0, 0),
            cohort = cohort?.let(me.matsumo.fukurou.trading.domain.EvaluationCohort::valueOf)
                ?: me.matsumo.fukurou.trading.domain.EvaluationCohort.CURRENT,
            executionSemanticsVersion = me.matsumo.fukurou.trading.domain.PAPER_EXECUTION_SEMANTICS_VERSION,
            initialCashJpy = BigDecimal.ZERO,
        )
    }

    /** report 用 internal facts を単一 snapshot として取得する。 */
    suspend fun fetchReportSnapshot(period: EvaluationPeriod): Result<EvaluationReportSnapshotFacts> = runCatching {
        EvaluationReportSnapshotFacts(
            trades = fetchClosedTrades(period).getOrThrow(),
            dailyPnl = fetchDailyTradePnl(period).getOrThrow(),
            priorPnlJpy = sumTradePnlBefore(period.from).getOrThrow(),
            initialCashJpy = fetchInitialCashJpy().getOrThrow(),
            usages = fetchLlmPhaseUsages(period).getOrThrow(),
            exclusions = fetchExclusionSummary(period).getOrThrow(),
        )
    }

    /** immutable epoch/cohort を固定した report snapshot を返す。 */
    suspend fun fetchReportSnapshot(
        period: EvaluationPeriod,
        scope: EvaluationScope,
    ): Result<EvaluationReportSnapshotFacts> = runCatching {
        val tradeResult = fetchClosedTrades(period, scope = scope).getOrThrow()
        val priorTrades = fetchClosedTrades(
            period = EvaluationPeriod(Instant.EPOCH, period.from),
            limit = Int.MAX_VALUE,
            scope = scope,
        ).getOrThrow().trades

        fetchReportSnapshot(period).getOrThrow().copy(
            trades = tradeResult,
            dailyPnl = tradeResult.trades.map { trade -> DailyTradePnlFact(trade.closedAt, trade.tradePnlJpy) },
            priorPnlJpy = priorTrades.sumOf(ClosedTradeFact::tradePnlJpy),
            initialCashJpy = scope.initialCashJpy,
        )
    }

    /** market-data gap による評価除外 summary を返す。 */
    suspend fun fetchExclusionSummary(period: EvaluationPeriod): Result<EvaluationExclusionSummary> {
        return Result.success(EvaluationExclusionSummary())
    }

    /**
     * closed trade fact を取得する。
     */
    suspend fun fetchClosedTrades(
        period: EvaluationPeriod,
        limit: Int = DEFAULT_EVALUATION_QUERY_LIMIT,
    ): Result<EvaluationTradeQueryResult>

    /** immutable scope で closed trade fact を取得する。 */
    suspend fun fetchClosedTrades(
        period: EvaluationPeriod,
        limit: Int = DEFAULT_EVALUATION_QUERY_LIMIT,
        scope: EvaluationScope,
    ): Result<EvaluationTradeQueryResult> = fetchClosedTrades(period, limit)

    /**
     * 期間内の distinct decision run 数を取得する。
     */
    suspend fun countDecisionRuns(period: EvaluationPeriod): Result<Int>

    /**
     * 期間内の decision action 別件数を取得する。
     */
    suspend fun countDecisionsByAction(period: EvaluationPeriod): Result<List<DecisionActionCount>>

    /**
     * benchmark 用の日次 realized PnL fact を取得する。
     */
    suspend fun fetchDailyTradePnl(period: EvaluationPeriod): Result<List<DailyTradePnlFact>>

    /**
     * 指定時刻より前の realized PnL 合計を取得する。
     */
    suspend fun sumTradePnlBefore(instant: Instant): Result<BigDecimal>

    /**
     * paper account の初期資金を取得する。
     */
    suspend fun fetchInitialCashJpy(): Result<BigDecimal>

    /**
     * runner phase ごとの LLM usage fact を取得する。
     */
    suspend fun fetchLlmPhaseUsages(
        period: EvaluationPeriod,
        limit: Int = DEFAULT_EVALUATION_QUERY_LIMIT,
    ): Result<EvaluationLlmUsageQueryResult>

    /**
     * kill 基準に必要な closed trade 数と PF を取得する。
     */
    suspend fun fetchKillCriterionStats(): Result<KillCriterionStats>
}

/** immutable evaluation report の DB snapshot facts。 */
data class EvaluationReportSnapshotFacts(
    val trades: EvaluationTradeQueryResult,
    val dailyPnl: List<DailyTradePnlFact>,
    val priorPnlJpy: BigDecimal,
    val initialCashJpy: BigDecimal,
    val usages: EvaluationLlmUsageQueryResult,
    val exclusions: EvaluationExclusionSummary,
)

/**
 * unit test と in-memory runtime 用の空の評価 repository。
 */
class InMemoryEvaluationRepository : EvaluationRepository {
    override suspend fun fetchExclusionSummary(period: EvaluationPeriod): Result<EvaluationExclusionSummary> {
        return Result.success(EvaluationExclusionSummary())
    }
    override suspend fun fetchClosedTrades(period: EvaluationPeriod, limit: Int): Result<EvaluationTradeQueryResult> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            EvaluationTradeQueryResult(
                trades = emptyList(),
                truncated = false,
            )
        }
    }

    override suspend fun countDecisionRuns(period: EvaluationPeriod): Result<Int> {
        return Result.success(0)
    }

    override suspend fun countDecisionsByAction(period: EvaluationPeriod): Result<List<DecisionActionCount>> {
        return Result.success(emptyList())
    }

    override suspend fun fetchDailyTradePnl(period: EvaluationPeriod): Result<List<DailyTradePnlFact>> {
        return Result.success(emptyList())
    }

    override suspend fun sumTradePnlBefore(instant: Instant): Result<BigDecimal> {
        return Result.success(BigDecimal.ZERO)
    }

    override suspend fun fetchInitialCashJpy(): Result<BigDecimal> {
        return Result.success(BigDecimal.ZERO)
    }

    override suspend fun fetchLlmPhaseUsages(
        period: EvaluationPeriod,
        limit: Int,
    ): Result<EvaluationLlmUsageQueryResult> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            EvaluationLlmUsageQueryResult(
                facts = emptyList(),
                truncated = false,
            )
        }
    }

    override suspend fun fetchKillCriterionStats(): Result<KillCriterionStats> {
        return Result.success(
            KillCriterionStats(
                closedTrades = 0,
                profitFactor = null,
            ),
        )
    }
}
