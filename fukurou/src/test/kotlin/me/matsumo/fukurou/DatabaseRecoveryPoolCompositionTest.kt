package me.matsumo.fukurou

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.runner.LlmExecutionRecoveryService
import me.matsumo.fukurou.trading.runner.OneShotExecutionPolicy
import org.testcontainers.DockerClientFactory
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/** Application production poolとrecovery worker compositionのdeadlineを検証する。 */
class DatabaseRecoveryPoolCompositionTest {
    @Test
    fun exhaustedApplicationPoolFailsTickWithinBudgetAndSameServiceRetryConverges() = runBlocking {
        if (!isDockerAvailable()) return@runBlocking

        ProductionPoolPostgresContainer().use { container ->
            container.start()
            val dataSource = createDataSource(DatabaseConfig(container.jdbcUrl, container.username, container.password))
            val database = ExposedDatabase.connect(dataSource)
            val now = Instant.parse("2026-07-14T00:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)

            try {
                assertEquals(DATABASE_CONNECTION_TIMEOUT_MILLIS, dataSource.connectionTimeout)
                TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
                val repository = me.matsumo.fukurou.trading.persistence.ExposedLlmLaunchReservationRepository(database)
                assertIs<LlmLaunchReservationOutcome.Reserved>(
                    repository.tryReserve(recoveryReservationRequest(now.minusSeconds(1_800))).getOrThrow(),
                )
                val service = LlmExecutionRecoveryService(
                    repository = repository,
                    policy = OneShotExecutionPolicy.from(LlmRunnerConfig()),
                    clock = clock,
                )
                val heldConnections = holdEveryPoolConnection(dataSource.maximumPoolSize) { dataSource.connection }
                val startedAt = System.nanoTime()

                val firstAttempt = service.tick()
                val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

                assertTrue(firstAttempt.isFailure)
                assertTrue(elapsed < Duration.ofMillis(5_750), "pool exhaustion elapsed=$elapsed")
                assertFalse(LlmExecutionAdmissionHealth.isHealthy())
                heldConnections.forEach(Connection::close)
                assertEquals(
                    LlmLaunchReservationStatus.RUNNING,
                    repository.findExecutionClaim(RECOVERY_INVOCATION_ID).getOrThrow()?.status,
                )

                assertEquals(1, service.tick().getOrThrow())
                assertTrue(LlmExecutionAdmissionHealth.isHealthy())
                assertEquals(
                    LlmLaunchReservationStatus.FAILED,
                    repository.findExecutionClaim(RECOVERY_INVOCATION_ID).getOrThrow()?.status,
                )
            } finally {
                dataSource.close()
            }
        }
    }
}

private fun recoveryReservationRequest(reservedAt: Instant): LlmLaunchReservationRequest {
    return LlmLaunchReservationRequest(
        invocationId = RECOVERY_INVOCATION_ID,
        triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
        triggerKey = RECOVERY_INVOCATION_ID,
        reservedAt = reservedAt,
        runnerConfig = LlmRunnerConfig(),
        hourlyWindow = Duration.ofHours(1),
        dailyWindow = Duration.ofDays(1),
        activeReservationStaleAfter = Duration.ofMinutes(30),
    )
}

private fun holdEveryPoolConnection(poolSize: Int, acquire: () -> Connection): List<Connection> {
    return List(poolSize) { acquire() }
}

private fun isDockerAvailable(): Boolean {
    return runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
}

private class ProductionPoolPostgresContainer : BoundedTestPostgresContainer<ProductionPoolPostgresContainer>(
    "postgres:16-alpine",
)

private const val RECOVERY_INVOCATION_ID = "production-pool-recovery"
