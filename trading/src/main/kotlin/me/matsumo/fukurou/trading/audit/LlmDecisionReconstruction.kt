package me.matsumo.fukurou.trading.audit

import java.time.Instant
import java.util.UUID

/** 保存済み decision audit graph の再構成結果。 */
data class LlmDecisionReconstruction(
    val decisionId: UUID,
    val invocationId: String?,
    val classification: LlmDecisionReconstructionClassification,
    val reason: LlmDecisionReconstructionReason?,
    val phaseCount: Int,
    val evidenceCount: Int,
)

/** decision audit graph の完全性分類。 */
enum class LlmDecisionReconstructionClassification {
    COMPLETE,
    INCOMPLETE,
    PENDING,
    LEGACY_PRE_EVIDENCE,
    HASH_MISMATCH,
}

/** decision audit graph を完全と判定しない安定理由。 */
enum class LlmDecisionReconstructionReason {
    RUN_IN_PROGRESS,
    PRE_EVIDENCE,
    RUN_LIFECYCLE_INCOMPLETE,
    GRAPH_MISSING,
    BOUND_EXCEEDED,
    ASSOCIATION_MISMATCH,
    CONTENT_HASH_MISMATCH,
    REFERENCE_HASH_MISMATCH,
    MALFORMED_CANONICAL_VALUE,
}

/** 指定期間の terminal evidence 構造 coverage。 */
data class LlmDecisionEvidenceCoverageSummary(
    val from: Instant,
    val toExclusive: Instant,
    val decisionCount: Long,
    val terminalDecisionCount: Long,
    val structurallyCompleteDecisionCount: Long,
    val structurallyIncompleteDecisionCount: Long,
    val pendingDecisionCount: Long,
    val incompleteRunDecisionCount: Long,
    val legacyTerminalDecisionCount: Long,
    val terminalNoDecisionRunCount: Long,
)

/** 1 transaction で削除した audit root の件数。 */
data class LlmAuditPruneBatchResult(
    val deletedRootCount: Int,
    val hasMore: Boolean,
)

/** inactive audit maintenance の内部 repository contract。 */
interface LlmDecisionReconstructionRepository {
    suspend fun findDecision(decisionId: UUID): Result<LlmDecisionReconstruction?>

    suspend fun summarizeCoverage(from: Instant, toExclusive: Instant): Result<LlmDecisionEvidenceCoverageSummary>

    suspend fun pruneExpiredAuditRoots(now: Instant): Result<LlmAuditPruneBatchResult>
}

/** reconstruction が読む decision-capable phase 上限。 */
const val MAX_RECONSTRUCTION_PHASE_COUNT = 3

/** reconstruction が1 phaseから読む evidence 上限。 */
const val MAX_RECONSTRUCTION_EVIDENCE_PER_PHASE = MAX_TERMINAL_TOOL_EVIDENCE_COUNT

/** reconstruction が1 decisionから読む evidence 合計上限。 */
const val MAX_RECONSTRUCTION_EVIDENCE_COUNT =
    MAX_RECONSTRUCTION_PHASE_COUNT * MAX_RECONSTRUCTION_EVIDENCE_PER_PHASE

/** prune が1 transactionで削除する audit root 上限。 */
const val LLM_AUDIT_PRUNE_BATCH_SIZE = 500
