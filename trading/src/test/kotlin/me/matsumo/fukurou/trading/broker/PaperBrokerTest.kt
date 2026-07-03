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
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.safety.InMemorySafetyViolationRepository
import me.matsumo.fukurou.trading.safety.SafetyFloorRule
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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
        assertEquals(listOf(OrderType.STOP), orders.map { order -> order.orderType })
        assertEquals(1, executions.size)
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
        assertEquals(0, decisionRepository.intentConsumptions().size)
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
                protectiveStopPriceJpy = BigDecimal("9700000"),
                takeProfitPriceJpy = BigDecimal("10062000"),
                estimatedWinProbability = BigDecimal.ONE,
            ),
        )

        val result = broker.placeOrder(command).getOrThrow()

        assertFalse(result.accepted)
        assertEquals(SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO, result.safetyViolation?.rule)
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
        assertTrue(riskState.hardHalt)
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
                hardHalt = true,
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
        assertTrue(riskState.hardHalt)
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

private fun restingLimitCommand(): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        sizeBtc = BigDecimal("0.0050"),
        priceJpy = BigDecimal("9900000"),
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
        sizeBtc = BigDecimal("0.009995"),
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
 * fee 予約検証用に数量 step を細かくした fake market data。
 */
private object FineStepMarketDataSource : MarketDataSource by FakeMarketDataSource {
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

    override suspend fun fillMarketEntry(
        command: PlaceOrderCommand,
        fill: SimulatedFill,
        positionId: UUID,
        tradeGroupId: UUID,
        stopOrderId: UUID,
    ): Result<PaperTradeResult> {
        return Result.failure(IllegalStateException("ledger write failed"))
    }

    override suspend fun createRestingEntryOrder(
        command: PlaceOrderCommand,
        orderId: UUID,
        tradeGroupId: UUID,
    ): Result<PaperTradeResult> {
        return Result.failure(IllegalStateException("ledger write failed"))
    }
}

private suspend fun approvedCommand(
    repository: DecisionRepository,
    command: PlaceOrderCommand,
): PlaceOrderCommand {
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

private suspend fun intentCommand(
    repository: DecisionRepository,
    command: PlaceOrderCommand,
): PlaceOrderCommand {
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

/**
 * test 用固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

/**
 * test 用固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
