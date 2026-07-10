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
import me.matsumo.fukurou.trading.activity.DecisionRunFalsification
import me.matsumo.fukurou.trading.activity.DecisionRunIntent
import me.matsumo.fukurou.trading.activity.DecisionRunOrder
import me.matsumo.fukurou.trading.activity.DecisionRunRawRecord
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyViolation
import me.matsumo.fukurou.trading.activity.DecisionRunSummary
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64

private const val RUNS_TAG = "ops"
private const val DEFAULT_RUN_LIMIT = 30
private const val MAX_RUN_LIMIT = 100

/** decision run 一覧 response。 */
@Serializable
data class OpsDecisionRunsResponse(
    val runs: List<OpsDecisionRunSummaryResponse>,
    val nextBefore: String?,
)

/** decision run の API outcome。 */
@Serializable
enum class OpsDecisionRunOutcome {
    EXECUTED,
    DENIED,
    NO_TRADE,
    INTERRUPTED,
    RUNNING,
    FAILED,
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
    val errorMessage: String?,
    val orderCount: Int,
    val executionCount: Int,
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
        val records = repository.listRuns(cursor, limit + 1).getOrThrow()
        val hasNext = records.size > limit
        val visible = records.take(limit)
        val nextBefore = if (hasNext) visible.lastOrNull()?.let(::encodeRunCursor) else null

        call.respond(
            OpsDecisionRunsResponse(
                runs = visible.map(DecisionRunSummary::toResponse),
                nextBefore = nextBefore,
            ),
        )
    }.describe {
        summary = "decision run 一覧を取得する"
        description = "llm_runs を起点に decision、Falsifier、SafetyFloor、order、execution を正規化した run 一覧を新しい順で返します。"
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
        }
        responses {
            HttpStatusCode.OK {
                description = "decision run 一覧です。"
                schema = jsonSchema<OpsDecisionRunsResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "limit または before が不正です。"
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

        call.respond(detail.toResponse())
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

private fun DecisionRunSummary.toResponse(): OpsDecisionRunSummaryResponse {
    return OpsDecisionRunSummaryResponse(
        invocationId = invocationId,
        mode = mode,
        symbol = symbol,
        triggerKind = triggerKind,
        status = status,
        outcome = OpsDecisionRunOutcome.valueOf(outcome.name),
        startedAt = startedAt.toString(),
        finishedAt = finishedAt?.toString(),
        durationMillis = finishedAt?.let { end -> Duration.between(startedAt, end).toMillis() },
        action = action,
        reasonJa = reasonJa,
        falsificationVerdict = falsificationVerdict,
        safetyRule = safetyRule,
        safetyMessageJa = safetyMessageJa,
        errorMessage = errorMessage,
        orderCount = orderCount,
        executionCount = executionCount,
    )
}

private fun DecisionRunDetail.toResponse(): OpsDecisionRunDetailResponse {
    return OpsDecisionRunDetailResponse(
        summary = summary.toResponse(),
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
    val isRunning = summary.outcome.name == "RUNNING"
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
    return if (requiresIntent()) stoppedStatus else "NOT_REQUIRED"
}

private fun DecisionRunDetail.falsificationPhaseStatus(stoppedStatus: String): String {
    return falsification?.verdict ?: if (intent == null) "NOT_REQUIRED" else stoppedStatus
}

private fun DecisionRunDetail.safetyPhaseStatus(stoppedStatus: String): String {
    return when {
        safetyViolation != null -> "DENIED"
        !requiresIntent() -> "NOT_REQUIRED"
        orders.isNotEmpty() -> "PASSED"
        executions.isNotEmpty() -> "PASSED"
        else -> stoppedStatus
    }
}

private fun DecisionRunDetail.orderExecutionPhaseStatus(stoppedStatus: String): String {
    return if (orders.isNotEmpty() || executions.isNotEmpty()) "COMPLETED" else stoppedStatus
}

private fun DecisionRunDetail.requiresIntent(): Boolean {
    return decision?.action == "ENTER" || decision?.action == "ADD_LONG"
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

private fun encodeRunCursor(summary: DecisionRunSummary): String {
    val value = "${summary.startedAt.toEpochMilli()}:${summary.invocationId}"
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
