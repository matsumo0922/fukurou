package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.safety.InMemorySafetyFloorMarginRepository
import me.matsumo.fukurou.trading.safety.ObservedVerdict
import me.matsumo.fukurou.trading.safety.SafetyFloorCallSite
import me.matsumo.fukurou.trading.safety.SafetyFloorMarginRepository
import me.matsumo.fukurou.trading.safety.SafetyFloorObservationReport
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
 * PaperBroker が SafetyFloor の観測を記録し、それが台帳を変更しないことを検証するテスト。
 */
class PaperBrokerMarginObservationTest {

    @Test
    fun `place order records an observation with 27 points`() = runBlocking {
        val marginRepository = InMemorySafetyFloorMarginRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = broker(marginRepository = marginRepository, decisionRepository = decisionRepository)

        broker.placeOrder(approved(decisionRepository, marketEntry())).getOrThrow()

        val report = marginRepository.all().single()
        assertEquals(SafetyFloorCallSite.PLACE, report.callSite)
        assertEquals(ObservedVerdict.ACCEPTED, report.verdict)
        assertEquals(27, report.observations.size)
        assertFalse(report.divergence)
    }

    @Test
    fun `preview order records a preview observation`() = runBlocking {
        val marginRepository = InMemorySafetyFloorMarginRepository()
        val broker = broker(marginRepository = marginRepository)

        broker.previewOrder(marketEntry()).getOrThrow()

        val report = marginRepository.all().single()
        assertEquals(SafetyFloorCallSite.PREVIEW, report.callSite)
    }

    @Test
    fun `a rejected order records the rejected rule without divergence`() = runBlocking {
        val marginRepository = InMemorySafetyFloorMarginRepository()
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val broker = broker(marginRepository = marginRepository, decisionRepository = decisionRepository)

        // stop を entry より上に置くと STOP_LOSS_REQUIRED で拒否される。
        broker.placeOrder(
            approved(decisionRepository, marketEntry(protectiveStopPriceJpy = BigDecimal("11000000"))),
        ).getOrThrow()

        val report = marginRepository.all().single()
        assertEquals(ObservedVerdict.REJECTED, report.verdict)
        assertFalse(report.divergence)
    }

    @Test
    fun `margin persistence failure does not change the ledger effect`() = runBlocking {
        val decisionRepository = InMemoryDecisionRepository(fixedClock())
        val failingRepository = broker(
            marginRepository = FailingSafetyFloorMarginRepository,
            decisionRepository = decisionRepository,
        )

        val result = failingRepository.placeOrder(approved(decisionRepository, marketEntry())).getOrThrow()
        val positions = failingRepository.getPositions().getOrThrow()

        assertTrue(result.accepted)
        assertEquals(1, positions.size)
    }

    @Test
    fun `margin write leaves cash and positions identical to a run without observation failure`() = runBlocking {
        val workingDecisions = InMemoryDecisionRepository(fixedClock())
        val working = broker(
            marginRepository = InMemorySafetyFloorMarginRepository(),
            decisionRepository = workingDecisions,
        )
        val failingDecisions = InMemoryDecisionRepository(fixedClock())
        val failing = broker(
            marginRepository = FailingSafetyFloorMarginRepository,
            decisionRepository = failingDecisions,
        )

        working.placeOrder(approved(workingDecisions, marketEntry())).getOrThrow()
        failing.placeOrder(approved(failingDecisions, marketEntry())).getOrThrow()

        assertEquals(
            working.getBalance().getOrThrow().cashJpy,
            failing.getBalance().getOrThrow().cashJpy,
        )
        assertEquals(
            working.getPositions().getOrThrow().size,
            failing.getPositions().getOrThrow().size,
        )
    }

    private fun broker(
        marginRepository: SafetyFloorMarginRepository,
        decisionRepository: DecisionRepository = InMemoryDecisionRepository(fixedClock()),
    ): PaperBroker {
        return PaperBroker(
            ledgerRepository = InMemoryPaperLedgerRepository(),
            riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
            decisionRepository = decisionRepository,
            safetyFloorMarginRepository = marginRepository,
            marketDataSource = MarginFakeMarketDataSource,
            clock = fixedClock(),
        )
    }

    private fun marketEntry(protectiveStopPriceJpy: BigDecimal = BigDecimal("9700000")): PlaceOrderCommand {
        return PlaceOrderCommand(
            commandId = UUID.randomUUID(),
            symbol = TradingSymbol.BTC,
            side = OrderSide.BUY,
            orderType = OrderType.MARKET,
            sizeBtc = BigDecimal("0.0050"),
            priceJpy = null,
            tradeGroupId = null,
            protectiveStopPriceJpy = protectiveStopPriceJpy,
            takeProfitPriceJpy = BigDecimal("10500000"),
            estimatedWinProbability = BigDecimal("0.60"),
            reasonJa = "margin observation test entry",
            auditContext = PaperTradeAuditContext.EMPTY,
        )
    }

    private suspend fun approved(repository: DecisionRepository, command: PlaceOrderCommand): PlaceOrderCommand {
        val decisionResult = repository.submitDecision(decisionSubmission(command)).getOrThrow()
        val intentId = requireNotNull(decisionResult.tradeIntent?.intentId)

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

    private fun decisionSubmission(command: PlaceOrderCommand): DecisionSubmission {
        return DecisionSubmission(
            invocationId = "margin-observation-invocation",
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
                invalidationPredicates = listOf(
                    TradePlanInvalidationPredicate(
                        type = TradePlanInvalidationType.LAST_PRICE_AT_OR_BELOW,
                        decimalThresholdJpy = command.protectiveStopPriceJpy,
                    ),
                ),
            ),
        )
    }
}

/** 観測の保存が必ず失敗する repository。isolation の検証に使う。 */
private object FailingSafetyFloorMarginRepository : SafetyFloorMarginRepository {
    override suspend fun append(report: SafetyFloorObservationReport): Result<Unit> {
        return Result.failure(IllegalStateException("margin persistence is unavailable in this test."))
    }

    override suspend fun find(id: UUID): Result<SafetyFloorObservationReport?> = Result.success(null)
}

private object MarginFakeMarketDataSource : MarketDataSource {
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

private fun fixedInstant(): Instant = Instant.parse("2026-07-17T00:00:00Z")

private fun fixedClock(): Clock = Clock.fixed(fixedInstant(), ZoneOffset.UTC)
