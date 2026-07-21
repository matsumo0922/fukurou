package me.matsumo.fukurou.trading.replay

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.BoundedTestPostgresContainer
import me.matsumo.fukurou.trading.domain.EvaluationCohort
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.jdbcConnection
import me.matsumo.fukurou.trading.retryTransientTestPostgresConnection
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.testcontainers.DockerClientFactory
import java.sql.PreparedStatement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** TTL 短縮感度 replay の Postgres 統合回帰テスト。 */
class TtlShorteningReplayIntegrationTest {

    @Test
    fun disclosesPopulationAndConfirmedDropsWithoutSynthesizingFills() = runReplayTest { database ->
        val sessionId = UUID.randomUUID()
        val windowFrom = 1_000_000L

        exposedTransaction(database) {
            seedSession(sessionId)
            // 約定 CURRENT order。約定は created+600s。receipt は source_sequence 1..3 で連続。
            val filledOrder = UUID.randomUUID()
            seedOrder(
                orderId = filledOrder,
                createdAtMs = windowFrom,
                effectiveTtlSeconds = 1800,
                marketDataSessionId = sessionId,
                marketEligibleAfterSequence = 0,
            )
            seedTradePlanAndIntent(filledOrder, timeStopAtMs = windowFrom + 3_600_000L)
            seedEntryExecution(filledOrder, executedAtMs = windowFrom + 600_000L, sessionId = sessionId, sourceSequence = 3)
            seedReceipt(sessionId, sourceSequence = 1, admissionOrdinal = 1)
            seedReceipt(sessionId, sourceSequence = 2, admissionOrdinal = 2)
            seedReceipt(sessionId, sourceSequence = 3, admissionOrdinal = 3)

            // TTL 失効 order (execution 無し・expired_at あり)。
            seedOrder(orderId = UUID.randomUUID(), createdAtMs = windowFrom + 10L, effectiveTtlSeconds = 1800, expiredAtMs = windowFrom + 1_800_000L)

            // 非 TTL 終端 order (cancel_reason あり・execution 無し・expired_at NULL)。
            seedOrder(orderId = UUID.randomUUID(), createdAtMs = windowFrom + 20L, effectiveTtlSeconds = 1800, cancelReason = "hard_halt", canceledAtMs = windowFrom + 30L)

            // snapshot 時点 OPEN order。
            seedOrder(orderId = UUID.randomUUID(), createdAtMs = windowFrom + 40L, effectiveTtlSeconds = 1800)
        }

        val output = buildOutput(database, window(windowFrom, windowFrom + 2_000_000L), candidates = listOf(300L, 900L))

        val current = output.cohortSummaries.single { summary -> summary.cohort == EvaluationCohort.CURRENT }
        assertEquals(1, current.filledCount)
        assertEquals(1, current.ttlExpiredCount)
        assertEquals(2, current.eligibleCount)
        assertEquals(1, current.nonTtlTerminalCount)
        assertEquals(1, current.openAtSnapshotCount)

        val shortCandidate = current.candidates.single { candidate -> candidate.candidateTtlSeconds == 300L }
        assertEquals(1, shortCandidate.confirmedDroppedCount)
        val longCandidate = current.candidates.single { candidate -> candidate.candidateTtlSeconds == 900L }
        assertEquals(1, longCandidate.retentionUnconfirmedCount)

        val nonTtlLine = output.targets.single { line -> line.classification == ReplayOrderClassification.NON_TTL_TERMINAL }
        assertTrue(nonTtlLine.candidates.isEmpty())
        val openLine = output.targets.single { line -> line.classification == ReplayOrderClassification.OPEN_AT_SNAPSHOT }
        assertEquals(ReplayPopulationStatus.OPEN_AT_SNAPSHOT, openLine.populationStatus)
    }

    @Test
    fun replayIssuesNoWrites() = runReplayTest { database ->
        val windowFrom = 2_000_000L
        exposedTransaction(database) {
            seedOrder(orderId = UUID.randomUUID(), createdAtMs = windowFrom, effectiveTtlSeconds = 1800, expiredAtMs = windowFrom + 1_800_000L)
        }

        val before = tableCounts(database)
        buildOutput(database, window(windowFrom, windowFrom + 2_000_000L), candidates = listOf(300L))
        val after = tableCounts(database)

        assertEquals(before, after)
    }

    @Test
    fun targetIntersectingMarketDataGapIsUnknown() = runReplayTest { database ->
        val sessionId = UUID.randomUUID()
        val windowFrom = 3_000_000L

        exposedTransaction(database) {
            seedSession(sessionId)
            val orderId = UUID.randomUUID()
            seedOrder(orderId = orderId, createdAtMs = windowFrom, effectiveTtlSeconds = 1800, marketDataSessionId = sessionId, marketEligibleAfterSequence = 0)
            seedTradePlanAndIntent(orderId, timeStopAtMs = windowFrom + 3_600_000L)
            seedEntryExecution(orderId, executedAtMs = windowFrom + 600_000L, sessionId = sessionId, sourceSequence = 3)
            seedReceipt(sessionId, sourceSequence = 1, admissionOrdinal = 1)
            seedReceipt(sessionId, sourceSequence = 2, admissionOrdinal = 2)
            seedReceipt(sessionId, sourceSequence = 3, admissionOrdinal = 3)
            seedMarketDataGap(sessionId, startedAtMs = windowFrom + 100_000L, recoveredAtMs = windowFrom + 200_000L)
        }

        val output = buildOutput(database, window(windowFrom, windowFrom + 2_000_000L), candidates = listOf(300L))

        val line = output.targets.single()
        assertEquals(ReplayPopulationStatus.UNKNOWN, line.populationStatus)
        assertEquals(ReplayUnknownReason.MARKET_DATA_GAP, line.unknownReason)
        assertTrue(line.candidates.isEmpty())
    }

    @Test
    fun ordinalHoleWithContinuousSequenceStaysEligible() = runReplayTest { database ->
        val sessionId = UUID.randomUUID()
        val windowFrom = 4_000_000L

        exposedTransaction(database) {
            seedSession(sessionId)
            val orderId = UUID.randomUUID()
            seedOrder(orderId = orderId, createdAtMs = windowFrom, effectiveTtlSeconds = 1800, marketDataSessionId = sessionId, marketEligibleAfterSequence = 0)
            seedTradePlanAndIntent(orderId, timeStopAtMs = windowFrom + 3_600_000L)
            seedEntryExecution(orderId, executedAtMs = windowFrom + 600_000L, sessionId = sessionId, sourceSequence = 3)
            // source_sequence は 1..3 で連続、admission_ordinal は 5,6,8 で欠番あり。
            seedReceipt(sessionId, sourceSequence = 1, admissionOrdinal = 5)
            seedReceipt(sessionId, sourceSequence = 2, admissionOrdinal = 6)
            seedReceipt(sessionId, sourceSequence = 3, admissionOrdinal = 8)
        }

        val output = buildOutput(database, window(windowFrom, windowFrom + 2_000_000L), candidates = listOf(300L))

        val line = output.targets.single()
        assertEquals(ReplayPopulationStatus.ELIGIBLE, line.populationStatus)
        assertNull(line.unknownReason)
    }

    @Test
    fun targetCountOverLimitFailsRun() = runReplayTest { database ->
        val windowFrom = 5_000_000L
        exposedTransaction(database) {
            seedOrder(orderId = UUID.randomUUID(), createdAtMs = windowFrom, effectiveTtlSeconds = 1800, expiredAtMs = windowFrom + 1_800_000L)
            seedOrder(orderId = UUID.randomUUID(), createdAtMs = windowFrom + 1L, effectiveTtlSeconds = 1800, expiredAtMs = windowFrom + 1_800_000L)
        }

        assertFailsWith<ReplayRunFailedException> {
            buildOutput(database, window(windowFrom, windowFrom + 2_000_000L), candidates = listOf(300L), maxTargets = 1)
        }
    }

    private fun buildOutput(
        database: ExposedDatabase,
        window: ReplayWindow,
        candidates: List<Long>,
        maxTargets: Int = 100,
    ): TtlReplayRunOutput {
        return ReplayReadOnlyRuntime.fromDatabase(database).use { runtime ->
            TtlShorteningReplay(runtime).buildOutput(
                ReplayBounds(
                    window = window,
                    candidateTtlSeconds = candidates,
                    maxTargets = maxTargets,
                    statementTimeoutSeconds = 30,
                ),
            )
        }
    }

    private fun window(fromMs: Long, toExclusiveMs: Long): ReplayWindow = ReplayWindow(fromMs, toExclusiveMs)

    private fun tableCounts(database: ExposedDatabase): Map<String, Long> {
        return exposedTransaction(database) {
            COUNTED_TABLES.associateWith { table ->
                jdbcConnection().prepareStatement("SELECT COUNT(*) FROM $table").use { statement ->
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun JdbcTransaction.seedOrder(
        orderId: UUID,
        createdAtMs: Long,
        effectiveTtlSeconds: Long,
        marketDataSessionId: UUID? = null,
        marketEligibleAfterSequence: Long? = null,
        expiredAtMs: Long? = null,
        cancelReason: String? = null,
        canceledAtMs: Long? = null,
        intentId: UUID? = null,
    ) {
        val expiresAt = createdAtMs + effectiveTtlSeconds * 1000L
        execUpdate(
            """
            INSERT INTO orders (
                id, mode, symbol, side, order_type, status, size_btc, limit_price_jpy,
                expires_at, expiry_source, effective_ttl_seconds, expired_at, cancel_reason, canceled_at,
                market_data_session_id, market_eligible_after_sequence, execution_semantics_version,
                intent_id, created_at, updated_at
            ) VALUES (?, 'PAPER', 'BTC_JPY', 'BUY', 'LIMIT', 'OPEN', 0.0100, 10000000,
                ?, 'TTL', ?, ?, ?, ?, ?, ?, 'PAPER_WS_V1', ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, orderId)
            statement.setLong(2, expiresAt)
            statement.setLong(3, effectiveTtlSeconds)
            statement.setObject(4, expiredAtMs)
            statement.setString(5, cancelReason)
            statement.setObject(6, canceledAtMs)
            statement.setObject(7, marketDataSessionId)
            statement.setObject(8, marketEligibleAfterSequence)
            statement.setObject(9, intentId)
            statement.setLong(10, createdAtMs)
            statement.setLong(11, createdAtMs)
        }
    }

    private fun JdbcTransaction.seedEntryExecution(
        orderId: UUID,
        executedAtMs: Long,
        sessionId: UUID,
        sourceSequence: Long,
    ) {
        execUpdate(
            """
            INSERT INTO executions (
                id, order_id, mode, symbol, side, price_jpy, size_btc, fee_jpy, realized_pnl_jpy,
                liquidity, executed_at, source_session_id, source_sequence, execution_semantics_version
            ) VALUES (?, ?, 'PAPER', 'BTC_JPY', 'BUY', 10000000, 0.0100, 20, 0, 'MAKER', ?, ?, ?, 'PAPER_WS_V1')
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, orderId)
            statement.setLong(3, executedAtMs)
            statement.setObject(4, sessionId)
            statement.setLong(5, sourceSequence)
        }
    }

    private fun JdbcTransaction.seedTradePlanAndIntent(orderId: UUID, timeStopAtMs: Long) {
        val planId = UUID.randomUUID()
        val intentId = UUID.randomUUID()
        execUpdate(
            """
            INSERT INTO trade_plans (
                id, decision_id, revision_count, symbol, thesis_ja, invalidation_conditions_ja,
                invalidation_predicates, setup_tags, time_stop_at, created_at
            ) VALUES (?, ?, 0, 'BTC_JPY', 'thesis', '[]', '', '[]', ?, 0)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, planId)
            statement.setObject(2, UUID.randomUUID())
            statement.setLong(3, timeStopAtMs)
        }
        execUpdate(
            """
            INSERT INTO trade_intents (
                id, decision_id, trade_plan_id, symbol, side, order_type, size_btc,
                protective_stop_price_jpy, estimated_win_probability, created_at
            ) VALUES (?, ?, ?, 'BTC_JPY', 'BUY', 'LIMIT', 0.0100, 9000000, 0.5, 0)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, intentId)
            statement.setObject(2, UUID.randomUUID())
            statement.setObject(3, planId)
        }
        execUpdate("UPDATE orders SET intent_id = ? WHERE id = ?") { statement ->
            statement.setObject(1, intentId)
            statement.setObject(2, orderId)
        }
    }

    private fun JdbcTransaction.seedSession(sessionId: UUID) {
        execUpdate(
            "INSERT INTO market_data_sessions (id, state, connected_at, last_processed_sequence) VALUES (?, 'CONNECTED', 0, 0)",
        ) { statement -> statement.setObject(1, sessionId) }
    }

    private fun JdbcTransaction.seedReceipt(
        sessionId: UUID,
        sourceSequence: Long,
        admissionOrdinal: Long,
    ) {
        execUpdate(
            """
            INSERT INTO paper_market_event_receipts (
                id, session_id, source_sequence, source_timestamp, socket_observed_at,
                normalized_payload, payload_hash, admission_ordinal, advisory_wait_nanos, recorded_at
            ) VALUES (?, ?, ?, 0, 0, '{}', 'hash', ?, 0, 0)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, sessionId)
            statement.setLong(3, sourceSequence)
            statement.setLong(4, admissionOrdinal)
        }
    }

    private fun JdbcTransaction.seedMarketDataGap(
        sessionId: UUID,
        startedAtMs: Long,
        recoveredAtMs: Long,
    ) {
        execUpdate(
            "INSERT INTO market_data_gaps (id, session_id, reason, started_at, recovered_at) VALUES (?, ?, 'STALL', ?, ?)",
        ) { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, sessionId)
            statement.setLong(3, startedAtMs)
            statement.setLong(4, recoveredAtMs)
        }
    }

    private fun JdbcTransaction.execUpdate(sql: String, bind: (PreparedStatement) -> Unit) {
        jdbcConnection().prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeUpdate()
        }
    }

    private fun runReplayTest(block: (ExposedDatabase) -> Unit) = runBlocking {
        if (!isDockerAvailable()) {
            println("Skipping TTL replay integration test because Docker is unavailable.")
            return@runBlocking
        }

        val container = ReplayPostgresContainer()
        container.start()
        try {
            retryTransientTestPostgresConnection { dataSource(container) }.use { dataSource ->
                val database = ExposedDatabase.connect(dataSource)
                TradingPersistenceBootstrap(database, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
                    .ensureSchema()
                    .getOrThrow()
                block(database)
            }
        } finally {
            container.stop()
        }
    }

    private fun dataSource(container: ReplayPostgresContainer): HikariDataSource {
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = 4
            },
        )
    }

    private fun isDockerAvailable(): Boolean {
        return runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16-alpine"
        val COUNTED_TABLES =
            listOf("orders", "executions", "positions", "paper_market_event_receipts", "market_data_gaps")
    }

    private class ReplayPostgresContainer :
        BoundedTestPostgresContainer<ReplayPostgresContainer>(POSTGRES_IMAGE)
}
