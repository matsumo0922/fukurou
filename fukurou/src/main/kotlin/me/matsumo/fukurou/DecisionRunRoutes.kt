package me.matsumo.fukurou

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.Serializable
import me.matsumo.fukurou.trading.activity.DecisionRunCursor
import me.matsumo.fukurou.trading.activity.DecisionRunDecision
import me.matsumo.fukurou.trading.activity.DecisionRunDetail
import me.matsumo.fukurou.trading.activity.DecisionRunExecution
import me.matsumo.fukurou.trading.activity.DecisionRunFilter
import me.matsumo.fukurou.trading.activity.DecisionRunFalsification
import me.matsumo.fukurou.trading.activity.DecisionRunIntent
import me.matsumo.fukurou.trading.activity.DecisionRunOrder
import me.matsumo.fukurou.trading.activity.DecisionRunOutcome
import me.matsumo.fukurou.trading.activity.DecisionRunPage
import me.matsumo.fukurou.trading.activity.DecisionRunRawRecord
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyViolation
import me.matsumo.fukurou.trading.activity.DecisionRunSummary
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.requiresEntryIntent
import me.matsumo.fukurou.trading.decision.requiresSafetyFloor
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64

private const val RUNS_TAG = "ops"
private const val DEFAULT_RUN_LIMIT = 30
private const val MAX_RUN_LIMIT = 100

/** decision run 一覧 endpoint の OpenAPI description。 */
private const val RUNS_DESCRIPTION =
    "llm_runs を起点に decision、Falsifier、SafetyFloor、order、execution を正規化した run 一覧を新しい順で返します。" +
        "outcome filter は bounded window を走査し、上限到達時は次の window 用 cursor を返します。"

/** decision run 一覧 response。 */
@Serializable
data class OpsDecisionRunsResponse(
    val runs: List<OpsDecisionRunSummaryResponse>,
    val nextBefore: String?,
)

/** decision run の API outcome。 */
@Serializable
enum class OpsDecisionRunOutcome {
    WAITING,
    FILLED,
    EXPIRED,
    NO_ENTRY,
    RUNNING,
    FAILED,
    ACTION_REQUIRED,
}

/** Activity の目的別 filter。 */
@Serializable
enum class OpsDecisionRunFilter {
    ACTION_REQUIRED,
    WAITING,
    FILLED,
    NO_ENTRY,
}

/** decision run 一覧要素。 */
@Serializable
data class OpsDecisionRunSummaryResponse(
    val invocationId: String,
    val mode: String,
    val symbol: String,
    val triggerKind: String?,
    val status: String,
    val outcome: OpsDecisionRunOutcome,
    val startedAt: String,
    val finishedAt: String?,
    val durationMillis: Long?,
    val action: String?,
    val reasonJa: String?,
    val falsificationVerdict: String?,
    val safetyRule: String?,
    val safetyMessageJa: String?,
    val finalReason: String?,
    val errorMessage: String?,
    val orderCount: Int,
    val executionCount: Int,
    val order: OpsDecisionRunOrderResponse?,
    val currentQuote: OpsDecisionRunQuoteResponse?,
)

/** Activity に表示する参考価格。paper fill の根拠には使わない。 */
@Serializable
data class OpsDecisionRunQuoteResponse(
    val priceJpy: String,
    val observedAt: String,
    val stale: Boolean,
)

/** decision run 詳細 response。 */
@Serializable
data class OpsDecisionRunDetailResponse(
    val summary: OpsDecisionRunSummaryResponse,
    val phases: List<OpsDecisionRunPhaseResponse>,
    val decision: OpsDecisionRunDecisionResponse?,
    val intent: OpsDecisionRunIntentResponse?,
    val falsification: OpsDecisionRunFalsificationResponse?,
    val safetyViolation: OpsDecisionRunSafetyViolationResponse?,
    val orders: List<OpsDecisionRunOrderResponse>,
    val executions: List<OpsDecisionRunExecutionResponse>,
    val raw: List<OpsDecisionRunRawRecordResponse>,
)

/** run 内の段階表示。 */
@Serializable
data class OpsDecisionRunPhaseResponse(
    val key: String,
    val status: String,
    val detail: String?,
)

/** LLM decision section。 */
@Serializable
data class OpsDecisionRunDecisionResponse(
    val decisionId: String,
    val action: String,
    val provider: String?,
    val estimatedWinProbability: String,
    val expectedRMultiple: String?,
    val roundTripCostR: String?,
    val reasonJa: String,
    val setupTagsJson: String,
    val missingDataJaJson: String,
    val noTradeConditionsJaJson: String,
    val createdAt: String,
)

/** intent / TradePlan section。 */
@Serializable
data class OpsDecisionRunIntentResponse(
    val intentId: String,
    val tradePlanId: String,
    val parentTradePlanId: String?,
    val revisionCount: Int,
    val side: String,
    val orderType: String,
    val sizeBtc: String,
    val priceJpy: String?,
    val protectiveStopPriceJpy: String,
    val takeProfitPriceJpy: String?,
    val thesisJa: String?,
    val invalidationConditionsJaJson: String?,
    val targetPriceJpy: String?,
    val timeStopAt: String?,
    val setupTagsJson: String?,
)

/** Falsifier section。 */
@Serializable
data class OpsDecisionRunFalsificationResponse(
    val verdict: String,
    val provider: String?,
    val reasonJa: String,
    val createdAt: String,
)

/** SafetyFloor section。 */
@Serializable
data class OpsDecisionRunSafetyViolationResponse(
    val rule: String,
    val measuredValue: String,
    val limitValue: String,
    val messageJa: String,
    val createdAt: String,
)

/** order section。 */
@Serializable
data class OpsDecisionRunOrderResponse(
    val orderId: String,
    val intentId: String?,
    val positionId: String?,
    val tradeGroupId: String?,
    val side: String,
    val orderType: String,
    val status: String,
    val sizeBtc: String,
    val limitPriceJpy: String?,
    val reasonJa: String?,
    val expiresAt: String?,
    val expirySource: String?,
    val effectiveTtlSeconds: Long?,
    val expiredAt: String?,
    val canceledAt: String?,
    val cancelReason: String?,
    val createdAt: String,
)

/** execution section。 */
@Serializable
data class OpsDecisionRunExecutionResponse(
    val executionId: String,
    val orderId: String?,
    val positionId: String?,
    val side: String,
    val priceJpy: String,
    val sizeBtc: String,
    val realizedPnlJpy: String,
    val executedAt: String,
)

/** secret payload を除外した raw/debug section。 */
@Serializable
data class OpsDecisionRunRawRecordResponse(
    val source: String,
    val occurredAt: String,
    val values: Map<String, String?>,
)

@OptIn(ExperimentalKtorApi::class)
internal fun Route.registerOpsDecisionRunRoutes(dependencies: OpsRouteDependencies) {
    registerOpsDecisionRunsListRoute(dependencies)
    registerOpsDecisionRunDetailRoute(dependencies)
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsDecisionRunsListRoute(dependencies: OpsRouteDependencies) {
    get("/ops/runs") {
        val repository = dependencies.feed.decisionRunProjectionRepository
        if (repository == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("decision run projection is unavailable"))
            return@get
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_RUN_LIMIT
        if (limit !in 1..MAX_RUN_LIMIT) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("limit must be between 1 and $MAX_RUN_LIMIT"))
            return@get
        }
        val cursor = call.request.queryParameters["before"]?.let(::decodeRunCursor)
        if (call.request.queryParameters["before"] != null && cursor == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("before must be a valid opaque cursor"))
            return@get
        }
        val filterParameter = call.request.queryParameters["filter"]
        val filter = filterParameter?.let(::parseRunFilter)
        if (filterParameter != null && filter == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("filter must be a valid decision run filter"))
            return@get
        }
        val page = repository.listRuns(cursor, limit + 1, filter).getOrThrow()
        call.respond(page.toResponse(limit, dependencies.referenceQuote()))
    }.describe {
        summary = "decision run 一覧を取得する"
        description = RUNS_DESCRIPTION
        tag(RUNS_TAG)
        parameters {
            query("limit") {
                description = "取得件数です。既定 30、最大 100 です。"
                schema = jsonSchema<Int>()
            }
            query("before") {
                description = "前回応答の nextBefore を指定する opaque cursor です。"
                schema = jsonSchema<String>()
            }
            query("filter") {
                description = "pagination より前に bounded scan で適用する目的別 filter です。"
                schema = jsonSchema<OpsDecisionRunFilter>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "decision run 一覧です。"
                schema = jsonSchema<OpsDecisionRunsResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "limit、before、または filter が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "projection repository が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsDecisionRunDetailRoute(dependencies: OpsRouteDependencies) {
    get("/ops/runs/{invocationId}") {
        val repository = dependencies.feed.decisionRunProjectionRepository
        if (repository == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("decision run projection is unavailable"))
            return@get
        }
        val invocationId = call.parameters["invocationId"].orEmpty()
        val detail = repository.findRun(invocationId).getOrThrow()
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("decision run was not found"))
            return@get
        }

        call.respond(detail.toResponse(dependencies.referenceQuote()))
    }.describe {
        summary = "decision run 詳細を取得する"
        description = "Trigger から Order / Execution までの段階、LLM 申告値、Falsifier、SafetyFloor、関連 ledger、secret を除外した raw/debug 情報を返します。"
        tag(RUNS_TAG)
        parameters {
            path("invocationId") {
                description = "runner の invocation ID です。"
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "decision run 詳細です。"
                schema = jsonSchema<OpsDecisionRunDetailResponse>()
            }
            HttpStatusCode.NotFound {
                description = "run が存在しません。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "projection repository が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

private suspend fun OpsRouteDependencies.referenceQuote(): OpsDecisionRunQuoteResponse? {
    val snapshot = feed.paperLedgerRepository?.getAccountSnapshotWithUpdatedAt()?.getOrNull() ?: return null
    val observedAt = snapshot.updatedAt

    return OpsDecisionRunQuoteResponse(
        priceJpy = snapshot.accountSnapshot.btcMarkPriceJpy,
        observedAt = observedAt.toString(),
        stale = Duration.between(observedAt, clock.instant()) > Duration.ofMinutes(2),
    )
}

private fun DecisionRunPage.toResponse(
    limit: Int,
    quote: OpsDecisionRunQuoteResponse?,
): OpsDecisionRunsResponse {
    val visible = runs.take(limit)
    val nextCursor = if (runs.size > limit) {
        visible.lastOrNull()?.toCursor()
    } else {
        scanContinuation
    }

    return OpsDecisionRunsResponse(
        runs = visible.map { summary -> summary.toResponse(quote) },
        nextBefore = nextCursor?.let(::encodeRunCursor),
    )
}

private fun DecisionRunSummary.toResponse(quote: OpsDecisionRunQuoteResponse?): OpsDecisionRunSummaryResponse {
    return OpsDecisionRunSummaryResponse(
        invocationId = invocationId,
        mode = mode,
        symbol = symbol,
        triggerKind = triggerKind,
        status = status,
        outcome = outcome.toResponse(),
        startedAt = startedAt.toString(),
        finishedAt = finishedAt?.toString(),
        durationMillis = finishedAt?.let { end -> Duration.between(startedAt, end).toMillis() },
        action = action,
        reasonJa = reasonJa,
        falsificationVerdict = falsificationVerdict,
        safetyRule = safetyRule,
        safetyMessageJa = safetyMessageJa,
        finalReason = finalReason,
        errorMessage = errorMessage,
        orderCount = orderCount,
        executionCount = executionCount,
        order = order?.toResponse(),
        currentQuote = quote,
    )
}

private fun DecisionRunDetail.toResponse(quote: OpsDecisionRunQuoteResponse?): OpsDecisionRunDetailResponse {
    return OpsDecisionRunDetailResponse(
        summary = summary.toResponse(quote),
        phases = phases(),
        decision = decision?.toResponse(),
        intent = intent?.toResponse(),
        falsification = falsification?.toResponse(),
        safetyViolation = safetyViolation?.toResponse(),
        orders = orders.map(DecisionRunOrder::toResponse),
        executions = executions.map(DecisionRunExecution::toResponse),
        raw = raw.map(DecisionRunRawRecord::toResponse),
    )
}

private fun DecisionRunDetail.phases(): List<OpsDecisionRunPhaseResponse> {
    val isRunning = summary.outcome == DecisionRunOutcome.RUNNING
    val stoppedStatus = if (isRunning) "RUNNING" else "NOT_REACHED"
    return listOf(
        OpsDecisionRunPhaseResponse("TRIGGER", "COMPLETED", summary.triggerKind ?: "MANUAL"),
        OpsDecisionRunPhaseResponse("PROPOSER", decision?.let { "COMPLETED" } ?: stoppedStatus, decision?.provider),
        OpsDecisionRunPhaseResponse("INTENT", intentPhaseStatus(stoppedStatus), intent?.intentId),
        OpsDecisionRunPhaseResponse(
            "FALSIFIER",
            falsificationPhaseStatus(stoppedStatus),
            falsification?.reasonJa,
        ),
        OpsDecisionRunPhaseResponse(
            "SAFETY",
            safetyPhaseStatus(stoppedStatus),
            safetyViolation?.rule,
        ),
        OpsDecisionRunPhaseResponse(
            "ORDER_EXECUTION",
            orderExecutionPhaseStatus(stoppedStatus),
            "orders=${orders.size}, executions=${executions.size}",
        ),
    )
}

private fun DecisionRunDetail.intentPhaseStatus(stoppedStatus: String): String {
    if (intent != null) return "COMPLETED"
    return if (decisionAction()?.requiresEntryIntent() == true) stoppedStatus else "NOT_REQUIRED"
}

private fun DecisionRunDetail.falsificationPhaseStatus(stoppedStatus: String): String {
    return falsification?.verdict ?: if (intent == null) "NOT_REQUIRED" else stoppedStatus
}

private fun DecisionRunDetail.safetyPhaseStatus(stoppedStatus: String): String {
    return when {
        safetyViolation != null -> "DENIED"
        decisionAction()?.requiresSafetyFloor() != true -> "NOT_REQUIRED"
        orders.isNotEmpty() -> "PASSED"
        executions.isNotEmpty() -> "PASSED"
        else -> stoppedStatus
    }
}

private fun DecisionRunDetail.orderExecutionPhaseStatus(stoppedStatus: String): String {
    return if (orders.isNotEmpty() || executions.isNotEmpty()) "COMPLETED" else stoppedStatus
}

private fun DecisionRunDetail.decisionAction(): DecisionAction? {
    val action = decision?.action ?: return null

    return DecisionAction.entries.find { candidate -> candidate.name == action }
}

private fun DecisionRunOutcome.toResponse(): OpsDecisionRunOutcome {
    return when (this) {
        DecisionRunOutcome.WAITING -> OpsDecisionRunOutcome.WAITING
        DecisionRunOutcome.FILLED -> OpsDecisionRunOutcome.FILLED
        DecisionRunOutcome.EXPIRED -> OpsDecisionRunOutcome.EXPIRED
        DecisionRunOutcome.NO_ENTRY -> OpsDecisionRunOutcome.NO_ENTRY
        DecisionRunOutcome.RUNNING -> OpsDecisionRunOutcome.RUNNING
        DecisionRunOutcome.FAILED -> OpsDecisionRunOutcome.FAILED
        DecisionRunOutcome.ACTION_REQUIRED -> OpsDecisionRunOutcome.ACTION_REQUIRED
    }
}

private fun DecisionRunDecision.toResponse() = OpsDecisionRunDecisionResponse(
    decisionId = decisionId,
    action = action,
    provider = provider,
    estimatedWinProbability = estimatedWinProbability,
    expectedRMultiple = expectedRMultiple,
    roundTripCostR = roundTripCostR,
    reasonJa = reasonJa,
    setupTagsJson = setupTagsJson,
    missingDataJaJson = missingDataJaJson,
    noTradeConditionsJaJson = noTradeConditionsJaJson,
    createdAt = createdAt.toString(),
)

private fun DecisionRunIntent.toResponse() = OpsDecisionRunIntentResponse(
    intentId = intentId,
    tradePlanId = tradePlanId,
    parentTradePlanId = parentTradePlanId,
    revisionCount = revisionCount,
    side = side,
    orderType = orderType,
    sizeBtc = sizeBtc,
    priceJpy = priceJpy,
    protectiveStopPriceJpy = protectiveStopPriceJpy,
    takeProfitPriceJpy = takeProfitPriceJpy,
    thesisJa = thesisJa,
    invalidationConditionsJaJson = invalidationConditionsJaJson,
    targetPriceJpy = targetPriceJpy,
    timeStopAt = timeStopAt?.toString(),
    setupTagsJson = setupTagsJson,
)

private fun DecisionRunFalsification.toResponse() = OpsDecisionRunFalsificationResponse(
    verdict = verdict,
    provider = provider,
    reasonJa = reasonJa,
    createdAt = createdAt.toString(),
)

private fun DecisionRunSafetyViolation.toResponse() = OpsDecisionRunSafetyViolationResponse(
    rule = rule,
    measuredValue = measuredValue,
    limitValue = limitValue,
    messageJa = messageJa,
    createdAt = createdAt.toString(),
)

private fun DecisionRunOrder.toResponse() = OpsDecisionRunOrderResponse(
    orderId = orderId,
    intentId = intentId,
    positionId = positionId,
    tradeGroupId = tradeGroupId,
    side = side,
    orderType = orderType,
    status = status,
    sizeBtc = sizeBtc,
    limitPriceJpy = limitPriceJpy,
    reasonJa = reasonJa,
    expiresAt = expiresAt?.toString(),
    expirySource = expirySource,
    effectiveTtlSeconds = effectiveTtlSeconds,
    expiredAt = expiredAt?.toString(),
    canceledAt = canceledAt?.toString(),
    cancelReason = cancelReason,
    createdAt = createdAt.toString(),
)

private fun DecisionRunExecution.toResponse() = OpsDecisionRunExecutionResponse(
    executionId = executionId,
    orderId = orderId,
    positionId = positionId,
    side = side,
    priceJpy = priceJpy,
    sizeBtc = sizeBtc,
    realizedPnlJpy = realizedPnlJpy,
    executedAt = executedAt.toString(),
)

private fun DecisionRunRawRecord.toResponse() = OpsDecisionRunRawRecordResponse(
    source = source,
    occurredAt = occurredAt.toString(),
    values = values,
)

private fun DecisionRunSummary.toCursor(): DecisionRunCursor {
    return DecisionRunCursor(startedAt, invocationId)
}

private fun encodeRunCursor(cursor: DecisionRunCursor): String {
    val value = "${cursor.startedAt.toEpochMilli()}:${cursor.invocationId}"
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

private fun decodeRunCursor(value: String): DecisionRunCursor? {
    return runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        val separator = decoded.indexOf(':')
        require(separator > 0)
        DecisionRunCursor(
            startedAt = Instant.ofEpochMilli(decoded.substring(0, separator).toLong()),
            invocationId = decoded.substring(separator + 1).also { require(it.isNotBlank()) },
        )
    }.getOrNull()
}

private fun parseRunFilter(value: String): DecisionRunFilter? {
    return DecisionRunFilter.entries.find { filter -> filter.name == value }
}
