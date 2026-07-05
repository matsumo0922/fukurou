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
import me.matsumo.fukurou.trading.config.TradingBotConfig
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
 * @param riskStateRepository SOFT_HALT flat 判定に使う repository
 * @param commandEventLog 監査 log
 * @param launchReservationRepository LLM 起動予約 repository
 * @param openRiskReader 建玉 / open order の有無を読む境界
 * @param requestBase one-shot runner に渡す固定 request
 * @param launchOneShot one-shot runner 起動境界
 * @param clock 予約と監査時刻に使う clock
 * @param idGenerator invocation ID generator
 * @param warnLogger 非同期 runner 失敗の warning logger
 * @param scope runner を非同期実行する Application lifecycle-backed scope
 */
class DefaultManualLlmLaunchService(
    private val tradingConfig: TradingBotConfig,
    private val riskStateRepository: RiskStateRepository,
    private val commandEventLog: CommandEventLog,
    private val launchReservationRepository: LlmLaunchReservationRepository,
    private val openRiskReader: LlmDaemonOpenRiskReader,
    private val requestBase: OneShotRunnerRequest,
    private val launchOneShot: suspend (OneShotRunnerRequest) -> Result<OneShotRunnerResult>,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
    private val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = Logger.getLogger(DefaultManualLlmLaunchService::class.java.name),
        clock = clock,
    ),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ManualLlmLaunchService, AutoCloseable {

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
        val riskState = riskStateRepository.current().getOrThrow()

        if (riskState.state == RiskHaltState.SOFT_HALT) {
            val hasOpenRisk = openRiskReader.hasOpenRisk().getOrThrow()

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
        )
        val reservationOutcome = launchReservationRepository.tryReserve(reservationRequest).getOrThrow()

        if (reservationOutcome is LlmLaunchReservationOutcome.Rejected) {
            val skipReason = reservationOutcome.reason.toDaemonSkipReason()

            appendSkip(
                skipReason = skipReason,
                requestReason = reason,
                observedAt = observedAt,
            ).getOrThrow()

            return ManualLlmLaunchResult.Rejected(skipReason)
        }

        val reservedOutcome = reservationOutcome as LlmLaunchReservationOutcome.Reserved

        appendLaunchedOrFinishReservation(
            invocationId = reservedOutcome.invocationId,
            reason = reason,
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
            val cancellation = throwable as? CancellationException ?: return@invokeOnCompletion

            if (!started.get()) {
                finishCancelledBeforeStart(invocationId, cancellation)
            }
        }
    }

    private suspend fun appendLaunchedOrFinishReservation(
        invocationId: String,
        reason: String,
        observedAt: Instant,
    ): Result<Unit> {
        val appendResult = appendLaunched(
            invocationId = invocationId,
            reason = reason,
            observedAt = observedAt,
        )
        val appendFailure = appendResult.exceptionOrNull()

        if (appendFailure != null) {
            finishReservedInvocation(
                invocationId = invocationId,
                status = LlmLaunchReservationStatus.FAILED,
                reason = appendFailure.javaClass.simpleName,
                finishedAt = Instant.now(clock),
            )
        }

        return appendResult
    }

    private fun finishCancelledBeforeStart(invocationId: String, cancellation: CancellationException) {
        runBlocking {
            finishReservedInvocation(
                invocationId = invocationId,
                status = LlmLaunchReservationStatus.FAILED,
                reason = cancellation.javaClass.simpleName,
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
        val finishReason = runnerResult?.status?.name ?: failure?.javaClass?.simpleName

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
    ): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
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
                }.toString(),
                occurredAt = observedAt,
            ),
        )
    }

    private suspend fun appendLaunched(
        invocationId: String,
        reason: String,
        observedAt: Instant,
    ): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = manualDecisionRunContext(invocationId),
                toolName = MANUAL_TOOL_NAME,
                toolCallId = null,
                clientRequestId = LLM_MANUAL_TRIGGER_KEY,
                eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
                payload = buildJsonObject {
                    put("triggerKind", LlmDaemonTriggerKind.MANUAL.name)
                    put("triggerKey", LLM_MANUAL_TRIGGER_KEY)
                    put("eventName", null as String?)
                    put("invocationId", invocationId)
                    put("observedAt", observedAt.toString())
                    put("reason", reason)
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
                decisionRunContext = manualDecisionRunContext(invocationId),
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
                }.toString(),
                occurredAt = finishedAt,
            ),
        )
    }
}

private fun manualDecisionRunContext(invocationId: String): DecisionRunContext {
    return DecisionRunContext(
        decisionRunId = invocationId,
        llmProvider = null,
        promptHash = null,
        systemPromptVersion = null,
        marketSnapshotId = null,
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
