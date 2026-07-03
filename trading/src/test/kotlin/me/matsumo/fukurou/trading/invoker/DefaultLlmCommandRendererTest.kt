package me.matsumo.fukurou.trading.invoker

import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DefaultLlmCommandRenderer の provider config 境界を検証するテスト。
 */
class DefaultLlmCommandRendererTest {

    @Test
    fun configFromEnvironment_splitsQuotedCommandTemplates() {
        val config = LlmCommandRendererConfig.fromEnvironment(
            mapOf(
                FUKUROU_CLAUDE_COMMAND_TEMPLATE_ENV to """docker run "claude image" claude""",
                FUKUROU_CODEX_COMMAND_TEMPLATE_ENV to """docker run 'codex image' codex""",
            ),
        )

        assertEquals(listOf("docker", "run", "claude image", "claude"), config.claudeCommandTemplate)
        assertEquals(listOf("docker", "run", "codex image", "codex"), config.codexCommandTemplate)
    }

    @Test
    fun renderCodex_usesConfiguredCommandTemplateModelAndRequestMcpServerName() {
        val renderer = DefaultLlmCommandRenderer(
            config = LlmCommandRendererConfig(
                codexCommandTemplate = listOf("docker", "run", "--rm", "codex-image", "codex"),
                codexModel = "gpt-5-codex-test",
                codexCommonArgs = listOf("--headless-test"),
                codexFalsifierArgs = listOf("--yolo"),
            ),
        )
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
        )

        val command = renderer.render(request).getOrThrow()
        val joinedArgs = command.args.joinToString(" ")
        val configPath = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV])).resolve("config.toml")
        val configContent = Files.readString(configPath)
        val userArgIndex = command.args.indexOf("--headless-test")
        val sandboxArgIndex = command.args.indexOf("--sandbox")

        assertEquals("docker", command.executable)
        assertEquals(listOf("run", "--rm", "codex-image", "codex", "exec"), command.args.take(5))
        assertTrue(joinedArgs.contains("-m gpt-5-codex-test"))
        assertTrue(joinedArgs.contains("--headless-test"))
        assertTrue(joinedArgs.contains("--yolo"))
        assertTrue(userArgIndex < sandboxArgIndex)
        assertFalse(joinedArgs.contains("mcp_servers.fukurou-mcp.command"))
        assertTrue(configContent.contains("[mcp_servers.\"custom-mcp\"]"))
        assertTrue(configContent.contains("command = \"java\""))

        command.deleteCleanupPaths()
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
        val userArgIndex = command.args.indexOf("--headless-test")
        val permissionArgIndex = command.args.indexOf("--permission-mode")

        assertEquals("sandbox", command.executable)
        assertEquals("claude", command.args.first())
        assertTrue(joinedArgs.contains("--model claude-test-model"))
        assertTrue(joinedArgs.contains("--headless-test"))
        assertTrue(userArgIndex < permissionArgIndex)
        assertTrue(joinedArgs.contains("custom-mcp"))
        assertTrue(joinedArgs.contains("mcp__custom-mcp__submit_decision"))

        command.deleteCleanupPaths()
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

        command.deleteCleanupPaths()
    }

    @Test
    fun renderClaude_writesMcpConfigToPrivateFileWithoutArgvSecret() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PROPOSER,
            mcpServerName = "custom-mcp",
            mcpEnvironment = mapOf(
                "DB_URL" to "jdbc:postgresql://localhost:5432/fukurou",
                "DB_PASSWORD" to "secret-password",
            ),
        )

        val command = renderer.render(request).getOrThrow()
        val joinedArgs = command.args.joinToString(" ")
        val configPath = Path.of(command.args[command.args.indexOf("--mcp-config") + 1])
        val configContent = Files.readString(configPath)

        assertFalse(joinedArgs.contains("secret-password"))
        assertTrue(configContent.contains("secret-password"))
        assertTrue(Files.exists(configPath))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderCodex_writesMcpConfigToPrivateCodexHomeWithoutArgvSecret() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            mcpEnvironment = mapOf(
                "DB_URL" to "jdbc:postgresql://localhost:5432/fukurou",
                "DB_PASSWORD" to "secret-password",
            ),
        )

        val command = renderer.render(request).getOrThrow()
        val joinedArgs = command.args.joinToString(" ")
        val codexHome = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV]))
        val configContent = Files.readString(codexHome.resolve("config.toml"))

        assertFalse(joinedArgs.contains("secret-password"))
        assertFalse(command.environment.containsKey("DB_PASSWORD"))
        assertTrue(configContent.contains("secret-password"))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderCodex_copiesParentAuthJsonToPrivateCodexHome() {
        val parentCodexHome = Files.createTempDirectory("fukurou-parent-codex-home")
        val parentAuthFile = parentCodexHome.resolve(CODEX_AUTH_FILE_NAME)
        Files.writeString(parentAuthFile, """{"token":"test-auth-token"}""")
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            environment = mapOf(CODEX_HOME_ENV to parentCodexHome.toString()),
        )

        val command = renderer.render(request).getOrThrow()
        val joinedArgs = command.args.joinToString(" ")
        val privateCodexHome = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV]))
        val copiedAuthFile = privateCodexHome.resolve(CODEX_AUTH_FILE_NAME)
        val copiedAuthContent = Files.readString(copiedAuthFile)

        assertFalse(privateCodexHome == parentCodexHome)
        assertFalse(joinedArgs.contains("test-auth-token"))
        assertEquals("""{"token":"test-auth-token"}""", copiedAuthContent)

        command.deleteCleanupPaths()
        Files.deleteIfExists(parentAuthFile)
        Files.deleteIfExists(parentCodexHome)
    }

    @Test
    fun configRejectsCommonArgsThatOverrideSafetyBoundary() {
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                claudeCommonArgs = listOf("--allowedTools", "Bash"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                claudeCommonArgs = listOf("--mcp-config=unsafe.json"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommonArgs = listOf("-c", "approval_policy=\"on-request\""),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommonArgs = listOf("-csandbox_mode=\"danger-full-access\""),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommonArgs = listOf("--dangerously-bypass-approvals-and-sandbox"),
            )
        }
    }

    @Test
    fun configRestrictsCodexFalsifierArgsToExplicitSandboxOptIn() {
        val config = LlmCommandRendererConfig(
            codexCommandTemplate = listOf("docker", "run", "--rm", "codex-image", "codex"),
            codexFalsifierArgs = listOf("--dangerously-bypass-approvals-and-sandbox"),
        )

        assertEquals(listOf("--dangerously-bypass-approvals-and-sandbox"), config.codexFalsifierArgs)
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommandTemplate = listOf("codex"),
                codexFalsifierArgs = listOf("--yolo"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommandTemplate = listOf("docker", "run", "--rm", "codex-image", "codex"),
                codexFalsifierArgs = listOf("-c", "mcp_servers.unsafe.command=\"bash\""),
            )
        }
    }

    @Test
    fun configRejectsCodexYoloWithUnsafeContainerTemplateArgs() {
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommandTemplate = listOf("docker", "run", "--privileged", "codex-image", "codex"),
                codexFalsifierArgs = listOf("--yolo"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommandTemplate = listOf("docker", "run", "--network=host", "codex-image", "codex"),
                codexFalsifierArgs = listOf("--yolo"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommandTemplate = listOf("docker", "run", "-v", "/:/hostroot", "codex-image", "codex"),
                codexFalsifierArgs = listOf("--yolo"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommandTemplate = listOf(
                    "docker",
                    "run",
                    "--mount=type=bind,target=/hostroot,source=/",
                    "codex-image",
                    "codex",
                ),
                codexFalsifierArgs = listOf("--yolo"),
            )
        }
    }

    private fun request(
        provider: LlmProvider,
        phase: LlmInvocationPhase,
        mcpServerName: String,
        allowedTools: List<String> = emptyList(),
        mcpEnvironment: Map<String, String> = mapOf("FUKUROU_INVOCATION_ID" to "invocation-test"),
        environment: Map<String, String> = emptyMap(),
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
                environment = mcpEnvironment,
            ),
            environment = environment,
            allowedTools = allowedTools,
        )
    }
}

private fun RenderedLlmCommand.deleteCleanupPaths() {
    cleanupPaths.forEach { path -> Files.deleteIfExists(path) }
}
