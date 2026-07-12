@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.ClosedTradeFact
import me.matsumo.fukurou.trading.evaluation.DailyTradePnlFact
import me.matsumo.fukurou.trading.evaluation.DecisionActionCount
import me.matsumo.fukurou.trading.evaluation.EvaluationLlmUsageQueryResult
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationScope
import me.matsumo.fukurou.trading.evaluation.EvaluationTradeQueryResult
import me.matsumo.fukurou.trading.evaluation.KillCriterionStats
import me.matsumo.fukurou.trading.evaluation.LlmModelUsage
import me.matsumo.fukurou.trading.evaluation.LlmPhaseUsageFact
import me.matsumo.fukurou.trading.evaluation.LlmTokenUsage
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * evaluation route の HTTP contract を検証するテスト。
 */
class EvaluationRouteTest {
    @Test
    fun evaluationRoutesExposeEmptyPartialAndFullLifecyclePeriods() = testApplication {
        val lifecycleRepository = object : EvaluationRepository by FakeEvaluationRepository {
            override suspend fun resolveScope(epochId: String?, cohort: String?): Result<EvaluationScope> =
                Result.success(
                    EvaluationScope(
                        accountEpochId = UUID.fromString("00000000-0000-0000-0000-000000000184"),
                        cohort = me.matsumo.fukurou.trading.domain.EvaluationCohort.CURRENT,
                        executionSemanticsVersion = "PAPER_WS_V1",
                        initialCashJpy = BigDecimal("1000000"),
                        lifecycleFromInclusive = Instant.parse("2026-07-02T00:00:00Z"),
                    ),
                )
        }
        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = lifecycleRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val empty = client.get("/evaluation/benchmark?from=2026-06-01&to=2026-06-02").bodyAsText()
        val partial = client.get("/evaluation/summary?from=2026-07-01&to=2026-07-03").bodyAsText()
        val full = client.get("/evaluation/costs?from=2026-07-03&to=2026-07-04").bodyAsText()

        assertTrue(empty.contains("\"populationState\":\"EMPTY_LIFECYCLE\""))
        assertTrue(empty.contains("\"effectiveFrom\":null"))
        assertTrue(empty.contains("\"state\":\"EMPTY_LIFECYCLE\""))
        assertTrue(partial.contains("\"populationState\":\"PARTIAL_LIFECYCLE\""))
        assertTrue(partial.contains("\"effectiveFrom\":\"2026-07-02\""))
        assertTrue(full.contains("\"populationState\":\"FULL_REQUESTED_PERIOD\""))
    }

    @Test
    fun evaluationReportUsesEffectiveLifecyclePeriod() = testApplication {
        val lifecycleRepository = object : EvaluationRepository by FakeEvaluationRepository {
            override suspend fun resolveScope(epochId: String?, cohort: String?): Result<EvaluationScope> =
                Result.success(
                    EvaluationScope(
                        accountEpochId = UUID.fromString("00000000-0000-0000-0000-000000000184"),
                        cohort = me.matsumo.fukurou.trading.domain.EvaluationCohort.CURRENT,
                        executionSemanticsVersion = "PAPER_WS_V1",
                        initialCashJpy = BigDecimal("1000000"),
                        lifecycleFromInclusive = Instant.parse("2026-07-02T00:00:00Z"),
                    ),
                )
        }
        application {
            module(
                readinessProbe = { true }, clock = fixedClock(), evaluationRepository = lifecycleRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }
        val accepted = client.post("/evaluation/reports/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"CUSTOM","from":"2026-07-01","toInclusive":"2026-07-03"}""")
        }.bodyAsText()
        val revisionId = requireNotNull(Regex("\\\"revisionId\\\":\\\"([^\\\"]+)").find(accepted)).groupValues[1]
        var revision = ""
        repeat(100) {
            if (!revision.contains("\"status\":\"SUCCEEDED\"")) {
                revision = client.get("/evaluation/reports/revisions/$revisionId").bodyAsText()
                if (!revision.contains("\"status\":\"SUCCEEDED\"")) delay(10)
            }
        }

        assertTrue(revision.contains("\"populationState\":\"PARTIAL_LIFECYCLE\""))
        assertTrue(revision.contains("\"effectiveFrom\":\"2026-07-02\""))

        val emptyAccepted = client.post("/evaluation/reports/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"CUSTOM","from":"2026-06-01","toInclusive":"2026-06-02"}""")
        }.bodyAsText()
        val emptyRevisionId = requireNotNull(
            Regex("\\\"revisionId\\\":\\\"([^\\\"]+)").find(emptyAccepted),
        ).groupValues[1]
        var emptyRevision = ""
        repeat(100) {
            if (!emptyRevision.contains("\"status\":\"SUCCEEDED\"")) {
                emptyRevision = client.get("/evaluation/reports/revisions/$emptyRevisionId").bodyAsText()
                if (!emptyRevision.contains("\"status\":\"SUCCEEDED\"")) delay(10)
            }
        }
        assertTrue(emptyRevision.contains("\"populationState\":\"EMPTY_LIFECYCLE\""))
        assertTrue(emptyRevision.contains("\"benchmark\":{\"baselineEquityJpy\":null,\"points\":[]"))
        assertTrue(emptyRevision.contains("\"state\":\"EMPTY_LIFECYCLE\""))
        assertTrue(!emptyRevision.contains("\"factId\":\"benchmark."))

        val fullAccepted = client.post("/evaluation/reports/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"CUSTOM","from":"2026-07-03","toInclusive":"2026-07-04"}""")
        }.bodyAsText()
        val fullRevisionId = requireNotNull(
            Regex("\\\"revisionId\\\":\\\"([^\\\"]+)").find(fullAccepted),
        ).groupValues[1]
        var fullRevision = ""
        repeat(100) {
            if (!fullRevision.contains("\"status\":\"SUCCEEDED\"")) {
                fullRevision = client.get("/evaluation/reports/revisions/$fullRevisionId").bodyAsText()
                if (!fullRevision.contains("\"status\":\"SUCCEEDED\"")) delay(10)
            }
        }
        assertTrue(fullRevision.contains("\"populationState\":\"FULL_REQUESTED_PERIOD\""))
        assertTrue(fullRevision.contains("\"effectiveFrom\":\"2026-07-03\""))
    }

    @Test
    fun evaluationReportScopeKeyCodecKeepsLegacyAndVersionedIdentityCanonical() {
        val legacy = EvaluationReportScopeKey.decode("PRESET:30D")
        val versioned = legacy.version("epoch-1", "CURRENT")

        assertEquals("PRESET:30D", legacy.encode())
        assertEquals("PRESET:30D|EPOCH:epoch-1|COHORT:CURRENT", versioned.encode())
        assertEquals(versioned, EvaluationReportScopeKey.decode(versioned.encode()))
        assertTrue(runCatching { EvaluationReportScopeKey.decode("PRESET:30D|EPOCH:epoch-1") }.isFailure)
        assertTrue(
            runCatching {
                EvaluationReportScopeKey.decode("PRESET:30D|EPOCH:epoch-1|COHORT:CURRENT|EXTRA:value")
            }.isFailure,
        )
    }

    @Test
    fun evaluationReport_failsClosedBeforeGenerationWhenUsageSnapshotIsTruncated() = testApplication {
        val truncatedRepository = object : EvaluationRepository by FakeEvaluationRepository {
            override suspend fun fetchReportSnapshot(period: EvaluationPeriod) =
                FakeEvaluationRepository.fetchReportSnapshot(period).map { snapshot ->
                    snapshot.copy(usages = snapshot.usages.copy(truncated = true))
                }

            override suspend fun fetchReportSnapshot(
                period: EvaluationPeriod,
                scope: me.matsumo.fukurou.trading.evaluation.EvaluationScope,
            ) = fetchReportSnapshot(period)
        }
        application {
            module(
                readinessProbe = { true }, clock = fixedClock(), evaluationRepository = truncatedRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }
        val accepted = client.post("/evaluation/reports/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"PRESET","days":30}""")
        }.bodyAsText()
        val jobId = requireNotNull(Regex("\\\"jobId\\\":\\\"([^\\\"]+)").find(accepted)).groupValues[1]
        var terminal = ""
        var attempts = 100
        while (attempts > 0 && !terminal.contains("\"status\":\"FAILED\"")) {
            terminal = client.get("/evaluation/reports/jobs/$jobId").bodyAsText()
            if (!terminal.contains("\"status\":\"FAILED\"")) delay(10)
            attempts -= 1
        }
        assertTrue(terminal.contains("USAGE_SNAPSHOT_TRUNCATED"))
    }

    @Test
    fun evaluationRoutes_returnOkShapes() = testApplication {
        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val paths = listOf(
            "/evaluation/summary",
            "/evaluation/setups",
            "/evaluation/calibration",
            "/evaluation/benchmark",
            "/evaluation/costs",
        )

        paths.forEach { path ->
            val response = client.get(path)
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status, path)
            assertTrue(body.contains("\"period\""), path)
        }

        val summaryBody = client.get("/evaluation/summary").bodyAsText()
        val costsBody = client.get("/evaluation/costs").bodyAsText()

        assertTrue(summaryBody.contains("\"killCriterion\""))
        assertTrue(costsBody.contains("\"truncated\""))
        assertTrue(costsBody.contains("\"knownCostUsd\":\"0.01\""))
        assertTrue(costsBody.contains("\"knownCostUsd\":null"))
        assertTrue(costsBody.contains("\"unpricedPhaseCount\":1"))
        assertTrue(costsBody.contains("\"unattributedTokenPhaseCount\":1"))
        assertTrue(costsBody.contains("\"reasoningOutputTokens\":2"))
    }

    @Test
    fun evaluationRoutes_returnBadRequestForInvalidDate() = testApplication {
        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/evaluation/summary?from=not-a-date")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("ISO-8601"))
    }

    @Test
    fun evaluationRoutes_requestHistoricalCandlesFromReferenceDate() = testApplication {
        val marketDataSource = RecordingEvaluationMarketDataSource()

        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = marketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/evaluation/benchmark?from=2026-01-01&to=2026-01-02")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            expected = listOf(224),
            actual = marketDataSource.requestedLimits,
        )
    }

    @Test
    fun evaluationBenchmark_legacyScopeDoesNotExposeSyntheticBaselineSeriesOrReturns() = testApplication {
        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/evaluation/benchmark?cohort=LEGACY_PRE_WS")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("BASELINE_NOT_COMPARABLE", body.getValue("state").jsonPrimitive.content)
        assertTrue(body.getValue("baselineEquityJpy") is kotlinx.serialization.json.JsonNull)
        assertTrue(body.getValue("returns") is kotlinx.serialization.json.JsonNull)
        assertTrue(body.getValue("points").jsonArray.isEmpty())
    }

    @Test
    fun evaluationRoutes_returnBadRequestWhenDailyCandleLimitExceedsMaximum() = testApplication {
        val marketDataSource = RecordingEvaluationMarketDataSource()

        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = marketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/evaluation/benchmark?from=2024-01-01&to=2024-01-02")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("maximum is 500"))
        assertEquals(
            expected = emptyList(),
            actual = marketDataSource.requestedLimits,
        )
    }

    @Test
    fun evaluationReport_acceptsPresetAndCustomManualJobs() = testApplication {
        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val preset = client.post("/evaluation/reports/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"PRESET","days":30}""")
        }
        val custom = client.post("/evaluation/reports/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"CUSTOM","from":"2026-06-01","toInclusive":"2026-06-30"}""")
        }

        assertEquals(HttpStatusCode.Accepted, preset.status)
        assertEquals(HttpStatusCode.Accepted, custom.status)

        val revisionId = requireNotNull(Regex("\\\"revisionId\\\":\\\"([^\\\"]+)").find(custom.bodyAsText()))
            .groupValues[1]
        var revisionBody: String? = null
        var attemptsRemaining = 100
        while (revisionBody == null && attemptsRemaining > 0) {
            val revision = client.get("/evaluation/reports/revisions/$revisionId")
            if (revision.status == HttpStatusCode.OK) revisionBody = revision.bodyAsText()
            if (revisionBody == null) delay(20)
            attemptsRemaining -= 1
        }
        val generated = requireNotNull(revisionBody)
        assertTrue(generated.contains("\"scopeKey\":\"CUSTOM:2026-06-01:2026-06-30|EPOCH:"))
        assertTrue(generated.contains("|COHORT:CURRENT\""))
        assertTrue(generated.contains("\"benchmark\""))
        assertTrue(generated.contains("\"calibration\""))
        assertTrue(generated.contains("\"performanceLattice\""))
        assertTrue(generated.contains("\"integrity\""))
        assertTrue(generated.contains("\"inputAsOf\":\"2026-07-03T00:00:00Z\""))
        assertTrue(generated.contains("\"snapshotId\":"))
        assertTrue(generated.contains("\"promptVersion\":\"evaluation-report-prompt-v1\""))
        assertTrue(generated.contains("\"schemaVersion\":\"evaluation-report-schema-v1\""))
    }

    @Test
    fun evaluationReport_rejectsRevisionScopeMismatchForPreviewAndPin() = testApplication {
        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }
        val accepted = client.post("/evaluation/reports/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"PRESET","days":30,"cohort":"CURRENT"}""")
        }.bodyAsText()
        val revisionId = requireNotNull(Regex("\\\"revisionId\\\":\\\"([^\\\"]+)").find(accepted)).groupValues[1]
        var ready = false
        repeat(100) {
            if (client.get("/evaluation/reports/revisions/$revisionId").status == HttpStatusCode.OK) ready = true
            if (!ready) delay(10)
        }
        assertTrue(ready)

        val preview = client.get(
            "/evaluation/reports/revisions/$revisionId?scopeKey=PRESET:30D&cohort=LEGACY_PRE_WS",
        )
        val pin = client.put("/evaluation/reports/pins") {
            contentType(ContentType.Application.Json)
            setBody("""{"scopeKey":"PRESET:30D","revisionId":"$revisionId","cohort":"LEGACY_PRE_WS"}""")
        }
        val delete = client.delete("/evaluation/reports/pins?scopeKey=PRESET:30D&cohort=CURRENT")

        assertEquals(HttpStatusCode.BadRequest, preview.status)
        assertTrue(preview.bodyAsText().contains("REPORT_SCOPE_MISMATCH"))
        assertEquals(HttpStatusCode.BadRequest, pin.status)
        assertTrue(pin.bodyAsText().contains("REPORT_SCOPE_MISMATCH"))
        assertEquals(HttpStatusCode.NoContent, delete.status)
    }

    @Test
    fun evaluationReport_rejectsInvalidCustomRange() = testApplication {
        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
                evaluationRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.post("/evaluation/reports/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"CUSTOM","from":"2026-06-30","toInclusive":"2026-06-01"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

/**
 * route test 用 fake evaluation repository。
 */
private object FakeEvaluationRepository : EvaluationRepository {

    override suspend fun fetchClosedTrades(period: EvaluationPeriod, limit: Int): Result<EvaluationTradeQueryResult> {
        return Result.success(
            EvaluationTradeQueryResult(
                trades = listOf(testTrade()),
                truncated = false,
            ),
        )
    }

    override suspend fun fetchClosedTrades(
        period: EvaluationPeriod,
        limit: Int,
        scope: EvaluationScope,
    ): Result<EvaluationTradeQueryResult> = fetchClosedTrades(period, limit)

    override suspend fun countDecisionRuns(period: EvaluationPeriod): Result<Int> {
        return Result.success(2)
    }

    override suspend fun countDecisionsByAction(period: EvaluationPeriod): Result<List<DecisionActionCount>> {
        return Result.success(
            listOf(
                DecisionActionCount("ENTER", 1),
                DecisionActionCount("NO_TRADE", 1),
            ),
        )
    }

    override suspend fun fetchDailyTradePnl(period: EvaluationPeriod): Result<List<DailyTradePnlFact>> {
        return Result.success(
            listOf(
                DailyTradePnlFact(
                    closedAt = Instant.parse("2026-07-02T00:00:00Z"),
                    pnlJpy = BigDecimal("100"),
                ),
            ),
        )
    }

    override suspend fun sumTradePnlBefore(instant: Instant): Result<BigDecimal> {
        return Result.success(BigDecimal.ZERO)
    }

    override suspend fun sumTradePnlBefore(instant: Instant, scope: EvaluationScope): Result<BigDecimal> {
        return Result.success(BigDecimal.ZERO)
    }

    override suspend fun fetchInitialCashJpy(): Result<BigDecimal> {
        return Result.success(BigDecimal("100000"))
    }

    override suspend fun fetchLlmPhaseUsages(
        period: EvaluationPeriod,
        limit: Int,
    ): Result<EvaluationLlmUsageQueryResult> {
        return Result.success(
            EvaluationLlmUsageQueryResult(
                facts = listOf(
                    LlmPhaseUsageFact(
                        decisionRunId = "claude-run",
                        provider = "claude",
                        phase = "proposer",
                        occurredAt = Instant.parse("2026-07-02T00:00:00Z"),
                        usage = LlmUsageDetails(
                            totalCostUsd = BigDecimal("0.01"),
                            numTurns = 1,
                            durationMs = 100,
                            usage = null,
                            modelUsages = listOf(
                                LlmModelUsage(
                                    model = "claude-test",
                                    usage = LlmTokenUsage(
                                        inputTokens = 3,
                                        outputTokens = 1,
                                        cacheCreationInputTokens = null,
                                        cacheReadInputTokens = null,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    LlmPhaseUsageFact(
                        decisionRunId = "codex-run",
                        provider = "codex",
                        phase = "falsifier",
                        occurredAt = Instant.parse("2026-07-02T00:01:00Z"),
                        usage = LlmUsageDetails(
                            totalCostUsd = null,
                            numTurns = null,
                            durationMs = null,
                            usage = LlmTokenUsage(
                                inputTokens = 10,
                                outputTokens = 4,
                                reasoningOutputTokens = 2,
                                cacheCreationInputTokens = null,
                                cacheReadInputTokens = 5,
                            ),
                            modelUsages = listOf(
                                LlmModelUsage(
                                    model = "gpt-test",
                                    usage = LlmTokenUsage(
                                        inputTokens = 10,
                                        outputTokens = 4,
                                        reasoningOutputTokens = 2,
                                        cacheCreationInputTokens = null,
                                        cacheReadInputTokens = 5,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    LlmPhaseUsageFact(
                        decisionRunId = "unattributed-run",
                        provider = "claude",
                        phase = "reflection",
                        occurredAt = Instant.parse("2026-07-02T00:02:00Z"),
                        usage = LlmUsageDetails(
                            totalCostUsd = BigDecimal.ZERO,
                            numTurns = null,
                            durationMs = null,
                            usage = LlmTokenUsage(
                                inputTokens = 1,
                                outputTokens = 1,
                                cacheCreationInputTokens = null,
                                cacheReadInputTokens = null,
                            ),
                            modelUsages = emptyList(),
                        ),
                    ),
                ),
                truncated = false,
            ),
        )
    }

    override suspend fun fetchKillCriterionStats(): Result<KillCriterionStats> {
        return Result.success(KillCriterionStats(closedTrades = 1, profitFactor = null))
    }
}

/**
 * route test 用 fake market data source。
 */
private object FakeEvaluationMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(
            listOf(
                Candle(
                    symbol = "BTC",
                    interval = CandleInterval.ONE_DAY,
                    openTime = "2026-07-01T00:00:00Z",
                    open = "10000000",
                    high = "10100000",
                    low = "9900000",
                    close = "10000000",
                    volume = "1.0",
                ),
                Candle(
                    symbol = "BTC",
                    interval = CandleInterval.ONE_DAY,
                    openTime = "2026-07-02T00:00:00Z",
                    open = "10000000",
                    high = "10200000",
                    low = "9900000",
                    close = "10100000",
                    volume = "1.0",
                ),
            ),
        )
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.failure(UnsupportedOperationException("not used"))
    }
}

/**
 * 日足取得 limit を記録する route test 用 market data source。
 */
private class RecordingEvaluationMarketDataSource : MarketDataSource {

    val requestedLimits = mutableListOf<Int>()

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        requestedLimits += limit

        return Result.success(
            listOf(
                Candle(
                    symbol = "BTC",
                    interval = CandleInterval.ONE_DAY,
                    openTime = "2026-01-01T00:00:00Z",
                    open = "10000000",
                    high = "10100000",
                    low = "9900000",
                    close = "10000000",
                    volume = "1.0",
                ),
                Candle(
                    symbol = "BTC",
                    interval = CandleInterval.ONE_DAY,
                    openTime = "2026-01-02T00:00:00Z",
                    open = "10000000",
                    high = "10200000",
                    low = "9900000",
                    close = "10100000",
                    volume = "1.0",
                ),
            ),
        )
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.failure(UnsupportedOperationException("not used"))
    }
}

private fun testTrade(): ClosedTradeFact {
    return ClosedTradeFact(
        positionId = UUID.randomUUID(),
        openedAt = Instant.parse("2026-07-01T00:00:00Z"),
        closedAt = Instant.parse("2026-07-02T00:00:00Z"),
        sizeBtc = BigDecimal("0.01"),
        averageEntryPriceJpy = BigDecimal("10000000"),
        entryWeightedProtectiveStopPriceJpy = BigDecimal("9900000"),
        highestPriceSinceEntryJpy = BigDecimal("10100000"),
        lowestPriceSinceEntryJpy = BigDecimal("9950000"),
        tradePnlJpy = BigDecimal("100"),
        estimatedWinProbability = BigDecimal("0.7"),
        setupTags = listOf("route-test"),
        llmProvider = "claude",
    )
}

private fun fixedClock(): Clock {
    return Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC)
}
