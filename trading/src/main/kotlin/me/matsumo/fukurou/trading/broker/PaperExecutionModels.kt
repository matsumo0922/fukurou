package me.matsumo.fukurou.trading.broker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.PaperOrderCancelReason
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.safety.SafetyViolation
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * paper trade command に保存する audit context。
 *
 * @param decisionRunContext LLM 起動単位の監査 context
 * @param toolCallId tool call 単位の ID
 * @param clientRequestId 呼び出し元 request ID
 */
data class PaperTradeAuditContext(
    val decisionRunContext: DecisionRunContext,
    val toolCallId: String?,
    val clientRequestId: String?,
) {
    companion object {
        /**
         * LLM 起動に紐づかない worker / test 用 context。
         */
        val EMPTY = PaperTradeAuditContext(
            decisionRunContext = DecisionRunContext.EMPTY,
            toolCallId = null,
            clientRequestId = null,
        )

        /**
         * ToolCallGuard の envelope から audit context を取り出す。
         */
        fun fromGuardedToolCall(call: GuardedToolCall): PaperTradeAuditContext {
            return PaperTradeAuditContext(
                decisionRunContext = call.decisionRunContext,
                toolCallId = call.toolCallId,
                clientRequestId = call.clientRequestId,
            )
        }
    }
}

/**
 * paper entry 注文 command。
 *
 * @param commandId command ID
 * @param intentId decision → falsification → order を結ぶ intent ID
 * @param symbol 取引対象 symbol
 * @param side 注文 side
 * @param orderType 注文種別
 * @param sizeBtc 注文数量
 * @param priceJpy LIMIT / STOP entry の価格
 * @param tradeGroupId 買い増し対象の trade group ID。null の場合は新規 group
 * @param protectiveStopPriceJpy entry 後に必ず置く保護 STOP 価格
 * @param takeProfitPriceJpy virtual TP 価格
 * @param estimatedWinProbability LLM が申告した推定勝率。EV は SafetyFloor がこの値から計算する
 * @param timeStopAt LLM TradePlan が指定した entry の期限
 * @param reasonJa 判断理由
 * @param auditContext audit context
 * @param canonicalThesisId in-memory ledger に投影する persisted intent の canonical thesis ID
 */
data class PlaceOrderCommand(
    val commandId: UUID,
    val intentId: UUID? = null,
    val symbol: TradingSymbol,
    val side: OrderSide,
    val orderType: OrderType,
    val sizeBtc: BigDecimal,
    val priceJpy: BigDecimal?,
    val tradeGroupId: UUID?,
    val protectiveStopPriceJpy: BigDecimal,
    val takeProfitPriceJpy: BigDecimal?,
    val estimatedWinProbability: BigDecimal,
    val timeStopAt: Instant? = null,
    val reasonJa: String,
    val auditContext: PaperTradeAuditContext,
    val canonicalThesisId: String? = null,
)

/**
 * paper position close command。
 *
 * @param commandId command ID
 * @param positionId 対象 position ID
 * @param closeAll 全 open position を閉じるか
 * @param closeRatio 対象 position 残量のうち決済する比率
 * @param reasonJa 判断理由
 * @param auditContext audit context
 */
data class ClosePositionCommand(
    val commandId: UUID,
    val positionId: UUID?,
    val closeAll: Boolean,
    val closeRatio: BigDecimal = BigDecimal.ONE,
    val reasonJa: String,
    val auditContext: PaperTradeAuditContext,
)

/**
 * paper position の保護更新 command。
 *
 * @param commandId command ID
 * @param positionId 対象 position ID
 * @param newStopPriceJpy 新しい STOP 価格
 * @param takeProfitPriceSpecified TP 更新指定が存在したか
 * @param newTakeProfitPriceJpy 新しい virtual TP 価格。null 指定なら削除
 * @param reasonJa 判断理由
 * @param auditContext audit context
 */
data class UpdateProtectionCommand(
    val commandId: UUID,
    val positionId: UUID,
    val newStopPriceJpy: BigDecimal?,
    val takeProfitPriceSpecified: Boolean,
    val newTakeProfitPriceJpy: BigDecimal?,
    val reasonJa: String,
    val auditContext: PaperTradeAuditContext,
)

/**
 * paper order cancel command。
 *
 * @param commandId command ID
 * @param orderId 取消対象 order ID
 * @param cancelReason 永続化する取消理由 code
 * @param reasonJa 判断理由
 * @param auditContext audit context
 */
data class CancelOrderCommand(
    val commandId: UUID,
    val orderId: UUID,
    val cancelReason: PaperOrderCancelReason = PaperOrderCancelReason.EXPLICIT_CANCEL,
    val reasonJa: String,
    val auditContext: PaperTradeAuditContext,
)

/**
 * paper execution の計算済み約定。
 *
 * @param executionId execution ID
 * @param priceJpy 約定価格
 * @param sizeBtc 約定数量
 * @param feeJpy 手数料。maker rebate は負値
 * @param realizedPnlJpy 実現損益
 * @param liquidity maker / taker 区分
 * @param executedAt 約定時刻
 */
data class SimulatedFill(
    val executionId: UUID,
    val priceJpy: BigDecimal,
    val sizeBtc: BigDecimal,
    val feeJpy: BigDecimal,
    val realizedPnlJpy: BigDecimal,
    val liquidity: ExecutionLiquidity,
    val executedAt: Instant,
)

/**
 * paper LIMIT 約定と FAK 部分約定モデルの乖離を追跡する structured memo。
 *
 * @param kind memo 種別
 * @param orderId 乖離が発生した paper order ID
 * @param intentId entry intent ID
 * @param tradeGroupId entry / stop / position を束ねる trade group ID
 * @param clientRequestId 呼び出し元 request ID
 * @param symbol 取引対象 symbol
 * @param side 注文 side
 * @param limitPriceJpy LIMIT 価格
 * @param requestedSizeBtc 注文数量
 * @param hypotheticalFilledSizeBtc FAK なら約定したと推定される数量
 * @param hypotheticalRemainingSizeBtc FAK なら残ったと推定される数量
 * @param boardDepthBtc LIMIT 価格までの反対側板数量
 * @param queueFillRatio maker queue の約定率
 * @param bestBidJpy memo 作成時点の best bid
 * @param bestAskJpy memo 作成時点の best ask
 */
data class PaperExecutionDivergenceMemo(
    val kind: String,
    val orderId: String? = null,
    val intentId: String? = null,
    val tradeGroupId: String? = null,
    val clientRequestId: String? = null,
    val symbol: String? = null,
    val side: OrderSide,
    val limitPriceJpy: BigDecimal,
    val requestedSizeBtc: BigDecimal,
    val hypotheticalFilledSizeBtc: BigDecimal,
    val hypotheticalRemainingSizeBtc: BigDecimal,
    val boardDepthBtc: BigDecimal,
    val queueFillRatio: BigDecimal,
    val bestBidJpy: BigDecimal?,
    val bestAskJpy: BigDecimal?,
)

/**
 * paper execution 乖離 memo を audit payload 用 JSON object に変換する。
 */
fun PaperExecutionDivergenceMemo.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("kind", kind)
        orderId?.let { value -> put("orderId", value) }
        intentId?.let { value -> put("intentId", value) }
        tradeGroupId?.let { value -> put("tradeGroupId", value) }
        clientRequestId?.let { value -> put("clientRequestId", value) }
        symbol?.let { value -> put("symbol", value) }
        put("side", side.name)
        put("limitPriceJpy", limitPriceJpy.toPlainString())
        put("requestedSizeBtc", requestedSizeBtc.toPlainString())
        put("hypotheticalFilledSizeBtc", hypotheticalFilledSizeBtc.toPlainString())
        put("hypotheticalRemainingSizeBtc", hypotheticalRemainingSizeBtc.toPlainString())
        put("boardDepthBtc", boardDepthBtc.toPlainString())
        put("queueFillRatio", queueFillRatio.toPlainString())
        bestBidJpy?.let { value -> put("bestBidJpy", value.toPlainString()) }
        bestAskJpy?.let { value -> put("bestAskJpy", value.toPlainString()) }
    }
}

/**
 * paper command の戻り値。
 *
 * @param accepted command を受理したか
 * @param status 主注文または対象注文の状態
 * @param orderIds command で作成・更新した order IDs
 * @param positionIds command で作成・更新した position IDs
 * @param executionIds command で作成した execution IDs
 * @param messageJa 呼び出し元へ返す日本語 message
 * @param safetyViolation SafetyFloor による拒否内容
 * @param divergenceMemos paper/live 乖離を audit に渡す structured memo
 */
data class PaperTradeResult(
    val accepted: Boolean,
    val status: OrderStatus,
    val orderIds: List<String>,
    val positionIds: List<String>,
    val executionIds: List<String>,
    val messageJa: String,
    val safetyViolation: SafetyViolation? = null,
    val divergenceMemos: List<PaperExecutionDivergenceMemo> = emptyList(),
)

/**
 * Reconciler が進めた paper ledger の結果。
 *
 * @param advanced 状態が前進したか
 * @param filledOrderIds 約定した order IDs
 * @param canceledOrderIds 取消した order IDs
 * @param rejectedOrderIds 拒否した order IDs
 * @param closedPositionIds close された position IDs
 * @param executionIds 作成された execution IDs
 * @param divergenceMemos paper/live 乖離を command_event_log へ残すための structured memo
 */
data class PaperReconcileResult(
    val advanced: Boolean,
    val filledOrderIds: List<String>,
    val canceledOrderIds: List<String>,
    val rejectedOrderIds: List<String>,
    val closedPositionIds: List<String>,
    val executionIds: List<String>,
    val divergenceMemos: List<PaperExecutionDivergenceMemo> = emptyList(),
)
