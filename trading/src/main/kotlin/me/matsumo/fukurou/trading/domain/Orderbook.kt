package me.matsumo.fukurou.trading.domain

import kotlinx.serialization.Serializable

/**
 * 取引所から取得した板情報。
 *
 * @param symbol 取引対象 symbol
 * @param bids 買い板
 * @param asks 売り板
 */
@Serializable
data class Orderbook(
    val symbol: String,
    val bids: List<OrderbookLevel>,
    val asks: List<OrderbookLevel>,
)

/**
 * 板の 1 price level。
 *
 * @param price 価格
 * @param size 数量
 */
@Serializable
data class OrderbookLevel(
    val price: String,
    val size: String,
)
