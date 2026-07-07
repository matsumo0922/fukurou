package me.matsumo.fukurou.trading.decision

import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.knowledge.DecisionJournalRecord
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
     * invocation ID に紐づく最新 decision と付随する intent / TradePlan を取得する。
     */
    suspend fun latestDecisionByInvocationId(invocationId: String): Result<DecisionSubmissionResult?>

    /**
     * 指定範囲に作成された decision を Obsidian note 用に取得する。
     */
    suspend fun findDecisionsCreatedBetween(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): Result<List<DecisionJournalRecord>>

    /**
     * 安定 cursor 条件に一致する decision を Activity timeline 用に新しい順で取得する。
     */
    suspend fun findDecisionsForStableFeed(
        cursor: StableFeedCursor,
        limit: Int,
    ): Result<List<DecisionJournalRecord>>

    /**
     * intent ID に紐づく最新 falsification を取得する。
     */
    suspend fun latestFalsification(intentId: UUID): Result<FalsificationRecord?>

    /**
     * Falsifier review 用に intent と TradePlan を読み直す。
     */
    suspend fun tradeIntentReviewSnapshot(intentId: UUID): Result<TradeIntentReviewSnapshot?>

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

/**
 * intent consumption と外部 ledger 書き込みを同じ in-memory lock 内で直列化できる repository。
 */
interface AtomicIntentConsumptionRepository : DecisionRepository {
    /**
     * intent が未消費であることを検証し、ledgerBlock 成功後に consumption を保存する。
     */
    suspend fun <T> consumeIntentAfterLedgerWrite(
        intentId: UUID,
        orderId: UUID?,
        consumedAt: Instant,
        ledgerBlock: suspend () -> T,
    ): Result<T>
}
