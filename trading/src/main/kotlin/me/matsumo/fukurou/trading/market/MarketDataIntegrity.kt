package me.matsumo.fukurou.trading.market

import java.time.Instant
import java.util.UUID

/** market-data connection の永続状態。 */
enum class MarketDataConnectionState {
    CONNECTED,
    DISCONNECTED,
}

/** market-data gap の理由。 */
enum class MarketDataGapReason {
    DISCONNECTED,
    MESSAGE_STALE,
    TRANSPORT_LIVENESS_LOST,
    INVALID_MESSAGE,
    SEQUENCE_GAP,
    DATABASE_FAILURE,
    PROCESS_RESTART,
}

/**
 * paper execution が参照する market-data integrity snapshot。
 */
data class MarketDataIntegritySnapshot(
    val state: MarketDataConnectionState = MarketDataConnectionState.DISCONNECTED,
    val sessionId: UUID? = null,
    val lastProcessedSequence: Long = 0,
    val lastTransportActivityAt: Instant? = null,
    val lastTradeAt: Instant? = null,
    val lastMaintenanceAt: Instant? = null,
    @Deprecated("Use lastTradeAt.")
    val lastReceivedAt: Instant? = null,
    val gapStartedAt: Instant? = null,
    val recoveredAt: Instant? = null,
    val gapReason: MarketDataGapReason? = null,
    val startupRecoveryCompleted: Boolean = false,
)

/**
 * market-data session / gap / evaluation exclusion の永続化境界。
 */
interface MarketDataIntegrityRepository {
    /** 現在状態を返す。 */
    suspend fun snapshot(): Result<MarketDataIntegritySnapshot>

    /** 新しい接続 session を開始する。 */
    suspend fun beginSession(sessionId: UUID, connectedAt: Instant): Result<Unit>

    /** transport activity を単調増加で保存する。 */
    suspend fun markTransportActivity(sessionId: UUID, observedAt: Instant): Result<Unit> = Result.success(Unit)

    /** periodic safety maintenance の最終成功時刻を保存する。 */
    suspend fun markMaintenanceSucceeded(sessionId: UUID, succeededAt: Instant): Result<Unit>

    /** session を先に DISCONNECTED として永続化し、新規 resting order を fail-closed にする。 */
    suspend fun markDisconnected(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String? = null,
    ): Result<Unit>

    /** 永続化済み gap の取消・評価除外を global trading lock 内で適用する。 */
    suspend fun applyGapImpact(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
    ): Result<Unit>

    /** 接続を gap として閉じ、影響 entity を fail-closed にする。 */
    suspend fun recordGap(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String? = null,
    ): Result<Unit>

    /** 起動時に残存 session を gap として回収する。 */
    suspend fun recoverStaleSession(recoveredAt: Instant): Result<Unit>
}

/** market-data persistence を持たない runtime 用の fail-closed 実装。 */
object UnavailableMarketDataIntegrityRepository : MarketDataIntegrityRepository {
    override suspend fun snapshot(): Result<MarketDataIntegritySnapshot> {
        return Result.success(MarketDataIntegritySnapshot())
    }

    override suspend fun beginSession(sessionId: UUID, connectedAt: Instant): Result<Unit> {
        return Result.failure(IllegalStateException("market-data integrity repository is unavailable."))
    }

    override suspend fun markTransportActivity(sessionId: UUID, observedAt: Instant): Result<Unit> {
        return Result.failure(IllegalStateException("market-data integrity repository is unavailable."))
    }

    override suspend fun markMaintenanceSucceeded(sessionId: UUID, succeededAt: Instant): Result<Unit> {
        return Result.failure(IllegalStateException("market-data integrity repository is unavailable."))
    }

    override suspend fun markDisconnected(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String?,
    ): Result<Unit> {
        return Result.failure(IllegalStateException("market-data integrity repository is unavailable."))
    }

    override suspend fun applyGapImpact(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
    ): Result<Unit> {
        return Result.failure(IllegalStateException("market-data integrity repository is unavailable."))
    }

    override suspend fun recordGap(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String?,
    ): Result<Unit> {
        return Result.failure(IllegalStateException("market-data integrity repository is unavailable."))
    }

    override suspend fun recoverStaleSession(recoveredAt: Instant): Result<Unit> {
        return Result.failure(IllegalStateException("market-data integrity repository is unavailable."))
    }
}
