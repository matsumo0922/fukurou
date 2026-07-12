package me.matsumo.fukurou.trading.knowledge

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.InMemoryLlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ObsidianVaultWriter の Markdown 生成と file 書き込みを検証するテスト。
 */
class ObsidianVaultWriterTest {

    @Test
    fun inMemoryRangeQueries_filterOrderLimitAndReturnFieldValues() = runBlocking {
        val mutableClock = MutableTestClock(FIXED_INSTANT)
        val decisionRepository = InMemoryDecisionRepository(mutableClock)
        val llmRunRepository = InMemoryLlmRunRepository()
        val firstPosition = closedPosition()
        val secondPosition = closedPosition().copy(
            positionId = "77777777-7777-7777-7777-777777777777",
            closedAt = EXIT_INSTANT.plusSeconds(60).toString(),
        )
        val ledgerRepository = InMemoryPaperLedgerRepository(
            positions = listOf(firstPosition, secondPosition),
            executions = executionsFor(firstPosition) + executionsFor(secondPosition),
            decisionRunIdsByPositionId = mapOf(
                firstPosition.positionId to "run-linked",
                secondPosition.positionId to "run-second",
            ),
        )

        submitLinkedDecision(decisionRepository, "run-linked", "最初の判断です。")
        mutableClock.currentInstant = FIXED_INSTANT.plusSeconds(60)
        submitNoTradeDecision(decisionRepository)
        llmRunRepository.finish(failedLlmRun()).getOrThrow()
        llmRunRepository.finish(
            failedLlmRun().copy(
                invocationId = "failed-run-2",
                startedAt = FIXED_INSTANT.plusSeconds(60),
                finishedAt = FIXED_INSTANT.plusSeconds(62),
            ),
        ).getOrThrow()

        val decisions = decisionRepository.findDecisionsCreatedBetween(
            from = FIXED_INSTANT.minusSeconds(1),
            toExclusive = FIXED_INSTANT.plusSeconds(120),
            limit = 2,
        ).getOrThrow()
        val limitedDecisions = decisionRepository.findDecisionsCreatedBetween(
            from = FIXED_INSTANT.minusSeconds(1),
            toExclusive = FIXED_INSTANT.plusSeconds(120),
            limit = 1,
        ).getOrThrow()
        val llmRuns = llmRunRepository.findRunsStartedBetween(
            from = FIXED_INSTANT.minusSeconds(1),
            toExclusive = FIXED_INSTANT.plusSeconds(120),
            limit = 2,
        ).getOrThrow()
        val closedPositions = ledgerRepository.findClosedPositionsClosedBetween(
            from = FIXED_INSTANT,
            toExclusive = EXIT_INSTANT.plusSeconds(120),
            limit = 2,
        ).getOrThrow()
        val limitedClosedPositions = ledgerRepository.findClosedPositionsClosedBetween(
            from = FIXED_INSTANT,
            toExclusive = EXIT_INSTANT.plusSeconds(120),
            limit = 1,
        ).getOrThrow()

        assertEquals(listOf("run-linked", "run-no-trade"), decisions.map { record -> record.decision.submission.invocationId })
        assertEquals(listOf("breakout", "trend-follow"), decisions.first().decision.submission.setupTags)
        assertEquals("最初の判断です。", decisions.first().decision.submission.reasonJa)
        assertEquals(listOf("run-no-trade"), limitedDecisions.map { record -> record.decision.submission.invocationId })
        assertEquals(listOf("failed-run", "failed-run-2"), llmRuns.map { run -> run.invocationId })
        assertEquals(
            listOf("failed-run-2"),
            llmRunRepository.findRunsStartedBetween(
                from = FIXED_INSTANT.minusSeconds(1),
                toExclusive = FIXED_INSTANT.plusSeconds(120),
                limit = 1,
            ).getOrThrow().map { run -> run.invocationId },
        )
        assertEquals(LLM_RUN_STATUS_FAILED, llmRuns.first().status)
        assertEquals(listOf(firstPosition.positionId, secondPosition.positionId), closedPositions.map { record -> record.position.positionId })
        assertEquals("run-linked", closedPositions.first().decisionRunId)
        assertEquals("10100000.00000000", closedPositions.first().executions.last().priceJpy)
        assertEquals(listOf(secondPosition.positionId), limitedClosedPositions.map { record -> record.position.positionId })
    }

    @Test
    fun writeOnce_generatesTradeDailyDashboardAndSkeletonFromDatabaseValues() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-obsidian-writer")
        val fixture = writerFixture(vaultPath)

        try {
            val summary = fixture.writer.writeOnce().getOrThrow()
            val tradeContent = Files.readString(fixture.tradeNotePath)
            val dailyContent = Files.readString(fixture.dailyNotePath)

            assertEquals(3, summary.writtenFiles)
            assertTrue(Files.isDirectory(vaultPath.resolve("Knowledge/Setups")))
            assertTrue(Files.isDirectory(vaultPath.resolve("Instruments")))
            assertTrue(Files.exists(vaultPath.resolve("00_MOC/Trading Dashboard.md")))
            assertTrue(tradeContent.contains("trade_id: \"11111111-1111-1111-1111-111111111111\""))
            assertTrue(tradeContent.contains("decision_id: \"run-linked\""))
            assertTrue(tradeContent.contains("entry_price_jpy: 10000000.00000000"))
            assertTrue(tradeContent.contains("exit_price_jpy: 10100000.00000000"))
            assertTrue(tradeContent.contains("realized_pnl_jpy: 390.00000000"))
            assertTrue(tradeContent.contains("- thesis: 1時間足の上昇継続に乗る。"))
            assertTrue(tradeContent.contains("- verdict: APPROVED"))
            assertTrue(tradeContent.contains("- entry: 10000000.00000000 JPY / 0.005000000000 BTC"))
            assertTrue(dailyContent.contains("trades: 1"))
            assertTrue(dailyContent.contains("wins: 1"))
            assertTrue(dailyContent.contains("losses: 0"))
            assertTrue(dailyContent.contains("no_trades: 1"))
            assertTrue(dailyContent.contains("net_pnl_jpy: 390.00000000"))
            assertTrue(dailyContent.contains("gross_profit_jpy: 390.00000000"))
            assertTrue(dailyContent.contains("gross_loss_jpy: 0.00000000"))
            assertTrue(dailyContent.contains("llm_failures: 1"))
            assertTrue(dailyContent.contains("## 今日の相場"))
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun writeOnce_generatesLedgerOnlyTradeNoteWhenDecisionLinkIsMissing() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-obsidian-ledger-only")
        val fixture = writerFixture(
            vaultPath = vaultPath,
            decisionRunId = null,
            includeDecision = false,
        )

        try {
            fixture.writer.writeOnce().getOrThrow()

            val tradeContent = Files.readString(fixture.tradeNotePath)

            assertTrue(tradeContent.contains("decision_id: null"))
            assertTrue(tradeContent.contains("action: \"UNKNOWN\""))
            assertTrue(tradeContent.contains("- exit: 10100000.00000000 JPY / 0.005000000000 BTC"))
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun writeOnce_preservesExistingTradeBodyWhenFrontmatterIsRegenerated() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-obsidian-trade-body")
        val fixture = writerFixture(vaultPath)

        try {
            fixture.writer.writeOnce().getOrThrow()
            Files.writeString(
                fixture.tradeNotePath,
                Files.readString(fixture.tradeNotePath) + "\nA-3 が追記した学びは残す。\n",
            )

            fixture.writer.writeOnce().getOrThrow()

            val tradeContent = Files.readString(fixture.tradeNotePath)

            assertTrue(tradeContent.contains("realized_pnl_jpy: 390.00000000"))
            assertTrue(tradeContent.contains("A-3 が追記した学びは残す。"))
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun writeOnce_preservesExistingDailyBodyWhenFrontmatterIsRegenerated() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-obsidian-daily-body")
        val fixture = writerFixture(vaultPath)

        try {
            Files.createDirectories(fixture.dailyNotePath.parent)
            Files.writeString(
                fixture.dailyNotePath,
                """
                ---
                trades: 0
                ---

                # Daily Review 2026-07-02

                ## 良かった判断
                手書きメモは残す。
                """.trimIndent(),
            )

            fixture.writer.writeOnce().getOrThrow()

            val dailyContent = Files.readString(fixture.dailyNotePath)

            assertTrue(dailyContent.contains("trades: 1"))
            assertTrue(dailyContent.contains("手書きメモは残す。"))
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun writeOnce_doesNotOverwriteDailyNoteWhenFrontmatterCannotBeParsed() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-obsidian-malformed-daily")
        val fixture = writerFixture(vaultPath)
        val malformedContent = """
            # Daily Review 2026-07-02

            手書き本文だけがある。
        """.trimIndent()

        try {
            Files.createDirectories(fixture.dailyNotePath.parent)
            Files.writeString(fixture.dailyNotePath, malformedContent)

            fixture.writer.writeOnce().getOrThrow()

            assertEquals(malformedContent, Files.readString(fixture.dailyNotePath))
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun writeOnce_isIdempotentAndRegeneratesDeletedVault() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-obsidian-idempotent")
        val fixture = writerFixture(vaultPath)

        try {
            fixture.writer.writeOnce().getOrThrow()

            val secondSummary = fixture.writer.writeOnce().getOrThrow()

            assertEquals(0, secondSummary.writtenFiles)
            assertEquals(2, secondSummary.unchangedFiles)

            deleteRecursively(vaultPath)

            val regeneratedSummary = fixture.writer.writeOnce().getOrThrow()

            assertTrue(regeneratedSummary.writtenFiles >= 3)
            assertTrue(Files.exists(fixture.tradeNotePath))
            assertTrue(Files.exists(fixture.dailyNotePath))
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun writeOnce_redactsLongNotesWithoutTruncatingExistingBody() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-obsidian-long-redaction")
        val fixture = writerFixture(
            vaultPath = vaultPath,
            redactor = SecretRedactor(setOf("long-note-secret")),
        )
        val longBody = "x".repeat(9_000) + "long-note-secret-tail"

        try {
            Files.createDirectories(fixture.dailyNotePath.parent)
            Files.writeString(
                fixture.dailyNotePath,
                """
                ---
                trades: 0
                ---

                $longBody
                """.trimIndent(),
            )

            fixture.writer.writeOnce().getOrThrow()

            val dailyContent = Files.readString(fixture.dailyNotePath)

            assertFalse(dailyContent.contains("long-note-secret"))
            assertTrue(dailyContent.contains("[REDACTED]-tail"))
            assertTrue(dailyContent.length > 9_000)
        } finally {
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun writeOnce_redactsSecretsBeforeWritingNotes() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-obsidian-redaction")
        val fixture = writerFixture(
            vaultPath = vaultPath,
            decisionReason = "秘密値 obsidian-secret-token を含む判断です。",
            redactor = SecretRedactor(setOf("obsidian-secret-token")),
        )

        try {
            fixture.writer.writeOnce().getOrThrow()

            val tradeContent = Files.readString(fixture.tradeNotePath)

            assertFalse(tradeContent.contains("obsidian-secret-token"))
            assertTrue(tradeContent.contains("[REDACTED]"))
        } finally {
            deleteRecursively(vaultPath)
        }
    }
}

/**
 * Obsidian writer test の生成物 path。
 *
 * @param writer 検証対象 writer
 * @param tradeNotePath trade note path
 * @param dailyNotePath daily note path
 */
private data class ObsidianWriterFixture(
    val writer: ObsidianVaultWriter,
    val tradeNotePath: Path,
    val dailyNotePath: Path,
)

/**
 * repository range query test 用に現在時刻を動かせる clock。
 *
 * @param currentInstant 現在時刻として返す instant
 */
private class MutableTestClock(
    var currentInstant: Instant,
) : Clock() {

    override fun getZone(): ZoneOffset {
        return ZoneOffset.UTC
    }

    override fun instant(): Instant {
        return currentInstant
    }

    override fun withZone(zone: java.time.ZoneId): Clock {
        return this
    }
}

private suspend fun writerFixture(
    vaultPath: Path,
    decisionRunId: String? = "run-linked",
    includeDecision: Boolean = true,
    decisionReason: String = "ブレイク継続を狙います。",
    redactor: SecretRedactor = SecretRedactor(emptySet()),
): ObsidianWriterFixture {
    val decisionRepository = InMemoryDecisionRepository(FIXED_CLOCK)
    val llmRunRepository = InMemoryLlmRunRepository()
    val position = closedPosition()
    val executions = executionsFor(position)
    val ledgerRepository = InMemoryPaperLedgerRepository(
        positions = listOf(position),
        executions = executions,
        decisionRunIdsByPositionId = mapOf(position.positionId to decisionRunId),
    )

    if (includeDecision) {
        submitLinkedDecision(decisionRepository, requireNotNull(decisionRunId), decisionReason)
        submitNoTradeDecision(decisionRepository)
    }
    llmRunRepository.finish(failedLlmRun()).getOrThrow()

    return ObsidianWriterFixture(
        writer = ObsidianVaultWriter(
            vaultPath = vaultPath,
            decisionRepository = decisionRepository,
            llmRunRepository = llmRunRepository,
            paperLedgerRepository = ledgerRepository,
            tradingConfig = TradingBotConfig(),
            redactor = redactor,
            clock = WRITER_CLOCK,
        ),
        tradeNotePath = vaultPath.resolve("Trades/2026/07/2026-07-02-11111111.md"),
        dailyNotePath = vaultPath.resolve("Daily/2026/07/2026-07-02.md"),
    )
}

private suspend fun submitLinkedDecision(
    repository: InMemoryDecisionRepository,
    invocationId: String,
    decisionReason: String,
) {
    val result = repository.submitDecision(
        DecisionSubmission(
            invocationId = invocationId,
            llmProvider = "claude",
            promptHash = "prompt-hash",
            systemPromptVersion = "system-prompt-v1",
            marketSnapshotId = "snapshot-1",
            action = DecisionAction.ENTER,
            setupTags = listOf("breakout", "trend-follow"),
            estimatedWinProbability = BigDecimal("0.73"),
            expectedRMultiple = BigDecimal("1.80"),
            roundTripCostR = BigDecimal("0.05"),
            toolEvidenceIds = listOf("tool-1"),
            factCheckJson = """{"ticker":true}""",
            selfReviewJson = """{"reasonsNotToTrade":[]}""",
            reasonJa = decisionReason,
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
                invalidationPredicates = listOf(
                    TradePlanInvalidationPredicate(
                        type = TradePlanInvalidationType.LAST_PRICE_AT_OR_BELOW,
                        decimalThresholdJpy = BigDecimal("9700000"),
                    ),
                ),
                targetPriceJpy = BigDecimal("10500000"),
                timeStopAt = FIXED_INSTANT.plusSeconds(3600),
                setupTags = listOf("breakout", "trend-follow"),
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
            setupTags = emptyList(),
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

private fun closedPosition(): Position {
    return Position(
        positionId = "11111111-1111-1111-1111-111111111111",
        tradeGroupId = "22222222-2222-2222-2222-222222222222",
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.CLOSED,
        openedAt = ENTRY_INSTANT.toString(),
        closedAt = EXIT_INSTANT.toString(),
        sizeBtc = "0.005000000000",
        averageEntryPriceJpy = "10000000.00000000",
        currentPriceJpy = "10100000.00000000",
        currentStopLossJpy = null,
        currentTakeProfitJpy = null,
        unrealizedPnlJpy = "0.00000000",
        unrealizedR = "0",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "10100000.00000000",
        lowestPriceSinceEntryJpy = "10000000.00000000",
    )
}

private fun executionsFor(position: Position): List<Execution> {
    return listOf(
        Execution(
            executionId = "33333333-3333-3333-3333-333333333333",
            orderId = "44444444-4444-4444-4444-444444444444",
            positionId = position.positionId,
            symbol = "BTC",
            mode = TradingMode.PAPER,
            side = OrderSide.BUY,
            priceJpy = "10000000.00000000",
            sizeBtc = "0.005000000000",
            feeJpy = "50.00000000",
            realizedPnlJpy = "0.00000000",
            liquidity = ExecutionLiquidity.TAKER,
            executedAt = ENTRY_INSTANT.toString(),
        ),
        Execution(
            executionId = "55555555-5555-5555-5555-555555555555",
            orderId = "66666666-6666-6666-6666-666666666666",
            positionId = position.positionId,
            symbol = "BTC",
            mode = TradingMode.PAPER,
            side = OrderSide.SELL,
            priceJpy = "10100000.00000000",
            sizeBtc = "0.005000000000",
            feeJpy = "60.00000000",
            realizedPnlJpy = "440.00000000",
            liquidity = ExecutionLiquidity.TAKER,
            executedAt = EXIT_INSTANT.toString(),
        ),
    )
}

private fun failedLlmRun(): LlmRunFinish {
    return LlmRunFinish(
        invocationId = "failed-run",
        mode = TradingMode.PAPER,
        symbol = TradingSymbol.BTC,
        triggerKind = null,
        status = LLM_RUN_STATUS_FAILED,
        startedAt = FIXED_INSTANT,
        finishedAt = FIXED_INSTANT.plusSeconds(2),
        errorMessage = "redacted failure",
    )
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
 * entry 約定時刻。
 */
private val ENTRY_INSTANT: Instant = Instant.parse("2026-07-02T00:10:00Z")

/**
 * exit 約定時刻。
 */
private val EXIT_INSTANT: Instant = Instant.parse("2026-07-02T02:00:00Z")

/**
 * test の固定 clock。
 */
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)

/**
 * writer が closed trade を読み取れる exit 後の固定時刻。
 */
private val WRITER_CLOCK: Clock = Clock.fixed(EXIT_INSTANT.plusSeconds(3600), ZoneOffset.UTC)
