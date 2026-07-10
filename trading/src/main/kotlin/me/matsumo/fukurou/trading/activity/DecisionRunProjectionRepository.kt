package me.matsumo.fukurou.trading.activity

import java.time.Instant

/** decision run の機械判定 outcome。 */
enum class DecisionRunOutcome {
    EXECUTED,
    DENIED,
    NO_TRADE,
    INTERRUPTED,
    RUNNING,
    FAILED,
}

/** outcome 判定に使う保存済み run 証跡。 */
data class DecisionRunOutcomeEvidence(
    val status: String,
    val errorMessage: String?,
    val action: String?,
    val safetyRule: String?,
    val orderCount: Int,
    val executionCount: Int,
    val hasNoTradeExit: Boolean,
)

/** 保存済み run 証跡から fail-closed な outcome を決定する。 */
fun classifyDecisionRunOutcome(evidence: DecisionRunOutcomeEvidence): DecisionRunOutcome {
    val normalizedError = evidence.errorMessage.orEmpty().lowercase()
    val recoveredAfterShutdown = normalizedError.contains("previous process") &&
        (normalizedError.contains("shutdown") || normalizedError.contains("container"))

    return when {
        evidence.status == "RUNNING" -> DecisionRunOutcome.RUNNING
        recoveredAfterShutdown -> DecisionRunOutcome.INTERRUPTED
        evidence.safetyRule != null -> DecisionRunOutcome.DENIED
        evidence.status == "FAILED" || evidence.status == "CANCELLED" -> DecisionRunOutcome.FAILED
        evidence.action == "NO_TRADE" || evidence.hasNoTradeExit -> DecisionRunOutcome.NO_TRADE
        evidence.orderCount > 0 || evidence.executionCount > 0 -> DecisionRunOutcome.EXECUTED
        else -> DecisionRunOutcome.FAILED
    }
}

/** decision run 一覧の安定 cursor。 */
data class DecisionRunCursor(
    val startedAt: Instant,
    val invocationId: String,
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

/** decision run 詳細の正規化 projection。 */
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
    /** run を新しい順で返す。 */
    suspend fun listRuns(cursor: DecisionRunCursor?, limit: Int): Result<List<DecisionRunSummary>>

    /** invocation ID に対応する run 詳細を返す。 */
    suspend fun findRun(invocationId: String): Result<DecisionRunDetail?>
}
