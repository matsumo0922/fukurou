package me.matsumo.fukurou.trading.decision

import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * LLM が提出する最終 action。
 */
enum class DecisionAction {
    /**
     * 新規 long entry を提案する。
     */
    ENTER,

    /**
     * 既存 position の退出を提案する。
     */
    EXIT,

    /**
     * 既存 position の保護更新を提案する。
     */
    ADJUST_PROTECTION,

    /**
     * 今回は取引しない正式判断。
     */
    NO_TRADE,
}

/**
 * Falsifier が entry intent に対して返す verdict。
 */
enum class FalsificationVerdict {
    /**
     * entry intent を fresh window 内で承認する。
     */
    APPROVED,

    /**
     * entry intent を拒否する。
     */
    REJECTED,
}

/**
 * decision.submit_decision が受け取る判断本文。
 *
 * @param invocationId daemon / CLI 起動単位の ID
 * @param llmProvider LLM provider 名
 * @param promptHash prompt 内容 hash
 * @param systemPromptVersion system prompt version
 * @param marketSnapshotId 判断前 market snapshot ID
 * @param action 最終 action
 * @param setupTags setup taxonomy 用タグ
 * @param estimatedWinProbability LLM 申告の推定勝率
 * @param expectedRMultiple 期待 R 倍率
 * @param roundTripCostR 往復 cost の R 換算
 * @param toolEvidenceIds 判断根拠にした tool call ID 一覧
 * @param factCheckJson fact check の JSON 文字列
 * @param selfReviewJson self review の JSON 文字列
 * @param reasonJa 判断理由
 * @param missingDataJa NO_TRADE を含む不足データ
 * @param noTradeConditionsJa 見送り条件
 * @param entryIntent ENTER 時に作成する intent 宣言
 * @param tradePlan ENTER または正式修正時に保存する TradePlan
 */
data class DecisionSubmission(
    val invocationId: String?,
    val llmProvider: String?,
    val promptHash: String?,
    val systemPromptVersion: String?,
    val marketSnapshotId: String?,
    val action: DecisionAction,
    val setupTags: List<String>,
    val estimatedWinProbability: BigDecimal,
    val expectedRMultiple: BigDecimal?,
    val roundTripCostR: BigDecimal?,
    val toolEvidenceIds: List<String>,
    val factCheckJson: String,
    val selfReviewJson: String,
    val reasonJa: String,
    val missingDataJa: List<String>,
    val noTradeConditionsJa: List<String>,
    val entryIntent: EntryIntentDraft?,
    val tradePlan: TradePlanDraft?,
)

/**
 * 永続化済み decision。
 *
 * @param decisionId decision ID
 * @param submission 提出された判断本文
 * @param createdAt 保存時刻
 */
data class DecisionRecord(
    val decisionId: UUID,
    val submission: DecisionSubmission,
    val createdAt: Instant,
)

/**
 * ENTER decision から発行する intent 宣言。
 *
 * @param symbol 取引対象 symbol
 * @param side 注文 side
 * @param orderType 注文種別
 * @param sizeBtc 注文数量
 * @param priceJpy LIMIT / STOP entry 価格
 * @param protectiveStopPriceJpy 保護 STOP 価格
 * @param takeProfitPriceJpy virtual TP 価格
 */
data class EntryIntentDraft(
    val symbol: TradingSymbol,
    val side: OrderSide,
    val orderType: OrderType,
    val sizeBtc: BigDecimal,
    val priceJpy: BigDecimal?,
    val protectiveStopPriceJpy: BigDecimal,
    val takeProfitPriceJpy: BigDecimal?,
)

/**
 * 永続化済み trade intent。
 *
 * @param intentId intent ID
 * @param decisionId 紐づく decision ID
 * @param tradePlanId 紐づく TradePlan ID
 * @param draft 宣言された発注内容
 * @param estimatedWinProbability decision に保存した推定勝率
 * @param createdAt 作成時刻
 */
data class TradeIntentRecord(
    val intentId: UUID,
    val decisionId: UUID,
    val tradePlanId: UUID,
    val draft: EntryIntentDraft,
    val estimatedWinProbability: BigDecimal,
    val createdAt: Instant,
)

/**
 * TradePlan の保存要求。
 *
 * @param parentTradePlanId 改訂元 TradePlan ID
 * @param revisionCount 改訂回数
 * @param symbol 取引対象 symbol
 * @param thesisJa 取引仮説
 * @param invalidationConditionsJa 否定条件
 * @param targetPriceJpy 目標価格
 * @param timeStopAt 時間切れ条件
 * @param setupTags setup taxonomy 用タグ
 */
data class TradePlanDraft(
    val parentTradePlanId: UUID?,
    val revisionCount: Int,
    val symbol: TradingSymbol,
    val thesisJa: String,
    val invalidationConditionsJa: List<String>,
    val targetPriceJpy: BigDecimal?,
    val timeStopAt: Instant?,
    val setupTags: List<String>,
)

/**
 * 永続化済み TradePlan。
 *
 * @param tradePlanId TradePlan ID
 * @param decisionId 紐づく decision ID
 * @param draft 保存された TradePlan 本文
 * @param createdAt 作成時刻
 */
data class TradePlanRecord(
    val tradePlanId: UUID,
    val decisionId: UUID,
    val draft: TradePlanDraft,
    val createdAt: Instant,
)

/**
 * decision.submit_decision の保存結果。
 *
 * @param decision 保存済み decision
 * @param tradeIntent ENTER 時に作成された intent
 * @param tradePlan 作成または改訂された TradePlan
 */
data class DecisionSubmissionResult(
    val decision: DecisionRecord,
    val tradeIntent: TradeIntentRecord?,
    val tradePlan: TradePlanRecord?,
)

/**
 * decision.submit_falsification の保存要求。
 *
 * @param intentId 対象 intent ID
 * @param verdict Falsifier の判定
 * @param llmProvider Falsifier provider 名
 * @param reasonJa 判定理由
 */
data class FalsificationSubmission(
    val intentId: UUID?,
    val verdict: FalsificationVerdict,
    val llmProvider: String?,
    val reasonJa: String,
)

/**
 * 永続化済み falsification。
 *
 * @param falsificationId falsification ID
 * @param intentId 対象 intent ID
 * @param verdict Falsifier の判定
 * @param llmProvider Falsifier provider 名
 * @param reasonJa 判定理由
 * @param createdAt 作成時刻
 */
data class FalsificationRecord(
    val falsificationId: UUID,
    val intentId: UUID,
    val verdict: FalsificationVerdict,
    val llmProvider: String?,
    val reasonJa: String,
    val createdAt: Instant,
)

/**
 * SafetyFloor へ渡す entry intent の snapshot。
 *
 * @param tradeIntent intent 宣言
 * @param falsification 最新 falsification
 * @param consumed intent が既に消費済みか
 * @param freshApproved fresh window 内の APPROVED が存在するか
 */
data class EntryIntentSafetySnapshot(
    val tradeIntent: TradeIntentRecord,
    val falsification: FalsificationRecord?,
    val consumed: Boolean,
    val freshApproved: Boolean,
)

/**
 * Falsifier が intent ID だけから読み直す review 用 snapshot。
 *
 * @param tradeIntent intent 宣言
 * @param tradePlan intent に紐づく TradePlan
 */
data class TradeIntentReviewSnapshot(
    val tradeIntent: TradeIntentRecord,
    val tradePlan: TradePlanRecord?,
)

/**
 * intent 消費記録。
 *
 * @param consumptionId consumption ID
 * @param intentId 対象 intent ID
 * @param orderId 消費元 order ID
 * @param consumedAt 消費時刻
 */
data class TradeIntentConsumptionRecord(
    val consumptionId: UUID,
    val intentId: UUID,
    val orderId: UUID?,
    val consumedAt: Instant,
)

/**
 * 指定時刻で fresh な APPROVED verdict と見なせるかを返す。
 */
fun FalsificationRecord?.isFreshApprovedAt(observedAt: Instant, freshnessWindow: Duration): Boolean {
    if (this == null) {
        return false
    }
    if (verdict != FalsificationVerdict.APPROVED) {
        return false
    }

    val expiresAt = createdAt.plus(freshnessWindow)

    return !expiresAt.isBefore(observedAt)
}
