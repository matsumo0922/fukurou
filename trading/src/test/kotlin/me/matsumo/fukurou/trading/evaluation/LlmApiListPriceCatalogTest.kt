package me.matsumo.fukurou.trading.evaluation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** LlmApiListPriceCatalog の保守的な適用条件を検証する。 */
class LlmApiListPriceCatalogTest {
    @Test
    fun exactGpt55UsageUsesStandardRatesWithoutDoubleChargingReasoning() {
        val price = LlmApiListPriceCatalog.calculate(
            codexFact(
                usage = LlmTokenUsage(
                    inputTokens = 1_000,
                    outputTokens = 100,
                    reasoningOutputTokens = 80,
                    cacheCreationInputTokens = null,
                    cacheReadInputTokens = 400,
                ),
            ),
        )

        assertEquals("0.0062", price?.stripTrailingZeros()?.toPlainString())
    }

    @Test
    fun unprovableModelTierOrTokenUsageRemainsUnpriced() {
        val valid = codexFact()
        val invalidFacts = listOf(
            valid.copy(provider = "claude"),
            valid.copy(configuredModel = null),
            valid.copy(configuredModel = "gpt-5.5-2026-04-23"),
            codexFact(inputTokens = 272_000),
            codexFact(inputTokens = 9, cachedInputTokens = 10),
            codexFact(outputTokens = 9, reasoningOutputTokens = 10),
            codexFact(cachedInputTokens = null),
            codexFact(outputTokens = -1),
        )

        invalidFacts.forEach { fact -> assertNull(LlmApiListPriceCatalog.calculate(fact)) }
    }
}

private fun codexFact(
    inputTokens: Long? = 10,
    cachedInputTokens: Long? = 5,
    outputTokens: Long? = 4,
    reasoningOutputTokens: Long? = null,
    usage: LlmTokenUsage = LlmTokenUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningOutputTokens = reasoningOutputTokens,
        cacheCreationInputTokens = null,
        cacheReadInputTokens = cachedInputTokens,
    ),
): LlmPhaseUsageFact {
    return LlmPhaseUsageFact(
        decisionRunId = "run-1",
        provider = "codex",
        phase = "falsifier",
        configuredModel = "gpt-5.5",
        occurredAt = Instant.parse("2026-07-17T00:00:00Z"),
        usage = LlmUsageDetails(null, null, null, usage, emptyList()),
    )
}
