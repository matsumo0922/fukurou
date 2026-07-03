package me.matsumo.fukurou.trading.config

import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * TradingBotConfig の env override を検証するテスト。
 */
class TradingBotConfigTest {

    @Test
    fun fromEnvironment_usesDefaultsWhenEnvironmentIsEmpty() {
        val config = TradingBotConfig.fromEnvironment(emptyMap())

        assertEquals(TradingSymbol.BTC, config.symbol)
        assertEquals(TradingMode.PAPER, config.mode)
        assertEquals(BigDecimal("100000"), config.paperAccount.initialCashJpy)
        assertEquals(BigDecimal("0.80"), config.safetyFloor.maxTotalExposureRatio)
        assertEquals(10, config.gmoPublicClient.rateLimit.permitsPerSecond)
    }

    @Test
    fun fromEnvironment_appliesTradingSafetyAndGmoOverrides() {
        val config = TradingBotConfig.fromEnvironment(
            mapOf(
                "FUKUROU_TRADING_SYMBOL" to "BTC",
                "FUKUROU_TRADING_MODE" to "paper",
                "FUKUROU_PAPER_INITIAL_CASH_JPY" to "250000",
                "FUKUROU_MARKET_SLIPPAGE_BPS" to "7",
                "FUKUROU_FALLBACK_MAKER_FEE_RATE" to "0.0000",
                "FUKUROU_FALLBACK_TAKER_FEE_RATE" to "0.0006",
                "FUKUROU_MAX_RISK_PER_TRADE_RATIO" to "0.015",
                "FUKUROU_MAX_DRAWDOWN_RATIO" to "-0.12",
                "FUKUROU_MAX_TOTAL_EXPOSURE_RATIO" to "0.70",
                "FUKUROU_MIN_EXPECTED_VALUE_R" to "0.12",
                "FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO" to "3.5",
                "FUKUROU_MAX_TAKER_FEE_RATIO" to "0.0008",
                "FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS" to "8",
                "FUKUROU_FALSIFICATION_FRESHNESS_SECONDS" to "90",
                "FUKUROU_GMO_PUBLIC_BASE_URL" to "https://example.test/public",
                "FUKUROU_GMO_CONNECT_TIMEOUT_MS" to "3000",
                "FUKUROU_GMO_REQUEST_TIMEOUT_MS" to "4000",
                "FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS" to "60",
                "FUKUROU_GMO_PUBLIC_REST_PER_SECOND" to "5",
                "FUKUROU_GMO_PUBLIC_REST_BURST" to "6",
                "FUKUROU_GMO_RETRY_MAX_ATTEMPTS" to "4",
                "FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS" to "50",
                "FUKUROU_GMO_RETRY_MAX_BACKOFF_MS" to "500",
                "FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER" to "3",
            ),
        )

        assertEquals(TradingSymbol.BTC, config.symbol)
        assertEquals(TradingMode.PAPER, config.mode)
        assertEquals(BigDecimal("250000"), config.paperAccount.initialCashJpy)
        assertEquals(BigDecimal("7"), config.paperExecution.marketSlippageBps)
        assertEquals(BigDecimal("0.0000"), config.paperMarket.fallbackMakerFeeRate)
        assertEquals(BigDecimal("0.0006"), config.paperMarket.fallbackTakerFeeRate)
        assertEquals(BigDecimal("0.015"), config.safetyFloor.maxRiskPerTradeRatio)
        assertEquals(BigDecimal("-0.12"), config.safetyFloor.maxDrawdownRatio)
        assertEquals(BigDecimal("0.70"), config.safetyFloor.maxTotalExposureRatio)
        assertEquals(BigDecimal("0.12"), config.safetyFloor.minExpectedValueR)
        assertEquals(BigDecimal("3.5"), config.safetyFloor.minExpectedMoveToCostRatio)
        assertEquals(BigDecimal("0.0008"), config.safetyFloor.maxTakerFeeRatio)
        assertEquals(BigDecimal("8"), config.safetyFloor.marketSlippageReserveBps)
        assertEquals(Duration.ofSeconds(90), config.decisionProtocol.falsificationFreshnessWindow)
        assertEquals("https://example.test/public", config.gmoPublicClient.baseUrl)
        assertEquals(Duration.ofMillis(3000), config.gmoPublicClient.connectTimeout)
        assertEquals(Duration.ofMillis(4000), config.gmoPublicClient.requestTimeout)
        assertEquals(Duration.ofSeconds(60), config.gmoPublicClient.symbolRulesCacheTtl)
        assertEquals(5, config.gmoPublicClient.rateLimit.permitsPerSecond)
        assertEquals(6, config.gmoPublicClient.rateLimit.burstSize)
        assertEquals(4, config.gmoPublicClient.retry.maxAttempts)
        assertEquals(Duration.ofMillis(50), config.gmoPublicClient.retry.initialBackoff)
        assertEquals(Duration.ofMillis(500), config.gmoPublicClient.retry.maxBackoff)
        assertEquals(3, config.gmoPublicClient.retry.backoffMultiplier)
    }

    @Test
    fun fromEnvironment_rejectsUnsafeTradingOverrides() {
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MARKET_SLIPPAGE_BPS" to "-1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_FALLBACK_MAKER_FEE_RATE" to "-0.0002"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_FALLBACK_TAKER_FEE_RATE" to "-0.0001"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MAX_RISK_PER_TRADE_RATIO" to "0.03"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MAX_DRAWDOWN_RATIO" to "-0.20"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MAX_TOTAL_EXPOSURE_RATIO" to "0.90"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MIN_EXPECTED_VALUE_R" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO" to "2.5"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MAX_TAKER_FEE_RATIO" to "0.002"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_FALSIFICATION_FRESHNESS_SECONDS" to "121"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_FALSIFICATION_FRESHNESS_SECONDS" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_GMO_PUBLIC_REST_PER_SECOND" to "11"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_GMO_PUBLIC_REST_BURST" to "11"),
            )
        }
    }

    @Test
    fun fromEnvironment_rejectsLiveModeUntilLiveBrokerExists() {
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_TRADING_MODE" to "LIVE"),
            )
        }
    }
}
