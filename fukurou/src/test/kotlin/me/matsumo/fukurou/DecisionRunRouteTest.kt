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
import me.matsumo.fukurou.trading.activity.DecisionRunOutcome
import me.matsumo.fukurou.trading.activity.DecisionRunProjectionRepository
import me.matsumo.fukurou.trading.activity.DecisionRunRawRecord
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyViolation
import me.matsumo.fukurou.trading.activity.DecisionRunSummary
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

        val detailResponse = client.get("/ops/runs/run-new")
        assertEquals(HttpStatusCode.OK, detailResponse.status)
        val detail = Json.decodeFromString<OpsDecisionRunDetailResponse>(detailResponse.body())
        assertEquals(OpsDecisionRunOutcome.DENIED, detail.summary.outcome)
        assertEquals("APPROVED", detail.falsification?.verdict)
        assertEquals("0.1100000000", detail.decision?.expectedRMultiple)
        assertEquals("0.03357778", detail.safetyViolation?.measuredValue)
        assertEquals("0.10", detail.safetyViolation?.limitValue)
        assertEquals(0, detail.orders.size)
        assertEquals(0, detail.executions.size)
        assertTrue(detail.raw.none { raw -> raw.values.keys.any { key -> key.contains("secret", ignoreCase = true) } })
        assertEquals(HttpStatusCode.BadRequest, client.get("/ops/runs?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/ops/runs?before=invalid").status)
    }

    @Test
    fun runsRouteHandlesUnavailableInvalidAndMissingProjectionStates() = testApplication {
        application { module(databaseConfig = null) }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ops/runs").status)
    }
}

private class FakeDecisionRunProjectionRepository : DecisionRunProjectionRepository {
    var lastCursor: DecisionRunCursor? = null

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

    override suspend fun listRuns(cursor: DecisionRunCursor?, limit: Int): Result<List<DecisionRunSummary>> {
        lastCursor = cursor
        return Result.success(if (cursor == null) listOf(denied, interrupted).take(limit) else listOf(interrupted).take(limit))
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
        orderCount = 0,
        executionCount = 0,
        outcome = DecisionRunOutcome.DENIED,
    )
}
