package me.matsumo.fukurou.trading.safety

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.EntryIntentSafetySnapshot
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.Order
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
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskState
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun `flags divergence when the observer is stricter than the verdict`() {
        // verdict は既定の 2% risk 上限で通過するが、observer に極端に厳しい上限を与えると
        // MAX_RISK_PER_TRADE などが FAIL になり、Accepted と食い違う。乖離検査がこれを捕らえる。
        val command = acceptedEntry()
        val context = acceptedContext(command)
        val verdict = safetyFloor().evaluatePlaceOrder(command, context)
        assertIs<SafetyFloorVerdict.Accepted>(verdict)

        val strictObserver = SafetyFloorMarginObserver(
            config = SafetyFloorConfig(maxRiskPerTradeRatio = BigDecimal("0.0000001")),
            clock = fixedClock(),
        )
        val report = strictObserver.observe(command, context, verdict, SafetyFloorCallSite.PLACE)

        assertTrue(report.divergence, "An observer stricter than the verdict must be flagged as divergent.")
    }

    @Test
    fun `does not flag divergence when the verdict rejects and the rule fails`() {
        val command = entryCommand(protectiveStopPriceJpy = BigDecimal("11000000"))
        val report = observe(command, baseContext())

        assertFalse(report.divergence)
        assertEquals(RuleStatus.FAIL, report.statusOf(SafetyFloorRule.STOP_LOSS_REQUIRED, POINT_BELOW_ENTRY))
    }

    @Test
    fun `agrees with the verdict on an approved entry that reaches deep predicates`() {
        // approved intent を与えると falsifier gate を越え、stop / risk / EV / exposure の
        // 述語まで実際に評価される。全 27 point が verdict と整合することを確認する。
        val command = acceptedEntry()
        val context = acceptedContext(command)
        val verdict = safetyFloor().evaluatePlaceOrder(command, context)
        assertIs<SafetyFloorVerdict.Accepted>(verdict)

        val report = observer().observe(command, context, verdict, SafetyFloorCallSite.PLACE)

        assertFalse(report.divergence)
        assertEquals(RuleStatus.PASS, report.statusOf(SafetyFloorRule.MISSING_FRESH_FALSIFICATION))
        assertEquals(RuleStatus.PASS, report.statusOf(SafetyFloorRule.STOP_LOSS_REQUIRED, POINT_BELOW_ENTRY))
        assertEquals(RuleStatus.PASS, report.statusOf(SafetyFloorRule.MAX_RISK_PER_TRADE))
        assertEquals(RuleStatus.PASS, report.statusOf(SafetyFloorRule.EXPECTED_VALUE_GATE))
    }

    @Test
    fun `evaluates averaging down across all open positions without a trade group`() {
        // verdict は tradeGroupId が null でも全 open position でナンピンを判定する。
        val losing = openPosition(currentPriceJpy = "9000000", unrealizedPnlJpy = "-5000")
        val command = entryCommand()
        val context = baseContext(positions = listOf(losing)).copy(entryIntent = approvedIntentSnapshot(command))
        val verdict = safetyFloor().evaluatePlaceOrder(command, context)
        assertIs<SafetyFloorVerdict.Rejected>(verdict)
        assertEquals(SafetyFloorRule.NO_AVERAGING_DOWN, verdict.violation.rule)

        val report = observer().observe(command, context, verdict, SafetyFloorCallSite.PLACE)

        assertFalse(report.divergence, "NO_AVERAGING_DOWN must be evaluated on all open positions when no trade group.")
        assertEquals(RuleStatus.FAIL, report.statusOf(SafetyFloorRule.NO_AVERAGING_DOWN, POINT_UNREALIZED_PNL))
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
    fun `drawdown status agrees with the verdict immediately before the threshold`() {
        val command = acceptedEntry()
        val context = acceptedContext(command).copy(
            account = acceptedContext(command).account.copy(drawdownRatio = "-0.1499999999"),
        )
        val verdict = safetyFloor().evaluatePlaceOrder(command, context)
        assertIs<SafetyFloorVerdict.Accepted>(verdict)

        val report = observer().observe(command, context, verdict, SafetyFloorCallSite.PLACE)

        assertEquals(RuleStatus.PASS, report.statusOf(SafetyFloorRule.MAX_DRAWDOWN_HALT, POINT_THRESHOLD))
        assertFalse(report.divergence)
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
        assertEquals(2, report.observationSchemaVersion)
    }

    @Test
    fun `records margins only for evaluable numeric points`() {
        val report = observe(entryCommand(), baseContext())

        report.observations.forEach { observation ->
            if (observation.point.isNumeric && observation.status != RuleStatus.NA) {
                assertNotNull(observation.marginValue, "${observation.point} must carry a numeric margin.")
            } else {
                assertNull(observation.marginValue, "BOOLEAN and NA points must not carry a margin.")
            }
        }
    }

    @Test
    fun `records positive room for passing margins and negative excess for failing margins`() {
        val command = acceptedEntry()
        val context = acceptedContext(command)
        val passing = observer().observe(
            command = command,
            context = context,
            verdict = safetyFloor().evaluatePlaceOrder(command, context),
            callSite = SafetyFloorCallSite.PLACE,
        )
        val strictConfig = SafetyFloorConfig(maxRiskPerTradeRatio = BigDecimal("0.0000001"))
        val failing = SafetyFloorMarginObserver(strictConfig, fixedClock()).observe(
            command = command,
            context = context,
            verdict = SafetyFloor(config = strictConfig, clock = fixedClock()).evaluatePlaceOrder(command, context),
            callSite = SafetyFloorCallSite.PLACE,
        )

        assertTrue(passing.marginOf(SafetyFloorRule.MAX_RISK_PER_TRADE) > BigDecimal.ZERO)
        assertEquals(RuleStatus.PASS, passing.statusOf(SafetyFloorRule.MAX_RISK_PER_TRADE))
        assertTrue(failing.marginOf(SafetyFloorRule.MAX_RISK_PER_TRADE) < BigDecimal.ZERO)
        assertEquals(RuleStatus.FAIL, failing.statusOf(SafetyFloorRule.MAX_RISK_PER_TRADE))
    }

    @Test
    fun `records every non-pyramid numeric margin using the design formulas`() {
        val command = acceptedEntry()
        val context = acceptedContext(command)
        val config = observerConfig()
        val report = SafetyFloorMarginObserver(config, fixedClock()).observe(
            command = command,
            context = context,
            verdict = SafetyFloor(config = config, clock = fixedClock()).evaluatePlaceOrder(command, context),
            callSite = SafetyFloorCallSite.PLACE,
        )
        val details = SafetyFloor(config = config, clock = fixedClock()).placeOrderRiskDetails(command, context)
        val calendar = config.fomcBlackoutCalendar

        assertMargin(report, SafetyFloorRule.MAX_DRAWDOWN_HALT, POINT_THRESHOLD, BigDecimal("0.15"))
        assertMargin(
            report,
            SafetyFloorRule.FOMC_CALENDAR_EXPIRED,
            expected = BigDecimal.valueOf(Duration.between(fixedInstant(), requireNotNull(calendar.validThrough)).seconds),
        )
        assertMargin(
            report,
            SafetyFloorRule.ECONOMIC_EVENT_BLACKOUT,
            expected = calendar.events.minOf { event ->
                val window = requireNotNull(event.toSafeWindow())
                maxOf(
                    BigDecimal.valueOf(Duration.between(fixedInstant(), window.startsAt).seconds),
                    BigDecimal.valueOf(Duration.between(window.endsAt, fixedInstant()).seconds),
                )
            },
        )
        assertMargin(report, SafetyFloorRule.STOP_LOSS_REQUIRED, POINT_POSITIVE, command.protectiveStopPriceJpy)
        assertMargin(
            report,
            SafetyFloorRule.STOP_LOSS_REQUIRED,
            POINT_BELOW_ENTRY,
            details.estimatedEntryPriceJpy.toBigDecimal().subtract(command.protectiveStopPriceJpy),
        )
        assertMargin(
            report,
            SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT,
            POINT_CASH,
            details.availableCashJpy.toBigDecimal().subtract(details.requiredCashJpy.toBigDecimal()),
        )
        assertMargin(
            report,
            SafetyFloorRule.MAX_RISK_PER_TRADE,
            expected = details.maxRiskPerTradeJpy.toBigDecimal().subtract(details.groupRiskAfterOrderJpy.toBigDecimal()),
        )
        assertMargin(
            report,
            SafetyFloorRule.MAX_TOTAL_EXPOSURE,
            expected = details.maxTotalExposureJpy.toBigDecimal()
                .subtract(details.totalExposureAfterOrderJpy.toBigDecimal()),
        )
        assertMargin(
            report,
            SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE,
            expected = requireNotNull(details.expectedValueR).toBigDecimal(),
        )
        assertMargin(
            report,
            SafetyFloorRule.EXPECTED_VALUE_GATE,
            expected = details.expectedValueR.toBigDecimal().subtract(config.minExpectedValueR),
        )
        assertMargin(
            report,
            SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO,
            expected = requireNotNull(details.expectedMoveToCostRatio).toBigDecimal()
                .subtract(config.minExpectedMoveToCostRatio),
        )
    }

    @Test
    fun `records every position and pyramid margin using the design formulas`() {
        val tradeGroupId = UUID.randomUUID()
        val command = acceptedEntry().copy(tradeGroupId = tradeGroupId)
        val position = openPosition(
            tradeGroupId = tradeGroupId,
            currentStopLossJpy = "9600000",
            unrealizedPnlJpy = "5000",
            currentPriceJpy = "10200000",
            unrealizedR = "3.0",
            pyramidAddCount = 1,
        )
        val initialOrder = filledInitialOrder(tradeGroupId)
        val context = acceptedContext(command).copy(
            positions = listOf(position),
            tradeGroupOrders = listOf(initialOrder),
        )
        val config = observerConfig()
        val report = SafetyFloorMarginObserver(config, fixedClock()).observe(
            command = command,
            context = context,
            verdict = SafetyFloor(config = config, clock = fixedClock()).evaluatePlaceOrder(command, context),
            callSite = SafetyFloorCallSite.PLACE,
        )
        val calculator = SafetyFloorRiskCalculator(
            config = config,
            clock = fixedClock(),
            paperExecutionConfig = PaperExecutionConfig(),
        )
        val details = calculator.placeOrderRiskDetails(command, context)
        val initialBudget = requireNotNull(calculator.initialTradeGroupRiskBudget(context, tradeGroupId.toString()))
        val expectedAddRiskMargin = initialBudget.multiply(PYRAMID_ADD_RISK_RATIO)
            .safetyScale()
            .subtract(details.orderRiskJpy.toBigDecimal())

        assertMargin(report, SafetyFloorRule.STOP_LOSS_LOOSENING, expected = BigDecimal("100000"))
        assertMargin(report, SafetyFloorRule.NO_AVERAGING_DOWN, POINT_UNREALIZED_PNL, BigDecimal("5000"))
        assertMargin(report, SafetyFloorRule.NO_AVERAGING_DOWN, POINT_PRICE_DIFF, BigDecimal("100000"))
        assertMargin(report, SafetyFloorRule.PYRAMID_ADD_LIMIT, expected = BigDecimal("1"))
        assertMargin(report, SafetyFloorRule.PYRAMID_PROFIT_GATE, expected = BigDecimal("1"))
        assertMargin(report, SafetyFloorRule.PYRAMID_ADD_RISK_LIMIT, expected = expectedAddRiskMargin)
        assertFalse(
            report.marginOf(SafetyFloorRule.PYRAMID_ADD_RISK_LIMIT)
                .compareTo(
                    details.groupRiskBeforeOrderJpy.toBigDecimal()
                        .multiply(PYRAMID_ADD_RISK_RATIO)
                        .subtract(details.orderRiskJpy.toBigDecimal())
                        .safetyScale(),
                ) == 0,
            "The initial trade-group risk budget must take precedence over current group risk.",
        )
    }

    @Test
    fun `pyramid profit status agrees with the verdict immediately below required R`() {
        val tradeGroupId = UUID.randomUUID()
        val command = acceptedEntry().copy(tradeGroupId = tradeGroupId)
        val context = acceptedContext(command).copy(
            positions = listOf(
                openPosition(
                    tradeGroupId = tradeGroupId,
                    currentStopLossJpy = "9600000",
                    unrealizedPnlJpy = "5000",
                    currentPriceJpy = "10200000",
                    unrealizedR = "1.9999999999",
                    pyramidAddCount = 1,
                ),
            ),
        )
        val verdict = safetyFloor().evaluatePlaceOrder(command, context)
        val rejected = assertIs<SafetyFloorVerdict.Rejected>(verdict)
        assertEquals(SafetyFloorRule.PYRAMID_PROFIT_GATE, rejected.violation.rule)

        val report = observer().observe(command, context, verdict, SafetyFloorCallSite.PLACE)

        assertEquals(RuleStatus.FAIL, report.statusOf(SafetyFloorRule.PYRAMID_PROFIT_GATE))
        assertFalse(report.divergence)
    }

    @Test
    fun `expected value status agrees with a higher precision runtime threshold`() {
        val command = acceptedEntry()
        val context = acceptedContext(command)
        val calculator = SafetyFloorRiskCalculator(
            config = observerConfig(),
            clock = fixedClock(),
            paperExecutionConfig = PaperExecutionConfig(),
        )
        val expectedValueR = calculator.expectedValueDetails(
            command,
            context,
            requireNotNull(command.takeProfitPriceJpy),
        ).expectedValueR
        val config = SafetyFloorConfig(
            minExpectedValueR = expectedValueR.add(BigDecimal("0.0000000049")),
        )
        val safetyFloor = SafetyFloor(config = config, clock = fixedClock())
        val verdict = safetyFloor.evaluatePlaceOrder(command, context)
        val rejected = assertIs<SafetyFloorVerdict.Rejected>(verdict)
        assertEquals(SafetyFloorRule.EXPECTED_VALUE_GATE, rejected.violation.rule)

        val report = SafetyFloorMarginObserver(config = config, clock = fixedClock()).observe(
            command,
            context,
            verdict,
            SafetyFloorCallSite.PLACE,
        )

        assertEquals(RuleStatus.FAIL, report.statusOf(SafetyFloorRule.EXPECTED_VALUE_GATE))
        assertFalse(report.divergence)
    }

    @Test
    fun `economic blackout margin is positive before and after a window and negative inside`() {
        val eventAt = fixedInstant().plusSeconds(100)
        val events = listOf(
            EconomicEventBlackout("cpi-test", "CPI", eventAt, Duration.ofSeconds(10), Duration.ofSeconds(10)),
            EconomicEventBlackout("fomc-future", "FOMC", eventAt.plusSeconds(10_000), Duration.ZERO, Duration.ZERO),
        )

        listOf(
            fixedInstant().plusSeconds(80) to BigDecimal("10"),
            fixedInstant().plusMillis(89_500) to BigDecimal("0.5"),
            fixedInstant().plusSeconds(100) to BigDecimal("-10"),
            fixedInstant().plusSeconds(120) to BigDecimal("10"),
        ).forEach { (observedAt, expected) ->
            val report = observeAt(observedAt, events)

            assertMargin(report, SafetyFloorRule.ECONOMIC_EVENT_BLACKOUT, expected = expected)
        }
    }

    @Test
    fun `economic blackout status agrees with the verdict one nanosecond before the window`() {
        val startsAt = fixedInstant().plusSeconds(100)
        val observedAt = startsAt.minusNanos(1)
        val events = listOf(
            EconomicEventBlackout("cpi-boundary", "CPI", startsAt, Duration.ZERO, Duration.ZERO),
            EconomicEventBlackout(
                "fomc-future",
                "FOMC",
                startsAt.plusSeconds(10_000),
                Duration.ZERO,
                Duration.ZERO,
            ),
        )
        val config = SafetyFloorConfig(economicEventBlackouts = events)
        val command = acceptedEntry()
        val context = acceptedContext(command)
        val clock = Clock.fixed(observedAt, ZoneOffset.UTC)
        val verdict = SafetyFloor(config = config, clock = clock).evaluatePlaceOrder(command, context)
        assertIs<SafetyFloorVerdict.Accepted>(verdict)

        val report = SafetyFloorMarginObserver(config = config, clock = clock).observe(
            command,
            context,
            verdict,
            SafetyFloorCallSite.PLACE,
        )

        assertEquals(RuleStatus.PASS, report.statusOf(SafetyFloorRule.ECONOMIC_EVENT_BLACKOUT))
        assertFalse(report.divergence)
    }

    @Test
    fun `FOMC calendar expiry passes at the valid through boundary`() {
        val events = listOf(
            EconomicEventBlackout("fomc-boundary", "FOMC", fixedInstant(), Duration.ZERO, Duration.ZERO),
        )
        val report = observeAt(fixedInstant(), events)

        assertEquals(RuleStatus.PASS, report.statusOf(SafetyFloorRule.FOMC_CALENDAR_EXPIRED))
        assertMargin(report, SafetyFloorRule.FOMC_CALENDAR_EXPIRED, expected = BigDecimal.ZERO)
    }

    @Test
    fun `FOMC expiry status agrees with the verdict one nanosecond after valid through`() {
        val validThrough = fixedInstant()
        val observedAt = validThrough.plusNanos(1)
        val config = SafetyFloorConfig(
            economicEventBlackouts = listOf(
                EconomicEventBlackout("fomc-boundary", "FOMC", validThrough, Duration.ZERO, Duration.ZERO),
            ),
        )
        val command = acceptedEntry()
        val context = acceptedContext(command)
        val clock = Clock.fixed(observedAt, ZoneOffset.UTC)
        val verdict = SafetyFloor(config = config, clock = clock).evaluatePlaceOrder(command, context)
        val rejected = assertIs<SafetyFloorVerdict.Rejected>(verdict)
        assertEquals(SafetyFloorRule.FOMC_CALENDAR_EXPIRED, rejected.violation.rule)

        val report = SafetyFloorMarginObserver(config = config, clock = clock).observe(
            command,
            context,
            verdict,
            SafetyFloorCallSite.PLACE,
        )

        assertEquals(RuleStatus.FAIL, report.statusOf(SafetyFloorRule.FOMC_CALENDAR_EXPIRED))
        assertFalse(report.divergence)
    }

    @Test
    fun `records the design unit for every numeric point`() {
        val expected = mapOf(
            SafetyFloorRule.MAX_DRAWDOWN_HALT to mapOf(POINT_THRESHOLD to MarginUnit.RATIO),
            SafetyFloorRule.FOMC_CALENDAR_EXPIRED to mapOf(DEFAULT_POINT to MarginUnit.SECONDS),
            SafetyFloorRule.ECONOMIC_EVENT_BLACKOUT to mapOf(DEFAULT_POINT to MarginUnit.SECONDS),
            SafetyFloorRule.STOP_LOSS_REQUIRED to mapOf(
                POINT_POSITIVE to MarginUnit.JPY,
                POINT_BELOW_ENTRY to MarginUnit.JPY,
            ),
            SafetyFloorRule.NO_AVERAGING_DOWN to mapOf(
                POINT_UNREALIZED_PNL to MarginUnit.JPY,
                POINT_PRICE_DIFF to MarginUnit.JPY_PER_BTC,
            ),
            SafetyFloorRule.STOP_LOSS_LOOSENING to mapOf(DEFAULT_POINT to MarginUnit.JPY),
            SafetyFloorRule.PYRAMID_ADD_LIMIT to mapOf(DEFAULT_POINT to MarginUnit.COUNT),
            SafetyFloorRule.PYRAMID_PROFIT_GATE to mapOf(DEFAULT_POINT to MarginUnit.R),
            SafetyFloorRule.PYRAMID_ADD_RISK_LIMIT to mapOf(DEFAULT_POINT to MarginUnit.JPY),
            SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT to mapOf(POINT_CASH to MarginUnit.JPY),
            SafetyFloorRule.MAX_RISK_PER_TRADE to mapOf(DEFAULT_POINT to MarginUnit.JPY),
            SafetyFloorRule.MAX_TOTAL_EXPOSURE to mapOf(DEFAULT_POINT to MarginUnit.JPY),
            SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE to mapOf(DEFAULT_POINT to MarginUnit.R),
            SafetyFloorRule.EXPECTED_VALUE_GATE to mapOf(DEFAULT_POINT to MarginUnit.R),
            SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO to mapOf(DEFAULT_POINT to MarginUnit.RATIO),
        )
        val actual = SafetyFloorEvaluationPoints.placeOrderPoints
            .filter { point -> point.isNumeric }
            .groupBy { point -> point.rule }
            .mapValues { (_, points) -> points.associate { point -> point.pointId to requireNotNull(point.marginUnit) } }

        assertEquals(expected, actual)
    }

    @Test
    fun `marks missing TP and all-null stops as NA without margins`() {
        val missingTargetReport = observe(entryCommand(takeProfitPriceJpy = null), baseContext())
        assertEquals(RuleStatus.NA, missingTargetReport.statusOf(SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO))
        assertNull(missingTargetReport.observationOf(SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO).marginValue)

        val tradeGroupId = UUID.randomUUID()
        val command = entryCommand(tradeGroupId = tradeGroupId)
        val allNullStopsReport = observe(
            command,
            baseContext(positions = listOf(openPosition(tradeGroupId = tradeGroupId, currentStopLossJpy = null))),
        )
        assertEquals(RuleStatus.NA, allNullStopsReport.statusOf(SafetyFloorRule.STOP_LOSS_LOOSENING))
        assertNull(allNullStopsReport.observationOf(SafetyFloorRule.STOP_LOSS_LOOSENING).marginValue)
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

    private fun observeAt(observedAt: Instant, events: List<EconomicEventBlackout>): SafetyFloorObservationReport {
        val config = SafetyFloorConfig(economicEventBlackouts = events)
        val command = acceptedEntry()
        val context = acceptedContext(command)
        val clock = Clock.fixed(observedAt, ZoneOffset.UTC)
        val verdict = SafetyFloor(config = config, clock = clock).evaluatePlaceOrder(command, context)

        return SafetyFloorMarginObserver(config = config, clock = clock).observe(
            command = command,
            context = context,
            verdict = verdict,
            callSite = SafetyFloorCallSite.PLACE,
        )
    }

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
        const val POINT_BELOW_ENTRY = SafetyFloorEvaluationPoints.POINT_BELOW_ENTRY
        const val POINT_POSITIVE = SafetyFloorEvaluationPoints.POINT_POSITIVE
        const val POINT_UNREALIZED_PNL = SafetyFloorEvaluationPoints.POINT_UNREALIZED_PNL
        const val POINT_PRICE_DIFF = SafetyFloorEvaluationPoints.POINT_PRICE_DIFF
        const val DEFAULT_POINT = EvaluationPointId.DEFAULT_POINT_ID
    }
}

private fun assertMargin(
    report: SafetyFloorObservationReport,
    rule: SafetyFloorRule,
    pointId: String? = null,
    expected: BigDecimal,
) {
    assertEquals(expected.safetyScale(), report.marginOf(rule, pointId), "$rule.$pointId")
}

private fun SafetyFloorObservationReport.observationOf(
    rule: SafetyFloorRule,
    pointId: String? = null,
): RuleObservation {
    return observations.single { observation ->
        observation.point.rule == rule && (pointId == null || observation.point.pointId == pointId)
    }
}

private fun SafetyFloorObservationReport.marginOf(rule: SafetyFloorRule, pointId: String? = null): BigDecimal {
    return requireNotNull(observationOf(rule, pointId).marginValue)
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

/** verdict が Accepted になる entry。大きな reward と高い勝率で EV gate を確実に通す。 */
private fun acceptedEntry(): PlaceOrderCommand {
    return entryCommand(
        takeProfitPriceJpy = BigDecimal("11000000"),
        estimatedWinProbability = BigDecimal("0.90"),
    )
}

/** [acceptedEntry] を通す context。approved intent と余裕のある残高を与える。 */
private fun acceptedContext(command: PlaceOrderCommand): SafetyFloorContext {
    return baseContext().copy(
        account = AccountSnapshot(
            mode = TradingMode.PAPER,
            cashJpy = "100000.00000000",
            initialCashJpy = "200000.00000000",
            btcQuantity = "0.000000000000",
            btcMarkPriceJpy = "10000000.00000000",
            totalEquityJpy = "200000.00000000",
            equityPeakJpy = "200000.00000000",
            drawdownRatio = "0",
        ),
        entryIntent = approvedIntentSnapshot(command),
        marketDataObservedAt = fixedInstant(),
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

private fun filledInitialOrder(tradeGroupId: UUID): Order {
    return Order(
        orderId = UUID.randomUUID().toString(),
        intentId = UUID.randomUUID().toString(),
        positionId = UUID.randomUUID().toString(),
        tradeGroupId = tradeGroupId.toString(),
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        status = OrderStatus.FILLED,
        sizeBtc = "0.0100",
        limitPriceJpy = "10000000",
        triggerPriceJpy = null,
        protectiveStopPriceJpy = "9900000",
        takeProfitPriceJpy = "10500000",
        estimatedWinProbability = "0.60",
        reasonJa = "initial trade group order",
        clientRequestId = null,
        createdAt = fixedInstant().minusSeconds(60).toString(),
        updatedAt = fixedInstant().minusSeconds(60).toString(),
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
