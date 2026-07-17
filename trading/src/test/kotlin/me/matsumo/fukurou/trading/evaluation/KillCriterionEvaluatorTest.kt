package me.matsumo.fukurou.trading.evaluation

import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.broker.AccountSnapshotWithUpdatedAt
import me.matsumo.fukurou.trading.broker.AccountStatusWithUpdatedAt
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.OpenOrdersWithUpdatedAt
import me.matsumo.fukurou.trading.broker.PaperReconcileResult
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.PositionsWithUpdatedAt
import me.matsumo.fukurou.trading.broker.PreviewOrderResult
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.config.KillCriterionConfig
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateCommandService
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KillCriterionEvaluator の HARD_HALT 接続を検証するテスト。
 */
class KillCriterionEvaluatorTest {

    @Test
    fun evaluate_breachedCriterionAppendsAuditSetsHardHaltAndSweeps() = kotlinx.coroutines.runBlocking {
        val fixture = evaluatorFixture(
            statsSource = { Result.success(KillCriterionStats(closedTrades = 2, profitFactor = BigDecimal("0.70"))) },
        )

        fixture.evaluator.evaluate(tickSnapshot()).getOrThrow()

        val eventTypes = fixture.eventLog.events().map { event -> event.eventType }

        assertEquals(
            listOf(
                CommandEventType.KILL_CRITERION_BREACHED,
                CommandEventType.HARD_HALT_SET,
            ),
            eventTypes,
        )
        assertEquals(RiskHaltState.HARD_HALT, fixture.riskStateRepository.current().getOrThrow().state)
        assertEquals(1, fixture.broker.sweepCount)
    }

    @Test
    fun evaluate_skipsWhenHardHaltAlreadySticky() = kotlinx.coroutines.runBlocking {
        var statsCallCount = 0
        val fixture = evaluatorFixture(
            statsSource = {
                statsCallCount += 1

                Result.success(KillCriterionStats(closedTrades = 2, profitFactor = BigDecimal("0.70")))
            },
        )
        fixture.riskStateRepository.setHardHalt("sticky", fixedInstant()).getOrThrow()

        fixture.evaluator.evaluate(tickSnapshot()).getOrThrow()

        assertEquals(0, statsCallCount)
        assertEquals(0, fixture.broker.sweepCount)
    }

    @Test
    fun evaluate_escalatesSoftHaltToHardHaltWhenCriterionBreaches() = kotlinx.coroutines.runBlocking {
        val fixture = evaluatorFixture(
            statsSource = { Result.success(KillCriterionStats(closedTrades = 2, profitFactor = BigDecimal("0.70"))) },
        )

        fixture.riskStateRepository.setSoftHalt("operator pause", fixedInstant()).getOrThrow()
        fixture.evaluator.evaluate(tickSnapshot()).getOrThrow()

        val riskState = fixture.riskStateRepository.current().getOrThrow()

        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
        assertEquals(1, fixture.broker.sweepCount)
    }

    @Test
    fun evaluate_doesNotFireForInsufficientTradesOrNullProfitFactor() = kotlinx.coroutines.runBlocking {
        val insufficientTrades = evaluatorFixture(
            statsSource = { Result.success(KillCriterionStats(closedTrades = 1, profitFactor = BigDecimal("0.70"))) },
        )
        val nullProfitFactor = evaluatorFixture(
            statsSource = { Result.success(KillCriterionStats(closedTrades = 2, profitFactor = null)) },
        )

        insufficientTrades.evaluator.evaluate(tickSnapshot()).getOrThrow()
        nullProfitFactor.evaluator.evaluate(tickSnapshot()).getOrThrow()

        assertEquals(RiskHaltState.RUNNING, insufficientTrades.riskStateRepository.current().getOrThrow().state)
        assertEquals(RiskHaltState.RUNNING, nullProfitFactor.riskStateRepository.current().getOrThrow().state)
    }

    @Test
    fun evaluate_throttlesRepeatedEvaluationWithinFiveMinutes() = kotlinx.coroutines.runBlocking {
        val clock = MutableTestClock(fixedInstant())
        var statsCallCount = 0
        val fixture = evaluatorFixture(
            clock = clock,
            statsSource = {
                statsCallCount += 1

                Result.success(KillCriterionStats(closedTrades = 1, profitFactor = BigDecimal("0.90")))
            },
        )

        fixture.evaluator.evaluate(tickSnapshot()).getOrThrow()
        clock.currentInstant = fixedInstant().plusSeconds(60)
        fixture.evaluator.evaluate(tickSnapshot()).getOrThrow()

        assertEquals(1, statsCallCount)
    }

    @Test
    fun evaluate_ignoresStatsFailureAndKeepsPassSuccessful() = kotlinx.coroutines.runBlocking {
        val fixture = evaluatorFixture(
            statsSource = { Result.failure(IllegalStateException("stats unavailable")) },
        )

        val result = fixture.evaluator.evaluate(tickSnapshot())

        assertTrue(result.isSuccess)
        assertEquals(RiskHaltState.RUNNING, fixture.riskStateRepository.current().getOrThrow().state)
        assertEquals(0, fixture.broker.sweepCount)
    }

    @Test
    fun evaluate_retriesImmediatelyWhenHardHaltCommandFailsAfterBreach() = kotlinx.coroutines.runBlocking {
        var statsCallCount = 0
        val clock = MutableTestClock(fixedInstant())
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val eventLog = InMemoryCommandEventLog()
        val commandService = FailingOnceRiskStateCommandService(riskStateRepository, eventLog, clock)
        val fixture = evaluatorFixture(
            clock = clock,
            riskStateRepository = riskStateRepository,
            commandEventLog = eventLog,
            riskStateCommandService = commandService,
            statsSource = {
                statsCallCount += 1

                Result.success(KillCriterionStats(closedTrades = 2, profitFactor = BigDecimal("0.70")))
            },
        )

        val firstResult = fixture.evaluator.evaluate(tickSnapshot())
        val secondResult = fixture.evaluator.evaluate(tickSnapshot())

        assertTrue(firstResult.isFailure)
        assertTrue(secondResult.isSuccess)
        assertEquals(2, statsCallCount)
        assertEquals(RiskHaltState.HARD_HALT, fixture.riskStateRepository.current().getOrThrow().state)
        assertEquals(1, fixture.broker.sweepCount)
    }

    @Test
    fun evaluate_rethrowsCancellationException() {
        kotlinx.coroutines.runBlocking {
            val fixture = evaluatorFixture(
                statsSource = { throw kotlinx.coroutines.CancellationException("stop") },
            )

            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
                fixture.evaluator.evaluate(tickSnapshot())
            }
        }
    }
}

/**
 * KillCriterionEvaluator test fixture。
 *
 * @param evaluator test target
 * @param riskStateRepository risk_state repository
 * @param eventLog command event log
 * @param broker fake broker
 */
private data class KillEvaluatorFixture(
    val evaluator: KillCriterionEvaluator,
    val riskStateRepository: InMemoryRiskStateRepository,
    val eventLog: InMemoryCommandEventLog,
    val broker: FakeBroker,
)

private fun evaluatorFixture(
    clock: Clock = fixedClock(),
    riskStateRepository: InMemoryRiskStateRepository = InMemoryRiskStateRepository(clock),
    commandEventLog: InMemoryCommandEventLog = InMemoryCommandEventLog(),
    riskStateCommandService: RiskStateCommandService = InMemoryRiskStateCommandService(
        riskStateRepository = riskStateRepository,
        commandEventLog = commandEventLog,
        clock = clock,
    ),
    statsSource: suspend () -> Result<KillCriterionStats>,
): KillEvaluatorFixture {
    val broker = FakeBroker()
    val evaluator = KillCriterionEvaluator(
        config = KillCriterionConfig(
            minClosedTrades = 2,
            minProfitFactor = BigDecimal("0.80"),
        ),
        riskStateRepository = riskStateRepository,
        riskStateCommandService = riskStateCommandService,
        commandEventLog = commandEventLog,
        broker = broker,
        statsSource = statsSource,
        clock = clock,
    )

    return KillEvaluatorFixture(
        evaluator = evaluator,
        riskStateRepository = riskStateRepository,
        eventLog = commandEventLog,
        broker = broker,
    )
}

/**
 * sweep 呼び出しだけを記録する fake broker。
 */
private class FakeBroker : Broker {

    /**
     * HARD_HALT sweep 呼び出し回数。
     */
    var sweepCount: Int = 0
        private set

    override suspend fun getBalance(): Result<AccountSnapshot> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getBalanceWithUpdatedAt(): Result<AccountSnapshotWithUpdatedAt> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getPositions(): Result<List<Position>> {
        return Result.success(emptyList())
    }

    override suspend fun getPositionsWithUpdatedAt(): Result<PositionsWithUpdatedAt> {
        return Result.success(
            PositionsWithUpdatedAt(
                positions = emptyList(),
                updatedAt = fixedInstant(),
            ),
        )
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return Result.success(emptyList())
    }

    override suspend fun getOpenOrdersWithUpdatedAt(): Result<OpenOrdersWithUpdatedAt> {
        return Result.success(
            OpenOrdersWithUpdatedAt(
                openOrders = emptyList(),
                updatedAt = fixedInstant(),
            ),
        )
    }

    override suspend fun getAccountStatus(): Result<AccountStatus> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getAccountStatusWithUpdatedAt(): Result<AccountStatusWithUpdatedAt> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun placeOrder(command: PlaceOrderCommand): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun previewOrder(command: PlaceOrderCommand): Result<PreviewOrderResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun closePosition(command: ClosePositionCommand): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun exitPosition(command: ClosePositionCommand): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun reconcile(tickSnapshot: TickSnapshot): Result<PaperReconcileResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun sweepHardHalt(reasonJa: String, tickSnapshot: TickSnapshot?): Result<PaperTradeResult> {
        sweepCount += 1

        return Result.success(
            PaperTradeResult(
                accepted = true,
                status = OrderStatus.FILLED,
                orderIds = emptyList(),
                positionIds = emptyList(),
                executionIds = emptyList(),
                messageJa = reasonJa,
                safetyViolation = null,
            ),
        )
    }
}

/**
 * 初回だけ HARD_HALT 設定に失敗する fake command service。
 *
 * @param riskStateRepository 共有する risk_state repository
 * @param commandEventLog command event log
 * @param clock 状態変更時刻に使う clock
 */
private class FailingOnceRiskStateCommandService(
    riskStateRepository: InMemoryRiskStateRepository,
    commandEventLog: InMemoryCommandEventLog,
    clock: Clock,
) : RiskStateCommandService {

    private val delegate = InMemoryRiskStateCommandService(riskStateRepository, commandEventLog, clock)
    private var shouldFail = true

    override suspend fun setHardHalt(reason: String, decisionRunContext: DecisionRunContext): Result<RiskState> {
        if (shouldFail) {
            shouldFail = false

            return Result.failure(IllegalStateException("temporary halt failure"))
        }

        return delegate.setHardHalt(reason, decisionRunContext)
    }

    override suspend fun setSoftHalt(reason: String, decisionRunContext: DecisionRunContext): Result<RiskState> {
        return delegate.setSoftHalt(reason, decisionRunContext)
    }

    override suspend fun resume(reason: String, decisionRunContext: DecisionRunContext): Result<RiskState> {
        return delegate.resume(reason, decisionRunContext)
    }
}

/**
 * 変更可能な test clock。
 *
 * @param currentInstant 現在時刻
 */
private class MutableTestClock(
    var currentInstant: Instant,
) : Clock() {
    override fun instant(): Instant {
        return currentInstant
    }

    override fun withZone(zone: ZoneId): Clock {
        return this
    }

    override fun getZone(): ZoneId {
        return ZoneOffset.UTC
    }
}

private fun tickSnapshot(): TickSnapshot {
    return TickSnapshot(
        symbol = "BTC",
        observedAt = fixedInstant(),
        lastPrice = "100",
        bidPrice = "99",
        askPrice = "101",
    )
}

private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}
