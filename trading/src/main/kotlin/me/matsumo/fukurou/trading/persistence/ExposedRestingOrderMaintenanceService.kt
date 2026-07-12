package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.daemon.LlmDaemonOpenRiskSnapshot
import me.matsumo.fukurou.trading.daemon.RestingOrderMaintenanceService
import me.matsumo.fukurou.trading.daemon.RestingSuppressionReason
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** production の resting-only tick を lock 下で再確認し append-only 監査する。 */
@Suppress("LongParameterList")
class ExposedRestingOrderMaintenanceService(
    private val database: Database,
    private val broker: Broker,
    private val tradingLock: TradingLock,
    private val latestMarketQuoteStore: LatestMarketQuoteStore,
) : RestingOrderMaintenanceService {
    override suspend fun maintain(
        snapshot: LlmDaemonOpenRiskSnapshot,
        observedAt: Instant,
    ): Result<RestingSuppressionReason> = runCatching {
        // Quote は lock 取得前に読む。store access は HTTP を伴わない。
        val quote = latestMarketQuoteStore.snapshot()

        tradingLock.withLock(LOCK_OWNER) {
            val openOrderIds = broker.getOpenOrders().getOrThrow().map { it.orderId }.toSet()
            val reference = snapshot.restingEntryOrders.singleOrNull()
            val stateChanged = reference == null || reference.orderId !in openOrderIds
            val identity = reference?.intentId?.let { intentId -> findIdentity(intentId) }
            val executablePrice = when (reference?.side) {
                OrderSide.BUY -> quote?.askPriceJpy
                OrderSide.SELL -> quote?.bidPriceJpy
                null -> null
            }
            val quoteStale = quote != null && Duration.between(quote.observedAt, observedAt) > MAX_QUOTE_AGE

            val reason = when {
                stateChanged -> RestingSuppressionReason.RESTING_ORDER_STATE_RACE
                identity == null -> RestingSuppressionReason.RESTING_ORDER_IDENTITY_UNAVAILABLE
                quote == null || executablePrice == null || quoteStale -> {
                    RestingSuppressionReason.RESTING_ORDER_QUOTE_UNAVAILABLE
                }
                else -> RestingSuppressionReason.RESTING_ORDER_UNCHANGED
            }
            appendObservation(
                identity = identity,
                orderId = reference?.orderId,
                entryPrice = reference?.limitPriceJpy,
                executablePrice = executablePrice,
                reason = reason,
                observedAt = observedAt,
            )
            reason
        }
    }

    private suspend fun findIdentity(intentId: String): RestingIdentity? = withContext(Dispatchers.IO) {
        runCatching { UUID.fromString(intentId) }.getOrNull()?.let { id ->
            transaction(database) {
                jdbcConnection().prepareStatement(
                    "SELECT opportunity_episode_id, material_state_hash FROM trade_intents WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            null
                        } else {
                            val episodeId = result.getObject(1, UUID::class.java) ?: return@use null
                            val materialHash = result.getString(2) ?: return@use null
                            RestingIdentity(episodeId, materialHash)
                        }
                    }
                }
            }
        }
    }

    private suspend fun appendObservation(
        identity: RestingIdentity?,
        orderId: String?,
        entryPrice: String?,
        executablePrice: BigDecimal?,
        reason: RestingSuppressionReason,
        observedAt: Instant,
    ) = withContext(Dispatchers.IO) {
        val entry = entryPrice?.toBigDecimalOrNull()
        val distance = if (entry != null && executablePrice != null) executablePrice.subtract(entry) else null
        val bps = if (distance != null && entry?.signum() != 0) {
            distance.multiply(BPS).divide(entry, 8, RoundingMode.HALF_UP)
        } else {
            null
        }
        transaction(database) {
            appendObservationRow(identity, orderId, executablePrice, reason, observedAt, distance, bps)
        }
    }

    private fun JdbcTransaction.appendObservationRow(
        identity: RestingIdentity?,
        orderId: String?,
        executablePrice: BigDecimal?,
        reason: RestingSuppressionReason,
        observedAt: Instant,
        distance: BigDecimal?,
        bps: BigDecimal?,
    ) {
        val sql = """INSERT INTO dedupe_shadow_observations
            (id, observation_kind, opportunity_episode_id, classification, suppression_reason,
             reference_order_id, old_material_state_hash, new_material_state_hash,
             invalidation_state, distance_jpy, signed_distance_bps, data_quality, observed_at)
            VALUES (?, 'RESTING_MAINTENANCE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val observationId = UUID.randomUUID()
        jdbcConnection().prepareStatement(sql).use { statement ->
            statement.setObject(1, observationId)
            statement.setObject(2, identity?.episodeId)
            statement.setString(3, if (reason == RestingSuppressionReason.RESTING_ORDER_UNCHANGED) "MAINTAIN_PENDING" else "REVISE")
            statement.setString(4, reason.wireCode)
            statement.setObject(5, orderId?.let { runCatching { UUID.fromString(it) }.getOrNull() })
            statement.setString(6, identity?.materialStateHash)
            statement.setString(7, identity?.materialStateHash)
            statement.setString(8, if (reason == RestingSuppressionReason.RESTING_ORDER_QUOTE_UNAVAILABLE) "UNKNOWN_DATA" else "VALID")
            statement.setBigDecimal(9, distance?.abs())
            statement.setBigDecimal(10, bps)
            statement.setString(11, if (executablePrice == null) "UNKNOWN_DATA" else "COMPLETE")
            statement.setLong(12, observedAt.toEpochMilli())
            statement.executeUpdate()
        }
        val resolution = when {
            reason == RestingSuppressionReason.RESTING_ORDER_UNCHANGED -> "PENDING"
            reason == RestingSuppressionReason.RESTING_ORDER_MATERIAL_CHANGED -> "FALSE_SUPPRESSION_PROXY"
            reason == RestingSuppressionReason.RESTING_ORDER_INVALIDATED -> "FALSE_SUPPRESSION_PROXY"
            else -> "UNKNOWN_DATA"
        }
        jdbcConnection().prepareStatement(
            "INSERT INTO dedupe_shadow_resolutions (id, observation_id, resolution, resolved_at) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, observationId)
            statement.setString(3, resolution)
            statement.setLong(4, observedAt.toEpochMilli())
            statement.executeUpdate()
        }
    }

    private data class RestingIdentity(val episodeId: UUID, val materialStateHash: String)

    private companion object {
        const val LOCK_OWNER = "llm-daemon-resting-maintenance"
        val MAX_QUOTE_AGE: Duration = Duration.ofMinutes(1)
        val BPS: BigDecimal = BigDecimal("10000")
    }
}
