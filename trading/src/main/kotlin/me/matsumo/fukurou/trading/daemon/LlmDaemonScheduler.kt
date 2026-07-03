package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.runner.MAX_DAILY_INVOCATION_COUNT_WINDOW
import me.matsumo.fukurou.trading.runner.MAX_INVOCATION_COUNT_WINDOW
import me.matsumo.fukurou.trading.runner.OneShotLlmRunner
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.OneShotRunnerResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * daemon scheduler が参照する open risk 状態。
 */
fun interface LlmDaemonOpenRiskReader {
    /**
     * open position または open order が存在するなら true を返す。
     */
    suspend fun hasOpenRisk(): Result<Boolean>
}

/**
 * LLM daemon scheduler の 1 tick 結果。
 */
sealed interface LlmDaemonTickResult {
    /**
     * one-shot runner を起動した。
     *
     * @param invocationId 起動 ID
     * @param triggerKind 起動 trigger 種別
     * @param status runner の最終状態
     */
    data class Launched(
        val invocationId: String,
        val triggerKind: LlmDaemonTriggerKind,
        val status: String,
    ) : LlmDaemonTickResult

    /**
     * one-shot runner を起動しなかった。
     *
     * @param reason skip 理由
     * @param triggerKind 起動候補 trigger 種別
     */
    data class Skipped(
        val reason: String,
        val triggerKind: LlmDaemonTriggerKind?,
    ) : LlmDaemonTickResult
}

/**
 * Ktor 常駐 process 内で one-shot runner を保守的に起動する daemon scheduler。
 *
 * @param tradingConfig 取引 bot 設定
 * @param riskStateRepository HARD_HALT 判定用 repository
 * @param commandEventLog 監査 log
 * @param launchReservationRepository LLM 起動予約 repository
 * @param openRiskReader 建玉 / open order の有無を読む境界
 * @param requestBase one-shot runner に渡す固定 request
 * @param launchOneShot one-shot runner 起動境界
 * @param clock cadence と監査時刻に使う clock
 * @param idGenerator invocation ID generator
 */
class LlmDaemonScheduler(
    private val tradingConfig: TradingBotConfig,
    private val riskStateRepository: RiskStateRepository,
    private val commandEventLog: CommandEventLog,
    private val launchReservationRepository: LlmLaunchReservationRepository,
    private val openRiskReader: LlmDaemonOpenRiskReader,
    private val requestBase: OneShotRunnerRequest,
    private val launchOneShot: suspend (OneShotRunnerRequest) -> Result<OneShotRunnerResult>,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
) {
    private val daemonConfig: LlmDaemonConfig = tradingConfig.daemon

    /**
     * daemon scheduler loop を開始する。
     */
    suspend fun runLoop(interval: Duration = daemonConfig.pollInterval) {
        appendDaemonStarted().getOrThrow()

        while (currentCoroutineContext().isActive) {
            tick()
            delay(interval.toMillis())
        }
    }

    /**
     * daemon scheduler の 1 回分の起動判断を進める。
     */
    suspend fun tick(): LlmDaemonTickResult {
        val observedAt = Instant.now(clock)
        val hardHalt = riskStateRepository.current().getOrThrow().hardHalt

        if (hardHalt) {
            appendSkip(
                reason = DAEMON_SKIP_HARD_HALT,
                trigger = null,
                observedAt = observedAt,
            ).getOrThrow()

            return LlmDaemonTickResult.Skipped(DAEMON_SKIP_HARD_HALT, null)
        }

        val trigger = selectTrigger(observedAt)

        if (trigger == null) {
            return LlmDaemonTickResult.Skipped(DAEMON_SKIP_NO_TRIGGER, null)
        }

        return reserveAndLaunch(trigger, observedAt)
    }

    private suspend fun reserveAndLaunch(
        trigger: LlmDaemonTrigger,
        observedAt: Instant,
    ): LlmDaemonTickResult {
        val invocationId = idGenerator().toString()
        val reservationRequest = LlmLaunchReservationRequest(
            invocationId = invocationId,
            triggerKind = trigger.kind,
            triggerKey = trigger.key,
            reservedAt = observedAt,
            runnerConfig = tradingConfig.runner,
            hourlyWindow = MAX_INVOCATION_COUNT_WINDOW,
            dailyWindow = MAX_DAILY_INVOCATION_COUNT_WINDOW,
            activeReservationStaleAfter = daemonConfig.launchReservationStaleAfter,
        )
        val reservationOutcome = launchReservationRepository.tryReserve(reservationRequest).getOrThrow()

        if (reservationOutcome is LlmLaunchReservationOutcome.Rejected) {
            val reason = reservationOutcome.reason.toDaemonSkipReason()

            appendSkip(
                reason = reason,
                trigger = trigger,
                observedAt = observedAt,
            ).getOrThrow()

            return LlmDaemonTickResult.Skipped(reason, trigger.kind)
        }

        appendLaunched(trigger, invocationId, observedAt).getOrThrow()

        return runReservedInvocation(trigger, invocationId)
    }

    private suspend fun runReservedInvocation(
        trigger: LlmDaemonTrigger,
        invocationId: String,
    ): LlmDaemonTickResult {
        val request = requestBase.copy(
            invocationId = invocationId,
            marketSnapshotId = "daemon-${trigger.key}-$invocationId",
        )
        val result = launchOneShot(request)
        val runnerResult = result.getOrNull()
        val failure = result.exceptionOrNull()
        val finishedAt = Instant.now(clock)
        val status = if (failure == null) {
            LlmLaunchReservationStatus.FINISHED
        } else {
            LlmLaunchReservationStatus.FAILED
        }
        val reason = runnerResult?.status?.name ?: failure?.javaClass?.simpleName

        launchReservationRepository.finish(
            LlmLaunchReservationFinish(
                invocationId = invocationId,
                status = status,
                reason = reason,
                finishedAt = finishedAt,
            ),
        ).getOrThrow()
        appendCompleted(trigger, invocationId, reason ?: "unknown", finishedAt).getOrThrow()

        if (failure != null) {
            throw failure
        }

        return LlmDaemonTickResult.Launched(
            invocationId = invocationId,
            triggerKind = trigger.kind,
            status = requireNotNull(runnerResult).status.name,
        )
    }

    private suspend fun selectTrigger(observedAt: Instant): LlmDaemonTrigger? {
        val hasOpenRisk = openRiskReader.hasOpenRisk().getOrThrow()

        if (hasOpenRisk) {
            return holdingTriggerIfDue(observedAt)
        }

        val eventTrigger = eventTriggerIfDue(observedAt)

        if (eventTrigger != null) {
            return eventTrigger
        }

        return flatHeartbeatTriggerIfDue(observedAt)
    }

    private suspend fun holdingTriggerIfDue(observedAt: Instant): LlmDaemonTrigger? {
        val trigger = LlmDaemonTrigger(
            kind = LlmDaemonTriggerKind.HOLDING_DENSE_CHECK,
            key = HOLDING_DENSE_TRIGGER_KEY,
            eventName = null,
        )

        return if (triggerDue(trigger.key, daemonConfig.holdingCheckInterval, observedAt)) trigger else null
    }

    private suspend fun eventTriggerIfDue(observedAt: Instant): LlmDaemonTrigger? {
        val activeEvent = tradingConfig.safetyFloor.economicEventBlackouts
            .firstOrNull { event -> event.contains(observedAt) }
            ?: return null
        val trigger = LlmDaemonTrigger(
            kind = LlmDaemonTriggerKind.ECONOMIC_EVENT,
            key = "economic-event:${activeEvent.eventId}:${activeEvent.eventAt}",
            eventName = activeEvent.eventName,
        )
        val alreadyLaunchedForEvent = launchReservationRepository.latestReservedAt(trigger.key)
            .getOrThrow() != null

        return if (alreadyLaunchedForEvent) null else trigger
    }

    private suspend fun flatHeartbeatTriggerIfDue(observedAt: Instant): LlmDaemonTrigger? {
        val trigger = LlmDaemonTrigger(
            kind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            key = FLAT_HEARTBEAT_TRIGGER_KEY,
            eventName = null,
        )

        return if (triggerDue(trigger.key, daemonConfig.flatHeartbeatInterval, observedAt)) trigger else null
    }

    private suspend fun triggerDue(triggerKey: String, interval: Duration, observedAt: Instant): Boolean {
        val latestReservedAt = launchReservationRepository.latestReservedAt(triggerKey).getOrThrow()
            ?: return true
        val nextDueAt = latestReservedAt.plus(interval)

        return !observedAt.isBefore(nextDueAt)
    }

    private suspend fun appendDaemonStarted(): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
                toolName = DAEMON_TOOL_NAME,
                toolCallId = null,
                clientRequestId = null,
                eventType = CommandEventType.DAEMON_STARTED,
                payload = buildJsonObject {
                    put("enabled", daemonConfig.enabled)
                    put("pollIntervalSeconds", daemonConfig.pollInterval.seconds)
                    put("flatHeartbeatSeconds", daemonConfig.flatHeartbeatInterval.seconds)
                    put("holdingCheckSeconds", daemonConfig.holdingCheckInterval.seconds)
                }.toString(),
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private suspend fun appendSkip(
        reason: String,
        trigger: LlmDaemonTrigger?,
        observedAt: Instant,
    ): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
                toolName = DAEMON_TOOL_NAME,
                toolCallId = null,
                clientRequestId = trigger?.key,
                eventType = CommandEventType.DAEMON_TRIGGER_SKIPPED,
                payload = buildJsonObject {
                    put("reason", reason)
                    put("triggerKind", trigger?.kind?.name)
                    put("triggerKey", trigger?.key)
                    put("eventName", trigger?.eventName)
                    put("observedAt", observedAt.toString())
                }.toString(),
                occurredAt = observedAt,
            ),
        )
    }

    private suspend fun appendLaunched(
        trigger: LlmDaemonTrigger,
        invocationId: String,
        observedAt: Instant,
    ): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = daemonDecisionRunContext(invocationId),
                toolName = DAEMON_TOOL_NAME,
                toolCallId = null,
                clientRequestId = trigger.key,
                eventType = CommandEventType.DAEMON_TRIGGER_LAUNCHED,
                payload = buildJsonObject {
                    put("triggerKind", trigger.kind.name)
                    put("triggerKey", trigger.key)
                    put("eventName", trigger.eventName)
                    put("invocationId", invocationId)
                    put("observedAt", observedAt.toString())
                }.toString(),
                occurredAt = observedAt,
            ),
        )
    }

    private suspend fun appendCompleted(
        trigger: LlmDaemonTrigger,
        invocationId: String,
        status: String,
        finishedAt: Instant,
    ): Result<Unit> {
        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = daemonDecisionRunContext(invocationId),
                toolName = DAEMON_TOOL_NAME,
                toolCallId = null,
                clientRequestId = trigger.key,
                eventType = CommandEventType.DAEMON_INVOCATION_COMPLETED,
                payload = buildJsonObject {
                    put("triggerKind", trigger.kind.name)
                    put("triggerKey", trigger.key)
                    put("eventName", trigger.eventName)
                    put("invocationId", invocationId)
                    put("status", status)
                    put("finishedAt", finishedAt.toString())
                }.toString(),
                occurredAt = finishedAt,
            ),
        )
    }
}

private fun daemonDecisionRunContext(invocationId: String): DecisionRunContext {
    return DecisionRunContext(
        decisionRunId = invocationId,
        llmProvider = null,
        promptHash = null,
        systemPromptVersion = null,
        marketSnapshotId = null,
    )
}

private fun LlmLaunchReservationRejectionReason.toDaemonSkipReason(): String {
    return when (this) {
        LlmLaunchReservationRejectionReason.HARD_HALT -> DAEMON_SKIP_HARD_HALT
        LlmLaunchReservationRejectionReason.CONCURRENT_INVOCATION -> "concurrent_invocation"
        LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_HOUR -> "max_invocations_per_hour_exceeded"
        LlmLaunchReservationRejectionReason.MAX_INVOCATIONS_PER_DAY -> "max_invocations_per_day_exceeded"
    }
}

/**
 * daemon scheduler の内部 trigger。
 *
 * @param kind trigger 種別
 * @param key cadence と監査に使う key
 * @param eventName 経済イベント名
 */
private data class LlmDaemonTrigger(
    val kind: LlmDaemonTriggerKind,
    val key: String,
    val eventName: String?,
)

/**
 * daemon scheduler の監査 tool 名。
 */
private const val DAEMON_TOOL_NAME = "llm-daemon-scheduler"

/**
 * flat heartbeat の trigger key。
 */
private const val FLAT_HEARTBEAT_TRIGGER_KEY = "flat-heartbeat"

/**
 * holding dense check の trigger key。
 */
private const val HOLDING_DENSE_TRIGGER_KEY = "holding-dense-check"

/**
 * 通常 cadence 待ちで起動しなかった理由。
 */
private const val DAEMON_SKIP_NO_TRIGGER = "no_trigger_due"

/**
 * HARD_HALT 中で起動しなかった理由。
 */
private const val DAEMON_SKIP_HARD_HALT = "hard_halt"

/**
 * OneShotLlmRunner を launchOneShot lambda として渡す helper。
 */
fun OneShotLlmRunner.asDaemonLauncher(): suspend (OneShotRunnerRequest) -> Result<OneShotRunnerResult> {
    return { request -> runOneShot(request) }
}
