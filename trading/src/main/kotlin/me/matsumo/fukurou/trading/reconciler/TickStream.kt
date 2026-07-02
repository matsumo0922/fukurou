package me.matsumo.fukurou.trading.reconciler

import java.time.Instant

/**
 * Reconciler が参照する直近 tick snapshot。
 *
 * @param symbol 取引対象 symbol
 * @param observedAt tick 観測時刻
 */
data class TickSnapshot(
    val symbol: String,
    val observedAt: Instant,
)

/**
 * ProtectionReconciler が市場データを受け取る抽象。
 */
interface TickStream {
    /**
     * 直近 tick を返す。Step1.5 では null を許容する骨格に留める。
     */
    suspend fun latestTick(): Result<TickSnapshot?>
}

/**
 * Step1.5 の空 TickStream。
 */
object EmptyTickStream : TickStream {
    override suspend fun latestTick(): Result<TickSnapshot?> {
        return Result.success(null)
    }
}
