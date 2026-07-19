package me.matsumo.fukurou

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant

internal const val BACKUP_MONITORING_PROJECTION_FILE_NAME = "backup-restore.json"
internal val DEFAULT_BACKUP_MONITORING_PROJECTION_PATH: Path = Path.of(
    "/var/lib/fukurou/monitoring-public",
    BACKUP_MONITORING_PROJECTION_FILE_NAME,
)
private const val MAX_BACKUP_MONITORING_PROJECTION_BYTES = 65_536L
private val InvocationIdPattern = Regex("^[0-9a-f]{32}$")
private val BootIdPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
internal val BackupResultCodes = setOf(
    "BACKUP_BUSY",
    "BACKUP_SIGNALLED",
    "CAPACITY_FLOOR_NOT_MET",
    "DEPLOY_IN_PROGRESS",
    "DUMP_FAILED",
    "INTEGRITY_CHECK_FAILED",
    "INVALID_CONFIGURATION",
    "INVALID_STATUS",
    "PARTIAL_SNAPSHOT_FORGET_FAILED",
    "REPOSITORY_FAILED",
    "RESTORE_CLEANUP_FAILED",
    "RESTORE_PROFILE_FAILED",
    "RESTORE_SIGNALLED",
    "RESTORE_SNAPSHOT_FAILED",
    "RESTORE_START_FAILED",
    "RESTORE_TIMEOUT",
    "RETENTION_FAILED",
    "SNAPSHOT_IDENTITY_FAILED",
    "STATUS_PUBLICATION_FAILED",
    "SUCCESS",
    "WATCHDOG_TERMINATION_FAILED",
)
private val BackupProjectionJson = Json {
    ignoreUnknownKeys = false
}

/** application-readable projection の service lifecycle。 */
@Serializable
internal data class BackupProjectionService(
    val state: MonitoringServiceState,
    val startedAt: String,
    val terminalAt: String? = null,
    val terminalSemantic: MonitoringServiceTerminalSemantic? = null,
    val invocationId: String,
    val bootId: String,
)

/** application-readable projection の latest attempt。 */
@Serializable
internal data class BackupProjectionAttempt(
    val attemptedAt: String,
    val resultCode: String,
    val invocationId: String,
    val bootId: String,
)

/** application-readable projection の job 状態。 */
@Serializable
internal data class BackupProjectionJob(
    val service: BackupProjectionService? = null,
    val lastAttempt: BackupProjectionAttempt? = null,
    val lastSuccessAt: String? = null,
)

/** root-only status から生成する secret-free projection v1。 */
@Serializable
internal data class BackupMonitoringProjection(
    val schemaVersion: Int,
    val publishedAt: String,
    val backup: BackupProjectionJob,
    val restore: BackupProjectionJob,
)

/** projection reader の typed failure。 */
internal class BackupProjectionReadException(
    val reason: MonitoringUnknownReason,
) : RuntimeException()

/** fixed projection file を regular-file/size/schema 検証して読む。 */
internal class BackupMonitoringProjectionReader(
    private val projectionPath: Path = DEFAULT_BACKUP_MONITORING_PROJECTION_PATH,
) {
    fun read(): Result<BackupMonitoringProjection> = runCatching {
        val attributes = try {
            Files.readAttributes(
                projectionPath,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            throw BackupProjectionReadException(MonitoringUnknownReason.BACKUP_PROJECTION_NOT_ACTIVATED)
        }
        if (!attributes.isRegularFile || Files.isSymbolicLink(projectionPath)) {
            throw BackupProjectionReadException(MonitoringUnknownReason.BACKUP_PROJECTION_NOT_REGULAR)
        }
        if (attributes.size() > MAX_BACKUP_MONITORING_PROJECTION_BYTES) {
            throw BackupProjectionReadException(MonitoringUnknownReason.BACKUP_PROJECTION_OVERSIZED)
        }

        val document = Files.readString(projectionPath)
        if (document.toByteArray().size > MAX_BACKUP_MONITORING_PROJECTION_BYTES) {
            throw BackupProjectionReadException(MonitoringUnknownReason.BACKUP_PROJECTION_OVERSIZED)
        }
        val projection = try {
            BackupProjectionJson.decodeFromString<BackupMonitoringProjection>(document)
        } catch (_: SerializationException) {
            throw BackupProjectionReadException(MonitoringUnknownReason.BACKUP_PROJECTION_MALFORMED)
        }

        projection.validate()
        projection
    }.recoverCatching { failure ->
        if (failure is BackupProjectionReadException) throw failure
        throw BackupProjectionReadException(MonitoringUnknownReason.BACKUP_PROJECTION_MALFORMED)
    }
}

private fun BackupMonitoringProjection.validate() {
    if (schemaVersion != 1) malformedProjection()
    val published = publishedAt.parseProjectionInstant()

    backup.validate(published)
    restore.validate(published)
}

private fun BackupProjectionJob.validate(publishedAt: Instant) {
    val lifecycle = service
    lifecycle?.validate(publishedAt)
    val attempt = lastAttempt
    attempt?.validate(publishedAt)
    lastSuccessAt?.parseProjectionInstant()?.let { successAt ->
        if (successAt.isAfter(publishedAt)) malformedProjection()
    }

    val lifecycleTerminal = lifecycle?.state == MonitoringServiceState.TERMINAL
    val terminalSuccess = lifecycle?.terminalSemantic == MonitoringServiceTerminalSemantic.SUCCESS
    val lifecycleSucceeded = lifecycleTerminal && terminalSuccess
    if (lifecycleSucceeded) {
        if (attempt == null || attempt.resultCode != "SUCCESS") malformedProjection()
        if (attempt.invocationId != lifecycle.invocationId || attempt.bootId != lifecycle.bootId) malformedProjection()
    }
}

private fun BackupProjectionService.validate(publishedAt: Instant) {
    if (!InvocationIdPattern.matches(invocationId) || !BootIdPattern.matches(bootId)) malformedProjection()
    val started = startedAt.parseProjectionInstant()
    if (started.isAfter(publishedAt)) malformedProjection()

    when (state) {
        MonitoringServiceState.RUNNING -> {
            if (terminalAt != null || terminalSemantic != null) malformedProjection()
        }
        MonitoringServiceState.TERMINAL -> {
            val terminal = terminalAt?.parseProjectionInstant() ?: malformedProjection()
            if (terminalSemantic == null) malformedProjection()
            if (terminal.isBefore(started)) malformedProjection()
            if (terminal.isAfter(publishedAt)) malformedProjection()
        }
    }
}

private fun BackupProjectionAttempt.validate(publishedAt: Instant) {
    if (!InvocationIdPattern.matches(invocationId) || !BootIdPattern.matches(bootId)) malformedProjection()
    if (resultCode !in BackupResultCodes) malformedProjection()
    if (attemptedAt.parseProjectionInstant().isAfter(publishedAt)) malformedProjection()
}

private fun String.parseProjectionInstant(): Instant {
    return runCatching { Instant.parse(this) }.getOrElse { malformedProjection() }
}

private fun malformedProjection(): Nothing {
    throw BackupProjectionReadException(MonitoringUnknownReason.BACKUP_PROJECTION_MALFORMED)
}
