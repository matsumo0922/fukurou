package me.matsumo.fukurou.trading.knowledge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenial
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialQuery
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialReader
import me.matsumo.fukurou.trading.activity.EmptyDecisionRunSafetyDenialReader
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.TradePlanRecord
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.ClosedTradeFact
import me.matsumo.fukurou.trading.evaluation.EvaluationMath
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_CANCELLED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunRecord
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.evaluation.SetupPerformance
import me.matsumo.fukurou.trading.evaluation.intersectLifecycle
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Knowledge tool が repository から読む decision 件数の倍率。
 */
private const val KNOWLEDGE_DECISION_FETCH_MULTIPLIER = 8

/**
 * Knowledge tool が一度に読む decision の最大件数。
 */
private const val MAX_KNOWLEDGE_DECISION_FETCH_LIMIT = 80

/**
 * Knowledge tool が一度に読む llm_runs の最大件数。
 */
private const val MAX_KNOWLEDGE_RUN_FETCH_LIMIT = 80

/**
 * Knowledge tool が一度に読む closed trade fact の最大件数。
 */
private const val MAX_KNOWLEDGE_EVALUATION_TRADE_FETCH_LIMIT = 200

/**
 * Knowledge response の通常 text 最大文字数。
 */
private const val KNOWLEDGE_TEXT_MAX_LENGTH = 240

/**
 * Knowledge response の短い text 最大文字数。
 */
private const val KNOWLEDGE_SHORT_TEXT_MAX_LENGTH = 160

/**
 * Knowledge response の識別子最大文字数。
 */
private const val KNOWLEDGE_IDENTIFIER_MAX_LENGTH = 96

/**
 * Knowledge response の配列要素最大件数。
 */
private const val KNOWLEDGE_LIST_MAX_ITEMS = 5

/**
 * Knowledge response の setup performance 最大件数。
 */
private const val KNOWLEDGE_SETUP_PERFORMANCE_MAX_ITEMS = 5

/**
 * 類似検索に使う最小 token 文字数。
 */
private const val KNOWLEDGE_MIN_SEARCH_TOKEN_LENGTH = 2

/**
 * 切り詰めた Knowledge text に付ける suffix。
 */
private const val KNOWLEDGE_TRUNCATED_SUFFIX = "...[TRUNCATED]"

/**
 * secret redaction 後に whitespace を正規化する regex。
 */
private val KnowledgeWhitespaceRegex = Regex("\\s+")

/**
 * setup/signal query を token 化する regex。
 */
private val KnowledgeTokenSplitRegex = Regex("[\\s,./:;_\\-]+")

/**
 * Knowledge tool 用の read-only service。
 *
 * @param decisionRepository decision protocol の repository
 * @param llmRunRepository llm_runs の repository
 * @param evaluationRepository evaluation aggregate の repository
 * @param clock lookback 期間の基準 clock
 * @param redactor response text の秘密値 redactor
 */
class KnowledgeService(
    private val decisionRepository: DecisionRepository,
    private val llmRunRepository: LlmRunRepository,
    private val evaluationRepository: EvaluationRepository,
    private val safetyDenialReader: DecisionRunSafetyDenialReader = EmptyDecisionRunSafetyDenialReader,
    private val clock: Clock = Clock.systemUTC(),
    private val redactor: SecretRedactor = SecretRedactor.fromEnvironment(System.getenv()),
) {

    /**
     * recent lessons を取得する。
     */
    suspend fun getRecentLessons(query: KnowledgeRecentLessonsQuery): Result<KnowledgeRecentLessonsResult> {
        return runCatching {
            validateRecentLessonsQuery(query)

            val scope = evaluationRepository.resolveScope(null, "CURRENT").getOrThrow()
            val period = query.toEvaluationPeriod(clock.instant()).intersectLifecycle(scope)
            val decisionRecords = fetchRecentDecisionRecords(period, query.limit)
                .filter { record -> record.matchesSymbol(query.symbol) }
            val runRecords = fetchRecentRuns(period, query.limit)
                .filter { record -> record.symbol == query.symbol }
            val tradeResult = evaluationRepository.fetchClosedTrades(
                period = period,
                limit = MAX_KNOWLEDGE_EVALUATION_TRADE_FETCH_LIMIT,
                scope = scope,
            ).getOrThrow()
            val safetyDenialPage = safetyDenialReader.readSafetyDenials(
                DecisionRunSafetyDenialQuery(
                    symbol = query.symbol,
                    from = period.from,
                    toExclusive = period.toExclusive,
                    limit = query.limit.coerceAtMost(KNOWLEDGE_LIST_MAX_ITEMS),
                ),
            ).getOrThrow()
            val setupPerformance = setupPerformanceSummaries(
                trades = tradeResult.trades,
                queryTags = emptyList(),
            )
            val lessons = decisionRecords
                .take(query.limit)
                .map { record -> record.toKnowledgeLesson() }
            val failurePatterns = failurePatterns(
                decisionRecords = decisionRecords,
                runRecords = runRecords,
                setupPerformance = setupPerformance,
                limit = query.limit,
            )
            val runSummaries = runRecords
                .take(query.limit)
                .map { record -> record.toRunSummary() }

            KnowledgeRecentLessonsResult(
                symbol = query.symbol,
                lookbackDays = query.lookbackDays,
                limit = query.limit,
                lessons = lessons,
                failurePatterns = failurePatterns,
                runSummaries = runSummaries,
                setupPerformance = setupPerformance.take(KNOWLEDGE_SETUP_PERFORMANCE_MAX_ITEMS),
                evaluationTruncated = tradeResult.truncated,
                safetyFloorDenials = safetyDenialPage.denials
                    .take(KNOWLEDGE_LIST_MAX_ITEMS)
                    .map { denial -> denial.toKnowledgeSafetyFloorDenial() },
                safetyFloorDenialsTruncated = safetyDenialPage.truncated ||
                    safetyDenialPage.denials.size > KNOWLEDGE_LIST_MAX_ITEMS,
            )
        }
    }

    /**
     * setup tag と signal summary に近い過去 decision を検索する。
     */
    suspend fun searchSimilarSetups(query: KnowledgeSimilarSetupsQuery): Result<KnowledgeSimilarSetupsResult> {
        return runCatching {
            validateSimilarSetupsQuery(query)

            val normalizedTags = normalizeTags(query.setupTags)
            val searchTerms = query.searchTerms()
            val scope = evaluationRepository.resolveScope(null, "CURRENT").getOrThrow()
            val period = query.toEvaluationPeriod(clock.instant()).intersectLifecycle(scope)
            val runRecords = fetchRecentRuns(period, query.limit)
                .filter { record -> record.symbol == query.symbol }
            val runsByInvocationId = runRecords.associateBy { record -> record.invocationId }
            val decisionRecords = fetchRecentDecisionRecords(period, query.limit)
                .filter { record -> record.matchesSymbol(query.symbol) }
            val scoredRecords = decisionRecords
                .mapNotNull { record -> record.scoredOrNull(normalizedTags, searchTerms, runsByInvocationId) }
                .sortedWith(
                    compareByDescending<ScoredKnowledgeRecord> { record -> record.score }
                        .thenByDescending { record -> record.journalRecord.decision.createdAt },
                )
                .take(query.limit)
            val tradeResult = evaluationRepository.fetchClosedTrades(
                period = period,
                limit = MAX_KNOWLEDGE_EVALUATION_TRADE_FETCH_LIMIT,
                scope = scope,
            ).getOrThrow()
            val setupPerformance = setupPerformanceSummaries(
                trades = tradeResult.trades,
                queryTags = normalizedTags,
            )

            KnowledgeSimilarSetupsResult(
                symbol = query.symbol,
                setupTags = normalizedTags,
                regime = query.regime.sanitizeOptional(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                signalSummary = query.signalSummary.sanitizeOptional(KNOWLEDGE_TEXT_MAX_LENGTH),
                lookbackDays = query.lookbackDays,
                limit = query.limit,
                hits = scoredRecords.map { record -> record.toSimilarSetupHit() },
                setupPerformance = setupPerformance.take(KNOWLEDGE_SETUP_PERFORMANCE_MAX_ITEMS),
                evaluationTruncated = tradeResult.truncated,
            )
        }
    }

    private fun validateRecentLessonsQuery(query: KnowledgeRecentLessonsQuery) {
        require(query.limit in 1..MAX_KNOWLEDGE_RECENT_LESSONS_LIMIT) {
            "limit must be between 1 and $MAX_KNOWLEDGE_RECENT_LESSONS_LIMIT."
        }
        require(query.lookbackDays in 1..MAX_KNOWLEDGE_LOOKBACK_DAYS) {
            "lookback_days must be between 1 and $MAX_KNOWLEDGE_LOOKBACK_DAYS."
        }
    }

    private fun validateSimilarSetupsQuery(query: KnowledgeSimilarSetupsQuery) {
        val normalizedTags = normalizeTags(query.setupTags)
        val searchTerms = query.searchTerms()
        val hasSearchCondition = normalizedTags.isNotEmpty() || searchTerms.isNotEmpty()

        require(query.limit in 1..MAX_KNOWLEDGE_SIMILAR_SETUPS_LIMIT) {
            "limit must be between 1 and $MAX_KNOWLEDGE_SIMILAR_SETUPS_LIMIT."
        }
        require(query.lookbackDays in 1..MAX_KNOWLEDGE_LOOKBACK_DAYS) {
            "lookback_days must be between 1 and $MAX_KNOWLEDGE_LOOKBACK_DAYS."
        }
        require(hasSearchCondition) {
            "setup_tags, regime, or signal_summary is required."
        }
    }

    private suspend fun fetchRecentDecisionRecords(
        period: EvaluationPeriod,
        responseLimit: Int,
    ): List<DecisionJournalRecord> {
        val fetchLimit = (responseLimit * KNOWLEDGE_DECISION_FETCH_MULTIPLIER)
            .coerceAtMost(MAX_KNOWLEDGE_DECISION_FETCH_LIMIT)

        return decisionRepository.findDecisionsCreatedBetween(
            from = period.from,
            toExclusive = period.toExclusive,
            limit = fetchLimit,
        ).getOrThrow().asReversed()
    }

    private suspend fun fetchRecentRuns(period: EvaluationPeriod, responseLimit: Int): List<LlmRunRecord> {
        val fetchLimit = (responseLimit * KNOWLEDGE_DECISION_FETCH_MULTIPLIER)
            .coerceAtMost(MAX_KNOWLEDGE_RUN_FETCH_LIMIT)

        return llmRunRepository.findRunsStartedBetween(
            from = period.from,
            toExclusive = period.toExclusive,
            limit = fetchLimit,
        ).getOrThrow().asReversed()
    }

    private fun failurePatterns(
        decisionRecords: List<DecisionJournalRecord>,
        runRecords: List<LlmRunRecord>,
        setupPerformance: List<KnowledgeSetupPerformanceSummary>,
        limit: Int,
    ): List<KnowledgeFailurePattern> {
        val runPatterns = runRecords.mapNotNull { record -> record.toRunFailurePatternOrNull() }
        val performancePatterns = setupPerformance.mapNotNull { performance ->
            performance.toPerformanceFailurePatternOrNull()
        }
        val decisionPatterns = decisionRecords.flatMap { record -> record.toDecisionFailurePatterns() }

        return interleavedFailurePatterns(
            sourcePatternBuckets = listOf(runPatterns, performancePatterns, decisionPatterns),
            limit = limit,
        )
    }

    private fun interleavedFailurePatterns(
        sourcePatternBuckets: List<List<KnowledgeFailurePattern>>,
        limit: Int,
    ): List<KnowledgeFailurePattern> {
        val patterns = mutableListOf<KnowledgeFailurePattern>()
        var patternIndex = 0

        while (patterns.size < limit) {
            var addedPattern = false

            for (bucket in sourcePatternBuckets) {
                val canAddPattern = patternIndex < bucket.size && patterns.size < limit

                if (canAddPattern) {
                    patterns += bucket[patternIndex]
                    addedPattern = true
                }
            }

            if (!addedPattern) {
                return patterns
            }

            patternIndex += 1
        }

        return patterns
    }

    private fun DecisionJournalRecord.toKnowledgeLesson(): KnowledgeLesson {
        val submission = decision.submission

        return KnowledgeLesson(
            decisionId = decision.decisionId.toString(),
            createdAt = decision.createdAt,
            invocationId = submission.invocationId?.sanitizeIdentifier(),
            action = submission.action.name,
            setupTags = sanitizeList(recordSetupTags(), KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
            estimatedWinProbability = submission.estimatedWinProbability.toPlainString(),
            expectedRMultiple = submission.expectedRMultiple?.toPlainString(),
            reasonJa = submission.reasonJa.sanitize(KNOWLEDGE_TEXT_MAX_LENGTH),
            selfReviewSummary = submission.selfReviewJson.sanitize(KNOWLEDGE_TEXT_MAX_LENGTH),
            missingDataJa = sanitizeList(submission.missingDataJa, KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
            noTradeConditionsJa = sanitizeList(submission.noTradeConditionsJa, KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
            tradePlanSummary = tradePlan?.toKnowledgeTradePlanSummary(),
            falsificationSummary = falsification?.let { record ->
                KnowledgeFalsificationSummary(
                    verdict = record.verdict.name,
                    reasonJa = record.reasonJa.sanitize(KNOWLEDGE_TEXT_MAX_LENGTH),
                    createdAt = record.createdAt,
                )
            },
        )
    }

    private fun DecisionRunSafetyDenial.toKnowledgeSafetyFloorDenial(): KnowledgeSafetyFloorDenial {
        val proposal = decision?.let { declared ->
            KnowledgePriorProposal(
                action = declared.action.sanitize(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                setupTags = declared.setupTagsJson.toSanitizedSetupTags(),
                tradePlanThesisJa = intent?.thesisJa.sanitizeOptional(KNOWLEDGE_TEXT_MAX_LENGTH),
                estimatedWinProbability = declared.estimatedWinProbability.sanitizeOptional(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                expectedRMultiple = declared.expectedRMultiple.sanitizeOptional(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                roundTripCostR = declared.roundTripCostR.sanitizeOptional(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                intent = intent?.let { priorIntent ->
                    KnowledgePriorIntent(
                        orderType = priorIntent.orderType.sanitize(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                        sizeBtc = priorIntent.sizeBtc.sanitize(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                        priceJpy = priorIntent.priceJpy.sanitizeOptional(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                        protectiveStopPriceJpy = priorIntent.protectiveStopPriceJpy.sanitize(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                        takeProfitPriceJpy = priorIntent.takeProfitPriceJpy.sanitizeOptional(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                    )
                },
            )
        }

        return KnowledgeSafetyFloorDenial(
            invocationId = invocationId.sanitizeIdentifier(),
            deniedAt = deniedAt,
            finalReason = finalReason.sanitizeOptional(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
            priorProposal = proposal,
            falsifier = falsification?.let { value ->
                KnowledgeFalsifierOutcome(
                    verdict = value.verdict.sanitize(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                    createdAt = value.createdAt,
                )
            },
            machineOutcome = KnowledgeMachineOutcome(
                rule = safetyViolation.rule.sanitize(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
                measuredValue = safetyViolation.measuredValue.sanitize(KNOWLEDGE_TEXT_MAX_LENGTH),
                limitValue = safetyViolation.limitValue.sanitize(KNOWLEDGE_TEXT_MAX_LENGTH),
                messageJa = safetyViolation.messageJa.sanitize(KNOWLEDGE_TEXT_MAX_LENGTH),
            ),
        )
    }

    private fun TradePlanRecord.toKnowledgeTradePlanSummary(): KnowledgeTradePlanSummary {
        return KnowledgeTradePlanSummary(
            tradePlanId = tradePlanId.toString(),
            thesisJa = draft.thesisJa.sanitize(KNOWLEDGE_TEXT_MAX_LENGTH),
            invalidationConditionsJa = sanitizeList(
                values = draft.invalidationConditionsJa,
                maxTextLength = KNOWLEDGE_SHORT_TEXT_MAX_LENGTH,
            ),
            revisionCount = draft.revisionCount,
        )
    }

    private fun DecisionJournalRecord.toDecisionFailurePatterns(): List<KnowledgeFailurePattern> {
        val patterns = mutableListOf<KnowledgeFailurePattern>()

        missingDataPatternOrNull()?.let { pattern -> patterns.add(pattern) }
        noTradeConditionPatternOrNull()?.let { pattern -> patterns.add(pattern) }
        rejectedFalsificationPatternOrNull()?.let { pattern -> patterns.add(pattern) }
        nonPositiveExpectedRPatternOrNull()?.let { pattern -> patterns.add(pattern) }

        return patterns
    }

    private fun DecisionJournalRecord.missingDataPatternOrNull(): KnowledgeFailurePattern? {
        val missingData = decision.submission.missingDataJa

        if (missingData.isEmpty()) {
            return null
        }

        return KnowledgeFailurePattern(
            source = "decision",
            sourceId = decision.decisionId.toString().sanitizeIdentifier(),
            occurredAt = decision.createdAt,
            summary = "missing_data",
            evidence = sanitizeList(missingData, KNOWLEDGE_SHORT_TEXT_MAX_LENGTH).joinToString("; "),
        )
    }

    private fun DecisionJournalRecord.noTradeConditionPatternOrNull(): KnowledgeFailurePattern? {
        val noTradeConditions = decision.submission.noTradeConditionsJa

        if (noTradeConditions.isEmpty()) {
            return null
        }

        return KnowledgeFailurePattern(
            source = "decision",
            sourceId = decision.decisionId.toString().sanitizeIdentifier(),
            occurredAt = decision.createdAt,
            summary = "no_trade_conditions",
            evidence = sanitizeList(noTradeConditions, KNOWLEDGE_SHORT_TEXT_MAX_LENGTH).joinToString("; "),
        )
    }

    private fun DecisionJournalRecord.rejectedFalsificationPatternOrNull(): KnowledgeFailurePattern? {
        val record = falsification ?: return null

        if (record.verdict != FalsificationVerdict.REJECTED) {
            return null
        }

        return KnowledgeFailurePattern(
            source = "falsification",
            sourceId = record.falsificationId.toString().sanitizeIdentifier(),
            occurredAt = record.createdAt,
            summary = "falsifier_rejected",
            evidence = record.reasonJa.sanitize(KNOWLEDGE_TEXT_MAX_LENGTH),
        )
    }

    private fun DecisionJournalRecord.nonPositiveExpectedRPatternOrNull(): KnowledgeFailurePattern? {
        val expectedRMultiple = decision.submission.expectedRMultiple ?: return null

        if (expectedRMultiple > BigDecimal.ZERO) {
            return null
        }

        return KnowledgeFailurePattern(
            source = "decision",
            sourceId = decision.decisionId.toString().sanitizeIdentifier(),
            occurredAt = decision.createdAt,
            summary = "non_positive_expected_r",
            evidence = expectedRMultiple.toPlainString(),
        )
    }

    private fun LlmRunRecord.toRunFailurePatternOrNull(): KnowledgeFailurePattern? {
        val failed = status == LLM_RUN_STATUS_FAILED
        val cancelled = status == LLM_RUN_STATUS_CANCELLED

        if (!failed && !cancelled) {
            return null
        }

        return KnowledgeFailurePattern(
            source = "llm_run",
            sourceId = invocationId.sanitizeIdentifier(),
            occurredAt = finishedAt ?: startedAt,
            summary = status.lowercase(),
            evidence = errorMessage.sanitizeOptional(KNOWLEDGE_TEXT_MAX_LENGTH) ?: "",
        )
    }

    private fun KnowledgeSetupPerformanceSummary.toPerformanceFailurePatternOrNull(): KnowledgeFailurePattern? {
        val expectedRValue = expectedR?.toBigDecimalOrNull()
        val totalPnlValue = totalPnlJpy.toBigDecimalOrNull()
        val weakExpectedR = expectedRValue != null && expectedRValue < BigDecimal.ZERO
        val losingSetup = totalPnlValue != null && totalPnlValue < BigDecimal.ZERO

        if (!weakExpectedR && !losingSetup) {
            return null
        }

        return KnowledgeFailurePattern(
            source = "evaluation",
            sourceId = setupTag.sanitizeIdentifier(),
            occurredAt = null,
            summary = "weak_setup_performance",
            evidence = "trade_count=$tradeCount total_pnl_jpy=$totalPnlJpy expected_r=${expectedR ?: "null"}",
        )
    }

    private fun LlmRunRecord.toRunSummary(): KnowledgeRunSummary {
        return KnowledgeRunSummary(
            invocationId = invocationId.sanitizeIdentifier(),
            mode = mode.name,
            symbol = symbol.apiSymbol,
            status = status,
            triggerKind = triggerKind?.name,
            startedAt = startedAt,
            finishedAt = finishedAt,
            errorMessage = errorMessage.sanitizeOptional(KNOWLEDGE_TEXT_MAX_LENGTH),
        )
    }

    private fun DecisionJournalRecord.scoredOrNull(
        queryTags: List<String>,
        searchTerms: List<String>,
        runsByInvocationId: Map<String, LlmRunRecord>,
    ): ScoredKnowledgeRecord? {
        val recordTags = recordSetupTags().map { tag -> tag.normalizeTerm() }
        val matchedTags = queryTags.filter { tag -> tag.normalizeTerm() in recordTags }
        val searchText = searchableText().normalizeTerm()
        val searchTokens = searchableTokens()
        val matchedTerms = searchTerms.filter { term ->
            term.matchesSearchText(searchText, searchTokens)
        }
        val falsifierRejectedScore = if (falsification?.verdict == FalsificationVerdict.REJECTED) 1 else 0
        val baseScore = matchedTags.size * 3 + matchedTerms.size

        if (baseScore <= 0) {
            return null
        }

        val score = baseScore + falsifierRejectedScore
        val invocationId = decision.submission.invocationId
        val runRecord = invocationId?.let { value -> runsByInvocationId[value] }

        return ScoredKnowledgeRecord(
            journalRecord = this,
            runRecord = runRecord,
            score = score,
            matchedTerms = (matchedTags + matchedTerms)
                .distinct()
                .take(KNOWLEDGE_LIST_MAX_ITEMS),
        )
    }

    private fun ScoredKnowledgeRecord.toSimilarSetupHit(): KnowledgeSimilarSetupHit {
        val record = journalRecord

        return KnowledgeSimilarSetupHit(
            score = score,
            matchedTerms = matchedTerms,
            lesson = record.toKnowledgeLesson(),
            outcome = KnowledgeDecisionOutcome(
                runStatus = runRecord?.status,
                falsificationVerdict = record.falsification?.verdict?.name,
                hasTradeIntent = record.tradeIntent != null,
                expectedRMultiple = record.decision.submission.expectedRMultiple?.toPlainString(),
            ),
        )
    }

    private fun setupPerformanceSummaries(
        trades: List<ClosedTradeFact>,
        queryTags: List<String>,
    ): List<KnowledgeSetupPerformanceSummary> {
        val normalizedQueryTags = queryTags.map { tag -> tag.normalizeTerm() }.toSet()
        val performances = EvaluationMath.summarizeBySetup(trades)
        val filteredPerformances = if (normalizedQueryTags.isEmpty()) {
            performances
        } else {
            performances.filter { performance -> performance.setupTag.normalizeTerm() in normalizedQueryTags }
        }

        return filteredPerformances
            .sortedWith(
                compareBy<SetupPerformance> { performance -> performance.stats.expectedR ?: BigDecimal.ZERO }
                    .thenBy { performance -> performance.setupTag },
            )
            .map { performance -> performance.toSummary() }
    }

    private fun SetupPerformance.toSummary(): KnowledgeSetupPerformanceSummary {
        val performanceStats = stats

        return KnowledgeSetupPerformanceSummary(
            setupTag = setupTag.sanitize(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH),
            tradeCount = performanceStats.tradeCount,
            totalPnlJpy = performanceStats.totalPnlJpy.toPlainString(),
            profitFactor = performanceStats.profitFactor?.toPlainString(),
            winRate = performanceStats.winRate?.toPlainString(),
            expectedR = performanceStats.expectedR?.toPlainString(),
            averageMaeR = performanceStats.averageMaeR?.toPlainString(),
            averageMfeR = performanceStats.averageMfeR?.toPlainString(),
        )
    }

    private fun DecisionJournalRecord.recordSetupTags(): List<String> {
        return (decision.submission.setupTags + tradePlan?.draft?.setupTags.orEmpty())
            .map { value -> value.trim() }
            .filter { value -> value.isNotBlank() }
            .distinct()
            .take(KNOWLEDGE_LIST_MAX_ITEMS)
    }

    private fun DecisionJournalRecord.matchesSymbol(symbol: TradingSymbol): Boolean {
        val recordSymbol = tradeIntent?.draft?.symbol
            ?: tradePlan?.draft?.symbol
            ?: TradingSymbol.BTC

        return recordSymbol == symbol
    }

    private fun DecisionJournalRecord.searchableText(): String {
        val submission = decision.submission

        return listOf(
            submission.action.name,
            submission.reasonJa,
            submission.selfReviewJson,
            submission.missingDataJa.joinToString(" "),
            submission.noTradeConditionsJa.joinToString(" "),
            recordSetupTags().joinToString(" "),
            tradePlan?.draft?.thesisJa.orEmpty(),
            tradePlan?.draft?.invalidationConditionsJa.orEmpty().joinToString(" "),
            falsification?.reasonJa.orEmpty(),
        ).joinToString(" ")
    }

    private fun DecisionJournalRecord.searchableTokens(): Set<String> {
        return searchableText().searchTokens().toSet()
    }

    private fun KnowledgeRecentLessonsQuery.toEvaluationPeriod(now: Instant): EvaluationPeriod {
        return evaluationPeriod(now, lookbackDays)
    }

    private fun KnowledgeSimilarSetupsQuery.toEvaluationPeriod(now: Instant): EvaluationPeriod {
        return evaluationPeriod(now, lookbackDays)
    }

    private fun evaluationPeriod(now: Instant, lookbackDays: Int): EvaluationPeriod {
        return EvaluationPeriod(
            from = now.minus(Duration.ofDays(lookbackDays.toLong())),
            toExclusive = now.plusMillis(1),
        )
    }

    private fun KnowledgeSimilarSetupsQuery.searchTerms(): List<String> {
        return listOfNotNull(regime, signalSummary)
            .flatMap { value -> value.queryTerms() }
            .distinct()
            .take(KNOWLEDGE_LIST_MAX_ITEMS)
    }

    private fun String.queryTerms(): List<String> {
        val sanitizedValue = sanitize(KNOWLEDGE_TEXT_MAX_LENGTH)
        val splitTerms = sanitizedValue.searchTokens()
        val wholeTerm = sanitizedValue.normalizeTerm()
            .takeIf { value -> value.length >= KNOWLEDGE_MIN_SEARCH_TOKEN_LENGTH }

        return (splitTerms + listOfNotNull(wholeTerm)).distinct()
    }

    private fun String.searchTokens(): List<String> {
        return split(KnowledgeTokenSplitRegex)
            .map { value -> value.normalizeTerm() }
            .filter { value -> value.length >= KNOWLEDGE_MIN_SEARCH_TOKEN_LENGTH }
    }

    private fun String.matchesSearchText(searchText: String, searchTokens: Set<String>): Boolean {
        val normalizedTerm = normalizeTerm()
        val shortSearchToken = normalizedTerm.length <= KNOWLEDGE_MIN_SEARCH_TOKEN_LENGTH

        if (shortSearchToken) {
            return normalizedTerm in searchTokens
        }

        return normalizedTerm in searchTokens || searchText.contains(normalizedTerm)
    }

    private fun normalizeTags(tags: List<String>): List<String> {
        return tags
            .map { value -> value.sanitize(KNOWLEDGE_SHORT_TEXT_MAX_LENGTH) }
            .filter { value -> value.isNotBlank() }
            .distinct()
            .take(KNOWLEDGE_LIST_MAX_ITEMS)
    }

    private fun sanitizeList(values: List<String>, maxTextLength: Int): List<String> {
        return values
            .map { value -> value.sanitize(maxTextLength) }
            .filter { value -> value.isNotBlank() }
            .take(KNOWLEDGE_LIST_MAX_ITEMS)
    }

    private fun String?.sanitizeOptional(maxLength: Int): String? {
        val value = this ?: return null
        val sanitized = value.sanitize(maxLength)

        return sanitized.ifBlank { null }
    }

    private fun String.sanitize(maxLength: Int): String {
        val redacted = redactor.redact(this)
        val normalized = redacted.replace(KnowledgeWhitespaceRegex, " ").trim()

        if (normalized.length <= maxLength) {
            return normalized
        }

        return normalized.take(maxLength) + KNOWLEDGE_TRUNCATED_SUFFIX
    }

    private fun String.sanitizeIdentifier(): String {
        return sanitize(KNOWLEDGE_IDENTIFIER_MAX_LENGTH)
    }

    private fun String.toSanitizedSetupTags(): List<String> {
        val values = runCatching {
            Json.parseToJsonElement(this).jsonArray.mapNotNull { value -> (value as? JsonPrimitive)?.content }
        }.getOrDefault(emptyList())

        return sanitizeList(values, KNOWLEDGE_SHORT_TEXT_MAX_LENGTH)
    }

    private fun String.normalizeTerm(): String {
        return trim().lowercase()
    }
}

/**
 * 類似度計算済みの decision record。
 *
 * @param journalRecord decision journal record
 * @param runRecord invocation に紐づく llm_run
 * @param score 類似度 score
 * @param matchedTerms 一致した検索語
 */
private data class ScoredKnowledgeRecord(
    val journalRecord: DecisionJournalRecord,
    val runRecord: LlmRunRecord?,
    val score: Int,
    val matchedTerms: List<String>,
)
