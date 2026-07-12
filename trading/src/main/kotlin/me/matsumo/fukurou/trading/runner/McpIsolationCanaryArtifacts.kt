package me.matsumo.fukurou.trading.runner

import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmMcpServerConfig
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.McpLaunchManifestWriter
import java.nio.file.Path
import java.time.Duration

/** exact-image canary が production writer/renderer artifact を生成する内部 entrypoint。 */
fun main(args: Array<String>) {
    require(System.getenv(CANARY_ARTIFACT_ENV)?.toBooleanStrictOrNull() == true) {
        "MCP isolation canary artifact generation is disabled."
    }
    val phase = LlmInvocationPhase.valueOf(requireNotNull(args.getOrNull(0)))
    val provider = LlmProvider.valueOf(requireNotNull(args.getOrNull(1)))
    require(phase == LlmInvocationPhase.PROPOSER || phase == LlmInvocationPhase.FALSIFIER)
    generateArtifacts(phase, provider)
}

private fun generateArtifacts(phase: LlmInvocationPhase, provider: LlmProvider) {
    val config = TradingBotConfig.fromEnvironment()
    val shortTools = when (phase) {
        LlmInvocationPhase.PROPOSER -> CANONICAL_PROPOSER_MCP_TOOL_NAMES
        LlmInvocationPhase.FALSIFIER -> CANONICAL_FALSIFIER_MCP_TOOL_NAMES
        LlmInvocationPhase.PRE_FILTER,
        LlmInvocationPhase.REFLECTION,
        LlmInvocationPhase.EVALUATION_REPORT,
        -> error("Unsupported canary phase.")
    }
    val context = DecisionRunContext(
        decisionRunId = CANARY_INVOCATION_ID,
        llmProvider = provider.name.lowercase(),
        promptHash = "fixture",
        systemPromptVersion = "fixture",
        marketSnapshotId = "fixture",
    )
    val capability = McpLaunchManifestWriter().write(
        invocationId = CANARY_INVOCATION_ID,
        phase = phase,
        context = context,
        allowedTools = shortTools.toList(),
        databaseUrl = requireNotNull(System.getenv("DB_URL")),
        databaseUser = requireNotNull(System.getenv("FUKUROU_MCP_DB_USER")),
        gmoPublicBaseUrl = requireNotNull(System.getenv("FUKUROU_GMO_PUBLIC_BASE_URL")),
        runtimeEnvironment = RuntimeConfigCatalog.runtimeEnvironment(config),
        timeout = Duration.ofMinutes(5),
        totalToolCallLimit = config.runner.maxToolCallsPerRun,
        actToolCallLimit = config.runner.maxActToolCallsPerRun,
    )
    val serverName = DEFAULT_RUNNER_MCP_SERVER_NAME
    val allowedTools = shortTools.map { tool -> "mcp__${serverName}__$tool" }
    val request = LlmInvocationRequest(
        invocationId = CANARY_INVOCATION_ID,
        provider = provider,
        phase = phase,
        prompt = "deterministic canary fixture",
        timeout = Duration.ofMinutes(5),
        workingDirectory = Path.of("/tmp/fukurou-canary-work"),
        decisionRunContext = context,
        mcpServer = LlmMcpServerConfig(
            name = serverName,
            command = DEFAULT_RUNNER_MCP_SERVER_COMMAND,
            manifestId = capability.id,
            manifestPath = capability.path,
            autoApprovedTools = emptyList(),
        ),
        environment = System.getenv(),
        allowedTools = allowedTools,
        effort = LlmEffort.LOW,
    )
    val rendered = DefaultLlmCommandRenderer().render(request).getOrThrow()

    println("MANIFEST_ID=${capability.id}")
    rendered.cleanupPaths.forEach { path -> println("ARTIFACT=$path") }
}

private const val CANARY_ARTIFACT_ENV = "FUKUROU_MCP_CANARY_ARTIFACT_GENERATOR"
private const val CANARY_INVOCATION_ID = "mcp-canary-run"
