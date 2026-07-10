package me.matsumo.fukurou.trading.invoker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.matsumo.fukurou.trading.evaluation.LlmModelUsage
import me.matsumo.fukurou.trading.evaluation.LlmTokenUsage
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * provider output を正規化した結果。
 *
 * @param responseText semantic response 本文
 * @param usage invocation 単位の structured usage
 */
data class ParsedLlmOutput(
    val responseText: String,
    val usage: LlmUsageDetails?,
)

/**
 * Claude JSON と Codex JSONL を解析する既定 output parser。
 */
class DefaultLlmOutputParser : LlmOutputParser {

    override fun parse(
        request: LlmInvocationRequest,
        command: RenderedLlmCommand,
        processResult: ProcessRunResult,
        startedAt: Instant,
        completedAt: Instant,
    ): ParsedLlmOutput {
        return when (request.provider) {
            LlmProvider.CLAUDE -> parseClaude(processResult.stdout)
            LlmProvider.CODEX -> parseCodex(command, processResult.stdout, startedAt, completedAt)
        }
    }

    private fun parseClaude(stdout: String): ParsedLlmOutput {
        val responseText = runCatching {
            OutputJson.parseToJsonElement(stdout)
                .jsonObject["result"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull() ?: stdout

        return ParsedLlmOutput(
            responseText = responseText,
            usage = LlmUsageParser.parseClaudeStdout(stdout),
        )
    }

    private fun parseCodex(
        command: RenderedLlmCommand,
        stdout: String,
        startedAt: Instant,
        completedAt: Instant,
    ): ParsedLlmOutput {
        val parsed = parseCodexEvents(stdout)
        val model = parsed.threadId?.let { threadId ->
            resolveCodexModel(command, threadId, startedAt, completedAt)
        }
        val usage = parsed.usage?.let { tokenUsage ->
            LlmUsageDetails(
                totalCostUsd = null,
                numTurns = null,
                durationMs = null,
                usage = tokenUsage,
                modelUsages = model?.let { modelName ->
                    listOf(LlmModelUsage(modelName, tokenUsage))
                }.orEmpty(),
            )
        }

        return ParsedLlmOutput(
            responseText = parsed.responseText.orEmpty(),
            usage = usage,
        )
    }

    private fun parseCodexEvents(stdout: String): ParsedCodexEvents {
        var threadId: String? = null
        var responseText: String? = null
        var usage: LlmTokenUsage? = null

        stdout.lineSequence().forEach { line ->
            val event = runCatching { OutputJson.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return@forEach

            when (event.stringOrNull("type")) {
                CODEX_THREAD_STARTED_EVENT -> {
                    threadId = event.stringOrNull("thread_id") ?: threadId
                }

                CODEX_ITEM_COMPLETED_EVENT -> {
                    val item = event.objectOrNull("item")
                    if (item?.stringOrNull("type") == CODEX_AGENT_MESSAGE_ITEM) {
                        responseText = item.stringOrNull("text") ?: responseText
                    }
                }

                CODEX_TURN_COMPLETED_EVENT -> {
                    usage = event.objectOrNull("usage")?.toCodexTokenUsage() ?: usage
                }
            }
        }

        return ParsedCodexEvents(
            threadId = threadId,
            responseText = responseText,
            usage = usage,
        )
    }

    private fun resolveCodexModel(
        command: RenderedLlmCommand,
        threadId: String,
        startedAt: Instant,
        completedAt: Instant,
    ): String? {
        val codexHomeValue = command.environment[CODEX_HOME_ENV] ?: return null
        val codexHome = runCatching { Path.of(codexHomeValue) }.getOrNull() ?: return null
        val sessionRoot = codexHome.resolve(CODEX_SESSIONS_DIRECTORY)
        val candidateFiles = invocationDates(startedAt, completedAt)
            .flatMap { date -> sessionFiles(sessionRoot, date) }

        return candidateFiles.firstNotNullOfOrNull { path -> resolveModelFromSession(path, threadId) }
    }

    private fun invocationDates(startedAt: Instant, completedAt: Instant): Set<LocalDate> {
        return setOf(
            startedAt.atZone(ZoneOffset.UTC).toLocalDate(),
            completedAt.atZone(ZoneOffset.UTC).toLocalDate(),
        )
    }

    private fun sessionFiles(sessionRoot: Path, date: LocalDate): List<Path> {
        val directory = sessionRoot
            .resolve(date.year.toString())
            .resolve(date.monthValue.toString().padStart(2, '0'))
            .resolve(date.dayOfMonth.toString().padStart(2, '0'))

        return runCatching {
            if (!Files.isDirectory(directory)) {
                return@runCatching emptyList()
            }

            Files.list(directory).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) }
                    .limit(MAX_SESSION_FILES_PER_DAY)
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun resolveModelFromSession(path: Path, threadId: String): String? {
        return runCatching {
            var sessionMatches = false
            var model: String? = null

            Files.newBufferedReader(path).useLines { lines ->
                lines.take(MAX_SESSION_LINES).forEach { line ->
                    val event = runCatching { OutputJson.parseToJsonElement(line).jsonObject }.getOrNull()
                        ?: return@forEach
                    val payload = event.objectOrNull("payload") ?: return@forEach

                    when (event.stringOrNull("type")) {
                        CODEX_SESSION_META_EVENT -> {
                            sessionMatches = payload.stringOrNull("id") == threadId
                        }

                        CODEX_TURN_CONTEXT_EVENT -> {
                            if (sessionMatches) {
                                model = payload.stringOrNull("model") ?: model
                            }
                        }
                    }
                }
            }

            model.takeIf { sessionMatches }
        }.getOrNull()
    }
}

private data class ParsedCodexEvents(
    val threadId: String?,
    val responseText: String?,
    val usage: LlmTokenUsage?,
)

private fun JsonObject.objectOrNull(key: String): JsonObject? {
    return this[key] as? JsonObject
}

private fun JsonObject.stringOrNull(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.toCodexTokenUsage(): LlmTokenUsage? {
    val inputTokens = longOrNull("input_tokens")
    val cachedInputTokens = longOrNull("cached_input_tokens")
    val outputTokens = longOrNull("output_tokens")
    val reasoningOutputTokens = longOrNull("reasoning_output_tokens")
    val hasTokenUsage = listOf(inputTokens, cachedInputTokens, outputTokens, reasoningOutputTokens)
        .any { value -> value != null }

    if (!hasTokenUsage) return null

    return LlmTokenUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningOutputTokens = reasoningOutputTokens,
        cacheCreationInputTokens = null,
        cacheReadInputTokens = cachedInputTokens,
    )
}

private fun JsonObject.longOrNull(key: String): Long? {
    return (this[key] as? JsonPrimitive)?.longOrNull
}

private const val CODEX_THREAD_STARTED_EVENT = "thread.started"
private const val CODEX_ITEM_COMPLETED_EVENT = "item.completed"
private const val CODEX_TURN_COMPLETED_EVENT = "turn.completed"
private const val CODEX_AGENT_MESSAGE_ITEM = "agent_message"
private const val CODEX_SESSION_META_EVENT = "session_meta"
private const val CODEX_TURN_CONTEXT_EVENT = "turn_context"
private const val CODEX_SESSIONS_DIRECTORY = "sessions"
private const val MAX_SESSION_FILES_PER_DAY = 256L
private const val MAX_SESSION_LINES = 10_000

private val OutputJson = Json {
    ignoreUnknownKeys = true
}
