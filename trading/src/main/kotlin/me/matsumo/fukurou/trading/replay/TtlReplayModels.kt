package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.domain.EvaluationCohort
import java.math.BigDecimal
import java.util.UUID

/** 対象 resting LIMIT entry order の記録済み事実。selection query が 1 行 1 対象で埋める。 */
data class TtlReplayOrderRow(
    val orderId: UUID,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val effectiveTtlSeconds: Long?,
    val expiredAtMs: Long?,
    val canceledAtMs: Long?,
    val cancelReason: String?,
    val updatedAtMs: Long,
    val limitPriceJpy: BigDecimal?,
    val sizeBtc: BigDecimal,
    val marketDataSessionId: UUID?,
    val marketEligibleAfterSequence: Long?,
    val orderSemanticsVersion: String?,
    val hasTradePlan: Boolean,
    val timeStopAtMs: Long?,
    val executedAtMs: Long?,
    val executionSourceSessionId: UUID?,
    val executionSourceSequence: Long?,
    val executionPriceJpy: BigDecimal?,
    val executionFeeJpy: BigDecimal?,
    val executionSemanticsVersion: String?,
) {
    /** 記録済み結果の TTL 秒。`effective_ttl_seconds` が無ければ期限差から復元する。 */
    val recordedTtlSeconds: Long
        get() = effectiveTtlSeconds ?: ((expiresAtMs - createdAtMs) / MILLIS_PER_SECOND)

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/** 対象 1 件の分類・overlay 判定・候補結果を束ねた解析結果。 */
data class TtlReplayTargetResult(
    val orderId: UUID,
    val cohort: EvaluationCohort,
    val classification: ReplayOrderClassification,
    val populationStatus: ReplayPopulationStatus,
    val unknownReason: ReplayUnknownReason?,
    val fidelity: ReplayFidelity,
    val createdAtMs: Long,
    val executedAtMs: Long?,
    val marketResponseLatencyMs: Long?,
    val recordedTtlSeconds: Long,
    val timeStopAtMs: Long?,
    val candidates: List<ReplayCandidateResult>,
    val notes: List<String>,
) {
    /** JSON Lines の target 行へ変換する。 */
    fun toLine(): ReplayTargetLine {
        return ReplayTargetLine(
            orderId = orderId.toString(),
            cohort = cohort,
            populationStatus = populationStatus,
            classification = classification,
            fidelity = fidelity,
            unknownReason = unknownReason,
            createdAtMs = createdAtMs,
            executedAtMs = executedAtMs,
            marketResponseLatencyMs = marketResponseLatencyMs,
            recordedEffectiveTtlSeconds = recordedTtlSeconds,
            timeStopAtMs = timeStopAtMs,
            candidates = candidates,
            notes = notes,
        )
    }
}
