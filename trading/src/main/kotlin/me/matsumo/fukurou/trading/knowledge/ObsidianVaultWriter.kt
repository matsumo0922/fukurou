package me.matsumo.fukurou.trading.knowledge

import kotlinx.coroutines.CancellationException
import me.matsumo.fukurou.trading.broker.PaperLedgerRepository
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.evaluation.EQUITY_SNAPSHOT_TRADING_DATE_ZONE
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunRecord
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Obsidian vault の Trade / Daily note を DB 状態から機械的に再生成する writer。
 *
 * @param vaultPath 書き込み先 vault path
 * @param decisionRepository decision protocol 読み取り repository
 * @param llmRunRepository llm_runs 読み取り repository
 * @param paperLedgerRepository paper ledger 読み取り repository
 * @param tradingConfig note frontmatter に使う trading config
 * @param redactor note 書き込み前に秘密値を伏せる redactor
 * @param clock 今日の Daily note と読み取り範囲に使う clock
 * @param queryLimit 1 tick で読む最大行数
 */
class ObsidianVaultWriter(
    private val vaultPath: Path,
    private val decisionRepository: DecisionRepository,
    private val llmRunRepository: LlmRunRepository,
    private val paperLedgerRepository: PaperLedgerRepository,
    private val tradingConfig: TradingBotConfig,
    private val redactor: SecretRedactor,
    private val clock: Clock = Clock.systemUTC(),
    private val queryLimit: Int = DEFAULT_OBSIDIAN_QUERY_LIMIT,
) : ObsidianWriter {

    init {
        require(queryLimit > 0) {
            "queryLimit must be greater than 0."
        }
    }

    override suspend fun writeOnce(): Result<ObsidianWriteSummary> {
        return try {
            val toExclusive = clock.instant().plusSeconds(READ_RANGE_FUTURE_TOLERANCE_SECONDS)
            val decisions = decisionRepository.findDecisionsCreatedBetween(
                from = Instant.EPOCH,
                toExclusive = toExclusive,
                limit = queryLimit,
            ).getOrThrow()
            val llmRuns = llmRunRepository.findRunsStartedBetween(
                from = Instant.EPOCH,
                toExclusive = toExclusive,
                limit = queryLimit,
            ).getOrThrow()
            val closedPositions = paperLedgerRepository.findClosedPositionsClosedBetween(
                from = Instant.EPOCH,
                toExclusive = toExclusive,
                limit = queryLimit,
            ).getOrThrow()
            val summary = ObsidianWriteCounter()

            createDirectorySkeleton()
            writeDashboardIfMissing(summary)
            writeTradeNotes(closedPositions, decisions, summary)
            writeDailyNotes(closedPositions, decisions, llmRuns, summary)

            Result.success(summary.toSummary())
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private fun createDirectorySkeleton() {
        REQUIRED_DIRECTORIES.forEach { relativePath ->
            Files.createDirectories(vaultPath.resolve(relativePath))
        }
    }

    private fun writeDashboardIfMissing(summary: ObsidianWriteCounter) {
        val dashboardPath = vaultPath.resolve(TRADING_DASHBOARD_PATH)

        if (Files.exists(dashboardPath)) {
            return
        }

        summary.record(writeIfChanged(TRADING_DASHBOARD_PATH, TRADING_DASHBOARD_MARKDOWN))
    }

    private fun writeTradeNotes(
        closedPositions: List<ClosedPaperPosition>,
        decisions: List<DecisionJournalRecord>,
        summary: ObsidianWriteCounter,
    ) {
        val decisionsByInvocationId = decisions
            .mapNotNull { record ->
                val invocationId = record.decision.submission.invocationId ?: return@mapNotNull null

                invocationId to record
            }
            .toMap()

        closedPositions.forEach { closedPosition ->
            val decision = closedPosition.decisionRunId?.let { invocationId -> decisionsByInvocationId[invocationId] }
            val relativePath = closedPosition.tradeNoteRelativePath()
            val content = buildTradeNote(relativePath, closedPosition, decision)

            if (content == null) {
                summary.record(VaultWriteState.UNCHANGED)
            } else {
                summary.record(writeIfChanged(relativePath, content))
            }
        }
    }

    private fun writeDailyNotes(
        closedPositions: List<ClosedPaperPosition>,
        decisions: List<DecisionJournalRecord>,
        llmRuns: List<LlmRunRecord>,
        summary: ObsidianWriteCounter,
    ) {
        collectTradingDates(closedPositions, decisions, llmRuns).forEach { tradingDate ->
            val relativePath = dailyNoteRelativePath(tradingDate)
            val content = buildDailyNote(
                relativePath = relativePath,
                tradingDate = tradingDate,
                closedPositions = closedPositions,
                decisions = decisions,
                llmRuns = llmRuns,
            )

            if (content == null) {
                summary.record(VaultWriteState.UNCHANGED)
            } else {
                summary.record(writeIfChanged(relativePath, content))
            }
        }
    }

    private fun writeIfChanged(relativePath: String, rawContent: String): VaultWriteState {
        val targetPath = vaultPath.resolve(relativePath)
        val content = redactor.redact(rawContent)

        Files.createDirectories(requireNotNull(targetPath.parent))

        if (Files.exists(targetPath) && Files.readString(targetPath, StandardCharsets.UTF_8) == content) {
            return VaultWriteState.UNCHANGED
        }

        atomicReplace(targetPath, content)

        return VaultWriteState.WRITTEN
    }

    private fun atomicReplace(targetPath: Path, content: String) {
        val parentPath = requireNotNull(targetPath.parent)
        val tempPath = Files.createTempFile(parentPath, "${targetPath.fileName}.", ".tmp")

        try {
            Files.writeString(
                tempPath,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            moveReplacing(tempPath, targetPath)
        } catch (throwable: Throwable) {
            Files.deleteIfExists(tempPath)

            throw throwable
        }
    }

    private fun moveReplacing(tempPath: Path, targetPath: Path) {
        try {
            Files.move(
                tempPath,
                targetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempPath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun buildTradeNote(
        relativePath: String,
        closedPosition: ClosedPaperPosition,
        decision: DecisionJournalRecord?,
    ): String? {
        val position = closedPosition.position
        val entryExecution = closedPosition.entryExecution()
        val exitExecution = closedPosition.exitExecution()
        val entryTime = position.openedAt.toJstText()
        val exitTime = requireNotNull(position.closedAt).toJstText()
        val setupTags = decision?.decision?.submission?.setupTags.orEmpty()
        val feeJpy = closedPosition.totalFeeJpy()
        val tradePnlJpy = closedPosition.tradeNetPnlJpy()
        val frontmatter = buildString {
            appendLine("---")
            appendLine("type: ${TRADE_TYPE.yamlQuoted()}")
            appendLine("trade_id: ${position.positionId.yamlQuoted()}")
            appendLine("decision_id: ${(decision?.decision?.submission?.invocationId ?: closedPosition.decisionRunId).yamlNullable()}")
            appendLine("symbol: ${position.symbol.yamlQuoted()}")
            appendLine("mode: ${position.mode.name.yamlQuoted()}")
            appendLine("action: ${(decision?.decision?.submission?.action?.name ?: UNKNOWN_ACTION).yamlQuoted()}")
            appendLine("status: ${position.status.name.yamlQuoted()}")
            appendLine("entry_time: ${entryTime.yamlQuoted()}")
            appendLine("exit_time: ${exitTime.yamlQuoted()}")
            appendLine("entry_price_jpy: ${position.averageEntryPriceJpy}")
            appendLine("exit_price_jpy: ${exitExecution?.priceJpy ?: position.currentPriceJpy}")
            appendLine("size_btc: ${position.sizeBtc}")
            appendLine("realized_pnl_jpy: ${tradePnlJpy.toMoneyText()}")
            appendLine("fee_jpy: ${feeJpy.toMoneyText()}")
            appendYamlList("setup_tags", setupTags)
            appendLine("created: ${entryTime.yamlQuoted()}")
            appendLine("updated: ${exitTime.yamlQuoted()}")
            appendYamlList("tags", tradeTags(position))
            appendLine("---")
            appendLine()
        }
        val body = when (val existingBody = existingMarkdownBody(relativePath)) {
            ExistingMarkdownBody.Missing -> buildTradeBody(
                closedPosition = closedPosition,
                decision = decision,
                entryExecution = entryExecution,
                exitExecution = exitExecution,
            )
            is ExistingMarkdownBody.Parsed -> existingBody.body
            ExistingMarkdownBody.Unparseable -> return null
        }

        return frontmatter + body
    }

    private fun buildTradeBody(
        closedPosition: ClosedPaperPosition,
        decision: DecisionJournalRecord?,
        entryExecution: Execution?,
        exitExecution: Execution?,
    ): String {
        val position = closedPosition.position
        val decisionReason = decision?.decision?.submission?.reasonJa.orEmpty()

        return buildString {
            appendLine("# ${position.symbol} ${position.positionId.shortId()}")
            appendLine()
            appendLine("## 判断理由")
            appendBodyLine(decisionReason)
            appendLine()
            appendLine("## TradePlan")
            appendTradePlan(decision)
            appendLine()
            appendLine("## 反証")
            appendFalsification(decision)
            appendLine()
            appendLine("## 実行")
            appendExecution(entryExecution, exitExecution, closedPosition)
            appendLine()
            appendLine("## 学び候補")
        }
    }

    private fun StringBuilder.appendTradePlan(decision: DecisionJournalRecord?) {
        val tradePlan = decision?.tradePlan ?: return

        appendLine("- thesis: ${tradePlan.draft.thesisJa}")
        appendLine("- invalidation:")
        tradePlan.draft.invalidationConditionsJa.forEach { condition ->
            appendLine("  - $condition")
        }
        tradePlan.draft.targetPriceJpy?.let { targetPrice ->
            appendLine("- target: ${targetPrice.toPlainString()} JPY")
        }
        tradePlan.draft.timeStopAt?.let { timeStopAt ->
            appendLine("- time stop: ${timeStopAt.toJstText()}")
        }
    }

    private fun StringBuilder.appendFalsification(decision: DecisionJournalRecord?) {
        val falsification = decision?.falsification ?: return

        appendLine("- verdict: ${falsification.verdict.name}")
        appendLine("- reason: ${falsification.reasonJa}")
    }

    private fun StringBuilder.appendExecution(
        entryExecution: Execution?,
        exitExecution: Execution?,
        closedPosition: ClosedPaperPosition,
    ) {
        entryExecution?.let { execution ->
            appendLine("- entry: ${execution.priceJpy} JPY / ${execution.sizeBtc} BTC / ${execution.executedAt.toJstText()}")
        }
        exitExecution?.let { execution ->
            appendLine("- exit: ${execution.priceJpy} JPY / ${execution.sizeBtc} BTC / ${execution.executedAt.toJstText()}")
        }
        appendLine("- fee: ${closedPosition.totalFeeJpy().toMoneyText()} JPY")
        appendLine("- realized pnl: ${closedPosition.tradeNetPnlJpy().toMoneyText()} JPY")
    }

    private fun buildDailyNote(
        relativePath: String,
        tradingDate: LocalDate,
        closedPositions: List<ClosedPaperPosition>,
        decisions: List<DecisionJournalRecord>,
        llmRuns: List<LlmRunRecord>,
    ): String? {
        val stats = dailyStatsFor(tradingDate, closedPositions, decisions, llmRuns)
        val frontmatter = buildDailyFrontmatter(tradingDate, stats)
        val body = when (val existingBody = existingMarkdownBody(relativePath)) {
            ExistingMarkdownBody.Missing -> defaultDailyBody(tradingDate)
            is ExistingMarkdownBody.Parsed -> existingBody.body
            ExistingMarkdownBody.Unparseable -> return null
        }

        return frontmatter + body
    }

    private fun buildDailyFrontmatter(tradingDate: LocalDate, stats: DailyStats): String {
        return buildString {
            appendLine("---")
            appendLine("type: ${DAILY_TYPE.yamlQuoted()}")
            appendLine("date: ${tradingDate.toString().yamlQuoted()}")
            appendLine("symbol: ${tradingConfig.symbol.apiSymbol.yamlQuoted()}")
            appendLine("mode: ${tradingConfig.mode.name.yamlQuoted()}")
            appendLine("trades: ${stats.trades}")
            appendLine("wins: ${stats.wins}")
            appendLine("losses: ${stats.losses}")
            appendLine("no_trades: ${stats.noTrades}")
            appendLine("net_pnl_jpy: ${stats.netPnlJpy.toMoneyText()}")
            appendLine("gross_profit_jpy: ${stats.grossProfitJpy.toMoneyText()}")
            appendLine("gross_loss_jpy: ${stats.grossLossJpy.toMoneyText()}")
            appendLine("llm_failures: ${stats.llmFailures}")
            appendLine("created: ${tradingDate.dailyCreatedAtText().yamlQuoted()}")
            appendYamlList("tags", dailyTags())
            appendLine("---")
            appendLine()
        }
    }

    private fun existingMarkdownBody(relativePath: String): ExistingMarkdownBody {
        val path = vaultPath.resolve(relativePath)

        if (!Files.exists(path)) {
            return ExistingMarkdownBody.Missing
        }

        val content = Files.readString(path, StandardCharsets.UTF_8)

        if (!content.startsWith(FRONTMATTER_START)) {
            return ExistingMarkdownBody.Unparseable
        }

        val frontmatterEndIndex = content.indexOf(FRONTMATTER_END, startIndex = FRONTMATTER_START.length)

        if (frontmatterEndIndex < 0) {
            return ExistingMarkdownBody.Unparseable
        }

        val body = content
            .substring(frontmatterEndIndex + FRONTMATTER_END.length)
            .removePrefix("\n")

        return ExistingMarkdownBody.Parsed(body)
    }

    private fun defaultDailyBody(tradingDate: LocalDate): String {
        return buildString {
            appendLine("# Daily Review $tradingDate")
            appendLine()
            DAILY_HEADINGS.forEach { heading ->
                appendLine("## $heading")
                appendLine()
            }
        }
    }

    private fun collectTradingDates(
        closedPositions: List<ClosedPaperPosition>,
        decisions: List<DecisionJournalRecord>,
        llmRuns: List<LlmRunRecord>,
    ): List<LocalDate> {
        val today = clock.instant().atTradingDate()
        val closedPositionDates = closedPositions.map { position -> position.closedAtInstant().atTradingDate() }
        val decisionDates = decisions.map { decision -> decision.decision.createdAt.atTradingDate() }
        val llmRunDates = llmRuns.map { run -> run.startedAt.atTradingDate() }

        return (closedPositionDates + decisionDates + llmRunDates + today)
            .toSet()
            .sorted()
    }

    private fun dailyStatsFor(
        tradingDate: LocalDate,
        closedPositions: List<ClosedPaperPosition>,
        decisions: List<DecisionJournalRecord>,
        llmRuns: List<LlmRunRecord>,
    ): DailyStats {
        val positionsForDate = closedPositions.filter { position -> position.closedAtInstant().atTradingDate() == tradingDate }
        val pnlValues = positionsForDate.map { position -> position.tradeNetPnlJpy() }
        val decisionsForDate = decisions.filter { decision -> decision.decision.createdAt.atTradingDate() == tradingDate }
        val llmRunsForDate = llmRuns.filter { run -> run.startedAt.atTradingDate() == tradingDate }
        val grossProfit = pnlValues
            .filter { pnl -> pnl > BigDecimal.ZERO }
            .sumBigDecimal()
        val grossLoss = pnlValues
            .filter { pnl -> pnl < BigDecimal.ZERO }
            .sumBigDecimal()
            .abs()

        return DailyStats(
            trades = positionsForDate.size,
            wins = pnlValues.count { pnl -> pnl > BigDecimal.ZERO },
            losses = pnlValues.count { pnl -> pnl < BigDecimal.ZERO },
            noTrades = decisionsForDate.count { decision -> decision.decision.submission.action == DecisionAction.NO_TRADE },
            netPnlJpy = grossProfit.subtract(grossLoss),
            grossProfitJpy = grossProfit,
            grossLossJpy = grossLoss,
            llmFailures = llmRunsForDate.count { run -> run.status == LLM_RUN_STATUS_FAILED },
        )
    }

    private fun ClosedPaperPosition.tradeNoteRelativePath(): String {
        val date = closedAtInstant().atTradingDate()
        val shortId = position.positionId.shortId()

        return "Trades/${date.year}/${date.monthValue.twoDigits()}/$date-$shortId.md"
    }

    private fun dailyNoteRelativePath(tradingDate: LocalDate): String {
        return "Daily/${tradingDate.year}/${tradingDate.monthValue.twoDigits()}/$tradingDate.md"
    }

    private fun ClosedPaperPosition.entryExecution(): Execution? {
        return executions
            .filter { execution -> execution.side == OrderSide.BUY }
            .minByOrNull { execution -> Instant.parse(execution.executedAt) }
    }

    private fun ClosedPaperPosition.exitExecution(): Execution? {
        return executions
            .filter { execution -> execution.side == OrderSide.SELL }
            .maxByOrNull { execution -> Instant.parse(execution.executedAt) }
    }

    private fun ClosedPaperPosition.totalFeeJpy(): BigDecimal {
        return executions.sumOf { execution -> execution.feeJpy.toBigDecimal() }
    }

    private fun ClosedPaperPosition.tradeNetPnlJpy(): BigDecimal {
        val realizedSellPnl = executions
            .filter { execution -> execution.side == OrderSide.SELL }
            .sumOf { execution -> execution.realizedPnlJpy.toBigDecimal() }
        val buyFees = executions
            .filter { execution -> execution.side == OrderSide.BUY }
            .sumOf { execution -> execution.feeJpy.toBigDecimal() }

        return realizedSellPnl.subtract(buyFees)
    }

    private fun ClosedPaperPosition.closedAtInstant(): Instant {
        return Instant.parse(requireNotNull(position.closedAt))
    }

    private fun tradeTags(position: Position): List<String> {
        return listOf(
            CRYPTO_TAG,
            position.symbol.lowercase(),
            position.mode.name.lowercase(),
            TRADE_TAG,
        )
    }

    private fun dailyTags(): List<String> {
        return listOf(
            DAILY_TAG,
            TRADING_REVIEW_TAG,
            tradingConfig.symbol.apiSymbol.lowercase(),
        )
    }
}

/**
 * Daily note の機械集計値。
 *
 * @param trades closed trade 数
 * @param wins net PnL が正の trade 数
 * @param losses net PnL が負の trade 数
 * @param noTrades NO_TRADE decision 数
 * @param netPnlJpy 日次 net PnL
 * @param grossProfitJpy 正の trade PnL 合計
 * @param grossLossJpy 負の trade PnL の絶対値合計
 * @param llmFailures FAILED status の llm_runs 数
 */
private data class DailyStats(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val noTrades: Int,
    val netPnlJpy: BigDecimal,
    val grossProfitJpy: BigDecimal,
    val grossLossJpy: BigDecimal,
    val llmFailures: Int,
)

/**
 * 既存 Markdown body の読み取り結果。
 */
private sealed interface ExistingMarkdownBody {

    /**
     * note file がまだ存在しない。
     */
    data object Missing : ExistingMarkdownBody

    /**
     * frontmatter 付き note として body を読めた。
     *
     * @param body frontmatter 後の本文
     */
    data class Parsed(
        val body: String,
    ) : ExistingMarkdownBody

    /**
     * frontmatter として安全に parse できないため、上書きしない。
     */
    data object Unparseable : ExistingMarkdownBody
}

/**
 * vault file 書き込み結果。
 */
private enum class VaultWriteState {
    /**
     * file を作成または置換した。
     */
    WRITTEN,

    /**
     * 既存 file と内容が一致していた。
     */
    UNCHANGED,
}

/**
 * writer の出力件数を集計する mutable counter。
 */
private class ObsidianWriteCounter {

    private var writtenFiles = 0
    private var unchangedFiles = 0

    /**
     * file ごとの書き込み結果を集計する。
     */
    fun record(state: VaultWriteState) {
        when (state) {
            VaultWriteState.WRITTEN -> writtenFiles += 1
            VaultWriteState.UNCHANGED -> unchangedFiles += 1
        }
    }

    /**
     * immutable summary に変換する。
     */
    fun toSummary(): ObsidianWriteSummary {
        return ObsidianWriteSummary(
            writtenFiles = writtenFiles,
            unchangedFiles = unchangedFiles,
        )
    }
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

private fun StringBuilder.appendBodyLine(value: String) {
    if (value.isBlank()) {
        return
    }

    appendLine(value)
}

private fun List<BigDecimal>.sumBigDecimal(): BigDecimal {
    return fold(BigDecimal.ZERO) { total, value -> total.add(value) }
}

private fun BigDecimal.toMoneyText(): String {
    return setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString()
}

private fun String.shortId(): String {
    return take(POSITION_SHORT_ID_LENGTH)
}

private fun Int.twoDigits(): String {
    return toString().padStart(length = 2, padChar = '0')
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

private fun String?.yamlNullable(): String {
    return this?.yamlQuoted() ?: "null"
}

private fun String.toJstText(): String {
    return Instant.parse(this).toJstText()
}

private fun Instant.toJstText(): String {
    return atZone(TRADING_DATE_ZONE).format(ISO_OFFSET_DATE_TIME_FORMATTER)
}

private fun Instant.atTradingDate(): LocalDate {
    return atZone(TRADING_DATE_ZONE).toLocalDate()
}

private fun LocalDate.dailyCreatedAtText(): String {
    return atStartOfDay(TRADING_DATE_ZONE).format(ISO_OFFSET_DATE_TIME_FORMATTER)
}

/**
 * note 読み取り範囲の将来許容秒数。
 */
private const val READ_RANGE_FUTURE_TOLERANCE_SECONDS = 1L

/**
 * money 値の出力 scale。
 */
private const val MONEY_SCALE = 8

/**
 * position ID を note 名へ使う長さ。
 */
private const val POSITION_SHORT_ID_LENGTH = 8

/**
 * trade note の type。
 */
private const val TRADE_TYPE = "trade"

/**
 * daily note の type。
 */
private const val DAILY_TYPE = "daily_review"

/**
 * decision が解決できない場合の action。
 */
private const val UNKNOWN_ACTION = "UNKNOWN"

/**
 * dashboard note path。
 */
private const val TRADING_DASHBOARD_PATH = "00_MOC/Trading Dashboard.md"

/**
 * crypto tag。
 */
private const val CRYPTO_TAG = "crypto"

/**
 * trade tag。
 */
private const val TRADE_TAG = "trade"

/**
 * daily tag。
 */
private const val DAILY_TAG = "daily"

/**
 * trading review tag。
 */
private const val TRADING_REVIEW_TAG = "trading-review"

/**
 * frontmatter 開始 marker。
 */
private const val FRONTMATTER_START = "---\n"

/**
 * frontmatter 終了 marker。
 */
private const val FRONTMATTER_END = "\n---\n"

/**
 * YAML double quoted scalar にそのまま入れない制御文字の境界。
 */
private const val YAML_CONTROL_CHARACTER_BOUNDARY = 0x20

/**
 * Obsidian skeleton として必ず作る directory。
 */
private val REQUIRED_DIRECTORIES = listOf(
    "Trades",
    "Daily",
    "Knowledge",
    "Knowledge/Setups",
    "Knowledge/FailureModes",
    "Knowledge/Calibration",
    "Knowledge/WeeklyReviews",
    "Knowledge/PromptCandidates",
    "Instruments",
    "00_MOC",
)

/**
 * Daily note に作る空 section 見出し。
 */
private val DAILY_HEADINGS = listOf(
    "今日の相場",
    "成績",
    "良かった判断",
    "悪かった判断",
    "安全床/override",
    "明日への仮説",
)

/**
 * Trading Dashboard の初期 Markdown。
 */
private val TRADING_DASHBOARD_MARKDOWN = """
    | # Trading Dashboard
    |
    | ## Recent Trades
    |
    | ```dataview
    | TABLE entry_time, exit_time, realized_pnl_jpy, setup_tags
    | FROM "Trades"
    | SORT exit_time DESC
    | LIMIT 20
    | ```
    |
    | ## Daily PnL
    |
    | ```dataview
    | TABLE trades, wins, losses, net_pnl_jpy, llm_failures
    | FROM "Daily"
    | SORT date DESC
    | LIMIT 30
    | ```
    |
""".trimMargin()

/**
 * 取引日判定に使う timezone。
 */
private val TRADING_DATE_ZONE: ZoneId = EQUITY_SNAPSHOT_TRADING_DATE_ZONE

/**
 * frontmatter/body 用 ISO offset formatter。
 */
private val ISO_OFFSET_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
