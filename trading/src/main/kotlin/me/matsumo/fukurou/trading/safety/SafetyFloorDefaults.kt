package me.matsumo.fukurou.trading.safety

import java.math.BigDecimal

/**
 * SafetyFloor と保護 reconcile が共有する安全床の既定値。
 */
object SafetyFloorDefaults {

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
