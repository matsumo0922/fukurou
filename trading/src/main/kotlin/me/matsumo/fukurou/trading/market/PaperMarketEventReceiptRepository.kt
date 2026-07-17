package me.matsumo.fukurou.trading.market

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * durable market-event receipt の commit 結果。
 *
 * @param receiptId receipt row ID
 * @param admissionOrdinal receipt admission の単調増加 ordinal
 * @param payloadHash normalized payload の SHA-256 hash
 * @param socketObservedAt DB に保存された canonical socket observation 時刻
 * @param duplicate 同一 source event の既存 receipt を返した場合は true
 * @param transactionDurationNanos commit/fsync 完了までの処理時間
 * @param advisoryWaitNanos session-scoped shared advisory lock の待機時間
 */
data class PaperMarketEventReceiptCommit(
    val receiptId: UUID,
    val admissionOrdinal: Long,
    val payloadHash: String,
    val socketObservedAt: Instant,
    val duplicate: Boolean,
    val transactionDurationNanos: Long,
    val advisoryWaitNanos: Long,
)

/** WebSocket trade を application queue より先に durable receipt へ保存する境界。 */
interface PaperMarketEventReceiptRepository {
    /** normalized receipt を commit し、同一 source event の retry には同じ receipt を返す。 */
    suspend fun commit(event: PaperMarketTradeEvent): Result<PaperMarketEventReceiptCommit>
}

/** receipt persistence 自体を完了できなかった typed infrastructure failure。 */
class MarketEventReceiptPersistenceException(cause: Throwable? = null) :
    SQLException("paper market-event receipt persistence failed", cause)

/** 同一 source identity に異なる normalized payload が届いた integrity conflict。 */
class MarketEventReceiptIntegrityConflictException :
    SQLException("paper market-event receipt payload integrity conflict")

/** receipt repository を持たない transport 構築を fail-closed にする実装。 */
object UnavailablePaperMarketEventReceiptRepository : PaperMarketEventReceiptRepository {
    override suspend fun commit(event: PaperMarketTradeEvent): Result<PaperMarketEventReceiptCommit> {
        return Result.failure(MarketEventReceiptPersistenceException())
    }
}
