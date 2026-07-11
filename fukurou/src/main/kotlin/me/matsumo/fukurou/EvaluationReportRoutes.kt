@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import io.ktor.http.HttpStatusCode
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
import me.matsumo.fukurou.trading.evaluation.EvaluationMath
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.OutcomeRidgeChartFacts
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
        val scope = request?.toScope()
        if (scope == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("use preset days 7, 30, 90 or a valid CUSTOM from/toInclusive range"))
            return@post
        }

        val job = runCatching { store.request(scope) }.getOrElse { error ->
            val code = error.message ?: "REPORT_ADMISSION_FAILED"
            val status = if (code == "CONCURRENT_INVOCATION") HttpStatusCode.Conflict else HttpStatusCode.TooManyRequests

            call.respond(status, EvaluationReportAdmissionErrorResponse(code))
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
            HttpStatusCode.Conflict { schema = jsonSchema<EvaluationReportAdmissionErrorResponse>() }
            HttpStatusCode.TooManyRequests { schema = jsonSchema<EvaluationReportAdmissionErrorResponse>() }
            HttpStatusCode.InternalServerError { schema = jsonSchema<ErrorResponse>() }
        }
    }

    get("/evaluation/reports/default") {
        val scopeKey = call.reportScopeKey()
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
        val scopeKey = call.reportScopeKey()
        call.respond(EvaluationReportHistoryResponse(store.history(scopeKey)))
    }.describe {
        summary = "評価レポート履歴を取得する"
        description = "生成 request ごとに保持する immutable revision 履歴を新しい順で返します。"
        tag(EVALUATION_REPORT_TAG)
        responses { HttpStatusCode.OK { schema = jsonSchema<EvaluationReportHistoryResponse>() } }
    }

    get("/evaluation/reports/revisions/{revisionId}") {
        val revision = call.parameters["revisionId"]?.let { revisionId -> store.revision(revisionId) }
        if (revision == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("report revision was not found"))
            return@get
        }
        call.respond(revision)
    }.describe {
        summary = "評価レポート revision を取得する"
        description = "履歴から選択した immutable artifact と同一 snapshot evidence を返します。"
        tag(EVALUATION_REPORT_TAG)
        responses {
            HttpStatusCode.OK { schema = jsonSchema<EvaluationReportResponse>() }
            HttpStatusCode.NotFound { schema = jsonSchema<ErrorResponse>() }
        }
    }

    put("/evaluation/reports/pins") {
        val request = call.receive<EvaluationReportPinRequest>()
        store.pin(request.resolvedScopeKey(), request.revisionId).getOrElse { error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "pin failed"))
            return@put
        }
        call.respond(EvaluationReportPinResponse(request.resolvedScopeKey(), request.revisionId))
    }.describe {
        summary = "評価レポート revision を pin する"
        description = "successful immutable revision を選択 scope の既定表示へ明示的に固定します。"
        tag(EVALUATION_REPORT_TAG)
        requestBody { schema = jsonSchema<EvaluationReportPinRequest>() }
        responses {
            HttpStatusCode.OK { schema = jsonSchema<EvaluationReportPinResponse>() }
            HttpStatusCode.BadRequest { schema = jsonSchema<ErrorResponse>() }
        }
    }

    delete("/evaluation/reports/pins") {
        store.unpin(call.reportScopeKey())
        call.respond(HttpStatusCode.NoContent)
    }.describe {
        summary = "評価レポート pin を解除する"
        description = "artifact を削除せず、scope の明示 pin だけを解除します。"
        tag(EVALUATION_REPORT_TAG)
        responses { HttpStatusCode.NoContent { description = "pin を解除しました。" } }
    }
}

@Suppress("LongParameterList")
private class EvaluationReportStore(
    private val repository: EvaluationRepository?,
    private val marketDataSource: MarketDataSource?,
    private val symbol: me.matsumo.fukurou.trading.domain.TradingSymbol,
    private val llmInvoker: LlmInvoker?,
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
        )
        persistence?.admit(job, scope.key)?.getOrThrow()
        synchronized(jobs) { jobs[job.jobId] = job }

        return job
    }

    @Suppress("LongMethod")
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
        val queryResult = source.fetchClosedTrades(period).getOrThrow()
        val candles = marketDataSource?.getCandles(
            symbol = symbol,
            interval = CandleInterval.ONE_DAY,
            limit = (scope.days + 40).coerceAtMost(500),
        )?.getOrDefault(emptyList()).orEmpty()
        val regimes = EvaluationMath.classifyMarketRegimes(candles, ReportZone)
        val stats = EvaluationMath.summarizeTrades(queryResult.trades)
        val ridge = EvaluationMath.historicalOutcomeRidges(queryResult.trades, ReportZone, regimes)
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
        val facts = baseFacts + ridgeFacts
        val chartIndex = listOf(
            EvaluationChartIndexResponse(
                chartId = "historical-realized-r-ridge",
                catalogVersion = ridge.catalogVersion,
                factIds = ridgeFacts.map { fact -> fact.factId },
            ),
        )
        val claims = listOf(
            EvaluationReportClaim("claim-pnl-direction", "FACT_DIRECTION", listOf("performance.totalPnlJpy"), direction(stats.totalPnlJpy)),
            EvaluationReportClaim("claim-trade-count", "FACT_VALUE", listOf("performance.tradeCount"), stats.tradeCount.toString()),
        )
        updateJob(job.copy(status = "RUNNING", stage = "GENERATING_REPORT"))
        val artifact = generateArtifact(
            fallbackClaims = claims,
            facts = facts,
            days = scope.days,
            snapshotId = UUID.randomUUID().toString(),
        )
        updateJob(job.copy(status = "RUNNING", stage = "VALIDATING"))
        val validation = EvaluationClaimValidator.validate(artifact.claims.toDomain(), facts)
        validateSnapshotReferences(
            facts = facts,
            sourceIds = setOf("paper-ledger"),
            chartIndex = chartIndex,
            claims = artifact.claims,
        )
        val canonical = buildString {
            append(from).append('|').append(toInclusive).append('|').append(queryResult.truncated)
            facts.forEach { fact -> append('|').append(fact.factId).append('=').append(fact.value) }
            ridge.groupings.forEach { grouping ->
                grouping.groups.forEach { group ->
                    val binCounts = group.bins.joinToString { bin -> bin.count.toString() }

                    append('|').append(grouping.groupBy).append(':').append(group.groupKey).append(':').append(binCounts)
                }
            }
        }
        val revisionNumber = persistence?.nextRevisionNumber()?.getOrThrow() ?: revisionSequence.incrementAndGet()
        val report = EvaluationReportResponse(
            jobId = job.jobId,
            revisionId = job.revisionId,
            revisionNumber = revisionNumber,
            scopeKey = scope.key,
            status = "SUCCEEDED",
            period = EvaluationReportPeriodResponse(from.toString(), toInclusive.toString(), ReportZone.id),
            inputAsOf = clock.instant().toString(),
            inputHash = sha256(canonical),
            snapshotId = UUID.randomUUID().toString(),
            generatedAt = clock.instant().toString(),
            provider = if (llmInvoker == null) "DETERMINISTIC_FALLBACK" else LlmProvider.CLAUDE.name,
            model = environment["FUKUROU_CLAUDE_MODEL"] ?: "CLI_DEFAULT",
            title = "${scope.label} PERFORMANCE / EVIDENCE REVIEW",
            segments = artifact.segments,
            claims = artifact.claims,
            validation = validation.map { result -> EvaluationClaimValidationResponse(result.claimId, result.status.name, result.asserted, result.actual, result.factIds, result.code) },
            facts = facts.map { fact -> EvaluationReportFactResponse(fact.factId, fact.value, fact.unit, fact.availability, fact.sourceIds) },
            sources = listOf(EvaluationReportSourceResponse("paper-ledger", clock.instant().toString(), "SNAPSHOT")),
            chartIndex = chartIndex,
            outcomeRidge = ridge.toResponse(),
            truncated = queryResult.truncated,
        )
        synchronized(reports) { reports.getOrPut(scope.key) { mutableListOf() }.add(0, report) }
        persistence?.saveReport(report)?.getOrThrow()
        updateJob(job.copy(status = "SUCCEEDED", stage = "COMPLETE"))
        report
    }.onFailure { error ->
        updateJob(
            job.copy(
                status = "FAILED",
                stage = "FAILED",
                failureCode = error::class.simpleName ?: "REPORT_GENERATION_FAILED",
                failureMessage = error.message?.take(300),
            ),
        )
    }

    private suspend fun generateArtifact(
        fallbackClaims: List<EvaluationReportClaim>,
        facts: List<EvaluationReportFact>,
        days: Int,
        snapshotId: String,
    ): GeneratedEvaluationArtifact {
        val invoker = llmInvoker ?: return fallbackArtifact(fallbackClaims, facts, days)
        val invocationId = UUID.randomUUID().toString()
        val promptHash = sha256(facts.joinToString { fact -> "${fact.factId}=${fact.value}" })
        val workingDirectory = Files.createTempDirectory("fukurou-evaluation-report-")
        val safeEnvironment = environment.filterKeys { key -> key in REPORT_CHILD_ENV_ALLOWLIST }
        val response = try {
            invoker.invoke(
                LlmInvocationRequest(
                    invocationId = invocationId,
                    provider = LlmProvider.CLAUDE,
                    phase = LlmInvocationPhase.EVALUATION_REPORT,
                    prompt = reportPrompt(days, facts),
                    timeout = Duration.ofMinutes(5),
                    workingDirectory = workingDirectory,
                    decisionRunContext = DecisionRunContext(
                        decisionRunId = invocationId,
                        llmProvider = LlmProvider.CLAUDE.name,
                        promptHash = promptHash,
                        systemPromptVersion = "evaluation-report-v1",
                        marketSnapshotId = snapshotId,
                    ),
                    mcpServer = null,
                    environment = safeEnvironment,
                    allowedTools = emptyList(),
                ),
            ).getOrThrow().responseText
        } finally {
            workingDirectory.toFile().deleteRecursively()
        }

        return ReportJson.decodeFromString(response.removeJsonFence())
    }

    fun default(scopeKey: String): EvaluationReportResponse? = synchronized(reports) {
        val pinnedId = pins[scopeKey]
        reports[scopeKey]?.firstOrNull { report -> report.revisionId == pinnedId }
            ?: reports[scopeKey]?.firstOrNull()
            ?: persistence?.default(scopeKey)?.getOrThrow()
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
            require(report.scopeKey == scopeKey) { "revision scope does not match pin scope" }
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
            EvaluationReportHistoryItemResponse(report.jobId, report.revisionId, report.revisionNumber, report.status, report.generatedAt, true)
        }
    }
}

private val ReportJson = Json { ignoreUnknownKeys = false }
private val REPORT_CHILD_ENV_ALLOWLIST = setOf("HOME", "PATH", "TMPDIR", "CODEX_HOME", "CLAUDE_CONFIG_DIR")

private fun reportPrompt(days: Int, facts: List<EvaluationReportFact>): String = """
    Generate a factual evaluation report for the previous $days complete calendar days.
    Return JSON only with this exact shape:
    {"segments":[{"segmentId":"seg-1","kind":"SUMMARY|PERFORMANCE|COVERAGE|LIMITATION","text":"...","claimIds":["claim-1"]}],"claims":[{"claimId":"claim-1","type":"FACT_VALUE|FACT_DIRECTION|FACT_COMPARISON","factIds":["fact.id"],"asserted":"value or operator"}]}
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
    claims: List<EvaluationReportClaimResponse>,
) {
    val factIds = facts.map { fact -> fact.factId }.toSet()
    require(facts.size == factIds.size) { "duplicate fact ID" }

    val sourcesAreValid = facts.flatMap { fact -> fact.sourceIds }.all { sourceId -> sourceId in sourceIds }
    require(sourcesAreValid) { "dangling source reference" }

    val chartFactsAreValid = chartIndex.flatMap { chart -> chart.factIds }.all { factId -> factId in factIds }
    require(chartFactsAreValid) { "dangling chart fact reference" }

    val claimFactsAreValid = claims.flatMap { claim -> claim.factIds }.all { factId -> factId in factIds }
    require(claimFactsAreValid) { "dangling claim fact reference" }
}

private fun fallbackArtifact(
    claims: List<EvaluationReportClaim>,
    facts: List<EvaluationReportFact>,
    days: Int,
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
            text = "欠損または除外された evidence は favorable outcome に補完されません。対象期間は ${days}D です。",
            claimIds = emptyList(),
        ),
    ),
    claims = claims.map { claim ->
        EvaluationReportClaimResponse(claim.claimId, claim.type, claim.factIds, claim.asserted)
    },
)

@Serializable
private data class GeneratedEvaluationArtifact(
    val segments: List<EvaluationReportSegmentResponse>,
    val claims: List<EvaluationReportClaimResponse>,
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
)

private data class EvaluationReportScope(
    val days: Int,
    val key: String,
    val label: String,
    val from: LocalDate? = null,
    val toInclusive: LocalDate? = null,
)

private fun EvaluationReportGenerateRequest.toScope(): EvaluationReportScope? {
    if (kind == "PRESET" && days in setOf(7, 30, 90)) {
        return EvaluationReportScope(requireNotNull(days), "PRESET:${days}D", "${days}D")
    }
    val hasCompleteCustomRange = kind == "CUSTOM" && from != null && toInclusive != null
    if (!hasCompleteCustomRange) return null

    val parsedFrom = runCatching { LocalDate.parse(requireNotNull(from)) }.getOrNull() ?: return null
    val parsedTo = runCatching { LocalDate.parse(requireNotNull(toInclusive)) }.getOrNull() ?: return null
    val customDays = java.time.temporal.ChronoUnit.DAYS.between(parsedFrom, parsedTo).toInt() + 1
    if (customDays !in 1..365) return null

    return EvaluationReportScope(
        days = customDays,
        key = "CUSTOM:$parsedFrom:$parsedTo",
        label = "$parsedFrom — $parsedTo",
        from = parsedFrom,
        toInclusive = parsedTo,
    )
}

@Serializable
data class EvaluationReportAdmissionErrorResponse(val code: String)

@Serializable
data class EvaluationReportPinRequest(
    val days: Int? = null,
    val scopeKey: String? = null,
    val revisionId: String,
)

@Serializable
data class EvaluationReportPinResponse(val scopeKey: String, val revisionId: String)

private fun EvaluationReportPinRequest.resolvedScopeKey(): String = scopeKey ?: "PRESET:${days ?: 30}D"

private fun io.ktor.server.application.ApplicationCall.reportScopeKey(): String =
    request.queryParameters["scopeKey"] ?: "PRESET:${request.queryParameters["days"]?.toIntOrNull() ?: 30}D"

@Serializable
data class EvaluationReportJobResponse(
    val jobId: String,
    val revisionId: String,
    val status: String,
    val stage: String,
    val failureCode: String? = null,
    val failureMessage: String? = null,
)

@Serializable
data class EvaluationReportPeriodResponse(val from: String, val toInclusive: String, val timezone: String)

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
    val title: String,
    val segments: List<EvaluationReportSegmentResponse>,
    val claims: List<EvaluationReportClaimResponse>,
    val validation: List<EvaluationClaimValidationResponse>,
    val facts: List<EvaluationReportFactResponse>,
    val sources: List<EvaluationReportSourceResponse>,
    val chartIndex: List<EvaluationChartIndexResponse>,
    val outcomeRidge: OutcomeRidgeResponse,
    val truncated: Boolean,
)

@Serializable
data class EvaluationReportHistoryItemResponse(
    val jobId: String,
    val revisionId: String,
    val revisionNumber: Long,
    val status: String,
    val requestedAt: String,
    val pinned: Boolean,
)

@Serializable
data class EvaluationReportHistoryResponse(val revisions: List<EvaluationReportHistoryItemResponse>)
