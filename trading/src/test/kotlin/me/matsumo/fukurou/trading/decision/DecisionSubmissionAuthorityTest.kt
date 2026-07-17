package me.matsumo.fukurou.trading.decision

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DecisionSubmissionAuthorityTest {
    @Test
    fun `canonical payload classifies every decision submission field`() {
        val declaredFields = DecisionSubmission::class.java.declaredFields
            .asSequence()
            .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
            .map { field -> field.name }
            .toSet()
        val classifiedFields = DECISION_SUBMISSION_BUSINESS_FIELDS + DECISION_SUBMISSION_EXCLUDED_METADATA_FIELDS

        assertTrue(
            DECISION_SUBMISSION_BUSINESS_FIELDS.intersect(DECISION_SUBMISSION_EXCLUDED_METADATA_FIELDS).isEmpty(),
            "DecisionSubmission field classifications must not overlap.",
        )
        assertEquals(
            classifiedFields,
            declaredFields,
            "Every DecisionSubmission field must be classified as business payload or excluded metadata.",
        )
    }

    @Test
    fun `canonical payload changes for every mutable persisted business field`() {
        val baseline = completeSubmission()
        val intent = requireNotNull(baseline.entryIntent)
        val plan = requireNotNull(baseline.tradePlan)
        val pricePredicate = plan.invalidationPredicates[0]
        val timePredicate = plan.invalidationPredicates[1]
        val variants = mapOf(
            "action" to baseline.copy(action = DecisionAction.ADD_LONG),
            "closeRatio" to baseline.copy(closeRatio = BigDecimal("0.2")),
            "setupTags" to baseline.copy(setupTags = listOf("changed")),
            "estimatedWinProbability" to baseline.copy(estimatedWinProbability = BigDecimal("0.61")),
            "expectedRMultiple" to baseline.copy(expectedRMultiple = BigDecimal("2.4")),
            "roundTripCostR" to baseline.copy(roundTripCostR = BigDecimal("0.09")),
            "toolEvidenceIds" to baseline.copy(toolEvidenceIds = listOf("tool-changed")),
            "factCheck" to baseline.copy(factCheckJson = """{"a":1,"nested":{"x":3}}"""),
            "selfReview" to baseline.copy(selfReviewJson = """{"risk":"changed"}"""),
            "reasonJa" to baseline.copy(reasonJa = "変更した理由"),
            "missingDataJa" to baseline.copy(missingDataJa = listOf("changed")),
            "noTradeConditionsJa" to baseline.copy(noTradeConditionsJa = listOf("changed")),
            "intent.side" to baseline.copy(entryIntent = intent.copy(side = OrderSide.SELL)),
            "intent.orderType" to baseline.copy(entryIntent = intent.copy(orderType = OrderType.LIMIT)),
            "intent.sizeBtc" to baseline.copy(entryIntent = intent.copy(sizeBtc = BigDecimal("0.02"))),
            "intent.priceJpy" to baseline.copy(entryIntent = intent.copy(priceJpy = BigDecimal("9999000"))),
            "intent.protectiveStopPriceJpy" to baseline.copy(
                entryIntent = intent.copy(protectiveStopPriceJpy = BigDecimal("9600000")),
            ),
            "intent.takeProfitPriceJpy" to baseline.copy(
                entryIntent = intent.copy(takeProfitPriceJpy = BigDecimal("10600000")),
            ),
            "plan.parentTradePlanId" to baseline.copy(
                tradePlan = plan.copy(parentTradePlanId = UUID.fromString("00000000-0000-0000-0000-000000000002")),
            ),
            "plan.revisionCount" to baseline.copy(tradePlan = plan.copy(revisionCount = 1)),
            "plan.thesisJa" to baseline.copy(tradePlan = plan.copy(thesisJa = "変更した仮説")),
            "plan.invalidationConditionsJa" to baseline.copy(
                tradePlan = plan.copy(invalidationConditionsJa = listOf("変更した否定条件")),
            ),
            "plan.targetPriceJpy" to baseline.copy(tradePlan = plan.copy(targetPriceJpy = BigDecimal("10600000"))),
            "plan.timeStopAt" to baseline.copy(
                tradePlan = plan.copy(timeStopAt = Instant.parse("2026-07-17T02:00:00Z")),
            ),
            "plan.setupTags" to baseline.copy(tradePlan = plan.copy(setupTags = listOf("plan-changed"))),
            "predicate.type" to baseline.copy(
                tradePlan = plan.copy(
                    invalidationPredicates = listOf(
                        pricePredicate.copy(type = TradePlanInvalidationType.LAST_PRICE_AT_OR_ABOVE),
                        timePredicate,
                    ),
                ),
            ),
            "predicate.decimalThresholdJpy" to baseline.copy(
                tradePlan = plan.copy(
                    invalidationPredicates = listOf(
                        pricePredicate.copy(decimalThresholdJpy = BigDecimal("9500000")),
                        timePredicate,
                    ),
                ),
            ),
            "predicate.instantThreshold" to baseline.copy(
                tradePlan = plan.copy(
                    invalidationPredicates = listOf(
                        pricePredicate,
                        timePredicate.copy(instantThreshold = Instant.parse("2026-07-17T03:00:00Z")),
                    ),
                ),
            ),
        )
        val baselineHash = baseline.canonicalBusinessPayload().hash

        variants.forEach { (field, variant) ->
            assertNotEquals(baselineHash, variant.canonicalBusinessPayload().hash, field)
        }

        val canonicalJson = baseline.canonicalBusinessPayload().canonicalJson
        assertTrue(canonicalJson.contains("\"entryIntent\":{\"symbol\":\"BTC\""))
        assertTrue(canonicalJson.contains("\"tradePlan\":{\"parentTradePlanId\""))
        assertTrue(canonicalJson.contains("\"symbol\":\"BTC\""))
    }

    @Test
    fun `canonical payload normalizes JSON key order and decimal scale`() {
        val baseline = completeSubmission()
        val reordered = baseline.copy(
            estimatedWinProbability = BigDecimal("0.6000"),
            expectedRMultiple = BigDecimal("2.000"),
            roundTripCostR = BigDecimal("0.0500"),
            factCheckJson = """{"nested":{"b":2,"a":1},"a":1}""",
            selfReviewJson = """{"z":2,"risk":"ok"}""",
            entryIntent = requireNotNull(baseline.entryIntent).copy(
                sizeBtc = BigDecimal("0.0100"),
                protectiveStopPriceJpy = BigDecimal("9700000.00"),
                takeProfitPriceJpy = BigDecimal("10500000.000"),
            ),
            tradePlan = requireNotNull(baseline.tradePlan).copy(
                targetPriceJpy = BigDecimal("10500000.00"),
                invalidationPredicates = baseline.tradePlan.invalidationPredicates.map { predicate ->
                    predicate.copy(decimalThresholdJpy = predicate.decimalThresholdJpy?.setScale(2))
                },
            ),
        )

        assertEquals(baseline.canonicalBusinessPayload(), reordered.canonicalBusinessPayload())
    }

    @Test
    fun `canonical payload excludes server and transport metadata`() {
        val baseline = completeSubmission()
        val metadataChanged = baseline.copy(
            invocationId = "other-run",
            llmProvider = "other-provider",
            promptHash = "other-prompt",
            systemPromptVersion = "other-system-prompt",
            marketSnapshotId = "other-snapshot",
        )

        assertEquals(baseline.canonicalBusinessPayload(), metadataChanged.canonicalBusinessPayload())
    }

    @Test
    fun `in memory authority returns same result and separates phases`() = runBlocking {
        val repository = InMemoryDecisionRepository(FIXED_CLOCK)
        val submission = completeSubmission().copy(entryIntent = null, tradePlan = null, action = DecisionAction.NO_TRADE)
        val proposer = DecisionSubmissionAuthority(INVOCATION_ID, LlmInvocationPhase.PROPOSER)
        val riskReduction = DecisionSubmissionAuthority(INVOCATION_ID, LlmInvocationPhase.RISK_REDUCTION_ONLY)

        val first = repository.submitDecision(proposer, submission).getOrThrow()
        val retry = repository.submitDecision(proposer, submission).getOrThrow()
        val otherPhase = repository.submitDecision(riskReduction, submission).getOrThrow()

        assertEquals(first, retry)
        assertNotEquals(first.decision.decisionId, otherPhase.decision.decisionId)
        assertEquals(2, repository.snapshots.decisions().size)
    }

    @Test
    fun `in memory authority fails closed for changed and incomplete payload`() = runBlocking {
        val repository = InMemoryDecisionRepository(FIXED_CLOCK)
        val submission = completeSubmission().copy(entryIntent = null, tradePlan = null, action = DecisionAction.NO_TRADE)
        val completed = DecisionSubmissionAuthority(INVOCATION_ID, LlmInvocationPhase.PROPOSER)
        val incomplete = DecisionSubmissionAuthority(INVOCATION_ID, LlmInvocationPhase.RISK_REDUCTION_ONLY)
        repository.submitDecision(completed, submission).getOrThrow()
        repository.seedIncompleteDecisionSubmissionAuthority(incomplete, submission)

        assertFailsWith<DecisionSubmissionConflictException> {
            repository.submitDecision(completed, submission.copy(reasonJa = "changed")).getOrThrow()
        }
        assertFailsWith<DecisionSubmissionUnknownException> {
            repository.submitDecision(incomplete, submission).getOrThrow()
        }
        assertEquals(1, repository.snapshots.decisions().size)
    }

    @Test
    fun `in memory concurrent submissions have one winner`() = runBlocking {
        val repository = InMemoryDecisionRepository(FIXED_CLOCK)
        val authority = DecisionSubmissionAuthority(INVOCATION_ID, LlmInvocationPhase.PROPOSER)
        val submission = completeSubmission().copy(entryIntent = null, tradePlan = null, action = DecisionAction.NO_TRADE)

        val results = coroutineScope {
            List(16) { async { repository.submitDecision(authority, submission).getOrThrow() } }.awaitAll()
        }

        assertEquals(1, results.map { result -> result.decision.decisionId }.distinct().size)
        assertEquals(1, repository.snapshots.decisions().size)
    }
}

private fun completeSubmission(): DecisionSubmission = DecisionSubmission(
    invocationId = INVOCATION_ID,
    llmProvider = "provider",
    promptHash = "prompt",
    systemPromptVersion = "system-v1",
    marketSnapshotId = "snapshot",
    action = DecisionAction.ENTER,
    closeRatio = null,
    setupTags = listOf("breakout", "trend"),
    estimatedWinProbability = BigDecimal("0.6"),
    expectedRMultiple = BigDecimal("2"),
    roundTripCostR = BigDecimal("0.05"),
    toolEvidenceIds = listOf("tool-1", "tool-2"),
    factCheckJson = """{"a":1,"nested":{"a":1,"b":2}}""",
    selfReviewJson = """{"risk":"ok","z":2}""",
    reasonJa = "テスト判断",
    missingDataJa = listOf("funding"),
    noTradeConditionsJa = listOf("volume-low"),
    entryIntent = EntryIntentDraft(
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.MARKET,
        sizeBtc = BigDecimal("0.01"),
        priceJpy = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("10500000"),
    ),
    tradePlan = TradePlanDraft(
        parentTradePlanId = null,
        revisionCount = 0,
        symbol = TradingSymbol.BTC,
        thesisJa = "上昇継続",
        invalidationConditionsJa = listOf("直近安値割れ"),
        targetPriceJpy = BigDecimal("10500000"),
        timeStopAt = Instant.parse("2026-07-17T01:00:00Z"),
        setupTags = listOf("breakout"),
        invalidationPredicates = listOf(
            TradePlanInvalidationPredicate(
                type = TradePlanInvalidationType.LAST_PRICE_AT_OR_BELOW,
                decimalThresholdJpy = BigDecimal("9700000"),
            ),
            TradePlanInvalidationPredicate(
                type = TradePlanInvalidationType.TIME_AT_OR_AFTER,
                instantThreshold = Instant.parse("2026-07-17T01:00:00Z"),
            ),
        ),
    ),
)

private const val INVOCATION_ID = "decision-authority-run"
private val FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC)

private val DECISION_SUBMISSION_BUSINESS_FIELDS = setOf(
    "action",
    "closeRatio",
    "setupTags",
    "estimatedWinProbability",
    "expectedRMultiple",
    "roundTripCostR",
    "toolEvidenceIds",
    "factCheckJson",
    "selfReviewJson",
    "reasonJa",
    "missingDataJa",
    "noTradeConditionsJa",
    "entryIntent",
    "tradePlan",
)

private val DECISION_SUBMISSION_EXCLUDED_METADATA_FIELDS = setOf(
    "invocationId",
    "llmProvider",
    "promptHash",
    "systemPromptVersion",
    "marketSnapshotId",
)
