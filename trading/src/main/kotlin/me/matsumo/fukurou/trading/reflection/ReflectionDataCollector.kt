package me.matsumo.fukurou.trading.reflection

import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.evaluation.EQUITY_SNAPSHOT_TRADING_DATE_ZONE
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

/**
 * reflection runner が必要とする DB 由来データを読み取る collector。
 *
 * @param decisionRepository decision protocol repository
 * @param llmRunRepository llm_runs repository
 * @param evaluationRepository evaluation aggregate repository
 * @param clock 日次・週次期間の基準 clock
 * @param queryLimit 1 tick で読む最大行数
 * @param calibrationLookbackDays confidence calibration が参照する日数
 * @param tradingZone 取引日判定に使う timezone
 */
class ReflectionDataCollector(
    private val decisionRepository: DecisionRepository,
    private val llmRunRepository: LlmRunRepository,
    private val evaluationRepository: EvaluationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val queryLimit: Int = DEFAULT_REFLECTION_QUERY_LIMIT,
    private val calibrationLookbackDays: Int = DEFAULT_REFLECTION_CALIBRATION_LOOKBACK_DAYS,
    private val tradingZone: ZoneId = EQUITY_SNAPSHOT_TRADING_DATE_ZONE,
) {

    init {
        require(queryLimit > 0) {
            "queryLimit must be greater than 0."
        }
        require(calibrationLookbackDays > 0) {
            "calibrationLookbackDays must be greater than 0."
        }
    }

    /**
     * reflection report 1 tick 分の入力データを収集する。
     */
    suspend fun collect(): Result<ReflectionDataset> {
        return runCatching {
            val collectedAt = clock.instant()
            val tradingDate = collectedAt.atZone(tradingZone).toLocalDate()
            val previousTradingDate = tradingDate.minusDays(1)
            val previousWeekDate = tradingDate
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusDays(1)
            val dailyPeriod = dailyPeriod(tradingDate)
            val previousDailyPeriod = dailyPeriod(previousTradingDate)
            val weeklyPeriod = weeklyPeriod(tradingDate)
            val previousWeeklyPeriod = weeklyPeriod(previousWeekDate)
            val calibrationPeriod = calibrationPeriod(tradingDate)

            ReflectionDataset(
                tradingDate = tradingDate,
                weekId = weekId(tradingDate),
                daily = collectWindow(dailyPeriod),
                previousTradingDate = previousTradingDate,
                previousDaily = collectWindow(previousDailyPeriod),
                weekly = collectWindow(weeklyPeriod),
                previousWeekId = weekId(previousWeekDate),
                previousWeekly = collectWindow(previousWeeklyPeriod),
                calibration = collectWindow(calibrationPeriod),
            )
        }
    }

    private suspend fun collectWindow(period: ReflectionPeriod): ReflectionWindowData {
        val evaluationPeriod = period.toEvaluationPeriod()
        val decisions = fetchLimited(queryLimit) { limit ->
            decisionRepository.findDecisionsCreatedBetween(
                from = period.from,
                toExclusive = period.toExclusive,
                limit = limit,
            ).getOrThrow()
        }
        val llmRuns = fetchLimited(queryLimit) { limit ->
            llmRunRepository.findRunsStartedBetween(
                from = period.from,
                toExclusive = period.toExclusive,
                limit = limit,
            ).getOrThrow()
        }
        val closedTradeResult = evaluationRepository.fetchClosedTrades(
            period = evaluationPeriod,
            limit = queryLimit,
        ).getOrThrow()
        val usageResult = evaluationRepository.fetchLlmPhaseUsages(
            period = evaluationPeriod,
            limit = queryLimit,
        ).getOrThrow()

        return ReflectionWindowData(
            period = period,
            decisions = decisions.values,
            llmRuns = llmRuns.values,
            closedTrades = closedTradeResult.trades,
            decisionRunCount = evaluationRepository.countDecisionRuns(evaluationPeriod).getOrThrow(),
            actionCounts = evaluationRepository.countDecisionsByAction(evaluationPeriod).getOrThrow(),
            llmPhaseUsages = usageResult.facts,
            truncation = ReflectionTruncationFlags(
                decisions = decisions.truncated,
                llmRuns = llmRuns.truncated,
                closedTrades = closedTradeResult.truncated,
                llmUsages = usageResult.truncated,
            ),
        )
    }

    private suspend fun <T> fetchLimited(limit: Int, fetch: suspend (Int) -> List<T>): LimitedValues<T> {
        val fetchedValues = fetch(limit + 1)

        return LimitedValues(
            values = fetchedValues.take(limit),
            truncated = fetchedValues.size > limit,
        )
    }

    private fun dailyPeriod(tradingDate: LocalDate): ReflectionPeriod {
        val from = tradingDate.atStartOfDay(tradingZone).toInstant()
        val toExclusive = tradingDate.plusDays(1).atStartOfDay(tradingZone).toInstant()

        return ReflectionPeriod(
            id = tradingDate.toString(),
            from = from,
            toExclusive = toExclusive,
        )
    }

    private fun weeklyPeriod(tradingDate: LocalDate): ReflectionPeriod {
        val weekStart = tradingDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEndExclusive = weekStart.plusDays(DAYS_PER_WEEK)

        return ReflectionPeriod(
            id = weekId(tradingDate),
            from = weekStart.atStartOfDay(tradingZone).toInstant(),
            toExclusive = weekEndExclusive.atStartOfDay(tradingZone).toInstant(),
        )
    }

    private fun calibrationPeriod(tradingDate: LocalDate): ReflectionPeriod {
        val toExclusive = tradingDate.plusDays(1).atStartOfDay(tradingZone).toInstant()

        return ReflectionPeriod(
            id = "last-${calibrationLookbackDays}d",
            from = toExclusive.minus(Duration.ofDays(calibrationLookbackDays.toLong())),
            toExclusive = toExclusive,
        )
    }

    private fun weekId(date: LocalDate): String {
        val weekFields = WeekFields.ISO
        val weekBasedYear = date.get(weekFields.weekBasedYear())
        val week = date.get(weekFields.weekOfWeekBasedYear())

        return "$weekBasedYear-W${week.twoDigits()}"
    }
}

/**
 * 取得上限を反映した値。
 *
 * @param values 採用する値
 * @param truncated 取得上限で切り詰められたか
 */
private data class LimitedValues<T>(
    val values: List<T>,
    val truncated: Boolean,
)

private fun Int.twoDigits(): String {
    return toString().padStart(length = 2, padChar = '0')
}

/**
 * 1 週間の日数。
 */
private const val DAYS_PER_WEEK = 7L
