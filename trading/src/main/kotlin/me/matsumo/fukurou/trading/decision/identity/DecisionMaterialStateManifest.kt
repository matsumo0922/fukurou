package me.matsumo.fukurou.trading.decision.identity

import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import java.math.BigDecimal
import java.time.Instant

/** material-state manifest の起動元。 */
enum class DecisionTriggerKind { DAEMON, MANUAL }

/** market source の鮮度。 */
enum class MaterialFreshness { FRESH, STALE, UNKNOWN }

/** 取得できなかった bounded source と理由。 */
data class MaterialMissingSource(val source: String, val reason: String)

/** bounded source の取得由来と truncation。 */
data class MaterialSourceMetadata(
    val observedAt: Instant?,
    val provenance: String,
    val truncated: Boolean,
    val totalCount: Int?,
)

/** schema v2 の typed ticker snapshot。 */
data class MaterialTickerSnapshot(
    val bestBidJpy: BigDecimal?,
    val bestAskJpy: BigDecimal?,
    val lastPriceJpy: BigDecimal?,
    val metadata: MaterialSourceMetadata,
)

/** schema v2 の bounded candle summary。 */
data class MaterialCandleSummary(
    val openTime: Instant,
    val openJpy: BigDecimal,
    val highJpy: BigDecimal,
    val lowJpy: BigDecimal,
    val closeJpy: BigDecimal,
    val volumeBtc: BigDecimal?,
)

/** schema v2 の indicator value。 */
data class MaterialIndicatorSnapshot(val name: String, val value: BigDecimal?, val sampleCount: Int)

/** raw level を含まない bounded orderbook summary。 */
data class MaterialOrderbookSummary(
    val bestBidJpy: BigDecimal?,
    val bestAskJpy: BigDecimal?,
    val midJpy: BigDecimal?,
    val spreadBps: BigDecimal?,
    val topBidQuantityBtc: BigDecimal,
    val topAskQuantityBtc: BigDecimal,
    val topBidNotionalJpy: BigDecimal,
    val topAskNotionalJpy: BigDecimal,
    val imbalance: BigDecimal?,
    val levelLimit: Int = 10,
    val metadata: MaterialSourceMetadata,
)

/** account/ledger の bounded typed row。 */
data class MaterialLedgerFact(
    val id: String,
    val status: String,
    val side: String?,
    val type: String?,
)

/** coherent account snapshot。 */
data class MaterialAccountSnapshot(
    val riskState: String,
    val availableJpy: BigDecimal?,
    val equityJpy: BigDecimal?,
    val positions: List<MaterialLedgerFact>,
    val openOrders: List<MaterialLedgerFact>,
    val positionMetadata: MaterialSourceMetadata,
    val orderMetadata: MaterialSourceMetadata,
)

/** material schema v2 の typed market/account bundle。 */
data class MarketFeatureBundle(
    val ticker: MaterialTickerSnapshot?,
    val candleSummaries: List<MaterialCandleSummary>,
    val indicators: List<MaterialIndicatorSnapshot>,
    val orderbookSummary: MaterialOrderbookSummary?,
    val account: MaterialAccountSnapshot,
    val missingSources: List<MaterialMissingSource>,
)

/** Issue #188 の invocation manifest が参照する bounded market facts。 */
data class DecisionMaterialStateManifest(
    val invocationId: String,
    val capturedAt: Instant,
    val triggerKind: DecisionTriggerKind,
    val symbol: String,
    val runtimeConfigVersion: String?,
    val runtimeConfigHash: String?,
    val riskState: String,
    val priceMoveThresholdRatio: BigDecimal = BigDecimal("0.01"),
    val bestBidJpy: BigDecimal?,
    val bestAskJpy: BigDecimal?,
    val lastPriceJpy: BigDecimal?,
    val sourceTimestamp: Instant?,
    val freshness: MaterialFreshness,
    val atr14FiveMinutesJpy: BigDecimal?,
    val latestCandleOpenJpy: BigDecimal?,
    val latestCandleHighJpy: BigDecimal?,
    val latestCandleLowJpy: BigDecimal?,
    val latestCandleCloseJpy: BigDecimal?,
    val openPositionFacts: List<String>,
    val openOrderFacts: List<String>,
    val missingSources: List<MaterialMissingSource>,
    val schemaVersion: Int = 1,
    val canonicalContentHash: String,
    /** #185 identity hashとは分離した、manifest全体のimmutable snapshot hash。 */
    val snapshotContentHash: String = canonicalContentHash,
    val materialProjection: String = "",
    val marketFeatureBundle: MarketFeatureBundle? = null,
)

/** runner が既存 episode の fixed identity context を読むための値。 */
data class DecisionMaterialProjectionContext(
    val anchorPriceJpy: BigDecimal?,
    val priceMoveThresholdRatio: BigDecimal,
    val invalidationPredicates: List<TradePlanInvalidationPredicate>,
)
