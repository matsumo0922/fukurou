package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.persistence.ExposedLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** migration failure 時の application fail-closed composition を検証する。 */
class ApplicationMigrationFailureTest {

    @Test
    fun missingSingleAttemptColumnLockTimesOutBeforeFirstDdlAndKeepsApplicationFailClosed() {
        assertEconomicEventMigrationFailure(EconomicEventMigrationFailureFixture.MISSING_COLUMN_LOCK)
    }

    @Test
    fun invalidSingleAttemptIndexKeepsApplicationFailClosedWithoutRepair() {
        assertEconomicEventMigrationFailure(EconomicEventMigrationFailureFixture.INVALID_INDEX)
    }

    private fun assertEconomicEventMigrationFailure(fixture: EconomicEventMigrationFailureFixture) {
        if (!applicationMigrationDockerAvailable()) {
            println("Skipping migration failure application test because Docker is unavailable.")
            return
        }

        val container = ApplicationMigrationPostgresContainer()
        container.start()
        val databaseConfig = DatabaseConfig(container.jdbcUrl, container.username, container.password)
        val database = ExposedDatabase.connect(
            url = databaseConfig.url,
            driver = "org.postgresql.Driver",
            user = databaseConfig.user,
            password = databaseConfig.password,
        )
        val clock = Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC)
        val invocationId = "application-migration-lock"
        val triggerKey = "economic-event:application-migration:${clock.instant()}"
        var lockConnection: Connection? = null

        try {
            TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
            val repository = ExposedLlmLaunchReservationRepository(database)
            val reservation = runBlocking {
                repository.tryReserve(
                    LlmLaunchReservationRequest(
                        invocationId = invocationId,
                        triggerKind = LlmDaemonTriggerKind.ECONOMIC_EVENT,
                        triggerKey = triggerKey,
                        reservedAt = clock.instant(),
                        runnerConfig = LlmRunnerConfig(),
                        hourlyWindow = Duration.ofHours(1),
                        dailyWindow = Duration.ofHours(24),
                        activeReservationStaleAfter = Duration.ofMinutes(30),
                        singleAttemptKey = "ECONOMIC_EVENT:$triggerKey",
                    ),
                ).getOrThrow()
            }
            assertIs<LlmLaunchReservationOutcome.Reserved>(reservation)
            runBlocking {
                repository.finish(
                    LlmLaunchReservationFinish(
                        invocationId = invocationId,
                        status = LlmLaunchReservationStatus.FAILED,
                        reason = "legacy fixture",
                        finishedAt = clock.instant().plusSeconds(1),
                    ),
                ).getOrThrow()
            }
            exposedTransaction(database) {
                when (fixture) {
                    EconomicEventMigrationFailureFixture.MISSING_COLUMN_LOCK -> {
                        executeMigrationFixtureUpdate(
                            "ALTER TABLE llm_launch_reservations DROP COLUMN single_attempt_key",
                        )
                    }

                    EconomicEventMigrationFailureFixture.INVALID_INDEX -> {
                        executeMigrationFixtureUpdate(
                            "DROP INDEX idx_llm_launch_reservations_single_attempt_key_unique",
                        )
                        executeMigrationFixtureUpdate(
                            "ALTER TABLE llm_launch_reservations DISABLE TRIGGER USER",
                        )
                        executeMigrationFixtureUpdate(
                            "INSERT INTO llm_launch_reservations (" +
                                "id, invocation_id, trigger_kind, trigger_key, single_attempt_key, " +
                                "status, reserved_at, finished_at, reason" +
                                ") SELECT gen_random_uuid(), 'application-invalid-index-duplicate', " +
                                "trigger_kind, trigger_key, single_attempt_key, status, " +
                                "reserved_at + 1, finished_at, reason FROM llm_launch_reservations " +
                                "WHERE invocation_id = 'application-migration-lock'",
                        )
                        executeMigrationFixtureUpdate(
                            "ALTER TABLE llm_launch_reservations ENABLE TRIGGER USER",
                        )
                    }
                }
            }

            if (fixture == EconomicEventMigrationFailureFixture.INVALID_INDEX) {
                createInvalidSingleAttemptIndex(container)
            }

            if (fixture == EconomicEventMigrationFailureFixture.MISSING_COLUMN_LOCK) {
                lockConnection = java.sql.DriverManager.getConnection(
                    container.jdbcUrl,
                    container.username,
                    container.password,
                ).apply { autoCommit = false }
                lockConnection.createStatement().use { statement ->
                    statement.execute("LOCK TABLE llm_launch_reservations IN ACCESS SHARE MODE")
                }
            }
            val reconcilerStatus = MutableReconcilerStatus()
            var requestElapsed = Duration.ZERO

            testApplication {
                application {
                    module(
                        clock = clock,
                        reconcilerStatus = reconcilerStatus,
                        tradingConfig = TradingBotConfig(),
                        databaseConfig = databaseConfig,
                    )
                }

                val requestStartedAt = System.nanoTime()
                val response = client.get("/health/ready")
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)

                requestElapsed = Duration.ofNanos(System.nanoTime() - requestStartedAt)
            }

            assertTrue(requestElapsed < Duration.ofSeconds(6), "request elapsed=$requestElapsed")
            assertFalse(reconcilerStatus.snapshot().startupFullReconcileCompleted)
            assertEquals(0, countWorkerStartedEvents(database))

            when (fixture) {
                EconomicEventMigrationFailureFixture.MISSING_COLUMN_LOCK -> {
                    assertTrue(requestElapsed >= Duration.ofMillis(1_500), "request elapsed=$requestElapsed")
                    assertFalse(singleAttemptMigrationColumnExists(database))
                    assertFalse(singleAttemptMigrationIndexExists(database))
                }

                EconomicEventMigrationFailureFixture.INVALID_INDEX -> {
                    assertFalse(singleAttemptMigrationIndexIsValid(database))
                    assertEquals(2, countBackfilledAttemptKeys(database))
                }
            }
        } finally {
            lockConnection?.rollback()
            lockConnection?.close()
            container.stop()
        }
    }
}

private fun createInvalidSingleAttemptIndex(container: ApplicationMigrationPostgresContainer) {
    java.sql.DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val result = runCatching {
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE UNIQUE INDEX CONCURRENTLY " +
                        "idx_llm_launch_reservations_single_attempt_key_unique " +
                        "ON llm_launch_reservations (single_attempt_key) " +
                        "WHERE single_attempt_key IS NOT NULL",
                )
            }
        }
        assertTrue(result.isFailure)
    }
}

/** application migration failure test の破損 fixture。 */
private enum class EconomicEventMigrationFailureFixture {
    MISSING_COLUMN_LOCK,
    INVALID_INDEX,
}

private fun JdbcTransaction.executeMigrationFixtureUpdate(sql: String) {
    migrationTestConnection().prepareStatement(sql).use { statement -> statement.executeUpdate() }
}

private fun JdbcTransaction.migrationTestConnection(): Connection = connection.connection as Connection

private fun countWorkerStartedEvents(database: ExposedDatabase): Int {
    return exposedTransaction(database) {
        migrationTestConnection().prepareStatement(
            "SELECT COUNT(*) FROM command_event_log WHERE event_type IN (?, ?)",
        ).use { statement ->
            statement.setString(1, CommandEventType.DAEMON_STARTED.name)
            statement.setString(2, CommandEventType.RECONCILER_STARTED.name)
            statement.executeQuery().use { resultSet ->
                require(resultSet.next())
                resultSet.getInt(1)
            }
        }
    }
}

private fun singleAttemptMigrationIndexExists(database: ExposedDatabase): Boolean {
    return exposedTransaction(database) {
        migrationTestConnection().prepareStatement(
            "SELECT to_regclass('idx_llm_launch_reservations_single_attempt_key_unique') IS NOT NULL",
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next())
                resultSet.getBoolean(1)
            }
        }
    }
}

private fun singleAttemptMigrationColumnExists(database: ExposedDatabase): Boolean {
    return exposedTransaction(database) {
        migrationTestConnection().prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = 'llm_launch_reservations' " +
                "AND column_name = 'single_attempt_key')",
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next())
                resultSet.getBoolean(1)
            }
        }
    }
}

private fun singleAttemptMigrationIndexIsValid(database: ExposedDatabase): Boolean {
    return exposedTransaction(database) {
        migrationTestConnection().prepareStatement(
            "SELECT indisvalid FROM pg_index WHERE indexrelid = " +
                "'idx_llm_launch_reservations_single_attempt_key_unique'::regclass",
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next())
                resultSet.getBoolean(1)
            }
        }
    }
}

private fun countBackfilledAttemptKeys(database: ExposedDatabase): Int {
    return exposedTransaction(database) {
        migrationTestConnection().prepareStatement(
            "SELECT COUNT(*) FROM llm_launch_reservations WHERE single_attempt_key IS NOT NULL",
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                require(resultSet.next())
                resultSet.getInt(1)
            }
        }
    }
}

/** application migration failure test 用 PostgreSQL container。 */
private class ApplicationMigrationPostgresContainer :
    PostgreSQLContainer<ApplicationMigrationPostgresContainer>("postgres:16-alpine")

private fun applicationMigrationDockerAvailable(): Boolean {
    return runCatching {
        DockerClientFactory.instance().client().pingCmd().exec()
        true
    }.getOrDefault(false)
}
