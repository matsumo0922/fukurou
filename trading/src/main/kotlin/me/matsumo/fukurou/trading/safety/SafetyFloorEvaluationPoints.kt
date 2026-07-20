package me.matsumo.fukurou.trading.safety

/**
 * SafetyFloor rule を evaluation point へ展開した定義。
 *
 * 1 rule が複数の validator から出る場合と、複数の独立した条件を持つ場合があるため、
 * 観測は rule 単位ではなく evaluation point 単位で行う。
 */
object SafetyFloorEvaluationPoints {

    /** `MAX_DRAWDOWN_HALT` の sticky halt state 側。 */
    const val POINT_STICKY_STATE: String = "stickyState"

    /** `MAX_DRAWDOWN_HALT` の数値閾値側。 */
    const val POINT_THRESHOLD: String = "threshold"

    /** `STOP_LOSS_REQUIRED` の STOP が正であることの検査。 */
    const val POINT_POSITIVE: String = "positive"

    /** `STOP_LOSS_REQUIRED` の STOP が entry を下回ることの検査。 */
    const val POINT_BELOW_ENTRY: String = "belowEntry"

    /** `NO_AVERAGING_DOWN` の含み損側。 */
    const val POINT_UNREALIZED_PNL: String = "unrealizedPnl"

    /** `NO_AVERAGING_DOWN` の価格差側。 */
    const val POINT_PRICE_DIFF: String = "priceDiff"

    /** `BALANCE_RATE_AND_COST_LIMIT` の cash 側。 */
    const val POINT_CASH: String = "cash"

    /** `BALANCE_RATE_AND_COST_LIMIT` の fee rate 側。 */
    const val POINT_FEE: String = "fee"

    /**
     * PLACE_ORDER 経路が評価する 27 evaluation point。
     *
     * 23 rule のうち 4 rule を 2 point へ分割している。update 専用の
     * `ATR_TRAILING_FLOOR` と `IMMEDIATE_STOP_TRIGGER` は含まない。
     */
    val placeOrderPoints: List<EvaluationPointId> = listOf(
        // halt state
        EvaluationPointId(SafetyFloorRule.MAX_DRAWDOWN_HALT, POINT_STICKY_STATE),
        EvaluationPointId(SafetyFloorRule.MAX_DRAWDOWN_HALT, POINT_THRESHOLD, MarginUnit.RATIO),
        EvaluationPointId(SafetyFloorRule.SOFT_HALT_ENTRY_BLOCKED),

        // economic event calendar
        EvaluationPointId(SafetyFloorRule.FOMC_CALENDAR_MISSING),
        EvaluationPointId(SafetyFloorRule.FOMC_CALENDAR_INVALID),
        EvaluationPointId(SafetyFloorRule.FOMC_CALENDAR_EXPIRED, marginUnit = MarginUnit.SECONDS),
        EvaluationPointId(SafetyFloorRule.ECONOMIC_EVENT_BLACKOUT, marginUnit = MarginUnit.SECONDS),

        // falsifier gate
        EvaluationPointId(SafetyFloorRule.MISSING_FRESH_FALSIFICATION),
        EvaluationPointId(SafetyFloorRule.INTENT_CONSUMED),
        EvaluationPointId(SafetyFloorRule.INTENT_MISMATCH),

        // stop loss
        EvaluationPointId(SafetyFloorRule.STOP_LOSS_REQUIRED, POINT_POSITIVE, MarginUnit.JPY),
        EvaluationPointId(SafetyFloorRule.STOP_LOSS_REQUIRED, POINT_BELOW_ENTRY, MarginUnit.JPY),

        // averaging down
        EvaluationPointId(SafetyFloorRule.NO_AVERAGING_DOWN, POINT_UNREALIZED_PNL, MarginUnit.JPY),
        EvaluationPointId(SafetyFloorRule.NO_AVERAGING_DOWN, POINT_PRICE_DIFF, MarginUnit.JPY_PER_BTC),

        // pyramiding
        EvaluationPointId(SafetyFloorRule.STOP_LOSS_LOOSENING, marginUnit = MarginUnit.JPY),
        EvaluationPointId(SafetyFloorRule.PYRAMID_ADD_LIMIT, marginUnit = MarginUnit.COUNT),
        EvaluationPointId(SafetyFloorRule.PYRAMID_PROFIT_GATE, marginUnit = MarginUnit.R),
        EvaluationPointId(SafetyFloorRule.PYRAMID_ADD_RISK_LIMIT, marginUnit = MarginUnit.JPY),

        // balance, rate and cost
        EvaluationPointId(SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT, POINT_FEE),
        EvaluationPointId(SafetyFloorRule.BALANCE_RATE_AND_COST_LIMIT, POINT_CASH, MarginUnit.JPY),

        // risk and exposure
        EvaluationPointId(SafetyFloorRule.MAX_RISK_PER_TRADE, marginUnit = MarginUnit.JPY),
        EvaluationPointId(SafetyFloorRule.MAX_TOTAL_EXPOSURE, marginUnit = MarginUnit.JPY),

        // expected value
        EvaluationPointId(SafetyFloorRule.INVALID_WIN_PROBABILITY),
        EvaluationPointId(SafetyFloorRule.MISSING_TARGET_PRICE),
        EvaluationPointId(SafetyFloorRule.NON_POSITIVE_EXPECTED_VALUE, marginUnit = MarginUnit.R),
        EvaluationPointId(SafetyFloorRule.EXPECTED_VALUE_GATE, marginUnit = MarginUnit.R),
        EvaluationPointId(SafetyFloorRule.EXPECTED_MOVE_TO_COST_RATIO, marginUnit = MarginUnit.RATIO),
    )

    /** PLACE_ORDER 経路が評価しない rule。update 専用の 2 つ。 */
    val updateOnlyRules: Set<SafetyFloorRule> = setOf(
        SafetyFloorRule.ATR_TRAILING_FLOOR,
        SafetyFloorRule.IMMEDIATE_STOP_TRIGGER,
    )

    /** 指定した評価経路の evaluation point を返す。 */
    fun pointsFor(path: SafetyFloorEvaluationPath): List<EvaluationPointId> {
        return when (path) {
            SafetyFloorEvaluationPath.PLACE_ORDER -> placeOrderPoints
        }
    }
}
