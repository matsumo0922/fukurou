package me.matsumo.fukurou.trading.config

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RuntimeConfigResolver の DB active 優先と fail-closed 挙動を検証するテスト。
 */
class RuntimeConfigResolverTest {

    @Test
    fun resolve_usesActiveRuntimeConfigAheadOfLegacyRuntimeEnv() {
        val values = RuntimeConfigCatalog.runtimeDefaultValues() + mapOf(
            "runner.maxToolCallsPerRun" to "12",
            "safety.economicEventBlackouts" to economicEventBlackoutsJson(),
        )
        val resolver = RuntimeConfigResolver(FakeActiveRuntimeConfigSource(values))

        val result = resolver.resolve(
            environment = mapOf(
                "FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT" to "48",
                "FUKUROU_TRADING_SYMBOL" to "BTC",
            ),
        ).getOrThrow()

        assertEquals(12, result.tradingConfig.runner.maxToolCallsPerRun)
        assertEquals("12", result.typedEnvironment.getValue("FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT"))
        assertEquals("12", result.catalogEnvironment.getValue("FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT"))
        assertEquals("runtime-config-version", result.auditSnapshot.versionId)
        assertEquals(calculateRuntimeConfigHash(values), result.auditSnapshot.hash)
    }

    @Test
    fun resolve_preservesStructuredRuntimeConfigJson() {
        val resolver = RuntimeConfigResolver(
            FakeActiveRuntimeConfigSource(
                RuntimeConfigCatalog.runtimeDefaultValues() + mapOf(
                    "safety.economicEventBlackouts" to economicEventBlackoutsJson(),
                ),
            ),
        )

        val result = resolver.resolve(emptyMap()).getOrThrow()
        val blackout = result.tradingConfig.safetyFloor.economicEventBlackouts.single()

        assertEquals("cpi-20260703", blackout.eventId)
        assertEquals(Instant.parse("2026-07-03T12:30:00Z"), blackout.eventAt)
        assertEquals(Duration.ofSeconds(30), blackout.blackoutBefore)
        assertEquals(Duration.ofSeconds(90), blackout.blackoutAfter)
        assertTrue(result.catalogEnvironment.getValue("FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC").startsWith("["))
    }

    @Test
    fun resolve_failsClosedWhenActiveRuntimeConfigIsMissingCatalogKey() {
        val values = RuntimeConfigCatalog.runtimeDefaultValues()
            .filterKeys { key -> key != "runner.maxToolCallsPerRun" }
        val resolver = RuntimeConfigResolver(FakeActiveRuntimeConfigSource(values))

        assertTrue(resolver.resolve(emptyMap()).isFailure)
    }

    @Test
    fun resolve_failsClosedWhenTypedValidationFails() {
        val values = RuntimeConfigCatalog.runtimeDefaultValues() + mapOf(
            "runner.maxToolCallsPerRun" to "49",
        )
        val resolver = RuntimeConfigResolver(FakeActiveRuntimeConfigSource(values))

        assertTrue(resolver.resolve(emptyMap()).isFailure)
    }
}

private class FakeActiveRuntimeConfigSource(
    private val values: Map<String, String>,
) : ActiveRuntimeConfigSource {
    override fun activeSnapshot(): Result<ActiveRuntimeConfigSnapshot> {
        return Result.success(
            ActiveRuntimeConfigSnapshot(
                versionId = "runtime-config-version",
                activatedAt = Instant.parse("2026-07-01T00:00:00Z"),
                values = values,
            ),
        )
    }
}

private fun economicEventBlackoutsJson(): String {
    return """
        [
          {
            "eventId": "cpi-20260703",
            "eventName": "CPI",
            "eventAt": "2026-07-03T12:30:00Z",
            "blackoutBeforeSeconds": 30,
            "blackoutAfterSeconds": 90
          }
        ]
    """.trimIndent()
}
