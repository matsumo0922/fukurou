package me.matsumo.fukurou.trading.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RuntimeConfigCatalog の分類と secret redaction を検証するテスト。
 */
class RuntimeConfigCatalogTest {

    @Test
    fun snapshot_returnsRuntimeDeploymentAndSecretGroups() {
        val environment = mapOf(
            "FUKUROU_TRADING_SYMBOL" to "BTC",
            "FUKUROU_GMO_PUBLIC_BASE_URL" to "https://example.test/public",
            "FUKUROU_CLAUDE_COMMAND_TEMPLATE" to "docker run claude",
            "FUKUROU_PROPOSER_ALLOWED_TOOLS" to "mcp__fukurou-mcp__get_ticker",
            "DB_PASSWORD" to "super-secret-password",
        )
        val config = TradingBotConfig.fromEnvironment(environment)

        val snapshot = RuntimeConfigCatalog.snapshot(config, environment)
        val groups = snapshot.groups.associateBy { group -> group.id }
        val deploymentItems = requireNotNull(groups["deployment"]).items.associateBy { item -> item.key }
        val runtimeItems = requireNotNull(groups["runtime"]).items.associateBy { item -> item.key }
        val secretItems = requireNotNull(groups["secrets"]).items.associateBy { item -> item.key }

        assertEquals(setOf("runtime", "deployment", "secrets"), groups.keys)
        assertEquals(RuntimeConfigSourceKind.RUNTIME, runtimeItems.getValue("runner.maxToolCallsPerRun").sourceKind)
        assertEquals(RuntimeConfigSourceKind.DEPLOYMENT, deploymentItems.getValue("gmoPublic.baseUrl").sourceKind)
        assertEquals(RuntimeConfigSourceKind.DEPLOYMENT, deploymentItems.getValue("llm.claudeCommandTemplate").sourceKind)
        assertEquals(RuntimeConfigSourceKind.DEPLOYMENT, deploymentItems.getValue("runner.proposerAllowedTools").sourceKind)
        assertEquals("https://example.test/public", deploymentItems.getValue("gmoPublic.baseUrl").effectiveValue)
        assertFalse(deploymentItems.getValue("trading.mode").editable)
        assertFalse(runtimeItems.getValue("safety.maxRiskPerTradeRatio").editable)
        assertEquals(RuntimeConfigSafetyTier.GUARDED, runtimeItems.getValue("gmoPublic.restPerSecond").safetyTier)
        assertEquals(RuntimeConfigSafetyTier.GUARDED, runtimeItems.getValue("gmoPublic.restBurst").safetyTier)
        assertTrue(secretItems.getValue("database.password").valueConfigured)
        assertNull(secretItems.getValue("database.password").currentValue)
        assertNull(secretItems.getValue("database.password").effectiveValue)
    }

    @Test
    fun snapshot_marksSecretsMissingWithoutExposingValues() {
        val environment = mapOf(
            "CLOUDFLARED_TUNNEL_TOKEN" to "cf-secret-token",
            "POSTGRES_PASSWORD" to "postgres-secret",
        )

        val snapshot = RuntimeConfigCatalog.snapshot(
            tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
            environment = environment,
        )
        val renderedValues = snapshot.groups
            .flatMap { group -> group.items }
            .flatMap { item -> listOfNotNull(item.defaultValue, item.currentValue, item.effectiveValue) }
            .joinToString("\n")
        val secrets = snapshot.groups
            .single { group -> group.id == "secrets" }
            .items
            .associateBy { item -> item.key }

        assertTrue(secrets.getValue("cloudflare.tunnelToken").valueConfigured)
        assertTrue(secrets.getValue("postgres.password").valueConfigured)
        assertFalse(secrets.getValue("database.password").valueConfigured)
        assertFalse(renderedValues.contains("cf-secret-token"))
        assertFalse(renderedValues.contains("postgres-secret"))
        assertNotNull(secrets.getValue("cloudflare.tunnelToken").labelKey)
    }
}
