package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.daemon.LlmDaemonOpenRiskSnapshot
import me.matsumo.fukurou.trading.daemon.OpportunityEpisodeLifecycleObserver
import me.matsumo.fukurou.trading.daemon.RestingOrderMaintenanceService
import me.matsumo.fukurou.trading.daemon.RestingSuppressionReason
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationState
import me.matsumo.fukurou.trading.decision.evaluateInvalidationPredicates
import me.matsumo.fukurou.trading.decision.identity.DecisionIdentityGenerator
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialProjection
import me.matsumo.fukurou.trading.decision.identity.MaterialFreshness
import me.matsumo.fukurou.trading.domain.Order
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
class ExposedRestingOrderMaintenanceService(
    private val database: Database,
    private val broker: Broker,
    private val tradingLock: TradingLock,
    private val latestMarketQuoteStore: LatestMarketQuoteStore,
    private val maxQuoteAge: Duration = Duration.ofMinutes(1),
) : RestingOrderMaintenanceService, OpportunityEpisodeLifecycleObserver {
    override suspend fun observe(observedAt: Instant): Result<Unit> = runCatching {
        tradingLock.withLock(LIFECYCLE_LOCK_OWNER) {
            withContext(Dispatchers.IO) {
                transaction(database) { closePersistedTerminalEpisodes(observedAt) }
            }
        }
    }
    override suspend fun maintain(
        snapshot: LlmDaemonOpenRiskSnapshot,
        observedAt: Instant,
    ): Result<RestingSuppressionReason> = runCatching {
        // Atomic store の読み取りだけを lock 前に行う。HTTP / LLM は呼ばない。
        val quote = latestMarketQuoteStore.snapshot()

        tradingLock.withLock(LOCK_OWNER) {
            val maintenanceTickId = UUID.randomUUID()
            val positionsAppeared = broker.getPositions().getOrThrow().isNotEmpty()
            val openOrderIds = broker.getOpenOrders().getOrThrow().map(Order::orderId).toSet()
            val riskState = findRiskState()
            val observations = snapshot.restingEntryOrders.map { reference ->
                evaluateOrder(
                    reference = reference,
                    maintenanceTickId = maintenanceTickId,
                    riskState = riskState,
                    orderStillOpen = reference.orderId in openOrderIds && !positionsAppeared,
                    quote = quote,
                    observedAt = observedAt,
                )
            }
            observations.forEach { observation ->
                appendObservation(observation)
                observeTerminalLifecycle(observation)
            }
            observations.aggregateReason()
        }
    }

    @Suppress("LongParameterList")
    private suspend fun evaluateOrder(
        reference: Order,
        maintenanceTickId: UUID,
        riskState: String,
        orderStillOpen: Boolean,
        quote: me.matsumo.fukurou.trading.reconciler.LatestMarketQuote?,
        observedAt: Instant,
    ): MaintenanceObservation {
        val identity = reference.intentId?.let { intentId -> findIdentity(intentId) }
        val executablePrice = reference.executablePrice(quote?.bidPriceJpy, quote?.askPriceJpy)
        val quoteStale = quote != null && Duration.between(quote.observedAt, observedAt) > maxQuoteAge
        val distance = reference.distanceFromEntry(executablePrice)
        val baseline = identity?.episodeId?.let { episodeId -> findBaseline(episodeId, reference.orderId) }
        val priceApproached = reference.priceApproached(
            distance = distance,
            baseline = baseline?.distanceJpy,
            threshold = identity?.priceMoveThresholdRatio,
        )
        val currentAtrRatio = quote?.atr14Jpy?.let { atr ->
            executablePrice?.takeUnless { it.signum() == 0 }?.let { price ->
                atr.divide(price, 12, RoundingMode.HALF_UP)
            }
        }
        val volatilityChanged = hasVolatilityChanged(
            baselineAtrRatio = baseline?.atrPriceRatio,
            currentAtrRatio = currentAtrRatio,
            thresholdRatio = identity?.priceMoveThresholdRatio,
        )
        val materialChanged = priceApproached == true || volatilityChanged == true
        val invalidationState = identity?.let { resolvedIdentity ->
            evaluateInvalidationPredicates(
                predicates = resolvedIdentity.predicates,
                lastPriceJpy = if (quoteStale) null else quote?.lastPriceJpy,
                bestBidJpy = if (quoteStale) null else quote?.bidPriceJpy,
                bestAskJpy = if (quoteStale) null else quote?.askPriceJpy,
                observedAt = observedAt,
                materialStateChanged = materialChanged,
            )
        } ?: TradePlanInvalidationState.UNKNOWN_DATA
        val reason = resolveReason(
            stateChanged = !orderStillOpen,
            identityAvailable = identity != null,
            quoteAvailable = quote != null && executablePrice != null && !quoteStale,
            materialChanged = materialChanged,
            invalidationState = invalidationState,
        )
        return MaintenanceObservation(
            maintenanceTickId = maintenanceTickId,
            identity = identity,
            orderId = reference.orderId,
            executablePrice = executablePrice,
            entryPrice = reference.limitPriceJpy?.toBigDecimalOrNull(),
            distance = distance,
            reason = reason,
            invalidationState = invalidationState,
            quoteStale = quoteStale,
            priceApproached = priceApproached,
            volatilityChanged = volatilityChanged,
            atrPriceRatio = currentAtrRatio,
            atr14Jpy = quote?.atr14Jpy,
            bestBidJpy = quote?.bidPriceJpy,
            bestAskJpy = quote?.askPriceJpy,
            riskState = riskState,
            observedAt = observedAt,
        )
    }

    private fun List<MaintenanceObservation>.aggregateReason(): RestingSuppressionReason {
        val priority = listOf(
            RestingSuppressionReason.RESTING_ORDER_STATE_RACE,
            RestingSuppressionReason.RESTING_ORDER_QUOTE_UNAVAILABLE,
            RestingSuppressionReason.RESTING_ORDER_IDENTITY_UNAVAILABLE,
            RestingSuppressionReason.RESTING_ORDER_INVALIDATED,
            RestingSuppressionReason.RESTING_ORDER_MATERIAL_CHANGED,
            RestingSuppressionReason.RESTING_ORDER_UNCHANGED,
        )
        return priority.first { reason -> any { observation -> observation.reason == reason } }
    }

    private fun resolveReason(
        stateChanged: Boolean,
        identityAvailable: Boolean,
        quoteAvailable: Boolean,
        materialChanged: Boolean,
        invalidationState: TradePlanInvalidationState,
    ): RestingSuppressionReason = when {
        stateChanged -> RestingSuppressionReason.RESTING_ORDER_STATE_RACE
        !identityAvailable -> RestingSuppressionReason.RESTING_ORDER_IDENTITY_UNAVAILABLE
        !quoteAvailable -> RestingSuppressionReason.RESTING_ORDER_QUOTE_UNAVAILABLE
        invalidationState == TradePlanInvalidationState.INVALIDATED -> {
            RestingSuppressionReason.RESTING_ORDER_INVALIDATED
        }
        materialChanged -> RestingSuppressionReason.RESTING_ORDER_MATERIAL_CHANGED
        else -> RestingSuppressionReason.RESTING_ORDER_UNCHANGED
    }

    private suspend fun findIdentity(intentId: String): RestingIdentity? = withContext(Dispatchers.IO) {
        runCatching { UUID.fromString(intentId) }.getOrNull()?.let { id ->
            transaction(database) {
                val sql = """SELECT ti.opportunity_episode_id, ti.material_state_hash,
                    tp.invalidation_predicates, e.price_move_threshold_ratio,
                    (SELECT first_ti.price_jpy FROM trade_intents first_ti JOIN decisions d ON d.id=first_ti.decision_id
                     WHERE first_ti.opportunity_episode_id=e.id AND first_ti.price_jpy IS NOT NULL
                     ORDER BY d.created_at, d.id LIMIT 1)
                    FROM trade_intents ti JOIN trade_plans tp ON tp.id = ti.trade_plan_id
                    JOIN opportunity_episodes e ON e.id=ti.opportunity_episode_id
                    WHERE ti.id = ?
                """.trimIndent()
                jdbcConnection().prepareStatement(sql).use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            null
                        } else {
                            val episodeId = result.getObject(1, UUID::class.java) ?: return@use null
                            val materialHash = result.getString(2) ?: return@use null
                            RestingIdentity(
                                episodeId = episodeId,
                                materialStateHash = materialHash,
                                predicates = TradePlanInvalidationPredicateCodec.decode(result.getString(3)),
                                priceMoveThresholdRatio = result.getBigDecimal(4),
                                anchorPriceJpy = result.getBigDecimal(5),
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun findBaseline(episodeId: UUID, orderId: String?): EpisodeBaseline? =
        withContext(Dispatchers.IO) {
            val referenceOrderId = orderId.toUuidOrNull() ?: return@withContext null
            transaction(database) {
                jdbcConnection().prepareStatement(
                    """SELECT
                      (SELECT distance_jpy FROM dedupe_shadow_observations
                       WHERE opportunity_episode_id=? AND reference_order_id=? AND distance_jpy IS NOT NULL
                       AND data_quality='COMPLETE' ORDER BY observed_at, id LIMIT 1),
                      (SELECT atr_price_ratio FROM dedupe_shadow_observations
                       WHERE opportunity_episode_id=? AND reference_order_id=? AND atr_price_ratio IS NOT NULL
                       AND data_quality='COMPLETE' ORDER BY observed_at, id LIMIT 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, episodeId)
                    statement.setObject(2, referenceOrderId)
                    statement.setObject(3, episodeId)
                    statement.setObject(4, referenceOrderId)
                    statement.executeQuery().use { result ->
                        if (result.next()) EpisodeBaseline(result.getBigDecimal(1), result.getBigDecimal(2)) else null
                    }
                }
            }
        }

    private suspend fun appendObservation(observation: MaintenanceObservation) = withContext(Dispatchers.IO) {
        transaction(database) { appendObservationRow(observation) }
    }

    private fun findRiskState(): String = transaction(database) {
        jdbcConnection().prepareStatement("SELECT state FROM risk_state WHERE id=1").use { statement ->
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else "UNKNOWN" }
        }
    }

    @Suppress("LongMethod")
    private fun JdbcTransaction.appendObservationRow(observation: MaintenanceObservation) {
        val observationId = UUID.randomUUID()
        val identity = observation.identity
        val resolution = observation.resolution()
        val materialChanged = observation.reason in setOf(
            RestingSuppressionReason.RESTING_ORDER_MATERIAL_CHANGED,
            RestingSuppressionReason.RESTING_ORDER_INVALIDATED,
        )
        val newMaterialHash = if (materialChanged && identity != null) {
            val projection = DecisionMaterialProjection(
                riskState = observation.riskState,
                freshness = if (observation.quoteStale) MaterialFreshness.STALE else MaterialFreshness.FRESH,
                hasOpenPosition = false,
                hasOpenOrder = true,
                anchorPriceJpy = identity.anchorPriceJpy,
                currentPriceJpy = observation.executablePrice,
                atr14Jpy = observation.atr14Jpy,
                bestBidJpy = observation.bestBidJpy,
                bestAskJpy = observation.bestAskJpy,
                invalidationState = observation.invalidationState,
            ).canonical(identity.priceMoveThresholdRatio)
            "mat_v1_${DecisionIdentityGenerator.contentHash(projection)}"
        } else {
            identity?.materialStateHash
        }
        val sql = """INSERT INTO dedupe_shadow_observations
            (id, observation_kind, opportunity_episode_id, classification, suppression_reason,
             maintenance_tick_id, reference_order_id, old_material_state_hash, new_material_state_hash,
             invalidation_state, distance_jpy, signed_distance_bps, atr_price_ratio, data_quality, observed_at)
            VALUES (?, 'RESTING_MAINTENANCE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        jdbcConnection().prepareStatement(sql).use { statement ->
            statement.setObject(1, observationId)
            statement.setObject(2, identity?.episodeId)
            statement.setString(
                3,
                when (resolution) {
                    "FALSE_SUPPRESSION_PROXY" -> "REVISE"
                    "PENDING" -> "MAINTAIN_PENDING"
                    else -> null
                },
            )
            statement.setString(4, observation.reason.wireCode)
            statement.setObject(5, observation.maintenanceTickId)
            statement.setObject(6, observation.orderId.toUuidOrNull())
            statement.setString(7, identity?.materialStateHash)
            statement.setString(8, newMaterialHash)
            statement.setString(9, observation.invalidationState.name)
            statement.setBigDecimal(10, observation.distance?.abs())
            statement.setBigDecimal(11, observation.signedDistanceBps())
            statement.setBigDecimal(12, observation.atrPriceRatio)
            statement.setString(13, observation.dataQuality())
            statement.setLong(14, observation.observedAt.toEpochMilli())
            statement.executeUpdate()
        }
        appendResolution(
            observationId = observationId,
            resolution = resolution,
            resolvedAt = observation.observedAt,
        )
    }

    private suspend fun observeTerminalLifecycle(observation: MaintenanceObservation) = withContext(Dispatchers.IO) {
        val identity = observation.identity ?: return@withContext
        transaction(database) {
            val terminalReason = when {
                observation.invalidationState == TradePlanInvalidationState.INVALIDATED -> "TYPED_INVALIDATION"
                hasFilledEntry(identity.episodeId) -> "ENTRY_FILL"
                hasExplicitCancellation(identity.episodeId) -> "NON_TTL_CANCEL"
                else -> null
            } ?: return@transaction
            val closed = jdbcConnection().prepareStatement(
                """UPDATE opportunity_episodes SET closed_at = ?, close_reason = ?
                    WHERE id = ? AND closed_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, observation.observedAt.toEpochMilli())
                statement.setString(2, terminalReason)
                statement.setObject(3, identity.episodeId)
                statement.executeUpdate() == 1
            }
            if (closed) resolveTerminalEpisode(identity.episodeId, observation.observedAt)
        }
    }

    private fun JdbcTransaction.hasFilledEntry(episodeId: UUID): Boolean {
        return episodeHasOrder(episodeId, "o.status = 'FILLED'")
    }

    private fun JdbcTransaction.closePersistedTerminalEpisodes(observedAt: Instant) {
        val sql = """SELECT e.id,
          CASE
            WHEN EXISTS (SELECT 1 FROM orders o JOIN trade_intents ti ON ti.id=o.intent_id
              WHERE ti.opportunity_episode_id=e.id AND o.status='FILLED') THEN 'ENTRY_FILL'
            WHEN EXISTS (SELECT 1 FROM orders o JOIN trade_intents ti ON ti.id=o.intent_id
              WHERE ti.opportunity_episode_id=e.id AND o.status='CANCELED'
              AND o.cancel_reason NOT IN ('resting_entry_order_ttl_expired','legacy_ttl_sweep')) THEN 'NON_TTL_CANCEL'
          END
          FROM opportunity_episodes e WHERE e.closed_at IS NULL
        """.trimIndent()
        val terminals = jdbcConnection().prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        result.getString(2)?.let { reason ->
                            add(result.getObject(1, UUID::class.java) to reason)
                        }
                    }
                }
            }
        }
        terminals.forEach { (episodeId, reason) ->
            val closed = jdbcConnection().prepareStatement(
                "UPDATE opportunity_episodes SET closed_at=?, close_reason=? WHERE id=? AND closed_at IS NULL",
            ).use { statement ->
                statement.setLong(1, observedAt.toEpochMilli())
                statement.setString(2, reason)
                statement.setObject(3, episodeId)
                statement.executeUpdate() == 1
            }
            if (closed) resolveTerminalEpisode(episodeId, observedAt)
        }
    }

    private fun JdbcTransaction.hasExplicitCancellation(episodeId: UUID): Boolean {
        return episodeHasOrder(
            episodeId,
            "o.status = 'CANCELED' AND o.cancel_reason NOT IN " +
                "('resting_entry_order_ttl_expired','legacy_ttl_sweep')",
        )
    }

    private fun JdbcTransaction.episodeHasOrder(episodeId: UUID, predicate: String): Boolean {
        val sql = """SELECT 1 FROM orders o JOIN trade_intents ti ON ti.id = o.intent_id
            WHERE ti.opportunity_episode_id = ? AND $predicate LIMIT 1
        """.trimIndent()
        return jdbcConnection().prepareStatement(sql).use { statement ->
            statement.setObject(1, episodeId)
            statement.executeQuery().use { result -> result.next() }
        }
    }

    private fun JdbcTransaction.resolveTerminalEpisode(episodeId: UUID, resolvedAt: Instant) {
        val sql = """SELECT MIN(id::text)::uuid,
            BOOL_OR(invalidation_state = 'INVALIDATED' OR old_material_state_hash IS DISTINCT FROM new_material_state_hash),
            BOOL_OR(data_quality <> 'COMPLETE' OR invalidation_state = 'UNKNOWN_DATA')
            FROM dedupe_shadow_observations WHERE opportunity_episode_id = ?
        """.trimIndent()
        val terminal = jdbcConnection().prepareStatement(sql).use { statement ->
            statement.setObject(1, episodeId)
            statement.executeQuery().use { result ->
                if (!result.next()) return
                Triple(result.getObject(1, UUID::class.java), result.getBoolean(2), result.getBoolean(3))
            }
        }
        val resolution = when {
            terminal.second -> "FALSE_SUPPRESSION_PROXY"
            terminal.third -> "UNKNOWN_DATA"
            else -> "VALID_SUPPRESSION_PROXY"
        }
        appendResolution(terminal.first, resolution, resolvedAt)
    }

    private fun JdbcTransaction.appendResolution(
        observationId: UUID,
        resolution: String,
        resolvedAt: Instant,
    ) {
        jdbcConnection().prepareStatement(
            "INSERT INTO dedupe_shadow_resolutions (id, observation_id, resolution, resolved_at) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, observationId)
            statement.setString(3, resolution)
            statement.setLong(4, resolvedAt.toEpochMilli())
            statement.executeUpdate()
        }
    }

    private fun Order?.executablePrice(bid: BigDecimal?, ask: BigDecimal?): BigDecimal? = when (this?.side) {
        OrderSide.BUY -> ask
        OrderSide.SELL -> bid
        null -> null
    }

    private fun Order?.distanceFromEntry(executablePrice: BigDecimal?): BigDecimal? {
        val entry = this?.limitPriceJpy?.toBigDecimalOrNull() ?: return null
        return executablePrice?.subtract(entry)
    }

    private fun Order?.priceApproached(
        distance: BigDecimal?,
        baseline: BigDecimal?,
        threshold: BigDecimal?,
    ): Boolean? {
        val entry = this?.limitPriceJpy?.toBigDecimalOrNull() ?: return null
        return threshold?.let { ratio -> hasPriceApproached(entry, baseline, distance, ratio) }
    }

    private fun String?.toUuidOrNull(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private data class RestingIdentity(
        val episodeId: UUID,
        val materialStateHash: String,
        val predicates: List<TradePlanInvalidationPredicate>,
        val priceMoveThresholdRatio: BigDecimal,
        val anchorPriceJpy: BigDecimal?,
    )

    private data class MaintenanceObservation(
        val maintenanceTickId: UUID,
        val identity: RestingIdentity?,
        val orderId: String?,
        val executablePrice: BigDecimal?,
        val entryPrice: BigDecimal?,
        val distance: BigDecimal?,
        val reason: RestingSuppressionReason,
        val invalidationState: TradePlanInvalidationState,
        val quoteStale: Boolean,
        val priceApproached: Boolean?,
        val volatilityChanged: Boolean?,
        val atrPriceRatio: BigDecimal?,
        val atr14Jpy: BigDecimal?,
        val bestBidJpy: BigDecimal?,
        val bestAskJpy: BigDecimal?,
        val riskState: String,
        val observedAt: Instant,
    ) {
        fun signedDistanceBps(): BigDecimal? {
            val price = entryPrice?.takeUnless { it.signum() == 0 } ?: return null
            return distance?.multiply(BPS)?.divide(price, 8, RoundingMode.HALF_UP)
        }

        fun dataQuality(): String = when {
            quoteStale -> "QUOTE_STALE"
            executablePrice == null -> "UNKNOWN_DATA"
            identity == null -> "IDENTITY_UNAVAILABLE"
            else -> "COMPLETE"
        }

        fun resolution(): String = when {
            priceApproached == true || volatilityChanged == true ||
                invalidationState == TradePlanInvalidationState.INVALIDATED -> {
                "FALSE_SUPPRESSION_PROXY"
            }
            dataQuality() != "COMPLETE" || invalidationState == TradePlanInvalidationState.UNKNOWN_DATA -> "UNKNOWN_DATA"
            priceApproached == null || volatilityChanged == null -> "UNKNOWN_DATA"
            else -> "PENDING"
        }
    }

    private companion object {
        const val LOCK_OWNER = "llm-daemon-resting-maintenance"
        const val LIFECYCLE_LOCK_OWNER = "llm-daemon-episode-lifecycle"
        val BPS: BigDecimal = BigDecimal("10000")
    }

    private data class EpisodeBaseline(val distanceJpy: BigDecimal?, val atrPriceRatio: BigDecimal?)
}

/** episode baseline から entry への距離が threshold 以上縮小したかを判定する。 */
internal fun hasPriceApproached(
    entryPrice: BigDecimal,
    baselineDistance: BigDecimal?,
    currentDistance: BigDecimal?,
    thresholdRatio: BigDecimal,
): Boolean? {
    val baseline = baselineDistance?.abs() ?: return null
    val current = currentDistance?.abs() ?: return null
    return baseline.subtract(current) >= entryPrice.abs().multiply(thresholdRatio)
}

/** ATR / executable quote ratio が episode baseline から threshold 以上変化したかを判定する。 */
internal fun hasVolatilityChanged(
    baselineAtrRatio: BigDecimal?,
    currentAtrRatio: BigDecimal?,
    thresholdRatio: BigDecimal?,
): Boolean? {
    val threshold = thresholdRatio ?: return null
    val baseline = baselineAtrRatio?.takeUnless { it.signum() == 0 } ?: return null
    val current = currentAtrRatio ?: return null
    val relativeChange = current.subtract(baseline).abs().divide(baseline.abs(), 12, RoundingMode.HALF_UP)
    return relativeChange >= threshold
}
