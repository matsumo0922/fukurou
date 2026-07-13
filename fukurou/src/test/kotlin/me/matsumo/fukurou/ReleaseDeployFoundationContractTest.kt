package me.matsumo.fukurou

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** production release/deploy foundation の file-level integration contract を検証するテスト。 */
class ReleaseDeployFoundationContractTest {
    private val root = repositoryRoot()

    @Test
    fun `typed operations and hooks remain cumulative and allowlisted`() {
        val contract = Json.parseToJsonElement(
            Files.readString(root.resolve("scripts/deploy/deploy-contract-v1.json")),
        ).jsonObject

        assertEquals(1, contract.getValue("contractVersion").jsonPrimitive.content.toInt())
        assertEquals(
            setOf(
                "CREATE_DIR_V1",
                "INSTALL_SECRET_REF_V1",
                "RUN_COMPOSE_PROJECT_V1",
                "DB_MAINTENANCE_V1",
                "DB_MIGRATION_V1",
                "SMOKE_HOOK_V1",
            ),
            contract.getValue("requiredCapabilities").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            listOf("FOUNDATION_PREFLIGHT_V1"),
            contract.getValue("requiredHooks").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `rollback capture precedes every deploy mutation`() {
        val executor = Files.readString(root.resolve("scripts/deploy/deploy-fukurou"))
        val main = executor.substringAfter("main() {")
        val capture = main.indexOf("capture_rollback_state")

        assertTrue(capture >= 0)
        assertTrue(capture < main.indexOf("provision_fence_root"))
        assertTrue(capture < main.indexOf("disable_launches"))
        assertTrue(capture < main.indexOf("run_candidate_preflight"))
        assertTrue(capture < main.indexOf("deploy_compose"))
        val captureBody = executor.substringAfter("capture_rollback_state() {").substringBefore("rollback_on_error() {")
        assertFalse(captureBody.contains("${'$'}{ENV_FILE}"))
    }

    @Test
    fun `production compose uses code owned PID one and durable fence`() {
        val compose = Files.readString(root.resolve("docker-compose.prod.yml"))
        val dockerfile = Files.readString(root.resolve("Dockerfile"))
        val agentLauncher = Files.readString(root.resolve("scripts/runtime/fukurou-llm-agent-launcher.c"))
        val mcpLauncher = Files.readString(root.resolve("scripts/runtime/fukurou-mcp-launcher.c"))

        assertFalse(compose.contains("init: true"))
        assertTrue(compose.contains("/srv/fukurou/runtime/launch-fence"))
        assertTrue(dockerfile.contains("ENTRYPOINT [\"/usr/local/libexec/fukurou-runtime-supervisor\"]"))
        assertTrue(agentLauncher.contains("fukurou_supervisor_proxy"))
        assertFalse(agentLauncher.contains("execve(executable"))
        assertTrue(mcpLauncher.contains("fukurou_supervisor_proxy"))
        assertFalse(mcpLauncher.contains("execve(\"/opt/java/openjdk/bin/java\""))
    }

    @Test
    fun `candidate preflight uses the same PID one without production credentials`() {
        val executor = Files.readString(root.resolve("scripts/deploy/deploy-fukurou"))
        val supervisor = Files.readString(root.resolve("scripts/runtime/fukurou-runtime-supervisor.c"))

        assertTrue(executor.contains("--canary-preflight"))
        assertTrue(executor.contains("--network none"))
        assertTrue(executor.contains("FUKUROU_CANDIDATE_DIGEST="))
        assertFalse(executor.contains("--entrypoint java"))
        assertTrue(supervisor.contains("run_canary_preflight"))
        assertTrue(supervisor.contains("DeploymentPreflightMain"))
    }
}

private fun repositoryRoot(): Path {
    var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (!Files.exists(candidate.resolve("settings.gradle.kts"))) {
        candidate = requireNotNull(candidate.parent) { "repository root was not found" }
    }
    return candidate
}
