package me.matsumo.fukurou.trading.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import java.time.Clock
import java.time.Duration

/**
 * LLM phase の process 実行結果を共通 event 形式で command_event_log へ保存する auditor。
 *
 * @param commandEventLog audit 保存先
 * @param redactor stdout / stderr / error message を保存前に伏せる redactor
 * @param clock event 発生時刻に使う clock
 * @param toolName audit event の tool 名
 * @param humanLogger 運用ログ出力
 * @param authFailureMessage 認証失敗疑いを検出したときに出す運用ログ。null なら出さない
 */
class LlmInvocationAuditor(
    private val commandEventLog: CommandEventLog,
    private val redactor: SecretRedactor,
    private val clock: Clock,
    private val toolName: String = DEFAULT_LLM_PHASE_AUDIT_TOOL_NAME,
    private val humanLogger: (String) -> Unit = {},
    private val authFailureMessage: String? = null,
) {

    /**
     * LLM phase を起動し、完了または起動失敗を audit へ保存する。
     */
    suspend fun invokeAndAudit(
        phaseName: String,
        context: DecisionRunContext,
        request: LlmInvocationRequest,
        llmInvoker: LlmInvoker,
    ): Result<LlmPhaseAuditResult> {
        val startedAt = System.nanoTime()
        val result = llmInvoker.invoke(request)
        val duration = Duration.ofNanos(System.nanoTime() - startedAt)
        val invocationResult = result.getOrNull()
        val processResult = invocationResult?.processResult
        val startFailureError = result.exceptionOrNull()
            ?.takeIf { processResult == null }
            ?.redactedQualifiedErrorMessage()
        val usage = processResult?.let { completedProcess ->
            usageForAudit(request.provider, completedProcess.stdout)
        }
        val authFailureSuspected = processResult?.authFailureSuspected() ?: false

        appendPhase(
            context = context,
            phaseName = phaseName,
            duration = duration,
            details = buildJsonObject {
                put("provider", request.provider.name.lowercase())
                put("status", processResult?.status?.name ?: "FAILED_TO_START")
                put("exitCode", processResult?.exitCode?.toString() ?: "null")
                startFailureError?.let { error -> put("error", error) }
                processResult?.let { completedProcess ->
                    put("stdout", redactor.redactAndTruncate(completedProcess.stdout))
                    put("stderr", redactor.redactAndTruncate(completedProcess.stderr))
                }
                if (authFailureSuspected) {
                    put("authFailureSuspected", "true")
                }
                usage?.let { parsedUsage ->
                    put("usage", LlmUsageParser.toJsonObject(parsedUsage))
                }
            },
        ).getOrThrow()
        humanLogger("$phaseName completed invocation=${request.invocationId} duration=${duration.toMillis()}ms")
        if (authFailureSuspected && authFailureMessage != null) {
            humanLogger(authFailureMessage)
        }

        val processFailed = processResult?.didFail() ?: false

        if (processFailed) {
            return Result.failure(IllegalStateException("$phaseName process did not exit cleanly."))
        }

        return result.fold(
            onSuccess = { invocation ->
                Result.success(
                    LlmPhaseAuditResult(
                        invocationResult = invocation,
                        duration = duration,
                        authFailureSuspected = authFailureSuspected,
                    ),
                )
            },
            onFailure = { throwable -> Result.failure(throwable) },
        )
    }

    /**
     * LLM 以外の deterministic phase を同じ event 形式で保存する。
     */
    suspend fun appendPhase(
        context: DecisionRunContext,
        phaseName: String,
        duration: Duration,
        details: JsonObject,
    ): Result<Unit> {
        val payload = buildJsonObject {
            put("phase", phaseName)
            put("durationMillis", duration.toMillis())
            put("details", details)
        }.toString()

        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = context,
                toolName = toolName,
                toolCallId = null,
                clientRequestId = context.decisionRunId,
                eventType = CommandEventType.RUNNER_PHASE_COMPLETED,
                payload = payload,
                occurredAt = clock.instant(),
            ),
        )
    }

    private fun Throwable.redactedQualifiedErrorMessage(): String {
        val detail = message.orEmpty()
        val auditMessage = "${javaClass.name}: $detail"

        return redactor.redactAndTruncate(auditMessage)
    }

    private fun usageForAudit(provider: LlmProvider, stdout: String) = when (provider) {
        LlmProvider.CLAUDE -> LlmUsageParser.parseClaudeStdout(stdout)
        LlmProvider.CODEX -> null
    }

    private fun ProcessRunResult.authFailureSuspected(): Boolean {
        val exitFailed = exitCode?.let { completedExitCode -> completedExitCode != 0 } ?: false
        val combinedOutput = "$stdout\n$stderr"
        val outputContainsAuthFailure = LLM_CLI_AUTH_FAILURE_PATTERNS.any { pattern ->
            combinedOutput.contains(pattern, ignoreCase = true)
        }
        val outputContainsCliError = LLM_CLI_ERROR_OUTPUT_PATTERN.containsMatchIn(combinedOutput)

        return outputContainsAuthFailure && (exitFailed || outputContainsCliError)
    }

    private fun ProcessRunResult.didFail(): Boolean {
        val timedOut = status == ProcessRunStatus.TIMED_OUT
        val nonZeroExit = exitCode?.let { completedExitCode -> completedExitCode != 0 } ?: false

        return timedOut || nonZeroExit
    }
}

/**
 * LLM phase audit の結果。
 *
 * @param invocationResult process 実行結果を含む LLM 起動結果
 * @param duration phase 実行時間
 * @param authFailureSuspected CLI 認証失敗らしい出力を検出したか
 */
data class LlmPhaseAuditResult(
    val invocationResult: LlmInvocationResult,
    val duration: Duration,
    val authFailureSuspected: Boolean,
)

/**
 * LLM phase audit の既定 tool 名。
 */
const val DEFAULT_LLM_PHASE_AUDIT_TOOL_NAME = "one_shot_runner"

/**
 * LLM CLI 認証失敗の可能性を運用ログへ伝える案内。
 */
const val LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE =
    "LLM CLI authentication failure suspected. Login runbook: " +
        "docs/llm-obsidian-production-setup.md" +
        "（claude: docker exec -it fukurou-ktor claude → /login、" +
        "codex: docker exec -it fukurou-ktor codex login --device-auth）"

/**
 * LLM CLI 認証失敗を疑うための出力断片。
 *
 * stdout / stderr の raw output に対する推定シグナルであり、provider や CLI version によっては
 * false positive を含みうる。fail-closed の挙動は変えず、運用上の気づきだけを追加する。
 */
private val LLM_CLI_AUTH_FAILURE_PATTERNS = listOf(
    "invalid authentication",
    "401",
    "unauthorized",
    "not logged in",
    "token expired",
    "please run /login",
)

/**
 * Claude CLI の result JSON が error 終了を示す出力断片。
 */
private val LLM_CLI_ERROR_OUTPUT_PATTERN = Regex(""""is_error"\s*:\s*true""")
