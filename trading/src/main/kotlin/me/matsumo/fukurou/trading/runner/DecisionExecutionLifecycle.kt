package me.matsumo.fukurou.trading.runner

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
import me.matsumo.fukurou.trading.config.TradingBotConfig
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
            val observedAt = Instant.now(clock)
            val openOrders = tradingRuntime.broker.getOpenOrders().getOrThrow()
            val expiredOrders = openOrders.filter { order ->
                order.isExpiredRestingEntryOrder(
                    observedAt = observedAt,
                    ttl = tradingConfig.decisionProtocol.restingEntryOrderTtl,
                )
            }
            val results = expiredOrders.map { order ->
                cancelOrder(
                    context = context,
                    order = order,
                    clientRequestId = "runner-ttl-cancel-${order.orderId}",
                    reason = "resting_entry_order_ttl_exceeded",
                )
            }

            appendLifecyclePhase(
                context = context,
                phase = "stale_resting_entry_ttl_sweep",
                details = buildJsonObject {
                    put("ttlSeconds", tradingConfig.decisionProtocol.restingEntryOrderTtl.seconds)
                    put("expiredOrderCount", expiredOrders.size)
                    put("canceledOrderIds", results.flatMap { result -> result.orderIds }.joinToString(","))
                },
            ).getOrThrow()

            results
        }
    }

    /**
     * EXIT decision を close_position または cancel_order に写像する。
     */
    suspend fun executeExitDecision(
        context: DecisionRunContext,
        decision: DecisionSubmissionResult,
    ): DecisionLifecycleExecutionResult {
        val openPositions = tradingRuntime.broker.getPositions().getOrThrow()
        val openEntryOrders = openRestingEntryOrders()
        val target = resolveExitTarget(openPositions, openEntryOrders)

        if (target is ExitExecutionTarget.FailClosed) {
            appendFailClosedPhase(context, "exit_execution", target.reason).getOrThrow()
            recordNoTrade(context, target.reason, null).getOrThrow()

            return DecisionLifecycleExecutionResult(
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                tradeResult = null,
            )
        }

        val result = runCatching {
            when (target) {
                is ExitExecutionTarget.ClosePosition -> closePosition(context, target.position, decision)
                is ExitExecutionTarget.CancelEntryOrder -> cancelOrder(
                    context = context,
                    order = target.order,
                    clientRequestId = "runner-exit-cancel-${target.order.orderId}",
                    reason = "exit_decision_cancel_resting_entry_order",
                )
                is ExitExecutionTarget.FailClosed -> error("fail closed target must be handled before execution.")
            }
        }.getOrElse { throwable ->
            appendFailClosedPhase(context, "exit_execution", "exit_execution_failed").getOrThrow()
            recordNoTrade(context, "exit_execution_failed", throwable).getOrThrow()

            return DecisionLifecycleExecutionResult(
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                tradeResult = null,
            )
        }

        appendExecutionPhase(
            context = context,
            phase = "exit_execution",
            operation = target.operationName,
            result = result,
        ).getOrThrow()

        return DecisionLifecycleExecutionResult(
            status = OneShotRunnerStatus.PAPER_EXIT_EXECUTED,
            tradeResult = result,
        )
    }

    /**
     * ADJUST_PROTECTION decision を update_protection に写像する。
     */
    suspend fun executeAdjustProtectionDecision(
        context: DecisionRunContext,
        decision: DecisionSubmissionResult,
    ): DecisionLifecycleExecutionResult {
        val openPositions = tradingRuntime.broker.getPositions().getOrThrow()
        val targetPosition = openPositions.singleOrNull()
        val failClosedReason = adjustProtectionFailClosedReason(decision, openPositions, targetPosition)

        if (failClosedReason != null) {
            appendFailClosedPhase(context, "adjust_protection_execution", failClosedReason).getOrThrow()
            recordNoTrade(context, failClosedReason, null).getOrThrow()

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
                reason = "adjust_protection_execution_failed",
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

    private fun resolveExitTarget(
        openPositions: List<Position>,
        openEntryOrders: List<Order>,
    ): ExitExecutionTarget {
        if (openPositions.size == 1 && openEntryOrders.isEmpty()) {
            return ExitExecutionTarget.ClosePosition(openPositions.single())
        }
        if (openPositions.isEmpty() && openEntryOrders.size == 1) {
            return ExitExecutionTarget.CancelEntryOrder(openEntryOrders.single())
        }
        if (openPositions.isEmpty() && openEntryOrders.isEmpty()) {
            return ExitExecutionTarget.FailClosed("exit_target_not_found")
        }

        return ExitExecutionTarget.FailClosed("exit_target_ambiguous")
    }

    private fun adjustProtectionFailClosedReason(
        decision: DecisionSubmissionResult,
        openPositions: List<Position>,
        targetPosition: Position?,
    ): String? {
        if (openPositions.size != 1) {
            return "adjust_protection_target_ambiguous"
        }
        if (targetPosition?.currentStopLossJpy == null) {
            return "adjust_protection_unprotected_position"
        }
        if (decision.tradePlan?.draft?.targetPriceJpy == null) {
            return "adjust_protection_missing_target_price"
        }

        return null
    }

    private suspend fun closePosition(
        context: DecisionRunContext,
        position: Position,
        decision: DecisionSubmissionResult,
    ): PaperTradeResult {
        val call = guardedTradeCall(
            toolName = "close_position",
            clientRequestId = "runner-exit-close-${position.positionId}",
            context = context,
            payload = buildJsonObject {
                put("decisionId", decision.decision.decisionId.toString())
                put("positionId", position.positionId)
                put("source", "one_shot_runner")
                put("reason", "exit_decision_close_position")
            }.toString(),
        )
        val command = ClosePositionCommand(
            commandId = idGenerator(),
            positionId = UUID.fromString(position.positionId),
            closeAll = false,
            reasonJa = "EXIT decision による runner deterministic close。",
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        )

        return tradingRuntime.toolCallGuard.runTradeTool(call) {
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
    ): Result<Unit> {
        return appendLifecyclePhase(
            context = context,
            phase = phase,
            details = buildJsonObject {
                put("operation", operation)
                put("accepted", result.accepted)
                put("status", result.status.name)
                put("orderIds", result.orderIds.joinToString(","))
                put("positionIds", result.positionIds.joinToString(","))
            },
        )
    }

    private suspend fun appendFailClosedPhase(
        context: DecisionRunContext,
        phase: String,
        reason: String,
    ): Result<Unit> {
        return appendLifecyclePhase(
            context = context,
            phase = phase,
            details = buildJsonObject {
                put("accepted", false)
                put("reason", reason)
            },
        )
    }

    private suspend fun appendLifecyclePhase(
        context: DecisionRunContext,
        phase: String,
        details: JsonObject,
    ): Result<Unit> {
        val payload = buildJsonObject {
            put("phase", phase)
            put("durationMillis", 0)
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
                occurredAt = Instant.now(clock),
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
     * @param reason no-trade audit に残す理由
     */
    data class FailClosed(
        val reason: String,
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
