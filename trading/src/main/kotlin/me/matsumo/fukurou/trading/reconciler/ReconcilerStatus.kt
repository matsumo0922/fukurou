package me.matsumo.fukurou.trading.reconciler

import java.time.Instant

/**
 * ProtectionReconciler の現在状態。
 *
 * @param lastReconciledAt 最後に reconcile pass が完了した時刻
 * @param startupFullReconcileCompleted 起動時 full reconcile pass が完了済みか
 * @param lastMarketDataAt 最後に tick を確認した時刻
 */
data class ReconcilerStatus(
    val lastReconciledAt: Instant? = null,
    val startupFullReconcileCompleted: Boolean = false,
    val lastMarketDataAt: Instant? = null,
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
            lastMarketDataAt = lastMarketDataAt ?: currentStatus.lastMarketDataAt,
        )
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
