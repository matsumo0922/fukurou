package me.matsumo.fukurou.trading.audit

import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmProvider
import java.time.Instant

/** LLM invocation family の用途。 */
enum class LlmAuditRootKind { DECISION_ATTEMPT, REFLECTION, EVALUATION_REPORT }

/** run/material 参照が存在しない理由。 */
enum class LlmManifestNotApplicableReason { PRE_FILTER_BEFORE_FULL_RUN, NON_DECISION_INVOCATION }

/** 起動後に観測する provider identity の coverage。 */
enum class LlmIdentityCoverageStatus {
    OBSERVED,
    NOT_REPORTED_BY_PROVIDER,
    NOT_OBSERVABLE_BEFORE_START,
    INFRASTRUCTURE_ATTRIBUTION_INCOMPLETE,
}

/** immutable invocation family root。 */
data class LlmInvocationAuditRoot(
    val rootId: String,
    val kind: LlmAuditRootKind,
    val capturedAt: Instant,
)

/** scheduler と runner が同じ instance で共有する typed trigger。 */
data class LlmRunTriggerSnapshot(
    val kind: String,
    val observedAt: Instant,
    val measurements: List<LlmTriggerMeasurement>,
    val entities: List<LlmTriggerEntity>,
    val notApplicableReason: String?,
)

/** comparator を満たす側を正とする trigger measurement。 */
data class LlmTriggerMeasurement(
    val metric: String,
    val measuredValue: String,
    val comparator: String,
    val threshold: String,
    val signedMargin: String,
    val unit: String,
)

/** trigger entity の typed reference。 */
data class LlmTriggerEntity(val type: String, val id: String)

/** full decision run の immutable input manifest。 */
data class LlmRunInputManifest(
    val invocationId: String,
    val rootId: String,
    val trigger: LlmRunTriggerSnapshot,
    val runtimeConfigVersion: String?,
    val runtimeConfigHash: String?,
    val runtimeConfigSnapshot: String,
    val materialInvocationId: String,
    val materialContentHash: String,
    val schemaVersion: Int,
    val capturedAt: Instant,
    val canonicalContentHash: String,
)

/** CLI 起動直前に固定する effective invocation。 */
data class LlmPhaseInputManifest(
    val phaseManifestId: String,
    val rootId: String,
    val invocationId: String,
    val phase: LlmInvocationPhase,
    val prompt: String,
    val role: String,
    val provider: LlmProvider,
    val configuredModel: String?,
    val configuredEffort: LlmEffort,
    val renderedEffort: String?,
    val cliVersion: String,
    val toolAllowlist: List<String>,
    val canonicalToolSchema: String,
    val runtimeConfigHash: String?,
    val runtimeConfigSnapshot: String,
    val runManifestInvocationId: String?,
    val runManifestContentHash: String?,
    val materialInvocationId: String?,
    val materialContentHash: String?,
    val notApplicableReason: LlmManifestNotApplicableReason?,
    val capturedAt: Instant,
    val effectiveInvocationHash: String,
)

/** phase 完了時に append する observed identity。 */
data class LlmPhaseObservation(
    val phaseManifestId: String,
    val observedModels: List<String>,
    val observedEffort: String?,
    val modelCoverageStatus: LlmIdentityCoverageStatus,
    val effortCoverageStatus: LlmIdentityCoverageStatus,
    val terminatedAt: Instant,
)
