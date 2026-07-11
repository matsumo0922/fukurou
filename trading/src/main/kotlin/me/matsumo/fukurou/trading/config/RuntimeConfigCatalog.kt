@file:Suppress("LargeClass", "LongMethod", "LongParameterList")

package me.matsumo.fukurou.trading.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.invoker.FUKUROU_CLAUDE_MODEL_ENV
import me.matsumo.fukurou.trading.invoker.FUKUROU_CODEX_MODEL_ENV
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.runner.FUKUROU_FALSIFIER_ALLOWED_TOOLS_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_JAR_PATH_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_SERVER_ARGS_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_SERVER_COMMAND_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_SERVER_NAME_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_PROPOSER_ALLOWED_TOOLS_ENV
import me.matsumo.fukurou.trading.runner.OneShotRunnerCliConfig
import me.matsumo.fukurou.trading.safety.EconomicEventBlackout

/**
 * catalog から明示的に退役し、bootstrap 時に旧 active snapshot から除去する runtime key。
 */
internal val retiredRuntimeConfigKeys = setOf(
    "obsidian.vaultPath",
    "gmoPublic.websocketStaleTimeout",
)

/**
 * runtime config の code-owned catalog を提供する。
 */
object RuntimeConfigCatalog {

    /**
     * runtime group の catalog item 一覧を返す。
     */
    fun runtimeItems(tradingConfig: TradingBotConfig = TradingBotConfig()): List<RuntimeConfigItem> {
        return runtimeGroup(
            config = tradingConfig,
            environment = emptyMap(),
            defaults = RuntimeConfigDefaults(),
        ).items
    }

    /**
     * runtime group の legacy env 名一覧を返す。
     */
    fun runtimeLegacyEnvNames(): Set<String> {
        return runtimeItems().map { item -> item.legacyEnvName }.toSet()
    }

    /**
     * deployment group の legacy env 名一覧を返す。
     */
    fun deploymentLegacyEnvNames(): Set<String> {
        val config = TradingBotConfig()
        val environment = emptyMap<String, String>()
        val defaults = RuntimeConfigDefaults()
        val deploymentValues = RuntimeConfigDeploymentValues.fromEnvironment(environment)

        return deploymentGroup(config, environment, defaults, deploymentValues)
            .items
            .map { item -> item.legacyEnvName }
            .toSet()
    }

    /**
     * code catalog default を runtime config key ごとに返す。
     */
    fun runtimeDefaultValues(): Map<String, String> {
        return runtimeItems().associate { item ->
            val defaultValue = requireNotNull(item.defaultValue) {
                "Runtime config default value must not be null: ${item.key}"
            }

            item.key to defaultValue
        }
    }

    /**
     * typed config と等価な runtime env map を返す。
     */
    fun runtimeEnvironment(tradingConfig: TradingBotConfig): Map<String, String> {
        return runtimeItems(tradingConfig).associate { item ->
            val effectiveValue = requireNotNull(item.effectiveValue) {
                "Runtime config effective value must not be null: ${item.key}"
            }

            item.legacyEnvName to effectiveValue
        }
    }

    /**
     * 実効設定 snapshot を作る。
     */
    fun snapshot(
        tradingConfig: TradingBotConfig,
        environment: Map<String, String> = System.getenv(),
    ): RuntimeConfigSnapshot {
        val defaults = RuntimeConfigDefaults()
        val deploymentValues = RuntimeConfigDeploymentValues.fromEnvironment(environment)

        return RuntimeConfigSnapshot(
            groups = listOf(
                runtimeGroup(tradingConfig, environment, defaults),
                deploymentGroup(tradingConfig, environment, defaults, deploymentValues),
                secretsGroup(environment),
            ),
        )
    }

    private fun runtimeGroup(
        config: TradingBotConfig,
        environment: Map<String, String>,
        defaults: RuntimeConfigDefaults,
    ): RuntimeConfigGroup {
        return RuntimeConfigGroup(
            id = "runtime",
            labelKey = "config.group.runtime.label",
            descriptionKey = "config.group.runtime.description",
            items = listOf(
                runtimeItem(
                    key = "paper.initialCashJpy",
                    legacyEnvName = FUKUROU_PAPER_INITIAL_CASH_JPY_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.paperAccount.initialCashJpy.toPlainString(),
                    effectiveValue = config.paperAccount.initialCashJpy.toPlainString(),
                    unit = "JPY",
                ),
                runtimeItem(
                    key = "paper.marketSlippageBps",
                    legacyEnvName = FUKUROU_MARKET_SLIPPAGE_BPS_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.paperExecution.marketSlippageBps.toPlainString(),
                    effectiveValue = config.paperExecution.marketSlippageBps.toPlainString(),
                    unit = "bps",
                ),
                runtimeItem(
                    key = "paper.volatilitySlippageMultiplier",
                    legacyEnvName = FUKUROU_VOLATILITY_SLIPPAGE_MULTIPLIER_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.paperExecution.volatilitySlippageMultiplier.toPlainString(),
                    effectiveValue = config.paperExecution.volatilitySlippageMultiplier.toPlainString(),
                    unit = null,
                ),
                runtimeItem(
                    key = "paper.fallbackMakerFeeRate",
                    legacyEnvName = FUKUROU_FALLBACK_MAKER_FEE_RATE_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.paperMarket.fallbackMakerFeeRate.toPlainString(),
                    effectiveValue = config.paperMarket.fallbackMakerFeeRate.toPlainString(),
                    unit = null,
                ),
                runtimeItem(
                    key = "paper.fallbackTakerFeeRate",
                    legacyEnvName = FUKUROU_FALLBACK_TAKER_FEE_RATE_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.paperMarket.fallbackTakerFeeRate.toPlainString(),
                    effectiveValue = config.paperMarket.fallbackTakerFeeRate.toPlainString(),
                    unit = null,
                ),
                runtimeItem(
                    key = "paper.fallbackSpreadBps",
                    legacyEnvName = FUKUROU_FALLBACK_SPREAD_BPS_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.paperMarket.fallbackSpreadBps.toPlainString(),
                    effectiveValue = config.paperMarket.fallbackSpreadBps.toPlainString(),
                    unit = "bps",
                ),
                runtimeItem(
                    key = "safety.maxRiskPerTradeRatio",
                    legacyEnvName = FUKUROU_MAX_RISK_PER_TRADE_RATIO_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.safetyFloor.maxRiskPerTradeRatio.toPlainString(),
                    effectiveValue = config.safetyFloor.maxRiskPerTradeRatio.toPlainString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "safety.maxDrawdownRatio",
                    legacyEnvName = FUKUROU_MAX_DRAWDOWN_RATIO_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.safetyFloor.maxDrawdownRatio.toPlainString(),
                    effectiveValue = config.safetyFloor.maxDrawdownRatio.toPlainString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "safety.maxTotalExposureRatio",
                    legacyEnvName = FUKUROU_MAX_TOTAL_EXPOSURE_RATIO_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.safetyFloor.maxTotalExposureRatio.toPlainString(),
                    effectiveValue = config.safetyFloor.maxTotalExposureRatio.toPlainString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "safety.minExpectedValueR",
                    legacyEnvName = FUKUROU_MIN_EXPECTED_VALUE_R_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.safetyFloor.minExpectedValueR.toPlainString(),
                    effectiveValue = config.safetyFloor.minExpectedValueR.toPlainString(),
                    unit = "R",
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "safety.minExpectedMoveToCostRatio",
                    legacyEnvName = FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.safetyFloor.minExpectedMoveToCostRatio.toPlainString(),
                    effectiveValue = config.safetyFloor.minExpectedMoveToCostRatio.toPlainString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "safety.maxTakerFeeRatio",
                    legacyEnvName = FUKUROU_MAX_TAKER_FEE_RATIO_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.safetyFloor.maxTakerFeeRatio.toPlainString(),
                    effectiveValue = config.safetyFloor.maxTakerFeeRatio.toPlainString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "safety.marketSlippageReserveBps",
                    legacyEnvName = FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.safetyFloor.marketSlippageReserveBps.toPlainString(),
                    effectiveValue = config.safetyFloor.marketSlippageReserveBps.toPlainString(),
                    unit = "bps",
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "safety.dataQualityStaleAfter",
                    legacyEnvName = FUKUROU_DATA_QUALITY_STALE_AFTER_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.safetyFloor.dataQualityCap.staleAfter.seconds.toString(),
                    effectiveValue = config.safetyFloor.dataQualityCap.staleAfter.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "safety.dataQualityCappedProbability",
                    legacyEnvName = FUKUROU_DATA_QUALITY_CAPPED_PROBABILITY_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.safetyFloor.dataQualityCap.cappedProbability.toPlainString(),
                    effectiveValue = config.safetyFloor.dataQualityCap.cappedProbability.toPlainString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "safety.economicEventBlackouts",
                    legacyEnvName = FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC_ENV,
                    valueType = RuntimeConfigValueType.STRUCTURED_JSON_LIST,
                    defaultValue = "[]",
                    effectiveValue = config.safetyFloor.economicEventBlackouts.toCatalogJson(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "decision.falsificationFreshnessWindow",
                    legacyEnvName = FUKUROU_FALSIFICATION_FRESHNESS_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.decisionProtocol.falsificationFreshnessWindow.seconds.toString(),
                    effectiveValue = config.decisionProtocol.falsificationFreshnessWindow.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "decision.restingEntryOrderTtl",
                    legacyEnvName = FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.decisionProtocol.restingEntryOrderTtl.seconds.toString(),
                    effectiveValue = config.decisionProtocol.restingEntryOrderTtl.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "runner.maxToolCallsPerRun",
                    legacyEnvName = FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.runner.maxToolCallsPerRun.toString(),
                    effectiveValue = config.runner.maxToolCallsPerRun.toString(),
                    unit = "calls",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "runner.maxActToolCallsPerRun",
                    legacyEnvName = FUKUROU_MCP_ACT_TOOL_CALL_LIMIT_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.runner.maxActToolCallsPerRun.toString(),
                    effectiveValue = config.runner.maxActToolCallsPerRun.toString(),
                    unit = "calls",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "runner.perRunTimeout",
                    legacyEnvName = FUKUROU_LLM_RUN_TIMEOUT_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.runner.perRunTimeout.seconds.toString(),
                    effectiveValue = config.runner.perRunTimeout.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "runner.maxInvocationsPerHour",
                    legacyEnvName = FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.runner.maxInvocationsPerHour.toString(),
                    effectiveValue = config.runner.maxInvocationsPerHour.toString(),
                    unit = "invocations",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "runner.maxInvocationsPerDay",
                    legacyEnvName = FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.runner.maxInvocationsPerDay.toString(),
                    effectiveValue = config.runner.maxInvocationsPerDay.toString(),
                    unit = "invocations",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "llm.proposer.provider",
                    legacyEnvName = FUKUROU_PROPOSER_PROVIDER_ENV,
                    valueType = RuntimeConfigValueType.ENUM,
                    defaultValue = defaults.config.llmRoleAssignments.proposer.provider.name,
                    effectiveValue = config.llmRoleAssignments.proposer.provider.name,
                    unit = null,
                    enumValues = LlmProvider.entries.map { provider -> provider.name },
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "llm.proposer.model",
                    legacyEnvName = FUKUROU_PROPOSER_MODEL_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = defaults.config.llmRoleAssignments.proposer.model.orEmpty(),
                    effectiveValue = config.llmRoleAssignments.proposer.model.orEmpty(),
                    unit = null,
                    blankAllowed = true,
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "llm.proposer.effort",
                    legacyEnvName = FUKUROU_PROPOSER_EFFORT_ENV,
                    valueType = RuntimeConfigValueType.ENUM,
                    defaultValue = defaults.config.llmRoleAssignments.proposer.effort.name,
                    effectiveValue = config.llmRoleAssignments.proposer.effort.name,
                    unit = null,
                    enumValues = LlmEffort.entries.map { effort -> effort.name },
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "llm.falsifier.provider",
                    legacyEnvName = FUKUROU_FALSIFIER_PROVIDER_ENV,
                    valueType = RuntimeConfigValueType.ENUM,
                    defaultValue = defaults.config.llmRoleAssignments.falsifier.provider.name,
                    effectiveValue = config.llmRoleAssignments.falsifier.provider.name,
                    unit = null,
                    enumValues = LlmProvider.entries.map { provider -> provider.name },
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "llm.falsifier.model",
                    legacyEnvName = FUKUROU_FALSIFIER_MODEL_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = defaults.config.llmRoleAssignments.falsifier.model.orEmpty(),
                    effectiveValue = config.llmRoleAssignments.falsifier.model.orEmpty(),
                    unit = null,
                    blankAllowed = true,
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "llm.falsifier.effort",
                    legacyEnvName = FUKUROU_FALSIFIER_EFFORT_ENV,
                    valueType = RuntimeConfigValueType.ENUM,
                    defaultValue = defaults.config.llmRoleAssignments.falsifier.effort.name,
                    effectiveValue = config.llmRoleAssignments.falsifier.effort.name,
                    unit = null,
                    enumValues = LlmEffort.entries.map { effort -> effort.name },
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "llm.claudeModel",
                    legacyEnvName = FUKUROU_CLAUDE_MODEL_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = defaults.config.llmModels.claudeModel.orEmpty(),
                    effectiveValue = config.llmModels.claudeModel.orEmpty(),
                    unit = null,
                    blankAllowed = true,
                ),
                runtimeItem(
                    key = "llm.codexModel",
                    legacyEnvName = FUKUROU_CODEX_MODEL_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = defaults.config.llmModels.codexModel.orEmpty(),
                    effectiveValue = config.llmModels.codexModel.orEmpty(),
                    unit = null,
                    blankAllowed = true,
                ),
                runtimeItem(
                    key = "daemon.enabled",
                    legacyEnvName = FUKUROU_LLM_DAEMON_ENABLED_ENV,
                    valueType = RuntimeConfigValueType.BOOLEAN,
                    defaultValue = defaults.config.daemon.enabled.toString(),
                    effectiveValue = config.daemon.enabled.toString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.pollInterval",
                    legacyEnvName = FUKUROU_LLM_DAEMON_POLL_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.daemon.pollInterval.seconds.toString(),
                    effectiveValue = config.daemon.pollInterval.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.flatHeartbeatInterval",
                    legacyEnvName = FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.daemon.flatHeartbeatInterval.seconds.toString(),
                    effectiveValue = config.daemon.flatHeartbeatInterval.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.holdingCheckInterval",
                    legacyEnvName = FUKUROU_LLM_HOLDING_CHECK_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.daemon.holdingCheckInterval.seconds.toString(),
                    effectiveValue = config.daemon.holdingCheckInterval.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.priceMoveTriggerEnabled",
                    legacyEnvName = FUKUROU_LLM_TRIGGER_PRICE_MOVE_ENABLED_ENV,
                    valueType = RuntimeConfigValueType.BOOLEAN,
                    defaultValue = defaults.config.daemon.priceMoveTriggerEnabled.toString(),
                    effectiveValue = config.daemon.priceMoveTriggerEnabled.toString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.priceMoveWindow",
                    legacyEnvName = FUKUROU_LLM_TRIGGER_PRICE_MOVE_WINDOW_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.daemon.priceMoveWindow.seconds.toString(),
                    effectiveValue = config.daemon.priceMoveWindow.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.priceMoveThresholdRatio",
                    legacyEnvName = FUKUROU_LLM_TRIGGER_PRICE_MOVE_THRESHOLD_RATIO_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.daemon.priceMoveThresholdRatio.toPlainString(),
                    effectiveValue = config.daemon.priceMoveThresholdRatio.toPlainString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.priceMoveCooldown",
                    legacyEnvName = FUKUROU_LLM_TRIGGER_PRICE_MOVE_COOLDOWN_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.daemon.priceMoveCooldown.seconds.toString(),
                    effectiveValue = config.daemon.priceMoveCooldown.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.entryFillTriggerEnabled",
                    legacyEnvName = FUKUROU_LLM_TRIGGER_ENTRY_FILL_ENABLED_ENV,
                    valueType = RuntimeConfigValueType.BOOLEAN,
                    defaultValue = defaults.config.daemon.entryFillTriggerEnabled.toString(),
                    effectiveValue = config.daemon.entryFillTriggerEnabled.toString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.entryFillCooldown",
                    legacyEnvName = FUKUROU_LLM_TRIGGER_ENTRY_FILL_COOLDOWN_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.daemon.entryFillCooldown.seconds.toString(),
                    effectiveValue = config.daemon.entryFillCooldown.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.preFilterEnabled",
                    legacyEnvName = FUKUROU_LLM_PRE_FILTER_ENABLED_ENV,
                    valueType = RuntimeConfigValueType.BOOLEAN,
                    defaultValue = defaults.config.daemon.preFilterEnabled.toString(),
                    effectiveValue = config.daemon.preFilterEnabled.toString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.stopProximityTriggerEnabled",
                    legacyEnvName = FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_ENABLED_ENV,
                    valueType = RuntimeConfigValueType.BOOLEAN,
                    defaultValue = defaults.config.daemon.stopProximityTriggerEnabled.toString(),
                    effectiveValue = config.daemon.stopProximityTriggerEnabled.toString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.stopProximityRemainingRThreshold",
                    legacyEnvName = FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_REMAINING_R_THRESHOLD_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.daemon.stopProximityRemainingRThreshold.toPlainString(),
                    effectiveValue = config.daemon.stopProximityRemainingRThreshold.toPlainString(),
                    unit = "R",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "daemon.stopProximityCooldown",
                    legacyEnvName = FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_COOLDOWN_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.daemon.stopProximityCooldown.seconds.toString(),
                    effectiveValue = config.daemon.stopProximityCooldown.seconds.toString(),
                    unit = "seconds",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "obsidian.enabled",
                    legacyEnvName = FUKUROU_OBSIDIAN_ENABLED_ENV,
                    valueType = RuntimeConfigValueType.BOOLEAN,
                    defaultValue = defaults.config.obsidian.enabled.toString(),
                    effectiveValue = config.obsidian.enabled.toString(),
                    unit = null,
                ),
                runtimeItem(
                    key = "obsidian.writeInterval",
                    legacyEnvName = FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.obsidian.writeInterval.seconds.toString(),
                    effectiveValue = config.obsidian.writeInterval.seconds.toString(),
                    unit = "seconds",
                ),
                runtimeItem(
                    key = "reflection.minInterval",
                    legacyEnvName = FUKUROU_REFLECTION_MIN_INTERVAL_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.reflection.minInterval.seconds.toString(),
                    effectiveValue = config.reflection.minInterval.seconds.toString(),
                    unit = "seconds",
                ),
                runtimeItem(
                    key = "reflection.queryLimit",
                    legacyEnvName = FUKUROU_REFLECTION_QUERY_LIMIT_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.reflection.queryLimit.toString(),
                    effectiveValue = config.reflection.queryLimit.toString(),
                    unit = "rows",
                ),
                runtimeItem(
                    key = "reflection.calibrationLookbackDays",
                    legacyEnvName = FUKUROU_REFLECTION_CALIBRATION_LOOKBACK_DAYS_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.reflection.calibrationLookbackDays.toString(),
                    effectiveValue = config.reflection.calibrationLookbackDays.toString(),
                    unit = "days",
                ),
                runtimeItem(
                    key = "reflection.recentDecisionLimit",
                    legacyEnvName = FUKUROU_REFLECTION_RECENT_DECISION_LIMIT_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.reflection.recentDecisionLimit.toString(),
                    effectiveValue = config.reflection.recentDecisionLimit.toString(),
                    unit = "decisions",
                ),
                runtimeItem(
                    key = "reflection.sampleWarningTradeCount",
                    legacyEnvName = FUKUROU_REFLECTION_SAMPLE_WARNING_TRADE_COUNT_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.reflection.sampleWarningTradeCount.toString(),
                    effectiveValue = config.reflection.sampleWarningTradeCount.toString(),
                    unit = "trades",
                ),
                runtimeItem(
                    key = "reflection.promptCandidateProvider",
                    legacyEnvName = FUKUROU_REFLECTION_PROMPT_CANDIDATE_PROVIDER_ENV,
                    valueType = RuntimeConfigValueType.ENUM,
                    defaultValue = defaults.config.reflection.promptCandidateProvider.name,
                    effectiveValue = config.reflection.promptCandidateProvider.name,
                    unit = null,
                ),
                runtimeItem(
                    key = "reflection.promptCandidateTimeout",
                    legacyEnvName = FUKUROU_REFLECTION_PROMPT_CANDIDATE_TIMEOUT_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.reflection.promptCandidateTimeout.seconds.toString(),
                    effectiveValue = config.reflection.promptCandidateTimeout.seconds.toString(),
                    unit = "seconds",
                ),
                runtimeItem(
                    key = "reflection.promptCandidateMaxAttempts",
                    legacyEnvName = FUKUROU_REFLECTION_PROMPT_CANDIDATE_MAX_ATTEMPTS_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.reflection.promptCandidateMaxAttemptsPerPeriod.toString(),
                    effectiveValue = config.reflection.promptCandidateMaxAttemptsPerPeriod.toString(),
                    unit = "attempts",
                ),
                runtimeItem(
                    key = "killCriterion.minClosedTrades",
                    legacyEnvName = FUKUROU_KILL_MIN_CLOSED_TRADES_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.killCriterion.minClosedTrades.toString(),
                    effectiveValue = config.killCriterion.minClosedTrades.toString(),
                    unit = "trades",
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "killCriterion.minProfitFactor",
                    legacyEnvName = FUKUROU_KILL_MIN_PROFIT_FACTOR_ENV,
                    valueType = RuntimeConfigValueType.DECIMAL_STRING,
                    defaultValue = defaults.config.killCriterion.minProfitFactor.toPlainString(),
                    effectiveValue = config.killCriterion.minProfitFactor.toPlainString(),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                runtimeItem(
                    key = "gmoPublic.restPerSecond",
                    legacyEnvName = FUKUROU_GMO_PUBLIC_REST_PER_SECOND_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicClient.rateLimit.permitsPerSecond.toString(),
                    effectiveValue = config.gmoPublicClient.rateLimit.permitsPerSecond.toString(),
                    unit = "requests/second",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "gmoPublic.restBurst",
                    legacyEnvName = FUKUROU_GMO_PUBLIC_REST_BURST_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicClient.rateLimit.burstSize.toString(),
                    effectiveValue = config.gmoPublicClient.rateLimit.burstSize.toString(),
                    unit = "requests",
                    safetyTier = RuntimeConfigSafetyTier.GUARDED,
                ),
                runtimeItem(
                    key = "gmoPublic.retryMaxAttempts",
                    legacyEnvName = FUKUROU_GMO_RETRY_MAX_ATTEMPTS_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicClient.retry.maxAttempts.toString(),
                    effectiveValue = config.gmoPublicClient.retry.maxAttempts.toString(),
                    unit = "attempts",
                ),
                runtimeItem(
                    key = "gmoPublic.retryInitialBackoff",
                    legacyEnvName = FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicClient.retry.initialBackoff.toMillis().toString(),
                    effectiveValue = config.gmoPublicClient.retry.initialBackoff.toMillis().toString(),
                    unit = "milliseconds",
                ),
                runtimeItem(
                    key = "gmoPublic.retryMaxBackoff",
                    legacyEnvName = FUKUROU_GMO_RETRY_MAX_BACKOFF_MS_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicClient.retry.maxBackoff.toMillis().toString(),
                    effectiveValue = config.gmoPublicClient.retry.maxBackoff.toMillis().toString(),
                    unit = "milliseconds",
                ),
                runtimeItem(
                    key = "gmoPublic.retryBackoffMultiplier",
                    legacyEnvName = FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicClient.retry.backoffMultiplier.toString(),
                    effectiveValue = config.gmoPublicClient.retry.backoffMultiplier.toString(),
                    unit = null,
                ),
                runtimeItem(
                    key = "gmoPublic.connectTimeout",
                    legacyEnvName = FUKUROU_GMO_CONNECT_TIMEOUT_MS_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicClient.connectTimeout.toMillis().toString(),
                    effectiveValue = config.gmoPublicClient.connectTimeout.toMillis().toString(),
                    unit = "milliseconds",
                ),
                runtimeItem(
                    key = "gmoPublic.requestTimeout",
                    legacyEnvName = FUKUROU_GMO_REQUEST_TIMEOUT_MS_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicClient.requestTimeout.toMillis().toString(),
                    effectiveValue = config.gmoPublicClient.requestTimeout.toMillis().toString(),
                    unit = "milliseconds",
                ),
                runtimeItem(
                    key = "gmoPublic.symbolRulesCacheTtl",
                    legacyEnvName = FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.gmoPublicClient.symbolRulesCacheTtl.seconds.toString(),
                    effectiveValue = config.gmoPublicClient.symbolRulesCacheTtl.seconds.toString(),
                    unit = "seconds",
                ),
                runtimeItem(
                    key = "gmoPublic.websocketConnectTimeout",
                    legacyEnvName = FUKUROU_GMO_WEBSOCKET_CONNECT_TIMEOUT_MS_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicWebSocket.connectTimeout.toMillis().toString(),
                    effectiveValue = config.gmoPublicWebSocket.connectTimeout.toMillis().toString(),
                    unit = "milliseconds",
                ),
                runtimeItem(
                    key = "gmoPublic.websocketTransportLivenessTimeout",
                    legacyEnvName = FUKUROU_GMO_WEBSOCKET_TRANSPORT_LIVENESS_TIMEOUT_SECONDS_ENV,
                    valueType = RuntimeConfigValueType.DURATION_SECONDS,
                    defaultValue = defaults.config.gmoPublicWebSocket.transportLivenessTimeout.seconds.toString(),
                    effectiveValue = config.gmoPublicWebSocket.transportLivenessTimeout.seconds.toString(),
                    unit = "seconds",
                ),
                runtimeItem(
                    key = "gmoPublic.websocketReconnectBackoff",
                    legacyEnvName = FUKUROU_GMO_WEBSOCKET_RECONNECT_BACKOFF_MS_ENV,
                    valueType = RuntimeConfigValueType.INT,
                    defaultValue = defaults.config.gmoPublicWebSocket.reconnectBackoff.toMillis().toString(),
                    effectiveValue = config.gmoPublicWebSocket.reconnectBackoff.toMillis().toString(),
                    unit = "milliseconds",
                ),
            ).map { item -> item.withCurrentValue(environment) },
        )
    }

    private fun deploymentGroup(
        config: TradingBotConfig,
        environment: Map<String, String>,
        defaults: RuntimeConfigDefaults,
        deploymentValues: RuntimeConfigDeploymentValues,
    ): RuntimeConfigGroup {
        return RuntimeConfigGroup(
            id = "deployment",
            labelKey = "config.group.deployment.label",
            descriptionKey = "config.group.deployment.description",
            items = listOf(
                deploymentItem(
                    key = "trading.symbol",
                    legacyEnvName = FUKUROU_TRADING_SYMBOL_ENV,
                    valueType = RuntimeConfigValueType.ENUM,
                    defaultValue = defaults.config.symbol.apiSymbol,
                    effectiveValue = config.symbol.apiSymbol,
                    unit = null,
                ),
                deploymentItem(
                    key = "trading.mode",
                    legacyEnvName = FUKUROU_TRADING_MODE_ENV,
                    valueType = RuntimeConfigValueType.ENUM,
                    defaultValue = defaults.config.mode.name,
                    effectiveValue = config.mode.name,
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                deploymentItem(
                    key = "gmoPublic.baseUrl",
                    legacyEnvName = FUKUROU_GMO_PUBLIC_BASE_URL_ENV,
                    valueType = RuntimeConfigValueType.URL,
                    defaultValue = defaults.config.gmoPublicClient.baseUrl,
                    effectiveValue = config.gmoPublicClient.baseUrl,
                    unit = null,
                ),
                deploymentItem(
                    key = "gmoPublic.websocketUrl",
                    legacyEnvName = FUKUROU_GMO_PUBLIC_WEBSOCKET_URL_ENV,
                    valueType = RuntimeConfigValueType.URL,
                    defaultValue = defaults.config.gmoPublicWebSocket.endpoint,
                    effectiveValue = config.gmoPublicWebSocket.endpoint,
                    unit = null,
                ),
                deploymentItem(
                    key = "obsidian.vaultPath",
                    legacyEnvName = FUKUROU_OBSIDIAN_VAULT_PATH_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = defaults.config.obsidian.vaultPath,
                    effectiveValue = config.obsidian.vaultPath,
                    unit = null,
                ),
                deploymentItem(
                    key = "runner.repositoryRoot",
                    legacyEnvName = FUKUROU_REPOSITORY_ROOT_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = ".",
                    effectiveValue = deploymentValues.repositoryRoot,
                    unit = null,
                ),
                deploymentItem(
                    key = "runner.workingDirectory",
                    legacyEnvName = FUKUROU_LLM_WORKING_DIRECTORY_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = ".",
                    effectiveValue = deploymentValues.workingDirectory,
                    unit = null,
                ),
                deploymentItem(
                    key = "runner.mcpJarPath",
                    legacyEnvName = FUKUROU_MCP_JAR_PATH_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = "mcp/build/libs/fukurou-mcp-all.jar",
                    effectiveValue = deploymentValues.mcpJarPath,
                    unit = null,
                ),
                deploymentItem(
                    key = "runner.mcpServerName",
                    legacyEnvName = FUKUROU_MCP_SERVER_NAME_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = defaults.cliConfig.mcpServerName,
                    effectiveValue = deploymentValues.cliConfig.mcpServerName,
                    unit = null,
                ),
                deploymentItem(
                    key = "runner.mcpServerCommand",
                    legacyEnvName = FUKUROU_MCP_SERVER_COMMAND_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = defaults.cliConfig.mcpServerCommand,
                    effectiveValue = deploymentValues.cliConfig.mcpServerCommand,
                    unit = null,
                ),
                deploymentItem(
                    key = "runner.mcpServerArgs",
                    legacyEnvName = FUKUROU_MCP_SERVER_ARGS_ENV,
                    valueType = RuntimeConfigValueType.STRING_LIST,
                    defaultValue = null,
                    effectiveValue = deploymentValues.cliConfig.mcpServerArgs?.joinToString(" "),
                    unit = null,
                ),
                deploymentItem(
                    key = "runner.proposerAllowedTools",
                    legacyEnvName = FUKUROU_PROPOSER_ALLOWED_TOOLS_ENV,
                    valueType = RuntimeConfigValueType.STRING_LIST,
                    defaultValue = defaults.cliConfig.proposerAllowedTools.joinToString(","),
                    effectiveValue = deploymentValues.cliConfig.proposerAllowedTools.joinToString(","),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                deploymentItem(
                    key = "runner.falsifierAllowedTools",
                    legacyEnvName = FUKUROU_FALSIFIER_ALLOWED_TOOLS_ENV,
                    valueType = RuntimeConfigValueType.STRING_LIST,
                    defaultValue = defaults.cliConfig.falsifierAllowedTools.joinToString(","),
                    effectiveValue = deploymentValues.cliConfig.falsifierAllowedTools.joinToString(","),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                deploymentItem(
                    key = "llm.claudeCommandTemplate",
                    legacyEnvName = FUKUROU_CLAUDE_COMMAND_TEMPLATE_ENV,
                    valueType = RuntimeConfigValueType.STRING_LIST,
                    defaultValue = defaults.rendererConfig.claudeCommandTemplate.joinToString(" "),
                    effectiveValue = deploymentValues.rendererConfig.claudeCommandTemplate.joinToString(" "),
                    unit = null,
                ),
                deploymentItem(
                    key = "llm.codexCommandTemplate",
                    legacyEnvName = FUKUROU_CODEX_COMMAND_TEMPLATE_ENV,
                    valueType = RuntimeConfigValueType.STRING_LIST,
                    defaultValue = defaults.rendererConfig.codexCommandTemplate.joinToString(" "),
                    effectiveValue = deploymentValues.rendererConfig.codexCommandTemplate.joinToString(" "),
                    unit = null,
                ),
                deploymentItem(
                    key = "llm.claudeCommonArgs",
                    legacyEnvName = FUKUROU_CLAUDE_COMMON_ARGS_ENV,
                    valueType = RuntimeConfigValueType.STRING_LIST,
                    defaultValue = "",
                    effectiveValue = deploymentValues.rendererConfig.claudeCommonArgs.joinToString(" "),
                    unit = null,
                ),
                deploymentItem(
                    key = "llm.codexCommonArgs",
                    legacyEnvName = FUKUROU_CODEX_COMMON_ARGS_ENV,
                    valueType = RuntimeConfigValueType.STRING_LIST,
                    defaultValue = "",
                    effectiveValue = deploymentValues.rendererConfig.codexCommonArgs.joinToString(" "),
                    unit = null,
                ),
                deploymentItem(
                    key = "llm.codexFalsifierArgs",
                    legacyEnvName = FUKUROU_CODEX_FALSIFIER_ARGS_ENV,
                    valueType = RuntimeConfigValueType.STRING_LIST,
                    defaultValue = "",
                    effectiveValue = deploymentValues.rendererConfig.codexFalsifierArgs.joinToString(" "),
                    unit = null,
                    safetyTier = RuntimeConfigSafetyTier.SAFETY_CRITICAL,
                ),
                deploymentItem(
                    key = "llm.codexPersistentHome",
                    legacyEnvName = FUKUROU_CODEX_PERSISTENT_HOME_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = null,
                    effectiveValue = deploymentValues.rendererConfig.codexPersistentHome?.toString(),
                    unit = null,
                ),
                deploymentItem(
                    key = "app.revision",
                    legacyEnvName = FUKUROU_REVISION_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = null,
                    effectiveValue = environment.readOptional(FUKUROU_REVISION_ENV),
                    unit = null,
                ),
                deploymentItem(
                    key = "app.webRoot",
                    legacyEnvName = FUKUROU_WEB_ROOT_ENV,
                    valueType = RuntimeConfigValueType.STRING,
                    defaultValue = null,
                    effectiveValue = environment.readOptional(FUKUROU_WEB_ROOT_ENV),
                    unit = null,
                ),
                deploymentItem(
                    key = "app.prodBaseUrl",
                    legacyEnvName = FUKUROU_PROD_BASE_URL_ENV,
                    valueType = RuntimeConfigValueType.URL,
                    defaultValue = null,
                    effectiveValue = environment.readOptional(FUKUROU_PROD_BASE_URL_ENV),
                    unit = null,
                ),
            ).map { item -> item.withCurrentValue(environment) },
        )
    }

    private fun secretsGroup(environment: Map<String, String>): RuntimeConfigGroup {
        return RuntimeConfigGroup(
            id = "secrets",
            labelKey = "config.group.secrets.label",
            descriptionKey = "config.group.secrets.description",
            items = listOf(
                secretItem("database.password", "DB_PASSWORD"),
                secretItem("postgres.password", "POSTGRES_PASSWORD"),
                secretItem("cloudflare.tunnelToken", "CLOUDFLARED_TUNNEL_TOKEN"),
                secretItem("cloudflare.accessClientId", "CLOUDFLARED_TUNNEL_CLIENT_ID"),
                secretItem("cloudflare.accessClientSecret", "CLOUDFLARED_TUNNEL_CLIENT_SECRET"),
            ).map { item -> item.withSecretStatus(environment) },
        )
    }

    private fun runtimeItem(
        key: String,
        legacyEnvName: String,
        valueType: RuntimeConfigValueType,
        defaultValue: String?,
        effectiveValue: String?,
        unit: String?,
        safetyTier: RuntimeConfigSafetyTier = RuntimeConfigSafetyTier.STANDARD,
        blankAllowed: Boolean = false,
        enumValues: List<String> = emptyList(),
    ): RuntimeConfigItem {
        return configItem(
            key = key,
            sourceKind = RuntimeConfigSourceKind.RUNTIME,
            valueType = valueType,
            defaultValue = defaultValue,
            effectiveValue = effectiveValue,
            unit = unit,
            legacyEnvName = legacyEnvName,
            safetyTier = safetyTier,
            editable = true,
            applyMode = RuntimeConfigApplyMode.NEXT_RESTART,
            blankAllowed = blankAllowed,
            enumValues = enumValues,
        )
    }

    private fun deploymentItem(
        key: String,
        legacyEnvName: String,
        valueType: RuntimeConfigValueType,
        defaultValue: String?,
        effectiveValue: String?,
        unit: String?,
        safetyTier: RuntimeConfigSafetyTier = RuntimeConfigSafetyTier.DEPLOYMENT_BOUNDARY,
    ): RuntimeConfigItem {
        return configItem(
            key = key,
            sourceKind = RuntimeConfigSourceKind.DEPLOYMENT,
            valueType = valueType,
            defaultValue = defaultValue,
            effectiveValue = effectiveValue,
            unit = unit,
            legacyEnvName = legacyEnvName,
            safetyTier = safetyTier,
            editable = false,
            applyMode = RuntimeConfigApplyMode.PROCESS_RESTART,
            blankAllowed = false,
        )
    }

    private fun secretItem(key: String, legacyEnvName: String): RuntimeConfigItem {
        return RuntimeConfigItem(
            key = key,
            sourceKind = RuntimeConfigSourceKind.SECRET,
            valueType = RuntimeConfigValueType.SECRET_STATUS,
            defaultValue = null,
            currentValue = null,
            effectiveValue = null,
            unit = null,
            valueConfigured = false,
            legacyEnvName = legacyEnvName,
            editable = false,
            applyMode = RuntimeConfigApplyMode.PROCESS_RESTART,
            safetyTier = RuntimeConfigSafetyTier.SECRET,
            labelKey = "config.item.$key.label",
            descriptionKey = "config.item.$key.description",
        )
    }

    private fun configItem(
        key: String,
        sourceKind: RuntimeConfigSourceKind,
        valueType: RuntimeConfigValueType,
        defaultValue: String?,
        effectiveValue: String?,
        unit: String?,
        legacyEnvName: String,
        safetyTier: RuntimeConfigSafetyTier,
        editable: Boolean,
        applyMode: RuntimeConfigApplyMode,
        blankAllowed: Boolean,
        enumValues: List<String> = emptyList(),
    ): RuntimeConfigItem {
        return RuntimeConfigItem(
            key = key,
            sourceKind = sourceKind,
            valueType = valueType,
            defaultValue = defaultValue,
            currentValue = null,
            effectiveValue = effectiveValue,
            unit = unit,
            valueConfigured = effectiveValue != null,
            legacyEnvName = legacyEnvName,
            editable = editable,
            applyMode = applyMode,
            safetyTier = safetyTier,
            labelKey = "config.item.$key.label",
            descriptionKey = "config.item.$key.description",
            blankAllowed = blankAllowed,
            enumValues = enumValues,
        )
    }
}

/**
 * runtime config catalog API の snapshot。
 *
 * @param groups 設定 group 一覧
 * @param activeVersion active runtime config version
 * @param versions runtime config version 履歴
 * @param warnings catalog 表示は継続できるが運用者確認が必要な warning
 */
@Serializable
data class RuntimeConfigSnapshot(
    val groups: List<RuntimeConfigGroup>,
    val activeVersion: RuntimeConfigVersionSummary? = null,
    val versions: List<RuntimeConfigVersionSummary> = emptyList(),
    val warnings: List<RuntimeConfigSnapshotWarning> = emptyList(),
)

/**
 * runtime config snapshot の warning。
 *
 * @param code WebUI i18n 用の machine-readable code
 * @param validation runtime config validation に由来する warning の詳細
 */
@Serializable
data class RuntimeConfigSnapshotWarning(
    val code: String,
    val validation: RuntimeConfigValidationResult? = null,
)

/**
 * runtime config catalog の group。
 *
 * @param id group ID
 * @param labelKey UI label の i18n key
 * @param descriptionKey UI description の i18n key
 * @param items group 内の設定項目
 */
@Serializable
data class RuntimeConfigGroup(
    val id: String,
    val labelKey: String,
    val descriptionKey: String,
    val items: List<RuntimeConfigItem>,
)

/**
 * runtime config catalog の項目。
 *
 * @param key code-owned config key
 * @param sourceKind 設定値の管理種別
 * @param valueType 値の型
 * @param defaultValue 安全に表示できる既定値
 * @param currentValue 現在の env override 値。未設定または secret の場合は null
 * @param effectiveValue 実効値。secret の場合は null
 * @param unit 値の単位
 * @param valueConfigured secret や optional 値が設定済みかどうか
 * @param legacyEnvName 既存 env 名
 * @param editable WebUI から編集可能かどうか
 * @param applyMode 反映に必要な適用 mode
 * @param safetyTier 安全上の分類
 * @param labelKey UI label の i18n key
 * @param descriptionKey UI description の i18n key
 * @param blankAllowed runtime candidate に空文字を保存できるか
 * @param enumValues ENUM で選択可能な code-owned value 一覧
 */
@Serializable
data class RuntimeConfigItem(
    val key: String,
    val sourceKind: RuntimeConfigSourceKind,
    val valueType: RuntimeConfigValueType,
    val defaultValue: String?,
    val currentValue: String?,
    val effectiveValue: String?,
    val unit: String?,
    val valueConfigured: Boolean,
    val legacyEnvName: String,
    val editable: Boolean,
    val applyMode: RuntimeConfigApplyMode,
    val safetyTier: RuntimeConfigSafetyTier,
    val labelKey: String,
    val descriptionKey: String,
    @Transient
    val blankAllowed: Boolean = false,
    val enumValues: List<String> = emptyList(),
)

/**
 * runtime config の管理種別。
 */
@Serializable
enum class RuntimeConfigSourceKind {
    /** runtime config として扱う値。 */
    RUNTIME,

    /** deploy 境界で固定する値。 */
    DEPLOYMENT,

    /** 値を返さず設定有無だけ返す secret。 */
    SECRET,
}

/**
 * runtime config catalog が表現する値の型。
 */
@Serializable
enum class RuntimeConfigValueType {
    /** boolean 値。 */
    BOOLEAN,

    /** enum 値。 */
    ENUM,

    /** 文字列。 */
    STRING,

    /** URL 文字列。 */
    URL,

    /** integer 値。 */
    INT,

    /** 秒単位 duration。 */
    DURATION_SECONDS,

    /** 精度を保つ decimal 文字列。 */
    DECIMAL_STRING,

    /** 文字列 list。 */
    STRING_LIST,

    /** JSON object の list。 */
    STRUCTURED_JSON_LIST,

    /** secret の configured / missing 状態。 */
    SECRET_STATUS,
}

/**
 * 設定変更の反映 mode。
 */
@Serializable
enum class RuntimeConfigApplyMode {
    /** 次回 runtime 起動で反映する。 */
    NEXT_RESTART,

    /** Ktor process の再起動で反映する。 */
    PROCESS_RESTART,
}

/**
 * runtime config の安全分類。
 */
@Serializable
enum class RuntimeConfigSafetyTier {
    /** 標準的な runtime 設定。 */
    STANDARD,

    /** 保守側 validation を持つ設定。 */
    GUARDED,

    /** 安全床や取引停止に関わる重要設定。 */
    SAFETY_CRITICAL,

    /** deploy 境界で固定する設定。 */
    DEPLOYMENT_BOUNDARY,

    /** 値を露出しない secret。 */
    SECRET,
}

private data class RuntimeConfigDefaults(
    val config: TradingBotConfig = TradingBotConfig(),
    val cliConfig: OneShotRunnerCliConfig = OneShotRunnerCliConfig(),
    val rendererConfig: LlmCommandRendererConfig = LlmCommandRendererConfig(),
)

private data class RuntimeConfigDeploymentValues(
    val repositoryRoot: String,
    val workingDirectory: String,
    val mcpJarPath: String,
    val cliConfig: OneShotRunnerCliConfig,
    val rendererConfig: LlmCommandRendererConfig,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): RuntimeConfigDeploymentValues {
            return RuntimeConfigDeploymentValues(
                repositoryRoot = environment.readOptional(FUKUROU_REPOSITORY_ROOT_ENV) ?: ".",
                workingDirectory = environment.readOptional(FUKUROU_LLM_WORKING_DIRECTORY_ENV) ?: ".",
                mcpJarPath = environment.readOptional(FUKUROU_MCP_JAR_PATH_ENV) ?: "mcp/build/libs/fukurou-mcp-all.jar",
                cliConfig = OneShotRunnerCliConfig.fromEnvironment(environment),
                rendererConfig = LlmCommandRendererConfig.fromEnvironment(environment),
            )
        }
    }
}

private fun RuntimeConfigItem.withCurrentValue(environment: Map<String, String>): RuntimeConfigItem {
    val currentValue = environment.readOptional(legacyEnvName)

    return copy(
        currentValue = currentValue,
        valueConfigured = effectiveValue != null,
    )
}

private fun RuntimeConfigItem.withSecretStatus(environment: Map<String, String>): RuntimeConfigItem {
    return copy(valueConfigured = environment.readOptional(legacyEnvName) != null)
}

private fun Map<String, String>.readOptional(name: String): String? {
    return this[name]
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
}

private fun List<EconomicEventBlackout>.toCatalogJson(): String {
    val payload = buildJsonArray {
        forEach { blackout ->
            add(
                buildJsonObject {
                    put("eventId", blackout.eventId)
                    put("eventName", blackout.eventName)
                    put("eventAt", blackout.eventAt.toString())
                    put("blackoutBeforeSeconds", blackout.blackoutBefore.seconds)
                    put("blackoutAfterSeconds", blackout.blackoutAfter.seconds)
                },
            )
        }
    }

    return Json.encodeToString(payload)
}

private const val FUKUROU_TRADING_SYMBOL_ENV = "FUKUROU_TRADING_SYMBOL"
private const val FUKUROU_TRADING_MODE_ENV = "FUKUROU_TRADING_MODE"
private const val FUKUROU_PAPER_INITIAL_CASH_JPY_ENV = "FUKUROU_PAPER_INITIAL_CASH_JPY"
private const val FUKUROU_MARKET_SLIPPAGE_BPS_ENV = "FUKUROU_MARKET_SLIPPAGE_BPS"
private const val FUKUROU_VOLATILITY_SLIPPAGE_MULTIPLIER_ENV = "FUKUROU_VOLATILITY_SLIPPAGE_MULTIPLIER"
private const val FUKUROU_FALLBACK_MAKER_FEE_RATE_ENV = "FUKUROU_FALLBACK_MAKER_FEE_RATE"
private const val FUKUROU_FALLBACK_TAKER_FEE_RATE_ENV = "FUKUROU_FALLBACK_TAKER_FEE_RATE"
private const val FUKUROU_FALLBACK_SPREAD_BPS_ENV = "FUKUROU_FALLBACK_SPREAD_BPS"
private const val FUKUROU_MAX_RISK_PER_TRADE_RATIO_ENV = "FUKUROU_MAX_RISK_PER_TRADE_RATIO"
private const val FUKUROU_MAX_DRAWDOWN_RATIO_ENV = "FUKUROU_MAX_DRAWDOWN_RATIO"
private const val FUKUROU_MAX_TOTAL_EXPOSURE_RATIO_ENV = "FUKUROU_MAX_TOTAL_EXPOSURE_RATIO"
private const val FUKUROU_MIN_EXPECTED_VALUE_R_ENV = "FUKUROU_MIN_EXPECTED_VALUE_R"
private const val FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO_ENV = "FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO"
private const val FUKUROU_MAX_TAKER_FEE_RATIO_ENV = "FUKUROU_MAX_TAKER_FEE_RATIO"
private const val FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC_ENV = "FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC"
private const val FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS_ENV = "FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS"
private const val FUKUROU_DATA_QUALITY_STALE_AFTER_SECONDS_ENV = "FUKUROU_DATA_QUALITY_STALE_AFTER_SECONDS"
private const val FUKUROU_DATA_QUALITY_CAPPED_PROBABILITY_ENV = "FUKUROU_DATA_QUALITY_CAPPED_PROBABILITY"
private const val FUKUROU_FALSIFICATION_FRESHNESS_SECONDS_ENV = "FUKUROU_FALSIFICATION_FRESHNESS_SECONDS"
private const val FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS_ENV = "FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS"
private const val FUKUROU_LLM_RUN_TIMEOUT_SECONDS_ENV = "FUKUROU_LLM_RUN_TIMEOUT_SECONDS"
private const val FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR_ENV = "FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR"
private const val FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY_ENV = "FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY"
private const val FUKUROU_LLM_DAEMON_ENABLED_ENV = "FUKUROU_LLM_DAEMON_ENABLED"
private const val FUKUROU_LLM_DAEMON_POLL_SECONDS_ENV = "FUKUROU_LLM_DAEMON_POLL_SECONDS"
private const val FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS_ENV = "FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS"
private const val FUKUROU_LLM_HOLDING_CHECK_SECONDS_ENV = "FUKUROU_LLM_HOLDING_CHECK_SECONDS"
private const val FUKUROU_LLM_TRIGGER_PRICE_MOVE_ENABLED_ENV = "FUKUROU_LLM_TRIGGER_PRICE_MOVE_ENABLED"
private const val FUKUROU_LLM_TRIGGER_PRICE_MOVE_WINDOW_SECONDS_ENV = "FUKUROU_LLM_TRIGGER_PRICE_MOVE_WINDOW_SECONDS"
private const val FUKUROU_LLM_TRIGGER_PRICE_MOVE_THRESHOLD_RATIO_ENV = "FUKUROU_LLM_TRIGGER_PRICE_MOVE_THRESHOLD_RATIO"
private const val FUKUROU_LLM_TRIGGER_PRICE_MOVE_COOLDOWN_SECONDS_ENV = "FUKUROU_LLM_TRIGGER_PRICE_MOVE_COOLDOWN_SECONDS"
private const val FUKUROU_LLM_TRIGGER_ENTRY_FILL_ENABLED_ENV = "FUKUROU_LLM_TRIGGER_ENTRY_FILL_ENABLED"
private const val FUKUROU_LLM_TRIGGER_ENTRY_FILL_COOLDOWN_SECONDS_ENV =
    "FUKUROU_LLM_TRIGGER_ENTRY_FILL_COOLDOWN_SECONDS"
private const val FUKUROU_LLM_PRE_FILTER_ENABLED_ENV = "FUKUROU_LLM_PRE_FILTER_ENABLED"
private const val FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_ENABLED_ENV = "FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_ENABLED"
private const val FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_REMAINING_R_THRESHOLD_ENV =
    "FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_REMAINING_R_THRESHOLD"
private const val FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_COOLDOWN_SECONDS_ENV =
    "FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_COOLDOWN_SECONDS"
private const val FUKUROU_OBSIDIAN_ENABLED_ENV = "FUKUROU_OBSIDIAN_ENABLED"
private const val FUKUROU_OBSIDIAN_VAULT_PATH_ENV = "FUKUROU_OBSIDIAN_VAULT_PATH"
private const val FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS_ENV = "FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS"
private const val FUKUROU_KILL_MIN_CLOSED_TRADES_ENV = "FUKUROU_KILL_MIN_CLOSED_TRADES"
private const val FUKUROU_KILL_MIN_PROFIT_FACTOR_ENV = "FUKUROU_KILL_MIN_PROFIT_FACTOR"
private const val FUKUROU_GMO_PUBLIC_BASE_URL_ENV = "FUKUROU_GMO_PUBLIC_BASE_URL"
private const val FUKUROU_GMO_PUBLIC_REST_PER_SECOND_ENV = "FUKUROU_GMO_PUBLIC_REST_PER_SECOND"
private const val FUKUROU_GMO_PUBLIC_REST_BURST_ENV = "FUKUROU_GMO_PUBLIC_REST_BURST"
private const val FUKUROU_GMO_RETRY_MAX_ATTEMPTS_ENV = "FUKUROU_GMO_RETRY_MAX_ATTEMPTS"
private const val FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS_ENV = "FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS"
private const val FUKUROU_GMO_RETRY_MAX_BACKOFF_MS_ENV = "FUKUROU_GMO_RETRY_MAX_BACKOFF_MS"
private const val FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER_ENV = "FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER"
private const val FUKUROU_GMO_CONNECT_TIMEOUT_MS_ENV = "FUKUROU_GMO_CONNECT_TIMEOUT_MS"
private const val FUKUROU_GMO_REQUEST_TIMEOUT_MS_ENV = "FUKUROU_GMO_REQUEST_TIMEOUT_MS"
private const val FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS_ENV = "FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS"
private const val FUKUROU_GMO_PUBLIC_WEBSOCKET_URL_ENV = "FUKUROU_GMO_PUBLIC_WEBSOCKET_URL"
private const val FUKUROU_GMO_WEBSOCKET_CONNECT_TIMEOUT_MS_ENV = "FUKUROU_GMO_WEBSOCKET_CONNECT_TIMEOUT_MS"
private const val FUKUROU_GMO_WEBSOCKET_TRANSPORT_LIVENESS_TIMEOUT_SECONDS_ENV =
    "FUKUROU_GMO_WEBSOCKET_TRANSPORT_LIVENESS_TIMEOUT_SECONDS"
private const val FUKUROU_GMO_WEBSOCKET_RECONNECT_BACKOFF_MS_ENV = "FUKUROU_GMO_WEBSOCKET_RECONNECT_BACKOFF_MS"
private const val FUKUROU_REPOSITORY_ROOT_ENV = "FUKUROU_REPOSITORY_ROOT"
private const val FUKUROU_LLM_WORKING_DIRECTORY_ENV = "FUKUROU_LLM_WORKING_DIRECTORY"
private const val FUKUROU_CLAUDE_COMMAND_TEMPLATE_ENV = "FUKUROU_CLAUDE_COMMAND_TEMPLATE"
private const val FUKUROU_CODEX_COMMAND_TEMPLATE_ENV = "FUKUROU_CODEX_COMMAND_TEMPLATE"
private const val FUKUROU_CLAUDE_COMMON_ARGS_ENV = "FUKUROU_CLAUDE_COMMON_ARGS"
private const val FUKUROU_CODEX_COMMON_ARGS_ENV = "FUKUROU_CODEX_COMMON_ARGS"
private const val FUKUROU_CODEX_FALSIFIER_ARGS_ENV = "FUKUROU_CODEX_FALSIFIER_ARGS"
private const val FUKUROU_CODEX_PERSISTENT_HOME_ENV = "FUKUROU_CODEX_PERSISTENT_HOME"
private const val FUKUROU_REVISION_ENV = "FUKUROU_REVISION"
private const val FUKUROU_WEB_ROOT_ENV = "FUKUROU_WEB_ROOT"
private const val FUKUROU_PROD_BASE_URL_ENV = "FUKUROU_PROD_BASE_URL"
