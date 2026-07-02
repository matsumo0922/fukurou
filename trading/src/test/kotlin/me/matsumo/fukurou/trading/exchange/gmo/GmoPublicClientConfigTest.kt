package me.matsumo.fukurou.trading.exchange.gmo

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GMO Public client config の env override を検証するテスト。
 */
class GmoPublicClientConfigTest {

    @Test
    fun fromEnvironment_ignoresUnrelatedTradingEnvironment() {
        val config = GmoPublicClientConfig.fromEnvironment(
            mapOf(
                "FUKUROU_TRADING_MODE" to "LIVE",
                "FUKUROU_PAPER_INITIAL_CASH_JPY" to "-1",
            ),
        )

        assertEquals("https://api.coin.z.com/public", config.baseUrl)
        assertEquals(10, config.rateLimit.permitsPerSecond)
        assertEquals(10, config.rateLimit.burstSize)
    }

    @Test
    fun fromEnvironment_appliesGmoPublicClientOverrides() {
        val config = GmoPublicClientConfig.fromEnvironment(
            mapOf(
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

        assertEquals("https://example.test/public", config.baseUrl)
        assertEquals(Duration.ofMillis(3000), config.connectTimeout)
        assertEquals(Duration.ofMillis(4000), config.requestTimeout)
        assertEquals(Duration.ofSeconds(60), config.symbolRulesCacheTtl)
        assertEquals(5, config.rateLimit.permitsPerSecond)
        assertEquals(6, config.rateLimit.burstSize)
        assertEquals(4, config.retry.maxAttempts)
        assertEquals(Duration.ofMillis(50), config.retry.initialBackoff)
        assertEquals(Duration.ofMillis(500), config.retry.maxBackoff)
        assertEquals(3, config.retry.backoffMultiplier)
    }
}
