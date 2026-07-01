package me.matsumo.fukurou.trading.domain

import kotlinx.serialization.Serializable

/**
 * 現在値を表す ticker。数値は取引所の decimal 精度を保つため文字列で保持する。
 *
 * @param symbol 取引対象 symbol
 * @param last 最終約定価格
 * @param bid 最良買気配
 * @param ask 最良売気配
 * @param high 当日高値
 * @param low 当日安値
 * @param volume 出来高
 * @param timestamp 取引所が返した ticker 時刻
 */
@Serializable
data class Ticker(
    val symbol: String,
    val last: String,
    val bid: String,
    val ask: String,
    val high: String,
    val low: String,
    val volume: String,
    val timestamp: String,
)
