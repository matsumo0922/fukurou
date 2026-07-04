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
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
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
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotReason
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecord
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotRecorder
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.lock.TradingLockLease
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.math.BigDecimal
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

        assertTrue(result.isSuccess)
        assertEquals(1, lock.acquisitionCount)
        assertEquals(fixedInstant(), status.snapshot().lastReconciledAt)
        assertTrue(status.snapshot().startupFullReconcileCompleted)
        assertEquals(fixedInstant(), status.snapshot().lastMarketDataAt)
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

        withTimeout(500) {
            while (lock.acquisitionCount < 2) {
                delay(10)
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

        withTimeout(500) {
            while (status.snapshot().lastReconciledAt == null) {
                delay(10)
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

        withTimeout(500) {
            while (!status.snapshot().startupFullReconcileCompleted) {
                delay(10)
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

        assertEquals(
            listOf(
                CommandEventType.RECONCILER_PASS_COMPLETED,
                CommandEventType.RECONCILER_PASS_FAILED,
                CommandEventType.RECONCILER_PASS_COMPLETED,
                CommandEventType.RECONCILER_PASS_RECOVERED,
            ),
            eventTypes,
        )
    }

    @Test
    fun tick_failure_advances_reconciled_freshness_without_market_freshness() = runBlocking {
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
        assertEquals(secondInstant, snapshot.lastReconciledAt)
        assertEquals(firstInstant, snapshot.lastMarketDataAt)
        assertEquals(
            listOf(
                CommandEventType.RECONCILER_PASS_COMPLETED,
                CommandEventType.RECONCILER_PASS_COMPLETED,
            ),
            eventTypes,
        )
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
        assertEquals(null, snapshot.lastReconciledAt)
        assertEquals(null, snapshot.lastMarketDataAt)
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
        assertTrue(riskState.hardHalt)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
        assertEquals(2, repository.getExecutions().getOrThrow().size)
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
        assertTrue(riskState.hardHalt)
        assertEquals(0, broker.getPositions().getOrThrow().size)
        assertEquals(0, broker.getOpenOrders().getOrThrow().size)
        assertEquals(2, repository.getExecutions().getOrThrow().size)
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

private fun assertDecimalStringEquals(fieldName: String, expected: String, actual: BigDecimal) {
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

    override suspend fun <T> withLock(owner: String, block: suspend (TradingLockLease) -> T): T {
        return delegate.withLock(owner) { lease ->
            acquisitionCount += 1

            block(lease)
        }
    }
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
): ProtectionReconciler {
    return ProtectionReconciler(
        riskStateRepository = riskStateRepository,
        commandEventLog = eventLog,
        tradingLock = lock,
        tickStream = tickStream,
        broker = broker,
        equitySnapshotRecorder = equitySnapshotRecorder,
        status = status,
        clock = clock,
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

    override suspend fun countDistinctDecisionRunsSince(since: Instant): Result<Int> {
        return Result.failure(IllegalStateException("audit count failed"))
    }

    override suspend fun countToolCallEvents(
        decisionRunId: String,
        toolNames: Set<String>,
    ): Result<Int> {
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
private fun fixedTickSnapshot(
    observedAt: Instant = fixedInstant(),
): TickSnapshot {
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
        commandId = java.util.UUID.randomUUID(),
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
        commandId = java.util.UUID.randomUUID(),
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
