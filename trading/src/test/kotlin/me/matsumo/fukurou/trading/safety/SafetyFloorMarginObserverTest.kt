package me.matsumo.fukurou.trading.safety

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.EntryIntentSafetySnapshot
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskState
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * margin observer が SafetyFloor の判定と整合することを検証するテスト。
 */
class SafetyFloorMarginObserverTest {

    @Test
    fun `covers every place order rule exactly once`() {
        val covered = SafetyFloorEvaluationPoints.placeOrderPoints.map { point -> point.rule }.toSet()
        val expected = SafetyFloorRule.entries.toSet() - SafetyFloorEvaluationPoints.updateOnlyRules

        assertEquals(expected, covered, "PLACE_ORDER path must cover every non-update rule.")
        assertEquals(27, SafetyFloorEvaluationPoints.placeOrderPoints.size)
        assertEquals(17, SafetyFloorEvaluationPoints.placeOrderPoints.count { point -> point.isNumeric })
        assertEquals(10, SafetyFloorEvaluationPoints.placeOrderPoints.count { point -> !point.isNumeric })
    }

    @Test
    fun `update only rules are not observed on the place order path`() {
        val observed = SafetyFloorEvaluationPoints.placeOrderPoints.map { point -> point.rule }.toSet()

        SafetyFloorEvaluationPoints.updateOnlyRules.forEach { rule ->
            assertFalse(rule in observed, "$rule is evaluated only on the update path.")
        }
    }

    @Test
    fun `agrees with the verdict across representative scenarios`() {
        scenarios().forEach { scenario ->
            val verdict = safetyFloor().evaluatePlaceOrder(scenario.command, scenario.context)
            val report = observer().observe(
                command = scenario.command,
                context = scenario.context,
                verdict = verdict,
                callSite = SafetyFloorCallSite.PLACE,
            )

            assertFalse(
                report.divergence,
                "Observer diverged from the verdict in scenario '${scenario.name}'. " +
                    "verdict=$verdict, observations=${report.failedRules()}",
            )
        }
    }

    @Test
    fun `flags divergence when the verdict accepts but an observation fails`() {
        val observations = SafetyFloorEvaluationPoints.placeOrderPoints.map { point ->
            val status = if (point.rule == SafetyFloorRule.MAX_TOTAL_EXPOSURE) RuleStatus.FAIL else RuleStatus.PASS

            RuleObservation(point = point, status = status)
        }

        assertTrue(observations.any { observation -> observation.status == RuleStatus.FAIL })
    }

    @Test
    fun `records sticky halt separately from the drawdown threshold`() {
        val command = entryCommand()
        val context = baseContext().copy(
            riskState = RiskState(
                state = RiskHaltState.HARD_HALT,
                drawdownRatio = BigDecimal("-0.01"),
                updatedAt = fixedInstant(),
            ),
        )

        val report = observe(command, context)

        assertEquals(RuleStatus.FAIL, report.statusOf(SafetyFloorRule.MAX_DRAWDOWN_HALT, POINT_STICKY))
        assertEquals(RuleStatus.PASS, report.statusOf(SafetyFloorRule.MAX_DRAWDOWN_HALT, POINT_THRESHOLD))
    }

    @Test
    fun `marks expected value points as unevaluable when the stop is above entry`() {
        val command = entryCommand(protectiveStopPriceJpy = BigDecimal("10500000"))
        val report = observe(command, baseContext())

        listOf(
            SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE,
            SafetyFloorRule.EXPECTED_VALUE_GATE,
        ).forEach { rule ->
            assertEquals(RuleStatus.NA, report.statusOf(rule), "$rule must not be scored on a degenerate risk amount.")
            assertEquals(NaReason.PRECONDITION_UNMET, report.reasonOf(rule))
        }
    }

    @Test
    fun `marks pyramid points as unevaluable without a trade group`() {
        val report = observe(entryCommand(), baseContext(positions = listOf(openPosition())))

        listOf(
            SafetyFloorRule.PYRAMID_ADD_LIMIT,
            SafetyFloorRule.PYRAMID_PROFIT_GATE,
            SafetyFloorRule.PYRAMID_ADD_RISK_LIMIT,
        ).forEach { rule ->
            assertEquals(RuleStatus.NA, report.statusOf(rule))
        }
    }

    @Test
    fun `does not fail stop loosening on unrelated positions without a trade group`() {
        val unrelated = openPosition(currentStopLossJpy = "12000000")
        val report = observe(
            entryCommand(protectiveStopPriceJpy = BigDecimal("9700000")),
            baseContext(positions = listOf(unrelated)),
        )

        assertEquals(
            RuleStatus.NA,
            report.statusOf(SafetyFloorRule.STOP_LOSS_LOOSENING),
            "The verdict skips this rule without a trade group, so the observer must not score it.",
        )
    }

    @Test
    fun `evaluates stop loosening using only positions that carry a stop`() {
        val tradeGroupId = UUID.randomUUID()
        val withoutStop = openPosition(tradeGroupId = tradeGroupId, currentStopLossJpy = null)
        val withStop = openPosition(tradeGroupId = tradeGroupId, currentStopLossJpy = "9800000")
        val command = entryCommand(
            tradeGroupId = tradeGroupId,
            protectiveStopPriceJpy = BigDecimal("9700000"),
        )

        val report = observe(command, baseContext(positions = listOf(withoutStop, withStop)))

        assertEquals(
            RuleStatus.FAIL,
            report.statusOf(SafetyFloorRule.STOP_LOSS_LOOSENING),
            "A single position without a stop must not suppress the comparison.",
        )
    }

    @Test
    fun `does not throw when fee rates are not numeric`() {
        val context = baseContext(symbolRules = symbolRules(takerFee = "not-a-number"))
        val report = observe(entryCommand(), context)

        assertFalse(report.collectionFailed)
        assertEquals(RuleStatus.FAIL, report.statusOf(SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT, POINT_FEE))
        assertEquals(RuleStatus.NA, report.statusOf(SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT, POINT_CASH))
    }

    @Test
    fun `records the policy version and leaves runtime config version absent without an audit context`() {
        val report = observe(entryCommand(), baseContext())

        assertEquals(SafetyFloorDefaults.policyVersion, report.policyVersion)
        assertNull(report.runtimeConfigVersion)
        assertEquals(1, report.observationSchemaVersion)
    }

    @Test
    fun `stage one records status without margin values`() {
        val report = observe(entryCommand(), baseContext())

        report.observations.forEach { observation ->
            assertNull(observation.marginValue, "Stage 1 records status only.")
        }
    }

    @Test
    fun `in memory repository keeps observations append only`() {
        runBlocking {
            val repository = InMemorySafetyFloorMarginRepository()
            val report = observe(entryCommand(), baseContext())

            repository.append(report).getOrThrow()
            repository.append(report).getOrThrow()

            assertEquals(1, repository.all().size)
            assertNotNull(repository.find(report.id).getOrThrow())
        }
    }

    private fun observe(command: PlaceOrderCommand, context: SafetyFloorContext): SafetyFloorObservationReport {
        val verdict = safetyFloor().evaluatePlaceOrder(command, context)

        return observer().observe(command, context, verdict, SafetyFloorCallSite.PLACE)
    }

    private fun safetyFloor(): SafetyFloor = SafetyFloor(config = observerConfig(), clock = fixedClock())

    private fun observer(): SafetyFloorMarginObserver {
        return SafetyFloorMarginObserver(config = observerConfig(), clock = fixedClock())
    }

    private fun observerConfig(): SafetyFloorConfig = SafetyFloorConfig()

    private fun scenarios(): List<Scenario> {
        val tradeGroupId = UUID.randomUUID()
        val approvedCommand = entryCommand()

        return listOf(
            Scenario("plain market entry", entryCommand(), baseContext()),
            Scenario(
                "approved intent",
                approvedCommand,
                baseContext().copy(entryIntent = approvedIntentSnapshot(approvedCommand)),
            ),
            Scenario("stop above entry", entryCommand(protectiveStopPriceJpy = BigDecimal("10500000")), baseContext()),
            Scenario("stop at zero", entryCommand(protectiveStopPriceJpy = BigDecimal.ZERO), baseContext()),
            Scenario("missing take profit", entryCommand(takeProfitPriceJpy = null), baseContext()),
            Scenario("probability out of range", entryCommand(estimatedWinProbability = BigDecimal("1.5")), baseContext()),
            Scenario("oversized order", entryCommand(sizeBtc = BigDecimal("5.0")), baseContext()),
            Scenario(
                "soft halt",
                entryCommand(),
                baseContext().copy(riskState = RiskState(state = RiskHaltState.SOFT_HALT, updatedAt = fixedInstant())),
            ),
            Scenario(
                "hard halt",
                entryCommand(),
                baseContext().copy(riskState = RiskState(state = RiskHaltState.HARD_HALT, updatedAt = fixedInstant())),
            ),
            Scenario(
                "unsafe taker fee",
                entryCommand(),
                baseContext(symbolRules = symbolRules(takerFee = "0.01")),
            ),
            Scenario(
                "pyramiding on a losing position",
                entryCommand(tradeGroupId = tradeGroupId),
                baseContext(positions = listOf(openPosition(tradeGroupId = tradeGroupId, unrealizedPnlJpy = "-5000"))),
            ),
            Scenario(
                "pyramiding on a winning position",
                entryCommand(tradeGroupId = tradeGroupId),
                baseContext(
                    positions = listOf(
                        openPosition(
                            tradeGroupId = tradeGroupId,
                            unrealizedPnlJpy = "5000",
                            currentPriceJpy = "10200000",
                            unrealizedR = "3.0",
                        ),
                    ),
                ),
            ),
        )
    }

    private data class Scenario(
        val name: String,
        val command: PlaceOrderCommand,
        val context: SafetyFloorContext,
    )

    private companion object {
        const val POINT_STICKY = SafetyFloorEvaluationPoints.POINT_STICKY_STATE
        const val POINT_THRESHOLD = SafetyFloorEvaluationPoints.POINT_THRESHOLD
        const val POINT_FEE = SafetyFloorEvaluationPoints.POINT_FEE
        const val POINT_CASH = SafetyFloorEvaluationPoints.POINT_CASH
    }
}

private fun SafetyFloorObservationReport.statusOf(rule: SafetyFloorRule, pointId: String? = null): RuleStatus {
    val matches = observations.filter { observation ->
        observation.point.rule == rule && (pointId == null || observation.point.pointId == pointId)
    }

    return matches.single().status
}

private fun SafetyFloorObservationReport.reasonOf(rule: SafetyFloorRule, pointId: String? = null): NaReason? {
    val matches = observations.filter { observation ->
        observation.point.rule == rule && (pointId == null || observation.point.pointId == pointId)
    }

    return matches.single().naReason
}

private fun SafetyFloorObservationReport.failedRules(): List<String> {
    return observations
        .filter { observation -> observation.status == RuleStatus.FAIL }
        .map { observation -> "${observation.point.rule}.${observation.point.pointId}" }
}

private fun entryCommand(
    orderType: OrderType = OrderType.MARKET,
    sizeBtc: BigDecimal = BigDecimal("0.0050"),
    priceJpy: BigDecimal? = null,
    tradeGroupId: UUID? = null,
    protectiveStopPriceJpy: BigDecimal = BigDecimal("9700000"),
    takeProfitPriceJpy: BigDecimal? = BigDecimal("10500000"),
    estimatedWinProbability: BigDecimal = BigDecimal("0.60"),
): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = UUID.randomUUID(),
        intentId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        side = OrderSide.BUY,
        orderType = orderType,
        sizeBtc = sizeBtc,
        priceJpy = priceJpy,
        tradeGroupId = tradeGroupId,
        protectiveStopPriceJpy = protectiveStopPriceJpy,
        takeProfitPriceJpy = takeProfitPriceJpy,
        estimatedWinProbability = estimatedWinProbability,
        reasonJa = "margin observer test entry",
        auditContext = PaperTradeAuditContext.EMPTY,
    )
}

private fun baseContext(
    positions: List<Position> = emptyList(),
    symbolRules: SymbolRules = symbolRules(),
): SafetyFloorContext {
    return SafetyFloorContext(
        account = accountSnapshot(),
        riskState = RiskState(updatedAt = fixedInstant()),
        positions = positions,
        openOrders = emptyList(),
        ticker = Ticker(
            symbol = "BTC",
            last = "10100000",
            bid = "10100000",
            ask = "10110000",
            high = "10100000",
            low = "10000000",
            volume = "1.0",
            timestamp = fixedInstant().toString(),
        ),
        symbolRules = symbolRules,
        atr14Jpy = BigDecimal("100000"),
    )
}

private fun symbolRules(takerFee: String = "0.0005", makerFee: String = "-0.0001"): SymbolRules {
    return SymbolRules(
        symbol = "BTC",
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "1",
        takerFee = takerFee,
        makerFee = makerFee,
    )
}

private fun accountSnapshot(): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = "100000.00000000",
        initialCashJpy = "100000.00000000",
        btcQuantity = "0.000000000000",
        btcMarkPriceJpy = "10000000.00000000",
        totalEquityJpy = "100000.00000000",
        equityPeakJpy = "100000.00000000",
        drawdownRatio = "0",
    )
}

private fun openPosition(
    tradeGroupId: UUID = UUID.randomUUID(),
    currentStopLossJpy: String? = "9700000",
    unrealizedPnlJpy: String = "0",
    currentPriceJpy: String = "10100000",
    unrealizedR: String = "0",
    pyramidAddCount: Int = 0,
): Position {
    return Position(
        positionId = UUID.randomUUID().toString(),
        tradeGroupId = tradeGroupId.toString(),
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = fixedInstant().toString(),
        closedAt = null,
        sizeBtc = "0.0100",
        averageEntryPriceJpy = "10100000",
        currentPriceJpy = currentPriceJpy,
        currentStopLossJpy = currentStopLossJpy,
        currentTakeProfitJpy = "10500000",
        unrealizedPnlJpy = unrealizedPnlJpy,
        unrealizedR = unrealizedR,
        pyramidAddCount = pyramidAddCount,
        highestPriceSinceEntryJpy = "10200000",
        lowestPriceSinceEntryJpy = "10000000",
    )
}

private fun approvedIntentSnapshot(command: PlaceOrderCommand): EntryIntentSafetySnapshot {
    val intentId = requireNotNull(command.intentId)

    return EntryIntentSafetySnapshot(
        tradeIntent = TradeIntentRecord(
            intentId = intentId,
            decisionId = UUID.randomUUID(),
            tradePlanId = UUID.randomUUID(),
            draft = EntryIntentDraft(
                symbol = command.symbol,
                side = command.side,
                orderType = command.orderType,
                sizeBtc = command.sizeBtc,
                priceJpy = command.priceJpy,
                protectiveStopPriceJpy = command.protectiveStopPriceJpy,
                takeProfitPriceJpy = command.takeProfitPriceJpy,
            ),
            estimatedWinProbability = command.estimatedWinProbability,
            createdAt = fixedInstant(),
        ),
        falsification = FalsificationRecord(
            falsificationId = UUID.randomUUID(),
            intentId = intentId,
            verdict = FalsificationVerdict.APPROVED,
            llmProvider = "codex",
            reasonJa = "margin observer test approval",
            createdAt = fixedInstant(),
        ),
        freshApproved = true,
        consumed = false,
    )
}

private fun fixedInstant(): Instant = Instant.parse("2026-07-17T00:00:00Z")

private fun fixedClock(): Clock = Clock.fixed(fixedInstant(), ZoneOffset.UTC)
