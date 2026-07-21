package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.PaperOrderCancelReason
import me.matsumo.fukurou.trading.shadow.GateShadowObservation
import me.matsumo.fukurou.trading.shadow.GateShadowOutcome
import me.matsumo.fukurou.trading.shadow.GateShadowRepository
import me.matsumo.fukurou.trading.shadow.GateShadowResolution
import me.matsumo.fukurou.trading.shadow.GateShadowScanProgress
import me.matsumo.fukurou.trading.shadow.ShadowDataQuality
import me.matsumo.fukurou.trading.shadow.gateShadowResult
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL の gate-shadow repository。
 *
 * 各メソッドは独立 transaction で動作し、ledger transaction に参加しない。
 */
class ExposedGateShadowRepository(
    private val database: Database,
) : GateShadowRepository {

    override suspend fun appendObservation(observation: GateShadowObservation): Result<Unit> {
        return writeResult {
            jdbcConnection().prepareStatement(INSERT_OBSERVATION_SQL).use { statement ->
                statement.bindObservation(observation)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun findObservationByOrderId(orderId: UUID): Result<GateShadowObservation?> {
        return readResult {
            jdbcConnection().prepareStatement(SELECT_OBSERVATION_BY_ORDER_ID_SQL).use { statement ->
                statement.setObject(1, orderId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toObservation() else null }
            }
        }
    }

    override suspend fun upsertScanProgress(progress: GateShadowScanProgress): Result<Unit> {
        return writeResult {
            jdbcConnection().prepareStatement(UPSERT_SCAN_PROGRESS_SQL).use { statement ->
                statement.setObject(1, progress.observationId)
                statement.setLong(2, progress.lastScannedAdmissionOrdinal)
                statement.setLong(3, progress.lastScannedAt.toEpochMilli())
                statement.executeUpdate()
            }
        }
    }

    override suspend fun findScanProgress(observationId: UUID): Result<GateShadowScanProgress?> {
        return readResult {
            jdbcConnection().prepareStatement(SELECT_SCAN_PROGRESS_SQL).use { statement ->
                statement.setObject(1, observationId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toScanProgress() else null }
            }
        }
    }

    override suspend fun upsertResolution(resolution: GateShadowResolution): Result<Unit> {
        val sql = if (resolution.outcome == GateShadowOutcome.CROSSED) {
            UPSERT_CROSSED_RESOLUTION_SQL
        } else {
            INSERT_UNKNOWN_RESOLUTION_SQL
        }

        return writeResult {
            jdbcConnection().prepareStatement(sql).use { statement ->
                statement.bindResolution(resolution)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun findResolution(observationId: UUID): Result<GateShadowResolution?> {
        return readResult {
            jdbcConnection().prepareStatement(SELECT_RESOLUTION_SQL).use { statement ->
                statement.setObject(1, observationId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toResolution() else null }
            }
        }
    }

    override suspend fun countMissingTtlExpiryObservations(): Result<Long> {
        return readResult {
            jdbcConnection().prepareStatement(COUNT_MISSING_TTL_OBSERVATIONS_SQL).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Gate-shadow reconciliation count was not returned." }
                    rows.getLong(1)
                }
            }
        }
    }

    override suspend fun isReceiptScanIndexReady(): Result<Boolean> {
        return readResult {
            jdbcConnection().prepareStatement(SELECT_RECEIPT_SCAN_INDEX_READY_SQL).use { statement ->
                statement.executeQuery().use { rows -> rows.next() }
            }
        }
    }

    private suspend fun <T> readResult(block: JdbcTransaction.() -> T): Result<T> {
        return withContext(Dispatchers.IO) {
            gateShadowResult { transaction(database) { block() } }
        }
    }

    private suspend fun <T> writeResult(block: JdbcTransaction.() -> T): Result<T> {
        return withContext(Dispatchers.IO) {
            gateShadowResult {
                transaction(database) {
                    applyWriteTimeouts()
                    block()
                }
            }
        }
    }

    private fun JdbcTransaction.applyWriteTimeouts() {
        jdbcConnection().createStatement().use { statement ->
            statement.execute("SET LOCAL lock_timeout = '2s'")
            statement.execute("SET LOCAL statement_timeout = '2s'")
        }
    }

    private fun PreparedStatement.bindObservation(observation: GateShadowObservation) {
        setObject(1, observation.id)
        setObject(2, observation.orderId)
        setNullableUuid(3, observation.decisionId)
        setNullableUuid(4, observation.opportunityEpisodeId)
        setNullableString(5, observation.geometryHash)
        setString(6, observation.symbol)
        setString(7, observation.side.name)
        setString(8, observation.orderType.name)
        setBigDecimal(9, observation.sizeBtc)
        setNullableBigDecimal(10, observation.limitPriceJpy)
        setNullableBigDecimal(11, observation.triggerPriceJpy)
        setNullableBigDecimal(12, observation.stopPriceJpy)
        setNullableBigDecimal(13, observation.takeProfitPriceJpy)
        setNullableBigDecimal(14, observation.queueAheadBtc)
        setNullableUuid(15, observation.marketDataSessionId)
        setLong(16, observation.startAdmissionOrdinal)
        setLong(17, observation.windowStartTime.toEpochMilli())
        setString(18, observation.dataQuality.name)
        setLong(19, observation.observedAt.toEpochMilli())
    }

    private fun PreparedStatement.bindResolution(resolution: GateShadowResolution) {
        setObject(1, resolution.observationId)
        setString(2, resolution.outcome.name)
        setNullableLong(3, resolution.crossingEventSequence)
        setNullableLong(4, resolution.crossingExchangeAt?.toEpochMilli())
        setNullableBigDecimal(5, resolution.crossingPriceJpy)
        setNullableBigDecimal(6, resolution.distanceJpy)
        setString(7, resolution.dataQuality.name)
        setLong(8, resolution.resolvedAt.toEpochMilli())
    }

    private fun PreparedStatement.setNullableUuid(index: Int, value: UUID?) {
        if (value == null) setNull(index, Types.OTHER) else setObject(index, value)
    }

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private fun PreparedStatement.setNullableBigDecimal(index: Int, value: BigDecimal?) {
        if (value == null) setNull(index, Types.NUMERIC) else setBigDecimal(index, value)
    }

    private fun ResultSet.toObservation(): GateShadowObservation {
        return GateShadowObservation(
            id = getObject("id", UUID::class.java),
            orderId = getObject("order_id", UUID::class.java),
            decisionId = getObject("decision_id", UUID::class.java),
            opportunityEpisodeId = getObject("opportunity_episode_id", UUID::class.java),
            geometryHash = getString("geometry_hash"),
            symbol = getString("symbol"),
            side = OrderSide.valueOf(getString("side")),
            orderType = OrderType.valueOf(getString("order_type")),
            sizeBtc = getBigDecimal("size_btc"),
            limitPriceJpy = getBigDecimal("limit_price_jpy"),
            triggerPriceJpy = getBigDecimal("trigger_price_jpy"),
            stopPriceJpy = getBigDecimal("stop_price_jpy"),
            takeProfitPriceJpy = getBigDecimal("take_profit_price_jpy"),
            queueAheadBtc = getBigDecimal("queue_ahead_btc"),
            marketDataSessionId = getObject("market_data_session_id", UUID::class.java),
            startAdmissionOrdinal = getLong("start_admission_ordinal"),
            windowStartTime = Instant.ofEpochMilli(getLong("window_start_time")),
            dataQuality = ShadowDataQuality.valueOf(getString("data_quality")),
            observedAt = Instant.ofEpochMilli(getLong("observed_at")),
        )
    }

    private fun ResultSet.toScanProgress(): GateShadowScanProgress {
        return GateShadowScanProgress(
            observationId = getObject("observation_id", UUID::class.java),
            lastScannedAdmissionOrdinal = getLong("last_scanned_admission_ordinal"),
            lastScannedAt = Instant.ofEpochMilli(getLong("last_scanned_at")),
        )
    }

    private fun ResultSet.toResolution(): GateShadowResolution {
        val crossingExchangeAt = getLong("crossing_exchange_at").takeUnless { wasNull() }?.let(Instant::ofEpochMilli)

        return GateShadowResolution(
            observationId = getObject("observation_id", UUID::class.java),
            outcome = GateShadowOutcome.valueOf(getString("outcome")),
            crossingEventSequence = getLong("crossing_event_sequence").takeUnless { wasNull() },
            crossingExchangeAt = crossingExchangeAt,
            crossingPriceJpy = getBigDecimal("crossing_price_jpy"),
            distanceJpy = getBigDecimal("distance_jpy"),
            dataQuality = ShadowDataQuality.valueOf(getString("data_quality")),
            resolvedAt = Instant.ofEpochMilli(getLong("resolved_at")),
        )
    }

    private companion object {
        const val INSERT_OBSERVATION_SQL = """
            INSERT INTO gate_shadow_observations (
                id, order_id, decision_id, opportunity_episode_id, geometry_hash,
                symbol, side, order_type, size_btc, limit_price_jpy, trigger_price_jpy,
                stop_price_jpy, take_profit_price_jpy, queue_ahead_btc, market_data_session_id,
                start_admission_ordinal, window_start_time, data_quality, observed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (order_id) DO NOTHING
        """

        const val SELECT_OBSERVATION_BY_ORDER_ID_SQL = """
            SELECT * FROM gate_shadow_observations WHERE order_id = ?
        """

        const val UPSERT_SCAN_PROGRESS_SQL = """
            INSERT INTO gate_shadow_scan_progress (
                observation_id, last_scanned_admission_ordinal, last_scanned_at
            ) VALUES (?, ?, ?)
            ON CONFLICT (observation_id) DO UPDATE SET
                last_scanned_admission_ordinal = GREATEST(
                    gate_shadow_scan_progress.last_scanned_admission_ordinal,
                    EXCLUDED.last_scanned_admission_ordinal
                ),
                last_scanned_at = CASE
                    WHEN EXCLUDED.last_scanned_admission_ordinal >=
                        gate_shadow_scan_progress.last_scanned_admission_ordinal
                    THEN EXCLUDED.last_scanned_at
                    ELSE gate_shadow_scan_progress.last_scanned_at
                END
        """

        const val SELECT_SCAN_PROGRESS_SQL = """
            SELECT * FROM gate_shadow_scan_progress WHERE observation_id = ?
        """

        const val INSERT_UNKNOWN_RESOLUTION_SQL = """
            INSERT INTO gate_shadow_resolutions (
                observation_id, outcome, crossing_event_sequence, crossing_exchange_at,
                crossing_price_jpy, distance_jpy, data_quality, resolved_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (observation_id) DO NOTHING
        """

        const val UPSERT_CROSSED_RESOLUTION_SQL = """
            INSERT INTO gate_shadow_resolutions (
                observation_id, outcome, crossing_event_sequence, crossing_exchange_at,
                crossing_price_jpy, distance_jpy, data_quality, resolved_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (observation_id) DO UPDATE SET
                outcome = 'CROSSED',
                crossing_event_sequence = EXCLUDED.crossing_event_sequence,
                crossing_exchange_at = EXCLUDED.crossing_exchange_at,
                crossing_price_jpy = EXCLUDED.crossing_price_jpy,
                distance_jpy = EXCLUDED.distance_jpy,
                data_quality = EXCLUDED.data_quality,
                resolved_at = EXCLUDED.resolved_at
        """

        const val SELECT_RESOLUTION_SQL = """
            SELECT * FROM gate_shadow_resolutions WHERE observation_id = ?
        """

        // cancel_reason は enum 名ではなく wire code で永続化されるため、reconciliation も wire code で照合する。
        val COUNT_MISSING_TTL_OBSERVATIONS_SQL = """
            SELECT COUNT(*)
            FROM orders AS orders
            LEFT JOIN gate_shadow_observations AS observations ON observations.order_id = orders.id
            WHERE orders.cancel_reason = '${PaperOrderCancelReason.TTL_EXPIRY.wireCode}'
                AND orders.side = 'BUY'
                AND orders.order_type IN ('LIMIT', 'STOP')
                AND observations.order_id IS NULL
        """

        const val SELECT_RECEIPT_SCAN_INDEX_READY_SQL = """
            SELECT 1
            FROM pg_index AS index_state
            JOIN pg_class AS index_relation ON index_relation.oid = index_state.indexrelid
            JOIN pg_namespace AS index_namespace ON index_namespace.oid = index_relation.relnamespace
            JOIN pg_class AS table_relation ON table_relation.oid = index_state.indrelid
            WHERE index_namespace.nspname = current_schema()
                AND table_relation.relname = 'paper_market_event_receipts'
                AND index_relation.relname = 'idx_paper_market_receipts_session_admission_ordinal'
                AND index_state.indisvalid
                AND index_state.indisready
                AND index_state.indislive
        """
    }
}
