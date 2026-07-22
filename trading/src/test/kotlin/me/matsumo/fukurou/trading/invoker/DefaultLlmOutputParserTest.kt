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

/**
 * pinned Claude/Codex output adapter contract を検証するテスト。
 */
class DefaultLlmOutputParserTest {

    @Test
    fun parseCodexParsesExactSchemaAndIgnoresUnsupportedModelFields() {
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
            {"type":"future.event","payload":{"secret":"ignored"}}
            {"type":"item.completed","item":{"id":"item-1","type":"agent_message","text":"draft"}}
            {"type":"turn.completed","model":"ignored","usage":{"input_tokens":120,"cached_input_tokens":40,"output_tokens":30,"reasoning_output_tokens":12}}
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
        assertEquals(emptyList(), output.usage?.modelUsages)
        assertNull(output.observedModelIdentity)
        assertNull(output.providerFailure)

        codexHome.toFile().deleteRecursively()
    }

    @Test
    fun parseCodex_preservesUsageAndResponseWhenSessionIsUnavailable() {
        val codexHome = Files.createTempDirectory("codex-output-parser-missing-session-test")
        val stdout = """
            {"type":"thread.started","thread_id":"thread-without-session"}
            {"type":"item.completed","item":{"type":"agent_message","text":"partial response"}}
            {"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":4,"reasoning_output_tokens":0}}
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

        val output = DefaultLlmOutputParser().parse(
            request = request(LlmProvider.CODEX),
            command = command(codexHome),
            processResult = processResult(codexStdout(threadId)),
            startedAt = Instant.parse("2026-07-10T00:00:00Z"),
            completedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )

        assertNull(output.usage)
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

        val output = DefaultLlmOutputParser().parse(
            request = request(LlmProvider.CODEX),
            command = command(codexHome),
            processResult = processResult(codexStdout(threadId)),
            startedAt = Instant.parse("2026-07-10T00:00:00Z"),
            completedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )

        assertNull(output.usage)
        assertEquals(emptyList(), warnings)

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

        val output = DefaultLlmOutputParser().parse(
            request = request(LlmProvider.CODEX),
            command = command(codexHome),
            processResult = processResult(codexStdout(threadId)),
            startedAt = Instant.parse("2026-07-10T00:00:00Z"),
            completedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )

        assertNull(output.usage)
        assertEquals(emptyList(), warnings)

        codexHome.toFile().deleteRecursively()
    }

    @Test
    fun parseCodex_doesNotWarnWhenModelIsResolvedBeforeSessionLineLimit() {
        val codexHome = Files.createTempDirectory("codex-output-parser-resolved-line-limit-test")
        val sessionDirectory = codexHome.resolve("sessions/2026/07/10")
        Files.createDirectories(sessionDirectory)
        val threadId = "019f0f13-d14f-71c1-a517-f71bb01767b5"
        val sessionContent = buildString {
            appendLine("""{"type":"session_meta","payload":{"id":"$threadId"}}""")
            appendLine("""{"type":"turn_context","payload":{"model":"gpt-5.4"}}""")
            repeat(9_999) { appendLine("malformed") }
        }
        Files.writeString(
            sessionDirectory.resolve("rollout-$threadId.jsonl"),
            sessionContent,
        )
        val warnings = mutableListOf<String>()

        val output = DefaultLlmOutputParser().parse(
            request = request(LlmProvider.CODEX),
            command = command(codexHome),
            processResult = processResult(codexStdout(threadId)),
            startedAt = Instant.parse("2026-07-10T00:00:00Z"),
            completedAt = Instant.parse("2026-07-10T00:00:01Z"),
        )

        assertNull(output.usage)
        assertEquals(emptyList(), warnings)

        codexHome.toFile().deleteRecursively()
    }

    @Test
    fun parseClaude_preservesExistingSemanticResponseAndUsage() {
        val stdout = """
            {
              "type":"result",
              "is_error":false,
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
        assertNull(output.providerFailure)
    }

    @Test
    fun parseCodexClassifiesExactFailuresAndRejectsInvalidTerminals() {
        val thread = """{"type":"thread.started","thread_id":"thread-failure"}"""
        val completed = """{"type":"turn.completed","usage":{"input_tokens":2,"cached_input_tokens":0,"output_tokens":1,"reasoning_output_tokens":0}}"""
        val cases = mapOf(
            processResult("$thread\n{\"type\":\"error\",\"message\":\"Not logged in\"}\n{\"type\":\"turn.failed\",\"error\":{\"message\":\"turn failed\"}}") to LlmProviderFailureCategory.AUTHENTICATION,
            processResult("$thread\n{\"type\":\"error\",\"message\":\"Session limit reached\"}\n{\"type\":\"turn.failed\",\"error\":{\"message\":\"turn failed\"}}") to LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT,
            processResult("$thread\n{\"type\":\"error\",\"message\":\"Quota exhausted\"}\n{\"type\":\"turn.failed\",\"error\":{\"message\":\"turn failed\"}}") to LlmProviderFailureCategory.QUOTA_EXHAUSTED,
            processResult("$thread\n{\"type\":\"error\",\"message\":\"Not logged in\"}\n$completed") to LlmProviderFailureCategory.OUTPUT_CONTRACT,
            processResult("$thread\n{\"type\":\"turn.failed\",\"error\":{\"message\":\"Session limit reached\"}}\n$completed") to LlmProviderFailureCategory.OUTPUT_CONTRACT,
            failedProcess("ChatGPT login is required, but an API key is currently being used. Logging out.\n") to LlmProviderFailureCategory.AUTHENTICATION,
            failedProcess("prefix ChatGPT login is required, but an API key is currently being used. Logging out.") to LlmProviderFailureCategory.OUTPUT_CONTRACT,
        )
        cases.forEach { (processResult, category) ->
            val output = DefaultLlmOutputParser().parse(
                request(LlmProvider.CODEX),
                command(Files.createTempDirectory("codex-failure-output-test")),
                processResult,
                Instant.EPOCH,
                Instant.EPOCH,
            )

            assertEquals(category, output.providerFailure?.category)
        }
    }

    @Test
    fun parseCodex_tracksAuthEvidenceFromTurnFailedMessageEvenWhenAnEarlierEventDeterminesTheCategory() {
        val thread = """{"type":"thread.started","thread_id":"thread-evidence"}"""
        // 先行する error event が providerCategory を RATE_OR_SESSION_LIMIT に確定させる（first-win）。
        // 後続の turn.failed の message は AUTHENTICATION に分類されるが、first-win では category を
        // 上書きしない。authEvidenceObserved はそれとは独立に true になるべき
        val stdout = """
            $thread
            {"type":"error","message":"Session limit reached"}
            {"type":"turn.failed","error":{"message":"Not logged in"}}
        """.trimIndent()

        val output = DefaultLlmOutputParser().parse(
            request(LlmProvider.CODEX),
            command(Files.createTempDirectory("codex-evidence-turn-failed-test")),
            processResult(stdout, exitCode = 1),
            Instant.EPOCH,
            Instant.EPOCH,
        )

        assertEquals(LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT, output.providerFailure?.category)
        assertEquals(true, output.authEvidenceObserved)
    }

    @Test
    fun parseCodex_tracksAuthEvidenceFromErrorEventMessageEvenWhenAnEarlierEventDeterminesTheCategory() {
        val thread = """{"type":"thread.started","thread_id":"thread-evidence"}"""
        // 先行する error event が providerCategory を QUOTA_EXHAUSTED に確定させる（first-win）。
        // 後続の error の message は AUTHENTICATION に分類されるが、first-win では category を
        // 上書きしない。authEvidenceObserved はそれとは独立に true になるべき
        val stdout = """
            $thread
            {"type":"error","message":"Quota exhausted"}
            {"type":"error","message":"Invalid authentication credentials"}
            {"type":"turn.failed","error":{"message":"turn failed"}}
        """.trimIndent()

        val output = DefaultLlmOutputParser().parse(
            request(LlmProvider.CODEX),
            command(Files.createTempDirectory("codex-evidence-error-test")),
            processResult(stdout, exitCode = 1),
            Instant.EPOCH,
            Instant.EPOCH,
        )

        assertEquals(LlmProviderFailureCategory.QUOTA_EXHAUSTED, output.providerFailure?.category)
        assertEquals(true, output.authEvidenceObserved)
    }

    @Test
    fun parseCodex_tracksAuthEvidenceFromStderrAlongsideACompleteSuccessfulEventStream() {
        val stdout = """
            {"type":"thread.started","thread_id":"thread-evidence"}
            {"type":"item.completed","item":{"type":"agent_message","text":"final response"}}
            {"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":5,"reasoning_output_tokens":0}}
        """.trimIndent()

        val output = DefaultLlmOutputParser().parse(
            request(LlmProvider.CODEX),
            command(Files.createTempDirectory("codex-evidence-success-stream-test")),
            processResult(stdout, exitCode = 0).copy(stderr = "Not logged in"),
            Instant.EPOCH,
            Instant.EPOCH,
        )

        assertNull(output.providerFailure)
        assertEquals(true, output.authEvidenceObserved)
    }

    @Test
    fun parseCodex_doesNotObserveAuthEvidenceForSchemaDriftWithNoKnownAuthText() {
        val output = DefaultLlmOutputParser().parse(
            request(LlmProvider.CODEX),
            command(Files.createTempDirectory("codex-evidence-schema-drift-no-evidence-test")),
            processResult("mcp handshake failed: unexpected token at position 0", exitCode = 1)
                .copy(stderr = "codex: failed to initialize MCP server"),
            Instant.EPOCH,
            Instant.EPOCH,
        )

        assertEquals(LlmProviderFailureCategory.OUTPUT_CONTRACT, output.providerFailure?.category)
        assertEquals(false, output.authEvidenceObserved)
    }

    @Test
    fun parseCodex_observesAuthEvidenceForSchemaDriftWhenStdoutCarriesAShortKnownAuthText() {
        // "Not logged in" は CODEX_STDERR_AUTH_FAILURES の2長文とは異なる、
        // knownCompatibilityFailureCategory() 由来の短い既知文言（Finding 1 の回帰防止）
        val output = DefaultLlmOutputParser().parse(
            request(LlmProvider.CODEX),
            command(Files.createTempDirectory("codex-evidence-schema-drift-stdout-evidence-test")),
            processResult("Not logged in - mcp handshake failed: unexpected token", exitCode = 1),
            Instant.EPOCH,
            Instant.EPOCH,
        )

        assertEquals(LlmProviderFailureCategory.OUTPUT_CONTRACT, output.providerFailure?.category)
        assertEquals(true, output.authEvidenceObserved)
    }

    @Test
    fun parseCodex_observesAuthEvidenceWhenOnlyStderrCarriesAKnownAuthTextAlongsideUnrelatedStdoutGarbage() {
        // exitCode=0 にすることで、旧来の stderrAuthFailure（exitCode!=0 前提の完全一致判定）を
        // 経由しない形で、新設の evidence 追跡（stdout/stderr 全文への .contains() 検査）が
        // 単独で authEvidenceObserved を true にすることを確認する
        val output = DefaultLlmOutputParser().parse(
            request(LlmProvider.CODEX),
            command(Files.createTempDirectory("codex-evidence-stderr-only-test")),
            processResult("unrelated diagnostic garbage", exitCode = 0)
                .copy(stderr = CODEX_STDERR_AUTH_FAILURES.first()),
            Instant.EPOCH,
            Instant.EPOCH,
        )

        assertEquals(LlmProviderFailureCategory.OUTPUT_CONTRACT, output.providerFailure?.category)
        assertEquals(true, output.authEvidenceObserved)
    }

    @Test
    fun parseClaudeMapsSupportedStructuredFailureCodes() {
        val cases = mapOf(
            "authentication_error" to LlmProviderFailureCategory.AUTHENTICATION,
            "session_limit" to LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT,
            "quota_exhausted" to LlmProviderFailureCategory.QUOTA_EXHAUSTED,
        )

        cases.forEach { (code, category) ->
            val output = DefaultLlmOutputParser().parse(
                request(LlmProvider.CLAUDE),
                command(Files.createTempDirectory("claude-failure-output-test")),
                processResult("""{"type":"result","subtype":"$code","is_error":true,"result":"failed"}"""),
                Instant.EPOCH,
                Instant.EPOCH,
            )

            assertEquals(category, output.providerFailure?.category)
        }
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
        toolPolicy = ToolPolicy(emptySet(), emptyList()),
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

private fun failedProcess(stderr: String) = processResult("", 1).copy(stderr = stderr)
