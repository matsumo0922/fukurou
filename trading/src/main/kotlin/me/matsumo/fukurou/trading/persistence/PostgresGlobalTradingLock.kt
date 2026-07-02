package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.lock.TradingLockLease
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

/**
 * fukurou 全体で共有する Postgres advisory lock key。
 */
private const val TRADING_LOCK_KEY = 9_206_202_601L

/**
 * Postgres advisory lock を取る SQL。
 */
private const val ACQUIRE_ADVISORY_LOCK_SQL = "SELECT pg_advisory_lock(?)"

/**
 * Postgres advisory lock を解放する SQL。
 */
private const val RELEASE_ADVISORY_LOCK_SQL = "SELECT pg_advisory_unlock(?)"

/**
 * Postgres advisory lock で実装した global trading lock。
 *
 * @param dataSource Postgres DataSource
 * @param clock lock 取得時刻に使う clock
 * @param lockKey advisory lock key
 */
class PostgresGlobalTradingLock(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val lockKey: Long = TRADING_LOCK_KEY,
) : TradingLock {

    override suspend fun <T> withLock(owner: String, block: suspend (TradingLockLease) -> T): T {
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(ACQUIRE_ADVISORY_LOCK_SQL).use { statement ->
                    statement.setLong(1, lockKey)
                    statement.execute()
                }

                try {
                    block(
                        TradingLockLease(
                            owner = owner,
                            acquiredAt = Instant.now(clock),
                        ),
                    )
                } finally {
                    connection.prepareStatement(RELEASE_ADVISORY_LOCK_SQL).use { statement ->
                        statement.setLong(1, lockKey)
                        statement.execute()
                    }
                }
            }
        }
    }
}
