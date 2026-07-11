package me.matsumo.fukurou.trading.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatusProvider
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * 最新の reconciler 完了 event を読む SQL。
 */
private const val SELECT_LATEST_RECONCILER_STATUS_SQL = """
    SELECT
        ts,
        payload
    FROM command_event_log
    WHERE event_type = ?
    ORDER BY ts DESC
    LIMIT 1
"""

/**
 * Reconciler status payload の JSON 設定。
 */
private val ReconcilerStatusJson = Json {
    ignoreUnknownKeys = true
}

/**
 * command_event_log から ProtectionReconciler の最新状態を読む provider。
 *
 * @param database Exposed database
 */
class ExposedReconcilerStatusProvider(
    private val database: ExposedDatabase,
) : ReconcilerStatusProvider {

    override fun snapshot(): ReconcilerStatus {
        return exposedTransaction(database) {
            val integrity = selectMarketDataIntegritySnapshot()

            if (integrity.sessionId == null) {
                return@exposedTransaction selectLatestReconcilerStatus()
            }

            ReconcilerStatus(
                lastReconciledAt = integrity.lastMaintenanceAt,
                startupFullReconcileCompleted = integrity.startupRecoveryCompleted,
                lastTransportActivityAt = integrity.lastTransportActivityAt,
                lastTradeAt = integrity.lastTradeAt,
                lastMaintenanceAt = integrity.lastMaintenanceAt,
                marketDataState = integrity.state,
                marketDataSessionId = integrity.sessionId,
                lastProcessedSequence = integrity.lastProcessedSequence,
                gapStartedAt = integrity.gapStartedAt,
                recoveredAt = integrity.recoveredAt,
                gapReason = integrity.gapReason,
                startupRecoveryCompleted = integrity.startupRecoveryCompleted,
            )
        }
    }
}

/**
 * 最新の reconciler status snapshot を読む。
 */
internal fun JdbcTransaction.selectLatestReconcilerStatus(): ReconcilerStatus {
    return jdbcConnection().prepareStatement(SELECT_LATEST_RECONCILER_STATUS_SQL).use { statement ->
        statement.setString(1, CommandEventType.RECONCILER_PASS_COMPLETED.name)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                return ReconcilerStatus()
            }

            val payloadObject = parsePayloadObject(resultSet.getString("payload"))
            val eventTimestamp = Instant.ofEpochMilli(resultSet.getLong("ts"))

            ReconcilerStatus(
                lastReconciledAt = payloadObject.instantOrNull("lastReconciledAt") ?: eventTimestamp,
                startupFullReconcileCompleted = payloadObject.booleanOrFalse("startupFullReconcileCompleted"),
                lastMaintenanceAt = payloadObject.instantOrNull("lastMaintenanceAt"),
            )
        }
    }
}

private fun parsePayloadObject(payload: String): JsonObject? {
    return runCatching {
        ReconcilerStatusJson.parseToJsonElement(payload).jsonObject
    }.getOrNull()
}

private fun JsonObject?.instantOrNull(key: String): Instant? {
    val value = this
        ?.get(key)
        ?.jsonPrimitive
        ?.contentOrNull

    return value?.let { instantText ->
        runCatching { Instant.parse(instantText) }.getOrNull()
    }
}

private fun JsonObject?.booleanOrFalse(key: String): Boolean {
    return this
        ?.get(key)
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toBooleanStrictOrNull()
        ?: false
}
