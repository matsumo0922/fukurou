@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

/** untrusted LLM report artifact の structural bounds を検証する。 */
class EvaluationReportArtifactValidationTest {

    @Test
    fun canonicalIntegrityHash_coversEveryIntegrityCountAndEffortUsesSingleConfiguredValue() {
        val base = EvaluationIntegrityResponse(1, 2, 3, 4, 5, mapOf("reason" to 6), 7, 8, 9, "0.1", false)
        val variants = listOf(
            base.copy(eligibleTradeCount = 11), base.copy(missingRCount = 12), base.copy(excludedOrderCount = 13),
            base.copy(excludedPositionCount = 14), base.copy(excludedDecisionRunCount = 15),
            base.copy(llmPhaseCount = 17), base.copy(missingUsagePhaseCount = 18), base.copy(unpricedPhaseCount = 19),
        )
        variants.forEach { variant -> assertNotEquals(canonicalIntegrityHash(base), canonicalIntegrityHash(variant)) }
        assertEquals(me.matsumo.fukurou.trading.invoker.LlmEffort.HIGH, evaluationReportEffort(mapOf("FUKUROU_CLAUDE_EFFORT" to "HIGH")))
    }

    @Test
    fun validator_rejectsDuplicateIdsUnknownKindsAndDanglingClaimReferences() {
        val validClaim = EvaluationReportClaimResponse("claim-1", "FACT_VALUE", listOf("unknown-fact"), "1")
        val cases = listOf(
            GeneratedEvaluationArtifact(
                segments = listOf(
                    EvaluationReportSegmentResponse("segment-1", "SUMMARY", "text", listOf("claim-1")),
                    EvaluationReportSegmentResponse("segment-1", "SUMMARY", "text", listOf("claim-1")),
                ),
                claims = listOf(validClaim),
            ),
            GeneratedEvaluationArtifact(
                segments = listOf(EvaluationReportSegmentResponse("segment-1", "PREDICTION", "text", listOf("claim-1"))),
                claims = listOf(validClaim),
            ),
            GeneratedEvaluationArtifact(
                segments = listOf(EvaluationReportSegmentResponse("segment-1", "SUMMARY", "text", listOf("missing"))),
                claims = listOf(validClaim),
            ),
            GeneratedEvaluationArtifact(
                segments = listOf(EvaluationReportSegmentResponse("segment-1", "SUMMARY", "text", listOf("claim-1"))),
                claims = listOf(validClaim, validClaim),
            ),
            GeneratedEvaluationArtifact(
                segments = listOf(EvaluationReportSegmentResponse("segment-1", "SUMMARY", "profit is 42", emptyList())),
                claims = emptyList(),
            ),
        )

        cases.forEach { artifact ->
            assertFailsWith<IllegalArgumentException> { validateGeneratedArtifact(artifact) }
        }
    }

    @Test
    fun validator_keepsUnknownFactForClaimLevelFactMissingResult() {
        validateGeneratedArtifact(
            GeneratedEvaluationArtifact(
                segments = listOf(EvaluationReportSegmentResponse("segment-1", "SUMMARY", "text", listOf("claim-1"))),
                claims = listOf(EvaluationReportClaimResponse("claim-1", "FACT_VALUE", listOf("unknown-fact"), "1")),
            ),
        )
    }

    @Test
    fun validator_acceptsEveryVersionedKindAndTypeAndEnforcesTextBounds() {
        val kinds = listOf("SUMMARY", "PERFORMANCE", "CALIBRATION", "RISK", "COST", "COVERAGE", "LIMITATION")
        val types = listOf("FACT_VALUE", "FACT_DIRECTION", "FACT_COMPARISON", "FACT_DELTA", "FACT_COVERAGE")
        val artifact = GeneratedEvaluationArtifact(
            segments = kinds.mapIndexed { index, kind -> EvaluationReportSegmentResponse("segment-$index", kind, "text", listOf("claim-${index % types.size}")) },
            claims = types.mapIndexed { index, type -> EvaluationReportClaimResponse("claim-$index", type, listOf("fact-$index"), "value") },
        )
        validateGeneratedArtifact(artifact)
        assertEquals(kinds.size, artifact.segments.size)
        assertFailsWith<IllegalArgumentException> {
            validateGeneratedArtifact(artifact.copy(segments = listOf(EvaluationReportSegmentResponse("long", "SUMMARY", "x".repeat(1_201), emptyList()))))
        }
        assertFailsWith<IllegalArgumentException> {
            validateGeneratedArtifact(
                artifact.copy(segments = List(11) { index -> EvaluationReportSegmentResponse("large-$index", "SUMMARY", "x".repeat(1_100), emptyList()) }),
            )
        }
    }
}
