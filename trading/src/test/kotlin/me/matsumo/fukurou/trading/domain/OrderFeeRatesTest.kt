package me.matsumo.fukurou.trading.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * order type aware fee helper の境界を検証するテスト。
 */
class OrderFeeRatesTest {

    @Test
    fun cashFeeReserveFor_clampsMakerRebateToZero() {
        val notional = BigDecimal("1000")

        val reserve = cashFeeReserveFor(
            notional = notional,
            orderType = OrderType.LIMIT,
            symbolRules = symbolRules(),
        )
        val requiredCash = requiredCashFor(
            notional = notional,
            orderType = OrderType.LIMIT,
            symbolRules = symbolRules(),
        )

        assertEquals(BigDecimal.ZERO, reserve)
        assertEquals(notional, requiredCash)
    }

    @Test
    fun roundTripCostReserveFor_clampsNegativeFeeReserveToZero() {
        val reserve = roundTripCostReserveFor(
            entryNotional = BigDecimal("10000"),
            exitNotional = BigDecimal("1000"),
            entryOrderType = OrderType.LIMIT,
            symbolRules = symbolRules(
                makerFee = "-0.0010",
                takerFee = "0.0005",
            ),
            slippageRatio = BigDecimal.ZERO,
        )

        assertEquals(BigDecimal.ZERO, reserve)
    }

    @Test
    fun unsafeOrderFeeRateReasonOrNull_acceptsConservativeMakerRebateAndPositiveTakerFee() {
        val unsafeReason = unsafeOrderFeeRateReasonOrNull(symbolRules())

        assertNull(unsafeReason)
    }

    @Test
    fun unsafeOrderFeeRateReasonOrNull_rejectsNegativeTakerFee() {
        val unsafeReason = unsafeOrderFeeRateReasonOrNull(
            symbolRules(
                takerFee = "-0.0010",
                makerFee = "-0.0001",
            ),
        )

        assertNotNull(unsafeReason)
    }
}

private fun symbolRules(
    takerFee: String = "0.0005",
    makerFee: String = "-0.0001",
): SymbolRules {
    return SymbolRules(
        symbol = "BTC",
        minOrderSize = "0.0001",
        sizeStep = "0.0001",
        tickSize = "1",
        takerFee = takerFee,
        makerFee = makerFee,
    )
}
