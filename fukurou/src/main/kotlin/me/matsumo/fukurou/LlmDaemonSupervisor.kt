package me.matsumo.fukurou

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventFeedReader
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.config.RuntimeConfigActivationResult
import me.matsumo.fukurou.trading.config.RuntimeConfigActiveVersionChangedException
import me.matsumo.fukurou.trading.config.RuntimeConfigAdminService
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigDraftCreation
import me.matsumo.fukurou.trading.config.RuntimeConfigValidationRejectedException
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.config.calculateRuntimeConfigHash
import me.matsumo.fukurou.trading.daemon.LlmDaemonInvocationMetadata
import me.matsumo.fukurou.trading.daemon.LlmDaemonSchedulerObserver
import me.matsumo.fukurou.trading.daemon.LlmDaemonTickResult
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger

/**
 * daemon supervisor が公開する observed state。
 */
enum class LlmDaemonObservedState {
    /** worker の構築を開始している。 */
    STARTING,

    /** worker が scheduler tick を処理できる。 */
    RUNNING,

    /** 新規 tick を止め、実行中の処理を drain している。 */
    STOPPING,

    /** worker が存在しない。 */
    STOPPED,

    /** desired state へ収束できず再試行を待っている。 */
    DEGRADED,
}

/**
 * daemon observed state の理由。
 */
enum class LlmDaemonStatusReason {
    /** active config で daemon が無効。 */
    ACTIVE_CONFIG_DISABLED,

    /** operator が停止を要求した。 */
    INTENTIONAL_STOP,

    /** daemon config の hot apply のため drain している。 */
    CONFIG_APPLY,

    /** worker が稼働中。 */
    RUNNING,

    /** worker を起動中。 */
    STARTING,

    /** worker の起動または loop が失敗した。 */
    START_FAILED,

    /** scheduler signal が許容無音時間を超えた。 */
    SILENCE_DETECTED,

    /** graceful drain が上限を超えた。 */
    DRAIN_TIMED_OUT,

    /** active runtime config が利用できない。 */
    RUNTIME_CONFIG_UNAVAILABLE,

    /** Application shutdown 中。 */
    PROCESS_SHUTDOWN,
}

/**
 * daemon status に含める config identity。
 *
 * @param versionId versioned runtime config の ID。合成 config など version がない場合は null
 * @param hash config content hash
 */
data class LlmDaemonConfigIdentity(
    val versionId: String?,
    val hash: String?,
)

/**
 * full runtime config ではなく daemon component に適用した config identity。
 *
 * @param component component 名
 * @param sourceVersionId daemon section を取得した active runtime config version ID
 * @param hash daemon section だけの content hash
 */
data class LlmDaemonComponentConfigIdentity(
    val component: String,
    val sourceVersionId: String?,
    val hash: String?,
)

/**
 * daemon の直近 launch 情報。
 *
 * @param invocationId 起動 ID
 * @param triggerKind trigger 種別
 * @param startedAt 起動開始時刻
 */
data class LlmDaemonLaunchStatus(
    val invocationId: String,
    val triggerKind: String,
    val startedAt: Instant,
)

/**
 * daemon の直近 skip 情報。
 *
 * @param reason skip 理由
 * @param triggerKind trigger 種別
 * @param occurredAt 観測時刻
 */
data class LlmDaemonSkipStatus(
    val reason: String,
    val triggerKind: String?,
    val occurredAt: Instant,
)

/**
 * daemon supervisor の status snapshot。
 */
data class LlmDaemonSupervisorStatus(
    val desiredEnabled: Boolean,
    val observedState: LlmDaemonObservedState,
    val reason: LlmDaemonStatusReason,
    val detail: String?,
    val activeConfig: LlmDaemonConfigIdentity,
    val appliedConfig: LlmDaemonConfigIdentity,
    val daemonAppliedConfig: LlmDaemonComponentConfigIdentity,
    val restartRequired: Boolean,
    val lastSchedulerSignalAt: Instant?,
    val lastLaunch: LlmDaemonLaunchStatus?,
    val lastSkip: LlmDaemonSkipStatus?,
    val nextHeartbeatAt: Instant?,
    val inFlightRun: LlmDaemonLaunchStatus?,
    val silenceWarning: Boolean,
    val nextRetryAt: Instant?,
)

/**
 * Ops route が daemon status / desired state を扱う境界。
 */
interface LlmDaemonOperations {
    /** 現在の daemon status を返す。 */
    suspend fun status(): LlmDaemonSupervisorStatus

    /** versioned runtime config を介して desired state を変更する。 */
    suspend fun setDesiredEnabled(enabled: Boolean, reason: String): Result<LlmDaemonSupervisorStatus>
}

/**
 * generic runtime config activate / rollback を daemon lifecycle contract と一体で扱う境界。
 */
internal interface LlmDaemonRuntimeConfigActivationService {
    /** 保存済み draft を active 化する。 */
    suspend fun activateDraft(versionId: String, reason: String?): Result<RuntimeConfigActivationResult>

    /** 保存済み inactive version へ rollback する。 */
    suspend fun rollbackToVersion(versionId: String, reason: String?): Result<RuntimeConfigActivationResult>
}

/**
 * HARD_HALT 中の daemon start 拒否。
 */
class LlmDaemonHardHaltRejectedException : IllegalStateException("daemon start is rejected during HARD_HALT")

/**
 * STOPPING 中の daemon 操作拒否。
 */
class LlmDaemonStoppingRejectedException : IllegalStateException("daemon operation is rejected while stopping")

/**
 * active runtime config から supervisor が読む snapshot。
 */
internal data class LlmDaemonRuntimeSnapshot(
    val tradingConfig: TradingBotConfig,
    val configIdentity: RuntimeConfigAuditSnapshot?,
    val values: Map<String, String>,
    val available: Boolean,
)

/**
 * active runtime config snapshot の読み取り境界。
 */
internal fun interface LlmDaemonRuntimeSnapshotProvider {
    fun snapshot(): LlmDaemonRuntimeSnapshot
}

/**
 * 直近に完了した daemon desired state 操作を監査ログから読む境界。
 */
internal fun interface LlmDaemonDesiredStateReasonProvider {
    suspend fun latestCompletedChange(): Result<LlmDaemonDesiredStateChange?>
}

/**
 * daemon desired state 操作の正本となる監査情報。
 */
internal data class LlmDaemonDesiredStateChange(
    val enabled: Boolean,
    val reason: String,
    val occurredAt: Instant,
)

/**
 * worker 構築境界。
 */
internal fun interface LlmDaemonWorkerFactory {
    fun create(request: LlmDaemonWorkerRequest): LlmDaemonWorkerHandle
}

/**
 * worker 構築入力。
 */
internal data class LlmDaemonWorkerRequest(
    val tradingConfig: TradingBotConfig,
    val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    val observer: LlmDaemonSchedulerObserver,
    val lifecycleListener: LlmDaemonWorkerLifecycleListener,
)

/**
 * `daemon.enabled` を versioned draft として active 化する境界。
 */
internal fun interface LlmDaemonDesiredStateActivator {
    suspend fun activate(enabled: Boolean, reason: String): Result<RuntimeConfigActivationResult>
}

/**
 * runtime config admin service を用いる desired state activator。
 */
internal class VersionedLlmDaemonDesiredStateActivator(
    private val adminService: RuntimeConfigAdminService,
    private val snapshotProvider: LlmDaemonRuntimeSnapshotProvider,
    private val onActiveChanged: () -> Unit,
) : LlmDaemonDesiredStateActivator {
    override suspend fun activate(enabled: Boolean, reason: String): Result<RuntimeConfigActivationResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                repeat(MAX_DESIRED_ACTIVATION_ATTEMPTS) {
                    val activeSnapshot = snapshotProvider.snapshot()
                    val activeVersionId = activeSnapshot.configIdentity?.versionId
                        ?: error("active runtime config version is unavailable")
                    val draft = adminService.createDraft(
                        RuntimeConfigDraftCreation(
                            baseVersionId = activeVersionId,
                            values = mapOf(DAEMON_ENABLED_CONFIG_KEY to enabled.toString()),
                            note = reason,
                            createdBy = DAEMON_CONTROL_CREATED_BY,
                        ),
                    ).getOrThrow()
                    var activated = false

                    try {
                        if (!draft.validation.valid) {
                            throw RuntimeConfigValidationRejectedException(draft.validation)
                        }

                        adminService.validateVersion(draft.version.id).getOrThrow()
                        val activation = adminService.activateDraftIfActive(draft.version.id, activeVersionId)

                        if (activation.exceptionOrNull() is RuntimeConfigActiveVersionChangedException) {
                            onActiveChanged()

                            return@repeat
                        }

                        val result = activation.getOrThrow()
                        activated = true
                        onActiveChanged()

                        return@runCatching result
                    } finally {
                        if (!activated) {
                            adminService.discardDraft(draft.version.id).onFailure { error ->
                                DAEMON_SUPERVISOR_LOGGER.log(
                                    Level.WARNING,
                                    "Discarding an inactive LLM daemon control draft failed.",
                                    error,
                                )
                            }
                        }
                    }
                }

                throw RuntimeConfigActiveVersionChangedException()
            }
        }
    }
}

/**
 * desired config と worker lifecycle を単一 worker へ収束させる supervisor。
 */
internal class LlmDaemonSupervisor(
    private val processSnapshot: LlmDaemonRuntimeSnapshot,
    private val snapshotProvider: LlmDaemonRuntimeSnapshotProvider,
    private val desiredStateActivator: LlmDaemonDesiredStateActivator,
    private val runtimeConfigAdminService: RuntimeConfigAdminService? = null,
    private val onActiveConfigChanged: () -> Unit = {},
    private val riskStateRepository: RiskStateRepository,
    private val commandEventLog: CommandEventLog,
    private val desiredStateReasonProvider: LlmDaemonDesiredStateReasonProvider =
        LlmDaemonDesiredStateReasonProvider { Result.success(null) },
    private val workerFactory: LlmDaemonWorkerFactory,
    private val clock: Clock = Clock.systemUTC(),
    private val drainGrace: Duration = DEFAULT_DAEMON_DRAIN_GRACE,
    private val initialRetryDelay: Duration = DEFAULT_DAEMON_RETRY_DELAY,
    private val maxRetryDelay: Duration = MAX_DAEMON_RETRY_DELAY,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : LlmDaemonOperations, LlmDaemonRuntimeConfigActivationService, AutoCloseable {
    private val reconciliationMutex = Mutex()
    private val controlMutex = Mutex()
    private val stateLock = Any()
    private var currentWorker: LlmDaemonWorkerHandle? = null
    private var workerTerminationPending = false
    private var currentWorkerDaemonValues: Map<String, String>? = null
    private var terminationRetryAttempt = 0

    @Volatile
    private var workerGeneration = 0L

    private var daemonAppliedIdentity = emptyDaemonComponentIdentity()
    private var retryAttempt = 0
    private var runtimeRetryAttempt = 0
    private var retryJob: Job? = null

    @Volatile
    private var shuttingDown = false

    private var lastCompletedDesiredChange: LlmDaemonDesiredStateChange? = null
    private var desiredStateReasonLoaded = false
    private var mutableStatus = initialStatus(processSnapshot)

    /** Application lifecycle とともに desired state への収束を開始する。 */
    fun start(): LlmDaemonSupervisor {
        scope.launch { reconcile() }

        return this
    }

    /** active runtime config 変更後の収束を非同期で要求する。 */
    fun notifyConfigChanged() {
        scope.launch { reconcile() }
    }

    override suspend fun status(): LlmDaemonSupervisorStatus {
        val snapshot = currentSnapshotSafely()
        val status = currentStatusSnapshot()

        return status.withDerivedTiming(Instant.now(clock), snapshot)
    }

    override suspend fun setDesiredEnabled(enabled: Boolean, reason: String): Result<LlmDaemonSupervisorStatus> {
        val result = runCatching {
            controlMutex.withLock {
                validateDaemonTransition(enabled)
                appendOperationRequested(enabled, reason).getOrThrow()
                desiredStateActivator.activate(enabled, reason).getOrThrow()
                recordCompletedDesiredChange(enabled, reason)
                reconcile()
                appendOperationCompleted(enabled, reason).onFailure { error ->
                    logAuditFailure("desired state completion", error)
                }

                status()
            }
        }

        result.exceptionOrNull()?.let { error -> appendOperationFailed(enabled, reason, error) }

        return result
    }

    override suspend fun activateDraft(versionId: String, reason: String?): Result<RuntimeConfigActivationResult> {
        return activateRuntimeConfigVersion(versionId, reason) { service, targetVersionId ->
            service.activateDraft(targetVersionId)
        }
    }

    override suspend fun rollbackToVersion(versionId: String, reason: String?): Result<RuntimeConfigActivationResult> {
        return activateRuntimeConfigVersion(versionId, reason) { service, targetVersionId ->
            service.rollbackToVersion(targetVersionId)
        }
    }

    private suspend fun activateRuntimeConfigVersion(
        versionId: String,
        requestedReason: String?,
        activate: (RuntimeConfigAdminService, String) -> Result<RuntimeConfigActivationResult>,
    ): Result<RuntimeConfigActivationResult> {
        var targetEnabled: Boolean? = null
        val result = runCatching {
            controlMutex.withLock {
                val service = checkNotNull(runtimeConfigAdminService) {
                    "runtime config activation service is unavailable"
                }
                val target = withContext(Dispatchers.IO) {
                    service.validateVersion(versionId).getOrThrow()
                }

                if (!target.validation.valid) {
                    throw RuntimeConfigValidationRejectedException(target.validation)
                }

                val currentSnapshot = currentSnapshotSafely()
                val daemonChanged = !currentSnapshot.available ||
                    target.values.daemonValues() != currentSnapshot.values.daemonValues()
                targetEnabled = target.values.getValue(DAEMON_ENABLED_CONFIG_KEY).toBooleanStrict()
                val enabled = requireNotNull(targetEnabled)
                val desiredChanged = enabled != currentStatusSnapshot().desiredEnabled
                val reason = requestedReason?.trim()?.takeIf { value -> value.isNotEmpty() }
                    ?: target.version.note
                    ?: DEFAULT_RUNTIME_CONFIG_ACTIVATION_REASON

                if (daemonChanged) {
                    validateDaemonTransition(enabled)
                }
                if (desiredChanged) {
                    appendOperationRequested(enabled, reason).getOrThrow()
                }

                val activationResult = withContext(Dispatchers.IO) {
                    activate(service, versionId).getOrThrow()
                }
                onActiveConfigChanged()
                if (desiredChanged) {
                    recordCompletedDesiredChange(enabled, reason)
                }
                reconcile()
                if (desiredChanged) {
                    appendOperationCompleted(enabled, reason).onFailure { error ->
                        logAuditFailure("runtime config activation completion", error)
                    }
                }

                activationResult
            }
        }

        val error = result.exceptionOrNull()
        val enabled = targetEnabled
        val failedTargetEnabled = enabled?.takeIf { it != currentStatusSnapshot().desiredEnabled }
        if (error != null && failedTargetEnabled != null) {
            appendOperationFailed(failedTargetEnabled, requestedReason ?: DEFAULT_RUNTIME_CONFIG_ACTIVATION_REASON, error)
        }

        return result
    }

    private suspend fun validateDaemonTransition(enabled: Boolean) {
        if (currentStatusSnapshot().observedState == LlmDaemonObservedState.STOPPING) {
            throw LlmDaemonStoppingRejectedException()
        }

        if (!enabled) {
            return
        }

        val riskState = riskStateRepository.current().getOrThrow()

        if (riskState.state == RiskHaltState.HARD_HALT) {
            throw LlmDaemonHardHaltRejectedException()
        }
    }

    private fun recordCompletedDesiredChange(enabled: Boolean, reason: String) {
        lastCompletedDesiredChange = LlmDaemonDesiredStateChange(
            enabled = enabled,
            reason = reason,
            occurredAt = Instant.now(clock),
        )
        desiredStateReasonLoaded = true
    }

    override fun close() {
        runBlocking {
            reconciliationMutex.withLock {
                shuttingDown = true
                retryJob?.cancel()
                retryJob = null
                updateStatus(
                    observedState = LlmDaemonObservedState.STOPPING,
                    reason = LlmDaemonStatusReason.PROCESS_SHUTDOWN,
                )
                stopCurrentWorkerLocked(LlmDaemonStatusReason.PROCESS_SHUTDOWN)
                releaseCurrentWorkerLocked()
            }
        }
        scope.cancel()
    }

    private suspend fun reconcile() {
        reconciliationMutex.withLock {
            if (shuttingDown) {
                return
            }

            val snapshot = currentSnapshotSafely()

            if (!reapTerminationPendingWorkerLocked()) return

            if (!snapshot.available) {
                handleUnavailableRuntimeSnapshotLocked()

                return
            }

            runtimeRetryAttempt = 0
            updateActiveSnapshot(snapshot)

            if (!snapshot.tradingConfig.daemon.enabled) {
                retryJob?.cancel()
                retryJob = null
                retryAttempt = 0
                val completedStop = completedStopReason()
                val disabledReason = completedStop?.let { LlmDaemonStatusReason.INTENTIONAL_STOP }
                    ?: LlmDaemonStatusReason.ACTIVE_CONFIG_DISABLED
                val stopResult = stopCurrentWorkerLocked(disabledReason)

                if (stopResult == LlmDaemonWorkerStopResult.TERMINATION_PENDING) {
                    return
                }

                val stopReason = if (stopResult == LlmDaemonWorkerStopResult.TIMED_OUT) {
                    LlmDaemonStatusReason.DRAIN_TIMED_OUT
                } else {
                    disabledReason
                }
                updateStatus(
                    observedState = LlmDaemonObservedState.STOPPED,
                    reason = stopReason,
                    detail = if (stopResult == LlmDaemonWorkerStopResult.TIMED_OUT) {
                        "in-flight run was cancelled after bounded drain"
                    } else {
                        completedStop?.reason
                    },
                    nextRetryAt = null,
                )

                return
            }

            val activeDaemonValues = snapshot.values.daemonValues()
            val workerNeedsRebuild = currentWorker != null && currentWorkerDaemonValues != activeDaemonValues

            if (workerNeedsRebuild) {
                stopCurrentWorkerLocked(LlmDaemonStatusReason.CONFIG_APPLY)
            }

            if (currentWorker == null) {
                startWorkerLocked(snapshot)
            }
        }
    }

    private suspend fun handleUnavailableRuntimeSnapshotLocked() {
        val stopResult = stopCurrentWorkerLocked(LlmDaemonStatusReason.RUNTIME_CONFIG_UNAVAILABLE)

        updateStatus(
            observedState = LlmDaemonObservedState.DEGRADED,
            reason = LlmDaemonStatusReason.RUNTIME_CONFIG_UNAVAILABLE,
            detail = "active runtime config is unavailable",
        )
        if (stopResult != LlmDaemonWorkerStopResult.TERMINATION_PENDING) {
            scheduleRuntimeRecoveryLocked()
        }
    }

    private suspend fun reapTerminationPendingWorkerLocked(): Boolean {
        if (!workerTerminationPending) {
            return true
        }

        val worker = currentWorker ?: run {
            workerTerminationPending = false
            terminationRetryAttempt = 0

            return true
        }
        val stopResult = worker.stopGracefully(TERMINATION_REAP_TIMEOUT)

        if (stopResult == LlmDaemonWorkerStopResult.TERMINATION_PENDING) {
            terminationRetryAttempt = (terminationRetryAttempt + 1).coerceAtMost(MAX_RETRY_EXPONENT)
            val retryDelay = retryDelay(terminationRetryAttempt, initialRetryDelay, maxRetryDelay)

            updateStatus(nextRetryAt = Instant.now(clock).plus(retryDelay))
            scheduleRetryLocked(retryDelay)

            return false
        }

        releaseCurrentWorkerLocked()

        return true
    }

    private suspend fun completedStopReason(): LlmDaemonDesiredStateChange? {
        if (!desiredStateReasonLoaded) {
            lastCompletedDesiredChange = desiredStateReasonProvider.latestCompletedChange().getOrNull()
            desiredStateReasonLoaded = true
        }

        return lastCompletedDesiredChange?.takeUnless { change -> change.enabled }
    }

    private suspend fun startWorkerLocked(snapshot: LlmDaemonRuntimeSnapshot) {
        workerGeneration += 1
        val generation = workerGeneration
        val appliedTradingConfig = processSnapshot.tradingConfig.copy(daemon = snapshot.tradingConfig.daemon)
        val appliedValues = processSnapshot.values + snapshot.values.daemonValues()
        val appliedHash = calculateRuntimeConfigHash(appliedValues)
        val nextDaemonAppliedIdentity = LlmDaemonComponentConfigIdentity(
            component = DAEMON_COMPONENT_NAME,
            sourceVersionId = snapshot.configIdentity?.versionId,
            hash = calculateRuntimeConfigHash(snapshot.values.daemonValues()),
        )

        updateStatus(
            desiredEnabled = true,
            observedState = LlmDaemonObservedState.STARTING,
            reason = LlmDaemonStatusReason.STARTING,
            detail = null,
            nextRetryAt = null,
        )

        currentWorkerDaemonValues = snapshot.values.daemonValues()

        val worker = runCatching {
            workerFactory.create(
                LlmDaemonWorkerRequest(
                    tradingConfig = appliedTradingConfig,
                    runtimeConfigSnapshot = RuntimeConfigAuditSnapshot(
                        versionId = null,
                        hash = appliedHash,
                    ),
                    observer = supervisorObserver(generation),
                    lifecycleListener = supervisorLifecycleListener(
                        generation = generation,
                        appliedIdentity = nextDaemonAppliedIdentity,
                    ),
                ),
            )
        }.getOrElse { error ->
            handleStartFailureLocked(error)

            return
        }

        currentWorker = worker
        workerTerminationPending = false
        terminationRetryAttempt = 0
        runCatching { worker.start() }.onFailure { error ->
            releaseCurrentWorkerLocked()
            handleStartFailureLocked(error)
        }
    }

    private suspend fun stopCurrentWorkerLocked(reason: LlmDaemonStatusReason): LlmDaemonWorkerStopResult? {
        val worker = currentWorker ?: return null

        worker.requestStop()
        workerGeneration += 1

        updateStatus(
            observedState = LlmDaemonObservedState.STOPPING,
            reason = reason,
            detail = null,
        )
        appendStateChanged(LlmDaemonObservedState.STOPPING, reason)

        val drainTimeout = processSnapshot.tradingConfig.runner.perRunTimeout.plus(drainGrace)
        val stopResult = worker.stopGracefully(drainTimeout)

        if (stopResult == LlmDaemonWorkerStopResult.TERMINATION_PENDING) {
            workerTerminationPending = true
            terminationRetryAttempt = (terminationRetryAttempt + 1).coerceAtMost(MAX_RETRY_EXPONENT)
            val retryDelay = retryDelay(terminationRetryAttempt, initialRetryDelay, maxRetryDelay)
            val nextRetryAt = Instant.now(clock).plus(retryDelay)
            updateStatus(
                observedState = LlmDaemonObservedState.DEGRADED,
                reason = LlmDaemonStatusReason.DRAIN_TIMED_OUT,
                detail = "worker cancellation did not terminate within the bounded wait",
                nextRetryAt = nextRetryAt,
            )
            appendDrainTimedOut(drainTimeout, terminationPending = true)
            scheduleRetryLocked(retryDelay)

            return stopResult
        }

        releaseCurrentWorkerLocked()

        if (stopResult == LlmDaemonWorkerStopResult.TIMED_OUT) {
            updateStatus(
                observedState = LlmDaemonObservedState.DEGRADED,
                reason = LlmDaemonStatusReason.DRAIN_TIMED_OUT,
                detail = "daemon drain timed out and the in-flight run was cancelled",
                inFlightRun = null,
            )
            appendDrainTimedOut(drainTimeout, terminationPending = false)
        } else {
            updateStatus(
                observedState = LlmDaemonObservedState.STOPPED,
                reason = reason,
                detail = null,
                inFlightRun = null,
            )
            appendStateChanged(LlmDaemonObservedState.STOPPED, reason)
        }

        return stopResult
    }

    private suspend fun releaseCurrentWorkerLocked() {
        currentWorker?.shutdown()
        currentWorker = null
        workerTerminationPending = false
        currentWorkerDaemonValues = null
        terminationRetryAttempt = 0
    }

    private fun supervisorObserver(generation: Long): LlmDaemonSchedulerObserver {
        return object : LlmDaemonSchedulerObserver {
            override fun onSchedulerSignal(observedAt: Instant) {
                updateForGeneration(generation) {
                    copy(lastSchedulerSignalAt = observedAt)
                }
            }

            override fun onInvocationStarted(metadata: LlmDaemonInvocationMetadata) {
                val launch = metadata.toLaunchStatus()

                updateForGeneration(generation) {
                    copy(
                        lastLaunch = launch,
                        inFlightRun = launch,
                    )
                }
            }

            override fun onInvocationFinished(invocationId: String, finishedAt: Instant) {
                updateForGeneration(generation) {
                    val currentInFlight = inFlightRun?.takeUnless { run -> run.invocationId == invocationId }

                    copy(
                        inFlightRun = currentInFlight,
                        lastSchedulerSignalAt = finishedAt,
                    )
                }
            }

            override fun onTickCompleted(result: LlmDaemonTickResult, completedAt: Instant) {
                updateForGeneration(generation) {
                    val skip = (result as? LlmDaemonTickResult.Skipped)?.let { skipped ->
                        LlmDaemonSkipStatus(
                            reason = skipped.reason,
                            triggerKind = skipped.triggerKind?.name,
                            occurredAt = completedAt,
                        )
                    }

                    copy(
                        lastSchedulerSignalAt = completedAt,
                        lastSkip = skip ?: lastSkip,
                    )
                }
            }
        }
    }

    private fun supervisorLifecycleListener(
        generation: Long,
        appliedIdentity: LlmDaemonComponentConfigIdentity,
    ): LlmDaemonWorkerLifecycleListener {
        return object : LlmDaemonWorkerLifecycleListener {
            override fun onStarted() {
                scope.launch {
                    reconciliationMutex.withLock {
                        if (generation != workerGeneration || shuttingDown) {
                            return@withLock
                        }

                        retryAttempt = 0
                        daemonAppliedIdentity = appliedIdentity
                        updateStatus(
                            observedState = LlmDaemonObservedState.RUNNING,
                            reason = LlmDaemonStatusReason.RUNNING,
                            detail = null,
                            nextRetryAt = null,
                            daemonAppliedConfig = appliedIdentity,
                        )
                    }
                    appendStateChanged(LlmDaemonObservedState.RUNNING, LlmDaemonStatusReason.RUNNING)
                }
            }

            override fun onFailed(error: Throwable) {
                scope.launch {
                    reconciliationMutex.withLock {
                        if (generation != workerGeneration || shuttingDown) {
                            return@withLock
                        }

                        releaseCurrentWorkerLocked()
                        handleStartFailureLocked(error)
                    }
                }
            }
        }
    }

    private fun handleStartFailureLocked(error: Throwable) {
        currentWorkerDaemonValues = null
        retryAttempt = (retryAttempt + 1).coerceAtMost(MAX_RETRY_EXPONENT)
        val retryDelay = retryDelay(retryAttempt, initialRetryDelay, maxRetryDelay)
        val nextRetryAt = Instant.now(clock).plus(retryDelay)

        updateStatus(
            observedState = LlmDaemonObservedState.DEGRADED,
            reason = LlmDaemonStatusReason.START_FAILED,
            detail = error.javaClass.simpleName,
            nextRetryAt = nextRetryAt,
            inFlightRun = null,
        )
        scope.launch { appendOperationFailureEvent(error) }
        scheduleRetryLocked(retryDelay)
    }

    private fun scheduleRetryLocked(retryDelay: Duration) {
        retryJob?.cancel()
        val scheduledJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(retryDelay.toMillis())
            val thisJob = currentCoroutineContext()[Job]
            val shouldReconcile = reconciliationMutex.withLock {
                if (retryJob !== thisJob || shuttingDown) {
                    false
                } else {
                    retryJob = null
                    true
                }
            }

            if (shouldReconcile) {
                reconcile()
            }
        }
        retryJob = scheduledJob
        scheduledJob.start()
    }

    private fun scheduleRuntimeRecoveryLocked() {
        runtimeRetryAttempt = (runtimeRetryAttempt + 1).coerceAtMost(MAX_RETRY_EXPONENT)
        val recoveryDelay = retryDelay(runtimeRetryAttempt, initialRetryDelay, maxRetryDelay)

        updateStatus(nextRetryAt = Instant.now(clock).plus(recoveryDelay))
        scheduleRetryLocked(recoveryDelay)
    }

    private fun updateActiveSnapshot(snapshot: LlmDaemonRuntimeSnapshot) {
        val activeIdentity = LlmDaemonConfigIdentity(
            versionId = snapshot.configIdentity?.versionId,
            hash = snapshot.configIdentity?.hash,
        )
        val processIdentity = LlmDaemonConfigIdentity(
            versionId = processSnapshot.configIdentity?.versionId,
            hash = processSnapshot.configIdentity?.hash ?: calculateRuntimeConfigHash(processSnapshot.values),
        )
        val restartRequired = snapshot.values.nonDaemonValues() != processSnapshot.values.nonDaemonValues()

        updateStatus(
            desiredEnabled = snapshot.tradingConfig.daemon.enabled,
            activeConfig = activeIdentity,
            appliedConfig = processIdentity,
            daemonAppliedConfig = daemonAppliedIdentity,
            restartRequired = restartRequired,
        )
    }

    private fun updateStatus(
        desiredEnabled: Boolean? = null,
        observedState: LlmDaemonObservedState? = null,
        reason: LlmDaemonStatusReason? = null,
        detail: String? = KEEP_DETAIL,
        activeConfig: LlmDaemonConfigIdentity? = null,
        appliedConfig: LlmDaemonConfigIdentity? = null,
        daemonAppliedConfig: LlmDaemonComponentConfigIdentity? = null,
        restartRequired: Boolean? = null,
        inFlightRun: LlmDaemonLaunchStatus? = KEEP_IN_FLIGHT,
        nextRetryAt: Instant? = KEEP_RETRY_AT,
    ) {
        synchronized(stateLock) {
            mutableStatus = mutableStatus.copy(
                desiredEnabled = desiredEnabled ?: mutableStatus.desiredEnabled,
                observedState = observedState ?: mutableStatus.observedState,
                reason = reason ?: mutableStatus.reason,
                detail = if (detail == KEEP_DETAIL) mutableStatus.detail else detail,
                activeConfig = activeConfig ?: mutableStatus.activeConfig,
                appliedConfig = appliedConfig ?: mutableStatus.appliedConfig,
                daemonAppliedConfig = daemonAppliedConfig ?: mutableStatus.daemonAppliedConfig,
                restartRequired = restartRequired ?: mutableStatus.restartRequired,
                inFlightRun = if (inFlightRun === KEEP_IN_FLIGHT) mutableStatus.inFlightRun else inFlightRun,
                nextRetryAt = if (nextRetryAt == KEEP_RETRY_AT) mutableStatus.nextRetryAt else nextRetryAt,
            )
        }
    }

    private fun updateForGeneration(
        generation: Long,
        transform: LlmDaemonSupervisorStatus.() -> LlmDaemonSupervisorStatus,
    ) {
        synchronized(stateLock) {
            if (generation == workerGeneration && !shuttingDown) {
                mutableStatus = mutableStatus.transform()
            }
        }
    }

    private suspend fun currentSnapshotSafely(): LlmDaemonRuntimeSnapshot {
        return withContext(Dispatchers.IO) {
            runCatching { snapshotProvider.snapshot() }.getOrElse { unavailableRuntimeSnapshot() }
        }
    }

    private fun unavailableRuntimeSnapshot(): LlmDaemonRuntimeSnapshot {
        return processSnapshot.copy(
            configIdentity = null,
            available = false,
        )
    }

    private fun LlmDaemonSupervisorStatus.withDerivedTiming(
        now: Instant,
        snapshot: LlmDaemonRuntimeSnapshot,
    ): LlmDaemonSupervisorStatus {
        val signalBase = lastSchedulerSignalAt ?: lastLaunch?.startedAt
        val silenceDeadline = signalBase?.plus(snapshot.tradingConfig.daemon.flatHeartbeatInterval)
            ?.plus(snapshot.tradingConfig.daemon.pollInterval)
        val silenceWarning = desiredEnabled && observedState == LlmDaemonObservedState.RUNNING &&
            silenceDeadline != null && now.isAfter(silenceDeadline)
        val nextHeartbeatAt = (lastLaunch?.startedAt ?: lastSchedulerSignalAt)
            ?.plus(snapshot.tradingConfig.daemon.flatHeartbeatInterval)

        return copy(
            nextHeartbeatAt = nextHeartbeatAt,
            silenceWarning = silenceWarning,
            reason = if (silenceWarning) LlmDaemonStatusReason.SILENCE_DETECTED else reason,
        )
    }

    private suspend fun appendOperationRequested(enabled: Boolean, reason: String): Result<Unit> {
        return appendOperationEvent(
            eventType = if (enabled) CommandEventType.DAEMON_START_REQUESTED else CommandEventType.DAEMON_STOP_REQUESTED,
            payload = buildJsonObject {
                put("desiredEnabled", enabled)
                put("reason", reason)
            }.toString(),
        )
    }

    private suspend fun appendOperationCompleted(enabled: Boolean, reason: String): Result<Unit> {
        return appendOperationEvent(
            eventType = CommandEventType.DAEMON_DESIRED_STATE_CHANGED,
            payload = buildJsonObject {
                put("desiredEnabled", enabled)
                put("observedState", currentStatusSnapshot().observedState.name)
                put("reason", reason)
            }.toString(),
        )
    }

    private fun appendOperationFailed(
        enabled: Boolean,
        reason: String,
        error: Throwable,
    ) {
        scope.launch {
            appendOperationEvent(
                eventType = CommandEventType.DAEMON_OPERATION_FAILED,
                payload = buildJsonObject {
                    put("desiredEnabled", enabled)
                    put("reason", reason)
                    put("errorType", error.javaClass.simpleName)
                }.toString(),
            )
        }
    }

    private suspend fun appendStateChanged(state: LlmDaemonObservedState, reason: LlmDaemonStatusReason) {
        appendOperationEvent(
            eventType = CommandEventType.DAEMON_STATE_CHANGED,
            payload = buildJsonObject {
                put("desiredEnabled", currentStatusSnapshot().desiredEnabled)
                put("observedState", state.name)
                put("reason", reason.name)
            }.toString(),
        )
    }

    private suspend fun appendDrainTimedOut(timeout: Duration, terminationPending: Boolean) {
        appendOperationEvent(
            eventType = CommandEventType.DAEMON_DRAIN_TIMED_OUT,
            payload = buildJsonObject {
                put("timeoutSeconds", timeout.seconds)
                put("terminationPending", terminationPending)
                put(
                    "detail",
                    if (terminationPending) {
                        "worker cancellation did not terminate within the bounded wait"
                    } else {
                        "in-flight run cancelled after bounded drain"
                    },
                )
            }.toString(),
        )
    }

    private suspend fun appendOperationFailureEvent(error: Throwable) {
        appendOperationEvent(
            eventType = CommandEventType.DAEMON_OPERATION_FAILED,
            payload = buildJsonObject {
                put("desiredEnabled", true)
                put("operation", "worker_start")
                put("errorType", error.javaClass.simpleName)
            }.toString(),
        )
    }

    private suspend fun appendOperationEvent(eventType: CommandEventType, payload: String): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
                toolName = DAEMON_SUPERVISOR_TOOL_NAME,
                toolCallId = null,
                clientRequestId = null,
                eventType = eventType,
                payload = payload,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun logAuditFailure(operation: String, error: Throwable) {
        DAEMON_SUPERVISOR_LOGGER.log(
            Level.WARNING,
            "LLM daemon $operation audit write failed after the lifecycle operation completed.",
            error,
        )
    }

    private fun currentStatusSnapshot(): LlmDaemonSupervisorStatus {
        return synchronized(stateLock) { mutableStatus }
    }
}

/** command_event_log の完了監査から直近 desired state 操作を復元する。 */
internal fun commandEventDesiredStateReasonProvider(
    reader: CommandEventFeedReader,
): LlmDaemonDesiredStateReasonProvider {
    return LlmDaemonDesiredStateReasonProvider {
        reader.findEvents(
            limit = DESIRED_STATE_AUDIT_LOOKBACK,
            eventType = CommandEventType.DAEMON_DESIRED_STATE_CHANGED,
        ).map { events ->
            events.firstNotNullOfOrNull { event ->
                runCatching {
                    val payload = Json.parseToJsonElement(event.payload).jsonObject

                    LlmDaemonDesiredStateChange(
                        enabled = payload.getValue("desiredEnabled").jsonPrimitive.content.toBooleanStrict(),
                        reason = payload.getValue("reason").jsonPrimitive.content,
                        occurredAt = event.occurredAt,
                    )
                }.getOrNull()
            }
        }
    }
}

private fun initialStatus(snapshot: LlmDaemonRuntimeSnapshot): LlmDaemonSupervisorStatus {
    val identity = LlmDaemonConfigIdentity(
        versionId = snapshot.configIdentity?.versionId,
        hash = snapshot.configIdentity?.hash ?: calculateRuntimeConfigHash(snapshot.values),
    )

    return LlmDaemonSupervisorStatus(
        desiredEnabled = snapshot.tradingConfig.daemon.enabled,
        observedState = LlmDaemonObservedState.STOPPED,
        reason = LlmDaemonStatusReason.ACTIVE_CONFIG_DISABLED,
        detail = null,
        activeConfig = identity,
        appliedConfig = identity,
        daemonAppliedConfig = emptyDaemonComponentIdentity(),
        restartRequired = false,
        lastSchedulerSignalAt = null,
        lastLaunch = null,
        lastSkip = null,
        nextHeartbeatAt = null,
        inFlightRun = null,
        silenceWarning = false,
        nextRetryAt = null,
    )
}

private fun emptyDaemonComponentIdentity(): LlmDaemonComponentConfigIdentity {
    return LlmDaemonComponentConfigIdentity(
        component = DAEMON_COMPONENT_NAME,
        sourceVersionId = null,
        hash = null,
    )
}

private fun LlmDaemonInvocationMetadata.toLaunchStatus(): LlmDaemonLaunchStatus {
    return LlmDaemonLaunchStatus(
        invocationId = invocationId,
        triggerKind = triggerKind.name,
        startedAt = startedAt,
    )
}

private fun Map<String, String>.daemonValues(): Map<String, String> {
    return filterKeys { key -> key.startsWith(DAEMON_CONFIG_PREFIX) }
}

private fun Map<String, String>.nonDaemonValues(): Map<String, String> {
    return filterKeys { key -> !key.startsWith(DAEMON_CONFIG_PREFIX) }
}

private fun retryDelay(
    attempt: Int,
    initial: Duration,
    maximum: Duration,
): Duration {
    val multiplier = 1L shl (attempt - 1).coerceAtLeast(0)

    return initial.multipliedBy(multiplier).coerceAtMost(maximum)
}

private fun Duration.coerceAtMost(maximum: Duration): Duration {
    return if (this > maximum) maximum else this
}

private const val DAEMON_ENABLED_CONFIG_KEY = "daemon.enabled"
private const val DAEMON_CONFIG_PREFIX = "daemon."
private const val DAEMON_COMPONENT_NAME = "daemon"
private const val DAEMON_CONTROL_CREATED_BY = "webui-daemon-control"
private const val DAEMON_SUPERVISOR_TOOL_NAME = "llm-daemon-supervisor"
private const val DESIRED_STATE_AUDIT_LOOKBACK = 1
private const val MAX_DESIRED_ACTIVATION_ATTEMPTS = 3
private const val MAX_RETRY_EXPONENT = 7
private const val DEFAULT_RUNTIME_CONFIG_ACTIVATION_REASON = "runtime config activation"
private val DEFAULT_DAEMON_DRAIN_GRACE: Duration = Duration.ofSeconds(30)
private val DEFAULT_DAEMON_RETRY_DELAY: Duration = Duration.ofSeconds(5)
private val MAX_DAEMON_RETRY_DELAY: Duration = Duration.ofMinutes(5)

/** cancel 済み worker の終端確認を再開する際は、drain を再実行しない。 */
private val TERMINATION_REAP_TIMEOUT: Duration = Duration.ZERO

private val DAEMON_SUPERVISOR_LOGGER: Logger = Logger.getLogger(LlmDaemonSupervisor::class.java.name)

private const val KEEP_DETAIL = "__keep_detail__"
private val KEEP_IN_FLIGHT = LlmDaemonLaunchStatus("__keep__", "__keep__", Instant.EPOCH)
private val KEEP_RETRY_AT = Instant.MIN
