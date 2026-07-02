package me.matsumo.fukurou.trading.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GMO Public API の klines interval。
 *
 * @param apiValue GMO API に渡す interval 文字列
 * @param dateRange klines の date parameter 粒度
 */
@Serializable
enum class CandleInterval(
    val apiValue: String,
    val dateRange: CandleDateRange,
) {
    /**
     * 1 分足。
     */
    @SerialName("1min")
    ONE_MINUTE("1min", CandleDateRange.DAY),

    /**
     * 5 分足。
     */
    @SerialName("5min")
    FIVE_MINUTES("5min", CandleDateRange.DAY),

    /**
     * 10 分足。
     */
    @SerialName("10min")
    TEN_MINUTES("10min", CandleDateRange.DAY),

    /**
     * 15 分足。
     */
    @SerialName("15min")
    FIFTEEN_MINUTES("15min", CandleDateRange.DAY),

    /**
     * 30 分足。
     */
    @SerialName("30min")
    THIRTY_MINUTES("30min", CandleDateRange.DAY),

    /**
     * 1 時間足。
     */
    @SerialName("1hour")
    ONE_HOUR("1hour", CandleDateRange.DAY),

    /**
     * 4 時間足。
     */
    @SerialName("4hour")
    FOUR_HOURS("4hour", CandleDateRange.YEAR),

    /**
     * 8 時間足。
     */
    @SerialName("8hour")
    EIGHT_HOURS("8hour", CandleDateRange.YEAR),

    /**
     * 12 時間足。
     */
    @SerialName("12hour")
    TWELVE_HOURS("12hour", CandleDateRange.YEAR),

    /**
     * 日足。
     */
    @SerialName("1day")
    ONE_DAY("1day", CandleDateRange.YEAR),

    /**
     * 週足。
     */
    @SerialName("1week")
    ONE_WEEK("1week", CandleDateRange.YEAR),

    /**
     * 月足。
     */
    @SerialName("1month")
    ONE_MONTH("1month", CandleDateRange.YEAR),
    ;

    /**
     * CandleInterval の解決 helper。
     */
    companion object {
        /**
         * API 文字列から interval を解決する。
         */
        fun fromApiValue(apiValue: String): CandleInterval? {
            return entries.firstOrNull { interval -> interval.apiValue == apiValue }
        }
    }
}

/**
 * GMO klines date parameter の粒度。
 */
@Serializable
enum class CandleDateRange {
    /**
     * `YYYYMMDD` で取得する短期足。
     */
    DAY,

    /**
     * `YYYY` で取得する長期足。
     */
    YEAR,
}

/**
 * 取引所から取得したローソク足。
 *
 * @param symbol 取引対象 symbol
 * @param interval ローソク足 interval
 * @param openTime 始値時刻
 * @param open 始値
 * @param high 高値
 * @param low 安値
 * @param close 終値
 * @param volume 出来高
 */
@Serializable
data class Candle(
    val symbol: String,
    val interval: CandleInterval,
    val openTime: String,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String,
)
