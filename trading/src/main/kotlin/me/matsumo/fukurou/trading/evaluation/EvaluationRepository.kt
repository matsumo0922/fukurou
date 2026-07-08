package me.matsumo.fukurou.trading.evaluation

import java.math.BigDecimal
import java.time.Instant

/**
 * 評価系が参照する DB 読み取り repository。
 */
interface EvaluationRepository {
    /**
     * closed trade fact を取得する。
     */
    suspend fun fetchClosedTrades(
        period: EvaluationPeriod,
        limit: Int = DEFAULT_EVALUATION_QUERY_LIMIT,
    ): Result<EvaluationTradeQueryResult>

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

/**
 * unit test と in-memory runtime 用の空の評価 repository。
 */
class InMemoryEvaluationRepository : EvaluationRepository {
    override suspend fun fetchClosedTrades(period: EvaluationPeriod, limit: Int,): Result<EvaluationTradeQueryResult> {
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
