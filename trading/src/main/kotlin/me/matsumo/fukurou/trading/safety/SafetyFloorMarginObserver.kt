package me.matsumo.fukurou.trading.safety

import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.config.FomcBlackoutCalendarState
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.unsafeOrderFeeRateReasonOrNull
import me.matsumo.fukurou.trading.risk.RiskHaltState
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * SafetyFloor の evaluation point ごとに PASS / FAIL / NA を観測する。
 *
 * SafetyFloor の評価関数は呼ばず、同じ入力に対して独立に述語を評価する。verdict を
 * 生むコードに差分が入らないため、拒否判定の同値性は構造的に保たれる。その代償として
 * 述語が 2 箇所に存在するため、[observe] は verdict との整合を双方向に検査する。
 *
 * SafetyFloor と同一の [config] / [clock] / [paperExecutionConfig] を受け取ること。
 * 既定値を独自に構築すると、runtime config で閾値が変更されている場合に status は
 * 一致したまま margin だけが誤り、整合検査でも検出できない。
 *
 * @param config SafetyFloor と共有する安全床しきい値
 * @param clock SafetyFloor と共有する clock
 * @param paperExecutionConfig SafetyFloor と共有する paper 約定近似設定
 */
internal class SafetyFloorMarginObserver(
    private val config: SafetyFloorConfig = SafetyFloorConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val paperExecutionConfig: PaperExecutionConfig = PaperExecutionConfig(),
) {
    private val riskCalculator = SafetyFloorRiskCalculator(config, clock, paperExecutionConfig)
    private val maxDrawdownPolicy = MaxDrawdownPolicy(config.maxDrawdownRatio)

    /**
     * place_order 経路の全 evaluation point を観測し、verdict との整合を検査する。
     *
     * 観測の算出が全面的に失敗した場合も report を返す。呼び出し側が欠測として
     * 保存できるようにするため、ここでは例外を投げない。
     */
    fun observe(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        verdict: SafetyFloorVerdict,
        callSite: SafetyFloorCallSite,
    ): SafetyFloorObservationReport {
        val observedAt = Instant.now(clock)
        val rejectedRule = (verdict as? SafetyFloorVerdict.Rejected)?.violation?.rule
        val observations = runCatching { evaluateAll(command, context, observedAt) }.getOrNull()

        return SafetyFloorObservationReport(
            id = UUID.randomUUID(),
            path = SafetyFloorEvaluationPath.PLACE_ORDER,
            callSite = callSite,
            decisionRunId = command.auditContext.decisionRunContext.decisionRunId,
            commandId = command.commandId,
            verdict = if (rejectedRule == null) ObservedVerdict.ACCEPTED else ObservedVerdict.REJECTED,
            rejectedRule = rejectedRule,
            policyVersion = SafetyFloorDefaults.policyVersion,
            runtimeConfigVersion = command.auditContext.decisionRunContext.runtimeConfigVersionId,
            observationSchemaVersion = OBSERVATION_SCHEMA_VERSION,
            divergence = observations != null && hasDivergence(observations, rejectedRule),
            collectionFailed = observations == null,
            observations = observations.orEmpty(),
            observedAt = observedAt,
        )
    }

    /**
     * verdict と観測の整合が破れているかを判定する。
     *
     * `Accepted` は全 validator の通過を意味するため、観測側の FAIL は early-return では
     * 説明できず必ず乖離である。`Rejected` 方向は rule 単位に留まる。[SafetyViolation] が
     * 拒否を生んだ evaluation point を識別しないため、同一 rule の別 point の乖離までは
     * 絞り込めない。
     */
    private fun hasDivergence(observations: List<RuleObservation>, rejectedRule: SafetyFloorRule?): Boolean {
        if (rejectedRule == null) {
            return observations.any { observation -> observation.status == RuleStatus.FAIL }
        }

        return observations.none { observation ->
            observation.point.rule == rejectedRule && observation.status == RuleStatus.FAIL
        }
    }

    private fun evaluateAll(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        observedAt: Instant,
    ): List<RuleObservation> {
        val preconditions = Preconditions.of(
            command = command,
            context = context,
            config = config,
            riskCalculator = riskCalculator,
        )

        return SafetyFloorEvaluationPoints.placeOrderPoints.map { point ->
            evaluatePoint(point, command, context, observedAt, preconditions)
        }
    }

    private fun evaluatePoint(
        point: EvaluationPointId,
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        observedAt: Instant,
        preconditions: Preconditions,
    ): RuleObservation {
        return runCatching {
            statusOf(point, command, context, observedAt, preconditions)
        }.getOrElse { RuleStatus.NA to NaReason.EVALUATION_ERROR }
            .let { (status, reason) -> RuleObservation(point = point, status = status, naReason = reason) }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun statusOf(
        point: EvaluationPointId,
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        observedAt: Instant,
        preconditions: Preconditions,
    ): StatusResult {
        return when (point.rule) {
            SafetyFloorRule.MAX_DRAWDOWN_HALT -> drawdownStatus(point, context)
            SafetyFloorRule.SOFT_HALT_ENTRY_BLOCKED -> failWhen(context.riskState.state == RiskHaltState.SOFT_HALT)
            SafetyFloorRule.FOMC_CALENDAR_MISSING,
            SafetyFloorRule.FOMC_CALENDAR_INVALID,
            SafetyFloorRule.FOMC_CALENDAR_EXPIRED,
            SafetyFloorRule.ECONOMIC_EVENT_BLACKOUT,
            -> calendarStatus(point, observedAt)

            SafetyFloorRule.MISSING_FRESH_FALSIFICATION,
            SafetyFloorRule.INTENT_CONSUMED,
            SafetyFloorRule.INTENT_MISMATCH,
            -> falsifierStatus(point, command, context)

            SafetyFloorRule.STOP_LOSS_REQUIRED -> stopLossStatus(point, command, context)
            SafetyFloorRule.NO_AVERAGING_DOWN -> averagingDownStatus(point, preconditions)
            SafetyFloorRule.STOP_LOSS_LOOSENING -> stopLooseningStatus(command, preconditions)
            SafetyFloorRule.PYRAMID_ADD_LIMIT -> pyramidAddLimitStatus(preconditions)
            SafetyFloorRule.PYRAMID_PROFIT_GATE -> pyramidProfitStatus(preconditions)
            SafetyFloorRule.PYRAMID_ADD_RISK_LIMIT -> pyramidAddRiskStatus(command, context, preconditions)
            SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT -> balanceStatus(point, command, context, preconditions)
            SafetyFloorRule.MAX_RISK_PER_TRADE -> groupRiskStatus(command, context, preconditions)
            SafetyFloorRule.MAX_TOTAL_EXPOSURE -> totalExposureStatus(command, context, preconditions)
            SafetyFloorRule.INVALID_WIN_PROBABILITY -> winProbabilityStatus(command)
            SafetyFloorRule.MISSING_TARGET_PRICE -> failWhen(command.takeProfitPriceJpy == null)
            SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE,
            SafetyFloorRule.EXPECTED_VALUE_GATE,
            -> expectedValueStatus(point, command, context, preconditions)

            SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO -> expectedMoveStatus(command, context, preconditions)
            SafetyFloorRule.ATR_TRAILING_FLOOR,
            SafetyFloorRule.IMMEDIATE_STOP_TRIGGER,
            -> RuleStatus.NA to NaReason.NOT_APPLICABLE_PATH
        }
    }

    private fun drawdownStatus(point: EvaluationPointId, context: SafetyFloorContext): StatusResult {
        if (point.pointId == SafetyFloorEvaluationPoints.POINT_STICKY_STATE) {
            return failWhen(context.riskState.state == RiskHaltState.HARD_HALT)
        }

        val measuredDrawdown = minOf(
            context.account.drawdownRatio.toBigDecimal(),
            context.riskState.drawdownRatio,
        )

        return failWhen(maxDrawdownPolicy.isHardHalt(measuredDrawdown))
    }

    /**
     * calendar 由来の 4 point を評価する。
     *
     * calendar state は排他なので、state が確定しない point は `NA` とする。
     * `validThrough` は MISSING / INVALID のとき null なので、expiry は評価できない。
     */
    private fun calendarStatus(point: EvaluationPointId, observedAt: Instant): StatusResult {
        val state = config.fomcBlackoutCalendar.stateAt(observedAt)

        return when (point.rule) {
            SafetyFloorRule.FOMC_CALENDAR_MISSING -> failWhen(state == FomcBlackoutCalendarState.MISSING)
            SafetyFloorRule.FOMC_CALENDAR_INVALID -> failWhen(state == FomcBlackoutCalendarState.INVALID)
            SafetyFloorRule.FOMC_CALENDAR_EXPIRED -> expiryStatus(state)
            else -> blackoutStatus(state, observedAt)
        }
    }

    private fun expiryStatus(state: FomcBlackoutCalendarState): StatusResult {
        val evaluable = state == FomcBlackoutCalendarState.ACTIVE || state == FomcBlackoutCalendarState.EXPIRED

        if (!evaluable) return RuleStatus.NA to NaReason.MISSING_INPUT

        return failWhen(state == FomcBlackoutCalendarState.EXPIRED)
    }

    private fun blackoutStatus(state: FomcBlackoutCalendarState, observedAt: Instant): StatusResult {
        if (state != FomcBlackoutCalendarState.ACTIVE) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val inBlackout = config.fomcBlackoutCalendar.events.any { event -> event.contains(observedAt) }

        return failWhen(inBlackout)
    }

    /**
     * falsifier gate 由来の 3 point を評価する。
     *
     * verdict 側は intent の欠落から intent 一致検査まで段階的に評価するため、
     * 前段が成立しない point は `NA` とする。
     */
    private fun falsifierStatus(
        point: EvaluationPointId,
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
    ): StatusResult {
        val snapshot = context.entryIntent

        if (point.rule == SafetyFloorRule.MISSING_FRESH_FALSIFICATION) {
            val missing = command.intentId == null || snapshot == null || !snapshot.freshApproved

            return failWhen(missing)
        }

        if (command.intentId == null || snapshot == null) return RuleStatus.NA to NaReason.MISSING_INPUT

        if (point.rule == SafetyFloorRule.INTENT_CONSUMED) return failWhen(snapshot.consumed)

        if (snapshot.consumed || !snapshot.freshApproved) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        return failWhen(!intentMatchesCommand(command, snapshot.tradeIntent.draft))
    }

    private fun intentMatchesCommand(command: PlaceOrderCommand, draft: EntryIntentDraft): Boolean {
        return command.symbol == draft.symbol &&
            command.side == draft.side &&
            command.orderType == draft.orderType &&
            command.sizeBtc.compareTo(draft.sizeBtc) == 0 &&
            nullableAmountMatches(command.priceJpy, draft.priceJpy) &&
            command.protectiveStopPriceJpy.compareTo(draft.protectiveStopPriceJpy) == 0 &&
            nullableAmountMatches(command.takeProfitPriceJpy, draft.takeProfitPriceJpy)
    }

    private fun nullableAmountMatches(left: BigDecimal?, right: BigDecimal?): Boolean {
        if (left == null || right == null) return left == null && right == null

        return left.compareTo(right) == 0
    }

    private fun stopLossStatus(
        point: EvaluationPointId,
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
    ): StatusResult {
        val stopPrice = command.protectiveStopPriceJpy

        if (point.pointId == SafetyFloorEvaluationPoints.POINT_POSITIVE) {
            return failWhen(stopPrice <= BigDecimal.ZERO)
        }

        val entryPrice = riskCalculator.estimatedEntryPrice(command, context)

        return failWhen(stopPrice >= entryPrice)
    }

    private fun averagingDownStatus(point: EvaluationPointId, preconditions: Preconditions): StatusResult {
        // verdict は tradeGroupId が null でも全 open position を対象にするため、pyramid 用の
        // group 限定集合ではなく filterTargetPositions(tradeGroupId) の結果を使う。
        val positions = preconditions.averagingPositions

        if (positions.isEmpty()) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val losing = if (point.pointId == SafetyFloorEvaluationPoints.POINT_UNREALIZED_PNL) {
            positions.any { position -> position.unrealizedPnlJpy.toBigDecimal() < BigDecimal.ZERO }
        } else {
            positions.any { position ->
                position.currentPriceJpy.toBigDecimal() < position.averageEntryPriceJpy.toBigDecimal()
            }
        }

        return failWhen(losing)
    }

    /**
     * place 経路の STOP 緩和を評価する。
     *
     * verdict 側は [SafetyFloor] の pyramiding gate 経由でのみ評価するため、
     * trade group が未指定または対象 position が空なら評価そのものが行われない。
     * 対象 position を絞らずに評価すると、無関係な position で FAIL を作る。
     */
    private fun stopLooseningStatus(command: PlaceOrderCommand, preconditions: Preconditions): StatusResult {
        if (!preconditions.pyramidEvaluable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val comparable = preconditions.targetPositions.mapNotNull { position ->
            position.currentStopLossJpy?.toBigDecimal()
        }

        if (comparable.isEmpty()) return RuleStatus.NA to NaReason.MISSING_INPUT

        return failWhen(comparable.any { currentStop -> command.protectiveStopPriceJpy < currentStop })
    }

    private fun pyramidAddLimitStatus(preconditions: Preconditions): StatusResult {
        if (!preconditions.pyramidEvaluable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val maxAddCount = preconditions.targetPositions.maxOf { position -> position.pyramidAddCount }

        return failWhen(maxAddCount >= MAX_PYRAMID_ADD_COUNT)
    }

    private fun pyramidProfitStatus(preconditions: Preconditions): StatusResult {
        if (!preconditions.pyramidEvaluable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val insufficient = preconditions.targetPositions.any { position ->
            position.unrealizedR.toBigDecimal() < BigDecimal(position.pyramidAddCount + 1)
        }

        return failWhen(insufficient)
    }

    private fun pyramidAddRiskStatus(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        preconditions: Preconditions,
    ): StatusResult {
        if (!preconditions.pyramidEvaluable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET
        if (!preconditions.feeParseable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val tradeGroupId = preconditions.tradeGroupId ?: return RuleStatus.NA to NaReason.PRECONDITION_UNMET
        val budget = riskCalculator.initialTradeGroupRiskBudget(context, tradeGroupId)
            ?: riskCalculator.groupRiskBeforeOrder(context, tradeGroupId)
        val addRiskLimit = budget.multiply(PYRAMID_ADD_RISK_RATIO).safetyScale()

        return failWhen(riskCalculator.orderRisk(command, context) > addRiskLimit)
    }

    private fun balanceStatus(
        point: EvaluationPointId,
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        preconditions: Preconditions,
    ): StatusResult {
        if (point.pointId == SafetyFloorEvaluationPoints.POINT_FEE) {
            return failWhen(!preconditions.feeParseable || preconditions.unsafeFeeReason != null)
        }

        if (!preconditions.feeParseable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val availableCash = riskCalculator.availableCash(context)
        val requiredCash = riskCalculator.orderRequiredCash(command, context)

        return failWhen(requiredCash > availableCash)
    }

    private fun groupRiskStatus(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        preconditions: Preconditions,
    ): StatusResult {
        if (!preconditions.feeParseable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val limit = context.account.totalEquityJpy.toBigDecimal().multiply(config.maxRiskPerTradeRatio).safetyScale()

        return failWhen(riskCalculator.groupRiskAfterOrder(command, context) > limit)
    }

    private fun totalExposureStatus(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        preconditions: Preconditions,
    ): StatusResult {
        if (!preconditions.feeParseable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val limit = context.account.totalEquityJpy.toBigDecimal().multiply(config.maxTotalExposureRatio).safetyScale()
        val exposureAfterOrder = riskCalculator.currentExposure(context)
            .add(riskCalculator.orderExposure(command, context))
            .safetyScale()

        return failWhen(exposureAfterOrder > limit)
    }

    private fun winProbabilityStatus(command: PlaceOrderCommand): StatusResult {
        val probability = command.estimatedWinProbability
        val inRange = probability >= BigDecimal.ZERO && probability <= BigDecimal.ONE

        return failWhen(!inRange)
    }

    /**
     * EV 系 2 point を評価する。
     *
     * stop が entry 以上だと risk が 0 になり EV が退化するため、その入力では
     * 値を算出せず `NA` とする。退化した値を FAIL として記録すると、閾値調整の
     * 判断材料を汚染する。
     */
    private fun expectedValueStatus(
        point: EvaluationPointId,
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        preconditions: Preconditions,
    ): StatusResult {
        if (!preconditions.feeParseable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET
        if (!preconditions.stopBelowEntry) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val takeProfitPrice = command.takeProfitPriceJpy ?: return RuleStatus.NA to NaReason.MISSING_INPUT
        val probability = command.estimatedWinProbability

        if (probability < BigDecimal.ZERO || probability > BigDecimal.ONE) {
            return RuleStatus.NA to NaReason.PRECONDITION_UNMET
        }

        val expectedValueR = riskCalculator.expectedValueDetails(command, context, takeProfitPrice).expectedValueR

        if (point.rule == SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE) {
            return failWhen(expectedValueR <= BigDecimal.ZERO)
        }

        // EV が非正でも gate 条件（EV >= minExpectedValueR）は不成立なので FAIL とする。
        // NA にすると verdict が拒否した decision の FAIL 分布を欠落させる。
        return failWhen(expectedValueR < config.minExpectedValueR)
    }

    private fun expectedMoveStatus(
        command: PlaceOrderCommand,
        context: SafetyFloorContext,
        preconditions: Preconditions,
    ): StatusResult {
        if (!preconditions.feeParseable) return RuleStatus.NA to NaReason.PRECONDITION_UNMET

        val ratio = riskCalculator.expectedMoveToCostRatioOrNull(command, context)
            ?: return RuleStatus.NA to NaReason.MISSING_INPUT

        return failWhen(ratio < config.minExpectedMoveToCostRatio)
    }

    private fun failWhen(failed: Boolean): StatusResult {
        return if (failed) RuleStatus.FAIL to null else RuleStatus.PASS to null
    }

    /**
     * evaluation point 間で共有する事前条件。
     *
     * verdict 側では validator の入れ子や宣言順が担っていた保護を、observer では
     * 明示的な事前条件として持つ。
     *
     * @param tradeGroupId 対象 trade group
     * @param averagingPositions ナンピン判定の対象 position。tradeGroupId が null なら全 open position
     * @param targetPositions pyramiding gate の対象 position。tradeGroupId が null なら空
     * @param pyramidEvaluable pyramiding gate 由来の point を評価できるか
     * @param feeParseable fee 文字列が数値として解釈できるか
     * @param unsafeFeeReason fee rate が許容範囲外である理由。安全なら null
     * @param stopBelowEntry STOP が想定 entry 価格を下回るか
     */
    private data class Preconditions(
        val tradeGroupId: String?,
        val averagingPositions: List<Position>,
        val targetPositions: List<Position>,
        val pyramidEvaluable: Boolean,
        val feeParseable: Boolean,
        val unsafeFeeReason: String?,
        val stopBelowEntry: Boolean,
    ) {
        companion object {
            fun of(
                command: PlaceOrderCommand,
                context: SafetyFloorContext,
                config: SafetyFloorConfig,
                riskCalculator: SafetyFloorRiskCalculator,
            ): Preconditions {
                val tradeGroupId = command.tradeGroupId?.toString()
                // verdict の validateNoAveragingDown は tradeGroupId が null でも全 open position を
                // 対象にする（filterTargetPositions(null) は全件を返す）。pyramid gate だけが null で対象外。
                val averagingPositions = context.positions.filterTargetPositions(tradeGroupId)
                val targetPositions = if (tradeGroupId != null) averagingPositions else emptyList()
                val feeParseable = context.symbolRules.takerFee.toBigDecimalOrNull() != null &&
                    context.symbolRules.makerFee.toBigDecimalOrNull() != null
                val stopBelowEntry = feeParseable && runCatching {
                    command.protectiveStopPriceJpy < riskCalculator.estimatedEntryPrice(command, context)
                }.getOrDefault(false)

                return Preconditions(
                    tradeGroupId = tradeGroupId,
                    averagingPositions = averagingPositions,
                    targetPositions = targetPositions,
                    pyramidEvaluable = tradeGroupId != null && targetPositions.isNotEmpty(),
                    feeParseable = feeParseable,
                    unsafeFeeReason = unsafeOrderFeeRateReasonOrNull(
                        symbolRules = context.symbolRules,
                        maxFeeRate = config.maxTakerFeeRatio,
                    ),
                    stopBelowEntry = stopBelowEntry,
                )
            }
        }
    }

    companion object {
        /** 観測が保持する情報の世代。Stage 1 は status のみを保持する。 */
        private const val OBSERVATION_SCHEMA_VERSION: Int = 1

        /**
         * SafetyFloor と同一の設定を共有する observer を作る。
         *
         * config を並行して渡すのではなく SafetyFloor が持つインスタンスをそのまま使うため、
         * 閾値の乖離が構造的に起こらない。
         */
        fun sharing(safetyFloor: SafetyFloor): SafetyFloorMarginObserver {
            return SafetyFloorMarginObserver(
                config = safetyFloor.config,
                clock = safetyFloor.clock,
                paperExecutionConfig = safetyFloor.paperExecutionConfig,
            )
        }
    }
}

/** evaluation point の判定結果と、`NA` の場合の理由。 */
private typealias StatusResult = Pair<RuleStatus, NaReason?>
