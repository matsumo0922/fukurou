package me.matsumo.fukurou.trading.market

import java.time.Instant
import java.util.UUID

/**
 * realtime market event 接続を作る transport-neutral 境界。
 */
interface MarketEventStream {
    /**
     * 新しい接続 session を開く。
     */
    suspend fun connect(): Result<MarketEventSession>
}

/**
 * 1 WebSocket 接続に対応する順序付き market event session。
 */
interface MarketEventSession : AutoCloseable {
    /** 接続 session ID。 */
    val sessionId: UUID

    /** 接続開始時刻。 */
    val connectedAt: Instant

    /**
     * 次の realtime trade event を待つ。切断、parse failure、timeout は failure を返す。
     */
    suspend fun receive(): Result<PaperMarketTradeEvent>
}
