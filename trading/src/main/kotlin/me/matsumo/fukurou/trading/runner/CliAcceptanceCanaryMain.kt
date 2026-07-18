package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.invoker.CLAUDE_OUTPUT_ADAPTER_VERSION
import me.matsumo.fukurou.trading.invoker.CODEX_OUTPUT_ADAPTER_VERSION
import me.matsumo.fukurou.trading.invoker.DefaultLlmCommandRenderer
import me.matsumo.fukurou.trading.invoker.DefaultLlmOutputParser
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.LlmConfiguredModelSource
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmMcpServerConfig
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.LlmProviderContractException
import me.matsumo.fukurou.trading.invoker.LlmProviderFailureCategory
import me.matsumo.fukurou.trading.invoker.McpToolContractCatalog
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.invoker.ShellLlmInvoker
import me.matsumo.fukurou.trading.invoker.ShellProcessRunner
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
    INVOCATION,
    IDENTITY,
}

/** raw provider output や例外 message を保持しない acceptance failure。 */
internal class CliAcceptanceFailure(
    val code: CliAcceptanceFailureCode,
    val phase: LlmInvocationPhase,
    private val iteration: Int,
    private val adapter: String,
) : IllegalStateException("CLI acceptance failed closed.") {
    /** allowlist field だけの operator 向け結果。 */
    fun safeMessage(): String {
        return "CLI_ACCEPTANCE_V1 FAIL code=$code phase=$phase iteration=$iteration adapter=$adapter"
    }
}

/** production invocation components を通して pinned CLI phase matrix を検証する driver。 */
internal class CliAcceptanceCanary(
    private val invoker: LlmInvoker,
    private val workingDirectory: Path,
    private val environment: Map<String, String>,
    private val fixtureCommand: String,
    private val nonceFactory: (LlmInvocationPhase, Int) -> String = { _, _ -> UUID.randomUUID().toString() },
) {
    /** complete matrix を1回または3回だけ直列実行する。 */
    suspend fun run(repetitions: Int): Result<Unit> {
        require(repetitions == 1 || repetitions == 3) { "CLI acceptance repetitions must be 1 or 3." }

        repeat(repetitions) { index ->
            val iteration = index + 1
            CANARY_PHASE_MATRIX.forEach { phase ->
                val nonce = nonceFactory(phase.phase, iteration)
                val request = runCatching { phase.toRequest(iteration, nonce) }.getOrElse {
                    return Result.failure(failure(CliAcceptanceFailureCode.INVOCATION, phase, iteration))
                }
                val result = invoker.invoke(request).getOrElse { throwable ->
                    val code = (throwable as? LlmProviderContractException)
                        ?.providerFailure
                        ?.category
                        ?.toCanaryCode()
                        ?: CliAcceptanceFailureCode.INVOCATION
                    return Result.failure(failure(code, phase, iteration))
                }
                validate(result, phase, iteration, nonce)?.let { failure -> return Result.failure(failure) }
            }
        }

        return Result.success(Unit)
    }

    private fun CanaryPhase.toRequest(iteration: Int, nonce: String): LlmInvocationRequest {
        val invocationId = "cli-canary-${phase.name.lowercase()}-$iteration-${nonce.take(8)}"
        val enabledTools = McpToolContractCatalog.toolsFor(phase)
            .sorted()
            .map { tool -> "mcp__${CANARY_MCP_SERVER_NAME}__$tool" }
        val mcpServer = if (phase in CANARY_MCP_PHASES) {
            LlmMcpServerConfig(
                name = CANARY_MCP_SERVER_NAME,
                command = fixtureCommand,
                manifestId = invocationId,
                manifestPath = Path.of(System.getProperty("java.io.tmpdir"), "$invocationId.manifest"),
            )
        } else {
            null
        }

        return LlmInvocationRequest(
            invocationId = invocationId,
            provider = provider,
            phase = phase,
            prompt = prompt,
            timeout = PHASE_TIMEOUT,
            workingDirectory = workingDirectory,
            decisionRunContext = DecisionRunContext.EMPTY,
            mcpServer = mcpServer,
            environment = environment + (CLI_CANARY_NONCE_ENV to nonce),
            toolPolicy = McpToolContractCatalog.canonicalPolicy(phase, enabledTools),
            model = model,
            effort = effort,
            useConfiguredModelFallback = false,
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun validate(
        result: LlmInvocationResult,
        phase: CanaryPhase,
        iteration: Int,
        nonce: String,
    ): CliAcceptanceFailure? {
        result.providerFailure?.let { providerFailure ->
            return failure(providerFailure.category.toCanaryCode(), phase, iteration)
        }
        if (result.processResult.status == ProcessRunStatus.TIMED_OUT) {
            return failure(CliAcceptanceFailureCode.TIMEOUT, phase, iteration)
        }
        if (result.processResult.exitCode != 0) {
            return failure(CliAcceptanceFailureCode.PROCESS_EXIT, phase, iteration)
        }
        if (result.cleanupFailure != null) {
            return failure(CliAcceptanceFailureCode.CLEANUP, phase, iteration)
        }
        val configuredModelMatches = result.configuredModelIdentity.source == LlmConfiguredModelSource.REQUEST &&
            result.configuredModelIdentity.name == phase.model
        if (!configuredModelMatches) {
            return failure(CliAcceptanceFailureCode.CONFIGURED_MODEL, phase, iteration)
        }
        val observedModelMatches = if (phase.provider == LlmProvider.CLAUDE) {
            result.observedModelIdentity?.name == phase.model
        } else {
            result.observedModelIdentity == null
        }
        if (!observedModelMatches) {
            return failure(CliAcceptanceFailureCode.OBSERVED_MODEL, phase, iteration)
        }
        val expectedMarker = if (phase.phase in CANARY_MCP_PHASES) nonce else NO_TOOL_MARKER
        if (result.responseText.trim() != expectedMarker) {
            return failure(CliAcceptanceFailureCode.PROBE_MARKER, phase, iteration)
        }

        return null
    }

    private fun failure(
        code: CliAcceptanceFailureCode,
        phase: CanaryPhase,
        iteration: Int,
    ): CliAcceptanceFailure {
        return CliAcceptanceFailure(
            code = code,
            phase = phase.phase,
            iteration = iteration,
            adapter = phase.adapter,
        )
    }
}

/** canary を container entrypoint として安全に実行する。 */
fun main(args: Array<String>) = runBlocking {
    val repetitions = args.singleOrNull()?.toIntOrNull()
    if (!canStartCanary(repetitions)) {
        System.err.println(
            CliAcceptanceFailure(
                code = CliAcceptanceFailureCode.IDENTITY,
                phase = LlmInvocationPhase.PRE_FILTER,
                iteration = 0,
                adapter = CLAUDE_OUTPUT_ADAPTER_VERSION,
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
        environment = System.getenv(),
        fixtureCommand = FIXTURE_MCP_COMMAND,
    ).run(repetitions)
    val failure = result.exceptionOrNull() as? CliAcceptanceFailure
    if (failure == null) {
        println("CLI_ACCEPTANCE_V1 OK runs=$repetitions phases=${CANARY_PHASE_MATRIX.size}")
    } else {
        System.err.println(failure.safeMessage())
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

private fun canStartCanary(repetitions: Int?): Boolean {
    if (repetitions == null) return false
    if (repetitions != 1 && repetitions != 3) return false

    return hasAppIdentity()
}

private fun hasAppIdentity(): Boolean {
    val fields = runCatching { Files.readAllLines(Path.of("/proc/self/status")) }.getOrNull() ?: return false
    val uid = fields.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(2)
    val gid = fields.firstOrNull { it.startsWith("Gid:") }?.split(Regex("\\s+"))?.getOrNull(2)

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

/** data-free MCP fixtureが返すrun固有nonceの環境変数。 */
internal const val CLI_CANARY_NONCE_ENV = "FUKUROU_CLI_CANARY_NONCE"

/** fixture MCPを有効化するphase。 */
internal val CANARY_MCP_PHASES = setOf(LlmInvocationPhase.PROPOSER, LlmInvocationPhase.FALSIFIER)
private const val CANARY_MCP_SERVER_NAME = "canary"
private const val PINNED_CLAUDE_COMMAND = "/usr/local/bin/claude"
private const val PINNED_CODEX_COMMAND = "/usr/local/bin/codex"
private const val FIXTURE_MCP_COMMAND = "/usr/local/libexec/fukurou-cli-canary-mcp.mjs"
private const val NO_TOOL_MARKER = "FUKUROU_CLI_CANARY_OK"
private val PHASE_TIMEOUT = Duration.ofSeconds(120)
private val CANARY_PHASE_MATRIX = listOf(
    CanaryPhase(
        LlmInvocationPhase.PRE_FILTER,
        LlmProvider.CLAUDE,
        CLAUDE_CANARY_MODEL,
        LlmEffort.DEFAULT,
        CLAUDE_OUTPUT_ADAPTER_VERSION,
        "Return exactly $NO_TOOL_MARKER. Do not use tools.",
    ),
    CanaryPhase(
        LlmInvocationPhase.PROPOSER,
        LlmProvider.CLAUDE,
        CLAUDE_CANARY_MODEL,
        LlmEffort.DEFAULT,
        CLAUDE_OUTPUT_ADAPTER_VERSION,
        "Call get_account_status once, then return only the nonce from its result.",
    ),
    CanaryPhase(
        LlmInvocationPhase.FALSIFIER,
        LlmProvider.CODEX,
        CODEX_CANARY_MODEL,
        LlmEffort.LOW,
        CODEX_OUTPUT_ADAPTER_VERSION,
        "Call get_account_status once, then return only the nonce from its result.",
    ),
    CanaryPhase(
        LlmInvocationPhase.REFLECTION,
        LlmProvider.CLAUDE,
        CLAUDE_CANARY_MODEL,
        LlmEffort.DEFAULT,
        CLAUDE_OUTPUT_ADAPTER_VERSION,
        "Return exactly $NO_TOOL_MARKER. Do not use tools.",
    ),
)
