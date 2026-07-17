package me.matsumo.fukurou.trading.reconciler

import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.IndicatorCalculator
import me.matsumo.fukurou.trading.market.IndicatorParams
import me.matsumo.fukurou.trading.market.IndicatorType
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.market.isFailClosedGmoRequestFailure
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant

/**
 * TickSnapshot を生成した market input の種類。
 */
enum class TickSnapshotSource {
    /** GMO Public REST ticker polling。 */
    GMO_PUBLIC_REST,

    /** GMO Public WebSocket の causal trade event。 */
    REALTIME_MARKET_EVENT,

    /** repository 内部処理や明示的な local injection。 */
    INTERNAL,
}

/**
 * Reconciler が参照する直近 tick snapshot。
 *
 * @param symbol 取引対象 symbol
 * @param observedAt app が tick を観測した時刻
 * @param lastPrice ticker が返した直近価格
 * @param bidPrice 最良買気配
 * @param askPrice 最良売気配
 * @param recentTradeCount 同じ polling pass で取得した直近約定数
 * @param symbolRules 取引所数量・価格・手数料ルール
 * @param atr14Jpy 5分足 ATR(14)
 * @param sourceTimestamp 取引所が宣言した source 時刻。parse 不能または未提供の場合は null
 * @param source snapshot を生成した market input の種類
 */
data class TickSnapshot(
    val symbol: String,
    val observedAt: Instant,
    val lastPrice: String?,
    val bidPrice: String? = null,
    val askPrice: String? = null,
    val recentTradeCount: Int = 0,
    val symbolRules: SymbolRules? = null,
    val atr14Jpy: String? = null,
    val sourceTimestamp: Instant? = null,
    val source: TickSnapshotSource = TickSnapshotSource.INTERNAL,
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
 * @param latestMarketQuoteStore API と共有する最新気配値 store
 * @param symbol polling 対象 symbol
 * @param clock observedAt に使う clock
 */
class RestPollingTickStream(
    private val marketDataSource: MarketDataSource,
    private val latestMarketQuoteStore: LatestMarketQuoteStore = LatestMarketQuoteStore(),
    private val symbol: TradingSymbol = TradingSymbol.BTC,
    private val clock: Clock = Clock.systemUTC(),
) : TickStream {

    override suspend fun latestTick(): Result<TickSnapshot?> {
        return runCatching {
            val ticker = marketDataSource.getTicker(symbol).getOrThrow()
            val bidPrice = ticker.bid.toBigDecimalOrNull()
            val askPrice = ticker.ask.toBigDecimalOrNull()
            val tickerObservedAt = runCatching { Instant.parse(ticker.timestamp) }.getOrNull()
            val atr14Jpy = fetchAtr14Jpy()
            val quoteIsValid = bidPrice != null && askPrice != null && tickerObservedAt != null

            if (quoteIsValid) {
                latestMarketQuoteStore.update(
                    LatestMarketQuote(
                        bidPriceJpy = checkNotNull(bidPrice),
                        askPriceJpy = checkNotNull(askPrice),
                        observedAt = checkNotNull(tickerObservedAt),
                        lastPriceJpy = BigDecimal(ticker.last),
                        atr14Jpy = atr14Jpy?.toBigDecimalOrNull(),
                    ),
                )
            }

            val recentTrades = marketDataSource.getTrades(symbol, RECENT_TRADES_LIMIT).getOrThrow()
            val symbolRules = marketDataSource.getSymbolRules(symbol).getOrThrow()

            TickSnapshot(
                symbol = symbol.apiSymbol,
                observedAt = clock.instant(),
                lastPrice = ticker.last,
                bidPrice = ticker.bid,
                askPrice = ticker.ask,
                recentTradeCount = recentTrades.size,
                symbolRules = symbolRules,
                atr14Jpy = atr14Jpy,
                sourceTimestamp = tickerObservedAt,
                source = TickSnapshotSource.GMO_PUBLIC_REST,
            )
        }
    }

    private suspend fun fetchAtr14Jpy(): String? {
        val candles = marketDataSource.getCandles(
            symbol = symbol,
            interval = CandleInterval.FIVE_MINUTES,
            limit = ATR_CANDLE_LIMIT,
        ).onFailure { throwable ->
            if (throwable.isFailClosedGmoRequestFailure()) throw throwable
        }
            .getOrNull()
            ?: return null
        val atr = IndicatorCalculator.calculate(
            candles = candles,
            indicator = IndicatorType.ATR,
            params = IndicatorParams(period = ATR_PERIOD),
        ).getOrNull() ?: return null
        val latestAtr = atr.values
            .lastOrNull { value -> value.value != null }
            ?.value
            ?: return null

        return BigDecimal.valueOf(latestAtr)
            .setScale(ATR_SCALE, RoundingMode.HALF_UP)
            .toPlainString()
    }
}

/**
 * reconciler freshness 確認で取得する直近約定数。
 */
private const val RECENT_TRADES_LIMIT = 100

/**
 * ATR 算出に取得する 5分足本数。
 */
private const val ATR_CANDLE_LIMIT = 64

/**
 * ATR の期間。
 */
private const val ATR_PERIOD = 14

/**
 * ATR の返却 scale。
 */
private const val ATR_SCALE = 8

/**
 * test と明示 local injection 用の空 TickStream。
 */
object EmptyTickStream : TickStream {
    override suspend fun latestTick(): Result<TickSnapshot?> {
        return Result.success(null)
    }
}
