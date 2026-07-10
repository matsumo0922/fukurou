package me.matsumo.fukurou.trading.invoker

import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Codex CLI 0.142.5 JSONL と session model attribution を検証するテスト。
 */
class DefaultLlmOutputParserTest {

    @Test
    fun parseCodex_extractsLastSemanticResponseUsageAndMatchingSessionModel() {
        val codexHome = Files.createTempDirectory("codex-output-parser-test")
        val sessionDirectory = codexHome.resolve("sessions/2026/07/10")
        Files.createDirectories(sessionDirectory)
        Files.writeString(
            sessionDirectory.resolve("unrelated.jsonl"),
            sessionJsonl(threadId = "other-thread", model = "wrong-model"),
        )
        Files.writeString(
            sessionDirectory.resolve("matching.jsonl"),
            sessionJsonl(threadId = "thread-157", model = "gpt-5.4"),
        )
        val stdout = """
            {"type":"thread.started","thread_id":"thread-157"}
            malformed-line
            {"type":"future.event","payload":{"secret":"ignored"}}
            {"type":"item.completed","item":{"id":"item-1","type":"agent_message","text":"draft"}}
            {"type":"turn.completed","usage":{"input_tokens":120,"cached_input_tokens":40,"output_tokens":30,"reasoning_output_tokens":12}}
            {"type":"item.completed","item":{"id":"item-2","type":"agent_message","text":"final response"}}
        """.trimIndent()

        val output = DefaultLlmOutputParser().parse(
            request = request(LlmProvider.CODEX),
            command = command(codexHome),
            processResult = processResult(stdout),
            startedAt = Instant.parse("2026-07-10T23:59:59Z"),
            completedAt = Instant.parse("2026-07-11T00:00:01Z"),
        )

        assertEquals("final response", output.responseText)
        assertNull(output.usage?.totalCostUsd)
        assertEquals(120, output.usage?.usage?.inputTokens)
        assertEquals(40, output.usage?.usage?.cacheReadInputTokens)
        assertEquals(30, output.usage?.usage?.outputTokens)
        assertEquals(12, output.usage?.usage?.reasoningOutputTokens)
        assertEquals("gpt-5.4", output.usage?.modelUsages?.single()?.model)
        assertEquals(30, output.usage?.modelUsages?.single()?.usage?.outputTokens)

        codexHome.toFile().deleteRecursively()
    }

    @Test
    fun parseCodex_preservesUsageAndResponseWhenSessionIsUnavailable() {
        val codexHome = Files.createTempDirectory("codex-output-parser-missing-session-test")
        val stdout = """
            {"type":"thread.started","thread_id":"thread-without-session"}
            {"type":"item.completed","item":{"type":"agent_message","text":"partial response"}}
            {"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":4}}
            {"type":"turn.failed","error":{"message":"synthetic failure"}}
        """.trimIndent()

        val output = DefaultLlmOutputParser().parse(
            request = request(LlmProvider.CODEX),
            command = command(codexHome),
            processResult = processResult(stdout, exitCode = 1),
            startedAt = Instant.parse("2026-07-10T00:00:00Z"),
            completedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )

        assertEquals("partial response", output.responseText)
        assertEquals(10, output.usage?.usage?.inputTokens)
        assertEquals(4, output.usage?.usage?.outputTokens)
        assertEquals(emptyList(), output.usage?.modelUsages)

        codexHome.toFile().deleteRecursively()
    }

    @Test
    fun parseClaude_preservesExistingSemanticResponseAndUsage() {
        val stdout = """
            {
              "type":"result",
              "result":"YES",
              "total_cost_usd":0.01,
              "usage":{"input_tokens":3,"output_tokens":1}
            }
        """.trimIndent()

        val output = DefaultLlmOutputParser().parse(
            request = request(LlmProvider.CLAUDE),
            command = command(Files.createTempDirectory("claude-output-parser-test")),
            processResult = processResult(stdout),
            startedAt = Instant.EPOCH,
            completedAt = Instant.EPOCH,
        )

        assertEquals("YES", output.responseText)
        assertEquals("0.01", output.usage?.totalCostUsd?.toPlainString())
        assertEquals(3, output.usage?.usage?.inputTokens)
    }
}

private fun sessionJsonl(threadId: String, model: String): String {
    return """
        {"timestamp":"2026-07-10T23:59:59Z","type":"session_meta","payload":{"id":"$threadId"}}
        {"timestamp":"2026-07-10T23:59:59Z","type":"turn_context","payload":{"model":"$model"}}
    """.trimIndent()
}

private fun request(provider: LlmProvider): LlmInvocationRequest {
    return LlmInvocationRequest(
        invocationId = "invocation-157",
        provider = provider,
        phase = LlmInvocationPhase.PRE_FILTER,
        prompt = "synthetic prompt",
        timeout = Duration.ofSeconds(1),
        workingDirectory = Path.of("."),
        decisionRunContext = DecisionRunContext.EMPTY,
        mcpServer = null,
        environment = emptyMap(),
        allowedTools = emptyList(),
    )
}

private fun command(codexHome: Path): RenderedLlmCommand {
    return RenderedLlmCommand(
        executable = "synthetic",
        args = emptyList(),
        environment = mapOf(CODEX_HOME_ENV to codexHome.toString()),
        workingDirectory = Path.of("."),
        timeout = Duration.ofSeconds(1),
        stdin = null,
    )
}

private fun processResult(stdout: String, exitCode: Int = 0): ProcessRunResult {
    return ProcessRunResult(
        status = ProcessRunStatus.EXITED,
        exitCode = exitCode,
        stdout = stdout,
        stderr = "",
    )
}
