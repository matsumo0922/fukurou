package me.matsumo.fukurou

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** untrusted LLM report artifact の structural bounds を検証する。 */
class EvaluationReportArtifactValidationTest {

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
}
