package me.matsumo.fukurou.trading.config

import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicWebSocketConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoRateLimitConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoRetryConfig
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.safety.DataQualityCapConfig
import me.matsumo.fukurou.trading.safety.EconomicEventBlackout
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * runtime env の render -> parse -> render が固定点になることを検証するテスト。
 */
class RuntimeConfigEnvironmentRoundTripTest {

    @Test
    fun runtimeEnvironment_roundTripsDefaultConfig() {
        val environment = assertRuntimeEnvironmentRoundTrips(TradingBotConfig())

        assertEquals("false", environment.getValue("FUKUROU_LLM_DAEMON_ENABLED"))
        assertEquals("7", environment.getValue("FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR"))
        assertEquals("120", environment.getValue("FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY"))
    }

    @Test
    fun emptyEnvironment_usesEveryCodeOwnedRuntimeDefault() {
        val defaultEnvironment = RuntimeConfigCatalog.runtimeEnvironment(TradingBotConfig())
        val emptyEnvironmentConfig = TradingBotConfig.fromEnvironment(emptyMap())

        assertEquals(RuntimeConfigCatalog.runtimeLegacyEnvNames(), defaultEnvironment.keys)
        assertEquals(TradingBotConfig(), emptyEnvironmentConfig)
        assertEquals(defaultEnvironment, RuntimeConfigCatalog.runtimeEnvironment(emptyEnvironmentConfig))
    }

    @Test
    fun runtimeEnvironment_roundTripsNonDefaultConfig() {
        val environment = assertRuntimeEnvironmentRoundTrips(nonDefaultRuntimeConfig())

        assertEquals("true", environment.getValue("FUKUROU_LLM_DAEMON_ENABLED"))
        assertEquals("123456.7800", environment.getValue("FUKUROU_PAPER_INITIAL_CASH_JPY"))
        assertEquals("0.0010", environment.getValue("FUKUROU_FALLBACK_TAKER_FEE_RATE"))
        assertEquals("240", environment.getValue("FUKUROU_LLM_RUN_TIMEOUT_SECONDS"))
        assertEquals("claude-runtime-test", environment.getValue("FUKUROU_CLAUDE_MODEL"))
        assertEquals("codex-runtime-test", environment.getValue("FUKUROU_CODEX_MODEL"))
        assertEquals("CODEX", environment.getValue("FUKUROU_PROPOSER_PROVIDER"))
        assertEquals("proposer-runtime-test", environment.getValue("FUKUROU_PROPOSER_MODEL"))
        assertEquals("HIGH", environment.getValue("FUKUROU_PROPOSER_EFFORT"))
        assertEquals("CLAUDE", environment.getValue("FUKUROU_FALSIFIER_PROVIDER"))
        assertEquals("falsifier-runtime-test", environment.getValue("FUKUROU_FALSIFIER_MODEL"))
        assertEquals("LOW", environment.getValue("FUKUROU_FALSIFIER_EFFORT"))
        assertEquals(
            """[{"eventId":"cpi-20260703","eventName":"CPI","eventAt":"2026-07-03T12:30:00Z","blackoutBeforeSeconds":30,"blackoutAfterSeconds":90}]""",
            environment.getValue("FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC"),
        )
    }

    @Test
    fun nonDefaultRuntimeConfig_perturbsEveryRuntimeKey() {
        val defaultEnvironment = RuntimeConfigCatalog.runtimeEnvironment(TradingBotConfig())
        val nonDefaultEnvironment = RuntimeConfigCatalog.runtimeEnvironment(nonDefaultRuntimeConfig())

        val unperturbedValues = defaultEnvironment
            .filter { (name, value) -> nonDefaultEnvironment[name] == value }

        assertEquals(
            emptyMap(),
            unperturbedValues,
            "round-trip の判別力を保つため、全 runtime key を default と異なる値にしてください。",
        )
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

    // round-trip の判別力を保つための perturb 値であり、取引運用の推奨値ではない。
    return config.copy(
        paperAccount = config.paperAccount.copy(
            initialCashJpy = BigDecimal("123456.7800"),
        ),
        paperExecution = PaperExecutionConfig(
            marketSlippageBps = BigDecimal("6.25"),
            volatilitySlippageMultiplier = BigDecimal("0.20"),
        ),
        paperMarket = config.paperMarket.copy(
            fallbackMakerFeeRate = BigDecimal("0.0000"),
            fallbackTakerFeeRate = BigDecimal("0.0010"),
            fallbackSpreadBps = BigDecimal("3.0"),
        ),
        safetyFloor = config.safetyFloor.copy(
            maxRiskPerTradeRatio = BigDecimal("0.0100"),
            maxDrawdownRatio = BigDecimal("-0.10"),
            maxTotalExposureRatio = BigDecimal("0.60"),
            minExpectedValueR = BigDecimal("0.20"),
            minExpectedMoveToCostRatio = BigDecimal("3.0"),
            dataQualityCap = DataQualityCapConfig(
                staleAfter = Duration.ofSeconds(30),
                cappedProbability = BigDecimal("0.40"),
            ),
            maxTakerFeeRatio = BigDecimal("0.0008"),
            economicEventBlackouts = listOf(
                EconomicEventBlackout(
                    eventId = "cpi-20260703",
                    eventName = "CPI",
                    eventAt = Instant.parse("2026-07-03T12:30:00Z"),
                    blackoutBefore = Duration.ofSeconds(30),
                    blackoutAfter = Duration.ofSeconds(90),
                ),
            ),
            marketSlippageReserveBps = BigDecimal("6"),
        ),
        decisionProtocol = config.decisionProtocol.copy(
            falsificationFreshnessWindow = Duration.ofSeconds(90),
            restingEntryOrderTtl = Duration.ofSeconds(1200),
        ),
        runner = config.runner.copy(
            maxToolCallsPerRun = 24,
            maxActToolCallsPerRun = 2,
            perRunTimeout = Duration.ofSeconds(240),
            maxInvocationsPerHour = 3,
            maxInvocationsPerDay = 48,
        ),
        daemon = config.daemon.copy(
            enabled = true,
            pollInterval = Duration.ofSeconds(120),
            flatHeartbeatInterval = Duration.ofMinutes(20),
            holdingCheckInterval = Duration.ofMinutes(20),
            priceMoveTriggerEnabled = false,
            priceMoveWindow = Duration.ofSeconds(360),
            priceMoveThresholdRatio = BigDecimal("0.0200"),
            priceMoveCooldown = Duration.ofSeconds(720),
            stopProximityTriggerEnabled = false,
            stopProximityRemainingRThreshold = BigDecimal("0.20"),
            stopProximityCooldown = Duration.ofSeconds(960),
            entryFillTriggerEnabled = false,
            entryFillCooldown = Duration.ofSeconds(1200),
            preFilterEnabled = true,
        ),
        llmModels = LlmModelConfig(
            claudeModel = "claude-runtime-test",
            codexModel = "codex-runtime-test",
        ),
        llmRoleAssignments = LlmRoleAssignments(
            proposer = LlmRoleAssignment(
                provider = LlmProvider.CODEX,
                model = "proposer-runtime-test",
                effort = LlmEffort.HIGH,
            ),
            falsifier = LlmRoleAssignment(
                provider = LlmProvider.CLAUDE,
                model = "falsifier-runtime-test",
                effort = LlmEffort.LOW,
            ),
        ),
        obsidian = config.obsidian.copy(
            enabled = true,
            vaultPath = "/tmp/fukurou-roundtrip-vault",
            writeInterval = Duration.ofMinutes(6),
        ),
        reflection = config.reflection.copy(
            minInterval = Duration.ofHours(2),
            queryLimit = 500,
            calibrationLookbackDays = 90,
            recentDecisionLimit = 25,
            sampleWarningTradeCount = 20,
            promptCandidateProvider = LlmProvider.CODEX,
            promptCandidateTimeout = Duration.ofSeconds(90),
            promptCandidateMaxAttemptsPerPeriod = 3,
        ),
        killCriterion = config.killCriterion.copy(
            minClosedTrades = 80,
            minProfitFactor = BigDecimal("0.90"),
        ),
        gmoPublicClient = config.gmoPublicClient.copy(
            connectTimeout = Duration.ofMillis(6000),
            requestTimeout = Duration.ofMillis(11000),
            symbolRulesCacheTtl = Duration.ofSeconds(900),
            rateLimit = GmoRateLimitConfig(
                permitsPerSecond = 7,
                burstSize = 8,
            ),
            retry = GmoRetryConfig(
                maxAttempts = 4,
                initialBackoff = Duration.ofMillis(300),
                maxBackoff = Duration.ofMillis(2500),
                backoffMultiplier = 3,
            ),
        ),
        gmoPublicWebSocket = GmoPublicWebSocketConfig(
            endpoint = "wss://example.invalid/fukurou-test",
            connectTimeout = Duration.ofMillis(6100),
            messageStaleTimeout = Duration.ofSeconds(31),
            reconnectBackoff = Duration.ofMillis(2100),
        ),
    )
}
