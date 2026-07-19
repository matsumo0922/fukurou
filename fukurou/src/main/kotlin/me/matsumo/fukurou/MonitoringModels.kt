package me.matsumo.fukurou

import kotlinx.serialization.Serializable

/** monitoring component の利用可能状態。 */
@Serializable
enum class MonitoringComponentState {
    AVAILABLE,
    UNKNOWN,
}

/** source-local failure を表す安定 reason code。 */
@Serializable
enum class MonitoringUnknownReason {
    DAEMON_DATABASE_UNAVAILABLE,
    DAEMON_QUERY_FAILED,
    DAEMON_EVENT_MALFORMED,
    PROVIDER_DATABASE_UNAVAILABLE,
    PROVIDER_QUERY_FAILED,
    PROVIDER_QUERY_BOUND_EXCEEDED,
    PROVIDER_EVENT_MALFORMED,
    RECONCILER_STATUS_UNAVAILABLE,
    GAP_DATABASE_UNAVAILABLE,
    GAP_QUERY_FAILED,
    GAP_QUERY_BOUND_EXCEEDED,
    GAP_EVENT_MALFORMED,
    BACKUP_PROJECTION_NOT_ACTIVATED,
    BACKUP_PROJECTION_NOT_REGULAR,
    BACKUP_PROJECTION_OVERSIZED,
    BACKUP_PROJECTION_MALFORMED,
    BACKUP_PROJECTION_STALE_RUNNING,
}

/** daemon invocation の安定 terminal semantic。 */
@Serializable
enum class MonitoringDaemonTerminalSemantic {
    NORMAL_COMPLETION,
    NO_TRADE,
    SAFETY_DENIED,
    TIMED_OUT,
    RUNNER_FAILED,
    CALLER_CANCELLED,
    RESTART_INTERRUPTED,
    LEGACY_UNCLASSIFIED,
}

/** root-owned service lifecycle の公開状態。 */
@Serializable
enum class MonitoringServiceState {
    RUNNING,
    TERMINAL,
}

/** root-owned service terminal の安定 semantic。 */
@Serializable
enum class MonitoringServiceTerminalSemantic {
    SUCCESS,
    FAILURE,
    UNKNOWN,
}

/** provider 別 30 分 outcome aggregate。 */
@Serializable
data class MonitoringProviderOutcomeResponse(
    val provider: String,
    val totalCount: Int,
    val failureCount: Int,
    val authenticationFailureCount: Int,
)

/** daemon worker と invocation terminal の独立 snapshot。 */
@Serializable
data class MonitoringDaemonResponse(
    val state: MonitoringComponentState,
    val reason: MonitoringUnknownReason? = null,
    val enabled: Boolean,
    val cadenceSeconds: Long,
    val lastTickAt: String? = null,
    val lastTickOutcome: String? = null,
    val lastInvocationTerminalAt: String? = null,
    val lastInvocationTerminalSemantic: MonitoringDaemonTerminalSemantic? = null,
)

/** provider outcome component。 */
@Serializable
data class MonitoringProvidersResponse(
    val state: MonitoringComponentState,
    val reason: MonitoringUnknownReason? = null,
    val windowStartedAt: String,
    val windowEndedAt: String,
    val outcomes: List<MonitoringProviderOutcomeResponse> = emptyList(),
)

/** ProtectionReconciler の in-process snapshot。 */
@Serializable
data class MonitoringReconcilerResponse(
    val state: MonitoringComponentState,
    val reason: MonitoringUnknownReason? = null,
    val lastMaintenanceAt: String? = null,
    val lastTransportActivityAt: String? = null,
    val marketDataState: String? = null,
)

/** unresolved gap aggregate。 */
@Serializable
data class MonitoringGapsResponse(
    val state: MonitoringComponentState,
    val reason: MonitoringUnknownReason? = null,
    val unresolvedMarketDataCount: Int? = null,
    val oldestMarketDataOpenedAt: String? = null,
    val unresolvedInfrastructureCount: Int? = null,
    val oldestInfrastructureOpenedAt: String? = null,
)

/** backup または restore service の公開 snapshot。 */
@Serializable
data class MonitoringBackupJobResponse(
    val serviceState: MonitoringServiceState? = null,
    val serviceStartedAt: String? = null,
    val serviceTerminalAt: String? = null,
    val serviceTerminalSemantic: MonitoringServiceTerminalSemantic? = null,
    val lastAttemptAt: String? = null,
    val lastAttemptResult: String? = null,
    val lastSuccessAt: String? = null,
)

/** root-only authority から分離した backup/restore projection。 */
@Serializable
data class MonitoringBackupRestoreResponse(
    val state: MonitoringComponentState,
    val reason: MonitoringUnknownReason? = null,
    val projectionPublishedAt: String? = null,
    val backup: MonitoringBackupJobResponse? = null,
    val restore: MonitoringBackupJobResponse? = null,
)

/** `GET /ops/monitoring` の versioned redacted response。 */
@Serializable
data class OpsMonitoringResponse(
    val schemaVersion: Int = 1,
    val observedAt: String,
    val revision: String,
    val daemon: MonitoringDaemonResponse,
    val providers: MonitoringProvidersResponse,
    val reconciler: MonitoringReconcilerResponse,
    val gaps: MonitoringGapsResponse,
    val backupRestore: MonitoringBackupRestoreResponse,
)
