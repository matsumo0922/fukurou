package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
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
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationTradeQueryResult
import me.matsumo.fukurou.trading.evaluation.KillCriterionStats
import me.matsumo.fukurou.trading.evaluation.LlmPhaseUsageFact
import me.matsumo.fukurou.trading.market.MarketDataSource
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
    fun evaluationRoutes_returnOkShapes() = testApplication {
        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
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
    }

    @Test
    fun evaluationRoutes_returnBadRequestForInvalidDate() = testApplication {
        application {
            module(
                readinessProbe = { true },
                clock = fixedClock(),
                evaluationRepository = FakeEvaluationRepository,
                evaluationMarketDataSource = FakeEvaluationMarketDataSource,
                tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            )
        }

        val response = client.get("/evaluation/summary?from=not-a-date")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("ISO-8601"))
    }
}

/**
 * route test 用 fake evaluation repository。
 */
private object FakeEvaluationRepository : EvaluationRepository {

    override suspend fun fetchClosedTrades(
        period: EvaluationPeriod,
        limit: Int,
    ): Result<EvaluationTradeQueryResult> {
        return Result.success(
            EvaluationTradeQueryResult(
                trades = listOf(testTrade()),
                truncated = false,
            ),
        )
    }

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

    override suspend fun fetchInitialCashJpy(): Result<BigDecimal> {
        return Result.success(BigDecimal("100000"))
    }

    override suspend fun fetchLlmPhaseUsages(period: EvaluationPeriod): Result<List<LlmPhaseUsageFact>> {
        return Result.success(emptyList())
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

    override suspend fun getOrderbook(
        symbol: TradingSymbol,
        depth: Int,
    ): Result<Orderbook> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getTrades(
        symbol: TradingSymbol,
        limit: Int,
    ): Result<List<RecentTrade>> {
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
        initialProtectiveStopPriceJpy = BigDecimal("9900000"),
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
