package me.matsumo.fukurou.trading.config

import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.persistence.staleLlmRunRecoveryThreshold
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
        assertEquals(BigDecimal("5"), config.paperExecution.marketSlippageBps)
        assertEquals(BigDecimal("0.1"), config.paperExecution.volatilitySlippageMultiplier)
        assertEquals(BigDecimal("0.80"), config.safetyFloor.maxTotalExposureRatio)
        assertEquals(BigDecimal("2.5"), config.safetyFloor.minExpectedMoveToCostRatio)
        assertEquals(Duration.ofSeconds(60), config.safetyFloor.dataQualityCap.staleAfter)
        assertEquals(BigDecimal("0.5"), config.safetyFloor.dataQualityCap.cappedProbability)
        assertEquals(Duration.ofMinutes(30), config.decisionProtocol.restingEntryOrderTtl)
        assertEquals(48, config.runner.maxToolCallsPerRun)
        assertEquals(3, config.runner.maxActToolCallsPerRun)
        assertEquals(Duration.ofSeconds(180), config.runner.perRunTimeout)
        assertEquals(7, config.runner.maxInvocationsPerHour)
        assertEquals(120, config.runner.maxInvocationsPerDay)
        assertEquals(false, config.daemon.enabled)
        assertEquals(Duration.ofMinutes(15), config.daemon.flatHeartbeatInterval)
        assertEquals(Duration.ofMinutes(15), config.daemon.holdingCheckInterval)
        assertEquals(true, config.daemon.priceMoveTriggerEnabled)
        assertEquals(Duration.ofSeconds(300), config.daemon.priceMoveWindow)
        assertEquals(BigDecimal("0.01"), config.daemon.priceMoveThresholdRatio)
        assertEquals(Duration.ofSeconds(600), config.daemon.priceMoveCooldown)
        assertEquals(true, config.daemon.entryFillTriggerEnabled)
        assertEquals(Duration.ofSeconds(600), config.daemon.entryFillCooldown)
        assertEquals(false, config.daemon.preFilterEnabled)
        assertEquals(true, config.daemon.stopProximityTriggerEnabled)
        assertEquals(BigDecimal("0.3"), config.daemon.stopProximityRemainingRThreshold)
        assertEquals(Duration.ofSeconds(900), config.daemon.stopProximityCooldown)
        assertEquals(false, config.obsidian.enabled)
        assertEquals("/vault", config.obsidian.vaultPath)
        assertEquals(Duration.ofMinutes(5), config.obsidian.writeInterval)
        assertEquals(Duration.ofHours(1), config.reflection.minInterval)
        assertEquals(1_000, config.reflection.queryLimit)
        assertEquals(180, config.reflection.calibrationLookbackDays)
        assertEquals(50, config.reflection.recentDecisionLimit)
        assertEquals(30, config.reflection.sampleWarningTradeCount)
        assertEquals(LlmProvider.CLAUDE, config.reflection.promptCandidateProvider)
        assertEquals(Duration.ofSeconds(60), config.reflection.promptCandidateTimeout)
        assertEquals(2, config.reflection.promptCandidateMaxAttemptsPerPeriod)
        assertEquals(100, config.killCriterion.minClosedTrades)
        assertEquals(BigDecimal("0.8"), config.killCriterion.minProfitFactor)
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
                "FUKUROU_VOLATILITY_SLIPPAGE_MULTIPLIER" to "0.2",
                "FUKUROU_FALLBACK_MAKER_FEE_RATE" to "0.0000",
                "FUKUROU_FALLBACK_TAKER_FEE_RATE" to "0.0006",
                "FUKUROU_MAX_RISK_PER_TRADE_RATIO" to "0.015",
                "FUKUROU_MAX_DRAWDOWN_RATIO" to "-0.12",
                "FUKUROU_MAX_TOTAL_EXPOSURE_RATIO" to "0.70",
                "FUKUROU_MIN_EXPECTED_VALUE_R" to "0.12",
                "FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO" to "3.5",
                "FUKUROU_DATA_QUALITY_STALE_AFTER_SECONDS" to "30",
                "FUKUROU_DATA_QUALITY_CAPPED_PROBABILITY" to "0.4",
                "FUKUROU_MAX_TAKER_FEE_RATIO" to "0.0008",
                "FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS" to "8",
                "FUKUROU_FALSIFICATION_FRESHNESS_SECONDS" to "90",
                "FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS" to "600",
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
                "FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT" to "20",
                "FUKUROU_MCP_ACT_TOOL_CALL_LIMIT" to "2",
                "FUKUROU_LLM_RUN_TIMEOUT_SECONDS" to "120",
                "FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR" to "1",
                "FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY" to "3",
                "FUKUROU_CLAUDE_MODEL" to "claude-runtime-test",
                "FUKUROU_CODEX_MODEL" to "codex-runtime-test",
                "FUKUROU_LLM_DAEMON_ENABLED" to "true",
                "FUKUROU_LLM_DAEMON_POLL_SECONDS" to "120",
                "FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS" to "28800",
                "FUKUROU_LLM_HOLDING_CHECK_SECONDS" to "14400",
                "FUKUROU_LLM_TRIGGER_PRICE_MOVE_ENABLED" to "false",
                "FUKUROU_LLM_TRIGGER_PRICE_MOVE_WINDOW_SECONDS" to "600",
                "FUKUROU_LLM_TRIGGER_PRICE_MOVE_THRESHOLD_RATIO" to "0.02",
                "FUKUROU_LLM_TRIGGER_PRICE_MOVE_COOLDOWN_SECONDS" to "1200",
                "FUKUROU_LLM_TRIGGER_ENTRY_FILL_ENABLED" to "false",
                "FUKUROU_LLM_TRIGGER_ENTRY_FILL_COOLDOWN_SECONDS" to "1200",
                "FUKUROU_LLM_PRE_FILTER_ENABLED" to "true",
                "FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_ENABLED" to "false",
                "FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_REMAINING_R_THRESHOLD" to "0.4",
                "FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_COOLDOWN_SECONDS" to "1800",
                "FUKUROU_OBSIDIAN_ENABLED" to "true",
                "FUKUROU_OBSIDIAN_VAULT_PATH" to "/srv/fukurou/obsidian-vault",
                "FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS" to "600",
                "FUKUROU_REFLECTION_MIN_INTERVAL_SECONDS" to "7200",
                "FUKUROU_REFLECTION_QUERY_LIMIT" to "500",
                "FUKUROU_REFLECTION_CALIBRATION_LOOKBACK_DAYS" to "90",
                "FUKUROU_REFLECTION_RECENT_DECISION_LIMIT" to "25",
                "FUKUROU_REFLECTION_SAMPLE_WARNING_TRADE_COUNT" to "20",
                "FUKUROU_REFLECTION_PROMPT_CANDIDATE_PROVIDER" to "codex",
                "FUKUROU_REFLECTION_PROMPT_CANDIDATE_TIMEOUT_SECONDS" to "90",
                "FUKUROU_REFLECTION_PROMPT_CANDIDATE_MAX_ATTEMPTS" to "3",
                "FUKUROU_KILL_MIN_CLOSED_TRADES" to "50",
                "FUKUROU_KILL_MIN_PROFIT_FACTOR" to "0.9",
                "FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC" to
                    "fomc-20260729|FOMC|2026-07-29T18:00:00Z|60|90",
            ),
        )

        assertEquals(TradingSymbol.BTC, config.symbol)
        assertEquals(TradingMode.PAPER, config.mode)
        assertEquals(BigDecimal("250000"), config.paperAccount.initialCashJpy)
        assertEquals(BigDecimal("7"), config.paperExecution.marketSlippageBps)
        assertEquals(BigDecimal("0.2"), config.paperExecution.volatilitySlippageMultiplier)
        assertEquals(BigDecimal("0.0000"), config.paperMarket.fallbackMakerFeeRate)
        assertEquals(BigDecimal("0.0006"), config.paperMarket.fallbackTakerFeeRate)
        assertEquals(BigDecimal("0.015"), config.safetyFloor.maxRiskPerTradeRatio)
        assertEquals(BigDecimal("-0.12"), config.safetyFloor.maxDrawdownRatio)
        assertEquals(BigDecimal("0.70"), config.safetyFloor.maxTotalExposureRatio)
        assertEquals(BigDecimal("0.12"), config.safetyFloor.minExpectedValueR)
        assertEquals(BigDecimal("3.5"), config.safetyFloor.minExpectedMoveToCostRatio)
        assertEquals(Duration.ofSeconds(30), config.safetyFloor.dataQualityCap.staleAfter)
        assertEquals(BigDecimal("0.4"), config.safetyFloor.dataQualityCap.cappedProbability)
        assertEquals(BigDecimal("0.0008"), config.safetyFloor.maxTakerFeeRatio)
        assertEquals(BigDecimal("8"), config.safetyFloor.marketSlippageReserveBps)
        assertEquals(Duration.ofSeconds(90), config.decisionProtocol.falsificationFreshnessWindow)
        assertEquals(Duration.ofSeconds(600), config.decisionProtocol.restingEntryOrderTtl)
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
        assertEquals(20, config.runner.maxToolCallsPerRun)
        assertEquals(2, config.runner.maxActToolCallsPerRun)
        assertEquals(Duration.ofSeconds(120), config.runner.perRunTimeout)
        assertEquals(1, config.runner.maxInvocationsPerHour)
        assertEquals(3, config.runner.maxInvocationsPerDay)
        assertEquals("claude-runtime-test", config.llmModels.claudeModel)
        assertEquals("codex-runtime-test", config.llmModels.codexModel)
        assertEquals(true, config.daemon.enabled)
        assertEquals(Duration.ofSeconds(120), config.daemon.pollInterval)
        assertEquals(Duration.ofHours(8), config.daemon.flatHeartbeatInterval)
        assertEquals(Duration.ofHours(4), config.daemon.holdingCheckInterval)
        assertEquals(false, config.daemon.priceMoveTriggerEnabled)
        assertEquals(Duration.ofSeconds(600), config.daemon.priceMoveWindow)
        assertEquals(BigDecimal("0.02"), config.daemon.priceMoveThresholdRatio)
        assertEquals(Duration.ofSeconds(1200), config.daemon.priceMoveCooldown)
        assertEquals(false, config.daemon.entryFillTriggerEnabled)
        assertEquals(Duration.ofSeconds(1200), config.daemon.entryFillCooldown)
        assertEquals(true, config.daemon.preFilterEnabled)
        assertEquals(false, config.daemon.stopProximityTriggerEnabled)
        assertEquals(BigDecimal("0.4"), config.daemon.stopProximityRemainingRThreshold)
        assertEquals(Duration.ofSeconds(1800), config.daemon.stopProximityCooldown)
        assertEquals(true, config.obsidian.enabled)
        assertEquals("/srv/fukurou/obsidian-vault", config.obsidian.vaultPath)
        assertEquals(Duration.ofMinutes(10), config.obsidian.writeInterval)
        assertEquals(Duration.ofHours(2), config.reflection.minInterval)
        assertEquals(500, config.reflection.queryLimit)
        assertEquals(90, config.reflection.calibrationLookbackDays)
        assertEquals(25, config.reflection.recentDecisionLimit)
        assertEquals(20, config.reflection.sampleWarningTradeCount)
        assertEquals(LlmProvider.CODEX, config.reflection.promptCandidateProvider)
        assertEquals(Duration.ofSeconds(90), config.reflection.promptCandidateTimeout)
        assertEquals(3, config.reflection.promptCandidateMaxAttemptsPerPeriod)
        assertEquals(50, config.killCriterion.minClosedTrades)
        assertEquals(BigDecimal("0.9"), config.killCriterion.minProfitFactor)
        assertEquals(1, config.safetyFloor.economicEventBlackouts.size)
        assertEquals("fomc-20260729", config.safetyFloor.economicEventBlackouts.single().eventId)
    }

    @Test
    fun fromEnvironment_acceptsLlmRunTimeoutBoundaryOverrides() {
        val defaultTimeoutConfig = TradingBotConfig.fromEnvironment(
            mapOf(
                "FUKUROU_LLM_RUN_TIMEOUT_SECONDS" to "180",
            ),
        )
        val maxTimeoutConfig = TradingBotConfig.fromEnvironment(
            mapOf(
                "FUKUROU_LLM_RUN_TIMEOUT_SECONDS" to "600",
            ),
        )

        assertEquals(Duration.ofSeconds(180), defaultTimeoutConfig.runner.perRunTimeout)
        assertEquals(Duration.ofSeconds(600), maxTimeoutConfig.runner.perRunTimeout)
    }

    @Test
    fun staleLlmRunRecoveryThreshold_keepsReflectionTimeoutFloor() {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(perRunTimeout = Duration.ofSeconds(30)),
        )

        assertEquals(Duration.ofSeconds(360), config.staleLlmRunRecoveryThreshold())
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
                mapOf("FUKUROU_VOLATILITY_SLIPPAGE_MULTIPLIER" to "-0.1"),
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
                mapOf("FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO" to "2.4999"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_DATA_QUALITY_STALE_AFTER_SECONDS" to "61"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_DATA_QUALITY_CAPPED_PROBABILITY" to "0.6"),
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
                mapOf("FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS" to "1801"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS" to "0"),
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
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT" to "49"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_MCP_ACT_TOOL_CALL_LIMIT" to "4"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_RUN_TIMEOUT_SECONDS" to "601"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR" to "8"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY" to "121"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_DAEMON_POLL_SECONDS" to "30"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS" to "600"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_HOLDING_CHECK_SECONDS" to "600"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_TRIGGER_PRICE_MOVE_THRESHOLD_RATIO" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_TRIGGER_PRICE_MOVE_WINDOW_SECONDS" to "30"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_TRIGGER_PRICE_MOVE_COOLDOWN_SECONDS" to "30"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_TRIGGER_ENTRY_FILL_COOLDOWN_SECONDS" to "30"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_REMAINING_R_THRESHOLD" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_COOLDOWN_SECONDS" to "30"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS" to "30"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_REFLECTION_MIN_INTERVAL_SECONDS" to "30"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_REFLECTION_QUERY_LIMIT" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_REFLECTION_CALIBRATION_LOOKBACK_DAYS" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_REFLECTION_RECENT_DECISION_LIMIT" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_REFLECTION_SAMPLE_WARNING_TRADE_COUNT" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_REFLECTION_PROMPT_CANDIDATE_PROVIDER" to "unknown"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_REFLECTION_PROMPT_CANDIDATE_TIMEOUT_SECONDS" to "121"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_REFLECTION_PROMPT_CANDIDATE_MAX_ATTEMPTS" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_KILL_MIN_CLOSED_TRADES" to "101"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_KILL_MIN_PROFIT_FACTOR" to "0.7"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf("FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC" to "cpi|CPI|2026-07-29T18:00:00+09:00|60|60"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBotConfig.fromEnvironment(
                mapOf(
                    "FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT" to "1",
                    "FUKUROU_MCP_ACT_TOOL_CALL_LIMIT" to "2",
                ),
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
