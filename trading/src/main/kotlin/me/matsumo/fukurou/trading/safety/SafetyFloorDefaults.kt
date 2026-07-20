package me.matsumo.fukurou.trading.safety

import java.math.BigDecimal

/**
 * SafetyFloor と保護 reconcile が共有する安全床の既定値。
 */
object SafetyFloorDefaults {

    /**
     * 安全床の閾値世代。
     *
     * margin 観測レコードに記録し、閾値変更の前後で母集団を切り分けるために使う。
     * 本 object の閾値定数を変更したときは、同じ変更で必ずこの値を更新する。
     * 定数の追加やリネームだけでは更新しない（閾値が変わっていないのに母集団が切れるため）。
     */
    const val policyVersion: String = "sfp_v1"

    /**
     * HARD_HALT を立てる drawdown。
     */
    val maxDrawdownRatio: BigDecimal = BigDecimal("-0.15")

    /**
     * 想定値幅 / 往復 cost の既定最小比率。
     */
    val minExpectedMoveToCostRatio: BigDecimal = BigDecimal("2.5")

    /**
     * ATR trailing stop に使う既定倍率。
     */
    val trailingAtrMultiplier: BigDecimal = BigDecimal("2.0")
}
