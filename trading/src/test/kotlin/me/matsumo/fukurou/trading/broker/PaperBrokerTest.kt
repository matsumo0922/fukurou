package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderExpirySource
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
import me.matsumo.fukurou.trading.domain.PaperOrderCancelReason
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.safety.DataQualityCapConfig
import me.matsumo.fukurou.trading.safety.InMemorySafetyViolationRepository
import me.matsumo.fukurou.trading.safety.SafetyFloor
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
import me.matsumo.fukurou.trading.safety.SafetyFloorContext
import me.matsumo.fukurou.trading.safety.SafetyFloorRule
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PaperBroker の読み取り集計 contract を検証するテスト。
 */
class PaperBrokerTest {

    @Test
    fun get_account_status_returns_initial_empty_account() = runBlocking {
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            clock = fixedClock(),
        )

        val accountStatus = broker.getAccountStatus().getOrThrow()

        assertEquals(TradingMode.PAPER, accountStatus.mode)
        assertEquals("RUNNING", accountStatus.riskState)
        assertEquals("100000.00000000", accountStatus.currentEquityJpy)
        assertEquals("0", accountStatus.todayRealizedPnlJpy)
        assertFalse(accountStatus.hardHalt)
        assertEquals(0, accountStatus.protectionStatus.protectedPositionCount)
        assertEquals(0, accountStatus.protectionStatus.unprotectedPositionCount)
        assertEquals(0, accountStatus.protectionStatus.orphanStopCount)
    }

    @Test
    fun get_account_status_returns_soft_halt_state_name() = runBlocking {
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = riskStateRepository,
            clock = fixedClock(),
        )

        riskStateRepository.setSoftHalt("operator pause", fixedInstant()).getOrThrow()

        val accountStatus = broker.getAccountStatus().getOrThrow()

        assertEquals(RiskHaltState.SOFT_HALT.name, accountStatus.riskState)
        assertFalse(accountStatus.hardHalt)
    }

    @Test
    fun get_account_status_counts_protection_gaps() = runBlocking {
        val ledgerRepository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshot(),
            positions = listOf(protectedPosition(), unprotectedPosition()),
            openOrders = listOf(
                linkedStopOrder(),
                orphanStopOrder(),
                orphanTakeProfitOrder(),
                pendingCancelOrder(),
            ),
        )
        val broker = PaperBroker(
            ledgerRepository = ledgerRepository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            clock = fixedClock(),
        )

        val protectionStatus = broker.getAccountStatus().getOrThrow().protectionStatus

        assertEquals(1, protectionStatus.protectedPositionCount)
        assertEquals(1, protectionStatus.unprotectedPositionCount)
        assertEquals(1, protectionStatus.orphanStopCount)
        assertEquals(1, protectionStatus.orphanTakeProfitCount)
        assertEquals(1, protectionStatus.pendingCancelCount)
    }

    @Test
    fun get_account_status_requires_active_stop_order_to_count_position_as_protected() = runBlocking {
        val ledgerRepository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshot(),
            positions = listOf(protectedPosition()),
            openOrders = emptyList(),
        )
        val broker = PaperBroker(
            ledgerRepository = ledgerRepository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            clock = fixedClock(),
        )

        val protectionStatus = broker.getAccountStatus().getOrThrow().protectionStatus

        assertEquals(0, protectionStatus.protectedPositionCount)
        assertEquals(1, protectionStatus.unprotectedPositionCount)
    }

    @Test
    fun get_account_status_includes_reconciler_freshness() = runBlocking {
        val reconcilerStatus = MutableReconcilerStatus()
        reconcilerStatus.markReconciled(
            reconciledAt = fixedInstant(),
            startupFullReconcileCompleted = true,
            lastMarketDataAt = fixedInstant(),
        )
        val broker = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            reconcilerStatusProvider = reconcilerStatus,
            clock = fixedClock(),
        )

        val protectionStatus = broker.getAccountStatus().getOrThrow().protectionStatus

        assertEquals(fixedInstant().toString(), protectionStatus.lastReconciledAt)
        assertEquals(fixedInstant().toString(), protectionStatus.lastMarketDataAt)
    }

    @Test
    fun place_order_market_entry_creates_position_stop_execution_and_updates_account() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()
        val balance = broker.getBalance().getOrThrow()
        val positions = broker.getPositions().getOrThrow()
        val orders = broker.getOpenOrders().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertTrue(result.accepted)
        assertEquals(OrderStatus.FILLED, result.status)
        assertEquals("0.005000000000", balance.btcQuantity)
        assertEquals(1, positions.size)
        assertEquals("9700000.00000000", positions.single().currentStopLossJpy)
        assertEquals("10500000.00000000", positions.single().currentTakeProfitJpy)
        assertEquals("10005000.00000000", positions.single().highestPriceSinceEntryJpy)
        assertEquals("10005000.00000000", positions.single().lowestPriceSinceEntryJpy)
        assertEquals(listOf(OrderType.STOP), orders.map { order -> order.orderType })
        assertEquals(1, executions.size)
    }

    @Test
    fun place_order_market_entry_uses_orderbook_walk_and_atr_slippage() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = OrderbookAtrMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()
        val positions = broker.getPositions().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertTrue(result.accepted)
        assertEquals("10017406.00000000", positions.single().averageEntryPriceJpy)
        assertEquals("10017406.00000000", executions.single().priceJpy)
    }

    @Test
    fun place_order_market_entry_with_zero_volatility_multiplier_uses_fixed_bps_after_orderbook_walk() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = OrderbookAtrMarketDataSource,
            paperExecutionConfig = PaperExecutionConfig(
                volatilitySlippageMultiplier = BigDecimal.ZERO,
            ),
            clock = fixedClock(),
        )

        val result = broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertTrue(result.accepted)
        assertEquals("10017006.00000000", executions.single().priceJpy)
    }

    @Test
    fun place_order_market_entry_falls_back_when_orderbook_fetch_fails() = runBlocking {
        val logger = Logger.getLogger(PaperBroker::class.java.name)
        val handler = CapturingLogHandler()
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        logger.addHandler(handler)
        try {
            val result = broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()
            val executions = repository.getExecutions().getOrThrow()

            assertTrue(result.accepted)
            assertEquals("10005000.00000000", executions.single().priceJpy)
            assertTrue(
                handler.messages.any { message ->
                    message.contains("could not fetch orderbook for paper execution")
                },
            )
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun place_order_crossing_limit_entry_fills_immediately_as_taker() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = MutableOrderbookMarketDataSource(
                orderbook = orderbookWithAsk("10000000"),
            ),
            clock = fixedClock(),
        )
        val command = restingLimitCommand(priceJpy = BigDecimal("10000000"))

        val result = broker.placeOrder(approvedCommand(decisionRepository, command)).getOrThrow()
        val openOrders = broker.getOpenOrders().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertEquals(OrderStatus.FILLED, result.status)
        assertEquals(listOf(OrderType.STOP), openOrders.map { order -> order.orderType })
        assertEquals(ExecutionLiquidity.TAKER, executions.single().liquidity)
        assertEquals("10000000.00000000", executions.single().priceJpy)
        assertEquals("25.00000000", executions.single().feeJpy)
    }

    @Test
    fun place_order_crossing_limit_records_fak_divergence_memo_when_depth_is_partial() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = MutableOrderbookMarketDataSource(
                orderbook = orderbookWithAsk(price = "10000000", size = "0.0020"),
            ),
            clock = fixedClock(),
        )
        val command = restingLimitCommand(priceJpy = BigDecimal("10000000"))
        val approved = approvedCommand(decisionRepository, command)

        val result = broker.placeOrder(approved).getOrThrow()
        val execution = repository.getExecutions().getOrThrow().single()
        val memo = result.divergenceMemos.single()

        assertEquals("0.005000000000", execution.sizeBtc)
        assertEquals(LIMIT_PARTIAL_FAK_DIVERGENCE_KIND, memo.kind)
        assertEquals(approved.commandId.toString(), memo.orderId)
        assertEquals(approved.intentId.toString(), memo.intentId)
        assertEquals(TradingSymbol.BTC.apiSymbol, memo.symbol)
        assertEquals("0.005000000000", memo.requestedSizeBtc.toPlainString())
        assertEquals("0.002000000000", memo.hypotheticalFilledSizeBtc.toPlainString())
        assertEquals("0.003000000000", memo.hypotheticalRemainingSizeBtc.toPlainString())
        assertEquals("0.002000000000", memo.boardDepthBtc.toPlainString())
    }

    @Test
    fun place_order_non_crossing_limit_entry_remains_resting() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = MutableOrderbookMarketDataSource(
                orderbook = orderbookWithAsk("10000000"),
            ),
            clock = fixedClock(),
        )

        val result = broker.placeOrder(approvedCommand(decisionRepository, restingLimitCommand())).getOrThrow()

        assertEquals(OrderStatus.OPEN, result.status)
        assertEquals(1, broker.getOpenOrders().getOrThrow().size)
        assertEquals(0, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun restingEntryPersistsEarlierLlmTimeStopWithoutFollowingLaterConfigChanges() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val marketDataSource = MutableOrderbookMarketDataSource(orderbook = orderbookWithAsk("10000000"))
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            restingEntryOrderTtl = Duration.ofSeconds(60),
            marketDataSource = marketDataSource,
            clock = fixedClock(),
        )
        val command = restingLimitCommand().copy(timeStopAt = fixedInstant().plusSeconds(30))

        broker.placeOrder(approvedCommand(decisionRepository, command)).getOrThrow()
        val original = broker.getOpenOrders().getOrThrow().single()
        PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            restingEntryOrderTtl = Duration.ofSeconds(10),
            marketDataSource = marketDataSource,
            clock = fixedClock(),
        )
        val unchanged = broker.getOpenOrders().getOrThrow().single()

        assertEquals(fixedInstant().plusSeconds(30).toString(), original.expiresAt)
        assertEquals(OrderExpirySource.LLM_TIME_STOP, original.expirySource)
        assertEquals(30, original.effectiveTtlSeconds)
        assertEquals(original.expiresAt, unchanged.expiresAt)
    }

    @Test
    fun restingEntryKeepsPastLlmTimeStopAndClampsEffectiveTtlToZero() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            restingEntryOrderTtl = Duration.ofSeconds(60),
            marketDataSource = MutableOrderbookMarketDataSource(orderbook = orderbookWithAsk("10000000")),
            clock = fixedClock(),
        )
        val pastTimeStop = fixedInstant().minusSeconds(5)

        broker.placeOrder(
            approvedCommand(decisionRepository, restingLimitCommand().copy(timeStopAt = pastTimeStop)),
        ).getOrThrow()
        val order = broker.getOpenOrders().getOrThrow().single()

        assertEquals(pastTimeStop.toString(), order.expiresAt)
        assertEquals(OrderExpirySource.LLM_TIME_STOP, order.expirySource)
        assertEquals(0, order.effectiveTtlSeconds)
    }

    @Test
    fun reconcileExpiresAtBoundaryBeforePotentialFillAndRemainsTerminalAfterward() = runBlocking {
        val ledgerClock = MutablePaperTestClock(fixedInstant())
        val repository = InMemoryPaperLedgerRepository(clock = ledgerClock)
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val marketDataSource = MutableOrderbookMarketDataSource(orderbook = orderbookWithAsk("10000000"))
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            restingEntryOrderTtl = Duration.ofSeconds(60),
            marketDataSource = marketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, restingLimitCommand())).getOrThrow()
        val openOrder = broker.getOpenOrders().getOrThrow().single()
        val tradeGroupId = UUID.fromString(requireNotNull(openOrder.tradeGroupId))

        broker.reconcile(watermarkTickSnapshot("10000000").copy(observedAt = fixedInstant().plusSeconds(59))).getOrThrow()
        assertEquals(OrderStatus.OPEN, broker.getOpenOrders().getOrThrow().single().status)

        marketDataSource.orderbook = orderbookWithAsk("9800000")
        ledgerClock.currentInstant = fixedInstant().plusSeconds(60)
        broker.reconcile(watermarkTickSnapshot("9800000").copy(observedAt = fixedInstant().plusSeconds(600))).getOrThrow()
        broker.reconcile(watermarkTickSnapshot("9800000").copy(observedAt = fixedInstant().plusSeconds(61))).getOrThrow()
        val expiredOrder = repository.findOrdersByTradeGroupId(tradeGroupId).getOrThrow().single()

        assertEquals(OrderStatus.CANCELED, expiredOrder.status)
        assertEquals(fixedInstant().plusSeconds(60).toString(), expiredOrder.expiredAt)
        assertEquals(fixedInstant().plusSeconds(60).toString(), expiredOrder.canceledAt)
        assertEquals(PaperOrderCancelReason.TTL_EXPIRY, expiredOrder.cancelReason)
        assertEquals(0, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun place_order_capsProbabilityFromStaleTickerTimestamp() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            safetyFloor = SafetyFloor(
                config = SafetyFloorConfig(
                    dataQualityCap = DataQualityCapConfig(
                        staleAfter = Duration.ofSeconds(60),
                        cappedProbability = BigDecimal("0.50"),
                    ),
                ),
                clock = fixedClock(),
            ),
            marketDataSource = StaleTickerMarketDataSource,
            clock = fixedClock(),
        )
        val command = marketEntryCommand(
            sizeBtc = BigDecimal("0.0040"),
            takeProfitPriceJpy = BigDecimal("10300000"),
            estimatedWinProbability = BigDecimal("0.95"),
        )

        val result = broker.placeOrder(approvedCommand(decisionRepository, command)).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE, result.safetyViolation?.rule)
        assertTrue(result.safetyViolation?.messageJa.orEmpty().contains("データ鮮度劣化により p を 0.50 に cap"))
    }

    @Test
    fun place_order_capsProbabilityFromBrokenTickerTimestamp() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val logHandler = CapturingLogHandler()
        val logger = Logger.getLogger(PaperBroker::class.java.name)
        logger.addHandler(logHandler)

        try {
            val broker = PaperBroker(
                ledgerRepository = repository,
                riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                decisionRepository = decisionRepository,
                safetyFloor = SafetyFloor(
                    config = SafetyFloorConfig(
                        dataQualityCap = DataQualityCapConfig(
                            staleAfter = Duration.ofSeconds(60),
                            cappedProbability = BigDecimal("0.50"),
                        ),
                    ),
                    clock = fixedClock(),
                ),
                marketDataSource = BrokenTimestampMarketDataSource,
                clock = fixedClock(),
            )
            val command = marketEntryCommand(
                sizeBtc = BigDecimal("0.0040"),
                takeProfitPriceJpy = BigDecimal("10300000"),
                estimatedWinProbability = BigDecimal("0.95"),
            )

            val result = broker.placeOrder(approvedCommand(decisionRepository, command)).getOrThrow()

            assertFalse(result.accepted)
            assertEquals(SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE, result.safetyViolation?.rule)
            assertTrue(result.safetyViolation?.messageJa.orEmpty().contains("データ鮮度劣化により p を 0.50 に cap"))
            assertTrue(logHandler.messages.any { message -> message.contains("could not parse ticker timestamp") })
        } finally {
            logger.removeHandler(logHandler)
        }
    }

    @Test
    fun place_order_returns_existing_result_for_same_client_request_id() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val command = approvedCommand(decisionRepository, marketEntryCommand(clientRequestId = "entry-1"))
        val firstResult = broker.placeOrder(command).getOrThrow()
        val secondResult = broker.placeOrder(command).getOrThrow()
        val positions = broker.getPositions().getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertEquals(firstResult.orderIds, secondResult.orderIds)
        assertEquals(firstResult.positionIds, secondResult.positionIds)
        assertEquals(firstResult.executionIds, secondResult.executionIds)
        assertEquals(1, positions.size)
        assertEquals(1, executions.size)
    }

    @Test
    fun place_order_rejects_entry_without_approved_falsification() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val violationRepository = InMemorySafetyViolationRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = intentCommand(decisionRepository, marketEntryCommand())

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.MISSING_FRESH_FALSIFICATION, result.safetyViolation?.rule)
        assertEquals(0, repository.getExecutions().getOrThrow().size)
        assertEquals(1, violationRepository.violations().size)
    }

    @Test
    fun place_order_rejects_entry_with_stale_approved_falsification() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val violationRepository = InMemorySafetyViolationRepository()
        val staleClock = Clock.fixed(fixedInstant().minusSeconds(121), ZoneOffset.UTC)
        val decisionRepository = InMemoryDecisionRepository(staleClock)
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(decisionRepository, marketEntryCommand())

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.MISSING_FRESH_FALSIFICATION, result.safetyViolation?.rule)
        assertEquals(0, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun place_order_rejects_entry_when_intent_declared_values_do_not_match() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val violationRepository = InMemorySafetyViolationRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val approvedCommand = approvedCommand(decisionRepository, marketEntryCommand())
        val mismatchedCommand = approvedCommand.copy(sizeBtc = BigDecimal("0.0060"))

        val result = broker.placeOrder(mismatchedCommand).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.INTENT_MISMATCH, result.safetyViolation?.rule)
        assertEquals(0, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun place_order_rejects_entry_when_intent_is_consumed() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val violationRepository = InMemorySafetyViolationRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(decisionRepository, marketEntryCommand())

        val firstResult = broker.placeOrder(command).getOrThrow()
        val secondResult = broker.placeOrder(command).getOrThrow()

        assertTrue(firstResult.accepted)
        assertFalse(secondResult.accepted)
        assertEquals(SafetyFloorRule.INTENT_CONSUMED, secondResult.safetyViolation?.rule)
        assertEquals(1, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun place_order_accepts_entry_with_fresh_approved_falsification() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val violationRepository = InMemorySafetyViolationRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)

        val result = broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()

        assertTrue(result.accepted)
        assertEquals(1, repository.getExecutions().getOrThrow().size)
        assertEquals(0, violationRepository.violations().size)
    }

    @Test
    fun soft_halt_rejects_place_order_but_allows_close_update_and_cancel() = runBlocking {
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc(),
            positions = listOf(protectedPosition()),
            openOrders = listOf(linkedStopOrder(), orphanTakeProfitOrder()),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            safetyViolationRepository = violationRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        riskStateRepository.setSoftHalt("operator pause", fixedInstant()).getOrThrow()

        val placeResult = broker.placeOrder(marketEntryCommand()).getOrThrow()
        val updateResult = broker.updateProtection(
            UpdateProtectionCommand(
                commandId = UUID.randomUUID(),
                positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                newStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceSpecified = false,
                newTakeProfitPriceJpy = null,
                reasonJa = "tighten protection during soft halt",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val cancelResult = broker.cancelOrder(
            CancelOrderCommand(
                commandId = UUID.randomUUID(),
                orderId = UUID.fromString("20000000-0000-0000-0000-000000000002"),
                reasonJa = "cancel stale take profit during soft halt",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val closeResult = broker.closePosition(
            ClosePositionCommand(
                commandId = UUID.randomUUID(),
                positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                closeAll = false,
                reasonJa = "close during soft halt",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()

        assertFalse(placeResult.accepted)
        assertEquals(SafetyFloorRule.SOFT_HALT_ENTRY_BLOCKED, placeResult.safetyViolation?.rule)
        assertTrue(updateResult.accepted)
        assertTrue(cancelResult.accepted)
        assertTrue(closeResult.accepted)
        assertEquals(1, violationRepository.violations().size)
        assertEquals(0, broker.getPositions().getOrThrow().size)
    }

    @Test
    fun close_position_uses_bid_orderbook_walk_and_atr_slippage() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc().copy(btcQuantity = "0.010000000000"),
            positions = listOf(protectedPosition()),
            openOrders = listOf(linkedStopOrder()),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            marketDataSource = OrderbookAtrMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.closePosition(
            ClosePositionCommand(
                commandId = UUID.randomUUID(),
                positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                closeAll = false,
                reasonJa = "close with orderbook",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertTrue(result.accepted)
        assertEquals("9984605.00000000", executions.single().priceJpy)
    }

    @Test
    fun place_order_does_not_consume_intent_when_ledger_write_fails() = runBlocking {
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = FailingPlaceOrderLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        val command = approvedCommand(decisionRepository, marketEntryCommand())

        val result = broker.placeOrder(command)

        assertTrue(result.isFailure)
        assertEquals(0, decisionRepository.snapshots.intentConsumptions().size)
    }

    @Test
    fun close_position_and_update_protection_do_not_require_falsification() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc(),
            positions = listOf(protectedPosition()),
            openOrders = listOf(linkedStopOrder()),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val updateResult = broker.updateProtection(
            UpdateProtectionCommand(
                commandId = UUID.randomUUID(),
                positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                newStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceSpecified = false,
                newTakeProfitPriceJpy = null,
                reasonJa = "test update without falsification",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val closeResult = broker.closePosition(
            ClosePositionCommand(
                commandId = UUID.randomUUID(),
                positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                closeAll = false,
                reasonJa = "test close without falsification",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()

        assertTrue(updateResult.accepted)
        assertTrue(closeResult.accepted)
    }

    @Test
    fun close_position_with_partial_ratio_keeps_position_open_and_reduces_stop_size() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc(),
            positions = listOf(protectedPosition()),
            openOrders = listOf(linkedStopOrder()),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.closePosition(
            ClosePositionCommand(
                commandId = UUID.randomUUID(),
                positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                closeAll = false,
                closeRatio = BigDecimal("0.50"),
                reasonJa = "test partial close",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val position = broker.getPositions().getOrThrow().single()
        val stopOrder = broker.getOpenOrders().getOrThrow().single()
        val sellExecution = repository.getExecutions().getOrThrow().single()

        assertTrue(result.accepted)
        assertEquals("0.005000000000", position.sizeBtc)
        assertEquals(PositionStatus.OPEN, position.status)
        assertEquals("0.005000000000", stopOrder.sizeBtc)
        assertEquals(OrderSide.SELL, sellExecution.side)
        assertEquals("0.005000000000", sellExecution.sizeBtc)
    }

    @Test
    fun close_position_with_partial_ratio_allows_missing_linked_stop_order() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc(),
            positions = listOf(protectedPosition()),
            openOrders = emptyList(),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.closePosition(
            ClosePositionCommand(
                commandId = UUID.randomUUID(),
                positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                closeAll = false,
                closeRatio = BigDecimal("0.50"),
                reasonJa = "test partial close without stop",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val position = broker.getPositions().getOrThrow().single()
        val sellExecution = repository.getExecutions().getOrThrow().single()

        assertTrue(result.accepted)
        assertEquals("0.005000000000", position.sizeBtc)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
        assertEquals(OrderSide.SELL, sellExecution.side)
    }

    @Test
    fun close_position_promotes_dust_remainder_to_full_close() = runBlocking {
        val tinyPosition = protectedPosition().copy(sizeBtc = "0.000250000000")
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc().copy(btcQuantity = "0.000250000000"),
            positions = listOf(tinyPosition),
            openOrders = listOf(linkedStopOrder().copy(sizeBtc = "0.000250000000")),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.closePosition(
            ClosePositionCommand(
                commandId = UUID.randomUUID(),
                positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                closeAll = false,
                closeRatio = BigDecimal("0.80"),
                reasonJa = "test dust close",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val closedPosition = repository.getAllPositionsForTest().single()
        val sellExecution = repository.getExecutions().getOrThrow().single()

        assertTrue(result.accepted)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(PositionStatus.CLOSED, closedPosition.status)
        assertEquals("0.000250000000", sellExecution.sizeBtc)
    }

    @Test
    fun place_order_add_long_merges_into_existing_position_and_tightens_stop() = runBlocking {
        val tradeGroupId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = highEquityAccountSnapshotWithBtc(),
            positions = listOf(protectedPosition().copy(unrealizedR = "1.200000")),
            openOrders = listOf(linkedStopOrder()),
        )
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                sizeBtc = BigDecimal("0.0010"),
                tradeGroupId = tradeGroupId,
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10600000"),
                estimatedWinProbability = BigDecimal("0.99"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()
        val position = broker.getPositions().getOrThrow().single()
        val stopOrder = broker.getOpenOrders().getOrThrow().single()
        val executions = repository.getExecutions().getOrThrow()

        assertTrue(result.accepted)
        assertEquals(1, broker.getPositions().getOrThrow().size)
        assertEquals("0.011000000000", position.sizeBtc)
        assertEquals("10000454.54545455", position.averageEntryPriceJpy)
        assertEquals(1, position.pyramidAddCount)
        assertEquals("9900000.00000000", position.currentStopLossJpy)
        assertEquals("0.011000000000", stopOrder.sizeBtc)
        assertEquals("9900000.00000000", stopOrder.triggerPriceJpy)
        assertEquals(listOf(OrderSide.BUY), executions.map { execution -> execution.side })
    }

    @Test
    fun place_order_add_long_group_risk_uses_merged_position_with_tightened_stop() = runBlocking {
        val tradeGroupId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc().copy(
                btcQuantity = "0.005000000000",
                btcMarkPriceJpy = "10100000.00000000",
                totalEquityJpy = "100000.00000000",
                equityPeakJpy = "100000.00000000",
            ),
            positions = listOf(
                protectedPosition().copy(
                    sizeBtc = "0.005000000000",
                    currentStopLossJpy = "9600000.00000000",
                    unrealizedR = "1.200000",
                ),
            ),
            openOrders = listOf(
                linkedStopOrder().copy(
                    sizeBtc = "0.005000000000",
                    triggerPriceJpy = "9600000.00000000",
                ),
            ),
        )
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                sizeBtc = BigDecimal("0.0010"),
                tradeGroupId = tradeGroupId,
                protectiveStopPriceJpy = BigDecimal("9990000"),
                takeProfitPriceJpy = BigDecimal("10600000"),
                estimatedWinProbability = BigDecimal("0.99"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()
        val position = broker.getPositions().getOrThrow().single()

        assertTrue(result.accepted)
        assertEquals("0.006000000000", position.sizeBtc)
        assertEquals("9990000.00000000", position.currentStopLossJpy)
        assertEquals(0, violationRepository.violations().size)
    }

    @Test
    fun safety_floor_rejects_add_long_when_pyramid_add_count_reaches_limit() = runBlocking {
        val tradeGroupId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc(),
            positions = listOf(protectedPosition().copy(pyramidAddCount = 2, unrealizedR = "3.000000")),
            openOrders = listOf(linkedStopOrder()),
        )
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                sizeBtc = BigDecimal("0.0010"),
                tradeGroupId = tradeGroupId,
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10600000"),
                estimatedWinProbability = BigDecimal("0.99"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.PYRAMID_ADD_LIMIT, result.safetyViolation?.rule)
        assertEquals("0.010000000000", broker.getPositions().getOrThrow().single().sizeBtc)
    }

    @Test
    fun safety_floor_rejects_add_long_without_required_unrealized_r() = runBlocking {
        val tradeGroupId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc(),
            positions = listOf(protectedPosition().copy(pyramidAddCount = 1, unrealizedR = "1.500000")),
            openOrders = listOf(linkedStopOrder()),
        )
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                sizeBtc = BigDecimal("0.0010"),
                tradeGroupId = tradeGroupId,
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10600000"),
                estimatedWinProbability = BigDecimal("0.99"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.PYRAMID_PROFIT_GATE, result.safetyViolation?.rule)
    }

    @Test
    fun safety_floor_rejects_add_long_when_add_risk_exceeds_half_initial_budget() = runBlocking {
        val tradeGroupId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc(),
            positions = listOf(protectedPosition().copy(sizeBtc = "0.001000000000", unrealizedR = "1.200000")),
            openOrders = listOf(linkedStopOrder().copy(sizeBtc = "0.001000000000")),
        )
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                sizeBtc = BigDecimal("0.0030"),
                tradeGroupId = tradeGroupId,
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10600000"),
                estimatedWinProbability = BigDecimal("0.99"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.PYRAMID_ADD_RISK_LIMIT, result.safetyViolation?.rule)
    }

    @Test
    fun safety_floor_uses_initial_entry_risk_budget_for_second_add_long() = runBlocking {
        val tradeGroupId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val initialOrderId = "20000000-0000-0000-0000-000000000010"
        val violationRepository = InMemorySafetyViolationRepository()
        val initialEntryOrder = order(
            orderId = initialOrderId,
            positionId = "00000000-0000-0000-0000-000000000001",
            tradeGroupId = tradeGroupId.toString(),
            orderType = OrderType.MARKET,
            side = OrderSide.BUY,
            status = OrderStatus.FILLED,
        ).copy(
            sizeBtc = "0.001000000000",
            protectiveStopPriceJpy = "9900000.00000000",
            createdAt = fixedInstant().minusSeconds(120).toString(),
        )
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = highEquityAccountSnapshotWithBtc(),
            positions = listOf(
                protectedPosition().copy(
                    sizeBtc = "0.003000000000",
                    currentStopLossJpy = "9900000.00000000",
                    pyramidAddCount = 1,
                    unrealizedR = "2.200000",
                ),
            ),
            openOrders = listOf(
                initialEntryOrder,
                linkedStopOrder().copy(
                    sizeBtc = "0.003000000000",
                    triggerPriceJpy = "9900000.00000000",
                ),
            ),
            executions = listOf(initialBuyExecution(orderId = initialOrderId)),
        )
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                sizeBtc = BigDecimal("0.0010"),
                tradeGroupId = tradeGroupId,
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10600000"),
                estimatedWinProbability = BigDecimal("0.99"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.PYRAMID_ADD_RISK_LIMIT, result.safetyViolation?.rule)
    }

    @Test
    fun hard_halt_allows_close_position_as_risk_reducing_operation() = runBlocking {
        val riskStateRepository = InMemoryRiskStateRepository(
            clock = fixedClock(),
            initialState = RiskState(
                state = RiskHaltState.HARD_HALT,
                drawdownRatio = BigDecimal("-0.2000000000"),
                equityPeak = BigDecimal("100000"),
                haltReason = "test hard halt",
                haltAt = fixedInstant(),
                updatedAt = fixedInstant(),
            ),
        )
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = drawdownAccountSnapshotWithBtc(),
            positions = listOf(protectedPosition()),
            openOrders = listOf(linkedStopOrder()),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.closePosition(
            ClosePositionCommand(
                commandId = UUID.randomUUID(),
                positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                closeAll = false,
                closeRatio = BigDecimal("0.25"),
                reasonJa = "test reduce during hard halt",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()

        assertTrue(result.accepted)
        assertEquals("0.007500000000", broker.getPositions().getOrThrow().single().sizeBtc)
    }

    @Test
    fun place_order_reserves_open_buy_fee_when_checking_cash() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(accountSnapshot = highEquityLowCashAccountSnapshot())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FineStepMarketDataSource,
            clock = fixedClock(),
        )

        broker.placeOrder(approvedCommand(decisionRepository, nearAllCashRestingLimitCommand())).getOrThrow()

        val result = broker.placeOrder(approvedCommand(decisionRepository, tinyMarketEntryCommand())).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT, result.safetyViolation?.rule)
    }

    @Test
    fun estimatedBuyReservationJpy_usesSlippageForOpenStopOrder() = runBlocking {
        val ticker = FakeMarketDataSource.getTicker(TradingSymbol.BTC).getOrThrow()
        val order = order(
            orderId = "20000000-0000-0000-0000-000000000006",
            positionId = null,
            orderType = OrderType.STOP,
            side = OrderSide.BUY,
            status = OrderStatus.OPEN,
        ).copy(
            limitPriceJpy = null,
            triggerPriceJpy = "10000000.00000000",
        )

        val reservation = order.estimatedBuyReservationJpy(
            ticker = ticker,
            rules = defaultSymbolRules(),
            fillSimulator = FillSimulator(clock = fixedClock()),
        )

        assertEquals("100100.02500000", reservation.toPlainString())
    }

    @Test
    fun safety_floor_rejects_group_risk_over_two_percent_before_side_effect() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                protectiveStopPriceJpy = BigDecimal("9000000"),
                takeProfitPriceJpy = BigDecimal("10500000"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()
        val violations = violationRepository.violations()

        assertFalse(result.accepted)
        assertEquals(OrderStatus.REJECTED, result.status)
        assertEquals(SafetyFloorRule.MAX_RISK_PER_TRADE, result.safetyViolation?.rule)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(1, violations.size)
    }

    @Test
    fun safety_floor_rejects_entry_without_valid_stop_loss() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                protectiveStopPriceJpy = BigDecimal("10010000"),
                takeProfitPriceJpy = BigDecimal("10500000"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.STOP_LOSS_REQUIRED, result.safetyViolation?.rule)
        assertEquals(0, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun safety_floor_rejects_averaging_down_on_losing_group() = runBlocking {
        val tradeGroupId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc(),
            positions = listOf(losingPosition(tradeGroupId.toString())),
            openOrders = listOf(linkedStopOrder(tradeGroupId.toString())),
        )
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                tradeGroupId = tradeGroupId,
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10100000"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.NO_AVERAGING_DOWN, result.safetyViolation?.rule)
        assertEquals(1, broker.getPositions().getOrThrow().size)
    }

    @Test
    fun safety_floor_rejects_total_exposure_over_eighty_percent() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                sizeBtc = BigDecimal("0.0090"),
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10100000"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.MAX_TOTAL_EXPOSURE, result.safetyViolation?.rule)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
    }

    @Test
    fun safety_floor_rejects_cash_shortage_after_existing_buy_reservation() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository(accountSnapshot = highEquityLowCashAccountSnapshot())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(
            repository = repository,
            violationRepository = violationRepository,
            decisionRepository = decisionRepository,
            marketDataSource = FineStepMarketDataSource,
        )
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                sizeBtc = BigDecimal("0.0120"),
                protectiveStopPriceJpy = BigDecimal("9950000"),
                takeProfitPriceJpy = BigDecimal("10100000"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT, result.safetyViolation?.rule)
        assertEquals(0, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun safety_floor_rejects_calculated_non_positive_expected_value() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10500000"),
                estimatedWinProbability = BigDecimal("0.20"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE, result.safetyViolation?.rule)
    }

    @Test
    fun safety_floor_rejects_missing_take_profit_for_ev_gate() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                takeProfitPriceJpy = null,
                estimatedWinProbability = BigDecimal("0.99"),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.MISSING_TARGET_PRICE, result.safetyViolation?.rule)
    }

    @Test
    fun safety_floor_rejects_calculated_expected_move_to_cost_ratio_below_threshold() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10048000"),
                estimatedWinProbability = BigDecimal.ONE,
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO, result.safetyViolation?.rule)
        assertEquals("2.16026124", result.safetyViolation?.measuredValue)
        assertEquals("2.5", result.safetyViolation?.limitValue)
    }

    @Test
    fun safety_floor_accepts_calculated_expected_move_to_cost_ratio_between_new_and_old_thresholds() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        val command = approvedCommand(
            repository = decisionRepository,
            command = marketEntryCommand(
                protectiveStopPriceJpy = BigDecimal("9900000"),
                takeProfitPriceJpy = BigDecimal("10059000"),
                estimatedWinProbability = BigDecimal.ONE,
            ),
        )
        val riskDetails = SafetyFloor(clock = fixedClock()).placeOrderRiskDetails(
            command = command,
            context = SafetyFloorContext(
                account = accountSnapshot(),
                riskState = RiskState(updatedAt = fixedInstant()),
                positions = emptyList(),
                openOrders = emptyList(),
                ticker = FakeMarketDataSource.getTicker(TradingSymbol.BTC).getOrThrow(),
                symbolRules = FakeMarketDataSource.getSymbolRules(TradingSymbol.BTC).getOrThrow(),
                marketDataObservedAt = fixedInstant(),
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertEquals("2.71288621", riskDetails.expectedMoveToCostRatio)
        assertTrue(result.accepted)
    }

    @Test
    fun safety_floor_hard_halt_sets_risk_state_and_sweeps_open_risk() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val riskStateRepository = InMemoryRiskStateRepository(
            clock = fixedClock(),
            initialState = RiskState(
                drawdownRatio = BigDecimal("-0.1500000000"),
                equityPeak = BigDecimal("100000"),
                updatedAt = fixedInstant(),
            ),
        )
        val commandEventLog = InMemoryCommandEventLog()
        val riskStateCommandService = InMemoryRiskStateCommandService(
            riskStateRepository = riskStateRepository,
            commandEventLog = commandEventLog,
            clock = fixedClock(),
        )
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = drawdownAccountSnapshotWithBtc(),
            positions = listOf(protectedPosition()),
            openOrders = listOf(linkedStopOrder()),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            riskStateCommandService = riskStateCommandService,
            safetyViolationRepository = violationRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.placeOrder(marketEntryCommand()).getOrThrow()
        val riskState = riskStateRepository.current().getOrThrow()

        assertFalse(result.accepted)
        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
        assertEquals(SafetyFloorRule.MAX_DRAWDOWN_HALT, result.safetyViolation?.rule)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
        assertTrue(result.executionIds.isNotEmpty())
    }

    @Test
    fun safety_floor_rejects_sticky_hard_halt_even_without_current_drawdown() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val riskStateRepository = InMemoryRiskStateRepository(
            clock = fixedClock(),
            initialState = RiskState(
                state = RiskHaltState.HARD_HALT,
                drawdownRatio = BigDecimal.ZERO,
                equityPeak = BigDecimal("100000"),
                haltReason = "test hard halt",
                haltAt = fixedInstant(),
                updatedAt = fixedInstant(),
            ),
        )
        val repository = InMemoryPaperLedgerRepository()
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            safetyViolationRepository = violationRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.placeOrder(marketEntryCommand()).getOrThrow()
        val riskState = riskStateRepository.current().getOrThrow()

        assertFalse(result.accepted)
        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
        assertEquals(SafetyFloorRule.MAX_DRAWDOWN_HALT, result.safetyViolation?.rule)
        assertEquals(1, violationRepository.violations().size)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
    }

    @Test
    fun safety_floor_rejects_stop_loosen_but_allows_take_profit_clear() = runBlocking {
        val violationRepository = InMemorySafetyViolationRepository()
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = safetyBroker(repository, violationRepository, decisionRepository = decisionRepository)
        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()
        val positionId = UUID.fromString(broker.getPositions().getOrThrow().single().positionId)

        val loosenResult = broker.updateProtection(
            UpdateProtectionCommand(
                commandId = UUID.randomUUID(),
                positionId = positionId,
                newStopPriceJpy = BigDecimal("9600000"),
                takeProfitPriceSpecified = false,
                newTakeProfitPriceJpy = null,
                reasonJa = "test loosen",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val clearTakeProfitResult = broker.updateProtection(
            UpdateProtectionCommand(
                commandId = UUID.randomUUID(),
                positionId = positionId,
                newStopPriceJpy = null,
                takeProfitPriceSpecified = true,
                newTakeProfitPriceJpy = null,
                reasonJa = "test clear take profit",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()

        assertFalse(loosenResult.accepted)
        assertEquals(SafetyFloorRule.STOP_LOSS_LOOSENING, loosenResult.safetyViolation?.rule)
        assertTrue(clearTakeProfitResult.accepted)
        assertEquals(null, broker.getPositions().getOrThrow().single().currentTakeProfitJpy)
    }

    @Test
    fun update_protection_updates_stop_and_virtual_take_profit() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()
        val positionId = UUID.fromString(broker.getPositions().getOrThrow().single().positionId)

        val result = broker.updateProtection(
            UpdateProtectionCommand(
                commandId = UUID.randomUUID(),
                positionId = positionId,
                newStopPriceJpy = BigDecimal("9800000"),
                takeProfitPriceSpecified = true,
                newTakeProfitPriceJpy = BigDecimal("10600000"),
                reasonJa = "test update",
                auditContext = PaperTradeAuditContext.EMPTY,
            ),
        ).getOrThrow()
        val position = broker.getPositions().getOrThrow().single()
        val stopOrder = broker.getOpenOrders().getOrThrow().single { order -> order.orderType == OrderType.STOP }

        assertEquals(OrderStatus.OPEN, result.status)
        assertEquals("9800000", position.currentStopLossJpy)
        assertEquals("10600000", position.currentTakeProfitJpy)
        assertEquals("9800000.00000000", stopOrder.triggerPriceJpy)
    }

    @Test
    fun cancel_order_rejects_protective_stop_and_allows_resting_entry_cancel() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, restingLimitCommand())).getOrThrow()
        val restingOrderId = UUID.fromString(broker.getOpenOrders().getOrThrow().single().orderId)
        val cancelRestingResult = broker.cancelOrder(cancelCommand(restingOrderId)).getOrThrow()

        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()
        val stopOrderId = UUID.fromString(
            broker.getOpenOrders()
                .getOrThrow()
                .single { order -> order.orderType == OrderType.STOP }
                .orderId,
        )

        val cancelStopResult = broker.cancelOrder(cancelCommand(stopOrderId))

        assertEquals(OrderStatus.CANCELED, cancelRestingResult.status)
        assertTrue(cancelStopResult.isFailure)
    }

    @Test
    fun reconcile_rounds_atr_trailing_stop_down_to_tick_size() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()

        broker.reconcile(trailingTickSnapshot()).getOrThrow()

        val position = broker.getPositions().getOrThrow().single()
        val stopOrder = broker.getOpenOrders().getOrThrow().single { order -> order.orderType == OrderType.STOP }

        assertEquals("10099750.00000000", position.currentStopLossJpy)
        assertEquals("10099750.00000000", stopOrder.triggerPriceJpy)
        assertEquals("10100000.00000000", position.highestPriceSinceEntryJpy)
        assertEquals("10005000.00000000", position.lowestPriceSinceEntryJpy)
    }

    @Test
    fun periodic_rest_maintenance_does_not_execute_protective_stop_but_market_event_does() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()

        broker.maintainProtections(stopGapTickSnapshot()).getOrThrow()

        assertEquals(1, broker.getPositions().getOrThrow().size)
        assertEquals(1, repository.getExecutions().getOrThrow().size)

        broker.applyMarketEvent(
            PaperMarketTradeEvent(
                symbol = TradingSymbol.BTC,
                side = OrderSide.SELL,
                priceJpy = BigDecimal("9600000"),
                sizeBtc = BigDecimal("0.0010"),
                exchangeAt = fixedInstant().plusSeconds(1),
                receivedAt = fixedInstant().plusSeconds(1),
                connectionSessionId = UUID.fromString("00000000-0000-0000-0000-000000000171"),
                sequence = 1,
            ),
        ).getOrThrow()

        assertTrue(broker.getPositions().getOrThrow().isEmpty())
        assertEquals(2, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun market_event_limit_entry_consumes_sell_queue_once_before_fill() = runBlocking {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000178")
        val repository = InMemoryPaperLedgerRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val reconcilerStatus = MutableReconcilerStatus()
        val marketDataSource = MutableOrderbookMarketDataSource(
            orderbook = Orderbook(
                symbol = "BTC",
                bids = listOf(OrderbookLevel(price = "9900000", size = "0.0020")),
                asks = listOf(OrderbookLevel(price = "10000000", size = "1.0000")),
            ),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = marketDataSource,
            reconcilerStatusProvider = reconcilerStatus,
            requireRealtimeIntegrityForRestingOrders = true,
            clock = fixedClock(),
        )
        broker.applyMarketEvent(inMemoryPaperTradeEvent(sessionId, 1, OrderSide.SELL, "0.0010")).getOrThrow()
        reconcilerStatus.updateMarketData(
            ReconcilerStatus(
                lastReconciledAt = fixedInstant(),
                startupFullReconcileCompleted = true,
                lastMarketDataAt = fixedInstant(),
                marketDataState = MarketDataConnectionState.CONNECTED,
                marketDataSessionId = sessionId,
                lastProcessedSequence = 1,
                startupRecoveryCompleted = true,
            ),
        )
        broker.placeOrder(approvedCommand(decisionRepository, restingLimitCommand())).getOrThrow()

        broker.applyMarketEvent(inMemoryPaperTradeEvent(sessionId, 2, OrderSide.BUY, "1.0000")).getOrThrow()
        broker.applyMarketEvent(inMemoryPaperTradeEvent(sessionId, 3, OrderSide.SELL, "0.0040")).getOrThrow()
        broker.applyMarketEvent(inMemoryPaperTradeEvent(sessionId, 3, OrderSide.SELL, "0.0040")).getOrThrow()

        assertTrue(repository.getExecutions().getOrThrow().isEmpty())

        broker.applyMarketEvent(inMemoryPaperTradeEvent(sessionId, 4, OrderSide.SELL, "0.0030")).getOrThrow()

        assertEquals(1, repository.getExecutions().getOrThrow().size)
        assertEquals(OrderType.STOP, broker.getOpenOrders().getOrThrow().single().orderType)
    }

    @Test
    fun periodic_rest_maintenance_does_not_execute_virtual_take_profit_but_market_event_does() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()

        broker.maintainProtections(watermarkTickSnapshot("10600000")).getOrThrow()

        assertEquals(1, broker.getPositions().getOrThrow().size)
        assertEquals(1, repository.getExecutions().getOrThrow().size)

        broker.applyMarketEvent(
            PaperMarketTradeEvent(
                symbol = TradingSymbol.BTC,
                side = OrderSide.BUY,
                priceJpy = BigDecimal("10600000"),
                sizeBtc = BigDecimal("0.0010"),
                exchangeAt = fixedInstant().plusSeconds(1),
                receivedAt = fixedInstant().plusSeconds(1),
                connectionSessionId = UUID.fromString("00000000-0000-0000-0000-000000000172"),
                sequence = 1,
            ),
        ).getOrThrow()

        assertTrue(broker.getPositions().getOrThrow().isEmpty())
        assertEquals(2, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun market_event_session_switch_rebinds_existing_position_for_first_recovered_event() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()

        broker.applyMarketEvent(
            PaperMarketTradeEvent(
                symbol = TradingSymbol.BTC,
                side = OrderSide.SELL,
                priceJpy = BigDecimal("10000000"),
                sizeBtc = BigDecimal("0.0010"),
                exchangeAt = fixedInstant().plusSeconds(1),
                receivedAt = fixedInstant().plusSeconds(1),
                connectionSessionId = UUID.fromString("00000000-0000-0000-0000-000000000176"),
                sequence = 1,
            ),
        ).getOrThrow()

        broker.applyMarketEvent(
            PaperMarketTradeEvent(
                symbol = TradingSymbol.BTC,
                side = OrderSide.SELL,
                priceJpy = BigDecimal("9600000"),
                sizeBtc = BigDecimal("0.0010"),
                exchangeAt = fixedInstant().plusSeconds(2),
                receivedAt = fixedInstant().plusSeconds(2),
                connectionSessionId = UUID.fromString("00000000-0000-0000-0000-000000000177"),
                sequence = 1,
            ),
        ).getOrThrow()

        assertTrue(broker.getPositions().getOrThrow().isEmpty())
        assertEquals(2, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun market_event_session_switch_rebinds_existing_position_for_first_recovered_take_profit() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()

        broker.applyMarketEvent(
            PaperMarketTradeEvent(
                symbol = TradingSymbol.BTC,
                side = OrderSide.SELL,
                priceJpy = BigDecimal("10000000"),
                sizeBtc = BigDecimal("0.0010"),
                exchangeAt = fixedInstant().plusSeconds(1),
                receivedAt = fixedInstant().plusSeconds(1),
                connectionSessionId = UUID.fromString("00000000-0000-0000-0000-000000000176"),
                sequence = 1,
            ),
        ).getOrThrow()

        broker.applyMarketEvent(
            PaperMarketTradeEvent(
                symbol = TradingSymbol.BTC,
                side = OrderSide.BUY,
                priceJpy = BigDecimal("10600000"),
                sizeBtc = BigDecimal("0.0010"),
                exchangeAt = fixedInstant().plusSeconds(2),
                receivedAt = fixedInstant().plusSeconds(2),
                connectionSessionId = UUID.fromString("00000000-0000-0000-0000-000000000177"),
                sequence = 1,
            ),
        ).getOrThrow()

        assertTrue(broker.getPositions().getOrThrow().isEmpty())
        assertEquals(2, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun reconcile_buy_limit_reaches_when_best_ask_is_at_limit_even_if_last_price_is_above_limit() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val marketDataSource = MutableOrderbookMarketDataSource(
            orderbook = orderbookWithAsk("10000000"),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = marketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, restingLimitCommand())).getOrThrow()

        marketDataSource.orderbook = orderbookWithAsk("9900000")
        val result = broker.reconcile(watermarkTickSnapshot("10000000")).getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertEquals(1, result.filledOrderIds.size)
        assertEquals(1, executions.size)
        assertEquals(ExecutionLiquidity.MAKER, executions.single().liquidity)
    }

    @Test
    fun reconcile_buy_limit_stays_pending_when_best_ask_is_above_limit_even_if_last_price_is_below_limit() {
        runBlocking {
            val repository = InMemoryPaperLedgerRepository(clock = fixedClock())
            val decisionRepository = InMemoryDecisionRepository(fixedClock())
            val broker = PaperBroker(
                ledgerRepository = repository,
                riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                decisionRepository = decisionRepository,
                marketDataSource = MutableOrderbookMarketDataSource(
                    orderbook = orderbookWithAsk("10000000"),
                ),
                clock = fixedClock(),
            )
            broker.placeOrder(approvedCommand(decisionRepository, restingLimitCommand())).getOrThrow()

            val result = broker.reconcile(watermarkTickSnapshot("9800000")).getOrThrow()

            assertFalse(result.advanced)
            assertEquals(1, broker.getOpenOrders().getOrThrow().size)
            assertEquals(0, repository.getExecutions().getOrThrow().size)
        }
    }

    @Test
    fun reconcile_buy_limit_records_fak_divergence_memo_while_full_filling_paper_order() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val marketDataSource = MutableOrderbookMarketDataSource(
            orderbook = orderbookWithAsk("10000000"),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = marketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, restingLimitCommand())).getOrThrow()

        marketDataSource.orderbook = orderbookWithAsk(price = "9900000", size = "0.0020")
        val result = broker.reconcile(watermarkTickSnapshot("10000000")).getOrThrow()
        val execution = repository.getExecutions().getOrThrow().single()
        val memo = result.divergenceMemos.single()

        assertEquals("0.005000000000", execution.sizeBtc)
        assertEquals("0.005000000000", memo.requestedSizeBtc.toPlainString())
        assertEquals("0.002000000000", memo.hypotheticalFilledSizeBtc.toPlainString())
        assertEquals("0.003000000000", memo.hypotheticalRemainingSizeBtc.toPlainString())
        assertEquals("0.002000000000", memo.boardDepthBtc.toPlainString())
        assertEquals(LIMIT_PARTIAL_FAK_DIVERGENCE_KIND, memo.kind)
        assertEquals(TradingSymbol.BTC.apiSymbol, memo.symbol)
    }

    @Test
    fun reconcile_tracks_lowest_and_highest_watermarks_monotonically() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )

        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()
        broker.reconcile(watermarkTickSnapshot("9900000")).getOrThrow()

        val lowerMarkedPosition = broker.getPositions().getOrThrow().single()

        assertEquals("10005000.00000000", lowerMarkedPosition.highestPriceSinceEntryJpy)
        assertEquals("9900000.00000000", lowerMarkedPosition.lowestPriceSinceEntryJpy)
        assertEquals("-525.00000000", lowerMarkedPosition.unrealizedPnlJpy)

        broker.reconcile(watermarkTickSnapshot("10100000")).getOrThrow()
        broker.reconcile(watermarkTickSnapshot("10050000")).getOrThrow()

        val finalPosition = broker.getPositions().getOrThrow().single()

        assertEquals("10100000.00000000", finalPosition.highestPriceSinceEntryJpy)
        assertEquals("9900000.00000000", finalPosition.lowestPriceSinceEntryJpy)
    }

    @Test
    fun reconcile_folds_gap_stop_fill_into_closed_position_watermark() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(approvedCommand(decisionRepository, marketEntryCommand())).getOrThrow()

        val positionId = broker.getPositions().getOrThrow().single().positionId

        broker.reconcile(stopGapTickSnapshot()).getOrThrow()

        val closedPosition = repository.getAllPositionsForTest()
            .single { position -> position.positionId == positionId }

        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(PositionStatus.CLOSED, closedPosition.status)
        assertEquals("10005000.00000000", closedPosition.highestPriceSinceEntryJpy)
        assertEquals("9685155.00000000", closedPosition.lowestPriceSinceEntryJpy)
        assertEquals("9685155.00000000", closedPosition.currentPriceJpy)
    }

    @Test
    fun reconcile_stop_fill_uses_bid_orderbook_walk_and_tick_atr_slippage() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = accountSnapshotWithBtc().copy(btcQuantity = "0.010000000000"),
            positions = listOf(protectedPosition()),
            openOrders = listOf(linkedStopOrder()),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            marketDataSource = StopOrderbookMarketDataSource,
            clock = fixedClock(),
        )

        val result = broker.reconcile(stopGapTickSnapshotWithAtr()).getOrThrow()
        val executions = repository.getExecutions().getOrThrow()

        assertEquals(1, result.closedPositionIds.size)
        assertEquals("9672761.00000000", executions.single().priceJpy)
    }
}

private fun marketEntryCommand(
    intentId: UUID? = null,
    clientRequestId: String? = null,
    sizeBtc: BigDecimal = BigDecimal("0.0050"),
    tradeGroupId: UUID? = null,
    protectiveStopPriceJpy: BigDecimal = BigDecimal("9700000"),
    takeProfitPriceJpy: BigDecimal? = BigDecimal("10500000"),
    estimatedWinProbability: BigDecimal = BigDecimal("0.60"),
): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        intentId = intentId,
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.MARKET,
        sizeBtc = sizeBtc,
        priceJpy = null,
        tradeGroupId = tradeGroupId,
        protectiveStopPriceJpy = protectiveStopPriceJpy,
        takeProfitPriceJpy = takeProfitPriceJpy,
        estimatedWinProbability = estimatedWinProbability,
        reasonJa = "test entry",
        auditContext = PaperTradeAuditContext.EMPTY.copy(clientRequestId = clientRequestId),
    )
}

private fun restingLimitCommand(priceJpy: BigDecimal = BigDecimal("9900000")): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        sizeBtc = BigDecimal("0.0050"),
        priceJpy = priceJpy,
        tradeGroupId = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("10500000"),
        estimatedWinProbability = BigDecimal("0.60"),
        reasonJa = "test resting entry",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun nearAllCashRestingLimitCommand(): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        sizeBtc = BigDecimal("0.009999"),
        priceJpy = BigDecimal("10000000"),
        tradeGroupId = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("10500000"),
        estimatedWinProbability = BigDecimal("0.60"),
        reasonJa = "test near all cash resting entry",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun tinyMarketEntryCommand(): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.MARKET,
        sizeBtc = BigDecimal("0.000001"),
        priceJpy = null,
        tradeGroupId = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("10500000"),
        estimatedWinProbability = BigDecimal("0.60"),
        reasonJa = "test tiny entry",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun cancelCommand(orderId: UUID): CancelOrderCommand {
    return CancelOrderCommand(
        commandId = UUID.randomUUID(),
        orderId = orderId,
        reasonJa = "test cancel",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun trailingTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "10100000",
        bidPrice = "10100000",
        askPrice = "10110000",
        symbolRules = trailingSymbolRules(),
        atr14Jpy = "123.456789",
    )
}

/**
 * STOP gap fill で watermark を close fill 価格へ fold するための tick。
 */
private fun stopGapTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "9700000",
        bidPrice = "9690000",
        askPrice = "9700000",
        symbolRules = defaultSymbolRules(),
    )
}

/**
 * STOP gap fill で tick ATR を含む bid 板歩きを検証するための tick。
 */
private fun stopGapTickSnapshotWithAtr(): TickSnapshot {
    return stopGapTickSnapshot().copy(atr14Jpy = "4000")
}

/**
 * watermark の単調更新検証に使う tick。
 */
private fun watermarkTickSnapshot(
    lastPrice: String,
    bidPrice: String = lastPrice,
    askPrice: String = lastPrice,
): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = lastPrice,
        bidPrice = bidPrice,
        askPrice = askPrice,
        symbolRules = defaultSymbolRules(),
    )
}

private fun orderbookWithAsk(price: String, size: String = "0.0100"): Orderbook {
    return Orderbook(
        symbol = "BTC",
        bids = listOf(OrderbookLevel(price = "9990000", size = "0.0100")),
        asks = listOf(OrderbookLevel(price = price, size = size)),
    )
}

private fun defaultSymbolRules(): SymbolRules {
    return SymbolRules(
        symbol = "BTC",
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "1",
        takerFee = "0.0005",
        makerFee = "-0.0001",
    )
}

private fun trailingSymbolRules(): SymbolRules {
    return SymbolRules(
        symbol = "BTC",
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "10",
        takerFee = "0.0005",
        makerFee = "-0.0001",
    )
}

/**
 * PaperBroker execution test 用 fake market data。
 */
private object FakeMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.success(
            Ticker(
                symbol = symbol.apiSymbol,
                last = "10000000",
                bid = "9990000",
                ask = "10000000",
                high = "10100000",
                low = "9900000",
                volume = "1.0",
                timestamp = fixedInstant().toString(),
            ),
        )
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(emptyList())
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        return Result.success(emptyList())
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.success(
            SymbolRules(
                symbol = symbol.apiSymbol,
                minOrderSize = "0.0001",
                sizeStep = "0.0001",
                tickSize = "1",
                takerFee = "0.0005",
                makerFee = "-0.0001",
            ),
        )
    }
}

/**
 * 板歩きと ATR slippage 検証用 fake market data。
 */
private object OrderbookAtrMarketDataSource : MarketDataSource by FakeMarketDataSource {
    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(atrCandles(symbol, interval, limit))
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(
            Orderbook(
                symbol = symbol.apiSymbol,
                bids = listOf(
                    OrderbookLevel(price = "9990000", size = "0.0100"),
                ),
                asks = listOf(
                    OrderbookLevel(price = "10000000", size = "0.0020"),
                    OrderbookLevel(price = "10020000", size = "0.0030"),
                ),
            ),
        )
    }
}

/**
 * LIMIT entry の board relation を切り替える fake market data。
 *
 * @param orderbook 現在返す orderbook
 */
private class MutableOrderbookMarketDataSource(
    var orderbook: Orderbook,
) : MarketDataSource by FakeMarketDataSource {
    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(orderbook)
    }
}

/**
 * STOP reconcile の bid 板歩き検証用 fake market data。
 */
private object StopOrderbookMarketDataSource : MarketDataSource by FakeMarketDataSource {
    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(
            Orderbook(
                symbol = symbol.apiSymbol,
                bids = listOf(
                    OrderbookLevel(price = "9690000", size = "0.0040"),
                    OrderbookLevel(price = "9670000", size = "0.0060"),
                ),
                asks = emptyList(),
            ),
        )
    }
}

/**
 * stale ticker timestamp を返す fake market data。
 */
private object StaleTickerMarketDataSource : MarketDataSource by FakeMarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return FakeMarketDataSource.getTicker(symbol).map { ticker ->
            ticker.copy(timestamp = fixedInstant().minusSeconds(61).toString())
        }
    }
}

/**
 * 壊れた ticker timestamp を返す fake market data。
 */
private object BrokenTimestampMarketDataSource : MarketDataSource by FakeMarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return FakeMarketDataSource.getTicker(symbol).map { ticker ->
            ticker.copy(timestamp = "not-a-timestamp")
        }
    }
}

/**
 * warn log を test 内で捕捉する handler。
 */
private class CapturingLogHandler : Handler() {
    val messages = mutableListOf<String>()

    override fun publish(record: LogRecord) {
        messages += record.message
    }

    override fun flush() = Unit

    override fun close() = Unit
}

/**
 * fee 予約検証用に数量 step を細かくした fake market data。
 */
private object FineStepMarketDataSource : MarketDataSource by FakeMarketDataSource {
    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(orderbookWithAsk(price = "10000001"))
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.success(
            SymbolRules(
                symbol = symbol.apiSymbol,
                minOrderSize = "0.000001",
                sizeStep = "0.000001",
                tickSize = "1",
                takerFee = "0.0005",
                makerFee = "-0.0001",
            ),
        )
    }
}

private fun accountSnapshot(): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = "100000.00000000",
        initialCashJpy = "100000.00000000",
        btcQuantity = "0.000000000000",
        btcMarkPriceJpy = "0.00000000",
        totalEquityJpy = "100000.00000000",
        equityPeakJpy = "100000.00000000",
        drawdownRatio = "0",
    )
}

private fun accountSnapshotWithBtc(): AccountSnapshot {
    return accountSnapshot().copy(
        cashJpy = "50000.00000000",
        btcQuantity = "0.005000000000",
        btcMarkPriceJpy = "9900000.00000000",
        totalEquityJpy = "99500.00000000",
        equityPeakJpy = "100000.00000000",
        drawdownRatio = "-0.0050000000",
    )
}

private fun highEquityLowCashAccountSnapshot(): AccountSnapshot {
    return accountSnapshot().copy(
        cashJpy = "100000.00000000",
        totalEquityJpy = "200000.00000000",
        equityPeakJpy = "200000.00000000",
    )
}

private fun highEquityAccountSnapshotWithBtc(): AccountSnapshot {
    return accountSnapshotWithBtc().copy(
        totalEquityJpy = "200000.00000000",
        equityPeakJpy = "200000.00000000",
    )
}

private fun drawdownAccountSnapshotWithBtc(): AccountSnapshot {
    return accountSnapshot().copy(
        cashJpy = "49000.00000000",
        btcQuantity = "0.005000000000",
        btcMarkPriceJpy = "9900000.00000000",
        totalEquityJpy = "85000.00000000",
        equityPeakJpy = "100000.00000000",
        drawdownRatio = "-0.1500000000",
    )
}

private fun protectedPosition(): Position {
    return position(
        positionId = "00000000-0000-0000-0000-000000000001",
        currentStopLossJpy = "9800000.00000000",
    )
}

private fun unprotectedPosition(): Position {
    return position(
        positionId = "00000000-0000-0000-0000-000000000002",
        currentStopLossJpy = null,
    )
}

private fun losingPosition(tradeGroupId: String): Position {
    return position(
        positionId = "00000000-0000-0000-0000-000000000005",
        tradeGroupId = tradeGroupId,
        currentStopLossJpy = "9800000.00000000",
    ).copy(
        currentPriceJpy = "9900000.00000000",
        unrealizedPnlJpy = "-500.00000000",
        highestPriceSinceEntryJpy = "10000000.00000000",
        lowestPriceSinceEntryJpy = "9900000.00000000",
    )
}

private fun position(
    positionId: String,
    currentStopLossJpy: String?,
    tradeGroupId: String = "10000000-0000-0000-0000-000000000001",
): Position {
    return Position(
        positionId = positionId,
        tradeGroupId = tradeGroupId,
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = fixedInstant().toString(),
        closedAt = null,
        sizeBtc = "0.010000000000",
        averageEntryPriceJpy = "10000000.00000000",
        currentPriceJpy = "10100000.00000000",
        currentStopLossJpy = currentStopLossJpy,
        currentTakeProfitJpy = null,
        unrealizedPnlJpy = "1000.00000000",
        unrealizedR = "0.500000",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "10100000.00000000",
        lowestPriceSinceEntryJpy = "10000000.00000000",
    )
}

private fun orphanStopOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000001",
        positionId = null,
        orderType = OrderType.STOP,
        side = OrderSide.SELL,
        status = OrderStatus.OPEN,
    )
}

private fun orphanTakeProfitOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000002",
        positionId = null,
        orderType = OrderType.LIMIT,
        side = OrderSide.SELL,
        status = OrderStatus.OPEN,
    )
}

private fun pendingCancelOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000003",
        positionId = null,
        orderType = OrderType.LIMIT,
        side = OrderSide.BUY,
        status = OrderStatus.PENDING_CANCEL,
    )
}

private fun linkedStopOrder(): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000004",
        positionId = "00000000-0000-0000-0000-000000000001",
        tradeGroupId = "10000000-0000-0000-0000-000000000001",
        orderType = OrderType.STOP,
        side = OrderSide.SELL,
        status = OrderStatus.OPEN,
    )
}

private fun linkedStopOrder(tradeGroupId: String): Order {
    return order(
        orderId = "20000000-0000-0000-0000-000000000005",
        positionId = "00000000-0000-0000-0000-000000000005",
        tradeGroupId = tradeGroupId,
        orderType = OrderType.STOP,
        side = OrderSide.SELL,
        status = OrderStatus.OPEN,
    )
}

private fun order(
    orderId: String,
    positionId: String?,
    tradeGroupId: String? = null,
    orderType: OrderType,
    side: OrderSide,
    status: OrderStatus,
): Order {
    return Order(
        orderId = orderId,
        positionId = positionId,
        tradeGroupId = tradeGroupId,
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = side,
        orderType = orderType,
        status = status,
        sizeBtc = "0.010000000000",
        limitPriceJpy = "10200000.00000000",
        triggerPriceJpy = null,
        protectiveStopPriceJpy = null,
        takeProfitPriceJpy = null,
        reasonJa = "test",
        clientRequestId = null,
        createdAt = fixedInstant().toString(),
        updatedAt = fixedInstant().toString(),
    )
}

private fun initialBuyExecution(orderId: String): Execution {
    return Execution(
        executionId = "30000000-0000-0000-0000-000000000010",
        orderId = orderId,
        positionId = "00000000-0000-0000-0000-000000000001",
        symbol = "BTC",
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        priceJpy = "10000000.00000000",
        sizeBtc = "0.001000000000",
        feeJpy = "5.00000000",
        realizedPnlJpy = "0.00000000",
        liquidity = ExecutionLiquidity.TAKER,
        executedAt = fixedInstant().minusSeconds(119).toString(),
    )
}

private fun safetyBroker(
    repository: InMemoryPaperLedgerRepository,
    violationRepository: InMemorySafetyViolationRepository,
    decisionRepository: DecisionRepository = InMemoryDecisionRepository(fixedClock()),
    marketDataSource: MarketDataSource = FakeMarketDataSource,
): PaperBroker {
    return PaperBroker(
        ledgerRepository = repository,
        riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
        decisionRepository = decisionRepository,
        safetyViolationRepository = violationRepository,
        marketDataSource = marketDataSource,
        clock = fixedClock(),
    )
}

/**
 * place_order ledger 書き込みだけ失敗させる repository。
 */
private class FailingPlaceOrderLedgerRepository(
    private val delegate: PaperLedgerRepository = InMemoryPaperLedgerRepository(),
) : PaperLedgerRepository by delegate {

    override suspend fun fillMarketEntry(request: MarketEntryFillRequest): Result<PaperTradeResult> {
        return Result.failure(IllegalStateException("ledger write failed"))
    }

    override suspend fun createRestingEntryOrder(request: RestingEntryOrderRequest): Result<PaperTradeResult> {
        return Result.failure(IllegalStateException("ledger write failed"))
    }
}

private suspend fun approvedCommand(repository: DecisionRepository, command: PlaceOrderCommand): PlaceOrderCommand {
    val intentId = submitIntent(repository, command)

    repository.submitFalsification(
        FalsificationSubmission(
            intentId = intentId,
            verdict = FalsificationVerdict.APPROVED,
            llmProvider = "test-falsifier",
            reasonJa = "test approved",
        ),
    ).getOrThrow()

    return command.copy(intentId = intentId)
}

private suspend fun intentCommand(repository: DecisionRepository, command: PlaceOrderCommand): PlaceOrderCommand {
    val intentId = submitIntent(repository, command)

    return command.copy(intentId = intentId)
}

private suspend fun submitIntent(repository: DecisionRepository, command: PlaceOrderCommand): UUID {
    val decisionResult = repository.submitDecision(decisionSubmission(command)).getOrThrow()

    return requireNotNull(decisionResult.tradeIntent?.intentId)
}

private fun decisionSubmission(command: PlaceOrderCommand): DecisionSubmission {
    return DecisionSubmission(
        invocationId = "test-invocation",
        llmProvider = "test-proposer",
        promptHash = "test-prompt-hash",
        systemPromptVersion = "system-prompt-v1",
        marketSnapshotId = "test-market-snapshot",
        action = DecisionAction.ENTER,
        setupTags = listOf("test-setup"),
        estimatedWinProbability = command.estimatedWinProbability,
        expectedRMultiple = BigDecimal("2.0"),
        roundTripCostR = BigDecimal("0.1"),
        toolEvidenceIds = listOf("tool-1"),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = "test decision",
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = EntryIntentDraft(
            symbol = command.symbol,
            side = command.side,
            orderType = command.orderType,
            sizeBtc = command.sizeBtc,
            priceJpy = command.priceJpy,
            protectiveStopPriceJpy = command.protectiveStopPriceJpy,
            takeProfitPriceJpy = command.takeProfitPriceJpy,
        ),
        tradePlan = TradePlanDraft(
            parentTradePlanId = null,
            revisionCount = 0,
            symbol = command.symbol,
            thesisJa = "test thesis",
            invalidationConditionsJa = listOf("test invalidation"),
            targetPriceJpy = command.takeProfitPriceJpy,
            timeStopAt = null,
            setupTags = listOf("test-setup"),
        ),
    )
}

private fun atrCandles(
    symbol: TradingSymbol,
    interval: CandleInterval,
    limit: Int,
): List<Candle> {
    return (0 until limit).map { index ->
        Candle(
            symbol = symbol.apiSymbol,
            interval = interval,
            openTime = fixedInstant().plusSeconds(index.toLong() * 300).toString(),
            open = "10000000",
            high = "10002000",
            low = "9998000",
            close = "10000000",
            volume = "1.0",
        )
    }
}

/**
 * test 用固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

private fun inMemoryPaperTradeEvent(
    sessionId: UUID,
    sequence: Long,
    side: OrderSide,
    sizeBtc: String,
): PaperMarketTradeEvent {
    val receivedAt = fixedInstant().plusSeconds(sequence)

    return PaperMarketTradeEvent(
        symbol = TradingSymbol.BTC,
        side = side,
        priceJpy = BigDecimal("9900000"),
        sizeBtc = BigDecimal(sizeBtc),
        exchangeAt = receivedAt,
        receivedAt = receivedAt,
        connectionSessionId = sessionId,
        sequence = sequence,
    )
}

/**
 * test 用固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

private class MutablePaperTestClock(var currentInstant: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = Clock.fixed(currentInstant, zone)

    override fun instant(): Instant = currentInstant
}
