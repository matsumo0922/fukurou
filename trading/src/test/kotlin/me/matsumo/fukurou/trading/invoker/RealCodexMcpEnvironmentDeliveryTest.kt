package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 実 Codex CLI を起動し、MCP subprocess が実際に受け取る環境変数を検証する回帰テスト。
 *
 * issue #288 Stage 3 の本番 incident で判明した通り、Codex は MCP subprocess の環境を
 * ゼロから再構築し、親プロセスの環境変数を暗黙に継承しない。`literalEnvironmentVariables`
 * に明示しない値（`FUKUROU_MCP_MANIFEST_DIRECTORY` など）は MCP subprocess から一切見えず、
 * config file の内容チェックだけの単体テストではこの欠落を検出できなかった。
 */
class RealCodexMcpEnvironmentDeliveryTest {
    @Test
    fun mcpSubprocessReceivesManifestDirectoryAndDbPasswordFromRealCodexCli() = runBlocking {
        val codexPath = resolveCodexBinary() ?: return@runBlocking
        val workingDirectory = Files.createTempDirectory("fukurou-codex-env-test")
        val stubScript = workingDirectory.resolve("stub-mcp-server.sh")
        val observedEnvFile = workingDirectory.resolve("observed-env.txt")
        Files.writeString(
            stubScript,
            """
            |#!/bin/sh
            |{
            |  echo "ARGS: ${'$'}@"
            |  env | sort
            |} > "$observedEnvFile"
            |exit 1
            """.trimMargin(),
        )
        stubScript.toFile().setExecutable(true)
        val manifestPlaceholder = Files.createTempFile("fukurou-test-manifest-", ".json")
        val phase = LlmInvocationPhase.PROPOSER
        val enabledTools = McpToolContractCatalog.toolsFor(phase).map { tool -> "mcp__fukurou-mcp__$tool" }

        val request = LlmInvocationRequest(
            invocationId = "codex-env-delivery-test",
            provider = LlmProvider.CODEX,
            phase = phase,
            prompt = "test prompt",
            timeout = Duration.ofSeconds(15),
            workingDirectory = workingDirectory,
            decisionRunContext = DecisionRunContext(
                decisionRunId = "codex-env-delivery-test",
                llmProvider = "codex",
                promptHash = "hash",
                systemPromptVersion = "system-prompt-v1",
                marketSnapshotId = "snapshot",
            ),
            mcpServer = LlmMcpServerConfig(
                name = "fukurou-mcp",
                command = stubScript.toString(),
                manifestId = "manifest-id-real-cli-test",
                manifestPath = manifestPlaceholder,
                forwardedEnvironmentVariables = listOf("FUKUROU_INVOCATION_ID"),
                literalEnvironmentVariables = mapOf(
                    "DB_PASSWORD" to "literal-db-password-real-cli-test",
                    "FUKUROU_MCP_MANIFEST_DIRECTORY" to "/run/fukurou/mcp-manifests-real-cli-test",
                ),
            ),
            environment = mapOf(
                "FUKUROU_INVOCATION_ID" to "codex-env-delivery-test",
                "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
                "HOME" to (System.getenv("HOME") ?: "/tmp"),
            ),
            toolPolicy = McpToolContractCatalog.canonicalPolicy(phase, enabledTools),
        )

        val renderer = DefaultLlmCommandRenderer(
            config = LlmCommandRendererConfig(codexCommandTemplate = listOf(codexPath)),
        )
        val command = renderer.render(request).getOrThrow()

        try {
            ShellProcessRunner(terminationGrace = Duration.ofSeconds(5)).run(command)

            val observedEnv = waitForFile(observedEnvFile, Duration.ofSeconds(10))
            assertTrue(
                observedEnv.contains("FUKUROU_MCP_MANIFEST_DIRECTORY=/run/fukurou/mcp-manifests-real-cli-test"),
                "real codex CLI did not deliver FUKUROU_MCP_MANIFEST_DIRECTORY to the MCP subprocess; " +
                    "observed env:\n$observedEnv",
            )
            assertTrue(
                observedEnv.contains("DB_PASSWORD=literal-db-password-real-cli-test"),
                "real codex CLI did not deliver DB_PASSWORD to the MCP subprocess; observed env:\n$observedEnv",
            )
        } finally {
            command.deleteCleanupPaths()
            workingDirectory.toFile().deleteRecursively()
        }
    }
}

private fun resolveCodexBinary(): String? {
    return runCatching {
        val process = ProcessBuilder("which", "codex").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        output.takeIf { exitCode == 0 && it.isNotBlank() }
    }.getOrNull()
}

private fun waitForFile(path: Path, timeout: Duration): String {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (Files.exists(path)) return Files.readString(path)
        Thread.sleep(100)
    }
    return if (Files.exists(path)) Files.readString(path) else ""
}

private fun RenderedLlmCommand.deleteCleanupPaths() {
    cleanupPaths.forEach { path -> File(path.toString()).delete() }
}
