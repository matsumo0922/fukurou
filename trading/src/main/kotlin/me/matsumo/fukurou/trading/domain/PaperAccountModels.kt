package me.matsumo.fukurou.trading.domain

import kotlinx.serialization.Serializable

/**
 * 取引実行 mode。
 */
@Serializable
enum class TradingMode {
    /**
     * 仮想資金だけで売買状態を管理する mode。
     */
    PAPER,

    /**
     * 将来の実資金 mode。現時点では読み取り model の予約値。
     */
    LIVE,
}

/**
 * 現物 position の方向。
 */
@Serializable
enum class PositionSide {
    /**
     * BTC 現物の買い持ち。
     */
    LONG,
}

/**
 * position ledger 上の状態。
 */
@Serializable
enum class PositionStatus {
    /**
     * 現在保有中。
     */
    OPEN,

    /**
     * 決済済み。
     */
    CLOSED,
}

/**
 * 注文 side。
 */
@Serializable
enum class OrderSide {
    /**
     * 買い注文。
     */
    BUY,

    /**
     * 売り注文。
     */
    SELL,
}

/**
 * GMO 現物で扱う注文種別。
 */
@Serializable
enum class OrderType {
    /**
     * 成行注文。
     */
    MARKET,

    /**
     * 指値注文。
     */
    LIMIT,

    /**
     * 逆指値注文。
     */
    STOP,
}

/**
 * paper / exchange 注文の状態。
 */
@Serializable
enum class OrderStatus {
    /**
     * 未約定。
     */
    OPEN,

    /**
     * 取消待ち。
     */
    PENDING_CANCEL,

    /**
     * 約定済み。
     */
    FILLED,

    /**
     * 取消済み。
     */
    CANCELED,

    /**
     * 拒否済み。
     */
    REJECTED,
}

/** resting entry order の実効期限を決めた入力。 */
@Serializable
enum class OrderExpirySource {
    /** system の resting entry order TTL。 */
    SYSTEM_TTL,

    /** LLM TradePlan の timeStopAt。 */
    LLM_TIME_STOP,
}

/**
 * 約定時の流動性区分。
 */
@Serializable
enum class ExecutionLiquidity {
    /**
     * maker 約定。
     */
    MAKER,

    /**
     * taker 約定。
     */
    TAKER,
}

/**
 * 注文種別だけで entry liquidity を近似する既定値。
 */
internal fun OrderType.defaultEntryLiquidity(): ExecutionLiquidity {
    return when (this) {
        OrderType.LIMIT -> ExecutionLiquidity.MAKER
        OrderType.MARKET,
        OrderType.STOP,
        -> ExecutionLiquidity.TAKER
    }
}

/**
 * paper 口座の資産 snapshot。
 *
 * @param mode 取引 mode
 * @param cashJpy JPY 現金残高
 * @param initialCashJpy 初期 JPY 残高
 * @param btcQuantity BTC 保有数量
 * @param btcMarkPriceJpy BTC 評価価格
 * @param totalEquityJpy 総評価額
 * @param equityPeakJpy 総評価額の過去ピーク
 * @param drawdownRatio equityPeakJpy からの下落率
 */
@Serializable
data class AccountSnapshot(
    val mode: TradingMode,
    val cashJpy: String,
    val initialCashJpy: String,
    val btcQuantity: String,
    val btcMarkPriceJpy: String,
    val totalEquityJpy: String,
    val equityPeakJpy: String,
    val drawdownRatio: String,
)

/**
 * 現物 position ledger の読み取り model。
 *
 * @param positionId position ID
 * @param tradeGroupId group 単位リスク評価用 ID
 * @param symbol 取引対象 symbol
 * @param mode 取引 mode
 * @param side position side
 * @param status position 状態
 * @param openedAt 開設時刻
 * @param closedAt 決済時刻
 * @param sizeBtc 未決済 BTC 残量。closed position の初期数量と決済履歴は execution から復元する。
 * @param averageEntryPriceJpy 平均取得単価
 * @param currentPriceJpy 現在評価価格
 * @param currentStopLossJpy 現在の保護 STOP 価格
 * @param currentTakeProfitJpy 現在の virtual TP 価格
 * @param unrealizedPnlJpy 未実現損益
 * @param unrealizedR 未実現 R
 * @param pyramidAddCount ピラミッディング追加回数
 * @param highestPriceSinceEntryJpy entry 以降の最高値
 * @param lowestPriceSinceEntryJpy entry 以降の最安値。null は記録開始前で #28 の MAE 評価対象外であることを表す。
 */
@Serializable
data class Position(
    val positionId: String,
    val tradeGroupId: String,
    val symbol: String,
    val mode: TradingMode,
    val side: PositionSide,
    val status: PositionStatus,
    val openedAt: String,
    val closedAt: String?,
    val sizeBtc: String,
    val averageEntryPriceJpy: String,
    val currentPriceJpy: String,
    val currentStopLossJpy: String?,
    val currentTakeProfitJpy: String?,
    val unrealizedPnlJpy: String,
    val unrealizedR: String,
    val pyramidAddCount: Int,
    val highestPriceSinceEntryJpy: String,
    val lowestPriceSinceEntryJpy: String?,
)

/**
 * 注文 ledger の読み取り model。
 *
 * @param orderId 注文 ID
 * @param intentId entry intent ID
 * @param positionId 関連 position ID
 * @param tradeGroupId 関連 trade group ID
 * @param symbol 取引対象 symbol
 * @param mode 取引 mode
 * @param side 注文 side
 * @param orderType 注文種別
 * @param status 注文状態
 * @param sizeBtc 注文数量
 * @param limitPriceJpy 指値価格
 * @param triggerPriceJpy STOP trigger 価格
 * @param protectiveStopPriceJpy entry intent に紐づく保護 STOP 価格
 * @param takeProfitPriceJpy entry intent に紐づく virtual TP 価格
 * @param estimatedWinProbability entry intent で LLM が申告した推定勝率
 * @param reasonJa LLM / system の判断理由
 * @param clientRequestId 呼び出し元が渡した冪等化・追跡用 ID
 * @param expiresAt resting entry order の作成時に固定した実効期限
 * @param expirySource 実効期限を決めた入力
 * @param effectiveTtlSeconds 作成時刻から実効期限までの秒数
 * @param expiredAt 論理的な期限到達時刻。TTL取消では expiresAt と一致する
 * @param canceledAt 取消処理を永続化した server 時刻
 * @param cancelReason 取消理由 code
 * @param canceledByDecisionRunId 取消を実行した decision run ID
 * @param createdAt 作成時刻
 * @param updatedAt 更新時刻
 */
@Serializable
data class Order(
    val orderId: String,
    val intentId: String? = null,
    val positionId: String?,
    val tradeGroupId: String?,
    val symbol: String,
    val mode: TradingMode,
    val side: OrderSide,
    val orderType: OrderType,
    val status: OrderStatus,
    val sizeBtc: String,
    val limitPriceJpy: String?,
    val triggerPriceJpy: String?,
    val protectiveStopPriceJpy: String?,
    val takeProfitPriceJpy: String?,
    val estimatedWinProbability: String? = null,
    val reasonJa: String?,
    val clientRequestId: String?,
    val expiresAt: String? = null,
    val expirySource: OrderExpirySource? = null,
    val effectiveTtlSeconds: Long? = null,
    val expiredAt: String? = null,
    val canceledAt: String? = null,
    val cancelReason: PaperOrderCancelReason? = null,
    val canceledByDecisionRunId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * 約定 ledger の読み取り model。
 *
 * @param executionId 約定 ID
 * @param orderId 関連注文 ID
 * @param positionId 関連 position ID
 * @param symbol 取引対象 symbol
 * @param mode 取引 mode
 * @param side 約定 side
 * @param priceJpy 約定価格
 * @param sizeBtc 約定数量
 * @param feeJpy 手数料
 * @param realizedPnlJpy 実現損益
 * @param liquidity maker / taker 区分
 * @param executedAt 約定時刻
 */
@Serializable
data class Execution(
    val executionId: String,
    val orderId: String?,
    val positionId: String?,
    val symbol: String,
    val mode: TradingMode,
    val side: OrderSide,
    val priceJpy: String,
    val sizeBtc: String,
    val feeJpy: String,
    val realizedPnlJpy: String,
    val liquidity: ExecutionLiquidity,
    val executedAt: String,
)

/**
 * 口座保護状態の summary。
 *
 * @param protectedPositionCount 保護 STOP を持つ open position 数
 * @param unprotectedPositionCount 保護 STOP を持たない open position 数
 * @param orphanStopCount position に紐づかない STOP 注文数
 * @param orphanTakeProfitCount position に紐づかない TP 注文数
 * @param pendingCancelCount 取消待ち注文数
 * @param lastReconciledAt 最後の reconciler 完了時刻
 * @param lastMarketDataAt 最後の market data 確認時刻
 * @param tradingLockOwner 現在の lock owner
 */
@Serializable
data class ProtectionStatus(
    val protectedPositionCount: Int,
    val unprotectedPositionCount: Int,
    val orphanStopCount: Int,
    val orphanTakeProfitCount: Int,
    val pendingCancelCount: Int,
    val lastReconciledAt: String?,
    val lastMarketDataAt: String?,
    val tradingLockOwner: String?,
)

/**
 * LLM に返す account status。
 *
 * @param mode 取引 mode
 * @param riskState risk_state 由来の状態
 * @param drawdownRatio equity peak からの drawdown
 * @param hardHalt sticky HARD_HALT flag
 * @param currentEquityJpy 現在の総評価額
 * @param todayRealizedPnlJpy 当日実現損益
 * @param protectionStatus 保護状態 summary
 */
@Serializable
data class AccountStatus(
    val mode: TradingMode,
    val riskState: String,
    val drawdownRatio: String,
    val hardHalt: Boolean,
    val currentEquityJpy: String,
    val todayRealizedPnlJpy: String,
    val protectionStatus: ProtectionStatus,
)
