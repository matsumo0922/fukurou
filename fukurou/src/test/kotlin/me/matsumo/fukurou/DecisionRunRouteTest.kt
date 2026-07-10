package me.matsumo.fukurou

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.activity.DecisionRunCursor
import me.matsumo.fukurou.trading.activity.DecisionRunDecision
import me.matsumo.fukurou.trading.activity.DecisionRunDetail
import me.matsumo.fukurou.trading.activity.DecisionRunFalsification
import me.matsumo.fukurou.trading.activity.DecisionRunIntent
import me.matsumo.fukurou.trading.activity.DecisionRunOrder
import me.matsumo.fukurou.trading.activity.DecisionRunOutcome
import me.matsumo.fukurou.trading.activity.DecisionRunPage
import me.matsumo.fukurou.trading.activity.DecisionRunProjectionRepository
import me.matsumo.fukurou.trading.activity.DecisionRunRawRecord
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyViolation
import me.matsumo.fukurou.trading.activity.DecisionRunSummary
import me.matsumo.fukurou.trading.decision.DecisionAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecisionRunRouteTest {

    @Test
    fun runsRouteReturnsStableCursorAndNormalizedDeniedDetail() = testApplication {
        val repository = FakeDecisionRunProjectionRepository()
        application {
            module(
                opsDecisionRunProjectionRepository = repository,
                databaseConfig = null,
            )
        }

        val firstResponse = client.get("/ops/runs?limit=1")
        assertEquals(HttpStatusCode.OK, firstResponse.status)
        val firstPage = Json.decodeFromString<OpsDecisionRunsResponse>(firstResponse.body())
        assertEquals(listOf("run-new"), firstPage.runs.map { run -> run.invocationId })
        assertNotNull(firstPage.nextBefore)

        val secondResponse = client.get("/ops/runs?limit=1&before=${firstPage.nextBefore}")
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        assertEquals(Instant.parse("2026-07-10T00:47:27Z"), repository.lastCursor?.startedAt)
        assertEquals("run-new", repository.lastCursor?.invocationId)

        val filteredResponse = client.get("/ops/runs?outcome=INTERRUPTED")
        assertEquals(HttpStatusCode.OK, filteredResponse.status)
        val filteredPage = Json.decodeFromString<OpsDecisionRunsResponse>(filteredResponse.body())
        assertEquals(listOf("run-old"), filteredPage.runs.map { run -> run.invocationId })
        assertEquals(DecisionRunOutcome.INTERRUPTED, repository.lastOutcome)

        val cappedResponse = client.get("/ops/runs?outcome=RUNNING")
        assertEquals(HttpStatusCode.OK, cappedResponse.status)
        val cappedPage = Json.decodeFromString<OpsDecisionRunsResponse>(cappedResponse.body())
        assertTrue(cappedPage.runs.isEmpty())
        assertNotNull(cappedPage.nextBefore)

        val continuedResponse = client.get("/ops/runs?outcome=RUNNING&before=${cappedPage.nextBefore}")
        assertEquals(HttpStatusCode.OK, continuedResponse.status)
        val continuedPage = Json.decodeFromString<OpsDecisionRunsResponse>(continuedResponse.body())
        assertTrue(continuedPage.runs.isEmpty())
        assertEquals(null, continuedPage.nextBefore)
        assertEquals("scan-boundary", repository.lastCursor?.invocationId)

        val detailResponse = client.get("/ops/runs/run-new")
        assertEquals(HttpStatusCode.OK, detailResponse.status)
        val detail = Json.decodeFromString<OpsDecisionRunDetailResponse>(detailResponse.body())
        assertEquals(OpsDecisionRunOutcome.DENIED, detail.summary.outcome)
        assertEquals("APPROVED", detail.falsification?.verdict)
        assertEquals("0.1100000000", detail.decision?.expectedRMultiple)
        assertEquals("0.03357778", detail.safetyViolation?.measuredValue)
        assertEquals("0.10", detail.safetyViolation?.limitValue)
        assertEquals("preview_order_rejected", detail.summary.finalReason)
        assertEquals("parent-plan-1", detail.intent?.parentTradePlanId)
        assertEquals(2, detail.intent?.revisionCount)
        assertEquals("[\"trend-breakout\"]", detail.intent?.setupTagsJson)
        assertEquals(0, detail.orders.size)
        assertEquals(0, detail.executions.size)
        assertTrue(detail.raw.none { raw -> raw.values.keys.any { key -> key.contains("secret", ignoreCase = true) } })
        assertEquals(HttpStatusCode.BadRequest, client.get("/ops/runs?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/ops/runs?before=invalid").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/ops/runs?outcome=UNKNOWN").status)
    }

    @Test
    fun nonEntryTradeActionsShowSafetyFloorAsPassed() = testApplication {
        application {
            module(
                opsDecisionRunProjectionRepository = SafetyPassedDecisionRunProjectionRepository(),
                databaseConfig = null,
            )
        }

        listOf(DecisionAction.EXIT, DecisionAction.REDUCE, DecisionAction.ADJUST_PROTECTION).forEach { action ->
            val response = client.get("/ops/runs/safety-${action.name.lowercase()}")
            assertEquals(HttpStatusCode.OK, response.status)
            val detail = Json.decodeFromString<OpsDecisionRunDetailResponse>(response.body())
            assertEquals("NOT_REQUIRED", detail.phases.single { phase -> phase.key == "INTENT" }.status)
            assertEquals("PASSED", detail.phases.single { phase -> phase.key == "SAFETY" }.status)
            assertEquals("COMPLETED", detail.phases.single { phase -> phase.key == "ORDER_EXECUTION" }.status)
        }

        val unknownResponse = client.get("/ops/runs/safety-unknown")
        assertEquals(HttpStatusCode.OK, unknownResponse.status)
        val unknownDetail = Json.decodeFromString<OpsDecisionRunDetailResponse>(unknownResponse.body())
        assertEquals("NOT_REQUIRED", unknownDetail.phases.single { phase -> phase.key == "INTENT" }.status)
        assertEquals("NOT_REQUIRED", unknownDetail.phases.single { phase -> phase.key == "SAFETY" }.status)
    }

    @Test
    fun runsRouteHandlesUnavailableInvalidAndMissingProjectionStates() = testApplication {
        application { module(databaseConfig = null) }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ops/runs").status)
    }
}

private class FakeDecisionRunProjectionRepository : DecisionRunProjectionRepository {
    var lastCursor: DecisionRunCursor? = null
    var lastOutcome: DecisionRunOutcome? = null

    private val denied = deniedRunSummary()
    private val interrupted = denied.copy(
        invocationId = "run-old",
        status = "FAILED",
        startedAt = Instant.parse("2026-07-10T00:47:27Z"),
        finishedAt = Instant.parse("2026-07-10T00:20:00Z"),
        errorMessage = "previous process/container shutdown recovery",
        action = null,
        reasonJa = null,
        falsificationVerdict = null,
        safetyRule = null,
        safetyMessageJa = null,
        outcome = DecisionRunOutcome.INTERRUPTED,
    )

    override suspend fun listRuns(
        cursor: DecisionRunCursor?,
        limit: Int,
        outcome: DecisionRunOutcome?,
    ): Result<DecisionRunPage> {
        lastCursor = cursor
        lastOutcome = outcome
        if (outcome == DecisionRunOutcome.RUNNING) {
            val continuation = if (cursor == null) {
                DecisionRunCursor(Instant.parse("2026-07-09T23:00:00Z"), "scan-boundary")
            } else {
                null
            }

            return Result.success(DecisionRunPage(emptyList(), continuation))
        }
        val runs = if (cursor == null) listOf(denied, interrupted) else listOf(interrupted)

        return Result.success(
            DecisionRunPage(
                runs = runs.filter { run -> outcome == null || run.outcome == outcome }.take(limit),
                scanContinuation = null,
            ),
        )
    }

    override suspend fun findRun(invocationId: String): Result<DecisionRunDetail?> {
        if (invocationId != denied.invocationId) return Result.success(null)

        return Result.success(
            DecisionRunDetail(
                summary = denied,
                decision = DecisionRunDecision(
                    decisionId = "decision-1",
                    action = "ENTER",
                    provider = "codex",
                    estimatedWinProbability = "0.61",
                    expectedRMultiple = "0.1100000000",
                    roundTripCostR = "0.01",
                    reasonJa = "上昇継続を想定します。",
                    setupTagsJson = "[]",
                    missingDataJaJson = "[]",
                    noTradeConditionsJaJson = "[]",
                    createdAt = Instant.parse("2026-07-10T00:47:30Z"),
                ),
                intent = DecisionRunIntent(
                    intentId = "intent-1",
                    tradePlanId = "plan-1",
                    parentTradePlanId = "parent-plan-1",
                    revisionCount = 2,
                    setupTagsJson = "[\"trend-breakout\"]",
                    side = "BUY",
                    orderType = "LIMIT",
                    sizeBtc = "0.06",
                    priceJpy = "17000000",
                    protectiveStopPriceJpy = "16500000",
                    takeProfitPriceJpy = "18000000",
                    thesisJa = "上昇トレンド継続",
                    invalidationConditionsJaJson = "[]",
                    targetPriceJpy = "18000000",
                    timeStopAt = null,
                ),
                falsification = DecisionRunFalsification(
                    verdict = "APPROVED",
                    provider = "claude",
                    reasonJa = "反証条件を確認しました。",
                    createdAt = Instant.parse("2026-07-10T00:47:32Z"),
                ),
                safetyViolation = DecisionRunSafetyViolation(
                    rule = "EXPECTED_VALUE_GATE",
                    measuredValue = "0.03357778",
                    limitValue = "0.10",
                    messageJa = "期待値が安全床を下回りました。",
                    createdAt = Instant.parse("2026-07-10T00:47:35Z"),
                ),
                orders = emptyList(),
                executions = emptyList(),
                raw = listOf(
                    DecisionRunRawRecord(
                        source = "audit",
                        occurredAt = Instant.parse("2026-07-10T00:47:34Z"),
                        values = mapOf("eventType" to "RUNNER_PHASE_COMPLETED"),
                    ),
                ),
            ),
        )
    }
}

private class SafetyPassedDecisionRunProjectionRepository : DecisionRunProjectionRepository {
    override suspend fun listRuns(
        cursor: DecisionRunCursor?,
        limit: Int,
        outcome: DecisionRunOutcome?,
    ): Result<DecisionRunPage> {
        return Result.success(DecisionRunPage(emptyList(), scanContinuation = null))
    }

    override suspend fun findRun(invocationId: String): Result<DecisionRunDetail?> {
        if (invocationId == "safety-unknown") {
            val detail = safetyPassedDetail(DecisionAction.EXIT)
            return Result.success(
                detail.copy(
                    summary = detail.summary.copy(
                        invocationId = invocationId,
                        action = "REMOVED_ACTION",
                        orderCount = 0,
                        outcome = DecisionRunOutcome.FAILED,
                    ),
                    decision = detail.decision?.copy(action = "REMOVED_ACTION"),
                    orders = emptyList(),
                ),
            )
        }
        val action = DecisionAction.entries.find { candidate -> invocationId == "safety-${candidate.name.lowercase()}" }

        return Result.success(action?.let(::safetyPassedDetail))
    }
}

private fun deniedRunSummary(): DecisionRunSummary {
    return DecisionRunSummary(
        invocationId = "run-new",
        mode = "PAPER",
        symbol = "BTC_JPY",
        triggerKind = "SCHEDULED",
        status = "SUCCEEDED",
        startedAt = Instant.parse("2026-07-10T00:47:27Z"),
        finishedAt = Instant.parse("2026-07-10T00:47:36Z"),
        errorMessage = null,
        action = "ENTER",
        reasonJa = "上昇継続を想定します。",
        falsificationVerdict = "APPROVED",
        safetyRule = "EXPECTED_VALUE_GATE",
        safetyMessageJa = "期待値が安全床を下回りました。",
        finalReason = "preview_order_rejected",
        orderCount = 0,
        executionCount = 0,
        outcome = DecisionRunOutcome.DENIED,
    )
}

private fun safetyPassedDetail(action: DecisionAction): DecisionRunDetail {
    val invocationId = "safety-${action.name.lowercase()}"
    val summary = deniedRunSummary().copy(
        invocationId = invocationId,
        action = action.name,
        reasonJa = "既存 position を更新します。",
        falsificationVerdict = null,
        safetyRule = null,
        safetyMessageJa = null,
        finalReason = null,
        orderCount = 1,
        outcome = DecisionRunOutcome.EXECUTED,
    )

    return DecisionRunDetail(
        summary = summary,
        decision = DecisionRunDecision(
            decisionId = "decision-${action.name.lowercase()}",
            action = action.name,
            provider = "codex",
            estimatedWinProbability = "0.61",
            expectedRMultiple = null,
            roundTripCostR = null,
            reasonJa = "既存 position を更新します。",
            setupTagsJson = "[]",
            missingDataJaJson = "[]",
            noTradeConditionsJaJson = "[]",
            createdAt = Instant.parse("2026-07-10T00:47:30Z"),
        ),
        intent = null,
        falsification = null,
        safetyViolation = null,
        orders = listOf(
            DecisionRunOrder(
                orderId = "order-${action.name.lowercase()}",
                intentId = null,
                positionId = "position-1",
                tradeGroupId = "trade-group-1",
                side = "SELL",
                orderType = "MARKET",
                status = "FILLED",
                sizeBtc = "0.01",
                limitPriceJpy = null,
                reasonJa = null,
                createdAt = Instant.parse("2026-07-10T00:47:34Z"),
            ),
        ),
        executions = emptyList(),
        raw = emptyList(),
    )
}
