package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.domain.EvaluationCohort

/**
 * 対象 order の記録済み事実から cohort・分類・短縮 TTL 候補の反実仮想を導く純粋ロジック。DB へ触れない。
 *
 * cohort は lineage (order とその execution の semantics version) から既存 CASE 規則を order 粒度へ写像して
 * 導出し、receipt の有無を判定に使わない。
 */
object TtlReplayClassifier {

    private const val CURRENT_SEMANTICS_VERSION = "PAPER_WS_V1"
    private const val MILLIS_PER_SECOND = 1000L

    /** 記録済み結果を ledger の ground truth で分類する。execution 行を持たない order に fill を合成しない。 */
    fun classify(row: TtlReplayOrderRow): ReplayOrderClassification {
        return when {
            row.executedAtMs != null -> ReplayOrderClassification.FILLED
            row.expiredAtMs != null -> ReplayOrderClassification.TTL_EXPIRED
            row.cancelReason != null -> ReplayOrderClassification.NON_TTL_TERMINAL
            else -> ReplayOrderClassification.OPEN_AT_SNAPSHOT
        }
    }

    /** order とその execution の semantics version から cohort を導出する。 */
    fun deriveCohort(row: TtlReplayOrderRow): EvaluationCohort {
        val semantics = listOfNotNull(row.orderSemanticsVersion, row.executionSemanticsVersion)
        val hasUnsupported = semantics.any { version -> version.isNotEmpty() && version != CURRENT_SEMANTICS_VERSION }
        if (hasUnsupported) return EvaluationCohort.UNSUPPORTED_EXECUTION_SEMANTICS

        val orderIsCurrent = row.orderSemanticsVersion == CURRENT_SEMANTICS_VERSION
        val executionIsCurrentOrAbsent =
            row.executionSemanticsVersion == null || row.executionSemanticsVersion == CURRENT_SEMANTICS_VERSION
        if (orderIsCurrent && executionIsCurrentOrAbsent) return EvaluationCohort.CURRENT

        return EvaluationCohort.LEGACY_PRE_WS
    }

    /**
     * 短縮 TTL 候補ごとに confirmed-DROPPED / RETENTION_UNCONFIRMED / TIME_STOP_UNRESOLVED を判定する。
     *
     * FILLED order にのみ意味を持つ。候補 `T'` が記録済み TTL より長い場合は延長になるため評価対象にしない。
     */
    fun evaluateCandidates(row: TtlReplayOrderRow, candidateTtlSeconds: List<Long>): List<ReplayCandidateResult> {
        val executedAtMs = row.executedAtMs ?: return emptyList()
        val recordedTtlSeconds = row.recordedTtlSeconds

        return candidateTtlSeconds
            .filter { candidate -> candidate <= recordedTtlSeconds }
            .map { candidate -> evaluateCandidate(row, executedAtMs, candidate) }
    }

    private fun evaluateCandidate(
        row: TtlReplayOrderRow,
        executedAtMs: Long,
        candidateSeconds: Long,
    ): ReplayCandidateResult {
        val candidateExpiryMs = row.createdAtMs + candidateSeconds * MILLIS_PER_SECOND

        // 短縮候補の TTL 期限が約定 event の socket 受信時刻以下なら、time stop に依らず DROPPED は EXACT。
        if (candidateExpiryMs <= executedAtMs) {
            return ReplayCandidateResult(
                candidateTtlSeconds = candidateSeconds,
                effectiveExpiryMs = effectiveExpiryOrNull(row, candidateExpiryMs),
                verdict = ReplayCandidateVerdict.DROPPED,
                fidelity = ReplayFidelity.EXACT,
            )
        }

        // 候補 TTL 期限が約定より後。time stop が早ければ DROPPED を確定でき、解決できなければ確定できない。
        if (!row.hasTradePlan) {
            return ReplayCandidateResult(
                candidateTtlSeconds = candidateSeconds,
                effectiveExpiryMs = null,
                verdict = ReplayCandidateVerdict.TIME_STOP_UNRESOLVED,
                fidelity = ReplayFidelity.UNKNOWN,
            )
        }

        val effectiveExpiryMs = effectiveExpiry(row.timeStopAtMs, candidateExpiryMs)
        val verdict = if (effectiveExpiryMs <= executedAtMs) {
            ReplayCandidateVerdict.DROPPED
        } else {
            ReplayCandidateVerdict.RETENTION_UNCONFIRMED
        }
        val fidelity = if (verdict == ReplayCandidateVerdict.DROPPED) ReplayFidelity.EXACT else ReplayFidelity.UNKNOWN

        return ReplayCandidateResult(
            candidateTtlSeconds = candidateSeconds,
            effectiveExpiryMs = effectiveExpiryMs,
            verdict = verdict,
            fidelity = fidelity,
        )
    }

    private fun effectiveExpiryOrNull(row: TtlReplayOrderRow, candidateExpiryMs: Long): Long? {
        if (!row.hasTradePlan) return candidateExpiryMs

        return effectiveExpiry(row.timeStopAtMs, candidateExpiryMs)
    }

    private fun effectiveExpiry(timeStopAtMs: Long?, candidateExpiryMs: Long): Long {
        return if (timeStopAtMs == null) candidateExpiryMs else minOf(timeStopAtMs, candidateExpiryMs)
    }
}
