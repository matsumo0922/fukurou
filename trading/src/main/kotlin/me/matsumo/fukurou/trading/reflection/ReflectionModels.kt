package me.matsumo.fukurou.trading.reflection

import me.matsumo.fukurou.trading.evaluation.ClosedTradeFact
import me.matsumo.fukurou.trading.evaluation.DecisionActionCount
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.LlmPhaseUsageFact
import me.matsumo.fukurou.trading.evaluation.LlmRunRecord
import me.matsumo.fukurou.trading.knowledge.DecisionJournalRecord
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Reflection runner loop の既定最小間隔。
 */
val DEFAULT_REFLECTION_MIN_INTERVAL: Duration = Duration.ofHours(1)

/**
 * Reflection runner loop の許容最小間隔。
 */
val MIN_REFLECTION_MIN_INTERVAL: Duration = Duration.ofMinutes(1)

/**
 * Reflection runner が一度に読む既定行数。
 */
const val DEFAULT_REFLECTION_QUERY_LIMIT = 1_000

/**
 * confidence calibration が参照する既定日数。
 */
const val DEFAULT_REFLECTION_CALIBRATION_LOOKBACK_DAYS = 180

/**
 * sample size warning を出す closed trade 件数のしきい値。
 */
const val DEFAULT_REFLECTION_SAMPLE_WARNING_TRADE_COUNT = 30

/**
 * Recent Decisions に表示する既定行数。
 */
const val DEFAULT_REFLECTION_RECENT_DECISION_LIMIT = 50

/**
 * deterministic reflection runner 設定。
 *
 * @param minInterval runner loop の最小間隔
 * @param queryLimit 1 tick で読む最大行数
 * @param calibrationLookbackDays confidence calibration が参照する日数
 * @param recentDecisionLimit Recent Decisions に表示する最大行数
 * @param sampleWarningTradeCount sample size warning を出す closed trade 件数
 */
data class ReflectionConfig(
    val minInterval: Duration = DEFAULT_REFLECTION_MIN_INTERVAL,
    val queryLimit: Int = DEFAULT_REFLECTION_QUERY_LIMIT,
    val calibrationLookbackDays: Int = DEFAULT_REFLECTION_CALIBRATION_LOOKBACK_DAYS,
    val recentDecisionLimit: Int = DEFAULT_REFLECTION_RECENT_DECISION_LIMIT,
    val sampleWarningTradeCount: Int = DEFAULT_REFLECTION_SAMPLE_WARNING_TRADE_COUNT,
) {
    init {
        require(minInterval >= MIN_REFLECTION_MIN_INTERVAL) {
            "minInterval must be greater than or equal to ${MIN_REFLECTION_MIN_INTERVAL.seconds} seconds."
        }
        require(queryLimit > 0) {
            "queryLimit must be greater than 0."
        }
        require(calibrationLookbackDays > 0) {
            "calibrationLookbackDays must be greater than 0."
        }
        require(recentDecisionLimit > 0) {
            "recentDecisionLimit must be greater than 0."
        }
        require(sampleWarningTradeCount > 0) {
            "sampleWarningTradeCount must be greater than 0."
        }
    }
}

/**
 * reflection report の対象期間。
 *
 * @param id report path と frontmatter に使う期間 ID
 * @param from 期間開始時刻
 * @param toExclusive 期間終了時刻
 */
data class ReflectionPeriod(
    val id: String,
    val from: Instant,
    val toExclusive: Instant,
) {
    /**
     * 評価 repository の期間 model に変換する。
     */
    fun toEvaluationPeriod(): EvaluationPeriod {
        return EvaluationPeriod(
            from = from,
            toExclusive = toExclusive,
        )
    }
}

/**
 * reflection 読み取りの切り詰め状態。
 *
 * @param decisions decisions が取得上限で切り詰められた可能性があるか
 * @param llmRuns llm_runs が取得上限で切り詰められた可能性があるか
 * @param closedTrades closed trade fact が取得上限で切り詰められたか
 * @param llmUsages LLM usage fact が取得上限で切り詰められたか
 */
data class ReflectionTruncationFlags(
    val decisions: Boolean,
    val llmRuns: Boolean,
    val closedTrades: Boolean,
    val llmUsages: Boolean,
) {
    /**
     * いずれかの入力が切り詰められているか。
     */
    val any: Boolean
        get() = decisions || llmRuns || closedTrades || llmUsages
}

/**
 * reflection report 1 期間分の DB 由来データ。
 *
 * @param period 対象期間
 * @param decisions 期間内の decision
 * @param llmRuns 期間内に開始した llm_runs
 * @param closedTrades 期間内に close した trade fact
 * @param decisionRunCount distinct decision run 数
 * @param actionCounts decision action 別件数
 * @param llmPhaseUsages 期間内の proposer / falsifier usage fact
 * @param truncation 取得上限による切り詰め状態
 */
data class ReflectionWindowData(
    val period: ReflectionPeriod,
    val decisions: List<DecisionJournalRecord>,
    val llmRuns: List<LlmRunRecord>,
    val closedTrades: List<ClosedTradeFact>,
    val decisionRunCount: Int,
    val actionCounts: List<DecisionActionCount>,
    val llmPhaseUsages: List<LlmPhaseUsageFact>,
    val truncation: ReflectionTruncationFlags,
)

/**
 * reflection runner 1 tick 分の入力データ。
 *
 * @param tradingDate 日次 reflection の JST 日付
 * @param weekId 週次 reflection の ISO week ID
 * @param daily 日次対象データ
 * @param previousTradingDate 前日の日次 reflection 日付
 * @param previousDaily 前日の日次対象データ
 * @param weekly 週次対象データ
 * @param previousWeekId 前週の ISO week ID
 * @param previousWeekly 前週の週次対象データ
 * @param calibration confidence calibration 対象データ
 */
data class ReflectionDataset(
    val tradingDate: LocalDate,
    val weekId: String,
    val daily: ReflectionWindowData,
    val previousTradingDate: LocalDate,
    val previousDaily: ReflectionWindowData,
    val weekly: ReflectionWindowData,
    val previousWeekId: String,
    val previousWeekly: ReflectionWindowData,
    val calibration: ReflectionWindowData,
)

/**
 * vault に書く Markdown file。
 *
 * @param relativePath vault root からの相対 path
 * @param content Markdown 本文
 */
data class ReflectionMarkdownFile(
    val relativePath: String,
    val content: String,
)

/**
 * reflection runner が生成した Markdown 一式。
 *
 * @param files vault に書く file 一覧
 */
data class ReflectionReports(
    val files: List<ReflectionMarkdownFile>,
)

/**
 * reflection vault 書き込み結果。
 *
 * @param writtenFiles 新規作成または内容差し替えした file 数
 * @param unchangedFiles 既存内容と一致していた file 数
 */
data class ReflectionWriteSummary(
    val writtenFiles: Int,
    val unchangedFiles: Int,
)
