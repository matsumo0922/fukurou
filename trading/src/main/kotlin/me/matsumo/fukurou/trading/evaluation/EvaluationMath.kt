package me.matsumo.fukurou.trading.evaluation

import me.matsumo.fukurou.trading.domain.Candle
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * 評価系の DB 非依存な数値計算。
 */
object EvaluationMath {

    /**
     * closed trade fact へ R 系指標を付与する。
     */
    fun evaluateTrade(fact: ClosedTradeFact): EvaluatedTrade {
        val riskWidth = fact.entryWeightedProtectiveStopPriceJpy
            ?.let { stopPrice -> fact.averageEntryPriceJpy.subtract(stopPrice) }
            ?.takeIf { width -> width > BigDecimal.ZERO }
        val riskAmount = riskWidth
            ?.multiply(fact.sizeBtc)
            ?.takeIf { amount -> amount > BigDecimal.ZERO }
        val realizedR = riskAmount?.let { amount -> fact.tradePnlJpy.divideEvaluation(amount) }
        val mfeR = riskWidth?.let { width ->
            fact.highestPriceSinceEntryJpy
                .subtract(fact.averageEntryPriceJpy)
                .maxZero()
                .divideEvaluation(width)
        }
        val maeR = riskWidth?.let { width ->
            fact.lowestPriceSinceEntryJpy
                ?.let { lowPrice -> fact.averageEntryPriceJpy.subtract(lowPrice).maxZero() }
                ?.divideEvaluation(width)
        }

        return EvaluatedTrade(
            fact = fact,
            initialRiskPriceWidthJpy = riskWidth,
            realizedR = realizedR,
            maeR = maeR,
            mfeR = mfeR,
        )
    }

    /**
     * trade 一覧を成績指標へ集計する。
     */
    fun summarizeTrades(trades: List<ClosedTradeFact>): TradePerformanceStats {
        val evaluatedTrades = trades.map { fact -> evaluateTrade(fact) }

        return summarizeEvaluatedTrades(evaluatedTrades)
    }

    /**
     * setup tag 別の成績を返す。複数 tag の trade は各 tag に重複計上する。
     */
    fun summarizeBySetup(trades: List<ClosedTradeFact>): List<SetupPerformance> {
        return trades
            .flatMap { fact -> fact.setupTags.ifEmpty { listOf(UNCLASSIFIED_SETUP_TAG) }.map { tag -> tag to fact } }
            .groupBy(keySelector = { entry -> entry.first }, valueTransform = { entry -> entry.second })
            .map { (setupTag, setupTrades) ->
                SetupPerformance(
                    setupTag = setupTag,
                    stats = summarizeTrades(setupTrades),
                )
            }
            .sortedBy { performance -> performance.setupTag }
    }

    /**
     * setup tag 別の較正 curve を返す。
     */
    fun calibrationBySetup(trades: List<ClosedTradeFact>): List<CalibrationGroupStats> {
        val groupedTrades = trades
            .filter { fact -> fact.estimatedWinProbability != null }
            .flatMap { fact -> fact.setupTags.ifEmpty { listOf(UNCLASSIFIED_SETUP_TAG) }.map { tag -> tag to fact } }
            .groupBy(keySelector = { entry -> entry.first }, valueTransform = { entry -> entry.second })

        return groupedTrades.toCalibrationGroups()
    }

    /**
     * LLM provider 別の較正 curve を返す。
     */
    fun calibrationByProvider(trades: List<ClosedTradeFact>): List<CalibrationGroupStats> {
        val groupedTrades = trades
            .filter { fact -> fact.estimatedWinProbability != null }
            .groupBy { fact -> fact.llmProvider ?: UNKNOWN_PROVIDER }

        return groupedTrades.toCalibrationGroups()
    }

    /**
     * decision 起動数に対する action rate を計算する。
     */
    fun decisionRunRates(decisionRunCount: Int, actionCounts: List<DecisionActionCount>): DecisionRunRateStats {
        val entryCount = actionCounts.firstCount("ENTER")
        val noTradeCount = actionCounts.firstCount("NO_TRADE")

        return DecisionRunRateStats(
            decisionRunCount = decisionRunCount,
            actionCounts = actionCounts.sortedBy { actionCount -> actionCount.action },
            entryRate = rateOrNull(entryCount, decisionRunCount),
            noTradeRate = rateOrNull(noTradeCount, decisionRunCount),
        )
    }

    /**
     * 日足と realized PnL から benchmark 系列を計算する。
     */
    fun benchmark(request: BenchmarkCalculationRequest): BenchmarkResult {
        val candlePoints = request.candles
            .mapNotNull { candle -> candle.toDailyCloseOrNull(request.zoneId) }
            .filter { point -> !point.date.isBefore(request.fromDate) && !point.date.isAfter(request.toDateInclusive) }
            .sortedBy { point -> point.date }
        val firstClose = candlePoints.firstOrNull()?.closeJpy

        if (firstClose == null || firstClose <= BigDecimal.ZERO) {
            return BenchmarkResult(
                points = emptyList(),
                buyAndHoldReturn = null,
                noTradeReturn = null,
                botReturn = null,
            )
        }

        val pnlByDate = request.dailyPnlFacts
            .groupBy { fact -> fact.closedAt.atZone(request.zoneId).toLocalDate() }
            .mapValues { entry -> entry.value.sumOfBigDecimal { fact -> fact.pnlJpy } }
        val buyAndHoldBtc = request.baselineEquityJpy.divideEvaluation(firstClose)
        var cumulativeBotPnl = BigDecimal.ZERO
        val points = candlePoints.map { point ->
            cumulativeBotPnl = cumulativeBotPnl.add(pnlByDate[point.date] ?: BigDecimal.ZERO)

            BenchmarkPoint(
                date = point.date,
                buyAndHoldEquityJpy = buyAndHoldBtc.multiply(point.closeJpy).evaluationScale(),
                noTradeEquityJpy = request.baselineEquityJpy.evaluationScale(),
                botEquityJpy = request.baselineEquityJpy.add(cumulativeBotPnl).evaluationScale(),
            )
        }

        return BenchmarkResult(
            points = points,
            buyAndHoldReturn = points.returnOf { point -> point.buyAndHoldEquityJpy },
            noTradeReturn = points.returnOf { point -> point.noTradeEquityJpy },
            botReturn = points.returnOf { point -> point.botEquityJpy },
        )
    }

    /**
     * 日足から相場局面 label を計算する。
     */
    fun classifyMarketRegimes(candles: List<Candle>, zoneId: ZoneId): List<MarketRegimeLabel> {
        val points = candles
            .mapNotNull { candle -> candle.toDailyOhlcOrNull(zoneId) }
            .sortedBy { point -> point.date }
        val rollingRangeAverages = points.mapIndexed { index, point ->
            point.date to averageRangeOrNull(points, index, VOLATILITY_WINDOW)
        }
        val medianRange = rollingRangeAverages
            .mapNotNull { entry -> entry.second }
            .medianOrNull()

        return points.mapIndexed { index, point ->
            val trend = trendRegime(points, index)
            val volatility = volatilityRegime(
                rollingRangeAverage = rollingRangeAverages[index].second,
                medianRange = medianRange,
            )

            MarketRegimeLabel(
                date = point.date,
                trend = trend,
                volatility = volatility,
            )
        }
    }

    /**
     * trade を entry 日の相場局面へ bucket して成績を返す。
     */
    fun summarizeByMarketRegime(
        trades: List<ClosedTradeFact>,
        regimes: List<MarketRegimeLabel>,
        zoneId: ZoneId,
    ): List<MarketRegimePerformance> {
        val regimeByDate = regimes.associateBy { regime -> regime.date }
        val groupedTrades = trades.groupBy { fact ->
            fact.marketRegimeBucketKey(regimeByDate, zoneId)
        }

        return groupedTrades
            .map { (bucketKey, bucketTrades) ->
                MarketRegimePerformance(
                    trend = bucketKey.trend,
                    volatility = bucketKey.volatility,
                    stats = summarizeTrades(bucketTrades),
                )
            }
            .sortedWith(compareBy({ performance -> performance.trend.name }, { performance -> performance.volatility.name }))
    }

    /**
     * runner phase usage fact を cost 集計へ変換する。
     */
    fun summarizeLlmCosts(facts: List<LlmPhaseUsageFact>): LlmCostStats {
        val llmFacts = facts.filter { fact -> fact.isLlmInvocationPhase() }
        val missingUsageCount = llmFacts.count { fact -> fact.usage == null }
        val totalCost = llmFacts.sumOfBigDecimal { fact -> fact.usage?.totalCostUsd ?: BigDecimal.ZERO }
        val byProvider = llmFacts
            .groupBy { fact -> fact.provider ?: UNKNOWN_PROVIDER }
            .map { (provider, providerFacts) ->
                LlmProviderCostStats(
                    provider = provider,
                    totalCostUsd = providerFacts.sumOfBigDecimal { fact -> fact.usage?.totalCostUsd ?: BigDecimal.ZERO },
                    phaseCount = providerFacts.size,
                    missingUsagePhaseCount = providerFacts.count { fact -> fact.usage == null },
                )
            }
            .sortedBy { stats -> stats.provider }
        val byModel = llmFacts
            .flatMap { fact -> fact.usage?.modelUsages.orEmpty() }
            .groupBy { usage -> usage.model }
            .map { (model, modelUsages) -> modelUsages.toModelTokenStats(model) }
            .sortedBy { stats -> stats.model }

        return LlmCostStats(
            phaseCount = llmFacts.size,
            missingUsagePhaseCount = missingUsageCount,
            totalCostUsd = totalCost.evaluationScale(),
            byProvider = byProvider,
            byModel = byModel,
        )
    }
}

/**
 * benchmark 系列の計算入力。
 *
 * @param candles 日足 candle
 * @param dailyPnlFacts 日次 realized PnL fact
 * @param baselineEquityJpy 期間開始時点の基準 equity
 * @param fromDate 集計開始日
 * @param toDateInclusive 集計終了日
 * @param zoneId 日付境界に使う timezone
 */
data class BenchmarkCalculationRequest(
    val candles: List<Candle>,
    val dailyPnlFacts: List<DailyTradePnlFact>,
    val baselineEquityJpy: BigDecimal,
    val fromDate: LocalDate,
    val toDateInclusive: LocalDate,
    val zoneId: ZoneId,
)

/**
 * 日次 close price point。
 *
 * @param date JST 日付
 * @param closeJpy close 価格
 */
private data class DailyClosePoint(
    val date: LocalDate,
    val closeJpy: BigDecimal,
)

/**
 * 日次 OHLC point。
 *
 * @param date JST 日付
 * @param highJpy high 価格
 * @param lowJpy low 価格
 * @param closeJpy close 価格
 */
private data class DailyOhlcPoint(
    val date: LocalDate,
    val highJpy: BigDecimal,
    val lowJpy: BigDecimal,
    val closeJpy: BigDecimal,
)

/**
 * 相場局面成績の集計 key。
 *
 * @param trend トレンド区分
 * @param volatility ボラティリティ区分
 */
private data class MarketRegimeBucketKey(
    val trend: TrendRegime,
    val volatility: VolatilityRegime,
)

private fun summarizeEvaluatedTrades(trades: List<EvaluatedTrade>): TradePerformanceStats {
    val totalPnl = trades.sumOfBigDecimal { trade -> trade.fact.tradePnlJpy }
    val winningPnl = trades
        .filter { trade -> trade.fact.tradePnlJpy > BigDecimal.ZERO }
        .sumOfBigDecimal { trade -> trade.fact.tradePnlJpy }
    val losingPnl = trades
        .filter { trade -> trade.fact.tradePnlJpy < BigDecimal.ZERO }
        .sumOfBigDecimal { trade -> trade.fact.tradePnlJpy }
    val profitFactor = if (losingPnl < BigDecimal.ZERO) {
        winningPnl.divideEvaluation(losingPnl.abs())
    } else {
        null
    }
    val winCount = trades.count { trade -> trade.fact.tradePnlJpy > BigDecimal.ZERO }
    val realizedValues = trades.mapNotNull { trade -> trade.realizedR }
    val maeValues = trades.mapNotNull { trade -> trade.maeR }
    val mfeValues = trades.mapNotNull { trade -> trade.mfeR }

    return TradePerformanceStats(
        tradeCount = trades.size,
        totalPnlJpy = totalPnl.evaluationScale(),
        profitFactor = profitFactor,
        winRate = rateOrNull(winCount, trades.size),
        expectedR = averageOrNull(realizedValues),
        averageMaeR = averageOrNull(maeValues),
        averageMfeR = averageOrNull(mfeValues),
        rUnavailableCount = trades.count { trade -> trade.realizedR == null },
        maeUnavailableCount = trades.count { trade -> trade.maeR == null },
        mfeUnavailableCount = trades.count { trade -> trade.mfeR == null },
    )
}

private fun Map<String, List<ClosedTradeFact>>.toCalibrationGroups(): List<CalibrationGroupStats> {
    return map { (groupKey, trades) ->
        CalibrationGroupStats(
            groupKey = groupKey,
            bins = calibrationBins(trades),
        )
    }.sortedBy { group -> group.groupKey }
}

private fun calibrationBins(trades: List<ClosedTradeFact>): List<CalibrationBinStats> {
    val groupedTrades = trades.groupBy { fact -> probabilityBinIndex(requireNotNull(fact.estimatedWinProbability)) }

    return (0 until CALIBRATION_BIN_COUNT).map { binIndex ->
        val binTrades = groupedTrades[binIndex].orEmpty()
        val winCount = binTrades.count { fact -> fact.tradePnlJpy > BigDecimal.ZERO }

        CalibrationBinStats(
            binIndex = binIndex,
            lowerBoundInclusive = binLowerBound(binIndex),
            upperBound = binUpperBound(binIndex),
            tradeCount = binTrades.size,
            averageEstimatedProbability = averageOrNull(binTrades.mapNotNull { fact -> fact.estimatedWinProbability }),
            realizedWinRate = rateOrNull(winCount, binTrades.size),
        )
    }
}

private fun probabilityBinIndex(probability: BigDecimal): Int {
    val rawIndex = probability
        .multiply(BigDecimal.TEN)
        .setScale(0, RoundingMode.FLOOR)
        .toInt()

    return rawIndex.coerceIn(0, CALIBRATION_BIN_COUNT - 1)
}

private fun binLowerBound(binIndex: Int): BigDecimal {
    return BigDecimal(binIndex).divide(BigDecimal.TEN, EVALUATION_SCALE, RoundingMode.HALF_UP)
}

private fun binUpperBound(binIndex: Int): BigDecimal {
    if (binIndex == CALIBRATION_BIN_COUNT - 1) {
        return BigDecimal.ONE
    }

    return binLowerBound(binIndex + 1)
}

private fun List<DecisionActionCount>.firstCount(action: String): Int {
    return firstOrNull { actionCount -> actionCount.action == action }?.count ?: 0
}

private fun Candle.toDailyCloseOrNull(zoneId: ZoneId): DailyClosePoint? {
    val date = openTime.toLocalDateOrNull(zoneId) ?: return null
    val closeJpy = close.toBigDecimalOrNull() ?: return null

    return DailyClosePoint(
        date = date,
        closeJpy = closeJpy,
    )
}

private fun Candle.toDailyOhlcOrNull(zoneId: ZoneId): DailyOhlcPoint? {
    val date = openTime.toLocalDateOrNull(zoneId) ?: return null
    val highJpy = high.toBigDecimalOrNull() ?: return null
    val lowJpy = low.toBigDecimalOrNull() ?: return null
    val closeJpy = close.toBigDecimalOrNull() ?: return null

    return DailyOhlcPoint(
        date = date,
        highJpy = highJpy,
        lowJpy = lowJpy,
        closeJpy = closeJpy,
    )
}

private fun ClosedTradeFact.marketRegimeBucketKey(
    regimeByDate: Map<LocalDate, MarketRegimeLabel>,
    zoneId: ZoneId,
): MarketRegimeBucketKey {
    val entryDate = openedAt.atZone(zoneId).toLocalDate()
    val label = regimeByDate[entryDate]

    return MarketRegimeBucketKey(
        trend = label?.trend ?: TrendRegime.UNKNOWN,
        volatility = label?.volatility ?: VolatilityRegime.UNKNOWN,
    )
}

private fun String.toLocalDateOrNull(zoneId: ZoneId): LocalDate? {
    val parsedDate = runCatching {
        java.time.Instant.parse(this)
            .atZone(zoneId)
            .toLocalDate()
    }

    return parsedDate.getOrNull()
}

private fun trendRegime(points: List<DailyOhlcPoint>, currentIndex: Int): TrendRegime {
    val sma5 = averageCloseOrNull(points, currentIndex, SHORT_SMA_WINDOW) ?: return TrendRegime.UNKNOWN
    val sma20 = averageCloseOrNull(points, currentIndex, LONG_SMA_WINDOW) ?: return TrendRegime.UNKNOWN
    val close = points[currentIndex].closeJpy
    val diff = sma5.subtract(sma20)
    val rangeThreshold = close.multiply(RANGE_THRESHOLD_RATIO)
    val range = diff.abs() < rangeThreshold

    return when {
        range -> TrendRegime.RANGE
        diff > BigDecimal.ZERO -> TrendRegime.TREND_UP
        else -> TrendRegime.TREND_DOWN
    }
}

private fun volatilityRegime(rollingRangeAverage: BigDecimal?, medianRange: BigDecimal?): VolatilityRegime {
    val currentAverage = rollingRangeAverage ?: return VolatilityRegime.UNKNOWN
    val median = medianRange ?: return VolatilityRegime.UNKNOWN

    return if (currentAverage >= median) {
        VolatilityRegime.HIGH_VOL
    } else {
        VolatilityRegime.LOW_VOL
    }
}

private fun averageCloseOrNull(
    points: List<DailyOhlcPoint>,
    currentIndex: Int,
    window: Int,
): BigDecimal? {
    val startIndex = currentIndex - window + 1

    if (startIndex < 0) {
        return null
    }

    return averageOrNull(points.subList(startIndex, currentIndex + 1).map { point -> point.closeJpy })
}

private fun averageRangeOrNull(
    points: List<DailyOhlcPoint>,
    currentIndex: Int,
    window: Int,
): BigDecimal? {
    val startIndex = currentIndex - window + 1

    if (startIndex < 0) {
        return null
    }

    return averageOrNull(
        points.subList(startIndex, currentIndex + 1)
            .map { point -> point.highJpy.subtract(point.lowJpy).maxZero() },
    )
}

private fun List<BigDecimal>.medianOrNull(): BigDecimal? {
    if (isEmpty()) {
        return null
    }

    val sortedValues = sorted()
    val middleIndex = size / 2

    if (size % 2 == 1) {
        return sortedValues[middleIndex]
    }

    return sortedValues[middleIndex - 1]
        .add(sortedValues[middleIndex])
        .divideEvaluation(BigDecimal("2"))
}

private fun List<BenchmarkPoint>.returnOf(selector: (BenchmarkPoint) -> BigDecimal): BigDecimal? {
    val firstValue = firstOrNull()?.let(selector) ?: return null
    val lastValue = lastOrNull()?.let(selector) ?: return null

    if (firstValue <= BigDecimal.ZERO) {
        return null
    }

    return lastValue
        .subtract(firstValue)
        .divideEvaluation(firstValue)
}

private fun List<LlmModelUsage>.toModelTokenStats(model: String): LlmModelTokenStats {
    return LlmModelTokenStats(
        model = model,
        inputTokens = sumOf { usage -> usage.usage.inputTokens ?: 0L },
        outputTokens = sumOf { usage -> usage.usage.outputTokens ?: 0L },
        cacheCreationInputTokens = sumOf { usage -> usage.usage.cacheCreationInputTokens ?: 0L },
        cacheReadInputTokens = sumOf { usage -> usage.usage.cacheReadInputTokens ?: 0L },
    )
}

private fun rateOrNull(numerator: Int, denominator: Int): BigDecimal? {
    if (denominator <= 0) {
        return null
    }

    return BigDecimal(numerator).divideEvaluation(BigDecimal(denominator))
}

private fun averageOrNull(values: List<BigDecimal>): BigDecimal? {
    if (values.isEmpty()) {
        return null
    }

    return values.sumOfBigDecimal { value -> value }.divideEvaluation(BigDecimal(values.size))
}

private fun <T> Iterable<T>.sumOfBigDecimal(selector: (T) -> BigDecimal): BigDecimal {
    return fold(BigDecimal.ZERO) { accumulator, value -> accumulator.add(selector(value)) }
}

private fun BigDecimal.divideEvaluation(other: BigDecimal): BigDecimal {
    require(other.compareTo(BigDecimal.ZERO) != 0) { "evaluation divisor must not be zero." }

    return divide(other, EVALUATION_SCALE, RoundingMode.HALF_UP)
}

private fun BigDecimal.evaluationScale(): BigDecimal {
    return setScale(EVALUATION_SCALE, RoundingMode.HALF_UP)
}

private fun BigDecimal.maxZero(): BigDecimal {
    if (this < BigDecimal.ZERO) {
        return BigDecimal.ZERO
    }

    return this
}

/**
 * 未分類 setup tag 名。
 */
private const val UNCLASSIFIED_SETUP_TAG = "unclassified"

/**
 * 不明 provider 名。
 */
private const val UNKNOWN_PROVIDER = "unknown"

/**
 * proposer phase 名。
 */
private const val PROPOSER_PHASE = "proposer"

/**
 * falsifier phase 名。
 */
private const val FALSIFIER_PHASE = "falsifier"

/**
 * reflection phase 名。
 */
private const val REFLECTION_PHASE = "reflection"

/**
 * 較正 bin 数。
 */
private const val CALIBRATION_BIN_COUNT = 10

/**
 * 評価数値の scale。
 */
private const val EVALUATION_SCALE = 10

/**
 * short SMA window。
 */
private const val SHORT_SMA_WINDOW = 5

/**
 * long SMA window。
 */
private const val LONG_SMA_WINDOW = 20

/**
 * volatility range window。
 */
private const val VOLATILITY_WINDOW = 14

/**
 * RANGE 判定の close 比率。
 */
private val RANGE_THRESHOLD_RATIO = BigDecimal("0.005")

private fun LlmPhaseUsageFact.isLlmInvocationPhase(): Boolean {
    return phase == PROPOSER_PHASE || phase == FALSIFIER_PHASE || phase == REFLECTION_PHASE
}
