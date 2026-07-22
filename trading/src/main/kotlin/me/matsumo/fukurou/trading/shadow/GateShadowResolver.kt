package me.matsumo.fukurou.trading.shadow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
 * @param wallTimeBudget 1 tick の resolver 作業時間上限
 * @param maxObservationsPerTick 1 tick で処理する observation 上限
 * @param maxReceiptsPerObservation 1 observation・1 tick で読む receipt 上限
 */
class GateShadowResolver(
    private val repository: GateShadowRepository,
    private val horizon: Duration,
    private val settlementGrace: Duration,
    private val wallTimeBudget: Duration = DEFAULT_WALL_TIME_BUDGET,
    private val maxObservationsPerTick: Int,
    private val maxReceiptsPerObservation: Int,
) {

    init {
        require(!horizon.isNegative && !horizon.isZero) { "Gate-shadow horizon must be positive." }
        require(!settlementGrace.isNegative) { "Gate-shadow settlement grace must not be negative." }
        require(!wallTimeBudget.isNegative && !wallTimeBudget.isZero) {
            "Gate-shadow wall-time budget must be positive."
        }
        require(wallTimeBudget.toMillis() > 0L) { "Gate-shadow wall-time budget must be at least 1 ms." }
        require(maxObservationsPerTick > 0) { "Gate-shadow observation limit must be positive." }
        require(maxObservationsPerTick < Int.MAX_VALUE) { "Gate-shadow observation limit is too large." }
        require(maxReceiptsPerObservation > 0) { "Gate-shadow receipt limit must be positive." }
    }

    /** settle 済み observation を wall-time 予算と件数上限内で解決する。 */
    suspend fun observe(observedAt: Instant): Result<Unit> {
        return try {
            val completedWithinBudget = withTimeoutOrNull(wallTimeBudget.toMillis()) {
                observeWithinBudget(observedAt)
                true
            } ?: false

            if (!completedWithinBudget) {
                gateShadowResolverLogger.warning(
                    "gate-shadow wall-time budget reached; pending observations will resume next tick: " +
                        "budgetMillis=${wallTimeBudget.toMillis()}",
                )
            }

            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private suspend fun observeWithinBudget(observedAt: Instant) {
        val candidates = repository.findUnresolvedObservations(maxObservationsPerTick + 1).getOrThrow()
        val observations = candidates.take(maxObservationsPerTick)

        if (candidates.size > maxObservationsPerTick) {
            gateShadowResolverLogger.warning(
                "gate-shadow observation limit reached; remaining observations will resume next tick: " +
                    "limit=$maxObservationsPerTick",
            )
        }

        observations.forEach { observation -> resolveIsolated(observation, observedAt) }
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

    @Suppress("LongMethod")
    private suspend fun resolve(observation: GateShadowObservation, observedAt: Instant) {
        if (!observation.isSupportedLongEntry()) return

        val windowEndTime = observation.windowStartTime.plus(horizon)
        val settlementTime = windowEndTime.plus(settlementGrace)

        if (observedAt < settlementTime) return

        val sessionId = observation.marketDataSessionId ?: return writeUnknown(
            observation = observation,
            observedAt = observedAt,
            dataQuality = worstShadowDataQuality(
                observation.dataQuality,
                ShadowDataQuality.MISSING_MARKET_DATA_SESSION_ID,
            ),
        )

        if (observation.boundaryPrice() == null) {
            return writeUnknown(
                observation = observation,
                observedAt = observedAt,
                dataQuality = worstShadowDataQuality(
                    observation.dataQuality,
                    ShadowDataQuality.MISSING_GEOMETRY_HASH,
                ),
            )
        }

        val progress = repository.findScanProgress(observation.id).getOrThrow()
        val scanStart = observation.scanStart(progress)
        val highWatermark = repository.findSessionAdmissionHighWatermark(sessionId).getOrThrow()

        if (highWatermark <= scanStart.cursorOrdinal) {
            return writeUnknown(observation, observedAt, scanStart.dataQuality)
        }

        val receipts = repository.scanReceipts(
            sessionId = sessionId,
            startAdmissionOrdinal = observation.startAdmissionOrdinal,
            windowStartTime = observation.windowStartTime,
            windowEndTime = windowEndTime,
            cursorOrdinal = scanStart.cursorOrdinal,
            limit = maxReceiptsPerObservation,
        ).getOrThrow()

        val scan = scanForCrossing(
            observation = observation,
            receipts = receipts,
            windowEndTime = windowEndTime,
            scanStart = scanStart,
        )

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
                dataQuality = scan.dataQuality,
                lastSocketObservedAt = scan.lastSocketObservedAt,
                lastScannedAt = observedAt,
            ),
        ).getOrThrow()

        gateShadowResolverLogger.warning(
            "gate-shadow receipt limit reached; scan cursor persisted for next tick: " +
                "observationId=${observation.id}, cursor=${scan.lastScannedOrdinal}, limit=$maxReceiptsPerObservation",
        )
    }

    private fun GateShadowObservation.scanStart(progress: GateShadowScanProgress?): ScanStart {
        val cursorOrdinal = maxOf(
            startAdmissionOrdinal,
            progress?.lastScannedAdmissionOrdinal ?: startAdmissionOrdinal,
        )
        val dataQuality = progress?.dataQuality
            ?.let { progressQuality -> worstShadowDataQuality(this.dataQuality, progressQuality) }
            ?: this.dataQuality

        return ScanStart(
            cursorOrdinal = cursorOrdinal,
            dataQuality = dataQuality,
            previousSocketObservedAt = progress?.lastSocketObservedAt,
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
        scanStart: ScanStart,
    ): CrossingScan {
        var dataQuality = scanStart.dataQuality
        var previousSocketObservedAt = scanStart.previousSocketObservedAt

        for (receipt in receipts) {
            val insideWindow = receipt.socketObservedAt >= observation.windowStartTime &&
                receipt.socketObservedAt <= windowEndTime
            if (!insideWindow) continue

            val previousTime = previousSocketObservedAt
            if (previousTime != null && receipt.socketObservedAt < previousTime) {
                dataQuality = worstShadowDataQuality(
                    dataQuality,
                    ShadowDataQuality.NON_MONOTONIC_SOCKET_TIME,
                )
            }
            previousSocketObservedAt = receipt.socketObservedAt

            val payload = ReceiptPayloadDecoder.decode(receipt.normalizedPayload)
            if (payload == null) {
                dataQuality = worstShadowDataQuality(
                    dataQuality,
                    ShadowDataQuality.PAYLOAD_DECODE_FAILED,
                )
                continue
            }

            if (observation.crosses(payload.priceJpy)) {
                return CrossingScan(
                    crossedReceipt = receipt,
                    crossedPayload = payload,
                    dataQuality = dataQuality,
                    lastScannedOrdinal = receipt.admissionOrdinal,
                    lastSocketObservedAt = previousSocketObservedAt,
                )
            }
        }

        val lastScannedOrdinal = receipts.lastOrNull()?.admissionOrdinal ?: scanStart.cursorOrdinal

        return CrossingScan(
            crossedReceipt = null,
            crossedPayload = null,
            dataQuality = dataQuality,
            lastScannedOrdinal = lastScannedOrdinal,
            lastSocketObservedAt = previousSocketObservedAt,
        )
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

    private data class ScanStart(
        val cursorOrdinal: Long,
        val dataQuality: ShadowDataQuality,
        val previousSocketObservedAt: Instant?,
    )

    private data class CrossingScan(
        val crossedReceipt: GateShadowReceipt?,
        val crossedPayload: ReceiptPayload?,
        val dataQuality: ShadowDataQuality,
        val lastScannedOrdinal: Long,
        val lastSocketObservedAt: Instant?,
    )

    private companion object {
        const val DISTANCE_SCALE = 8
        val DEFAULT_WALL_TIME_BUDGET: Duration = Duration.ofMillis(750)
    }
}

private val gateShadowResolverLogger = Logger.getLogger(GateShadowResolver::class.java.name)
