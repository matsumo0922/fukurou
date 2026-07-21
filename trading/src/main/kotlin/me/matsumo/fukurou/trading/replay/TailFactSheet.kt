package me.matsumo.fukurou.trading.replay

import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.domain.EvaluationCohort
import me.matsumo.fukurou.trading.evaluation.ClosedTradeFact
import me.matsumo.fukurou.trading.evaluation.EvaluationMath
import me.matsumo.fukurou.trading.persistence.jdbcConnection
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
import java.time.Instant

/** tail run 全体の出力。単一 snapshot 内で組み立て、transaction の外で serialize して書く。 */
data class TailRunOutput(
    val targets: List<TailTargetLine>,
    val cohortSummaries: List<TailCohortSummaryLine>,
    val runSummary: TailRunSummaryLine,
)

/**
 * closed long position の逆行を fill-weighted R 換算で集計する tail 事実シートを単一 read-only snapshot で実行する。
 *
 * 初期リスク R を既存 evaluation と同じ fill-weighted stop から復元し、実際の逆行を平均約定価格と台帳最安値の差から
 * 求める。最安値は exit fill slippage を含む台帳値であり market 最安値そのものとは主張しない。
 */
class TailFactSheet(private val runtime: ReplayReadOnlyRuntime) {

    private val json = Json { encodeDefaults = true }

    /** tail 事実シートを実行し、JSON Lines sink へ target 行・cohort 集計・run summary を書く。 */
    fun run(bounds: TailReplayBounds, sink: (String) -> Unit) {
        val output = buildOutput(bounds)

        output.targets.forEach { line -> sink(json.encodeToString(TailTargetLine.serializer(), line)) }
        output.cohortSummaries.forEach { line -> sink(json.encodeToString(TailCohortSummaryLine.serializer(), line)) }
        sink(json.encodeToString(TailRunSummaryLine.serializer(), output.runSummary))
    }

    /** 単一 snapshot 内で全出力を組み立てて返す。 */
    fun buildOutput(bounds: TailReplayBounds): TailRunOutput {
        return runtime.readInSingleSnapshot(bounds.statementTimeoutSeconds) {
            val snapshotAtMs = readSnapshotNow()
            enforceTargetLimit(bounds)

            val gapProjection = ReplayGapProjection.project(this, bounds.window, snapshotAtMs)
            val rows = TailReplayQuery.selectTargets(this, bounds.window)
            val results = rows.map { row -> analyze(row, gapProjection, bounds.thresholdRMultiple) }

            TailRunOutput(
                targets = results.map(TailTargetResult::toLine),
                cohortSummaries = buildCohortSummaries(results, bounds.thresholdRMultiple),
                runSummary = TailRunSummaryLine(
                    windowFromMs = bounds.window.fromMs,
                    windowToExclusiveMs = bounds.window.toExclusiveMs,
                    snapshotAtMs = snapshotAtMs,
                    targetCount = results.size,
                    thresholdRMultiple = bounds.thresholdRMultiple.toPlainString(),
                    disclosures = TailRunSummaryLine.DEFAULT_DISCLOSURES,
                ),
            )
        }
    }

    private fun JdbcTransaction.enforceTargetLimit(bounds: TailReplayBounds) {
        val targetCount = TailReplayQuery.countTargets(this, bounds.window)
        if (targetCount > bounds.maxTargets) {
            throw ReplayRunFailedException(
                "tail target count $targetCount exceeds max ${bounds.maxTargets}; the run is failed without truncation.",
            )
        }
    }

    private fun analyze(
        row: TailPositionRow,
        gapProjection: ReplayGapProjection,
        thresholdRMultiple: BigDecimal,
    ): TailTargetResult {
        val gapReason = gapProjection.intersectingReason(row.openedAtMs, row.closedAtMs)
        if (gapReason != null) {
            return unknownResult(row, gapReason, buildNotes(row))
        }

        val evaluated = EvaluationMath.evaluateTrade(row.toClosedTradeFact())
        val maeR = evaluated.maeR
        if (maeR == null) {
            return unknownResult(row, ReplayUnknownReason.TAIL_BASIS_UNAVAILABLE, buildNotes(row))
        }

        val adverseExcursionJpy = row.lowestPriceSinceEntryJpy
            ?.let { lowest -> row.averageEntryPriceJpy?.subtract(lowest) }
        val breaches = maeR > thresholdRMultiple

        return TailTargetResult(
            positionId = row.positionId,
            cohort = row.cohort,
            populationStatus = ReplayPopulationStatus.ELIGIBLE,
            unknownReason = null,
            fidelity = ReplayFidelity.LEDGER_FACT,
            openedAtMs = row.openedAtMs,
            closedAtMs = row.closedAtMs,
            entrySizeBtc = row.entrySizeBtc,
            averageEntryPriceJpy = row.averageEntryPriceJpy,
            fillWeightedStopPriceJpy = row.fillWeightedStopPriceJpy,
            lowestPriceSinceEntryJpy = row.lowestPriceSinceEntryJpy,
            initialRiskPriceWidthJpy = evaluated.initialRiskPriceWidthJpy,
            adverseExcursionJpy = adverseExcursionJpy,
            adverseExcursionR = maeR,
            breachesThreshold = breaches,
            notes = buildNotes(row) + NOTE_LEDGER_VALUE,
        )
    }

    private fun unknownResult(
        row: TailPositionRow,
        reason: ReplayUnknownReason,
        notes: List<String>,
    ): TailTargetResult {
        return TailTargetResult(
            positionId = row.positionId,
            cohort = row.cohort,
            populationStatus = ReplayPopulationStatus.UNKNOWN,
            unknownReason = reason,
            fidelity = ReplayFidelity.UNKNOWN,
            openedAtMs = row.openedAtMs,
            closedAtMs = row.closedAtMs,
            entrySizeBtc = row.entrySizeBtc,
            averageEntryPriceJpy = row.averageEntryPriceJpy,
            fillWeightedStopPriceJpy = row.fillWeightedStopPriceJpy,
            lowestPriceSinceEntryJpy = row.lowestPriceSinceEntryJpy,
            initialRiskPriceWidthJpy = null,
            adverseExcursionJpy = null,
            adverseExcursionR = null,
            breachesThreshold = false,
            notes = notes,
        )
    }

    private fun buildNotes(row: TailPositionRow): List<String> {
        return buildList {
            if (row.hasPartialClose) add(NOTE_PARTIAL_CLOSE)
            if (row.hasPyramidAdd) add(NOTE_PYRAMID_ADD)
        }
    }

    private fun buildCohortSummaries(
        results: List<TailTargetResult>,
        thresholdRMultiple: BigDecimal,
    ): List<TailCohortSummaryLine> {
        return results
            .groupBy(TailTargetResult::cohort)
            .toSortedMap(compareBy(EvaluationCohort::name))
            .map { (cohort, cohortResults) -> summarizeCohort(cohort, cohortResults, thresholdRMultiple) }
    }

    private fun summarizeCohort(
        cohort: EvaluationCohort,
        results: List<TailTargetResult>,
        thresholdRMultiple: BigDecimal,
    ): TailCohortSummaryLine {
        val eligible = results.filter { result -> result.populationStatus == ReplayPopulationStatus.ELIGIBLE }
        val unknownByReason = results
            .filter { result -> result.populationStatus == ReplayPopulationStatus.UNKNOWN }
            .mapNotNull(TailTargetResult::unknownReason)
            .groupingBy { reason -> reason }
            .eachCount()

        return TailCohortSummaryLine(
            cohort = cohort,
            thresholdRMultiple = thresholdRMultiple.toPlainString(),
            eligibleCount = eligible.size,
            thresholdBreachCount = eligible.count { result -> result.breachesThreshold },
            partialCloseCount = eligible.count { result -> result.notes.contains(NOTE_PARTIAL_CLOSE) },
            pyramidAddCount = eligible.count { result -> result.notes.contains(NOTE_PYRAMID_ADD) },
            unknownCountByReason = unknownByReason,
        )
    }

    private fun JdbcTransaction.readSnapshotNow(): Long {
        return jdbcConnection().prepareStatement(SNAPSHOT_NOW_SQL).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "snapshot now query returned no rows." }
                rows.getLong("now_ms")
            }
        }
    }

    private fun TailPositionRow.toClosedTradeFact(): ClosedTradeFact {
        return ClosedTradeFact(
            positionId = positionId,
            openedAt = Instant.ofEpochMilli(openedAtMs),
            closedAt = Instant.ofEpochMilli(closedAtMs),
            sizeBtc = entrySizeBtc ?: BigDecimal.ZERO,
            averageEntryPriceJpy = averageEntryPriceJpy ?: BigDecimal.ZERO,
            entryWeightedProtectiveStopPriceJpy = fillWeightedStopPriceJpy,
            highestPriceSinceEntryJpy = highestPriceSinceEntryJpy ?: (averageEntryPriceJpy ?: BigDecimal.ZERO),
            lowestPriceSinceEntryJpy = lowestPriceSinceEntryJpy,
            tradePnlJpy = BigDecimal.ZERO,
            estimatedWinProbability = null,
            setupTags = emptyList(),
            llmProvider = null,
            cohort = cohort,
        )
    }

    private companion object {
        const val SNAPSHOT_NOW_SQL =
            "SELECT (extract(epoch FROM transaction_timestamp()) * 1000)::bigint AS now_ms"

        const val NOTE_LEDGER_VALUE =
            "逆行は台帳記録値 (positions.lowest_price_since_entry_jpy) であり exit fill slippage を含みうる。"
        const val NOTE_PARTIAL_CLOSE = "部分決済 (複数 SELL fill) で決済中に基準数量が変わった position。"
        const val NOTE_PYRAMID_ADD = "pyramiding で建て増しした position。fill-weighted で同時点の基準を用いる。"
    }
}
