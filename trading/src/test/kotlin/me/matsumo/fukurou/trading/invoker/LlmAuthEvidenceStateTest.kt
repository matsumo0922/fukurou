package me.matsumo.fukurou.trading.invoker

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * in-process の CLI auth 失敗 evidence の上書き規則を検証するテスト。
 */
class LlmAuthEvidenceStateTest {

    @Test
    fun lastFailure_returnsNullBeforeAnyFailureIsObserved() {
        val state = LlmAuthEvidenceState()

        assertNull(state.lastFailure(LlmProvider.CODEX))
    }

    @Test
    fun recordFailure_keepsEvidencePerProvider() {
        val state = LlmAuthEvidenceState()
        val codexEvidence = evidence(observedAt = "2026-07-20T00:00:00Z", generation = "2026-07-19T00:00:00Z")

        state.recordFailure(LlmProvider.CODEX, codexEvidence)

        assertEquals(codexEvidence, state.lastFailure(LlmProvider.CODEX))
        assertNull(state.lastFailure(LlmProvider.CLAUDE))
    }

    @Test
    fun recordFailure_doesNotLetAnOlderGenerationOverwriteANewerOne() {
        // 旧 credential で走っていた invocation が、新 credential の invocation より後に完了し得る。
        // 無条件に上書きすると旧世代 evidence が残り、marker との世代比較で無視されて降格が消える
        val state = LlmAuthEvidenceState()
        val newerGeneration = evidence(observedAt = "2026-07-20T00:00:00Z", generation = "2026-07-20T00:00:00Z")
        val olderGeneration = evidence(observedAt = "2026-07-20T00:05:00Z", generation = "2026-07-19T00:00:00Z")

        state.recordFailure(LlmProvider.CODEX, newerGeneration)
        state.recordFailure(LlmProvider.CODEX, olderGeneration)

        assertEquals(newerGeneration, state.lastFailure(LlmProvider.CODEX))
    }

    @Test
    fun recordFailure_replacesEvidenceWhenTheGenerationIsNewer() {
        val state = LlmAuthEvidenceState()
        val olderGeneration = evidence(observedAt = "2026-07-20T00:00:00Z", generation = "2026-07-19T00:00:00Z")
        val newerGeneration = evidence(observedAt = "2026-07-20T00:05:00Z", generation = "2026-07-20T00:00:00Z")

        state.recordFailure(LlmProvider.CODEX, olderGeneration)
        state.recordFailure(LlmProvider.CODEX, newerGeneration)

        assertEquals(newerGeneration, state.lastFailure(LlmProvider.CODEX))
    }

    @Test
    fun recordFailure_keepsTheLatestObservationWithinTheSameGeneration() {
        val state = LlmAuthEvidenceState()
        val earlier = evidence(observedAt = "2026-07-20T00:00:00Z", generation = "2026-07-19T00:00:00Z")
        val later = evidence(observedAt = "2026-07-20T00:05:00Z", generation = "2026-07-19T00:00:00Z")

        state.recordFailure(LlmProvider.CODEX, later)
        state.recordFailure(LlmProvider.CODEX, earlier)

        assertEquals(later, state.lastFailure(LlmProvider.CODEX))
    }

    @Test
    fun recordFailure_fallsBackToObservationTimeWhenAGenerationIsUnknown() {
        val state = LlmAuthEvidenceState()
        val withoutGeneration = LlmAuthFailureEvidence(
            observedAt = Instant.parse("2026-07-20T00:05:00Z"),
            credentialGeneration = null,
        )
        val earlierWithGeneration = evidence(observedAt = "2026-07-20T00:00:00Z", generation = "2026-07-19T00:00:00Z")

        state.recordFailure(LlmProvider.CODEX, withoutGeneration)
        state.recordFailure(LlmProvider.CODEX, earlierWithGeneration)

        assertEquals(withoutGeneration, state.lastFailure(LlmProvider.CODEX))
    }
}

private fun evidence(observedAt: String, generation: String): LlmAuthFailureEvidence {
    return LlmAuthFailureEvidence(
        observedAt = Instant.parse(observedAt),
        credentialGeneration = Instant.parse(generation),
    )
}
