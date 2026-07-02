package me.matsumo.fukurou.trading.lock

import java.time.Instant

/**
 * global trading lock の取得情報。
 *
 * @param owner lock を取った論理 owner
 * @param acquiredAt lock 取得時刻
 */
data class TradingLockLease(
    val owner: String,
    val acquiredAt: Instant,
)

/**
 * trade 系 tool と ProtectionReconciler が共有する global trading lock。
 */
interface TradingLock {
    /**
     * global trading lock を取得して block を直列実行する。
     */
    suspend fun <T> withLock(owner: String, block: suspend (TradingLockLease) -> T): T
}
