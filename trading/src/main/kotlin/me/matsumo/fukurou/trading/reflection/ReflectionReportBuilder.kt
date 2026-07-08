package me.matsumo.fukurou.trading.reflection

import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.evaluation.CalibrationGroupStats
import me.matsumo.fukurou.trading.evaluation.EQUITY_SNAPSHOT_TRADING_DATE_ZONE
import me.matsumo.fukurou.trading.evaluation.EvaluationMath
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_CANCELLED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_RUNNING
import me.matsumo.fukurou.trading.evaluation.LlmCostStats
import me.matsumo.fukurou.trading.evaluation.SetupPerformance
import me.matsumo.fukurou.trading.evaluation.TradePerformanceStats
import me.matsumo.fukurou.trading.knowledge.DecisionJournalRecord
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * reflection 用 Markdown report を決定論的に組み立てる builder。
 *
 * @param tradingConfig frontmatter に使う trading config
 * @param sampleWarningTradeCount sample size warning を出す closed trade 件数
 * @param tradingZone frontmatter の時刻表現に使う timezone
 */
class ReflectionReportBuilder(
    private val tradingConfig: TradingBotConfig,
    private val sampleWarningTradeCount: Int = DEFAULT_REFLECTION_SAMPLE_WARNING_TRADE_COUNT,
    private val tradingZone: ZoneId = EQUITY_SNAPSHOT_TRADING_DATE_ZONE,
) {

    init {
        require(sampleWarningTradeCount > 0) {
            "sampleWarningTradeCount must be greater than 0."
        }
    }

    /**
     * 収集済みデータから vault に書く report 一式を生成する。
     */
    fun build(dataset: ReflectionDataset): Result<ReflectionReports> {
        return runCatching {
            ReflectionReports(
                files = listOf(
                    dailyReport(dataset),
                    weeklyReport(dataset),
                    calibrationReport(dataset),
                    tagTaxonomyReport(dataset),
                ),
            )
        }
    }

    private fun dailyReport(dataset: ReflectionDataset): ReflectionMarkdownFile {
        val data = dataset.daily

        return ReflectionMarkdownFile(
            relativePath = "Knowledge/DailyReflections/${dataset.tradingDate}.md",
            content = buildWindowReport(
                WindowReportContext(
                    title = "Daily Reflection ${dataset.tradingDate}",
                    reflectionType = "daily_reflection",
                    periodLabel = dataset.tradingDate.toString(),
                    dataset = dataset,
                    data = data,
                    reportTags = listOf("reflection", "daily-reflection", tradingConfig.symbol.apiSymbol.lowercase()),
                ),
            ),
        )
    }

    private fun weeklyReport(dataset: ReflectionDataset): ReflectionMarkdownFile {
        val data = dataset.weekly

        return ReflectionMarkdownFile(
            relativePath = "Knowledge/WeeklyReviews/${dataset.weekId}.md",
            content = buildWindowReport(
                WindowReportContext(
                    title = "Weekly Review ${dataset.weekId}",
                    reflectionType = "weekly_reflection",
                    periodLabel = dataset.weekId,
                    dataset = dataset,
                    data = data,
                    reportTags = listOf("reflection", "weekly-review", tradingConfig.symbol.apiSymbol.lowercase()),
                ),
            ),
        )
    }

    private fun buildWindowReport(context: WindowReportContext): String {
        val data = context.data
        val stats = WindowReportStats(
            tradeStats = EvaluationMath.summarizeTrades(data.closedTrades),
            setupPerformance = EvaluationMath.summarizeBySetup(data.closedTrades),
            costStats = EvaluationMath.summarizeLlmCosts(data.llmPhaseUsages),
            sampleSizeWarning = data.closedTrades.size < sampleWarningTradeCount,
        )

        return buildString {
            appendWindowFrontmatter(
                context = context,
                stats = stats,
            )
            appendInlineFields(
                context.reflectionType,
                context.periodLabel,
                data,
                stats.sampleSizeWarning,
            )
            appendLine("# ${context.title}")
            appendLine()
            appendSummarySection(data, stats.tradeStats, stats.costStats)
            appendDecisionSection(data)
            appendLlmRunSection(data)
            appendSetupPerformanceSection(stats.setupPerformance)
            appendQualitySection(data, stats.sampleSizeWarning)
        }
    }

    private fun StringBuilder.appendWindowFrontmatter(context: WindowReportContext, stats: WindowReportStats) {
        val dataset = context.dataset
        val data = context.data

        appendLine("---")
        appendLine("type: ${context.reflectionType.yamlQuoted()}")
        appendLine("period: ${context.periodLabel.yamlQuoted()}")
        appendLine("date: ${dataset.tradingDate.toString().yamlQuoted()}")
        appendLine("week: ${dataset.weekId.yamlQuoted()}")
        appendLine("symbol: ${tradingConfig.symbol.apiSymbol.yamlQuoted()}")
        appendLine("mode: ${tradingConfig.mode.name.yamlQuoted()}")
        appendLine("generated_at: ${dataset.generatedAt.toOffsetText().yamlQuoted()}")
        appendLine("period_start: ${data.period.from.toOffsetText().yamlQuoted()}")
        appendLine("period_end: ${data.period.toExclusive.toOffsetText().yamlQuoted()}")
        appendLine("decision_runs: ${data.decisionRunCount}")
        appendLine("decisions: ${data.decisions.size}")
        appendLine("closed_trades: ${data.closedTrades.size}")
        appendLine("llm_runs: ${data.llmRuns.size}")
        appendLine("total_pnl_jpy: ${stats.tradeStats.totalPnlJpy.toPlainString()}")
        appendLine("profit_factor: ${stats.tradeStats.profitFactor.yamlNumberOrNull()}")
        appendLine("win_rate: ${stats.tradeStats.winRate.yamlNumberOrNull()}")
        appendLine("expected_r: ${stats.tradeStats.expectedR.yamlNumberOrNull()}")
        appendLine("llm_cost_usd: ${stats.costStats.totalCostUsd.toPlainString()}")
        appendLine("llm_phase_count: ${stats.costStats.phaseCount}")
        appendLine("llm_missing_usage_phases: ${stats.costStats.missingUsagePhaseCount}")
        appendLine("sample_size_warning: ${stats.sampleSizeWarning}")
        appendLine("truncated: ${data.truncation.any}")
        appendLine("decision_truncated: ${data.truncation.decisions}")
        appendLine("llm_run_truncated: ${data.truncation.llmRuns}")
        appendLine("closed_trade_truncated: ${data.truncation.closedTrades}")
        appendLine("llm_usage_truncated: ${data.truncation.llmUsages}")
        appendYamlList("tags", context.reportTags)
        appendLine("---")
        appendLine()
    }

    private fun StringBuilder.appendInlineFields(
        reflectionType: String,
        periodLabel: String,
        data: ReflectionWindowData,
        sampleSizeWarning: Boolean,
    ) {
        appendLine("reflection_type:: $reflectionType")
        appendLine("reflection_period:: $periodLabel")
        appendLine("sample_size_warning:: $sampleSizeWarning")
        appendLine("truncated:: ${data.truncation.any}")
        appendLine()
    }

    private fun StringBuilder.appendSummarySection(
        data: ReflectionWindowData,
        tradeStats: TradePerformanceStats,
        costStats: LlmCostStats,
    ) {
        appendLine("## Summary")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---:|")
        appendMetric("decision_runs", data.decisionRunCount)
        appendMetric("decisions", data.decisions.size)
        appendMetric("closed_trades", data.closedTrades.size)
        appendMetric("total_pnl_jpy", tradeStats.totalPnlJpy.toPlainString())
        appendMetric("profit_factor", tradeStats.profitFactor?.toPlainString() ?: "null")
        appendMetric("win_rate", tradeStats.winRate?.toPlainString() ?: "null")
        appendMetric("expected_r", tradeStats.expectedR?.toPlainString() ?: "null")
        appendMetric("llm_cost_usd", costStats.totalCostUsd.toPlainString())
        appendLine()
    }

    private fun StringBuilder.appendDecisionSection(data: ReflectionWindowData) {
        appendLine("## Decision Mix")
        appendLine()
        appendLine("| Action | Count |")
        appendLine("|---|---:|")
        decisionActions(data).forEach { actionCount ->
            appendLine("| ${actionCount.action} | ${actionCount.count} |")
        }
        appendLine()
        appendLine("### Recent Decisions")
        appendLine()
        appendLine("| Created | Action | Setups | Reason |")
        appendLine("|---|---|---|---|")
        data.decisions.forEach { record ->
            appendLine(
                "| ${record.decision.createdAt.toOffsetText()} | ${record.decision.submission.action.name} | " +
                    "${record.recordSetupTags().joinToString(", ").markdownCell()} | " +
                    "${record.decision.submission.reasonJa.markdownCell()} |",
            )
        }
        appendEmptyDecisionRowIfNeeded(data.decisions)
        appendLine()
    }

    private fun decisionActions(data: ReflectionWindowData): List<ActionCountView> {
        val countsByAction = data.actionCounts.associate { actionCount ->
            actionCount.action to actionCount.count
        }

        return DecisionAction.entries.map { action ->
            ActionCountView(
                action = action.name,
                count = countsByAction[action.name] ?: 0,
            )
        }
    }

    private fun StringBuilder.appendLlmRunSection(data: ReflectionWindowData) {
        appendLine("## LLM Runs")
        appendLine()
        appendLine("| Status | Count |")
        appendLine("|---|---:|")
        llmRunStatusCounts(data).forEach { statusCount ->
            appendLine("| ${statusCount.status} | ${statusCount.count} |")
        }
        appendLine()
    }

    private fun llmRunStatusCounts(data: ReflectionWindowData): List<StatusCountView> {
        val counts = data.llmRuns.groupingBy { run -> run.status }.eachCount()
        val knownStatuses = listOf(
            LLM_RUN_STATUS_RUNNING,
            LLM_RUN_STATUS_FAILED,
            LLM_RUN_STATUS_CANCELLED,
        )
        val unknownStatuses = counts.keys
            .filterNot { status -> status in knownStatuses }
            .sorted()

        return (knownStatuses + unknownStatuses).map { status ->
            StatusCountView(
                status = status,
                count = counts[status] ?: 0,
            )
        }
    }

    private fun StringBuilder.appendSetupPerformanceSection(setupPerformance: List<SetupPerformance>) {
        appendLine("## Setup Performance")
        appendLine()
        appendLine("| Setup | Trades | PnL JPY | PF | Win Rate | Expected R |")
        appendLine("|---|---:|---:|---:|---:|---:|")
        setupPerformance.forEach { performance ->
            appendSetupPerformanceRow(performance)
        }
        appendEmptySetupRowIfNeeded(setupPerformance)
        appendLine()
    }

    private fun StringBuilder.appendSetupPerformanceRow(performance: SetupPerformance) {
        val stats = performance.stats

        appendLine(
            "| ${performance.setupTag} | ${stats.tradeCount} | ${stats.totalPnlJpy.toPlainString()} | " +
                "${stats.profitFactor.markdownNumberOrNull()} | ${stats.winRate.markdownNumberOrNull()} | " +
                "${stats.expectedR.markdownNumberOrNull()} |",
        )
    }

    private fun StringBuilder.appendQualitySection(data: ReflectionWindowData, sampleSizeWarning: Boolean) {
        appendLine("## Data Quality")
        appendLine()
        appendLine("- sample_size_warning: $sampleSizeWarning")
        appendLine("- sample_size_threshold_trades: $sampleWarningTradeCount")
        appendLine("- truncated: ${data.truncation.any}")
        appendLine("- decision_truncated: ${data.truncation.decisions}")
        appendLine("- llm_run_truncated: ${data.truncation.llmRuns}")
        appendLine("- closed_trade_truncated: ${data.truncation.closedTrades}")
        appendLine("- llm_usage_truncated: ${data.truncation.llmUsages}")
        appendLine()
    }

    private fun calibrationReport(dataset: ReflectionDataset): ReflectionMarkdownFile {
        val data = dataset.calibration
        val setupCalibration = EvaluationMath.calibrationBySetup(data.closedTrades)
        val providerCalibration = EvaluationMath.calibrationByProvider(data.closedTrades)
        val sampleSizeWarning = data.closedTrades.size < sampleWarningTradeCount

        return ReflectionMarkdownFile(
            relativePath = "Knowledge/Calibration/ConfidenceCalibration.md",
            content = buildString {
                appendCalibrationFrontmatter(dataset, data, sampleSizeWarning)
                appendInlineFields(
                    reflectionType = "confidence_calibration",
                    periodLabel = data.period.id,
                    data = data,
                    sampleSizeWarning = sampleSizeWarning,
                )
                appendLine("# Confidence Calibration")
                appendLine()
                appendCalibrationGroups("Provider Calibration", providerCalibration)
                appendCalibrationGroups("Setup Calibration", setupCalibration)
                appendQualitySection(data, sampleSizeWarning)
            },
        )
    }

    private fun StringBuilder.appendCalibrationFrontmatter(
        dataset: ReflectionDataset,
        data: ReflectionWindowData,
        sampleSizeWarning: Boolean,
    ) {
        appendLine("---")
        appendLine("type: ${"confidence_calibration".yamlQuoted()}")
        appendLine("period: ${data.period.id.yamlQuoted()}")
        appendLine("symbol: ${tradingConfig.symbol.apiSymbol.yamlQuoted()}")
        appendLine("mode: ${tradingConfig.mode.name.yamlQuoted()}")
        appendLine("generated_at: ${dataset.generatedAt.toOffsetText().yamlQuoted()}")
        appendLine("period_start: ${data.period.from.toOffsetText().yamlQuoted()}")
        appendLine("period_end: ${data.period.toExclusive.toOffsetText().yamlQuoted()}")
        appendLine("closed_trades: ${data.closedTrades.size}")
        appendLine("sample_size_warning: $sampleSizeWarning")
        appendLine("truncated: ${data.truncation.any}")
        appendLine("decision_truncated: ${data.truncation.decisions}")
        appendLine("llm_run_truncated: ${data.truncation.llmRuns}")
        appendLine("closed_trade_truncated: ${data.truncation.closedTrades}")
        appendLine("llm_usage_truncated: ${data.truncation.llmUsages}")
        appendYamlList(
            key = "tags",
            values = listOf("reflection", "confidence-calibration", tradingConfig.symbol.apiSymbol.lowercase()),
        )
        appendLine("---")
        appendLine()
    }

    private fun StringBuilder.appendCalibrationGroups(title: String, groups: List<CalibrationGroupStats>) {
        appendLine("## $title")
        appendLine()
        groups.forEach { group ->
            appendLine("### ${group.groupKey}")
            appendLine()
            appendLine("| Bin | Trades | Avg Estimated P | Realized Win Rate |")
            appendLine("|---|---:|---:|---:|")
            group.bins.forEach { bin ->
                appendLine(
                    "| ${bin.lowerBoundInclusive.toPlainString()}-${bin.upperBound.toPlainString()} | " +
                        "${bin.tradeCount} | ${bin.averageEstimatedProbability.markdownNumberOrNull()} | " +
                        "${bin.realizedWinRate.markdownNumberOrNull()} |",
                )
            }
            appendLine()
        }
        appendEmptyMessageIfNeeded(groups)
    }

    private fun tagTaxonomyReport(dataset: ReflectionDataset): ReflectionMarkdownFile {
        val data = dataset.weekly
        val tagSummaries = tagSummaries(data)
        val aliasGroups = tagSummaries
            .filter { summary -> summary.rawTags.size > 1 }
        val sampleSizeWarning = data.closedTrades.size < sampleWarningTradeCount

        return ReflectionMarkdownFile(
            relativePath = "Knowledge/Setups/TagTaxonomy-${dataset.weekId}.md",
            content = buildString {
                appendTagTaxonomyFrontmatter(dataset, data, tagSummaries, aliasGroups, sampleSizeWarning)
                appendInlineFields(
                    reflectionType = "setup_tag_taxonomy",
                    periodLabel = dataset.weekId,
                    data = data,
                    sampleSizeWarning = sampleSizeWarning,
                )
                appendLine("# Setup Tag Taxonomy ${dataset.weekId}")
                appendLine()
                appendTagSummarySection(tagSummaries)
                appendAliasSection(aliasGroups)
                appendQualitySection(data, sampleSizeWarning)
            },
        )
    }

    private fun StringBuilder.appendTagTaxonomyFrontmatter(
        dataset: ReflectionDataset,
        data: ReflectionWindowData,
        tagSummaries: List<TagSummaryView>,
        aliasGroups: List<TagSummaryView>,
        sampleSizeWarning: Boolean,
    ) {
        appendLine("---")
        appendLine("type: ${"setup_tag_taxonomy".yamlQuoted()}")
        appendLine("week: ${dataset.weekId.yamlQuoted()}")
        appendLine("symbol: ${tradingConfig.symbol.apiSymbol.yamlQuoted()}")
        appendLine("mode: ${tradingConfig.mode.name.yamlQuoted()}")
        appendLine("generated_at: ${dataset.generatedAt.toOffsetText().yamlQuoted()}")
        appendLine("period_start: ${data.period.from.toOffsetText().yamlQuoted()}")
        appendLine("period_end: ${data.period.toExclusive.toOffsetText().yamlQuoted()}")
        appendLine("tag_count: ${tagSummaries.size}")
        appendLine("alias_candidate_count: ${aliasGroups.size}")
        appendLine("sample_size_warning: $sampleSizeWarning")
        appendLine("truncated: ${data.truncation.any}")
        appendLine("decision_truncated: ${data.truncation.decisions}")
        appendLine("llm_run_truncated: ${data.truncation.llmRuns}")
        appendLine("closed_trade_truncated: ${data.truncation.closedTrades}")
        appendLine("llm_usage_truncated: ${data.truncation.llmUsages}")
        appendYamlList("tags", listOf("reflection", "setup-taxonomy", tradingConfig.symbol.apiSymbol.lowercase()))
        appendLine("---")
        appendLine()
    }

    private fun StringBuilder.appendTagSummarySection(tagSummaries: List<TagSummaryView>) {
        appendLine("## Tag Summary")
        appendLine()
        appendLine("| Canonical Tag | Raw Tags | Decisions | Trades | PF | Win Rate | Expected R |")
        appendLine("|---|---|---:|---:|---:|---:|---:|")
        tagSummaries.forEach { summary ->
            appendLine(
                "| ${summary.canonicalTag} | ${summary.rawTags.joinToString(", ")} | " +
                    "${summary.decisionCount} | ${summary.tradeCount} | ${summary.profitFactor} | " +
                    "${summary.winRate} | ${summary.expectedR} |",
            )
        }
        appendEmptyTagRowIfNeeded(tagSummaries)
        appendLine()
    }

    private fun StringBuilder.appendAliasSection(aliasGroups: List<TagSummaryView>) {
        appendLine("## Consolidation Candidates")
        appendLine()
        if (aliasGroups.isEmpty()) {
            appendLine("- none")
            appendLine()
            return
        }

        aliasGroups.forEach { summary ->
            appendLine("- ${summary.canonicalTag}: ${summary.rawTags.joinToString(", ")}")
        }
        appendLine()
    }

    private fun tagSummaries(data: ReflectionWindowData): List<TagSummaryView> {
        val decisionCounts = data.decisions
            .flatMap { record -> record.recordSetupTags() }
            .groupingBy { tag -> tag.normalizedTag() }
            .eachCount()
        val rawTagsByCanonical = data.decisions
            .flatMap { record -> record.recordSetupTags() }
            .groupBy { tag -> tag.normalizedTag() }
            .mapValues { entry -> entry.value.toSortedSet().toList() }
        val setupPerformanceByTag = EvaluationMath.summarizeBySetup(data.closedTrades)
            .associateBy { performance -> performance.setupTag.normalizedTag() }
        val canonicalTags = (decisionCounts.keys + rawTagsByCanonical.keys + setupPerformanceByTag.keys).sorted()

        return canonicalTags.map { canonicalTag ->
            val stats = setupPerformanceByTag[canonicalTag]?.stats

            TagSummaryView(
                canonicalTag = canonicalTag,
                rawTags = rawTagsByCanonical[canonicalTag].orEmpty().ifEmpty { listOf(canonicalTag) },
                decisionCount = decisionCounts[canonicalTag] ?: 0,
                tradeCount = stats?.tradeCount ?: 0,
                profitFactor = stats?.profitFactor.markdownNumberOrNull(),
                winRate = stats?.winRate.markdownNumberOrNull(),
                expectedR = stats?.expectedR.markdownNumberOrNull(),
            )
        }
    }

    private fun DecisionJournalRecord.recordSetupTags(): List<String> {
        val decisionTags = decision.submission.setupTags
        val planTags = tradePlan?.draft?.setupTags.orEmpty()

        return (decisionTags + planTags)
            .map { tag -> tag.trim() }
            .filter { tag -> tag.isNotBlank() }
            .distinct()
    }

    private fun String.normalizedTag(): String {
        return trim()
            .lowercase()
            .replace(WhitespaceRegex, "-")
            .trim('-')
            .ifBlank { "unclassified" }
    }

    private fun StringBuilder.appendMetric(key: String, value: Any) {
        appendLine("| $key | $value |")
    }

    private fun StringBuilder.appendEmptyDecisionRowIfNeeded(values: List<DecisionJournalRecord>) {
        if (values.isNotEmpty()) {
            return
        }

        appendLine("| none | none | none | none |")
    }

    private fun StringBuilder.appendEmptySetupRowIfNeeded(values: List<SetupPerformance>) {
        if (values.isNotEmpty()) {
            return
        }

        appendLine("| none | 0 | 0 | null | null | null |")
    }

    private fun StringBuilder.appendEmptyTagRowIfNeeded(values: List<TagSummaryView>) {
        if (values.isNotEmpty()) {
            return
        }

        appendLine("| none | none | 0 | 0 | null | null | null |")
    }

    private fun String.markdownCell(): String {
        return replace("|", "\\|")
            .replace("\n", " ")
            .replace("\r", " ")
    }

    private fun <T> StringBuilder.appendEmptyMessageIfNeeded(values: List<T>) {
        if (values.isNotEmpty()) {
            return
        }

        appendLine("- none")
        appendLine()
    }

    private fun StringBuilder.appendYamlList(key: String, values: List<String>) {
        if (values.isEmpty()) {
            appendLine("$key: []")
            return
        }

        appendLine("$key:")
        values.forEach { value ->
            appendLine("  - ${value.yamlQuoted()}")
        }
    }

    private fun BigDecimal?.yamlNumberOrNull(): String {
        return this?.toPlainString() ?: "null"
    }

    private fun BigDecimal?.markdownNumberOrNull(): String {
        return this?.toPlainString() ?: "null"
    }

    private fun String.yamlQuoted(): String {
        val escaped = buildString {
            this@yamlQuoted.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> appendYamlCharacter(character)
                }
            }
        }

        return "\"$escaped\""
    }

    private fun StringBuilder.appendYamlCharacter(character: Char) {
        if (character.code < YAML_CONTROL_CHARACTER_BOUNDARY) {
            append(' ')
        } else {
            append(character)
        }
    }

    private fun java.time.Instant.toOffsetText(): String {
        return atZone(tradingZone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}

/**
 * 日次・週次 report の共通 context。
 *
 * @param title Markdown title
 * @param reflectionType frontmatter の type
 * @param periodLabel 表示用 period label
 * @param dataset report 生成入力全体
 * @param data 対象期間の入力データ
 * @param reportTags frontmatter tags
 */
private data class WindowReportContext(
    val title: String,
    val reflectionType: String,
    val periodLabel: String,
    val dataset: ReflectionDataset,
    val data: ReflectionWindowData,
    val reportTags: List<String>,
)

/**
 * 日次・週次 report の算出済み stats。
 *
 * @param tradeStats trade 成績
 * @param setupPerformance setup tag 別成績
 * @param costStats LLM cost 集計
 * @param sampleSizeWarning sample size warning を表示するか
 */
private data class WindowReportStats(
    val tradeStats: TradePerformanceStats,
    val setupPerformance: List<SetupPerformance>,
    val costStats: LlmCostStats,
    val sampleSizeWarning: Boolean,
)

/**
 * action count 出力用 view。
 *
 * @param action decision action
 * @param count 件数
 */
private data class ActionCountView(
    val action: String,
    val count: Int,
)

/**
 * llm run status count 出力用 view。
 *
 * @param status llm_runs status
 * @param count 件数
 */
private data class StatusCountView(
    val status: String,
    val count: Int,
)

/**
 * setup tag taxonomy 出力用 view。
 *
 * @param canonicalTag 正規化済み tag
 * @param rawTags 元の表記一覧
 * @param decisionCount decision に出現した件数
 * @param tradeCount closed trade 件数
 * @param profitFactor profit factor
 * @param winRate 勝率
 * @param expectedR 平均実現 R
 */
private data class TagSummaryView(
    val canonicalTag: String,
    val rawTags: List<String>,
    val decisionCount: Int,
    val tradeCount: Int,
    val profitFactor: String,
    val winRate: String,
    val expectedR: String,
)

/**
 * YAML double quoted scalar にそのまま入れない制御文字の境界。
 */
private const val YAML_CONTROL_CHARACTER_BOUNDARY = 0x20

/**
 * tag 正規化用 whitespace regex。
 */
private val WhitespaceRegex = Regex("\\s+")
