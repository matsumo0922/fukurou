package me.matsumo.fukurou.trading.domain

import kotlinx.serialization.Serializable

/**
 * 直近約定の売買方向。
 */
@Serializable
enum class TradeSide {
    /**
     * 買い約定。
     */
    BUY,

    /**
     * 売り約定。
     */
    SELL,
}

/**
 * 取引所から取得した直近約定。
 *
 * @param symbol 取引対象 symbol
 * @param price 約定価格
 * @param size 約定数量
 * @param side 売買方向
 * @param timestamp 取引所が返した約定時刻
 */
@Serializable
data class RecentTrade(
    val symbol: String,
    val price: String,
    val size: String,
    val side: TradeSide,
    val timestamp: String,
)
