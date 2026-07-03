package me.matsumo.fukurou.trading.invoker

import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DefaultLlmCommandRenderer の provider config 境界を検証するテスト。
 */
class DefaultLlmCommandRendererTest {

    @Test
    fun renderCodex_usesConfiguredCommandTemplateModelAndRequestMcpServerName() {
        val renderer = DefaultLlmCommandRenderer(
            config = LlmCommandRendererConfig(
                codexCommandTemplate = listOf("docker", "run", "--rm", "codex-image", "codex"),
                codexModel = "gpt-5-codex-test",
                codexCommonArgs = listOf("--headless-test"),
                codexFalsifierArgs = listOf("--sandbox-test"),
            ),
        )
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
        )

        val command = renderer.render(request).getOrThrow()
        val joinedArgs = command.args.joinToString(" ")

        assertEquals("docker", command.executable)
        assertEquals(listOf("run", "--rm", "codex-image", "codex", "exec"), command.args.take(5))
        assertTrue(joinedArgs.contains("-m gpt-5-codex-test"))
        assertTrue(joinedArgs.contains("--headless-test"))
        assertTrue(joinedArgs.contains("--sandbox-test"))
        assertTrue(joinedArgs.contains("mcp_servers.custom-mcp.command"))
        assertFalse(joinedArgs.contains("mcp_servers.fukurou-mcp.command"))
    }

    @Test
    fun renderClaude_usesConfiguredCommandTemplateModelAndAllowedTools() {
        val renderer = DefaultLlmCommandRenderer(
            config = LlmCommandRendererConfig(
                claudeCommandTemplate = listOf("sandbox", "claude"),
                claudeModel = "claude-test-model",
                claudeCommonArgs = listOf("--headless-test"),
            ),
        )
        val request = request(
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PROPOSER,
            mcpServerName = "custom-mcp",
            allowedTools = listOf("mcp__custom-mcp__submit_decision"),
        )

        val command = renderer.render(request).getOrThrow()
        val joinedArgs = command.args.joinToString(" ")

        assertEquals("sandbox", command.executable)
        assertEquals("claude", command.args.first())
        assertTrue(joinedArgs.contains("--model claude-test-model"))
        assertTrue(joinedArgs.contains("--headless-test"))
        assertTrue(joinedArgs.contains("custom-mcp"))
        assertTrue(joinedArgs.contains("mcp__custom-mcp__submit_decision"))
    }

    @Test
    fun renderCodex_defaultFalsifierArgsDoNotBypassSandbox() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
        )

        val command = renderer.render(request).getOrThrow()
        val joinedArgs = command.args.joinToString(" ")

        assertFalse(joinedArgs.contains("--dangerously-bypass-approvals-and-sandbox"))
    }

    private fun request(
        provider: LlmProvider,
        phase: LlmInvocationPhase,
        mcpServerName: String,
        allowedTools: List<String> = emptyList(),
    ): LlmInvocationRequest {
        return LlmInvocationRequest(
            invocationId = "invocation-test",
            provider = provider,
            phase = phase,
            prompt = "prompt",
            timeout = Duration.ofSeconds(1),
            workingDirectory = Path.of(".").toAbsolutePath().normalize(),
            decisionRunContext = DecisionRunContext(
                decisionRunId = "invocation-test",
                llmProvider = provider.name.lowercase(),
                promptHash = "hash",
                systemPromptVersion = "system-prompt-v1",
                marketSnapshotId = "snapshot",
            ),
            mcpServer = LlmMcpServerConfig(
                name = mcpServerName,
                command = "java",
                args = listOf("-jar", "mcp.jar"),
                environment = mapOf("FUKUROU_INVOCATION_ID" to "invocation-test"),
            ),
            environment = emptyMap(),
            allowedTools = allowedTools,
        )
    }
}
