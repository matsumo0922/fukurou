package me.matsumo.fukurou.trading.reconciler

import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.market.MarketDataGapReason
import java.time.Instant
import java.util.UUID

/**
 * ProtectionReconciler の現在状態。
 *
 * @param lastReconciledAt 最後に reconcile pass が完了した時刻
 * @param startupFullReconcileCompleted 起動時 full reconcile pass が完了済みか
 * @param lastTransportActivityAt 最後に transport activity を確認した時刻
 * @param lastTradeAt 最後に realtime trade を確認した時刻
 * @param lastMaintenanceAt 最後に periodic maintenance が成功した時刻
 */
data class ReconcilerStatus(
    val lastReconciledAt: Instant? = null,
    val startupFullReconcileCompleted: Boolean = false,
    val lastTransportActivityAt: Instant? = null,
    val lastTradeAt: Instant? = null,
    val lastMaintenanceAt: Instant? = null,
    @Deprecated("Use lastTradeAt.")
    val lastMarketDataAt: Instant? = null,
    val marketDataState: MarketDataConnectionState = MarketDataConnectionState.DISCONNECTED,
    val marketDataSessionId: UUID? = null,
    val lastProcessedSequence: Long = 0,
    val gapStartedAt: Instant? = null,
    val recoveredAt: Instant? = null,
    val gapReason: MarketDataGapReason? = null,
    val startupRecoveryCompleted: Boolean = false,
)

/**
 * readiness などが Reconciler 状態を読むための provider。
 */
interface ReconcilerStatusProvider {
    /**
     * 現在の Reconciler 状態 snapshot を返す。
     */
    fun snapshot(): ReconcilerStatus
}

/**
 * Reconciler が更新する in-memory status holder。
 */
class MutableReconcilerStatus : ReconcilerStatusProvider {

    @Volatile
    private var currentStatus = ReconcilerStatus()

    override fun snapshot(): ReconcilerStatus {
        return currentStatus
    }

    /**
     * reconcile pass 完了状態を記録する。
     */
    fun markReconciled(
        reconciledAt: Instant,
        startupFullReconcileCompleted: Boolean,
        lastMarketDataAt: Instant?,
    ) {
        currentStatus = currentStatus.copy(
            lastReconciledAt = reconciledAt,
            startupFullReconcileCompleted = currentStatus.startupFullReconcileCompleted || startupFullReconcileCompleted,
            lastMaintenanceAt = lastMarketDataAt ?: currentStatus.lastMaintenanceAt,
            lastMarketDataAt = lastMarketDataAt ?: currentStatus.lastMarketDataAt,
        )
    }

    /** 永続 market-data integrity 状態を反映する。 */
    fun updateMarketData(status: ReconcilerStatus) {
        currentStatus = status
    }
}

/**
 * Reconciler が未設定であることを示す provider。
 */
object NoReconcilerStatusProvider : ReconcilerStatusProvider {
    override fun snapshot(): ReconcilerStatus {
        return ReconcilerStatus()
    }
}
