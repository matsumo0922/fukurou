package me.matsumo.fukurou

import me.matsumo.fukurou.trading.market.MarketDataConnectionState
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
 * @param staleAfter この時間を超えて maintenance されていなければ not-ready
 * @param transportLivenessTimeout transport activity の鮮度許容時間
 */
class ReconcilerFreshnessReadinessProbe(
    private val delegate: ReadinessProbe,
    private val reconcilerStatusProvider: ReconcilerStatusProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = DEFAULT_RECONCILER_STALE_AFTER,
    private val transportLivenessTimeout: Duration = Duration.ofSeconds(150),
) : ReadinessProbe {

    override suspend fun isReady(): Boolean {
        val dependenciesReady = delegate.isReady()

        if (!dependenciesReady) {
            return false
        }

        val status = reconcilerStatusProvider.snapshot()
        val lastTransportActivityAt = status.lastTransportActivityAt ?: return false
        val lastMaintenanceAt = status.lastMaintenanceAt ?: return false
        val now = Instant.now(clock)
        val transportFreshEnough = lastTransportActivityAt.isFreshEnough(now, transportLivenessTimeout)
        val maintenanceFreshEnough = lastMaintenanceAt.isFreshEnough(now, staleAfter)

        val connected = status.marketDataState == MarketDataConnectionState.CONNECTED
        val activeGap = status.gapStartedAt != null && status.recoveredAt == null

        return status.startupRecoveryCompleted && connected && !activeGap && transportFreshEnough && maintenanceFreshEnough
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
