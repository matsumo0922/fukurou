package me.matsumo.fukurou.trading.decision

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.fukurou.trading.decision.identity.DecisionIdentityGenerator
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.knowledge.DecisionJournalRecord
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
) : AtomicIntentConsumptionRepository {

    private val mutex = Mutex()
    private val decisions = mutableListOf<DecisionRecord>()
    private val tradePlans = mutableListOf<TradePlanRecord>()
    private val tradeIntents = mutableListOf<TradeIntentRecord>()
    private val falsifications = mutableListOf<FalsificationRecord>()
    private val intentConsumptions = mutableListOf<TradeIntentConsumptionRecord>()

    /**
     * 保存済み record の snapshot 読み取り境界。
     */
    val snapshots = InMemoryDecisionSnapshots(
        mutex = mutex,
        decisions = decisions,
        tradePlans = tradePlans,
        tradeIntents = tradeIntents,
        falsifications = falsifications,
        intentConsumptions = intentConsumptions,
    )

    override suspend fun submitDecision(submission: DecisionSubmission): Result<DecisionSubmissionResult> {
        return runCatching {
            mutex.withLock {
                validateDecisionSubmission(submission, maxTradePlanRevisions)

                val now = Instant.now(clock)
                val parentTradePlan = submission.tradePlan
                    ?.parentTradePlanId
                    ?.let { parentTradePlanId ->
                        tradePlans.firstOrNull { tradePlan -> tradePlan.tradePlanId == parentTradePlanId }
                    }

                validateTradePlanLineage(submission, parentTradePlan, maxTradePlanRevisions)

                val identity = submission.entryIntent?.let { intent ->
                    submission.tradePlan?.let { plan ->
                        submission.marketSnapshotId?.let { materialProjection ->
                            DecisionIdentityGenerator.generate(UUID.randomUUID(), plan, intent, materialProjection)
                        }
                    }
                }
                val decision = DecisionRecord(
                    decisionId = UUID.randomUUID(),
                    submission = submission,
                    createdAt = now,
                    identity = identity,
                )
                val tradePlan = submission.tradePlan?.toRecord(decision.decisionId, now)
                val tradeIntent = submission.entryIntent?.toRecord(
                    decisionId = decision.decisionId,
                    tradePlanId = requireNotNull(tradePlan?.tradePlanId) {
                        "${submission.action.name} decision requires trade_plan."
                    },
                    estimatedWinProbability = submission.estimatedWinProbability,
                    createdAt = now,
                )?.copy(identity = identity)

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

    override suspend fun latestDecisionByInvocationId(invocationId: String): Result<DecisionSubmissionResult?> {
        return runCatching {
            mutex.withLock {
                val decision = decisions
                    .filter { record -> record.submission.invocationId == invocationId }
                    .maxByOrNull { record -> record.createdAt }
                    ?: return@withLock null
                val tradeIntent = tradeIntents.firstOrNull { intent -> intent.decisionId == decision.decisionId }
                val tradePlan = tradePlans.firstOrNull { plan -> plan.decisionId == decision.decisionId }

                DecisionSubmissionResult(
                    decision = decision,
                    tradeIntent = tradeIntent,
                    tradePlan = tradePlan,
                )
            }
        }
    }

    override suspend fun findDecisionsCreatedBetween(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): Result<List<DecisionJournalRecord>> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            mutex.withLock {
                decisions
                    .asSequence()
                    .filter { decision -> decision.createdAt in from..<toExclusive }
                    .sortedByDescending { decision -> decision.createdAt }
                    .take(limit)
                    .sortedBy { decision -> decision.createdAt }
                    .map { decision -> decision.toJournalRecordLocked() }
                    .toList()
            }
        }
    }

    override suspend fun findDecisionsForStableFeed(
        cursor: StableFeedCursor,
        limit: Int,
    ): Result<List<DecisionJournalRecord>> {
        return runCatching {
            require(limit > 0) {
                "limit must be greater than 0."
            }

            mutex.withLock {
                decisions
                    .filter { decision -> cursor.accepts(decision.createdAt, decision.decisionId.toString()) }
                    .sortedWith(
                        compareByDescending<DecisionRecord> { decision -> decision.createdAt }
                            .thenBy { decision -> decision.decisionId.toString() },
                    )
                    .take(limit)
                    .map { decision -> decision.toJournalRecordLocked() }
            }
        }
    }

    override suspend fun latestFalsification(intentId: UUID): Result<FalsificationRecord?> {
        return runCatching {
            mutex.withLock {
                falsifications
                    .filter { falsification -> falsification.intentId == intentId }
                    .maxByOrNull { falsification -> falsification.createdAt }
            }
        }
    }

    override suspend fun tradeIntentReviewSnapshot(intentId: UUID): Result<TradeIntentReviewSnapshot?> {
        return runCatching {
            mutex.withLock {
                val intent = tradeIntents.firstOrNull { candidate -> candidate.intentId == intentId }
                    ?: return@withLock null
                val tradePlan = tradePlans.firstOrNull { candidate -> candidate.tradePlanId == intent.tradePlanId }

                TradeIntentReviewSnapshot(
                    tradeIntent = intent,
                    tradePlan = tradePlan,
                )
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
                appendIntentConsumptionLocked(
                    tradeIntents = tradeIntents,
                    intentConsumptions = intentConsumptions,
                    intentId = intentId,
                    orderId = orderId,
                    consumedAt = consumedAt,
                )
            }
        }
    }

    override suspend fun <T> consumeIntentAfterLedgerWrite(
        intentId: UUID,
        orderId: UUID?,
        consumedAt: Instant,
        ledgerBlock: suspend () -> T,
    ): Result<T> {
        mutex.lock()

        return try {
            validateConsumableIntentLocked(
                tradeIntents = tradeIntents,
                intentConsumptions = intentConsumptions,
                intentId = intentId,
            )

            val result = ledgerBlock()

            appendIntentConsumptionLocked(
                tradeIntents = tradeIntents,
                intentConsumptions = intentConsumptions,
                intentId = intentId,
                orderId = orderId,
                consumedAt = consumedAt,
            )

            Result.success(result)
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        } finally {
            mutex.unlock()
        }
    }

    private fun DecisionRecord.toJournalRecordLocked(): DecisionJournalRecord {
        val tradeIntent = tradeIntents.firstOrNull { intent -> intent.decisionId == decisionId }
        val tradePlan = tradePlans.firstOrNull { plan -> plan.decisionId == decisionId }
        val falsification = tradeIntent?.let { intent ->
            falsifications
                .filter { candidate -> candidate.intentId == intent.intentId }
                .maxByOrNull { candidate -> candidate.createdAt }
        }

        return DecisionJournalRecord(
            decision = this,
            tradeIntent = tradeIntent,
            tradePlan = tradePlan,
            falsification = falsification,
        )
    }
}

/**
 * InMemoryDecisionRepository の保存済み record snapshot reader。
 *
 * @param mutex repository state を守る mutex
 * @param decisions 保存済み decision
 * @param tradePlans 保存済み trade plan
 * @param tradeIntents 保存済み trade intent
 * @param falsifications 保存済み falsification
 * @param intentConsumptions 保存済み intent consumption
 */
class InMemoryDecisionSnapshots internal constructor(
    private val mutex: Mutex,
    private val decisions: List<DecisionRecord>,
    private val tradePlans: List<TradePlanRecord>,
    private val tradeIntents: List<TradeIntentRecord>,
    private val falsifications: List<FalsificationRecord>,
    private val intentConsumptions: List<TradeIntentConsumptionRecord>,
) {
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

private fun appendIntentConsumptionLocked(
    tradeIntents: List<TradeIntentRecord>,
    intentConsumptions: MutableList<TradeIntentConsumptionRecord>,
    intentId: UUID,
    orderId: UUID?,
    consumedAt: Instant,
): TradeIntentConsumptionRecord {
    validateConsumableIntentLocked(
        tradeIntents = tradeIntents,
        intentConsumptions = intentConsumptions,
        intentId = intentId,
    )

    val record = TradeIntentConsumptionRecord(
        consumptionId = UUID.randomUUID(),
        intentId = intentId,
        orderId = orderId,
        consumedAt = consumedAt,
    )

    intentConsumptions += record

    return record
}

private fun validateConsumableIntentLocked(
    tradeIntents: List<TradeIntentRecord>,
    intentConsumptions: List<TradeIntentConsumptionRecord>,
    intentId: UUID,
) {
    require(tradeIntents.any { intent -> intent.intentId == intentId }) {
        "trade intent was not found."
    }
    require(intentConsumptions.none { consumption -> consumption.intentId == intentId }) {
        "trade intent was already consumed."
    }
}

/**
 * decision submission の最小 contract を検証する。
 */
fun validateDecisionSubmission(submission: DecisionSubmission, maxTradePlanRevisions: Int = MAX_TRADE_PLAN_REVISIONS) {
    val estimatedProbabilityIsInRange = submission.estimatedWinProbability >= BigDecimal.ZERO &&
        submission.estimatedWinProbability <= BigDecimal.ONE
    val closeRatio = submission.closeRatio
    val closeRatioIsInRange = closeRatio?.let { value ->
        value > BigDecimal.ZERO && value <= BigDecimal.ONE
    } ?: true

    require(estimatedProbabilityIsInRange) {
        "estimated_win_probability must be between 0 and 1."
    }
    require(closeRatioIsInRange) {
        "close_ratio must be greater than zero and less than or equal to 1."
    }
    require(closeRatio == null || submission.action == DecisionAction.REDUCE) {
        "close_ratio is only supported for REDUCE decisions."
    }
    require(submission.reasonJa.isNotBlank()) {
        "reason_ja is required."
    }
    require((submission.tradePlan?.revisionCount ?: 0) <= maxTradePlanRevisions) {
        "trade_plan revision_count must be less than or equal to $maxTradePlanRevisions."
    }

    if (submission.action.requiresEntryIntent()) {
        require(submission.entryIntent != null) {
            "${submission.action.name} decision requires entry_intent."
        }
        require(submission.tradePlan != null) {
            "${submission.action.name} decision requires trade_plan."
        }
        require(submission.setupTags.isNotEmpty()) {
            "${submission.action.name} decision requires setup_tags."
        }
        val predicates = requireNotNull(submission.tradePlan).invalidationPredicates
        val usesTypedInvalidationContract = submission.systemPromptVersion == SystemPromptV1.VERSION
        require(predicates.isNotEmpty() || !usesTypedInvalidationContract) {
            "${submission.action.name} decision requires trade_plan_invalidation_predicates."
        }
        predicates.forEach(::validateInvalidationPredicate)
    }

    if (submission.action == DecisionAction.REDUCE) {
        require(closeRatio != null) {
            "REDUCE decision requires close_ratio."
        }
    }
}

private fun validateInvalidationPredicate(predicate: TradePlanInvalidationPredicate) {
    val needsDecimal = predicate.type in setOf(
        TradePlanInvalidationType.LAST_PRICE_AT_OR_BELOW,
        TradePlanInvalidationType.LAST_PRICE_AT_OR_ABOVE,
        TradePlanInvalidationType.BEST_BID_AT_OR_BELOW,
        TradePlanInvalidationType.BEST_ASK_AT_OR_ABOVE,
    )
    require(!needsDecimal || predicate.decimalThresholdJpy != null) { "price predicate requires decimal threshold." }
    require(predicate.type != TradePlanInvalidationType.TIME_AT_OR_AFTER || predicate.instantThreshold != null) {
        "time predicate requires instant threshold."
    }
    require(predicate.type == TradePlanInvalidationType.TIME_AT_OR_AFTER || predicate.instantThreshold == null) {
        "instant threshold is only supported for time predicate."
    }
    require(needsDecimal || predicate.decimalThresholdJpy == null) {
        "decimal threshold is only supported for price predicate."
    }
}

/**
 * TradePlan の parent / revision lineage contract を検証する。
 */
fun validateTradePlanLineage(
    submission: DecisionSubmission,
    parentTradePlan: TradePlanRecord?,
    maxTradePlanRevisions: Int = MAX_TRADE_PLAN_REVISIONS,
) {
    val tradePlan = submission.tradePlan ?: return

    if (submission.action == DecisionAction.ENTER) {
        require(tradePlan.parentTradePlanId == null) {
            "ENTER trade_plan must not include parent_trade_plan_id."
        }
        require(tradePlan.revisionCount == 0) {
            "ENTER trade_plan revision_count must be 0."
        }

        return
    }

    val parentTradePlanId = requireNotNull(tradePlan.parentTradePlanId) {
        "trade_plan revision requires parent_trade_plan_id."
    }
    val parent = requireNotNull(parentTradePlan) {
        "parent trade_plan was not found: $parentTradePlanId."
    }
    val expectedRevisionCount = parent.draft.revisionCount + 1

    require(tradePlan.symbol == parent.draft.symbol) {
        "trade_plan revision symbol must match parent trade_plan."
    }
    require(tradePlan.revisionCount == expectedRevisionCount) {
        "trade_plan revision_count must equal parent revision_count + 1."
    }
    require(expectedRevisionCount <= maxTradePlanRevisions) {
        "trade_plan revision_count must be less than or equal to $maxTradePlanRevisions."
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

/**
 * TradePlan 正式修正の既定上限。
 */
const val MAX_TRADE_PLAN_REVISIONS = 2
