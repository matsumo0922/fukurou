package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.OrderSide
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
