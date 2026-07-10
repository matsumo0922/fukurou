package me.matsumo.fukurou.trading.evaluation

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 評価系の既定取得上限。
 */
const val DEFAULT_EVALUATION_QUERY_LIMIT = 20_000

/**
 * 評価対象期間。終了時刻は含めない。
 *
 * @param from 期間開始時刻
 * @param toExclusive 期間終了時刻
 */
data class EvaluationPeriod(
    val from: Instant,
    val toExclusive: Instant,
)

/**
 * closed trade fact の取得結果。
 *
 * @param trades 取得した closed trade fact
 * @param truncated 取得上限を超えたため切り詰めたか
 */
data class EvaluationTradeQueryResult(
    val trades: List<ClosedTradeFact>,
    val truncated: Boolean,
)

/**
 * LLM phase usage fact の取得結果。
 *
 * @param facts 取得した phase usage fact
 * @param truncated 取得上限を超えたため切り詰めたか
 */
data class EvaluationLlmUsageQueryResult(
    val facts: List<LlmPhaseUsageFact>,
    val truncated: Boolean,
)

/**
 * market-data gap により評価から外れた entity の summary。
 *
 * @param orderCount 除外 order 数
 * @param decisionRunCount 除外 decision run 数
 * @param positionCount 除外 position/trade 数
 * @param reasons reason 別 entity 件数
 */
data class EvaluationExclusionSummary(
    val orderCount: Int = 0,
    val decisionRunCount: Int = 0,
    val positionCount: Int = 0,
    val reasons: Map<String, Int> = emptyMap(),
)

/**
 * DB から読み出した closed trade の評価用 fact。
 *
 * setup tag が複数ある trade は setup 別集計で各 tag へ重複計上する。
 *
 * @param positionId position ID
 * @param openedAt position 開設時刻
 * @param closedAt position 決済時刻
 * @param sizeBtc entry BUY execution の合計数量
 * @param averageEntryPriceJpy entry BUY execution の数量加重平均価格
 * @param entryWeightedProtectiveStopPriceJpy entry BUY order の数量加重 protective stop 価格
 * @param highestPriceSinceEntryJpy entry 以降の最高値
 * @param lowestPriceSinceEntryJpy entry 以降の最安値。null は MAE 集計対象外
 * @param tradePnlJpy entry fee 控除後の trade 損益
 * @param estimatedWinProbability LLM が申告した推定勝率
 * @param setupTags setup tag 一覧
 * @param llmProvider decision を提出した LLM provider
 */
data class ClosedTradeFact(
    val positionId: UUID,
    val openedAt: Instant,
    val closedAt: Instant,
    val sizeBtc: BigDecimal,
    val averageEntryPriceJpy: BigDecimal,
    val entryWeightedProtectiveStopPriceJpy: BigDecimal?,
    val highestPriceSinceEntryJpy: BigDecimal,
    val lowestPriceSinceEntryJpy: BigDecimal?,
    val tradePnlJpy: BigDecimal,
    val estimatedWinProbability: BigDecimal?,
    val setupTags: List<String>,
    val llmProvider: String?,
)

/**
 * R 系指標を算出済みの closed trade。
 *
 * @param fact 元の closed trade fact
 * @param initialRiskPriceWidthJpy 初期 stop から entry までの 1R 価格幅
 * @param realizedR 実現損益の R 換算
 * @param maeR MAE の R 換算
 * @param mfeR MFE の R 換算
 */
data class EvaluatedTrade(
    val fact: ClosedTradeFact,
    val initialRiskPriceWidthJpy: BigDecimal?,
    val realizedR: BigDecimal?,
    val maeR: BigDecimal?,
    val mfeR: BigDecimal?,
)

/**
 * trade 成績の集計値。
 *
 * @param tradeCount trade 数
 * @param totalPnlJpy 合計損益
 * @param profitFactor profit factor。負け trade がない場合は null
 * @param winRate 勝率。trade がない場合は null
 * @param expectedR 実現 R の平均
 * @param averageMaeR MAE_R の平均
 * @param averageMfeR MFE_R の平均
 * @param rUnavailableCount 1R が算出不能だった件数
 * @param maeUnavailableCount MAE_R が算出不能だった件数
 * @param mfeUnavailableCount MFE_R が算出不能だった件数
 */
data class TradePerformanceStats(
    val tradeCount: Int,
    val totalPnlJpy: BigDecimal,
    val profitFactor: BigDecimal?,
    val winRate: BigDecimal?,
    val expectedR: BigDecimal?,
    val averageMaeR: BigDecimal?,
    val averageMfeR: BigDecimal?,
    val rUnavailableCount: Int,
    val maeUnavailableCount: Int,
    val mfeUnavailableCount: Int,
)

/**
 * setup tag 別の成績。
 *
 * @param setupTag setup tag
 * @param stats trade 成績
 */
data class SetupPerformance(
    val setupTag: String,
    val stats: TradePerformanceStats,
)

/**
 * 較正 bin の集計値。
 *
 * @param binIndex 0.1 幅 bin の index
 * @param lowerBoundInclusive 下限
 * @param upperBound 上限。最後の bin だけ 1.0 を含む
 * @param tradeCount trade 数
 * @param averageEstimatedProbability 平均申告 p
 * @param realizedWinRate 実現勝率
 */
data class CalibrationBinStats(
    val binIndex: Int,
    val lowerBoundInclusive: BigDecimal,
    val upperBound: BigDecimal,
    val tradeCount: Int,
    val averageEstimatedProbability: BigDecimal?,
    val realizedWinRate: BigDecimal?,
)

/**
 * 較正 group の集計値。
 *
 * @param groupKey setup tag または LLM provider
 * @param bins 0.1 幅 bin 一覧
 */
data class CalibrationGroupStats(
    val groupKey: String,
    val bins: List<CalibrationBinStats>,
)

/**
 * decision action 別件数。
 *
 * @param action decision action 名
 * @param count 件数
 */
data class DecisionActionCount(
    val action: String,
    val count: Int,
)

/**
 * 起動数に対する action rate。
 *
 * invocation_id が null の decision は分子に含めるが、起動数とは突合しない。
 *
 * @param decisionRunCount 期間内の distinct decision run 数
 * @param actionCounts action 別 decision 件数
 * @param entryRate ENTER 件数 / 起動数
 * @param noTradeRate NO_TRADE 件数 / 起動数
 */
data class DecisionRunRateStats(
    val decisionRunCount: Int,
    val actionCounts: List<DecisionActionCount>,
    val entryRate: BigDecimal?,
    val noTradeRate: BigDecimal?,
)

/**
 * 日次 realized PnL fact。
 *
 * @param closedAt trade close 時刻
 * @param pnlJpy trade 損益
 */
data class DailyTradePnlFact(
    val closedAt: Instant,
    val pnlJpy: BigDecimal,
)

/**
 * benchmark の日次 equity point。
 *
 * @param date JST 日付
 * @param buyAndHoldEquityJpy buy and hold equity
 * @param noTradeEquityJpy no-trade equity
 * @param botEquityJpy bot realized equity
 */
data class BenchmarkPoint(
    val date: LocalDate,
    val buyAndHoldEquityJpy: BigDecimal,
    val noTradeEquityJpy: BigDecimal,
    val botEquityJpy: BigDecimal,
)

/**
 * benchmark 集計結果。
 *
 * @param points 日次 equity 系列
 * @param buyAndHoldReturn buy and hold 期間 return
 * @param noTradeReturn no-trade 期間 return
 * @param botReturn bot 期間 return
 */
data class BenchmarkResult(
    val points: List<BenchmarkPoint>,
    val buyAndHoldReturn: BigDecimal?,
    val noTradeReturn: BigDecimal?,
    val botReturn: BigDecimal?,
)

/**
 * trend 相場分類。
 */
enum class TrendRegime {
    /**
     * SMA5 が SMA20 を十分上回る。
     */
    TREND_UP,

    /**
     * SMA5 が SMA20 を十分下回る。
     */
    TREND_DOWN,

    /**
     * SMA5 と SMA20 の差が close の 0.5% 未満。
     */
    RANGE,

    /**
     * 分類に必要な根拠が不足している。
     */
    UNKNOWN,
}

/**
 * volatility 相場分類。
 */
enum class VolatilityRegime {
    /**
     * 14 日平均レンジが期間中央値以上。
     */
    HIGH_VOL,

    /**
     * 14 日平均レンジが期間中央値未満。
     */
    LOW_VOL,

    /**
     * 分類に必要な根拠が不足している。
     */
    UNKNOWN,
}

/**
 * 日次の相場局面 label。
 *
 * @param date JST 日付
 * @param trend trend 分類
 * @param volatility volatility 分類
 */
data class MarketRegimeLabel(
    val date: LocalDate,
    val trend: TrendRegime,
    val volatility: VolatilityRegime,
)

/**
 * 相場局面別の成績。
 *
 * @param trend trend 分類
 * @param volatility volatility 分類
 * @param stats trade 成績
 */
data class MarketRegimePerformance(
    val trend: TrendRegime,
    val volatility: VolatilityRegime,
    val stats: TradePerformanceStats,
)

/**
 * LLM usage の token 内訳。
 *
 * @param inputTokens input token 数
 * @param outputTokens output token 数
 * @param reasoningOutputTokens output token のうち reasoning token 数
 * @param cacheCreationInputTokens cache 作成 input token 数
 * @param cacheReadInputTokens cache read input token 数
 */
data class LlmTokenUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val reasoningOutputTokens: Long? = null,
    val cacheCreationInputTokens: Long?,
    val cacheReadInputTokens: Long?,
)

/**
 * model 別の LLM usage。
 *
 * @param model model 名
 * @param usage token 内訳
 */
data class LlmModelUsage(
    val model: String,
    val usage: LlmTokenUsage,
)

/**
 * provider の structured output から抽出した usage。
 *
 * @param totalCostUsd 合計 cost USD
 * @param numTurns turn 数
 * @param durationMs 実行 duration ms
 * @param usage 全体 token 内訳
 * @param modelUsages model 別 token 内訳
 */
data class LlmUsageDetails(
    val totalCostUsd: BigDecimal?,
    val numTurns: Long?,
    val durationMs: Long?,
    val usage: LlmTokenUsage?,
    val modelUsages: List<LlmModelUsage>,
)

/**
 * runner phase ごとの LLM usage fact。
 *
 * @param decisionRunId decision run ID
 * @param provider LLM provider
 * @param phase runner phase 名
 * @param occurredAt phase 完了時刻
 * @param usage 抽出済み usage。欠落時は null
 */
data class LlmPhaseUsageFact(
    val decisionRunId: String?,
    val provider: String?,
    val phase: String?,
    val occurredAt: Instant,
    val usage: LlmUsageDetails?,
)

/**
 * provider 別 LLM cost。
 *
 * @param provider provider 名
 * @param knownCostUsd 取得済み cost の合計 USD。全 phase で未取得なら null
 * @param phaseCount phase 数
 * @param missingUsagePhaseCount usage 欠落 phase 数
 * @param unpricedPhaseCount monetary cost 未取得 phase 数。usage 欠落 phase を含む
 * @param unattributedTokenPhaseCount model attribution 欠落 phase 数
 */
data class LlmProviderCostStats(
    val provider: String,
    val knownCostUsd: BigDecimal?,
    val phaseCount: Int,
    val missingUsagePhaseCount: Int,
    val unpricedPhaseCount: Int,
    val unattributedTokenPhaseCount: Int,
)

/**
 * model 別 token 合計。
 *
 * @param model model 名
 * @param inputTokens input token 数
 * @param outputTokens output token 数
 * @param reasoningOutputTokens output token のうち reasoning token 数
 * @param cacheCreationInputTokens cache 作成 input token 数
 * @param cacheReadInputTokens cache read input token 数
 */
data class LlmModelTokenStats(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningOutputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
)

/**
 * LLM cost / usage 集計。
 *
 * @param phaseCount phase 数
 * @param missingUsagePhaseCount usage 欠落 phase 数
 * @param unpricedPhaseCount monetary cost 未取得 phase 数。usage 欠落 phase を含む
 * @param unattributedTokenPhaseCount model attribution 欠落 phase 数
 * @param knownCostUsd 取得済み cost の合計 USD。全 phase で未取得なら null
 * @param byProvider provider 別 cost
 * @param byModel model 別 token 合計
 */
data class LlmCostStats(
    val phaseCount: Int,
    val missingUsagePhaseCount: Int,
    val unpricedPhaseCount: Int,
    val unattributedTokenPhaseCount: Int,
    val knownCostUsd: BigDecimal?,
    val byProvider: List<LlmProviderCostStats>,
    val byModel: List<LlmModelTokenStats>,
)

/**
 * kill 基準の入力 stats。
 *
 * @param closedTrades closed trade 数
 * @param profitFactor profit factor
 */
data class KillCriterionStats(
    val closedTrades: Int,
    val profitFactor: BigDecimal?,
)
