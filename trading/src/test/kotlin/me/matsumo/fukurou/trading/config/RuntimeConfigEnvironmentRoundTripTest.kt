package me.matsumo.fukurou.trading.config

import me.matsumo.fukurou.trading.safety.EconomicEventBlackout
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * runtime env の render -> parse -> render が固定点になることを検証するテスト。
 */
class RuntimeConfigEnvironmentRoundTripTest {

    @Test
    fun runtimeEnvironment_roundTripsDefaultConfig() {
        val environment = assertRuntimeEnvironmentRoundTrips(TradingBotConfig())

        assertEquals("false", environment.getValue("FUKUROU_LLM_DAEMON_ENABLED"))
    }

    @Test
    fun runtimeEnvironment_roundTripsNonDefaultConfig() {
        val environment = assertRuntimeEnvironmentRoundTrips(nonDefaultRuntimeConfig())

        assertEquals("true", environment.getValue("FUKUROU_LLM_DAEMON_ENABLED"))
        assertEquals("0.0010", environment.getValue("FUKUROU_FALLBACK_TAKER_FEE_RATE"))
        assertEquals("240", environment.getValue("FUKUROU_LLM_RUN_TIMEOUT_SECONDS"))
        assertTrue(environment.getValue("FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC").startsWith("["))
    }
}

private fun assertRuntimeEnvironmentRoundTrips(config: TradingBotConfig): Map<String, String> {
    val renderedEnvironment = RuntimeConfigCatalog.runtimeEnvironment(config)
    val parsedConfig = TradingBotConfig.fromEnvironment(renderedEnvironment)
    val reparsedEnvironment = RuntimeConfigCatalog.runtimeEnvironment(parsedConfig)

    assertEquals(renderedEnvironment, reparsedEnvironment)

    return renderedEnvironment
}

private fun nonDefaultRuntimeConfig(): TradingBotConfig {
    val config = TradingBotConfig()

    return config.copy(
        paperAccount = config.paperAccount.copy(
            initialCashJpy = BigDecimal("123456.7800"),
        ),
        paperMarket = config.paperMarket.copy(
            fallbackTakerFeeRate = BigDecimal("0.0010"),
        ),
        safetyFloor = config.safetyFloor.copy(
            economicEventBlackouts = listOf(
                EconomicEventBlackout(
                    eventId = "cpi-20260703",
                    eventName = "CPI",
                    eventAt = Instant.parse("2026-07-03T12:30:00Z"),
                    blackoutBefore = Duration.ofSeconds(30),
                    blackoutAfter = Duration.ofSeconds(90),
                ),
            ),
        ),
        runner = config.runner.copy(
            perRunTimeout = Duration.ofSeconds(240),
        ),
        daemon = config.daemon.copy(
            enabled = true,
        ),
    )
}
