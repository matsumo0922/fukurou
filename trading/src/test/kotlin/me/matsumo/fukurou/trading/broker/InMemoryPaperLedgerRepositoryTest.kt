package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.domain.AccountSnapshot
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
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.risk.HardHaltCleanupState
import me.matsumo.fukurou.trading.risk.HardHaltTradingRejectedException
import me.matsumo.fukurou.trading.risk.InMemoryAccountStateBoundary
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskHaltState
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * InMemoryPaperLedgerRepository の read model contract を検証するテスト。
 */
class InMemoryPaperLedgerRepositoryTest {

    @Test
    fun findExecutionActivitiesForStableFeed_returnsSeededEntryDecisionDetails() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(
            positions = listOf(activityPosition()),
            openOrders = listOf(activityStopOrder()),
            executions = listOf(activityExecution()),
            decisionRunIdsByPositionId = mapOf(ACTIVITY_POSITION_ID to ACTIVITY_DECISION_RUN_ID),
            decisionContextsByRunId = mapOf(
                ACTIVITY_DECISION_RUN_ID to ExecutionActivityDecisionContext(
                    decisionId = ACTIVITY_DECISION_ID,
                    decisionRunId = ACTIVITY_DECISION_RUN_ID,
                    action = DecisionAction.ENTER,
                    reasonJa = ACTIVITY_DECISION_REASON_JA,
                ),
            ),
        )

        val activities = repository.findExecutionActivitiesForStableFeed(
            cursor = StableFeedCursor(
                occurredAt = Instant.MAX,
                includesSameTimestamp = false,
                afterId = null,
            ),
            limit = 10,
        ).getOrThrow()
        val entryDecision = requireNotNull(activities.single().entryDecision)

        assertEquals(ACTIVITY_DECISION_ID, entryDecision.decisionId)
        assertEquals(ACTIVITY_DECISION_RUN_ID, entryDecision.decisionRunId)
        assertEquals(DecisionAction.ENTER, entryDecision.action)
        assertEquals(ACTIVITY_DECISION_REASON_JA, entryDecision.reasonJa)
    }

    @Test
    fun sameThesisRiskExitClosesPositionAndCancelsOnlyMatchingPendingBuy() = runBlocking {
        val repository = atomicRiskExitRepository()

        val result = repository.executeRiskExit(sameThesisRiskExitRequest()).getOrThrow()
        val openOrders = repository.getOpenOrders().getOrThrow()

        assertEquals(PaperRiskExitCompletion.SAFE, result.completion)
        assertEquals(listOf(SAME_THESIS_PENDING_ORDER_ID), result.canceledOrderIds)
        assertEquals(listOf(TARGET_POSITION_ID), result.closedPositionIds)
        assertEquals(listOf(UNRELATED_PENDING_ORDER_ID), openOrders.map(Order::orderId))
        assertTrue(repository.getOpenPositions().getOrThrow().isEmpty())
        assertEquals(1, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun sameThesisRiskExitFailsWithoutMutationForMissingNullOrMultipleLinkage() = runBlocking {
        val candidateSets = listOf(
            emptyMap(),
            mapOf(TARGET_INTENT_ID to setOf(null)),
            mapOf(TARGET_INTENT_ID to setOf(TARGET_THESIS_ID, "ths-other")),
        )

        candidateSets.forEach { candidates ->
            val repository = atomicRiskExitRepository(
                thesisCandidates = candidates,
                includePendingOrders = false,
            )
            val beforePositions = repository.getOpenPositions().getOrThrow()
            val beforeOrders = repository.getOpenOrders().getOrThrow()

            val result = repository.executeRiskExit(sameThesisRiskExitRequest())

            assertIs<PaperRiskExitException.AmbiguousLinkage>(result.exceptionOrNull())
            assertEquals(beforePositions, repository.getOpenPositions().getOrThrow())
            assertEquals(beforeOrders, repository.getOpenOrders().getOrThrow())
            assertTrue(repository.getExecutions().getOrThrow().isEmpty())
        }
    }

    @Test
    fun sameThesisRiskExitFailsWithoutMutationForContradictoryGroupOrStaleTarget() = runBlocking {
        val contradictory = atomicRiskExitRepository(
            thesisCandidates = mapOf(
                TARGET_INTENT_ID to setOf(TARGET_THESIS_ID),
                CONTRADICTORY_INTENT_ID to setOf("ths-contradictory"),
            ),
            contradictoryTargetGroup = true,
            includePendingOrders = false,
        )

        assertIs<PaperRiskExitException.AmbiguousLinkage>(
            contradictory.executeRiskExit(sameThesisRiskExitRequest()).exceptionOrNull(),
        )
        assertTrue(contradictory.getExecutions().getOrThrow().isEmpty())

        val stale = atomicRiskExitRepository()
        val staleRequest = sameThesisRiskExitRequest().copy(
            scope = PaperRiskExitScope.SameThesis(UUID.fromString("00000000-0000-0000-0000-000000000099")),
        )

        assertIs<PaperRiskExitException.StaleTarget>(stale.executeRiskExit(staleRequest).exceptionOrNull())
        assertTrue(stale.getExecutions().getOrThrow().isEmpty())
    }

    @Test
    fun hardHaltCleanupKeepsUnknownWithoutTickThenConvergesAndRetriesIdempotently() = runBlocking {
        val boundary = InMemoryAccountStateBoundary()
        val riskRepository = InMemoryRiskStateRepository(fixedClock(), accountStateBoundary = boundary)
        val ledgerRepository = atomicRiskExitRepository(accountStateBoundary = boundary)
        riskRepository.setHardHalt("halt", fixedInstant()).getOrThrow()

        val incomplete = ledgerRepository.executeRiskExit(allOpenRiskExitRequest(null)).getOrThrow()

        assertEquals(PaperRiskExitCompletion.INCOMPLETE, incomplete.completion)
        assertEquals(HardHaltCleanupState.UNKNOWN, riskRepository.current().getOrThrow().hardHaltCleanupState)
        assertEquals(1, ledgerRepository.getOpenPositions().getOrThrow().size)

        val completed = ledgerRepository.executeRiskExit(allOpenRiskExitRequest(riskExitContext())).getOrThrow()
        val executionCount = ledgerRepository.getExecutions().getOrThrow().size
        val retried = ledgerRepository.executeRiskExit(allOpenRiskExitRequest(null)).getOrThrow()

        assertEquals(PaperRiskExitCompletion.SAFE, completed.completion)
        assertEquals(PaperRiskExitCompletion.SAFE, retried.completion)
        assertEquals(HardHaltCleanupState.SAFE, riskRepository.current().getOrThrow().hardHaltCleanupState)
        assertEquals(executionCount, ledgerRepository.getExecutions().getOrThrow().size)
        assertEquals(RiskHaltState.RUNNING, riskRepository.resume("resume", fixedInstant()).getOrThrow().state)
    }

    @Test
    fun flatHardHaltCleanupStoresSafeWithoutTickAndStaleSafeReturnsToUnknown() = runBlocking {
        val flatBoundary = InMemoryAccountStateBoundary()
        val flatRisk = InMemoryRiskStateRepository(fixedClock(), accountStateBoundary = flatBoundary)
        val flatLedger = InMemoryPaperLedgerRepository(accountStateBoundary = flatBoundary)
        flatRisk.setHardHalt("flat halt", fixedInstant()).getOrThrow()

        val flatResult = flatLedger.executeRiskExit(allOpenRiskExitRequest(null)).getOrThrow()

        assertEquals(PaperRiskExitCompletion.SAFE, flatResult.completion)
        assertEquals(HardHaltCleanupState.SAFE, flatRisk.current().getOrThrow().hardHaltCleanupState)

        val staleBoundary = InMemoryAccountStateBoundary()
        val staleRisk = InMemoryRiskStateRepository(fixedClock(), accountStateBoundary = staleBoundary)
        val staleLedger = atomicRiskExitRepository(accountStateBoundary = staleBoundary)
        staleRisk.setHardHalt("stale halt", fixedInstant()).getOrThrow()
        staleBoundary.updateRiskState { state -> state.copy(hardHaltCleanupState = HardHaltCleanupState.SAFE) }

        val staleResult = staleLedger.executeRiskExit(allOpenRiskExitRequest(null)).getOrThrow()

        assertEquals(PaperRiskExitCompletion.INCOMPLETE, staleResult.completion)
        assertEquals(HardHaltCleanupState.UNKNOWN, staleRisk.current().getOrThrow().hardHaltCleanupState)
    }

    @Test
    fun directMarketAndRestingEntriesHonorInMemoryHardHaltMutationBoundary() = runBlocking {
        DirectInMemoryEntryMutationKind.entries.forEach { mutationKind ->
            listOf(true, false).forEach { haltFirst ->
                val boundary = InMemoryAccountStateBoundary()
                val riskRepository = InMemoryRiskStateRepository(fixedClock(), accountStateBoundary = boundary)
                val ledgerRepository = InMemoryPaperLedgerRepository(
                    clock = fixedClock(),
                    accountStateBoundary = boundary,
                )
                if (haltFirst) riskRepository.setHardHalt("direct entry hard halt", fixedInstant()).getOrThrow()

                val result = when (mutationKind) {
                    DirectInMemoryEntryMutationKind.MARKET -> ledgerRepository.fillMarketEntry(marketEntryRequest())
                    DirectInMemoryEntryMutationKind.RESTING -> {
                        ledgerRepository.createRestingEntryOrder(restingEntryRequest())
                    }
                }
                if (!haltFirst) riskRepository.setHardHalt("direct entry hard halt", fixedInstant()).getOrThrow()

                assertEquals(RiskHaltState.HARD_HALT, riskRepository.current().getOrThrow().state)
                if (haltFirst) {
                    assertIs<HardHaltTradingRejectedException>(result.exceptionOrNull())
                    assertTrue(ledgerRepository.getOpenPositions().getOrThrow().isEmpty())
                    assertTrue(ledgerRepository.getOpenOrders().getOrThrow().isEmpty())
                    assertTrue(ledgerRepository.getExecutions().getOrThrow().isEmpty())
                } else {
                    assertTrue(result.isSuccess)
                    when (mutationKind) {
                        DirectInMemoryEntryMutationKind.MARKET -> {
                            assertEquals(1, ledgerRepository.getOpenPositions().getOrThrow().size)
                            assertEquals(1, ledgerRepository.getExecutions().getOrThrow().size)
                        }

                        DirectInMemoryEntryMutationKind.RESTING -> {
                            assertEquals(1, ledgerRepository.getOpenOrders().getOrThrow().size)
                            assertTrue(ledgerRepository.getExecutions().getOrThrow().isEmpty())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun hardHaltPreventsInMemoryTickAndMarketEventEntryFills() = runBlocking {
        val tickBoundary = InMemoryAccountStateBoundary()
        val tickRisk = InMemoryRiskStateRepository(fixedClock(), accountStateBoundary = tickBoundary)
        val tickLedger = InMemoryPaperLedgerRepository(
            clock = fixedClock(),
            accountStateBoundary = tickBoundary,
        )
        tickLedger.createRestingEntryOrder(restingEntryRequest()).getOrThrow()
        tickRisk.setHardHalt("tick entry hard halt", fixedInstant()).getOrThrow()

        val tickResult = tickLedger.reconcile(
            tickSnapshot = entryTickSnapshot(),
            simulator = FixedEntrySimulator,
            simulationContext = entrySimulationContext(),
            reconcileScope = PaperLedgerReconcileScope.FULL_TICK_EXECUTION,
        ).getOrThrow()

        assertTrue(tickResult.filledOrderIds.isEmpty())
        assertTrue(tickLedger.getExecutions().getOrThrow().isEmpty())
        assertEquals(1, tickLedger.getOpenOrders().getOrThrow().size)

        val marketEventBoundary = InMemoryAccountStateBoundary()
        val marketEventRisk = InMemoryRiskStateRepository(fixedClock(), accountStateBoundary = marketEventBoundary)
        val marketEventLedger = InMemoryPaperLedgerRepository(
            clock = fixedClock(),
            accountStateBoundary = marketEventBoundary,
        )
        val sessionId = UUID.randomUUID()
        marketEventLedger.createRestingEntryOrder(
            restingEntryRequest(
                marketEligibility = RestingOrderMarketEligibility(
                    sessionId = sessionId,
                    eligibleAfterSequence = 0,
                    eligibleFrom = fixedInstant(),
                    queueAheadBtc = BigDecimal.ZERO,
                    queueSnapshotAt = fixedInstant(),
                ),
            ),
        ).getOrThrow()
        marketEventRisk.setHardHalt("market event entry hard halt", fixedInstant()).getOrThrow()

        val marketEventResult = marketEventLedger.applyMarketEvent(
            event = PaperMarketTradeEvent(
                symbol = TradingSymbol.BTC,
                side = OrderSide.SELL,
                priceJpy = BigDecimal("9900000"),
                sizeBtc = BigDecimal("0.0100"),
                exchangeAt = fixedInstant().plusSeconds(1),
                receivedAt = fixedInstant().plusSeconds(1),
                connectionSessionId = sessionId,
                sequence = 1,
            ),
            simulator = FixedEntrySimulator,
        ).getOrThrow()

        assertTrue(marketEventResult.filledOrderIds.isEmpty())
        assertTrue(marketEventLedger.getExecutions().getOrThrow().isEmpty())
        assertEquals(1, marketEventLedger.getOpenOrders().getOrThrow().size)
    }
}

/** direct entry mutation の種類。 */
private enum class DirectInMemoryEntryMutationKind {
    MARKET,
    RESTING,
}

private fun marketEntryRequest(): MarketEntryFillRequest {
    val command = directEntryCommand(OrderType.MARKET)

    return MarketEntryFillRequest(
        command = command,
        fill = fixedEntryFill(command.sizeBtc, ExecutionLiquidity.TAKER),
        positionId = UUID.randomUUID(),
        tradeGroupId = UUID.randomUUID(),
        stopOrderId = UUID.randomUUID(),
    )
}

private fun restingEntryRequest(marketEligibility: RestingOrderMarketEligibility? = null): RestingEntryOrderRequest {
    val command = directEntryCommand(OrderType.LIMIT)

    return RestingEntryOrderRequest(
        command = command,
        orderId = command.commandId,
        tradeGroupId = UUID.randomUUID(),
        createdAt = fixedInstant(),
        expiresAt = fixedInstant().plusSeconds(300),
        expirySource = OrderExpirySource.SYSTEM_TTL,
        effectiveTtlSeconds = 300,
        marketEligibility = marketEligibility,
    )
}

private fun directEntryCommand(orderType: OrderType): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = orderType,
        sizeBtc = BigDecimal("0.0010"),
        priceJpy = BigDecimal("10000000").takeIf { orderType == OrderType.LIMIT },
        tradeGroupId = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("11000000"),
        estimatedWinProbability = BigDecimal("0.70"),
        reasonJa = "direct entry hard halt boundary test",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private object FixedEntrySimulator : PaperExecutionSimulator {
    override fun simulateImmediate(
        request: ImmediateExecutionRequest,
        context: PaperSimulationContext,
    ): SimulatedFill = fixedEntryFill(request.sizeBtc, ExecutionLiquidity.TAKER)

    override fun simulatePendingLimit(
        request: PendingLimitExecutionRequest,
        context: PaperSimulationContext,
    ): PaperOrderUpdate {
        return PaperOrderUpdate(
            fill = fixedEntryFill(request.sizeBtc),
            remainingSizeBtc = BigDecimal.ZERO,
            expired = false,
        )
    }
}

private fun fixedEntryFill(
    sizeBtc: BigDecimal,
    liquidity: ExecutionLiquidity = ExecutionLiquidity.MAKER,
): SimulatedFill {
    return SimulatedFill(
        executionId = UUID.randomUUID(),
        priceJpy = BigDecimal("9900000"),
        sizeBtc = sizeBtc,
        feeJpy = BigDecimal.ZERO,
        realizedPnlJpy = BigDecimal.ZERO,
        liquidity = liquidity,
        executedAt = fixedInstant().plusSeconds(1),
    )
}

private fun entryTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = TradingSymbol.BTC.apiSymbol,
        observedAt = fixedInstant().plusSeconds(1),
        lastPrice = "9900000",
        bidPrice = "9900000",
        askPrice = "9900000",
        symbolRules = riskExitContext().rules,
    )
}

private fun entrySimulationContext(): PaperSimulationContext {
    return riskExitContext().copy(
        ticker = riskExitContext().ticker.copy(
            last = "9900000",
            bid = "9900000",
            ask = "9900000",
        ),
    )
}

private const val TARGET_POSITION_ID = "00000000-0000-0000-0000-000000000010"
private const val TARGET_GROUP_ID = "00000000-0000-0000-0000-000000000011"
private const val SAME_THESIS_GROUP_ID = "00000000-0000-0000-0000-000000000012"
private const val UNRELATED_GROUP_ID = "00000000-0000-0000-0000-000000000013"
private const val TARGET_ENTRY_ORDER_ID = "00000000-0000-0000-0000-000000000014"
private const val PROTECTIVE_ORDER_ID = "00000000-0000-0000-0000-000000000015"
private const val SAME_THESIS_PENDING_ORDER_ID = "00000000-0000-0000-0000-000000000016"
private const val UNRELATED_PENDING_ORDER_ID = "00000000-0000-0000-0000-000000000017"
private const val CONTRADICTORY_ENTRY_ORDER_ID = "00000000-0000-0000-0000-000000000018"
private const val TARGET_INTENT_ID = "00000000-0000-0000-0000-000000000020"
private const val SAME_THESIS_INTENT_ID = "00000000-0000-0000-0000-000000000021"
private const val UNRELATED_INTENT_ID = "00000000-0000-0000-0000-000000000022"
private const val CONTRADICTORY_INTENT_ID = "00000000-0000-0000-0000-000000000023"
private const val TARGET_THESIS_ID = "ths-target"

private fun atomicRiskExitRepository(
    thesisCandidates: Map<String, Set<String?>> = mapOf(
        TARGET_INTENT_ID to setOf(TARGET_THESIS_ID),
        SAME_THESIS_INTENT_ID to setOf(TARGET_THESIS_ID),
        UNRELATED_INTENT_ID to setOf("ths-unrelated"),
    ),
    includePendingOrders: Boolean = true,
    contradictoryTargetGroup: Boolean = false,
    accountStateBoundary: InMemoryAccountStateBoundary = InMemoryAccountStateBoundary(),
): InMemoryPaperLedgerRepository {
    val orders = buildList {
        add(riskExitEntryOrder())
        add(riskExitProtectiveOrder())
        if (contradictoryTargetGroup) add(riskExitEntryOrder(CONTRADICTORY_ENTRY_ORDER_ID, CONTRADICTORY_INTENT_ID))
        if (includePendingOrders) {
            add(riskExitPendingOrder(SAME_THESIS_PENDING_ORDER_ID, SAME_THESIS_INTENT_ID, SAME_THESIS_GROUP_ID))
            add(riskExitPendingOrder(UNRELATED_PENDING_ORDER_ID, UNRELATED_INTENT_ID, UNRELATED_GROUP_ID))
        }
    }

    return InMemoryPaperLedgerRepository(
        accountSnapshot = riskExitAccount(),
        positions = listOf(riskExitPosition()),
        openOrders = orders,
        thesisCandidatesByIntentId = thesisCandidates,
        clock = fixedClock(),
        accountStateBoundary = accountStateBoundary,
    )
}

private fun sameThesisRiskExitRequest(): PaperRiskExitRequest {
    return PaperRiskExitRequest(
        scope = PaperRiskExitScope.SameThesis(UUID.fromString(TARGET_POSITION_ID)),
        reasonJa = "test same thesis exit",
        auditContext = PaperTradeAuditContext.EMPTY,
        simulationContext = riskExitContext(),
        simulator = FixedRiskExitSimulator,
    )
}

private fun allOpenRiskExitRequest(context: PaperSimulationContext?): PaperRiskExitRequest {
    return PaperRiskExitRequest(
        scope = PaperRiskExitScope.AllOpenRisk,
        reasonJa = "test hard halt cleanup",
        auditContext = PaperTradeAuditContext.EMPTY,
        simulationContext = context,
        simulator = FixedRiskExitSimulator,
    )
}

private object FixedRiskExitSimulator : PaperExecutionSimulator {
    override fun simulateImmediate(
        request: ImmediateExecutionRequest,
        context: PaperSimulationContext,
    ): SimulatedFill {
        return SimulatedFill(
            executionId = UUID.randomUUID(),
            priceJpy = BigDecimal("10000000"),
            sizeBtc = request.sizeBtc,
            feeJpy = BigDecimal.ZERO,
            realizedPnlJpy = BigDecimal.ZERO,
            liquidity = ExecutionLiquidity.TAKER,
            executedAt = fixedInstant(),
        )
    }

    override fun simulatePendingLimit(
        request: PendingLimitExecutionRequest,
        context: PaperSimulationContext,
    ): PaperOrderUpdate {
        return PaperOrderUpdate(null, request.sizeBtc, expired = false)
    }
}

private fun riskExitContext(): PaperSimulationContext {
    return PaperSimulationContext(
        ticker = Ticker(
            symbol = "BTC",
            last = "10000000",
            bid = "10000000",
            ask = "10000000",
            high = "10000000",
            low = "10000000",
            volume = "1",
            timestamp = fixedInstant().toString(),
        ),
        rules = SymbolRules("BTC", "0.0001", "0.0001", "1", "0", "0"),
    )
}

private fun riskExitAccount(): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = "980000.00000000",
        initialCashJpy = "1000000.00000000",
        btcQuantity = "0.002000000000",
        btcMarkPriceJpy = "10000000.00000000",
        totalEquityJpy = "1000000.00000000",
        equityPeakJpy = "1000000.00000000",
        drawdownRatio = "0",
    )
}

private fun riskExitPosition(): Position {
    return Position(
        positionId = TARGET_POSITION_ID,
        tradeGroupId = TARGET_GROUP_ID,
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = fixedInstant().toString(),
        closedAt = null,
        sizeBtc = "0.002000000000",
        averageEntryPriceJpy = "10000000.00000000",
        currentPriceJpy = "10000000.00000000",
        currentStopLossJpy = "9800000.00000000",
        currentTakeProfitJpy = null,
        unrealizedPnlJpy = "0",
        unrealizedR = "0",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "10000000.00000000",
        lowestPriceSinceEntryJpy = "10000000.00000000",
    )
}

private fun riskExitEntryOrder(orderId: String = TARGET_ENTRY_ORDER_ID, intentId: String = TARGET_INTENT_ID): Order {
    return riskExitPendingOrder(orderId, intentId, TARGET_GROUP_ID).copy(
        positionId = TARGET_POSITION_ID,
        orderType = OrderType.MARKET,
        status = OrderStatus.FILLED,
    )
}

private fun riskExitProtectiveOrder(): Order {
    return riskExitPendingOrder(PROTECTIVE_ORDER_ID, TARGET_INTENT_ID, TARGET_GROUP_ID).copy(
        intentId = null,
        positionId = TARGET_POSITION_ID,
        side = OrderSide.SELL,
        orderType = OrderType.STOP,
        triggerPriceJpy = "9800000.00000000",
    )
}

private fun riskExitPendingOrder(
    orderId: String,
    intentId: String,
    tradeGroupId: String,
): Order {
    return Order(
        orderId = orderId,
        intentId = intentId,
        positionId = null,
        tradeGroupId = tradeGroupId,
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        status = OrderStatus.OPEN,
        sizeBtc = "0.001000000000",
        limitPriceJpy = "9900000.00000000",
        triggerPriceJpy = null,
        protectiveStopPriceJpy = "9800000.00000000",
        takeProfitPriceJpy = null,
        estimatedWinProbability = "0.6",
        reasonJa = "test",
        clientRequestId = null,
        createdAt = fixedInstant().toString(),
        updatedAt = fixedInstant().toString(),
    )
}

private fun fixedInstant(): Instant = Instant.parse("2026-07-17T00:00:00Z")

private fun fixedClock(): Clock = Clock.fixed(fixedInstant(), ZoneOffset.UTC)

private const val ACTIVITY_POSITION_ID = "position-activity-context"
private const val ACTIVITY_TRADE_GROUP_ID = "trade-group-activity-context"
private const val ACTIVITY_STOP_ORDER_ID = "order-activity-stop"
private const val ACTIVITY_EXECUTION_ID = "execution-activity-stop"
private const val ACTIVITY_DECISION_ID = "decision-activity-entry"
private const val ACTIVITY_DECISION_RUN_ID = "run-activity-entry"
private const val ACTIVITY_DECISION_REASON_JA = "entry 判断を Activity 詳細に表示するための理由。"
private const val ACTIVITY_EXECUTED_AT = "2026-07-08T03:46:35Z"

/**
 * Activity context 用の position seed を作る。
 */
private fun activityPosition(): Position {
    return Position(
        positionId = ACTIVITY_POSITION_ID,
        tradeGroupId = ACTIVITY_TRADE_GROUP_ID,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.CLOSED,
        openedAt = "2026-07-08T03:40:00Z",
        closedAt = ACTIVITY_EXECUTED_AT,
        sizeBtc = "0.001",
        averageEntryPriceJpy = "16000000",
        currentPriceJpy = "16100000",
        currentStopLossJpy = "15900000",
        currentTakeProfitJpy = "16200000",
        unrealizedPnlJpy = "0",
        unrealizedR = "0",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "16150000",
        lowestPriceSinceEntryJpy = "15980000",
    )
}

/**
 * Activity context 用の STOP order seed を作る。
 */
private fun activityStopOrder(): Order {
    return Order(
        orderId = ACTIVITY_STOP_ORDER_ID,
        positionId = ACTIVITY_POSITION_ID,
        tradeGroupId = ACTIVITY_TRADE_GROUP_ID,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.SELL,
        orderType = OrderType.STOP,
        status = OrderStatus.FILLED,
        sizeBtc = "0.001",
        limitPriceJpy = null,
        triggerPriceJpy = "15900000",
        protectiveStopPriceJpy = null,
        takeProfitPriceJpy = null,
        reasonJa = "STOP が約定したため。",
        clientRequestId = null,
        createdAt = "2026-07-08T03:41:00Z",
        updatedAt = ACTIVITY_EXECUTED_AT,
    )
}

/**
 * Activity context 用の execution seed を作る。
 */
private fun activityExecution(): Execution {
    return Execution(
        executionId = ACTIVITY_EXECUTION_ID,
        orderId = ACTIVITY_STOP_ORDER_ID,
        positionId = ACTIVITY_POSITION_ID,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.SELL,
        priceJpy = "15900000",
        sizeBtc = "0.001",
        feeJpy = "0",
        realizedPnlJpy = "-1000",
        liquidity = ExecutionLiquidity.TAKER,
        executedAt = ACTIVITY_EXECUTED_AT,
    )
}
