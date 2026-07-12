package me.matsumo.fukurou.trading.daemon

import me.matsumo.fukurou.trading.domain.Order
import java.time.Instant

/** daemon scheduler が参照する open risk 状態。 */
fun interface LlmDaemonOpenRiskReader {
    /** open position と open order の分類済み snapshot を返す。 */
    suspend fun snapshot(): Result<LlmDaemonOpenRiskSnapshot>
}

/**
 * daemon が full run 前に参照する open risk の分類済み snapshot。
 *
 * @param openPositionCount open position 数
 * @param restingEntryOrders position に紐づかない OPEN BUY entry order
 * @param otherOpenOrderCount entry 以外の open order 数
 */
data class LlmDaemonOpenRiskSnapshot(
    val openPositionCount: Int,
    val restingEntryOrders: List<Order>,
    val otherOpenOrderCount: Int,
) {
    /** open position または open order が存在するか。 */
    val hasOpenRisk: Boolean = openPositionCount > 0 || hasOpenOrders()

    /** resting entry だけが open risk として存在するか。 */
    val isRestingEntryOnly: Boolean = openPositionCount == 0 && hasOnlyRestingEntries()

    private fun hasOpenOrders(): Boolean = restingEntryOrders.isNotEmpty() || otherOpenOrderCount > 0

    private fun hasOnlyRestingEntries(): Boolean = restingEntryOrders.isNotEmpty() && otherOpenOrderCount == 0
}

/** resting-only tick を決定論的に監査する境界。 */
fun interface RestingOrderMaintenanceService {
    /** 現在も生存する resting entry を監査し、stable suppression reason を返す。 */
    suspend fun maintain(snapshot: LlmDaemonOpenRiskSnapshot, observedAt: Instant): Result<RestingSuppressionReason>
}

/** resting entry 中に full run を抑止した理由。 */
enum class RestingSuppressionReason(val wireCode: String) {
    RESTING_ORDER_UNCHANGED("resting_order_unchanged"),
    RESTING_ORDER_MATERIAL_CHANGED("resting_order_material_changed"),
    RESTING_ORDER_INVALIDATED("resting_order_invalidated"),
    RESTING_ORDER_QUOTE_UNAVAILABLE("resting_order_quote_unavailable"),
    RESTING_ORDER_IDENTITY_UNAVAILABLE("resting_order_identity_unavailable"),
    RESTING_ORDER_STATE_RACE("resting_order_state_race"),
}
