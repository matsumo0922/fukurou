package me.matsumo.fukurou.trading.safety

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * SafetyFloor の評価経路。
 */
enum class SafetyFloorEvaluationPath {
    /** place_order / preview_order が通る entry 評価。 */
    PLACE_ORDER,
}

/**
 * SafetyFloor 評価の呼び出し地点。
 *
 * `evaluatePlaceOrder` は preview と実注文の双方から呼ばれるため、両者を区別する。
 */
enum class SafetyFloorCallSite {
    /** preview_order。副作用を持たない。 */
    PREVIEW,

    /** place_order。拒否時に HARD_HALT と掃引を伴いうる。 */
    PLACE,
}

/**
 * evaluation point の判定結果。
 */
enum class RuleStatus {
    /** 閾値を満たした。 */
    PASS,

    /** 閾値に違反した。 */
    FAIL,

    /** 構造的に評価できなかった。PASS として扱ってはならない。 */
    NA,
}

/**
 * evaluation point が `NA` になった理由。
 */
enum class NaReason {
    /** その評価経路の対象ではない。 */
    NOT_APPLICABLE_PATH,

    /** 判定に必要な入力が存在しない。 */
    MISSING_INPUT,

    /** 別の条件の成立を前提としており、その前提が崩れている。 */
    PRECONDITION_UNMET,

    /** 評価が例外で終了した。 */
    EVALUATION_ERROR,
}

/**
 * margin の単位。
 */
enum class MarginUnit {
    /** 円。 */
    JPY,

    /** 円 / BTC。価格差に使う。 */
    JPY_PER_BTC,

    /** 比率。 */
    RATIO,

    /** R 倍。 */
    R,

    /** 件数。 */
    COUNT,

    /** 秒。 */
    SECONDS,
}

/**
 * evaluation point の識別子。
 *
 * 1 つの rule が複数の validator から出る場合と、複数の独立した条件を持つ場合があるため、
 * 観測の単位は rule ではなく evaluation point とする。
 *
 * @param rule 対応する SafetyFloor rule
 * @param pointId 同一 rule 内で条件を区別する識別子。単一条件の rule は "default"
 * @param marginUnit 数値 margin の単位。margin を持たない point は null
 */
data class EvaluationPointId(
    val rule: SafetyFloorRule,
    val pointId: String = DEFAULT_POINT_ID,
    val marginUnit: MarginUnit? = null,
) {
    /** margin を持つ point か。 */
    val isNumeric: Boolean get() = marginUnit != null

    companion object {
        /** 単一条件の rule が使う point 識別子。 */
        const val DEFAULT_POINT_ID: String = "default"
    }
}

/**
 * 1 evaluation point の観測結果。
 *
 * @param point 観測した evaluation point
 * @param status 判定結果
 * @param naReason `NA` の理由。`NA` 以外では null
 * @param marginValue 閾値までの距離。正が余裕、負が違反量。BOOLEAN な point と `NA` では null
 */
data class RuleObservation(
    val point: EvaluationPointId,
    val status: RuleStatus,
    val naReason: NaReason? = null,
    val marginValue: BigDecimal? = null,
) {
    init {
        require((status == RuleStatus.NA) == (naReason != null)) {
            "naReason must be present exactly when status is NA."
        }
        require(marginValue == null || point.isNumeric) {
            "marginValue is only allowed for numeric evaluation points."
        }
        require(marginValue == null || status != RuleStatus.NA) {
            "marginValue must be absent when status is NA."
        }
    }
}

/**
 * 1 decision 分の観測。
 *
 * @param id レコード ID
 * @param path 評価経路
 * @param callSite 呼び出し地点
 * @param decisionRunId decision run ID
 * @param commandId command ID
 * @param verdict SafetyFloor の判定
 * @param rejectedRule 拒否された rule。通過時は null
 * @param policyVersion SafetyFloor policy version
 * @param runtimeConfigVersion 評価時点の runtime config version。取得できない場合は null
 * @param observationSchemaVersion 観測が保持する情報の世代
 * @param divergence verdict と観測の整合が破れたか
 * @param collectionFailed 観測の算出が全面的に失敗したか
 * @param observations evaluation point ごとの観測結果
 * @param observedAt 観測時刻
 */
data class SafetyFloorObservationReport(
    val id: UUID,
    val path: SafetyFloorEvaluationPath,
    val callSite: SafetyFloorCallSite,
    val decisionRunId: String?,
    val commandId: UUID,
    val verdict: ObservedVerdict,
    val rejectedRule: SafetyFloorRule?,
    val policyVersion: String,
    val runtimeConfigVersion: String?,
    val observationSchemaVersion: Int,
    val divergence: Boolean,
    val collectionFailed: Boolean,
    val observations: List<RuleObservation>,
    val observedAt: Instant,
) {
    init {
        require((verdict == ObservedVerdict.REJECTED) == (rejectedRule != null)) {
            "rejectedRule must be present exactly when verdict is REJECTED."
        }
        require(collectionFailed || observations.isNotEmpty()) {
            "observations must be present unless collection failed."
        }
    }
}

/**
 * 観測レコードに保存する verdict。
 */
enum class ObservedVerdict {
    /** 安全床を通過した。 */
    ACCEPTED,

    /** 安全床により拒否された。 */
    REJECTED,
}
