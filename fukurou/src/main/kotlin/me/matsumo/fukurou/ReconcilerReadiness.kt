package me.matsumo.fukurou

import me.matsumo.fukurou.trading.reconciler.ReconcilerStatusProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Reconciler readiness の既定 stale 許容時間。
 */
private val DEFAULT_RECONCILER_STALE_AFTER = Duration.ofSeconds(30)

/**
 * DB readiness と Reconciler 鮮度を合成する readiness probe。
 *
 * @param delegate DB など既存外部依存の readiness probe
 * @param reconcilerStatusProvider Reconciler 状態 provider
 * @param clock 鮮度判定に使う clock
 * @param staleAfter この時間を超えて reconcile されていなければ not-ready
 */
class ReconcilerFreshnessReadinessProbe(
    private val delegate: ReadinessProbe,
    private val reconcilerStatusProvider: ReconcilerStatusProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = DEFAULT_RECONCILER_STALE_AFTER,
) : ReadinessProbe {

    override suspend fun isReady(): Boolean {
        val dependenciesReady = delegate.isReady()

        if (!dependenciesReady) {
            return false
        }

        val status = reconcilerStatusProvider.snapshot()
        val lastReconciledAt = status.lastReconciledAt ?: return false
        val lastMarketDataAt = status.lastMarketDataAt ?: return false
        val now = Instant.now(clock)
        val reconcilerFreshEnough = lastReconciledAt.isFreshEnough(now, staleAfter)
        val marketDataFreshEnough = lastMarketDataAt.isFreshEnough(now, staleAfter)

        return status.startupFullReconcileCompleted && reconcilerFreshEnough && marketDataFreshEnough
    }
}

/**
 * Reconciler 最終更新時刻が stale でないか判定する。
 */
private fun Instant.isFreshEnough(now: Instant, staleAfter: Duration): Boolean {
    if (isAfter(now)) {
        return false
    }

    return Duration.between(this, now) <= staleAfter
}
