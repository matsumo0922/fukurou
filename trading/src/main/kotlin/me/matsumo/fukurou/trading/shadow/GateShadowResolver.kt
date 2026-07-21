package me.matsumo.fukurou.trading.shadow

import kotlinx.coroutines.CancellationException
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger

/**
 * settle 済み gate-shadow observation を receipt journal から解決する。
 *
 * ledger への依存を持たず、resolution と scan cursor だけを更新する。
 *
 * @param repository gate-shadow の永続化境界
 * @param horizon 失効後に観測する時間窓
 * @param settlementGrace receipt commit の settle 待機時間
 * @param maxObservationsPerTick 1 tick で処理する observation 上限
 * @param maxReceiptsPerObservation 1 observation・1 tick で読む receipt 上限
 */
class GateShadowResolver(
    private val repository: GateShadowRepository,
    private val horizon: Duration,
    private val settlementGrace: Duration,
    private val maxObservationsPerTick: Int,
    private val maxReceiptsPerObservation: Int,
) {

    init {
        require(!horizon.isNegative && !horizon.isZero) { "Gate-shadow horizon must be positive." }
        require(!settlementGrace.isNegative) { "Gate-shadow settlement grace must not be negative." }
        require(maxObservationsPerTick > 0) { "Gate-shadow observation limit must be positive." }
        require(maxObservationsPerTick < Int.MAX_VALUE) { "Gate-shadow observation limit is too large." }
        require(maxReceiptsPerObservation > 0) { "Gate-shadow receipt limit must be positive." }
    }

    /** settle 済み observation を上限内で解決する。 */
    suspend fun observe(observedAt: Instant): Result<Unit> {
        return try {
            val candidates = repository.findUnresolvedObservations(maxObservationsPerTick + 1).getOrThrow()
            val observations = candidates.take(maxObservationsPerTick)

            if (candidates.size > maxObservationsPerTick) {
                gateShadowResolverLogger.warning(
                    "gate-shadow observation limit reached; remaining observations will resume next tick: " +
                        "limit=$maxObservationsPerTick",
                )
            }

            observations.forEach { observation -> resolveIsolated(observation, observedAt) }

            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private suspend fun resolveIsolated(observation: GateShadowObservation, observedAt: Instant) {
        try {
            resolve(observation, observedAt)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            gateShadowResolverLogger.log(
                Level.WARNING,
                "gate-shadow observation resolution failed; leaving pending: observationId=${observation.id}",
                throwable,
            )
        }
    }

    private suspend fun resolve(observation: GateShadowObservation, observedAt: Instant) {
        if (!observation.isSupportedLongEntry()) return

        val windowEndTime = observation.windowStartTime.plus(horizon)
        val settlementTime = windowEndTime.plus(settlementGrace)

        if (observedAt < settlementTime) return

        val sessionId = observation.marketDataSessionId
            ?: return writeUnknown(observation, observedAt, ShadowDataQuality.MISSING_MARKET_DATA_SESSION_ID)

        if (observation.boundaryPrice() == null) {
            return writeUnknown(observation, observedAt, observation.dataQuality)
        }

        val cursorOrdinal = resolveCursorOrdinal(observation)
        val highWatermark = repository.findSessionAdmissionHighWatermark(sessionId).getOrThrow()

        if (highWatermark <= cursorOrdinal) {
            return writeUnknown(observation, observedAt, observation.dataQuality)
        }

        val receipts = repository.scanReceipts(
            sessionId = sessionId,
            startAdmissionOrdinal = observation.startAdmissionOrdinal,
            windowStartTime = observation.windowStartTime,
            windowEndTime = windowEndTime,
            cursorOrdinal = cursorOrdinal,
            limit = maxReceiptsPerObservation,
        ).getOrThrow()

        val scan = scanForCrossing(observation, receipts, windowEndTime, cursorOrdinal)

        if (scan.crossedReceipt != null && scan.crossedPayload != null) {
            repository.upsertResolution(
                observation.crossedResolution(
                    receipt = scan.crossedReceipt,
                    payload = scan.crossedPayload,
                    dataQuality = scan.dataQuality,
                    resolvedAt = observedAt,
                ),
            ).getOrThrow()
            return
        }

        val exhaustedRange = receipts.size < maxReceiptsPerObservation
        val reachedHighWatermark = scan.lastScannedOrdinal >= highWatermark
        val readComplete = exhaustedRange || reachedHighWatermark

        if (readComplete) {
            return writeUnknown(observation, observedAt, scan.dataQuality)
        }

        repository.upsertScanProgress(
            GateShadowScanProgress(
                observationId = observation.id,
                lastScannedAdmissionOrdinal = scan.lastScannedOrdinal,
                lastScannedAt = observedAt,
            ),
        ).getOrThrow()

        gateShadowResolverLogger.warning(
            "gate-shadow receipt limit reached; scan cursor persisted for next tick: " +
                "observationId=${observation.id}, cursor=${scan.lastScannedOrdinal}, limit=$maxReceiptsPerObservation",
        )
    }

    private suspend fun resolveCursorOrdinal(observation: GateShadowObservation): Long {
        val progress = repository.findScanProgress(observation.id).getOrThrow()

        return maxOf(
            observation.startAdmissionOrdinal,
            progress?.lastScannedAdmissionOrdinal ?: observation.startAdmissionOrdinal,
        )
    }

    private suspend fun writeUnknown(
        observation: GateShadowObservation,
        observedAt: Instant,
        dataQuality: ShadowDataQuality,
    ) {
        repository.upsertResolution(observation.unknownResolution(observedAt, dataQuality)).getOrThrow()
    }

    private fun scanForCrossing(
        observation: GateShadowObservation,
        receipts: List<GateShadowReceipt>,
        windowEndTime: Instant,
        cursorOrdinal: Long,
    ): CrossingScan {
        var dataQuality = observation.dataQuality
        var previousSocketObservedAt: Instant? = null

        for (receipt in receipts) {
            val insideWindow = receipt.socketObservedAt >= observation.windowStartTime &&
                receipt.socketObservedAt <= windowEndTime
            if (!insideWindow) continue

            val previousTime = previousSocketObservedAt
            if (previousTime != null && receipt.socketObservedAt < previousTime) {
                dataQuality = ShadowDataQuality.NON_MONOTONIC_SOCKET_TIME
            }
            previousSocketObservedAt = receipt.socketObservedAt

            val payload = ReceiptPayloadDecoder.decode(receipt.normalizedPayload)
            if (payload == null) {
                dataQuality = ShadowDataQuality.PAYLOAD_DECODE_FAILED
                continue
            }

            if (observation.crosses(payload.priceJpy)) {
                return CrossingScan(receipt, payload, dataQuality, receipt.admissionOrdinal)
            }
        }

        val lastScannedOrdinal = receipts.lastOrNull()?.admissionOrdinal ?: cursorOrdinal

        return CrossingScan(null, null, dataQuality, lastScannedOrdinal)
    }

    private fun GateShadowObservation.isSupportedLongEntry(): Boolean {
        val supportedOrderType = orderType == OrderType.LIMIT || orderType == OrderType.STOP

        return side == OrderSide.BUY && supportedOrderType
    }

    private fun GateShadowObservation.boundaryPrice(): BigDecimal? {
        return when (orderType) {
            OrderType.LIMIT -> limitPriceJpy
            OrderType.STOP -> triggerPriceJpy
            OrderType.MARKET -> null
        }
    }

    private fun GateShadowObservation.crosses(eventPrice: BigDecimal): Boolean {
        val boundaryPrice = checkNotNull(boundaryPrice())

        return when (orderType) {
            OrderType.LIMIT -> eventPrice <= boundaryPrice
            OrderType.STOP -> eventPrice >= boundaryPrice
            OrderType.MARKET -> false
        }
    }

    private fun GateShadowObservation.crossedResolution(
        receipt: GateShadowReceipt,
        payload: ReceiptPayload,
        dataQuality: ShadowDataQuality,
        resolvedAt: Instant,
    ): GateShadowResolution {
        val boundaryPrice = checkNotNull(boundaryPrice())
        val distanceJpy = payload.priceJpy.subtract(boundaryPrice).abs().setScale(DISTANCE_SCALE, RoundingMode.HALF_UP)

        return GateShadowResolution(
            observationId = id,
            outcome = GateShadowOutcome.CROSSED,
            crossingEventSequence = receipt.sourceSequence,
            crossingExchangeAt = payload.exchangeAt,
            crossingPriceJpy = payload.priceJpy,
            distanceJpy = distanceJpy,
            dataQuality = dataQuality,
            resolvedAt = resolvedAt,
        )
    }

    private fun GateShadowObservation.unknownResolution(
        resolvedAt: Instant,
        dataQuality: ShadowDataQuality,
    ): GateShadowResolution {
        return GateShadowResolution(
            observationId = id,
            outcome = GateShadowOutcome.UNKNOWN,
            crossingEventSequence = null,
            crossingExchangeAt = null,
            crossingPriceJpy = null,
            distanceJpy = null,
            dataQuality = dataQuality,
            resolvedAt = resolvedAt,
        )
    }

    private data class CrossingScan(
        val crossedReceipt: GateShadowReceipt?,
        val crossedPayload: ReceiptPayload?,
        val dataQuality: ShadowDataQuality,
        val lastScannedOrdinal: Long,
    )

    private companion object {
        const val DISTANCE_SCALE = 8
    }
}

private val gateShadowResolverLogger = Logger.getLogger(GateShadowResolver::class.java.name)
