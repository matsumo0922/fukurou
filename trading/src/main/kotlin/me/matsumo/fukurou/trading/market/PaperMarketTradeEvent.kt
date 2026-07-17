package me.matsumo.fukurou.trading.market

import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * durable receipt から投影された realtime event の照合 authority。
 *
 * @param receiptId receipt row ID
 * @param admissionOrdinal receipt admission の global ordinal
 * @param payloadHash normalized payload の SHA-256 hash
 * @param socketObservedAt DB に保存された canonical socket observation 時刻
 */
data class PaperMarketEventReceiptAuthority(
    val receiptId: UUID,
    val admissionOrdinal: Long,
    val payloadHash: String,
    val socketObservedAt: Instant,
) {
    init {
        require(admissionOrdinal > 0) { "admissionOrdinal must be greater than 0." }
        require(payloadHash.matches(Regex("[0-9a-f]{64}"))) { "payloadHash must be a lowercase SHA-256 hash." }
    }
}

/**
 * paper execution の因果的な正本となる realtime trade event。
 *
 * @param symbol 取引対象 symbol
 * @param side 約定 side
 * @param priceJpy 約定価格
 * @param sizeBtc 約定数量
 * @param exchangeAt 取引所が通知した約定時刻
 * @param receivedAt complete WebSocket message を受信した時刻
 * @param connectionSessionId 接続 session ID
 * @param sequence 接続内の local sequence
 * @param receiptAuthority durable receipt commit が返した exact authority
 */
data class PaperMarketTradeEvent(
    val symbol: TradingSymbol,
    val side: OrderSide,
    val priceJpy: BigDecimal,
    val sizeBtc: BigDecimal,
    val exchangeAt: Instant,
    val receivedAt: Instant,
    val connectionSessionId: UUID,
    val sequence: Long,
    val receiptAuthority: PaperMarketEventReceiptAuthority? = null,
) {
    init {
        require(priceJpy > BigDecimal.ZERO) { "priceJpy must be greater than 0." }
        require(sizeBtc > BigDecimal.ZERO) { "sizeBtc must be greater than 0." }
        require(sequence > 0) { "sequence must be greater than 0." }
    }
}
