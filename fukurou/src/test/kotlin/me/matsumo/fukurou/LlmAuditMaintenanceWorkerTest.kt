package me.matsumo.fukurou

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.audit.LlmAuditPruneBatchResult
import me.matsumo.fukurou.trading.audit.LlmDecisionEvidenceCoverageSummary
import me.matsumo.fukurou.trading.audit.LlmDecisionReconstruction
import me.matsumo.fukurou.trading.audit.LlmDecisionReconstructionRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmAuditMaintenanceWorkerTest {
    @Test
    fun runOnce_returnsSuccessWhenCoverageAndPruneAreEmpty() = runBlocking {
        val repository = FakeAuditRepository()

        val report = LlmAuditMaintenanceWorker(repository, fixedClock()).runOnce()

        assertEquals(LlmAuditMaintenanceStatus.SUCCEEDED, report.coverageStatus)
        assertEquals(LlmAuditMaintenanceStatus.SUCCEEDED, report.pruneStatus)
        assertEquals(1, report.pruneBatchCount)
        assertEquals(0, report.deletedRootCount)
        assertFalse(report.hasMore)
    }

    @Test
    fun runOnce_attemptsPruneAfterCoverageFailure() = runBlocking {
        val repository = FakeAuditRepository(coverageFailure = IllegalStateException("coverage failed"))

        val report = LlmAuditMaintenanceWorker(repository, fixedClock()).runOnce()

        assertEquals(LlmAuditMaintenanceStatus.RETRYABLE_FAILURE, report.coverageStatus)
        assertEquals(LlmAuditMaintenanceStatus.SUCCEEDED, report.pruneStatus)
        assertEquals(1, repository.pruneCalls)
    }

    @Test
    fun runOnce_stopsLaterBatchesAfterPruneFailure() = runBlocking {
        val repository = FakeAuditRepository(
            pruneResults = ArrayDeque(
                listOf(
                    Result.success(LlmAuditPruneBatchResult(500, true)),
                    Result.failure(IllegalStateException("prune failed")),
                    Result.success(LlmAuditPruneBatchResult(1, false)),
                ),
            ),
        )

        val report = LlmAuditMaintenanceWorker(repository, fixedClock()).runOnce()

        assertEquals(LlmAuditMaintenanceStatus.RETRYABLE_FAILURE, report.pruneStatus)
        assertEquals(1, report.pruneBatchCount)
        assertEquals(500, report.deletedRootCount)
        assertEquals(2, repository.pruneCalls)
        assertTrue(report.hasMore)
    }

    @Test
    fun runOnce_limitsFiveThousandAndOneRootsToTenBatches() = runBlocking {
        val repository = FakeAuditRepository(
            pruneResults = ArrayDeque(
                List(10) { Result.success(LlmAuditPruneBatchResult(500, true)) } +
                    Result.success(LlmAuditPruneBatchResult(1, false)),
            ),
        )

        val report = LlmAuditMaintenanceWorker(repository, fixedClock()).runOnce()

        assertEquals(MAX_PRUNE_BATCHES_PER_TICK, report.pruneBatchCount)
        assertEquals(5_000, report.deletedRootCount)
        assertEquals(10, repository.pruneCalls)
        assertTrue(report.hasMore)
    }

    @Test
    fun runOnce_convertsThrownCoverageAndPruneExceptionsToRetryableReport() = runBlocking {
        val repository = object : LlmDecisionReconstructionRepository by FakeAuditRepository() {
            override suspend fun summarizeCoverage(
                from: Instant,
                toExclusive: Instant,
            ): Result<LlmDecisionEvidenceCoverageSummary> = error("coverage exception")

            override suspend fun pruneExpiredAuditRoots(now: Instant): Result<LlmAuditPruneBatchResult> =
                error("prune exception")
        }

        val report = LlmAuditMaintenanceWorker(repository, fixedClock()).runOnce()

        assertEquals(LlmAuditMaintenanceStatus.RETRYABLE_FAILURE, report.coverageStatus)
        assertEquals(LlmAuditMaintenanceStatus.RETRYABLE_FAILURE, report.pruneStatus)
        assertEquals(0, report.pruneBatchCount)
        assertTrue(report.hasMore)
    }

    @Test
    fun scheduler_waitsOneIntervalBeforeFirstTickAndCancelsWithoutReport() = runBlocking {
        val repository = FakeAuditRepository()
        val delayStarted = CompletableDeferred<Unit>()
        val delayCancelled = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        val worker = LlmAuditMaintenanceWorker(
            repository = repository,
            initialDelay = java.time.Duration.ofHours(1),
            interval = java.time.Duration.ofHours(1),
            logger = logs::add,
            sleeper = {
                delayStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    delayCancelled.complete(Unit)
                }
            },
        ).start()

        delayStarted.await()
        worker.close()
        withTimeout(1_000) { delayCancelled.await() }

        assertEquals(0, repository.coverageCalls)
        assertEquals(0, repository.pruneCalls)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun closeDuringCoverageRethrowsCancellationAndSkipsPruneAndTickLog() = runBlocking {
        val coverageStarted = CompletableDeferred<Unit>()
        val coverageCancelled = CompletableDeferred<Unit>()
        val repository = object : LlmDecisionReconstructionRepository by FakeAuditRepository() {
            var pruneCalls = 0

            override suspend fun summarizeCoverage(
                from: Instant,
                toExclusive: Instant,
            ): Result<LlmDecisionEvidenceCoverageSummary> {
                coverageStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    coverageCancelled.complete(Unit)
                }
            }

            override suspend fun pruneExpiredAuditRoots(now: Instant): Result<LlmAuditPruneBatchResult> {
                pruneCalls += 1
                return Result.success(LlmAuditPruneBatchResult(0, false))
            }
        }
        val logs = mutableListOf<String>()
        val worker = LlmAuditMaintenanceWorker(
            repository = repository,
            initialDelay = java.time.Duration.ZERO,
            logger = logs::add,
        ).start()

        coverageStarted.await()
        worker.close()
        withTimeout(1_000) { coverageCancelled.await() }

        assertEquals(0, repository.pruneCalls)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun closeDuringPruneRethrowsCancellationAndSkipsTickLog() = runBlocking {
        val pruneStarted = CompletableDeferred<Unit>()
        val pruneCancelled = CompletableDeferred<Unit>()
        val repository = object : LlmDecisionReconstructionRepository by FakeAuditRepository() {
            override suspend fun pruneExpiredAuditRoots(now: Instant): Result<LlmAuditPruneBatchResult> {
                pruneStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    pruneCancelled.complete(Unit)
                }
            }
        }
        val logs = mutableListOf<String>()
        val worker = LlmAuditMaintenanceWorker(
            repository = repository,
            initialDelay = java.time.Duration.ZERO,
            logger = logs::add,
        ).start()

        pruneStarted.await()
        worker.close()
        withTimeout(1_000) { pruneCancelled.await() }

        assertTrue(logs.isEmpty())
    }
}

private class FakeAuditRepository(
    private val coverageFailure: Throwable? = null,
    private val pruneResults: ArrayDeque<Result<LlmAuditPruneBatchResult>> = ArrayDeque(),
) : LlmDecisionReconstructionRepository {
    var coverageCalls: Int = 0
        private set
    var pruneCalls: Int = 0
        private set

    override suspend fun findDecision(decisionId: UUID): Result<LlmDecisionReconstruction?> = Result.success(null)

    override suspend fun summarizeCoverage(
        from: Instant,
        toExclusive: Instant,
    ): Result<LlmDecisionEvidenceCoverageSummary> {
        coverageCalls += 1
        coverageFailure?.let { failure -> return Result.failure(failure) }

        return Result.success(
            LlmDecisionEvidenceCoverageSummary(
                from = from,
                toExclusive = toExclusive,
                decisionCount = 0,
                terminalDecisionCount = 0,
                structurallyCompleteDecisionCount = 0,
                structurallyIncompleteDecisionCount = 0,
                pendingDecisionCount = 0,
                incompleteRunDecisionCount = 0,
                legacyTerminalDecisionCount = 0,
                terminalNoDecisionRunCount = 0,
            ),
        )
    }

    override suspend fun pruneExpiredAuditRoots(now: Instant): Result<LlmAuditPruneBatchResult> {
        pruneCalls += 1
        return pruneResults.removeFirstOrNull() ?: Result.success(LlmAuditPruneBatchResult(0, false))
    }
}

private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC)
