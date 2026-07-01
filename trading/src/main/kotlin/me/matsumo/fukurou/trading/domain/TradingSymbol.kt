package me.matsumo.fukurou.trading.domain

/**
 * 取引対象の内部表現。GMO Public API へ渡す現物 symbol を保持する。
 */
enum class TradingSymbol(
    val apiSymbol: String,
) {
    /**
     * GMO コイン取引所の BTC 現物。
     */
    BTC("BTC"),
}
