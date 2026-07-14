package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.market.DurableIngressGapSource
import me.matsumo.fukurou.trading.market.DurableMarketEventIngress
import me.matsumo.fukurou.trading.market.IngressOperationDeadline
import me.matsumo.fukurou.trading.market.MarketStreamIdentity
import java.sql.Connection
import java.sql.SQLTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import javax.sql.DataSource

private val INGRESS_COMMIT_RESERVE: Duration = Duration.ofMillis(250)
private val INGRESS_LOCK_RETRY_DELAY: Duration = Duration.ofMillis(25)

/** PostgreSQL advisory authorityとprivate tableを使うdurable ingress repository。 */
class ExposedDurableMarketEventIngress(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : DurableMarketEventIngress {
    override suspend fun begin(
        sessionId: UUID,
        identity: MarketStreamIdentity,
        deadline: IngressOperationDeadline,
    ): Result<Unit> = mutate(deadline) { connection ->
        val now = Instant.now(clock).toEpochMilli()
        connection.prepareStatement(
            """
                INSERT INTO market_data_sessions (
                    id, state, connected_at, last_processed_sequence, last_transport_activity_at
                ) VALUES (?, 'DISCONNECTED', ?, 0, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setLong(2, now)
            statement.setLong(3, now)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
                INSERT INTO market_data_ingress_sessions (
                    session_id, provider, symbol, channel, state, last_received_sequence, starting_at
                ) VALUES (?, ?, ?, ?, 'STARTING', 0, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setString(2, identity.provider)
            statement.setString(3, identity.symbol)
            statement.setString(4, identity.channel)
            statement.setLong(5, now)
            statement.executeUpdate()
        }
    }

    override suspend fun activate(sessionId: UUID, deadline: IngressOperationDeadline): Result<Boolean> {
        return mutate(deadline) { connection ->
            val now = Instant.now(clock).toEpochMilli()
            val ingressUpdated = connection.prepareStatement(
                """
                    UPDATE market_data_ingress_sessions
                    SET state = 'CONNECTED', connected_at = ?
                    WHERE session_id = ? AND state = 'STARTING'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, now)
                statement.setObject(2, sessionId)
                statement.executeUpdate()
            }
            if (ingressUpdated == 0) return@mutate false

            val publicUpdated = connection.prepareStatement(
                """
                    UPDATE market_data_sessions
                    SET state = 'CONNECTED', connected_at = ?, disconnected_at = NULL, disconnect_reason = NULL
                    WHERE id = ? AND state = 'DISCONNECTED'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, now)
                statement.setObject(2, sessionId)
                statement.executeUpdate()
            }
            check(publicUpdated == 1) { "Public market-data session activation lost its conditional state." }
            true
        }
    }

    override suspend fun registerReceived(
        sessionId: UUID,
        sequence: Long,
        deadline: IngressOperationDeadline,
    ): Result<Boolean> {
        require(sequence > 0) { "sequence must be greater than 0." }

        return mutate(deadline) { connection ->
            connection.prepareStatement(
                """
                    UPDATE market_data_ingress_sessions
                    SET last_received_sequence = ?
                    WHERE session_id = ?
                      AND state = 'CONNECTED'
                      AND last_received_sequence = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, sequence)
                statement.setObject(2, sessionId)
                statement.setLong(3, sequence - 1)
                statement.executeUpdate() == 1
            }
        }
    }

    override suspend fun disconnect(
        sessionId: UUID,
        source: DurableIngressGapSource,
        deadline: IngressOperationDeadline,
    ): Result<Unit> = mutate(deadline) { connection ->
        val now = Instant.now(clock).toEpochMilli()
        connection.prepareStatement(
            """
                UPDATE market_data_ingress_sessions
                SET state = 'DISCONNECTED', disconnected_at = ?, disconnect_source = ?
                WHERE session_id = ? AND state IN ('STARTING', 'CONNECTED', 'STOPPING')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, now)
            statement.setString(2, source.name)
            statement.setObject(3, sessionId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
                UPDATE market_data_sessions
                SET state = 'DISCONNECTED', disconnected_at = ?, disconnect_reason = ?
                WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, now)
            statement.setString(2, source.name)
            statement.setObject(3, sessionId)
            statement.executeUpdate()
        }
    }

    private suspend fun <T> mutate(deadline: IngressOperationDeadline, mutation: (Connection) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                deadline.requireRemaining()
                dataSource.connection.use { connection ->
                    deadline.requireRemaining()
                    connection.autoCommit = false
                    armTimeouts(connection, deadline)
                    acquireAuthority(connection, deadline)

                    try {
                        val result = mutation(connection)
                        requireCommitBudget(deadline)
                        connection.commit()
                        deadline.requireRemaining()
                        result
                    } catch (throwable: Throwable) {
                        runCatching { connection.rollback() }
                        throw throwable
                    } finally {
                        releaseAuthority(connection)
                        deadline.requireRemaining()
                    }
                }
            }
        }

    private fun armTimeouts(connection: Connection, deadline: IngressOperationDeadline) {
        val operationMillis = deadline.requireRemaining().minus(INGRESS_COMMIT_RESERVE).toMillis()
        if (operationMillis <= 0) throw SQLTimeoutException("No durable ingress transaction budget remains.")

        connection.setNetworkTimeout(
            Executor { command -> command.run() },
            operationMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        connection.prepareStatement("SELECT set_config('lock_timeout', ?, TRUE)").use { statement ->
            statement.setString(1, "${operationMillis}ms")
            statement.executeQuery().close()
        }
        connection.prepareStatement("SELECT set_config('statement_timeout', ?, TRUE)").use { statement ->
            statement.setString(1, "${operationMillis}ms")
            statement.executeQuery().close()
        }
    }

    private fun acquireAuthority(connection: Connection, deadline: IngressOperationDeadline) {
        while (deadline.remaining() > INGRESS_COMMIT_RESERVE) {
            val acquired = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                statement.setLong(1, GLOBAL_TRADING_AUTHORITY_LOCK_KEY)
                statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
            }
            if (acquired) return
            Thread.sleep(INGRESS_LOCK_RETRY_DELAY.toMillis().coerceAtMost(deadline.remaining().toMillis()))
        }
        throw SQLTimeoutException("Timed out waiting for durable ingress authority.")
    }

    private fun releaseAuthority(connection: Connection) {
        connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
            statement.setLong(1, GLOBAL_TRADING_AUTHORITY_LOCK_KEY)
            statement.executeQuery().close()
        }
    }

    private fun requireCommitBudget(deadline: IngressOperationDeadline) {
        if (deadline.remaining() <= INGRESS_COMMIT_RESERVE) {
            throw SQLTimeoutException("No durable ingress commit budget remains.")
        }
    }
}
