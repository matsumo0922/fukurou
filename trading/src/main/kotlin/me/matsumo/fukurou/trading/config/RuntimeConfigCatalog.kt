@file:Suppress("LongParameterList")

package me.matsumo.fukurou.trading.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.runner.FUKUROU_FALSIFIER_ALLOWED_TOOLS_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_JAR_PATH_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_SERVER_ARGS_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_SERVER_COMMAND_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_MCP_SERVER_NAME_ENV
import me.matsumo.fukurou.trading.runner.FUKUROU_PROPOSER_ALLOWED_TOOLS_ENV
import me.matsumo.fukurou.trading.runner.OneShotRunnerCliConfig
import me.matsumo.fukurou.trading.safety.EconomicEventBlackout

/**
 * runtime config の code-owned catalog を提供する。
 */
object RuntimeConfigCatalog {

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
                runtimeItem("paper.initialCashJpy", FUKUROU_PAPER_INITIAL_CASH_JPY_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.paperAccount.initialCashJpy.toPlainString(), config.paperAccount.initialCashJpy.toPlainString(), "JPY"),
                runtimeItem("paper.marketSlippageBps", FUKUROU_MARKET_SLIPPAGE_BPS_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.paperExecution.marketSlippageBps.toPlainString(), config.paperExecution.marketSlippageBps.toPlainString(), "bps"),
                runtimeItem("paper.fallbackMakerFeeRate", FUKUROU_FALLBACK_MAKER_FEE_RATE_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.paperMarket.fallbackMakerFeeRate.toPlainString(), config.paperMarket.fallbackMakerFeeRate.toPlainString(), null),
                runtimeItem("paper.fallbackTakerFeeRate", FUKUROU_FALLBACK_TAKER_FEE_RATE_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.paperMarket.fallbackTakerFeeRate.toPlainString(), config.paperMarket.fallbackTakerFeeRate.toPlainString(), null),
                runtimeItem("paper.fallbackSpreadBps", FUKUROU_FALLBACK_SPREAD_BPS_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.paperMarket.fallbackSpreadBps.toPlainString(), config.paperMarket.fallbackSpreadBps.toPlainString(), "bps"),
                runtimeItem("safety.maxRiskPerTradeRatio", FUKUROU_MAX_RISK_PER_TRADE_RATIO_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.safetyFloor.maxRiskPerTradeRatio.toPlainString(), config.safetyFloor.maxRiskPerTradeRatio.toPlainString(), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("safety.maxDrawdownRatio", FUKUROU_MAX_DRAWDOWN_RATIO_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.safetyFloor.maxDrawdownRatio.toPlainString(), config.safetyFloor.maxDrawdownRatio.toPlainString(), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("safety.maxTotalExposureRatio", FUKUROU_MAX_TOTAL_EXPOSURE_RATIO_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.safetyFloor.maxTotalExposureRatio.toPlainString(), config.safetyFloor.maxTotalExposureRatio.toPlainString(), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("safety.minExpectedValueR", FUKUROU_MIN_EXPECTED_VALUE_R_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.safetyFloor.minExpectedValueR.toPlainString(), config.safetyFloor.minExpectedValueR.toPlainString(), "R", RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("safety.minExpectedMoveToCostRatio", FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.safetyFloor.minExpectedMoveToCostRatio.toPlainString(), config.safetyFloor.minExpectedMoveToCostRatio.toPlainString(), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("safety.maxTakerFeeRatio", FUKUROU_MAX_TAKER_FEE_RATIO_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.safetyFloor.maxTakerFeeRatio.toPlainString(), config.safetyFloor.maxTakerFeeRatio.toPlainString(), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("safety.marketSlippageReserveBps", FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.safetyFloor.marketSlippageReserveBps.toPlainString(), config.safetyFloor.marketSlippageReserveBps.toPlainString(), "bps", RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("safety.dataQualityStaleAfter", FUKUROU_DATA_QUALITY_STALE_AFTER_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.safetyFloor.dataQualityCap.staleAfter.seconds.toString(), config.safetyFloor.dataQualityCap.staleAfter.seconds.toString(), "seconds", RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("safety.dataQualityCappedProbability", FUKUROU_DATA_QUALITY_CAPPED_PROBABILITY_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.safetyFloor.dataQualityCap.cappedProbability.toPlainString(), config.safetyFloor.dataQualityCap.cappedProbability.toPlainString(), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("safety.economicEventBlackouts", FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC_ENV, RuntimeConfigValueType.STRUCTURED_JSON_LIST, "[]", config.safetyFloor.economicEventBlackouts.toCatalogJson(), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("decision.falsificationFreshnessWindow", FUKUROU_FALSIFICATION_FRESHNESS_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.decisionProtocol.falsificationFreshnessWindow.seconds.toString(), config.decisionProtocol.falsificationFreshnessWindow.seconds.toString(), "seconds", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("decision.restingEntryOrderTtl", FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.decisionProtocol.restingEntryOrderTtl.seconds.toString(), config.decisionProtocol.restingEntryOrderTtl.seconds.toString(), "seconds", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("runner.maxToolCallsPerRun", FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT_ENV, RuntimeConfigValueType.INT, defaults.config.runner.maxToolCallsPerRun.toString(), config.runner.maxToolCallsPerRun.toString(), "calls", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("runner.maxActToolCallsPerRun", FUKUROU_MCP_ACT_TOOL_CALL_LIMIT_ENV, RuntimeConfigValueType.INT, defaults.config.runner.maxActToolCallsPerRun.toString(), config.runner.maxActToolCallsPerRun.toString(), "calls", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("runner.perRunTimeout", FUKUROU_LLM_RUN_TIMEOUT_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.runner.perRunTimeout.seconds.toString(), config.runner.perRunTimeout.seconds.toString(), "seconds", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("runner.maxInvocationsPerHour", FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR_ENV, RuntimeConfigValueType.INT, defaults.config.runner.maxInvocationsPerHour.toString(), config.runner.maxInvocationsPerHour.toString(), "invocations", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("runner.maxInvocationsPerDay", FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY_ENV, RuntimeConfigValueType.INT, defaults.config.runner.maxInvocationsPerDay.toString(), config.runner.maxInvocationsPerDay.toString(), "invocations", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.enabled", FUKUROU_LLM_DAEMON_ENABLED_ENV, RuntimeConfigValueType.BOOLEAN, defaults.config.daemon.enabled.toString(), config.daemon.enabled.toString(), null, RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.pollInterval", FUKUROU_LLM_DAEMON_POLL_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.daemon.pollInterval.seconds.toString(), config.daemon.pollInterval.seconds.toString(), "seconds", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.flatHeartbeatInterval", FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.daemon.flatHeartbeatInterval.seconds.toString(), config.daemon.flatHeartbeatInterval.seconds.toString(), "seconds", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.holdingCheckInterval", FUKUROU_LLM_HOLDING_CHECK_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.daemon.holdingCheckInterval.seconds.toString(), config.daemon.holdingCheckInterval.seconds.toString(), "seconds", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.priceMoveTriggerEnabled", FUKUROU_LLM_TRIGGER_PRICE_MOVE_ENABLED_ENV, RuntimeConfigValueType.BOOLEAN, defaults.config.daemon.priceMoveTriggerEnabled.toString(), config.daemon.priceMoveTriggerEnabled.toString(), null, RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.priceMoveWindow", FUKUROU_LLM_TRIGGER_PRICE_MOVE_WINDOW_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.daemon.priceMoveWindow.seconds.toString(), config.daemon.priceMoveWindow.seconds.toString(), "seconds", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.priceMoveThresholdRatio", FUKUROU_LLM_TRIGGER_PRICE_MOVE_THRESHOLD_RATIO_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.daemon.priceMoveThresholdRatio.toPlainString(), config.daemon.priceMoveThresholdRatio.toPlainString(), null, RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.priceMoveCooldown", FUKUROU_LLM_TRIGGER_PRICE_MOVE_COOLDOWN_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.daemon.priceMoveCooldown.seconds.toString(), config.daemon.priceMoveCooldown.seconds.toString(), "seconds", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.stopProximityTriggerEnabled", FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_ENABLED_ENV, RuntimeConfigValueType.BOOLEAN, defaults.config.daemon.stopProximityTriggerEnabled.toString(), config.daemon.stopProximityTriggerEnabled.toString(), null, RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.stopProximityRemainingRThreshold", FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_REMAINING_R_THRESHOLD_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.daemon.stopProximityRemainingRThreshold.toPlainString(), config.daemon.stopProximityRemainingRThreshold.toPlainString(), "R", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("daemon.stopProximityCooldown", FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_COOLDOWN_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.daemon.stopProximityCooldown.seconds.toString(), config.daemon.stopProximityCooldown.seconds.toString(), "seconds", RuntimeConfigSafetyTier.GUARDED),
                runtimeItem("obsidian.enabled", FUKUROU_OBSIDIAN_ENABLED_ENV, RuntimeConfigValueType.BOOLEAN, defaults.config.obsidian.enabled.toString(), config.obsidian.enabled.toString(), null),
                runtimeItem("obsidian.vaultPath", FUKUROU_OBSIDIAN_VAULT_PATH_ENV, RuntimeConfigValueType.STRING, defaults.config.obsidian.vaultPath, config.obsidian.vaultPath, null),
                runtimeItem("obsidian.writeInterval", FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.obsidian.writeInterval.seconds.toString(), config.obsidian.writeInterval.seconds.toString(), "seconds"),
                runtimeItem("killCriterion.minClosedTrades", FUKUROU_KILL_MIN_CLOSED_TRADES_ENV, RuntimeConfigValueType.INT, defaults.config.killCriterion.minClosedTrades.toString(), config.killCriterion.minClosedTrades.toString(), "trades", RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("killCriterion.minProfitFactor", FUKUROU_KILL_MIN_PROFIT_FACTOR_ENV, RuntimeConfigValueType.DECIMAL_STRING, defaults.config.killCriterion.minProfitFactor.toPlainString(), config.killCriterion.minProfitFactor.toPlainString(), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                runtimeItem("gmoPublic.restPerSecond", FUKUROU_GMO_PUBLIC_REST_PER_SECOND_ENV, RuntimeConfigValueType.INT, defaults.config.gmoPublicClient.rateLimit.permitsPerSecond.toString(), config.gmoPublicClient.rateLimit.permitsPerSecond.toString(), "requests/second"),
                runtimeItem("gmoPublic.restBurst", FUKUROU_GMO_PUBLIC_REST_BURST_ENV, RuntimeConfigValueType.INT, defaults.config.gmoPublicClient.rateLimit.burstSize.toString(), config.gmoPublicClient.rateLimit.burstSize.toString(), "requests"),
                runtimeItem("gmoPublic.retryMaxAttempts", FUKUROU_GMO_RETRY_MAX_ATTEMPTS_ENV, RuntimeConfigValueType.INT, defaults.config.gmoPublicClient.retry.maxAttempts.toString(), config.gmoPublicClient.retry.maxAttempts.toString(), "attempts"),
                runtimeItem("gmoPublic.retryInitialBackoff", FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS_ENV, RuntimeConfigValueType.INT, defaults.config.gmoPublicClient.retry.initialBackoff.toMillis().toString(), config.gmoPublicClient.retry.initialBackoff.toMillis().toString(), "milliseconds"),
                runtimeItem("gmoPublic.retryMaxBackoff", FUKUROU_GMO_RETRY_MAX_BACKOFF_MS_ENV, RuntimeConfigValueType.INT, defaults.config.gmoPublicClient.retry.maxBackoff.toMillis().toString(), config.gmoPublicClient.retry.maxBackoff.toMillis().toString(), "milliseconds"),
                runtimeItem("gmoPublic.retryBackoffMultiplier", FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER_ENV, RuntimeConfigValueType.INT, defaults.config.gmoPublicClient.retry.backoffMultiplier.toString(), config.gmoPublicClient.retry.backoffMultiplier.toString(), null),
                runtimeItem("gmoPublic.connectTimeout", FUKUROU_GMO_CONNECT_TIMEOUT_MS_ENV, RuntimeConfigValueType.INT, defaults.config.gmoPublicClient.connectTimeout.toMillis().toString(), config.gmoPublicClient.connectTimeout.toMillis().toString(), "milliseconds"),
                runtimeItem("gmoPublic.requestTimeout", FUKUROU_GMO_REQUEST_TIMEOUT_MS_ENV, RuntimeConfigValueType.INT, defaults.config.gmoPublicClient.requestTimeout.toMillis().toString(), config.gmoPublicClient.requestTimeout.toMillis().toString(), "milliseconds"),
                runtimeItem("gmoPublic.symbolRulesCacheTtl", FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS_ENV, RuntimeConfigValueType.DURATION_SECONDS, defaults.config.gmoPublicClient.symbolRulesCacheTtl.seconds.toString(), config.gmoPublicClient.symbolRulesCacheTtl.seconds.toString(), "seconds"),
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
                deploymentItem("trading.symbol", FUKUROU_TRADING_SYMBOL_ENV, RuntimeConfigValueType.ENUM, defaults.config.symbol.apiSymbol, config.symbol.apiSymbol, null),
                deploymentItem("trading.mode", FUKUROU_TRADING_MODE_ENV, RuntimeConfigValueType.ENUM, defaults.config.mode.name, config.mode.name, null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                deploymentItem("gmoPublic.baseUrl", FUKUROU_GMO_PUBLIC_BASE_URL_ENV, RuntimeConfigValueType.URL, defaults.config.gmoPublicClient.baseUrl, config.gmoPublicClient.baseUrl, null),
                deploymentItem("runner.repositoryRoot", FUKUROU_REPOSITORY_ROOT_ENV, RuntimeConfigValueType.STRING, ".", deploymentValues.repositoryRoot, null),
                deploymentItem("runner.workingDirectory", FUKUROU_LLM_WORKING_DIRECTORY_ENV, RuntimeConfigValueType.STRING, ".", deploymentValues.workingDirectory, null),
                deploymentItem("runner.mcpJarPath", FUKUROU_MCP_JAR_PATH_ENV, RuntimeConfigValueType.STRING, "mcp/build/libs/fukurou-mcp-all.jar", deploymentValues.mcpJarPath, null),
                deploymentItem("runner.mcpServerName", FUKUROU_MCP_SERVER_NAME_ENV, RuntimeConfigValueType.STRING, defaults.cliConfig.mcpServerName, deploymentValues.cliConfig.mcpServerName, null),
                deploymentItem("runner.mcpServerCommand", FUKUROU_MCP_SERVER_COMMAND_ENV, RuntimeConfigValueType.STRING, defaults.cliConfig.mcpServerCommand, deploymentValues.cliConfig.mcpServerCommand, null),
                deploymentItem("runner.mcpServerArgs", FUKUROU_MCP_SERVER_ARGS_ENV, RuntimeConfigValueType.STRING_LIST, null, deploymentValues.cliConfig.mcpServerArgs?.joinToString(" "), null),
                deploymentItem("runner.proposerAllowedTools", FUKUROU_PROPOSER_ALLOWED_TOOLS_ENV, RuntimeConfigValueType.STRING_LIST, defaults.cliConfig.proposerAllowedTools.joinToString(","), deploymentValues.cliConfig.proposerAllowedTools.joinToString(","), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                deploymentItem("runner.falsifierAllowedTools", FUKUROU_FALSIFIER_ALLOWED_TOOLS_ENV, RuntimeConfigValueType.STRING_LIST, defaults.cliConfig.falsifierAllowedTools.joinToString(","), deploymentValues.cliConfig.falsifierAllowedTools.joinToString(","), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                deploymentItem("llm.claudeCommandTemplate", FUKUROU_CLAUDE_COMMAND_TEMPLATE_ENV, RuntimeConfigValueType.STRING_LIST, defaults.rendererConfig.claudeCommandTemplate.joinToString(" "), deploymentValues.rendererConfig.claudeCommandTemplate.joinToString(" "), null),
                deploymentItem("llm.codexCommandTemplate", FUKUROU_CODEX_COMMAND_TEMPLATE_ENV, RuntimeConfigValueType.STRING_LIST, defaults.rendererConfig.codexCommandTemplate.joinToString(" "), deploymentValues.rendererConfig.codexCommandTemplate.joinToString(" "), null),
                deploymentItem("llm.claudeModel", FUKUROU_CLAUDE_MODEL_ENV, RuntimeConfigValueType.STRING, null, deploymentValues.rendererConfig.claudeModel, null),
                deploymentItem("llm.codexModel", FUKUROU_CODEX_MODEL_ENV, RuntimeConfigValueType.STRING, null, deploymentValues.rendererConfig.codexModel, null),
                deploymentItem("llm.claudeCommonArgs", FUKUROU_CLAUDE_COMMON_ARGS_ENV, RuntimeConfigValueType.STRING_LIST, "", deploymentValues.rendererConfig.claudeCommonArgs.joinToString(" "), null),
                deploymentItem("llm.codexCommonArgs", FUKUROU_CODEX_COMMON_ARGS_ENV, RuntimeConfigValueType.STRING_LIST, "", deploymentValues.rendererConfig.codexCommonArgs.joinToString(" "), null),
                deploymentItem("llm.codexFalsifierArgs", FUKUROU_CODEX_FALSIFIER_ARGS_ENV, RuntimeConfigValueType.STRING_LIST, "", deploymentValues.rendererConfig.codexFalsifierArgs.joinToString(" "), null, RuntimeConfigSafetyTier.SAFETY_CRITICAL),
                deploymentItem("llm.codexPersistentHome", FUKUROU_CODEX_PERSISTENT_HOME_ENV, RuntimeConfigValueType.STRING, null, deploymentValues.rendererConfig.codexPersistentHome?.toString(), null),
                deploymentItem("app.revision", FUKUROU_REVISION_ENV, RuntimeConfigValueType.STRING, null, environment.readOptional(FUKUROU_REVISION_ENV), null),
                deploymentItem("app.webRoot", FUKUROU_WEB_ROOT_ENV, RuntimeConfigValueType.STRING, null, environment.readOptional(FUKUROU_WEB_ROOT_ENV), null),
                deploymentItem("app.prodBaseUrl", FUKUROU_PROD_BASE_URL_ENV, RuntimeConfigValueType.URL, null, environment.readOptional(FUKUROU_PROD_BASE_URL_ENV), null),
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
            editable = false,
            applyMode = RuntimeConfigApplyMode.PROCESS_RESTART,
            safetyTier = safetyTier,
            labelKey = "config.item.$key.label",
            descriptionKey = "config.item.$key.description",
        )
    }
}

/**
 * runtime config catalog API の snapshot。
 *
 * @param groups 設定 group 一覧
 */
@Serializable
data class RuntimeConfigSnapshot(
    val groups: List<RuntimeConfigGroup>,
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
private const val FUKUROU_REPOSITORY_ROOT_ENV = "FUKUROU_REPOSITORY_ROOT"
private const val FUKUROU_LLM_WORKING_DIRECTORY_ENV = "FUKUROU_LLM_WORKING_DIRECTORY"
private const val FUKUROU_CLAUDE_COMMAND_TEMPLATE_ENV = "FUKUROU_CLAUDE_COMMAND_TEMPLATE"
private const val FUKUROU_CODEX_COMMAND_TEMPLATE_ENV = "FUKUROU_CODEX_COMMAND_TEMPLATE"
private const val FUKUROU_CLAUDE_MODEL_ENV = "FUKUROU_CLAUDE_MODEL"
private const val FUKUROU_CODEX_MODEL_ENV = "FUKUROU_CODEX_MODEL"
private const val FUKUROU_CLAUDE_COMMON_ARGS_ENV = "FUKUROU_CLAUDE_COMMON_ARGS"
private const val FUKUROU_CODEX_COMMON_ARGS_ENV = "FUKUROU_CODEX_COMMON_ARGS"
private const val FUKUROU_CODEX_FALSIFIER_ARGS_ENV = "FUKUROU_CODEX_FALSIFIER_ARGS"
private const val FUKUROU_CODEX_PERSISTENT_HOME_ENV = "FUKUROU_CODEX_PERSISTENT_HOME"
private const val FUKUROU_REVISION_ENV = "FUKUROU_REVISION"
private const val FUKUROU_WEB_ROOT_ENV = "FUKUROU_WEB_ROOT"
private const val FUKUROU_PROD_BASE_URL_ENV = "FUKUROU_PROD_BASE_URL"
