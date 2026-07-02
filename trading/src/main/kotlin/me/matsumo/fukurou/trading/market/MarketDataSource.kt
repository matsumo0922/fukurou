package me.matsumo.fukurou.trading.market

import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol

/**
 * 市場データ取得元。Step1.5 の TickStream は ticker と recent trades を REST polling する。
 */
interface MarketDataSource {
    /**
     * 指定 symbol の現在値を取得する。
     */
    suspend fun getTicker(symbol: TradingSymbol): Result<Ticker>

    /**
     * 指定 symbol の直近約定を取得する。
     */
    suspend fun getRecentTrades(symbol: TradingSymbol): Result<List<RecentTrade>>
}
