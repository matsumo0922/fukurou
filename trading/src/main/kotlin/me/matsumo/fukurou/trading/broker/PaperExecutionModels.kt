package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
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
 * @param symbol 取引対象 symbol
 * @param side 注文 side
 * @param orderType 注文種別
 * @param sizeBtc 注文数量
 * @param priceJpy LIMIT / STOP entry の価格
 * @param protectiveStopPriceJpy entry 後に必ず置く保護 STOP 価格
 * @param takeProfitPriceJpy virtual TP 価格
 * @param reasonJa 判断理由
 * @param auditContext audit context
 */
data class PlaceOrderCommand(
    val commandId: UUID,
    val symbol: TradingSymbol,
    val side: OrderSide,
    val orderType: OrderType,
    val sizeBtc: BigDecimal,
    val priceJpy: BigDecimal?,
    val protectiveStopPriceJpy: BigDecimal,
    val takeProfitPriceJpy: BigDecimal?,
    val reasonJa: String,
    val auditContext: PaperTradeAuditContext,
)

/**
 * paper position close command。
 *
 * @param commandId command ID
 * @param positionId 対象 position ID
 * @param closeAll 全 open position を閉じるか
 * @param reasonJa 判断理由
 * @param auditContext audit context
 */
data class ClosePositionCommand(
    val commandId: UUID,
    val positionId: UUID?,
    val closeAll: Boolean,
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
 * @param reasonJa 判断理由
 * @param auditContext audit context
 */
data class CancelOrderCommand(
    val commandId: UUID,
    val orderId: UUID,
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
 * paper command の戻り値。
 *
 * @param accepted command を受理したか
 * @param status 主注文または対象注文の状態
 * @param orderIds command で作成・更新した order IDs
 * @param positionIds command で作成・更新した position IDs
 * @param executionIds command で作成した execution IDs
 * @param messageJa 呼び出し元へ返す日本語 message
 */
data class PaperTradeResult(
    val accepted: Boolean,
    val status: OrderStatus,
    val orderIds: List<String>,
    val positionIds: List<String>,
    val executionIds: List<String>,
    val messageJa: String,
)

/**
 * Reconciler が進めた paper ledger の結果。
 *
 * @param advanced 状態が前進したか
 * @param triggeredOrderIds trigger された order IDs
 * @param closedPositionIds close された position IDs
 * @param executionIds 作成された execution IDs
 */
data class PaperReconcileResult(
    val advanced: Boolean,
    val triggeredOrderIds: List<String>,
    val closedPositionIds: List<String>,
    val executionIds: List<String>,
)
