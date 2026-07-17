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
 * @param configuredModelIdentity renderer が確定した model identity
 * @param observedModelIdentity provider output が報告した model identity
 * @param providerFailure schema または provider が報告した typed failure
 * @param adapterSchemaVersion pinned CLI output adapter revision
 */
data class ParsedLlmOutput(
    val responseText: String,
    val usage: LlmUsageDetails?,
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
            LlmProvider.CODEX -> parseCodex(command, processResult.stdout)
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
            configuredModelIdentity = command.configuredModelIdentity,
            observedModelIdentity = observedModel?.let { model ->
                LlmObservedModelIdentity(model, CLAUDE_OUTPUT_MODEL_SOURCE)
            },
            providerFailure = providerFailure,
            adapterSchemaVersion = CLAUDE_OUTPUT_ADAPTER_VERSION,
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun parseCodex(command: RenderedLlmCommand, stdout: String): ParsedLlmOutput {
        var threadId: String? = null
        var responseText: String? = null
        var usage: LlmTokenUsage? = null
        var terminalSucceeded: Boolean? = null
        var terminalCount = 0
        var providerMessage: String? = null
        var schemaDrift = false

        stdout.lineSequence().filter(String::isNotBlank).forEach { line ->
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
                    providerMessage = providerMessage ?: message
                    if (message == null) schemaDrift = true
                }
                "error" -> {
                    terminalCount++
                    terminalSucceeded = terminalSucceeded ?: false
                    val message = event.stringOrNull("message")
                    providerMessage = providerMessage ?: message
                    if (message == null) schemaDrift = true
                }
            }
        }

        val hasExactlyOneTerminal = terminalCount == 1
        val successfulContractComplete = hasExactlyOneTerminal && terminalSucceeded == true &&
            threadId != null && responseText != null && usage != null
        val failedContractComplete = hasExactlyOneTerminal && terminalSucceeded == false &&
            providerMessage != null
        val compatibilityFailure = providerMessage?.knownCompatibilityFailureCategory()
        val providerFailure = when {
            schemaDrift || !hasExactlyOneTerminal -> outputContractFailure(CODEX_OUTPUT_ADAPTER_VERSION)
            terminalSucceeded == false && compatibilityFailure != null -> failure(
                compatibilityFailure,
                "CODEX_ERROR_COMPATIBILITY",
                CODEX_OUTPUT_ADAPTER_VERSION,
            )
            !successfulContractComplete && !failedContractComplete ->
                outputContractFailure(CODEX_OUTPUT_ADAPTER_VERSION)
            terminalSucceeded == false -> failure(
                LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE,
                null,
                CODEX_OUTPUT_ADAPTER_VERSION,
            )
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
private val SAFE_PROVIDER_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")

private val OutputJson = Json {
    ignoreUnknownKeys = true
}
