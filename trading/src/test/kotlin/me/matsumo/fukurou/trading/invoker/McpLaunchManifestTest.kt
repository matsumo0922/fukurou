package me.matsumo.fukurou.trading.invoker

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** per-run MCP capability の非 secret contract を検証する。 */
class McpLaunchManifestTest {
    @Test
    fun write_createsUniqueOwnerOnlyManifestWithoutPassword() {
        val directory = Files.createTempDirectory("fukurou-manifest-test-")
        val writer = McpLaunchManifestWriter(
            directory = directory,
            clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
        )

        val first = writer.writeManifest("invocation-1")
        val second = writer.writeManifest("invocation-2")
        val content = Files.readString(first.path)
        val manifest = Json.decodeFromString<McpLaunchManifest>(content)

        assertNotEquals(first.id, second.id)
        assertEquals("2026-07-12T00:01:00Z", manifest.expiresAt)
        assertEquals(listOf("get_balance", "submit_decision"), manifest.allowedTools)
        assertEquals("v1", manifest.systemPromptVersion)
        assertTrue(manifest.terminalEvidenceCaptureEnabled)
        assertTrue(first.terminalEvidenceCaptureEnabled)
        assertFalse(content.contains("password", ignoreCase = true))
        assertEquals("rw-------", Files.getPosixFilePermissions(first.path).toPermissionString())
        assertTrue(first.path.parent == second.path.parent)

        Files.deleteIfExists(first.path)
        Files.deleteIfExists(second.path)
        Files.deleteIfExists(directory)
    }

    @Test
    fun write_supportsExplicitDisabledCompatibilityFixture() {
        val directory = Files.createTempDirectory("fukurou-manifest-disabled-test-")
        val writer = McpLaunchManifestWriter(directory = directory, clock = Clock.systemUTC())

        val capability = writer.writeManifest("disabled-fixture", terminalEvidenceCaptureEnabled = false)
        val manifest = Json.decodeFromString<McpLaunchManifest>(Files.readString(capability.path))

        assertFalse(capability.terminalEvidenceCaptureEnabled)
        assertFalse(manifest.terminalEvidenceCaptureEnabled)
        Files.deleteIfExists(capability.path)
        Files.deleteIfExists(directory)
    }

    @Test
    fun write_rejectsManifestAndDecisionRunIdentityMismatch() {
        val directory = Files.createTempDirectory("fukurou-manifest-mismatch-test-")
        val writer = McpLaunchManifestWriter(directory = directory, clock = Clock.systemUTC())

        assertFailsWith<IllegalArgumentException> {
            writer.write(
                invocationId = "manifest-invocation",
                phase = LlmInvocationPhase.PROPOSER,
                context = DecisionRunContext(
                    decisionRunId = "different-decision-run",
                    llmProvider = "claude",
                    promptHash = "hash",
                    systemPromptVersion = "v1",
                    marketSnapshotId = "snapshot",
                ),
                allowedTools = listOf("mcp__fukurou-mcp__submit_decision"),
                databaseUrl = "jdbc:postgresql://postgres/fukurou",
                databaseUser = "fukurou_mcp",
                gmoPublicBaseUrl = "http://127.0.0.1:1",
                runtimeEnvironment = me.matsumo.fukurou.trading.config.RuntimeConfigCatalog.runtimeEnvironment(
                    me.matsumo.fukurou.trading.config.TradingBotConfig(),
                ),
                timeout = Duration.ofMinutes(1),
                totalToolCallLimit = 48,
                actToolCallLimit = 3,
            )
        }
        assertTrue(Files.list(directory).use { entries -> entries.findAny().isEmpty })
        Files.deleteIfExists(directory)
    }

    private fun McpLaunchManifestWriter.writeManifest(
        invocationId: String,
        terminalEvidenceCaptureEnabled: Boolean = true,
    ): McpLaunchCapability {
        return write(
            invocationId = invocationId,
            phase = LlmInvocationPhase.PROPOSER,
            context = DecisionRunContext(
                decisionRunId = invocationId,
                llmProvider = "claude",
                promptHash = "hash",
                systemPromptVersion = "v1",
                marketSnapshotId = "snapshot",
            ),
            allowedTools = listOf("mcp__fukurou-mcp__submit_decision", "mcp__fukurou-mcp__get_balance"),
            databaseUrl = "jdbc:postgresql://postgres/fukurou",
            databaseUser = "fukurou_mcp",
            gmoPublicBaseUrl = "http://127.0.0.1:1",
            runtimeEnvironment = me.matsumo.fukurou.trading.config.RuntimeConfigCatalog.runtimeEnvironment(
                me.matsumo.fukurou.trading.config.TradingBotConfig(),
            ),
            timeout = Duration.ofMinutes(1),
            totalToolCallLimit = 48,
            actToolCallLimit = 3,
            terminalEvidenceCaptureEnabled = terminalEvidenceCaptureEnabled,
        )
    }
}

private fun Set<java.nio.file.attribute.PosixFilePermission>.toPermissionString(): String {
    val expectedOrder = listOf(
        java.nio.file.attribute.PosixFilePermission.OWNER_READ to 'r',
        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE to 'w',
        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE to 'x',
        java.nio.file.attribute.PosixFilePermission.GROUP_READ to 'r',
        java.nio.file.attribute.PosixFilePermission.GROUP_WRITE to 'w',
        java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE to 'x',
        java.nio.file.attribute.PosixFilePermission.OTHERS_READ to 'r',
        java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE to 'w',
        java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE to 'x',
    )

    return expectedOrder.joinToString("") { (permission, value) -> if (permission in this) value.toString() else "-" }
}
