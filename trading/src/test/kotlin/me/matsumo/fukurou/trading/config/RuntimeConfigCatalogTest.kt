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
            "FUKUROU_CLAUDE_MODEL" to "claude-runtime-test",
            "FUKUROU_CODEX_MODEL" to "codex-runtime-test",
            "FUKUROU_OBSIDIAN_VAULT_PATH" to "/vault",
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
        assertEquals(RuntimeConfigSourceKind.DEPLOYMENT, deploymentItems.getValue("obsidian.vaultPath").sourceKind)
        assertEquals(RuntimeConfigSourceKind.DEPLOYMENT, deploymentItems.getValue("runner.proposerAllowedTools").sourceKind)
        assertEquals(RuntimeConfigSourceKind.RUNTIME, runtimeItems.getValue("llm.claudeModel").sourceKind)
        assertEquals(RuntimeConfigSourceKind.RUNTIME, runtimeItems.getValue("llm.codexModel").sourceKind)
        assertEquals(RuntimeConfigSourceKind.RUNTIME, runtimeItems.getValue("reflection.minInterval").sourceKind)
        assertEquals(RuntimeConfigSourceKind.RUNTIME, runtimeItems.getValue("reflection.promptCandidateProvider").sourceKind)
        assertTrue(
            runtimeItems.keys.containsAll(
                setOf(
                    "reflection.minInterval",
                    "reflection.queryLimit",
                    "reflection.calibrationLookbackDays",
                    "reflection.recentDecisionLimit",
                    "reflection.sampleWarningTradeCount",
                    "reflection.promptCandidateProvider",
                    "reflection.promptCandidateTimeout",
                    "reflection.promptCandidateMaxAttempts",
                ),
            ),
        )
        assertEquals("https://example.test/public", deploymentItems.getValue("gmoPublic.baseUrl").effectiveValue)
        assertFalse(deploymentItems.getValue("trading.mode").editable)
        assertFalse(deploymentItems.getValue("obsidian.vaultPath").editable)
        assertTrue(runtimeItems.getValue("llm.claudeModel").editable)
        assertTrue(runtimeItems.getValue("llm.claudeModel").blankAllowed)
        assertTrue(runtimeItems.getValue("llm.codexModel").blankAllowed)
        assertFalse(runtimeItems.getValue("reflection.promptCandidateProvider").blankAllowed)
        assertEquals("claude-runtime-test", runtimeItems.getValue("llm.claudeModel").effectiveValue)
        assertEquals("codex-runtime-test", runtimeItems.getValue("llm.codexModel").effectiveValue)
        assertTrue(runtimeItems.getValue("safety.maxRiskPerTradeRatio").editable)
        assertEquals(RuntimeConfigApplyMode.NEXT_RESTART, runtimeItems.getValue("safety.maxRiskPerTradeRatio").applyMode)
        assertEquals(
            "FUKUROU_VOLATILITY_SLIPPAGE_MULTIPLIER",
            runtimeItems.getValue("paper.volatilitySlippageMultiplier").legacyEnvName,
        )
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

    @Test
    fun runtimeDefaultValues_exposePaperReviewDefaults() {
        val defaultValues = RuntimeConfigCatalog.runtimeDefaultValues()
        val defaultEnvironment = RuntimeConfigCatalog.runtimeEnvironment(TradingBotConfig())

        assertEquals("2.5", defaultValues.getValue("safety.minExpectedMoveToCostRatio"))
        assertEquals("7", defaultValues.getValue("runner.maxInvocationsPerHour"))
        assertEquals("120", defaultValues.getValue("runner.maxInvocationsPerDay"))
        assertEquals("1", defaultValues.getValue("runner.entryFillReservePerHour"))
        assertEquals("4", defaultValues.getValue("runner.entryFillReservePerDay"))
        assertEquals("1", defaultValues.getValue("runner.stopProximityReservePerHour"))
        assertEquals("4", defaultValues.getValue("runner.stopProximityReservePerDay"))
        assertEquals("900", defaultValues.getValue("daemon.flatHeartbeatInterval"))
        assertEquals("2.5", defaultEnvironment.getValue("FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO"))
        assertEquals("7", defaultEnvironment.getValue("FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR"))
        assertEquals("120", defaultEnvironment.getValue("FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY"))
        assertEquals("1", defaultEnvironment.getValue("FUKUROU_LLM_ENTRY_FILL_RESERVE_PER_HOUR"))
        assertEquals("4", defaultEnvironment.getValue("FUKUROU_LLM_ENTRY_FILL_RESERVE_PER_DAY"))
        assertEquals("1", defaultEnvironment.getValue("FUKUROU_LLM_STOP_PROXIMITY_RESERVE_PER_HOUR"))
        assertEquals("4", defaultEnvironment.getValue("FUKUROU_LLM_STOP_PROXIMITY_RESERVE_PER_DAY"))
        assertEquals("900", defaultEnvironment.getValue("FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS"))
    }
}
