package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FillSimulator の paper 約定価格と手数料 contract を検証するテスト。
 */
class FillSimulatorTest {

    @Test
    fun market_buy_uses_ask_plus_slippage_and_taker_fee() {
        val simulator = FillSimulator(clock = fixedClock())

        val fill = simulator.marketFill(
            side = OrderSide.BUY,
            sizeBtc = BigDecimal("0.0100"),
            ticker = ticker(),
            rules = symbolRules(),
        )

        assertEquals("10005000.00000000", fill.priceJpy.toPlainString())
        assertEquals("50.02500000", fill.feeJpy.toPlainString())
        assertEquals(ExecutionLiquidity.TAKER, fill.liquidity)
    }

    @Test
    fun market_sell_uses_bid_minus_slippage_and_taker_fee() {
        val simulator = FillSimulator(clock = fixedClock())

        val fill = simulator.marketFill(
            side = OrderSide.SELL,
            sizeBtc = BigDecimal("0.0100"),
            ticker = ticker(),
            rules = symbolRules(),
        )

        assertEquals("9985005.00000000", fill.priceJpy.toPlainString())
        assertEquals("49.92502500", fill.feeJpy.toPlainString())
        assertEquals(ExecutionLiquidity.TAKER, fill.liquidity)
    }

    @Test
    fun resting_limit_uses_limit_price_and_maker_rebate() {
        val simulator = FillSimulator(clock = fixedClock())

        val fill = simulator.restingLimitFill(
            sizeBtc = BigDecimal("0.0100"),
            limitPriceJpy = BigDecimal("9900000"),
            rules = symbolRules(),
        )

        assertEquals("9900000.00000000", fill.priceJpy.toPlainString())
        assertEquals("-9.90000000", fill.feeJpy.toPlainString())
        assertEquals(ExecutionLiquidity.MAKER, fill.liquidity)
    }

    @Test
    fun market_buy_walks_asks_and_applies_adverse_slippage() {
        val simulator = FillSimulator(clock = fixedClock())

        val fill = simulator.marketFill(
            side = OrderSide.BUY,
            sizeBtc = BigDecimal("0.0100"),
            context = paperSimulationContext(
                orderbook = orderbook(
                    asks = listOf(
                        OrderbookLevel(price = "10000000", size = "0.0050"),
                        OrderbookLevel(price = "10010000", size = "0.0050"),
                    ),
                ),
            ),
        )

        assertEquals("10010002.50000000", fill.priceJpy.toPlainString())
        assertEquals(ExecutionLiquidity.TAKER, fill.liquidity)
    }

    @Test
    fun market_sell_walks_bids_and_applies_adverse_slippage() {
        val simulator = FillSimulator(clock = fixedClock())

        val fill = simulator.marketFill(
            side = OrderSide.SELL,
            sizeBtc = BigDecimal("0.0100"),
            context = paperSimulationContext(
                orderbook = orderbook(
                    bids = listOf(
                        OrderbookLevel(price = "9990000", size = "0.0050"),
                        OrderbookLevel(price = "9980000", size = "0.0050"),
                    ),
                ),
            ),
        )

        assertEquals("9980007.50000000", fill.priceJpy.toPlainString())
        assertEquals(ExecutionLiquidity.TAKER, fill.liquidity)
    }

    @Test
    fun market_buy_fills_missing_depth_with_conservative_residual_price() {
        val simulator = FillSimulator(clock = fixedClock())

        val fill = simulator.marketFill(
            side = OrderSide.BUY,
            sizeBtc = BigDecimal("0.0150"),
            context = paperSimulationContext(
                orderbook = orderbook(
                    asks = listOf(
                        OrderbookLevel(price = "10000000", size = "0.0050"),
                        OrderbookLevel(price = "10010000", size = "0.0050"),
                    ),
                ),
            ),
        )

        assertEquals("10013339.16750000", fill.priceJpy.toPlainString())
    }

    @Test
    fun market_buy_adds_volatility_slippage_after_fixed_bps() {
        val simulator = FillSimulator(clock = fixedClock())

        val fill = simulator.marketFill(
            side = OrderSide.BUY,
            sizeBtc = BigDecimal("0.0100"),
            context = paperSimulationContext(
                volatilitySlippageJpy = BigDecimal("1000"),
            ),
        )

        assertEquals("10006000.00000000", fill.priceJpy.toPlainString())
    }
}

/**
 * FillSimulator test 用 ticker。
 */
private fun ticker(): Ticker {
    return Ticker(
        symbol = "BTC",
        last = "9990000",
        bid = "9990000",
        ask = "10000000",
        high = "10100000",
        low = "9900000",
        volume = "1.0",
        timestamp = fixedInstant().toString(),
    )
}

/**
 * FillSimulator test 用 symbol rules。
 */
private fun symbolRules(): SymbolRules {
    return SymbolRules(
        symbol = "BTC",
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "1",
        takerFee = "0.0005",
        makerFee = "-0.0001",
    )
}

private fun paperSimulationContext(
    orderbook: Orderbook? = null,
    volatilitySlippageJpy: BigDecimal = BigDecimal.ZERO,
): PaperSimulationContext {
    return PaperSimulationContext(
        ticker = ticker(),
        rules = symbolRules(),
        orderbook = orderbook,
        orderbookLookupAttempted = orderbook != null,
        volatilitySlippageJpy = volatilitySlippageJpy,
    )
}

private fun orderbook(
    bids: List<OrderbookLevel> = listOf(OrderbookLevel(price = "9990000", size = "0.0100")),
    asks: List<OrderbookLevel> = listOf(OrderbookLevel(price = "10000000", size = "0.0100")),
): Orderbook {
    return Orderbook(
        symbol = "BTC",
        bids = bids,
        asks = asks,
    )
}

/**
 * FillSimulator test 用固定時刻。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

/**
 * FillSimulator test 用固定 clock。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
