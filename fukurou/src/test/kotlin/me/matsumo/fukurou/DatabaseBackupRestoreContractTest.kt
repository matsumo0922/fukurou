package me.matsumo.fukurou

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PostgreSQL backup / restore のrepository・install・schema境界を検証するテスト。 */
class DatabaseBackupRestoreContractTest {
    private val root = backupRepositoryRoot()
    private val backupDirectory = root.resolve("scripts/backup")

    @Test
    fun `backup shell entrypoints have valid syntax and fixed installed paths`() {
        val scripts = listOf(
            "backup-common",
            "backup-fukurou",
            "restore-fukurou",
            "install-fukurou-backup",
        )

        scripts.forEach { name ->
            val script = backupDirectory.resolve(name)
            val process = ProcessBuilder("bash", "-n", script.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(0, process.waitFor(), "$name: $output")
            assertTrue(script.isExecutable(), "$name must be executable in the reviewed tree")
        }

        val installer = Files.readString(backupDirectory.resolve("install-fukurou-backup"))
        val common = Files.readString(backupDirectory.resolve("backup-common"))
        val backup = Files.readString(backupDirectory.resolve("backup-fukurou"))
        val restore = Files.readString(backupDirectory.resolve("restore-fukurou"))
        assertTrue(installer.contains("readonly LIBEXEC_DIR=/usr/local/libexec/fukurou"))
        assertTrue(installer.contains("readonly SHARE_DIR=/usr/local/share/fukurou"))
        assertTrue(installer.contains("readonly UNIT_DIR=/etc/systemd/system"))
        assertTrue(installer.contains("[[ \${EUID} -eq 0 ]]"))
        assertTrue(installer.contains("-m 0555 \"\${SCRIPT_DIR}/\${artifact}\""))
        assertTrue(installer.contains("-m 0444 \"\${UNIT_SOURCE_DIR}/\${artifact}\""))
        assertTrue(
            installer.contains(
                "-m 0700 \"\${BACKUP_PARENT}\" \"\${BACKUP_ROOT}\" \"\${STATUS_DIR}\" \"\${SECRET_DIR}\"",
            ),
        )
        assertTrue(installer.contains("verify_owner_mode \"\${BACKUP_PARENT}\" 700"))
        assertTrue(installer.contains("acquire_install_lock"))
        assertTrue(installer.contains("all_rollout_units_inactive"))
        assertTrue(installer.contains("require_quiescent_rollout"))
        assertTrue(installer.contains("verify_repository_snapshot \"\${snapshot_id}\""))
        assertTrue(installer.contains(".backup.lastAttempt.resultCode == \"SUCCESS\""))
        assertTrue(installer.contains(".backup.lastAttempt.retentionSucceeded == true"))
        assertTrue(installer.contains(".restore.lastAttempt.resultCode == \"SUCCESS\""))
        assertTrue(installer.contains(".backup.lastAttempt.snapshotId == .backup.lastSuccess.snapshotId"))
        assertTrue(installer.contains(".restore.lastAttempt.snapshotId == .restore.lastSuccess.snapshotId"))
        assertTrue(installer.contains("verify_no_restore_owned_resources"))
        assertTrue(installer.contains("serviceInvocationId"))
        assertTrue(installer.contains("serviceBootId"))
        assertTrue(installer.contains("current_boot_id"))
        assertTrue(installer.contains("journalctl --unit=\"\${unit}\" --boot=0 --output=json"))
        assertTrue(installer.contains("_SYSTEMD_INVOCATION_ID"))
        assertTrue(installer.contains("fukurou-backup: result=SUCCESS"))
        assertFalse(installer.contains("ExecMainStartTimestamp"))
        assertTrue(installer.contains("timeout --signal=TERM --kill-after=5"))
        assertTrue(installer.contains("label=\${RESTORE_LABEL_KEY}"))
        assertTrue(installer.contains("readonly INSTALL_MARKER=\${SHARE_DIR}/backup-installation-v1.json"))
        assertTrue(installer.contains("calculate_installed_artifact_hash"))
        assertTrue(installer.contains("write_install_marker || die"))
        assertTrue(installer.contains("verify_owner_mode \"\${INSTALL_MARKER}\" 400"))
        assertTrue(installer.contains("verify_install_marker \"\${INSTALL_MARKER}\" \"\${aggregate_hash}\""))
        assertTrue(installer.contains("readonly ROLLOUT_MAX_EVIDENCE_AGE_SECONDS=86400"))
        assertTrue(installer.contains("verify_rollout_freshness_epochs"))
        assertTrue(installer.contains("die \"disable \${timer} before continuing\" 75"))
        assertFalse(installer.contains("systemctl enable"))
        assertFalse(installer.contains("restic -r \"\${BACKUP_ROOT}\" init"))
        assertTrue(common.contains("FUKUROU_BACKUP_SHARE_DIRECTORY"))
        assertTrue(common.contains("FUKUROU_BACKUP_STDIN_PATH:-/postgres.dump"))
        assertTrue(common.contains("INVOCATION_ID"))
        assertTrue(common.contains("/proc/sys/kernel/random/boot_id"))
        assertFalse(backup.contains("readonly BACKUP_STDIN_PATH="))
        assertTrue(backup.contains("df -B1 --output=avail"))
        assertFalse(backup.contains("df -PB1 --output=avail"))
        assertTrue(restore.contains("dump \"\${snapshot_id}\" \"\${BACKUP_STDIN_PATH}\""))
        assertTrue(installer.contains("FUKUROU_BACKUP_SHARE_DIRECTORY=\"\${SHARE_DIR}\""))
        val validationWorkflow = root.resolve(".github/workflows/deploy-validation.yml").readText()
        assertTrue(
            validationWorkflow.contains(
                "sudo env FUKUROU_BACKUP_POSTGRES_SELFTEST_REQUIRE=true scripts/backup/backup-postgres-selftest",
            ),
        )
    }

    @Test
    fun `root services are hardened bounded and disabled by default`() {
        val unitDirectory = backupDirectory.resolve("systemd")
        val services = listOf(
            "fukurou-postgres-backup.service" to "/usr/local/libexec/fukurou/backup-fukurou",
            "fukurou-postgres-restore-drill.service" to "/usr/local/libexec/fukurou/restore-fukurou",
        )

        services.forEach { (name, entrypoint) ->
            val service = Files.readString(unitDirectory.resolve(name))

            assertTrue(service.contains("User=root\nGroup=root\nUMask=0077"), name)
            assertTrue(service.contains("Environment=FUKUROU_BACKUP_SHARE_DIRECTORY=/usr/local/share/fukurou"), name)
            assertTrue(service.contains("ExecStart=$entrypoint"), name)
            assertTrue(service.contains("TimeoutStartSec="), name)
            assertTrue(service.contains("TimeoutStopSec=7min"), name)
            assertTrue(service.contains("KillMode=control-group"), name)
            assertTrue(service.contains("NoNewPrivileges=yes"), name)
            assertTrue(service.contains("ProtectSystem=strict"), name)
            assertTrue(service.contains("ReadWritePaths=/srv/fukurou/backups/postgres /srv/fukurou/monitoring /var/lock"), name)
            assertFalse(service.lowercase().contains("password="), name)
            assertFalse(service.lowercase().contains("secret="), name)
        }

        val restoreService = Files.readString(unitDirectory.resolve("fukurou-postgres-restore-drill.service"))
        assertTrue(restoreService.contains("RuntimeDirectory=fukurou-restore"))
        assertTrue(restoreService.contains("RuntimeDirectoryMode=0700"))

        val daily = Files.readString(unitDirectory.resolve("fukurou-postgres-backup.timer"))
        val weekly = Files.readString(unitDirectory.resolve("fukurou-postgres-restore-drill.timer"))
        assertTrue(daily.contains("OnCalendar=daily"))
        assertTrue(weekly.contains("OnCalendar=Sun *-*-* 03:00:00"))
        listOf(daily, weekly).forEach { timer ->
            assertTrue(timer.contains("Persistent=true"))
            assertTrue(timer.contains("RandomizedDelaySec="))
        }
    }

    @Test
    fun `backup rollout does not expand deploy or application authority`() {
        val sudoers = Files.readAllLines(root.resolve("scripts/deploy/sudoers-fukurou"))
            .filterNot { line -> line.isBlank() || line.startsWith("#") }
        val workflow = Files.readString(root.resolve(".github/workflows/deploy.yml"))
        val executor = Files.readString(root.resolve("scripts/deploy/deploy-fukurou"))
        val compose = Files.readString(root.resolve("docker-compose.prod.yml"))
        val application = Files.readString(root.resolve("fukurou/src/main/kotlin/me/matsumo/fukurou/Application.kt"))
        val backupSources = Files.walk(backupDirectory).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) }
                .map { path -> Files.readString(path) }
                .toList()
                .joinToString("\n")
        }

        assertEquals(
            listOf("github-runner ALL=(root) NOPASSWD: /usr/local/sbin/deploy-fukurou"),
            sudoers,
        )
        assertTrue(workflow.contains("sudo /usr/local/sbin/deploy-fukurou"))
        assertFalse(workflow.contains("backup-fukurou"))
        assertFalse(executor.contains("backup-fukurou"))
        assertTrue(compose.contains("container_name: fukurou-postgres"))
        assertTrue(backupSources.contains("fukurou-postgres"))
        assertFalse(compose.contains("backup-status.json"))
        assertFalse(application.contains("backup-status.json"))
        assertFalse(backupSources.contains("docker-compose.prod.yml"))
        assertFalse(backupSources.contains("sudoers-fukurou"))
    }

    @Test
    fun `backup semantic and PostgreSQL selftests are wired into CI`() {
        val workflow = Files.readString(root.resolve(".github/workflows/deploy-validation.yml"))

        assertTrue(workflow.contains("scripts/backup/**"))
        assertTrue(workflow.contains("scripts/backup/backup-selftest"))
        assertTrue(workflow.contains("scripts/backup/restore-selftest"))
        assertTrue(workflow.contains("scripts/backup/backup-postgres-selftest"))
        assertTrue(workflow.contains("scripts/backup/install-selftest"))
        assertTrue(workflow.contains("postgresql-client restic"))
        assertTrue(workflow.contains("TradingTables.kt"))
        assertTrue(workflow.contains("TradingPersistenceBootstrap.kt"))
        assertTrue(workflow.contains("deploy-foundation-v1.sql"))
    }

    @Test
    fun `restore inventory follows every code owned schema authority`() {
        val inventory = Files.readAllLines(backupDirectory.resolve("restore-inventory-v1.txt"))
            .filterNot { line -> line.isBlank() || line.startsWith("#") }
            .toSet()
        val exposed = Files.readString(
            root.resolve("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingTables.kt"),
        )
        val sqlAuthorities = listOf(
            root.resolve("scripts/deploy/sql/deploy-foundation-v1.sql"),
            root.resolve("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingPersistenceBootstrap.kt"),
            root.resolve("fukurou/src/main/kotlin/me/matsumo/fukurou/EvaluationReportPersistence.kt"),
        ).map(Files::readString)
        val expected = mutableSetOf<String>()

        Regex("""Table\("([a-z][a-z0-9_]*)"\)""")
            .findAll(exposed)
            .forEach { match -> expected += "table public ${match.groupValues[1]}" }
        sqlAuthorities.forEach { source ->
            Regex(
                """(?i)CREATE\s+(?:OR\s+REPLACE\s+)?(TABLE|SEQUENCE|VIEW)\s+""" +
                    """(?:IF\s+NOT\s+EXISTS\s+)?([a-z][a-z0-9_]*)""",
            )
                .findAll(source)
                .forEach { match -> expected += "${match.groupValues[1].lowercase()} public ${match.groupValues[2]}" }
        }
        assertTrue(sqlAuthorities.last().contains("event_id BIGSERIAL"))
        expected += "sequence public evaluation_report_job_events_event_id_seq"

        assertEquals(expected, inventory)

        val criticalTables = Files.readAllLines(backupDirectory.resolve("restore-critical-tables-v1.txt"))
            .filterNot { line -> line.isBlank() || line.startsWith("#") }
            .map { line -> line.split(' ').last() }
        val inventoryTables = inventory.filter { line -> line.startsWith("table public ") }
            .map { line -> line.substringAfterLast(' ') }
            .toSet()
        assertTrue(criticalTables.all(inventoryTables::contains))
    }

    @Test
    fun `restore invariant columns and critical primary keys follow schema authority`() {
        val exposed = Files.readString(
            root.resolve("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingTables.kt"),
        )
        val foundation = Files.readString(root.resolve("scripts/deploy/sql/deploy-foundation-v1.sql"))
        val bootstrap = Files.readString(
            root.resolve("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingPersistenceBootstrap.kt"),
        )
        val exposedTables = parseExposedSchemaTables(exposed)
        val foundationTables = parseSqlSchemaTables(foundation)
        val bootstrapTables = parseSqlSchemaTables(bootstrap)
        val schemaTables = exposedTables + foundationTables
        val invariantSql = Files.readString(backupDirectory.resolve("restore-readonly-invariants-v1.sql"))
        val invariantReferences = parseQualifiedInvariantReferences(invariantSql)
        val expectedInvariantReferences = setOf(
            "paper_account.btc_quantity",
            "paper_account.cash_jpy",
            "paper_account.id",
            "paper_account.initial_cash_jpy",
            "paper_account.mode",
            "paper_account.total_equity_jpy",
            "runtime_config_versions.status",
            "runtime_config_versions.id",
            "runtime_config_values.version_id",
            "executions.account_epoch_id",
            "executions.execution_semantics_version",
            "executions.runtime_config_hash",
            "paper_account_epochs.id",
        )

        assertEquals(expectedInvariantReferences, invariantReferences)
        invariantReferences.forEach { reference ->
            val (table, column) = reference.split('.', limit = 2)
            assertTrue(schemaTables.getValue(table).columns.contains(column), "$reference is absent from schema authority")
        }

        val criticalTables = Files.readAllLines(backupDirectory.resolve("restore-critical-tables-v1.txt"))
            .filterNot { line -> line.isBlank() || line.startsWith("#") }
            .map { line -> line.substringAfterLast(' ') }
        criticalTables.forEach { table ->
            assertTrue(schemaTables.getValue(table).hasPrimaryKey, "$table has no primary key in schema authority")
            if (foundationTables.containsKey(table)) {
                assertTrue(
                    bootstrapTables.getValue(table).hasPrimaryKey,
                    "$table has no primary key in bootstrap schema authority",
                )
            }
        }
        assertFalse(
            parseExposedSchemaTables(
                """object FixtureTable : Table("fixture") {
                    |    val id = uuid("id")
                    |    // override val primaryKey = PrimaryKey(id)
                    |}
                """.trimMargin(),
            ).getValue("fixture").hasPrimaryKey,
        )
        assertFalse(
            parseSqlSchemaTables(
                """CREATE TABLE fixture (
                    |    id UUID,
                    |    -- id UUID PRIMARY KEY
                    |);
                """.trimMargin(),
            ).getValue("fixture").hasPrimaryKey,
        )
    }

    @Test
    fun `operator documentation states the limited recovery contract`() {
        val readme = Files.readString(root.resolve("README.md"))
        val deploy = Files.readString(root.resolve("docs/deploy.md"))
        val design = Files.readString(root.resolve("docs/design.md"))

        listOf(readme, deploy, design).forEach { document ->
            assertTrue(document.contains("same-NAS", ignoreCase = true) || document.contains("同一 NAS"))
            assertTrue(document.contains("newest 14 daily"))
            assertTrue(document.contains("PITR"))
            assertTrue(document.contains("RPO/RTO"))
        }
        assertTrue(deploy.contains("restic -r /srv/fukurou/backups/postgres init --repository-version 2"))
        assertTrue(deploy.contains("初回 backup / restore gate"))
        assertTrue(deploy.contains("systemctl enable --now fukurou-postgres-backup.timer"))
        assertTrue(deploy.contains("Production database replacement boundary"))
        assertTrue(deploy.contains("github-runner`のsudo authorityは`/usr/local/sbin/deploy-fukurou`だけ"))
        val restoreContainerInventory = deploy.indexOf("docker ps -a --filter \"label=\${restore_label}\"")
        val restoreNetworkInventory = deploy.indexOf("docker network ls --filter \"label=\${restore_label}\"")
        val restoreVolumeInventory = deploy.indexOf("docker volume ls --filter \"label=\${restore_label}\"")
        assertTrue(restoreContainerInventory >= 0)
        assertTrue(restoreContainerInventory < restoreNetworkInventory)
        assertTrue(restoreNetworkInventory < restoreVolumeInventory)
        assertTrue(deploy.contains("data-at-rest incident"))
        assertTrue(deploy.contains("global pruneは使わない"))
    }
}

private data class SchemaTableContract(
    val columns: Set<String>,
    val hasPrimaryKey: Boolean,
)

private fun parseExposedSchemaTables(source: String): Map<String, SchemaTableContract> {
    val tableBlocks = Regex(
        """(?ms)^object\s+\w+\s*:\s*Table\("([a-z][a-z0-9_]*)"\)\s*\{(.*?)(?=^object\s+\w+\s*:\s*Table|\z)""",
    )
    val column = Regex("""(?ms)^\s*val\s+\w+\s*=\s*\w+\(\s*"([a-z][a-z0-9_]*)"""")

    return tableBlocks.findAll(source).associate { match ->
        val body = match.groupValues[2]
        match.groupValues[1] to SchemaTableContract(
            columns = column.findAll(body).map { it.groupValues[1] }.toSet(),
            hasPrimaryKey = Regex(
                """(?m)^\s*override\s+val\s+primaryKey\s*=\s*PrimaryKey\s*\(""",
            ).containsMatchIn(body),
        )
    }
}

private fun parseSqlSchemaTables(source: String): Map<String, SchemaTableContract> {
    val tableBlocks = Regex(
        """(?ims)^[ \t]*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-z][a-z0-9_]*)\s*\((.*?)^[ \t]*\)[ \t]*;?[ \t]*$""",
    )
    val column = Regex("""(?m)^[ \t]+([a-z][a-z0-9_]*)\s+[A-Z]""")
    val primaryKey = Regex(
        """(?im)^[ \t]*(?:[a-z][a-z0-9_]*\s+[^,\n]*\bPRIMARY\s+KEY\b|PRIMARY\s+KEY\s*\()""",
    )

    return tableBlocks.findAll(source).associate { match ->
        val body = match.groupValues[2]
        match.groupValues[1] to SchemaTableContract(
            columns = column.findAll(body).map { it.groupValues[1] }.toSet(),
            hasPrimaryKey = primaryKey.containsMatchIn(body),
        )
    }
}

private fun parseQualifiedInvariantReferences(source: String): Set<String> {
    val aliases = Regex(
        """(?i)\b(?:FROM|JOIN)\s+public\.([a-z][a-z0-9_]*)\s+(?:AS\s+)?([a-z][a-z0-9_]*)""",
    ).findAll(source).associate { match -> match.groupValues[2] to match.groupValues[1] }

    return Regex("""\b([a-z][a-z0-9_]*)\.([a-z][a-z0-9_]*)\b""")
        .findAll(source)
        .mapNotNull { match ->
            aliases[match.groupValues[1]]?.let { table -> "$table.${match.groupValues[2]}" }
        }
        .toSet()
}

private fun backupRepositoryRoot(): Path {
    var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (!Files.exists(candidate.resolve("settings.gradle.kts"))) {
        candidate = requireNotNull(candidate.parent) { "repository root was not found" }
    }
    return candidate
}
