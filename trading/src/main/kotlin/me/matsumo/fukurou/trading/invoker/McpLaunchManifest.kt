package me.matsumo.fukurou.trading.invoker

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.TERMINAL_EVIDENCE_CAPTURE_ENABLED
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** MCP launcher が検証して FD 経由で bootstrap へ渡す非 secret manifest。 */
@Serializable
data class McpLaunchManifest(
    val version: Int,
    val invocationId: String,
    val phase: String,
    val expiresAt: String,
    val allowedTools: List<String>,
    val decisionRunId: String,
    val llmProvider: String,
    val promptHash: String,
    val systemPromptVersion: String,
    val marketSnapshotId: String,
    val dbUrl: String,
    val dbUser: String,
    val gmoPublicBaseUrl: String,
    val runtimeEnvironment: Map<String, String>,
    val totalToolCallLimit: Int,
    val actToolCallLimit: Int,
    val phaseManifestId: String = "$invocationId-${phase.lowercase()}",
    val effectiveInvocationHash: String = me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy.sha256(
        promptHash,
    ),
    val toolSchemaHash: String = McpToolContractCatalog.canonicalSchemaHash(LlmInvocationPhase.valueOf(phase)),
    val submissionSocketPath: String = "/tmp/fukurou-submission.sock",
    val terminalEvidenceCaptureEnabled: Boolean = false,
)

/** owner-only manifest を fixed directory へ atomic に発行する。 */
@Suppress("ClassOrdering")
class McpLaunchManifestWriter(
    private val directory: Path = Path.of(DEFAULT_MCP_MANIFEST_DIRECTORY),
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        /** CLI launch前に永続 phase identityをowner-only manifestへ固定する。 */
        fun bindPhaseIdentity(
            path: Path,
            phaseManifestId: String,
            effectiveInvocationHash: String,
        ) {
            val manifest = MANIFEST_JSON.decodeFromString<McpLaunchManifest>(Files.readString(path)).copy(
                phaseManifestId = phaseManifestId,
                effectiveInvocationHash = effectiveInvocationHash,
            )
            val temporary = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
            try {
                Files.writeString(
                    temporary,
                    MANIFEST_JSON.encodeToString(manifest),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                Files.setPosixFilePermissions(temporary, PRIVATE_FILE_PERMISSIONS)
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (throwable: Throwable) {
                Files.deleteIfExists(temporary)
                throw throwable
            }
        }

        /** launcher 接続前に app-owned gateway socket を同じ manifest へ固定する。 */
        fun bindSubmissionSocket(path: Path, submissionSocketPath: Path) {
            val manifest = MANIFEST_JSON.decodeFromString<McpLaunchManifest>(Files.readString(path)).copy(
                submissionSocketPath = submissionSocketPath.toString(),
            )
            val temporary = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
            try {
                Files.writeString(
                    temporary,
                    MANIFEST_JSON.encodeToString(manifest),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                Files.setPosixFilePermissions(temporary, PRIVATE_FILE_PERMISSIONS)
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (throwable: Throwable) {
                Files.deleteIfExists(temporary)
                throw throwable
            }
        }
    }

    @Suppress("LongParameterList")
    fun write(
        invocationId: String,
        phase: LlmInvocationPhase,
        context: DecisionRunContext,
        allowedTools: List<String>,
        databaseUrl: String,
        databaseUser: String,
        gmoPublicBaseUrl: String,
        runtimeEnvironment: Map<String, String>,
        timeout: Duration,
        totalToolCallLimit: Int,
        actToolCallLimit: Int,
        phaseManifestId: String = "$invocationId-${phase.name.lowercase()}",
        effectiveInvocationHash: String = me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy.sha256(
            context.promptHash.orEmpty(),
        ),
        submissionSocketPath: String = "",
        terminalEvidenceCaptureEnabled: Boolean = TERMINAL_EVIDENCE_CAPTURE_ENABLED,
    ): McpLaunchCapability {
        require(allowedTools.isNotEmpty()) { "MCP manifest allowedTools must not be empty." }
        require(databaseUrl.isNotBlank() && databaseUser.isNotBlank()) { "MCP database identity is required." }

        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS)
        val manifestId = randomManifestId()
        val target = directory.resolve("$manifestId.json")
        val temporary = Files.createTempFile(directory, ".$manifestId-", ".tmp")
        val manifest = McpLaunchManifest(
            version = MCP_MANIFEST_VERSION,
            invocationId = invocationId,
            phase = phase.name,
            expiresAt = Instant.now(clock).plus(timeout).toString(),
            allowedTools = allowedTools.map { tool -> tool.substringAfterLast("__") }.distinct().sorted(),
            decisionRunId = requireNotNull(context.decisionRunId) { "decisionRunId is required." },
            llmProvider = context.llmProvider.orEmpty(),
            promptHash = context.promptHash.orEmpty(),
            systemPromptVersion = requireNotNull(context.systemPromptVersion) { "systemPromptVersion is required." }
                .also { version -> require(version.isNotBlank()) { "systemPromptVersion must not be blank." } },
            marketSnapshotId = context.marketSnapshotId.orEmpty(),
            dbUrl = databaseUrl,
            dbUser = databaseUser,
            gmoPublicBaseUrl = gmoPublicBaseUrl,
            runtimeEnvironment = runtimeEnvironment.toSortedMap(),
            totalToolCallLimit = totalToolCallLimit,
            actToolCallLimit = actToolCallLimit,
            phaseManifestId = phaseManifestId,
            effectiveInvocationHash = effectiveInvocationHash,
            toolSchemaHash = McpToolContractCatalog.canonicalSchemaHash(phase),
            submissionSocketPath = submissionSocketPath,
            terminalEvidenceCaptureEnabled = terminalEvidenceCaptureEnabled,
        )

        try {
            Files.writeString(
                temporary,
                MANIFEST_JSON.encodeToString(manifest),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            Files.setPosixFilePermissions(temporary, PRIVATE_FILE_PERMISSIONS)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (throwable: Throwable) {
            Files.deleteIfExists(temporary)
            throw throwable
        }

        return McpLaunchCapability(
            id = manifestId,
            path = target,
            terminalEvidenceCaptureEnabled = terminalEvidenceCaptureEnabled,
        )
    }

    private fun randomManifestId(): String {
        val bytes = ByteArray(MANIFEST_ID_BYTES)
        SECURE_RANDOM.nextBytes(bytes)

        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** renderer に渡す opaque capability と cleanup path。 */
data class McpLaunchCapability(
    val id: String,
    val path: Path,
    val terminalEvidenceCaptureEnabled: Boolean,
)

const val DEFAULT_MCP_MANIFEST_DIRECTORY = "/run/fukurou/mcp-manifests"
const val MCP_MANIFEST_VERSION = 2
private const val MANIFEST_ID_BYTES = 24
private val SECURE_RANDOM = SecureRandom()
private val MANIFEST_JSON = Json { encodeDefaults = true }
private val PRIVATE_FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
private val PRIVATE_DIRECTORY_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)
