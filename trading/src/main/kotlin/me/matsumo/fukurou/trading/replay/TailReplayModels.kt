package me.matsumo.fukurou.trading.replay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.matsumo.fukurou.trading.domain.EvaluationCohort
import java.math.BigDecimal
import java.util.UUID

/**
 * 対象 closed long position の記録済み事実。selection query が 1 行 1 対象で埋める。
 *
 * `averageEntryPriceJpy` / `fillWeightedStopPriceJpy` は既存 evaluation と同じ、BUY execution の size 加重値である。
 * `lowestPriceSinceEntryJpy` は `positions` の running min であり exit fill slippage を含む台帳値である。
 */
data class TailPositionRow(
    val positionId: UUID,
    val openedAtMs: Long,
    val closedAtMs: Long,
    val cohort: EvaluationCohort,
    val entrySizeBtc: BigDecimal?,
    val averageEntryPriceJpy: BigDecimal?,
    val fillWeightedStopPriceJpy: BigDecimal?,
    val highestPriceSinceEntryJpy: BigDecimal?,
    val lowestPriceSinceEntryJpy: BigDecimal?,
    val pyramidAddCount: Int,
    val sellExecutionCount: Int,
    val isEvaluationExcluded: Boolean,
) {
    /** 部分決済 (scale-out) で基準数量が生存中に変わったか。SELL execution が複数あれば真。 */
    val hasPartialClose: Boolean
        get() = sellExecutionCount > 1

    /** pyramiding (buildup) で基準数量が生存中に変わったか。 */
    val hasPyramidAdd: Boolean
        get() = pyramidAddCount > 0
}

/** 対象 1 件の逆行解析結果。fill-weighted R 換算と台帳事実を束ねる。 */
data class TailTargetResult(
    val positionId: UUID,
    val cohort: EvaluationCohort,
    val populationStatus: ReplayPopulationStatus,
    val unknownReason: ReplayUnknownReason?,
    val fidelity: ReplayFidelity,
    val openedAtMs: Long,
    val closedAtMs: Long,
    val entrySizeBtc: BigDecimal?,
    val averageEntryPriceJpy: BigDecimal?,
    val fillWeightedStopPriceJpy: BigDecimal?,
    val lowestPriceSinceEntryJpy: BigDecimal?,
    val initialRiskPriceWidthJpy: BigDecimal?,
    val adverseExcursionJpy: BigDecimal?,
    val adverseExcursionR: BigDecimal?,
    val breachesThreshold: Boolean,
    val notes: List<String>,
) {
    /** JSON Lines の target 行へ変換する。BigDecimal は plain string で書く。 */
    fun toLine(): TailTargetLine {
        return TailTargetLine(
            positionId = positionId.toString(),
            cohort = cohort,
            populationStatus = populationStatus,
            fidelity = fidelity,
            unknownReason = unknownReason,
            openedAtMs = openedAtMs,
            closedAtMs = closedAtMs,
            sizeBtc = entrySizeBtc?.toPlainString(),
            averageEntryPriceJpy = averageEntryPriceJpy?.toPlainString(),
            fillWeightedStopPriceJpy = fillWeightedStopPriceJpy?.toPlainString(),
            lowestPriceSinceEntryJpy = lowestPriceSinceEntryJpy?.toPlainString(),
            initialRiskPriceWidthJpy = initialRiskPriceWidthJpy?.toPlainString(),
            adverseExcursionJpy = adverseExcursionJpy?.toPlainString(),
            adverseExcursionR = adverseExcursionR?.toPlainString(),
            breachesThreshold = breachesThreshold,
            notes = notes,
        )
    }
}

/** JSON Lines の 1 対象行。1 position 独立の逆行事実を表す。 */
@Serializable
data class TailTargetLine(
    val type: String = TARGET_LINE_TYPE,
    @SerialName("position_id") val positionId: String,
    val cohort: EvaluationCohort,
    @SerialName("population_status") val populationStatus: ReplayPopulationStatus,
    val fidelity: ReplayFidelity,
    @SerialName("unknown_reason") val unknownReason: ReplayUnknownReason?,
    @SerialName("opened_at_ms") val openedAtMs: Long,
    @SerialName("closed_at_ms") val closedAtMs: Long,
    @SerialName("size_btc") val sizeBtc: String?,
    @SerialName("average_entry_price_jpy") val averageEntryPriceJpy: String?,
    @SerialName("fill_weighted_stop_price_jpy") val fillWeightedStopPriceJpy: String?,
    @SerialName("lowest_price_since_entry_jpy") val lowestPriceSinceEntryJpy: String?,
    @SerialName("initial_risk_price_width_jpy") val initialRiskPriceWidthJpy: String?,
    @SerialName("adverse_excursion_jpy") val adverseExcursionJpy: String?,
    @SerialName("adverse_excursion_r") val adverseExcursionR: String?,
    @SerialName("breaches_threshold") val breachesThreshold: Boolean,
    val notes: List<String>,
) {
    companion object {
        /** target 行の type 判別値。 */
        const val TARGET_LINE_TYPE = "tail_target"
    }
}

/** cohort ごとに分離した tail 集計行。 */
@Serializable
data class TailCohortSummaryLine(
    val type: String = COHORT_SUMMARY_LINE_TYPE,
    val cohort: EvaluationCohort,
    @SerialName("threshold_r_multiple") val thresholdRMultiple: String,
    @SerialName("eligible_count") val eligibleCount: Int,
    @SerialName("threshold_breach_count") val thresholdBreachCount: Int,
    @SerialName("partial_close_count") val partialCloseCount: Int,
    @SerialName("pyramid_add_count") val pyramidAddCount: Int,
    @SerialName("unknown_count_by_reason") val unknownCountByReason: Map<ReplayUnknownReason, Int>,
) {
    companion object {
        /** cohort 集計行の type 判別値。 */
        const val COHORT_SUMMARY_LINE_TYPE = "tail_cohort_summary"
    }
}

/** run 全体の tail summary 行。母数開示と台帳値の注記を含む。 */
@Serializable
data class TailRunSummaryLine(
    val type: String = RUN_SUMMARY_LINE_TYPE,
    @SerialName("window_from_ms") val windowFromMs: Long,
    @SerialName("window_to_exclusive_ms") val windowToExclusiveMs: Long,
    @SerialName("snapshot_at_ms") val snapshotAtMs: Long,
    @SerialName("target_count") val targetCount: Int,
    @SerialName("threshold_r_multiple") val thresholdRMultiple: String,
    val disclosures: List<String>,
) {
    companion object {
        /** run summary 行の type 判別値。 */
        const val RUN_SUMMARY_LINE_TYPE = "tail_run_summary"

        /** 台帳値・R 復元・母数の読み方を示す固定注記。 */
        val DEFAULT_DISCLOSURES: List<String> = listOf(
            "逆行は average_entry_price_jpy − positions.lowest_price_since_entry_jpy の台帳記録値であり、" +
                "exit fill slippage を含みうる。market が到達した最安値そのものではない。",
            "初期リスク R は既存 evaluation と同じ fill-weighted stop から復元した価格幅である " +
                "(BUY execution の size 加重 average entry と、entry order の protective stop の size 加重値の差)。",
            "最安値または entry stop が null、あるいは risk width が非正 (fill-weighted stop ≥ average entry) の " +
                "position は TAIL_BASIS_UNAVAILABLE で UNKNOWN とし、母数から外す。逆行は exit 理由に依らず出力する。",
            "operator が evaluation_exclusions で戦略評価から外すと宣言した position は EVALUATION_EXCLUDED で " +
                "UNKNOWN とし、母数から外す (既存 evaluation と同じ扱い。infrastructure failure を戦略評価へ混ぜない)。",
        )
    }
}
