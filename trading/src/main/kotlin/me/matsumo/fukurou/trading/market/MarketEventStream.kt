package me.matsumo.fukurou.trading.market

import java.time.Instant
import java.util.UUID

/**
 * realtime market event 接続を作る transport-neutral 境界。
 */
interface MarketEventStream {
    /** 接続失敗または切断後に次の接続を試行するまでの待機時間。 */
    val reconnectBackoff: java.time.Duration
        get() = java.time.Duration.ofSeconds(5)

    /** event が届かない接続を gap と判定するまでの時間。 */
    val messageStaleTimeout: java.time.Duration
        get() = java.time.Duration.ofSeconds(30)

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
     * 次の realtime trade event を待つ。切断、parse failure は failure を返す。
     */
    suspend fun receive(): Result<PaperMarketTradeEvent>
}

/** market-data message の形式または値が不正であることを示す例外。 */
class InvalidMarketDataMessageException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** market-data subscription が取引所に拒否されたことを示す例外。 */
class MarketDataSubscriptionException(message: String) : IllegalStateException(message)

/** market-data message が期限内に届かなかったことを示す例外。 */
class MarketDataMessageStaleException(message: String) : IllegalStateException(message)

/** market-data consumer が受信速度に追従できないことを示す例外。 */
class MarketDataBackpressureException(message: String) : IllegalStateException(message)
