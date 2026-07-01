package me.matsumo.fukurou.trading.market

import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol

/**
 * 市場データ取得元。Step1 では ticker のみを公開する。
 */
interface MarketDataSource {
    /**
     * 指定 symbol の現在値を取得する。
     */
    suspend fun getTicker(symbol: TradingSymbol): Result<Ticker>
}
