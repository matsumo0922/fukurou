package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.LlmPhaseManifestRecorder
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.invoker.DEFAULT_MCP_MANIFEST_DIRECTORY
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmMcpServerConfig
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.McpLaunchManifest
import me.matsumo.fukurou.trading.invoker.McpLaunchManifestWriter
import me.matsumo.fukurou.trading.invoker.McpToolContractCatalog
import me.matsumo.fukurou.trading.invoker.ProcessScopedLlmCliVersionProbe
import me.matsumo.fukurou.trading.invoker.RenderedLlmCommand
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import java.math.BigDecimal
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/** exact-image canary が production writer/renderer artifact を生成する内部 entrypoint。 */
fun main(args: Array<String>) {
    require(System.getenv(CANARY_ARTIFACT_ENV)?.toBooleanStrictOrNull() == true) {
        "MCP isolation canary artifact generation is disabled."
    }
    if (args.firstOrNull() == CLEANUP_COMMAND) {
        cleanupArtifacts(args.drop(1).map(Path::of))

        return
    }
    if (args.firstOrNull() == GATEWAY_COMMAND) {
        serveGateway(requireNotNull(args.getOrNull(1)))

        return
    }
    if (args.firstOrNull() == GATEWAY_REJECTION_PROBE_COMMAND) {
        probeGatewayRejection(
            manifestId = requireNotNull(args.getOrNull(1)),
            mismatch = requireNotNull(args.getOrNull(2)),
        )

        return
    }
    if (args.firstOrNull() == IDENTITY_COMMAND) {
        recordCliIdentity(LlmProvider.valueOf(requireNotNull(args.getOrNull(1))))

        return
    }
    val phase = LlmInvocationPhase.valueOf(requireNotNull(args.getOrNull(0)))
    val provider = LlmProvider.valueOf(requireNotNull(args.getOrNull(1)))
    require(phase in CANARY_PHASES)
    generateArtifacts(phase, provider)
}

private fun probeGatewayRejection(manifestId: String, mismatch: String) {
    require(mismatch == MANIFEST_MISMATCH || mismatch == HASH_MISMATCH) { "Unknown gateway mismatch probe." }
    val manifestPath = Path.of(DEFAULT_MCP_MANIFEST_DIRECTORY, "$manifestId.json")
    val manifest = Json.decodeFromString<McpLaunchManifest>(Files.readString(manifestPath))
    val request = LlmSubmissionGatewayCodec.request(
        operation = "submit_decision",
        invocationId = manifest.invocationId,
        phase = LlmInvocationPhase.valueOf(manifest.phase),
        phaseManifestId = if (mismatch == MANIFEST_MISMATCH) "cross-manifest" else manifest.phaseManifestId,
        effectiveInvocationHash = if (mismatch == HASH_MISMATCH) "0".repeat(64) else manifest.effectiveInvocationHash,
        payload = LlmSubmissionGatewayCodec.encodeDecision(canaryNoTradeDecision()),
    )
    SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
        channel.connect(UnixDomainSocketAddress.of(manifestPath.resolveSibling("$manifestId.sock")))
        LlmSubmissionGatewayCodec.writeFrame(channel, request)
        val response = LlmSubmissionGatewayCodec.readFrame(channel)
        require(response["accepted"].toString() == "false") { "Cross-manifest gateway probe was accepted." }
    }
    println("GATEWAY_REJECTED=$mismatch")
}

private fun recordCliIdentity(provider: LlmProvider) = runBlocking {
    val runtime = me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory.fromEnvironment()
    try {
        val config = TradingBotConfig.fromEnvironment()
        val commandConfig = LlmCommandRendererConfig.fromEnvironment()
        val request = LlmInvocationRequest(
            invocationId = IDENTITY_INVOCATION_ID,
            provider = provider,
            phase = LlmInvocationPhase.PRE_FILTER,
            prompt = "exact-image identity canary",
            timeout = Duration.ofSeconds(30),
            workingDirectory = Path.of("/tmp"),
            decisionRunContext = DecisionRunContext.EMPTY,
            mcpServer = null,
            environment = System.getenv(),
            allowedTools = emptyList(),
        )
        val recorder = LlmPhaseManifestRecorder(
            repository = runtime.llmInputManifestRepository,
            cliVersionProbe = ProcessScopedLlmCliVersionProbe,
            runtimeConfigSnapshot = null,
            runtimeEnvironmentSnapshot = RuntimeConfigCatalog.runtimeEnvironment(config)
                .toSortedMap()
                .entries
                .joinToString("\n") { entry -> "${entry.key}=${entry.value}" },
            clock = Clock.systemUTC(),
            commandRendererConfig = commandConfig,
        )
        val saved = recorder.appendInput(request)

        println("PHASE_MANIFEST_ID=${saved.phaseManifestId}")
        println("CLI_IDENTITY=${saved.cliVersion}")
    } finally {
        runtime.close()
    }
}

private fun serveGateway(manifestId: String) {
    require(manifestId.matches(Regex("[0-9a-f]{48}"))) { "Canonical manifest ID is required." }
    val manifestPath = Path.of(DEFAULT_MCP_MANIFEST_DIRECTORY, "$manifestId.json")
    val manifest = Json.decodeFromString<McpLaunchManifest>(Files.readString(manifestPath))
    val socketPath = manifestPath.resolveSibling("$manifestId.sock")
    McpLaunchManifestWriter.bindSubmissionSocket(manifestPath, socketPath)
    val runtime = me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory.fromEnvironment()
    LlmDecisionSubmissionGateway.start(
        socketPath = socketPath,
        repository = runtime.decisionRepository,
        invocationId = manifest.invocationId,
        phase = LlmInvocationPhase.valueOf(manifest.phase),
        phaseManifestId = manifest.phaseManifestId,
        effectiveInvocationHash = manifest.effectiveInvocationHash,
    ).use { gateway ->
        println("GATEWAY_READY=$socketPath")
        System.out.flush()
        gateway.awaitCompletion()
    }
    runtime.close()
}

private fun cleanupArtifacts(paths: List<Path>) = runBlocking {
    require(paths.isNotEmpty()) { "At least one canary artifact is required." }
    val command = RenderedLlmCommand(
        executable = "/bin/true",
        args = emptyList(),
        environment = emptyMap(),
        workingDirectory = Path.of("/tmp"),
        timeout = Duration.ofSeconds(1),
        stdin = null,
        cleanupPaths = paths,
    )

    ShellProcessRunner().cleanup(command).getOrThrow()
}

private fun generateArtifacts(phase: LlmInvocationPhase, provider: LlmProvider) {
    val config = TradingBotConfig.fromEnvironment()
    val shortTools = when (phase) {
        LlmInvocationPhase.PROPOSER -> CANONICAL_PROPOSER_MCP_TOOL_NAMES
        LlmInvocationPhase.FALSIFIER -> CANONICAL_FALSIFIER_MCP_TOOL_NAMES
        LlmInvocationPhase.RISK_REDUCTION_ONLY -> McpToolContractCatalog.riskReductionTools
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

private fun canaryNoTradeDecision(): DecisionSubmission {
    return DecisionSubmission(
        invocationId = CANARY_INVOCATION_ID,
        llmProvider = "canary",
        promptHash = "fixture",
        systemPromptVersion = "fixture",
        marketSnapshotId = "fixture",
        action = DecisionAction.NO_TRADE,
        setupTags = emptyList(),
        estimatedWinProbability = BigDecimal.ZERO,
        expectedRMultiple = BigDecimal.ZERO,
        roundTripCostR = null,
        toolEvidenceIds = emptyList(),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = "canary fixture",
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = null,
        tradePlan = null,
    )
}

private const val CANARY_ARTIFACT_ENV = "FUKUROU_MCP_CANARY_ARTIFACT_GENERATOR"
private const val CLEANUP_COMMAND = "CLEANUP"
private const val GATEWAY_COMMAND = "GATEWAY"
private const val GATEWAY_REJECTION_PROBE_COMMAND = "GATEWAY_REJECTION_PROBE"
private const val IDENTITY_COMMAND = "IDENTITY"
private const val MANIFEST_MISMATCH = "MANIFEST"
private const val HASH_MISMATCH = "HASH"
private const val CANARY_INVOCATION_ID = "mcp-canary-run"
private const val IDENTITY_INVOCATION_ID = "mcp-canary-cli-identity"
private val CANARY_PHASES = setOf(
    LlmInvocationPhase.PROPOSER,
    LlmInvocationPhase.FALSIFIER,
    LlmInvocationPhase.RISK_REDUCTION_ONLY,
)
