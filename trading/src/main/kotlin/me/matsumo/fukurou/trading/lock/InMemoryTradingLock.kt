package me.matsumo.fukurou.trading.lock

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

/**
 * unit test と DB 未構成時のための in-memory global trading lock。
 *
 * @param clock lock 取得時刻に使う clock
 */
class InMemoryTradingLock(
    private val clock: Clock = Clock.systemUTC(),
) : TradingLock {

    private val mutex = Mutex()

    override suspend fun <T> withLock(owner: String, block: suspend (TradingLockLease) -> T): T {
        return mutex.withLock {
            block(
                TradingLockLease(
                    owner = owner,
                    acquiredAt = Instant.now(clock),
                ),
            )
        }
    }
}
