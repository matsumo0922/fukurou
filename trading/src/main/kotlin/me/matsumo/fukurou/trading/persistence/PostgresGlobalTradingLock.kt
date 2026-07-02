package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.lock.TradingLockLease
import java.sql.Connection
import java.sql.SQLTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * fukurou 全体で共有する Postgres advisory lock key。
 */
private const val TRADING_LOCK_KEY = 9_206_202_601L

/**
 * Postgres advisory lock の取得を試みる SQL。
 */
private const val TRY_ADVISORY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)"

/**
 * Postgres advisory lock を解放する SQL。
 */
private const val RELEASE_ADVISORY_LOCK_SQL = "SELECT pg_advisory_unlock(?)"

/**
 * advisory lock 取得の既定 timeout。
 */
private val DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(5)

/**
 * advisory lock polling の既定間隔。
 */
private val DEFAULT_LOCK_RETRY_DELAY = Duration.ofMillis(100)

/**
 * PostgresGlobalTradingLock 用 logger。
 */
private val POSTGRES_LOCK_LOGGER = Logger.getLogger(PostgresGlobalTradingLock::class.java.name)

/**
 * Postgres advisory lock で実装した global trading lock。
 *
 * @param dataSource Postgres DataSource
 * @param clock lock 取得時刻に使う clock
 * @param lockKey advisory lock key
 * @param lockTimeout lock 取得を待つ最大時間
 * @param lockRetryDelay lock 取得 retry 間隔
 */
class PostgresGlobalTradingLock(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val lockKey: Long = TRADING_LOCK_KEY,
    private val lockTimeout: Duration = DEFAULT_LOCK_TIMEOUT,
    private val lockRetryDelay: Duration = DEFAULT_LOCK_RETRY_DELAY,
) : TradingLock {

    override suspend fun <T> withLock(owner: String, block: suspend (TradingLockLease) -> T): T {
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                acquireAdvisoryLock(connection, owner)

                try {
                    block(
                        TradingLockLease(
                            owner = owner,
                            acquiredAt = Instant.now(clock),
                        ),
                    )
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        releaseAdvisoryLock(connection, owner)
                    }
                }
            }
        }
    }

    private fun acquireAdvisoryLock(connection: Connection, owner: String) {
        val timeoutNanos = lockTimeout.toNanos()
        val startedAtNanos = System.nanoTime()

        while (true) {
            if (tryAcquireAdvisoryLock(connection)) {
                return
            }

            val elapsedNanos = System.nanoTime() - startedAtNanos

            if (elapsedNanos >= timeoutNanos) {
                val exception = SQLTimeoutException(
                    "Timed out waiting for Postgres advisory lock owner=$owner timeout=$lockTimeout.",
                )
                POSTGRES_LOCK_LOGGER.log(
                    Level.WARNING,
                    "Timed out waiting for Postgres advisory lock.",
                    exception,
                )
                throw exception
            }

            sleepBeforeRetry(owner)
        }
    }

    private fun tryAcquireAdvisoryLock(connection: Connection): Boolean {
        return connection.prepareStatement(TRY_ADVISORY_LOCK_SQL).use { statement ->
            statement.setLong(1, lockKey)
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "pg_try_advisory_lock returned no rows." }

                resultSet.getBoolean(1)
            }
        }
    }

    private fun releaseAdvisoryLock(connection: Connection, owner: String) {
        val released = connection.prepareStatement(RELEASE_ADVISORY_LOCK_SQL).use { statement ->
            statement.setLong(1, lockKey)
            statement.executeQuery().use { resultSet ->
                require(resultSet.next()) { "pg_advisory_unlock returned no rows." }

                resultSet.getBoolean(1)
            }
        }

        if (!released) {
            POSTGRES_LOCK_LOGGER.warning("Postgres advisory lock was not held at release. owner=$owner")
        }
    }

    private fun sleepBeforeRetry(owner: String) {
        try {
            Thread.sleep(lockRetryDelay.toMillis())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()

            throw SQLTimeoutException(
                "Interrupted while waiting for Postgres advisory lock. owner=$owner",
                interrupted,
            )
        }
    }
}
