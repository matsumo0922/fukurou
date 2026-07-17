package me.matsumo.fukurou.trading.evaluation

import java.math.BigDecimal

/** API list-price 換算に使う versioned catalog metadata。 */
data class LlmApiListPriceCatalogMetadata(
    val version: String,
    val asOf: String,
    val basis: String,
    val sourceUrl: String,
    val maxPhaseInputTokensExclusive: Long,
)

/** 保存済み token usage を standard API list price 相当額へ換算する catalog。 */
object LlmApiListPriceCatalog {
    val metadata = LlmApiListPriceCatalogMetadata(
        version = "openai-gpt-5.5-2026-07-17",
        asOf = "2026-07-17",
        basis = "STANDARD_API",
        sourceUrl = "https://developers.openai.com/api/docs/models/gpt-5.5",
        maxPhaseInputTokensExclusive = STANDARD_INPUT_LIMIT,
    )

    /** 証明可能な Codex phase だけを USD へ換算する。 */
    fun calculate(fact: LlmPhaseUsageFact): BigDecimal? {
        if (fact.provider != CODEX_PROVIDER || fact.configuredModel != GPT_5_5_MODEL) return null
        val usage = fact.usage?.usage ?: return null
        val inputTokens = usage.inputTokens.validTokenCount() ?: return null
        val cachedInputTokens = usage.cacheReadInputTokens.validTokenCount() ?: return null
        val outputTokens = usage.outputTokens.validTokenCount() ?: return null
        val reasoningOutputTokens = usage.reasoningOutputTokens

        if (inputTokens >= STANDARD_INPUT_LIMIT || cachedInputTokens > inputTokens) return null
        val hasInvalidReasoningTokens = reasoningOutputTokens != null &&
            (reasoningOutputTokens < 0 || reasoningOutputTokens > outputTokens)
        if (hasInvalidReasoningTokens) return null

        val uncachedInputTokens = inputTokens - cachedInputTokens

        return BigDecimal.valueOf(uncachedInputTokens).multiply(UNCACHED_INPUT_RATE)
            .add(BigDecimal.valueOf(cachedInputTokens).multiply(CACHED_INPUT_RATE))
            .add(BigDecimal.valueOf(outputTokens).multiply(OUTPUT_RATE))
            .divide(TOKENS_PER_MILLION)
    }
}

private fun Long?.validTokenCount(): Long? = this?.takeIf { value -> value >= 0 }

private const val CODEX_PROVIDER = "codex"
private const val GPT_5_5_MODEL = "gpt-5.5"
private const val STANDARD_INPUT_LIMIT = 272_000L
private val TOKENS_PER_MILLION = BigDecimal("1000000")
private val UNCACHED_INPUT_RATE = BigDecimal("5")
private val CACHED_INPUT_RATE = BigDecimal("0.5")
private val OUTPUT_RATE = BigDecimal("30")
