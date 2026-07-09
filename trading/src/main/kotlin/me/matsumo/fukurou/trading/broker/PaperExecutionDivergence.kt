package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import java.math.BigDecimal
import java.util.UUID

internal fun limitFillDivergenceMemo(
    request: PendingLimitExecutionRequest,
    context: PaperSimulationContext,
    warnLogger: RateLimitedWarnLogger? = null,
): PaperExecutionDivergenceMemo? {
    val orderbook = context.orderbook ?: return null
    val depth = orderbook.limitExecutableDepth(request.side, request.limitPriceJpy)

    if (depth.invalidLevelFound) {
        warnLogger?.warn(
            key = ORDERBOOK_INVALID_LEVEL_LOG_KEY,
            message = "Paper execution ignored invalid orderbook levels.",
        )
    }

    val requestedSize = request.sizeBtc.btcScale()
    val queueFillRatio = context.queueFillRatio.max(BigDecimal.ZERO)
    val hypotheticalFilledSize = depth.availableSizeBtc
        .multiply(queueFillRatio)
        .min(request.sizeBtc)
        .btcScale()
    val hypotheticalRemainingSize = request.sizeBtc
        .subtract(hypotheticalFilledSize)
        .max(BigDecimal.ZERO)
        .btcScale()

    if (hypotheticalRemainingSize <= BigDecimal.ZERO) {
        return null
    }

    return PaperExecutionDivergenceMemo(
        kind = LIMIT_PARTIAL_FAK_DIVERGENCE_KIND,
        side = request.side,
        limitPriceJpy = request.limitPriceJpy.moneyScale(),
        requestedSizeBtc = requestedSize,
        hypotheticalFilledSizeBtc = hypotheticalFilledSize,
        hypotheticalRemainingSizeBtc = hypotheticalRemainingSize,
        boardDepthBtc = depth.availableSizeBtc.btcScale(),
        queueFillRatio = queueFillRatio.ratioScale(),
        bestBidJpy = depth.bestBidJpy?.moneyScale(),
        bestAskJpy = depth.bestAskJpy?.moneyScale(),
    )
}

internal fun PaperExecutionDivergenceMemo.withOrderContext(order: Order): PaperExecutionDivergenceMemo {
    return copy(
        orderId = order.orderId,
        intentId = order.intentId,
        tradeGroupId = order.tradeGroupId,
        clientRequestId = order.clientRequestId,
        symbol = order.symbol,
    )
}

internal fun PaperExecutionDivergenceMemo.withEntryCommandContext(
    command: PlaceOrderCommand,
    orderId: UUID,
    tradeGroupId: UUID,
): PaperExecutionDivergenceMemo {
    return copy(
        orderId = orderId.toString(),
        intentId = command.intentId?.toString(),
        tradeGroupId = tradeGroupId.toString(),
        clientRequestId = command.auditContext.clientRequestId,
        symbol = command.symbol.apiSymbol,
    )
}

/**
 * invalid orderbook level log の key。
 */
private const val ORDERBOOK_INVALID_LEVEL_LOG_KEY = "paper-execution-orderbook-invalid-level"
