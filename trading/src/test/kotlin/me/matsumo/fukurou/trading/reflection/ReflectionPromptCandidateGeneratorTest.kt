package me.matsumo.fukurou.trading.reflection

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ReflectionPromptCandidateGenerator の LLM output 検証と audit 記録を検証するテスト。
 */
class ReflectionPromptCandidateGeneratorTest {

    @Test
    fun generate_writesPromptCandidatesFromValidJsonAndRecordsReflectionRun() = runBlocking {
        val fixture = reflectionPromptCandidateGeneratorFixture()

        val generation = fixture.generator.generate(reflectionDataset(), existingState = null).getOrThrow()

        assertEquals(ReflectionPromptCandidateGenerationStatus.GENERATED, generation.generatedStatus())
        assertEquals(1, generation.attemptCount())
        assertEquals(listOf("Knowledge/PromptCandidates/2026-W27.md"), generation.files.map { file -> file.relativePath })
        assertTrue(generation.files.single().content.contains("requires_human_approval: true"))
        assertTrue(generation.files.single().content.contains("entry 前に closed_trades と setup tag の根拠を明記する。"))

        val request = fixture.invoker.requests.single()
        assertEquals(LlmInvocationPhase.REFLECTION, request.phase)
        assertEquals("claude", request.decisionRunContext.llmProvider)
        assertNull(request.mcpServer)
        assertEquals(emptyList(), request.allowedTools)

        val llmRun = fixture.llmRunRepository.records().single()
        assertEquals(LlmDaemonTriggerKind.REFLECTION, llmRun.triggerKind)
        assertEquals("SUCCEEDED", llmRun.status)

        val auditEvent = fixture.commandEventLog.events().single()
        assertTrue(auditEvent.payload.contains(""""phase":"reflection""""))
        assertTrue(auditEvent.payload.contains(""""status":"EXITED""""))
    }

    @Test
    fun generate_rejectsInvalidJsonAndRequiredFieldViolations() = runBlocking {
        val outputs = listOf(
            """{"candidates": [""",
            validPromptCandidateJson().replace(
                """"requiresHumanApproval": true""",
                """"requiresHumanApproval": false""",
            ),
            validPromptCandidateJson().replace(
                """"evidence": ["decision_runs and closed_trades evidence from 2026-W27"]""",
                """"evidence": ["unrelated observation"]""",
            ),
            validPromptCandidateJson().replace(
                """"proposedChangeJa": "entry 前に closed_trades と setup tag の根拠を明記する。"""",
                """"proposedChangeJa": """"",
            ),
        )

        outputs.forEach { output ->
            val fixture = reflectionPromptCandidateGeneratorFixture(
                processResult = Result.success(cleanReflectionProcess(output)),
            )

            val generation = fixture.generator.generate(reflectionDataset(), existingState = null).getOrThrow()

            assertEquals(ReflectionPromptCandidateGenerationStatus.INVALID_OUTPUT, generation.generatedStatus())
            assertEquals(1, generation.attemptCount())
            assertEquals(1, fixture.invoker.requests.size)
        }
    }

    @Test
    fun generate_rejectsSuspiciousSecretOutputBeforeWritingCandidate() = runBlocking {
        val fixture = reflectionPromptCandidateGeneratorFixture(
            processResult = Result.success(
                cleanReflectionProcess(
                    """
                        |{
                        |  "candidates": [],
                        |  "debug": "api_key=reflection-secret-token"
                        |}
                    """.trimMargin(),
                ),
            ),
        )

        val generation = fixture.generator.generate(reflectionDataset(), existingState = null).getOrThrow()

        assertEquals(ReflectionPromptCandidateGenerationStatus.INVALID_OUTPUT, generation.generatedStatus())
        assertFalse(generation.files.single().content.contains("reflection-secret-token"))
    }

    @Test
    fun generate_skipsLlmAndWritesInputTruncatedWhenWeeklyDataIsTruncated() = runBlocking {
        val fixture = reflectionPromptCandidateGeneratorFixture()

        val generation = fixture.generator.generate(
            dataset = reflectionDataset(weeklyTruncated = true),
            existingState = null,
        ).getOrThrow()

        assertEquals(ReflectionPromptCandidateGenerationStatus.INPUT_TRUNCATED, generation.generatedStatus())
        assertEquals(0, generation.attemptCount())
        assertTrue(fixture.invoker.requests.isEmpty())
        assertTrue(fixture.llmRunRepository.records().isEmpty())
    }
}
