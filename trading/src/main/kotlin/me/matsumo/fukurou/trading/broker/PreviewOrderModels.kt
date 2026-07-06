package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.safety.SafetyFloorPlaceOrderRiskDetails
import me.matsumo.fukurou.trading.safety.SafetyViolation
import java.security.MessageDigest

/**
 * preview hash の対象にする正規化済み order 内容。
 *
 * @param intentId entry intent ID
 * @param symbol 取引対象 symbol
 * @param side 注文 side
 * @param orderType 注文種別
 * @param sizeBtc 注文数量
 * @param priceJpy LIMIT / STOP entry 価格
 * @param tradeGroupId 明示された trade group ID
 * @param protectiveStopPriceJpy 保護 STOP 価格
 * @param takeProfitPriceJpy virtual TP 価格
 * @param estimatedWinProbability 推定勝率
 */
data class PreviewOrderNormalizedContent(
    val intentId: String?,
    val symbol: String,
    val side: String,
    val orderType: String,
    val sizeBtc: String,
    val priceJpy: String?,
    val tradeGroupId: String?,
    val protectiveStopPriceJpy: String,
    val takeProfitPriceJpy: String?,
    val estimatedWinProbability: String,
)

/**
 * paper entry 注文 preview の戻り値。
 *
 * @param accepted SafetyFloor と broker 事前検証を通過したか
 * @param previewHash 正規化 order 内容の SHA-256 hash
 * @param normalizedOrderContent hash 対象の正規化 order 内容
 * @param riskDetails SafetyFloor 計算で使う主要 risk 詳細
 * @param messageJa 呼び出し元向け日本語 message
 * @param safetyViolation SafetyFloor による最初の拒否内容
 */
data class PreviewOrderResult(
    val accepted: Boolean,
    val previewHash: String,
    val normalizedOrderContent: PreviewOrderNormalizedContent,
    val riskDetails: SafetyFloorPlaceOrderRiskDetails,
    val messageJa: String,
    val safetyViolation: SafetyViolation? = null,
)

/**
 * order command を preview hash 用に正規化する。
 */
fun PlaceOrderCommand.toPreviewOrderNormalizedContent(): PreviewOrderNormalizedContent {
    return PreviewOrderNormalizedContent(
        intentId = intentId?.toString(),
        symbol = symbol.apiSymbol,
        side = side.name,
        orderType = orderType.name,
        sizeBtc = sizeBtc.toPlainString(),
        priceJpy = priceJpy?.toPlainString(),
        tradeGroupId = tradeGroupId?.toString(),
        protectiveStopPriceJpy = protectiveStopPriceJpy.toPlainString(),
        takeProfitPriceJpy = takeProfitPriceJpy?.toPlainString(),
        estimatedWinProbability = estimatedWinProbability.toPlainString(),
    )
}

/**
 * 正規化 order 内容から preview hash を計算する。
 */
fun PreviewOrderNormalizedContent.calculatePreviewHash(): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalPreviewPayload().toByteArray(Charsets.UTF_8))

    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun PreviewOrderNormalizedContent.canonicalPreviewPayload(): String {
    return listOf(
        "intent_id=${intentId ?: "null"}",
        "symbol=$symbol",
        "side=$side",
        "type=$orderType",
        "size_btc=$sizeBtc",
        "price_jpy=${priceJpy ?: "null"}",
        "trade_group_id=${tradeGroupId ?: "null"}",
        "protective_stop_price_jpy=$protectiveStopPriceJpy",
        "take_profit_price_jpy=${takeProfitPriceJpy ?: "null"}",
        "estimated_win_probability=$estimatedWinProbability",
    ).joinToString(separator = "\n")
}
