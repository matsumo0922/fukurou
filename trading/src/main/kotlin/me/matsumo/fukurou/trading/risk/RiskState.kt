package me.matsumo.fukurou.trading.risk

import java.math.BigDecimal
import java.time.Instant

/**
 * risk_state single row が保持する DB 上の安全状態。
 *
 * @param hardHalt 新規取引を含む全操作を止める sticky halt
 * @param drawdownRatio 現在の drawdown ratio
 * @param equityPeak equity の過去ピーク
 * @param haltReason HARD_HALT 化した理由
 * @param haltAt HARD_HALT 化した時刻
 * @param resumedAt 手動再開した時刻
 * @param resumedReason 手動再開理由
 * @param updatedAt 最終更新時刻
 */
data class RiskState(
    val hardHalt: Boolean = false,
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
