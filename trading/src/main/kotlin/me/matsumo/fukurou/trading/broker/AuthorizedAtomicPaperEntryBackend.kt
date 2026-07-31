package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.OrderType
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * authorized entry の replay と新規作成を同じ backend 境界で確定する内部 capability。
 *
 * A2a では production path へ接続しない。
 */
internal interface AuthorizedAtomicPaperEntryBackend {
    /**
     * 同じ stable request の preflight から terminal までを直列化する。
     *
     * scope 取得に失敗した場合は [AuthorizedAtomicEntryUnavailableException] を送出する。
     */
    suspend fun <T> withStableRequestScope(
        identity: AuthorizedAtomicEntryIdentity,
        block: suspend AuthorizedStableRequestScope.() -> T,
    ): T

    /** attempt-local proposal を参照せず stable identity だけで replay を分類する。 */
    suspend fun strictReplay(identity: AuthorizedAtomicEntryIdentity): Result<AuthorizedPlaceOrderReplay>

    suspend fun commit(request: AuthorizedAtomicPaperEntryRequest): Result<AuthorizedAtomicEntryResult>
}

/** PostgreSQL root などが backend へ注入する stable request scope。 */
internal interface AuthorizedStableRequestExecutionScope {
    suspend fun <T> withScope(
        identity: AuthorizedAtomicEntryIdentity,
        block: suspend AuthorizedStableRequestScope.() -> T,
    ): T
}

/** live stable request scope のownership確認面。 */
internal interface AuthorizedStableRequestScope {
    suspend fun verifyOwnership(): Result<Unit>

    /** A2a が durable `Exact` / `Created` を確定した後に呼ぶ。 */
    fun markBackendResultConfirmed()
}

/** internal capability に渡す stable replay identity と attempt-local proposal。 */
internal data class AuthorizedAtomicPaperEntryRequest(
    val identity: AuthorizedAtomicEntryIdentity,
    val proposal: AuthorizedAtomicEntryCreationProposal,
) {
    /** strict replay の前に検証する、retry 間で不変な identity。 */
    fun requireStableIdentityValid() {
        identity.requireValid()
    }

    /** replay が Missing のときだけ検証する attempt-local proposal。 */
    fun requireCreationProposalValid() {
        proposal.requireMatches(identity)
    }
}

/** Missing replay 時だけ使用する attempt-local paper mutation。 */
internal sealed interface AuthorizedAtomicEntryCreationProposal {
    val command: PlaceOrderCommand
    val consumption: TradeIntentConsumptionRequest

    data class Market(
        val request: IntentConsumingMarketEntryFillRequest,
    ) : AuthorizedAtomicEntryCreationProposal {
        override val command: PlaceOrderCommand = request.entry.command
        override val consumption: TradeIntentConsumptionRequest = request.consumption
    }

    data class Resting(
        val request: IntentConsumingRestingEntryOrderRequest,
    ) : AuthorizedAtomicEntryCreationProposal {
        override val command: PlaceOrderCommand = request.order.command
        override val consumption: TradeIntentConsumptionRequest = request.consumption
    }
}

internal fun AuthorizedAtomicEntryCreationProposal.freshEntityIds(): Set<UUID> {
    return when (this) {
        is AuthorizedAtomicEntryCreationProposal.Market -> setOf(
            request.entry.command.commandId,
            request.entry.positionId,
            request.entry.stopOrderId,
            request.entry.fill.executionId,
        )
        is AuthorizedAtomicEntryCreationProposal.Resting -> setOf(
            request.order.command.commandId,
            request.order.orderId,
        )
    }
}

/** replay と新規作成を明示的に区別する capability の成功結果。 */
internal sealed interface AuthorizedAtomicEntryResult {
    val result: PaperTradeResult

    data class Exact(override val result: PaperTradeResult) : AuthorizedAtomicEntryResult

    data class Created(override val result: PaperTradeResult) : AuthorizedAtomicEntryResult
}

/** replay shape が一意に決まらない。 */
internal class AuthorizedAtomicEntryReplayIndeterminateException : IllegalStateException(
    "authorized atomic entry replay is indeterminate.",
)

/** command が参照する intent が存在しない。 */
internal class AuthorizedAtomicEntryIntentMissingException : IllegalStateException(
    "authorized atomic entry intent is missing.",
)

/** command が参照する intent はすでに消費されている。 */
internal class AuthorizedAtomicEntryIntentConsumedException : IllegalStateException(
    "authorized atomic entry intent is already consumed.",
)

/** account は新規 risk-increasing entry を受理できる flat state ではない。 */
internal class AuthorizedAtomicEntryNotFlatException : IllegalStateException(
    "authorized atomic entry account is not flat.",
)

/** rollback 確認済みの backend failure。 */
internal class AuthorizedAtomicEntryUnavailableException(cause: Throwable) : IllegalStateException(
    "authorized atomic entry backend is unavailable.",
    cause,
)

/** commit の成否を確定できない backend failure。 */
internal class AuthorizedAtomicEntryOutcomeIndeterminateException(cause: Throwable) : IllegalStateException(
    "authorized atomic entry outcome is indeterminate.",
    cause,
)

private fun AuthorizedAtomicEntryCreationProposal.requireMatches(identity: AuthorizedAtomicEntryIdentity) {
    require(command.intentId == identity.intentId)
    require(consumption.intentId == identity.intentId)
    require(command.auditContext.clientRequestId == identity.clientRequestId)
    require(command.symbol == identity.symbol)
    require(command.side == identity.side)
    require(command.orderType == identity.orderType)
    require(command.sizeBtc.compareTo(identity.sizeBtc) == 0)
    require(command.priceJpy.matches(identity.priceJpy))
    require(identity.tradeGroupId == null || command.tradeGroupId == identity.tradeGroupId)
    require(command.protectiveStopPriceJpy.compareTo(identity.protectiveStopPriceJpy) == 0)
    require(command.takeProfitPriceJpy.matches(identity.takeProfitPriceJpy))
    require(command.estimatedWinProbability.compareTo(identity.estimatedWinProbability) == 0)

    when (this) {
        is AuthorizedAtomicEntryCreationProposal.Market -> request.requireValidMarketProposal()
        is AuthorizedAtomicEntryCreationProposal.Resting -> request.requireValidRestingProposal()
    }
}

private fun IntentConsumingMarketEntryFillRequest.requireValidMarketProposal() {
    val entry = entry
    val command = entry.command
    val fill = entry.fill
    val allIds = setOf(
        command.commandId,
        entry.positionId,
        entry.tradeGroupId,
        entry.stopOrderId,
        entry.fill.executionId,
    )

    require(command.orderType == OrderType.MARKET || command.orderType == OrderType.LIMIT)
    require(command.tradeGroupId == entry.tradeGroupId)
    require(allIds.size == 5)
    require(fill.priceJpy > BigDecimal.ZERO)
    require(fill.sizeBtc.compareTo(command.sizeBtc) == 0)
    require(fill.feeJpy >= BigDecimal.ZERO)
    require(fill.realizedPnlJpy.compareTo(BigDecimal.ZERO) == 0)
    entry.positionMarketEligibility?.let { eligibility ->
        require(eligibility.eligibleAfterSequence >= 0)
    }
}

private fun IntentConsumingRestingEntryOrderRequest.requireValidRestingProposal() {
    val order = order
    val command = order.command
    val eligibility = order.marketEligibility

    require(command.tradeGroupId == order.tradeGroupId)
    require(command.orderType == OrderType.LIMIT || command.orderType == OrderType.STOP)
    require(setOf(command.commandId, order.orderId, order.tradeGroupId).size == 3)
    require(!order.expiresAt.isBefore(order.createdAt))
    require(order.effectiveTtlSeconds == Duration.between(order.createdAt, order.expiresAt).seconds)
    eligibility?.requireMatches(command.orderType, order.createdAt)
}

private fun RestingOrderMarketEligibility.requireMatches(orderType: OrderType, createdAt: Instant) {
    require(eligibleAfterSequence >= 0)
    require(!eligibleFrom.isBefore(createdAt))

    if (orderType == OrderType.LIMIT) {
        require(queueAheadBtc != null && queueAheadBtc >= BigDecimal.ZERO)
        require(queueSnapshotAt != null)
        require(!queueSnapshotAt.isAfter(eligibleFrom))
    } else {
        require(queueAheadBtc == null)
        require(queueSnapshotAt == null)
    }
}

private fun java.math.BigDecimal?.matches(expected: java.math.BigDecimal?): Boolean {
    return when {
        this == null || expected == null -> this == expected
        else -> compareTo(expected) == 0
    }
}
