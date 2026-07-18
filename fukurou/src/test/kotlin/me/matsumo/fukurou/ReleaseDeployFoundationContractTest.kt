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
    fun `production image publication is gated by exact target quality`() {
        val workflow = Files.readString(root.resolve(".github/workflows/deploy.yml"))
        val resolver = Files.readString(root.resolve("scripts/deploy/deploy-intent-resolver"))
        val resolveJob = workflow.substringAfter("\n  resolve:").substringBefore("\n  quality:")
        val qualityJob = workflow.substringAfter("\n  quality:").substringBefore("\n  build:")
        val buildJob = workflow.substringAfter("\n  build:").substringBefore("\n  deploy:")

        assertTrue(resolveJob.contains("deploy_sha: \${{ steps.resolve.outputs.deploy_sha }}"))
        assertTrue(resolveJob.contains("GITHUB_EVENT_NAME"))
        assertTrue(resolveJob.contains("scripts/deploy/deploy-intent-resolver"))
        assertTrue(resolver.contains("origin/main"))
        assertTrue(resolver.contains("AUTHORIZED_ROLLBACK"))
        assertTrue(resolver.contains("AUTO_IMAGE_ROLLBACK"))
        assertTrue(resolver.contains("SCHEMA_SENSITIVE_AUTOMATIC_DEPLOY_REQUIRES_MANUAL_REVIEW"))
        assertFalse(resolveJob.contains("packages: write"))

        assertTrue(qualityJob.contains("needs: resolve"))
        assertTrue(qualityJob.contains("ref: \${{ needs.resolve.outputs.deploy_sha }}"))
        assertTrue(qualityJob.contains("make test"))
        assertTrue(qualityJob.contains("make detekt"))
        assertTrue(qualityJob.contains("git diff --exit-code"))
        assertFalse(qualityJob.contains("packages: write"))

        assertTrue(buildJob.contains("needs: [resolve, quality]"))
        assertTrue(buildJob.contains("if: always()"))
        assertTrue(buildJob.contains("needs.resolve.result == 'success'"))
        assertTrue(buildJob.contains("needs.quality.result == 'success'"))
        assertFalse(buildJob.contains("needs.quality.result == 'skipped'"))
        assertFalse(buildJob.contains("requires_quality"))
        assertTrue(buildJob.contains("ref: \${{ needs.resolve.outputs.deploy_sha }}"))
        assertTrue(buildJob.contains("FUKUROU_REVISION=\${{ needs.resolve.outputs.deploy_sha }}"))
        assertFalse(buildJob.contains("steps.resolve.outputs.deploy_sha"))
        assertTrue(buildJob.indexOf("Verify exact target checkout") < buildJob.indexOf("Login to GHCR"))
        assertTrue(buildJob.contains("packages: write"))
    }

    @Test
    fun `workflow emits event-derived bundle v2 and requires installed contract v2`() {
        val workflow = Files.readString(root.resolve(".github/workflows/deploy.yml"))
        val resolver = Files.readString(root.resolve("scripts/deploy/deploy-intent-resolver"))
        val buildJob = workflow.substringAfter("\n  build:").substringBefore("\n  deploy:")
        val deployJob = workflow.substringAfter("\n  deploy:")

        assertTrue(workflow.contains("rollback_reason:"))
        assertTrue(workflow.contains("migration_rollback_mode:"))
        assertTrue(resolver.contains("workflow_event="))
        assertTrue(resolver.contains("deploy_intent="))
        assertTrue(resolver.contains("operator_reason="))
        assertTrue(resolver.contains("migration_rollback_mode="))
        assertTrue(resolver.contains("schema_sensitive_paths_sha256="))
        assertTrue(resolver.contains("SECRET_LIKE_REASON_PATTERN"))
        assertTrue(buildJob.contains("bundleSchemaVersion:2"))
        assertTrue(buildJob.contains("minimumContractVersion:2"))
        assertTrue(buildJob.contains("workflowEvent:${'$'}workflowEvent"))
        assertTrue(buildJob.contains("deployIntent:${'$'}deployIntent"))
        assertTrue(buildJob.contains("operatorReason:${'$'}operatorReason"))
        assertTrue(buildJob.contains("migrationRollbackMode:${'$'}migrationRollbackMode"))
        assertTrue(buildJob.contains("schemaSensitivePathsSha256:${'$'}schemaSensitivePathsSha256"))
        assertTrue(deployJob.contains("--print-contract-version") && deployJob.contains("== \"2\""))
        assertTrue(deployJob.contains("--print-schema-sensitive-paths-sha256"))
    }

    @Test
    fun `deploy intent resolver closes event reason and early diff cases`() {
        val process = ProcessBuilder("scripts/deploy/deploy-intent-resolver-selftest")
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, process.waitFor(), output)
        assertTrue(output.contains("DEPLOY_INTENT_RESOLVER_SELFTEST_OK"), output)
    }

    @Test
    fun `bundle schema and executor enforce revision and migration admission v2`() {
        val schema = Json.parseToJsonElement(
            Files.readString(root.resolve("scripts/deploy/deploy-bundle.schema.json")),
        ).jsonObject
        val executor = Files.readString(root.resolve("scripts/deploy/deploy-fukurou"))
        val inventory = Files.readAllLines(root.resolve("scripts/deploy/deploy-schema-sensitive-paths-v1.txt"))

        assertEquals("Fukurou signed deploy bundle v2", schema.getValue("title").jsonPrimitive.content)
        assertEquals(2, schema.getValue("properties").jsonObject.getValue("bundleSchemaVersion").jsonObject.getValue("const").jsonPrimitive.content.toInt())
        assertEquals(2, schema.getValue("properties").jsonObject.getValue("minimumContractVersion").jsonObject.getValue("const").jsonPrimitive.content.toInt())
        assertEquals(
            4,
            schema.getValue("allOf").jsonArray.single().jsonObject.getValue("oneOf").jsonArray.size,
        )
        assertTrue(schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.containsAll(
            listOf(
                "workflowEvent",
                "deployIntent",
                "operatorReason",
                "migrationRollbackMode",
                "schemaSensitivePathsSha256",
            ),
        ))
        assertTrue(executor.contains("readonly CONTRACT_VERSION=2"))
        assertTrue(executor.contains("observe_and_admit_deploy"))
        assertTrue(executor.contains("AUTHORIZED_ROLLBACK"))
        assertTrue(executor.contains("UNKNOWN_CURRENT_REVISION"))
        assertTrue(executor.contains("SCHEMA_SENSITIVE_MODE_MISMATCH"))
        assertTrue(executor.contains("CANDIDATE_ABORTED"))
        assertTrue(executor.contains("ROLL_FORWARD_ONLY"))
        assertTrue(inventory.contains("scripts/deploy/sql"))
        assertTrue(inventory.contains("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence"))
    }

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
        val catalog = Json.parseToJsonElement(
            Files.readString(root.resolve("scripts/deploy/deploy-capability-catalog-v1.json")),
        ).jsonObject
        assertEquals(1, catalog.getValue("catalogVersion").jsonPrimitive.content.toInt())
        assertTrue(catalog.getValue("operations").jsonArray.size >= 9)
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
        assertTrue(executor.contains("docker compose --env-file"))
        assertTrue(executor.contains("internal: true"))
        assertFalse(executor.contains("docker run --rm --read-only --network none"))
        assertTrue(executor.contains("FUKUROU_CANDIDATE_DIGEST"))
        assertFalse(executor.contains("--entrypoint java"))
        assertTrue(supervisor.contains("run_canary_preflight"))
        assertTrue(supervisor.contains("DeploymentPreflightMain"))
    }

    @Test
    fun `foundation uses immutable facts digest cutover and durable recovery`() {
        val executor = Files.readString(root.resolve("scripts/deploy/deploy-fukurou"))
        val migration = Files.readString(root.resolve("scripts/deploy/sql/deploy-foundation-v1.sql"))
        val compose = Files.readString(root.resolve("docker-compose.prod.yml"))
        val main = executor.substringAfter("main() {")

        assertTrue(migration.contains("infrastructure_gap_events"))
        assertFalse(migration.contains("UPDATE infrastructure_gap_events"))
        assertTrue(migration.contains("llm_pid_registrations"))
        assertTrue(compose.contains("FUKUROU_IMAGE_REFERENCE"))
        assertFalse(compose.contains("FUKUROU_IMAGE_TAG"))
        assertFalse(compose.contains("image: ghcr.io/matsumo0922/fukurou:\${FUKUROU_IMAGE_TAG"))
        assertTrue(executor.contains("MANUAL_RECOVERY_REQUIRED"))
        assertTrue(executor.contains("maintenance-cas"))
        assertTrue(executor.contains("CAPABILITY_CATALOG_REDEFINITION_OR_FORK"))
        assertTrue(executor.contains("FORWARD_DEADLINE_BOOTTIME=\$((DEPLOY_START_BOOTTIME + 1200))"))
        assertTrue(executor.contains("RECOVERY_LIMIT_BOOTTIME=\$((DEPLOY_START_BOOTTIME + 1500))"))
        assertTrue(
            main.substringAfter("recover_unfinished_deployments")
                .substringBefore("probe_candidate_operations")
                .contains("start_forward_deadline_watchdog"),
        )
        assertTrue(main.indexOf("validate_production_compose") < main.indexOf("capture_rollback_state"))
        assertTrue(executor.contains("legacy_journal_transition_allowed"))
        assertTrue(executor.contains("load_prepared_gap_event OPEN"))
    }

    @Test
    fun `terminal evidence activation is wired into runtime canary and bounded maintenance`() {
        val application = Files.readString(root.resolve("fukurou/src/main/kotlin/me/matsumo/fukurou/Application.kt"))
        val canary = Files.readString(root.resolve("scripts/mcp-credential-isolation-check"))
        val releaseBarrier = Files.readString(root.resolve("scripts/deploy/deploy-fukurou"))

        assertTrue(application.contains("llmAuditMaintenanceWorker = startLlmAuditMaintenanceWorker("))
        assertTrue(application.contains("private fun startLlmAuditMaintenanceWorker("))
        assertTrue(application.contains("ExposedLlmDecisionReconstructionRepository(database)"))
        assertTrue(application.contains("backgroundWorkers.llmAuditMaintenanceWorker?.let { worker -> worker::close }"))
        assertTrue(canary.contains("llm_tool_evidence_activation_boundaries"))
        assertTrue(canary.contains("TERMINAL_BUNDLE_CAPTURED"))
        assertFalse(releaseBarrier.contains("PREFILTER_ACTIVATION_RELEASED"))
    }
}

private fun repositoryRoot(): Path {
    var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (!Files.exists(candidate.resolve("settings.gradle.kts"))) {
        candidate = requireNotNull(candidate.parent) { "repository root was not found" }
    }
    return candidate
}
