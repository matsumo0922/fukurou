package me.matsumo.fukurou.trading.runner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.broker.toJsonObject
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.tool.CallerInvocation
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * LLM decision 保存後に runner が決定論的な paper 副作用へ写像する lifecycle。
 *
 * LLM role には cancel / close / protection update tool を開けず、既存 ledger から一意に対象を
 * 決められる場合だけ runner が実行する。対象が曖昧な場合は no-trade audit を残して fail closed する。
 *
 * @param tradingRuntime trading runtime
 * @param tradingConfig trading config
 * @param clock audit timestamp 用 clock
 * @param idGenerator command / tool call ID generator
 */
internal class DecisionExecutionLifecycle(
    private val tradingRuntime: TradingRuntime,
    private val tradingConfig: TradingBotConfig,
    private val clock: Clock,
    private val idGenerator: () -> UUID,
) {

    /**
     * TTL を超過した resting entry order を deterministic に cancel する。
     */
    suspend fun cancelExpiredRestingEntryOrders(context: DecisionRunContext): Result<List<PaperTradeResult>> {
        return runCatching {
            val startedAt = Instant.now(clock)
            val openOrders = tradingRuntime.broker.getOpenOrders().getOrThrow()
            val expiredOrders = openOrders.filter { order ->
                order.isExpiredRestingEntryOrder(
                    observedAt = startedAt,
                    ttl = tradingConfig.decisionProtocol.restingEntryOrderTtl,
                )
            }
            val successfulResults = mutableListOf<PaperTradeResult>()
            val failedOrderIds = mutableListOf<String>()
            val failureSummaries = mutableListOf<String>()

            expiredOrders.forEach { order ->
                runCatching {
                    cancelOrder(
                        context = context,
                        order = order,
                        clientRequestId = "runner-ttl-cancel-${order.orderId}",
                        reason = "resting_entry_order_ttl_exceeded",
                    )
                }.onSuccess { result ->
                    successfulResults += result
                }.onFailure { throwable ->
                    val failureSummary = listOf(
                        order.orderId,
                        throwable.javaClass.simpleName,
                        throwable.message.orEmpty(),
                    ).joinToString(":")

                    failedOrderIds += order.orderId
                    failureSummaries += failureSummary
                }
            }
            val canceledOrderIds = successfulResults
                .flatMap { result -> result.orderIds }
                .joinToString(",")

            appendLifecyclePhase(
                context = context,
                phase = "stale_resting_entry_ttl_sweep",
                startedAt = startedAt,
                details = buildJsonObject {
                    put("ttlSeconds", tradingConfig.decisionProtocol.restingEntryOrderTtl.seconds)
                    put("expiredOrderCount", expiredOrders.size)
                    put("canceledOrderIds", canceledOrderIds)
                    put("failedOrderIds", failedOrderIds.joinToString(","))
                    put("cancelSuccessCount", successfulResults.size)
                    put("cancelFailureCount", failedOrderIds.size)
                    put("failureSummaries", failureSummaries.joinToString(" | "))
                },
            ).getOrThrow()

            successfulResults
        }
    }

    /**
     * EXIT decision を close_position または cancel_order に写像する。
     */
    suspend fun executeExitDecision(
        context: DecisionRunContext,
        decision: DecisionSubmissionResult,
    ): DecisionLifecycleExecutionResult {
        val startedAt = Instant.now(clock)
        val openPositions = tradingRuntime.broker.getPositions().getOrThrow()
        val openEntryOrders = openRestingEntryOrders()
        val target = resolveExitTarget(openPositions, openEntryOrders)

        if (target is ExitExecutionTarget.FailClosed) {
            appendFailClosedPhase(
                context = context,
                phase = "exit_execution",
                failure = target.failure,
                startedAt = startedAt,
            ).getOrThrow()
            recordNoTrade(context, target.failure.reason, null).getOrThrow()

            return DecisionLifecycleExecutionResult(OneShotRunnerStatus.NO_TRADE_AUDITED, null)
        }

        val result = runCatching {
            when (target) {
                is ExitExecutionTarget.ClosePosition -> closePosition(
                    context = context,
                    position = target.position,
                    decision = decision,
                    closeRatio = BigDecimal.ONE,
                    reason = "exit_decision_close_position",
                )
                is ExitExecutionTarget.CancelEntryOrder -> cancelOrder(
                    context = context,
                    order = target.order,
                    clientRequestId = "runner-exit-cancel-${target.order.orderId}",
                    reason = "exit_decision_cancel_resting_entry_order",
                )
                is ExitExecutionTarget.FailClosed -> error("fail closed target must be handled before execution.")
            }
        }.getOrElse { throwable ->
            appendFailClosedPhase(
                context = context,
                phase = "exit_execution",
                failure = DecisionLifecycleFailure("exit_execution_failed"),
                startedAt = startedAt,
            ).getOrThrow()
            recordNoTrade(context, "exit_execution_failed", throwable).getOrThrow()

            return DecisionLifecycleExecutionResult(OneShotRunnerStatus.NO_TRADE_AUDITED, null)
        }

        appendExecutionPhase(
            context = context,
            phase = "exit_execution",
            operation = target.operationName,
            result = result,
            startedAt = startedAt,
        ).getOrThrow()

        return DecisionLifecycleExecutionResult(OneShotRunnerStatus.PAPER_EXIT_EXECUTED, result)
    }

    /**
     * REDUCE decision を close_position に写像する。
     */
    suspend fun executeReduceDecision(
        context: DecisionRunContext,
        decision: DecisionSubmissionResult,
    ): DecisionLifecycleExecutionResult {
        val startedAt = Instant.now(clock)
        val openPositions = tradingRuntime.broker.getPositions().getOrThrow()
        val targetPosition = openPositions.singleOrNull()

        if (targetPosition == null) {
            val failure = reduceFailure(openPositions)

            appendFailClosedPhase(
                context = context,
                phase = "reduce_execution",
                failure = failure,
                startedAt = startedAt,
            ).getOrThrow()
            recordNoTrade(context, failure.reason, null).getOrThrow()

            return DecisionLifecycleExecutionResult(
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                tradeResult = null,
            )
        }

        val closeRatio = requireNotNull(decision.decision.submission.closeRatio) {
            "REDUCE decision requires close_ratio."
        }
        val result = runCatching {
            closePosition(
                context = context,
                position = targetPosition,
                decision = decision,
                closeRatio = closeRatio,
                reason = "reduce_decision_close_position",
            )
        }.getOrElse { throwable ->
            appendFailClosedPhase(
                context = context,
                phase = "reduce_execution",
                failure = DecisionLifecycleFailure("reduce_execution_failed"),
                startedAt = startedAt,
            ).getOrThrow()
            recordNoTrade(context, "reduce_execution_failed", throwable).getOrThrow()

            return DecisionLifecycleExecutionResult(
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                tradeResult = null,
            )
        }

        appendExecutionPhase(
            context = context,
            phase = "reduce_execution",
            operation = "close_position",
            result = result,
            startedAt = startedAt,
        ).getOrThrow()

        return DecisionLifecycleExecutionResult(
            status = OneShotRunnerStatus.PAPER_REDUCE_EXECUTED,
            tradeResult = result,
        )
    }

    /**
     * ADD_LONG decision の対象 position が一意に決まることを検証する。
     */
    suspend fun ensureAddLongTargetPosition(context: DecisionRunContext): DecisionLifecycleExecutionResult? {
        val startedAt = Instant.now(clock)
        val openPositions = tradingRuntime.broker.getPositions().getOrThrow()
        val targetPosition = openPositions.singleOrNull()

        if (targetPosition != null) return null

        val failure = if (openPositions.isEmpty()) {
            DecisionLifecycleFailure("add_long_target_position_missing")
        } else {
            DecisionLifecycleFailure("add_long_target_position_ambiguous")
        }

        appendFailClosedPhase(
            context = context,
            phase = "add_long_execution",
            failure = failure,
            startedAt = startedAt,
        ).getOrThrow()
        recordNoTrade(context, failure.reason, null).getOrThrow()

        return DecisionLifecycleExecutionResult(
            status = OneShotRunnerStatus.NO_TRADE_AUDITED,
            tradeResult = null,
        )
    }

    /**
     * ADJUST_PROTECTION decision を update_protection に写像する。
     */
    suspend fun executeAdjustProtectionDecision(
        context: DecisionRunContext,
        decision: DecisionSubmissionResult,
    ): DecisionLifecycleExecutionResult {
        val startedAt = Instant.now(clock)
        val openPositions = tradingRuntime.broker.getPositions().getOrThrow()
        val targetPosition = openPositions.singleOrNull()
        val failClosedFailure = adjustProtectionFailClosedFailure(decision, openPositions, targetPosition)

        if (failClosedFailure != null) {
            appendFailClosedPhase(
                context = context,
                phase = "adjust_protection_execution",
                failure = failClosedFailure,
                startedAt = startedAt,
            ).getOrThrow()
            recordNoTrade(context, failClosedFailure.reason, null).getOrThrow()

            return DecisionLifecycleExecutionResult(
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                tradeResult = null,
            )
        }

        val position = requireNotNull(targetPosition)
        val targetPrice = requireNotNull(decision.tradePlan?.draft?.targetPriceJpy)
        val result = runCatching {
            updateProtection(
                context = context,
                position = position,
                newTakeProfitPriceJpy = targetPrice,
            )
        }.getOrElse { throwable ->
            appendFailClosedPhase(
                context = context,
                phase = "adjust_protection_execution",
                failure = DecisionLifecycleFailure("adjust_protection_execution_failed"),
                startedAt = startedAt,
            ).getOrThrow()
            recordNoTrade(context, "adjust_protection_execution_failed", throwable).getOrThrow()

            return DecisionLifecycleExecutionResult(
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                tradeResult = null,
            )
        }

        appendExecutionPhase(
            context = context,
            phase = "adjust_protection_execution",
            operation = "update_protection",
            result = result,
            startedAt = startedAt,
        ).getOrThrow()

        return DecisionLifecycleExecutionResult(
            status = OneShotRunnerStatus.PAPER_PROTECTION_UPDATED,
            tradeResult = result,
        )
    }

    private suspend fun openRestingEntryOrders(): List<Order> {
        return tradingRuntime.broker.getOpenOrders()
            .getOrThrow()
            .filter { order -> order.isRestingEntryOrder() }
    }

    private fun resolveExitTarget(openPositions: List<Position>, openEntryOrders: List<Order>): ExitExecutionTarget {
        if (openPositions.size == 1) {
            return ExitExecutionTarget.ClosePosition(openPositions.single())
        }
        if (openPositions.size > 1) {
            return ExitExecutionTarget.FailClosed(
                exitFailure(
                    reason = "exit_target_ambiguous",
                    openPositions = openPositions,
                    openEntryOrders = openEntryOrders,
                ),
            )
        }
        if (openEntryOrders.size == 1) {
            return ExitExecutionTarget.CancelEntryOrder(openEntryOrders.single())
        }
        if (openEntryOrders.isEmpty()) {
            return ExitExecutionTarget.FailClosed(
                exitFailure(
                    reason = "exit_target_not_found",
                    openPositions = openPositions,
                    openEntryOrders = openEntryOrders,
                ),
            )
        }

        return ExitExecutionTarget.FailClosed(
            exitFailure(
                reason = "exit_target_ambiguous",
                openPositions = openPositions,
                openEntryOrders = openEntryOrders,
            ),
        )
    }

    private fun adjustProtectionFailClosedFailure(
        decision: DecisionSubmissionResult,
        openPositions: List<Position>,
        targetPosition: Position?,
    ): DecisionLifecycleFailure? {
        if (openPositions.size != 1) {
            return DecisionLifecycleFailure(
                reason = "adjust_protection_target_ambiguous",
                details = buildJsonObject {
                    put("positionCount", openPositions.size)
                },
            )
        }

        val position = requireNotNull(targetPosition)
        val stopPriceText = position.currentStopLossJpy
            ?: return DecisionLifecycleFailure(
                reason = "adjust_protection_unprotected_position",
                details = buildJsonObject {
                    put("positionId", position.positionId)
                },
            )

        val targetPrice = decision.tradePlan?.draft?.targetPriceJpy
            ?: return DecisionLifecycleFailure(
                reason = "adjust_protection_missing_target_price",
                details = buildJsonObject {
                    put("positionId", position.positionId)
                    put("currentPriceJpy", position.currentPriceJpy)
                    put("currentStopLossJpy", stopPriceText)
                },
            )

        val currentPrice = position.currentPriceJpy.toBigDecimalOrNull()
            ?: return DecisionLifecycleFailure(
                reason = "adjust_protection_invalid_position_price",
                details = adjustProtectionPriceDetails(position, stopPriceText, targetPrice),
            )
        val stopPrice = stopPriceText.toBigDecimalOrNull()
            ?: return DecisionLifecycleFailure(
                reason = "adjust_protection_invalid_stop_price",
                details = adjustProtectionPriceDetails(position, stopPriceText, targetPrice),
            )
        val targetIsNotAboveCurrent = targetPrice <= currentPrice
        val targetIsNotAboveStop = targetPrice <= stopPrice

        if (targetIsNotAboveCurrent || targetIsNotAboveStop) {
            return DecisionLifecycleFailure(
                reason = "adjust_protection_invalid_take_profit_price",
                details = adjustProtectionPriceDetails(position, stopPriceText, targetPrice),
            )
        }

        return null
    }

    private fun exitFailure(
        reason: String,
        openPositions: List<Position>,
        openEntryOrders: List<Order>,
    ): DecisionLifecycleFailure {
        return DecisionLifecycleFailure(
            reason = reason,
            details = buildJsonObject {
                put("positionCount", openPositions.size)
                put("restingEntryOrderCount", openEntryOrders.size)
            },
        )
    }

    private fun reduceFailure(openPositions: List<Position>): DecisionLifecycleFailure {
        val reason = if (openPositions.isEmpty()) {
            "reduce_target_not_found"
        } else {
            "reduce_target_ambiguous"
        }

        return DecisionLifecycleFailure(
            reason = reason,
            details = buildJsonObject {
                put("positionCount", openPositions.size)
            },
        )
    }

    private fun adjustProtectionPriceDetails(
        position: Position,
        stopPriceText: String,
        targetPrice: BigDecimal,
    ): JsonObject {
        return buildJsonObject {
            put("positionId", position.positionId)
            put("currentPriceJpy", position.currentPriceJpy)
            put("currentStopLossJpy", stopPriceText)
            put("targetPriceJpy", targetPrice.toPlainString())
        }
    }

    private suspend fun closePosition(
        context: DecisionRunContext,
        position: Position,
        decision: DecisionSubmissionResult,
        closeRatio: BigDecimal,
        reason: String,
    ): PaperTradeResult {
        val action = decision.decision.submission.action
        val call = guardedTradeCall(
            toolName = "close_position",
            clientRequestId = "runner-${action.runnerCloseClientRequestPrefix()}-${position.positionId}",
            context = context,
            payload = buildJsonObject {
                put("decisionId", decision.decision.decisionId.toString())
                put("action", action.name)
                put("positionId", position.positionId)
                put("close_ratio", closeRatio.toPlainString())
                put("source", "one_shot_runner")
                put("reason", reason)
            }.toString(),
        )
        val command = ClosePositionCommand(
            commandId = idGenerator(),
            positionId = UUID.fromString(position.positionId),
            closeAll = false,
            closeRatio = closeRatio,
            reasonJa = "${action.name} decision による runner deterministic close。",
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        )

        return tradingRuntime.toolCallGuard.runRiskReducingTradeTool(call) {
            tradingRuntime.broker.closePosition(command).getOrThrow()
        }.getOrThrow()
    }

    private suspend fun cancelOrder(
        context: DecisionRunContext,
        order: Order,
        clientRequestId: String,
        reason: String,
    ): PaperTradeResult {
        val call = guardedTradeCall(
            toolName = "cancel_order",
            clientRequestId = clientRequestId,
            context = context,
            payload = buildJsonObject {
                put("orderId", order.orderId)
                put("source", "one_shot_runner")
                put("reason", reason)
            }.toString(),
        )
        val command = CancelOrderCommand(
            commandId = idGenerator(),
            orderId = UUID.fromString(order.orderId),
            reasonJa = "$reason: runner deterministic cancel。",
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        )

        return tradingRuntime.toolCallGuard.runTradeTool(call) {
            tradingRuntime.broker.cancelOrder(command).getOrThrow()
        }.getOrThrow()
    }

    private suspend fun updateProtection(
        context: DecisionRunContext,
        position: Position,
        newTakeProfitPriceJpy: BigDecimal,
    ): PaperTradeResult {
        val call = guardedTradeCall(
            toolName = "update_protection",
            clientRequestId = "runner-adjust-protection-${position.positionId}",
            context = context,
            payload = buildJsonObject {
                put("positionId", position.positionId)
                put("source", "one_shot_runner")
                put("newStopPriceJpy", "unchanged")
                put("newTakeProfitPriceJpy", newTakeProfitPriceJpy.toPlainString())
                put("reason", "adjust_protection_decision_update_take_profit")
            }.toString(),
        )
        val command = UpdateProtectionCommand(
            commandId = idGenerator(),
            positionId = UUID.fromString(position.positionId),
            newStopPriceJpy = null,
            takeProfitPriceSpecified = true,
            newTakeProfitPriceJpy = newTakeProfitPriceJpy,
            reasonJa = "ADJUST_PROTECTION decision による runner deterministic protection update。",
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        )

        return tradingRuntime.toolCallGuard.runTradeTool(call) {
            tradingRuntime.broker.updateProtection(command).getOrThrow()
        }.getOrThrow()
    }

    private fun guardedTradeCall(
        toolName: String,
        clientRequestId: String,
        context: DecisionRunContext,
        payload: String,
    ): GuardedToolCall {
        return GuardedToolCall(
            toolName = toolName,
            toolCallId = idGenerator().toString(),
            clientRequestId = clientRequestId,
            decisionRunContext = context,
            payload = payload,
        )
    }

    private suspend fun appendExecutionPhase(
        context: DecisionRunContext,
        phase: String,
        operation: String,
        result: PaperTradeResult,
        startedAt: Instant,
    ): Result<Unit> {
        return appendLifecyclePhase(
            context = context,
            phase = phase,
            startedAt = startedAt,
            details = buildJsonObject {
                put("operation", operation)
                put("accepted", result.accepted)
                put("status", result.status.name)
                put("orderIds", result.orderIds.joinToString(","))
                put("positionIds", result.positionIds.joinToString(","))
                if (result.divergenceMemos.isNotEmpty()) {
                    put(
                        "paperExecutionDivergenceMemos",
                        JsonArray(result.divergenceMemos.map { memo -> memo.toJsonObject() }),
                    )
                }
            },
        )
    }

    private suspend fun appendFailClosedPhase(
        context: DecisionRunContext,
        phase: String,
        failure: DecisionLifecycleFailure,
        startedAt: Instant,
    ): Result<Unit> {
        return appendLifecyclePhase(
            context = context,
            phase = phase,
            startedAt = startedAt,
            details = buildJsonObject {
                put("accepted", false)
                put("reason", failure.reason)
                put("evidence", failure.details)
            },
        )
    }

    private suspend fun appendLifecyclePhase(
        context: DecisionRunContext,
        phase: String,
        startedAt: Instant,
        details: JsonObject,
    ): Result<Unit> {
        val occurredAt = Instant.now(clock)
        val durationMillis = Duration.between(startedAt, occurredAt)
            .toMillis()
            .coerceAtLeast(0L)
        val payload = buildJsonObject {
            put("phase", phase)
            put("durationMillis", durationMillis)
            put("details", details)
        }.toString()

        return tradingRuntime.commandEventLog.append(
            CommandEvent(
                decisionRunContext = context,
                toolName = "one_shot_runner",
                toolCallId = null,
                clientRequestId = context.decisionRunId,
                eventType = CommandEventType.DECISION_LIFECYCLE_COMPLETED,
                payload = payload,
                occurredAt = occurredAt,
            ),
        )
    }

    private suspend fun recordNoTrade(
        context: DecisionRunContext,
        reason: String,
        cause: Throwable?,
    ): Result<Unit> {
        return tradingRuntime.callerNoTradeGuard.recordNoTradeExit(
            invocation = CallerInvocation(
                operationName = "one_shot_runner",
                clientRequestId = context.decisionRunId,
                decisionRunContext = context,
            ),
            reason = reason,
            cause = cause,
        )
    }
}

private fun DecisionAction.runnerCloseClientRequestPrefix(): String {
    return when (this) {
        DecisionAction.REDUCE -> "reduce-close"
        else -> "exit-close"
    }
}

/**
 * decision lifecycle 実行結果。
 *
 * @param status runner の最終状態
 * @param tradeResult 実行した paper trade command の結果
 */
internal data class DecisionLifecycleExecutionResult(
    val status: OneShotRunnerStatus,
    val tradeResult: PaperTradeResult?,
)

/**
 * lifecycle fail-closed の監査理由と補足 evidence。
 *
 * @param reason no-trade audit に残す理由
 * @param details runner lifecycle event に残す補足 evidence
 */
private data class DecisionLifecycleFailure(
    val reason: String,
    val details: JsonObject = buildJsonObject {},
)

/**
 * EXIT decision の決定論的な実行対象。
 */
private sealed interface ExitExecutionTarget {
    /**
     * 監査 payload に保存する操作名。
     */
    val operationName: String

    /**
     * 単一 position を close する。
     *
     * @param position close 対象 position
     */
    data class ClosePosition(
        val position: Position,
    ) : ExitExecutionTarget {
        override val operationName: String = "close_position"
    }

    /**
     * 単一 resting entry order を cancel する。
     *
     * @param order cancel 対象 order
     */
    data class CancelEntryOrder(
        val order: Order,
    ) : ExitExecutionTarget {
        override val operationName: String = "cancel_order"
    }

    /**
     * 対象が一意に決められないため fail closed する。
     *
     * @param failure fail-closed の理由と evidence
     */
    data class FailClosed(
        val failure: DecisionLifecycleFailure,
    ) : ExitExecutionTarget {
        override val operationName: String = "fail_closed"
    }
}

private fun Order.isRestingEntryOrder(): Boolean {
    val statusIsOpenRisk = status == OrderStatus.OPEN || status == OrderStatus.PENDING_CANCEL
    val isBuyEntry = side == OrderSide.BUY && positionId == null
    val isRestingType = orderType == OrderType.LIMIT || orderType == OrderType.STOP

    return statusIsOpenRisk && isBuyEntry && isRestingType
}

private fun Order.isExpiredRestingEntryOrder(observedAt: Instant, ttl: Duration): Boolean {
    if (!isRestingEntryOrder()) {
        return false
    }

    val createdInstant = runCatching { Instant.parse(createdAt) }
        .getOrElse { throwable ->
            throw IllegalStateException("resting entry order has invalid createdAt: $orderId", throwable)
        }
    val expiresAt = createdInstant.plus(ttl)

    return expiresAt.isBefore(observedAt)
}
