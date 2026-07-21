package me.matsumo.fukurou.trading.replay

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.BoundedTestPostgresContainer
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.jdbcConnection
import me.matsumo.fukurou.trading.retryTransientTestPostgresConnection
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.testcontainers.DockerClientFactory
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** tail 事実シート replay の Postgres 統合回帰テスト。 */
class TailFactSheetIntegrationTest {

    @Test
    fun breachingPositionIsCountedWithFillWeightedR() = runReplayTest { database ->
        val windowFrom = 1_000_000L
        val breaching = UUID.randomUUID()
        val nonBreaching = UUID.randomUUID()

        exposedTransaction(database) {
            // 逆行が閾値超えの position。fill-weighted stop = (9M*0.005 + 8M*0.005)/0.01 = 8.5M、width = 1.5M。
            // lowest 6.7M → 逆行 3.3M → maeR = 2.2 (> 閾値 2.0)。片方の stop だけを使う naive 計算 (width 1M で maeR 3.3、
            // または width 2M で maeR 1.65) とは異なる値になるため、fill-weighting を一意に検証できる。
            seedClosedPosition(breaching, closedAtMs = windowFrom + 10L, lowestPriceJpy = BigDecimal("6700000"))
            seedBuyFill(breaching, priceJpy = BigDecimal("10000000"), sizeBtc = BigDecimal("0.005"), stopJpy = BigDecimal("9000000"))
            seedBuyFill(breaching, priceJpy = BigDecimal("10000000"), sizeBtc = BigDecimal("0.005"), stopJpy = BigDecimal("8000000"))
            seedSellFill(breaching, priceJpy = BigDecimal("6700000"), sizeBtc = BigDecimal("0.010"))

            // 逆行が閾値以下の position。stop 9M、width 1M、lowest 9.5M → 逆行 0.5M → maeR 0.5。SELL 2 本で部分決済扱い。
            seedClosedPosition(nonBreaching, closedAtMs = windowFrom + 20L, lowestPriceJpy = BigDecimal("9500000"))
            seedBuyFill(nonBreaching, priceJpy = BigDecimal("10000000"), sizeBtc = BigDecimal("0.010"), stopJpy = BigDecimal("9000000"))
            seedSellFill(nonBreaching, priceJpy = BigDecimal("9500000"), sizeBtc = BigDecimal("0.004"))
            seedSellFill(nonBreaching, priceJpy = BigDecimal("9600000"), sizeBtc = BigDecimal("0.006"))
        }

        val output = buildOutput(database, window(windowFrom, windowFrom + 2_000_000L))

        val breachLine = output.targets.single { line -> line.positionId == breaching.toString() }
        assertEquals(ReplayPopulationStatus.ELIGIBLE, breachLine.populationStatus)
        assertEquals(ReplayFidelity.LEDGER_FACT, breachLine.fidelity)
        assertTrue(breachLine.breachesThreshold)
        assertEquals("2.2000000000", breachLine.adverseExcursionR)
        assertNotNull(breachLine.initialRiskPriceWidthJpy)
        assertTrue(breachLine.notes.any { note -> note.contains("台帳記録値") })

        val nonBreachLine = output.targets.single { line -> line.positionId == nonBreaching.toString() }
        assertEquals(ReplayPopulationStatus.ELIGIBLE, nonBreachLine.populationStatus)
        assertTrue(!nonBreachLine.breachesThreshold)
        assertEquals("0.5000000000", nonBreachLine.adverseExcursionR)

        val summary = output.cohortSummaries.single()
        assertEquals(2, summary.eligibleCount)
        assertEquals(1, summary.thresholdBreachCount)
        assertEquals(1, summary.partialCloseCount)
    }

    @Test
    fun nullLowestAndNonPositiveWidthAreTailBasisUnavailable() = runReplayTest { database ->
        val windowFrom = 2_000_000L
        val nullLowest = UUID.randomUUID()
        val nonPositiveWidth = UUID.randomUUID()

        exposedTransaction(database) {
            seedClosedPosition(nullLowest, closedAtMs = windowFrom + 10L, lowestPriceJpy = null)
            seedBuyFill(nullLowest, priceJpy = BigDecimal("10000000"), sizeBtc = BigDecimal("0.010"), stopJpy = BigDecimal("9000000"))
            seedSellFill(nullLowest, priceJpy = BigDecimal("9500000"), sizeBtc = BigDecimal("0.010"))

            // stop 11M >= average entry 10M → risk width が非正。
            seedClosedPosition(nonPositiveWidth, closedAtMs = windowFrom + 20L, lowestPriceJpy = BigDecimal("6000000"))
            seedBuyFill(nonPositiveWidth, priceJpy = BigDecimal("10000000"), sizeBtc = BigDecimal("0.010"), stopJpy = BigDecimal("11000000"))
            seedSellFill(nonPositiveWidth, priceJpy = BigDecimal("6000000"), sizeBtc = BigDecimal("0.010"))
        }

        val output = buildOutput(database, window(windowFrom, windowFrom + 2_000_000L))

        listOf(nullLowest, nonPositiveWidth).forEach { positionId ->
            val line = output.targets.single { line -> line.positionId == positionId.toString() }
            assertEquals(ReplayPopulationStatus.UNKNOWN, line.populationStatus)
            assertEquals(ReplayUnknownReason.TAIL_BASIS_UNAVAILABLE, line.unknownReason)
            assertEquals(ReplayFidelity.UNKNOWN, line.fidelity)
            assertNull(line.adverseExcursionR)
        }

        val summary = output.cohortSummaries.single()
        assertEquals(0, summary.eligibleCount)
        assertEquals(2, summary.unknownCountByReason[ReplayUnknownReason.TAIL_BASIS_UNAVAILABLE])
    }

    @Test
    fun targetIntersectingMarketDataGapIsUnknown() = runReplayTest { database ->
        val windowFrom = 3_000_000L
        val sessionId = UUID.randomUUID()
        val positionId = UUID.randomUUID()

        exposedTransaction(database) {
            seedSession(sessionId)
            seedClosedPosition(
                positionId,
                openedAtMs = windowFrom,
                closedAtMs = windowFrom + 500_000L,
                lowestPriceJpy = BigDecimal("6000000"),
            )
            seedBuyFill(positionId, priceJpy = BigDecimal("10000000"), sizeBtc = BigDecimal("0.010"), stopJpy = BigDecimal("9000000"))
            seedSellFill(positionId, priceJpy = BigDecimal("6000000"), sizeBtc = BigDecimal("0.010"))
            seedMarketDataGap(sessionId, startedAtMs = windowFrom + 100_000L, recoveredAtMs = windowFrom + 200_000L)
        }

        val output = buildOutput(database, window(windowFrom, windowFrom + 2_000_000L))

        val line = output.targets.single()
        assertEquals(ReplayPopulationStatus.UNKNOWN, line.populationStatus)
        assertEquals(ReplayUnknownReason.MARKET_DATA_GAP, line.unknownReason)
    }

    @Test
    fun replayIssuesNoWrites() = runReplayTest { database ->
        val windowFrom = 4_000_000L
        val positionId = UUID.randomUUID()

        exposedTransaction(database) {
            seedClosedPosition(positionId, closedAtMs = windowFrom + 10L, lowestPriceJpy = BigDecimal("6700000"))
            seedBuyFill(positionId, priceJpy = BigDecimal("10000000"), sizeBtc = BigDecimal("0.010"), stopJpy = BigDecimal("9000000"))
            seedSellFill(positionId, priceJpy = BigDecimal("6700000"), sizeBtc = BigDecimal("0.010"))
        }

        val before = tableCounts(database)
        buildOutput(database, window(windowFrom, windowFrom + 2_000_000L))
        val after = tableCounts(database)

        assertEquals(before, after)
    }

    private fun buildOutput(
        database: ExposedDatabase,
        window: ReplayWindow,
        thresholdRMultiple: BigDecimal = BigDecimal("2.0"),
        maxTargets: Int = 100,
    ): TailRunOutput {
        return ReplayReadOnlyRuntime.fromDatabase(database).use { runtime ->
            TailFactSheet(runtime).buildOutput(
                TailReplayBounds(
                    window = window,
                    thresholdRMultiple = thresholdRMultiple,
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

    private fun JdbcTransaction.seedClosedPosition(
        positionId: UUID,
        openedAtMs: Long = 0L,
        closedAtMs: Long,
        lowestPriceJpy: BigDecimal?,
    ) {
        execUpdate(
            """
            INSERT INTO positions (
                id, trade_group_id, mode, symbol, side, status, opened_at, closed_at, size_btc,
                average_entry_price_jpy, current_price_jpy, pyramid_add_count,
                highest_price_since_entry_jpy, lowest_price_since_entry_jpy
            ) VALUES (?, ?, 'PAPER', 'BTC', 'LONG', 'CLOSED', ?, ?, 0.0, 10000000, 10000000, 0, 10000000, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, positionId)
            statement.setObject(2, UUID.randomUUID())
            statement.setLong(3, openedAtMs)
            statement.setLong(4, closedAtMs)
            statement.setBigDecimal(5, lowestPriceJpy)
        }
    }

    private fun JdbcTransaction.seedBuyFill(
        positionId: UUID,
        priceJpy: BigDecimal,
        sizeBtc: BigDecimal,
        stopJpy: BigDecimal,
    ) {
        val orderId = UUID.randomUUID()
        execUpdate(
            """
            INSERT INTO orders (
                id, mode, symbol, side, order_type, status, size_btc, protective_stop_price_jpy,
                position_id, execution_semantics_version, created_at, updated_at
            ) VALUES (?, 'PAPER', 'BTC', 'BUY', 'LIMIT', 'FILLED', ?, ?, ?, 'PAPER_WS_V1', 0, 0)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, orderId)
            statement.setBigDecimal(2, sizeBtc)
            statement.setBigDecimal(3, stopJpy)
            statement.setObject(4, positionId)
        }
        execUpdate(
            """
            INSERT INTO executions (
                id, order_id, position_id, mode, symbol, side, price_jpy, size_btc, fee_jpy, realized_pnl_jpy,
                liquidity, executed_at, execution_semantics_version
            ) VALUES (?, ?, ?, 'PAPER', 'BTC', 'BUY', ?, ?, 0, 0, 'MAKER', 0, 'PAPER_WS_V1')
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, orderId)
            statement.setObject(3, positionId)
            statement.setBigDecimal(4, priceJpy)
            statement.setBigDecimal(5, sizeBtc)
        }
    }

    private fun JdbcTransaction.seedSellFill(
        positionId: UUID,
        priceJpy: BigDecimal,
        sizeBtc: BigDecimal,
    ) {
        execUpdate(
            """
            INSERT INTO executions (
                id, position_id, mode, symbol, side, price_jpy, size_btc, fee_jpy, realized_pnl_jpy,
                liquidity, executed_at, execution_semantics_version
            ) VALUES (?, ?, 'PAPER', 'BTC', 'SELL', ?, ?, 0, 0, 'TAKER', 1, 'PAPER_WS_V1')
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, positionId)
            statement.setBigDecimal(3, priceJpy)
            statement.setBigDecimal(4, sizeBtc)
        }
    }

    private fun JdbcTransaction.seedSession(sessionId: UUID) {
        execUpdate(
            "INSERT INTO market_data_sessions (id, state, connected_at, last_processed_sequence) VALUES (?, 'CONNECTED', 0, 0)",
        ) { statement -> statement.setObject(1, sessionId) }
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
            println("Skipping tail replay integration test because Docker is unavailable.")
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
        val COUNTED_TABLES = listOf("positions", "orders", "executions", "market_data_gaps")
    }

    private class ReplayPostgresContainer :
        BoundedTestPostgresContainer<ReplayPostgresContainer>(POSTGRES_IMAGE)
}
