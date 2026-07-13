package me.matsumo.fukurou.trading.evaluation

import java.time.Instant

/** root deploy protocol が記録する infrastructure gap。 */
data class InfrastructureGap(
    val id: String,
    val reason: String,
    val openedAt: Instant,
    val closedAt: Instant?,
)

/** entity の最早 cause から terminal までの causal/exposure interval。 */
data class EvaluationCausalInterval(
    val startedAt: Instant,
    val endedAt: Instant?,
)

/** infrastructure gap と causal interval の intersection を判定する projection。 */
object InfrastructureGapProjection {
    /** open end は query 時刻で固定し、半開区間同士が交差する gap を返す。 */
    fun affectedGapIds(
        interval: EvaluationCausalInterval,
        gaps: List<InfrastructureGap>,
        queryNow: Instant,
    ): Set<String> {
        val intervalEnd = interval.endedAt ?: queryNow

        return gaps.filterTo(mutableSetOf()) { gap ->
            val gapEnd = gap.closedAt ?: queryNow
            interval.startedAt < gapEnd && gap.openedAt < intervalEnd
        }.mapTo(mutableSetOf()) { gap -> gap.id }
    }
}

/** strategy KPI と母集団表示の境界を共通化する filter。 */
object EvaluationPopulationFilter {
    /** gap affected または attribution missing の trade を KPI から除外する。 */
    fun strategyEligibleTrades(trades: List<ClosedTradeFact>): List<ClosedTradeFact> {
        return trades.filter { trade ->
            trade.infrastructureGapIds.isEmpty() && trade.attributionStatus == EvaluationAttributionStatus.ATTRIBUTED
        }
    }
}
