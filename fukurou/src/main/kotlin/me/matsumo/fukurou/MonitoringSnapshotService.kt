package me.matsumo.fukurou

import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickStatusProvider
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatusProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val PROVIDER_OUTCOME_WINDOW: Duration = Duration.ofMinutes(30)
private val STALE_BACKUP_RUNNING_AFTER: Duration = Duration.ofHours(2)

/** monitoring route が versioned snapshot を作る境界。 */
fun interface MonitoringSnapshotService {
    suspend fun snapshot(): OpsMonitoringResponse
}

/** monitoring source を component-local failure isolation で合成する service。 */
internal class DefaultMonitoringSnapshotService(
    private val revision: String,
    private val daemonConfig: LlmDaemonConfig,
    private val tickStatusProvider: LlmDaemonTickStatusProvider,
    private val reconcilerStatusProvider: ReconcilerStatusProvider,
    private val repository: MonitoringRepository?,
    private val backupProjectionReader: BackupMonitoringProjectionReader,
    private val clock: Clock = Clock.systemUTC(),
) : MonitoringSnapshotService {

    override suspend fun snapshot(): OpsMonitoringResponse {
        val observedAt = clock.instant()
        val providerWindowStartedAt = observedAt.minus(PROVIDER_OUTCOME_WINDOW)

        return OpsMonitoringResponse(
            observedAt = observedAt.toString(),
            revision = revision,
            daemon = daemonSnapshot(),
            providers = providerSnapshot(providerWindowStartedAt, observedAt),
            reconciler = reconcilerSnapshot(),
            gaps = gapSnapshot(),
            backupRestore = backupRestoreSnapshot(observedAt),
        )
    }

    private suspend fun daemonSnapshot(): MonitoringDaemonResponse {
        val tick = runCatching(tickStatusProvider::snapshot).getOrNull()
        val base = MonitoringDaemonResponse(
            state = MonitoringComponentState.AVAILABLE,
            enabled = daemonConfig.enabled,
            cadenceSeconds = daemonConfig.pollInterval.seconds,
            lastTickAt = tick?.completedAt?.toString(),
            lastTickOutcome = tick?.outcome?.name,
        )
        val source = repository ?: return base.copy(
            state = MonitoringComponentState.UNKNOWN,
            reason = MonitoringUnknownReason.DAEMON_DATABASE_UNAVAILABLE,
        )
        val terminalResult = source.latestDaemonTerminal()
        val terminal = terminalResult.getOrNull()

        return if (terminalResult.isSuccess) {
            base.copy(
                lastInvocationTerminalAt = terminal?.occurredAt?.toString(),
                lastInvocationTerminalSemantic = terminal?.semantic,
            )
        } else {
            base.copy(
                state = MonitoringComponentState.UNKNOWN,
                reason = terminalResult.monitoringReason(
                    malformed = MonitoringUnknownReason.DAEMON_EVENT_MALFORMED,
                    bound = MonitoringUnknownReason.DAEMON_QUERY_FAILED,
                    other = MonitoringUnknownReason.DAEMON_QUERY_FAILED,
                ),
            )
        }
    }

    private suspend fun providerSnapshot(from: Instant, to: Instant): MonitoringProvidersResponse {
        val base = MonitoringProvidersResponse(
            state = MonitoringComponentState.AVAILABLE,
            windowStartedAt = from.toString(),
            windowEndedAt = to.toString(),
        )
        val source = repository ?: return base.copy(
            state = MonitoringComponentState.UNKNOWN,
            reason = MonitoringUnknownReason.PROVIDER_DATABASE_UNAVAILABLE,
        )
        val result = source.providerOutcomes(from, to)

        return if (result.isSuccess) {
            base.copy(outcomes = result.getOrThrow())
        } else {
            base.copy(
                state = MonitoringComponentState.UNKNOWN,
                reason = result.monitoringReason(
                    malformed = MonitoringUnknownReason.PROVIDER_EVENT_MALFORMED,
                    bound = MonitoringUnknownReason.PROVIDER_QUERY_BOUND_EXCEEDED,
                    other = MonitoringUnknownReason.PROVIDER_QUERY_FAILED,
                ),
            )
        }
    }

    private fun reconcilerSnapshot(): MonitoringReconcilerResponse {
        val result = runCatching(reconcilerStatusProvider::snapshot)
        val status = result.getOrNull()
        val sourceAvailable = result.isSuccess && status?.lastMaintenanceAt != null

        return MonitoringReconcilerResponse(
            state = if (sourceAvailable) MonitoringComponentState.AVAILABLE else MonitoringComponentState.UNKNOWN,
            reason = if (sourceAvailable) null else MonitoringUnknownReason.RECONCILER_STATUS_UNAVAILABLE,
            lastMaintenanceAt = status?.lastMaintenanceAt?.toString(),
            lastTransportActivityAt = status?.lastTransportActivityAt?.toString(),
            marketDataState = status?.marketDataState?.name,
        )
    }

    private suspend fun gapSnapshot(): MonitoringGapsResponse {
        val source = repository ?: return MonitoringGapsResponse(
            state = MonitoringComponentState.UNKNOWN,
            reason = MonitoringUnknownReason.GAP_DATABASE_UNAVAILABLE,
        )
        val result = source.unresolvedGaps()
        val aggregate = result.getOrNull()

        return if (aggregate != null) {
            MonitoringGapsResponse(
                state = MonitoringComponentState.AVAILABLE,
                unresolvedMarketDataCount = aggregate.marketDataCount,
                oldestMarketDataOpenedAt = aggregate.oldestMarketDataOpenedAt?.toString(),
                unresolvedInfrastructureCount = aggregate.infrastructureCount,
                oldestInfrastructureOpenedAt = aggregate.oldestInfrastructureOpenedAt?.toString(),
            )
        } else {
            MonitoringGapsResponse(
                state = MonitoringComponentState.UNKNOWN,
                reason = result.monitoringReason(
                    malformed = MonitoringUnknownReason.GAP_EVENT_MALFORMED,
                    bound = MonitoringUnknownReason.GAP_QUERY_BOUND_EXCEEDED,
                    other = MonitoringUnknownReason.GAP_QUERY_FAILED,
                ),
            )
        }
    }

    private fun backupRestoreSnapshot(observedAt: Instant): MonitoringBackupRestoreResponse {
        val result = backupProjectionReader.read()
        val projection = result.getOrNull()
        if (projection == null) {
            val reason = (result.exceptionOrNull() as? BackupProjectionReadException)?.reason
                ?: MonitoringUnknownReason.BACKUP_PROJECTION_MALFORMED

            return MonitoringBackupRestoreResponse(
                state = MonitoringComponentState.UNKNOWN,
                reason = reason,
            )
        }
        val publishedAt = runCatching { Instant.parse(projection.publishedAt) }.getOrNull()
        val publishedAtInvalid = publishedAt == null || publishedAt.isAfter(observedAt)
        val hasStaleRunningService = projection.jobs().any { job -> job.service.isStaleRunning(observedAt) }
        val staleRunning = publishedAtInvalid || hasStaleRunningService

        return MonitoringBackupRestoreResponse(
            state = if (staleRunning) MonitoringComponentState.UNKNOWN else MonitoringComponentState.AVAILABLE,
            reason = if (staleRunning) MonitoringUnknownReason.BACKUP_PROJECTION_STALE_RUNNING else null,
            projectionPublishedAt = projection.publishedAt,
            backup = projection.backup.toResponse(),
            restore = projection.restore.toResponse(),
        )
    }
}

private fun Result<*>.monitoringReason(
    malformed: MonitoringUnknownReason,
    bound: MonitoringUnknownReason,
    other: MonitoringUnknownReason,
): MonitoringUnknownReason {
    return when (exceptionOrNull()) {
        is MonitoringMalformedEventException -> malformed
        is MonitoringQueryBoundExceededException -> bound
        else -> other
    }
}

private fun BackupMonitoringProjection.jobs(): List<BackupProjectionJob> = listOf(backup, restore)

private fun BackupProjectionService?.isStaleRunning(observedAt: Instant): Boolean {
    if (this?.state != MonitoringServiceState.RUNNING) return false
    val started = runCatching { Instant.parse(startedAt) }.getOrNull() ?: return true

    return Duration.between(started, observedAt) > STALE_BACKUP_RUNNING_AFTER
}

private fun BackupProjectionJob.toResponse(): MonitoringBackupJobResponse {
    return MonitoringBackupJobResponse(
        serviceState = service?.state,
        serviceStartedAt = service?.startedAt,
        serviceTerminalAt = service?.terminalAt,
        serviceTerminalSemantic = service?.terminalSemantic,
        lastAttemptAt = lastAttempt?.attemptedAt,
        lastAttemptResult = lastAttempt?.resultCode,
        lastSuccessAt = lastSuccessAt,
    )
}
