package me.matsumo.fukurou.trading.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import me.matsumo.fukurou.trading.invoker.CODEX_INVOCATION_RESULT_UNAVAILABLE
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.invoker.classifyLlmFailure
import me.matsumo.fukurou.trading.invoker.safeExceptionType
import java.time.Clock
import java.time.Duration

/**
 * LLM phase の process 実行結果を共通 event 形式で command_event_log へ保存する auditor。
 *
 * @param commandEventLog audit 保存先
 * @param redactor Claude stdout / stderr と error message を保存前に伏せる redactor
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
        val startFailure = result.exceptionOrNull()
            ?.takeIf { processResult == null }
        val usage = invocationResult?.usage
        val auditSignals = processResult?.auditSignals() ?: LlmPhaseAuditSignals()

        val appendFailure = appendPhase(
            context = context,
            phaseName = phaseName,
            duration = duration,
            details = phaseDetails(
                provider = request.provider,
                processResult = processResult,
                startFailure = startFailure,
                usage = usage,
                auditSignals = auditSignals,
            ),
        ).exceptionOrNull()
        appendFailure?.let { failure -> throw failure.classifyLlmFailure(request.provider) }
        humanLogger("$phaseName completed invocation=${request.invocationId} duration=${duration.toMillis()}ms")
        if (auditSignals.authFailureSuspected && authFailureMessage != null) {
            humanLogger(authFailureMessage)
        }

        val processFailed = processResult?.didFail() ?: false

        if (processFailed) {
            val failure = IllegalStateException("$phaseName process did not exit cleanly.")

            return Result.failure(failure.classifyLlmFailure(request.provider))
        }

        return result.fold(
            onSuccess = { invocation ->
                Result.success(
                    LlmPhaseAuditResult(
                        invocationResult = invocation,
                        duration = duration,
                        authFailureSuspected = auditSignals.authFailureSuspected,
                        cliErrorReported = auditSignals.cliErrorReported,
                    ),
                )
            },
            onFailure = { throwable -> Result.failure(throwable.classifyLlmFailure(request.provider)) },
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

    private fun phaseDetails(
        provider: LlmProvider,
        processResult: ProcessRunResult?,
        startFailure: Throwable?,
        usage: LlmUsageDetails?,
        auditSignals: LlmPhaseAuditSignals,
    ): JsonObject {
        return buildJsonObject {
            put("provider", provider.name.lowercase())
            put("status", processResult?.status?.name ?: "FAILED_TO_START")
            put("exitCode", processResult?.exitCode?.toString() ?: "null")
            startFailure?.let { throwable ->
                when (provider) {
                    LlmProvider.CLAUDE -> put("error", throwable.redactedQualifiedErrorMessage())
                    LlmProvider.CODEX -> {
                        put("failureCategory", CODEX_INVOCATION_RESULT_UNAVAILABLE)
                        put("failureType", throwable.safeExceptionType())
                    }
                }
            }
            when (provider) {
                LlmProvider.CLAUDE -> processResult?.let { completedProcess ->
                    put("stdout", redactor.redactAndTruncate(completedProcess.stdout))
                    put("stderr", redactor.redactAndTruncate(completedProcess.stderr))
                }

                LlmProvider.CODEX -> {
                    if (processResult != null) {
                        put("rawOutputOmitted", "true")
                    }
                }
            }
            if (auditSignals.authFailureSuspected) {
                put("authFailureSuspected", "true")
            }
            if (auditSignals.cliErrorReported) {
                put("cliErrorReported", "true")
            }
            usage?.let { parsedUsage ->
                put("usage", LlmUsageParser.toJsonObject(parsedUsage))
            }
        }
    }

    private fun ProcessRunResult.auditSignals(): LlmPhaseAuditSignals {
        val cliErrorReported = cliErrorReported()

        return LlmPhaseAuditSignals(
            authFailureSuspected = authFailureSuspected(cliErrorReported),
            cliErrorReported = cliErrorReported,
        )
    }

    private fun ProcessRunResult.authFailureSuspected(cliErrorReported: Boolean): Boolean {
        val exitFailed = exitCode?.let { completedExitCode -> completedExitCode != 0 } ?: false
        val output = combinedOutput()
        val outputContainsAuthFailure = LLM_CLI_AUTH_FAILURE_PATTERNS.any { pattern ->
            output.contains(pattern, ignoreCase = true)
        }

        return outputContainsAuthFailure && (exitFailed || cliErrorReported)
    }

    private fun ProcessRunResult.cliErrorReported(): Boolean {
        return LLM_CLI_ERROR_OUTPUT_PATTERN.containsMatchIn(combinedOutput())
    }

    private fun ProcessRunResult.combinedOutput(): String {
        return "$stdout\n$stderr"
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
 * @param cliErrorReported CLI が error 終了を報告する出力を検出したか
 */
data class LlmPhaseAuditResult(
    val invocationResult: LlmInvocationResult,
    val duration: Duration,
    val authFailureSuspected: Boolean,
    val cliErrorReported: Boolean,
)

/**
 * LLM phase audit へ載せる CLI 出力由来の検出シグナル。
 *
 * @param authFailureSuspected CLI 認証失敗らしい出力を検出したか
 * @param cliErrorReported CLI が error 終了を報告する出力を検出したか
 */
private data class LlmPhaseAuditSignals(
    val authFailureSuspected: Boolean = false,
    val cliErrorReported: Boolean = false,
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
