package me.matsumo.fukurou.trading.invoker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import me.matsumo.fukurou.trading.evaluation.LlmTokenUsage
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import java.time.Instant

/**
 * provider output を正規化した結果。
 *
 * @param responseText semantic response 本文
 * @param usage invocation 単位の structured usage
 * @param authEvidenceObserved primary category の先勝ち解決とは独立に、既知の認証 evidence 文言
 * （`CODEX_KNOWN_AUTH_EVIDENCE_TEXTS`）を出力中に観測したか。default を持たないため、
 * 全ての構築箇所で明示が必須（fail-closed）。Codex 以外の provider では常に false
 * @param configuredModelIdentity renderer が確定した model identity
 * @param observedModelIdentity provider output が報告した model identity
 * @param providerFailure schema または provider が報告した typed failure
 * @param adapterSchemaVersion pinned CLI output adapter revision
 */
data class ParsedLlmOutput(
    val responseText: String,
    val usage: LlmUsageDetails?,
    val authEvidenceObserved: Boolean,
    val configuredModelIdentity: LlmConfiguredModelIdentity = LlmConfiguredModelIdentity.CLI_DEFAULT,
    val observedModelIdentity: LlmObservedModelIdentity? = null,
    val providerFailure: LlmProviderFailure? = null,
    val adapterSchemaVersion: String = LLM_INVOCATION_CONTRACT_VERSION,
)

/** Claude 2.1.199 result JSON と Codex 0.142.5 JSONL の versioned adapter。 */
class DefaultLlmOutputParser : LlmOutputParser {

    override fun parse(
        request: LlmInvocationRequest,
        command: RenderedLlmCommand,
        processResult: ProcessRunResult,
        startedAt: Instant,
        completedAt: Instant,
    ): ParsedLlmOutput {
        return when (request.provider) {
            LlmProvider.CLAUDE -> parseClaude(command, processResult.stdout)
            LlmProvider.CODEX -> parseCodex(command, processResult)
        }
    }

    private fun parseClaude(command: RenderedLlmCommand, stdout: String): ParsedLlmOutput {
        val result = runCatching { OutputJson.parseToJsonElement(stdout) as? JsonObject }.getOrNull()
        if (result == null) {
            return contractFailure(command, CLAUDE_OUTPUT_ADAPTER_VERSION)
        }

        val responseText = result.stringOrNull("result")
        val isError = result.booleanOrNull("is_error")
        val contractComplete = result.stringOrNull("type") == CLAUDE_RESULT_TYPE &&
            responseText != null && isError != null
        val providerCode = result.objectOrNull("error")?.safeCodeOrNull()
            ?: result.stringOrNull("subtype")?.safeProviderCodeOrNull()
        val structuredFailure = providerCode?.knownFailureCategory()
        val compatibilityFailure = responseText?.knownCompatibilityFailureCategory()
        val providerFailure = when {
            isError == true && structuredFailure != null -> failure(
                structuredFailure,
                providerCode,
                CLAUDE_OUTPUT_ADAPTER_VERSION,
            )
            isError == true && compatibilityFailure != null -> failure(
                compatibilityFailure,
                "CLAUDE_RESULT_COMPATIBILITY",
                CLAUDE_OUTPUT_ADAPTER_VERSION,
            )
            !contractComplete -> outputContractFailure(CLAUDE_OUTPUT_ADAPTER_VERSION)
            isError == true -> failure(
                LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE,
                providerCode,
                CLAUDE_OUTPUT_ADAPTER_VERSION,
            )
            else -> null
        }
        val usage = LlmUsageParser.parseClaudeStdout(stdout)
        val observedModel = result.stringOrNull("model")
            ?: usage?.modelUsages?.singleOrNull()?.model

        return ParsedLlmOutput(
            responseText = responseText.orEmpty(),
            usage = usage,
            authEvidenceObserved = false,
            configuredModelIdentity = command.configuredModelIdentity,
            observedModelIdentity = observedModel?.let { model ->
                LlmObservedModelIdentity(model, CLAUDE_OUTPUT_MODEL_SOURCE)
            },
            providerFailure = providerFailure,
            adapterSchemaVersion = CLAUDE_OUTPUT_ADAPTER_VERSION,
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun parseCodex(command: RenderedLlmCommand, processResult: ProcessRunResult): ParsedLlmOutput {
        var threadId: String? = null
        var responseText: String? = null
        var usage: LlmTokenUsage? = null
        var terminalSucceeded: Boolean? = null
        var terminalCount = 0
        var providerCategory: LlmProviderFailureCategory? = null
        var schemaDrift = false
        var authEvidenceObserved = false

        processResult.stdout.lineSequence().filter(String::isNotBlank).forEach { line ->
            val event = runCatching { OutputJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
            if (event == null) {
                schemaDrift = true
                return@forEach
            }

            when (event.stringOrNull("type")) {
                CODEX_THREAD_STARTED_EVENT -> {
                    threadId = event.stringOrNull("thread_id") ?: threadId
                    if (threadId == null) schemaDrift = true
                }
                CODEX_ITEM_COMPLETED_EVENT -> {
                    val item = event.objectOrNull("item")
                    if (item?.stringOrNull("type") == CODEX_AGENT_MESSAGE_ITEM) {
                        responseText = item.stringOrNull("text") ?: responseText
                    }
                }
                CODEX_TURN_COMPLETED_EVENT -> {
                    terminalCount++
                    terminalSucceeded = terminalSucceeded ?: true
                    usage = event.objectOrNull("usage")?.toCodexTokenUsage()
                    if (usage == null) schemaDrift = true
                }
                "turn.failed" -> {
                    terminalCount++
                    terminalSucceeded = terminalSucceeded ?: false
                    val message = event.objectOrNull("error")?.stringOrNull("message")
                    val messageCategory = message?.knownCompatibilityFailureCategory()
                    if (messageCategory == LlmProviderFailureCategory.AUTHENTICATION) authEvidenceObserved = true
                    providerCategory = providerCategory ?: messageCategory
                        ?: LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE
                    if (message == null) schemaDrift = true
                }
                "error" -> {
                    val message = event.stringOrNull("message")
                    val messageCategory = message?.knownCompatibilityFailureCategory()
                    if (messageCategory == LlmProviderFailureCategory.AUTHENTICATION) authEvidenceObserved = true
                    providerCategory = providerCategory ?: messageCategory
                        ?: LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE
                    if (message == null) schemaDrift = true
                }
            }
        }

        if (CODEX_KNOWN_AUTH_EVIDENCE_TEXTS.any { knownText ->
                processResult.stdout.contains(knownText) || processResult.stderr.contains(knownText)
            }
        ) {
            authEvidenceObserved = true
        }

        val hasExactlyOneTerminal = terminalCount == 1
        val successfulContractComplete = hasExactlyOneTerminal && terminalSucceeded == true &&
            providerCategory == null && threadId != null && responseText != null && usage != null
        val stderrAuthFailure = terminalCount == 0 && processResult.exitCode != 0 &&
            processResult.stderr.trimEnd('\r', '\n') in CODEX_STDERR_AUTH_FAILURES
        val providerFailure = when {
            stderrAuthFailure -> failure(
                LlmProviderFailureCategory.AUTHENTICATION,
                "CODEX_LOGIN_RESTRICTION",
                CODEX_OUTPUT_ADAPTER_VERSION,
            )
            schemaDrift || !hasExactlyOneTerminal -> outputContractFailure(CODEX_OUTPUT_ADAPTER_VERSION)
            terminalSucceeded == false -> failure(
                requireNotNull(providerCategory),
                "CODEX_ERROR_COMPATIBILITY",
                CODEX_OUTPUT_ADAPTER_VERSION,
            )
            !successfulContractComplete -> outputContractFailure(CODEX_OUTPUT_ADAPTER_VERSION)
            else -> null
        }
        val usageDetails = usage?.let { tokenUsage ->
            LlmUsageDetails(
                totalCostUsd = null,
                numTurns = null,
                durationMs = null,
                usage = tokenUsage,
                modelUsages = emptyList(),
            )
        }

        return ParsedLlmOutput(
            responseText = responseText.orEmpty(),
            usage = usageDetails,
            authEvidenceObserved = authEvidenceObserved,
            configuredModelIdentity = command.configuredModelIdentity,
            observedModelIdentity = null,
            providerFailure = providerFailure,
            adapterSchemaVersion = CODEX_OUTPUT_ADAPTER_VERSION,
        )
    }

    private fun contractFailure(command: RenderedLlmCommand, adapterVersion: String): ParsedLlmOutput {
        return ParsedLlmOutput(
            responseText = "",
            usage = null,
            authEvidenceObserved = false,
            configuredModelIdentity = command.configuredModelIdentity,
            observedModelIdentity = null,
            providerFailure = outputContractFailure(adapterVersion),
            adapterSchemaVersion = adapterVersion,
        )
    }
}

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.safeCodeOrNull(): String? {
    return stringOrNull("code")?.safeProviderCodeOrNull()
        ?: stringOrNull("type")?.safeProviderCodeOrNull()
}

private fun String.safeProviderCodeOrNull(): String? {
    val normalized = uppercase().replace('-', '_')
    return normalized.takeIf(SAFE_PROVIDER_CODE::matches)
}

private fun String.knownFailureCategory(): LlmProviderFailureCategory? = when (this) {
    "AUTHENTICATION", "AUTHENTICATION_ERROR", "INVALID_AUTHENTICATION", "UNAUTHORIZED" ->
        LlmProviderFailureCategory.AUTHENTICATION
    "RATE_LIMIT", "RATE_LIMIT_ERROR", "SESSION_LIMIT", "TOO_MANY_REQUESTS" ->
        LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT
    "QUOTA_EXHAUSTED", "USAGE_LIMIT", "INSUFFICIENT_QUOTA" ->
        LlmProviderFailureCategory.QUOTA_EXHAUSTED
    else -> null
}

private fun String.knownCompatibilityFailureCategory(): LlmProviderFailureCategory? = when (trim()) {
    "Not logged in", "Invalid authentication credentials" -> LlmProviderFailureCategory.AUTHENTICATION
    "Session limit reached", "Rate limit exceeded" -> LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT
    "Quota exhausted", "Usage limit reached" -> LlmProviderFailureCategory.QUOTA_EXHAUSTED
    else -> null
}

private fun JsonObject.toCodexTokenUsage(): LlmTokenUsage? {
    val inputTokens = longOrNull("input_tokens") ?: return null
    val cachedInputTokens = longOrNull("cached_input_tokens") ?: return null
    val outputTokens = longOrNull("output_tokens") ?: return null
    val reasoningOutputTokens = longOrNull("reasoning_output_tokens") ?: return null

    return LlmTokenUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningOutputTokens = reasoningOutputTokens,
        cacheCreationInputTokens = null,
        cacheReadInputTokens = cachedInputTokens,
    )
}

private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun outputContractFailure(adapterVersion: String): LlmProviderFailure {
    return failure(LlmProviderFailureCategory.OUTPUT_CONTRACT, "SCHEMA_DRIFT", adapterVersion)
}

private fun failure(
    category: LlmProviderFailureCategory,
    providerCode: String?,
    adapterVersion: String,
): LlmProviderFailure {
    return LlmProviderFailure(category, providerCode, adapterVersion)
}

/** Claude output adapter が対応する image pin。 */
const val CLAUDE_OUTPUT_ADAPTER_VERSION = "claude-code-2.1.199-result-v1"

/** Codex output adapter が対応する image pin。 */
const val CODEX_OUTPUT_ADAPTER_VERSION = "codex-cli-0.142.5-jsonl-v1"

private const val CLAUDE_RESULT_TYPE = "result"
private const val CLAUDE_OUTPUT_MODEL_SOURCE = "CLAUDE_RESULT"
private const val CODEX_THREAD_STARTED_EVENT = "thread.started"
private const val CODEX_ITEM_COMPLETED_EVENT = "item.completed"
private const val CODEX_TURN_COMPLETED_EVENT = "turn.completed"
private const val CODEX_AGENT_MESSAGE_ITEM = "agent_message"

/**
 * Codex が認証失敗時に stderr へ出す既知の固定文言。
 *
 * `parseCodex()` の主 category 判定（`stderrAuthFailure`）が `trimEnd()` した stderr 全体との
 * 完全一致で使う集合。[CODEX_KNOWN_AUTH_EVIDENCE_TEXTS] の一部としても使われる。
 */
internal val CODEX_STDERR_AUTH_FAILURES = setOf(
    "API key login is required, but ChatGPT is currently being used. Logging out.",
    "ChatGPT login is required, but an API key is currently being used. Logging out.",
)

/**
 * 認証 evidence の独立追跡（`authEvidenceObserved`）が stdout/stderr 全文に対して
 * `.contains()`（部分一致）で検査する既知文言の集合。
 *
 * [CODEX_STDERR_AUTH_FAILURES] の2文言に加え、[knownCompatibilityFailureCategory] が
 * `AUTHENTICATION` に分類する2文言（"Not logged in"/"Invalid authentication credentials"）を含む。
 * `RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED` に分類される文言は意図的に含まない
 * （それらは新設の output-interpreted 経路自身が公開対象とするカテゴリであり、
 * 分類文言自体を evidence 扱いすると自己矛盾でブロックされ続けるため）。
 *
 * この検査は「疑わしきは記録しない」という evidence 追跡が目的であり、主 category を
 * 確定させるための厳格な完全一致判定（[CODEX_STDERR_AUTH_FAILURES] 単体の用途）とは
 * 意図的に区別する。既知文言との一致判定に過ぎず、未知の secret や token の
 * 不存在を証明するものではない。
 */
internal val CODEX_KNOWN_AUTH_EVIDENCE_TEXTS = CODEX_STDERR_AUTH_FAILURES + setOf(
    "Not logged in",
    "Invalid authentication credentials",
)
private val SAFE_PROVIDER_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")

private val OutputJson = Json {
    ignoreUnknownKeys = true
}
