package me.matsumo.fukurou.trading.risk

import java.math.BigDecimal
import java.time.Instant

/**
 * risk_state single row が保持する取引停止状態。
 */
enum class RiskHaltState {
    /**
     * 通常稼働中。
     */
    RUNNING,

    /**
     * 新規 entry だけを停止する運用停止状態。
     */
    SOFT_HALT,

    /**
     * 全 trade 系操作と daemon 起動を停止する緊急停止状態。
     */
    HARD_HALT,
}

/**
 * risk_state single row が保持する DB 上の安全状態。
 *
 * @param state 現在の取引停止状態
 * @param drawdownRatio 現在の drawdown ratio
 * @param equityPeak equity の過去ピーク
 * @param haltReason halt 化した理由
 * @param haltAt halt 化した時刻
 * @param resumedAt 手動再開した時刻
 * @param resumedReason 手動再開理由
 * @param updatedAt 最終更新時刻
 */
data class RiskState(
    val state: RiskHaltState = RiskHaltState.RUNNING,
    val drawdownRatio: BigDecimal = BigDecimal.ZERO,
    val equityPeak: BigDecimal = BigDecimal.ZERO,
    val haltReason: String? = null,
    val haltAt: Instant? = null,
    val resumedAt: Instant? = null,
    val resumedReason: String? = null,
    val updatedAt: Instant,
)

/**
 * HARD_HALT により trade 系 tool を拒否したことを表す例外。
 */
class HardHaltTradingRejectedException(
    message: String,
) : RuntimeException(message)

/**
 * HARD_HALT から SOFT_HALT へ downgrade しようとしたことを表す例外。
 */
class SoftHaltDowngradeRejectedException(
    message: String = "SOFT_HALT cannot downgrade HARD_HALT.",
) : RuntimeException(message)
