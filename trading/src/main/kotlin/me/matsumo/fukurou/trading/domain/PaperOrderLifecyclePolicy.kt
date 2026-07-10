package me.matsumo.fukurou.trading.domain

import java.time.Duration
import java.time.Instant

/** paper order の取消理由。DB には [wireCode] だけを保存する。 */
enum class PaperOrderCancelReason(val wireCode: String) {
    /** resting entry の実効期限到達。 */
    TTL_EXPIRY("resting_entry_order_ttl_expired"),

    /** cancel_order による明示取消。 */
    EXPLICIT_CANCEL("explicit_cancel"),

    /** expiresAt を持たない旧方式 order の runner TTL sweep。 */
    LEGACY_TTL_SWEEP("legacy_ttl_sweep"),

    /** position close に伴う protective order 取消。 */
    POSITION_CLOSE("position_close"),

    /** HARD_HALT sweep による取消。 */
    HARD_HALT("hard_halt"),

    /** enum 導入前の自由記述値で理由を確定できない取消。 */
    LEGACY_UNCLASSIFIED("legacy_unclassified"),
    ;

    companion object {
        /** DB wire code を domain 値へ変換する。 */
        fun fromWireCode(value: String): PaperOrderCancelReason {
            return entries.firstOrNull { reason -> reason.wireCode == value }
                ?: error("Unknown paper order cancel reason: $value")
        }
    }
}

/** resting entry の期限到達後 lifecycle。 */
enum class RestingEntryExpiryState {
    /** 期限前。 */
    ACTIVE,

    /** 期限到達済みだが reconciler の通常処理猶予内。 */
    EXPIRING,

    /** 通常処理猶予を超えても未取消。 */
    OVERDUE,
}

/**
 * paper resting entry と期限処理の共有 policy。
 *
 * role は BUY、position 未紐付け、LIMIT/STOP の組み合わせで決まる。status は用途別に判定する。
 */
object PaperOrderLifecyclePolicy {
    /** ProtectionReconciler の標準 polling 間隔。 */
    val reconcilerInterval: Duration = Duration.ofSeconds(5)

    /** 期限到達後に通常の取消処理を待つ猶予。 */
    val cancellationGrace: Duration = reconcilerInterval.multipliedBy(2)

    /** resting entry role の side。 */
    val restingEntrySide: OrderSide = OrderSide.BUY

    /** resting entry role の order type。 */
    val restingEntryTypes: Set<OrderType> = setOf(OrderType.LIMIT, OrderType.STOP)

    /** lifecycle が処理する status。 */
    val lifecycleStatuses: Set<OrderStatus> = setOf(OrderStatus.OPEN, OrderStatus.PENDING_CANCEL)

    /** Activity が約定待ちとして扱う status。 */
    val waitingStatuses: Set<OrderStatus> = setOf(OrderStatus.OPEN)

    /** server 時刻における期限状態を返す。 */
    fun expiryState(expiresAt: Instant, now: Instant): RestingEntryExpiryState {
        if (now.isBefore(expiresAt)) return RestingEntryExpiryState.ACTIVE

        val graceEndsAt = expiresAt.plus(cancellationGrace)
        return if (now.isAfter(graceEndsAt)) RestingEntryExpiryState.OVERDUE else RestingEntryExpiryState.EXPIRING
    }
}

/** resting entry の role 条件に一致するかを返す。 */
fun Order.hasRestingEntryRole(): Boolean {
    return side == PaperOrderLifecyclePolicy.restingEntrySide &&
        positionId == null &&
        orderType in PaperOrderLifecyclePolicy.restingEntryTypes
}

/** lifecycle の処理対象 resting entry かを返す。 */
fun Order.isRestingEntryLifecycleCandidate(): Boolean {
    return hasRestingEntryRole() && status in PaperOrderLifecyclePolicy.lifecycleStatuses
}

/** Activity の約定待ち対象 resting entry かを返す。 */
fun Order.isWaitingRestingEntry(): Boolean {
    return hasRestingEntryRole() && status in PaperOrderLifecyclePolicy.waitingStatuses
}

/** TTL取消の monitoring delay を返す。 */
fun Order.ttlCancellationDelay(): Duration? {
    if (cancelReason != PaperOrderCancelReason.TTL_EXPIRY) return null

    val logicalExpiry = expiredAt?.let(Instant::parse) ?: return null
    val processedAt = canceledAt?.let(Instant::parse) ?: return null
    return Duration.between(logicalExpiry, processedAt).coerceAtLeast(Duration.ZERO)
}
