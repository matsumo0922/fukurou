package me.matsumo.fukurou.trading.decision

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.FalsifierPolicy
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FalsifierPolicyDecisionRepositoryTest {
    @Test
    fun `same payload retry returns one durable decision and event`() = runBlocking {
        val repository = InMemoryFalsifierPolicyDecisionRepository()
        val request = request()

        assertEquals(request.decision, repository.recordFalsifierPolicyDecision(request).getOrThrow())
        assertEquals(request.decision, repository.recordFalsifierPolicyDecision(request).getOrThrow())
        assertEquals(1, repository.events().size)
    }

    @Test
    fun `different payload for same intent fails closed`() = runBlocking {
        val repository = InMemoryFalsifierPolicyDecisionRepository()
        val request = request()
        repository.recordFalsifierPolicyDecision(request).getOrThrow()

        assertFailsWith<FalsifierPolicyDecisionConflictException> {
            repository.recordFalsifierPolicyDecision(
                request.copy(decision = FalsifierPolicyDecision.create(
                    decisionId = request.decision.decisionId,
                    intentId = request.decision.intentId,
                    policy = request.decision.policy,
                    required = false,
                    reasonCodes = request.decision.reasonCodes,
                    runtimeConfigVersionId = request.decision.runtimeConfigVersionId,
                    runtimeConfigHash = request.decision.runtimeConfigHash,
                    createdAt = request.decision.createdAt,
                )),
            ).getOrThrow()
        }
        Unit
    }

    @Test
    fun `missing event side fails closed`() = runBlocking {
        val repository = InMemoryFalsifierPolicyDecisionRepository()
        val request = request()
        repository.seedDecisionWithoutEvent(request.decision)

        assertFailsWith<FalsifierPolicyDecisionConflictException> {
            repository.recordFalsifierPolicyDecision(request).getOrThrow()
        }
        assertFailsWith<FalsifierPolicyDecisionConflictException> {
            repository.findFalsifierPolicyDecision(request.decision.intentId).getOrThrow()
        }
        Unit
    }

    private fun request(): FalsifierPolicyDecisionRequest = FalsifierPolicyDecisionRequest(
        decision = FalsifierPolicyDecision.create(
            decisionId = UUID.randomUUID(),
            intentId = UUID.randomUUID(),
            policy = FalsifierPolicy.ALWAYS_ON_V1,
            required = true,
            reasonCodes = setOf(FalsifierPolicyReasonCode.ALWAYS_ON),
            runtimeConfigVersionId = "runtime-v1",
            runtimeConfigHash = "a".repeat(64),
            createdAt = Instant.parse("2026-07-31T00:00:00Z"),
        ),
    )
}
