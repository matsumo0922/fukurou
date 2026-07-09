package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import java.math.BigDecimal

/**
 * MARKET 約定を計算する。
 */
internal fun PaperExecutionSimulator.marketFill(
    side: OrderSide,
    sizeBtc: BigDecimal,
    ticker: Ticker,
    rules: SymbolRules,
): SimulatedFill {
    return marketFill(
        side = side,
        sizeBtc = sizeBtc,
        context = PaperSimulationContext(
            ticker = ticker,
            rules = rules,
        ),
    )
}

/**
 * MARKET 約定を計算する。
 */
internal fun PaperExecutionSimulator.marketFill(
    side: OrderSide,
    sizeBtc: BigDecimal,
    context: PaperSimulationContext,
): SimulatedFill {
    return simulateImmediate(
        request = ImmediateExecutionRequest(
            side = side,
            orderType = OrderType.MARKET,
            sizeBtc = sizeBtc,
        ),
        context = context,
    )
}

/**
 * STOP 約定を計算する。
 */
internal fun PaperExecutionSimulator.stopFill(
    side: OrderSide,
    sizeBtc: BigDecimal,
    triggerPriceJpy: BigDecimal,
    ticker: Ticker,
    rules: SymbolRules,
): SimulatedFill {
    return stopFill(
        side = side,
        sizeBtc = sizeBtc,
        triggerPriceJpy = triggerPriceJpy,
        context = PaperSimulationContext(
            ticker = ticker,
            rules = rules,
        ),
    )
}

/**
 * STOP 約定を計算する。
 */
internal fun PaperExecutionSimulator.stopFill(
    side: OrderSide,
    sizeBtc: BigDecimal,
    triggerPriceJpy: BigDecimal,
    context: PaperSimulationContext,
): SimulatedFill {
    return simulateImmediate(
        request = ImmediateExecutionRequest(
            side = side,
            orderType = OrderType.STOP,
            sizeBtc = sizeBtc,
            triggerPriceJpy = triggerPriceJpy,
        ),
        context = context,
    )
}

/**
 * resting entry order の約定を注文種別に応じて計算する。
 */
internal fun PaperExecutionSimulator.restingEntryFill(request: RestingEntryFillRequest): SimulatedFill {
    return restingEntryFill(
        request = request,
        context = PaperSimulationContext(
            ticker = request.ticker,
            rules = request.rules,
        ),
    )
}

/**
 * resting entry order の約定を注文種別と市場 context に応じて計算する。
 */
internal fun PaperExecutionSimulator.restingEntryFill(
    request: RestingEntryFillRequest,
    context: PaperSimulationContext,
): SimulatedFill {
    return requireNotNull(restingEntryUpdate(request, context).fill) {
        "Triggered resting entry order must create a fill."
    }
}

/**
 * resting entry order の更新を注文種別と市場 context に応じて計算する。
 */
internal fun PaperExecutionSimulator.restingEntryUpdate(
    request: RestingEntryFillRequest,
    context: PaperSimulationContext,
): PaperOrderUpdate {
    return when (request.orderType) {
        OrderType.LIMIT -> simulatePendingLimit(
            request = PendingLimitExecutionRequest(
                side = request.side,
                sizeBtc = request.sizeBtc,
                limitPriceJpy = requireNotNull(request.limitPriceJpy) {
                    "LIMIT entry order requires limitPriceJpy."
                },
            ),
            context = context,
        )
        OrderType.STOP -> PaperOrderUpdate(
            fill = stopFill(
                side = request.side,
                sizeBtc = request.sizeBtc,
                triggerPriceJpy = requireNotNull(request.triggerPriceJpy) {
                    "STOP entry order requires triggerPriceJpy."
                },
                context = context,
            ),
            remainingSizeBtc = BigDecimal.ZERO.btcScale(),
            expired = false,
        )
        OrderType.MARKET -> error("MARKET entry is not a resting order.")
    }
}

/**
 * crossing LIMIT の即時 taker 約定を計算する。
 */
internal fun PaperExecutionSimulator.limitTakerFill(
    side: OrderSide,
    sizeBtc: BigDecimal,
    limitPriceJpy: BigDecimal,
    context: PaperSimulationContext,
): SimulatedFill {
    return simulateImmediate(
        request = ImmediateExecutionRequest(
            side = side,
            orderType = OrderType.LIMIT,
            sizeBtc = sizeBtc,
            limitPriceJpy = limitPriceJpy,
        ),
        context = context,
    )
}

/**
 * resting entry order の約定計算入力。
 *
 * @param side 注文 side
 * @param orderType 注文種別
 * @param sizeBtc 注文数量
 * @param limitPriceJpy LIMIT 価格
 * @param triggerPriceJpy STOP trigger 価格
 * @param ticker 現在 ticker
 * @param rules symbol rule
 */
data class RestingEntryFillRequest(
    val side: OrderSide,
    val orderType: OrderType,
    val sizeBtc: BigDecimal,
    val limitPriceJpy: BigDecimal?,
    val triggerPriceJpy: BigDecimal?,
    val ticker: Ticker,
    val rules: SymbolRules,
)
