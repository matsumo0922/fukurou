package me.matsumo.fukurou.trading.evaluation

import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * EvaluationMath の数式 contract を検証するテスト。
 */
class EvaluationMathTest {

    @Test
    fun summarizeTrades_calculatesPfWinRateAndExpectedR() {
        val stats = EvaluationMath.summarizeTrades(
            listOf(
                trade("20", setupTags = listOf("breakout")),
                trade("-10", setupTags = listOf("breakout")),
                trade("0", setupTags = listOf("breakout")),
            ),
        )

        assertEquals(3, stats.tradeCount)
        assertEquals("10.0000000000", stats.totalPnlJpy.toPlainString())
        assertEquals("2.0000000000", stats.profitFactor?.toPlainString())
        assertEquals("0.3333333333", stats.winRate?.toPlainString())
        assertEquals("0.3333333333", stats.expectedR?.toPlainString())
    }

    @Test
    fun summarizeTrades_returnsNullProfitFactorWhenNoLosingTrade() {
        val stats = EvaluationMath.summarizeTrades(
            listOf(
                trade("10"),
                trade("0"),
            ),
        )

        assertNull(stats.profitFactor)
        assertEquals("0.5000000000", stats.winRate?.toPlainString())
    }

    @Test
    fun summarizeTrades_returnsEmptyStatsForNoTrades() {
        val stats = EvaluationMath.summarizeTrades(emptyList())

        assertEquals(0, stats.tradeCount)
        assertEquals("0.0000000000", stats.totalPnlJpy.toPlainString())
        assertNull(stats.profitFactor)
        assertNull(stats.winRate)
        assertNull(stats.expectedR)
    }

    @Test
    fun evaluateTrade_calculatesMaeMfeAndTracksUnavailableCounts() {
        val normal = trade(
            pnlJpy = "20",
            highestPriceSinceEntryJpy = BigDecimal("130"),
            lowestPriceSinceEntryJpy = BigDecimal("95"),
        )
        val noLowest = trade(
            pnlJpy = "20",
            highestPriceSinceEntryJpy = BigDecimal("130"),
            lowestPriceSinceEntryJpy = null,
        )
        val invalidRisk = trade(
            pnlJpy = "20",
            entryWeightedProtectiveStopPriceJpy = BigDecimal("100"),
            highestPriceSinceEntryJpy = BigDecimal("130"),
            lowestPriceSinceEntryJpy = BigDecimal("95"),
        )
        val normalTrade = EvaluationMath.evaluateTrade(normal)
        val stats = EvaluationMath.summarizeTrades(listOf(normal, noLowest, invalidRisk))

        assertEquals("2.0000000000", normalTrade.realizedR?.toPlainString())
        assertEquals("0.5000000000", normalTrade.maeR?.toPlainString())
        assertEquals("3.0000000000", normalTrade.mfeR?.toPlainString())
        assertEquals(1, stats.rUnavailableCount)
        assertEquals(2, stats.maeUnavailableCount)
        assertEquals(1, stats.mfeUnavailableCount)
    }

    @Test
    fun evaluateTrade_clampsNegativeMaeAndMfeToZero() {
        val evaluatedTrade = EvaluationMath.evaluateTrade(
            trade(
                pnlJpy = "20",
                highestPriceSinceEntryJpy = BigDecimal("95"),
                lowestPriceSinceEntryJpy = BigDecimal("105"),
            ),
        )

        assertEquals("0.0000000000", evaluatedTrade.maeR?.toPlainString())
        assertEquals("0.0000000000", evaluatedTrade.mfeR?.toPlainString())
    }

    @Test
    fun calibrationBinsHandlesEdgesAndDuplicateSetupTags() {
        val calibration = EvaluationMath.calibrationBySetup(
            listOf(
                trade("10", probability = BigDecimal("0.0"), setupTags = listOf("breakout", "trend")),
                trade("-10", probability = BigDecimal("0.95"), setupTags = listOf("breakout")),
                trade("10", probability = BigDecimal("1.0"), setupTags = listOf("breakout")),
            ),
        )
        val breakout = calibration.single { group -> group.groupKey == "breakout" }
        val zeroBin = breakout.bins[0]
        val topBin = breakout.bins[9]
        val trend = calibration.single { group -> group.groupKey == "trend" }

        assertEquals(1, zeroBin.tradeCount)
        assertEquals("0.0000000000", zeroBin.averageEstimatedProbability?.toPlainString())
        assertEquals(2, topBin.tradeCount)
        assertEquals("0.5000000000", topBin.realizedWinRate?.toPlainString())
        assertEquals(1, trend.bins[0].tradeCount)
    }

    @Test
    fun calibrationByProviderSeparatesProviderGroups() {
        val calibration = EvaluationMath.calibrationByProvider(
            listOf(
                trade("10", probability = BigDecimal("0.20"), llmProvider = "claude"),
                trade("-10", probability = BigDecimal("0.20"), llmProvider = "codex"),
            ),
        )
        val claude = calibration.single { group -> group.groupKey == "claude" }
        val codex = calibration.single { group -> group.groupKey == "codex" }

        assertEquals(1, claude.bins[2].tradeCount)
        assertEquals("1.0000000000", claude.bins[2].realizedWinRate?.toPlainString())
        assertEquals(1, codex.bins[2].tradeCount)
        assertEquals("0.0000000000", codex.bins[2].realizedWinRate?.toPlainString())
    }

    @Test
    fun benchmarkCalculatesBuyHoldNoTradeAndBotSeries() {
        val result = EvaluationMath.benchmark(
            BenchmarkCalculationRequest(
                candles = listOf(
                    dailyCandle("2026-07-01", "100"),
                    dailyCandle("2026-07-02", "110"),
                    dailyCandle("2026-07-03", "120"),
                ),
                dailyPnlFacts = listOf(
                    DailyTradePnlFact(
                        closedAt = Instant.parse("2026-07-02T12:00:00Z"),
                        pnlJpy = BigDecimal("10"),
                    ),
                ),
                baselineEquityJpy = BigDecimal("1000"),
                fromDate = LocalDate.parse("2026-07-01"),
                toDateInclusive = LocalDate.parse("2026-07-03"),
                zoneId = ZoneId.of("Asia/Tokyo"),
            ),
        )

        assertEquals(3, result.points.size)
        assertEquals("1000.0000000000", result.points[0].botEquityJpy.toPlainString())
        assertEquals("1010.0000000000", result.points[1].botEquityJpy.toPlainString())
        assertEquals("1200.0000000000", result.points.last().buyAndHoldEquityJpy.toPlainString())
        assertEquals("1000.0000000000", result.points.last().noTradeEquityJpy.toPlainString())
        assertEquals("1010.0000000000", result.points.last().botEquityJpy.toPlainString())
        assertEquals("0.2000000000", result.buyAndHoldReturn?.toPlainString())
        assertEquals("0.0000000000", result.noTradeReturn?.toPlainString())
        assertEquals("0.0100000000", result.botReturn?.toPlainString())
    }

    @Test
    fun classifyMarketRegimesProducesTrendVolatilityAndUnknown() {
        val candles = (1..25).map { day ->
            val close = BigDecimal("100").add(BigDecimal(day))
            val range = if (day <= 14) BigDecimal("2") else BigDecimal("8")

            dailyCandle(
                date = "2026-07-${day.toString().padStart(2, '0')}",
                close = close.toPlainString(),
                high = close.add(range).toPlainString(),
                low = close.subtract(range).toPlainString(),
            )
        }
        val labels = EvaluationMath.classifyMarketRegimes(candles, ZoneId.of("Asia/Tokyo"))

        assertEquals(TrendRegime.UNKNOWN, labels.first().trend)
        assertEquals(VolatilityRegime.UNKNOWN, labels.first().volatility)
        assertEquals(TrendRegime.TREND_UP, labels.last().trend)
        assertEquals(VolatilityRegime.HIGH_VOL, labels.last().volatility)
    }

    @Test
    fun classifyMarketRegimesProducesTrendDownRangeAndLowVolatility() {
        val downTrendCandles = (1..25).map { day ->
            val close = BigDecimal("200").subtract(BigDecimal(day))
            val range = if (day <= 14) BigDecimal("8") else BigDecimal("2")

            dailyCandle(
                date = "2026-08-${day.toString().padStart(2, '0')}",
                close = close.toPlainString(),
                high = close.add(range).toPlainString(),
                low = close.subtract(range).toPlainString(),
            )
        }
        val rangeCandles = (1..25).map { day ->
            dailyCandle(
                date = "2026-09-${day.toString().padStart(2, '0')}",
                close = "100",
                high = "102",
                low = "98",
            )
        }
        val downLabels = EvaluationMath.classifyMarketRegimes(downTrendCandles, ZoneId.of("Asia/Tokyo"))
        val rangeLabels = EvaluationMath.classifyMarketRegimes(rangeCandles, ZoneId.of("Asia/Tokyo"))

        assertEquals(TrendRegime.TREND_DOWN, downLabels.last().trend)
        assertEquals(VolatilityRegime.LOW_VOL, downLabels.last().volatility)
        assertEquals(TrendRegime.RANGE, rangeLabels.last().trend)
    }

    @Test
    fun summarizeByMarketRegimeMergesSameRegimeAcrossEntryDates() {
        val stats = EvaluationMath.summarizeByMarketRegime(
            trades = listOf(
                trade(
                    pnlJpy = "10",
                    openedAt = Instant.parse("2026-07-01T00:00:00Z"),
                ),
                trade(
                    pnlJpy = "20",
                    openedAt = Instant.parse("2026-07-02T00:00:00Z"),
                ),
            ),
            regimes = listOf(
                MarketRegimeLabel(
                    date = LocalDate.parse("2026-07-01"),
                    trend = TrendRegime.TREND_UP,
                    volatility = VolatilityRegime.HIGH_VOL,
                ),
                MarketRegimeLabel(
                    date = LocalDate.parse("2026-07-02"),
                    trend = TrendRegime.TREND_UP,
                    volatility = VolatilityRegime.HIGH_VOL,
                ),
            ),
            zoneId = ZoneId.of("UTC"),
        )
        val bucket = stats.single()

        assertEquals(TrendRegime.TREND_UP, bucket.trend)
        assertEquals(VolatilityRegime.HIGH_VOL, bucket.volatility)
        assertEquals(2, bucket.stats.tradeCount)
        assertEquals("30.0000000000", bucket.stats.totalPnlJpy.toPlainString())
    }

    @Test
    fun summarizeLlmCostsCountsOnlyInvocationPhases() {
        val stats = EvaluationMath.summarizeLlmCosts(
            listOf(
                llmUsageFact(phase = "preflight", provider = null, usage = null),
                llmUsageFact(
                    phase = "proposer",
                    provider = "claude",
                    usage = LlmUsageDetails(
                        totalCostUsd = BigDecimal("0.25"),
                        numTurns = 1,
                        durationMs = 1000,
                        usage = null,
                        modelUsages = listOf(
                            LlmModelUsage(
                                model = "claude-sonnet-5",
                                usage = LlmTokenUsage(
                                    inputTokens = 10,
                                    outputTokens = 20,
                                    cacheCreationInputTokens = null,
                                    cacheReadInputTokens = null,
                                ),
                            ),
                        ),
                    ),
                ),
                llmUsageFact(phase = "falsifier", provider = "codex", usage = null),
                llmUsageFact(
                    phase = "reflection",
                    provider = "claude",
                    usage = LlmUsageDetails(
                        totalCostUsd = BigDecimal("0.05"),
                        numTurns = 1,
                        durationMs = 500,
                        usage = null,
                        modelUsages = emptyList(),
                    ),
                ),
            ),
        )

        assertEquals(3, stats.phaseCount)
        assertEquals(1, stats.missingUsagePhaseCount)
        assertEquals("0.3000000000", stats.totalCostUsd.toPlainString())
        assertEquals(listOf("claude", "codex"), stats.byProvider.map { provider -> provider.provider })
        assertEquals(10L, stats.byModel.single().inputTokens)
    }
}

private fun trade(
    pnlJpy: String,
    openedAt: Instant = Instant.parse("2026-07-01T00:00:00Z"),
    probability: BigDecimal = BigDecimal("0.50"),
    setupTags: List<String> = listOf("setup"),
    llmProvider: String? = "claude",
    entryWeightedProtectiveStopPriceJpy: BigDecimal? = BigDecimal("90"),
    highestPriceSinceEntryJpy: BigDecimal = BigDecimal("120"),
    lowestPriceSinceEntryJpy: BigDecimal? = BigDecimal("90"),
): ClosedTradeFact {
    return ClosedTradeFact(
        positionId = UUID.randomUUID(),
        openedAt = openedAt,
        closedAt = Instant.parse("2026-07-02T00:00:00Z"),
        sizeBtc = BigDecimal.ONE,
        averageEntryPriceJpy = BigDecimal("100"),
        entryWeightedProtectiveStopPriceJpy = entryWeightedProtectiveStopPriceJpy,
        highestPriceSinceEntryJpy = highestPriceSinceEntryJpy,
        lowestPriceSinceEntryJpy = lowestPriceSinceEntryJpy,
        tradePnlJpy = BigDecimal(pnlJpy),
        estimatedWinProbability = probability,
        setupTags = setupTags,
        llmProvider = llmProvider,
    )
}

private fun llmUsageFact(
    phase: String?,
    provider: String?,
    usage: LlmUsageDetails?,
): LlmPhaseUsageFact {
    return LlmPhaseUsageFact(
        decisionRunId = "run-1",
        provider = provider,
        phase = phase,
        occurredAt = Instant.parse("2026-07-02T00:00:00Z"),
        usage = usage,
    )
}

private fun dailyCandle(
    date: String,
    close: String,
    high: String = close,
    low: String = close,
): Candle {
    return Candle(
        symbol = "BTC",
        interval = CandleInterval.ONE_DAY,
        openTime = "${date}T00:00:00Z",
        open = close,
        high = high,
        low = low,
        close = close,
        volume = "1.0",
    )
}
