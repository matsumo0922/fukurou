package me.matsumo.fukurou

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.Serializable
import me.matsumo.fukurou.trading.evaluation.EvaluationMath
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.OutcomeRidgeChartFacts
import me.matsumo.fukurou.trading.evaluation.report.EvaluationClaimValidator
import me.matsumo.fukurou.trading.evaluation.report.EvaluationReportClaim
import me.matsumo.fukurou.trading.evaluation.report.EvaluationReportFact
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val EVALUATION_REPORT_TAG = "評価レポート"
private val ReportZone = ZoneId.of("Asia/Tokyo")

@OptIn(ExperimentalKtorApi::class)
internal fun Route.evaluationReportRoutes(dependencies: EvaluationRouteDependencies) {
    val store = EvaluationReportStore(dependencies.repository, dependencies.clock)

    post("/evaluation/reports/jobs") {
        val request = runCatching { call.receive<EvaluationReportGenerateRequest>() }.getOrNull()
        val days = request?.days
        if (days == null || days !in setOf(7, 30, 90)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("days must be one of 7, 30, 90"))
            return@post
        }

        val report = store.generate(days).getOrElse { error ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error.message ?: "report generation failed"))
            return@post
        }
        call.respond(HttpStatusCode.Accepted, EvaluationReportJobResponse(report.jobId, report.revisionId, "SUCCEEDED"))
    }.describe {
        summary = "評価レポートを手動生成する"
        description = "complete calendar days の immutable facts snapshot を固定し、typed claim の検証済みレポート revision を生成します。"
        tag(EVALUATION_REPORT_TAG)
        requestBody { schema = jsonSchema<EvaluationReportGenerateRequest>() }
        responses {
            HttpStatusCode.Accepted { schema = jsonSchema<EvaluationReportJobResponse>() }
            HttpStatusCode.BadRequest { schema = jsonSchema<ErrorResponse>() }
            HttpStatusCode.InternalServerError { schema = jsonSchema<ErrorResponse>() }
        }
    }

    get("/evaluation/reports/default") {
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        val report = store.default(days)
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

    get("/evaluation/reports/revisions") {
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        call.respond(EvaluationReportHistoryResponse(store.history(days)))
    }.describe {
        summary = "評価レポート履歴を取得する"
        description = "生成 request ごとに保持する immutable revision 履歴を新しい順で返します。"
        tag(EVALUATION_REPORT_TAG)
        responses { HttpStatusCode.OK { schema = jsonSchema<EvaluationReportHistoryResponse>() } }
    }
}

private class EvaluationReportStore(
    private val repository: EvaluationRepository?,
    private val clock: Clock,
) {
    private val revisionSequence = AtomicLong(0)
    private val reports = mutableMapOf<Int, MutableList<EvaluationReportResponse>>()

    @Suppress("LongMethod")
    suspend fun generate(days: Int): Result<EvaluationReportResponse> = runCatching {
        val source = requireNotNull(repository) { "evaluation repository is unavailable" }
        val today = LocalDate.now(clock.withZone(ReportZone))
        val toInclusive = today.minusDays(1)
        val from = toInclusive.minusDays(days.toLong() - 1)
        val period = EvaluationPeriod(
            from = from.atStartOfDay(ReportZone).toInstant(),
            toExclusive = toInclusive.plusDays(1).atStartOfDay(ReportZone).toInstant(),
        )
        val queryResult = source.fetchClosedTrades(period).getOrThrow()
        val stats = EvaluationMath.summarizeTrades(queryResult.trades)
        val ridge = EvaluationMath.historicalOutcomeRidges(queryResult.trades, ReportZone)
        val facts = listOf(
            EvaluationReportFact("performance.tradeCount", stats.tradeCount.toString(), "COUNT", "AVAILABLE", listOf("paper-ledger")),
            EvaluationReportFact("performance.totalPnlJpy", stats.totalPnlJpy.toPlainString(), "JPY", "AVAILABLE", listOf("paper-ledger")),
            EvaluationReportFact("performance.expectedR", stats.expectedR?.toPlainString(), "R", if (stats.expectedR == null) "MISSING" else "AVAILABLE", listOf("paper-ledger")),
        )
        val claims = listOf(
            EvaluationReportClaim("claim-pnl-direction", "FACT_DIRECTION", listOf("performance.totalPnlJpy"), direction(stats.totalPnlJpy)),
            EvaluationReportClaim("claim-trade-count", "FACT_VALUE", listOf("performance.tradeCount"), stats.tradeCount.toString()),
        )
        val validation = EvaluationClaimValidator.validate(claims, facts)
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
        val revisionNumber = revisionSequence.incrementAndGet()
        val report = EvaluationReportResponse(
            jobId = UUID.randomUUID().toString(),
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = revisionNumber,
            scopeKey = "PRESET:${days}D",
            status = "SUCCEEDED",
            period = EvaluationReportPeriodResponse(from.toString(), toInclusive.toString(), ReportZone.id),
            inputAsOf = clock.instant().toString(),
            inputHash = sha256(canonical),
            snapshotId = UUID.randomUUID().toString(),
            generatedAt = clock.instant().toString(),
            provider = "SERVER_REPORT_ENGINE",
            model = "evaluation-report-v1",
            title = "${days}D PERFORMANCE / EVIDENCE REVIEW",
            segments = listOf(
                EvaluationReportSegmentResponse(
                    segmentId = "segment-summary",
                    kind = "SUMMARY",
                    text = "期間内の確定済み paper trade は ${stats.tradeCount} 件、実現損益は ${stats.totalPnlJpy.toPlainString()} JPY です。",
                    claimIds = claims.map { claim -> claim.claimId },
                ),
                EvaluationReportSegmentResponse("segment-limitations", "LIMITATION", "欠損または除外された evidence は favorable outcome に補完されません。", emptyList()),
            ),
            claims = claims.map { claim -> EvaluationReportClaimResponse(claim.claimId, claim.type, claim.factIds, claim.asserted) },
            validation = validation.map { result -> EvaluationClaimValidationResponse(result.claimId, result.status.name, result.asserted, result.actual, result.factIds, result.code) },
            facts = facts.map { fact -> EvaluationReportFactResponse(fact.factId, fact.value, fact.unit, fact.availability, fact.sourceIds) },
            sources = listOf(EvaluationReportSourceResponse("paper-ledger", clock.instant().toString(), "SNAPSHOT")),
            outcomeRidge = ridge.toResponse(),
            truncated = queryResult.truncated,
        )
        synchronized(reports) { reports.getOrPut(days) { mutableListOf() }.add(0, report) }
        report
    }

    fun default(days: Int): EvaluationReportResponse? = synchronized(reports) { reports[days]?.firstOrNull() }

    fun history(days: Int): List<EvaluationReportHistoryItemResponse> = synchronized(reports) {
        reports[days].orEmpty().map { report ->
            EvaluationReportHistoryItemResponse(report.jobId, report.revisionId, report.revisionNumber, report.status, report.generatedAt, true)
        }
    }
}

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
data class EvaluationReportGenerateRequest(val days: Int)

@Serializable
data class EvaluationReportJobResponse(val jobId: String, val revisionId: String, val status: String)

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
