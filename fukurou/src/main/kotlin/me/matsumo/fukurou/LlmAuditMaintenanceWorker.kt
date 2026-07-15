package me.matsumo.fukurou

import me.matsumo.fukurou.trading.audit.LlmDecisionReconstructionRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** inactive audit maintenance の1回分をboundedに実行する未配線worker。 */
class LlmAuditMaintenanceWorker(
    private val repository: LlmDecisionReconstructionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
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
            pruneStatus = pruneStatus,
            pruneBatchCount = batchCount,
            deletedRootCount = deletedRootCount,
            hasMore = hasMore,
        )
    }

    private suspend fun <T> safely(block: suspend () -> Result<T>): Result<T> {
        return runCatching { block() }.getOrElse { failure -> Result.failure(failure) }
    }
}

/** maintenance operation のretry可否を表す状態。 */
enum class LlmAuditMaintenanceStatus { SUCCEEDED, RETRYABLE_FAILURE }

/** inactive maintenance worker のtyped report。 */
data class LlmAuditMaintenanceReport(
    val coverageStatus: LlmAuditMaintenanceStatus,
    val pruneStatus: LlmAuditMaintenanceStatus,
    val pruneBatchCount: Int,
    val deletedRootCount: Int,
    val hasMore: Boolean,
)

/** 1 tickで実行するprune batch上限。 */
const val MAX_PRUNE_BATCHES_PER_TICK = 10

private val COVERAGE_WINDOW: Duration = Duration.ofDays(14)
