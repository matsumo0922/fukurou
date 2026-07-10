package me.matsumo.fukurou.trading.evaluation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * Claude JSON stdout から監査保存可能な usage だけを抽出する parser。
 */
object LlmUsageParser {

    /**
     * Claude stdout を best-effort で解析する。失敗時は null を返す。
     */
    fun parseClaudeStdout(stdout: String): LlmUsageDetails? {
        val root = runCatching { UsageJson.parseToJsonElement(stdout).jsonObject }
            .getOrNull()
            ?: return null

        return detailsFromJson(root)
    }

    /**
     * 監査 payload の details.usage JSON を domain model へ戻す。
     */
    fun parseUsageElement(element: JsonElement): LlmUsageDetails? {
        val root = element as? JsonObject ?: return null

        return detailsFromJson(root)
    }

    /**
     * usage details を監査 payload へ入れる JSON に変換する。
     */
    fun toJsonObject(details: LlmUsageDetails): JsonObject {
        return buildJsonObject {
            details.totalCostUsd?.let { value -> put("totalCostUsd", value.toPlainString()) }
            details.numTurns?.let { value -> put("numTurns", value) }
            details.durationMs?.let { value -> put("durationMs", value) }
            details.usage?.let { usage -> put("usage", usage.toJsonObject()) }
            if (details.modelUsages.isNotEmpty()) {
                put(
                    "modelUsage",
                    buildJsonObject {
                        details.modelUsages.forEach { modelUsage ->
                            put(modelUsage.model, modelUsage.usage.toJsonObject())
                        }
                    },
                )
            }
        }
    }

    private fun detailsFromJson(root: JsonObject): LlmUsageDetails? {
        val totalCostUsd = root.decimalOrNull("total_cost_usd") ?: root.decimalOrNull("totalCostUsd")
        val numTurns = root.longOrNull("num_turns") ?: root.longOrNull("numTurns")
        val durationMs = root.longOrNull("duration_ms") ?: root.longOrNull("durationMs")
        val usage = root.objectOrNull("usage")?.toTokenUsage()
        val modelUsages = root.objectOrNull("modelUsage")
            ?.toModelUsages()
            .orEmpty()
        val extractedValues = listOf(totalCostUsd, numTurns, durationMs, usage)
        val hasAnyUsage = extractedValues.any { value -> value != null } || modelUsages.isNotEmpty()

        if (!hasAnyUsage) {
            return null
        }

        return LlmUsageDetails(
            totalCostUsd = totalCostUsd,
            numTurns = numTurns,
            durationMs = durationMs,
            usage = usage,
            modelUsages = modelUsages,
        )
    }
}

private fun JsonObject.decimalOrNull(key: String): BigDecimal? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    val content = primitive.contentOrNull ?: return null

    return runCatching { BigDecimal(content) }.getOrNull()
}

private fun JsonObject.longOrNull(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null

    return primitive.longOrNull
}

private fun JsonObject.objectOrNull(key: String): JsonObject? {
    return this[key] as? JsonObject
}

private fun JsonObject.toModelUsages(): List<LlmModelUsage> {
    return entries.mapNotNull { entry ->
        val usageObject = entry.value as? JsonObject ?: return@mapNotNull null
        val usage = usageObject.toTokenUsage() ?: return@mapNotNull null

        LlmModelUsage(
            model = entry.key,
            usage = usage,
        )
    }
}

private fun JsonObject.toTokenUsage(): LlmTokenUsage? {
    val inputTokens = longOrNull("input_tokens") ?: longOrNull("inputTokens")
    val outputTokens = longOrNull("output_tokens") ?: longOrNull("outputTokens")
    val reasoningOutputTokens = longOrNull("reasoning_output_tokens") ?: longOrNull("reasoningOutputTokens")
    val cacheCreationInputTokens = longOrNull("cache_creation_input_tokens") ?: longOrNull("cacheCreationInputTokens")
    val cacheReadInputTokens = longOrNull("cache_read_input_tokens") ?: longOrNull("cacheReadInputTokens")
    val tokenValues = listOf(
        inputTokens,
        outputTokens,
        reasoningOutputTokens,
        cacheCreationInputTokens,
        cacheReadInputTokens,
    )
    val hasAnyToken = tokenValues.any { value -> value != null }

    if (!hasAnyToken) {
        return null
    }

    return LlmTokenUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningOutputTokens = reasoningOutputTokens,
        cacheCreationInputTokens = cacheCreationInputTokens,
        cacheReadInputTokens = cacheReadInputTokens,
    )
}

private fun LlmTokenUsage.toJsonObject(): JsonObject {
    return buildJsonObject {
        inputTokens?.let { value -> put("inputTokens", value) }
        outputTokens?.let { value -> put("outputTokens", value) }
        reasoningOutputTokens?.let { value -> put("reasoningOutputTokens", value) }
        cacheCreationInputTokens?.let { value -> put("cacheCreationInputTokens", value) }
        cacheReadInputTokens?.let { value -> put("cacheReadInputTokens", value) }
    }
}

/**
 * usage parser 用 JSON。
 */
private val UsageJson = Json {
    ignoreUnknownKeys = true
}
