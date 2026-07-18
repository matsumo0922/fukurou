package me.matsumo.fukurou

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isExecutable
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
        assertTrue(installer.contains("readonly LIBEXEC_DIR=/usr/local/libexec/fukurou"))
        assertTrue(installer.contains("readonly SHARE_DIR=/usr/local/share/fukurou"))
        assertTrue(installer.contains("readonly UNIT_DIR=/etc/systemd/system"))
        assertTrue(installer.contains("[[ \${EUID} -eq 0 ]]"))
        assertTrue(installer.contains("-m 0555 \"\${SCRIPT_DIR}/\${artifact}\""))
        assertTrue(installer.contains("-m 0444 \"\${UNIT_SOURCE_DIR}/\${artifact}\""))
        assertTrue(installer.contains("-m 0700 \"\${BACKUP_ROOT}\" \"\${STATUS_DIR}\" \"\${SECRET_DIR}\""))
        assertTrue(installer.contains("disable \${artifact} before installing reviewed artifacts"))
        assertFalse(installer.contains("systemctl enable"))
        assertFalse(installer.contains("restic -r \"\${BACKUP_ROOT}\" init"))
        assertTrue(common.contains("FUKUROU_BACKUP_SHARE_DIRECTORY"))
        assertTrue(installer.contains("FUKUROU_BACKUP_SHARE_DIRECTORY=\"\${SHARE_DIR}\""))
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
            assertTrue(service.contains("KillMode=control-group"), name)
            assertTrue(service.contains("NoNewPrivileges=yes"), name)
            assertTrue(service.contains("ProtectSystem=strict"), name)
            assertTrue(service.contains("ReadWritePaths=/srv/fukurou/backups/postgres /srv/fukurou/monitoring /var/lock"), name)
            assertFalse(service.lowercase().contains("password="), name)
            assertFalse(service.lowercase().contains("secret="), name)
        }

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
    }
}

private fun backupRepositoryRoot(): Path {
    var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (!Files.exists(candidate.resolve("settings.gradle.kts"))) {
        candidate = requireNotNull(candidate.parent) { "repository root was not found" }
    }
    return candidate
}
