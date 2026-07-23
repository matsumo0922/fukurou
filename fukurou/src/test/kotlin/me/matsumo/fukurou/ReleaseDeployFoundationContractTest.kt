package me.matsumo.fukurou

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
    fun `deploy workflow resolves builds and deploys an immutable digest`() {
        val workflow = Files.readString(root.resolve(".github/workflows/deploy.yml"))
        val resolveJob = workflow.substringAfter("\n  resolve:").substringBefore("\n  build:")
        val buildJob = workflow.substringAfter("\n  build:").substringBefore("\n  deploy:")
        val deployJob = workflow.substringAfter("\n  deploy:")

        assertFalse(workflow.contains("\n  quality:"))
        assertFalse(workflow.contains("rollback_reason:"))
        assertFalse(workflow.contains("migration_rollback_mode:"))
        assertFalse(workflow.contains("deploy-intent-resolver"))
        assertFalse(workflow.contains("deploy-bundle"))
        assertFalse(workflow.contains("DEPLOY_SIGNING_PRIVATE_KEY"))

        assertTrue(resolveJob.contains("git merge-base --is-ancestor \"\${deploy_sha}\" origin/main"))
        assertTrue(resolveJob.contains("if [[ \"\${GITHUB_EVENT_NAME}\" == \"push\" ]]"))
        assertTrue(resolveJob.contains("PRODUCTION_REVISION_URL"))
        assertTrue(resolveJob.contains("git merge-base --is-ancestor \"\${current_revision}\" \"\${deploy_sha}\""))
        assertFalse(resolveJob.contains("packages: write"))

        assertTrue(buildJob.contains("needs: resolve"))
        assertTrue(buildJob.contains("ref: \${{ needs.resolve.outputs.deploy_sha }}"))
        assertTrue(buildJob.contains("image_digest: \${{ steps.build_push.outputs.digest }}"))
        assertTrue(buildJob.contains("FUKUROU_REVISION=\${{ needs.resolve.outputs.deploy_sha }}"))
        assertTrue(buildJob.contains("packages: write"))

        assertTrue(deployJob.contains("--event \"\${{ needs.resolve.outputs.workflow_event }}\""))
        assertTrue(deployJob.contains("--image-digest \"\${{ needs.build.outputs.image_digest }}\""))
        assertTrue(deployJob.contains("--deployment-id \"github-\${{ github.run_id }}-\${{ github.run_attempt }}\""))
        assertFalse(deployJob.contains("--print-contract-version"))
        assertFalse(deployJob.contains("--print-schema-sensitive-paths-sha256"))
    }

    @Test
    fun `executor keeps a straight digest pinned cutover flow`() {
        val executor = Files.readString(root.resolve("scripts/deploy/deploy-fukurou"))
        val main = executor.substringAfter("main() {")
        val deployCompose = executor.substringAfter("deploy_compose() {").substringBefore("\n}\n\nmain()")
        val composeCutover = executor.substringAfter("compose_cutover() {").substringBefore("\n}\n\nresume_launches_idempotently")

        assertFalse(executor.contains("--bundle"))
        assertFalse(executor.contains("--signature"))
        assertFalse(executor.contains("verify_bundle_signature"))
        assertFalse(executor.contains("capabilityCatalog"))
        assertFalse(executor.contains("run_candidate_preflight"))
        assertFalse(executor.contains("run_cli_acceptance_gate"))
        assertFalse(executor.contains("recover_unfinished_deployments"))
        assertFalse(executor.contains("journal_state"))

        assertTrue(main.indexOf("exec 9>") < main.indexOf("authoritative_descendant_check"))
        assertTrue(main.indexOf("authoritative_descendant_check") < main.indexOf("deploy_compose"))
        assertTrue(executor.contains("[[ \"\${WORKFLOW_EVENT}\" == \"push\" ]] || return 0"))
        assertTrue(executor.contains("IMAGE_REFERENCE=\"\${IMAGE_REPOSITORY}@\${CANDIDATE_DIGEST}\""))

        assertTrue(deployCompose.indexOf("pull_candidate") < deployCompose.indexOf("perform_migration"))
        assertTrue(deployCompose.indexOf("perform_migration") < deployCompose.indexOf("compose_cutover"))
        assertTrue(composeCutover.indexOf("docker compose") < composeCutover.indexOf("wait_for_health"))
        assertTrue(composeCutover.indexOf("wait_for_health") < composeCutover.indexOf("verify_running_digest"))
        assertTrue(composeCutover.indexOf("verify_running_revision") < composeCutover.indexOf("verify_running_digest"))
    }

    @Test
    fun `paused state preserves one maintenance incident until gap close`() {
        val executor = Files.readString(root.resolve("scripts/deploy/deploy-fukurou"))
        val startPause = executor.substringAfter("start_new_pause() {").substringBefore("\n}\n\nadopt_acknowledged_pause")
        val adoptPause = executor.substringAfter("adopt_acknowledged_pause() {").substringBefore("\n}\n\nperform_migration")
        val completeClose = executor.substringAfter("complete_pending_close() {").substringBefore("\n}\n\nrecover_healthy_pending_close")

        listOf(
            "PAUSED_BEFORE_MIGRATION",
            "MIGRATION_DONE",
            "CUTOVER_STARTED",
            "CUTOVER_HEALTHY_PENDING_CLOSE",
            "ACKNOWLEDGED_FOR_REDEPLOY",
        ).forEach { phase -> assertTrue(executor.contains(phase)) }
        assertTrue(executor.contains("--acknowledge-paused-state"))
        assertTrue(executor.contains("MARKER_INCIDENT_DEPLOYMENT_ID"))
        assertTrue(executor.contains("MARKER_GAP_ID"))
        assertTrue(executor.contains("MARKER_MAINTENANCE_GENERATION"))

        assertTrue(startPause.indexOf("persist_paused_state") < startPause.indexOf("maintenance-cas"))
        assertTrue(startPause.indexOf("maintenance-cas") < startPause.indexOf("drain_launches"))
        assertTrue(startPause.indexOf("drain_launches") < startPause.indexOf("append_gap_event OPEN"))
        assertFalse(adoptPause.contains("drain_launches"))
        assertFalse(adoptPause.contains("maintenance-cas"))
        assertTrue(completeClose.indexOf("resume_launches_idempotently") < completeClose.indexOf("close_gap_idempotently"))
        assertTrue(completeClose.indexOf("close_gap_idempotently") < completeClose.indexOf("clear_paused_state"))
        assertTrue(executor.contains("running_digest_matches_marker && health_is_currently_ready"))
        assertTrue(executor.contains("LEGACY_DRAIN_SENTINEL"))
        assertTrue(executor.contains("legacy_path_is_empty \"\${LEGACY_STATE_ROOT}\""))
    }

    @Test
    fun `DB helper marker and deploy backup use the closed interfaces`() {
        val executor = Files.readString(root.resolve("scripts/deploy/deploy-fukurou"))
        val databaseHelper = Files.readString(root.resolve("scripts/deploy/fukurou-deploy-db"))
        val backup = Files.readString(root.resolve("scripts/backup/backup-fukurou"))
        val dockerfile = Files.readString(root.resolve("Dockerfile"))

        listOf(
            "scripts/deploy/fukurou-deploy-db",
            "scripts/deploy/sql/deploy-foundation-v1-indexes.sql",
            "scripts/deploy/sql/deploy-foundation-v1.sql",
            "scripts/deploy/sql/mcp-role.sql",
        ).forEach { path ->
            assertTrue(executor.contains(path))
            assertTrue(databaseHelper.contains(path))
        }
        assertTrue(executor.contains("LC_ALL=C sort"))
        assertTrue(executor.contains("printf '%s\\0%s\\0'"))
        assertTrue(executor.contains("ROOT_DB_HELPER_INSTALLATION_CHANGED"))
        assertTrue(executor.contains("CANDIDATE_DB_HELPER_MARKER_MISMATCH"))
        assertTrue(databaseHelper.contains("write-install-marker"))
        assertTrue(databaseHelper.contains("sync -f \"\${marker_directory}\""))
        assertTrue(dockerfile.contains("FROM debian:bookworm-slim AS db-helper-manifest"))
        assertTrue(dockerfile.contains("/usr/local/share/fukurou/db-helper-manifest.sha256"))
        val installMarkerDirectory = "install -d -o root -g root -m 0755 /usr/local/share/fukurou"
        assertTrue(
            dockerfile.contains(installMarkerDirectory),
            "the marker directory must be created with a traversable mode before the marker COPY runs, otherwise " +
                "COPY --chmod=0444 also restricts the auto-created parent directory and a non-root docker run " +
                "(deploy-fukurou's read_candidate_db_helper_manifest) cannot read the marker file",
        )
        assertTrue(
            dockerfile.indexOf(installMarkerDirectory) <
                dockerfile.indexOf("/usr/local/share/fukurou/db-helper-manifest.sha256"),
        )

        assertTrue(executor.contains("\"\${BACKUP_HELPER}\" --invoked-by-deploy"))
        assertTrue(backup.contains("if [[ \"\${1:-}\" == \"--invoked-by-deploy\" ]]"))
        assertTrue(backup.contains("if [[ \"\${BACKUP_INVOKED_BY_DEPLOY}\" != \"true\" ]]"))
        assertTrue(backup.contains("backup_probe_deploy_lock"))
    }

    @Test
    fun `production compose and sudoers keep the existing authority boundary`() {
        val compose = Files.readString(root.resolve("docker-compose.prod.yml"))
        val sudoers = Files.readString(root.resolve("scripts/deploy/sudoers-fukurou"))
        val dockerfile = Files.readString(root.resolve("Dockerfile"))

        assertTrue(compose.contains("image: \${FUKUROU_IMAGE_REFERENCE:?FUKUROU_IMAGE_REFERENCE must be an immutable digest}"))
        assertFalse(compose.contains("FUKUROU_IMAGE_TAG"))
        assertTrue(dockerfile.contains("ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]"))
        assertTrue(
            compose.contains("init: true"),
            "ktor service must run under an init process (tini) so PID 1 reaps orphaned descendants " +
                "now that the C runtime supervisor no longer performs that reaping",
        )
        assertEquals(
            "github-runner ALL=(root) NOPASSWD: /usr/local/sbin/deploy-fukurou",
            sudoers.lineSequence().first { it.startsWith("github-runner ") },
        )
        assertFalse(sudoers.contains("docker"))
        assertFalse(sudoers.contains("/bin/bash"))
        assertFalse(sudoers.contains("/bin/sh"))
    }

    @Test
    fun `retired deploy artifacts are absent`() {
        listOf(
            "scripts/deploy/deploy-contract-selftest",
            "scripts/deploy/deploy-e2e-selftest",
            "scripts/deploy/deploy-runtime-selftest",
            "scripts/deploy/deploy-intent-resolver",
            "scripts/deploy/deploy-intent-resolver-selftest",
            "scripts/deploy/canary-compose-selftest",
            "scripts/deploy/deploy-bundle.schema.json",
            "scripts/deploy/deploy-capability-catalog-v1.json",
            "scripts/deploy/deploy-contract-v1.json",
            "scripts/deploy/deploy-public-key.pem",
            "scripts/deploy/deploy-schema-sensitive-paths-v1.txt",
            "scripts/runtime/fukurou-llm-agent-launcher.c",
            "scripts/runtime/fukurou-mcp-launcher.c",
            "scripts/runtime/fukurou-runtime-supervisor.c",
            "scripts/runtime/fukurou-runtime-proxy.h",
            "scripts/runtime/fukurou-runtime-protocol.h",
            "scripts/runtime/fukurou-mcp-canary-client.mjs",
            "scripts/runtime/validate-llm-launcher-probe.mjs",
            "scripts/mcp-credential-isolation-check",
            "scripts/mcp-credential-isolation-check-selftest",
            "fukurou/src/main/kotlin/me/matsumo/fukurou/LaunchFenceDatabaseProbeMain.kt",
        ).forEach { path -> assertFalse(Files.exists(root.resolve(path)), path) }
        assertTrue(Files.exists(root.resolve("scripts/runtime/fukurou-cli-canary-mcp.mjs")))
    }
}

private fun repositoryRoot(): Path {
    var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (!Files.exists(candidate.resolve("settings.gradle.kts"))) {
        candidate = requireNotNull(candidate.parent) { "repository root was not found" }
    }
    return candidate
}
