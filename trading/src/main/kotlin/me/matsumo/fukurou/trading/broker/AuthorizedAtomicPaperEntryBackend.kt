package me.matsumo.fukurou.trading.broker

/**
 * authorized entry の replay と新規作成を同じ backend 境界で確定する内部 capability。
 *
 * A2a では production path へ接続しない。
 */
internal interface AuthorizedAtomicPaperEntryBackend {
    suspend fun commit(request: AuthorizedAtomicPaperEntryRequest): Result<AuthorizedAtomicEntryResult>
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
}

private fun java.math.BigDecimal?.matches(expected: java.math.BigDecimal?): Boolean {
    return when {
        this == null || expected == null -> this == expected
        else -> compareTo(expected) == 0
    }
}
