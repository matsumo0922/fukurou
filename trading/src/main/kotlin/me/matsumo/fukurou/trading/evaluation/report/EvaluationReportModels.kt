package me.matsumo.fukurou.trading.evaluation.report

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Evaluation report の状態。 */
enum class EvaluationReportStatus {
    REQUESTED,
    SNAPSHOTTING,
    GENERATING,
    VALIDATING,
    SUCCEEDED,
    FAILED,
}

/** Evaluation report の claim 検証状態。 */
enum class EvaluationClaimStatus {
    VERIFIED,
    CONFLICT,
    INSUFFICIENT_EVIDENCE,
    FACT_MISSING,
    NOT_VERIFIABLE,
}

/** report の immutable period。 */
data class EvaluationReportPeriod(
    val from: LocalDate,
    val toInclusive: LocalDate,
    val timezone: String = "Asia/Tokyo",
)

/** snapshot 内の deterministic fact。 */
data class EvaluationReportFact(
    val factId: String,
    val value: String?,
    val unit: String?,
    val availability: String,
    val sourceIds: List<String>,
)

/** report claim。 */
data class EvaluationReportClaim(
    val claimId: String,
    val type: String,
    val factIds: List<String>,
    val asserted: String,
)

/** report segment。 */
data class EvaluationReportSegment(
    val segmentId: String,
    val kind: String,
    val text: String,
    val claimIds: List<String>,
)

/** claim の deterministic validation result。 */
data class EvaluationClaimValidation(
    val claimId: String,
    val status: EvaluationClaimStatus,
    val asserted: String,
    val actual: String?,
    val factIds: List<String>,
    val code: String,
)

/** immutable facts snapshot metadata。 */
data class EvaluationReportSnapshot(
    val snapshotId: UUID,
    val inputHash: String,
    val inputAsOf: Instant,
    val facts: List<EvaluationReportFact>,
)

/** immutable report revision。 */
data class EvaluationReportRevision(
    val revisionId: UUID,
    val revisionNumber: Long,
    val scopeKey: String,
    val period: EvaluationReportPeriod,
    val status: EvaluationReportStatus,
    val generatedAt: Instant,
    val provider: String,
    val model: String,
    val knownCostUsd: BigDecimal?,
    val snapshot: EvaluationReportSnapshot,
    val title: String,
    val segments: List<EvaluationReportSegment>,
    val claims: List<EvaluationReportClaim>,
    val validation: List<EvaluationClaimValidation>,
)
