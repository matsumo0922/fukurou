package me.matsumo.fukurou.trading.decision

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * decision protocol の append-only repository。
 */
interface DecisionRepository {
    /**
     * decision と付随する intent / TradePlan を append-only で保存する。
     */
    suspend fun submitDecision(submission: DecisionSubmission): Result<DecisionSubmissionResult>

    /**
     * Falsifier の verdict を append-only で保存する。
     */
    suspend fun submitFalsification(submission: FalsificationSubmission): Result<FalsificationRecord>

    /**
     * SafetyFloor 用に intent / falsification / consumption の snapshot を取得する。
     */
    suspend fun entryIntentSafetySnapshot(
        intentId: UUID,
        observedAt: Instant,
        freshnessWindow: Duration,
    ): Result<EntryIntentSafetySnapshot?>

    /**
     * intent を約定済みとして append-only に記録する。
     */
    suspend fun appendIntentConsumption(
        intentId: UUID,
        orderId: UUID?,
        consumedAt: Instant,
    ): Result<TradeIntentConsumptionRecord>
}
