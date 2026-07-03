package me.matsumo.fukurou.trading.invoker

import me.matsumo.fukurou.trading.audit.DecisionRunContext
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
     * 取引判断を提出する Proposer phase。
     */
    PROPOSER,

    /**
     * entry intent を反証する Falsifier phase。
     */
    FALSIFIER,
}

/**
 * MCP stdio server を CLI に登録するための設定。
 *
 * @param name MCP server 名
 * @param command MCP server 起動 command
 * @param args MCP server 起動引数
 * @param environment MCP server 子プロセスへ明示的に渡す環境変数
 */
data class LlmMcpServerConfig(
    val name: String,
    val command: String,
    val args: List<String>,
    val environment: Map<String, String>,
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
 * @param mcpServer stdio MCP server 設定
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
    val mcpServer: LlmMcpServerConfig,
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
 */
data class RenderedLlmCommand(
    val executable: String,
    val args: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
    val timeout: Duration,
    val stdin: String?,
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
 * @param processResult process 実行結果
 */
data class LlmInvocationResult(
    val request: LlmInvocationRequest,
    val processResult: ProcessRunResult,
)
