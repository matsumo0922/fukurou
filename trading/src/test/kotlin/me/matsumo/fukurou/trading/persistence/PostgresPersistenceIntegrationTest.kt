package me.matsumo.fukurou.trading.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.runtime.TradingDatabaseConfig
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * integration test 用 Postgres image。
 */
private const val POSTGRES_IMAGE = "postgres:16-alpine"

/**
 * integration test 用 Hikari pool size。
 */
private const val HIKARI_POOL_SIZE = 4

/**
 * risk_state single row を削除する SQL。
 */
private const val DELETE_RISK_STATE_ROW_SQL = "DELETE FROM risk_state WHERE id = ?"

/**
 * command_event_log table を削除する SQL。
 */
private const val DROP_COMMAND_EVENT_LOG_TABLE_SQL = "DROP TABLE command_event_log"

/**
 * Exposed/Postgres 実装の DB 契約を実 Postgres で検証するテスト。
 */
class PostgresPersistenceIntegrationTest {

    @Test
    fun bootstrap_verify_schema_fails_closed_until_backend_bootstrap_creates_schema() = runPostgresTest {
        val bootstrap = TradingPersistenceBootstrap(database, fixedClock())

        assertTrue(bootstrap.verifySchema().isFailure)

        bootstrap.ensureSchema().getOrThrow()

        assertTrue(bootstrap.verifySchema().isSuccess)
        assertTrue(ExposedRiskStateRepository(database).current().isSuccess)
    }

    @Test
    fun runtime_postgres_verifies_schema_without_running_ddl() = runPostgresTest {
        val missingSchemaResult = runCatching {
            TradingRuntimeFactory.postgres(
                config = tradingDatabaseConfig(),
                clock = fixedClock(),
            ).close()
        }

        assertTrue(missingSchemaResult.isFailure)

        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val runtime = TradingRuntimeFactory.postgres(
            config = tradingDatabaseConfig(),
            clock = fixedClock(),
        )

        runtime.close()
    }

    @Test
    fun risk_state_repository_current_does_not_create_missing_single_row() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()
        deleteRiskStateRow(database)

        val currentResult = ExposedRiskStateRepository(database).current()

        assertTrue(currentResult.isFailure)
    }

    @Test
    fun risk_state_command_service_rolls_back_when_audit_append_fails() = runPostgresTest {
        TradingPersistenceBootstrap(database, fixedClock()).ensureSchema().getOrThrow()

        val repository = ExposedRiskStateRepository(database)
        repository.setHardHalt("manual halt", fixedInstant()).getOrThrow()
        dropCommandEventLogTable(database)

        val resumeResult = ExposedRiskStateCommandService(database, fixedClock()).resume(
            reason = "manual resume",
            decisionRunContext = DecisionRunContext.EMPTY,
        )
        val riskState = repository.current().getOrThrow()

        assertTrue(resumeResult.isFailure)
        assertTrue(riskState.hardHalt)
    }

    @Test
    fun postgres_global_lock_times_out_when_lock_is_held() = runPostgresTest {
        val lock = PostgresGlobalTradingLock(
            dataSource = dataSource,
            clock = fixedClock(),
            lockTimeout = Duration.ofMillis(150),
            lockRetryDelay = Duration.ofMillis(10),
        )

        coroutineScope {
            val holderAcquired = CompletableDeferred<Unit>()
            val releaseHolder = CompletableDeferred<Unit>()
            val holderJob = launch {
                lock.withLock("holder") { lease ->
                    holderAcquired.complete(Unit)
                    releaseHolder.await()
                    lease.owner
                }
            }

            holderAcquired.await()

            val waiterResult = runCatching {
                lock.withLock("waiter") { lease -> lease.owner }
            }

            releaseHolder.complete(Unit)
            holderJob.join()

            val throwable = requireNotNull(waiterResult.exceptionOrNull())

            assertTrue(throwable is SQLTimeoutException)
        }
    }
}

/**
 * Postgres integration test の共有 context。
 *
 * @param container Testcontainers Postgres
 * @param dataSource Postgres DataSource
 * @param database Exposed database
 */
private class PostgresTestContext(
    private val container: FukurouPostgresContainer,
    val dataSource: HikariDataSource,
    val database: ExposedDatabase,
) {
    /**
     * container の接続情報を runtime config に変換する。
     */
    fun tradingDatabaseConfig(): TradingDatabaseConfig {
        return TradingDatabaseConfig(
            url = container.jdbcUrl,
            user = container.username,
            password = container.password,
        )
    }
}

/**
 * fukurou integration test 用 Postgres container。
 */
private class FukurouPostgresContainer : PostgreSQLContainer<FukurouPostgresContainer>(POSTGRES_IMAGE)

/**
 * Docker が利用できる場合だけ Postgres integration test を実行する。
 */
private fun runPostgresTest(
    block: suspend PostgresTestContext.() -> Unit,
) = runBlocking {
    if (!isDockerAvailable()) {
        println("Skipping Postgres integration test because Docker is unavailable.")
        return@runBlocking
    }

    val container = FukurouPostgresContainer()
    container.start()

    try {
        val dataSource = createDataSource(container)

        try {
            val database = ExposedDatabase.connect(dataSource)
            val context = PostgresTestContext(
                container = container,
                dataSource = dataSource,
                database = database,
            )

            context.block()
        } finally {
            dataSource.close()
        }
    } finally {
        container.stop()
    }
}

/**
 * Docker daemon が利用可能かを返す。
 */
private fun isDockerAvailable(): Boolean {
    return runCatching {
        DockerClientFactory.instance().isDockerAvailable
    }.getOrDefault(false)
}

/**
 * test container 用 DataSource を作る。
 */
private fun createDataSource(container: FukurouPostgresContainer): HikariDataSource {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = container.jdbcUrl
        username = container.username
        password = container.password
        maximumPoolSize = HIKARI_POOL_SIZE
    }

    return HikariDataSource(hikariConfig)
}

/**
 * risk_state single row を削除する。
 */
private fun deleteRiskStateRow(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DELETE_RISK_STATE_ROW_SQL).use { statement ->
            statement.setInt(1, RISK_STATE_SINGLE_ROW_ID)
            statement.executeUpdate()
        }
    }
}

/**
 * command_event_log table を削除する。
 */
private fun dropCommandEventLogTable(database: ExposedDatabase) {
    exposedTransaction(database) {
        jdbcConnection().prepareStatement(DROP_COMMAND_EVENT_LOG_TABLE_SQL).use { statement ->
            statement.executeUpdate()
        }
    }
}

/**
 * Postgres integration test 用の固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

/**
 * Postgres integration test 用の固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
