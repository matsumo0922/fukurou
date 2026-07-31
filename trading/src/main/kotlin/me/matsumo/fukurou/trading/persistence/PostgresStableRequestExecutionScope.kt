package me.matsumo.fukurou.trading.persistence

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicEntryIdentity
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicEntryUnavailableException
import me.matsumo.fukurou.trading.broker.AuthorizedStableRequestExecutionScope
import me.matsumo.fukurou.trading.broker.AuthorizedStableRequestScope
import me.matsumo.fukurou.trading.broker.StableRequestMutexRegistry
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.math.ceil

internal const val STABLE_REQUEST_ADVISORY_NAMESPACE = 1_179_994_962
private val DIRECT_JDBC_CLEANUP_EXECUTOR = Executor { command -> command.run() }
private val DEFAULT_ACQUISITION_TIMEOUT: Duration = Duration.ofSeconds(30)
private val DEFAULT_HEARTBEAT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val DEFAULT_CLEANUP_TIMEOUT: Duration = Duration.ofSeconds(5)
private val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMillis(50)

/**
 * root-owned DataSourceを使うPostgreSQL stable request session scope。
 *
 * production rootへの配線は後続composition sliceが行う。
 */
internal class PostgresStableRequestExecutionScope(
    private val dataSource: HikariDataSource,
    private val mutexRegistry: StableRequestMutexRegistry,
    private val evictConnection: (Connection) -> Unit = { connection ->
        try {
            dataSource.evictConnection(connection)
        } finally {
            connection.close()
        }
    },
    private val cleanupExecutor: Executor = DIRECT_JDBC_CLEANUP_EXECUTOR,
    private val acquisitionTimeout: Duration = DEFAULT_ACQUISITION_TIMEOUT,
    private val heartbeatTimeout: Duration = DEFAULT_HEARTBEAT_TIMEOUT,
    private val cleanupTimeout: Duration = DEFAULT_CLEANUP_TIMEOUT,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val nanoTime: () -> Long = System::nanoTime,
    private val connectionBorrower: suspend ((Connection) -> Unit) -> Connection = { publish ->
        runInterruptible(Dispatchers.IO) {
            dataSource.connection.also(publish)
        }
    },
    private val afterLocalLeasePublished: suspend () -> Unit = {},
    private val afterConnectionPublished: suspend () -> Unit = {},
    private val afterAdvisoryNotAcquired: suspend () -> Unit = {},
) : AuthorizedStableRequestExecutionScope {
    override suspend fun <T> withScope(
        identity: AuthorizedAtomicEntryIdentity,
        block: suspend AuthorizedStableRequestScope.() -> T,
    ): T {
        identity.requireValid()
        val deadline = MonotonicDeadline.start(acquisitionTimeout, nanoTime)
        val localLease = acquireLocalLease(identity.clientRequestId, deadline)

        try {
            return withDedicatedScope(identity.clientRequestId, deadline, block)
        } finally {
            localLease.close()
        }
    }

    private suspend fun acquireLocalLease(
        clientRequestId: String,
        deadline: MonotonicDeadline,
    ): StableRequestMutexRegistry.Lease {
        var publishedLease: StableRequestMutexRegistry.Lease? = null

        return try {
            withTimeout(deadline.remainingMillis(nanoTime)) {
                mutexRegistry.acquire(clientRequestId).also { lease ->
                    publishedLease = lease
                    afterLocalLeasePublished()
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            publishedLease?.close()
            throw AuthorizedAtomicEntryUnavailableException(timeout)
        } catch (cancellation: CancellationException) {
            publishedLease?.close()
            throw cancellation
        } catch (failure: Throwable) {
            publishedLease?.close()
            throw AuthorizedAtomicEntryUnavailableException(failure)
        }
    }

    private suspend fun <T> withDedicatedScope(
        clientRequestId: String,
        deadline: MonotonicDeadline,
        block: suspend AuthorizedStableRequestScope.() -> T,
    ): T {
        val connection = borrowConnection(deadline)
        val requestHash = clientRequestId.stableRequestHash()
        var acquired = false
        var primaryFailure: Throwable? = null
        var scope: PostgresStableRequestScope? = null

        try {
            val backendPid = acquireAdvisory(connection, requestHash, deadline)
            acquired = true
            scope = PostgresStableRequestScope(connection, backendPid, requestHash)

            return scope.block()
        } catch (cancellation: CancellationException) {
            primaryFailure = cancellation
            if (!acquired) evict(connection)
            throw cancellation
        } catch (timeout: StableRequestLockTimeoutException) {
            primaryFailure = timeout
            connection.close()
            throw AuthorizedAtomicEntryUnavailableException(timeout)
        } catch (failure: Throwable) {
            primaryFailure = failure
            if (!acquired) evict(connection)
            throw failure
        } finally {
            if (acquired) {
                runCatching { releaseAdvisory(connection, requestHash) }
                    .onFailure { cleanupFailure ->
                        when {
                            primaryFailure != null -> primaryFailure.addSuppressed(cleanupFailure)
                            scope?.isBackendResultConfirmed == true -> Unit
                            else -> propagateCleanupFailure(cleanupFailure)
                        }
                    }
            }
        }
    }

    private suspend fun borrowConnection(deadline: MonotonicDeadline): Connection {
        var publishedConnection: Connection? = null

        return try {
            withTimeout(deadline.remainingMillis(nanoTime)) {
                val connection = connectionBorrower { borrowed -> publishedConnection = borrowed }
                afterConnectionPublished()

                connection
            }
        } catch (timeout: TimeoutCancellationException) {
            publishedConnection?.let(::evict)
            throw AuthorizedAtomicEntryUnavailableException(timeout)
        } catch (cancellation: CancellationException) {
            publishedConnection?.let(::evict)
            throw cancellation
        } catch (failure: Throwable) {
            publishedConnection?.let(::evict)
            throw AuthorizedAtomicEntryUnavailableException(failure)
        }
    }

    private suspend fun acquireAdvisory(
        connection: Connection,
        requestHash: Int,
        deadline: MonotonicDeadline,
    ): Int {
        while (true) {
            val acquisition = try {
                executeBoundedQuery(connection, deadline, TRY_LOCK_SQL) { statement ->
                    statement.setInt(1, STABLE_REQUEST_ADVISORY_NAMESPACE)
                    statement.setInt(2, requestHash)
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next())
                        AdvisoryAcquisition(
                            acquired = resultSet.getBoolean("acquired"),
                            backendPid = resultSet.getInt("backend_pid"),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                throw AuthorizedAtomicEntryUnavailableException(failure)
            }
            if (acquisition.acquired) return acquisition.backendPid
            afterAdvisoryNotAcquired()
            delay(deadline.requirePollDelayMillis(pollInterval, nanoTime))
        }
    }

    private suspend fun releaseAdvisory(connection: Connection, requestHash: Int) {
        val cleanupFailure = withContext(NonCancellable) {
            runCatching {
                val deadline = MonotonicDeadline.start(cleanupTimeout, nanoTime)
                val unlocked = executeBoundedQuery(connection, deadline, UNLOCK_SQL) { statement ->
                    statement.setInt(1, STABLE_REQUEST_ADVISORY_NAMESPACE)
                    statement.setInt(2, requestHash)
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next())
                        resultSet.getBoolean("unlocked")
                    }
                }
                check(unlocked) { "stable request advisory lock was not held." }
            }.exceptionOrNull()
        }
        if (cleanupFailure == null) {
            connection.close()
        } else {
            runCatching { connection.abort(cleanupExecutor) }
            evict(connection)
            throw AuthorizedAtomicEntryUnavailableException(cleanupFailure)
        }
    }

    private suspend fun <T> executeBoundedQuery(
        connection: Connection,
        deadline: MonotonicDeadline,
        sql: String,
        query: (PreparedStatement) -> T,
    ): T {
        val originalNetworkTimeout = connection.networkTimeout
        val remainingMillis = deadline.remainingMillis(nanoTime)
        val queryTimeoutSeconds = ceil(remainingMillis / 1_000.0)
            .toInt()
            .coerceAtLeast(1)
        connection.setNetworkTimeout(cleanupExecutor, remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val statement = connection.prepareStatement(sql)
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { failure ->
            if (failure is CancellationException) cleanupExecutor.execute { runCatching { statement.cancel() } }
        }

        return try {
            statement.use {
                statement.queryTimeout = queryTimeoutSeconds
                runInterruptible(Dispatchers.IO) { query(statement) }
            }
        } finally {
            cancellationHandle?.dispose()
            connection.setNetworkTimeout(cleanupExecutor, originalNetworkTimeout)
        }
    }

    private fun evict(connection: Connection) {
        runCatching { evictConnection(connection) }
    }

    private fun propagateCleanupFailure(cleanupFailure: Throwable): Nothing {
        throw cleanupFailure
    }

    private inner class PostgresStableRequestScope(
        private val connection: Connection,
        private val backendPid: Int,
        private val requestHash: Int,
    ) : AuthorizedStableRequestScope {
        var isBackendResultConfirmed: Boolean = false
            private set

        override suspend fun verifyOwnership(): Result<Unit> {
            val deadline = MonotonicDeadline.start(heartbeatTimeout, nanoTime)

            return runCatching {
                executeHeartbeat(connection, backendPid, requestHash, deadline)
            }.onFailure {
                runCatching { connection.abort(cleanupExecutor) }
                evict(connection)
            }
        }

        override fun markBackendResultConfirmed() {
            isBackendResultConfirmed = true
        }
    }

    private suspend fun executeHeartbeat(
        connection: Connection,
        expectedBackendPid: Int,
        requestHash: Int,
        deadline: MonotonicDeadline,
    ) {
        executeBoundedQuery(connection, deadline, HEARTBEAT_SQL) { statement ->
            statement.setInt(1, STABLE_REQUEST_ADVISORY_NAMESPACE)
            statement.setInt(2, requestHash)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                check(resultSet.getInt("backend_pid") == expectedBackendPid)
                check(resultSet.getBoolean("owned"))
            }
        }
    }

    private companion object {
        val TRY_LOCK_SQL: String = """
            SELECT pg_try_advisory_lock(?, ?) AS acquired,
                   pg_backend_pid() AS backend_pid
        """.trimIndent()
        val UNLOCK_SQL: String = """
            SELECT pg_advisory_unlock(?, ?) AS unlocked
        """.trimIndent()
        val HEARTBEAT_SQL: String = """
                SELECT pg_backend_pid() AS backend_pid,
                       EXISTS (
                           SELECT 1
                           FROM pg_locks
                           WHERE pid = pg_backend_pid()
                             AND locktype = 'advisory'
                             AND classid = ((?::bigint) & 4294967295)::oid
                             AND objid = ((?::bigint) & 4294967295)::oid
                             AND objsubid = 2
                             AND granted
                       ) AS owned
        """.trimIndent()
    }
}

internal fun String.stableRequestHash(): Int {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))

    return ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int
}

private data class AdvisoryAcquisition(
    val acquired: Boolean,
    val backendPid: Int,
)

internal class StableRequestLockTimeoutException : IllegalStateException(
    "stable request scope acquisition timed out.",
)

internal data class MonotonicDeadline(
    private val expiresAtNanos: Long,
) {
    fun remainingMillis(nanoTime: () -> Long): Long {
        val remainingNanos = expiresAtNanos - nanoTime()
        check(remainingNanos > 0) { "monotonic deadline elapsed." }

        return ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }

    fun requirePollDelayMillis(pollInterval: Duration, nanoTime: () -> Long): Long {
        val remainingNanos = expiresAtNanos - nanoTime()
        if (remainingNanos <= 0) throw StableRequestLockTimeoutException()
        val remainingMillis = ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)

        return minOf(pollInterval.toMillis(), remainingMillis)
    }

    companion object {
        fun start(duration: Duration, nanoTime: () -> Long): MonotonicDeadline {
            require(!duration.isZero && !duration.isNegative)

            return MonotonicDeadline(Math.addExact(nanoTime(), duration.toNanos()))
        }
    }
}
