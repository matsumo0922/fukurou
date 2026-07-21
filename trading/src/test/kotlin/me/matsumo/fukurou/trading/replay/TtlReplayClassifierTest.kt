package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.domain.EvaluationCohort
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TTL 短縮感度の純粋分類・cohort・候補判定ロジックの回帰テスト。 */
class TtlReplayClassifierTest {

    @Test
    fun cutoffAtOrBeforeMarketResponseIsConfirmedDroppedExact() {
        val createdAt = 1_000_000L
        val executedAt = createdAt + 600_000L
        val row = filledRow(createdAtMs = createdAt, executedAtMs = executedAt, recordedTtlSeconds = 1800)

        val results = TtlReplayClassifier.evaluateCandidates(row, listOf(300L, 900L))

        val dropped = results.single { candidate -> candidate.candidateTtlSeconds == 300L }
        assertEquals(ReplayCandidateVerdict.DROPPED, dropped.verdict)
        assertEquals(ReplayFidelity.EXACT, dropped.fidelity)

        val unconfirmed = results.single { candidate -> candidate.candidateTtlSeconds == 900L }
        assertEquals(ReplayCandidateVerdict.RETENTION_UNCONFIRMED, unconfirmed.verdict)
        assertEquals(ReplayFidelity.UNKNOWN, unconfirmed.fidelity)
    }

    @Test
    fun lengtheningCandidateIsRefused() {
        val row = filledRow(createdAtMs = 0, executedAtMs = 100_000L, recordedTtlSeconds = 600)

        val results = TtlReplayClassifier.evaluateCandidates(row, listOf(1800L, 600L))

        assertTrue(results.none { candidate -> candidate.candidateTtlSeconds == 1800L })
        assertTrue(results.any { candidate -> candidate.candidateTtlSeconds == 600L })
    }

    @Test
    fun timeStopDominatesEffectiveExpiryWhenEarlier() {
        val createdAt = 0L
        val executedAt = 200_000L
        val timeStopAt = 400_000L
        val row = filledRow(
            createdAtMs = createdAt,
            executedAtMs = executedAt,
            recordedTtlSeconds = 1800,
            hasTradePlan = true,
            timeStopAtMs = timeStopAt,
        )

        val candidate = TtlReplayClassifier.evaluateCandidates(row, listOf(900L)).single()

        assertEquals(timeStopAt, candidate.effectiveExpiryMs)
        assertEquals(ReplayCandidateVerdict.RETENTION_UNCONFIRMED, candidate.verdict)
    }

    @Test
    fun unresolvedTimeStopAfterMarketResponseIsUnknown() {
        val row = filledRow(
            createdAtMs = 0,
            executedAtMs = 200_000L,
            recordedTtlSeconds = 1800,
            hasTradePlan = false,
        )

        val candidate = TtlReplayClassifier.evaluateCandidates(row, listOf(900L)).single()

        assertEquals(ReplayCandidateVerdict.TIME_STOP_UNRESOLVED, candidate.verdict)
        assertEquals(ReplayFidelity.UNKNOWN, candidate.fidelity)
        assertNull(candidate.effectiveExpiryMs)
    }

    @Test
    fun droppedStaysExactEvenWhenTimeStopUnresolved() {
        val row = filledRow(
            createdAtMs = 0,
            executedAtMs = 600_000L,
            recordedTtlSeconds = 1800,
            hasTradePlan = false,
        )

        val candidate = TtlReplayClassifier.evaluateCandidates(row, listOf(300L)).single()

        assertEquals(ReplayCandidateVerdict.DROPPED, candidate.verdict)
        assertEquals(ReplayFidelity.EXACT, candidate.fidelity)
    }

    @Test
    fun orderWithoutExecutionIsNotClassifiedAsFilled() {
        val expired = baseRow(createdAtMs = 0).copy(expiredAtMs = 500L)
        assertEquals(ReplayOrderClassification.TTL_EXPIRED, TtlReplayClassifier.classify(expired))

        val cancelled = baseRow(createdAtMs = 0).copy(cancelReason = "HARD_HALT")
        assertEquals(ReplayOrderClassification.NON_TTL_TERMINAL, TtlReplayClassifier.classify(cancelled))

        val open = baseRow(createdAtMs = 0)
        assertEquals(ReplayOrderClassification.OPEN_AT_SNAPSHOT, TtlReplayClassifier.classify(open))
    }

    @Test
    fun cohortSeparatesLegacyAndUnsupportedFromCurrent() {
        val current = baseRow(createdAtMs = 0).copy(orderSemanticsVersion = "PAPER_WS_V1")
        assertEquals(EvaluationCohort.CURRENT, TtlReplayClassifier.deriveCohort(current))

        val legacy = baseRow(createdAtMs = 0).copy(orderSemanticsVersion = null)
        assertEquals(EvaluationCohort.LEGACY_PRE_WS, TtlReplayClassifier.deriveCohort(legacy))

        val unsupported = baseRow(createdAtMs = 0).copy(orderSemanticsVersion = "FUTURE_SEMANTICS")
        assertEquals(EvaluationCohort.UNSUPPORTED_EXECUTION_SEMANTICS, TtlReplayClassifier.deriveCohort(unsupported))
    }

    private fun filledRow(
        createdAtMs: Long,
        executedAtMs: Long,
        recordedTtlSeconds: Long,
        hasTradePlan: Boolean = true,
        timeStopAtMs: Long? = null,
    ): TtlReplayOrderRow {
        return baseRow(createdAtMs).copy(
            effectiveTtlSeconds = recordedTtlSeconds,
            expiresAtMs = createdAtMs + recordedTtlSeconds * 1000L,
            executedAtMs = executedAtMs,
            executionSourceSessionId = UUID.randomUUID(),
            executionSourceSequence = 10L,
            executionSemanticsVersion = "PAPER_WS_V1",
            orderSemanticsVersion = "PAPER_WS_V1",
            hasTradePlan = hasTradePlan,
            timeStopAtMs = timeStopAtMs,
        )
    }

    private fun baseRow(createdAtMs: Long): TtlReplayOrderRow {
        return TtlReplayOrderRow(
            orderId = UUID.randomUUID(),
            createdAtMs = createdAtMs,
            expiresAtMs = createdAtMs + 1_800_000L,
            effectiveTtlSeconds = 1800L,
            expiredAtMs = null,
            canceledAtMs = null,
            cancelReason = null,
            updatedAtMs = createdAtMs,
            limitPriceJpy = BigDecimal("10000000"),
            sizeBtc = BigDecimal("0.01"),
            marketDataSessionId = null,
            marketEligibleAfterSequence = null,
            orderSemanticsVersion = "PAPER_WS_V1",
            hasTradePlan = true,
            timeStopAtMs = null,
            executedAtMs = null,
            executionSourceSessionId = null,
            executionSourceSequence = null,
            executionPriceJpy = null,
            executionFeeJpy = null,
            executionSemanticsVersion = null,
        )
    }
}
