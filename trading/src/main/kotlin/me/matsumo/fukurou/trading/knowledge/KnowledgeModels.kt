package me.matsumo.fukurou.trading.knowledge

import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.time.Instant

/**
 * recent lessons tool の既定取得件数。
 */
const val DEFAULT_KNOWLEDGE_RECENT_LESSONS_LIMIT = 5

/**
 * recent lessons tool の最大取得件数。
 */
const val MAX_KNOWLEDGE_RECENT_LESSONS_LIMIT = 10

/**
 * recent lessons tool の既定 lookback 日数。
 */
const val DEFAULT_KNOWLEDGE_RECENT_LESSONS_LOOKBACK_DAYS = 30

/**
 * similar setup search tool の既定取得件数。
 */
const val DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LIMIT = 3

/**
 * similar setup search tool の最大取得件数。
 */
const val MAX_KNOWLEDGE_SIMILAR_SETUPS_LIMIT = 5

/**
 * similar setup search tool の既定 lookback 日数。
 */
const val DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LOOKBACK_DAYS = 180

/**
 * Knowledge tool の最大 lookback 日数。
 */
const val MAX_KNOWLEDGE_LOOKBACK_DAYS = 365

/**
 * recent lessons tool の検索条件。
 *
 * @param symbol 取引対象 symbol
 * @param limit 返す lesson 件数
 * @param lookbackDays 参照する過去日数
 */
data class KnowledgeRecentLessonsQuery(
    val symbol: TradingSymbol = TradingSymbol.BTC,
    val limit: Int = DEFAULT_KNOWLEDGE_RECENT_LESSONS_LIMIT,
    val lookbackDays: Int = DEFAULT_KNOWLEDGE_RECENT_LESSONS_LOOKBACK_DAYS,
)

/**
 * similar setup search tool の検索条件。
 *
 * @param symbol 取引対象 symbol
 * @param setupTags setup tag 条件
 * @param regime 相場局面の短い説明
 * @param signalSummary signal の短い説明
 * @param limit 返す hit 件数
 * @param lookbackDays 参照する過去日数
 */
data class KnowledgeSimilarSetupsQuery(
    val symbol: TradingSymbol = TradingSymbol.BTC,
    val setupTags: List<String> = emptyList(),
    val regime: String? = null,
    val signalSummary: String? = null,
    val limit: Int = DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LIMIT,
    val lookbackDays: Int = DEFAULT_KNOWLEDGE_SIMILAR_SETUPS_LOOKBACK_DAYS,
)

/**
 * decision 由来の短い lesson。
 *
 * @param decisionId decision ID
 * @param createdAt decision 作成時刻
 * @param invocationId LLM invocation ID
 * @param action decision action
 * @param setupTags setup tag 一覧
 * @param estimatedWinProbability 申告勝率
 * @param expectedRMultiple 期待 R 倍率
 * @param reasonJa 判断理由の短縮版
 * @param selfReviewSummary self review の短縮版
 * @param missingDataJa 不足データの短縮版
 * @param noTradeConditionsJa NO_TRADE 時に次回評価へ残した entry trigger / invalidation 条件の短縮版
 * @param tradePlanSummary TradePlan の短縮版
 * @param falsificationSummary Falsifier verdict の短縮版
 */
data class KnowledgeLesson(
    val decisionId: String,
    val createdAt: Instant,
    val invocationId: String?,
    val action: String,
    val setupTags: List<String>,
    val estimatedWinProbability: String?,
    val expectedRMultiple: String?,
    val reasonJa: String,
    val selfReviewSummary: String,
    val missingDataJa: List<String>,
    val noTradeConditionsJa: List<String>,
    val tradePlanSummary: KnowledgeTradePlanSummary?,
    val falsificationSummary: KnowledgeFalsificationSummary?,
)

/**
 * Knowledge response 用の TradePlan 要約。
 *
 * @param tradePlanId TradePlan ID
 * @param thesisJa 仮説の短縮版
 * @param invalidationConditionsJa 否定条件の短縮版
 * @param revisionCount revision count
 */
data class KnowledgeTradePlanSummary(
    val tradePlanId: String,
    val thesisJa: String,
    val invalidationConditionsJa: List<String>,
    val revisionCount: Int,
)

/**
 * Knowledge response 用の Falsifier 要約。
 *
 * @param verdict verdict 名
 * @param reasonJa verdict 理由の短縮版
 * @param createdAt verdict 作成時刻
 */
data class KnowledgeFalsificationSummary(
    val verdict: String,
    val reasonJa: String,
    val createdAt: Instant,
)

/**
 * failure pattern の短い証跡。
 *
 * @param source pattern の出どころ
 * @param sourceId 出どころを識別する ID
 * @param occurredAt 発生時刻
 * @param summary pattern の短い説明
 * @param evidence 根拠の短縮版
 */
data class KnowledgeFailurePattern(
    val source: String,
    val sourceId: String,
    val occurredAt: Instant?,
    val summary: String,
    val evidence: String,
)

/**
 * llm_runs 由来の短い run 要約。
 *
 * @param invocationId LLM invocation ID
 * @param mode 取引 mode
 * @param symbol 取引対象 symbol
 * @param status run status
 * @param triggerKind trigger 種別
 * @param startedAt 開始時刻
 * @param finishedAt 終了時刻
 * @param errorMessage エラー message の短縮版
 */
data class KnowledgeRunSummary(
    val invocationId: String,
    val mode: String,
    val symbol: String,
    val status: String,
    val triggerKind: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorMessage: String?,
)

/**
 * setup tag 別の評価集計要約。
 *
 * @param setupTag setup tag
 * @param tradeCount closed trade 数
 * @param totalPnlJpy 合計 PnL
 * @param profitFactor profit factor
 * @param winRate 勝率
 * @param expectedR 平均実現 R
 * @param averageMaeR 平均 MAE_R
 * @param averageMfeR 平均 MFE_R
 */
data class KnowledgeSetupPerformanceSummary(
    val setupTag: String,
    val tradeCount: Int,
    val totalPnlJpy: String,
    val profitFactor: String?,
    val winRate: String?,
    val expectedR: String?,
    val averageMaeR: String?,
    val averageMfeR: String?,
)

/**
 * recent lessons tool の結果。
 *
 * @param symbol 取引対象 symbol
 * @param lookbackDays 参照した過去日数
 * @param limit 適用した取得件数上限
 * @param lessons recent lesson 一覧
 * @param failurePatterns failure pattern 一覧
 * @param runSummaries llm_runs 要約一覧
 * @param setupPerformance evaluation aggregate 要約一覧
 * @param evaluationTruncated evaluation 取得結果が切り詰められたか
 */
data class KnowledgeRecentLessonsResult(
    val symbol: TradingSymbol,
    val lookbackDays: Int,
    val limit: Int,
    val lessons: List<KnowledgeLesson>,
    val failurePatterns: List<KnowledgeFailurePattern>,
    val runSummaries: List<KnowledgeRunSummary>,
    val setupPerformance: List<KnowledgeSetupPerformanceSummary>,
    val evaluationTruncated: Boolean,
)

/**
 * similar setup hit の decision outcome 要約。
 *
 * @param runStatus LLM run status
 * @param falsificationVerdict Falsifier verdict
 * @param hasTradeIntent entry intent が作られたか
 * @param expectedRMultiple 期待 R 倍率
 */
data class KnowledgeDecisionOutcome(
    val runStatus: String?,
    val falsificationVerdict: String?,
    val hasTradeIntent: Boolean,
    val expectedRMultiple: String?,
)

/**
 * similar setup search の hit。
 *
 * @param score 類似度 score
 * @param matchedTerms 一致した検索語
 * @param lesson decision lesson
 * @param outcome decision outcome 要約
 */
data class KnowledgeSimilarSetupHit(
    val score: Int,
    val matchedTerms: List<String>,
    val lesson: KnowledgeLesson,
    val outcome: KnowledgeDecisionOutcome,
)

/**
 * similar setup search tool の結果。
 *
 * @param symbol 取引対象 symbol
 * @param setupTags 検索に使った setup tag
 * @param regime 検索に使った相場局面
 * @param signalSummary 検索に使った signal summary
 * @param lookbackDays 参照した過去日数
 * @param limit 適用した取得件数上限
 * @param hits 類似 hit 一覧
 * @param setupPerformance evaluation aggregate 要約一覧
 * @param evaluationTruncated evaluation 取得結果が切り詰められたか
 */
data class KnowledgeSimilarSetupsResult(
    val symbol: TradingSymbol,
    val setupTags: List<String>,
    val regime: String?,
    val signalSummary: String?,
    val lookbackDays: Int,
    val limit: Int,
    val hits: List<KnowledgeSimilarSetupHit>,
    val setupPerformance: List<KnowledgeSetupPerformanceSummary>,
    val evaluationTruncated: Boolean,
)
