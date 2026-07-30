package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderExpirySource
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AuthorizedAtomicEntryReplayTest {
    @Test
    fun complete_filled_market_is_exact_for_inmemory_and_postgresql_stop_identity() {
        for (requiresSameStopId in listOf(true, false)) {
            val entry = entry()
            val stop = stop(entry, clientRequestId = if (requiresSameStopId) entry.clientRequestId else null)
            val replay = classify(
                entry = entry,
                orders = listOf(entry, stop),
                positions = listOf(position()),
                executions = listOf(execution()),
                stopClientRequestIdPolicy = if (requiresSameStopId) {
                    ProtectiveStopClientRequestIdPolicy.SAME_AS_ENTRY
                } else {
                    ProtectiveStopClientRequestIdPolicy.NULL
                },
            )

            val exact = assertIs<AuthorizedPlaceOrderReplay.Exact>(replay)
            assertEquals(listOf("entry", "stop"), exact.result.orderIds)
            assertEquals(listOf("position"), exact.result.positionIds)
            assertEquals(listOf("execution"), exact.result.executionIds)
        }
    }

    @Test
    fun resting_shapes_and_filled_resting_shape_are_exact() {
        val restingStatuses = listOf(
            OrderStatus.OPEN,
            OrderStatus.PENDING_CANCEL,
            OrderStatus.CANCELED,
            OrderStatus.REJECTED,
        )
        for (status in restingStatuses) {
            val entry = entry(type = OrderType.LIMIT, status = status)
            assertIs<AuthorizedPlaceOrderReplay.Exact>(classify(entry, orders = listOf(entry)))
        }

        val filledLimit = entry(type = OrderType.LIMIT)
        assertIs<AuthorizedPlaceOrderReplay.Exact>(
            classify(filledLimit, orders = listOf(filledLimit, stop(filledLimit)), positions = listOf(position()), executions = listOf(execution())),
        )

        val unrelatedSameGroup = entry().copy(orderId = "unrelated", clientRequestId = "other-request", side = OrderSide.SELL)
        assertIs<AuthorizedPlaceOrderReplay.Exact>(
            classify(entry(), orders = listOf(entry(), stop(entry()), unrelatedSameGroup), positions = listOf(position()), executions = listOf(execution())),
        )
    }

    @Test
    fun incomplete_duplicate_and_mismatched_bundles_are_ambiguous() {
        val entry = entry()
        val completeOrders = listOf(entry, stop(entry))
        val cases = listOf(
            completeOrders to emptyList<Position>() to listOf(execution()),
            completeOrders to listOf(position()) to emptyList<Execution>(),
            listOf(entry) to listOf(position()) to listOf(execution()),
            completeOrders + stop(entry, "stop-2") to listOf(position()) to listOf(execution()),
            completeOrders + stop(entry, "stop-2", "other-request") to listOf(position()) to listOf(execution()),
            listOf(entry, stop(entry, entry.orderId)) to listOf(position()) to listOf(execution()),
            completeOrders + entry.copy(side = OrderSide.SELL, orderType = OrderType.MARKET) to
                listOf(position()) to listOf(execution()),
            completeOrders to listOf(position()) to listOf(execution(), execution("execution-2")),
            completeOrders to listOf(position()) to listOf(execution(), execution("execution-2").copy(side = OrderSide.SELL)),
            completeOrders + entry.copy(orderId = "entry-2") to listOf(position()) to listOf(execution()),
            completeOrders to listOf(position().copy(tradeGroupId = "other")) to listOf(execution()),
            completeOrders to listOf(position(), position().copy(tradeGroupId = "other")) to listOf(execution()),
            completeOrders to listOf(position()) to listOf(execution().copy(positionId = "other")),
        )

        for ((orderAndPosition, executions) in cases) {
            val (orders, positions) = orderAndPosition
            assertEquals(AuthorizedPlaceOrderReplay.Ambiguous, classify(entry, orders, positions, executions))
        }
    }

    @Test
    fun corrupt_resting_and_postgresql_non_null_stop_are_ambiguous() {
        val resting = entry(type = OrderType.LIMIT, status = OrderStatus.OPEN)
        assertEquals(
            AuthorizedPlaceOrderReplay.Ambiguous,
            classify(resting, orders = listOf(resting), executions = listOf(execution())),
        )
        assertEquals(
            AuthorizedPlaceOrderReplay.Ambiguous,
            classify(resting.copy(positionId = "position"), orders = listOf(resting.copy(positionId = "position")), positions = listOf(position())),
        )

        val filled = entry()
        assertEquals(
            AuthorizedPlaceOrderReplay.Ambiguous,
            classify(
                entry = filled,
                orders = listOf(filled, stop(filled)),
                positions = listOf(position()),
                executions = listOf(execution()),
                stopClientRequestIdPolicy = ProtectiveStopClientRequestIdPolicy.NULL,
            ),
        )
    }

    @Test
    fun later_protection_and_position_mutations_do_not_break_exact_replay() {
        val entry = entry()
        val mutatedStop = stop(entry).copy(sizeBtc = "0.005", triggerPriceJpy = "110")
        val mutatedPosition = position().copy(sizeBtc = "0.005", currentStopLossJpy = "110", currentTakeProfitJpy = "120")
        val unrelated = entry.copy(orderId = "later-request", clientRequestId = "other-request", side = OrderSide.SELL)

        assertIs<AuthorizedPlaceOrderReplay.Exact>(
            classify(
                entry = entry,
                orders = listOf(entry, mutatedStop, unrelated),
                positions = listOf(mutatedPosition),
                executions = listOf(execution()),
            ),
        )
    }

    @Test
    fun stable_business_identity_and_orphan_artifacts_fail_closed() {
        val entry = entry()
        val identityMismatches = listOf(
            entry.copy(intentId = UUID.randomUUID().toString()),
            entry.copy(symbol = "ETH"),
            entry.copy(mode = TradingMode.LIVE),
            entry.copy(side = OrderSide.SELL),
            entry.copy(orderType = OrderType.LIMIT, limitPriceJpy = "101"),
            entry.copy(sizeBtc = "0.02"),
            entry.copy(protectiveStopPriceJpy = "99"),
            entry.copy(takeProfitPriceJpy = "102"),
            entry.copy(estimatedWinProbability = "0.6"),
            entry.copy(tradeGroupId = "other"),
        )
        for (mismatch in identityMismatches) {
            assertEquals(AuthorizedPlaceOrderReplay.Ambiguous, classify(entry, listOf(mismatch)))
        }

        assertEquals(AuthorizedPlaceOrderReplay.Missing, classify(entry, emptyList()))
        assertEquals(AuthorizedPlaceOrderReplay.Ambiguous, classify(entry, listOf(stop(entry)), positions = listOf(position())))
        assertEquals(AuthorizedPlaceOrderReplay.Ambiguous, classify(entry, listOf(entry.copy(side = OrderSide.SELL))))
    }

    @Test
    fun invalid_stable_identity_is_rejected_before_replay() {
        val entry = entry()
        val identity = identity(entry)
        val invalidIdentities = listOf(
            identity.copy(clientRequestId = "public-request"),
            identity.copy(mode = TradingMode.LIVE),
            identity.copy(side = OrderSide.SELL),
            identity.copy(sizeBtc = BigDecimal.ZERO),
            identity.copy(priceJpy = BigDecimal.ONE),
            identity.copy(protectiveStopPriceJpy = BigDecimal.ZERO),
            identity.copy(takeProfitPriceJpy = BigDecimal.ZERO),
            identity.copy(estimatedWinProbability = BigDecimal("1.1")),
        )

        for (invalidIdentity in invalidIdentities) {
            assertFailsWith<IllegalArgumentException> {
                classifyAuthorizedAtomicEntryReplay(
                    identity = invalidIdentity,
                    orders = emptyList(),
                    positions = emptyList(),
                    executions = emptyList(),
                    stopClientRequestIdPolicy = ProtectiveStopClientRequestIdPolicy.SAME_AS_ENTRY,
                )
            }
        }
    }

    @Test
    fun atomic_creation_proposal_is_validated_against_stable_identity() {
        val identity = identity(entry())
        val command = PlaceOrderCommand(
            commandId = UUID.randomUUID(),
            intentId = identity.intentId,
            symbol = identity.symbol,
            side = identity.side,
            orderType = identity.orderType,
            sizeBtc = identity.sizeBtc,
            priceJpy = identity.priceJpy,
            tradeGroupId = identity.tradeGroupId,
            protectiveStopPriceJpy = identity.protectiveStopPriceJpy,
            takeProfitPriceJpy = identity.takeProfitPriceJpy,
            estimatedWinProbability = identity.estimatedWinProbability,
            reasonJa = "test",
            auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = identity.clientRequestId),
        )
        val request = atomicMarketRequest(identity, command)

        request.requireStableIdentityValid()
        request.requireCreationProposalValid()

        val changedIntent = atomicMarketRequest(identity, command.copy(intentId = UUID.randomUUID()))
        changedIntent.requireStableIdentityValid()
        assertFailsWith<IllegalArgumentException> { changedIntent.requireCreationProposalValid() }
    }

    @Test
    fun atomic_creation_proposal_allows_resolved_group_for_null_stable_group() {
        val identity = identity(entry()).copy(tradeGroupId = null)
        val command = PlaceOrderCommand(
            commandId = UUID.randomUUID(),
            intentId = identity.intentId,
            symbol = identity.symbol,
            side = identity.side,
            orderType = identity.orderType,
            sizeBtc = identity.sizeBtc,
            priceJpy = identity.priceJpy,
            tradeGroupId = UUID.randomUUID(),
            protectiveStopPriceJpy = identity.protectiveStopPriceJpy,
            takeProfitPriceJpy = identity.takeProfitPriceJpy,
            estimatedWinProbability = identity.estimatedWinProbability,
            reasonJa = "test",
            auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = identity.clientRequestId),
        )

        atomicMarketRequest(identity, command, requireNotNull(command.tradeGroupId)).run {
            requireStableIdentityValid()
            requireCreationProposalValid()
        }
    }

    @Test
    fun atomic_resting_creation_proposal_validates_against_stable_identity() {
        val identity = identity(entry(OrderType.LIMIT, OrderStatus.OPEN))
        val command = PlaceOrderCommand(
            commandId = UUID.randomUUID(),
            intentId = identity.intentId,
            symbol = identity.symbol,
            side = identity.side,
            orderType = identity.orderType,
            sizeBtc = identity.sizeBtc,
            priceJpy = identity.priceJpy,
            tradeGroupId = identity.tradeGroupId,
            protectiveStopPriceJpy = identity.protectiveStopPriceJpy,
            takeProfitPriceJpy = identity.takeProfitPriceJpy,
            estimatedWinProbability = identity.estimatedWinProbability,
            reasonJa = "test",
            auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = identity.clientRequestId),
        )
        val request = AuthorizedAtomicPaperEntryRequest(
            identity = identity,
            proposal = AuthorizedAtomicEntryCreationProposal.Resting(
                IntentConsumingRestingEntryOrderRequest(
                    order = RestingEntryOrderRequest(
                        command = command,
                        orderId = UUID.randomUUID(),
                        tradeGroupId = requireNotNull(identity.tradeGroupId),
                        createdAt = java.time.Instant.parse("2026-07-31T00:00:00Z"),
                        expiresAt = java.time.Instant.parse("2026-07-31T00:05:00Z"),
                        expirySource = OrderExpirySource.SYSTEM_TTL,
                        effectiveTtlSeconds = 300,
                    ),
                    consumption = TradeIntentConsumptionRequest(
                        identity.intentId,
                        java.time.Instant.parse("2026-07-31T00:00:00Z"),
                    ),
                ),
            ),
        )

        request.requireStableIdentityValid()
        request.requireCreationProposalValid()
    }

    private fun classify(
        entry: Order,
        orders: List<Order>,
        positions: List<Position> = emptyList(),
        executions: List<Execution> = emptyList(),
        stopClientRequestIdPolicy: ProtectiveStopClientRequestIdPolicy = ProtectiveStopClientRequestIdPolicy.SAME_AS_ENTRY,
    ): AuthorizedPlaceOrderReplay {
        return classifyAuthorizedAtomicEntryReplay(
            identity = identity(entry),
            orders = orders,
            positions = positions,
            executions = executions,
            stopClientRequestIdPolicy = stopClientRequestIdPolicy,
        )
    }

    private fun identity(entry: Order): AuthorizedAtomicEntryIdentity = AuthorizedAtomicEntryIdentity(
        clientRequestId = requireNotNull(entry.clientRequestId),
        intentId = UUID.fromString(requireNotNull(entry.intentId)),
        symbol = TradingSymbol.BTC,
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        orderType = entry.orderType,
        sizeBtc = BigDecimal("0.01"),
        priceJpy = when (entry.orderType) {
            OrderType.MARKET -> null
            else -> BigDecimal("101")
        },
        protectiveStopPriceJpy = BigDecimal("100"),
        takeProfitPriceJpy = BigDecimal("102"),
        estimatedWinProbability = BigDecimal("0.5"),
        tradeGroupId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
    )

    private fun atomicMarketRequest(
        identity: AuthorizedAtomicEntryIdentity,
        command: PlaceOrderCommand,
        resolvedTradeGroupId: UUID = requireNotNull(identity.tradeGroupId),
    ): AuthorizedAtomicPaperEntryRequest {
        return AuthorizedAtomicPaperEntryRequest(
            identity = identity,
            proposal = AuthorizedAtomicEntryCreationProposal.Market(
                IntentConsumingMarketEntryFillRequest(
                    entry = MarketEntryFillRequest(
                        command = command,
                        fill = SimulatedFill(
                            executionId = UUID.randomUUID(),
                            priceJpy = BigDecimal("101"),
                            sizeBtc = identity.sizeBtc,
                            feeJpy = BigDecimal.ZERO,
                            realizedPnlJpy = BigDecimal.ZERO,
                            liquidity = ExecutionLiquidity.TAKER,
                            executedAt = java.time.Instant.parse("2026-07-31T00:00:00Z"),
                        ),
                        positionId = UUID.randomUUID(),
                        tradeGroupId = resolvedTradeGroupId,
                        stopOrderId = UUID.randomUUID(),
                    ),
                    consumption = TradeIntentConsumptionRequest(identity.intentId, java.time.Instant.parse("2026-07-31T00:00:00Z")),
                ),
            ),
        )
    }

    private fun entry(type: OrderType = OrderType.MARKET, status: OrderStatus = OrderStatus.FILLED): Order = Order(
        orderId = "entry",
        intentId = "00000000-0000-0000-0000-000000000002",
        positionId = if (status == OrderStatus.FILLED) "position" else null,
        tradeGroupId = "00000000-0000-0000-0000-000000000001",
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        orderType = type,
        status = status,
        sizeBtc = "0.01000000",
        limitPriceJpy = if (type == OrderType.LIMIT) "101" else null,
        triggerPriceJpy = if (type == OrderType.STOP) "101" else null,
        protectiveStopPriceJpy = "100",
        takeProfitPriceJpy = "102",
        estimatedWinProbability = "0.5000000000",
        reasonJa = "test",
        clientRequestId = "runner-place-v2-test",
        createdAt = "2026-07-31T00:00:00Z",
        updatedAt = "2026-07-31T00:00:00Z",
    )

    private fun stop(
        entry: Order,
        orderId: String = "stop",
        clientRequestId: String? = entry.clientRequestId,
    ): Order {
        return entry.copy(
            orderId = orderId,
            intentId = null,
            side = OrderSide.SELL,
            orderType = OrderType.STOP,
            status = OrderStatus.OPEN,
            limitPriceJpy = null,
            triggerPriceJpy = "100",
            protectiveStopPriceJpy = null,
            takeProfitPriceJpy = null,
            estimatedWinProbability = null,
            clientRequestId = clientRequestId,
        )
    }

    private fun position(): Position = Position(
        "position", "00000000-0000-0000-0000-000000000001", "BTC", TradingMode.PAPER, PositionSide.LONG,
        PositionStatus.OPEN, "2026-07-31T00:00:00Z", null, "0.01000000", "101", "101", "100", "102", "0", "0", 0, "101", "101",
    )

    private fun execution(executionId: String = "execution"): Execution = Execution(
        executionId, "entry", "position", "BTC", TradingMode.PAPER, OrderSide.BUY, "101", "0.01000000", "0", "0",
        ExecutionLiquidity.TAKER, "2026-07-31T00:00:00Z",
    )
}
