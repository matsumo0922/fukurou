package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.invoker.CLAUDE_OUTPUT_ADAPTER_VERSION
import me.matsumo.fukurou.trading.invoker.CODEX_OUTPUT_ADAPTER_VERSION
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.DefaultLlmOutputParser
import me.matsumo.fukurou.trading.invoker.LlmArtifactCleanupException
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.LlmConfiguredModelSource
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmMcpServerConfig
import me.matsumo.fukurou.trading.invoker.LlmProcessStartedMarker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.LlmProviderContractException
import me.matsumo.fukurou.trading.invoker.LlmProviderFailureCategory
import me.matsumo.fukurou.trading.invoker.McpToolContractCatalog
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
import me.matsumo.fukurou.trading.invoker.isLlmProviderFailureMarker
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.system.exitProcess

/** pinned CLI acceptance canary の安全な失敗 code。 */
internal enum class CliAcceptanceFailureCode {
    AUTHENTICATION,
    RATE_OR_SESSION_LIMIT,
    QUOTA_EXHAUSTED,
    OUTPUT_CONTRACT,
    TIMEOUT,
    PROCESS_EXIT,
    CLEANUP,
    CONFIGURED_MODEL,
    OBSERVED_MODEL,
    PROBE_MARKER,
    TOOL_CALL,
    INVOCATION,
    IDENTITY,
}

/** provider/process failure と独立した artifact cleanup 結果。 */
internal enum class CliAcceptanceCleanupStatus {
    COMPLETED,
    FAILED,
}

/** raw provider output や例外 message を保持しない acceptance failure。 */
internal class CliAcceptanceFailure(
    val code: CliAcceptanceFailureCode,
    val phase: LlmInvocationPhase,
    private val iteration: Int,
    private val adapter: String,
    val cleanupStatus: CliAcceptanceCleanupStatus,
) : IllegalStateException("CLI acceptance failed closed.") {
    /** allowlist field だけの operator 向け結果。 */
    fun safeMessage() =
        "CLI_ACCEPTANCE_V1 FAIL code=$code phase=$phase iteration=$iteration adapter=$adapter cleanup=$cleanupStatus"
}

/** production invocation components を通して pinned CLI phase matrix を検証する driver。 */
internal class CliAcceptanceCanary(
    private val invoker: LlmInvoker,
    private val workingDirectory: Path,
    private val environment: Map<String, String>,
    private val fixtureCommand: String,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    /** complete matrix を1回または3回だけ直列実行する。 */
    suspend fun run(repetitions: Int): Result<Unit> {
        require(repetitions == 1 || repetitions == 3) { "CLI acceptance repetitions must be 1 or 3." }

        repeat(repetitions) { index ->
            val iteration = index + 1
            CANARY_PHASE_MATRIX.forEach { phase ->
                val invocation = runCatching { phase.toInvocation(iteration, tokenFactory()) }.getOrElse {
                    return Result.failure(failure(CliAcceptanceFailureCode.INVOCATION, phase, iteration))
                }
                val result = invoker.invoke(invocation.request).getOrElse { throwable ->
                    val primary = throwable.suppressed
                        .filterNot { failure -> failure === LlmProcessStartedMarker }
                        .filterIsInstance<LlmProviderContractException>()
                        .firstOrNull() ?: throwable
                    val code = (primary as? LlmProviderContractException)
                        ?.providerFailure
                        ?.category
                        ?.toCanaryCode()
                        ?: if (throwable is LlmArtifactCleanupException) {
                            CliAcceptanceFailureCode.CLEANUP
                        } else {
                            CliAcceptanceFailureCode.INVOCATION
                        }
                    val cleanupFailed = throwable is LlmArtifactCleanupException ||
                        throwable.suppressed.any { failure ->
                            failure !== LlmProcessStartedMarker && !failure.isLlmProviderFailureMarker()
                        } ||
                        cleanupRecord(invocation.recordPath)
                    return Result.failure(failure(code, phase, iteration, cleanupFailed))
                }
                val code = validationFailureCode(result, phase, invocation.recordPath)
                val cleanupFailed = result.cleanupFailure != null || cleanupRecord(invocation.recordPath)
                if (code != null || cleanupFailed) {
                    return Result.failure(
                        failure(code ?: CliAcceptanceFailureCode.CLEANUP, phase, iteration, cleanupFailed),
                    )
                }
            }
        }

        return Result.success(Unit)
    }

    private fun CanaryPhase.toInvocation(iteration: Int, token: String): CanaryInvocation {
        val invocationId = "cli-canary-${phase.name.lowercase()}-$iteration-${token.take(8)}"
        val enabledTools = McpToolContractCatalog.toolsFor(phase)
            .sorted()
            .map { tool -> "mcp__${CANARY_MCP_SERVER_NAME}__$tool" }
        val recordPath = if (phase in CANARY_MCP_PHASES) workingDirectory.resolve("$invocationId.calls") else null
        val mcpServer = if (phase in CANARY_MCP_PHASES) {
            LlmMcpServerConfig(
                name = CANARY_MCP_SERVER_NAME,
                command = fixtureCommand,
                manifestId = invocationId,
                manifestPath = workingDirectory.resolve("$invocationId.manifest"),
                autoApprovedTools = productionAutoApprovedTools(provider, enabledTools),
                forwardedEnvironmentVariables = if (provider == LlmProvider.CODEX) {
                    listOf(CLI_CANARY_RECORD_PATH_ENV)
                } else {
                    emptyList()
                },
            )
        } else {
            null
        }

        val request = LlmInvocationRequest(
            invocationId = invocationId,
            provider = provider,
            phase = phase,
            prompt = prompt,
            timeout = PHASE_TIMEOUT,
            workingDirectory = workingDirectory,
            decisionRunContext = DecisionRunContext.EMPTY,
            mcpServer = mcpServer,
            environment = recordPath?.let { environment + (CLI_CANARY_RECORD_PATH_ENV to it.toString()) } ?: environment,
            toolPolicy = McpToolContractCatalog.canonicalPolicy(phase, enabledTools),
            model = model,
            effort = effort,
            useConfiguredModelFallback = false,
        )
        return CanaryInvocation(request, recordPath)
    }

    private fun validationFailureCode(
        result: LlmInvocationResult,
        phase: CanaryPhase,
        recordPath: Path?,
    ): CliAcceptanceFailureCode? = processFailureCode(result)
        ?: modelFailureCode(result, phase)
        ?: semanticFailureCode(result, phase, recordPath)

    private fun processFailureCode(result: LlmInvocationResult): CliAcceptanceFailureCode? {
        result.providerFailure?.let { return it.category.toCanaryCode() }
        if (result.processResult.status == ProcessRunStatus.TIMED_OUT) return CliAcceptanceFailureCode.TIMEOUT
        if (result.processResult.exitCode != 0) return CliAcceptanceFailureCode.PROCESS_EXIT

        return null
    }

    private fun modelFailureCode(result: LlmInvocationResult, phase: CanaryPhase): CliAcceptanceFailureCode? {
        val configuredModelMatches = result.configuredModelIdentity.source == LlmConfiguredModelSource.REQUEST &&
            result.configuredModelIdentity.name == phase.model
        if (!configuredModelMatches) return CliAcceptanceFailureCode.CONFIGURED_MODEL

        val observedModelMatches = if (phase.provider == LlmProvider.CLAUDE) {
            result.observedModelIdentity?.name == phase.model
        } else {
            result.observedModelIdentity == null
        }
        return if (observedModelMatches) null else CliAcceptanceFailureCode.OBSERVED_MODEL
    }

    private fun semanticFailureCode(
        result: LlmInvocationResult,
        phase: CanaryPhase,
        recordPath: Path?,
    ): CliAcceptanceFailureCode? {
        if (result.responseText.trim() != RESPONSE_MARKER) return CliAcceptanceFailureCode.PROBE_MARKER
        if (recordPath != null && !hasCanonicalProbeCall(recordPath, phase.phase)) {
            return CliAcceptanceFailureCode.TOOL_CALL
        }

        return null
    }

    private fun hasCanonicalProbeCall(recordPath: Path, phase: LlmInvocationPhase): Boolean {
        val expected = "${phase.name}\t${cliCanaryProbeTool(phase)}"
        return runCatching { Files.readAllLines(recordPath).any { line -> line == expected } }.getOrDefault(false)
    }

    private fun cleanupRecord(recordPath: Path?) =
        recordPath?.let { path -> runCatching { Files.deleteIfExists(path) }.isFailure } ?: false

    private fun failure(
        code: CliAcceptanceFailureCode,
        phase: CanaryPhase,
        iteration: Int,
        cleanupFailed: Boolean = false,
    ) = CliAcceptanceFailure(
        code = code,
        phase = phase.phase,
        iteration = iteration,
        adapter = phase.adapter,
        cleanupStatus = if (cleanupFailed) CliAcceptanceCleanupStatus.FAILED else CliAcceptanceCleanupStatus.COMPLETED,
    )
}

/** canary を container entrypoint として安全に実行する。 */
fun main(args: Array<String>) = runBlocking {
    val repetitions = args.singleOrNull()?.toIntOrNull()
    if (!canStartCanary(repetitions)) {
        println(
            CliAcceptanceFailure(
                code = CliAcceptanceFailureCode.IDENTITY,
                phase = LlmInvocationPhase.PRE_FILTER,
                iteration = 0,
                adapter = CLAUDE_OUTPUT_ADAPTER_VERSION,
                cleanupStatus = CliAcceptanceCleanupStatus.COMPLETED,
            ).safeMessage(),
        )
        exitProcess(2)
    }
    requireNotNull(repetitions)
    val renderer = DefaultLlmCommandRenderer(
        LlmCommandRendererConfig(
            claudeCommandTemplate = listOf(PINNED_CLAUDE_COMMAND),
            codexCommandTemplate = listOf(PINNED_CODEX_COMMAND),
        ),
    )
    val result = CliAcceptanceCanary(
        invoker = ShellLlmInvoker(
            commandRenderer = renderer,
            processRunner = ShellProcessRunner(),
            outputParser = DefaultLlmOutputParser(),
        ),
        workingDirectory = Path.of("/tmp"),
        environment = cliCanaryEnvironment(System.getenv()),
        fixtureCommand = FIXTURE_MCP_COMMAND,
    ).run(repetitions)
    val failure = (result.exceptionOrNull() as? CliAcceptanceFailure) ?: result.exceptionOrNull()?.let {
        CliAcceptanceFailure(
            code = CliAcceptanceFailureCode.INVOCATION,
            phase = LlmInvocationPhase.PRE_FILTER,
            iteration = 0,
            adapter = CLAUDE_OUTPUT_ADAPTER_VERSION,
            cleanupStatus = CliAcceptanceCleanupStatus.COMPLETED,
        )
    }
    if (failure == null) {
        println("CLI_ACCEPTANCE_V1 OK runs=$repetitions phases=${CANARY_PHASE_MATRIX.size}")
    } else {
        println(failure.safeMessage())
        exitProcess(1)
    }
}

/** acceptance matrix の固定phase定義。 */
private data class CanaryPhase(
    val phase: LlmInvocationPhase,
    val provider: LlmProvider,
    val model: String,
    val effort: LlmEffort,
    val adapter: String,
    val prompt: String,
)

/** renderer request とfixture call recordを同じ invocation に束縛する。 */
private data class CanaryInvocation(val request: LlmInvocationRequest, val recordPath: Path?)

/** production child process と同じ curated allowlist だけを canary CLI へ渡す。 */
internal fun cliCanaryEnvironment(environment: Map<String, String>) =
    environment.filterKeys(CHILD_ENV_ALLOWLIST::contains)

internal fun cliCanaryProbeTool(phase: LlmInvocationPhase): String = when (phase) {
    LlmInvocationPhase.PROPOSER -> PROPOSER_WRITE_TOOL
    LlmInvocationPhase.FALSIFIER -> CODEX_FALSIFIER_WRITE_TOOL
    else -> error("CLI canary probe tool is only defined for MCP phases.")
}

/** pinned CLI の `non_prefixed_mcp_tool_names=false` がmodelへ提示するMCP tool ID。 */
internal fun cliCanaryQualifiedProbeTool(phase: LlmInvocationPhase): String =
    "mcp__${CANARY_MCP_SERVER_NAME}__${cliCanaryProbeTool(phase)}"

private fun canStartCanary(repetitions: Int?): Boolean {
    if (repetitions == null) return false
    if (repetitions != 1 && repetitions != 3) return false

    return hasAppIdentity()
}

private fun hasAppIdentity(): Boolean {
    val fields = runCatching { Files.readAllLines(Path.of("/proc/self/status")) }.getOrNull() ?: return false
    val uid = fields.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(2)
    val gid = fields.firstOrNull { it.startsWith("Gid:") }?.split(Regex("\\s+"))?.getOrNull(2)

    // Dockerfile の runtime APP_UID / LLM_SHARED_GID と同じ identity を要求する。
    return uid == "10001" && gid == "10004"
}

private fun LlmProviderFailureCategory.toCanaryCode(): CliAcceptanceFailureCode = when (this) {
    LlmProviderFailureCategory.AUTHENTICATION -> CliAcceptanceFailureCode.AUTHENTICATION
    LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT -> CliAcceptanceFailureCode.RATE_OR_SESSION_LIMIT
    LlmProviderFailureCategory.QUOTA_EXHAUSTED -> CliAcceptanceFailureCode.QUOTA_EXHAUSTED
    LlmProviderFailureCategory.OUTPUT_CONTRACT -> CliAcceptanceFailureCode.OUTPUT_CONTRACT
    LlmProviderFailureCategory.PROCESS_TIMEOUT -> CliAcceptanceFailureCode.TIMEOUT
    LlmProviderFailureCategory.PROCESS_EXIT -> CliAcceptanceFailureCode.PROCESS_EXIT
    LlmProviderFailureCategory.CLEANUP -> CliAcceptanceFailureCode.CLEANUP
    LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE -> CliAcceptanceFailureCode.INVOCATION
}

/** acceptance対象のClaude model pin。 */
internal const val CLAUDE_CANARY_MODEL = "claude-haiku-4-5-20251001"

/** acceptance対象のCodex model pin。 */
internal const val CODEX_CANARY_MODEL = "gpt-5.5"

/** data-free MCP fixture の call record path を渡す環境変数。 */
internal const val CLI_CANARY_RECORD_PATH_ENV = "FUKUROU_CLI_CANARY_RECORD_PATH"

/** fixture MCPを有効化するphase。 */
internal val CANARY_MCP_PHASES = setOf(LlmInvocationPhase.PROPOSER, LlmInvocationPhase.FALSIFIER)
private const val CANARY_MCP_SERVER_NAME = "canary"
private const val PINNED_CLAUDE_COMMAND = "/usr/local/bin/claude"
private const val PINNED_CODEX_COMMAND = "/usr/local/bin/codex"
private const val FIXTURE_MCP_COMMAND = "/usr/local/libexec/fukurou-cli-canary-mcp.mjs"
private const val RESPONSE_MARKER = "FUKUROU_CLI_CANARY_OK"
private const val PROPOSER_WRITE_TOOL = "submit_decision"
private const val CODEX_FALSIFIER_WRITE_TOOL = "submit_falsification"
private val PHASE_TIMEOUT = Duration.ofSeconds(120)
private val CANARY_PHASE_MATRIX = listOf(
    CanaryPhase(
        LlmInvocationPhase.PRE_FILTER,
        LlmProvider.CLAUDE,
        CLAUDE_CANARY_MODEL,
        LlmEffort.DEFAULT,
        CLAUDE_OUTPUT_ADAPTER_VERSION,
        "Return exactly $RESPONSE_MARKER. Do not use tools.",
    ),
    CanaryPhase(
        LlmInvocationPhase.PROPOSER,
        LlmProvider.CLAUDE,
        CLAUDE_CANARY_MODEL,
        LlmEffort.DEFAULT,
        CLAUDE_OUTPUT_ADAPTER_VERSION,
        "Call ${cliCanaryQualifiedProbeTool(LlmInvocationPhase.PROPOSER)} at least once. " +
            "Wait for its result, then return exactly $RESPONSE_MARKER.",
    ),
    CanaryPhase(
        LlmInvocationPhase.FALSIFIER,
        LlmProvider.CODEX,
        CODEX_CANARY_MODEL,
        LlmEffort.LOW,
        CODEX_OUTPUT_ADAPTER_VERSION,
        "Call ${cliCanaryQualifiedProbeTool(LlmInvocationPhase.FALSIFIER)} at least once. " +
            "Wait for its result, then return exactly $RESPONSE_MARKER.",
    ),
    CanaryPhase(
        LlmInvocationPhase.REFLECTION,
        LlmProvider.CLAUDE,
        CLAUDE_CANARY_MODEL,
        LlmEffort.DEFAULT,
        CLAUDE_OUTPUT_ADAPTER_VERSION,
        "Return exactly $RESPONSE_MARKER. Do not use tools.",
    ),
)
