package me.matsumo.fukurou.trading.shadow

import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * gate-shadow の観測結果。
 */
enum class GateShadowOutcome {
    /** 注文境界を跨ぐ価格 event を確認した。 */
    CROSSED,

    /** 注文境界を跨いだか確認できない。 */
    UNKNOWN,
}

/**
 * gate-shadow の観測品質。
 */
enum class ShadowDataQuality {
    /** 必要な capture 情報が揃っている。 */
    OK,

    /** order に market-data session が紐付いていない。 */
    MISSING_MARKET_DATA_SESSION_ID,

    /** order に canonical geometry hash が紐付いていない。 */
    MISSING_GEOMETRY_HASH,

    /** receipt payload を decode できない event があった。 */
    PAYLOAD_DECODE_FAILED,

    /** session 内の socket observed time が非単調だった。 */
    NON_MONOTONIC_SOCKET_TIME,
}

/** 2 つの観測品質から劣化順序が悪い方を返す。 */
internal fun worstShadowDataQuality(first: ShadowDataQuality, second: ShadowDataQuality): ShadowDataQuality {
    return if (first.degradationRank >= second.degradationRank) first else second
}

private val ShadowDataQuality.degradationRank: Int
    get() = when (this) {
        ShadowDataQuality.OK -> 0
        ShadowDataQuality.NON_MONOTONIC_SOCKET_TIME -> 1
        ShadowDataQuality.MISSING_MARKET_DATA_SESSION_ID -> 2
        ShadowDataQuality.MISSING_GEOMETRY_HASH -> 3
        ShadowDataQuality.PAYLOAD_DECODE_FAILED -> 4
    }

/**
 * TTL 失効した resting entry の geometry と因果境界。
 *
 * @param id observation ID
 * @param orderId TTL 失効した order ID
 * @param decisionId order の元になった decision ID
 * @param opportunityEpisodeId order の opportunity episode ID
 * @param geometryHash canonical order geometry hash
 * @param symbol 取引対象 symbol
 * @param side 注文 side
 * @param orderType 注文種別
 * @param sizeBtc 注文数量
 * @param limitPriceJpy LIMIT 境界価格
 * @param triggerPriceJpy STOP trigger 境界価格
 * @param stopPriceJpy entry intent の保護 STOP 価格
 * @param takeProfitPriceJpy entry intent の take-profit 価格
 * @param queueAheadBtc 失効時点の先行 queue 数量
 * @param marketDataSessionId order を作成した market-data session ID
 * @param startAdmissionOrdinal 失効 transaction 内で読んだ allocation fence
 * @param windowStartTime TTL の論理的な失効時刻
 * @param dataQuality capture 品質
 * @param observedAt observation の作成時刻
 */
data class GateShadowObservation(
    val id: UUID,
    val orderId: UUID,
    val decisionId: UUID?,
    val opportunityEpisodeId: UUID?,
    val geometryHash: String?,
    val symbol: String,
    val side: OrderSide,
    val orderType: OrderType,
    val sizeBtc: BigDecimal,
    val limitPriceJpy: BigDecimal?,
    val triggerPriceJpy: BigDecimal?,
    val stopPriceJpy: BigDecimal?,
    val takeProfitPriceJpy: BigDecimal?,
    val queueAheadBtc: BigDecimal?,
    val marketDataSessionId: UUID?,
    val startAdmissionOrdinal: Long,
    val windowStartTime: Instant,
    val dataQuality: ShadowDataQuality,
    val observedAt: Instant,
)

/**
 * settle 後の receipt 走査位置。
 *
 * @param observationId observation ID
 * @param lastScannedAdmissionOrdinal 最後に走査した admission ordinal
 * @param dataQuality 走査済み page で累積した最悪の観測品質
 * @param lastSocketObservedAt 最後に走査した receipt の socket observed time
 * @param lastScannedAt 最後に cursor を更新した時刻
 */
data class GateShadowScanProgress(
    val observationId: UUID,
    val lastScannedAdmissionOrdinal: Long,
    val dataQuality: ShadowDataQuality,
    val lastSocketObservedAt: Instant?,
    val lastScannedAt: Instant,
)

/**
 * gate-shadow observation の単調昇格 resolution。
 *
 * @param observationId observation ID
 * @param outcome CROSSED または UNKNOWN
 * @param crossingEventSequence 境界を跨いだ receipt の source sequence
 * @param crossingExchangeAt 境界を跨いだ event の取引所時刻
 * @param crossingPriceJpy 境界を跨いだ event の価格
 * @param distanceJpy 注文境界との表示用距離
 * @param dataQuality resolution 品質
 * @param resolvedAt resolution の作成時刻
 */
data class GateShadowResolution(
    val observationId: UUID,
    val outcome: GateShadowOutcome,
    val crossingEventSequence: Long?,
    val crossingExchangeAt: Instant?,
    val crossingPriceJpy: BigDecimal?,
    val distanceJpy: BigDecimal?,
    val dataQuality: ShadowDataQuality,
    val resolvedAt: Instant,
)
