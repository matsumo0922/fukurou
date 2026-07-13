package me.matsumo.fukurou.trading.evaluation

import java.time.Instant

/** infrastructure gap を含む全評価 entity の共通母集団状態。 */
enum class EvaluationPopulationStatus {
    ELIGIBLE,
    INFRASTRUCTURE_GAP,
    ATTRIBUTION_MISSING,
}

/** decision/run/order/position/execution/trade に共通する causal population fact。 */
data class EvaluationPopulationFact(
    val entityType: String,
    val entityId: String,
    val causeAt: Instant?,
    val terminalAt: Instant?,
    val status: EvaluationPopulationStatus,
    val representativeGapId: String?,
)

/** entity type ごとの母集団件数。 */
data class EvaluationPopulationCounts(
    val total: Int,
    val eligible: Int,
    val infrastructureGap: Int,
    val attributionMissing: Int,
)

/** causal interval と request-level gap catalog から共通状態を決める projection。 */
object EvaluationPopulationProjection {
    fun project(
        entityType: String,
        entityId: String,
        interval: EvaluationCausalInterval?,
        gaps: List<InfrastructureGap>,
        queryNow: Instant,
    ): EvaluationPopulationFact {
        val hasInvalidTerminal = interval?.endedAt?.let { terminalAt -> terminalAt < interval.startedAt } == true
        val isAttributionMissing = interval == null || hasInvalidTerminal
        if (isAttributionMissing) {
            return EvaluationPopulationFact(
                entityType = entityType,
                entityId = entityId,
                causeAt = interval?.startedAt,
                terminalAt = interval?.endedAt,
                status = EvaluationPopulationStatus.ATTRIBUTION_MISSING,
                representativeGapId = null,
            )
        }
        val representative = gaps
            .filter { gap -> gap.intersects(interval, queryNow) }
            .minWithOrNull(compareBy<InfrastructureGap> { gap -> gap.openedAt }.thenBy { gap -> gap.id })

        return EvaluationPopulationFact(
            entityType = entityType,
            entityId = entityId,
            causeAt = interval.startedAt,
            terminalAt = interval.endedAt,
            status = if (representative == null) EvaluationPopulationStatus.ELIGIBLE else EvaluationPopulationStatus.INFRASTRUCTURE_GAP,
            representativeGapId = representative?.id,
        )
    }
}

private fun InfrastructureGap.intersects(interval: EvaluationCausalInterval, queryNow: Instant): Boolean {
    val entityEnd = interval.endedAt ?: queryNow
    val gapEnd = closedAt ?: queryNow
    return interval.startedAt < gapEnd && openedAt < entityEnd
}
