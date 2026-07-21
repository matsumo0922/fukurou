package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.domain.EvaluationCohort
import me.matsumo.fukurou.trading.persistence.jdbcConnection
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/** run 全体の出力。単一 snapshot 内で組み立て、transaction の外で serialize して書く。 */
data class TtlReplayRunOutput(
    val targets: List<ReplayTargetLine>,
    val cohortSummaries: List<ReplayCohortSummaryLine>,
    val runSummary: ReplayRunSummaryLine,
)

/**
 * TTL 短縮感度の replay を単一 read-only snapshot で実行する。
 *
 * fill の権威を記録済み execution 行に置き、queue から fill を再導出しない。約定 order の market 応答レイテンシと、
 * 各短縮 TTL 候補が確実に取りこぼす fill 件数 (confirmed-DROPPED) を主出力とする。
 */
class TtlShorteningReplay(private val runtime: ReplayReadOnlyRuntime) {

    /** replay を実行し、JSON Lines writer へ target 行・cohort 集計・run summary を書く。 */
    fun run(bounds: ReplayBounds, writer: ReplayJsonLinesWriter) {
        val output = buildOutput(bounds)

        output.targets.forEach(writer::writeTarget)
        output.cohortSummaries.forEach(writer::writeCohortSummary)
        writer.writeRunSummary(output.runSummary)
    }

    /** 単一 snapshot 内で全出力を組み立てて返す。 */
    fun buildOutput(bounds: ReplayBounds): TtlReplayRunOutput {
        return runtime.readInSingleSnapshot(bounds.statementTimeoutSeconds) {
            val snapshotAtMs = readSnapshotNow()
            enforceTargetLimit(bounds)

            val gapProjection = ReplayGapProjection.project(this, bounds.window, snapshotAtMs)
            val rows = TtlReplayQuery.selectTargets(this, bounds.window)
            val results = rows.map { row -> analyze(row, gapProjection, snapshotAtMs, bounds.candidateTtlSeconds) }

            TtlReplayRunOutput(
                targets = results.map(TtlReplayTargetResult::toLine),
                cohortSummaries = buildCohortSummaries(results, bounds.candidateTtlSeconds),
                runSummary = ReplayRunSummaryLine(
                    windowFromMs = bounds.window.fromMs,
                    windowToExclusiveMs = bounds.window.toExclusiveMs,
                    snapshotAtMs = snapshotAtMs,
                    targetCount = results.size,
                    candidateTtlSeconds = bounds.candidateTtlSeconds,
                    disclosures = ReplayRunSummaryLine.DEFAULT_DISCLOSURES,
                ),
            )
        }
    }

    private fun JdbcTransaction.enforceTargetLimit(bounds: ReplayBounds) {
        val targetCount = TtlReplayQuery.countTargets(this, bounds.window)
        if (targetCount > bounds.maxTargets) {
            throw ReplayRunFailedException(
                "target count $targetCount exceeds max ${bounds.maxTargets}; the run is failed without truncation.",
            )
        }
    }

    private fun JdbcTransaction.analyze(
        row: TtlReplayOrderRow,
        gapProjection: ReplayGapProjection,
        snapshotAtMs: Long,
        candidateTtlSeconds: List<Long>,
    ): TtlReplayTargetResult {
        val cohort = TtlReplayClassifier.deriveCohort(row)
        val classification = TtlReplayClassifier.classify(row)
        val latencyMs = row.executedAtMs?.let { executedAtMs -> executedAtMs - row.createdAtMs }
        val terminalMs = terminalMs(row, classification, snapshotAtMs)

        val gapReason = gapProjection.intersectingReason(row.createdAtMs, terminalMs)
        if (gapReason != null) {
            return unknownResult(row, cohort, classification, latencyMs, gapReason)
        }

        return when (classification) {
            ReplayOrderClassification.FILLED ->
                analyzeFilled(row, cohort, latencyMs, candidateTtlSeconds)
            ReplayOrderClassification.TTL_EXPIRED ->
                eligibleResult(row, cohort, classification, latencyMs, emptyList(), listOf(NOTE_TTL_EXPIRED))
            ReplayOrderClassification.NON_TTL_TERMINAL ->
                terminalResult(row, cohort, classification, ReplayPopulationStatus.NON_TTL_TERMINAL, listOf(NOTE_NON_TTL))
            ReplayOrderClassification.OPEN_AT_SNAPSHOT ->
                openResult(row, cohort, classification)
        }
    }

    private fun JdbcTransaction.analyzeFilled(
        row: TtlReplayOrderRow,
        cohort: EvaluationCohort,
        latencyMs: Long?,
        candidateTtlSeconds: List<Long>,
    ): TtlReplayTargetResult {
        val receiptStatus = evaluateReceiptWindow(row)
        if (receiptStatus != null) {
            return when (receiptStatus) {
                ReplayPopulationStatus.NO_REPLAY_INPUT ->
                    noReplayInputResult(row, cohort, latencyMs)
                else ->
                    unknownResult(row, cohort, ReplayOrderClassification.FILLED, latencyMs, ReplayUnknownReason.RECEIPT_SEQUENCE_GAP)
            }
        }

        val candidates = TtlReplayClassifier.evaluateCandidates(row, candidateTtlSeconds)

        return eligibleResult(row, cohort, ReplayOrderClassification.FILLED, latencyMs, candidates, listOf(NOTE_INDEPENDENT))
    }

    /** receipt window を検査し、母数から外す population status を返す。適格なら null。 */
    private fun JdbcTransaction.evaluateReceiptWindow(row: TtlReplayOrderRow): ReplayPopulationStatus? {
        val sessionId = row.executionSourceSessionId
        val anchor = row.executionSourceSequence
        val after = row.marketEligibleAfterSequence
        val boundingUnavailable = sessionId == null || anchor == null || after == null
        if (boundingUnavailable) return ReplayPopulationStatus.NO_REPLAY_INPUT
        if (sessionId != row.marketDataSessionId) return null

        val check = ReplayReceiptContinuity.check(this, sessionId, after, anchor)
        val receiptsAbsent = check.observedCount == 0 && check.expectedCount > 0
        if (receiptsAbsent) return ReplayPopulationStatus.NO_REPLAY_INPUT
        if (check.anchorBeforeEligibility || check.hasSequenceGap) return ReplayPopulationStatus.UNKNOWN

        return null
    }

    private fun buildCohortSummaries(
        results: List<TtlReplayTargetResult>,
        candidateTtlSeconds: List<Long>,
    ): List<ReplayCohortSummaryLine> {
        return results
            .groupBy(TtlReplayTargetResult::cohort)
            .toSortedMap(compareBy(EvaluationCohort::name))
            .map { (cohort, cohortResults) -> summarizeCohort(cohort, cohortResults, candidateTtlSeconds) }
    }

    private fun summarizeCohort(
        cohort: EvaluationCohort,
        results: List<TtlReplayTargetResult>,
        candidateTtlSeconds: List<Long>,
    ): ReplayCohortSummaryLine {
        val eligible = results.filter { result -> result.populationStatus == ReplayPopulationStatus.ELIGIBLE }
        val filled = eligible.filter { result -> result.classification == ReplayOrderClassification.FILLED }
        val unknownByReason = results
            .filter { result -> result.populationStatus == ReplayPopulationStatus.UNKNOWN }
            .mapNotNull(TtlReplayTargetResult::unknownReason)
            .groupingBy { reason -> reason }
            .eachCount()

        return ReplayCohortSummaryLine(
            cohort = cohort,
            eligibleCount = eligible.size,
            filledCount = filled.size,
            ttlExpiredCount = eligible.count { r -> r.classification == ReplayOrderClassification.TTL_EXPIRED },
            nonTtlTerminalCount = results.count { r -> r.populationStatus == ReplayPopulationStatus.NON_TTL_TERMINAL },
            openAtSnapshotCount = results.count { r -> r.populationStatus == ReplayPopulationStatus.OPEN_AT_SNAPSHOT },
            inputMissingCount = results.count { r -> r.populationStatus == ReplayPopulationStatus.NO_REPLAY_INPUT },
            unknownCountByReason = unknownByReason,
            candidates = candidateTtlSeconds.map { candidate -> aggregateCandidate(candidate, filled) },
        )
    }

    private fun aggregateCandidate(
        candidateSeconds: Long,
        filled: List<TtlReplayTargetResult>,
    ): ReplayCandidateAggregate {
        val verdicts = filled
            .flatMap(TtlReplayTargetResult::candidates)
            .filter { candidate -> candidate.candidateTtlSeconds == candidateSeconds }

        return ReplayCandidateAggregate(
            candidateTtlSeconds = candidateSeconds,
            confirmedDroppedCount = verdicts.count { c -> c.verdict == ReplayCandidateVerdict.DROPPED },
            retentionUnconfirmedCount = verdicts.count { c -> c.verdict == ReplayCandidateVerdict.RETENTION_UNCONFIRMED },
            timeStopUnresolvedCount = verdicts.count { c -> c.verdict == ReplayCandidateVerdict.TIME_STOP_UNRESOLVED },
        )
    }

    private fun JdbcTransaction.readSnapshotNow(): Long {
        return jdbcConnection().prepareStatement(SNAPSHOT_NOW_SQL).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "snapshot now query returned no rows." }
                rows.getLong("now_ms")
            }
        }
    }

    private fun terminalMs(
        row: TtlReplayOrderRow,
        classification: ReplayOrderClassification,
        snapshotAtMs: Long,
    ): Long {
        return when (classification) {
            ReplayOrderClassification.FILLED -> row.executedAtMs ?: snapshotAtMs
            ReplayOrderClassification.TTL_EXPIRED -> row.expiredAtMs ?: snapshotAtMs
            ReplayOrderClassification.NON_TTL_TERMINAL -> row.canceledAtMs ?: row.updatedAtMs
            ReplayOrderClassification.OPEN_AT_SNAPSHOT -> snapshotAtMs
        }
    }

    @Suppress("LongParameterList")
    private fun eligibleResult(
        row: TtlReplayOrderRow,
        cohort: EvaluationCohort,
        classification: ReplayOrderClassification,
        latencyMs: Long?,
        candidates: List<ReplayCandidateResult>,
        notes: List<String>,
    ): TtlReplayTargetResult {
        return baseResult(
            row = row,
            cohort = cohort,
            classification = classification,
            populationStatus = ReplayPopulationStatus.ELIGIBLE,
            unknownReason = null,
            fidelity = ReplayFidelity.EXACT,
            latencyMs = latencyMs,
            candidates = candidates,
            notes = notes,
        )
    }

    private fun terminalResult(
        row: TtlReplayOrderRow,
        cohort: EvaluationCohort,
        classification: ReplayOrderClassification,
        populationStatus: ReplayPopulationStatus,
        notes: List<String>,
    ): TtlReplayTargetResult {
        return baseResult(
            row = row,
            cohort = cohort,
            classification = classification,
            populationStatus = populationStatus,
            unknownReason = null,
            fidelity = ReplayFidelity.EXACT,
            latencyMs = null,
            candidates = emptyList(),
            notes = notes,
        )
    }

    private fun openResult(
        row: TtlReplayOrderRow,
        cohort: EvaluationCohort,
        classification: ReplayOrderClassification,
    ): TtlReplayTargetResult {
        return baseResult(
            row = row,
            cohort = cohort,
            classification = classification,
            populationStatus = ReplayPopulationStatus.OPEN_AT_SNAPSHOT,
            unknownReason = null,
            fidelity = ReplayFidelity.UNKNOWN,
            latencyMs = null,
            candidates = emptyList(),
            notes = listOf(NOTE_OPEN),
        )
    }

    private fun unknownResult(
        row: TtlReplayOrderRow,
        cohort: EvaluationCohort,
        classification: ReplayOrderClassification,
        latencyMs: Long?,
        reason: ReplayUnknownReason,
    ): TtlReplayTargetResult {
        return baseResult(
            row = row,
            cohort = cohort,
            classification = classification,
            populationStatus = ReplayPopulationStatus.UNKNOWN,
            unknownReason = reason,
            fidelity = ReplayFidelity.UNKNOWN,
            latencyMs = latencyMs,
            candidates = emptyList(),
            notes = emptyList(),
        )
    }

    private fun noReplayInputResult(
        row: TtlReplayOrderRow,
        cohort: EvaluationCohort,
        latencyMs: Long?,
    ): TtlReplayTargetResult {
        return baseResult(
            row = row,
            cohort = cohort,
            classification = ReplayOrderClassification.FILLED,
            populationStatus = ReplayPopulationStatus.NO_REPLAY_INPUT,
            unknownReason = ReplayUnknownReason.NO_REPLAY_INPUT,
            fidelity = ReplayFidelity.UNKNOWN,
            latencyMs = latencyMs,
            candidates = emptyList(),
            notes = listOf(NOTE_NO_INPUT),
        )
    }

    @Suppress("LongParameterList")
    private fun baseResult(
        row: TtlReplayOrderRow,
        cohort: EvaluationCohort,
        classification: ReplayOrderClassification,
        populationStatus: ReplayPopulationStatus,
        unknownReason: ReplayUnknownReason?,
        fidelity: ReplayFidelity,
        latencyMs: Long?,
        candidates: List<ReplayCandidateResult>,
        notes: List<String>,
    ): TtlReplayTargetResult {
        return TtlReplayTargetResult(
            orderId = row.orderId,
            cohort = cohort,
            classification = classification,
            populationStatus = populationStatus,
            unknownReason = unknownReason,
            fidelity = fidelity,
            createdAtMs = row.createdAtMs,
            executedAtMs = row.executedAtMs,
            marketResponseLatencyMs = latencyMs,
            recordedTtlSeconds = row.recordedTtlSeconds,
            timeStopAtMs = row.timeStopAtMs,
            candidates = candidates,
            notes = notes,
        )
    }

    private companion object {
        const val SNAPSHOT_NOW_SQL =
            "SELECT (extract(epoch FROM transaction_timestamp()) * 1000)::bigint AS now_ms"

        const val NOTE_INDEPENDENT = "指値を記録済みの値に固定したまま TTL だけを短縮した独立な反実仮想。"
        const val NOTE_TTL_EXPIRED = "記録済み TTL で失効済み。より短い TTL でも約定しない (取りこぼす fill が無い)。"
        const val NOTE_NON_TTL = "安全ゲートで棄却された非 TTL 終端。TTL retention 母数から除外する。"
        const val NOTE_OPEN = "snapshot 時点で OPEN。約定を主張しない。"
        const val NOTE_NO_INPUT = "生存区間の receipt が読めず入力欠如。約定有無を推定しない。"
    }
}
