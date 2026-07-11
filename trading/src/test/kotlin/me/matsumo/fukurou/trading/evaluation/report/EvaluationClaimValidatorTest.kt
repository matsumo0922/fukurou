package me.matsumo.fukurou.trading.evaluation.report

import kotlin.test.Test
import kotlin.test.assertEquals

/** EvaluationClaimValidator の fail-closed contract を検証する。 */
class EvaluationClaimValidatorTest {

    @Test
    fun validate_reportsConflictWithoutRewritingClaim() {
        val claim = EvaluationReportClaim(
            claimId = "claim-1",
            type = "FACT_COMPARISON",
            factIds = listOf("bot", "benchmark"),
            asserted = "GT",
        )
        val result = EvaluationClaimValidator.validate(
            claims = listOf(claim),
            facts = listOf(
                EvaluationReportFact("bot", "0.03", "RATIO", "AVAILABLE", listOf("snapshot")),
                EvaluationReportFact("benchmark", "0.08", "RATIO", "AVAILABLE", listOf("snapshot")),
            ),
        ).single()

        assertEquals(EvaluationClaimStatus.CONFLICT, result.status)
        assertEquals("GT", result.asserted)
        assertEquals("LT", result.actual)
        assertEquals("COMPARISON_MISMATCH", result.code)
    }

    @Test
    fun validate_keepsMissingFactDistinctFromZero() {
        val result = EvaluationClaimValidator.validate(
            claims = listOf(EvaluationReportClaim("claim-1", "FACT_VALUE", listOf("missing"), "0")),
            facts = emptyList(),
        ).single()

        assertEquals(EvaluationClaimStatus.FACT_MISSING, result.status)
        assertEquals(null, result.actual)
    }
}
