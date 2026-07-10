package me.matsumo.fukurou.trading.config

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RuntimeConfigResolver の DB active 優先と fail-closed 挙動を検証するテスト。
 */
class RuntimeConfigResolverTest {

    @Test
    fun resolve_usesActiveRuntimeConfigAheadOfLegacyRuntimeEnv() {
        val values = RuntimeConfigCatalog.runtimeDefaultValues() + mapOf(
            "runner.maxToolCallsPerRun" to "12",
            "llm.claudeModel" to "claude-db-model",
            "llm.codexModel" to "codex-db-model",
            "safety.economicEventBlackouts" to economicEventBlackoutsJson(),
        )
        val resolver = RuntimeConfigResolver(FakeActiveRuntimeConfigSource(values))

        val result = resolver.resolve(
            environment = mapOf(
                "FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT" to "48",
                "FUKUROU_CLAUDE_MODEL" to "claude-legacy-env-model",
                "FUKUROU_CODEX_MODEL" to "codex-legacy-env-model",
                "FUKUROU_OBSIDIAN_VAULT_PATH" to "/deployment-vault",
                "FUKUROU_TRADING_SYMBOL" to "BTC",
            ),
        ).getOrThrow()

        assertEquals(12, result.tradingConfig.runner.maxToolCallsPerRun)
        assertEquals("claude-db-model", result.tradingConfig.llmModels.claudeModel)
        assertEquals("codex-db-model", result.tradingConfig.llmModels.codexModel)
        assertEquals("/deployment-vault", result.tradingConfig.obsidian.vaultPath)
        assertEquals("12", result.typedEnvironment.getValue("FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT"))
        assertEquals("claude-db-model", result.typedEnvironment.getValue("FUKUROU_CLAUDE_MODEL"))
        assertEquals("codex-db-model", result.typedEnvironment.getValue("FUKUROU_CODEX_MODEL"))
        assertEquals("/deployment-vault", result.typedEnvironment.getValue("FUKUROU_OBSIDIAN_VAULT_PATH"))
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
    fun resolve_acceptsStandardRuntimeConfigAboveAbsoluteMinimumBelowDefault() {
        val values = RuntimeConfigCatalog.runtimeDefaultValues() + mapOf(
            "obsidian.writeInterval" to "60",
            "reflection.minInterval" to "60",
            "reflection.queryLimit" to "500",
            "reflection.promptCandidateProvider" to "CODEX",
            "reflection.promptCandidateTimeout" to "120",
            "reflection.promptCandidateMaxAttempts" to "3",
        )
        val resolver = RuntimeConfigResolver(FakeActiveRuntimeConfigSource(values))

        val result = resolver.resolve(emptyMap()).getOrThrow()

        assertEquals(Duration.ofSeconds(60), result.tradingConfig.obsidian.writeInterval)
        assertEquals(Duration.ofSeconds(60), result.tradingConfig.reflection.minInterval)
        assertEquals(500, result.tradingConfig.reflection.queryLimit)
        assertEquals("CODEX", result.tradingConfig.reflection.promptCandidateProvider.name)
        assertEquals(Duration.ofSeconds(120), result.tradingConfig.reflection.promptCandidateTimeout)
        assertEquals(3, result.tradingConfig.reflection.promptCandidateMaxAttemptsPerPeriod)
        assertEquals("60", result.catalogEnvironment.getValue("FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS"))
    }

    @Test
    fun resolve_canonicalizesReflectionProviderBeforeTypedConfigAndCatalogProjection() {
        val values = RuntimeConfigCatalog.runtimeDefaultValues() + mapOf(
            "reflection.promptCandidateProvider" to "codex",
        )
        val resolver = RuntimeConfigResolver(FakeActiveRuntimeConfigSource(values))

        val result = resolver.resolve(emptyMap()).getOrThrow()

        assertEquals("CODEX", result.tradingConfig.reflection.promptCandidateProvider.name)
        assertEquals("CODEX", result.typedEnvironment.getValue("FUKUROU_REFLECTION_PROMPT_CANDIDATE_PROVIDER"))
        assertEquals("CODEX", result.catalogEnvironment.getValue("FUKUROU_REFLECTION_PROMPT_CANDIDATE_PROVIDER"))
    }

    @Test
    fun resolve_preservesExplicitOperatorInvocationCapsBelowCatalogDefaults() {
        val values = RuntimeConfigCatalog.runtimeDefaultValues() + mapOf(
            "runner.maxInvocationsPerHour" to "6",
            "runner.maxInvocationsPerDay" to "96",
        )
        val resolver = RuntimeConfigResolver(FakeActiveRuntimeConfigSource(values))

        val result = resolver.resolve(emptyMap()).getOrThrow()

        assertEquals(6, result.tradingConfig.runner.maxInvocationsPerHour)
        assertEquals(96, result.tradingConfig.runner.maxInvocationsPerDay)
        assertEquals("6", result.typedEnvironment.getValue("FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR"))
        assertEquals("96", result.typedEnvironment.getValue("FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY"))
    }

    @Test
    fun validate_conservativeOnlyRuntimeConfigKeysAcceptBoundaryAndRejectOneStepOutside() {
        val defaults = RuntimeConfigCatalog.runtimeDefaultValues()
        val cases = conservativeBoundaryCases(defaults)

        assertEquals(conservativeOnlyRuntimeConfigKeys, cases.map { case -> case.key }.toSet())

        cases.forEach { case ->
            val boundaryResult = RuntimeConfigCandidateValidator.validate(
                values = defaults + mapOf(case.key to defaults.getValue(case.key)),
                environment = emptyMap(),
            )
            val outsideResult = RuntimeConfigCandidateValidator.validate(
                values = defaults + mapOf(case.key to case.outsideValue),
                environment = emptyMap(),
            )

            assertTrue(boundaryResult.validation.valid, case.key)
            assertTrue(boundaryResult.tradingConfig != null, case.key)
            assertFalse(outsideResult.validation.valid, case.key)
            assertTrue(
                outsideResult.validation.errors.any { error -> error.key == case.key },
                case.key,
            )
        }
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
        val result = resolver.resolve(emptyMap())
        val exception = result.exceptionOrNull() as RuntimeConfigValidationRejectedException
        val validationError = exception.validation.errors.single()

        assertTrue(result.isFailure)
        assertEquals("runtimeConfig.validation.typedBetweenInclusive", validationError.code)
        assertEquals("runner.maxToolCallsPerRun", validationError.key)
        assertEquals("1", validationError.params.getValue("min"))
        assertEquals("48", validationError.params.getValue("max"))
    }

    @Test
    fun resolve_failsClosedWhenStandardRuntimeConfigViolatesAbsoluteMinimum() {
        val values = RuntimeConfigCatalog.runtimeDefaultValues() + mapOf(
            "obsidian.writeInterval" to "59",
        )
        val resolver = RuntimeConfigResolver(FakeActiveRuntimeConfigSource(values))
        val result = resolver.resolve(emptyMap())
        val exception = result.exceptionOrNull() as RuntimeConfigValidationRejectedException
        val validationError = exception.validation.errors.single()

        assertTrue(result.isFailure)
        assertEquals("runtimeConfig.validation.typedGreaterThanOrEqual", validationError.code)
        assertEquals("obsidian.writeInterval", validationError.key)
        assertEquals("60", validationError.params.getValue("min"))
    }

    @Test
    fun resolve_failsClosedWhenReflectionProviderIsUnknown() {
        val values = RuntimeConfigCatalog.runtimeDefaultValues() + mapOf(
            "reflection.promptCandidateProvider" to "UNKNOWN",
        )
        val resolver = RuntimeConfigResolver(FakeActiveRuntimeConfigSource(values))
        val result = resolver.resolve(emptyMap())
        val exception = result.exceptionOrNull() as RuntimeConfigValidationRejectedException
        val validationError = exception.validation.errors.single()

        assertTrue(result.isFailure)
        assertEquals("runtimeConfig.validation.typedOneOf", validationError.code)
        assertEquals("reflection.promptCandidateProvider", validationError.key)
        assertEquals("CLAUDE, CODEX", validationError.params.getValue("values"))
    }
}

private data class ConservativeBoundaryCase(
    val key: String,
    val outsideValue: String,
)

@Suppress("LongMethod")
private fun conservativeBoundaryCases(defaults: Map<String, String>): List<ConservativeBoundaryCase> {
    return listOf(
        conservativeBoundaryCase(
            defaults = defaults,
            key = "paper.fallbackMakerFeeRate",
            outsideValue = ::decimalBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "paper.fallbackTakerFeeRate",
            outsideValue = ::decimalBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "paper.fallbackSpreadBps",
            outsideValue = ::decimalBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "safety.maxRiskPerTradeRatio",
            outsideValue = ::decimalAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "safety.maxDrawdownRatio",
            outsideValue = ::decimalBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "safety.maxTotalExposureRatio",
            outsideValue = ::decimalAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "safety.minExpectedValueR",
            outsideValue = ::decimalBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "safety.minExpectedMoveToCostRatio",
            outsideValue = ::decimalBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "safety.maxTakerFeeRatio",
            outsideValue = ::decimalAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "safety.marketSlippageReserveBps",
            outsideValue = ::decimalBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "safety.dataQualityStaleAfter",
            outsideValue = ::longAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "safety.dataQualityCappedProbability",
            outsideValue = ::decimalAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "decision.falsificationFreshnessWindow",
            outsideValue = ::longAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "decision.restingEntryOrderTtl",
            outsideValue = ::longAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "runner.maxToolCallsPerRun",
            outsideValue = ::intAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "runner.maxActToolCallsPerRun",
            outsideValue = ::intAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "runner.perRunTimeout",
            outsideValue = ::longAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "runner.maxInvocationsPerHour",
            outsideValue = ::intAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "runner.maxInvocationsPerDay",
            outsideValue = ::intAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "daemon.pollInterval",
            outsideValue = ::longBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "daemon.flatHeartbeatInterval",
            outsideValue = ::longBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "daemon.holdingCheckInterval",
            outsideValue = ::longBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "killCriterion.minClosedTrades",
            outsideValue = ::intAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "killCriterion.minProfitFactor",
            outsideValue = ::decimalBelowDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "gmoPublic.restPerSecond",
            outsideValue = ::intAboveDefault,
        ),
        conservativeBoundaryCase(
            defaults = defaults,
            key = "gmoPublic.restBurst",
            outsideValue = ::intAboveDefault,
        ),
    )
}

private fun conservativeBoundaryCase(
    defaults: Map<String, String>,
    key: String,
    outsideValue: (Map<String, String>, String) -> String,
): ConservativeBoundaryCase {
    return ConservativeBoundaryCase(key, outsideValue(defaults, key))
}

private fun decimalBelowDefault(defaults: Map<String, String>, key: String): String {
    return defaults.getValue(key).toBigDecimal().subtract(decimalStep(defaults.getValue(key))).toPlainString()
}

private fun decimalAboveDefault(defaults: Map<String, String>, key: String): String {
    return defaults.getValue(key).toBigDecimal().add(decimalStep(defaults.getValue(key))).toPlainString()
}

private fun decimalStep(value: String): BigDecimal {
    return if (value.contains(".")) BigDecimal("0.0001") else BigDecimal.ONE
}

private fun intAboveDefault(defaults: Map<String, String>, key: String): String {
    return (defaults.getValue(key).toInt() + 1).toString()
}

private fun longAboveDefault(defaults: Map<String, String>, key: String): String {
    return (defaults.getValue(key).toLong() + 1).toString()
}

private fun longBelowDefault(defaults: Map<String, String>, key: String): String {
    return (defaults.getValue(key).toLong() - 1).toString()
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
