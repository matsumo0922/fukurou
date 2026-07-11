package me.matsumo.fukurou.trading.evaluation.report

import java.math.BigDecimal

/** typed claim を immutable facts と照合し、prose を変更せず結果だけを返す。 */
object EvaluationClaimValidator {

    /** claim 一覧を検証する。 */
    fun validate(
        claims: List<EvaluationReportClaim>,
        facts: List<EvaluationReportFact>,
    ): List<EvaluationClaimValidation> {
        val factsById = facts.associateBy { fact -> fact.factId }

        return claims.map { claim -> validateClaim(claim, factsById) }
    }

    private fun validateClaim(
        claim: EvaluationReportClaim,
        factsById: Map<String, EvaluationReportFact>,
    ): EvaluationClaimValidation {
        val referenced = claim.factIds.mapNotNull { factId -> factsById[factId] }
        if (referenced.size != claim.factIds.size) return claim.result(EvaluationClaimStatus.FACT_MISSING, null, "FACT_MISSING")
        if (referenced.any { fact -> fact.availability != "AVAILABLE" }) {
            return claim.result(EvaluationClaimStatus.INSUFFICIENT_EVIDENCE, null, "FACT_UNAVAILABLE")
        }

        return when (claim.type) {
            "FACT_VALUE" -> validateValue(claim, referenced.singleOrNull())
            "FACT_DIRECTION" -> validateDirection(claim, referenced.singleOrNull())
            "FACT_COMPARISON" -> validateComparison(claim, referenced)
            else -> claim.result(EvaluationClaimStatus.NOT_VERIFIABLE, null, "UNSUPPORTED_CLAIM_TYPE")
        }
    }

    private fun validateValue(claim: EvaluationReportClaim, fact: EvaluationReportFact?): EvaluationClaimValidation {
        val actual = fact?.value ?: return claim.result(EvaluationClaimStatus.FACT_MISSING, null, "FACT_MISSING")
        val status = if (actual == claim.asserted) EvaluationClaimStatus.VERIFIED else EvaluationClaimStatus.CONFLICT

        return claim.result(status, actual, if (status == EvaluationClaimStatus.VERIFIED) "MATCH" else "VALUE_MISMATCH")
    }

    private fun validateDirection(
        claim: EvaluationReportClaim,
        fact: EvaluationReportFact?,
    ): EvaluationClaimValidation {
        val actualValue = fact?.value?.toBigDecimalOrNull()
            ?: return claim.result(EvaluationClaimStatus.INSUFFICIENT_EVIDENCE, fact?.value, "NON_NUMERIC_FACT")
        val actual = when {
            actualValue > BigDecimal.ZERO -> "POSITIVE"
            actualValue < BigDecimal.ZERO -> "NEGATIVE"
            else -> "ZERO"
        }
        val status = if (actual == claim.asserted) EvaluationClaimStatus.VERIFIED else EvaluationClaimStatus.CONFLICT

        return claim.result(status, actual, if (status == EvaluationClaimStatus.VERIFIED) "MATCH" else "DIRECTION_MISMATCH")
    }

    private fun validateComparison(
        claim: EvaluationReportClaim,
        facts: List<EvaluationReportFact>,
    ): EvaluationClaimValidation {
        val values = facts.map { fact -> fact.value?.toBigDecimalOrNull() }
        if (values.size != 2 || values.any { value -> value == null }) {
            return claim.result(EvaluationClaimStatus.INSUFFICIENT_EVIDENCE, null, "NON_NUMERIC_FACT")
        }
        val comparison = values[0]!!.compareTo(values[1]!!)
        val actual = when {
            comparison < 0 -> "LT"
            comparison > 0 -> "GT"
            else -> "EQ"
        }
        val matches = when (claim.asserted) {
            "LT" -> comparison < 0
            "LTE" -> comparison <= 0
            "EQ" -> comparison == 0
            "GTE" -> comparison >= 0
            "GT" -> comparison > 0
            else -> false
        }
        val status = if (matches) EvaluationClaimStatus.VERIFIED else EvaluationClaimStatus.CONFLICT

        return claim.result(status, actual, if (matches) "MATCH" else "COMPARISON_MISMATCH")
    }

    private fun EvaluationReportClaim.result(
        status: EvaluationClaimStatus,
        actual: String?,
        code: String,
    ): EvaluationClaimValidation {
        return EvaluationClaimValidation(
            claimId = claimId,
            status = status,
            asserted = asserted,
            actual = actual,
            factIds = factIds,
            code = code,
        )
    }
}
