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

    /**
     * 週次 reflection から prompt candidate を生成する phase。
     */
    REFLECTION,
}

/**
 * MCP stdio server を CLI に登録するための設定。
 *
 * @param name MCP server 名
 * @param command MCP server 起動 command
 * @param args MCP server 起動引数
 * @param environment MCP server 子プロセスへ明示的に渡す環境変数
 * @param autoApprovedTools codex の MCP 承認ゲートを approve で通す tool 名。
 * write tool を phase 単位で最小限指定する
 */
data class LlmMcpServerConfig(
    val name: String,
    val command: String,
    val args: List<String>,
    val environment: Map<String, String>,
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
 * @param allowedTools CLI に許可する MCP tool 名
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
    val allowedTools: List<String>,
)

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
 */
data class RenderedLlmCommand(
    val executable: String,
    val args: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
    val timeout: Duration,
    val stdin: String?,
    val cleanupPaths: List<Path> = emptyList(),
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
)

/**
 * LLM 起動境界の戻り値。
 *
 * @param request 元の起動要求
 * @param responseText provider output から抽出した最終応答本文
 * @param usage provider output から抽出した structured usage
 * @param processResult process 実行結果
 * @param cleanupFailure process output の解析後に一時 artifact を削除できなかった failure
 */
data class LlmInvocationResult(
    val request: LlmInvocationRequest,
    val processResult: ProcessRunResult,
    val responseText: String,
    val usage: LlmUsageDetails? = null,
    val cleanupFailure: Throwable? = null,
)
