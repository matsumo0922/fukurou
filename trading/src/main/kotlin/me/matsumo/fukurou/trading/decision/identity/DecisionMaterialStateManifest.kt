package me.matsumo.fukurou.trading.decision.identity

import java.math.BigDecimal
import java.time.Instant

/** material-state manifest の起動元。 */
enum class DecisionTriggerKind { DAEMON, MANUAL }

/** market source の鮮度。 */
enum class MaterialFreshness { FRESH, STALE, UNKNOWN }

/** 取得できなかった bounded source と理由。 */
data class MaterialMissingSource(val source: String, val reason: String)

/** Issue #188 の invocation manifest が参照する bounded market facts。 */
data class DecisionMaterialStateManifest(
    val invocationId: String,
    val capturedAt: Instant,
    val triggerKind: DecisionTriggerKind,
    val symbol: String,
    val runtimeConfigVersion: String?,
    val runtimeConfigHash: String?,
    val riskState: String,
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
)
