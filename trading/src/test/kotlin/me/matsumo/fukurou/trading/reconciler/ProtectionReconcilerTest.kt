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
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.lock.TradingLockLease
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
): ProtectionReconciler {
    return ProtectionReconciler(
        riskStateRepository = riskStateRepository,
        commandEventLog = eventLog,
        tradingLock = lock,
        tickStream = tickStream,
        status = status,
        clock = clock,
    )
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
    )
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
