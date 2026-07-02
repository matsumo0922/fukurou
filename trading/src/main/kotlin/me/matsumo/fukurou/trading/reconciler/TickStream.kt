package me.matsumo.fukurou.trading.reconciler

import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.MarketDataSource
import java.time.Clock
import java.time.Instant

/**
 * Reconciler が参照する直近 tick snapshot。
 *
 * @param symbol 取引対象 symbol
 * @param observedAt tick 観測時刻
 * @param lastPrice ticker が返した直近価格
 * @param recentTradeCount 同じ polling pass で取得した直近約定数
 */
data class TickSnapshot(
    val symbol: String,
    val observedAt: Instant,
    val lastPrice: String?,
    val recentTradeCount: Int = 0,
)

/**
 * ProtectionReconciler が市場データを受け取る抽象。
 */
interface TickStream {
    /**
     * 直近 tick を返す。
     */
    suspend fun latestTick(): Result<TickSnapshot?>
}

/**
 * REST polling で ticker と recent trades を確認する TickStream。
 *
 * @param marketDataSource 市場データ取得元
 * @param symbol polling 対象 symbol
 * @param clock observedAt に使う clock
 */
class RestPollingTickStream(
    private val marketDataSource: MarketDataSource,
    private val symbol: TradingSymbol = TradingSymbol.BTC,
    private val clock: Clock = Clock.systemUTC(),
) : TickStream {

    override suspend fun latestTick(): Result<TickSnapshot?> {
        return runCatching {
            val ticker = marketDataSource.getTicker(symbol).getOrThrow()
            val recentTrades = marketDataSource.getRecentTrades(symbol).getOrThrow()

            TickSnapshot(
                symbol = symbol.apiSymbol,
                observedAt = clock.instant(),
                lastPrice = ticker.last,
                recentTradeCount = recentTrades.size,
            )
        }
    }
}

/**
 * test と明示 local injection 用の空 TickStream。
 */
object EmptyTickStream : TickStream {
    override suspend fun latestTick(): Result<TickSnapshot?> {
        return Result.success(null)
    }
}
