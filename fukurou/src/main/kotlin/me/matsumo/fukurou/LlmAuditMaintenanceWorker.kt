package me.matsumo.fukurou

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.matsumo.fukurou.trading.audit.LlmDecisionEvidenceCoverageSummary
import me.matsumo.fukurou.trading.audit.LlmDecisionReconstructionRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger

private val LLM_AUDIT_MAINTENANCE_LOGGER = Logger.getLogger(LlmAuditMaintenanceWorker::class.java.name)

/** terminal evidence coverage と retention を application lifecycle 内で bounded に実行する worker。 */
class LlmAuditMaintenanceWorker(
    private val repository: LlmDecisionReconstructionRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val initialDelay: Duration = MAINTENANCE_INTERVAL,
    private val interval: Duration = MAINTENANCE_INTERVAL,
    private val logger: (String) -> Unit = LLM_AUDIT_MAINTENANCE_LOGGER::info,
    private val sleeper: suspend (Duration) -> Unit = { duration -> delay(duration.toMillis()) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private var job: Job? = null

    /** 初回を1 interval後に実行する非重複schedulerを開始する。 */
    fun start(): LlmAuditMaintenanceWorker {
        require(job == null) { "LlmAuditMaintenanceWorker is already started." }

        job = scope.launch {
            sleeper(initialDelay)
            while (currentCoroutineContext().isActive) {
                val startedAtNanos = System.nanoTime()
                val report = runOnce()
                val elapsed = Duration.ofNanos(System.nanoTime() - startedAtNanos)

                logger(report.toLogMessage(elapsed))
                sleeper(interval)
            }
        }

        return this
    }

    /** coverage失敗とprune失敗を分離して1 tickのbounded reportを返す。 */
    suspend fun runOnce(): LlmAuditMaintenanceReport {
        val now = Instant.now(clock)
        val coverageResult = safely { repository.summarizeCoverage(now.minus(COVERAGE_WINDOW), now) }
        var deletedRootCount = 0
        var batchCount = 0
        var pruneStatus = LlmAuditMaintenanceStatus.SUCCEEDED
        var hasMore = false

        while (batchCount < MAX_PRUNE_BATCHES_PER_TICK) {
            val batchResult = safely { repository.pruneExpiredAuditRoots(now) }
            val batch = batchResult.getOrElse {
                pruneStatus = LlmAuditMaintenanceStatus.RETRYABLE_FAILURE
                hasMore = true
                break
            }
            batchCount += 1
            deletedRootCount += batch.deletedRootCount
            hasMore = batch.hasMore

            if (!batch.hasMore) break
        }

        return LlmAuditMaintenanceReport(
            coverageStatus = if (coverageResult.isSuccess) {
                LlmAuditMaintenanceStatus.SUCCEEDED
            } else {
                LlmAuditMaintenanceStatus.RETRYABLE_FAILURE
            },
            coverage = coverageResult.getOrNull(),
            pruneStatus = pruneStatus,
            pruneBatchCount = batchCount,
            deletedRootCount = deletedRootCount,
            hasMore = hasMore,
        )
    }

    private suspend fun <T> safely(block: suspend () -> Result<T>): Result<T> {
        return try {
            block().also { result ->
                val failure = result.exceptionOrNull()
                if (failure is CancellationException) throw failure
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }
}

/** maintenance operation のretry可否を表す状態。 */
enum class LlmAuditMaintenanceStatus { SUCCEEDED, RETRYABLE_FAILURE }

/** 1回のmaintenance tickを表すtyped report。 */
data class LlmAuditMaintenanceReport(
    val coverageStatus: LlmAuditMaintenanceStatus,
    val coverage: LlmDecisionEvidenceCoverageSummary?,
    val pruneStatus: LlmAuditMaintenanceStatus,
    val pruneBatchCount: Int,
    val deletedRootCount: Int,
    val hasMore: Boolean,
) {
    /** raw evidenceを含まないaggregate tick summary。 */
    fun toLogMessage(elapsed: Duration): String = "LLM audit maintenance tick " +
        "coverage=$coverageStatus " +
        "decisionCount=${coverage?.decisionCount ?: 0} " +
        "terminalDecisionCount=${coverage?.terminalDecisionCount ?: 0} " +
        "completeDecisionCount=${coverage?.structurallyCompleteDecisionCount ?: 0} " +
        "incompleteDecisionCount=${coverage?.structurallyIncompleteDecisionCount ?: 0} " +
        "prune=$pruneStatus pruneBatchCount=$pruneBatchCount " +
        "deletedRootCount=$deletedRootCount hasMore=$hasMore " +
        "elapsedMillis=${elapsed.toMillis()}"
}

/** 1 tickで実行するprune batch上限。 */
const val MAX_PRUNE_BATCHES_PER_TICK = 10

private val COVERAGE_WINDOW: Duration = Duration.ofDays(14)
private val MAINTENANCE_INTERVAL: Duration = Duration.ofHours(1)
