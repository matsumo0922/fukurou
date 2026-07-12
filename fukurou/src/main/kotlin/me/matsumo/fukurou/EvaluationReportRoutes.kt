@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.evaluation.BenchmarkCalculationRequest
import me.matsumo.fukurou.trading.evaluation.ClosedTradeFact
import me.matsumo.fukurou.trading.evaluation.EvaluationMath
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationScope
import me.matsumo.fukurou.trading.domain.EvaluationCohort
import me.matsumo.fukurou.trading.evaluation.intersectLifecycle
import me.matsumo.fukurou.trading.evaluation.OutcomeRidgeChartFacts
import me.matsumo.fukurou.trading.evaluation.MarketRegimeLabel
import me.matsumo.fukurou.trading.evaluation.report.EvaluationClaimValidator
import me.matsumo.fukurou.trading.evaluation.report.EvaluationReportClaim
import me.matsumo.fukurou.trading.evaluation.report.EvaluationReportFact
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.market.MarketDataSource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val EVALUATION_REPORT_TAG = "評価レポート"
private val ReportZone = ZoneId.of("Asia/Tokyo")

@OptIn(ExperimentalKtorApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun Route.evaluationReportRoutes(dependencies: EvaluationRouteDependencies) {
    val store = EvaluationReportStore(
        repository = dependencies.repository,
        marketDataSource = dependencies.marketDataSource,
        symbol = dependencies.tradingConfig.symbol,
        llmInvoker = dependencies.llmInvoker,
        llmInvocationAuditor = dependencies.llmInvocationAuditor,
        environment = dependencies.environment,
        persistence = dependencies.database?.let { database ->
            EvaluationReportPersistence(
                database = database,
                runnerConfig = dependencies.tradingConfig.runner,
                staleAfter = dependencies.tradingConfig.daemon.launchReservationStaleAfter,
                clock = dependencies.clock,
            )
        },
        clock = dependencies.clock,
    )

    post("/evaluation/reports/jobs") {
        val request = runCatching { call.receive<EvaluationReportGenerateRequest>() }.getOrNull()
        val evaluationScope = request?.let { value ->
            dependencies.repository?.resolveScope(value.epochId, value.cohort)?.getOrNull()
        }
        val scope = if (evaluationScope == null) null else request.toScope(evaluationScope)
        if (scope == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("use preset days 7, 30, 90 or a valid CUSTOM from/toInclusive range"))
            return@post
        }

        val job = runCatching { store.request(scope) }.getOrElse { error ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error.message ?: "report admission failed"))
            return@post
        }
        if (job.status == "REJECTED") {
            job.retryAfterSeconds?.let { seconds -> call.response.headers.append(HttpHeaders.RetryAfter, seconds.toString()) }
            val status = if (job.failureCode == "CONCURRENT_INVOCATION") HttpStatusCode.Conflict else HttpStatusCode.TooManyRequests
            call.respond(status, job)
            return@post
        }
        call.application.launch {
            store.generate(scope, job)
        }
        call.respond(HttpStatusCode.Accepted, job)
    }.describe {
        summary = "評価レポートを手動生成する"
        description = "complete calendar days の immutable facts snapshot を固定し、typed claim の検証済みレポート revision を生成します。"
        tag(EVALUATION_REPORT_TAG)
        requestBody { schema = jsonSchema<EvaluationReportGenerateRequest>() }
        responses {
            HttpStatusCode.Accepted { schema = jsonSchema<EvaluationReportJobResponse>() }
            HttpStatusCode.BadRequest { schema = jsonSchema<ErrorResponse>() }
            HttpStatusCode.Conflict {
                description = "共通 LLM reservation が使用中です。Retry-After と rejected job を返します。"
                schema = jsonSchema<EvaluationReportJobResponse>()
            }
            HttpStatusCode.TooManyRequests {
                description = "起動予算または report request rate を超過しました。Retry-After と rejected job を返します。"
                schema = jsonSchema<EvaluationReportJobResponse>()
            }
            HttpStatusCode.InternalServerError { schema = jsonSchema<ErrorResponse>() }
        }
    }

    get("/evaluation/reports/default") {
        val scopeKey = call.reportScopeKey(dependencies.repository) ?: return@get
        val report = store.default(scopeKey)
        if (report == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("report has not been generated"))
            return@get
        }

        call.respond(report)
    }.describe {
        summary = "既定の評価レポートを取得する"
        description = "選択期間へ pin された immutable revision と deterministic evidence snapshot を返します。current context は含みません。"
        tag(EVALUATION_REPORT_TAG)
        parameters {
            query("scopeKey") {
                description = "PRESET:30D または CUSTOM:from:to の report scope key です。"
                schema = jsonSchema<String>()
            }
            query("days") {
                description = "scopeKey 省略時の互換 preset 日数です。"
                schema = jsonSchema<Int>()
            }
            query("epochId") {
                description = "immutable account epoch ID。省略時は active epoch です。"
                schema = jsonSchema<String>()
            }
            query("cohort") {
                description = "CURRENT / LEGACY_PRE_WS / UNSUPPORTED_EXECUTION_SEMANTICS。"
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK { schema = jsonSchema<EvaluationReportResponse>() }
            HttpStatusCode.NotFound { schema = jsonSchema<ErrorResponse>() }
        }
    }

    get("/evaluation/reports/jobs/{jobId}") {
        val jobId = call.parameters["jobId"]
        val job = jobId?.let { value -> store.job(value) }
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("report job was not found"))
            return@get
        }

        call.respond(job)
    }.describe {
        summary = "評価レポート job の進捗を取得する"
        description = "manual generation の stage、terminal failure、revision identity を返します。"
        tag(EVALUATION_REPORT_TAG)
        responses {
            HttpStatusCode.OK { schema = jsonSchema<EvaluationReportJobResponse>() }
            HttpStatusCode.NotFound { schema = jsonSchema<ErrorResponse>() }
        }
    }

    get("/evaluation/reports/revisions") {
        val scopeKey = call.reportScopeKey(dependencies.repository) ?: return@get
        val currentHistory = store.history(scopeKey)
        val legacyHistory = if (call.request.queryParameters["cohort"] == "LEGACY_PRE_WS") {
            store.history(EvaluationReportScopeKey.decode(scopeKey).base)
        } else {
            emptyList()
        }
        call.respond(EvaluationReportHistoryResponse(currentHistory + legacyHistory))
    }.describe {
        summary = "評価レポート履歴を取得する"
        description = "生成 request ごとに保持する immutable revision 履歴を新しい順で返します。"
        tag(EVALUATION_REPORT_TAG)
        parameters {
            query("scopeKey") {
                description = "履歴対象の report scope key です。"
                schema = jsonSchema<String>()
            }
            query("days") {
                description = "scopeKey 省略時の互換 preset 日数です。"
                schema = jsonSchema<Int>()
            }
            query("epochId") {
                description = "履歴対象の immutable account epoch ID です。"
                schema = jsonSchema<String>()
            }
            query("cohort") {
                description = "履歴対象 cohort です。"
                schema = jsonSchema<String>()
            }
        }
        responses { HttpStatusCode.OK { schema = jsonSchema<EvaluationReportHistoryResponse>() } }
    }

    get("/evaluation/reports/revisions/{revisionId}") {
        val revision = call.parameters["revisionId"]?.let { revisionId -> store.revision(revisionId) }
        if (revision == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("report revision was not found"))
            return@get
        }
        val hasScopeContract = listOf("scopeKey", "days", "epochId", "cohort")
            .any { key -> call.request.queryParameters[key] != null }
        if (hasScopeContract) {
            val expectedScopeKey = call.reportScopeKey(dependencies.repository) ?: return@get
            if (revision.scopeKey != expectedScopeKey) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    EvaluationReportScopeErrorResponse("REPORT_SCOPE_MISMATCH", "revision scope does not match requested scope"),
                )
                return@get
            }
        }
        call.respond(revision)
    }.describe {
        summary = "評価レポート revision を取得する"
        description = "履歴から選択した immutable artifact と同一 snapshot evidence を返します。"
        tag(EVALUATION_REPORT_TAG)
        parameters {
            query("scopeKey") {
                schema = jsonSchema<String>()
                description = "preview対象の期間scope keyです。"
            }
            query("epochId") {
                schema = jsonSchema<String>()
                description = "preview対象のimmutable account epoch IDです。"
            }
            query("cohort") {
                schema = jsonSchema<String>()
                description = "preview対象cohortです。"
            }
        }
        responses {
            HttpStatusCode.OK { schema = jsonSchema<EvaluationReportResponse>() }
            HttpStatusCode.BadRequest { schema = jsonSchema<EvaluationReportScopeErrorResponse>() }
            HttpStatusCode.NotFound { schema = jsonSchema<ErrorResponse>() }
        }
    }

    put("/evaluation/reports/pins") {
        val request = call.receive<EvaluationReportPinRequest>()
        val scopeKey = request.resolvedScopeKey(dependencies.repository) ?: run {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("evaluation scope is invalid"))
            return@put
        }
        store.pin(scopeKey, request.revisionId).getOrElse { error ->
            val code = if (error.message == "REPORT_SCOPE_MISMATCH") "REPORT_SCOPE_MISMATCH" else "REPORT_PIN_REJECTED"
            call.respond(HttpStatusCode.BadRequest, EvaluationReportScopeErrorResponse(code, error.message ?: "pin failed"))
            return@put
        }
        call.respond(EvaluationReportPinResponse(scopeKey, request.revisionId))
    }.describe {
        summary = "評価レポート revision を pin する"
        description = "successful immutable revision を選択 scope の既定表示へ明示的に固定します。"
        tag(EVALUATION_REPORT_TAG)
        requestBody { schema = jsonSchema<EvaluationReportPinRequest>() }
        responses {
            HttpStatusCode.OK { schema = jsonSchema<EvaluationReportPinResponse>() }
            HttpStatusCode.BadRequest { schema = jsonSchema<EvaluationReportScopeErrorResponse>() }
        }
    }

    delete("/evaluation/reports/pins") {
        val scopeKey = call.reportScopeKey(dependencies.repository) ?: return@delete
        store.unpin(scopeKey)
        call.respond(HttpStatusCode.NoContent)
    }.describe {
        summary = "評価レポート pin を解除する"
        description = "artifact を削除せず、scope の明示 pin だけを解除します。"
        tag(EVALUATION_REPORT_TAG)
        parameters {
            query("scopeKey") {
                description = "pin を解除する report scope key です。"
                schema = jsonSchema<String>()
            }
            query("days") {
                description = "scopeKey 省略時の互換 preset 日数です。"
                schema = jsonSchema<Int>()
            }
            query("epochId") {
                description = "pin scopeのimmutable account epoch IDです。"
                schema = jsonSchema<String>()
            }
            query("cohort") {
                description = "pin scopeのcohortです。"
                schema = jsonSchema<String>()
            }
        }
        responses { HttpStatusCode.NoContent { description = "pin を解除しました。" } }
    }
}

@Suppress("LongParameterList")
private class EvaluationReportStore(
    private val repository: EvaluationRepository?,
    private val marketDataSource: MarketDataSource?,
    private val symbol: me.matsumo.fukurou.trading.domain.TradingSymbol,
    private val llmInvoker: LlmInvoker?,
    private val llmInvocationAuditor: me.matsumo.fukurou.trading.runner.LlmInvocationAuditor?,
    private val environment: Map<String, String>,
    private val persistence: EvaluationReportPersistence?,
    private val clock: Clock,
) {
    private val revisionSequence = AtomicLong(0)
    private val reports = mutableMapOf<String, MutableList<EvaluationReportResponse>>()
    private val jobs = mutableMapOf<String, EvaluationReportJobResponse>()
    private val pins = mutableMapOf<String, String>()

    fun request(scope: EvaluationReportScope): EvaluationReportJobResponse {
        val job = EvaluationReportJobResponse(
            jobId = UUID.randomUUID().toString(),
            revisionId = UUID.randomUUID().toString(),
            status = "REQUESTED",
            stage = "ADMITTED",
            epochId = scope.evaluationScope.accountEpochId.toString(),
            cohort = scope.evaluationScope.cohort.name,
        )
        val admittedJob = persistence?.admit(job, scope.key)?.getOrThrow()?.job ?: job.copy(
            revisionNumber = revisionSequence.incrementAndGet(),
        )
        synchronized(jobs) { jobs[admittedJob.jobId] = admittedJob }

        return admittedJob
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun generate(
        scope: EvaluationReportScope,
        job: EvaluationReportJobResponse,
    ): Result<EvaluationReportResponse> = runCatching {
        updateJob(job.copy(status = "RUNNING", stage = "SNAPSHOTTING"))
        val source = requireNotNull(repository) { "evaluation repository is unavailable" }
        val today = LocalDate.now(clock.withZone(ReportZone))
        val toInclusive = scope.toInclusive ?: today.minusDays(1)
        val from = scope.from ?: toInclusive.minusDays(scope.days.toLong() - 1)
        val period = EvaluationPeriod(
            from = from.atStartOfDay(ReportZone).toInstant(),
            toExclusive = toInclusive.plusDays(1).atStartOfDay(ReportZone).toInstant(),
        )
        val effectivePeriod = period.intersectLifecycle(scope.evaluationScope)
        val snapshotId = UUID.randomUUID().toString()
        val emptyLifecycle = effectivePeriod.from == effectivePeriod.toExclusive
        val snapshot = source.fetchReportSnapshot(effectivePeriod, scope.evaluationScope).getOrThrow()
        val queryResult = snapshot.trades
        require(!queryResult.truncated) { "SNAPSHOT_TRUNCATED" }
        val effectiveDays = java.time.Duration.between(effectivePeriod.from, effectivePeriod.toExclusive).toDays()
        val candles = if (emptyLifecycle) {
            emptyList()
        } else {
            marketDataSource?.getCandles(
                symbol = symbol,
                interval = CandleInterval.ONE_DAY,
                limit = (effectiveDays.toInt() + 40).coerceAtMost(500),
            )?.getOrThrow() ?: error("market data source is unavailable")
        }
        val regimes = EvaluationMath.classifyMarketRegimes(candles, ReportZone)
        val stats = EvaluationMath.summarizeTrades(queryResult.trades)
        val ridge = EvaluationMath.historicalOutcomeRidges(queryResult.trades, ReportZone, regimes)
        val baselineEquity = snapshot.initialCashJpy.add(snapshot.priorPnlJpy)
        val effectiveFromDate = effectivePeriod.from.atZone(ReportZone).toLocalDate()
        val effectiveToDate = if (emptyLifecycle) {
            effectiveFromDate
        } else {
            effectivePeriod.toExclusive.minusMillis(1).atZone(ReportZone).toLocalDate()
        }
        val benchmark = EvaluationMath.benchmark(
            BenchmarkCalculationRequest(
                candles = candles,
                dailyPnlFacts = snapshot.dailyPnl,
                baselineEquityJpy = baselineEquity,
                fromDate = effectiveFromDate,
                toDateInclusive = effectiveToDate,
                zoneId = ReportZone,
            ),
        )
        val benchmarkComparable = scope.evaluationScope.cohort !=
            EvaluationCohort.LEGACY_PRE_WS
        val benchmarkAvailable = benchmarkComparable && !emptyLifecycle
        val calibration = buildCalibrationResponse(queryResult.trades)
        val performanceLattice = buildPerformanceLattice(queryResult.trades, regimes)
        val usageResult = snapshot.usages
        require(!usageResult.truncated) { "USAGE_SNAPSHOT_TRUNCATED" }
        val costStats = EvaluationMath.summarizeLlmCosts(usageResult.facts)
        val exclusions = snapshot.exclusions
        val benchmarkPoints = benchmark.points.takeIf { benchmarkAvailable }.orEmpty()
        val benchmarkFacts = benchmarkPoints.flatMap { point ->
            listOf(
                EvaluationReportFact("benchmark.${point.date}.botEquityJpy", point.botEquityJpy.toPlainString(), "JPY", "AVAILABLE", listOf("paper-ledger")),
                EvaluationReportFact("benchmark.${point.date}.buyAndHoldEquityJpy", point.buyAndHoldEquityJpy.toPlainString(), "JPY", "AVAILABLE", listOf("daily-candles")),
                EvaluationReportFact("benchmark.${point.date}.noTradeEquityJpy", point.noTradeEquityJpy.toPlainString(), "JPY", "AVAILABLE", listOf("paper-ledger")),
            )
        }
        val calibrationFacts = calibration.cells.flatMap { cell ->
            listOf(
                EvaluationReportFact("calibration.${cell.groupBy}.${cell.groupKey}.${cell.lowerBoundInclusive}.forecast", cell.averageForecastProbability, "PROBABILITY", if (cell.averageForecastProbability == null) "MISSING" else "AVAILABLE", listOf("paper-ledger")),
                EvaluationReportFact("calibration.${cell.groupBy}.${cell.groupKey}.${cell.lowerBoundInclusive}.realized", cell.realizedWinRate, "PROBABILITY", if (cell.realizedWinRate == null) "MISSING" else "AVAILABLE", listOf("paper-ledger")),
            )
        }
        val latticeFacts = performanceLattice.cells.map { cell ->
            EvaluationReportFact("lattice.${cell.setup}.${cell.marketRegime}.expectedR", cell.expectedR, "R", if (cell.expectedR == null) "MISSING" else "AVAILABLE", listOf("paper-ledger", "daily-candles"))
        }
        val integrityFacts = listOf(
            EvaluationReportFact("integrity.missingRCount", stats.rUnavailableCount.toString(), "COUNT", "AVAILABLE", listOf("paper-ledger")),
            EvaluationReportFact("integrity.excludedPositionCount", exclusions.positionCount.toString(), "COUNT", "AVAILABLE", listOf("exclusion-audit")),
            EvaluationReportFact("integrity.knownCostUsd", costStats.knownCostUsd?.toPlainString(), "USD", if (costStats.knownCostUsd == null) "MISSING" else "AVAILABLE", listOf("runner-audit")),
        )
        val baseFacts = listOf(
            EvaluationReportFact("performance.tradeCount", stats.tradeCount.toString(), "COUNT", "AVAILABLE", listOf("paper-ledger")),
            EvaluationReportFact("performance.totalPnlJpy", stats.totalPnlJpy.toPlainString(), "JPY", "AVAILABLE", listOf("paper-ledger")),
            EvaluationReportFact("performance.expectedR", stats.expectedR?.toPlainString(), "R", if (stats.expectedR == null) "MISSING" else "AVAILABLE", listOf("paper-ledger")),
        )
        val ridgeFacts = ridge.groupings.flatMap { grouping ->
            grouping.groups.flatMap { group ->
                listOf(
                    EvaluationReportFact(
                        "distribution.${grouping.groupBy.name.lowercase()}.${group.groupKey}.medianR",
                        group.medianR?.toPlainString(),
                        "R",
                        if (group.medianR == null) "MISSING" else "AVAILABLE",
                        listOf("paper-ledger"),
                    ),
                    EvaluationReportFact(
                        "distribution.${grouping.groupBy.name.lowercase()}.${group.groupKey}.tailLossCount",
                        group.tailLossCount.toString(),
                        "COUNT",
                        "AVAILABLE",
                        listOf("paper-ledger"),
                    ),
                )
            }
        }
        val facts = baseFacts + ridgeFacts + benchmarkFacts + calibrationFacts + latticeFacts + integrityFacts
        val chartIndex = listOf(
            EvaluationChartIndexResponse(
                chartId = "historical-realized-r-ridge",
                catalogVersion = ridge.catalogVersion,
                factIds = ridgeFacts.map { fact -> fact.factId },
            ),
            EvaluationChartIndexResponse("bot-benchmark-equity", "evaluation-report-v1", benchmarkFacts.map { fact -> fact.factId }),
            EvaluationChartIndexResponse("forecast-calibration-lattice", "evaluation-report-v1", calibrationFacts.map { fact -> fact.factId }),
            EvaluationChartIndexResponse("setup-market-regime-lattice", "evaluation-report-v1", latticeFacts.map { fact -> fact.factId }),
            EvaluationChartIndexResponse("evidence-integrity", "evaluation-report-v1", integrityFacts.map { fact -> fact.factId }),
        )
        val claims = listOf(
            EvaluationReportClaim("claim-pnl-direction", "FACT_DIRECTION", listOf("performance.totalPnlJpy"), direction(stats.totalPnlJpy)),
            EvaluationReportClaim("claim-trade-count", "FACT_VALUE", listOf("performance.tradeCount"), stats.tradeCount.toString()),
        )
        val integrity = EvaluationIntegrityResponse(
            eligibleTradeCount = queryResult.trades.size,
            missingRCount = stats.rUnavailableCount,
            excludedOrderCount = exclusions.orderCount,
            excludedPositionCount = exclusions.positionCount,
            excludedDecisionRunCount = exclusions.decisionRunCount,
            exclusionReasons = exclusions.reasons,
            llmPhaseCount = costStats.phaseCount,
            missingUsagePhaseCount = costStats.missingUsagePhaseCount,
            unpricedPhaseCount = costStats.unpricedPhaseCount,
            knownCostUsd = costStats.knownCostUsd?.toPlainString(),
            usageTruncated = false,
        )
        val inputAsOf = clock.instant().toString()
        val reportSources = listOf(
            EvaluationReportSourceResponse("paper-ledger", inputAsOf, "SNAPSHOT"),
            EvaluationReportSourceResponse("daily-candles", candles.lastOrNull()?.openTime ?: inputAsOf, if (candles.isEmpty()) "UNAVAILABLE" else "SNAPSHOT"),
            EvaluationReportSourceResponse("exclusion-audit", inputAsOf, "SNAPSHOT"),
            EvaluationReportSourceResponse("runner-audit", inputAsOf, "SNAPSHOT"),
        )
        val benchmarkResponse = ReportBenchmarkChartResponse(
            baselineEquityJpy = baselineEquity.takeIf { benchmarkAvailable }?.toPlainString(),
            points = benchmarkPoints.map { point ->
                ReportBenchmarkPointResponse(
                    date = point.date.toString(),
                    botEquityJpy = point.botEquityJpy.toPlainString(),
                    buyAndHoldEquityJpy = point.buyAndHoldEquityJpy.toPlainString(),
                    noTradeEquityJpy = point.noTradeEquityJpy.toPlainString(),
                )
            },
            botReturn = benchmark.botReturn?.takeIf { benchmarkAvailable }?.toPlainString(),
            buyAndHoldReturn = benchmark.buyAndHoldReturn?.takeIf { benchmarkAvailable }?.toPlainString(),
            state = when {
                emptyLifecycle -> "EMPTY_LIFECYCLE"
                !benchmarkComparable -> "BASELINE_NOT_COMPARABLE"
                benchmark.points.isEmpty() -> "INSUFFICIENT_SAMPLE"
                else -> "AVAILABLE"
            },
        )
        val canonical = ReportJson.encodeToString(
            CanonicalEvaluationSnapshot(
                snapshotId = snapshotId,
                scopeKey = scope.key,
                from = from.toString(),
                toInclusive = toInclusive.toString(),
                inputAsOf = inputAsOf,
                facts = facts.map { fact -> EvaluationReportFactResponse(fact.factId, fact.value, fact.unit, fact.availability, fact.sourceIds) },
                sources = reportSources,
                chartIndex = chartIndex,
                outcomeRidge = ridge.toResponse(),
                benchmark = benchmarkResponse,
                calibration = calibration,
                performanceLattice = performanceLattice,
                integrity = integrity,
            ),
        )
        val inputHash = sha256(canonical)
        persistence?.saveSnapshot(snapshotId, scope.key, canonical, inputHash)?.getOrThrow()
        updateJob(job.copy(status = "RUNNING", stage = "GENERATING_REPORT"))
        val generated = generateArtifact(
            fallbackClaims = claims,
            facts = facts,
            days = scope.days,
            snapshotId = snapshotId,
            invocationId = job.jobId,
        )
        val artifact = generated.artifact
        updateJob(job.copy(status = "RUNNING", stage = "VALIDATING"))
        validateGeneratedArtifact(artifact)
        val validation = EvaluationClaimValidator.validate(artifact.claims.toDomain(), facts)
        validateSnapshotReferences(
            facts = facts,
            sourceIds = setOf("paper-ledger", "daily-candles", "exclusion-audit", "runner-audit"),
            chartIndex = chartIndex,
        )
        val revisionNumber = job.revisionNumber
        val report = EvaluationReportResponse(
            jobId = job.jobId,
            revisionId = job.revisionId,
            revisionNumber = revisionNumber,
            scopeKey = scope.key,
            epochId = scope.evaluationScope.accountEpochId.toString(),
            cohort = scope.evaluationScope.cohort.name,
            executionSemanticsVersion = scope.evaluationScope.executionSemanticsVersion,
            attributionCoverage = EvaluationAttributionCoverageResponse(
                attributed = queryResult.attributionCoverage.attributed,
                missing = queryResult.attributionCoverage.missing,
                total = queryResult.attributionCoverage.total,
            ),
            status = "SUCCEEDED",
            period = EvaluationReportPeriodResponse(
                from = from.toString(),
                toInclusive = toInclusive.toString(),
                timezone = ReportZone.id,
                effectiveFrom = effectivePeriod.from.atZone(ReportZone).toLocalDate().toString()
                    .takeUnless { emptyLifecycle },
                effectiveToInclusive = effectivePeriod.toExclusive.minusMillis(1)
                    .atZone(ReportZone).toLocalDate().toString().takeUnless { emptyLifecycle },
                populationState = when {
                    emptyLifecycle -> "EMPTY_LIFECYCLE"
                    effectivePeriod == period -> "FULL_REQUESTED_PERIOD"
                    else -> "PARTIAL_LIFECYCLE"
                },
                effectiveDays = effectiveDays,
            ),
            inputAsOf = inputAsOf,
            inputHash = inputHash,
            snapshotId = snapshotId,
            generatedAt = clock.instant().toString(),
            provider = if (llmInvoker == null) "DETERMINISTIC_FALLBACK" else LlmProvider.CLAUDE.name,
            model = environment["FUKUROU_CLAUDE_MODEL"] ?: "CLI_DEFAULT",
            generation = generated.metadata,
            title = "${scope.label} PERFORMANCE / EVIDENCE REVIEW",
            segments = artifact.segments,
            claims = artifact.claims,
            validation = validation.map { result -> EvaluationClaimValidationResponse(result.claimId, result.status.name, result.asserted, result.actual, result.factIds, result.code) },
            facts = facts.map { fact -> EvaluationReportFactResponse(fact.factId, fact.value, fact.unit, fact.availability, fact.sourceIds) },
            sources = reportSources,
            chartIndex = chartIndex,
            outcomeRidge = ridge.toResponse(),
            benchmark = benchmarkResponse,
            calibration = calibration,
            performanceLattice = performanceLattice,
            integrity = integrity,
            truncated = queryResult.truncated,
        )
        persistence?.complete(report, job)?.getOrThrow()
        synchronized(reports) {
            reports.getOrPut(scope.key) { mutableListOf() }.add(0, report)
            pins[scope.key] = report.revisionId
        }
        synchronized(jobs) { jobs[job.jobId] = job.copy(status = "SUCCEEDED", stage = "COMPLETE") }
        report
    }.onFailure { error ->
        val safeFailure = me.matsumo.fukurou.trading.runner.SecretRedactor.fromEnvironment(environment)
            .redactAndTruncate(error.message ?: "Report generation failed")
        val failedJob = job.copy(
            status = "FAILED",
            stage = "FAILED",
            failureCode = error::class.simpleName ?: "REPORT_GENERATION_FAILED",
            failureMessage = safeFailure.take(300),
        )
        persistence?.fail(failedJob)?.getOrThrow()
        synchronized(jobs) { jobs[job.jobId] = failedJob }
    }

    @Suppress("LongMethod")
    private suspend fun generateArtifact(
        fallbackClaims: List<EvaluationReportClaim>,
        facts: List<EvaluationReportFact>,
        days: Int,
        snapshotId: String,
        invocationId: String,
    ): GeneratedReportArtifact {
        val invoker = llmInvoker ?: return GeneratedReportArtifact(
            fallbackArtifact(fallbackClaims, facts),
            EvaluationReportGenerationMetadataResponse(invocationId, "DETERMINISTIC_FALLBACK", null, null, null),
        )
        val auditor = requireNotNull(llmInvocationAuditor) { "LLM invocation auditor is unavailable" }
        val prompt = reportPrompt(days, facts)
        val promptHash = sha256(prompt)
        val effort = evaluationReportEffort(environment)
        val workingDirectory = Files.createTempDirectory("fukurou-evaluation-report-")
        val safeEnvironment = environment.filterKeys { key -> key in REPORT_CHILD_ENV_ALLOWLIST }
        val audited = try {
            auditor.invokeAndAudit(
                phaseName = "evaluation_report",
                context = DecisionRunContext(
                    decisionRunId = invocationId,
                    llmProvider = LlmProvider.CLAUDE.name,
                    promptHash = promptHash,
                    systemPromptVersion = REPORT_PROMPT_VERSION,
                    marketSnapshotId = snapshotId,
                ),
                request = LlmInvocationRequest(
                    invocationId = invocationId,
                    provider = LlmProvider.CLAUDE,
                    phase = LlmInvocationPhase.EVALUATION_REPORT,
                    prompt = prompt,
                    timeout = Duration.ofMinutes(5),
                    workingDirectory = workingDirectory,
                    decisionRunContext = DecisionRunContext(
                        decisionRunId = invocationId,
                        llmProvider = LlmProvider.CLAUDE.name,
                        promptHash = promptHash,
                        systemPromptVersion = REPORT_PROMPT_VERSION,
                        marketSnapshotId = snapshotId,
                    ),
                    mcpServer = null,
                    environment = safeEnvironment,
                    allowedTools = emptyList(),
                    effort = effort,
                ),
                llmInvoker = invoker,
            ).getOrThrow()
        } finally {
            workingDirectory.toFile().deleteRecursively()
        }
        val usage = audited.invocationResult.usage
        return GeneratedReportArtifact(
            artifact = ReportJson.decodeFromString(audited.invocationResult.responseText.removeJsonFence()),
            metadata = EvaluationReportGenerationMetadataResponse(
                invocationId = invocationId,
                provider = LlmProvider.CLAUDE.name,
                durationMillis = audited.duration.toMillis(),
                totalCostUsd = usage?.totalCostUsd?.toPlainString(),
                observedModels = usage?.modelUsages?.map { model -> model.model }.orEmpty(),
                promptHash = promptHash,
                promptVersion = REPORT_PROMPT_VERSION,
                schemaVersion = REPORT_SCHEMA_VERSION,
                effort = effort.name,
            ),
        )
    }

    fun default(scopeKey: String): EvaluationReportResponse? = synchronized(reports) {
        persistence?.default(scopeKey)?.getOrThrow()?.let { report -> return@synchronized report }
        val pinnedId = pins[scopeKey]
        reports[scopeKey]?.firstOrNull { report -> report.revisionId == pinnedId }
            ?: reports[scopeKey]?.firstOrNull()
    }

    fun job(jobId: String): EvaluationReportJobResponse? = synchronized(jobs) {
        jobs[jobId] ?: persistence?.job(jobId)?.getOrThrow()
    }

    fun revision(revisionId: String): EvaluationReportResponse? = synchronized(reports) {
        reports.values.flatten().firstOrNull { report -> report.revisionId == revisionId }
            ?: persistence?.revision(revisionId)?.getOrThrow()
    }

    fun pin(scopeKey: String, revisionId: String): Result<Unit> {
        val report = revision(revisionId)
        return runCatching {
            require(report != null && report.status == "SUCCEEDED") { "revision must be successful" }
            require(report.scopeKey == scopeKey) { "REPORT_SCOPE_MISMATCH" }
            persistence?.pin(scopeKey, revisionId)?.getOrThrow()
            synchronized(reports) { pins[scopeKey] = revisionId }
        }
    }

    fun unpin(scopeKey: String) {
        persistence?.unpin(scopeKey)?.getOrThrow()
        synchronized(reports) { pins.remove(scopeKey) }
    }

    private fun updateJob(job: EvaluationReportJobResponse) {
        synchronized(jobs) { jobs[job.jobId] = job }
        persistence?.updateJob(job)?.getOrThrow()
    }

    fun history(scopeKey: String): List<EvaluationReportHistoryItemResponse> = synchronized(reports) {
        val persisted = persistence?.history(scopeKey)?.getOrThrow()
        if (persisted != null) return@synchronized persisted

        reports[scopeKey].orEmpty().map { report ->
            EvaluationReportHistoryItemResponse(
                jobId = report.jobId,
                revisionId = report.revisionId,
                revisionNumber = report.revisionNumber,
                status = report.status,
                requestedAt = report.generatedAt,
                pinned = true,
                epochId = report.epochId,
                cohort = report.cohort,
                scopeKey = report.scopeKey,
            )
        }
    }
}

private val ReportJson = Json { ignoreUnknownKeys = false }
private val REPORT_CHILD_ENV_ALLOWLIST = setOf("HOME", "PATH", "TMPDIR", "CODEX_HOME", "CLAUDE_CONFIG_DIR")

private const val REPORT_PROMPT_VERSION = "evaluation-report-prompt-v1"
private const val REPORT_SCHEMA_VERSION = "evaluation-report-schema-v1"
internal fun evaluationReportEffort(environment: Map<String, String>): me.matsumo.fukurou.trading.invoker.LlmEffort =
    environment["FUKUROU_CLAUDE_EFFORT"]?.let(me.matsumo.fukurou.trading.invoker.LlmEffort::valueOf)
        ?: me.matsumo.fukurou.trading.invoker.LlmEffort.DEFAULT
internal fun canonicalIntegrityHash(integrity: EvaluationIntegrityResponse): String =
    sha256(ReportJson.encodeToString(integrity))
private fun reportPrompt(days: Int, facts: List<EvaluationReportFact>): String = """
    Generate a factual evaluation report for the previous $days complete calendar days.
    Return JSON only with this exact shape:
    {"segments":[{"segmentId":"seg-1","kind":"SUMMARY|PERFORMANCE|CALIBRATION|RISK|COST|COVERAGE|LIMITATION","text":"...","claimIds":["claim-1"]}],"claims":[{"claimId":"claim-1","type":"FACT_VALUE|FACT_DIRECTION|FACT_COMPARISON|FACT_DELTA|FACT_COVERAGE","factIds":["fact.id"],"asserted":"value or operator"}]}
    Every numeric or directional statement must bind a typed claim. Do not predict or prescribe trades.
    Facts: ${facts.joinToString { fact -> "${fact.factId}=${fact.value ?: "MISSING"} ${fact.unit.orEmpty()} availability=${fact.availability}" }}
""".trimIndent()

private fun String.removeJsonFence(): String = trim()
    .removePrefix("```json")
    .removePrefix("```")
    .removeSuffix("```")
    .trim()

private fun List<EvaluationReportClaimResponse>.toDomain(): List<EvaluationReportClaim> = map { claim ->
    EvaluationReportClaim(claim.claimId, claim.type, claim.factIds, claim.asserted)
}

private fun validateSnapshotReferences(
    facts: List<EvaluationReportFact>,
    sourceIds: Set<String>,
    chartIndex: List<EvaluationChartIndexResponse>,
) {
    val factIds = facts.map { fact -> fact.factId }.toSet()
    require(facts.size == factIds.size) { "duplicate fact ID" }

    val sourcesAreValid = facts.flatMap { fact -> fact.sourceIds }.all { sourceId -> sourceId in sourceIds }
    require(sourcesAreValid) { "dangling source reference" }

    val chartFactsAreValid = chartIndex.flatMap { chart -> chart.factIds }.all { factId -> factId in factIds }
    require(chartFactsAreValid) { "dangling chart fact reference" }
}

internal fun validateGeneratedArtifact(artifact: GeneratedEvaluationArtifact) {
    require(artifact.segments.size in 1..12) { "report must contain 1..12 segments" }
    require(artifact.claims.size <= 40) { "report must contain at most 40 claims" }
    require(artifact.segments.sumOf { segment -> segment.text.length } <= 12_000) { "report body is too large" }
    val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    val segmentIds = artifact.segments.map { segment -> segment.segmentId }
    val claimIds = artifact.claims.map { claim -> claim.claimId }
    require(segmentIds.distinct().size == segmentIds.size && segmentIds.all(idPattern::matches)) {
        "segment IDs must be unique and bounded"
    }
    require(claimIds.distinct().size == claimIds.size && claimIds.all(idPattern::matches)) {
        "claim IDs must be unique and bounded"
    }
    require(
        artifact.segments.all { segment ->
            segment.kind in setOf("SUMMARY", "PERFORMANCE", "CALIBRATION", "RISK", "COST", "COVERAGE", "LIMITATION") &&
                segment.text.length in 1..1_200 &&
                segment.claimIds.all { claimId -> claimId in claimIds } &&
                (!segment.text.contains(Regex("[0-9]")) || segment.claimIds.isNotEmpty())
        },
    ) { "segment kind, text, or claim reference is invalid" }
    require(
        artifact.claims.all { claim ->
            claim.type in setOf("FACT_VALUE", "FACT_DIRECTION", "FACT_COMPARISON", "FACT_DELTA", "FACT_COVERAGE") &&
                claim.factIds.size in 1..2 &&
                claim.factIds.all(idPattern::matches) &&
                claim.asserted.length in 1..200
        },
    ) { "claim type, fact binding, or assertion is invalid" }
}

private fun fallbackArtifact(
    claims: List<EvaluationReportClaim>,
    facts: List<EvaluationReportFact>,
): GeneratedEvaluationArtifact = GeneratedEvaluationArtifact(
    segments = listOf(
        EvaluationReportSegmentResponse(
            segmentId = "segment-summary",
            kind = "SUMMARY",
            text = "期間内の確定済み paper trade は ${facts.first().value} 件です。",
            claimIds = claims.map { claim -> claim.claimId },
        ),
        EvaluationReportSegmentResponse(
            segmentId = "segment-limitations",
            kind = "LIMITATION",
            text = "欠損または除外された evidence は favorable outcome に補完されません。選択した対象期間だけを評価します。",
            claimIds = emptyList(),
        ),
    ),
    claims = claims.map { claim ->
        EvaluationReportClaimResponse(claim.claimId, claim.type, claim.factIds, claim.asserted)
    },
)

@Suppress("LongParameterList")
private fun buildCalibrationResponse(trades: List<ClosedTradeFact>): ReportCalibrationChartResponse {
    fun cells(groupBy: String, groups: List<me.matsumo.fukurou.trading.evaluation.CalibrationGroupStats>) =
        groups.flatMap { group ->
            group.bins.map { bin ->
                ReportCalibrationCellResponse(
                    groupBy = groupBy,
                    groupKey = group.groupKey,
                    lowerBoundInclusive = bin.lowerBoundInclusive.toPlainString(),
                    upperBound = bin.upperBound.toPlainString(),
                    averageForecastProbability = bin.averageEstimatedProbability?.toPlainString(),
                    realizedWinRate = bin.realizedWinRate?.toPlainString(),
                    sampleCount = bin.tradeCount,
                    state = sampleState(bin.tradeCount),
                )
            }
        }

    val result = cells("SETUP", EvaluationMath.calibrationBySetup(trades)) +
        cells("PROVIDER", EvaluationMath.calibrationByProvider(trades))
    return ReportCalibrationChartResponse(
        unit = "PROBABILITY_0_TO_1",
        authority = "IMMUTABLE_CLOSED_PAPER_TRADES",
        cells = result,
        state = if (result.any { cell -> cell.sampleCount > 0 }) "AVAILABLE" else "INSUFFICIENT_SAMPLE",
    )
}

private fun buildPerformanceLattice(
    trades: List<ClosedTradeFact>,
    regimes: List<MarketRegimeLabel>,
): ReportPerformanceLatticeResponse {
    val regimeByDate = regimes.associateBy { regime -> regime.date }
    val grouped = trades.flatMap { trade ->
        val regime = regimeByDate[trade.openedAt.atZone(ReportZone).toLocalDate()]
        val regimeKey = regime?.let { value -> "${value.trend.name}/${value.volatility.name}" } ?: "UNKNOWN"
        trade.setupTags.ifEmpty { listOf("UNCLASSIFIED") }.map { setup -> setup to regimeKey to trade }
    }.groupBy(
        keySelector = { entry -> entry.first },
        valueTransform = { entry -> entry.second },
    )
    val cells = grouped.map { (keys, groupedTrades) ->
        val stats = EvaluationMath.summarizeTrades(groupedTrades)
        ReportPerformanceCellResponse(
            setup = keys.first,
            marketRegime = keys.second,
            tradeCount = stats.tradeCount,
            expectedR = stats.expectedR?.toPlainString(),
            totalPnlJpy = stats.totalPnlJpy.toPlainString(),
            profitFactor = stats.profitFactor?.toPlainString(),
            state = sampleState(stats.tradeCount),
        )
    }.sortedWith(compareBy({ cell -> cell.setup }, { cell -> cell.marketRegime }))

    return ReportPerformanceLatticeResponse(
        unit = "EXPECTED_REALIZED_R",
        authority = "IMMUTABLE_CLOSED_PAPER_TRADES_AND_DAILY_MARKET_REGIME",
        cells = cells,
        state = if (cells.isEmpty()) "INSUFFICIENT_SAMPLE" else "AVAILABLE",
    )
}

private fun sampleState(count: Int): String = when {
    count == 0 -> "EMPTY"
    count < 10 -> "PROVISIONAL"
    else -> "COMPARABLE"
}

@Serializable
internal data class GeneratedEvaluationArtifact(
    val segments: List<EvaluationReportSegmentResponse>,
    val claims: List<EvaluationReportClaimResponse>,
)

private data class GeneratedReportArtifact(
    val artifact: GeneratedEvaluationArtifact,
    val metadata: EvaluationReportGenerationMetadataResponse,
)

private fun direction(value: java.math.BigDecimal): String = when {
    value.signum() > 0 -> "POSITIVE"
    value.signum() < 0 -> "NEGATIVE"
    else -> "ZERO"
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun OutcomeRidgeChartFacts.toResponse(): OutcomeRidgeResponse = OutcomeRidgeResponse(
    catalogVersion = catalogVersion,
    observationKind = "HISTORICAL_OBSERVED",
    domain = OutcomeRidgeDomainResponse(domainMinInclusive.toPlainString(), domainMaxExclusive.toPlainString(), binWidth.toPlainString()),
    referenceLines = referenceLines.map { value -> value.toPlainString() },
    groupings = groupings.map { grouping ->
        OutcomeRidgeGroupingResponse(
            groupBy = grouping.groupBy.name,
            groups = grouping.groups.map { group ->
                OutcomeRidgeGroupResponse(
                    groupKey = group.groupKey,
                    label = group.label,
                    tradeCount = group.tradeCount,
                    availableRCount = group.availableRCount,
                    missingRCount = group.missingRCount,
                    underflowCount = group.underflowCount,
                    overflowCount = group.overflowCount,
                    bins = group.bins.map { bin -> OutcomeRidgeBinResponse(bin.lowerInclusive.toPlainString(), bin.upperExclusive.toPlainString(), bin.count) },
                    medianR = group.medianR?.toPlainString(),
                    sampleState = group.sampleState.name,
                )
            },
        )
    },
)

@Serializable
data class EvaluationReportGenerateRequest(
    val days: Int? = null,
    val kind: String = "PRESET",
    val from: String? = null,
    val toInclusive: String? = null,
    val epochId: String? = null,
    val cohort: String? = null,
)

private data class EvaluationReportScope(
    val days: Int,
    val key: String,
    val label: String,
    val from: LocalDate? = null,
    val toInclusive: LocalDate? = null,
    val evaluationScope: EvaluationScope,
)

private fun EvaluationReportGenerateRequest.toScope(evaluationScope: EvaluationScope): EvaluationReportScope? {
    val suffix = "EPOCH:${evaluationScope.accountEpochId}|COHORT:${evaluationScope.cohort.name}"
    if (kind == "PRESET" && days in setOf(7, 30, 90)) {
        return EvaluationReportScope(
            days = requireNotNull(days),
            key = "PRESET:${days}D|$suffix",
            label = "${days}D / ${evaluationScope.cohort.name}",
            evaluationScope = evaluationScope,
        )
    }
    val hasCompleteCustomRange = kind == "CUSTOM" && from != null && toInclusive != null
    if (!hasCompleteCustomRange) return null

    val parsedFrom = runCatching { LocalDate.parse(requireNotNull(from)) }.getOrNull() ?: return null
    val parsedTo = runCatching { LocalDate.parse(requireNotNull(toInclusive)) }.getOrNull() ?: return null
    val customDays = java.time.temporal.ChronoUnit.DAYS.between(parsedFrom, parsedTo).toInt() + 1
    if (customDays !in 1..365) return null

    return EvaluationReportScope(
        days = customDays,
        label = "$parsedFrom — $parsedTo",
        from = parsedFrom,
        toInclusive = parsedTo,
        evaluationScope = evaluationScope,
        key = "CUSTOM:$parsedFrom:$parsedTo|$suffix",
    )
}

@Serializable
data class EvaluationReportAdmissionErrorResponse(val code: String)

@Serializable
data class EvaluationReportPinRequest(
    val days: Int? = null,
    val scopeKey: String? = null,
    val revisionId: String,
    val epochId: String? = null,
    val cohort: String? = null,
)

@Serializable
data class EvaluationReportPinResponse(val scopeKey: String, val revisionId: String)

/** report scope contract 違反の machine-readable response。 */
@Serializable
data class EvaluationReportScopeErrorResponse(val code: String, val message: String)

private suspend fun EvaluationReportPinRequest.resolvedScopeKey(repository: EvaluationRepository?): String? {
    val scope = repository?.resolveScope(epochId, cohort)?.getOrNull() ?: return null
    val supplied = runCatching {
        EvaluationReportScopeKey.decode(scopeKey ?: "PRESET:${days ?: 30}D")
    }.getOrNull() ?: return null
    val resolved = supplied.version(scope.accountEpochId.toString(), scope.cohort.name)
    return resolved.encode().takeIf { !supplied.versioned || supplied == resolved }
}

private suspend fun io.ktor.server.application.ApplicationCall.reportScopeKey(
    repository: EvaluationRepository?,
): String? {
    val suppliedKey = request.queryParameters["scopeKey"]
    val scope = repository?.resolveScope(
        request.queryParameters["epochId"],
        request.queryParameters["cohort"],
    )?.getOrNull()
    if (scope == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("evaluation scope is invalid"))
        return null
    }
    val supplied = runCatching {
        EvaluationReportScopeKey.decode(
            suppliedKey ?: "PRESET:${request.queryParameters["days"]?.toIntOrNull() ?: 30}D",
        )
    }.getOrNull()
    if (supplied == null) {
        respond(HttpStatusCode.BadRequest, EvaluationReportScopeErrorResponse("REPORT_SCOPE_INVALID", "scopeKey is malformed"))
        return null
    }
    val resolvedScope = supplied.version(scope.accountEpochId.toString(), scope.cohort.name)
    val resolved = resolvedScope.encode()
    val versionedScopeMismatch = suppliedKey != null &&
        supplied.versioned && supplied != resolvedScope
    if (versionedScopeMismatch) {
        respond(HttpStatusCode.BadRequest, EvaluationReportScopeErrorResponse("REPORT_SCOPE_MISMATCH", "scopeKey does not match epochId/cohort"))
        return null
    }
    return resolved
}

@Serializable
data class EvaluationReportJobResponse(
    val jobId: String,
    val revisionId: String,
    val revisionNumber: Long = 0,
    val status: String,
    val stage: String,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val activeInvocationId: String? = null,
    val retryAfterSeconds: Long? = null,
    val epochId: String? = null,
    val cohort: String? = null,
)

@Serializable
data class EvaluationReportPeriodResponse(
    val from: String,
    val toInclusive: String,
    val timezone: String,
    val effectiveFrom: String? = from,
    val effectiveToInclusive: String? = toInclusive,
    val populationState: String = "LEGACY_UNVERSIONED_PERIOD",
    val effectiveDays: Long = 0,
)

@Serializable
data class EvaluationReportSegmentResponse(val segmentId: String, val kind: String, val text: String, val claimIds: List<String>)

@Serializable
data class EvaluationReportClaimResponse(val claimId: String, val type: String, val factIds: List<String>, val asserted: String)

@Serializable
data class EvaluationClaimValidationResponse(val claimId: String, val status: String, val asserted: String, val actual: String?, val factIds: List<String>, val code: String)

@Serializable
data class EvaluationReportFactResponse(val factId: String, val value: String?, val unit: String?, val availability: String, val sourceIds: List<String>)

@Serializable
data class EvaluationReportSourceResponse(val sourceId: String, val observedAt: String, val freshness: String)

@Serializable
data class EvaluationChartIndexResponse(
    val chartId: String,
    val catalogVersion: String,
    val factIds: List<String>,
)

@Serializable
data class OutcomeRidgeDomainResponse(val minInclusive: String, val maxExclusive: String, val binWidth: String)

@Serializable
data class OutcomeRidgeBinResponse(val lowerInclusive: String, val upperExclusive: String, val count: Int)

@Serializable
data class OutcomeRidgeGroupResponse(val groupKey: String, val label: String, val tradeCount: Int, val availableRCount: Int, val missingRCount: Int, val underflowCount: Int, val overflowCount: Int, val bins: List<OutcomeRidgeBinResponse>, val medianR: String?, val sampleState: String)

@Serializable
data class OutcomeRidgeGroupingResponse(val groupBy: String, val groups: List<OutcomeRidgeGroupResponse>)

@Serializable
data class OutcomeRidgeResponse(val catalogVersion: String, val observationKind: String, val domain: OutcomeRidgeDomainResponse, val referenceLines: List<String>, val groupings: List<OutcomeRidgeGroupingResponse>)

@Serializable
data class ReportBenchmarkPointResponse(
    val date: String,
    val botEquityJpy: String,
    val buyAndHoldEquityJpy: String,
    val noTradeEquityJpy: String,
)

@Serializable
data class ReportBenchmarkChartResponse(
    val baselineEquityJpy: String?,
    val points: List<ReportBenchmarkPointResponse>,
    val botReturn: String?,
    val buyAndHoldReturn: String?,
    val state: String,
)

@Serializable
data class ReportCalibrationCellResponse(
    val groupBy: String,
    val groupKey: String,
    val lowerBoundInclusive: String,
    val upperBound: String,
    val averageForecastProbability: String?,
    val realizedWinRate: String?,
    val sampleCount: Int,
    val state: String,
)

@Serializable
data class ReportCalibrationChartResponse(
    val unit: String,
    val authority: String,
    val cells: List<ReportCalibrationCellResponse>,
    val state: String,
)

@Serializable
data class ReportPerformanceCellResponse(
    val setup: String,
    val marketRegime: String,
    val tradeCount: Int,
    val expectedR: String?,
    val totalPnlJpy: String,
    val profitFactor: String?,
    val state: String,
)

@Serializable
data class ReportPerformanceLatticeResponse(
    val unit: String,
    val authority: String,
    val cells: List<ReportPerformanceCellResponse>,
    val state: String,
)

@Serializable
data class EvaluationIntegrityResponse(
    val eligibleTradeCount: Int,
    val missingRCount: Int,
    val excludedOrderCount: Int,
    val excludedPositionCount: Int,
    val excludedDecisionRunCount: Int,
    val exclusionReasons: Map<String, Int>,
    val llmPhaseCount: Int,
    val missingUsagePhaseCount: Int,
    val unpricedPhaseCount: Int,
    val knownCostUsd: String?,
    val usageTruncated: Boolean,
)

@Serializable
data class EvaluationReportGenerationMetadataResponse(
    val invocationId: String,
    val provider: String,
    val durationMillis: Long?,
    val totalCostUsd: String?,
    val observedModels: List<String>?,
    val promptHash: String? = null,
    val promptVersion: String = REPORT_PROMPT_VERSION,
    val schemaVersion: String = REPORT_SCHEMA_VERSION,
    val effort: String = "DEFAULT",
)

@Serializable
private data class CanonicalEvaluationSnapshot(
    val snapshotId: String,
    val scopeKey: String,
    val from: String,
    val toInclusive: String,
    val inputAsOf: String,
    val facts: List<EvaluationReportFactResponse>,
    val sources: List<EvaluationReportSourceResponse>,
    val chartIndex: List<EvaluationChartIndexResponse>,
    val outcomeRidge: OutcomeRidgeResponse,
    val benchmark: ReportBenchmarkChartResponse,
    val calibration: ReportCalibrationChartResponse,
    val performanceLattice: ReportPerformanceLatticeResponse,
    val integrity: EvaluationIntegrityResponse,
)

@Serializable
data class EvaluationReportResponse(
    val jobId: String,
    val revisionId: String,
    val revisionNumber: Long,
    val scopeKey: String,
    val status: String,
    val period: EvaluationReportPeriodResponse,
    val inputAsOf: String,
    val inputHash: String,
    val snapshotId: String,
    val generatedAt: String,
    val provider: String,
    val model: String,
    val generation: EvaluationReportGenerationMetadataResponse,
    val title: String,
    val segments: List<EvaluationReportSegmentResponse>,
    val claims: List<EvaluationReportClaimResponse>,
    val validation: List<EvaluationClaimValidationResponse>,
    val facts: List<EvaluationReportFactResponse>,
    val sources: List<EvaluationReportSourceResponse>,
    val chartIndex: List<EvaluationChartIndexResponse>,
    val outcomeRidge: OutcomeRidgeResponse,
    val benchmark: ReportBenchmarkChartResponse,
    val calibration: ReportCalibrationChartResponse,
    val performanceLattice: ReportPerformanceLatticeResponse,
    val integrity: EvaluationIntegrityResponse,
    val truncated: Boolean,
    val epochId: String? = null,
    val cohort: String? = null,
    val executionSemanticsVersion: String? = null,
    val attributionCoverage: EvaluationAttributionCoverageResponse? = null,
)

@Serializable
data class EvaluationReportHistoryItemResponse(
    val jobId: String,
    val revisionId: String,
    val revisionNumber: Long,
    val status: String,
    val requestedAt: String,
    val pinned: Boolean,
    val epochId: String? = null,
    val cohort: String? = null,
    val scopeKey: String? = null,
)

@Serializable
data class EvaluationReportHistoryResponse(val revisions: List<EvaluationReportHistoryItemResponse>)
