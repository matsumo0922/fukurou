package me.matsumo.fukurou.trading.invoker

import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import java.nio.file.Path
import java.time.Duration

/**
 * LLM CLI provider。
 */
enum class LlmProvider {
    /**
     * Claude Code CLI。
     */
    CLAUDE,

    /**
     * Codex CLI。
     */
    CODEX,
}

/**
 * LLM CLI に要求する reasoning effort。
 *
 * DEFAULT は provider CLI の既定を使うため、renderer は effort 指定を出力しない。
 */
enum class LlmEffort {
    DEFAULT,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
}

/**
 * one-shot runner 内の LLM 起動 phase。
 */
enum class LlmInvocationPhase {
    /**
     * heartbeat 系 trigger の full run 要否を判定する軽量 pre-filter phase。
     */
    PRE_FILTER,

    /**
     * 取引判断を提出する Proposer phase。
     */
    PROPOSER,

    /**
     * entry intent を反証する Falsifier phase。
     */
    FALSIFIER,

    /** standard context 不成立時に exposure 減少だけを許可する phase。 */
    RISK_REDUCTION_ONLY,

    /**
     * 週次 reflection から prompt candidate を生成する phase。
     */
    REFLECTION,

    /** immutable evaluation snapshot を説明する report generation phase。 */
    EVALUATION_REPORT,
}

/**
 * phase が要求・有効化する tool の固定 policy。
 *
 * @param requiredTools phase 完了に必要な fully-qualified tool 名
 * @param enabledTools CLI に公開する fully-qualified tool 名
 */
data class ToolPolicy(
    val requiredTools: Set<String>,
    val enabledTools: List<String>,
) {
    init {
        require(enabledTools.size == enabledTools.distinct().size) { "Tool policy contains duplicate enabled tools." }
        require(requiredTools.all(enabledTools::contains)) { "Tool policy is missing a required tool." }
    }
}

/**
 * MCP stdio server を CLI に登録するための設定。
 *
 * @param name MCP server 名
 * @param command MCP server 起動 command
 * @param args MCP server 起動引数
 * @param manifestId root-owned launcher が検証する per-run manifest ID
 * @param manifestPath runner が cleanup する manifest path
 * @param terminalEvidenceCaptureEnabled immutable manifest が宣言した capture state
 * @param autoApprovedTools codex の MCP 承認ゲートを approve で通す tool 名。
 * write tool を phase 単位で最小限指定する
 */
data class LlmMcpServerConfig(
    val name: String,
    val command: String,
    val manifestId: String,
    val manifestPath: Path,
    val terminalEvidenceCaptureEnabled: Boolean = false,
    val autoApprovedTools: List<String> = emptyList(),
)

/**
 * LLM CLI へ渡す起動要求。
 *
 * @param invocationId runner 起動 ID
 * @param provider LLM provider
 * @param phase runner phase
 * @param prompt CLI に渡す prompt
 * @param timeout CLI 起動 timeout
 * @param workingDirectory CLI process の working directory
 * @param decisionRunContext MCP audit へ伝播する context
 * @param mcpServer stdio MCP server 設定。null の場合は MCP を登録しない
 * @param environment CLI process へ allowlist で渡す環境変数
 * @param toolPolicy phase が要求・有効化する tool policy
 * @param model この invocation に設定した model。null の場合は CLI の既定値を使う
 * @param effort この invocation に設定した reasoning effort
 * @param useConfiguredModelFallback renderer config の provider model override を使うか。role assignment は false にする
 */
data class LlmInvocationRequest(
    val invocationId: String,
    val provider: LlmProvider,
    val phase: LlmInvocationPhase,
    val prompt: String,
    val timeout: Duration,
    val workingDirectory: Path,
    val decisionRunContext: DecisionRunContext,
    val mcpServer: LlmMcpServerConfig?,
    val environment: Map<String, String>,
    val toolPolicy: ToolPolicy,
    val model: String? = null,
    val effort: LlmEffort = LlmEffort.DEFAULT,
    val useConfiguredModelFallback: Boolean = true,
) {
    /** 既存 manifest / MCP surface が参照する有効 tool 一覧。 */
    val allowedTools: List<String> get() = toolPolicy.enabledTools
}

/** configured model の由来。 */
enum class LlmConfiguredModelSource {
    REQUEST,
    RENDERER_CONFIG,
    CLI_DEFAULT,
}

/**
 * invocation に設定された model identity。
 *
 * @param name model 名。CLI_DEFAULT の場合は null
 * @param source model 名の由来
 */
data class LlmConfiguredModelIdentity(
    val name: String?,
    val source: LlmConfiguredModelSource,
) {
    init {
        require((source == LlmConfiguredModelSource.CLI_DEFAULT) == (name == null)) {
            "CLI default model identity must not invent a model name."
        }
    }

    companion object {
        /** CLI が既定 model を選択する identity。 */
        val CLI_DEFAULT = LlmConfiguredModelIdentity(null, LlmConfiguredModelSource.CLI_DEFAULT)
    }
}

/** provider output が報告した model identity。 */
data class LlmObservedModelIdentity(
    val name: String,
    val source: String,
)

/** provider-neutral failure category。 */
enum class LlmProviderFailureCategory {
    AUTHENTICATION,
    RATE_OR_SESSION_LIMIT,
    QUOTA_EXHAUSTED,
    OUTPUT_CONTRACT,
    PROCESS_TIMEOUT,
    PROCESS_EXIT,
    CLEANUP,
    UNKNOWN_PROVIDER_FAILURE,
}

/**
 * raw provider output を含まない typed failure。
 *
 * @param category provider-neutral category
 * @param providerCode allowlist 済み provider code
 * @param adapterSchemaVersion failure を解釈した adapter schema version
 */
data class LlmProviderFailure(
    val category: LlmProviderFailureCategory,
    val providerCode: String? = null,
    val adapterSchemaVersion: String,
)

/** process start 前に検出した typed provider contract failure。 */
class LlmProviderContractException(
    val providerFailure: LlmProviderFailure,
) : IllegalStateException("LLM provider contract rejected the invocation.")

/** provider-neutral invocation contract revision。 */
const val LLM_INVOCATION_CONTRACT_VERSION = "llm-invocation-contract-v1"

/**
 * renderer が生成した実行 command。
 *
 * @param executable 実行ファイル名
 * @param args executable へ渡す引数
 * @param environment CLI process へ渡す環境変数
 * @param workingDirectory CLI process の working directory
 * @param timeout process timeout
 * @param stdin 標準入力へ渡す文字列
 * @param cleanupPaths provider output の解析後に削除する runner 生成ファイル
 * @param configuredModelIdentity renderer が確定した configured model identity
 */
data class RenderedLlmCommand(
    val executable: String,
    val args: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
    val timeout: Duration,
    val stdin: String?,
    val cleanupPaths: List<Path> = emptyList(),
    val configuredModelIdentity: LlmConfiguredModelIdentity = LlmConfiguredModelIdentity.CLI_DEFAULT,
)

/**
 * process 終了状態。
 */
enum class ProcessRunStatus {
    /**
     * process が通常終了した。
     */
    EXITED,

    /**
     * timeout により process を終了した。
     */
    TIMED_OUT,
}

/** supervisor acknowledgement を含め、provider / MCP tree の終了を証明できたかを表す。 */
enum class ProcessTreeTerminationProof {
    /** supervisor acknowledgement または bounded TERM/KILL/wait により全 process の終了を確認した。 */
    PROVEN_EXITED,

    /** late fork、列挙、signal、wait のいずれかに不確実性が残る。 */
    UNCERTAIN,
}

/** app-owned submission gateway が観測した semantic submission の phase-local state。 */
enum class LlmSemanticSubmissionState {
    /** repository submission は開始されていない。 */
    NOT_ATTEMPTED,

    /** repository submission が進行中で、commit 成否をまだ確定できない。 */
    IN_FLIGHT,

    /** repository submission が正常に完了した。 */
    COMMITTED,

    /** request または repository submission が確定的に拒否された。 */
    REJECTED,
}

/**
 * ProcessRunner が返す process 実行結果。
 *
 * @param status process 終了状態
 * @param exitCode process exit code。timeout 時は null
 * @param stdout 標準出力
 * @param stderr 標準エラー
 */
data class ProcessRunResult(
    val status: ProcessRunStatus,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val processTreeTerminationProof: ProcessTreeTerminationProof = ProcessTreeTerminationProof.PROVEN_EXITED,
)

/**
 * LLM 起動境界の戻り値。
 *
 * @param request 元の起動要求
 * @param responseText provider output から抽出した最終応答本文
 * @param usage provider output から抽出した structured usage
 * @param processResult process 実行結果
 * @param cleanupFailure process output の解析後に一時 artifact を削除できなかった failure
 * @param configuredModelIdentity renderer が確定した configured model identity
 * @param observedModelIdentity provider output が報告した model identity
 * @param providerFailure provider adapter が検出した typed failure
 */
data class LlmInvocationResult(
    val request: LlmInvocationRequest,
    val processResult: ProcessRunResult,
    val responseText: String,
    val usage: LlmUsageDetails? = null,
    val cleanupFailure: Throwable? = null,
    val configuredModelIdentity: LlmConfiguredModelIdentity = LlmConfiguredModelIdentity.CLI_DEFAULT,
    val observedModelIdentity: LlmObservedModelIdentity? = null,
    val providerFailure: LlmProviderFailure? = null,
)
