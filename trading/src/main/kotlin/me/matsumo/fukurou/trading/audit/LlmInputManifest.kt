package me.matsumo.fukurou.trading.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

/** terminal tool evidence bundle の capture 状態。 */
enum class TerminalToolEvidenceBundleStatus { DISABLED, COMPLETE, INCOMPLETE }

/** terminal tool evidence bundle が不完全になった安定理由。 */
enum class TerminalToolEvidenceIncompleteReason {
    UNSUPPORTED_RESPONSE_SHAPE,
    CANONICALIZATION_FAILED,
    SECRET_DETECTED,
    COUNT_LIMIT,
    BYTE_LIMIT,
    FRAME_LIMIT,
}

/** tool response が宣言する source timestamp の状態。 */
enum class ToolEvidenceSourceTimestampStatus { PRESENT, MISSING, INVALID }

/** finalized MCP tool response の versioned canonical projection。 */
data class TerminalToolEvidence(
    val version: Int = TERMINAL_TOOL_EVIDENCE_VERSION,
    val ordinal: Int,
    val toolName: String,
    val responseJson: String,
    val responseHash: String,
    val sourceTimestamp: Instant?,
    val sourceTimestampStatus: ToolEvidenceSourceTimestampStatus,
    val isError: Boolean,
)

/** 既存 terminal frame に同梱する bounded evidence bundle。 */
data class TerminalToolEvidenceBundle(
    val version: Int = TERMINAL_TOOL_EVIDENCE_BUNDLE_VERSION,
    val status: TerminalToolEvidenceBundleStatus,
    val incompleteReason: TerminalToolEvidenceIncompleteReason?,
    val entries: List<TerminalToolEvidence>,
) {
    companion object {
        /** capture が無効な current production bundle。 */
        fun disabled(): TerminalToolEvidenceBundle = TerminalToolEvidenceBundle(
            status = TerminalToolEvidenceBundleStatus.DISABLED,
            incompleteReason = null,
            entries = emptyList(),
        )

        /** frame 上限時に entries を除いて送る小さい typed bundle。 */
        fun frameLimit(): TerminalToolEvidenceBundle = TerminalToolEvidenceBundle(
            status = TerminalToolEvidenceBundleStatus.INCOMPLETE,
            incompleteReason = TerminalToolEvidenceIncompleteReason.FRAME_LIMIT,
            entries = emptyList(),
        )
    }
}

/** app gateway binding を付与した repository 用 terminal evidence bundle。 */
data class TrustedTerminalToolEvidenceBundle(
    val invocationId: String,
    val phaseManifestId: String,
    val phase: LlmInvocationPhase,
    val captureEnabled: Boolean,
    val bundle: TerminalToolEvidenceBundle,
)

/** canonical responseから再導出したsource timestampと状態。 */
data class TerminalEvidenceSourceTimestamp(
    val value: Instant?,
    val status: ToolEvidenceSourceTimestampStatus,
)

/** response projectionの`freshness.sourceTimestamp`を例外なくtyped状態へ変換する。 */
fun JsonElement.terminalEvidenceSourceTimestamp(): TerminalEvidenceSourceTimestamp {
    val timestampElement = (this as? JsonObject)
        ?.get("freshness")
        ?.let { freshness -> (freshness as? JsonObject)?.get("sourceTimestamp") }
        ?: return TerminalEvidenceSourceTimestamp(null, ToolEvidenceSourceTimestampStatus.MISSING)
    val timestampText = (timestampElement as? JsonPrimitive)?.contentOrNull
        ?: return TerminalEvidenceSourceTimestamp(null, ToolEvidenceSourceTimestampStatus.INVALID)
    val timestamp = runCatching { Instant.parse(timestampText) }.getOrNull()

    return if (timestamp == null) {
        TerminalEvidenceSourceTimestamp(null, ToolEvidenceSourceTimestampStatus.INVALID)
    } else {
        TerminalEvidenceSourceTimestamp(timestamp, ToolEvidenceSourceTimestampStatus.PRESENT)
    }
}

/** tool evidence response JSON を key-order 非依存の文字列へ正規化する。 */
fun JsonElement.toTerminalEvidenceCanonicalString(): String = when (this) {
    is JsonObject -> entries.sortedBy { entry -> entry.key }.joinToString(prefix = "{", postfix = "}") { entry ->
        "${JsonPrimitive(entry.key)}:${entry.value.toTerminalEvidenceCanonicalString()}"
    }
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { element ->
        element.toTerminalEvidenceCanonicalString()
    }
    is JsonPrimitive -> toString()
    JsonNull -> "null"
}

/** canonical tool evidence projection version。 */
const val TERMINAL_TOOL_EVIDENCE_VERSION = 1

/** terminal evidence bundle version。 */
const val TERMINAL_TOOL_EVIDENCE_BUNDLE_VERSION = 1

/** 1 phaseで収集するtool response上限。 */
const val MAX_TERMINAL_TOOL_EVIDENCE_COUNT = 48

/** terminal frameへ同梱する前のbundle byte上限。 */
const val MAX_TERMINAL_TOOL_EVIDENCE_BUNDLE_BYTES = 96 * 1024

/** response以外のversioned entry metadataへ予約する保守的byte数。 */
const val TERMINAL_TOOL_EVIDENCE_ENTRY_OVERHEAD_BYTES = 512
