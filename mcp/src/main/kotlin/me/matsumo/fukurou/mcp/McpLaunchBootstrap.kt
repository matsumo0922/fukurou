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

/** argv + env だけから MCP runtime の launch context を構築する。 */
object McpLaunchBootstrap {
    /** argv の manifest id + manifest directory + env の password だけから MCP runtime の launch context を構築する。 */
    fun readFromArgs(
        manifestId: String,
        environment: Map<String, String>,
        clock: Clock = Clock.systemUTC(),
    ): McpBootstrapConfig {
        val manifestDirectory = requireNotNull(environment[MANIFEST_DIRECTORY_ENV]) {
            "MCP manifest directory environment variable is required."
        }
        val manifestBytes = Files.readAllBytes(Path.of(manifestDirectory, "$manifestId.json"))
        val password = requireNotNull(environment[DB_PASSWORD_ENV]) {
            "MCP database password environment variable is required."
        }

        return decode(manifestBytes, password.toByteArray(), clock)
    }

    internal fun decode(
        manifestBytes: ByteArray,
        passwordBytes: ByteArray,
        clock: Clock,
    ): McpBootstrapConfig {
        val manifest = MANIFEST_JSON.decodeFromString<McpLaunchManifest>(manifestBytes.decodeToString())
        val expiresAt = Instant.parse(manifest.expiresAt)
        val password = passwordBytes.decodeToString().trimEnd('\n', '\r')

        // 検証は運用ミスの早期検出だけを目的とする。writer 由来の値の再検算は行わない。
        require(manifest.version == MCP_MANIFEST_VERSION) {
            "MCP manifest version ${manifest.version} does not match this image ($MCP_MANIFEST_VERSION)."
        }
        val phase = runCatching { LlmInvocationPhase.valueOf(manifest.phase) }
            .getOrElse { throw IllegalArgumentException("Unsupported MCP manifest phase.") }
        val canonicalTools = McpToolContractCatalog.toolsFor(phase)
        require(canonicalTools.isNotEmpty()) { "Unsupported MCP manifest phase." }
        require(expiresAt.isAfter(Instant.now(clock))) { "MCP manifest is expired." }
        require(
            manifest.submissionSocketPath.isNotBlank() &&
                Path.of(manifest.submissionSocketPath).isAbsolute &&
                manifest.submissionSocketPath.endsWith(".sock"),
        ) {
            "MCP manifest submission gateway path is invalid."
        }
        require(password.isNotEmpty()) { "MCP database password environment value must not be empty." }
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
            // 実効 allowlist の正本は catalog。manifest の allowedTools は renderer 向けで、ここでは読まない。
            allowedTools = canonicalTools,
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
                submissionSocketPath = manifest.submissionSocketPath,
            ),
            terminalEvidenceCaptureEnabled = manifest.terminalEvidenceCaptureEnabled,
        )
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

private const val MANIFEST_DIRECTORY_ENV = "FUKUROU_MCP_MANIFEST_DIRECTORY"
private const val DB_PASSWORD_ENV = "DB_PASSWORD"
private val MANIFEST_JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
}
