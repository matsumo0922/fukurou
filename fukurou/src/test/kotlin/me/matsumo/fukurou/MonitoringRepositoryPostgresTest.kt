package me.matsumo.fukurou

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.DockerClientFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** PostgreSQL 上の monitoring query bound を検証するテスト。 */
class MonitoringRepositoryPostgresTest {
    @Test
    fun resolvedInfrastructureHistoryDoesNotConsumeUnresolvedBound() = runBlocking {
        if (!monitoringDockerAvailable()) return@runBlocking
        val container = MonitoringPostgresContainer().also { it.start() }
        try {
            val database = Database.connect(
                url = container.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = container.username,
                password = container.password,
            )
            prepareGapTables(database)

            val aggregate = ExposedMonitoringRepository(database).unresolvedGaps().getOrThrow()

            assertEquals(0, aggregate.marketDataCount)
            assertEquals(1, aggregate.infrastructureCount)
            assertEquals(Instant.parse("2026-07-19T01:00:00Z"), aggregate.oldestInfrastructureOpenedAt)
        } finally {
            container.stop()
        }
    }
}

private fun prepareGapTables(database: Database) {
    transaction(database) {
        exec(
            """
                CREATE TABLE market_data_gaps (
                    id UUID PRIMARY KEY,
                    started_at BIGINT NOT NULL,
                    recovered_at BIGINT
                );
                CREATE TABLE infrastructure_gap_events (
                    event_id UUID PRIMARY KEY,
                    gap_id UUID NOT NULL,
                    deployment_id VARCHAR(96) NOT NULL,
                    boundary VARCHAR(5) NOT NULL CHECK (boundary IN ('OPEN', 'CLOSE')),
                    occurred_at TIMESTAMPTZ NOT NULL,
                    UNIQUE (deployment_id, boundary),
                    UNIQUE (gap_id, boundary)
                );
                INSERT INTO infrastructure_gap_events(event_id, gap_id, deployment_id, boundary, occurred_at)
                SELECT md5('open-' || ordinal)::uuid, md5('gap-' || ordinal)::uuid,
                       'deploy-' || ordinal, 'OPEN', TIMESTAMPTZ '2026-07-18T00:00:00Z'
                FROM generate_series(1, 1001) ordinal;
                INSERT INTO infrastructure_gap_events(event_id, gap_id, deployment_id, boundary, occurred_at)
                SELECT md5('close-' || ordinal)::uuid, md5('gap-' || ordinal)::uuid,
                       'deploy-' || ordinal, 'CLOSE', TIMESTAMPTZ '2026-07-18T00:01:00Z'
                FROM generate_series(1, 1001) ordinal;
                INSERT INTO infrastructure_gap_events(event_id, gap_id, deployment_id, boundary, occurred_at)
                VALUES (
                    md5('open-unresolved')::uuid,
                    md5('gap-unresolved')::uuid,
                    'deploy-unresolved',
                    'OPEN',
                    TIMESTAMPTZ '2026-07-19T01:00:00Z'
                );
            """.trimIndent(),
        )
    }
}

private fun monitoringDockerAvailable(): Boolean = runCatching {
    DockerClientFactory.instance().isDockerAvailable
}.getOrDefault(false)

private class MonitoringPostgresContainer : BoundedTestPostgresContainer<MonitoringPostgresContainer>(
    "postgres:16-alpine",
)
