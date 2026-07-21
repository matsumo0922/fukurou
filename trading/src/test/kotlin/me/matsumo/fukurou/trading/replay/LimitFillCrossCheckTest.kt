package me.matsumo.fukurou.trading.replay

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 記録済み execution と simulator 再計算の cross-check 回帰テスト。 */
class LimitFillCrossCheckTest {

    private val limitPriceJpy = BigDecimal("10000000")
    private val sizeBtc = BigDecimal("0.0100")
    private val makerFeeRate = BigDecimal("0.0002")

    @Test
    fun recordedPriceAndFeeMatchSimulatorRecomputation() {
        val probe = LimitFillCrossCheck.verify(
            limitPriceJpy = limitPriceJpy,
            sizeBtc = sizeBtc,
            makerFeeRate = makerFeeRate,
            recordedPriceJpy = limitPriceJpy,
            recordedFeeJpy = BigDecimal.ZERO,
        )

        val matched = LimitFillCrossCheck.verify(
            limitPriceJpy = limitPriceJpy,
            sizeBtc = sizeBtc,
            makerFeeRate = makerFeeRate,
            recordedPriceJpy = probe.expectedPriceJpy,
            recordedFeeJpy = probe.expectedFeeJpy,
        )

        assertTrue(matched.matches)
    }

    @Test
    fun mismatchedFeeIsDetected() {
        val probe = LimitFillCrossCheck.verify(
            limitPriceJpy = limitPriceJpy,
            sizeBtc = sizeBtc,
            makerFeeRate = makerFeeRate,
            recordedPriceJpy = limitPriceJpy,
            recordedFeeJpy = BigDecimal.ZERO,
        )

        val mismatched = LimitFillCrossCheck.verify(
            limitPriceJpy = limitPriceJpy,
            sizeBtc = sizeBtc,
            makerFeeRate = makerFeeRate,
            recordedPriceJpy = probe.expectedPriceJpy,
            recordedFeeJpy = probe.expectedFeeJpy.add(BigDecimal.ONE),
        )

        assertTrue(mismatched.priceMatches)
        assertFalse(mismatched.feeMatches)
        assertFalse(mismatched.matches)
    }
}
