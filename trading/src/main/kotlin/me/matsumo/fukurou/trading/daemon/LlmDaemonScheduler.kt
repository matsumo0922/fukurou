package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.market.FreshnessDefaults
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.runner.MAX_DAILY_INVOCATION_COUNT_WINDOW
import me.matsumo.fukurou.trading.runner.MAX_INVOCATION_COUNT_WINDOW
import me.matsumo.fukurou.trading.runner.OneShotLlmRunner
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.OneShotRunnerResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

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
 * daemon scheduler が参照する最小 ticker snapshot。
 *
 * @param lastPriceJpy 最終約定価格
 * @param sourceTimestamp 取引所由来の ticker timestamp。parse できない場合は null
 */
data class LlmDaemonTickerSnapshot(
    val lastPriceJpy: BigDecimal,
    val sourceTimestamp: Instant?,
)

/**
 * daemon scheduler が参照する ticker 読み取り境界。
 */
fun interface LlmDaemonTickerReader {
    /**
     * 最新 ticker の価格と source timestamp を返す。
     */
    suspend fun latestTicker(): Result<LlmDaemonTickerSnapshot>
}

/**
 * daemon scheduler が参照する position 読み取り境界。
 */
fun interface LlmDaemonPositionsReader {
    /**
     * 現在の position 一覧を返す。
     */
    suspend fun positions(): Result<List<Position>>
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
     * one-shot runner は起動したが失敗として完了した。
     *
     * @param invocationId 起動 ID
     * @param triggerKind 起動 trigger 種別
     * @param reason 失敗理由
     */
    data class Failed(
        val invocationId: String,
        val triggerKind: LlmDaemonTriggerKind,
        val reason: String,
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
 * @param dependencies scheduler が参照する repository / reader
 * @param runtime scheduler の実行時境界
 */
class LlmDaemonScheduler(
    private val tradingConfig: TradingBotConfig,
    dependencies: LlmDaemonSchedulerDependencies,
    runtime: LlmDaemonSchedulerRuntime,
) {
    private val riskStateRepository = dependencies.riskStateRepository
    private val commandEventLog = dependencies.commandEventLog
    private val launchReservationRepository = dependencies.launchReservationRepository
    private val openRiskReader = dependencies.openRiskReader
    private val tickerReader = dependencies.tickerReader
    private val positionsReader = dependencies.positionsReader
    private val requestBase = runtime.requestBase
    private val launchOneShot = runtime.launchOneShot
    private val clock = runtime.clock
    private val idGenerator = runtime.idGenerator
    private val warnLogger = runtime.warnLogger
    private val daemonConfig: LlmDaemonConfig = tradingConfig.daemon
    private val priceSamples = mutableListOf<LlmDaemonPriceSample>()

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
        val result = runCatching { tickUnsafe(observedAt) }

        return result.getOrElse { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }

            warnLogger.warn(
                key = DAEMON_TICK_FAILURE_LOG_KEY,
                message = "LlmDaemonScheduler tick failed.",
                throwable = throwable,
            )
            appendTickFailure(throwable, observedAt)

            LlmDaemonTickResult.Skipped(DAEMON_SKIP_TICK_FAILED, null)
        }
    }

    private suspend fun tickUnsafe(observedAt: Instant): LlmDaemonTickResult {
        val riskState = riskStateRepository.current().getOrThrow()

        if (riskState.state == RiskHaltState.HARD_HALT) {
            appendSkip(
                reason = LLM_DAEMON_SKIP_HARD_HALT,
                trigger = null,
                observedAt = observedAt,
            ).getOrThrow()

            return LlmDaemonTickResult.Skipped(LLM_DAEMON_SKIP_HARD_HALT, null)
        }

        if (hasFreshRunningReservation(observedAt)) {
            return LlmDaemonTickResult.Skipped(DAEMON_SKIP_NO_TRIGGER, null)
        }

        val hasOpenRisk = openRiskReader.hasOpenRisk().getOrThrow()

        if (riskState.state == RiskHaltState.SOFT_HALT && !hasOpenRisk) {
            appendSkip(
                reason = LLM_DAEMON_SKIP_SOFT_HALT_FLAT,
                trigger = null,
                observedAt = observedAt,
            ).getOrThrow()

            return LlmDaemonTickResult.Skipped(LLM_DAEMON_SKIP_SOFT_HALT_FLAT, null)
        }

        val trigger = selectTrigger(hasOpenRisk, observedAt)

        if (trigger == null) {
            return LlmDaemonTickResult.Skipped(DAEMON_SKIP_NO_TRIGGER, null)
        }

        return reserveAndLaunch(trigger, observedAt)
    }

    private suspend fun hasFreshRunningReservation(observedAt: Instant): Boolean {
        val activeSince = observedAt.minus(daemonConfig.launchReservationStaleAfter)

        return launchReservationRepository.hasFreshRunningReservation(activeSince).getOrThrow()
    }

    private suspend fun reserveAndLaunch(trigger: LlmDaemonTrigger, observedAt: Instant): LlmDaemonTickResult {
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

    private suspend fun runReservedInvocation(trigger: LlmDaemonTrigger, invocationId: String): LlmDaemonTickResult {
        val request = requestBase.copy(
            invocationId = invocationId,
            marketSnapshotId = "daemon-${trigger.key}-$invocationId",
            triggerKind = trigger.kind,
        )
        val result = runCatching {
            launchOneShot(request).getOrThrow()
        }
        val runnerResult = result.getOrNull()
        val failure = result.exceptionOrNull()

        if (failure is CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    finishReservedInvocation(
                        trigger = trigger,
                        invocationId = invocationId,
                        status = LlmLaunchReservationStatus.FAILED,
                        reason = failure.javaClass.simpleName,
                        finishedAt = Instant.now(clock),
                    )
                }.onFailure { finishFailure ->
                    warnLogger.warn(
                        key = DAEMON_CANCELLATION_FINISH_FAILURE_LOG_KEY,
                        message = "LlmDaemonScheduler failed to finish cancelled invocation.",
                        throwable = finishFailure,
                    )
                }
            }

            throw failure
        }

        val finishedAt = Instant.now(clock)
        val status = if (failure == null) {
            LlmLaunchReservationStatus.FINISHED
        } else {
            LlmLaunchReservationStatus.FAILED
        }
        val reason = runnerResult?.status?.name ?: failure?.javaClass?.simpleName

        finishReservedInvocation(trigger, invocationId, status, reason, finishedAt)

        if (failure != null) {
            return LlmDaemonTickResult.Failed(
                invocationId = invocationId,
                triggerKind = trigger.kind,
                reason = reason ?: "unknown",
            )
        }

        return LlmDaemonTickResult.Launched(
            invocationId = invocationId,
            triggerKind = trigger.kind,
            status = requireNotNull(runnerResult).status.name,
        )
    }

    private suspend fun selectTrigger(hasOpenRisk: Boolean, observedAt: Instant): LlmDaemonTrigger? {
        if (hasOpenRisk) {
            val marketEvaluation = marketEvaluationIfNeeded(hasOpenRisk, observedAt)

            return stopProximityTriggerIfDue(marketEvaluation, observedAt)
                ?: priceMoveTriggerIfDue(marketEvaluation, observedAt)
                ?: holdingTriggerIfDue(observedAt)
        }

        val eventTrigger = eventTriggerIfDue(observedAt)

        if (eventTrigger != null) {
            return eventTrigger
        }

        val marketEvaluation = marketEvaluationIfNeeded(hasOpenRisk, observedAt)

        return priceMoveTriggerIfDue(marketEvaluation, observedAt)
            ?: flatHeartbeatTriggerIfDue(observedAt)
    }

    private suspend fun marketEvaluationIfNeeded(
        hasOpenRisk: Boolean,
        observedAt: Instant,
    ): LlmDaemonMarketEvaluation? {
        val priceMoveNeedsTicker = daemonConfig.priceMoveTriggerEnabled
        val stopProximityNeedsTicker = hasOpenRisk && daemonConfig.stopProximityTriggerEnabled

        if (!priceMoveNeedsTicker && !stopProximityNeedsTicker) {
            return null
        }

        if (priceMoveNeedsTicker) {
            prunePriceSamples(observedAt)
        }

        val tickerSnapshot = freshTickerSnapshotOrNull(observedAt) ?: return null

        if (priceMoveNeedsTicker) {
            appendPriceSample(tickerSnapshot, observedAt)
        }

        return LlmDaemonMarketEvaluation(tickerSnapshot)
    }

    private suspend fun freshTickerSnapshotOrNull(observedAt: Instant): LlmDaemonTickerSnapshot? {
        val tickerSnapshot = runCatching {
            tickerReader.latestTicker().getOrThrow()
        }.getOrElse { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }

            warnLogger.warn(
                key = PRICE_TICKER_FETCH_FAILURE_LOG_KEY,
                message = "LlmDaemonScheduler could not read ticker for market triggers.",
                throwable = throwable,
            )

            return null
        }

        if (tickerSnapshot.sourceTimestamp == null) {
            warnLogger.warn(
                key = PRICE_TICKER_TIMESTAMP_PARSE_FAILURE_LOG_KEY,
                message = "LlmDaemonScheduler could not parse ticker timestamp. Market triggers will be skipped.",
            )

            return null
        }

        if (tickerSnapshot.lastPriceJpy <= BigDecimal.ZERO) {
            warnLogger.warn(
                key = PRICE_TICKER_NON_POSITIVE_LOG_KEY,
                message = "LlmDaemonScheduler received non-positive ticker price. Market triggers will be skipped.",
            )

            return null
        }

        val tickerAge = Duration.between(tickerSnapshot.sourceTimestamp, observedAt)
            .coerceAtLeast(Duration.ZERO)
        val tickerIsStale = tickerAge > FreshnessDefaults.tickerStaleAfter

        if (tickerIsStale) {
            warnLogger.warn(
                key = PRICE_TICKER_STALE_LOG_KEY,
                message = "LlmDaemonScheduler received stale ticker. Market triggers will be skipped.",
            )

            return null
        }

        return tickerSnapshot
    }

    private fun appendPriceSample(tickerSnapshot: LlmDaemonTickerSnapshot, observedAt: Instant) {
        val latestSample = priceSamples.lastOrNull()

        if (latestSample?.observedAt == observedAt) {
            priceSamples[priceSamples.lastIndex] = LlmDaemonPriceSample(
                observedAt = observedAt,
                priceJpy = tickerSnapshot.lastPriceJpy,
            )
        } else {
            priceSamples += LlmDaemonPriceSample(
                observedAt = observedAt,
                priceJpy = tickerSnapshot.lastPriceJpy,
            )
        }

        prunePriceSamples(observedAt)
        trimPriceSamplesToLimit()
    }

    private fun prunePriceSamples(observedAt: Instant) {
        val earliestAllowedAt = observedAt.minus(daemonConfig.priceMoveWindow)
        val baseCandidate = priceSamples.lastOrNull { sample -> !sample.observedAt.isAfter(earliestAllowedAt) }

        priceSamples.removeAll { sample -> sample.observedAt.isBefore(earliestAllowedAt) }

        if (baseCandidate != null && priceSamples.firstOrNull()?.observedAt != baseCandidate.observedAt) {
            priceSamples.add(0, baseCandidate)
        }
    }

    private fun trimPriceSamplesToLimit() {
        val overflowCount = priceSamples.size - PRICE_SAMPLE_BUFFER_LIMIT

        if (overflowCount <= 0) {
            return
        }

        repeat(overflowCount) {
            priceSamples.removeAt(0)
        }
    }

    private suspend fun stopProximityTriggerIfDue(
        marketEvaluation: LlmDaemonMarketEvaluation?,
        observedAt: Instant,
    ): LlmDaemonTrigger? {
        if (!daemonConfig.stopProximityTriggerEnabled || marketEvaluation == null) {
            return null
        }

        val positions = positionsForStopProximity() ?: return null
        val stopProximity = positions
            .asSequence()
            .filter { position -> position.status == PositionStatus.OPEN }
            .filter { position -> position.side == PositionSide.LONG }
            .mapNotNull { position -> stopProximityFor(position, marketEvaluation.tickerSnapshot.lastPriceJpy) }
            .firstOrNull { proximity -> proximity.remainingR <= daemonConfig.stopProximityRemainingRThreshold }
            ?: return null

        val trigger = LlmDaemonTrigger(
            kind = LlmDaemonTriggerKind.STOP_PROXIMITY,
            key = STOP_PROXIMITY_TRIGGER_KEY,
            eventName = null,
            details = stopProximity.toTriggerDetails(marketEvaluation.tickerSnapshot.lastPriceJpy),
        )

        return if (triggerDue(trigger.key, daemonConfig.stopProximityCooldown, observedAt)) trigger else null
    }

    private suspend fun positionsForStopProximity(): List<Position>? {
        return runCatching {
            positionsReader.positions().getOrThrow()
        }.getOrElse { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }

            warnLogger.warn(
                key = STOP_POSITIONS_FETCH_FAILURE_LOG_KEY,
                message = "LlmDaemonScheduler could not read positions for STOP proximity trigger.",
                throwable = throwable,
            )

            null
        }
    }

    private fun stopProximityFor(position: Position, currentPriceJpy: BigDecimal): LlmDaemonStopProximity? {
        val stopLossJpy = position.currentStopLossJpy?.toBigDecimal() ?: return null
        val entryPriceJpy = position.averageEntryPriceJpy.toBigDecimal()
        val oneR = entryPriceJpy.subtract(stopLossJpy).abs()

        if (oneR <= BigDecimal.ZERO) {
            warnLogger.warn(
                key = STOP_ONE_R_INVALID_LOG_KEY,
                message = "LlmDaemonScheduler skipped STOP proximity position with non-positive oneR.",
            )

            return null
        }

        val remainingR = currentPriceJpy
            .subtract(stopLossJpy)
            .divide(oneR, TRIGGER_RATIO_SCALE, RoundingMode.HALF_UP)

        return LlmDaemonStopProximity(
            positionId = position.positionId,
            remainingR = remainingR,
            stopLossJpy = stopLossJpy,
        )
    }

    private suspend fun priceMoveTriggerIfDue(
        marketEvaluation: LlmDaemonMarketEvaluation?,
        observedAt: Instant,
    ): LlmDaemonTrigger? {
        if (!daemonConfig.priceMoveTriggerEnabled || marketEvaluation == null) {
            return null
        }

        val baseSample = priceSamples.firstOrNull() ?: return null
        val sampleAge = Duration.between(baseSample.observedAt, observedAt)

        if (sampleAge < daemonConfig.priceMoveWindow) {
            return null
        }

        val currentPriceJpy = marketEvaluation.tickerSnapshot.lastPriceJpy
        val changeRatio = currentPriceJpy
            .subtract(baseSample.priceJpy)
            .divide(baseSample.priceJpy, TRIGGER_RATIO_SCALE, RoundingMode.HALF_UP)
        val triggerThresholdReached = changeRatio.abs() >= daemonConfig.priceMoveThresholdRatio

        if (!triggerThresholdReached) {
            return null
        }

        val trigger = LlmDaemonTrigger(
            kind = LlmDaemonTriggerKind.PRICE_MOVE,
            key = PRICE_MOVE_TRIGGER_KEY,
            eventName = null,
            details = priceMoveDetails(
                changeRatio = changeRatio,
                windowSeconds = daemonConfig.priceMoveWindow.seconds,
                basePriceJpy = baseSample.priceJpy,
                currentPriceJpy = currentPriceJpy,
            ),
        )

        return if (triggerDue(trigger.key, daemonConfig.priceMoveCooldown, observedAt)) trigger else null
    }

    private suspend fun holdingTriggerIfDue(observedAt: Instant): LlmDaemonTrigger? {
        val trigger = LlmDaemonTrigger(
            kind = LlmDaemonTriggerKind.HOLDING_DENSE_CHECK,
            key = HOLDING_DENSE_TRIGGER_KEY,
            eventName = null,
            details = null,
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
            details = null,
        )
        val alreadyCompletedForEvent = launchReservationRepository.latestFinishedReservedAt(trigger.key)
            .getOrThrow() != null

        return if (alreadyCompletedForEvent) null else trigger
    }

    private suspend fun flatHeartbeatTriggerIfDue(observedAt: Instant): LlmDaemonTrigger? {
        val trigger = LlmDaemonTrigger(
            kind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
            key = FLAT_HEARTBEAT_TRIGGER_KEY,
            eventName = null,
            details = null,
        )

        return if (triggerDue(trigger.key, daemonConfig.flatHeartbeatInterval, observedAt)) trigger else null
    }

    private suspend fun triggerDue(
        triggerKey: String,
        interval: Duration,
        observedAt: Instant,
    ): Boolean {
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
                    trigger.details?.let { details ->
                        put("details", details)
                    }
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

    private suspend fun finishReservedInvocation(
        trigger: LlmDaemonTrigger,
        invocationId: String,
        status: LlmLaunchReservationStatus,
        reason: String?,
        finishedAt: Instant,
    ) {
        launchReservationRepository.finish(
            LlmLaunchReservationFinish(
                invocationId = invocationId,
                status = status,
                reason = reason,
                finishedAt = finishedAt,
            ),
        ).getOrThrow()
        appendCompleted(trigger, invocationId, reason ?: "unknown", finishedAt).getOrThrow()
    }

    private suspend fun appendTickFailure(throwable: Throwable, observedAt: Instant) {
        commandEventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
                toolName = DAEMON_TOOL_NAME,
                toolCallId = null,
                clientRequestId = null,
                eventType = CommandEventType.DAEMON_TRIGGER_SKIPPED,
                payload = buildJsonObject {
                    put("reason", DAEMON_SKIP_TICK_FAILED)
                    put("errorType", throwable.javaClass.simpleName)
                    put("observedAt", observedAt.toString())
                }.toString(),
                occurredAt = observedAt,
            ),
        ).onFailure { auditFailure ->
            warnLogger.warn(
                key = DAEMON_TICK_AUDIT_FAILURE_LOG_KEY,
                message = "LlmDaemonScheduler failed to audit tick failure.",
                throwable = auditFailure,
            )
        }
    }
}

/**
 * daemon scheduler が使う repository / reader 群。
 *
 * @param riskStateRepository HARD_HALT 判定用 repository
 * @param commandEventLog 監査 log
 * @param launchReservationRepository LLM 起動予約 repository
 * @param openRiskReader 建玉 / open order の有無を読む境界
 * @param tickerReader 価格 trigger 用 ticker 読み取り境界
 * @param positionsReader STOP 接近 trigger 用 position 読み取り境界
 */
data class LlmDaemonSchedulerDependencies(
    val riskStateRepository: RiskStateRepository,
    val commandEventLog: CommandEventLog,
    val launchReservationRepository: LlmLaunchReservationRepository,
    val openRiskReader: LlmDaemonOpenRiskReader,
    val tickerReader: LlmDaemonTickerReader,
    val positionsReader: LlmDaemonPositionsReader,
)

/**
 * daemon scheduler の起動境界と実行時依存。
 *
 * @param requestBase one-shot runner に渡す固定 request
 * @param launchOneShot one-shot runner 起動境界
 * @param clock cadence と監査時刻に使う clock
 * @param idGenerator invocation ID generator
 * @param warnLogger tick 失敗の rate-limited warning logger
 */
data class LlmDaemonSchedulerRuntime(
    val requestBase: OneShotRunnerRequest,
    val launchOneShot: suspend (OneShotRunnerRequest) -> Result<OneShotRunnerResult>,
    val clock: Clock = Clock.systemUTC(),
    val idGenerator: () -> UUID = { UUID.randomUUID() },
    val warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(
        logger = Logger.getLogger(LlmDaemonScheduler::class.java.name),
        clock = clock,
    ),
)

private fun daemonDecisionRunContext(invocationId: String): DecisionRunContext {
    return DecisionRunContext(
        decisionRunId = invocationId,
        llmProvider = null,
        promptHash = null,
        systemPromptVersion = null,
        marketSnapshotId = null,
    )
}

private fun priceMoveDetails(
    changeRatio: BigDecimal,
    windowSeconds: Long,
    basePriceJpy: BigDecimal,
    currentPriceJpy: BigDecimal,
): JsonObject {
    return buildJsonObject {
        put("changeRatio", changeRatio.toPlainString())
        put("windowSeconds", windowSeconds)
        put("basePriceJpy", basePriceJpy.toPlainString())
        put("currentPriceJpy", currentPriceJpy.toPlainString())
    }
}

/**
 * daemon scheduler の内部 trigger。
 *
 * @param kind trigger 種別
 * @param key cadence と監査に使う key
 * @param eventName 経済イベント名
 * @param details trigger 固有の監査詳細
 */
private data class LlmDaemonTrigger(
    val kind: LlmDaemonTriggerKind,
    val key: String,
    val eventName: String?,
    val details: JsonObject?,
)

/**
 * daemon scheduler の価格 sample。
 *
 * @param observedAt scheduler が sample を観測した時刻
 * @param priceJpy 観測価格
 */
private data class LlmDaemonPriceSample(
    val observedAt: Instant,
    val priceJpy: BigDecimal,
)

/**
 * ticker を必要とする trigger 評価で共有する market snapshot。
 *
 * @param tickerSnapshot 最新 ticker snapshot
 */
private data class LlmDaemonMarketEvaluation(
    val tickerSnapshot: LlmDaemonTickerSnapshot,
)

/**
 * STOP 接近 trigger の候補。
 *
 * @param positionId position ID
 * @param remainingR STOP までの残り R
 * @param stopLossJpy STOP 価格
 */
private data class LlmDaemonStopProximity(
    val positionId: String,
    val remainingR: BigDecimal,
    val stopLossJpy: BigDecimal,
) {
    /**
     * audit payload の details に変換する。
     */
    fun toTriggerDetails(currentPriceJpy: BigDecimal): JsonObject {
        return buildJsonObject {
            put("positionId", positionId)
            put("remainingR", remainingR.toPlainString())
            put("stopLossJpy", stopLossJpy.toPlainString())
            put("currentPriceJpy", currentPriceJpy.toPlainString())
        }
    }
}

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
 * 価格急変の trigger key。
 */
private const val PRICE_MOVE_TRIGGER_KEY = "price-move"

/**
 * STOP 接近の trigger key。
 */
private const val STOP_PROXIMITY_TRIGGER_KEY = "stop-proximity"

/**
 * trigger 変化率の小数桁。
 */
private const val TRIGGER_RATIO_SCALE = 8

/**
 * 価格 sample buffer の最大件数。
 */
private const val PRICE_SAMPLE_BUFFER_LIMIT = 1024

/**
 * 通常 cadence 待ちで起動しなかった理由。
 */
private const val DAEMON_SKIP_NO_TRIGGER = "no_trigger_due"

/**
 * daemon tick 失敗で起動しなかった理由。
 */
private const val DAEMON_SKIP_TICK_FAILED = "tick_failed"

/**
 * daemon tick failure log の rate limit key。
 */
private const val DAEMON_TICK_FAILURE_LOG_KEY = "llm-daemon-scheduler-tick-failure"

/**
 * daemon tick failure audit failure log の rate limit key。
 */
private const val DAEMON_TICK_AUDIT_FAILURE_LOG_KEY = "llm-daemon-scheduler-tick-audit-failure"

/**
 * daemon cancellation finish failure log の rate limit key。
 */
private const val DAEMON_CANCELLATION_FINISH_FAILURE_LOG_KEY = "llm-daemon-scheduler-cancellation-finish-failure"

/**
 * ticker fetch failure log の rate limit key。
 */
private const val PRICE_TICKER_FETCH_FAILURE_LOG_KEY = "llm-daemon-price-ticker-fetch-failure"

/**
 * ticker timestamp parse failure log の rate limit key。
 */
private const val PRICE_TICKER_TIMESTAMP_PARSE_FAILURE_LOG_KEY = "llm-daemon-price-ticker-timestamp-parse-failure"

/**
 * ticker stale log の rate limit key。
 */
private const val PRICE_TICKER_STALE_LOG_KEY = "llm-daemon-price-ticker-stale"

/**
 * ticker 非正値 log の rate limit key。
 */
private const val PRICE_TICKER_NON_POSITIVE_LOG_KEY = "llm-daemon-price-ticker-non-positive"

/**
 * position fetch failure log の rate limit key。
 */
private const val STOP_POSITIONS_FETCH_FAILURE_LOG_KEY = "llm-daemon-stop-positions-fetch-failure"

/**
 * STOP 接近計算で oneR が非正値だった場合の rate limit key。
 */
private const val STOP_ONE_R_INVALID_LOG_KEY = "llm-daemon-stop-one-r-invalid"

/**
 * OneShotLlmRunner を launchOneShot lambda として渡す helper。
 */
fun OneShotLlmRunner.asDaemonLauncher(): suspend (OneShotRunnerRequest) -> Result<OneShotRunnerResult> {
    return { request -> runOneShot(request) }
}
