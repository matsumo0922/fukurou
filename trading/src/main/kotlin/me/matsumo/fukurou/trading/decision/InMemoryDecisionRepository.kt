package me.matsumo.fukurou.trading.decision

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * unit test と DB 未構成 runtime 用の in-memory decision repository。
 *
 * @param clock 保存時刻に使う clock
 * @param maxTradePlanRevisions TradePlan 正式修正の上限
 */
class InMemoryDecisionRepository(
    private val clock: Clock = Clock.systemUTC(),
    private val maxTradePlanRevisions: Int = MAX_TRADE_PLAN_REVISIONS,
) : DecisionRepository {

    private val mutex = Mutex()
    private val decisions = mutableListOf<DecisionRecord>()
    private val tradePlans = mutableListOf<TradePlanRecord>()
    private val tradeIntents = mutableListOf<TradeIntentRecord>()
    private val falsifications = mutableListOf<FalsificationRecord>()
    private val intentConsumptions = mutableListOf<TradeIntentConsumptionRecord>()

    override suspend fun submitDecision(submission: DecisionSubmission): Result<DecisionSubmissionResult> {
        return runCatching {
            mutex.withLock {
                validateDecisionSubmission(submission, maxTradePlanRevisions)

                val now = Instant.now(clock)
                val decision = DecisionRecord(
                    decisionId = UUID.randomUUID(),
                    submission = submission,
                    createdAt = now,
                )
                val tradePlan = submission.tradePlan?.toRecord(decision.decisionId, now)
                val tradeIntent = submission.entryIntent?.toRecord(
                    decisionId = decision.decisionId,
                    tradePlanId = requireNotNull(tradePlan?.tradePlanId) {
                        "ENTER decision requires trade_plan."
                    },
                    estimatedWinProbability = submission.estimatedWinProbability,
                    createdAt = now,
                )

                decisions += decision
                tradePlan?.let { record -> tradePlans += record }
                tradeIntent?.let { record -> tradeIntents += record }

                DecisionSubmissionResult(
                    decision = decision,
                    tradeIntent = tradeIntent,
                    tradePlan = tradePlan,
                )
            }
        }
    }

    override suspend fun submitFalsification(submission: FalsificationSubmission): Result<FalsificationRecord> {
        return runCatching {
            mutex.withLock {
                val intentId = requireNotNull(submission.intentId) {
                    "intent_id is required."
                }
                require(submission.reasonJa.isNotBlank()) {
                    "reason_ja is required."
                }
                require(tradeIntents.any { intent -> intent.intentId == intentId }) {
                    "trade intent was not found."
                }
                require(intentConsumptions.none { consumption -> consumption.intentId == intentId }) {
                    "trade intent was already consumed."
                }
                require(falsifications.none { falsification -> falsification.intentId == intentId }) {
                    "falsification verdict already exists for intent."
                }

                val record = FalsificationRecord(
                    falsificationId = UUID.randomUUID(),
                    intentId = intentId,
                    verdict = submission.verdict,
                    llmProvider = submission.llmProvider,
                    reasonJa = submission.reasonJa,
                    createdAt = Instant.now(clock),
                )

                falsifications += record

                record
            }
        }
    }

    override suspend fun entryIntentSafetySnapshot(
        intentId: UUID,
        observedAt: Instant,
        freshnessWindow: Duration,
    ): Result<EntryIntentSafetySnapshot?> {
        return runCatching {
            mutex.withLock {
                val intent = tradeIntents.firstOrNull { candidate -> candidate.intentId == intentId }
                    ?: return@withLock null
                val falsification = falsifications.firstOrNull { candidate -> candidate.intentId == intentId }
                val consumed = intentConsumptions.any { consumption -> consumption.intentId == intentId }
                val freshApproved = falsification.isFreshApprovedAt(observedAt, freshnessWindow)

                EntryIntentSafetySnapshot(
                    tradeIntent = intent,
                    falsification = falsification,
                    consumed = consumed,
                    freshApproved = freshApproved,
                )
            }
        }
    }

    override suspend fun appendIntentConsumption(
        intentId: UUID,
        orderId: UUID?,
        consumedAt: Instant,
    ): Result<TradeIntentConsumptionRecord> {
        return runCatching {
            mutex.withLock {
                require(tradeIntents.any { intent -> intent.intentId == intentId }) {
                    "trade intent was not found."
                }
                require(intentConsumptions.none { consumption -> consumption.intentId == intentId }) {
                    "trade intent was already consumed."
                }

                val record = TradeIntentConsumptionRecord(
                    consumptionId = UUID.randomUUID(),
                    intentId = intentId,
                    orderId = orderId,
                    consumedAt = consumedAt,
                )

                intentConsumptions += record

                record
            }
        }
    }

    /**
     * 保存済み decision の snapshot を返す。
     */
    suspend fun decisions(): List<DecisionRecord> {
        return mutex.withLock { decisions.toList() }
    }

    /**
     * 保存済み intent の snapshot を返す。
     */
    suspend fun tradeIntents(): List<TradeIntentRecord> {
        return mutex.withLock { tradeIntents.toList() }
    }

    /**
     * 保存済み falsification の snapshot を返す。
     */
    suspend fun falsifications(): List<FalsificationRecord> {
        return mutex.withLock { falsifications.toList() }
    }

    /**
     * 保存済み TradePlan の snapshot を返す。
     */
    suspend fun tradePlans(): List<TradePlanRecord> {
        return mutex.withLock { tradePlans.toList() }
    }

    /**
     * 保存済み consumption の snapshot を返す。
     */
    suspend fun intentConsumptions(): List<TradeIntentConsumptionRecord> {
        return mutex.withLock { intentConsumptions.toList() }
    }
}

/**
 * decision submission の最小 contract を検証する。
 */
fun validateDecisionSubmission(submission: DecisionSubmission, maxTradePlanRevisions: Int = MAX_TRADE_PLAN_REVISIONS) {
    val estimatedProbabilityIsInRange = submission.estimatedWinProbability >= BigDecimal.ZERO &&
        submission.estimatedWinProbability <= BigDecimal.ONE

    require(estimatedProbabilityIsInRange) {
        "estimated_win_probability must be between 0 and 1."
    }
    require(submission.reasonJa.isNotBlank()) {
        "reason_ja is required."
    }
    require((submission.tradePlan?.revisionCount ?: 0) <= maxTradePlanRevisions) {
        "trade_plan revision_count must be less than or equal to $maxTradePlanRevisions."
    }

    if (submission.action == DecisionAction.ENTER) {
        require(submission.entryIntent != null) {
            "ENTER decision requires entry_intent."
        }
        require(submission.tradePlan != null) {
            "ENTER decision requires trade_plan."
        }
        require(submission.setupTags.isNotEmpty()) {
            "ENTER decision requires setup_tags."
        }
    }
}

private fun TradePlanDraft.toRecord(decisionId: UUID, createdAt: Instant): TradePlanRecord {
    return TradePlanRecord(
        tradePlanId = UUID.randomUUID(),
        decisionId = decisionId,
        draft = this,
        createdAt = createdAt,
    )
}

private fun EntryIntentDraft.toRecord(
    decisionId: UUID,
    tradePlanId: UUID,
    estimatedWinProbability: BigDecimal,
    createdAt: Instant,
): TradeIntentRecord {
    return TradeIntentRecord(
        intentId = UUID.randomUUID(),
        decisionId = decisionId,
        tradePlanId = tradePlanId,
        draft = this,
        estimatedWinProbability = estimatedWinProbability,
        createdAt = createdAt,
    )
}

private fun FalsificationRecord?.isFreshApprovedAt(observedAt: Instant, freshnessWindow: Duration): Boolean {
    if (this == null) {
        return false
    }
    if (verdict != FalsificationVerdict.APPROVED) {
        return false
    }

    val expiresAt = createdAt.plus(freshnessWindow)

    return !expiresAt.isBefore(observedAt)
}

/**
 * TradePlan 正式修正の既定上限。
 */
const val MAX_TRADE_PLAN_REVISIONS = 2
