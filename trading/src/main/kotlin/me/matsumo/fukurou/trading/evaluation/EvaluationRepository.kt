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
    suspend fun fetchLlmPhaseUsages(period: EvaluationPeriod): Result<List<LlmPhaseUsageFact>>

    /**
     * kill 基準に必要な closed trade 数と PF を取得する。
     */
    suspend fun fetchKillCriterionStats(): Result<KillCriterionStats>
}
