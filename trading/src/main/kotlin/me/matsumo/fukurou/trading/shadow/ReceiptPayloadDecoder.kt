package me.matsumo.fukurou.trading.shadow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.domain.OrderSide
import java.math.BigDecimal
import java.time.Instant

/**
 * paper market-event receipt の正規化済み payload。
 *
 * @param exchangeAt 取引所が通知した event 時刻
 * @param priceJpy event 価格
 * @param side event side
 * @param sizeBtc event 数量
 * @param symbol 取引対象 symbol
 */
data class ReceiptPayload(
    val exchangeAt: Instant,
    val priceJpy: BigDecimal,
    val side: OrderSide,
    val sizeBtc: BigDecimal,
    val symbol: String,
)

/** `normalized_payload` を typed payload に decode する。 */
object ReceiptPayloadDecoder {

    /** decode 不能な payload は `null` を返す。 */
    fun decode(normalizedPayload: String): ReceiptPayload? {
        return try {
            val payload = Json.parseToJsonElement(normalizedPayload).jsonObject

            ReceiptPayload(
                exchangeAt = Instant.parse(payload.getValue("exchangeAt").jsonPrimitive.content),
                priceJpy = payload.getValue("priceJpy").jsonPrimitive.content.toBigDecimal(),
                side = OrderSide.valueOf(payload.getValue("side").jsonPrimitive.content),
                sizeBtc = payload.getValue("sizeBtc").jsonPrimitive.content.toBigDecimal(),
                symbol = payload.getValue("symbol").jsonPrimitive.content,
            )
        } catch (_: Exception) {
            null
        }
    }
}
