package me.matsumo.fukurou.trading.invoker

import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun parseCodex_prioritizesThreadIdFilenameBeyondFallbackLimit() {
        val codexHome = Files.createTempDirectory("codex-output-parser-filename-test")
        val sessionDirectory = codexHome.resolve("sessions/2026/07/10")
        Files.createDirectories(sessionDirectory)
        repeat(300) { index ->
            Files.writeString(
                sessionDirectory.resolve("unrelated-$index.jsonl"),
                sessionJsonl(threadId = "other-thread-$index", model = "wrong-model"),
            )
        }
        val threadId = "019f0f13-d14f-71c1-a517-f71bb01767b5"
        Files.writeString(
            sessionDirectory.resolve("rollout-2026-07-10T00-00-00-$threadId.jsonl"),
            sessionJsonl(threadId = threadId, model = "gpt-5.4"),
        )
        val warnings = mutableListOf<String>()

        val output = DefaultLlmOutputParser(warnings::add).parse(
            request = request(LlmProvider.CODEX),
            command = command(codexHome),
            processResult = processResult(codexStdout(threadId)),
            startedAt = Instant.parse("2026-07-10T00:00:00Z"),
            completedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )

        assertEquals("gpt-5.4", output.usage?.modelUsages?.single()?.model)
        assertEquals(emptyList(), warnings)

        codexHome.toFile().deleteRecursively()
    }

    @Test
    fun parseCodex_sortsFallbackByLastModifiedAndWarnsWhenFileScanIsTruncated() {
        val codexHome = Files.createTempDirectory("codex-output-parser-fallback-test")
        val sessionDirectory = codexHome.resolve("sessions/2026/07/10")
        Files.createDirectories(sessionDirectory)
        repeat(256) { index ->
            Files.writeString(
                sessionDirectory.resolve("unrelated-$index.jsonl"),
                sessionJsonl(threadId = "other-thread-$index", model = "wrong-model"),
            )
        }
        val threadId = "019f0f13-d14f-71c1-a517-f71bb01767b5"
        val target = sessionDirectory.resolve("legacy-name.jsonl")
        Files.writeString(target, sessionJsonl(threadId = threadId, model = "gpt-5.4"))
        Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2100-01-01T00:00:00Z")))
        val warnings = mutableListOf<String>()

        val output = DefaultLlmOutputParser(warnings::add).parse(
            request = request(LlmProvider.CODEX),
            command = command(codexHome),
            processResult = processResult(codexStdout(threadId)),
            startedAt = Instant.parse("2026-07-10T00:00:00Z"),
            completedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )

        assertEquals("gpt-5.4", output.usage?.modelUsages?.single()?.model)
        assertTrue(warnings.single().contains("fallback truncated session files"))

        codexHome.toFile().deleteRecursively()
    }

    @Test
    fun parseCodex_warnsWhenMatchingSessionContentExceedsLineLimit() {
        val codexHome = Files.createTempDirectory("codex-output-parser-line-limit-test")
        val sessionDirectory = codexHome.resolve("sessions/2026/07/10")
        Files.createDirectories(sessionDirectory)
        val threadId = "019f0f13-d14f-71c1-a517-f71bb01767b5"
        val sessionContent = buildString {
            appendLine("""{"type":"session_meta","payload":{"id":"$threadId"}}""")
            repeat(9_999) { appendLine("malformed") }
            appendLine("""{"type":"turn_context","payload":{"model":"gpt-5.4"}}""")
        }
        Files.writeString(
            sessionDirectory.resolve("rollout-$threadId.jsonl"),
            sessionContent,
        )
        val warnings = mutableListOf<String>()

        val output = DefaultLlmOutputParser(warnings::add).parse(
            request = request(LlmProvider.CODEX),
            command = command(codexHome),
            processResult = processResult(codexStdout(threadId)),
            startedAt = Instant.parse("2026-07-10T00:00:00Z"),
            completedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )

        assertEquals(emptyList(), output.usage?.modelUsages)
        assertTrue(warnings.single().contains("truncated session content"))

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

private fun codexStdout(threadId: String): String {
    return """
        {"type":"thread.started","thread_id":"$threadId"}
        {"type":"item.completed","item":{"type":"agent_message","text":"response"}}
        {"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":4}}
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
