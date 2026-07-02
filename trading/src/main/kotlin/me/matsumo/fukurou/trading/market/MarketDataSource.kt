package me.matsumo.fukurou.trading.market

import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol

/**
 * LLM と保護処理が参照する公開市場データ取得元。
 */
interface MarketDataSource {
    /**
     * 指定 symbol の現在値を取得する。
     */
    suspend fun getTicker(symbol: TradingSymbol): Result<Ticker>

    /**
     * 指定 symbol / interval の直近ローソク足を取得する。
     */
    suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>>

    /**
     * 指定 symbol の板情報を取得する。
     */
    suspend fun getOrderbook(
        symbol: TradingSymbol,
        depth: Int,
    ): Result<Orderbook>

    /**
     * 指定 symbol の直近約定を取得する。
     */
    suspend fun getTrades(
        symbol: TradingSymbol,
        limit: Int,
    ): Result<List<RecentTrade>>

    /**
     * 指定 symbol の最小発注数量や刻み幅を取得する。
     */
    suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules>
}
