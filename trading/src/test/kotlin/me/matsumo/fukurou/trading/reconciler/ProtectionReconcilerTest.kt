package me.matsumo.fukurou.trading.reconciler

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.PaperBroker
import me.matsumo.fukurou.trading.broker.PaperReconcileResult
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.config.KillCriterionConfig
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
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
import me.matsumo.fukurou.trading.domain.PaperOrderCancelReason
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotReason
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecord
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecorder
import me.matsumo.fukurou.trading.evaluation.KillCriterionEvaluator
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.lock.TradingLockLease
import me.matsumo.fukurou.trading.market.GmoRateLimitException
import me.matsumo.fukurou.trading.market.GmoRequestAuditException
import me.matsumo.fukurou.trading.market.InvalidMarketDataMessageException
import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.market.MarketDataGapReason
import me.matsumo.fukurou.trading.market.MarketDataIntegrityRepository
import me.matsumo.fukurou.trading.market.MarketDataIntegritySnapshot
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.market.MarketEventSession
import me.matsumo.fukurou.trading.market.MarketEventSessionSignal
import me.matsumo.fukurou.trading.market.MarketEventStream
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import me.matsumo.fukurou.trading.risk.InMemoryAccountStateBoundary
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.safety.MaxDrawdownPolicy
import me.matsumo.fukurou.trading.safety.SafetyFloor
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * ProtectionReconciler の startup full pass と loop contract を検証するテスト。
 */
class ProtectionReconcilerTest {

    @Test
    fun startup_full_pass_takes_global_lock_and_updates_status() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val lock = CountingTradingLock(fixedClock())
        val status = MutableReconcilerStatus()
        val reconciler = createReconciler(
            eventLog = eventLog,
            lock = lock,
            status = status,
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.STARTUP_FULL)
        val completedPayload = eventLog.events().single().payload

        assertTrue(result.isSuccess)
        assertEquals(1, lock.acquisitionCount)
        assertEquals(fixedInstant(), status.snapshot().lastMaintenanceAt)
        assertTrue(status.snapshot().startupFullReconcileCompleted)
        assertEquals(fixedInstant(), status.snapshot().lastMaintenanceAt)
        assertFalse(completedPayload.contains("lastReconciledAt"))
        assertTrue(eventLog.events().any { event -> event.eventType == CommandEventType.RECONCILER_PASS_COMPLETED })
    }

    @Test
    fun run_loop_performs_startup_full_pass_before_loop_passes() = runBlocking {
        val lock = CountingTradingLock(fixedClock())
        val status = MutableReconcilerStatus()
        val reconciler = createReconciler(
            lock = lock,
            status = status,
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
            while (lock.acquisitionCount < 2) {
                delay(10.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertTrue(status.snapshot().startupFullReconcileCompleted)
        assertTrue(lock.acquisitionCount >= 2)
    }

    @Test
    fun run_loop_emits_started_event_once() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val status = MutableReconcilerStatus()
        val reconciler = createReconciler(
            eventLog = eventLog,
            status = status,
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
            while (status.snapshot().lastMaintenanceAt == null) {
                delay(10.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        val eventTypes = eventLog.events().map { event -> event.eventType }

        assertEquals(CommandEventType.RECONCILER_STARTED, eventTypes.first())
        assertEquals(1, eventTypes.count { eventType -> eventType == CommandEventType.RECONCILER_STARTED })
    }

    @Test
    fun steady_loop_success_appends_completed_events_for_persistent_freshness() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val reconciler = createReconciler(eventLog = eventLog)

        reconciler.reconcileOnce(ReconcilePassKind.STARTUP_FULL).getOrThrow()
        reconciler.reconcileOnce(ReconcilePassKind.LOOP).getOrThrow()
        reconciler.reconcileOnce(ReconcilePassKind.LOOP).getOrThrow()

        val eventTypes = eventLog.events().map { event -> event.eventType }

        assertEquals(
            listOf(
                CommandEventType.RECONCILER_PASS_COMPLETED,
                CommandEventType.RECONCILER_PASS_COMPLETED,
                CommandEventType.RECONCILER_PASS_COMPLETED,
            ),
            eventTypes,
        )
    }

    @Test
    fun run_loop_retries_startup_full_pass_until_success() = runBlocking {
        val riskStateRepository = FlakyRiskStateRepository(failuresBeforeSuccess = 1)
        val status = MutableReconcilerStatus()
        val reconciler = createReconciler(
            riskStateRepository = riskStateRepository,
            status = status,
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
            while (!status.snapshot().startupFullReconcileCompleted) {
                delay(10.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertTrue(riskStateRepository.currentCallCount >= 2)
        assertTrue(status.snapshot().startupFullReconcileCompleted)
    }

    @Test
    fun loop_failure_and_recovery_are_logged_only_on_transitions() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val riskStateRepository = SwitchableRiskStateRepository()
        val reconciler = createReconciler(
            eventLog = eventLog,
            riskStateRepository = riskStateRepository,
        )

        reconciler.reconcileOnce(ReconcilePassKind.STARTUP_FULL).getOrThrow()

        riskStateRepository.nextResult = Result.failure(IllegalStateException("risk_state unavailable"))
        assertTrue(reconciler.reconcileOnce(ReconcilePassKind.LOOP).isFailure)
        assertTrue(reconciler.reconcileOnce(ReconcilePassKind.LOOP).isFailure)

        riskStateRepository.nextResult = null
        reconciler.reconcileOnce(ReconcilePassKind.LOOP).getOrThrow()

        val eventTypes = eventLog.events().map { event -> event.eventType }
        val failurePayload = eventLog.events().first { event ->
            event.eventType == CommandEventType.RECONCILER_PASS_FAILED
        }.payload

        assertEquals(
            listOf(
                CommandEventType.RECONCILER_PASS_COMPLETED,
                CommandEventType.RECONCILER_PASS_FAILED,
                CommandEventType.RECONCILER_PASS_COMPLETED,
                CommandEventType.RECONCILER_PASS_RECOVERED,
            ),
            eventTypes,
        )
        assertTrue(failurePayload.contains("risk_state unavailable"))
        assertFalse(failurePayload.contains("failureCategory"))
    }

    @Test
    fun tick_failure_keeps_last_maintenance_at() = runBlocking {
        val firstInstant = Instant.parse("2026-07-02T00:00:00Z")
        val secondInstant = Instant.parse("2026-07-02T00:00:05Z")
        val clock = MutableTestClock(firstInstant)
        val eventLog = InMemoryCommandEventLog()
        val status = MutableReconcilerStatus()
        val tickStream = SwitchableTickStream(Result.success(fixedTickSnapshot(firstInstant)))
        val reconciler = createReconciler(
            clock = clock,
            eventLog = eventLog,
            status = status,
            tickStream = tickStream,
        )

        reconciler.reconcileOnce(ReconcilePassKind.STARTUP_FULL).getOrThrow()

        clock.currentInstant = secondInstant
        tickStream.nextResult = Result.failure(IllegalStateException("market data unavailable"))

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)
        val snapshot = status.snapshot()
        val eventTypes = eventLog.events().map { event -> event.eventType }

        assertTrue(result.isSuccess)
        assertEquals(firstInstant, snapshot.lastMaintenanceAt)
        assertEquals(
            listOf(
                CommandEventType.RECONCILER_PASS_COMPLETED,
                CommandEventType.RECONCILER_PASS_COMPLETED,
            ),
            eventTypes,
        )
    }

    @Test
    fun failClosedTickFailure_recordsFailureTransitionWithoutAdvancingMaintenance() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val status = MutableReconcilerStatus()
        val reconciler = createReconciler(
            eventLog = eventLog,
            status = status,
            tickStream = SwitchableTickStream(
                Result.failure(GmoRateLimitException("sentinel-rate-limit-message /private/rate-limit-path")),
            ),
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)

        assertTrue(result.exceptionOrNull() is GmoRateLimitException)
        assertEquals(null, status.snapshot().lastMaintenanceAt)
        assertEquals(
            listOf(CommandEventType.RECONCILER_PASS_FAILED),
            eventLog.events().map { event -> event.eventType },
        )
        val payload = eventLog.events().single().payload

        assertTrue(payload.contains("\"failureCategory\":\"GMO_RATE_LIMITED\""))
        assertTrue(payload.contains("\"failureType\":\"GmoRateLimitException\""))
        assertFalse(payload.contains("sentinel-rate-limit-message"))
        assertFalse(payload.contains("rate-limit-path"))
    }

    @Test
    fun auditFailure_recordsSafeClassificationWithoutRawDiagnosticGraph() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val auditFailure = GmoRequestAuditException().apply {
            addSuppressed(IllegalStateException("sentinel-audit-message /private/audit-path"))
        }
        val reconciler = createReconciler(
            eventLog = eventLog,
            tickStream = SwitchableTickStream(Result.failure(auditFailure)),
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)
        val payload = eventLog.events().single().payload

        assertEquals(auditFailure, result.exceptionOrNull())
        assertTrue(payload.contains("\"failureCategory\":\"GMO_REQUEST_AUDIT_FAILED\""))
        assertTrue(payload.contains("\"failureType\":\"GmoRequestAuditException\""))
        assertFalse(payload.contains("sentinel-audit-message"))
        assertFalse(payload.contains("audit-path"))
    }

    @Test
    fun reconcile_pass_recordsDailyEquitySnapshotOncePerJstDate() = runBlocking {
        val firstInstant = Instant.parse("2026-07-02T14:59:00Z")
        val clock = MutableTestClock(firstInstant)
        val repository = InMemoryPaperLedgerRepository()
        val tickStream = SwitchableTickStream(Result.success(fixedTickSnapshot(firstInstant)))
        val recorder = EquitySnapshotRecorder(
            accountSource = { repository.getAccountSnapshot() },
            repository = repository.equitySnapshotRepository,
            clock = clock,
            idGenerator = deterministicSnapshotIds(),
        )
        val reconciler = createReconciler(
            clock = clock,
            tickStream = tickStream,
            equitySnapshotRecorder = recorder,
        )

        reconciler.reconcileOnce(ReconcilePassKind.LOOP).getOrThrow()
        clock.currentInstant = firstInstant.plusSeconds(30)
        tickStream.nextResult = Result.success(fixedTickSnapshot(clock.currentInstant))
        reconciler.reconcileOnce(ReconcilePassKind.LOOP).getOrThrow()
        clock.currentInstant = firstInstant.plusSeconds(90)
        tickStream.nextResult = Result.success(fixedTickSnapshot(clock.currentInstant))
        reconciler.reconcileOnce(ReconcilePassKind.LOOP).getOrThrow()

        val dailySnapshots = repository.equitySnapshotRepository.findAll().getOrThrow()
            .filter { snapshot -> snapshot.reason == EquitySnapshotReason.DAILY }

        assertEquals(
            listOf(LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3)),
            dailySnapshots.map { snapshot -> snapshot.tradingDate },
        )
    }

    @Test
    fun reconcile_pass_recordsDailyEquitySnapshotWhenMarketDataFails() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-07-02T15:00:00Z"))
        val repository = InMemoryPaperLedgerRepository()
        val recorder = EquitySnapshotRecorder(
            accountSource = { repository.getAccountSnapshot() },
            repository = repository.equitySnapshotRepository,
            clock = clock,
            idGenerator = deterministicSnapshotIds(),
        )
        val reconciler = createReconciler(
            clock = clock,
            tickStream = SwitchableTickStream(Result.failure(IllegalStateException("market data unavailable"))),
            equitySnapshotRecorder = recorder,
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)
        val dailySnapshots = repository.equitySnapshotRepository.findAll().getOrThrow()
            .filter { snapshot -> snapshot.reason == EquitySnapshotReason.DAILY }
        val accountSnapshot = repository.getAccountSnapshot().getOrThrow()

        assertTrue(result.isSuccess)
        assertEquals(listOf(LocalDate.of(2026, 7, 3)), dailySnapshots.map { snapshot -> snapshot.tradingDate })
        assertEquitySnapshotMatchesAccount(dailySnapshots.single(), accountSnapshot)
    }

    @Test
    fun killStatsFailureStillCompletesPassAndRecordsDailyEquity() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-07-02T15:00:00Z"))
        val repository = InMemoryPaperLedgerRepository()
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val eventLog = InMemoryCommandEventLog()
        val decisionRepository = InMemoryDecisionRepository(clock)
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = clock,
        )
        val evaluator = KillCriterionEvaluator(
            config = KillCriterionConfig(minClosedTrades = 2, minProfitFactor = BigDecimal("0.80")),
            riskStateRepository = riskStateRepository,
            riskStateCommandService = InMemoryRiskStateCommandService(riskStateRepository, eventLog, clock),
            commandEventLog = eventLog,
            broker = broker,
            statsSource = { Result.failure(IllegalStateException("baseline mismatch")) },
            clock = clock,
        )
        val recorder = EquitySnapshotRecorder(
            accountSource = { repository.getAccountSnapshot() },
            repository = repository.equitySnapshotRepository,
            clock = clock,
            idGenerator = deterministicSnapshotIds(),
        )
        val status = MutableReconcilerStatus()
        val reconciler = createReconciler(
            clock = clock,
            eventLog = eventLog,
            status = status,
            riskStateRepository = riskStateRepository,
            tickStream = SwitchableTickStream(Result.success(fixedTickSnapshot(clock.currentInstant))),
            broker = broker,
            equitySnapshotRecorder = recorder,
            killCriterionEvaluator = evaluator,
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)
        val dailySnapshots = repository.equitySnapshotRepository.findAll().getOrThrow()
            .filter { snapshot -> snapshot.reason == EquitySnapshotReason.DAILY }
        val eventTypes = eventLog.events().map { event -> event.eventType }

        assertTrue(result.isSuccess)
        assertEquals(listOf(LocalDate.of(2026, 7, 3)), dailySnapshots.map { snapshot -> snapshot.tradingDate })
        assertEquals(fixedTickSnapshot(clock.currentInstant).observedAt, status.snapshot().lastMaintenanceAt)
        assertTrue(CommandEventType.RECONCILER_PASS_COMPLETED in eventTypes)
        assertTrue(CommandEventType.RECONCILER_PASS_FAILED !in eventTypes)
    }

    @Test
    fun audit_append_failure_does_not_advance_reconciler_status() = runBlocking {
        val status = MutableReconcilerStatus()
        val reconciler = createReconciler(
            eventLog = FailingCommandEventLog,
            status = status,
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.STARTUP_FULL)
        val snapshot = status.snapshot()

        assertTrue(result.isFailure)
        assertFalse(snapshot.startupFullReconcileCompleted)
        assertEquals(null, snapshot.lastMaintenanceAt)
    }

    @Test
    fun reconcile_pass_triggers_protective_stop_through_broker() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = reconcilerEntryCommand(takeProfitPriceJpy = BigDecimal("10500000")),
            ),
        ).getOrThrow()
        val reconciler = createReconciler(
            riskStateRepository = riskStateRepository,
            broker = broker,
            tickStream = SwitchableTickStream(Result.success(stopTickSnapshot())),
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)

        assertTrue(result.isSuccess)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(2, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun inMemoryPaperLedger_appendsFillEquitySnapshotsAndSkipsMarkOnlyUpdates() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = fixedClock(),
        )

        broker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = reconcilerEntryCommand(takeProfitPriceJpy = BigDecimal("10500000")),
            ),
        ).getOrThrow()
        val fillCountAfterBuy = repository.fillEquitySnapshotCount()
        broker.reconcile(neutralBtcTickSnapshot()).getOrThrow()
        val fillCountAfterMarkOnly = repository.fillEquitySnapshotCount()
        broker.reconcile(stopTickSnapshot()).getOrThrow()
        val fillCountAfterSell = repository.fillEquitySnapshotCount()

        assertEquals(1, fillCountAfterBuy)
        assertEquals(1, fillCountAfterMarkOnly)
        assertEquals(2, fillCountAfterSell)
    }

    @Test
    fun reconcile_pass_triggers_virtual_take_profit_and_cancels_stop() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = reconcilerEntryCommand(takeProfitPriceJpy = BigDecimal("10100000")),
            ),
        ).getOrThrow()
        val reconciler = createReconciler(
            riskStateRepository = riskStateRepository,
            broker = broker,
            tickStream = SwitchableTickStream(Result.success(takeProfitTickSnapshot())),
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)

        assertTrue(result.isSuccess)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
        assertEquals(2, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun reconcile_pass_records_limit_fak_divergence_memo_in_completed_audit_payload() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(clock = fixedClock())
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val eventLog = InMemoryCommandEventLog()
        val marketDataSource = MutableReconcilerOrderbookMarketDataSource(
            orderbook = reconcilerOrderbookWithAsk("10010000"),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = marketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = restingReconcilerEntryCommand(),
            ),
        ).getOrThrow()
        marketDataSource.orderbook = reconcilerOrderbookWithAsk(price = "10000000", size = "0.0020")
        val reconciler = createReconciler(
            eventLog = eventLog,
            riskStateRepository = riskStateRepository,
            broker = broker,
            tickStream = SwitchableTickStream(Result.success(limitReachTickSnapshot())),
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)
        val payload = eventLog.events()
            .last { event -> event.eventType == CommandEventType.RECONCILER_PASS_COMPLETED }
            .payload

        assertTrue(result.isSuccess)
        assertTrue(payload.contains(""""paperExecutionDivergenceMemos""""))
        assertTrue(payload.contains(""""hypotheticalRemainingSizeBtc":"0.003000000000""""))
        assertTrue(payload.contains(""""orderId""""))
    }

    @Test
    fun residentReconcilerExpiresRestingEntryWithoutLlmRunnerAndBeforeFill() = runBlocking {
        val clock = MutableTestClock(fixedInstant())
        val repository = InMemoryPaperLedgerRepository(clock = clock)
        val riskStateRepository = InMemoryRiskStateRepository(clock = clock)
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val marketDataSource = MutableReconcilerOrderbookMarketDataSource(
            orderbook = reconcilerOrderbookWithAsk("10010000"),
        )
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            restingEntryOrderTtl = Duration.ofSeconds(30),
            marketDataSource = marketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(
            approvedReconcilerEntryCommand(decisionRepository, restingReconcilerEntryCommand()),
        ).getOrThrow()
        val openOrder = broker.getOpenOrders().getOrThrow().single()
        val tradeGroupId = UUID.fromString(requireNotNull(openOrder.tradeGroupId))
        marketDataSource.orderbook = reconcilerOrderbookWithAsk("9990000")
        val expiryTick = limitReachTickSnapshot().copy(observedAt = fixedInstant().plusSeconds(30))
        clock.currentInstant = fixedInstant().plusSeconds(30)
        val reconciler = createReconciler(
            clock = clock,
            riskStateRepository = riskStateRepository,
            broker = broker,
            tickStream = SwitchableTickStream(Result.success(expiryTick)),
        )

        reconciler.reconcileOnce(ReconcilePassKind.LOOP).getOrThrow()
        val expiredOrder = repository.findOrdersByTradeGroupId(tradeGroupId).getOrThrow().single()

        assertEquals(OrderStatus.CANCELED, expiredOrder.status)
        assertEquals(PaperOrderCancelReason.TTL_EXPIRY, expiredOrder.cancelReason)
        assertTrue(repository.getExecutions().getOrThrow().isEmpty())
    }

    @Test
    fun reconcile_pass_sweeps_existing_hard_halt_without_trade_tool_guard() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = reconcilerEntryCommand(takeProfitPriceJpy = BigDecimal("12000000")),
            ),
        ).getOrThrow()
        riskStateRepository.setHardHalt("test hard halt", fixedInstant()).getOrThrow()
        val reconciler = createReconciler(
            riskStateRepository = riskStateRepository,
            broker = broker,
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)
        val riskState = riskStateRepository.current().getOrThrow()

        assertTrue(result.isSuccess)
        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
        assertEquals(2, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun reconcile_pass_does_not_sweep_soft_halt() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = reconcilerEntryCommand(takeProfitPriceJpy = BigDecimal("12000000")),
            ),
        ).getOrThrow()
        riskStateRepository.setSoftHalt("operator pause", fixedInstant()).getOrThrow()
        val reconciler = createReconciler(
            riskStateRepository = riskStateRepository,
            broker = broker,
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)

        assertTrue(result.isSuccess)
        assertEquals(1, broker.getPositions().getOrThrow().size)
        assertEquals(1, broker.getOpenOrders().getOrThrow().size)
        assertEquals(1, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun reconcile_pass_cancels_open_entry_before_fill_when_hard_halt() = runBlocking {
        val repository = InMemoryPaperLedgerRepository()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = restingReconcilerEntryCommand(),
            ),
        ).getOrThrow()
        riskStateRepository.setHardHalt("test hard halt", fixedInstant()).getOrThrow()
        val reconciler = createReconciler(
            riskStateRepository = riskStateRepository,
            broker = broker,
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)

        assertTrue(result.isSuccess)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
        assertEquals(0, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun reconcile_pass_cancels_open_entry_before_fill_when_tick_reaches_drawdown_halt() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(
            accountSnapshot = AccountSnapshot(
                mode = TradingMode.PAPER,
                cashJpy = "100000.00000000",
                initialCashJpy = "100000.00000000",
                btcQuantity = "0.000000000000",
                btcMarkPriceJpy = "0.00000000",
                totalEquityJpy = "100000.00000000",
                equityPeakJpy = "100000.00000000",
                drawdownRatio = "0.0000000000",
            ),
        )
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = PaperBroker(
            ledgerRepository = repository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = fixedClock(),
        )
        broker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = reconcilerEntryCommand(takeProfitPriceJpy = BigDecimal("12000000")),
            ),
        ).getOrThrow()
        broker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = restingReconcilerEntryCommand(sizeBtc = BigDecimal("0.0010")),
            ),
        ).getOrThrow()
        val reconciler = createReconciler(
            riskStateRepository = riskStateRepository,
            broker = broker,
            tickStream = SwitchableTickStream(Result.success(drawdownHaltTickSnapshot())),
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)
        val riskState = riskStateRepository.current().getOrThrow()

        assertTrue(result.isSuccess)
        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
        assertEquals(2, repository.getExecutions().getOrThrow().size)
    }

    @Test
    fun reconcile_pass_uses_non_default_policy_for_pre_sweep() = runBlocking {
        val policy = MaxDrawdownPolicy(BigDecimal("-0.10"))
        val riskStateRepository = InMemoryRiskStateRepository(
            clock = fixedClock(),
            initialState = RiskState(
                drawdownRatio = BigDecimal("-0.1200000000"),
                equityPeak = BigDecimal("100000"),
                updatedAt = fixedInstant(),
            ),
        )
        val repository = InMemoryPaperLedgerRepository(maxDrawdownPolicy = policy)
        val broker = RecordingBroker(
            delegate = PaperBroker(
                ledgerRepository = repository,
                riskStateRepository = riskStateRepository,
                safetyFloor = SafetyFloor(
                    config = SafetyFloorConfig(maxDrawdownRatio = BigDecimal("-0.10")),
                    clock = fixedClock(),
                    maxDrawdownPolicy = policy,
                ),
                maxDrawdownPolicy = policy,
                marketDataSource = ReconcilerFakeMarketDataSource,
                clock = fixedClock(),
            ),
        )
        val reconciler = createReconciler(
            riskStateRepository = riskStateRepository,
            broker = broker,
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
            maxDrawdownPolicy = policy,
        )

        val result = reconciler.reconcileOnce(ReconcilePassKind.LOOP)

        assertTrue(result.isSuccess)
        assertEquals(RiskHaltState.HARD_HALT, riskStateRepository.current().getOrThrow().state)
        assertEquals(listOf<TickSnapshot?>(null), broker.sweepCallTicks)
        assertTrue(broker.sweptTicks.isEmpty())
    }

    @Test
    fun default_equal_drawdown_rollback_resumes_sweep_without_entry_or_duplicate_execution() = runBlocking {
        val policy = MaxDrawdownPolicy()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val seedRepository = InMemoryPaperLedgerRepository(maxDrawdownPolicy = policy)
        val seedBroker = PaperBroker(
            ledgerRepository = seedRepository,
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            maxDrawdownPolicy = policy,
            clock = fixedClock(),
        )
        seedBroker.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = restingReconcilerEntryCommand(),
            ),
        ).getOrThrow()
        val restingEntry = seedBroker.getOpenOrders().getOrThrow().single()
        val rollbackRepository = InMemoryPaperLedgerRepository(
            accountSnapshot = AccountSnapshot(
                mode = TradingMode.PAPER,
                cashJpy = "85000.00000000",
                initialCashJpy = "100000.00000000",
                btcQuantity = "0.000000000000",
                btcMarkPriceJpy = "0.00000000",
                totalEquityJpy = "85000.00000000",
                equityPeakJpy = "100000.00000000",
                drawdownRatio = "-0.1500000000",
            ),
            openOrders = listOf(restingEntry),
            maxDrawdownPolicy = policy,
        )
        val riskStateRepository = InMemoryRiskStateRepository(
            clock = fixedClock(),
            initialState = RiskState(
                state = RiskHaltState.HARD_HALT,
                drawdownRatio = BigDecimal("-0.1500000000"),
                equityPeak = BigDecimal("100000"),
                haltReason = "max drawdown reached before crash",
                haltAt = fixedInstant(),
                updatedAt = fixedInstant(),
            ),
        )
        val rollbackBroker = PaperBroker(
            ledgerRepository = rollbackRepository,
            riskStateRepository = riskStateRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            maxDrawdownPolicy = policy,
            clock = fixedClock(),
        )
        val reconciler = createReconciler(
            riskStateRepository = riskStateRepository,
            broker = rollbackBroker,
            tickStream = SwitchableTickStream(Result.success(drawdownHaltTickSnapshot())),
            maxDrawdownPolicy = policy,
        )

        reconciler.reconcileOnce(ReconcilePassKind.LOOP).getOrThrow()
        reconciler.reconcileOnce(ReconcilePassKind.LOOP).getOrThrow()

        assertTrue(rollbackBroker.getOpenOrders().getOrThrow().isEmpty())
        assertTrue(rollbackRepository.getExecutions().getOrThrow().isEmpty())
        assertEquals(RiskHaltState.HARD_HALT, riskStateRepository.current().getOrThrow().state)
    }

    @Test
    fun market_event_loop_applies_event_and_retries_gap_impact_under_global_lock() = runBlocking {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000163")
        val event = PaperMarketTradeEvent(
            symbol = TradingSymbol.BTC,
            side = OrderSide.SELL,
            priceJpy = BigDecimal("10000000"),
            sizeBtc = BigDecimal("0.0010"),
            exchangeAt = fixedInstant(),
            receivedAt = fixedInstant(),
            connectionSessionId = sessionId,
            sequence = 1,
        )
        val stream = SingleSessionMarketEventStream(
            session = ScriptedMarketEventSession(
                sessionId = sessionId,
                connectedAt = fixedInstant(),
                results = listOf(
                    Result.success(event),
                    Result.failure(IllegalStateException("socket closed")),
                ),
            ),
        )
        val integrity = RetryableMarketDataIntegrityRepository(failMarkDisconnectedTimes = 1)
        val lock = CountingTradingLock(fixedClock())
        val broker = RecordingBroker(
            PaperBroker(
                ledgerRepository = InMemoryPaperLedgerRepository(),
                riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                decisionRepository = InMemoryDecisionRepository(fixedClock()),
                marketDataSource = ReconcilerFakeMarketDataSource,
                clock = fixedClock(),
            ),
        )
        val status = MutableReconcilerStatus()
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = lock,
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
            marketEventStream = stream,
            marketDataIntegrityRepository = integrity,
            broker = broker,
            status = status,
            clock = fixedClock(),
        )

        val job = launch {
            reconciler.runLoop(Duration.ofMillis(1))
        }

        withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
            while (integrity.applyGapImpactCount == 0) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertEquals(listOf(event), broker.appliedEvents)
        assertEquals(2, integrity.markDisconnectedCount)
        assertEquals(1, integrity.applyGapImpactCount)
        assertEquals(3, integrity.snapshotCount)
        assertTrue(lock.owners.count { owner -> owner == "paper-market-event" } >= 2)
        assertEquals(MarketDataConnectionState.DISCONNECTED, status.snapshot().marketDataState)
    }

    @Test
    fun marketEventPersistenceContentionRetriesSameEventWithoutCreatingGap() = runBlocking {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000187")
        val event = PaperMarketTradeEvent(
            symbol = TradingSymbol.BTC,
            side = OrderSide.SELL,
            priceJpy = BigDecimal("10000000"),
            sizeBtc = BigDecimal("0.0010"),
            exchangeAt = fixedInstant(),
            receivedAt = fixedInstant(),
            connectionSessionId = sessionId,
            sequence = 1,
        )
        val delegate = PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = InMemoryDecisionRepository(fixedClock()),
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = fixedClock(),
        )
        val broker = ContentionThenRecordingBroker(delegate)
        val integrity = RetryableMarketDataIntegrityRepository(0)
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = CountingTradingLock(fixedClock()),
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
            marketEventStream = SingleSessionMarketEventStream(
                BurstThenIdleMarketEventSession(sessionId, fixedInstant(), listOf(event)),
            ),
            marketDataIntegrityRepository = integrity,
            broker = broker,
            clock = fixedClock(),
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(1_000.toDuration(DurationUnit.MILLISECONDS)) {
            while (broker.attemptCount < 2) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertEquals(2, broker.attemptCount)
        assertEquals(listOf(event), broker.appliedEvents)
        assertEquals(0, integrity.markDisconnectedCount)
        assertEquals(0, integrity.applyGapImpactCount)
    }

    @Test
    fun market_event_hard_halt_sweeps_with_same_event_snapshot() = runBlocking {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000181")
        val event = PaperMarketTradeEvent(
            symbol = TradingSymbol.BTC,
            side = OrderSide.SELL,
            priceJpy = BigDecimal("8800000"),
            sizeBtc = BigDecimal("0.0010"),
            exchangeAt = fixedInstant(),
            receivedAt = fixedInstant().plusSeconds(1),
            connectionSessionId = sessionId,
            sequence = 1,
        )
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val broker = RecordingBroker(
            delegate = PaperBroker(
                ledgerRepository = InMemoryPaperLedgerRepository(),
                riskStateRepository = riskStateRepository,
                decisionRepository = InMemoryDecisionRepository(fixedClock()),
                marketDataSource = ReconcilerFakeMarketDataSource,
                clock = fixedClock(),
            ),
            afterApply = {
                riskStateRepository.setHardHalt("event threshold reached", event.receivedAt).getOrThrow()
            },
            rejectNullSweep = true,
        )
        val reconciler = ProtectionReconciler(
            riskStateRepository = riskStateRepository,
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = CountingTradingLock(fixedClock()),
            marketEventStream = SingleSessionMarketEventStream(
                BurstThenIdleMarketEventSession(sessionId, fixedInstant(), listOf(event)),
            ),
            marketDataIntegrityRepository = RetryableMarketDataIntegrityRepository(0),
            broker = broker,
            clock = fixedClock(),
        )
        val job = launch {
            reconciler.runLoop(Duration.ofSeconds(1))
        }

        withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
            while (broker.sweptTicks.isEmpty()) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        val sweptTick = broker.sweptTicks.single()
        assertEquals(listOf(event), broker.appliedEvents)
        assertEquals(event.symbol.apiSymbol, sweptTick.symbol)
        assertEquals(event.receivedAt, sweptTick.observedAt)
        assertEquals(event.priceJpy.toPlainString(), sweptTick.lastPrice)
        assertEquals(event.priceJpy.toPlainString(), sweptTick.bidPrice)
        assertEquals(event.priceJpy.toPlainString(), sweptTick.askPrice)
    }

    @Test
    fun websocket_hard_halt_cleanup_retriesFromStartupDuringPeriodicMaintenance() = runBlocking {
        val boundary = InMemoryAccountStateBoundary()
        val ledgerRepository = InMemoryPaperLedgerRepository(accountStateBoundary = boundary)
        val riskStateRepository = InMemoryRiskStateRepository(
            clock = fixedClock(),
            accountStateBoundary = boundary,
        )
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val delegate = PaperBroker(
            ledgerRepository = ledgerRepository,
            riskStateRepository = riskStateRepository,
            decisionRepository = decisionRepository,
            marketDataSource = ReconcilerFakeMarketDataSource,
            clock = fixedClock(),
        )
        delegate.placeOrder(
            approvedReconcilerEntryCommand(
                repository = decisionRepository,
                command = reconcilerEntryCommand(takeProfitPriceJpy = BigDecimal("12000000")),
            ),
        ).getOrThrow()
        riskStateRepository.setHardHalt("startup retry hard halt", fixedInstant()).getOrThrow()
        val broker = RejectFirstExecutableSweepBroker(delegate)
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000189")
        val reconciler = ProtectionReconciler(
            riskStateRepository = riskStateRepository,
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = CountingTradingLock(fixedClock()),
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
            marketEventStream = SingleSessionMarketEventStream(
                session = BurstThenIdleMarketEventSession(sessionId, fixedInstant(), emptyList()),
                transportLivenessTimeout = Duration.ofSeconds(1),
            ),
            marketDataIntegrityRepository = RetryableMarketDataIntegrityRepository(0),
            broker = broker,
            clock = fixedClock(),
        )
        val job = launch { reconciler.runLoop(Duration.ofMillis(10)) }

        withTimeout(1_000.toDuration(DurationUnit.MILLISECONDS)) {
            while (ledgerRepository.getOpenPositions().getOrThrow().isNotEmpty()) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertTrue(broker.sweepCallTicks.first() == null)
        assertTrue(broker.sweepCallTicks.count { tick -> tick == null } >= 2)
        assertTrue(broker.executableSweepAttemptCount >= 2)
        assertEquals(2, ledgerRepository.getExecutions().getOrThrow().size)
    }

    @Test
    fun websocket_connect_failure_retries_hard_halt_cleanup_evenAfterSafeEvidence() = runBlocking {
        val boundary = InMemoryAccountStateBoundary()
        val riskStateRepository = InMemoryRiskStateRepository(
            clock = fixedClock(),
            accountStateBoundary = boundary,
        )
        riskStateRepository.setHardHalt("connect failure hard halt", fixedInstant()).getOrThrow()
        val broker = RecordingBroker(
            PaperBroker(
                ledgerRepository = InMemoryPaperLedgerRepository(accountStateBoundary = boundary),
                riskStateRepository = riskStateRepository,
                decisionRepository = InMemoryDecisionRepository(fixedClock()),
                marketDataSource = ReconcilerFakeMarketDataSource,
                clock = fixedClock(),
            ),
        )
        val stream = AlwaysFailingMarketEventStream()
        val reconciler = ProtectionReconciler(
            riskStateRepository = riskStateRepository,
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = CountingTradingLock(fixedClock()),
            marketEventStream = stream,
            marketDataIntegrityRepository = RetryableMarketDataIntegrityRepository(0),
            broker = broker,
            clock = fixedClock(),
        )
        val job = launch { reconciler.runLoop(Duration.ofMillis(10)) }

        withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
            while (broker.sweepCallTicks.size < 2) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertTrue(stream.connectCount >= 1)
        assertEquals(listOf(null, null), broker.sweepCallTicks.take(2))
    }

    @Test
    fun market_event_invalid_message_is_recorded_with_invalid_message_reason() = runBlocking {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000180")
        val integrity = RetryableMarketDataIntegrityRepository(0)
        val status = MutableReconcilerStatus()
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = CountingTradingLock(fixedClock()),
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
            marketEventStream = SingleSessionMarketEventStream(
                ScriptedMarketEventSession(
                    sessionId = sessionId,
                    connectedAt = fixedInstant(),
                    results = listOf(Result.failure(InvalidMarketDataMessageException("invalid symbol"))),
                ),
            ),
            marketDataIntegrityRepository = integrity,
            status = status,
            clock = fixedClock(),
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
            while (integrity.applyGapImpactCount == 0) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertEquals(MarketDataGapReason.INVALID_MESSAGE, status.snapshot().gapReason)
    }

    @Test
    fun market_event_burst_rate_limits_periodic_rest_maintenance() = runBlocking {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000173")
        val events = (1..100).map { sequence ->
            PaperMarketTradeEvent(
                symbol = TradingSymbol.BTC,
                side = OrderSide.SELL,
                priceJpy = BigDecimal("10000000"),
                sizeBtc = BigDecimal("0.0010"),
                exchangeAt = fixedInstant(),
                receivedAt = fixedInstant(),
                connectionSessionId = sessionId,
                sequence = sequence.toLong(),
            )
        }
        val broker = RecordingBroker(
            PaperBroker(
                ledgerRepository = InMemoryPaperLedgerRepository(),
                riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                decisionRepository = InMemoryDecisionRepository(fixedClock()),
                marketDataSource = ReconcilerFakeMarketDataSource,
                clock = fixedClock(),
            ),
        )
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = CountingTradingLock(fixedClock()),
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
            marketEventStream = SingleSessionMarketEventStream(
                BurstThenIdleMarketEventSession(sessionId, fixedInstant(), events),
            ),
            marketDataIntegrityRepository = RetryableMarketDataIntegrityRepository(0),
            broker = broker,
            clock = fixedClock(),
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(50))
        }

        withTimeout(1_000.toDuration(DurationUnit.MILLISECONDS)) {
            while (broker.appliedEvents.size < events.size || broker.maintenanceCount == 0) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertEquals(events.size, broker.appliedEvents.size)
        assertEquals(1, broker.maintenanceCount)
    }

    @Test
    fun market_event_idle_session_continues_periodic_rest_maintenance() = runBlocking {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000174")
        val broker = RecordingBroker(
            PaperBroker(
                ledgerRepository = InMemoryPaperLedgerRepository(),
                riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
                decisionRepository = InMemoryDecisionRepository(fixedClock()),
                marketDataSource = ReconcilerFakeMarketDataSource,
                clock = fixedClock(),
            ),
        )
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = CountingTradingLock(fixedClock()),
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
            marketEventStream = SingleSessionMarketEventStream(
                BurstThenIdleMarketEventSession(sessionId, fixedInstant(), emptyList()),
            ),
            marketDataIntegrityRepository = RetryableMarketDataIntegrityRepository(0),
            broker = broker,
            clock = fixedClock(),
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(1_000.toDuration(DurationUnit.MILLISECONDS)) {
            while (broker.maintenanceCount < 2) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertTrue(broker.appliedEvents.isEmpty())
        assertTrue(broker.maintenanceCount >= 2)
    }

    @Test
    fun market_data_status_projection_failure_does_not_record_maintenance_failure() = runBlocking {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000182")
        val eventLog = InMemoryCommandEventLog()
        val integrity = RetryableMarketDataIntegrityRepository(
            failMarkDisconnectedTimes = 0,
            failSnapshotCalls = setOf(2),
        )
        val status = MutableReconcilerStatus()
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            commandEventLog = eventLog,
            tradingLock = CountingTradingLock(fixedClock()),
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
            marketEventStream = SingleSessionMarketEventStream(
                BurstThenIdleMarketEventSession(sessionId, fixedInstant(), emptyList()),
            ),
            marketDataIntegrityRepository = integrity,
            status = status,
            clock = fixedClock(),
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(1_000.toDuration(DurationUnit.MILLISECONDS)) {
            while (integrity.markMaintenanceSucceededCount < 2) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertFalse(eventLog.events().any { event -> event.eventType == CommandEventType.RECONCILER_PASS_FAILED })
        assertTrue(integrity.snapshotCount >= 3)
        assertEquals(fixedInstant(), status.snapshot().lastMaintenanceAt)
    }

    @Test
    fun marketEventPeriodicAuditFailure_recordsFailureWithoutMaintenanceSuccess() = runBlocking {
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000183")
        val eventLog = InMemoryCommandEventLog()
        val integrity = RetryableMarketDataIntegrityRepository(0)
        val status = MutableReconcilerStatus()
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            commandEventLog = eventLog,
            tradingLock = CountingTradingLock(fixedClock()),
            tickStream = SwitchableTickStream(Result.failure(GmoRequestAuditException())),
            marketEventStream = SingleSessionMarketEventStream(
                BurstThenIdleMarketEventSession(sessionId, fixedInstant(), emptyList()),
            ),
            marketDataIntegrityRepository = integrity,
            status = status,
            clock = fixedClock(),
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(1_000.toDuration(DurationUnit.MILLISECONDS)) {
            while (eventLog.events().none { event -> event.eventType == CommandEventType.RECONCILER_PASS_FAILED }) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertEquals(0, integrity.markMaintenanceSucceededCount)
        assertEquals(null, integrity.snapshot().getOrThrow().lastMaintenanceAt)
        assertEquals(null, status.snapshot().lastMaintenanceAt)
        assertEquals(
            1,
            eventLog.events().count { event -> event.eventType == CommandEventType.RECONCILER_PASS_FAILED },
        )
    }

    @Test
    fun market_event_idle_session_records_transport_liveness_gap_after_configured_timeout() = runBlocking {
        val clock = Clock.systemUTC()
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000179")
        val integrity = RetryableMarketDataIntegrityRepository(0)
        val status = MutableReconcilerStatus()
        val stream = SingleSessionMarketEventStream(
            session = BurstThenIdleMarketEventSession(sessionId, clock.instant(), emptyList()),
            transportLivenessTimeout = Duration.ofMillis(50),
        )
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = clock),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = CountingTradingLock(clock),
            tickStream = SwitchableTickStream(Result.success(neutralBtcTickSnapshot())),
            marketEventStream = stream,
            marketDataIntegrityRepository = integrity,
            status = status,
            clock = clock,
        )
        val job = launch {
            reconciler.runLoop(Duration.ofMillis(10))
        }

        withTimeout(1_000.toDuration(DurationUnit.MILLISECONDS)) {
            while (integrity.applyGapImpactCount == 0 || stream.connectCount < 2) {
                delay(1.toDuration(DurationUnit.MILLISECONDS))
            }
        }
        job.cancelAndJoin()

        assertEquals(MarketDataGapReason.TRANSPORT_LIVENESS_LOST, status.snapshot().gapReason)
        assertEquals(1, integrity.applyGapImpactCount)
        assertTrue(stream.connectCount >= 2)
    }
}

private suspend fun InMemoryPaperLedgerRepository.fillEquitySnapshotCount(): Int {
    return equitySnapshotRepository.findAll().getOrThrow()
        .count { snapshot -> snapshot.reason == EquitySnapshotReason.FILL }
}

private fun assertEquitySnapshotMatchesAccount(snapshot: EquitySnapshotRecord, account: AccountSnapshot) {
    assertDecimalStringEquals("cash_jpy", account.cashJpy, snapshot.cashJpy)
    assertDecimalStringEquals("btc_quantity", account.btcQuantity, snapshot.btcQuantity)
    assertDecimalStringEquals("btc_mark_price_jpy", account.btcMarkPriceJpy, snapshot.btcMarkPriceJpy)
    assertDecimalStringEquals("total_equity_jpy", account.totalEquityJpy, snapshot.totalEquityJpy)
    assertDecimalStringEquals("equity_peak_jpy", account.equityPeakJpy, snapshot.equityPeakJpy)
    assertDecimalStringEquals("drawdown_ratio", account.drawdownRatio, snapshot.drawdownRatio)
}

private fun assertDecimalStringEquals(
    fieldName: String,
    expected: String,
    actual: BigDecimal,
) {
    assertEquals(0, actual.compareTo(expected.toBigDecimal()), "$fieldName mismatch")
}

/**
 * lock 取得回数を数える test lock。
 */
private class CountingTradingLock(
    clock: Clock,
) : TradingLock {

    private val delegate = InMemoryTradingLock(clock)

    /**
     * lock 取得回数。
     */
    var acquisitionCount: Int = 0
        private set

    val owners = mutableListOf<String>()

    override suspend fun <T> withLock(owner: String, block: suspend (TradingLockLease) -> T): T {
        return delegate.withLock(owner) { lease ->
            acquisitionCount += 1
            owners += owner

            block(lease)
        }
    }
}

/** realtime event を実 broker に渡した回数を記録する test adapter。 */
private class RecordingBroker(
    private val delegate: Broker,
    private val afterApply: suspend (PaperMarketTradeEvent) -> Unit = {},
    private val rejectNullSweep: Boolean = false,
) : Broker by delegate {
    val appliedEvents = mutableListOf<PaperMarketTradeEvent>()
    var maintenanceCount = 0
        private set
    val sweptTicks = mutableListOf<TickSnapshot>()
    val sweepCallTicks = mutableListOf<TickSnapshot?>()

    override suspend fun applyMarketEvent(event: PaperMarketTradeEvent): Result<PaperReconcileResult> {
        val result = delegate.applyMarketEvent(event)

        if (result.isSuccess) afterApply(event)
        appliedEvents += event

        return result
    }

    override suspend fun maintainProtections(tickSnapshot: TickSnapshot): Result<PaperReconcileResult> {
        return delegate.maintainProtections(tickSnapshot).also {
            maintenanceCount += 1
        }
    }

    override suspend fun sweepHardHalt(reasonJa: String, tickSnapshot: TickSnapshot?): Result<PaperTradeResult> {
        if (tickSnapshot == null && rejectNullSweep) {
            sweepCallTicks += null

            return Result.success(
                PaperTradeResult(
                    accepted = false,
                    status = OrderStatus.OPEN,
                    orderIds = emptyList(),
                    positionIds = emptyList(),
                    executionIds = emptyList(),
                    messageJa = "test readback remains incomplete",
                ),
            )
        }

        return delegate.sweepHardHalt(reasonJa, tickSnapshot).also {
            sweepCallTicks += tickSnapshot
            if (tickSnapshot != null) sweptTicks += tickSnapshot
        }
    }
}

/** 最初の executable cleanup だけ未完了にして periodic retry を観測する broker。 */
private class RejectFirstExecutableSweepBroker(
    private val delegate: Broker,
) : Broker by delegate {
    val sweepCallTicks = mutableListOf<TickSnapshot?>()
    var executableSweepAttemptCount = 0
        private set

    override suspend fun sweepHardHalt(reasonJa: String, tickSnapshot: TickSnapshot?): Result<PaperTradeResult> {
        sweepCallTicks += tickSnapshot
        if (tickSnapshot != null) {
            executableSweepAttemptCount += 1
            if (executableSweepAttemptCount == 1) {
                return Result.success(
                    PaperTradeResult(
                        accepted = false,
                        status = OrderStatus.OPEN,
                        orderIds = emptyList(),
                        positionIds = emptyList(),
                        executionIds = emptyList(),
                        messageJa = "test executable cleanup remains incomplete",
                    ),
                )
            }
        }

        return delegate.sweepHardHalt(reasonJa, tickSnapshot)
    }
}

/** 初回だけ deadlock を返し、同じ event の再試行を記録する broker。 */
private class ContentionThenRecordingBroker(
    private val delegate: Broker,
) : Broker by delegate {
    val appliedEvents = mutableListOf<PaperMarketTradeEvent>()
    var attemptCount = 0
        private set

    override suspend fun applyMarketEvent(event: PaperMarketTradeEvent): Result<PaperReconcileResult> {
        attemptCount += 1
        if (attemptCount == 1) {
            return Result.failure(SQLException("deadlock detected", "40P01"))
        }

        return delegate.applyMarketEvent(event).also { result ->
            if (result.isSuccess) appliedEvents += event
        }
    }
}

/** 1 session の event と切断を返し、その後の reconnect を失敗させる stream。 */
private class SingleSessionMarketEventStream(
    private val session: MarketEventSession,
    override val transportLivenessTimeout: Duration = Duration.ofSeconds(30),
) : MarketEventStream {
    override val reconnectBackoff: Duration = Duration.ofMillis(1)
    var connectCount = 0
        private set

    override suspend fun connect(): Result<MarketEventSession> {
        connectCount += 1

        return if (connectCount == 1) Result.success(session) else Result.failure(IllegalStateException("reconnect unavailable"))
    }
}

/** 接続失敗だけを返す WebSocket stream。 */
private class AlwaysFailingMarketEventStream : MarketEventStream {
    override val reconnectBackoff: Duration = Duration.ofMillis(1)
    override val transportLivenessTimeout: Duration = Duration.ofSeconds(1)
    var connectCount = 0
        private set

    override suspend fun connect(): Result<MarketEventSession> {
        connectCount += 1

        return Result.failure(IllegalStateException("connect unavailable"))
    }
}

/** 指定した順に event 結果を返す session。 */
private class ScriptedMarketEventSession(
    override val sessionId: UUID,
    override val connectedAt: Instant,
    private val results: List<Result<PaperMarketTradeEvent>>,
) : MarketEventSession {
    private var nextIndex = 0

    override suspend fun receive(): Result<MarketEventSessionSignal> {
        val result = results.getOrElse(nextIndex) {
            Result.failure(IllegalStateException("scripted session exhausted"))
        }
        nextIndex += 1

        return result.map { event -> MarketEventSessionSignal.Trade(event) }
    }

    override fun close() = Unit
}

/** 即時 event burst の後、timeout まで待機し続ける session。 */
private class BurstThenIdleMarketEventSession(
    override val sessionId: UUID,
    override val connectedAt: Instant,
    private val events: List<PaperMarketTradeEvent>,
) : MarketEventSession {
    private var nextIndex = 0

    override suspend fun receive(): Result<MarketEventSessionSignal> {
        val event = events.getOrNull(nextIndex)
        if (event != null) {
            nextIndex += 1

            return Result.success(MarketEventSessionSignal.Trade(event))
        }

        delay(Long.MAX_VALUE)
        error("idle market event session was resumed without cancellation")
    }

    override fun close() = Unit
}

/** 切断永続化の一時失敗を再現する integrity repository。 */
private class RetryableMarketDataIntegrityRepository(
    private var failMarkDisconnectedTimes: Int,
    private val failSnapshotCalls: Set<Int> = emptySet(),
) : MarketDataIntegrityRepository {
    private var snapshot = MarketDataIntegritySnapshot()

    var markDisconnectedCount = 0
        private set
    var applyGapImpactCount = 0
        private set
    var snapshotCount = 0
        private set
    var markMaintenanceSucceededCount = 0
        private set

    override suspend fun snapshot(): Result<MarketDataIntegritySnapshot> {
        snapshotCount += 1
        if (snapshotCount in failSnapshotCalls) {
            return Result.failure(IllegalStateException("transient status projection failure"))
        }

        return Result.success(snapshot)
    }

    override suspend fun beginSession(sessionId: UUID, connectedAt: Instant): Result<Unit> {
        snapshot = MarketDataIntegritySnapshot(
            state = MarketDataConnectionState.CONNECTED,
            sessionId = sessionId,
            startupRecoveryCompleted = true,
        )

        return Result.success(Unit)
    }

    override suspend fun markTransportActivity(sessionId: UUID, observedAt: Instant): Result<Unit> {
        snapshot = snapshot.copy(lastTransportActivityAt = observedAt)

        return Result.success(Unit)
    }

    override suspend fun markMaintenanceSucceeded(sessionId: UUID, succeededAt: Instant): Result<Unit> {
        markMaintenanceSucceededCount += 1
        snapshot = snapshot.copy(lastMaintenanceAt = succeededAt)

        return Result.success(Unit)
    }

    override suspend fun markDisconnected(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String?,
    ): Result<Unit> {
        markDisconnectedCount += 1
        if (failMarkDisconnectedTimes > 0) {
            failMarkDisconnectedTimes -= 1
            return Result.failure(IllegalStateException("transient persistence failure"))
        }

        snapshot = snapshot.copy(
            state = MarketDataConnectionState.DISCONNECTED,
            sessionId = sessionId,
            gapStartedAt = detectedAt,
            gapReason = reason,
        )

        return Result.success(Unit)
    }

    override suspend fun applyGapImpact(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
    ): Result<Unit> {
        applyGapImpactCount += 1

        return Result.success(Unit)
    }

    override suspend fun recordGap(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String?,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("not used"))

    override suspend fun recoverStaleSession(recoveredAt: Instant): Result<Unit> = Result.success(Unit)
}

/**
 * ProtectionReconciler test 用の reconciler を作る。
 */
private fun createReconciler(
    clock: Clock = fixedClock(),
    eventLog: CommandEventLog = InMemoryCommandEventLog(),
    lock: TradingLock = InMemoryTradingLock(clock),
    status: MutableReconcilerStatus = MutableReconcilerStatus(),
    riskStateRepository: RiskStateRepository = InMemoryRiskStateRepository(clock = clock),
    tickStream: TickStream = FixedTickStream,
    broker: Broker? = null,
    equitySnapshotRecorder: EquitySnapshotRecorder? = null,
    killCriterionEvaluator: KillCriterionEvaluator? = null,
    maxDrawdownPolicy: MaxDrawdownPolicy = MaxDrawdownPolicy(),
): ProtectionReconciler {
    return ProtectionReconciler(
        riskStateRepository = riskStateRepository,
        commandEventLog = eventLog,
        tradingLock = lock,
        tickStream = tickStream,
        broker = broker,
        equitySnapshotRecorder = equitySnapshotRecorder,
        killCriterionEvaluator = killCriterionEvaluator,
        status = status,
        clock = clock,
        maxDrawdownPolicy = maxDrawdownPolicy,
    )
}

private fun deterministicSnapshotIds(): () -> UUID {
    var nextId = 0L

    return {
        nextId += 1
        UUID(0L, nextId)
    }
}

/**
 * ProtectionReconciler test 用の固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

/**
 * ProtectionReconciler test 用の固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

/**
 * 指定回数だけ current() に失敗する risk_state repository。
 */
private class FlakyRiskStateRepository(
    private val failuresBeforeSuccess: Int,
) : RiskStateRepository {

    private val delegate = InMemoryRiskStateRepository(clock = fixedClock())

    /**
     * current() 呼び出し回数。
     */
    var currentCallCount: Int = 0
        private set

    override suspend fun current(): Result<RiskState> {
        currentCallCount += 1

        if (currentCallCount <= failuresBeforeSuccess) {
            return Result.failure(IllegalStateException("risk_state unavailable"))
        }

        return delegate.current()
    }

    override suspend fun setHardHalt(reason: String, at: Instant): Result<RiskState> {
        return delegate.setHardHalt(reason, at)
    }

    override suspend fun setSoftHalt(reason: String, at: Instant): Result<RiskState> {
        return delegate.setSoftHalt(reason, at)
    }

    override suspend fun resume(reason: String, at: Instant): Result<RiskState> {
        return delegate.resume(reason, at)
    }
}

/**
 * current() の結果を切り替えられる risk_state repository。
 */
private class SwitchableRiskStateRepository : RiskStateRepository {

    private val delegate = InMemoryRiskStateRepository(clock = fixedClock())

    /**
     * null の場合は delegate の現在値を返す。
     */
    var nextResult: Result<RiskState>? = null

    override suspend fun current(): Result<RiskState> {
        return nextResult ?: delegate.current()
    }

    override suspend fun setHardHalt(reason: String, at: Instant): Result<RiskState> {
        return delegate.setHardHalt(reason, at)
    }

    override suspend fun setSoftHalt(reason: String, at: Instant): Result<RiskState> {
        return delegate.setSoftHalt(reason, at)
    }

    override suspend fun resume(reason: String, at: Instant): Result<RiskState> {
        return delegate.resume(reason, at)
    }
}

/**
 * append に必ず失敗する command_event_log。
 */
private object FailingCommandEventLog : CommandEventLog {
    override suspend fun append(event: CommandEvent): Result<Unit> {
        return Result.failure(IllegalStateException("audit append failed"))
    }

    override suspend fun countDistinctLlmLaunchesSince(since: Instant, excludedInvocationId: String?): Result<Int> {
        return Result.failure(IllegalStateException("audit count failed"))
    }

    override suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
        return Result.failure(IllegalStateException("audit count failed"))
    }
}

/**
 * 固定時刻の tick を返す TickStream。
 */
private object FixedTickStream : TickStream {
    override suspend fun latestTick(): Result<TickSnapshot?> {
        return Result.success(fixedTickSnapshot())
    }
}

/**
 * 次に返す結果を切り替えられる TickStream。
 */
private class SwitchableTickStream(
    var nextResult: Result<TickSnapshot?>,
) : TickStream {
    override suspend fun latestTick(): Result<TickSnapshot?> {
        return nextResult
    }
}

/**
 * 固定時刻の tick snapshot を返す。
 */
private fun fixedTickSnapshot(observedAt: Instant = fixedInstant()): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = observedAt,
        lastPrice = "100",
        bidPrice = "99",
        askPrice = "101",
    )
}

private fun stopTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "9700000",
        bidPrice = "9690000",
        askPrice = "9700000",
        symbolRules = reconcilerSymbolRules(),
    )
}

private fun takeProfitTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "10100000",
        bidPrice = "10100000",
        askPrice = "10110000",
        symbolRules = reconcilerSymbolRules(),
    )
}

private fun neutralBtcTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "10000000",
        bidPrice = "9990000",
        askPrice = "10000000",
        symbolRules = reconcilerSymbolRules(),
    )
}

private fun limitReachTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "10050000",
        bidPrice = "10040000",
        askPrice = "10050000",
        symbolRules = reconcilerSymbolRules(),
    )
}

private fun drawdownHaltTickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "6000000",
        bidPrice = "5990000",
        askPrice = "6000000",
        symbolRules = reconcilerSymbolRules(),
    )
}

private suspend fun approvedReconcilerEntryCommand(
    repository: DecisionRepository,
    command: PlaceOrderCommand,
): PlaceOrderCommand {
    val decisionResult = repository.submitDecision(reconcilerDecisionSubmission(command)).getOrThrow()
    val intentId = requireNotNull(decisionResult.tradeIntent?.intentId)

    repository.submitFalsification(
        FalsificationSubmission(
            intentId = intentId,
            verdict = FalsificationVerdict.APPROVED,
            llmProvider = "test-falsifier",
            reasonJa = "reconciler test では反証後に承認します。",
        ),
    ).getOrThrow()

    return command.copy(intentId = intentId)
}

private fun reconcilerDecisionSubmission(command: PlaceOrderCommand): DecisionSubmission {
    return DecisionSubmission(
        invocationId = "reconciler-test",
        llmProvider = "test-proposer",
        promptHash = "prompt-hash",
        systemPromptVersion = "system-prompt-v1",
        marketSnapshotId = "snapshot-1",
        action = DecisionAction.ENTER,
        setupTags = listOf("reconciler-test"),
        estimatedWinProbability = command.estimatedWinProbability,
        expectedRMultiple = BigDecimal("2.0"),
        roundTripCostR = BigDecimal("0.1"),
        toolEvidenceIds = listOf("tool-1"),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = command.reasonJa,
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
            thesisJa = "reconciler test entry の仮説です。",
            invalidationConditionsJa = listOf("protective stop 到達"),
            targetPriceJpy = command.takeProfitPriceJpy,
            timeStopAt = fixedInstant().plusSeconds(3600),
            setupTags = listOf("reconciler-test"),
        ),
    )
}

private fun reconcilerEntryCommand(takeProfitPriceJpy: BigDecimal): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.MARKET,
        sizeBtc = BigDecimal("0.0050"),
        priceJpy = null,
        tradeGroupId = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = takeProfitPriceJpy,
        estimatedWinProbability = BigDecimal("0.95"),
        reasonJa = "test entry",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun restingReconcilerEntryCommand(sizeBtc: BigDecimal = BigDecimal("0.0050")): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        sizeBtc = sizeBtc,
        priceJpy = BigDecimal("10000000"),
        tradeGroupId = null,
        protectiveStopPriceJpy = BigDecimal("9700000"),
        takeProfitPriceJpy = BigDecimal("12000000"),
        estimatedWinProbability = BigDecimal("0.95"),
        reasonJa = "test resting entry",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun reconcilerSymbolRules(): SymbolRules {
    return SymbolRules(
        symbol = "BTC",
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "1",
        takerFee = "0.0005",
        makerFee = "-0.0001",
    )
}

private fun reconcilerOrderbookWithAsk(price: String, size: String = "0.0100"): Orderbook {
    return Orderbook(
        symbol = "BTC",
        bids = listOf(OrderbookLevel(price = "9990000", size = "0.0100")),
        asks = listOf(OrderbookLevel(price = price, size = size)),
    )
}

/**
 * Reconciler broker integration test 用 fake market data。
 */
private object ReconcilerFakeMarketDataSource : MarketDataSource {
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
        return Result.success(reconcilerSymbolRules())
    }
}

/**
 * Reconciler の LIMIT board relation を差し替える fake market data。
 *
 * @param orderbook 現在返す orderbook
 */
private class MutableReconcilerOrderbookMarketDataSource(
    var orderbook: Orderbook,
) : MarketDataSource by ReconcilerFakeMarketDataSource {
    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(orderbook)
    }
}

/**
 * テスト内で任意時刻へ進められる clock。
 *
 * @param currentInstant 現在時刻として返す instant
 * @param currentZone clock の zone
 */
private class MutableTestClock(
    var currentInstant: Instant,
    private val currentZone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    override fun getZone(): ZoneId {
        return currentZone
    }

    override fun withZone(zone: ZoneId): Clock {
        return MutableTestClock(
            currentInstant = currentInstant,
            currentZone = zone,
        )
    }

    override fun instant(): Instant {
        return currentInstant
    }
}
