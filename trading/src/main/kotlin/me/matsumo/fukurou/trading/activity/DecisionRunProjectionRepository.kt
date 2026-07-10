package me.matsumo.fukurou.trading.activity

import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_CANCELLED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_RUNNING
import me.matsumo.fukurou.trading.persistence.STALE_LLM_RUN_RECOVERY_ERROR_MESSAGE
import java.time.Instant

/** decision run の機械判定 outcome。 */
enum class DecisionRunOutcome {
    WAITING,
    FILLED,
    EXPIRED,
    CANCELED,
    NO_ENTRY,
    RUNNING,
    FAILED,
    ACTION_REQUIRED,
}

/** Activity 一覧の目的別 filter。 */
enum class DecisionRunFilter {
    ACTION_REQUIRED,
    WAITING,
    FILLED,
    NO_ENTRY,
}

/** outcome 判定に使う保存済み run 証跡。 */
data class DecisionRunOutcomeEvidence(
    val status: String,
    val errorMessage: String?,
    val action: String?,
    val safetyRule: String?,
    val orderCount: Int,
    val filledOrderCount: Int,
    val executionCount: Int,
    val hasNoTradeExit: Boolean,
    val openOrderCount: Int = 0,
    val overdueOpenOrderCount: Int = 0,
    val ttlCanceledOrderCount: Int = 0,
    val canceledEntryOrderCount: Int = 0,
    val actorCanceledOrderCount: Int = 0,
)

/** 保存済み run 証跡から fail-closed な outcome を決定する。 */
fun classifyDecisionRunOutcome(evidence: DecisionRunOutcomeEvidence): DecisionRunOutcome {
    val hasExecutionEvidence = evidence.filledOrderCount > 0 || evidence.executionCount > 0

    return when {
        evidence.overdueOpenOrderCount > 0 -> DecisionRunOutcome.ACTION_REQUIRED
        evidence.openOrderCount > 0 -> DecisionRunOutcome.WAITING
        hasExecutionEvidence -> DecisionRunOutcome.FILLED
        evidence.ttlCanceledOrderCount > 0 -> DecisionRunOutcome.EXPIRED
        evidence.hasNormalCancellationEvidence() -> DecisionRunOutcome.CANCELED
        evidence.status == LLM_RUN_STATUS_RUNNING -> DecisionRunOutcome.RUNNING
        evidence.hasNoEntryEvidence() -> DecisionRunOutcome.NO_ENTRY
        evidence.errorMessage == STALE_LLM_RUN_RECOVERY_ERROR_MESSAGE -> DecisionRunOutcome.FAILED
        evidence.status == LLM_RUN_STATUS_FAILED || evidence.status == LLM_RUN_STATUS_CANCELLED -> DecisionRunOutcome.FAILED
        else -> DecisionRunOutcome.FAILED
    }
}

private fun DecisionRunOutcomeEvidence.hasNormalCancellationEvidence(): Boolean {
    return canceledEntryOrderCount > 0 || actorCanceledOrderCount > 0
}

private fun DecisionRunOutcomeEvidence.hasNoEntryEvidence(): Boolean {
    return safetyRule != null || action == DecisionAction.NO_TRADE.name || hasNoTradeExit
}

/** outcome が目的別 filter に一致するかを返す。 */
fun DecisionRunOutcome.matches(filter: DecisionRunFilter): Boolean {
    return when (filter) {
        DecisionRunFilter.ACTION_REQUIRED -> this == DecisionRunOutcome.ACTION_REQUIRED || this == DecisionRunOutcome.FAILED
        DecisionRunFilter.WAITING -> this == DecisionRunOutcome.WAITING
        DecisionRunFilter.FILLED -> this == DecisionRunOutcome.FILLED
        DecisionRunFilter.NO_ENTRY ->
            this == DecisionRunOutcome.NO_ENTRY ||
                this == DecisionRunOutcome.EXPIRED ||
                this == DecisionRunOutcome.CANCELED
    }
}

/** decision run 一覧の安定 cursor。 */
data class DecisionRunCursor(
    val startedAt: Instant,
    val invocationId: String,
)

/**
 * bounded scan で構築した decision run page。
 *
 * @param runs outcome filter 適用後の run
 * @param scanContinuation scan 上限で打ち切った場合に最後に確認した raw run の cursor
 */
data class DecisionRunPage(
    val runs: List<DecisionRunSummary>,
    val scanContinuation: DecisionRunCursor?,
)

/** decision run 一覧の表示用 projection。 */
data class DecisionRunSummary(
    val invocationId: String,
    val mode: String,
    val symbol: String,
    val triggerKind: String?,
    val status: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorMessage: String?,
    val action: String?,
    val reasonJa: String?,
    val falsificationVerdict: String?,
    val safetyRule: String?,
    val safetyMessageJa: String?,
    val finalReason: String?,
    val orderCount: Int,
    val executionCount: Int,
    val outcome: DecisionRunOutcome,
    val order: DecisionRunOrder? = null,
)

/** decision run の LLM 判断 projection。 */
data class DecisionRunDecision(
    val decisionId: String,
    val action: String,
    val provider: String?,
    val estimatedWinProbability: String,
    val expectedRMultiple: String?,
    val roundTripCostR: String?,
    val reasonJa: String,
    val setupTagsJson: String,
    val missingDataJaJson: String,
    val noTradeConditionsJaJson: String,
    val createdAt: Instant,
)

/** decision run の intent / TradePlan projection。 */
data class DecisionRunIntent(
    val intentId: String,
    val tradePlanId: String,
    val parentTradePlanId: String?,
    val revisionCount: Int,
    val side: String,
    val orderType: String,
    val sizeBtc: String,
    val priceJpy: String?,
    val protectiveStopPriceJpy: String,
    val takeProfitPriceJpy: String?,
    val thesisJa: String?,
    val invalidationConditionsJaJson: String?,
    val targetPriceJpy: String?,
    val timeStopAt: Instant?,
    val setupTagsJson: String?,
)

/** decision run の Falsifier projection。 */
data class DecisionRunFalsification(
    val verdict: String,
    val provider: String?,
    val reasonJa: String,
    val createdAt: Instant,
)

/** decision run の SafetyFloor projection。 */
data class DecisionRunSafetyViolation(
    val rule: String,
    val measuredValue: String,
    val limitValue: String,
    val messageJa: String,
    val createdAt: Instant,
)

/** decision run に紐づく order projection。 */
data class DecisionRunOrder(
    val orderId: String,
    val intentId: String?,
    val positionId: String?,
    val tradeGroupId: String?,
    val side: String,
    val orderType: String,
    val status: String,
    val sizeBtc: String,
    val limitPriceJpy: String?,
    val reasonJa: String?,
    val expiresAt: Instant? = null,
    val expirySource: String? = null,
    val effectiveTtlSeconds: Long? = null,
    val expiredAt: Instant? = null,
    val canceledAt: Instant? = null,
    val cancelReason: String? = null,
    val canceledByDecisionRunId: String? = null,
    val createdAt: Instant,
)

/** decision run に紐づく execution projection。 */
data class DecisionRunExecution(
    val executionId: String,
    val orderId: String?,
    val positionId: String?,
    val side: String,
    val priceJpy: String,
    val sizeBtc: String,
    val realizedPnlJpy: String,
    val executedAt: Instant,
)

/** secret を含む payload を除外した raw/debug 行。 */
data class DecisionRunRawRecord(
    val source: String,
    val occurredAt: Instant,
    val values: Map<String, String?>,
)

/**
 * decision run 詳細の正規化 projection。
 *
 * runner は 1 run に 1 decision と最大 1 entry intent を保存する。projection はその invariant を前提に
 * 最新 intent を表示し、run に紐づく order / execution は監査のため全件を保持する。
 */
data class DecisionRunDetail(
    val summary: DecisionRunSummary,
    val decision: DecisionRunDecision?,
    val intent: DecisionRunIntent?,
    val falsification: DecisionRunFalsification?,
    val safetyViolation: DecisionRunSafetyViolation?,
    val orders: List<DecisionRunOrder>,
    val executions: List<DecisionRunExecution>,
    val raw: List<DecisionRunRawRecord>,
)

/** Activity の decision run read model repository。 */
interface DecisionRunProjectionRepository {
    /** outcome filter を pagination より前に適用し、run を新しい順で返す。 */
    suspend fun listRuns(
        cursor: DecisionRunCursor?,
        limit: Int,
        filter: DecisionRunFilter? = null,
    ): Result<DecisionRunPage>

    /** invocation ID に対応する run 詳細を返す。 */
    suspend fun findRun(invocationId: String): Result<DecisionRunDetail?>
}
