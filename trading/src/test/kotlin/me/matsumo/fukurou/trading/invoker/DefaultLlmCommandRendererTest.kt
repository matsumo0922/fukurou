package me.matsumo.fukurou.trading.invoker

import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.config.LlmModelConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun configFromEnvironment_usesRuntimeModelsAheadOfLegacyEnvironment() {
        val environment = mapOf(
            FUKUROU_CLAUDE_MODEL_ENV to "claude-legacy-env-model",
            FUKUROU_CODEX_MODEL_ENV to "codex-legacy-env-model",
        )

        val runtimeConfig = LlmCommandRendererConfig.fromEnvironment(
            environment = environment,
            runtimeModels = LlmModelConfig(
                claudeModel = "claude-runtime-model",
                codexModel = "codex-runtime-model",
            ),
        )
        val unsetRuntimeConfig = LlmCommandRendererConfig.fromEnvironment(
            environment = environment,
            runtimeModels = LlmModelConfig(),
        )

        assertEquals("claude-runtime-model", runtimeConfig.claudeModel)
        assertEquals("codex-runtime-model", runtimeConfig.codexModel)
        assertNull(unsetRuntimeConfig.claudeModel)
        assertNull(unsetRuntimeConfig.codexModel)
    }

    @Test
    fun renderRoleAssignmentWithoutModel_ignoresProviderModelFallback() {
        val renderer = DefaultLlmCommandRenderer(
            LlmCommandRendererConfig(
                claudeModel = "legacy-claude-model",
                codexModel = "legacy-codex-model",
            ),
        )

        val claudeCommand = renderer.render(
            request(LlmProvider.CLAUDE, LlmInvocationPhase.REFLECTION, null)
                .copy(useConfiguredModelFallback = false),
        ).getOrThrow()
        val codexCommand = renderer.render(
            request(LlmProvider.CODEX, LlmInvocationPhase.REFLECTION, null)
                .copy(useConfiguredModelFallback = false),
        ).getOrThrow()

        assertFalse(claudeCommand.args.contains("legacy-claude-model"))
        assertFalse(codexCommand.args.contains("legacy-codex-model"))
        claudeCommand.deleteCleanupPaths()
        codexCommand.deleteCleanupPaths()
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
        assertTrue(configContent.contains("command = \"/usr/local/libexec/fukurou-mcp-run\""))

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
    fun renderClaude_withMcpKeepsToolSearchAndMcpPermissionArgs() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PROPOSER,
            mcpServerName = "custom-mcp",
        )

        val command = renderer.render(request).getOrThrow()
        val mcpConfigIndex = command.args.indexOf("--mcp-config")
        val allowedToolsIndex = command.args.indexOf("--allowedTools")
        val toolsIndex = command.args.indexOf("--tools")

        assertNotEquals(-1, mcpConfigIndex)
        assertTrue(command.args.contains("--strict-mcp-config"))
        assertNotEquals(-1, allowedToolsIndex)
        assertTrue(command.args[allowedToolsIndex + 1].contains("mcp__custom-mcp__get_ticker"))
        assertTrue(command.args[allowedToolsIndex + 1].contains("mcp__custom-mcp__submit_decision"))
        assertEquals(1, command.args.count { argument -> argument == "--tools" })
        assertNotEquals(-1, toolsIndex)
        assertEquals("ToolSearch", command.args[toolsIndex + 1])
        assertFalse(command.args.contains("--bare"))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderClaude_withoutMcpDisablesToolsAndMcpDiscovery() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.REFLECTION,
            mcpServerName = null,
        )

        val command = renderer.render(request).getOrThrow()
        val mcpConfigPath = Path.of(command.args[command.args.indexOf("--mcp-config") + 1])
        val allowedToolsIndex = command.args.indexOf("--allowedTools")
        val toolsIndex = command.args.indexOf("--tools")

        assertFalse(command.args.contains("--bare"))
        assertTrue(command.args.contains("--strict-mcp-config"))
        assertEquals("""{"mcpServers":{}}""", Files.readString(mcpConfigPath))
        assertEquals("", command.args[allowedToolsIndex + 1])
        assertEquals(1, command.args.count { argument -> argument == "--tools" })
        assertNotEquals(-1, toolsIndex)
        assertEquals("", command.args[toolsIndex + 1])
        assertTrue(command.cleanupPaths.contains(mcpConfigPath))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderClaude_withoutMcpAndAuthFailsTypedBeforeStart() {
        val request = request(
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.REFLECTION,
            mcpServerName = null,
            environment = mapOf(HOME_ENV to "/missing-claude-home"),
        )

        val failure = DefaultLlmCommandRenderer().render(request).exceptionOrNull()

        assertEquals(
            LlmProviderFailureCategory.AUTHENTICATION,
            (failure as LlmProviderContractException).providerFailure.category,
        )
    }

    @Test
    fun renderRejectsMissingAndArbitraryProposerToolPolicy() {
        val canonical = request(LlmProvider.CLAUDE, LlmInvocationPhase.PROPOSER, "fukurou-mcp")
        val missingRequired = canonical.copy(toolPolicy = ToolPolicy(emptySet(), canonical.allowedTools))
        val arbitrary = canonical.copy(
            toolPolicy = ToolPolicy(
                canonical.toolPolicy.requiredTools,
                canonical.allowedTools + "mcp__fukurou-mcp__place_order",
            ),
        )

        assertTrue(DefaultLlmCommandRenderer().render(missingRequired).isFailure)
        assertTrue(DefaultLlmCommandRenderer().render(arbitrary).isFailure)
    }

    @Test
    fun renderCodex_withoutMcpWritesEmptyConfig() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.REFLECTION,
            mcpServerName = null,
        )

        val command = renderer.render(request).getOrThrow()
        val codexHome = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV]))
        val configContent = Files.readString(codexHome.resolve(CODEX_CONFIG_FILE_NAME))

        assertEquals("", configContent)
        assertTrue(command.args.contains("--json"))
        assertTrue(command.args.contains("--skip-git-repo-check"))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderClaude_ignoresCodexAutoApprovedTools() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PROPOSER,
            mcpServerName = "custom-mcp",
            autoApprovedTools = listOf("submit_decision"),
        )

        val command = renderer.render(request).getOrThrow()
        val configPath = Path.of(command.args[command.args.indexOf("--mcp-config") + 1])
        val configContent = Files.readString(configPath)

        assertFalse(configContent.contains("approval_mode"))
        assertFalse(configContent.contains("tools"))

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
    fun renderCodex_includesSkipGitRepoCheckAndAutoApprovesOnlyRequestedWriteTool() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "fukurou-mcp",
            autoApprovedTools = listOf("submit_falsification"),
        )

        val command = renderer.render(request).getOrThrow()
        val codexHome = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV]))
        val configContent = Files.readString(codexHome.resolve(CODEX_CONFIG_FILE_NAME))

        assertTrue(command.args.contains("--skip-git-repo-check"))
        assertTrue(configContent.contains("required = true"))
        assertTrue(configContent.contains("[mcp_servers.\"fukurou-mcp\".tools.\"submit_falsification\"]"))
        assertTrue(configContent.contains("approval_mode = \"approve\""))
        assertFalse(configContent.contains("[mcp_servers.\"fukurou-mcp\".tools.\"get_ticker\"]"))
        assertFalse(configContent.contains("default_tools_approval_mode"))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderCodex_deduplicatesStructuredAndRepositoryFlagsFromConfiguredCommonArgs() {
        val renderer = DefaultLlmCommandRenderer(
            config = LlmCommandRendererConfig(
                codexCommonArgs = listOf("--json", "--skip-git-repo-check", "--headless-test"),
            ),
        )
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "fukurou-mcp",
        )

        val command = renderer.render(request).getOrThrow()
        val skipGitRepoCheckCount = command.args.count { argument ->
            argument == "--skip-git-repo-check"
        }
        val jsonCount = command.args.count { argument -> argument == "--json" }

        assertEquals(1, skipGitRepoCheckCount)
        assertEquals(1, jsonCount)
        assertTrue(command.args.contains("--headless-test"))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderCodex_emptyAutoApprovedToolsPreservesConfigWithoutToolStanza() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            autoApprovedTools = emptyList(),
        )

        val command = renderer.render(request).getOrThrow()
        val codexHome = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV]))
        val configContent = Files.readString(codexHome.resolve(CODEX_CONFIG_FILE_NAME))
        val expectedConfigContent = """
            |[mcp_servers."custom-mcp"]
            |command = "/usr/local/libexec/fukurou-mcp-run"
            |args = ["0123456789abcdef0123456789abcdef0123456789abcdef"]
            |required = true
            |
        """.trimMargin()

        assertEquals(expectedConfigContent, configContent)

        command.deleteCleanupPaths()
    }

    @Test
    fun renderCodex_requiresMcpAndForwardsOnlyDeclaredEnvironmentVariables() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            environment = mapOf("FUKUROU_RECORD_PATH" to "/tmp/record", "IGNORED" to "value"),
            forwardedEnvironmentVariables = listOf("FUKUROU_RECORD_PATH"),
        )

        val command = renderer.render(request).getOrThrow()
        val codexHome = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV]))
        val configContent = Files.readString(codexHome.resolve(CODEX_CONFIG_FILE_NAME))

        assertTrue(configContent.contains("required = true"))
        assertTrue(configContent.contains("env_vars = [\"FUKUROU_RECORD_PATH\"]"))
        assertFalse(configContent.contains("IGNORED"))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderCodex_rejectsMissingOrSensitiveForwardedEnvironmentVariables() {
        val renderer = DefaultLlmCommandRenderer()
        val missing = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            forwardedEnvironmentVariables = listOf("FUKUROU_RECORD_PATH"),
        )
        val sensitive = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            environment = mapOf("FUKUROU_SESSION_TOKEN" to "value"),
            forwardedEnvironmentVariables = listOf("FUKUROU_SESSION_TOKEN"),
        )

        assertTrue(renderer.render(missing).isFailure)
        assertTrue(renderer.render(sensitive).isFailure)
    }

    @Test
    fun renderCodex_autoApprovedToolsWorkWithoutEnvironmentTable() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            autoApprovedTools = listOf("submit_falsification"),
        )

        val command = renderer.render(request).getOrThrow()
        val codexHome = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV]))
        val configContent = Files.readString(codexHome.resolve(CODEX_CONFIG_FILE_NAME))

        assertFalse(configContent.contains("[mcp_servers.\"custom-mcp\".env]"))
        assertTrue(configContent.contains("[mcp_servers.\"custom-mcp\".tools.\"submit_falsification\"]"))
        assertTrue(configContent.contains("approval_mode = \"approve\""))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderCodex_writesLiteralEnvironmentToMcpEnvTableOnlyNotCliBodyEnvironment() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            literalEnvironmentVariables = mapOf("DB_PASSWORD" to "literal-db-password-fixture"),
        )

        val command = renderer.render(request).getOrThrow()
        val codexHome = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV]))
        val configContent = Files.readString(codexHome.resolve(CODEX_CONFIG_FILE_NAME))

        assertTrue(configContent.contains("[mcp_servers.\"custom-mcp\".env]"))
        assertTrue(configContent.contains("\"DB_PASSWORD\" = \"literal-db-password-fixture\""))
        assertFalse(command.environment.containsKey("DB_PASSWORD"))
        assertFalse(command.environment.values.any { value -> value.contains("literal-db-password-fixture") })

        command.deleteCleanupPaths()
    }

    @Test
    fun renderCodex_createsUniquePerRunHomeAndCopiesOnlyAuthJson() {
        val authSourceHome = Files.createTempDirectory("fukurou-codex-auth-source")
        val authFile = authSourceHome.resolve(CODEX_AUTH_FILE_NAME)
        Files.writeString(authFile, """{"token":"persistent-auth-token"}""")
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            environment = mapOf(CODEX_HOME_ENV to authSourceHome.toString()),
            autoApprovedTools = listOf("submit_falsification"),
        )

        val firstCommand = renderer.render(request).getOrThrow()
        val secondCommand = renderer.render(request).getOrThrow()
        val firstHome = Path.of(assertNotNull(firstCommand.environment[CODEX_HOME_ENV]))
        val secondHome = Path.of(assertNotNull(secondCommand.environment[CODEX_HOME_ENV]))
        val configPath = firstHome.resolve(CODEX_CONFIG_FILE_NAME)
        val configContent = Files.readString(configPath)

        assertNotEquals(firstHome, secondHome)
        assertNotEquals(authSourceHome, firstHome)
        assertTrue(configContent.contains("[mcp_servers.\"custom-mcp\"]"))
        assertTrue(configContent.contains("[mcp_servers.\"custom-mcp\".tools.\"submit_falsification\"]"))
        assertEquals("""{"token":"persistent-auth-token"}""", Files.readString(firstHome.resolve(CODEX_AUTH_FILE_NAME)))

        firstCommand.deleteCleanupPaths()
        secondCommand.deleteCleanupPaths()

        assertEquals("""{"token":"persistent-auth-token"}""", Files.readString(authFile))
        Files.deleteIfExists(authFile)
        Files.deleteIfExists(authSourceHome)
    }

    @Test
    fun renderClaude_writesMcpConfigToPrivateFileWithoutArgvSecret() {
        val secretValue = "renderer-db-password-fixture"
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PROPOSER,
            mcpServerName = "custom-mcp",
            environment = mapOf("DB_PASSWORD" to secretValue),
        )

        val command = renderer.render(request).getOrThrow()
        val joinedArgs = command.args.joinToString(" ")
        val configPath = Path.of(command.args[command.args.indexOf("--mcp-config") + 1])
        val configContent = Files.readString(configPath)

        assertFalse(joinedArgs.contains("secret-password"))
        assertFalse(configContent.contains("secret-password"))
        assertFalse(configContent.contains("DB_PASSWORD"))
        assertFalse(command.executable.contains(secretValue))
        assertFalse(command.args.any { argument -> argument.contains(secretValue) })
        assertFalse(command.environment.any { (key, value) -> key.contains(secretValue) || value.contains(secretValue) })
        assertFalse(configContent.contains(secretValue))
        assertTrue(Files.exists(configPath))

        command.deleteCleanupPaths()
    }

    @Test
    fun renderClaude_writesLiteralEnvironmentToMcpConfigOnlyNotCliBodyEnvironment() {
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PROPOSER,
            mcpServerName = "custom-mcp",
            literalEnvironmentVariables = mapOf("DB_PASSWORD" to "literal-db-password-fixture"),
        )

        val command = renderer.render(request).getOrThrow()
        val configPath = Path.of(command.args[command.args.indexOf("--mcp-config") + 1])
        val configContent = Files.readString(configPath)

        assertTrue(configContent.contains(""""env":{"DB_PASSWORD":"literal-db-password-fixture"}"""))
        assertFalse(command.environment.containsKey("DB_PASSWORD"))
        assertFalse(command.environment.values.any { value -> value.contains("literal-db-password-fixture") })

        command.deleteCleanupPaths()
    }

    @Test
    fun renderClaude_copiesCredentialsToPerRunConfigWithoutMutatingSource() {
        val sourceHome = Files.createTempDirectory("fukurou-claude-auth-source")
        val sourceDirectory = Files.createDirectories(sourceHome.resolve(".claude"))
        val sourceFile = sourceDirectory.resolve(".credentials.json")
        val sourceContent = """{"claudeAiOauth":{"accessToken":"fixture"}}"""
        Files.writeString(sourceFile, sourceContent)
        val command = DefaultLlmCommandRenderer().render(
            request(
                provider = LlmProvider.CLAUDE,
                phase = LlmInvocationPhase.PROPOSER,
                mcpServerName = "custom-mcp",
                environment = mapOf("HOME" to sourceHome.toString()),
            ),
        ).getOrThrow()
        val perRunDirectory = Path.of(assertNotNull(command.environment["CLAUDE_CONFIG_DIR"]))
        val copiedFile = perRunDirectory.resolve(".credentials.json")

        assertEquals(sourceContent, Files.readString(copiedFile))
        assertTrue(command.cleanupPaths.contains(copiedFile))
        command.deleteCleanupPaths()
        assertEquals(sourceContent, Files.readString(sourceFile))
        assertFalse(Files.exists(perRunDirectory))

        Files.deleteIfExists(sourceFile)
        Files.deleteIfExists(sourceDirectory)
        Files.deleteIfExists(sourceHome)
    }

    @Test
    fun renderClaude_copyFailureRemovesEveryGeneratedArtifactAndPreservesSource() {
        val sourceHome = Files.createTempDirectory("fukurou-claude-auth-failure")
        val sourceDirectory = Files.createDirectories(sourceHome.resolve(".claude"))
        val sourceFile = sourceDirectory.resolve(".credentials.json")
        val sourceContent = "fixture-source-content".toByteArray()
        Files.write(sourceFile, sourceContent)
        val directoriesBefore = claudeTemporaryDirectories()
        val renderer = DefaultLlmCommandRenderer(
            claudeAuthCopy = { _, _ -> throw java.nio.file.AccessDeniedException("synthetic-copy-target") },
        )

        val result = renderer.render(
            request(
                provider = LlmProvider.CLAUDE,
                phase = LlmInvocationPhase.PROPOSER,
                mcpServerName = null,
                environment = mapOf("HOME" to sourceHome.toString()),
            ),
        )

        assertTrue(result.isFailure)
        assertEquals(directoriesBefore, claudeTemporaryDirectories())
        assertTrue(sourceContent.contentEquals(Files.readAllBytes(sourceFile)))

        Files.deleteIfExists(sourceFile)
        Files.deleteIfExists(sourceDirectory)
        Files.deleteIfExists(sourceHome)
    }

    @Test
    fun renderCodex_writesMcpConfigToPrivateCodexHomeWithoutArgvSecret() {
        val secretValue = "renderer-db-password-fixture"
        val renderer = DefaultLlmCommandRenderer()
        val request = request(
            provider = LlmProvider.CODEX,
            phase = LlmInvocationPhase.FALSIFIER,
            mcpServerName = "custom-mcp",
            environment = mapOf("DB_PASSWORD" to secretValue),
        )

        val command = renderer.render(request).getOrThrow()
        val joinedArgs = command.args.joinToString(" ")
        val codexHome = Path.of(assertNotNull(command.environment[CODEX_HOME_ENV]))
        val configContent = Files.readString(codexHome.resolve("config.toml"))

        assertFalse(joinedArgs.contains("secret-password"))
        assertFalse(command.environment.containsKey("DB_PASSWORD"))
        assertFalse(configContent.contains("secret-password"))
        assertFalse(configContent.contains("DB_PASSWORD"))
        assertFalse(command.executable.contains(secretValue))
        assertFalse(command.args.any { argument -> argument.contains(secretValue) })
        assertFalse(command.environment.any { (key, value) -> key.contains(secretValue) || value.contains(secretValue) })
        assertFalse(configContent.contains(secretValue))

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
        val privateConfigFile = privateCodexHome.resolve(CODEX_CONFIG_FILE_NAME)
        val copiedAuthContent = Files.readString(copiedAuthFile)

        assertNotEquals(parentCodexHome, privateCodexHome)
        assertFalse(joinedArgs.contains("test-auth-token"))
        assertEquals("""{"token":"test-auth-token"}""", copiedAuthContent)
        assertTrue(Files.exists(privateConfigFile))
        assertTrue(command.cleanupPaths.contains(privateConfigFile))
        assertTrue(command.cleanupPaths.contains(copiedAuthFile))
        assertTrue(command.cleanupPaths.contains(privateCodexHome))

        command.deleteCleanupPaths()

        assertFalse(Files.exists(copiedAuthFile))
        assertFalse(Files.exists(privateConfigFile))
        assertFalse(Files.exists(privateCodexHome))

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
                claudeCommonArgs = listOf("--tools", "default"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                claudeCommonArgs = listOf("--bare"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                claudeCommonArgs = listOf("--settings", "unsafe.json"),
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
        assertFailsWith<IllegalArgumentException> {
            LlmCommandRendererConfig(
                codexCommonArgs = listOf("--ephemeral"),
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
                codexCommandTemplate = listOf("docker", "run", "--network", "host", "codex-image", "codex"),
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
        LlmCommandRendererConfig(
            codexCommandTemplate = listOf("docker", "run", "--network", "none", "codex-image", "codex"),
            codexFalsifierArgs = listOf("--yolo"),
        )
    }

    private fun request(
        provider: LlmProvider,
        phase: LlmInvocationPhase,
        mcpServerName: String?,
        allowedTools: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        autoApprovedTools: List<String> = emptyList(),
        forwardedEnvironmentVariables: List<String> = emptyList(),
        literalEnvironmentVariables: Map<String, String> = emptyMap(),
    ): LlmInvocationRequest {
        val enabledTools = if (mcpServerName == null) {
            allowedTools
        } else {
            allowedTools.ifEmpty {
                McpToolContractCatalog.toolsFor(phase).map { tool -> "mcp__${mcpServerName}__$tool" }
            }
        }
        val needsTestClaudeAuth = provider == LlmProvider.CLAUDE && mcpServerName == null && environment.isEmpty()
        val effectiveEnvironment = if (needsTestClaudeAuth) {
            val home = Files.createTempDirectory("fukurou-test-claude-home")
            Files.createDirectories(home.resolve(".claude"))
            Files.writeString(home.resolve(".claude/.credentials.json"), "{}")
            mapOf(HOME_ENV to home.toString())
        } else {
            environment
        }
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
            mcpServer = mcpServerName?.let { serverName ->
                LlmMcpServerConfig(
                    name = serverName,
                    command = "/usr/local/libexec/fukurou-mcp-run",
                    manifestId = "0123456789abcdef0123456789abcdef0123456789abcdef",
                    manifestPath = Files.createTempFile("fukurou-test-manifest-", ".json"),
                    autoApprovedTools = autoApprovedTools,
                    forwardedEnvironmentVariables = forwardedEnvironmentVariables,
                    literalEnvironmentVariables = literalEnvironmentVariables,
                )
            },
            environment = effectiveEnvironment,
            toolPolicy = if (mcpServerName == null) {
                ToolPolicy(emptySet(), enabledTools)
            } else {
                McpToolContractCatalog.canonicalPolicy(phase, enabledTools)
            },
        )
    }
}

private fun RenderedLlmCommand.deleteCleanupPaths() {
    cleanupPaths.forEach { path -> Files.deleteIfExists(path) }
}

private fun claudeTemporaryDirectories(): Set<Path> {
    return Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths
            .filter { path -> path.fileName.toString().startsWith("fukurou-llm-config-") }
            .toList()
            .toSet()
    }
}
