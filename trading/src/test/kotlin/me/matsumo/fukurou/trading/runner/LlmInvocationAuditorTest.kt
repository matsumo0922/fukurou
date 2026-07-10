package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.evaluation.LlmTokenUsage
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LlmInvocationAuditor の phase audit payload を検証するテスト。
 */
class LlmInvocationAuditorTest {

    @Test
    fun invokeAndAudit_appendsRedactedPhasePayloadWithClaudeUsage() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(setOf("reflection-secret-token")),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = LlmInvocationRequest(
            invocationId = "audit-run",
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PROPOSER,
            prompt = "test prompt",
            timeout = Duration.ofSeconds(60),
            workingDirectory = Path.of(".").toAbsolutePath().normalize(),
            decisionRunContext = DecisionRunContext(
                decisionRunId = "audit-run",
                llmProvider = "claude",
                promptHash = "prompt-hash",
                systemPromptVersion = "system-prompt-v1",
                marketSnapshotId = "snapshot-1",
            ),
            mcpServer = null,
            environment = emptyMap(),
            allowedTools = emptyList(),
        )
        val invoker = StaticAuditLlmInvoker(
            stdout = """{"total_cost_usd":"0.0123","note":"reflection-secret-token"}""",
        )

        val result = auditor.invokeAndAudit(
            phaseName = "proposer",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val event = commandEventLog.events().single()
        val payload = Json.parseToJsonElement(event.payload).jsonObject
        val details = requireNotNull(payload["details"]).jsonObject

        assertTrue(result.isSuccess)
        assertEquals("proposer", requireNotNull(payload["phase"]).jsonPrimitive.content)
        assertEquals("claude", requireNotNull(details["provider"]).jsonPrimitive.content)
        assertEquals("EXITED", requireNotNull(details["status"]).jsonPrimitive.content)
        assertEquals("0.0123", requireNotNull(details["usage"]).jsonObject["totalCostUsd"]?.jsonPrimitive?.content)
        assertTrue(requireNotNull(details["stdout"]).jsonPrimitive.content.contains("[REDACTED]"))
        assertFalse(event.payload.contains("reflection-secret-token"))
    }

    @Test
    fun invokeAndAudit_preservesPartialCodexUsageWhileFailingClosed() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CODEX)
        val usage = LlmUsageDetails(
            totalCostUsd = null,
            numTurns = null,
            durationMs = null,
            usage = LlmTokenUsage(
                inputTokens = 10,
                outputTokens = 4,
                reasoningOutputTokens = 2,
                cacheCreationInputTokens = null,
                cacheReadInputTokens = 3,
            ),
            modelUsages = emptyList(),
        )
        val invoker = StaticStructuredAuditLlmInvoker(usage)

        val result = auditor.invokeAndAudit(
            phaseName = "falsifier",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = invoker,
        )

        val payload = Json.parseToJsonElement(commandEventLog.events().single().payload).jsonObject
        val details = requireNotNull(payload["details"]).jsonObject
        val auditUsage = requireNotNull(details["usage"]).jsonObject

        assertTrue(result.isFailure)
        assertEquals("1", details["exitCode"]?.jsonPrimitive?.content)
        assertEquals("2", auditUsage["usage"]?.jsonObject?.get("reasoningOutputTokens")?.jsonPrimitive?.content)
        assertFalse(auditUsage.containsKey("totalCostUsd"))
    }
}

private fun auditRequest(provider: LlmProvider): LlmInvocationRequest {
    return LlmInvocationRequest(
        invocationId = "audit-run",
        provider = provider,
        phase = LlmInvocationPhase.FALSIFIER,
        prompt = "test prompt",
        timeout = Duration.ofSeconds(60),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        decisionRunContext = DecisionRunContext.EMPTY,
        mcpServer = null,
        environment = emptyMap(),
        allowedTools = emptyList(),
    )
}

private class StaticStructuredAuditLlmInvoker(
    private val usage: LlmUsageDetails,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = 1,
                    stdout = "partial structured output",
                    stderr = "synthetic failure",
                ),
                responseText = "partial response",
                usage = usage,
            ),
        )
    }
}

private class StaticAuditLlmInvoker(
    private val stdout: String,
) : LlmInvoker {

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = 0,
                    stdout = stdout,
                    stderr = "",
                ),
                usage = LlmUsageParser.parseClaudeStdout(stdout),
            ),
        )
    }
}
