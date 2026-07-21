package me.matsumo.fukurou.trading.replay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.domain.EvaluationCohort

/** replay 出力行が自己申告する忠実性区分。 */
@Serializable
enum class ReplayFidelity {
    /** 記録済み execution から一意に決まる事実。 */
    EXACT,

    /** 台帳に記録済みの事実だが exit fill slippage 等を含み、market 事実そのものとは主張しない値 (tail 逆行など)。 */
    LEDGER_FACT,

    /** 記録済みデータからは確定できず未知として開示する事実。 */
    UNKNOWN,
}

/** 対象 order の記録済み結果 (ground truth) 分類。 */
@Serializable
enum class ReplayOrderClassification {
    /** entry execution 行が存在し約定した order。 */
    FILLED,

    /** execution 無しで `expired_at` が立った TTL 失効 order。 */
    TTL_EXPIRED,

    /** execution 無し・`expired_at` NULL・`cancel_reason` ありの非 TTL 終端 order。 */
    NON_TTL_TERMINAL,

    /** execution・`expired_at`・`cancel_reason` いずれも持たない snapshot 時点 OPEN の order。 */
    OPEN_AT_SNAPSHOT,
}

/** 対象が母集団のどこに属するかを cohort とは独立に表す population 区分。 */
@Serializable
enum class ReplayPopulationStatus {
    /** TTL retention 分析の母数に入る適格対象。 */
    ELIGIBLE,

    /** TTL 由来で結果が変わらないため母数から除外する非 TTL 終端。 */
    NON_TTL_TERMINAL,

    /** snapshot 時点で OPEN のため約定を主張せず母数から分離する対象。 */
    OPEN_AT_SNAPSHOT,

    /** gap 交差・receipt 欠落・time stop 解決不能により母数から外す未知対象。 */
    UNKNOWN,

    /** 生存区間に replay 入力 (receipt) を持たない対象。 */
    NO_REPLAY_INPUT,
}

/** 対象または候補を UNKNOWN にした理由。 */
@Serializable
enum class ReplayUnknownReason {
    /** 短縮 TTL の論理期限が約定 event の socket 受信時刻より後で retention を確定できない。 */
    RETENTION_UNCONFIRMED,

    /** 候補 TTL と time stop の早い方を決める time stop を lineage から解決できない。 */
    TIME_STOP_UNRESOLVED,

    /** 生存区間が market data gap と交差する。 */
    MARKET_DATA_GAP,

    /** 生存区間が infrastructure gap と交差する。 */
    INFRASTRUCTURE_GAP,

    /** 生存区間の source sequence が連続せず receipt 欠落がある。 */
    RECEIPT_SEQUENCE_GAP,

    /** 生存区間に receipt が 1 件も存在しない。 */
    NO_REPLAY_INPUT,

    /** tail の最安値・entry stop が null、または risk width が非正で逆行を R 換算できない。 */
    TAIL_BASIS_UNAVAILABLE,

    /** operator が evaluation_exclusions で戦略評価から外すと宣言した (infrastructure failure 由来など)。 */
    EVALUATION_EXCLUDED,
}

/** 各短縮 TTL 候補の反実仮想判定。 */
@Serializable
enum class ReplayCandidateVerdict {
    /** 論理期限が約定 event の socket 受信時刻以下で約定を確実に取りこぼす。 */
    DROPPED,

    /** 論理期限が約定 event の socket 受信時刻より後で RETAINED / DROPPED を確定できない。 */
    RETENTION_UNCONFIRMED,

    /** time stop を解決できず実効期限を確定できない。 */
    TIME_STOP_UNRESOLVED,
}

/** 1 つの短縮 TTL 候補に対する反実仮想結果。 */
@Serializable
data class ReplayCandidateResult(
    @SerialName("candidate_ttl_seconds") val candidateTtlSeconds: Long,
    @SerialName("effective_expiry_ms") val effectiveExpiryMs: Long?,
    val verdict: ReplayCandidateVerdict,
    val fidelity: ReplayFidelity,
)

/** JSON Lines の 1 対象行。各 order 独立の反実仮想を表す。 */
@Serializable
data class ReplayTargetLine(
    val type: String = TARGET_LINE_TYPE,
    @SerialName("order_id") val orderId: String,
    val cohort: EvaluationCohort,
    @SerialName("population_status") val populationStatus: ReplayPopulationStatus,
    val classification: ReplayOrderClassification,
    val fidelity: ReplayFidelity,
    @SerialName("unknown_reason") val unknownReason: ReplayUnknownReason?,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("executed_at_ms") val executedAtMs: Long?,
    @SerialName("market_response_latency_ms") val marketResponseLatencyMs: Long?,
    @SerialName("recorded_effective_ttl_seconds") val recordedEffectiveTtlSeconds: Long?,
    @SerialName("time_stop_at_ms") val timeStopAtMs: Long?,
    val candidates: List<ReplayCandidateResult>,
    val notes: List<String>,
) {
    companion object {
        /** target 行の type 判別値。 */
        const val TARGET_LINE_TYPE = "target"
    }
}

/** 1 短縮 TTL 候補の cohort 集計。 */
@Serializable
data class ReplayCandidateAggregate(
    @SerialName("candidate_ttl_seconds") val candidateTtlSeconds: Long,
    @SerialName("confirmed_dropped_count") val confirmedDroppedCount: Int,
    @SerialName("retention_unconfirmed_count") val retentionUnconfirmedCount: Int,
    @SerialName("time_stop_unresolved_count") val timeStopUnresolvedCount: Int,
)

/** cohort ごとに分離した集計行。 */
@Serializable
data class ReplayCohortSummaryLine(
    val type: String = COHORT_SUMMARY_LINE_TYPE,
    val cohort: EvaluationCohort,
    @SerialName("eligible_count") val eligibleCount: Int,
    @SerialName("filled_count") val filledCount: Int,
    @SerialName("ttl_expired_count") val ttlExpiredCount: Int,
    @SerialName("non_ttl_terminal_count") val nonTtlTerminalCount: Int,
    @SerialName("open_at_snapshot_count") val openAtSnapshotCount: Int,
    @SerialName("input_missing_count") val inputMissingCount: Int,
    @SerialName("unknown_count_by_reason") val unknownCountByReason: Map<ReplayUnknownReason, Int>,
    val candidates: List<ReplayCandidateAggregate>,
) {
    companion object {
        /** cohort 集計行の type 判別値。 */
        const val COHORT_SUMMARY_LINE_TYPE = "cohort_summary"
    }
}

/** run 全体の summary 行。母数開示と読み方の注記を含む。 */
@Serializable
data class ReplayRunSummaryLine(
    val type: String = RUN_SUMMARY_LINE_TYPE,
    @SerialName("window_from_ms") val windowFromMs: Long,
    @SerialName("window_to_exclusive_ms") val windowToExclusiveMs: Long,
    @SerialName("snapshot_at_ms") val snapshotAtMs: Long,
    @SerialName("target_count") val targetCount: Int,
    @SerialName("candidate_ttl_seconds") val candidateTtlSeconds: List<Long>,
    val disclosures: List<String>,
) {
    companion object {
        /** run summary 行の type 判別値。 */
        const val RUN_SUMMARY_LINE_TYPE = "run_summary"

        /** confirmed-DROPPED が下界であり慎重側境界を示す固定注記。 */
        val DEFAULT_DISCLOSURES: List<String> = listOf(
            "confirmed-DROPPED は真の取りこぼしの下界である。安全に短縮できる境界は confirmed-DROPPED の" +
                "立ち上がりではなく RETENTION_UNCONFIRMED の立ち上がりを慎重側の境界として読むこと。",
            "各行は指値を記録済みの値に固定したまま TTL だけを短縮した、order ごとに独立な反実仮想である。",
            "RETAINED は主張しない。E' > executed_at の候補は RETENTION_UNCONFIRMED として UNKNOWN に開示する。",
        )
    }
}

/** JSON Lines を組み立てる出力ライター。1 行 1 対象 + cohort 集計 + run summary を書く。 */
class ReplayJsonLinesWriter(private val sink: (String) -> Unit) {
    private val json = Json { encodeDefaults = true }

    /** target 行を 1 行書く。 */
    fun writeTarget(line: ReplayTargetLine) {
        sink(json.encodeToString(line))
    }

    /** cohort 集計行を 1 行書く。 */
    fun writeCohortSummary(line: ReplayCohortSummaryLine) {
        sink(json.encodeToString(line))
    }

    /** run summary 行を 1 行書く。 */
    fun writeRunSummary(line: ReplayRunSummaryLine) {
        sink(json.encodeToString(line))
    }
}
