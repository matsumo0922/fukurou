package me.matsumo.fukurou.trading.reflection

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.ClosedTradeFact
import me.matsumo.fukurou.trading.evaluation.DailyTradePnlFact
import me.matsumo.fukurou.trading.evaluation.DecisionActionCount
import me.matsumo.fukurou.trading.evaluation.EvaluationLlmUsageQueryResult
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationTradeQueryResult
import me.matsumo.fukurou.trading.evaluation.InMemoryLlmRunRepository
import me.matsumo.fukurou.trading.evaluation.KillCriterionStats
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmPhaseUsageFact
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Comparator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ReflectionRunner の collector / builder / writer 配線を検証するテスト。
 */
class ReflectionRunnerTest {

    @Test
    fun runOnce_writesReflectionReportsAndDoesNotModifyDailyNotes() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-reflection-runner")
        val dailyPath = vaultPath.resolve("Daily/2026/07/2026-07-02.md")
        val dailyContent = "# Daily Review 2026-07-02\n\n手書きメモは残す。\n"
        val runner = reflectionRunner(vaultPath)

        try {
            Files.createDirectories(dailyPath.parent)
            Files.writeString(dailyPath, dailyContent)

            val summary = runner.runOnce().getOrThrow()
            val dailyReflection = Files.readString(vaultPath.resolve("Knowledge/DailyReflections/2026-07-02.md"))
            val weeklyReview = Files.readString(vaultPath.resolve("Knowledge/WeeklyReviews/2026-W27.md"))
            val calibration = Files.readString(vaultPath.resolve("Knowledge/Calibration/ConfidenceCalibration.md"))
            val taxonomy = Files.readString(vaultPath.resolve("Knowledge/Setups/TagTaxonomy-2026-W27.md"))

            assertEquals(7, summary.writtenFiles)
            assertEquals(dailyContent, Files.readString(dailyPath))
            assertTrue(Files.isDirectory(vaultPath.resolve("Knowledge/DailyReflections")))
            assertTrue(Files.exists(vaultPath.resolve("Knowledge/DailyReflections/2026-07-01.md")))
            assertTrue(Files.exists(vaultPath.resolve("Knowledge/WeeklyReviews/2026-W26.md")))
            assertTrue(Files.exists(vaultPath.resolve("Knowledge/Setups/TagTaxonomy-2026-W26.md")))
            assertTrue(dailyReflection.contains("type: \"daily_reflection\""))
            assertTrue(dailyReflection.contains("sample_size_warning: true"))
            assertTrue(dailyReflection.contains("truncated: false"))
            assertFalse(dailyReflection.contains("generated_at:"))
            assertFalse(dailyReflection.contains("sample_size_warning::"))
            assertFalse(dailyReflection.contains("truncated::"))
            assertTrue(weeklyReview.contains("type: \"weekly_reflection\""))
            assertTrue(calibration.contains("type: \"confidence_calibration\""))
            assertTrue(taxonomy.contains("type: \"setup_tag_taxonomy\""))
            assertTrue(taxonomy.contains("breakout"))
            assertReflectionSecretRedacted(dailyReflection, weeklyReview, calibration, taxonomy)
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun runOnce_marksTruncatedInputsInReports() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-reflection-truncated")
        val runner = reflectionRunner(
            vaultPath = vaultPath,
            queryLimit = 1,
            secondDecision = true,
            secondLlmRun = true,
            closedTradesTruncated = true,
            llmUsagesTruncated = true,
        )

        try {
            runner.runOnce().getOrThrow()

            val dailyReflection = Files.readString(vaultPath.resolve("Knowledge/DailyReflections/2026-07-02.md"))

            assertTrue(dailyReflection.contains("truncated: true"))
            assertTrue(dailyReflection.contains("decision_truncated: true"))
            assertTrue(dailyReflection.contains("llm_run_truncated: true"))
            assertTrue(dailyReflection.contains("closed_trade_truncated: true"))
            assertTrue(dailyReflection.contains("llm_usage_truncated: true"))
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun runOnce_doesNotRewriteReportsWhenOnlyClockAdvancesInsideSameTradingDate() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-reflection-unchanged")
        val firstRunner = reflectionRunner(vaultPath)
        val secondRunner = reflectionRunner(
            vaultPath = vaultPath,
            writerClock = Clock.fixed(Instant.parse("2026-07-02T12:05:00Z"), ZoneOffset.UTC),
        )

        try {
            val firstSummary = firstRunner.runOnce().getOrThrow()
            val secondSummary = secondRunner.runOnce().getOrThrow()

            assertEquals(7, firstSummary.writtenFiles)
            assertEquals(0, firstSummary.unchangedFiles)
            assertEquals(0, secondSummary.writtenFiles)
            assertEquals(7, secondSummary.unchangedFiles)
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun runOnce_limitsRecentDecisionRows() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-reflection-recent-decisions")
        val runner = reflectionRunner(
            vaultPath = vaultPath,
            secondDecision = true,
            recentDecisionLimit = 1,
        )

        try {
            runner.runOnce().getOrThrow()

            val dailyReflection = Files.readString(vaultPath.resolve("Knowledge/DailyReflections/2026-07-02.md"))

            assertTrue(dailyReflection.contains("recent_decisions_rendered: 1"))
            assertTrue(dailyReflection.contains("recent_decisions_omitted: 1"))
            assertTrue(dailyReflection.contains("decision_input_truncated: false"))
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun runOnce_marksSampleWarningForEmptyTradesAndClearsItAtThreshold() = runBlocking {
        val emptyVaultPath = Files.createTempDirectory("fukurou-reflection-empty-trades")
        val thresholdVaultPath = Files.createTempDirectory("fukurou-reflection-threshold-trades")
        val emptyRunner = reflectionRunner(
            vaultPath = emptyVaultPath,
            trades = emptyList(),
        )
        val thresholdRunner = reflectionRunner(
            vaultPath = thresholdVaultPath,
            trades = closedTrades(DEFAULT_REFLECTION_SAMPLE_WARNING_TRADE_COUNT),
        )

        try {
            emptyRunner.runOnce().getOrThrow()
            thresholdRunner.runOnce().getOrThrow()

            val emptyDailyReflection =
                Files.readString(emptyVaultPath.resolve("Knowledge/DailyReflections/2026-07-02.md"))
            val thresholdDailyReflection =
                Files.readString(thresholdVaultPath.resolve("Knowledge/DailyReflections/2026-07-02.md"))

            assertTrue(emptyDailyReflection.contains("closed_trades: 0"))
            assertTrue(emptyDailyReflection.contains("sample_size_warning: true"))
            assertTrue(thresholdDailyReflection.contains("closed_trades: 30"))
            assertTrue(thresholdDailyReflection.contains("sample_size_warning: false"))
        } finally {
            deleteRecursively(emptyVaultPath)
            deleteRecursively(thresholdVaultPath)
        }
    }

    @Test
    fun runOnce_mergesCanonicalSetupAliasesAndEscapesMarkdownTags() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-reflection-tag-taxonomy")
        val runner = reflectionRunner(
            vaultPath = vaultPath,
            trades = listOf(
                closedTrade(
                    positionId = "11111111-1111-1111-1111-111111111111",
                    setupTags = listOf("Break Out", "Pipe|Tag"),
                    tradePnlJpy = BigDecimal("390"),
                ),
                closedTrade(
                    positionId = "77777777-7777-7777-7777-777777777777",
                    setupTags = listOf("break\nout", "pipe|tag"),
                    tradePnlJpy = BigDecimal("-110"),
                ),
            ),
            linkedDecisionSetupTags = listOf("Break Out", "break\nout", "Pipe|Tag", "pipe|tag"),
        )

        try {
            runner.runOnce().getOrThrow()

            val weeklyReview = Files.readString(vaultPath.resolve("Knowledge/WeeklyReviews/2026-W27.md"))
            val taxonomy = Files.readString(vaultPath.resolve("Knowledge/Setups/TagTaxonomy-2026-W27.md"))

            assertTrue(
                taxonomy.contains(
                    "| break-out | Break Out, break out | 2 | 2 | 3.5454545455 | 0.5000000000 | 0.0933333334 |",
                ),
            )
            assertTrue(
                taxonomy.contains(
                    "| pipe\\|tag | Pipe\\|Tag, pipe\\|tag | 2 | 2 | 3.5454545455 | 0.5000000000 | " +
                        "0.0933333334 |",
                ),
            )
            assertTrue(taxonomy.contains("- break-out: Break Out, break out"))
            assertTrue(taxonomy.contains("- pipe\\|tag: Pipe\\|Tag, pipe\\|tag"))
            assertFalse(taxonomy.contains("break\nout"))
            assertTrue(weeklyReview.contains("| Pipe\\|Tag | 1 |"))
            assertTrue(weeklyReview.contains("| pipe\\|tag | 1 |"))
        } finally {
            deleteRecursively(vaultPath)
        }
    }
}

private suspend fun reflectionRunner(
    vaultPath: Path,
    queryLimit: Int = DEFAULT_REFLECTION_QUERY_LIMIT,
    secondDecision: Boolean = false,
    secondLlmRun: Boolean = false,
    closedTradesTruncated: Boolean = false,
    llmUsagesTruncated: Boolean = false,
    trades: List<ClosedTradeFact> = listOf(closedTrade()),
    linkedDecisionSetupTags: List<String> = listOf("breakout", "trend-follow"),
    recentDecisionLimit: Int = DEFAULT_REFLECTION_RECENT_DECISION_LIMIT,
    writerClock: Clock = WRITER_CLOCK,
): ReflectionRunner {
    val decisionRepository = InMemoryDecisionRepository(FIXED_CLOCK)
    val llmRunRepository = InMemoryLlmRunRepository()
    val evaluationRepository = StaticReflectionEvaluationRepository(
        trades = trades,
        decisionRunCount = if (secondDecision) 2 else 1,
        actionCounts = listOf(
            DecisionActionCount(action = DecisionAction.ENTER.name, count = 1),
            DecisionActionCount(action = DecisionAction.NO_TRADE.name, count = if (secondDecision) 1 else 0),
        ),
        usageFacts = listOf(llmUsageFact()),
        closedTradesTruncated = closedTradesTruncated,
        llmUsagesTruncated = llmUsagesTruncated,
    )

    submitLinkedDecision(
        repository = decisionRepository,
        setupTags = linkedDecisionSetupTags,
    )
    if (secondDecision) {
        submitNoTradeDecision(decisionRepository)
    }
    llmRunRepository.finish(failedLlmRun()).getOrThrow()
    if (secondLlmRun) {
        llmRunRepository.finish(failedLlmRun("failed-run-2", FIXED_INSTANT.plusSeconds(60))).getOrThrow()
    }

    return ReflectionRunner(
        dataCollector = ReflectionDataCollector(
            decisionRepository = decisionRepository,
            llmRunRepository = llmRunRepository,
            evaluationRepository = evaluationRepository,
            clock = writerClock,
            queryLimit = queryLimit,
        ),
        reportBuilder = ReflectionReportBuilder(
            tradingConfig = TradingBotConfig(
                reflection = ReflectionConfig(
                    recentDecisionLimit = recentDecisionLimit,
                ),
            ),
        ),
        vaultWriter = ReflectionVaultWriter(
            vaultPath = vaultPath,
            redactor = SecretRedactor(setOf("reflection-secret-token")),
        ),
    )
}

private suspend fun submitLinkedDecision(repository: InMemoryDecisionRepository, setupTags: List<String>) {
    val result = repository.submitDecision(
        DecisionSubmission(
            invocationId = "run-linked",
            llmProvider = "claude",
            promptHash = "prompt-hash",
            systemPromptVersion = "system-prompt-v1",
            marketSnapshotId = "snapshot-1",
            action = DecisionAction.ENTER,
            setupTags = setupTags,
            estimatedWinProbability = BigDecimal("0.73"),
            expectedRMultiple = BigDecimal("1.80"),
            roundTripCostR = BigDecimal("0.05"),
            toolEvidenceIds = listOf("tool-1"),
            factCheckJson = """{"ticker":true}""",
            selfReviewJson = """{"reasonsNotToTrade":[]}""",
            reasonJa = "reflection-secret-token を含む判断です。",
            missingDataJa = emptyList(),
            noTradeConditionsJa = emptyList(),
            entryIntent = EntryIntentDraft(
                symbol = TradingSymbol.BTC,
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                sizeBtc = BigDecimal("0.0050"),
                priceJpy = null,
                protectiveStopPriceJpy = BigDecimal("9700000"),
                takeProfitPriceJpy = BigDecimal("10500000"),
            ),
            tradePlan = TradePlanDraft(
                parentTradePlanId = null,
                revisionCount = 0,
                symbol = TradingSymbol.BTC,
                thesisJa = "1時間足の上昇継続に乗る。",
                invalidationConditionsJa = listOf("直近安値割れ", "出来高急減"),
                targetPriceJpy = BigDecimal("10500000"),
                timeStopAt = FIXED_INSTANT.plusSeconds(3600),
                setupTags = setupTags,
            ),
        ),
    ).getOrThrow()

    repository.submitFalsification(
        FalsificationSubmission(
            intentId = requireNotNull(result.tradeIntent?.intentId),
            verdict = FalsificationVerdict.APPROVED,
            llmProvider = "codex",
            reasonJa = "反証観点でも entry を拒否する理由が不足しています。",
        ),
    ).getOrThrow()
}

private suspend fun submitNoTradeDecision(repository: InMemoryDecisionRepository) {
    repository.submitDecision(
        DecisionSubmission(
            invocationId = "run-no-trade",
            llmProvider = "claude",
            promptHash = "prompt-hash",
            systemPromptVersion = "system-prompt-v1",
            marketSnapshotId = "snapshot-2",
            action = DecisionAction.NO_TRADE,
            setupTags = listOf("range"),
            estimatedWinProbability = BigDecimal("0.12"),
            expectedRMultiple = null,
            roundTripCostR = null,
            toolEvidenceIds = listOf("tool-2"),
            factCheckJson = """{"ticker":true}""",
            selfReviewJson = """{"reasonsNotToTrade":["出来高不足"]}""",
            reasonJa = "材料不足のため見送ります。",
            missingDataJa = listOf("orderbook"),
            noTradeConditionsJa = listOf("出来高が戻るまで待つ"),
            entryIntent = null,
            tradePlan = null,
        ),
    ).getOrThrow()
}

private fun closedTrade(
    positionId: String = "11111111-1111-1111-1111-111111111111",
    setupTags: List<String> = listOf("breakout"),
    tradePnlJpy: BigDecimal = BigDecimal("390"),
): ClosedTradeFact {
    return ClosedTradeFact(
        positionId = UUID.fromString(positionId),
        openedAt = FIXED_INSTANT.plusSeconds(600),
        closedAt = FIXED_INSTANT.plusSeconds(7_200),
        sizeBtc = BigDecimal("0.005"),
        averageEntryPriceJpy = BigDecimal("10000000"),
        initialProtectiveStopPriceJpy = BigDecimal("9700000"),
        highestPriceSinceEntryJpy = BigDecimal("10100000"),
        lowestPriceSinceEntryJpy = BigDecimal("9990000"),
        tradePnlJpy = tradePnlJpy,
        estimatedWinProbability = BigDecimal("0.73"),
        setupTags = setupTags,
        llmProvider = "claude",
    )
}

private fun closedTrades(count: Int): List<ClosedTradeFact> {
    return (1..count).map { index ->
        closedTrade(positionId = UUID(0L, index.toLong()).toString())
    }
}

private fun failedLlmRun(invocationId: String = "failed-run", startedAt: Instant = FIXED_INSTANT): LlmRunFinish {
    return LlmRunFinish(
        invocationId = invocationId,
        mode = TradingMode.PAPER,
        symbol = TradingSymbol.BTC,
        triggerKind = null,
        status = LLM_RUN_STATUS_FAILED,
        startedAt = startedAt,
        finishedAt = startedAt.plusSeconds(2),
        errorMessage = "redacted failure",
    )
}

private fun llmUsageFact(): LlmPhaseUsageFact {
    return LlmPhaseUsageFact(
        decisionRunId = "run-linked",
        provider = "claude",
        phase = "proposer",
        occurredAt = FIXED_INSTANT.plusSeconds(20),
        usage = LlmUsageDetails(
            totalCostUsd = BigDecimal("0.0123"),
            numTurns = 1,
            durationMs = 1_000,
            usage = null,
            modelUsages = emptyList(),
        ),
    )
}

private fun assertReflectionSecretRedacted(vararg contents: String) {
    contents.forEach { content ->
        assertFalse(content.contains("reflection-secret-token"))
        assertTrue(content.contains("[REDACTED]") || !content.contains("判断"))
    }
}

private class StaticReflectionEvaluationRepository(
    private val trades: List<ClosedTradeFact>,
    private val decisionRunCount: Int,
    private val actionCounts: List<DecisionActionCount>,
    private val usageFacts: List<LlmPhaseUsageFact>,
    private val closedTradesTruncated: Boolean,
    private val llmUsagesTruncated: Boolean,
) : EvaluationRepository {

    override suspend fun fetchClosedTrades(period: EvaluationPeriod, limit: Int): Result<EvaluationTradeQueryResult> {
        return Result.success(
            EvaluationTradeQueryResult(
                trades = trades.take(limit),
                truncated = closedTradesTruncated,
            ),
        )
    }

    override suspend fun countDecisionRuns(period: EvaluationPeriod): Result<Int> {
        return Result.success(decisionRunCount)
    }

    override suspend fun countDecisionsByAction(period: EvaluationPeriod): Result<List<DecisionActionCount>> {
        return Result.success(actionCounts)
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
        return Result.success(
            EvaluationLlmUsageQueryResult(
                facts = usageFacts.take(limit),
                truncated = llmUsagesTruncated,
            ),
        )
    }

    override suspend fun fetchKillCriterionStats(): Result<KillCriterionStats> {
        return Result.success(
            KillCriterionStats(
                closedTrades = trades.size,
                profitFactor = null,
            ),
        )
    }
}

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) {
        return
    }

    Files.walk(path).use { paths ->
        paths
            .sorted(Comparator.reverseOrder())
            .forEach { currentPath -> Files.deleteIfExists(currentPath) }
    }
}

/**
 * test の固定現在時刻。
 */
private val FIXED_INSTANT: Instant = Instant.parse("2026-07-02T00:00:00Z")

/**
 * repository 保存時刻用の固定 clock。
 */
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)

/**
 * writer が日次 reflection を生成する固定 clock。
 */
private val WRITER_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
