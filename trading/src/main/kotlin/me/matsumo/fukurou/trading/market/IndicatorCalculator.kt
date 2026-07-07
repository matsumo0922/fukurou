package me.matsumo.fukurou.trading.market

import kotlinx.serialization.Serializable
import me.matsumo.fukurou.trading.domain.Candle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 計算可能な technical indicator。
 */
@Serializable
enum class IndicatorType {
    /**
     * Average True Range。
     */
    ATR,

    /**
     * Exponential Moving Average。
     */
    EMA,

    /**
     * Relative Strength Index。
     */
    RSI,

    /**
     * Simple Moving Average。
     */
    SMA,

    /**
     * Moving Average Convergence Divergence。
     */
    MACD,

    /**
     * ATR を直近 lookback 本の中で百分位化した値。
     */
    ATR_PERCENTILE,

    /**
     * GMO kline の営業日境界で累積する session VWAP。
     */
    VWAP_SESSION,

    /**
     * 出来高を直近期間の平均との差で標準化した z-score。
     */
    VOLUME_Z_SCORE,
}

/**
 * indicator 計算に使う parameter。
 *
 * @param period ATR / ATR_PERCENTILE / EMA / RSI / SMA / VOLUME_Z_SCORE の期間
 * @param lookback ATR_PERCENTILE の比較に使う ATR 本数
 * @param fastPeriod MACD の短期 EMA 期間
 * @param slowPeriod MACD の長期 EMA 期間
 * @param signalPeriod MACD signal EMA 期間
 */
data class IndicatorParams(
    val period: Int? = null,
    val lookback: Int? = null,
    val fastPeriod: Int? = null,
    val slowPeriod: Int? = null,
    val signalPeriod: Int? = null,
)

/**
 * indicator 計算結果。
 *
 * @param indicator indicator 種別
 * @param params 実際に使った parameter
 * @param values openTime ごとの indicator 値
 */
@Serializable
data class IndicatorResult(
    val indicator: IndicatorType,
    val params: IndicatorResolvedParams,
    val values: List<IndicatorValue>,
)

/**
 * indicator 計算で確定した parameter。
 *
 * @param period ATR / ATR_PERCENTILE / EMA / RSI / SMA / VOLUME_Z_SCORE の期間
 * @param lookback ATR_PERCENTILE の比較に使った ATR 本数
 * @param fastPeriod MACD の短期 EMA 期間
 * @param slowPeriod MACD の長期 EMA 期間
 * @param signalPeriod MACD signal EMA 期間
 */
@Serializable
data class IndicatorResolvedParams(
    val period: Int? = null,
    val lookback: Int? = null,
    val fastPeriod: Int? = null,
    val slowPeriod: Int? = null,
    val signalPeriod: Int? = null,
)

/**
 * 1 candle に対応する indicator 値。
 *
 * @param openTime 始値時刻
 * @param value 単一値 indicator の値
 * @param macd MACD line
 * @param signal MACD signal line
 * @param histogram MACD histogram
 */
@Serializable
data class IndicatorValue(
    val openTime: String,
    val value: Double? = null,
    val macd: Double? = null,
    val signal: Double? = null,
    val histogram: Double? = null,
)

/**
 * ローソク足列から technical indicator を計算する純関数群。
 */
object IndicatorCalculator {

    /**
     * indicator が少なくとも 1 つの非 null 値を返すために必要な最小 candle 本数を返す。
     */
    fun requiredCandleCount(
        indicator: IndicatorType,
        params: IndicatorParams = IndicatorParams(),
    ): Result<Int> {
        return runCatching {
            when (indicator) {
                IndicatorType.ATR -> {
                    val period = validatePeriod(params.period ?: DEFAULT_ATR_PERIOD, "period")

                    validateRequiredCandleCount(period.toLong())
                }
                IndicatorType.EMA -> {
                    val period = validatePeriod(params.period ?: DEFAULT_EMA_PERIOD, "period")

                    validateRequiredCandleCount(period.toLong())
                }
                IndicatorType.RSI -> {
                    val period = validatePeriod(params.period ?: DEFAULT_RSI_PERIOD, "period")

                    validateRequiredCandleCount(period.toLong() + 1L)
                }
                IndicatorType.SMA -> {
                    val period = validatePeriod(params.period ?: DEFAULT_SMA_PERIOD, "period")

                    validateRequiredCandleCount(period.toLong())
                }
                IndicatorType.MACD -> {
                    val fastPeriod = validatePeriod(
                        period = params.fastPeriod ?: DEFAULT_MACD_FAST_PERIOD,
                        name = "fast_period",
                    )
                    val slowPeriod = validatePeriod(
                        period = params.slowPeriod ?: DEFAULT_MACD_SLOW_PERIOD,
                        name = "slow_period",
                    )
                    val signalPeriod = validatePeriod(
                        period = params.signalPeriod ?: DEFAULT_MACD_SIGNAL_PERIOD,
                        name = "signal_period",
                    )

                    require(fastPeriod < slowPeriod) {
                        "fast_period must be smaller than slow_period."
                    }

                    validateRequiredCandleCount(slowPeriod.toLong() + signalPeriod.toLong() - 1L)
                }
                IndicatorType.ATR_PERCENTILE -> {
                    val period = validatePeriod(params.period ?: DEFAULT_ATR_PERCENTILE_PERIOD, "period")
                    val lookback = validateLookback(params.lookback ?: DEFAULT_ATR_PERCENTILE_LOOKBACK)

                    validateRequiredCandleCount(period.toLong() + lookback.toLong() - 1L)
                }
                IndicatorType.VWAP_SESSION -> validateRequiredCandleCount(1L)
                IndicatorType.VOLUME_Z_SCORE -> {
                    val period = validatePeriod(params.period ?: DEFAULT_VOLUME_Z_SCORE_PERIOD, "period")

                    validateRequiredCandleCount(period.toLong())
                }
            }
        }.mapError()
    }

    /**
     * 指定 indicator を計算する。
     */
    fun calculate(
        candles: List<Candle>,
        indicator: IndicatorType,
        params: IndicatorParams = IndicatorParams(),
    ): Result<IndicatorResult> {
        return runCatching {
            require(candles.isNotEmpty()) {
                "candles must not be empty."
            }

            when (indicator) {
                IndicatorType.ATR -> calculateAtr(candles, params)
                IndicatorType.EMA -> calculateEma(candles, params)
                IndicatorType.RSI -> calculateRsi(candles, params)
                IndicatorType.SMA -> calculateSma(candles, params)
                IndicatorType.MACD -> calculateMacd(candles, params)
                IndicatorType.ATR_PERCENTILE -> calculateAtrPercentile(candles, params)
                IndicatorType.VWAP_SESSION -> calculateVwapSession(candles)
                IndicatorType.VOLUME_Z_SCORE -> calculateVolumeZScore(candles, params)
            }
        }.mapError()
    }

    private fun calculateAtr(candles: List<Candle>, params: IndicatorParams): IndicatorResult {
        val period = validatePeriod(params.period ?: DEFAULT_ATR_PERIOD, "period")
        val trueRanges = candles.mapIndexed { candleIndex, candle -> trueRange(candles, candleIndex, candle) }
        val atrValues = wilderAverage(trueRanges, period)
        val values = candles.mapIndexed { candleIndex, candle ->
            IndicatorValue(
                openTime = candle.openTime,
                value = atrValues[candleIndex],
            )
        }

        return IndicatorResult(
            indicator = IndicatorType.ATR,
            params = IndicatorResolvedParams(period = period),
            values = values,
        )
    }

    private fun calculateEma(candles: List<Candle>, params: IndicatorParams): IndicatorResult {
        val period = validatePeriod(params.period ?: DEFAULT_EMA_PERIOD, "period")
        val closeValues = candles.map { candle -> candle.close.toDoubleValue("close") }
        val emaValues = ema(closeValues, period)
        val values = candles.mapIndexed { candleIndex, candle ->
            IndicatorValue(
                openTime = candle.openTime,
                value = emaValues[candleIndex],
            )
        }

        return IndicatorResult(
            indicator = IndicatorType.EMA,
            params = IndicatorResolvedParams(period = period),
            values = values,
        )
    }

    private fun calculateRsi(candles: List<Candle>, params: IndicatorParams): IndicatorResult {
        val period = validatePeriod(params.period ?: DEFAULT_RSI_PERIOD, "period")
        val closeValues = candles.map { candle -> candle.close.toDoubleValue("close") }
        val rsiValues = rsi(closeValues, period)
        val values = candles.mapIndexed { candleIndex, candle ->
            IndicatorValue(
                openTime = candle.openTime,
                value = rsiValues[candleIndex],
            )
        }

        return IndicatorResult(
            indicator = IndicatorType.RSI,
            params = IndicatorResolvedParams(period = period),
            values = values,
        )
    }

    private fun calculateSma(candles: List<Candle>, params: IndicatorParams): IndicatorResult {
        val period = validatePeriod(params.period ?: DEFAULT_SMA_PERIOD, "period")
        val closeValues = candles.map { candle -> candle.close.toDoubleValue("close") }
        val smaValues = sma(closeValues, period)
        val values = candles.mapIndexed { candleIndex, candle ->
            IndicatorValue(
                openTime = candle.openTime,
                value = smaValues[candleIndex],
            )
        }

        return IndicatorResult(
            indicator = IndicatorType.SMA,
            params = IndicatorResolvedParams(period = period),
            values = values,
        )
    }

    private fun calculateMacd(candles: List<Candle>, params: IndicatorParams): IndicatorResult {
        val fastPeriod = validatePeriod(params.fastPeriod ?: DEFAULT_MACD_FAST_PERIOD, "fast_period")
        val slowPeriod = validatePeriod(params.slowPeriod ?: DEFAULT_MACD_SLOW_PERIOD, "slow_period")
        val signalPeriod = validatePeriod(params.signalPeriod ?: DEFAULT_MACD_SIGNAL_PERIOD, "signal_period")

        require(fastPeriod < slowPeriod) {
            "fast_period must be smaller than slow_period."
        }

        val closeValues = candles.map { candle -> candle.close.toDoubleValue("close") }
        val fastEmaValues = ema(closeValues, fastPeriod)
        val slowEmaValues = ema(closeValues, slowPeriod)
        val macdValues = calculateMacdLine(fastEmaValues, slowEmaValues)
        val signalValues = emaSparse(macdValues, signalPeriod)
        val values = candles.mapIndexed { candleIndex, candle ->
            val macd = macdValues[candleIndex]
            val signal = signalValues[candleIndex]

            IndicatorValue(
                openTime = candle.openTime,
                macd = macd,
                signal = signal,
                histogram = macd?.let { macdValue -> signal?.let { signalValue -> macdValue - signalValue } },
            )
        }

        return IndicatorResult(
            indicator = IndicatorType.MACD,
            params = IndicatorResolvedParams(
                fastPeriod = fastPeriod,
                slowPeriod = slowPeriod,
                signalPeriod = signalPeriod,
            ),
            values = values,
        )
    }

    private fun calculateAtrPercentile(candles: List<Candle>, params: IndicatorParams): IndicatorResult {
        val period = validatePeriod(params.period ?: DEFAULT_ATR_PERCENTILE_PERIOD, "period")
        val lookback = validateLookback(params.lookback ?: DEFAULT_ATR_PERCENTILE_LOOKBACK)
        validateRequiredCandleCount(period.toLong() + lookback.toLong() - 1L)

        val trueRanges = candles.mapIndexed { candleIndex, candle -> trueRange(candles, candleIndex, candle) }
        val atrValues = wilderAverage(trueRanges, period)
        val percentileValues = percentileRank(atrValues, lookback)
        val values = candles.mapIndexed { candleIndex, candle ->
            IndicatorValue(
                openTime = candle.openTime,
                value = percentileValues[candleIndex],
            )
        }

        return IndicatorResult(
            indicator = IndicatorType.ATR_PERCENTILE,
            params = IndicatorResolvedParams(
                period = period,
                lookback = lookback,
            ),
            values = values,
        )
    }

    private fun calculateVwapSession(candles: List<Candle>): IndicatorResult {
        var currentSessionDate: LocalDate? = null
        var cumulativePriceVolume = 0.0
        var cumulativeVolume = 0.0
        val values = mutableListOf<IndicatorValue>()

        for (candle in candles) {
            val sessionDate = candle.openTime.toGmoKlineSessionDate()

            if (sessionDate != currentSessionDate) {
                currentSessionDate = sessionDate
                cumulativePriceVolume = 0.0
                cumulativeVolume = 0.0
            }

            val typicalPrice = candle.typicalPrice()
            val volume = candle.volume.toDoubleValue("volume")

            cumulativePriceVolume += typicalPrice * volume
            cumulativeVolume += volume

            values += IndicatorValue(
                openTime = candle.openTime,
                value = vwapValue(cumulativePriceVolume, cumulativeVolume),
            )
        }

        return IndicatorResult(
            indicator = IndicatorType.VWAP_SESSION,
            params = IndicatorResolvedParams(),
            values = values,
        )
    }

    private fun calculateVolumeZScore(candles: List<Candle>, params: IndicatorParams): IndicatorResult {
        val period = validatePeriod(params.period ?: DEFAULT_VOLUME_Z_SCORE_PERIOD, "period")
        val volumeValues = candles.map { candle -> candle.volume.toDoubleValue("volume") }
        val zScoreValues = zScore(volumeValues, period)
        val values = candles.mapIndexed { candleIndex, candle ->
            IndicatorValue(
                openTime = candle.openTime,
                value = zScoreValues[candleIndex],
            )
        }

        return IndicatorResult(
            indicator = IndicatorType.VOLUME_Z_SCORE,
            params = IndicatorResolvedParams(period = period),
            values = values,
        )
    }
}

/**
 * ATR の既定期間。
 */
private const val DEFAULT_ATR_PERIOD = 14

/**
 * ATR_PERCENTILE の既定 ATR 期間。
 */
private const val DEFAULT_ATR_PERCENTILE_PERIOD = 14

/**
 * ATR_PERCENTILE の既定 lookback 本数。
 */
private const val DEFAULT_ATR_PERCENTILE_LOOKBACK = 100

/**
 * EMA の既定期間。
 */
private const val DEFAULT_EMA_PERIOD = 20

/**
 * RSI の既定期間。
 */
private const val DEFAULT_RSI_PERIOD = 14

/**
 * SMA の既定期間。
 */
private const val DEFAULT_SMA_PERIOD = 20

/**
 * MACD 短期 EMA の既定期間。
 */
private const val DEFAULT_MACD_FAST_PERIOD = 12

/**
 * MACD 長期 EMA の既定期間。
 */
private const val DEFAULT_MACD_SLOW_PERIOD = 26

/**
 * MACD signal EMA の既定期間。
 */
private const val DEFAULT_MACD_SIGNAL_PERIOD = 9

/**
 * VOLUME_Z_SCORE の既定期間。
 */
private const val DEFAULT_VOLUME_Z_SCORE_PERIOD = 20

/**
 * calc_indicator MCP の最大取得本数と同じ、indicator 計算側の検証上限。
 */
private const val MAX_INDICATOR_CANDLE_LIMIT = 500

/**
 * GMO kline の営業日が切り替わる JST 時刻。
 */
private const val GMO_KLINE_SESSION_BOUNDARY_HOUR = 6

/**
 * GMO kline の営業日境界判定に使う timezone。
 */
private val GmoKlineSessionZone = ZoneId.of("Asia/Tokyo")

private fun <Value> Result<Value>.mapError(): Result<Value> {
    return recoverCatching { throwable ->
        if (throwable is MarketDataParseException) {
            throw throwable
        }

        throw MarketInvalidRequestException(throwable.message.orEmpty())
    }
}

private fun validatePeriod(period: Int, name: String): Int {
    require(period > 0) {
        "$name must be greater than 0."
    }

    return period
}

private fun validateLookback(lookback: Int): Int {
    require(lookback > 0) {
        "lookback must be greater than 0."
    }

    return lookback
}

private fun validateRequiredCandleCount(requiredCandleCount: Long): Int {
    val isInRange = requiredCandleCount in 1L..MAX_INDICATOR_CANDLE_LIMIT.toLong()

    require(isInRange) {
        "required candle count must be between 1 and $MAX_INDICATOR_CANDLE_LIMIT: $requiredCandleCount"
    }

    return requiredCandleCount.toInt()
}

private fun trueRange(
    candles: List<Candle>,
    candleIndex: Int,
    candle: Candle,
): Double {
    val high = candle.high.toDoubleValue("high")
    val low = candle.low.toDoubleValue("low")
    val currentRange = high - low

    if (candleIndex == 0) {
        return currentRange
    }

    val previousClose = candles[candleIndex - 1].close.toDoubleValue("previous close")

    return max(currentRange, max(abs(high - previousClose), abs(low - previousClose)))
}

private fun sma(values: List<Double>, period: Int): List<Double?> {
    val result = MutableList<Double?>(values.size) { null }
    var rollingSum = 0.0

    values.forEachIndexed { valueIndex, value ->
        rollingSum += value

        if (valueIndex >= period) {
            rollingSum -= values[valueIndex - period]
        }

        if (valueIndex >= period - 1) {
            result[valueIndex] = rollingSum / period
        }
    }

    return result
}

private fun percentileRank(values: List<Double?>, lookback: Int): List<Double?> {
    val result = MutableList<Double?>(values.size) { null }

    for (valueIndex in values.indices) {
        val currentValue = values[valueIndex] ?: continue
        val windowStartIndex = valueIndex - lookback + 1

        if (windowStartIndex < 0) {
            continue
        }

        val window = values.subList(windowStartIndex, valueIndex + 1)
        val hasMissingValue = window.any { windowValue -> windowValue == null }

        if (hasMissingValue) {
            continue
        }

        val lessOrEqualCount = window.count { windowValue -> requireNotNull(windowValue) <= currentValue }
        result[valueIndex] = lessOrEqualCount.toDouble() / lookback
    }

    return result
}

private fun zScore(values: List<Double>, period: Int): List<Double?> {
    val result = MutableList<Double?>(values.size) { null }

    for (valueIndex in period - 1 until values.size) {
        val window = values.subList(valueIndex - period + 1, valueIndex + 1)
        val mean = window.average()
        val variance = window.sumOf { value -> (value - mean) * (value - mean) } / period
        val standardDeviation = sqrt(variance)

        if (standardDeviation == 0.0) {
            continue
        }

        result[valueIndex] = (values[valueIndex] - mean) / standardDeviation
    }

    return result
}

private fun vwapValue(cumulativePriceVolume: Double, cumulativeVolume: Double): Double? {
    if (cumulativeVolume == 0.0) {
        return null
    }

    return cumulativePriceVolume / cumulativeVolume
}

private fun ema(values: List<Double>, period: Int): List<Double?> {
    val result = MutableList<Double?>(values.size) { null }

    if (values.size < period) {
        return result
    }

    val multiplier = 2.0 / (period + 1)
    var currentEma = values.take(period).average()
    result[period - 1] = currentEma

    for (valueIndex in period until values.size) {
        currentEma = (values[valueIndex] - currentEma) * multiplier + currentEma
        result[valueIndex] = currentEma
    }

    return result
}

private fun emaSparse(values: List<Double?>, period: Int): List<Double?> {
    val validValues = values.withIndex()
        .filter { indexedValue -> indexedValue.value != null }
        .map { indexedValue -> indexedValue.index to requireNotNull(indexedValue.value) }
    val emaValues = ema(validValues.map { indexedValue -> indexedValue.second }, period)
    val result = MutableList<Double?>(values.size) { null }

    validValues.forEachIndexed { validIndex, indexedValue ->
        result[indexedValue.first] = emaValues[validIndex]
    }

    return result
}

private fun wilderAverage(values: List<Double>, period: Int): List<Double?> {
    val result = MutableList<Double?>(values.size) { null }

    if (values.size < period) {
        return result
    }

    var currentAverage = values.take(period).average()
    result[period - 1] = currentAverage

    for (valueIndex in period until values.size) {
        currentAverage = (currentAverage * (period - 1) + values[valueIndex]) / period
        result[valueIndex] = currentAverage
    }

    return result
}

private fun rsi(values: List<Double>, period: Int): List<Double?> {
    val result = MutableList<Double?>(values.size) { null }

    if (values.size <= period) {
        return result
    }

    var averageGain = 0.0
    var averageLoss = 0.0

    for (valueIndex in 1..period) {
        val change = values[valueIndex] - values[valueIndex - 1]

        if (change >= 0) {
            averageGain += change
        } else {
            averageLoss += abs(change)
        }
    }

    averageGain /= period
    averageLoss /= period
    result[period] = rsiValue(averageGain, averageLoss)

    for (valueIndex in period + 1 until values.size) {
        val change = values[valueIndex] - values[valueIndex - 1]
        val gain = if (change > 0) change else 0.0
        val loss = if (change < 0) abs(change) else 0.0

        averageGain = (averageGain * (period - 1) + gain) / period
        averageLoss = (averageLoss * (period - 1) + loss) / period
        result[valueIndex] = rsiValue(averageGain, averageLoss)
    }

    return result
}

private fun rsiValue(averageGain: Double, averageLoss: Double): Double {
    if (averageLoss == 0.0) {
        return 100.0
    }

    if (averageGain == 0.0) {
        return 0.0
    }

    val relativeStrength = averageGain / averageLoss

    return 100.0 - (100.0 / (1.0 + relativeStrength))
}

private fun calculateMacdLine(
    fastEmaValues: List<Double?>,
    slowEmaValues: List<Double?>,
): List<Double?> {
    return fastEmaValues.mapIndexed { valueIndex, fastEma ->
        val slowEma = slowEmaValues[valueIndex]

        if (fastEma == null || slowEma == null) {
            null
        } else {
            fastEma - slowEma
        }
    }
}

private fun Candle.typicalPrice(): Double {
    val high = high.toDoubleValue("high")
    val low = low.toDoubleValue("low")
    val close = close.toDoubleValue("close")

    return (high + low + close) / 3.0
}

private fun String.toGmoKlineSessionDate(): LocalDate {
    return toInstantValue("openTime")
        .atZone(GmoKlineSessionZone)
        .minusHours(GMO_KLINE_SESSION_BOUNDARY_HOUR.toLong())
        .toLocalDate()
}

private fun String.toInstantValue(fieldName: String): Instant {
    return try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        throw MarketDataParseException("$fieldName must be an ISO-8601 instant: $this")
    }
}

private fun String.toDoubleValue(fieldName: String): Double {
    return toDoubleOrNull() ?: throw MarketDataParseException("$fieldName must be a decimal number: $this")
}
