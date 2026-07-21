package me.matsumo.fukurou.trading.shadow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * gate-shadow observation、scan progress、resolution を保存する境界。
 */
interface GateShadowRepository {

    /** TTL 失効時の observation を append-only で保存する。 */
    suspend fun appendObservation(observation: GateShadowObservation): Result<Unit>

    /** order ID に対応する observation を返す。 */
    suspend fun findObservationByOrderId(orderId: UUID): Result<GateShadowObservation?>

    /** settle 後の走査 cursor を単調に前進させる。 */
    suspend fun upsertScanProgress(progress: GateShadowScanProgress): Result<Unit>

    /** observation の走査 cursor を返す。 */
    suspend fun findScanProgress(observationId: UUID): Result<GateShadowScanProgress?>

    /** resolution を UNKNOWN から CROSSED へ単調昇格させる。 */
    suspend fun upsertResolution(resolution: GateShadowResolution): Result<Unit>

    /** observation の resolution を返す。 */
    suspend fun findResolution(observationId: UUID): Result<GateShadowResolution?>

    /** TTL 失効の正本に存在し observation がない order 数を返す。 */
    suspend fun countMissingTtlExpiryObservations(): Result<Long>

    /** receipt の session/admission index が走査可能な状態なら true を返す。 */
    suspend fun isReceiptScanIndexReady(): Result<Boolean>
}

/**
 * gate-shadow repository の処理で `CancellationException` を握り潰さずに `Result` を返す。
 */
internal inline fun <T> gateShadowResult(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
}

/**
 * DB を持たない runtime と repository 単体テスト向けの in-memory 実装。
 *
 * in-memory ledger は admission ordinal の権威を持たないため、自動 capture は行わない。
 */
class InMemoryGateShadowRepository : GateShadowRepository {

    private val mutex = Mutex()
    private val observationsByOrderId = mutableMapOf<UUID, GateShadowObservation>()
    private val scanProgressByObservationId = mutableMapOf<UUID, GateShadowScanProgress>()
    private val resolutionsByObservationId = mutableMapOf<UUID, GateShadowResolution>()

    override suspend fun appendObservation(observation: GateShadowObservation): Result<Unit> {
        return mutex.withLock {
            gateShadowResult {
                val existing = observationsByOrderId[observation.orderId]

                require(existing == null || existing == observation) {
                    "Gate-shadow observation is append-only."
                }

                observationsByOrderId[observation.orderId] = observation
            }
        }
    }

    override suspend fun findObservationByOrderId(orderId: UUID): Result<GateShadowObservation?> {
        return mutex.withLock { gateShadowResult { observationsByOrderId[orderId] } }
    }

    override suspend fun upsertScanProgress(progress: GateShadowScanProgress): Result<Unit> {
        return mutex.withLock {
            gateShadowResult {
                val existing = scanProgressByObservationId[progress.observationId]
                val cursorMovesForward = existing == null ||
                    progress.lastScannedAdmissionOrdinal >= existing.lastScannedAdmissionOrdinal

                if (cursorMovesForward) {
                    scanProgressByObservationId[progress.observationId] = progress
                }
            }
        }
    }

    override suspend fun findScanProgress(observationId: UUID): Result<GateShadowScanProgress?> {
        return mutex.withLock { gateShadowResult { scanProgressByObservationId[observationId] } }
    }

    override suspend fun upsertResolution(resolution: GateShadowResolution): Result<Unit> {
        return mutex.withLock {
            gateShadowResult {
                val existing = resolutionsByObservationId[resolution.observationId]
                val shouldWrite = existing == null ||
                    existing.outcome != GateShadowOutcome.CROSSED && resolution.outcome == GateShadowOutcome.CROSSED

                if (shouldWrite) {
                    resolutionsByObservationId[resolution.observationId] = resolution
                }
            }
        }
    }

    override suspend fun findResolution(observationId: UUID): Result<GateShadowResolution?> {
        return mutex.withLock { gateShadowResult { resolutionsByObservationId[observationId] } }
    }

    override suspend fun countMissingTtlExpiryObservations(): Result<Long> = Result.success(0L)

    override suspend fun isReceiptScanIndexReady(): Result<Boolean> = Result.success(false)
}
