package me.matsumo.fukurou.trading.activity

import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.time.Instant

/** SafetyFloor 拒否 feedback の取得条件。 */
data class DecisionRunSafetyDenialQuery(
    val symbol: TradingSymbol,
    val from: Instant,
    val toExclusive: Instant,
    val limit: Int,
)

/** SafetyFloor が最終 outcome である decision run の projection。 */
data class DecisionRunSafetyDenial(
    val invocationId: String,
    val deniedAt: Instant,
    val finalReason: String?,
    val decision: DecisionRunDecision?,
    val intent: DecisionRunIntent?,
    val falsification: DecisionRunFalsification?,
    val safetyViolation: DecisionRunSafetyViolation,
)

/** bounded SafetyFloor 拒否 read の page。 */
data class DecisionRunSafetyDenialPage(
    val denials: List<DecisionRunSafetyDenial>,
    val truncated: Boolean,
)

/** Activity と共通の outcome policy で SafetyFloor 拒否を読む境界。 */
interface DecisionRunSafetyDenialReader {
    suspend fun readSafetyDenials(query: DecisionRunSafetyDenialQuery): Result<DecisionRunSafetyDenialPage>
}

/** DB を持たない runtime 用の明示的な空 reader。 */
object EmptyDecisionRunSafetyDenialReader : DecisionRunSafetyDenialReader {
    override suspend fun readSafetyDenials(query: DecisionRunSafetyDenialQuery): Result<DecisionRunSafetyDenialPage> {
        return Result.success(DecisionRunSafetyDenialPage(emptyList(), truncated = false))
    }
}
