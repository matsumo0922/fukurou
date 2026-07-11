package me.matsumo.fukurou

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.activity.DecisionRunCursor
import me.matsumo.fukurou.trading.activity.DecisionRunDecision
import me.matsumo.fukurou.trading.activity.DecisionRunDetail
import me.matsumo.fukurou.trading.activity.DecisionRunExecution
import me.matsumo.fukurou.trading.activity.DecisionRunFalsification
import me.matsumo.fukurou.trading.activity.DecisionRunFilter
import me.matsumo.fukurou.trading.activity.DecisionRunIntent
import me.matsumo.fukurou.trading.activity.DecisionRunOrder
import me.matsumo.fukurou.trading.activity.DecisionRunOutcome
import me.matsumo.fukurou.trading.activity.DecisionRunPage
import me.matsumo.fukurou.trading.activity.DecisionRunProjectionRepository
import me.matsumo.fukurou.trading.activity.DecisionRunRawRecord
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyViolation
import me.matsumo.fukurou.trading.activity.DecisionRunSummary
import me.matsumo.fukurou.trading.activity.DecisionRunTradeLifecycle
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuote
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecisionRunRouteTest {

    @Test
    fun runsRouteReturnsStableCursorAndNormalizedDeniedDetail() = testApplication {
        val repository = FakeDecisionRunProjectionRepository()
        val latestMarketQuoteStore = LatestMarketQuoteStore().apply {
            update(
                LatestMarketQuote(
                    bidPriceJpy = BigDecimal("16990000"),
                    askPriceJpy = BigDecimal("17000000"),
                    observedAt = Instant.parse("2026-07-10T00:47:30Z"),
                ),
            )
        }
        application {
            module(
                opsDecisionRunProjectionRepository = repository,
                latestMarketQuoteStore = latestMarketQuoteStore,
                clock = Clock.fixed(Instant.parse("2026-07-10T00:48:00Z"), ZoneOffset.UTC),
                databaseConfig = null,
            )
        }

        val firstResponse = client.get("/ops/runs?limit=1")
        assertEquals(HttpStatusCode.OK, firstResponse.status)
        val firstPage = Json.decodeFromString<OpsDecisionRunsResponse>(firstResponse.body())
        assertEquals(listOf("run-new"), firstPage.runs.map { run -> run.invocationId })
        assertNotNull(firstPage.nextBefore)
        val currentQuote = assertNotNull(firstPage.latestMarketQuote)
        assertEquals("16990000", currentQuote.bidPriceJpy)
        assertEquals("17000000", currentQuote.askPriceJpy)
        assertEquals("2026-07-10T00:47:30Z", currentQuote.observedAt)
        assertEquals(false, currentQuote.stale)

        val secondResponse = client.get("/ops/runs?limit=1&before=${firstPage.nextBefore}")
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        assertEquals(Instant.parse("2026-07-10T00:47:27Z"), repository.lastCursor?.startedAt)
        assertEquals("run-new", repository.lastCursor?.invocationId)

        val filteredResponse = client.get("/ops/runs?filter=ACTION_REQUIRED")
        assertEquals(HttpStatusCode.OK, filteredResponse.status)
        val filteredPage = Json.decodeFromString<OpsDecisionRunsResponse>(filteredResponse.body())
        assertEquals(listOf("run-old"), filteredPage.runs.map { run -> run.invocationId })
        assertEquals(DecisionRunFilter.ACTION_REQUIRED, repository.lastFilter)

        val cappedResponse = client.get("/ops/runs?filter=WAITING")
        assertEquals(HttpStatusCode.OK, cappedResponse.status)
        val cappedPage = Json.decodeFromString<OpsDecisionRunsResponse>(cappedResponse.body())
        assertTrue(cappedPage.runs.isEmpty())
        assertNotNull(cappedPage.nextBefore)

        val continuedResponse = client.get("/ops/runs?filter=WAITING&before=${cappedPage.nextBefore}")
        assertEquals(HttpStatusCode.OK, continuedResponse.status)
        val continuedPage = Json.decodeFromString<OpsDecisionRunsResponse>(continuedResponse.body())
        assertTrue(continuedPage.runs.isEmpty())
        assertEquals(null, continuedPage.nextBefore)
        assertEquals("scan-boundary", repository.lastCursor?.invocationId)

        val detailResponse = client.get("/ops/runs/run-new")
        assertEquals(HttpStatusCode.OK, detailResponse.status)
        val detail = Json.decodeFromString<OpsDecisionRunDetailResponse>(detailResponse.body())
        assertEquals(OpsDecisionRunOutcome.NO_ENTRY, detail.summary.outcome)
        assertEquals("APPROVED", detail.falsification?.verdict)
        assertEquals("0.1100000000", detail.decision?.expectedRMultiple)
        assertEquals("0.03357778", detail.safetyViolation?.measuredValue)
        assertEquals("0.10", detail.safetyViolation?.limitValue)
        assertEquals("preview_order_rejected", detail.summary.finalReason)
        assertEquals("parent-plan-1", detail.intent?.parentTradePlanId)
        assertEquals(2, detail.intent?.revisionCount)
        assertEquals("[\"trend-breakout\"]", detail.intent?.setupTagsJson)
        assertEquals(0, detail.orders.size)
        val directExecution = detail.executions.single()
        assertEquals("execution-direct", directExecution.executionId)
        assertEquals("order-direct", directExecution.orderId)
        assertEquals("SELL", directExecution.side)
        assertEquals("MARKET", directExecution.orderType)
        assertEquals("DIRECT_RUN", directExecution.kind)
        assertEquals("10100000", directExecution.priceJpy)
        assertEquals("0.01", directExecution.sizeBtc)
        assertEquals("10", directExecution.feeJpy)
        assertEquals("TAKER", directExecution.liquidity)
        assertEquals("1000", directExecution.realizedPnlJpy)
        assertEquals("2026-07-10T00:47:37Z", directExecution.executedAt)
        assertEquals("position-1", detail.tradeLifecycles.single().positionId)
        assertEquals("CLOSED", detail.tradeLifecycles.single().status)
        assertEquals("STOP", detail.tradeLifecycles.single().executions.single().kind)
        assertEquals("TAKER", detail.tradeLifecycles.single().executions.single().liquidity)
        assertTrue(detail.raw.none { raw -> raw.values.keys.any { key -> key.contains("secret", ignoreCase = true) } })
        assertEquals(HttpStatusCode.BadRequest, client.get("/ops/runs?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/ops/runs?before=invalid").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/ops/runs?filter=UNKNOWN").status)
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
    var lastFilter: DecisionRunFilter? = null

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
        outcome = DecisionRunOutcome.FAILED,
    )

    override suspend fun listRuns(
        cursor: DecisionRunCursor?,
        limit: Int,
        filter: DecisionRunFilter?,
    ): Result<DecisionRunPage> {
        lastCursor = cursor
        lastFilter = filter
        if (filter == DecisionRunFilter.WAITING) {
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
                runs = runs.filter { run ->
                    filter == null || filter == DecisionRunFilter.ACTION_REQUIRED && run.outcome == DecisionRunOutcome.FAILED
                }.take(limit),
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
                executions = listOf(
                    DecisionRunExecution(
                        executionId = "execution-direct",
                        orderId = "order-direct",
                        positionId = "position-direct",
                        side = "SELL",
                        priceJpy = "10100000",
                        sizeBtc = "0.01",
                        feeJpy = "10",
                        realizedPnlJpy = "1000",
                        liquidity = "TAKER",
                        orderType = "MARKET",
                        kind = "DIRECT_RUN",
                        executedAt = Instant.parse("2026-07-10T00:47:37Z"),
                    ),
                ),
                tradeLifecycles = listOf(
                    DecisionRunTradeLifecycle(
                        positionId = "position-1",
                        status = "CLOSED",
                        executions = listOf(
                            DecisionRunExecution(
                                executionId = "execution-stop",
                                orderId = "order-stop",
                                positionId = "position-1",
                                side = "SELL",
                                priceJpy = "9800000",
                                sizeBtc = "0.01",
                                feeJpy = "10",
                                realizedPnlJpy = "-1000",
                                liquidity = "TAKER",
                                orderType = "STOP",
                                kind = "STOP",
                                executedAt = Instant.parse("2026-07-10T00:47:36Z"),
                            ),
                        ),
                    ),
                ),
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
        filter: DecisionRunFilter?,
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
        outcome = DecisionRunOutcome.NO_ENTRY,
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
        outcome = DecisionRunOutcome.FILLED,
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
        tradeLifecycles = emptyList(),
        raw = emptyList(),
    )
}
