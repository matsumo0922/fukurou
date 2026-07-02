package me.matsumo.fukurou.trading.domain

import kotlinx.serialization.Serializable

/**
 * 取引所 symbol ごとの数量・価格・手数料ルール。
 *
 * @param symbol 取引対象 symbol
 * @param minOrderSize 最小発注数量
 * @param sizeStep 数量刻み
 * @param tickSize 価格刻み
 * @param takerFee taker 手数料率
 * @param makerFee maker 手数料率
 */
@Serializable
data class SymbolRules(
    val symbol: String,
    val minOrderSize: String,
    val sizeStep: String,
    val tickSize: String,
    val takerFee: String,
    val makerFee: String,
)
