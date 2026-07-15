@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.domain.PaperOrderCancelReason
import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.market.MarketDataGapReason
import me.matsumo.fukurou.trading.market.MarketDataIntegrityRepository
import me.matsumo.fukurou.trading.market.MarketDataIntegritySnapshot
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** PostgreSQL を正本とする market-data integrity repository。 */
class ExposedMarketDataIntegrityRepository(
    private val database: ExposedDatabase,
) : MarketDataIntegrityRepository {

    override suspend fun snapshot(): Result<MarketDataIntegritySnapshot> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) { selectMarketDataIntegritySnapshot() }
            }
        }
    }

    override suspend fun beginSession(sessionId: UUID, connectedAt: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    require(!hasConnectedMarketDataSession()) {
                        "A connected market-data session already exists."
                    }
                    prepare(
                        """
                            INSERT INTO market_data_sessions (
                                id, state, connected_at, last_processed_sequence, last_transport_activity_at
                            ) VALUES (?, 'CONNECTED', ?, 0, ?)
                        """,
                    ).use { statement ->
                        statement.setObject(1, sessionId)
                        statement.setLong(2, connectedAt.toEpochMilli())
                        statement.setLong(3, connectedAt.toEpochMilli())
                        statement.executeUpdate()
                    }
                    Unit
                }
            }
        }
    }

    override suspend fun markTransportActivity(sessionId: UUID, observedAt: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val updated = prepare(
                        """
                            UPDATE market_data_sessions
                            SET last_transport_activity_at = GREATEST(
                                COALESCE(last_transport_activity_at, ?),
                                ?
                            )
                            WHERE id = ? AND state = 'CONNECTED'
                        """,
                    ).use { statement ->
                        statement.setLong(1, observedAt.toEpochMilli())
                        statement.setLong(2, observedAt.toEpochMilli())
                        statement.setObject(3, sessionId)
                        statement.executeUpdate()
                    }
                    require(updated == 1) { "Connected market-data session was not found." }
                }
            }
        }
    }

    override suspend fun markMaintenanceSucceeded(sessionId: UUID, succeededAt: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val updated = prepare(
                        """
                            UPDATE market_data_sessions
                            SET last_maintenance_at = ?
                            WHERE id = ? AND state = 'CONNECTED'
                        """,
                    ).use { statement ->
                        statement.setLong(1, succeededAt.toEpochMilli())
                        statement.setObject(2, sessionId)
                        statement.executeUpdate()
                    }
                    require(updated == 1) { "Connected market-data session was not found." }
                }
            }
        }
    }

    override suspend fun recordGap(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String?,
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    applyMarketDataGap(sessionId, reason, detectedAt, detail)
                }
            }
        }
    }

    override suspend fun markDisconnected(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String?,
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    markMarketDataGap(sessionId, reason, detectedAt, detail)
                    Unit
                }
            }
        }
    }

    override suspend fun applyGapImpact(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val gapId = requireNotNull(selectGapId(sessionId)) { "market-data gap was not found." }
                    applyGapImpact(gapId, reason, detectedAt)
                }
            }
        }
    }

    override suspend fun recoverStaleSession(recoveredAt: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectConnectedMarketDataSessionIds().forEach { sessionId ->
                        applyMarketDataGap(
                            sessionId = sessionId,
                            reason = MarketDataGapReason.PROCESS_RESTART,
                            detectedAt = recoveredAt,
                            detail = "previous process ended before the WebSocket session was closed",
                        )
                    }
                    selectUnappliedMarketDataGaps().forEach { gap ->
                        applyGapImpact(gap.id, gap.reason, recoveredAt)
                    }
                }
            }
        }
    }
}

private data class UnappliedMarketDataGap(
    val id: UUID,
    val reason: MarketDataGapReason,
)

internal fun JdbcTransaction.selectMarketDataIntegritySnapshot(): MarketDataIntegritySnapshot {
    return prepare(
        """
            SELECT
                s.id,
                s.state,
                s.last_processed_sequence,
                s.last_transport_activity_at,
                s.last_trade_at,
                s.last_maintenance_at,
                g.started_at,
                g.recovered_at,
                g.reason
            FROM market_data_sessions s
            LEFT JOIN LATERAL (
                -- readiness と API は session 単位ではなく、全体で最後に観測した gap を表示する。
                SELECT started_at, recovered_at, reason
                FROM market_data_gaps
                ORDER BY started_at DESC
                LIMIT 1
            ) g ON TRUE
            ORDER BY s.connected_at DESC
            LIMIT 1
        """,
    ).use { statement ->
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                return MarketDataIntegritySnapshot(startupRecoveryCompleted = true)
            }

            MarketDataIntegritySnapshot(
                state = MarketDataConnectionState.valueOf(resultSet.getString("state")),
                sessionId = resultSet.getObject("id", UUID::class.java),
                lastProcessedSequence = resultSet.getLong("last_processed_sequence"),
                lastTransportActivityAt = resultSet.getLong("last_transport_activity_at")
                    .takeUnless { resultSet.wasNull() }
                    ?.let(Instant::ofEpochMilli),
                lastTradeAt = resultSet.getLong("last_trade_at")
                    .takeUnless { resultSet.wasNull() }
                    ?.let(Instant::ofEpochMilli),
                lastMaintenanceAt = resultSet.getLong("last_maintenance_at")
                    .takeUnless { resultSet.wasNull() }
                    ?.let(Instant::ofEpochMilli),
                gapStartedAt = resultSet.getLong("started_at")
                    .takeUnless { resultSet.wasNull() }
                    ?.let(Instant::ofEpochMilli),
                recoveredAt = resultSet.getLong("recovered_at")
                    .takeUnless { resultSet.wasNull() }
                    ?.let(Instant::ofEpochMilli),
                gapReason = resultSet.getString("reason")?.let(MarketDataGapReason::valueOf),
                startupRecoveryCompleted = true,
            )
        }
    }
}

private fun JdbcTransaction.hasConnectedMarketDataSession(): Boolean {
    return prepare("SELECT 1 FROM market_data_sessions WHERE state = 'CONNECTED' LIMIT 1").use { statement ->
        statement.executeQuery().use { resultSet -> resultSet.next() }
    }
}

private fun JdbcTransaction.selectConnectedMarketDataSessionIds(): List<UUID> {
    return prepare("SELECT id FROM market_data_sessions WHERE state = 'CONNECTED' FOR UPDATE").use { statement ->
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) add(resultSet.getObject("id", UUID::class.java))
            }
        }
    }
}

private fun JdbcTransaction.selectUnappliedMarketDataGaps(): List<UnappliedMarketDataGap> {
    return prepare(
        "SELECT id, reason FROM market_data_gaps WHERE impact_applied_at IS NULL FOR UPDATE",
    ).use { statement ->
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        UnappliedMarketDataGap(
                            id = resultSet.getObject("id", UUID::class.java),
                            reason = MarketDataGapReason.valueOf(resultSet.getString("reason")),
                        ),
                    )
                }
            }
        }
    }
}

private fun JdbcTransaction.applyMarketDataGap(
    sessionId: UUID,
    reason: MarketDataGapReason,
    detectedAt: Instant,
    detail: String?,
) {
    val gapId = markMarketDataGap(sessionId, reason, detectedAt, detail)
    applyGapImpact(gapId, reason, detectedAt)
}

private fun JdbcTransaction.markMarketDataGap(
    sessionId: UUID,
    reason: MarketDataGapReason,
    detectedAt: Instant,
    detail: String?,
): UUID {
    selectGapId(sessionId)?.let { gapId -> return gapId }

    val sessionUpdated = prepare(
        """
            UPDATE market_data_sessions
            SET state = 'DISCONNECTED', disconnected_at = ?, disconnect_reason = ?
            WHERE id = ?
        """,
    ).use { statement ->
        statement.setLong(1, detectedAt.toEpochMilli())
        statement.setString(2, reason.name)
        statement.setObject(3, sessionId)
        statement.executeUpdate()
    }
    require(sessionUpdated == 1) { "market-data session was not found." }

    val gapId = UUID.randomUUID()
    prepare(
        """
            INSERT INTO market_data_gaps (id, session_id, reason, detail, started_at)
            VALUES (?, ?, ?, ?, ?)
        """,
    ).use { statement ->
        statement.setObject(1, gapId)
        statement.setObject(2, sessionId)
        statement.setString(3, reason.name)
        statement.setString(4, detail?.take(512))
        statement.setLong(5, detectedAt.toEpochMilli())
        statement.executeUpdate()
    }
    return gapId
}

private fun JdbcTransaction.selectGapId(sessionId: UUID): UUID? {
    return prepare("SELECT id FROM market_data_gaps WHERE session_id = ? ORDER BY started_at DESC LIMIT 1 FOR UPDATE").use {
            statement ->
        statement.setObject(1, sessionId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getObject("id", UUID::class.java) else null
        }
    }
}

private fun JdbcTransaction.applyGapImpact(
    gapId: UUID,
    reason: MarketDataGapReason,
    detectedAt: Instant,
) {
    val alreadyApplied = prepare("SELECT impact_applied_at FROM market_data_gaps WHERE id = ? FOR UPDATE").use { statement ->
        statement.setObject(1, gapId)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "market-data gap was not found." }
            resultSet.getObject("impact_applied_at") != null
        }
    }
    if (alreadyApplied) return

    insertOrderAndRunExclusions(gapId, reason, detectedAt)
    insertPositionAndRunExclusions(gapId, reason, detectedAt)
    prepare(
        """
            UPDATE orders
            SET status = 'CANCELED',
                reason_ja = ?,
                canceled_at = ?,
                cancel_reason = ?,
                updated_at = ?
            WHERE status IN ('OPEN', 'PENDING_CANCEL')
                AND side = 'BUY'
                AND position_id IS NULL
        """,
    ).use { statement ->
        statement.setString(1, "market-data gap: ${reason.name}")
        statement.setLong(2, detectedAt.toEpochMilli())
        statement.setString(3, PaperOrderCancelReason.MARKET_DATA_GAP.wireCode)
        statement.setLong(4, detectedAt.toEpochMilli())
        statement.executeUpdate()
    }
    prepare("UPDATE market_data_gaps SET impact_applied_at = ? WHERE id = ?").use { statement ->
        statement.setLong(1, detectedAt.toEpochMilli())
        statement.setObject(2, gapId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertOrderAndRunExclusions(
    gapId: UUID,
    reason: MarketDataGapReason,
    at: Instant,
) {
    insertExclusionsFromQuery(
        gapId,
        reason,
        at,
        "ORDER",
        "SELECT id::text FROM orders WHERE status IN ('OPEN', 'PENDING_CANCEL') AND side = 'BUY' AND position_id IS NULL",
    )
    insertExclusionsFromQuery(
        gapId,
        reason,
        at,
        "DECISION_RUN",
        """
            SELECT DISTINCT decision_run_id FROM orders
            WHERE status IN ('OPEN', 'PENDING_CANCEL')
                AND side = 'BUY'
                AND position_id IS NULL
                AND decision_run_id IS NOT NULL
        """,
    )
}

private fun JdbcTransaction.insertPositionAndRunExclusions(
    gapId: UUID,
    reason: MarketDataGapReason,
    at: Instant,
) {
    insertExclusionsFromQuery(
        gapId,
        reason,
        at,
        "POSITION",
        "SELECT id::text FROM positions WHERE status = 'OPEN'",
    )
    insertExclusionsFromQuery(
        gapId,
        reason,
        at,
        "DECISION_RUN",
        "SELECT DISTINCT decision_run_id FROM positions WHERE status = 'OPEN' AND decision_run_id IS NOT NULL",
    )
    insertExclusionsFromQuery(
        gapId,
        reason,
        at,
        "DECISION_RUN",
        """
            SELECT DISTINCT entry.decision_run_id
            FROM orders entry
            INNER JOIN positions affected ON affected.trade_group_id = entry.trade_group_id
            WHERE affected.status = 'OPEN'
                AND entry.status = 'FILLED'
                AND entry.side = 'BUY'
                AND entry.decision_run_id IS NOT NULL
        """,
    )
}

private fun JdbcTransaction.insertExclusionsFromQuery(
    gapId: UUID,
    reason: MarketDataGapReason,
    at: Instant,
    entityType: String,
    entityQuery: String,
) {
    prepare(
        """
            INSERT INTO evaluation_exclusions (id, gap_id, entity_type, entity_id, reason, created_at)
            SELECT gen_random_uuid(), ?, ?, affected.entity_id, ?, ?
            FROM ($entityQuery) affected(entity_id)
            WHERE NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions existing
                WHERE existing.gap_id = ?
                    AND existing.entity_type = ?
                    AND existing.entity_id = affected.entity_id
            )
        """,
    ).use { statement ->
        statement.setObject(1, gapId)
        statement.setString(2, entityType)
        statement.setString(3, reason.name)
        statement.setLong(4, at.toEpochMilli())
        statement.setObject(5, gapId)
        statement.setString(6, entityType)
        statement.executeUpdate()
    }
}
