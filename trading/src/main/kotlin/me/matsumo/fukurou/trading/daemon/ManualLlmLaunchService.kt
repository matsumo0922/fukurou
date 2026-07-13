package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import me.matsumo.fukurou.trading.evaluation.terminalCauseForInvocationFailure
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.runner.MAX_DAILY_INVOCATION_COUNT_WINDOW
import me.matsumo.fukurou.trading.runner.MAX_INVOCATION_COUNT_WINDOW
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.OneShotRunnerResult
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * 手動 LLM 起動 API が利用する境界。
 */
fun interface ManualLlmLaunchService {
    /**
     * 運用者の reason 付きで LLM one-shot 起動を要求する。
     */
    suspend fun launch(reason: String): Result<ManualLlmLaunchResult>
}

/**
 * 手動 LLM 起動要求の結果。
 */
sealed interface ManualLlmLaunchResult {
    /**
     * 起動予約を取得し、runner を非同期開始した。
     *
     * @param invocationId 起動 ID
     * @param triggerKind 起動 trigger 種別
     */
    data class Accepted(
        val invocationId: String,
        val triggerKind: LlmDaemonTriggerKind,
    ) : ManualLlmLaunchResult

    /**
     * 起動予約または SOFT_HALT flat 判定により拒否した。
     *
     * @param reason API と監査に出す拒否理由
     */
    data class Rejected(
        val reason: String,
    ) : ManualLlmLaunchResult
}

/**
 * 予約 repository を正本として手動 LLM 起動を fire-and-forget で実行する service。
 *
 * @param tradingConfig 取引 bot 設定
 * @param runtimeConfigSnapshot 手動起動開始時に固定する runtime config snapshot
 * @param dependencies service が参照する repository / reader
 * @param runtime service の実行時境界
 */
class DefaultManualLlmLaunchService(
    private val tradingConfig: TradingBotConfig,
    private val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    dependencies: ManualLlmLaunchServiceDependencies,
    runtime: ManualLlmLaunchServiceRuntime,
) : ManualLlmLaunchService, AutoCloseable {
    private val riskStateRepository = dependencies.riskStateRepository
    private val commandEventLog = dependencies.commandEventLog
    private val launchReservationRepository = dependencies.launchReservationRepository
    private val openRiskReader = dependencies.openRiskReader
    private val requestBase = runtime.requestBase
    private val launchOneShot = runtime.launchOneShot
    private val clock = runtime.clock
    private val idGenerator = runtime.idGenerator
    private val warnLogger = runtime.warnLogger
    private val scope = runtime.scope

    override suspend fun launch(reason: String): Result<ManualLlmLaunchResult> {
        return runCatching {
            val trimmedReason = reason.trim()

            require(trimmedReason.isNotEmpty()) {
                "reason must not be blank."
            }

            launchUnsafe(trimmedReason)
        }
    }

    override fun close() {
        val scopeJob = scope.coroutineContext[Job] ?: return

        scopeJob.cancel()

        runBlocking {
            scopeJob.join()
        }
    }

    private suspend fun launchUnsafe(reason: String): ManualLlmLaunchResult {
        val observedAt = Instant.now(clock)

        if (!tradingConfig.daemon.launchEnabled) {
            appendSkip(
                skipReason = LLM_LAUNCH_DISABLED,
                requestReason = reason,
                observedAt = observedAt,
            ).getOrThrow()

            return ManualLlmLaunchResult.Rejected(LLM_LAUNCH_DISABLED)
        }

        val riskState = riskStateRepository.current().getOrThrow()
        val openRisk = openRiskReader.snapshot().getOrThrow()

        if (riskState.state == RiskHaltState.SOFT_HALT) {
            val hasOpenRisk = openRisk.hasOpenRisk

            if (!hasOpenRisk) {
                appendSkip(
                    skipReason = LLM_DAEMON_SKIP_SOFT_HALT_FLAT,
                    requestReason = reason,
                    observedAt = observedAt,
                ).getOrThrow()

                return ManualLlmLaunchResult.Rejected(LLM_DAEMON_SKIP_SOFT_HALT_FLAT)
            }
        }

        val invocationId = idGenerator().toString()
        val reservationRequest = LlmLaunchReservationRequest(
            invocationId = invocationId,
            triggerKind = LlmDaemonTriggerKind.MANUAL,
            triggerKey = LLM_MANUAL_TRIGGER_KEY,
            reservedAt = observedAt,
            runnerConfig = tradingConfig.runner,
            hourlyWindow = MAX_INVOCATION_COUNT_WINDOW,
            dailyWindow = MAX_DAILY_INVOCATION_COUNT_WINDOW,
            activeReservationStaleAfter = tradingConfig.daemon.launchReservationStaleAfter,
            populationScope = LlmLaunchReservationPopulationScope(
                kind = "SYMBOL",
                mode = tradingConfig.mode,
                symbol = tradingConfig.symbol,
            ),
        )
        val reservationOutcome = launchReservationRepository.tryReserve(reservationRequest).getOrThrow()

        if (reservationOutcome is LlmLaunchReservationOutcome.Rejected) {
            val skipReason = reservationOutcome.reason.toDaemonSkipReason()

            appendSkip(
                skipReason = skipReason,
                requestReason = reason,
                observedAt = observedAt,
                activeReservation = reservationOutcome.activeReservation,
            ).getOrThrow()

            return ManualLlmLaunchResult.Rejected(skipReason)
        }

        val reservedOutcome = reservationOutcome as LlmLaunchReservationOutcome.Reserved

        appendLaunchedOrFinishReservation(
            invocationId = reservedOutcome.invocationId,
            reason = reason,
            openRisk = openRisk,
            observedAt = observedAt,
        ).getOrThrow()
        launchReservedInvocation(reservedOutcome.invocationId)

        return ManualLlmLaunchResult.Accepted(
            invocationId = reservedOutcome.invocationId,
            triggerKind = LlmDaemonTriggerKind.MANUAL,
        )
    }

    private fun launchReservedInvocation(invocationId: String) {
        val started = AtomicBoolean(false)
        val job = scope.launch {
            started.set(true)
            runReservedInvocation(invocationId)
        }

        job.invokeOnCompletion { throwable ->
            throwable as? CancellationException ?: return@invokeOnCompletion

            if (!started.get()) {
                finishCancelledBeforeStart(invocationId)
            }
        }
    }

    private suspend fun appendLaunchedOrFinishReservation(
        invocationId: String,
        reason: String,
        openRisk: LlmDaemonOpenRiskSnapshot,
        observedAt: Instant,
    ): Result<Unit> {
        val appendResult = appendLaunched(
            invocationId = invocationId,
            reason = reason,
            openRisk = openRisk,
            observedAt = observedAt,
        )
        val appendFailure = appendResult.exceptionOrNull()

        if (appendFailure != null) {
            finishReservedInvocation(
                invocationId = invocationId,
                status = LlmLaunchReservationStatus.FAILED,
                reason = LlmRunTerminalCause.RUNNER_FAILED.name,
                finishedAt = Instant.now(clock),
            )
        }

        return appendResult
    }

    private fun finishCancelledBeforeStart(invocationId: String) {
        runBlocking {
            finishReservedInvocation(
                invocationId = invocationId,
                status = LlmLaunchReservationStatus.FAILED,
                reason = LlmRunTerminalCause.CALLER_CANCELLED.name,
                finishedAt = Instant.now(clock),
            )
        }
    }

    private suspend fun runReservedInvocation(invocationId: String) {
        val request = requestBase.copy(
            invocationId = invocationId,
            marketSnapshotId = "manual-$invocationId",
            triggerKind = LlmDaemonTriggerKind.MANUAL,
        )
        val result = runCatching {
            launchOneShot(request).getOrThrow()
        }
        val runnerResult = result.getOrNull()
        val failure = result.exceptionOrNull()
        val status = if (failure == null) {
            LlmLaunchReservationStatus.FINISHED
        } else {
            LlmLaunchReservationStatus.FAILED
        }
        val finishReason = (runnerResult?.terminalCause ?: terminalCauseForInvocationFailure(failure)).name

        finishReservedInvocation(
            invocationId = invocationId,
            status = status,
            reason = finishReason,
            finishedAt = Instant.now(clock),
        )

        if (failure is CancellationException) {
            throw failure
        }
        if (failure != null) {
            warnLogger.warn(
                key = MANUAL_LAUNCH_FAILURE_LOG_KEY,
                message = "ManualLlmLaunchService runner failed.",
                throwable = failure,
            )
        }
    }

    private suspend fun finishReservedInvocation(
        invocationId: String,
        status: LlmLaunchReservationStatus,
        reason: String?,
        finishedAt: Instant,
    ) {
        withContext(NonCancellable) {
            val finishResult = launchReservationRepository.finish(
                LlmLaunchReservationFinish(
                    invocationId = invocationId,
                    status = status,
                    reason = reason,
                    finishedAt = finishedAt,
                ),
            ).mapCatching {
                appendCompleted(
                    invocationId = invocationId,
                    status = reason ?: "unknown",
                    finishedAt = finishedAt,
                ).getOrThrow()
            }

            finishResult.onFailure { throwable ->
                warnLogger.warn(
                    key = MANUAL_FINISH_FAILURE_LOG_KEY,
                    message = "ManualLlmLaunchService failed to finish reserved invocation.",
                    throwable = throwable,
                )
            }
        }
    }

    private suspend fun appendSkip(
        skipReason: String,
        requestReason: String,
        observedAt: Instant,
        activeReservation: LlmActiveLaunchReservation? = null,
    ): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = activeReservation?.let { active ->
                    manualDecisionRunContext(active.invocationId, runtimeConfigSnapshot)
                } ?: DecisionRunContext.EMPTY,
                toolName = MANUAL_TOOL_NAME,
                toolCallId = null,
                clientRequestId = LLM_MANUAL_TRIGGER_KEY,
                eventType = CommandEventType.DAEMON_TRIGGER_SKIPPED,
                payload = buildJsonObject {
                    put("reason", skipReason)
                    put("requestReason", requestReason)
                    put("triggerKind", LlmDaemonTriggerKind.MANUAL.name)
                    put("triggerKey", LLM_MANUAL_TRIGGER_KEY)
                    put("eventName", null as String?)
                    put("observedAt", observedAt.toString())
                    activeReservation?.let { active ->
                        put("activeInvocationId", active.invocationId)
                        put("activeTriggerKind", active.triggerKind.name)
                        put("activeTriggerKey", active.triggerKey)
                        put("activeReservedAt", active.reservedAt.toString())
                    }
                    runtimeConfigSnapshot?.let { snapshot ->
                        put("runtimeConfigVersionId", snapshot.versionId)
                        put("runtimeConfigHash", snapshot.hash)
                    }
                }.toString(),
                occurredAt = observedAt,
            ),
        )
    }

    private suspend fun appendLaunched(
        invocationId: String,
        reason: String,
        openRisk: LlmDaemonOpenRiskSnapshot,
        observedAt: Instant,
    ): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = manualDecisionRunContext(invocationId, runtimeConfigSnapshot),
                toolName = MANUAL_TOOL_NAME,
                toolCallId = null,
                clientRequestId = LLM_MANUAL_TRIGGER_KEY,
                eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
                payload = buildJsonObject {
                    put("triggerKind", LlmDaemonTriggerKind.MANUAL.name)
                    put("triggerKey", LLM_MANUAL_TRIGGER_KEY)
                    put("eventName", null as String?)
                    put("invocationId", invocationId)
                    put("restingOnly", openRisk.isRestingEntryOnly)
                    put("openPositionCount", openRisk.openPositionCount)
                    put("restingEntryOrderCount", openRisk.restingEntryOrders.size)
                    put("observedAt", observedAt.toString())
                    put("reason", reason)
                    runtimeConfigSnapshot?.let { snapshot ->
                        put("runtimeConfigVersionId", snapshot.versionId)
                        put("runtimeConfigHash", snapshot.hash)
                    }
                }.toString(),
                occurredAt = observedAt,
            ),
        )
    }

    private suspend fun appendCompleted(
        invocationId: String,
        status: String,
        finishedAt: Instant,
    ): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = manualDecisionRunContext(invocationId, runtimeConfigSnapshot),
                toolName = MANUAL_TOOL_NAME,
                toolCallId = null,
                clientRequestId = LLM_MANUAL_TRIGGER_KEY,
                eventType = CommandEventType.DAEMON_INVOCATION_COMPLETED,
                payload = buildJsonObject {
                    put("triggerKind", LlmDaemonTriggerKind.MANUAL.name)
                    put("triggerKey", LLM_MANUAL_TRIGGER_KEY)
                    put("eventName", null as String?)
                    put("invocationId", invocationId)
                    put("status", status)
                    put("finishedAt", finishedAt.toString())
                    runtimeConfigSnapshot?.let { snapshot ->
                        put("runtimeConfigVersionId", snapshot.versionId)
                        put("runtimeConfigHash", snapshot.hash)
                    }
                }.toString(),
                occurredAt = finishedAt,
            ),
        )
    }
}

const val LLM_LAUNCH_DISABLED = "LLM_LAUNCH_DISABLED"

/**
 * 手動 LLM 起動 service が使う repository / reader 群。
 *
 * @param riskStateRepository SOFT_HALT flat 判定に使う repository
 * @param commandEventLog 監査 log
 * @param launchReservationRepository LLM 起動予約 repository
 * @param openRiskReader 建玉 / open order の有無を読む境界
 */
data class ManualLlmLaunchServiceDependencies(
    val riskStateRepository: RiskStateRepository,
    val commandEventLog: CommandEventLog,
    val launchReservationRepository: LlmLaunchReservationRepository,
    val openRiskReader: LlmDaemonOpenRiskReader,
)

/**
 * 手動 LLM 起動 service の起動境界と実行時依存。
 *
 * @param requestBase one-shot runner に渡す固定 request
 * @param launchOneShot one-shot runner 起動境界
 * @param clock 予約と監査時刻に使う clock
 * @param idGenerator invocation ID generator
 * @param warnLogger 非同期 runner 失敗の warning logger
 * @param scope runner を非同期実行する Application lifecycle-backed scope
 */
data class ManualLlmLaunchServiceRuntime(
    val requestBase: OneShotRunnerRequest,
    val launchOneShot: suspend (OneShotRunnerRequest) -> Result<OneShotRunnerResult>,
    val clock: Clock = Clock.systemUTC(),
    val idGenerator: () -> UUID = { UUID.randomUUID() },
    val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = Logger.getLogger(DefaultManualLlmLaunchService::class.java.name),
        clock = clock,
    ),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)

private fun manualDecisionRunContext(
    invocationId: String,
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
): DecisionRunContext {
    return DecisionRunContext(
        decisionRunId = invocationId,
        llmProvider = null,
        promptHash = null,
        systemPromptVersion = null,
        marketSnapshotId = null,
        runtimeConfigVersionId = runtimeConfigSnapshot?.versionId,
        runtimeConfigHash = runtimeConfigSnapshot?.hash,
    )
}

/**
 * 手動起動の trigger key。
 */
internal const val LLM_MANUAL_TRIGGER_KEY = "manual"

/**
 * 手動起動 service の監査 tool 名。
 */
private const val MANUAL_TOOL_NAME = "llm-manual-trigger"

/**
 * 非同期 runner 失敗 log の rate limit key。
 */
private const val MANUAL_LAUNCH_FAILURE_LOG_KEY = "manual-llm-launch-runner-failure"

/**
 * reservation finish 失敗 log の rate limit key。
 */
private const val MANUAL_FINISH_FAILURE_LOG_KEY = "manual-llm-launch-finish-failure"
