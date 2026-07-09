package me.matsumo.fukurou.trading.knowledge

import me.matsumo.fukurou.trading.decision.DecisionRecord
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.decision.TradePlanRecord
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Position

/**
 * Obsidian writer が一度に読む既定行数。
 */
const val DEFAULT_OBSIDIAN_QUERY_LIMIT = 1_000

/**
 * closed position と note 生成に必要な関連 ledger。
 *
 * @param position closed position 本体
 * @param decisionRunId entry 時の LLM invocation ID
 * @param executions position に紐づく約定履歴
 */
data class ClosedPaperPosition(
    val position: Position,
    val decisionRunId: String?,
    val executions: List<Execution>,
)

/**
 * decision と note 生成に必要な付随 record。
 *
 * @param decision 永続化済み decision
 * @param tradeIntent entry 系 action の intent
 * @param tradePlan decision に紐づく TradePlan
 * @param falsification intent に紐づく最新 Falsifier verdict
 */
data class DecisionJournalRecord(
    val decision: DecisionRecord,
    val tradeIntent: TradeIntentRecord?,
    val tradePlan: TradePlanRecord?,
    val falsification: FalsificationRecord?,
)

/**
 * Obsidian writer の 1 回分の出力結果。
 *
 * @param writtenFiles 新規作成または内容差し替えした file 数
 * @param unchangedFiles 既存内容と一致していた file 数
 */
data class ObsidianWriteSummary(
    val writtenFiles: Int,
    val unchangedFiles: Int,
)

/**
 * vault 書き込みを実行する interface。
 */
fun interface ObsidianWriter {
    /**
     * DB 状態から vault を 1 回再生成する。
     */
    suspend fun writeOnce(): Result<ObsidianWriteSummary>
}
