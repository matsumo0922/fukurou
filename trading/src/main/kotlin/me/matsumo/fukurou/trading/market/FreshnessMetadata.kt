package me.matsumo.fukurou.trading.market

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import me.matsumo.fukurou.trading.domain.CandleInterval
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * MCP response に付与するデータ鮮度 metadata。
 *
 * @param fetchedAt response を組み立てた app 側時刻
 * @param sourceTimestamp 取引所や台帳が宣言した source 側時刻
 * @param stalenessMs 鮮度判定に使う基準時刻から response 組み立て時刻までの経過 ms
 * @param staleAfterMs stale とみなす経過 ms
 * @param stale staleAfterMs を超過しているかどうか
 * @param source 鮮度 source の種類
 */
@Serializable
data class FreshnessMetadata(
    val fetchedAt: String,
    val sourceTimestamp: String?,
    val stalenessMs: Long,
    val staleAfterMs: Long,
    val stale: Boolean,
    val source: FreshnessSource,
) {
    /**
     * FreshnessMetadata の factory。
     */
    companion object {
        /**
         * injected clock と source timestamp から metadata を構築する。
         */
        fun build(
            clock: Clock,
            sourceTimestamp: Instant?,
            staleAfter: Duration,
            source: FreshnessSource,
        ): FreshnessMetadata {
            val fetchedAt = Instant.now(clock)
            val freshnessBaseAt = sourceTimestamp ?: fetchedAt
            val stalenessMs = Duration.between(freshnessBaseAt, fetchedAt)
                .toMillis()
                .coerceAtLeast(0)
            val staleAfterMs = staleAfter.toMillis()

            return FreshnessMetadata(
                fetchedAt = fetchedAt.toString(),
                sourceTimestamp = sourceTimestamp?.toString(),
                stalenessMs = stalenessMs,
                staleAfterMs = staleAfterMs,
                stale = stalenessMs > staleAfterMs,
                source = source,
            )
        }
    }
}

/**
 * JSON object に freshness metadata を additive に付与する。
 */
fun JsonObject.withFreshness(freshness: FreshnessMetadata): JsonObject {
    return buildJsonObject {
        this@withFreshness.forEach { fieldName, value ->
            put(fieldName, value)
        }
        put("freshness", Json.encodeToJsonElement(freshness))
    }
}

/**
 * MCP response の鮮度 source。
 */
@Serializable
enum class FreshnessSource {
    /**
     * GMO Coin Public REST API 由来の market data。
     */
    GMO_PUBLIC_REST,

    /**
     * paper ledger の口座・建玉・注文 data。
     */
    PAPER_LEDGER,
}

/**
 * MCP tool 種別ごとの鮮度しきい値。
 */
object FreshnessDefaults {
    /**
     * ticker の既定鮮度しきい値。
     */
    val tickerStaleAfter: Duration = Duration.ofSeconds(5)

    /**
     * orderbook の既定鮮度しきい値。
     */
    val orderbookStaleAfter: Duration = Duration.ofSeconds(3)

    /**
     * trades の既定鮮度しきい値。
     */
    val tradesStaleAfter: Duration = Duration.ofSeconds(10)

    /**
     * account / position / balance / account_status の既定鮮度しきい値。
     */
    val paperLedgerStaleAfter: Duration = Duration.ofSeconds(10)

    /**
     * candle interval ごとの完了時刻 + grace を stale しきい値として返す。
     */
    fun candleStaleAfter(interval: CandleInterval): Duration {
        return candleIntervalDuration(interval).plus(candleGraceDuration(interval))
    }

    private fun candleGraceDuration(interval: CandleInterval): Duration {
        return when (interval) {
            CandleInterval.ONE_MINUTE,
            CandleInterval.FIVE_MINUTES,
            CandleInterval.TEN_MINUTES,
            CandleInterval.FIFTEEN_MINUTES,
            CandleInterval.THIRTY_MINUTES,
            -> Duration.ofSeconds(90)
            CandleInterval.ONE_HOUR,
            CandleInterval.FOUR_HOURS,
            CandleInterval.EIGHT_HOURS,
            CandleInterval.TWELVE_HOURS,
            CandleInterval.ONE_DAY,
            CandleInterval.ONE_WEEK,
            CandleInterval.ONE_MONTH,
            -> Duration.ofMinutes(5)
        }
    }

    private fun candleIntervalDuration(interval: CandleInterval): Duration {
        return when (interval) {
            CandleInterval.ONE_MINUTE -> Duration.ofMinutes(1)
            CandleInterval.FIVE_MINUTES -> Duration.ofMinutes(5)
            CandleInterval.TEN_MINUTES -> Duration.ofMinutes(10)
            CandleInterval.FIFTEEN_MINUTES -> Duration.ofMinutes(15)
            CandleInterval.THIRTY_MINUTES -> Duration.ofMinutes(30)
            CandleInterval.ONE_HOUR -> Duration.ofHours(1)
            CandleInterval.FOUR_HOURS -> Duration.ofHours(4)
            CandleInterval.EIGHT_HOURS -> Duration.ofHours(8)
            CandleInterval.TWELVE_HOURS -> Duration.ofHours(12)
            CandleInterval.ONE_DAY -> Duration.ofDays(1)
            CandleInterval.ONE_WEEK -> Duration.ofDays(7)
            CandleInterval.ONE_MONTH -> Duration.ofDays(31)
        }
    }
}
