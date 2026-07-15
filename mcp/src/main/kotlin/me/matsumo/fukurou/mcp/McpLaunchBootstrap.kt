package me.matsumo.fukurou.mcp

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.config.DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN
import me.matsumo.fukurou.trading.config.DEFAULT_MAX_TOOL_CALLS_PER_RUN
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientConfig
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.MCP_MANIFEST_VERSION
import me.matsumo.fukurou.trading.invoker.McpLaunchManifest
import me.matsumo.fukurou.trading.invoker.McpToolContractCatalog
import me.matsumo.fukurou.trading.runtime.TradingDatabaseConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/** launcher が渡した descriptor だけから MCP runtime の launch context を構築する。 */
object McpLaunchBootstrap {
    fun read(clock: Clock = Clock.systemUTC()): McpBootstrapConfig {
        val manifestBytes = readBoundedDescriptor(MANIFEST_FD, MAX_MANIFEST_BYTES)
        val passwordBytes = readBoundedDescriptor(PASSWORD_FD, MAX_PASSWORD_BYTES)

        require(Files.exists(Path.of("/proc/self/fd/$SUBMISSION_GATEWAY_FD"))) {
            "MCP submission gateway descriptor is unavailable."
        }

        return decode(manifestBytes, passwordBytes, clock)
    }

    @Suppress("LongMethod")
    internal fun decode(
        manifestBytes: ByteArray,
        passwordBytes: ByteArray,
        clock: Clock,
    ): McpBootstrapConfig {
        require(manifestBytes.isNotEmpty() && manifestBytes.size <= MAX_MANIFEST_BYTES) {
            "MCP manifest descriptor size rejected."
        }
        require(passwordBytes.isNotEmpty() && passwordBytes.size <= MAX_PASSWORD_BYTES) {
            "MCP password descriptor size rejected."
        }
        val manifest = MANIFEST_JSON.decodeFromString<McpLaunchManifest>(manifestBytes.decodeToString())
        val expiresAt = Instant.parse(manifest.expiresAt)
        val password = passwordBytes.decodeToString().trimEnd('\n', '\r')

        require(manifest.version == MCP_MANIFEST_VERSION) { "Unsupported MCP manifest version." }
        val phase = runCatching { LlmInvocationPhase.valueOf(manifest.phase) }
            .getOrElse { throw IllegalArgumentException("Unsupported MCP manifest phase.") }
        val canonicalTools = McpToolContractCatalog.toolsFor(phase)
        require(canonicalTools.isNotEmpty()) { "Unsupported MCP manifest phase." }
        require(manifest.allowedTools.toSet() == canonicalTools) { "MCP manifest allowlist is not canonical." }
        require(manifest.toolSchemaHash == McpToolContractCatalog.canonicalSchemaHash(phase)) {
            "MCP manifest tool schema hash mismatch."
        }
        require(manifest.phaseManifestId.isNotBlank() && manifest.effectiveInvocationHash.length == 64) {
            "MCP manifest effective phase identity is required."
        }
        require(
            manifest.submissionSocketPath.isNotBlank() &&
                Path.of(manifest.submissionSocketPath).isAbsolute &&
                manifest.submissionSocketPath.endsWith(".sock"),
        ) {
            "MCP manifest submission gateway path is invalid."
        }
        require(manifest.systemPromptVersion.isNotBlank()) { "MCP manifest system prompt version is required." }
        require(expiresAt.isAfter(Instant.now(clock))) { "MCP manifest is expired." }
        require(password.isNotEmpty()) { "MCP password descriptor must not be empty." }
        require(manifest.totalToolCallLimit in 1..DEFAULT_MAX_TOOL_CALLS_PER_RUN) {
            "MCP total tool call budget is outside the canonical limit."
        }
        require(manifest.actToolCallLimit in 1..DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN) {
            "MCP act tool call budget is outside the canonical limit."
        }
        require(manifest.actToolCallLimit <= manifest.totalToolCallLimit) {
            "MCP act tool call budget exceeds total budget."
        }
        val runtimeConfig = TradingBotConfig.fromEnvironment(manifest.runtimeEnvironment)
        require(RuntimeConfigCatalog.runtimeEnvironment(runtimeConfig) == manifest.runtimeEnvironment) {
            "MCP runtime snapshot is missing, unknown, or non-canonical."
        }

        return McpBootstrapConfig(
            databaseConfig = TradingDatabaseConfig(manifest.dbUrl, manifest.dbUser, password),
            phase = phase,
            decisionRunContext = DecisionRunContext(
                decisionRunId = manifest.decisionRunId,
                llmProvider = manifest.llmProvider,
                promptHash = manifest.promptHash,
                systemPromptVersion = manifest.systemPromptVersion,
                marketSnapshotId = manifest.marketSnapshotId,
            ),
            allowedTools = manifest.allowedTools.toSet(),
            expiresAt = expiresAt,
            totalToolCallLimit = manifest.totalToolCallLimit,
            actToolCallLimit = manifest.actToolCallLimit,
            gmoPublicClientConfig = GmoPublicClientConfig(baseUrl = manifest.gmoPublicBaseUrl),
            tradingConfig = runtimeConfig,
            submissionGatewayBinding = McpSubmissionGatewayBinding(
                invocationId = manifest.invocationId,
                phase = phase,
                phaseManifestId = manifest.phaseManifestId,
                effectiveInvocationHash = manifest.effectiveInvocationHash,
            ),
            terminalEvidenceCaptureEnabled = manifest.terminalEvidenceCaptureEnabled,
        )
    }

    private fun readBoundedDescriptor(fd: Int, maximumBytes: Int): ByteArray {
        val bytes = Files.readAllBytes(Path.of("/proc/self/fd/$fd"))
        require(bytes.isNotEmpty() && bytes.size <= maximumBytes) { "MCP bootstrap descriptor size rejected." }

        return bytes
    }
}

/** validated MCP bootstrap values。password を log/serialization 対象へ渡さない。 */
data class McpBootstrapConfig(
    val databaseConfig: TradingDatabaseConfig,
    val phase: LlmInvocationPhase,
    val decisionRunContext: DecisionRunContext,
    val allowedTools: Set<String>,
    val expiresAt: Instant,
    val totalToolCallLimit: Int,
    val actToolCallLimit: Int,
    val gmoPublicClientConfig: GmoPublicClientConfig,
    val tradingConfig: TradingBotConfig,
    val submissionGatewayBinding: McpSubmissionGatewayBinding,
    val terminalEvidenceCaptureEnabled: Boolean = false,
) {
    override fun toString(): String = "McpBootstrapConfig(" +
        "databaseConfig=$databaseConfig, " +
        "phase=$phase, " +
        "decisionRunContext=$decisionRunContext, " +
        "allowedTools=$allowedTools, " +
        "expiresAt=$expiresAt, " +
        "totalToolCallLimit=$totalToolCallLimit, " +
        "actToolCallLimit=$actToolCallLimit, " +
        "gmoPublicClientConfig=$gmoPublicClientConfig, " +
        "tradingConfig=$tradingConfig" +
        ", submissionGatewayBinding=$submissionGatewayBinding" +
        ", terminalEvidenceCaptureEnabled=$terminalEvidenceCaptureEnabled" +
        ")"
}

private const val MANIFEST_FD = 3
private const val PASSWORD_FD = 4
private const val SUBMISSION_GATEWAY_FD = 5
private const val MAX_MANIFEST_BYTES = 64 * 1024
private const val MAX_PASSWORD_BYTES = 4096
private val MANIFEST_JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
}
