package me.matsumo.fukurou.trading.replay

import me.matsumo.fukurou.trading.broker.DefaultPaperExecutionSimulator
import me.matsumo.fukurou.trading.broker.PaperSimulationContext
import me.matsumo.fukurou.trading.broker.PendingLimitExecutionRequest
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import java.math.BigDecimal

/** 記録済み execution と simulator の再計算値を突き合わせた cross-check 結果。 */
data class LimitFillCrossCheckResult(
    val priceMatches: Boolean,
    val feeMatches: Boolean,
    val expectedPriceJpy: BigDecimal,
    val expectedFeeJpy: BigDecimal,
) {
    /** 価格・手数料の両方が一致したか。 */
    val matches: Boolean get() = priceMatches && feeMatches
}

/**
 * resting LIMIT entry の約定価格・手数料が `simulatePendingLimit` の再計算と整合するか検証する cross-check。
 *
 * fill 生成には使わず、記録済み execution が queue / maker fee の理解と整合するかを確かめる目的でのみ用いる。
 */
object LimitFillCrossCheck {

    private const val CROSS_CHECK_SYMBOL = "BTC_JPY"

    /**
     * 記録済み limit 価格・size・maker fee から fill を再計算し、記録済み execution の price / fee と比較する。
     */
    fun verify(
        limitPriceJpy: BigDecimal,
        sizeBtc: BigDecimal,
        makerFeeRate: BigDecimal,
        recordedPriceJpy: BigDecimal,
        recordedFeeJpy: BigDecimal,
    ): LimitFillCrossCheckResult {
        val simulator = DefaultPaperExecutionSimulator()
        val update = simulator.simulatePendingLimit(
            request = PendingLimitExecutionRequest(
                side = OrderSide.BUY,
                sizeBtc = sizeBtc,
                limitPriceJpy = limitPriceJpy,
            ),
            context = paperSimulationContext(limitPriceJpy, makerFeeRate),
        )
        val fill = requireNotNull(update.fill) { "simulatePendingLimit produced no fill for cross-check." }

        return LimitFillCrossCheckResult(
            priceMatches = fill.priceJpy.compareTo(recordedPriceJpy) == 0,
            feeMatches = fill.feeJpy.compareTo(recordedFeeJpy) == 0,
            expectedPriceJpy = fill.priceJpy,
            expectedFeeJpy = fill.feeJpy,
        )
    }

    private fun paperSimulationContext(limitPriceJpy: BigDecimal, makerFeeRate: BigDecimal): PaperSimulationContext {
        val priceText = limitPriceJpy.toPlainString()

        return PaperSimulationContext(
            ticker = Ticker(
                symbol = CROSS_CHECK_SYMBOL,
                last = priceText,
                bid = priceText,
                ask = priceText,
                high = priceText,
                low = priceText,
                volume = "0",
                timestamp = "0",
            ),
            rules = SymbolRules(
                symbol = CROSS_CHECK_SYMBOL,
                minOrderSize = "0.0001",
                sizeStep = "0.0001",
                tickSize = "1",
                takerFee = makerFeeRate.toPlainString(),
                makerFee = makerFeeRate.toPlainString(),
            ),
            orderbook = null,
            orderbookLookupAttempted = false,
        )
    }
}
