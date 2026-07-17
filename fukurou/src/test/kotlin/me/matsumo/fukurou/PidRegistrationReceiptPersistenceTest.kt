package me.matsumo.fukurou

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.persistence.ExposedLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.DockerClientFactory
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** final PID receipt CLI と reservation terminal lifecycle の実 PostgreSQL contract。 */
class PidRegistrationReceiptPersistenceTest {
    @Test
    fun finalEightArgumentReceiptActivatesExactProviderAndMcpThenFinishesWithActiveZero() = runBlocking {
        if (!receiptDockerAvailable()) return@runBlocking
        val container = ReceiptPostgresContainer().also { it.start() }
        try {
            val database = Database.connect(container.jdbcUrl, "org.postgresql.Driver", container.username, container.password)
            val clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC)
            TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
            val invocationId = "receipt-e2e-fixture"
            val reservationId = UUID.fromString("02729bd6-add4-67a2-6790-6e36ad77cb00")
            val providerId = UUID.fromString("d168f815-9f99-90ef-15fa-3853e0742173")
            val mcpId = UUID.fromString("f02b4431-a53e-e39d-8d42-d7dba634db87")
            transaction(database) {
                jdbcConnection().prepareStatement(
                    """
                        INSERT INTO llm_launch_reservations(
                            id,invocation_id,trigger_kind,trigger_key,status,reserved_at
                        ) VALUES (?,?,'MANUAL','receipt:e2e','RUNNING',?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, reservationId)
                    statement.setString(2, invocationId)
                    statement.setLong(3, clock.instant().toEpochMilli())
                    statement.executeUpdate()
                }
                jdbcConnection().prepareStatement(
                    """
                        INSERT INTO llm_pid_registrations(
                            registration_id,invocation_id,reservation_id,role,container_instance_id,state
                        ) VALUES (?,?,?,'PROVIDER','receipt-fixture','SPAWN_RESERVED')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, providerId)
                    statement.setString(2, invocationId)
                    statement.setObject(3, reservationId)
                    statement.executeUpdate()
                }
            }
            val connectionProvider = {
                DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
            }

            PidRegistrationReceiptMain.applyReceipt(
                arrayOf("PROVIDER", providerId.toString(), reservationId.toString(), invocationId, "receipt-fixture", "42", "4242", "99"),
                connectionProvider,
            )
            PidRegistrationReceiptMain.applyReceipt(
                arrayOf("MCP", mcpId.toString(), reservationId.toString(), invocationId, "receipt-fixture", "42", "4343", "100"),
                connectionProvider,
            )

            val finish = LlmLaunchReservationFinish(
                invocationId = invocationId,
                status = LlmLaunchReservationStatus.FINISHED,
                reason = "NO_TRADE_DECISION",
                finishedAt = clock.instant().plusSeconds(1),
            )
            ExposedLlmLaunchReservationRepository(database).finish(finish).getOrThrow()
            ExposedLlmLaunchReservationRepository(database).finish(finish).getOrThrow()

            transaction(database) {
                jdbcConnection().prepareStatement(
                    """
                        SELECT array_agg(registration_id ORDER BY role) AS ids,
                            COUNT(*) FILTER (WHERE state='TERMINAL') AS terminal_count,
                            COUNT(*) FILTER (WHERE state IN ('SPAWN_RESERVED','ACTIVE')) AS active_count
                        FROM llm_pid_registrations WHERE invocation_id=?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, invocationId)
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        val ids = (result.getArray("ids").array as Array<*>).map { it.toString() }.toSet()
                        assertEquals(setOf(providerId.toString(), mcpId.toString()), ids)
                        assertEquals(2, result.getInt("terminal_count"))
                        assertEquals(0, result.getInt("active_count"))
                    }
                }
            }
        } finally {
            container.stop()
        }
    }
}

private class ReceiptPostgresContainer :
    BoundedTestPostgresContainer<ReceiptPostgresContainer>("postgres:16-alpine")

private fun JdbcTransaction.jdbcConnection(): java.sql.Connection {
    return connection.connection as java.sql.Connection
}

private fun receiptDockerAvailable(): Boolean = runCatching {
    DockerClientFactory.instance().client().pingCmd().exec()
    true
}.getOrDefault(false)
