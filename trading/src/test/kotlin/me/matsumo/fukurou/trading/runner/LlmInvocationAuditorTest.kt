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
import java.nio.file.FileSystemException
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
        assertEquals("true", details["rawOutputOmitted"]?.jsonPrimitive?.content)
        assertEquals("true", details["authFailureSuspected"]?.jsonPrimitive?.content)
        assertFalse(details.containsKey("stdout"))
        assertFalse(details.containsKey("stderr"))
        assertFalse(auditUsage.containsKey("totalCostUsd"))
        assertFalse(commandEventLog.events().single().payload.contains("private trading strategy"))
        assertFalse(commandEventLog.events().single().payload.contains("submit_decision"))
        assertFalse(commandEventLog.events().single().payload.contains("private/session/path"))
    }

    @Test
    fun invokeAndAudit_recordsCompletedUsageBeforeFailingOnCleanup() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val humanLogs = mutableListOf<String>()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
            humanLogger = humanLogs::add,
        )
        val request = auditRequest(LlmProvider.CODEX)
        val usage = LlmUsageDetails(
            totalCostUsd = null,
            numTurns = null,
            durationMs = null,
            usage = LlmTokenUsage(
                inputTokens = 12,
                outputTokens = 3,
                reasoningOutputTokens = 1,
                cacheCreationInputTokens = null,
                cacheReadInputTokens = 2,
            ),
            modelUsages = emptyList(),
        )
        val cleanupFailure = FileSystemException(
            "/private/codex-home/path-marker",
            null,
            "cleanup path-message-marker",
        )
        val invoker = CleanupFailingAuditLlmInvoker(usage, cleanupFailure)

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
        assertEquals("EXITED", details["status"]?.jsonPrimitive?.content)
        assertEquals("0", details["exitCode"]?.jsonPrimitive?.content)
        assertEquals("true", details["cleanupFailed"]?.jsonPrimitive?.content)
        assertTrue(details["cleanupFailed"]?.jsonPrimitive?.isString == true)
        assertEquals("12", auditUsage["usage"]?.jsonObject?.get("inputTokens")?.jsonPrimitive?.content)
        assertFalse(commandEventLog.events().single().payload.contains("path-marker"))
        assertFalse(commandEventLog.events().single().payload.contains("path-message-marker"))
        assertEquals(emptyList(), humanLogs)
    }

    @Test
    fun invokeAndAudit_preservesClaudeStartFailureDetail() = runBlocking {
        val commandEventLog = InMemoryCommandEventLog()
        val auditor = LlmInvocationAuditor(
            commandEventLog = commandEventLog,
            redactor = SecretRedactor(emptySet()),
            clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
        )
        val request = auditRequest(LlmProvider.CLAUDE)

        val result = auditor.invokeAndAudit(
            phaseName = "proposer",
            context = request.decisionRunContext,
            request = request,
            llmInvoker = FailingAuditLlmInvoker(IllegalStateException("synthetic claude failure")),
        )

        val payload = Json.parseToJsonElement(commandEventLog.events().single().payload).jsonObject
        val details = requireNotNull(payload["details"]).jsonObject

        assertTrue(result.isFailure)
        assertEquals("FAILED_TO_START", details["status"]?.jsonPrimitive?.content)
        assertTrue(details["error"]?.jsonPrimitive?.content.orEmpty().contains("synthetic claude failure"))
        assertFalse(details.containsKey("failureCategory"))
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
                    stdout = """
                        {"type":"thread.started","prompt":"private trading strategy"}
                        {"type":"item.completed","item":{"type":"tool_call","name":"submit_decision","arguments":{"path":"/private/session/path"}}}
                    """.trimIndent(),
                    stderr = "401 unauthorized at /private/session/path",
                ),
                responseText = "partial response",
                usage = usage,
            ),
        )
    }
}

private class CleanupFailingAuditLlmInvoker(
    private val usage: LlmUsageDetails,
    private val cleanupFailure: Throwable,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = 0,
                    stdout = "private raw output",
                    stderr = "",
                ),
                responseText = "semantic response",
                usage = usage,
                cleanupFailure = cleanupFailure,
            ),
        )
    }
}

/**
 * auditor test 用の失敗する LLM invoker。
 */
private class FailingAuditLlmInvoker(
    private val failure: Throwable,
) : LlmInvoker {
    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        return Result.failure(failure)
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
                responseText = stdout,
                usage = LlmUsageParser.parseClaudeStdout(stdout),
            ),
        )
    }
}
