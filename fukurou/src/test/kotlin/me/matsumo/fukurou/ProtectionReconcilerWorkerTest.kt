package me.matsumo.fukurou

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.PaperBroker
import me.matsumo.fukurou.trading.broker.PaperReconcileResult
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.market.MarketDataGapReason
import me.matsumo.fukurou.trading.market.MarketDataIntegrityRepository
import me.matsumo.fukurou.trading.market.MarketDataIntegritySnapshot
import me.matsumo.fukurou.trading.market.MarketEventSession
import me.matsumo.fukurou.trading.market.MarketEventStream
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ProtectionReconciler
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.reconciler.TickStream
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Ktor backend worker が ProtectionReconciler loop を起動することを検証するテスト。
 */
class ProtectionReconcilerWorkerTest {

    @Test
    fun worker_starts_reconciler_loop() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC)
        val status = MutableReconcilerStatus()
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = clock),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = InMemoryTradingLock(clock),
            status = status,
            clock = clock,
        )
        val worker = ProtectionReconcilerWorker(
            reconciler = reconciler,
            interval = Duration.ofMillis(10),
        )

        worker.use {
            worker.start()
            withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
                while (status.snapshot().lastReconciledAt == null) {
                    delay(10.toDuration(DurationUnit.MILLISECONDS))
                }
            }
        }

        assertNotNull(status.snapshot().lastReconciledAt)
        assertTrue(status.snapshot().startupFullReconcileCompleted)
    }

    @Test
    fun worker_retries_bootstrap_before_reconciler_loop() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC)
        val status = MutableReconcilerStatus()
        val attempts = AtomicInteger(0)
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = clock),
            commandEventLog = InMemoryCommandEventLog(),
            tradingLock = InMemoryTradingLock(clock),
            status = status,
            clock = clock,
        )
        val worker = ProtectionReconcilerWorker(
            reconciler = reconciler,
            interval = Duration.ofMillis(10),
            bootstrap = {
                if (attempts.incrementAndGet() == 1) {
                    Result.failure(IllegalStateException("database is not ready"))
                } else {
                    Result.success(Unit)
                }
            },
        )

        worker.use {
            worker.start()
            withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
                while (status.snapshot().lastReconciledAt == null) {
                    delay(10.toDuration(DurationUnit.MILLISECONDS))
                }
            }
        }

        assertTrue(attempts.get() >= 2)
        assertTrue(status.snapshot().startupFullReconcileCompleted)
    }

    @Test
    fun worker_recovers_periodic_maintenance_failure_episodes_without_stopping_websocket_consumer() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC)
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000178")
        val events = Channel<Result<PaperMarketTradeEvent>>(Channel.UNLIMITED)
        val eventLog = InMemoryCommandEventLog()
        val integrityRepository = WorkerTestMarketDataIntegrityRepository()
        val broker = EpisodicMaintenanceFailureBroker(
            PaperBroker(
                ledgerRepository = InMemoryPaperLedgerRepository(clock = clock),
                riskStateRepository = InMemoryRiskStateRepository(clock = clock),
                decisionRepository = InMemoryDecisionRepository(clock),
                clock = clock,
            ),
        )
        val reconciler = ProtectionReconciler(
            riskStateRepository = InMemoryRiskStateRepository(clock = clock),
            commandEventLog = eventLog,
            tradingLock = InMemoryTradingLock(clock),
            tickStream = FixedTickStream(clock),
            marketEventStream = WorkerTestMarketEventStream(
                WorkerTestMarketEventSession(sessionId, clock.instant(), events),
            ),
            marketDataIntegrityRepository = integrityRepository,
            broker = broker,
            clock = clock,
        )
        val worker = ProtectionReconcilerWorker(
            reconciler = reconciler,
            interval = Duration.ofMillis(10),
        )

        worker.use {
            worker.start()
            events.send(workerTestEvent(sessionId, sequence = 1))

            withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
                while (broker.maintenanceAttempts.get() == 0) {
                    delay(1.toDuration(DurationUnit.MILLISECONDS))
                }
            }
            events.send(workerTestEvent(sessionId, sequence = 2))

            withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
                while (broker.appliedEventCount.get() < 2) {
                    delay(1.toDuration(DurationUnit.MILLISECONDS))
                }
            }
            withTimeout(500.toDuration(DurationUnit.MILLISECONDS)) {
                while (
                    broker.maintenanceAttempts.get() < 5 ||
                    eventLog.failureEpisodeEventTypes().size < 4
                ) {
                    delay(1.toDuration(DurationUnit.MILLISECONDS))
                }
            }
        }

        assertEquals(2, broker.appliedEventCount.get())
        assertTrue(broker.maintenanceAttempts.get() >= 5)
        assertEquals(MarketDataConnectionState.CONNECTED, integrityRepository.snapshot().getOrThrow().state)
        assertEquals(
            listOf(
                CommandEventType.RECONCILER_PASS_FAILED,
                CommandEventType.RECONCILER_PASS_RECOVERED,
                CommandEventType.RECONCILER_PASS_FAILED,
                CommandEventType.RECONCILER_PASS_RECOVERED,
            ),
            eventLog.failureEpisodeEventTypes(),
        )
    }
}

private suspend fun InMemoryCommandEventLog.failureEpisodeEventTypes(): List<CommandEventType> {
    return events()
        .map { event -> event.eventType }
        .filter { eventType ->
            eventType == CommandEventType.RECONCILER_PASS_FAILED ||
                eventType == CommandEventType.RECONCILER_PASS_RECOVERED
        }
}

private fun workerTestEvent(sessionId: UUID, sequence: Long): Result<PaperMarketTradeEvent> {
    return Result.success(
        PaperMarketTradeEvent(
            symbol = TradingSymbol.BTC,
            side = OrderSide.SELL,
            priceJpy = BigDecimal("10000000"),
            sizeBtc = BigDecimal("0.0010"),
            exchangeAt = Instant.parse("2026-07-02T00:00:00Z").plusSeconds(sequence),
            receivedAt = Instant.parse("2026-07-02T00:00:00Z").plusSeconds(sequence),
            connectionSessionId = sessionId,
            sequence = sequence,
        ),
    )
}

private class EpisodicMaintenanceFailureBroker(
    private val delegate: Broker,
) : Broker by delegate {
    val maintenanceAttempts = AtomicInteger(0)
    val appliedEventCount = AtomicInteger(0)

    override suspend fun applyMarketEvent(event: PaperMarketTradeEvent): Result<PaperReconcileResult> {
        return delegate.applyMarketEvent(event).also { appliedEventCount.incrementAndGet() }
    }

    override suspend fun maintainProtections(tickSnapshot: TickSnapshot): Result<PaperReconcileResult> {
        val attempt = maintenanceAttempts.incrementAndGet()
        if (attempt == 1 || attempt == 3) {
            return Result.failure(IllegalStateException("transient periodic maintenance failure"))
        }

        return delegate.maintainProtections(tickSnapshot)
    }
}

private class FixedTickStream(
    private val clock: Clock,
) : TickStream {
    override suspend fun latestTick(): Result<TickSnapshot?> {
        return Result.success(
            TickSnapshot(
                symbol = TradingSymbol.BTC.apiSymbol,
                observedAt = clock.instant(),
                lastPrice = "10000000",
                bidPrice = "10000000",
                askPrice = "10000000",
                symbolRules = workerTestSymbolRules(),
            ),
        )
    }
}

private fun workerTestSymbolRules(): SymbolRules {
    return SymbolRules(
        symbol = TradingSymbol.BTC.apiSymbol,
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "1",
        takerFee = "0.0005",
        makerFee = "-0.0001",
    )
}

private class WorkerTestMarketEventStream(
    private val session: MarketEventSession,
) : MarketEventStream {
    override val reconnectBackoff: Duration = Duration.ofMillis(1)
    private var connected = false

    override suspend fun connect(): Result<MarketEventSession> {
        if (connected) return Result.failure(IllegalStateException("unexpected reconnect"))

        connected = true
        return Result.success(session)
    }
}

private class WorkerTestMarketEventSession(
    override val sessionId: UUID,
    override val connectedAt: Instant,
    private val events: ReceiveChannel<Result<PaperMarketTradeEvent>>,
) : MarketEventSession {
    override suspend fun receive(): Result<PaperMarketTradeEvent> = events.receive()

    override fun close() = Unit
}

private class WorkerTestMarketDataIntegrityRepository : MarketDataIntegrityRepository {
    private var current = MarketDataIntegritySnapshot()

    override suspend fun snapshot(): Result<MarketDataIntegritySnapshot> = Result.success(current)

    override suspend fun beginSession(sessionId: UUID, connectedAt: Instant): Result<Unit> {
        current = MarketDataIntegritySnapshot(
            state = MarketDataConnectionState.CONNECTED,
            sessionId = sessionId,
            startupRecoveryCompleted = true,
        )

        return Result.success(Unit)
    }

    override suspend fun markMaintenanceSucceeded(sessionId: UUID, succeededAt: Instant): Result<Unit> {
        current = current.copy(lastMaintenanceAt = succeededAt)

        return Result.success(Unit)
    }

    override suspend fun markDisconnected(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String?,
    ): Result<Unit> {
        current = current.copy(
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
    ): Result<Unit> = Result.success(Unit)

    override suspend fun recordGap(
        sessionId: UUID,
        reason: MarketDataGapReason,
        detectedAt: Instant,
        detail: String?,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun recoverStaleSession(recoveredAt: Instant): Result<Unit> = Result.success(Unit)
}
