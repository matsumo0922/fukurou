@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.domain.PaperOrderCancelReason
import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.market.MarketDataGapReason
import me.matsumo.fukurou.trading.market.MarketDataIntegrityRepository
import me.matsumo.fukurou.trading.market.MarketDataIntegritySnapshot
import me.matsumo.fukurou.trading.market.MarketEventReceiptIntegrityConflictException
import me.matsumo.fukurou.trading.market.MarketEventReceiptPersistenceException
import me.matsumo.fukurou.trading.market.PaperMarketEventReceiptCommit
import me.matsumo.fukurou.trading.market.PaperMarketEventReceiptRepository
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.nio.ByteBuffer
import java.security.MessageDigest
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

/** PostgreSQL の独立 transaction で durable market-event receipt を保存する repository。 */
class ExposedPaperMarketEventReceiptRepository(
    private val database: ExposedDatabase,
    private val nanoTime: () -> Long = System::nanoTime,
) : PaperMarketEventReceiptRepository {

    override suspend fun commit(event: PaperMarketTradeEvent): Result<PaperMarketEventReceiptCommit> {
        val transactionStartedAtNanos = nanoTime()

        return withContext(Dispatchers.IO) {
            try {
                val commit = exposedTransaction(database) {
                    insertPaperMarketEventReceipt(event, nanoTime)
                }
                Result.success(
                    commit.copy(
                        transactionDurationNanos = elapsedNanos(transactionStartedAtNanos, nanoTime()),
                    ),
                )
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: MarketEventReceiptIntegrityConflictException) {
                Result.failure(throwable)
            } catch (throwable: Throwable) {
                Result.failure(MarketEventReceiptPersistenceException(throwable))
            }
        }
    }
}

private fun JdbcTransaction.insertPaperMarketEventReceipt(
    event: PaperMarketTradeEvent,
    nanoTime: () -> Long,
): PaperMarketEventReceiptCommit {
    val normalizedPayload = event.normalizedReceiptPayload()
    val payloadHash = normalizedPayload.sha256()
    val advisoryWaitStartedAtNanos = nanoTime()

    acquireSharedPaperMarketSessionLock(event.connectionSessionId)
    val advisoryWaitNanos = elapsedNanos(advisoryWaitStartedAtNanos, nanoTime())
    acquirePaperMarketSourceIdentityLock(event.connectionSessionId, event.sequence)

    selectPaperMarketEventReceipt(event.connectionSessionId, event.sequence)?.let { existing ->
        if (existing.payloadHash != payloadHash) throw MarketEventReceiptIntegrityConflictException()

        return existing.toCommit(
            duplicate = true,
            advisoryWaitNanos = advisoryWaitNanos,
        )
    }

    val receiptId = UUID.randomUUID()
    val admissionOrdinal = nextPaperMarketAdmissionOrdinal()
    prepare(
        """
            INSERT INTO paper_market_event_receipts (
                id, session_id, source_sequence, source_timestamp, socket_observed_at,
                normalized_payload, payload_hash, admission_ordinal, advisory_wait_nanos, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT)
        """,
    ).use { statement ->
        statement.setObject(1, receiptId)
        statement.setObject(2, event.connectionSessionId)
        statement.setLong(3, event.sequence)
        statement.setLong(4, event.exchangeAt.toEpochMilli())
        statement.setLong(5, event.receivedAt.toEpochMilli())
        statement.setString(6, normalizedPayload)
        statement.setString(7, payloadHash)
        statement.setLong(8, admissionOrdinal)
        statement.setLong(9, advisoryWaitNanos)
        check(statement.executeUpdate() == 1) { "paper market-event receipt insert did not affect one row." }
    }

    return PaperMarketEventReceiptCommit(
        receiptId = receiptId,
        admissionOrdinal = admissionOrdinal,
        payloadHash = payloadHash,
        duplicate = false,
        transactionDurationNanos = 0,
        advisoryWaitNanos = advisoryWaitNanos,
    )
}

private fun JdbcTransaction.acquireSharedPaperMarketSessionLock(sessionId: UUID) {
    prepare("SELECT pg_advisory_xact_lock_shared(?)").use { statement ->
        statement.setLong(1, paperMarketSessionAdvisoryLockKey(sessionId))
        statement.executeQuery().use { rows -> check(rows.next()) }
    }
}

private fun JdbcTransaction.acquirePaperMarketSourceIdentityLock(sessionId: UUID, sourceSequence: Long) {
    prepare("SELECT pg_advisory_xact_lock(?)").use { statement ->
        statement.setLong(1, paperMarketSourceIdentityLockKey(sessionId, sourceSequence))
        statement.executeQuery().use { rows -> check(rows.next()) }
    }
}

private fun JdbcTransaction.selectPaperMarketEventReceipt(
    sessionId: UUID,
    sourceSequence: Long,
): StoredPaperMarketEventReceipt? {
    return prepare(
        """
            SELECT id, admission_ordinal, payload_hash
            FROM paper_market_event_receipts
            WHERE session_id = ? AND source_sequence = ?
            FOR UPDATE
        """,
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.setLong(2, sourceSequence)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null

            StoredPaperMarketEventReceipt(
                receiptId = rows.getObject("id", UUID::class.java),
                admissionOrdinal = rows.getLong("admission_ordinal"),
                payloadHash = rows.getString("payload_hash"),
            )
        }
    }
}

private fun JdbcTransaction.nextPaperMarketAdmissionOrdinal(): Long {
    return prepare("SELECT nextval('paper_market_admission_ordinal_seq')").use { statement ->
        statement.executeQuery().use { rows ->
            check(rows.next()) { "paper market admission ordinal was not returned." }
            rows.getLong(1)
        }
    }
}

private fun PaperMarketTradeEvent.normalizedReceiptPayload(): String {
    val payload = buildJsonObject {
        put("exchangeAt", exchangeAt.toString())
        put("priceJpy", priceJpy.stripTrailingZeros().toPlainString())
        put("side", side.name)
        put("sizeBtc", sizeBtc.stripTrailingZeros().toPlainString())
        put("symbol", symbol.apiSymbol)
    }.toString()
    check(payload.length <= MAX_NORMALIZED_RECEIPT_PAYLOAD_LENGTH) {
        "normalized paper market-event receipt payload exceeded its bound."
    }

    return payload
}

internal fun paperMarketSessionAdvisoryLockKey(sessionId: UUID): Long {
    return "paper-market-session:$sessionId".stableAdvisoryLockKey()
}

private fun paperMarketSourceIdentityLockKey(sessionId: UUID, sourceSequence: Long): Long {
    return "paper-market-source:$sessionId:$sourceSequence".stableAdvisoryLockKey()
}

private fun String.stableAdvisoryLockKey(): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))

    return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
}

private fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun elapsedNanos(startedAtNanos: Long, finishedAtNanos: Long): Long {
    return (finishedAtNanos - startedAtNanos).coerceAtLeast(0)
}

private data class StoredPaperMarketEventReceipt(
    val receiptId: UUID,
    val admissionOrdinal: Long,
    val payloadHash: String,
) {
    fun toCommit(duplicate: Boolean, advisoryWaitNanos: Long): PaperMarketEventReceiptCommit {
        return PaperMarketEventReceiptCommit(
            receiptId = receiptId,
            admissionOrdinal = admissionOrdinal,
            payloadHash = payloadHash,
            duplicate = duplicate,
            transactionDurationNanos = 0,
            advisoryWaitNanos = advisoryWaitNanos,
        )
    }
}

private const val MAX_NORMALIZED_RECEIPT_PAYLOAD_LENGTH = 512

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
