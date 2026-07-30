@file:Suppress("CyclomaticComplexMethod", "ComplexCondition", "LongParameterList")

package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.math.BigDecimal
import java.util.UUID

/** retry 間で不変な authorized command の replay identity。 */
internal data class AuthorizedAtomicEntryIdentity(
    val clientRequestId: String,
    val intentId: UUID,
    val symbol: TradingSymbol,
    val mode: TradingMode,
    val side: OrderSide,
    val orderType: OrderType,
    val sizeBtc: BigDecimal,
    val priceJpy: BigDecimal?,
    val protectiveStopPriceJpy: BigDecimal,
    val takeProfitPriceJpy: BigDecimal?,
    val estimatedWinProbability: BigDecimal,
    val tradeGroupId: UUID?,
) {
    fun requireValid() {
        require(clientRequestId.startsWith(AUTHORIZED_CLIENT_REQUEST_ID_PREFIX))
        require(mode == TradingMode.PAPER)
        require(side == OrderSide.BUY)
        require(sizeBtc > BigDecimal.ZERO)
        require((orderType == OrderType.MARKET) == (priceJpy == null))
        require(priceJpy == null || priceJpy > BigDecimal.ZERO)
        require(protectiveStopPriceJpy > BigDecimal.ZERO)
        require(takeProfitPriceJpy == null || takeProfitPriceJpy > BigDecimal.ZERO)
        require(estimatedWinProbability >= BigDecimal.ZERO && estimatedWinProbability <= BigDecimal.ONE)
    }

    companion object {
        fun from(command: PlaceOrderCommand, mode: TradingMode): AuthorizedAtomicEntryIdentity {
            return AuthorizedAtomicEntryIdentity(
                clientRequestId = requireNotNull(command.auditContext.clientRequestId),
                intentId = requireNotNull(command.intentId),
                symbol = command.symbol,
                mode = mode,
                side = command.side,
                orderType = command.orderType,
                sizeBtc = command.sizeBtc,
                priceJpy = command.priceJpy,
                protectiveStopPriceJpy = command.protectiveStopPriceJpy,
                takeProfitPriceJpy = command.takeProfitPriceJpy,
                estimatedWinProbability = command.estimatedWinProbability,
                tradeGroupId = command.tradeGroupId,
            )
        }
    }
}

/** backend ごとの protective STOP client request ID の永続化規則。 */
internal enum class ProtectiveStopClientRequestIdPolicy {
    SAME_AS_ENTRY,
    NULL,
}

/** backend-neutral persisted replay classifier。 */
internal fun classifyAuthorizedAtomicEntryReplay(
    identity: AuthorizedAtomicEntryIdentity,
    orders: List<Order>,
    positions: List<Position>,
    executions: List<Execution>,
    stopClientRequestIdPolicy: ProtectiveStopClientRequestIdPolicy,
): AuthorizedPlaceOrderReplay {
    identity.requireValid()

    val sameIdRows = orders.filter { it.clientRequestId == identity.clientRequestId }
    val entries = sameIdRows.filter { it.side == OrderSide.BUY }
    if (entries.isEmpty()) return identity.missingOrAmbiguous(orders, positions)
    if (entries.size != 1) return AuthorizedPlaceOrderReplay.Ambiguous

    val entry = entries.single()
    if (!entry.matches(identity)) return AuthorizedPlaceOrderReplay.Ambiguous
    val groupId = entry.tradeGroupId ?: return AuthorizedPlaceOrderReplay.Ambiguous
    if (!sameIdRows.all { it.isExpectedSameIdRow(entry, groupId, stopClientRequestIdPolicy) }) {
        return AuthorizedPlaceOrderReplay.Ambiguous
    }

    return when (entry.status) {
        OrderStatus.FILLED -> classifyFilled(entry, groupId, orders, positions, executions, stopClientRequestIdPolicy)
        OrderStatus.OPEN, OrderStatus.PENDING_CANCEL, OrderStatus.CANCELED, OrderStatus.REJECTED -> {
            classifyResting(entry, orders, positions, executions)
        }
    }
}

private fun AuthorizedAtomicEntryIdentity.missingOrAmbiguous(
    orders: List<Order>,
    positions: List<Position>,
): AuthorizedPlaceOrderReplay {
    val hasSameIdArtifact = orders.any { it.clientRequestId == clientRequestId }
    val stableGroupId = tradeGroupId?.toString()
    val hasStableGroupArtifact = stableGroupId != null &&
        (orders.any { it.tradeGroupId == stableGroupId } || positions.any { it.tradeGroupId == stableGroupId })

    return if (hasSameIdArtifact || hasStableGroupArtifact) AuthorizedPlaceOrderReplay.Ambiguous else AuthorizedPlaceOrderReplay.Missing
}

private fun Order.matches(identity: AuthorizedAtomicEntryIdentity): Boolean {
    val priceMatches = when (identity.orderType) {
        OrderType.MARKET -> limitPriceJpy == null && triggerPriceJpy == null
        OrderType.LIMIT -> limitPriceJpy.matchesDecimal(identity.priceJpy?.moneyScale()) && triggerPriceJpy == null
        OrderType.STOP -> limitPriceJpy == null && triggerPriceJpy.matchesDecimal(identity.priceJpy?.moneyScale())
    }
    val groupMatches = identity.tradeGroupId == null || tradeGroupId == identity.tradeGroupId.toString()

    return intentId == identity.intentId.toString() && symbol == identity.symbol.apiSymbol && mode == identity.mode &&
        side == identity.side && orderType == identity.orderType && sizeBtc.matchesDecimal(identity.sizeBtc.btcScale()) &&
        priceMatches && protectiveStopPriceJpy.matchesDecimal(identity.protectiveStopPriceJpy.moneyScale()) &&
        takeProfitPriceJpy.matchesDecimal(identity.takeProfitPriceJpy?.moneyScale()) &&
        estimatedWinProbability.matchesDecimal(identity.estimatedWinProbability.ratioScale()) && groupMatches
}

private fun Order.isExpectedSameIdRow(
    entry: Order,
    groupId: String,
    stopClientRequestIdPolicy: ProtectiveStopClientRequestIdPolicy,
): Boolean {
    if (this == entry) return true

    return stopClientRequestIdPolicy == ProtectiveStopClientRequestIdPolicy.SAME_AS_ENTRY && entry.positionId != null &&
        positionId == entry.positionId && tradeGroupId == groupId &&
        side == OrderSide.SELL && orderType == OrderType.STOP
}

private fun classifyFilled(
    entry: Order,
    groupId: String,
    orders: List<Order>,
    positions: List<Position>,
    executions: List<Execution>,
    stopClientRequestIdPolicy: ProtectiveStopClientRequestIdPolicy,
): AuthorizedPlaceOrderReplay {
    val positionId = entry.positionId ?: return AuthorizedPlaceOrderReplay.Ambiguous
    val position = positions.singleOrNull { it.positionId == positionId }
        ?: return AuthorizedPlaceOrderReplay.Ambiguous
    val stop = orders.singleOrNull { it.isDirectProtectiveStopCandidate(entry, groupId) }
        ?: return AuthorizedPlaceOrderReplay.Ambiguous
    val execution = executions.singleOrNull { it.orderId == entry.orderId }
        ?: return AuthorizedPlaceOrderReplay.Ambiguous
    val hasCompleteIdentity = position.tradeGroupId == groupId &&
        position.matchesFilledEntry(entry) &&
        stop.orderId != entry.orderId &&
        stop.matchesFilledEntry(entry, stopClientRequestIdPolicy) &&
        execution.positionId == positionId &&
        execution.side == OrderSide.BUY &&
        execution.matchesFilledEntry(entry)
    if (!hasCompleteIdentity) {
        return AuthorizedPlaceOrderReplay.Ambiguous
    }

    return AuthorizedPlaceOrderReplay.Exact(
        PaperTradeResult(
            accepted = true,
            status = entry.status,
            orderIds = listOf(entry.orderId, stop.orderId),
            positionIds = listOf(position.positionId),
            executionIds = listOf(execution.executionId),
            messageJa = "authorized atomic replay を返しました。",
        ),
    )
}

private fun classifyResting(
    entry: Order,
    orders: List<Order>,
    positions: List<Position>,
    executions: List<Execution>,
): AuthorizedPlaceOrderReplay {
    val isRestingEntry = entry.orderType != OrderType.MARKET && entry.positionId == null
    val hasDirectArtifacts = entry.hasDirectArtifacts(orders, positions, executions)
    if (!isRestingEntry || hasDirectArtifacts) return AuthorizedPlaceOrderReplay.Ambiguous

    return AuthorizedPlaceOrderReplay.Exact(
        PaperTradeResult(
            accepted = true,
            status = entry.status,
            orderIds = listOf(entry.orderId),
            positionIds = emptyList(),
            executionIds = emptyList(),
            messageJa = "authorized atomic replay を返しました。",
        ),
    )
}

private fun Order.hasDirectArtifacts(
    orders: List<Order>,
    positions: List<Position>,
    executions: List<Execution>,
): Boolean {
    val positionId = positionId ?: return executions.any { it.orderId == orderId }

    return positions.any { it.positionId == positionId } ||
        orders.any { it.positionId == positionId && it.orderId != orderId } ||
        executions.any { it.orderId == orderId || it.positionId == positionId }
}

private fun Order.isDirectProtectiveStopCandidate(entry: Order, groupId: String): Boolean {
    return positionId == entry.positionId && tradeGroupId == groupId && side == OrderSide.SELL &&
        orderType == OrderType.STOP
}

private fun Position.matchesFilledEntry(entry: Order): Boolean {
    return symbol == entry.symbol && mode == entry.mode && side == PositionSide.LONG
}

private fun Order.matchesFilledEntry(entry: Order, idPolicy: ProtectiveStopClientRequestIdPolicy): Boolean {
    val clientRequestMatches = when (idPolicy) {
        ProtectiveStopClientRequestIdPolicy.SAME_AS_ENTRY -> clientRequestId == entry.clientRequestId
        ProtectiveStopClientRequestIdPolicy.NULL -> clientRequestId == null
    }

    return symbol == entry.symbol && mode == entry.mode && clientRequestMatches
}

private fun Execution.matchesFilledEntry(entry: Order): Boolean {
    return symbol == entry.symbol && mode == entry.mode && sizeBtc == entry.sizeBtc
}

private fun String?.matchesDecimal(expected: BigDecimal?): Boolean {
    return if (expected == null) this == null else this?.toBigDecimalOrNull()?.compareTo(expected) == 0
}
