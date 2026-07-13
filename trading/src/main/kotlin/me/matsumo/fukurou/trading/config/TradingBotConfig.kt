package me.matsumo.fukurou.trading.config

import me.matsumo.fukurou.trading.broker.PaperAccountConfig
import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicWebSocketConfig
import me.matsumo.fukurou.trading.invoker.FUKUROU_CLAUDE_MODEL_ENV
import me.matsumo.fukurou.trading.invoker.FUKUROU_CODEX_MODEL_ENV
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.reflection.ReflectionConfig
import me.matsumo.fukurou.trading.safety.DataQualityCapConfig
import me.matsumo.fukurou.trading.safety.EconomicEventBlackout
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
import me.matsumo.fukurou.trading.safety.SafetyFloorDefaults
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * 取引 bot 全体で共有する typed config。
 *
 * @param symbol 取引対象 symbol
 * @param mode 取引 mode
 * @param paperAccount paper 口座初期化設定
 * @param paperExecution paper 約定近似設定
 * @param paperMarket paper fallback 取引ルール設定
 * @param safetyFloor override 不可の安全床設定
 * @param decisionProtocol decision / Falsifier protocol 設定
 * @param runner LLM one-shot runner の保守的な上限設定
 * @param daemon Ktor 常駐 daemon scheduler 設定
 * @param llmRoleAssignments Proposer / Falsifier の LLM 割り当て
 * @param llmModels Reflection 用の LLM provider ごとの model override
 * @param obsidian Obsidian vault への機械生成 note writer 設定
 * @param reflection deterministic reflection runner 設定
 * @param killCriterion 評価成績による HARD_HALT 基準
 * @param gmoPublicClient GMO Public API client 設定
 * @param gmoPublicWebSocket GMO Public WebSocket client 設定
 */
data class TradingBotConfig(
    val symbol: TradingSymbol = TradingSymbol.BTC,
    val mode: TradingMode = TradingMode.PAPER,
    val paperAccount: PaperAccountConfig = PaperAccountConfig(mode = mode),
    val paperExecution: PaperExecutionConfig = PaperExecutionConfig(),
    val paperMarket: PaperMarketConfig = PaperMarketConfig(),
    val safetyFloor: SafetyFloorConfig = SafetyFloorConfig(),
    val decisionProtocol: DecisionProtocolConfig = DecisionProtocolConfig(),
    val runner: LlmRunnerConfig = LlmRunnerConfig(),
    val daemon: LlmDaemonConfig = LlmDaemonConfig(),
    val llmRoleAssignments: LlmRoleAssignments = LlmRoleAssignments(),
    val llmModels: LlmModelConfig = LlmModelConfig(),
    val obsidian: ObsidianConfig = ObsidianConfig(),
    val reflection: ReflectionConfig = ReflectionConfig(),
    val killCriterion: KillCriterionConfig = KillCriterionConfig(),
    val gmoPublicClient: GmoPublicClientConfig = GmoPublicClientConfig(),
    val gmoPublicWebSocket: GmoPublicWebSocketConfig = GmoPublicWebSocketConfig(),
) {
    init {
        require(mode == TradingMode.PAPER) {
            "TradingBotConfig.mode LIVE is reserved until live broker is implemented."
        }
        require(paperAccount.mode == mode) {
            "paperAccount.mode must match TradingBotConfig.mode."
        }
    }

    companion object {
        /**
         * 環境変数から typed config を構築する。
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): TradingBotConfig {
            return fromEnvironment(environment, tolerateEconomicEventCalendarCorruption = false)
        }

        /** 保存済み active calendar の破損だけを typed warning として読む。 */
        internal fun fromActiveRuntimeEnvironment(environment: Map<String, String>): TradingBotConfig {
            return fromEnvironment(environment, tolerateEconomicEventCalendarCorruption = true)
        }

        private fun fromEnvironment(
            environment: Map<String, String>,
            tolerateEconomicEventCalendarCorruption: Boolean,
        ): TradingBotConfig {
            val mode = environment.readTradingMode()
            val symbol = environment.readTradingSymbol()
            val initialCashJpy = environment.readDecimal(
                name = FUKUROU_PAPER_INITIAL_CASH_JPY_ENV,
                defaultValue = DEFAULT_INITIAL_CASH_JPY,
            )
            val paperMarket = environment.readPaperMarketConfig()
            val marketSlippageBps = environment.readDecimal(
                name = FUKUROU_MARKET_SLIPPAGE_BPS_ENV,
                defaultValue = DEFAULT_MARKET_SLIPPAGE_BPS,
            )
            val volatilitySlippageMultiplier = environment.readDecimal(
                name = FUKUROU_VOLATILITY_SLIPPAGE_MULTIPLIER_ENV,
                defaultValue = DEFAULT_VOLATILITY_SLIPPAGE_MULTIPLIER,
            )

            require(initialCashJpy > BigDecimal.ZERO) {
                "$FUKUROU_PAPER_INITIAL_CASH_JPY_ENV must be greater than 0."
            }

            return TradingBotConfig(
                symbol = symbol,
                mode = mode,
                paperAccount = PaperAccountConfig(
                    initialCashJpy = initialCashJpy,
                    mode = mode,
                ),
                paperExecution = PaperExecutionConfig(
                    marketSlippageBps = marketSlippageBps,
                    volatilitySlippageMultiplier = volatilitySlippageMultiplier,
                ),
                paperMarket = paperMarket,
                safetyFloor = environment.readSafetyFloorConfig(tolerateEconomicEventCalendarCorruption),
                decisionProtocol = environment.readDecisionProtocolConfig(),
                runner = environment.readLlmRunnerConfig(),
                daemon = environment.readLlmDaemonConfig(),
                llmRoleAssignments = environment.readLlmRoleAssignments(),
                llmModels = environment.readLlmModelConfig(),
                obsidian = environment.readObsidianConfig(),
                reflection = environment.readReflectionConfig(),
                killCriterion = environment.readKillCriterionConfig(),
                gmoPublicClient = environment.readGmoPublicClientConfig(),
                gmoPublicWebSocket = environment.readGmoPublicWebSocketConfig(),
            )
        }
    }
}

/** Proposer / Falsifier へ独立して渡す LLM 割り当て。 */
data class LlmRoleAssignments(
    val proposer: LlmRoleAssignment = LlmRoleAssignment(provider = LlmProvider.CLAUDE),
    val falsifier: LlmRoleAssignment = LlmRoleAssignment(provider = LlmProvider.CODEX),
)

/** role ごとの provider、model、reasoning effort。 */
data class LlmRoleAssignment(
    val provider: LlmProvider,
    val model: String? = null,
    val effort: LlmEffort = LlmEffort.DEFAULT,
)

/**
 * LLM provider ごとの model override。
 *
 * @param claudeModel Claude Code に渡す model。null の場合は CLI の既定値を使う
 * @param codexModel Codex に渡す model。null の場合は CLI の既定値を使う
 */
data class LlmModelConfig(
    val claudeModel: String? = null,
    val codexModel: String? = null,
)

/**
 * Obsidian vault へ DB 由来の Markdown note を再生成する writer 設定。
 *
 * @param enabled writer を Ktor process 内で起動するか
 * @param vaultPath container 内で writer が書き込む vault path
 * @param writeInterval writer loop の確認間隔
 */
data class ObsidianConfig(
    val enabled: Boolean = DEFAULT_OBSIDIAN_ENABLED,
    val vaultPath: String = DEFAULT_OBSIDIAN_VAULT_PATH,
    val writeInterval: Duration = DEFAULT_OBSIDIAN_WRITE_INTERVAL,
) {
    init {
        require(writeInterval >= MIN_OBSIDIAN_WRITE_INTERVAL) {
            "writeInterval must be greater than or equal to ${MIN_OBSIDIAN_WRITE_INTERVAL.seconds} seconds."
        }
    }
}

/**
 * paper で取引所 rule が取れない場合に使う fallback 値。
 *
 * @param fallbackMinOrderSize 最小発注数量
 * @param fallbackSizeStep 数量刻み
 * @param fallbackTickSize 価格刻み
 * @param fallbackMakerFeeRate maker 手数料率
 * @param fallbackTakerFeeRate taker 手数料率
 * @param fallbackSpreadBps ticker がない検証で使う spread 目安
 */
data class PaperMarketConfig(
    val fallbackMinOrderSize: BigDecimal = DEFAULT_FALLBACK_MIN_ORDER_SIZE,
    val fallbackSizeStep: BigDecimal = DEFAULT_FALLBACK_SIZE_STEP,
    val fallbackTickSize: BigDecimal = DEFAULT_FALLBACK_TICK_SIZE,
    val fallbackMakerFeeRate: BigDecimal = DEFAULT_FALLBACK_MAKER_FEE_RATE,
    val fallbackTakerFeeRate: BigDecimal = DEFAULT_FALLBACK_TAKER_FEE_RATE,
    val fallbackSpreadBps: BigDecimal = DEFAULT_FALLBACK_SPREAD_BPS,
) {
    init {
        val fallbackMakerFeeIsConservative = fallbackMakerFeeRate >= DEFAULT_FALLBACK_MAKER_FEE_RATE
        val fallbackTakerFeeIsConservative = fallbackTakerFeeRate >= DEFAULT_FALLBACK_TAKER_FEE_RATE
        val fallbackSpreadIsConservative = fallbackSpreadBps >= DEFAULT_FALLBACK_SPREAD_BPS

        require(fallbackMinOrderSize > BigDecimal.ZERO) {
            "fallbackMinOrderSize must be greater than 0."
        }
        require(fallbackSizeStep > BigDecimal.ZERO) {
            "fallbackSizeStep must be greater than 0."
        }
        require(fallbackTickSize > BigDecimal.ZERO) {
            "fallbackTickSize must be greater than 0."
        }
        require(fallbackMakerFeeIsConservative) {
            "fallbackMakerFeeRate must be greater than or equal to -0.0001."
        }
        require(fallbackTakerFeeIsConservative) {
            "fallbackTakerFeeRate must be greater than or equal to 0.0005."
        }
        require(fallbackSpreadIsConservative) {
            "fallbackSpreadBps must be greater than or equal to 2.0."
        }
    }

    /**
     * fallback 値を `SymbolRules` に変換する。
     */
    fun toSymbolRules(symbol: TradingSymbol): SymbolRules {
        return SymbolRules(
            symbol = symbol.apiSymbol,
            minOrderSize = fallbackMinOrderSize.toPlainString(),
            sizeStep = fallbackSizeStep.toPlainString(),
            tickSize = fallbackTickSize.toPlainString(),
            takerFee = fallbackTakerFeeRate.toPlainString(),
            makerFee = fallbackMakerFeeRate.toPlainString(),
        )
    }
}

/**
 * LLM one-shot runner の保守的な上限設定。
 *
 * @param maxToolCallsPerRun 1 MCP server instance あたりの総 tool call 上限
 * @param maxActToolCallsPerRun 1 MCP server instance あたりの act 系 tool call 上限
 * @param perRunTimeout 1 LLM CLI 起動の timeout
 * @param processTerminationGrace TERM 後に KILL へ移るまでの猶予
 * @param persistenceTerminalTimeout terminal persistence の timeout
 * @param maxInvocationsPerHour 直近 1 時間に許可する runner 起動数
 * @param maxInvocationsPerDay 直近 24 時間に許可する runner 起動数
 * @param entryFillReservePerHour ENTRY_FILL に保証する 1 時間の起動数
 * @param entryFillReservePerDay ENTRY_FILL に保証する 24 時間の起動数
 * @param stopProximityReservePerHour STOP_PROXIMITY に保証する 1 時間の起動数
 * @param stopProximityReservePerDay STOP_PROXIMITY に保証する 24 時間の起動数
 */
data class LlmRunnerConfig(
    val maxToolCallsPerRun: Int = DEFAULT_MAX_TOOL_CALLS_PER_RUN,
    val maxActToolCallsPerRun: Int = DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN,
    val perRunTimeout: Duration = DEFAULT_LLM_PER_RUN_TIMEOUT,
    val processTerminationGrace: Duration = DEFAULT_LLM_PROCESS_TERMINATION_GRACE,
    val persistenceTerminalTimeout: Duration = DEFAULT_LLM_PERSISTENCE_TERMINAL_TIMEOUT,
    val maxInvocationsPerHour: Int = DEFAULT_MAX_INVOCATIONS_PER_HOUR,
    val maxInvocationsPerDay: Int = DEFAULT_MAX_INVOCATIONS_PER_DAY,
    val entryFillReservePerHour: Int = DEFAULT_ENTRY_FILL_RESERVE_PER_HOUR,
    val entryFillReservePerDay: Int = DEFAULT_ENTRY_FILL_RESERVE_PER_DAY,
    val stopProximityReservePerHour: Int = DEFAULT_STOP_PROXIMITY_RESERVE_PER_HOUR,
    val stopProximityReservePerDay: Int = DEFAULT_STOP_PROXIMITY_RESERVE_PER_DAY,
) {
    init {
        val toolLimitIsConservative = maxToolCallsPerRun in 1..DEFAULT_MAX_TOOL_CALLS_PER_RUN
        val actLimitIsConservative = maxActToolCallsPerRun in 1..DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN
        val timeoutIsPositive = !perRunTimeout.isNegative && !perRunTimeout.isZero
        val timeoutIsWithinCap = timeoutIsPositive && perRunTimeout <= MAX_LLM_PER_RUN_TIMEOUT
        val terminationGraceIsValid = processTerminationGrace in MIN_LLM_PROCESS_TERMINATION_GRACE..MAX_LLM_PROCESS_TERMINATION_GRACE
        val persistenceTimeoutIsValid = persistenceTerminalTimeout in MIN_LLM_PERSISTENCE_TERMINAL_TIMEOUT..MAX_LLM_PERSISTENCE_TERMINAL_TIMEOUT
        val hourlyLimitIsConservative = maxInvocationsPerHour in 1..DEFAULT_MAX_INVOCATIONS_PER_HOUR
        val dailyLimitIsConservative = maxInvocationsPerDay in 1..DEFAULT_MAX_INVOCATIONS_PER_DAY
        val actLimitFitsTotal = maxActToolCallsPerRun <= maxToolCallsPerRun
        val reservesAreNonNegative = listOf(
            entryFillReservePerHour,
            entryFillReservePerDay,
            stopProximityReservePerHour,
            stopProximityReservePerDay,
        ).all { reserve -> reserve >= 0 }
        val hourlyReservesFit = entryFillReservePerHour.toLong() + stopProximityReservePerHour.toLong() <
            maxInvocationsPerHour.toLong()
        val dailyReservesFit = entryFillReservePerDay.toLong() + stopProximityReservePerDay.toLong() <
            maxInvocationsPerDay.toLong()

        require(toolLimitIsConservative) {
            "maxToolCallsPerRun must be between 1 and $DEFAULT_MAX_TOOL_CALLS_PER_RUN."
        }
        require(actLimitIsConservative) {
            "maxActToolCallsPerRun must be between 1 and $DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN."
        }
        require(timeoutIsWithinCap) {
            "perRunTimeout must be greater than 0 and less than or equal to ${MAX_LLM_PER_RUN_TIMEOUT.seconds} seconds."
        }
        require(terminationGraceIsValid) {
            "processTerminationGrace must be between 1 and ${MAX_LLM_PROCESS_TERMINATION_GRACE.seconds} seconds."
        }
        require(persistenceTimeoutIsValid) {
            "persistenceTerminalTimeout must be between 1 and ${MAX_LLM_PERSISTENCE_TERMINAL_TIMEOUT.seconds} seconds."
        }
        require(hourlyLimitIsConservative) {
            "maxInvocationsPerHour must be between 1 and $DEFAULT_MAX_INVOCATIONS_PER_HOUR."
        }
        require(dailyLimitIsConservative) {
            "maxInvocationsPerDay must be between 1 and $DEFAULT_MAX_INVOCATIONS_PER_DAY."
        }
        require(actLimitFitsTotal) {
            "maxActToolCallsPerRun must be less than or equal to maxToolCallsPerRun."
        }
        require(reservesAreNonNegative) { "Critical launch reserves must not be negative." }
        require(hourlyReservesFit) { "Hourly critical launch reserves must total less than maxInvocationsPerHour." }
        require(dailyReservesFit) { "Daily critical launch reserves must total less than maxInvocationsPerDay." }
    }
}

/**
 * Ktor 常駐 LLM daemon scheduler の保守的な設定。
 *
 * @param enabled daemon scheduler を Ktor process 内で起動するか
 * @param pollInterval daemon loop の確認間隔
 * @param flatHeartbeatInterval flat 状態で event 条件がない場合の heartbeat 間隔
 * @param holdingCheckInterval 建玉または open order がある場合の密な LLM 確認間隔
 * @param launchReservationStaleAfter 異常終了した起動予約を同時起動扱いから外すまでの猶予
 * @param priceMoveTriggerEnabled 価格急変 trigger を有効にするか
 * @param priceMoveWindow 価格急変判定に使う観測 window
 * @param priceMoveThresholdRatio 価格急変とみなす絶対変化率
 * @param priceMoveCooldown 価格急変 trigger の cooldown
 * @param entryFillTriggerEnabled entry fill trigger を有効にするか
 * @param entryFillCooldown entry fill trigger の cooldown
 * @param preFilterEnabled heartbeat 系 trigger の軽量 pre-filter を有効にするか
 * @param stopProximityTriggerEnabled STOP 接近 trigger を有効にするか
 * @param stopProximityRemainingRThreshold STOP 接近とみなす残り R
 * @param stopProximityCooldown STOP 接近 trigger の cooldown
 */
data class LlmDaemonConfig(
    val enabled: Boolean = DEFAULT_LLM_DAEMON_ENABLED,
    val launchEnabled: Boolean = DEFAULT_LLM_LAUNCH_ENABLED,
    val pollInterval: Duration = DEFAULT_LLM_DAEMON_POLL_INTERVAL,
    val flatHeartbeatInterval: Duration = DEFAULT_LLM_FLAT_HEARTBEAT_INTERVAL,
    val holdingCheckInterval: Duration = DEFAULT_LLM_HOLDING_CHECK_INTERVAL,
    val launchReservationStaleAfter: Duration = DEFAULT_LLM_LAUNCH_RESERVATION_STALE_AFTER,
    val priceMoveTriggerEnabled: Boolean = DEFAULT_LLM_PRICE_MOVE_TRIGGER_ENABLED,
    val priceMoveWindow: Duration = DEFAULT_LLM_PRICE_MOVE_WINDOW,
    val priceMoveThresholdRatio: BigDecimal = DEFAULT_LLM_PRICE_MOVE_THRESHOLD_RATIO,
    val priceMoveCooldown: Duration = DEFAULT_LLM_PRICE_MOVE_COOLDOWN,
    val entryFillTriggerEnabled: Boolean = DEFAULT_LLM_ENTRY_FILL_TRIGGER_ENABLED,
    val entryFillCooldown: Duration = DEFAULT_LLM_ENTRY_FILL_COOLDOWN,
    val preFilterEnabled: Boolean = DEFAULT_LLM_PRE_FILTER_ENABLED,
    val stopProximityTriggerEnabled: Boolean = DEFAULT_LLM_STOP_PROXIMITY_TRIGGER_ENABLED,
    val stopProximityRemainingRThreshold: BigDecimal = DEFAULT_LLM_STOP_PROXIMITY_REMAINING_R_THRESHOLD,
    val stopProximityCooldown: Duration = DEFAULT_LLM_STOP_PROXIMITY_COOLDOWN,
) {
    init {
        val pollIntervalIsConservative = pollInterval >= DEFAULT_LLM_DAEMON_POLL_INTERVAL
        val flatHeartbeatIsConservative = flatHeartbeatInterval >= DEFAULT_LLM_FLAT_HEARTBEAT_INTERVAL
        val holdingCheckIsConservative = holdingCheckInterval >= DEFAULT_LLM_HOLDING_CHECK_INTERVAL
        val reservationStaleIsPositive = !launchReservationStaleAfter.isNegative && !launchReservationStaleAfter.isZero
        val priceMoveThresholdIsPositive = priceMoveThresholdRatio > BigDecimal.ZERO
        val priceMoveWindowFitsPoll = priceMoveWindow >= pollInterval
        val priceMoveCooldownFitsPoll = priceMoveCooldown >= pollInterval
        val entryFillCooldownFitsPoll = entryFillCooldown >= pollInterval
        val stopProximityThresholdIsPositive = stopProximityRemainingRThreshold > BigDecimal.ZERO
        val stopProximityCooldownFitsPoll = stopProximityCooldown >= pollInterval

        require(pollIntervalIsConservative) {
            "pollInterval must be greater than or equal to ${DEFAULT_LLM_DAEMON_POLL_INTERVAL.seconds} seconds."
        }
        require(flatHeartbeatIsConservative) {
            "flatHeartbeatInterval must be greater than or equal to ${DEFAULT_LLM_FLAT_HEARTBEAT_INTERVAL.toMinutes()} minutes."
        }
        require(holdingCheckIsConservative) {
            "holdingCheckInterval must be greater than or equal to ${DEFAULT_LLM_HOLDING_CHECK_INTERVAL.toMinutes()} minutes."
        }
        require(reservationStaleIsPositive) {
            "launchReservationStaleAfter must be greater than 0."
        }
        require(priceMoveThresholdIsPositive) {
            "priceMoveThresholdRatio must be greater than 0."
        }
        require(priceMoveWindowFitsPoll) {
            "priceMoveWindow must be greater than or equal to pollInterval."
        }
        require(priceMoveCooldownFitsPoll) {
            "priceMoveCooldown must be greater than or equal to pollInterval."
        }
        require(entryFillCooldownFitsPoll) {
            "entryFillCooldown must be greater than or equal to pollInterval."
        }
        require(stopProximityThresholdIsPositive) {
            "stopProximityRemainingRThreshold must be greater than 0."
        }
        require(stopProximityCooldownFitsPoll) {
            "stopProximityCooldown must be greater than or equal to pollInterval."
        }
    }
}

/**
 * 評価成績による kill 基準の保守的な設定。
 *
 * @param minClosedTrades 判定に必要な最小 closed trade 数
 * @param minProfitFactor これを下回る PF で HARD_HALT する
 */
data class KillCriterionConfig(
    val minClosedTrades: Int = DEFAULT_KILL_MIN_CLOSED_TRADES,
    val minProfitFactor: BigDecimal = DEFAULT_KILL_MIN_PROFIT_FACTOR,
) {
    init {
        val closedTradesIsConservative = minClosedTrades in 1..DEFAULT_KILL_MIN_CLOSED_TRADES
        val profitFactorIsConservative = minProfitFactor >= DEFAULT_KILL_MIN_PROFIT_FACTOR

        require(closedTradesIsConservative) {
            "minClosedTrades must be between 1 and $DEFAULT_KILL_MIN_CLOSED_TRADES."
        }
        require(profitFactorIsConservative) {
            "minProfitFactor must be greater than or equal to ${DEFAULT_KILL_MIN_PROFIT_FACTOR.toPlainString()}."
        }
    }
}

/**
 * decision / Falsifier protocol の保守的な設定。
 *
 * @param falsificationFreshnessWindow fresh APPROVED とみなす時間窓
 * @param restingEntryOrderTtl ProtectionReconciler が resting entry order を cancel するまでの TTL
 */
data class DecisionProtocolConfig(
    val falsificationFreshnessWindow: Duration = DEFAULT_FALSIFICATION_FRESHNESS_WINDOW,
    val restingEntryOrderTtl: Duration = DEFAULT_RESTING_ENTRY_ORDER_TTL,
) {
    init {
        val windowIsPositive = !falsificationFreshnessWindow.isNegative && !falsificationFreshnessWindow.isZero
        val windowIsAtOrBelowDefault = falsificationFreshnessWindow <= DEFAULT_FALSIFICATION_FRESHNESS_WINDOW
        val ttlIsPositive = !restingEntryOrderTtl.isNegative && !restingEntryOrderTtl.isZero
        val ttlIsAtOrBelowDefault = restingEntryOrderTtl <= DEFAULT_RESTING_ENTRY_ORDER_TTL

        require(windowIsPositive && windowIsAtOrBelowDefault) {
            "falsificationFreshnessWindow must be greater than 0 and less than or equal to 120 seconds."
        }
        require(ttlIsPositive && ttlIsAtOrBelowDefault) {
            "restingEntryOrderTtl must be greater than 0 and less than or equal to 1800 seconds."
        }
    }
}

/**
 * 取引対象 symbol の環境変数名。
 */
private const val FUKUROU_TRADING_SYMBOL_ENV = "FUKUROU_TRADING_SYMBOL"

/**
 * trading mode の環境変数名。
 */
private const val FUKUROU_TRADING_MODE_ENV = "FUKUROU_TRADING_MODE"

/**
 * paper 初期残高の環境変数名。
 */
private const val FUKUROU_PAPER_INITIAL_CASH_JPY_ENV = "FUKUROU_PAPER_INITIAL_CASH_JPY"

/**
 * paper slippage bps の環境変数名。
 */
private const val FUKUROU_MARKET_SLIPPAGE_BPS_ENV = "FUKUROU_MARKET_SLIPPAGE_BPS"

/**
 * paper volatility slippage multiplier の環境変数名。
 */
private const val FUKUROU_VOLATILITY_SLIPPAGE_MULTIPLIER_ENV =
    "FUKUROU_VOLATILITY_SLIPPAGE_MULTIPLIER"

/**
 * paper fallback maker fee の環境変数名。
 */
private const val FUKUROU_FALLBACK_MAKER_FEE_RATE_ENV = "FUKUROU_FALLBACK_MAKER_FEE_RATE"

/**
 * paper fallback taker fee の環境変数名。
 */
private const val FUKUROU_FALLBACK_TAKER_FEE_RATE_ENV = "FUKUROU_FALLBACK_TAKER_FEE_RATE"

/**
 * paper fallback spread bps の環境変数名。
 */
private const val FUKUROU_FALLBACK_SPREAD_BPS_ENV = "FUKUROU_FALLBACK_SPREAD_BPS"

/**
 * 安全床の 1 trade 最大損失割合の環境変数名。
 */
private const val FUKUROU_MAX_RISK_PER_TRADE_RATIO_ENV = "FUKUROU_MAX_RISK_PER_TRADE_RATIO"

/**
 * 安全床の最大 drawdown の環境変数名。
 */
private const val FUKUROU_MAX_DRAWDOWN_RATIO_ENV = "FUKUROU_MAX_DRAWDOWN_RATIO"

/**
 * 安全床の最大 exposure の環境変数名。
 */
private const val FUKUROU_MAX_TOTAL_EXPOSURE_RATIO_ENV = "FUKUROU_MAX_TOTAL_EXPOSURE_RATIO"

/**
 * 安全床の最小 EV の環境変数名。
 */
private const val FUKUROU_MIN_EXPECTED_VALUE_R_ENV = "FUKUROU_MIN_EXPECTED_VALUE_R"

/**
 * 安全床の想定値幅 / cost 比率の環境変数名。
 */
private const val FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO_ENV = "FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO"

/**
 * 安全床の taker fee 上限の環境変数名。
 */
private const val FUKUROU_MAX_TAKER_FEE_RATIO_ENV = "FUKUROU_MAX_TAKER_FEE_RATIO"

/**
 * 経済イベント blackout static config の環境変数名。
 */
private const val FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC_ENV = "FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC"

/**
 * 安全床の片道 slippage reserve bps の環境変数名。
 */
private const val FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS_ENV = "FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS"

/**
 * data quality p cap の stale 秒数の環境変数名。
 */
private const val FUKUROU_DATA_QUALITY_STALE_AFTER_SECONDS_ENV = "FUKUROU_DATA_QUALITY_STALE_AFTER_SECONDS"

/**
 * data quality p cap の probability 上限の環境変数名。
 */
private const val FUKUROU_DATA_QUALITY_CAPPED_PROBABILITY_ENV = "FUKUROU_DATA_QUALITY_CAPPED_PROBABILITY"

/**
 * fresh falsification 判定 window 秒数の環境変数名。
 */
private const val FUKUROU_FALSIFICATION_FRESHNESS_SECONDS_ENV = "FUKUROU_FALSIFICATION_FRESHNESS_SECONDS"

/**
 * resting entry order を stale とみなす TTL 秒数の環境変数名。
 */
private const val FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS_ENV = "FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS"

/**
 * 1 MCP server instance あたりの総 tool call 上限の環境変数名。
 */
const val FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT_ENV = "FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT"

/**
 * 1 MCP server instance あたりの act 系 tool call 上限の環境変数名。
 */
const val FUKUROU_MCP_ACT_TOOL_CALL_LIMIT_ENV = "FUKUROU_MCP_ACT_TOOL_CALL_LIMIT"

/**
 * LLM CLI 1 起動 timeout 秒数の環境変数名。
 */
private const val FUKUROU_LLM_RUN_TIMEOUT_SECONDS_ENV = "FUKUROU_LLM_RUN_TIMEOUT_SECONDS"
private const val FUKUROU_LLM_PROCESS_TERMINATION_GRACE_SECONDS_ENV =
    "FUKUROU_LLM_PROCESS_TERMINATION_GRACE_SECONDS"
private const val FUKUROU_LLM_PERSISTENCE_TERMINAL_TIMEOUT_SECONDS_ENV =
    "FUKUROU_LLM_PERSISTENCE_TERMINAL_TIMEOUT_SECONDS"

/**
 * 直近 1 時間の runner 起動上限の環境変数名。
 */
private const val FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR_ENV = "FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR"

/**
 * 直近 24 時間の runner 起動上限の環境変数名。
 */
private const val FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY_ENV = "FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY"
private const val FUKUROU_LLM_ENTRY_FILL_RESERVE_PER_HOUR_ENV = "FUKUROU_LLM_ENTRY_FILL_RESERVE_PER_HOUR"
private const val FUKUROU_LLM_ENTRY_FILL_RESERVE_PER_DAY_ENV = "FUKUROU_LLM_ENTRY_FILL_RESERVE_PER_DAY"
private const val FUKUROU_LLM_STOP_PROXIMITY_RESERVE_PER_HOUR_ENV = "FUKUROU_LLM_STOP_PROXIMITY_RESERVE_PER_HOUR"
private const val FUKUROU_LLM_STOP_PROXIMITY_RESERVE_PER_DAY_ENV = "FUKUROU_LLM_STOP_PROXIMITY_RESERVE_PER_DAY"

/**
 * LLM daemon scheduler 有効化の環境変数名。
 */
private const val FUKUROU_LLM_DAEMON_ENABLED_ENV = "FUKUROU_LLM_DAEMON_ENABLED"
const val FUKUROU_LLM_LAUNCH_ENABLED_ENV = "FUKUROU_LLM_LAUNCH_ENABLED"

/**
 * LLM daemon loop poll 間隔秒数の環境変数名。
 */
private const val FUKUROU_LLM_DAEMON_POLL_SECONDS_ENV = "FUKUROU_LLM_DAEMON_POLL_SECONDS"

/**
 * flat heartbeat 間隔秒数の環境変数名。
 */
private const val FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS_ENV = "FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS"

/**
 * holding dense check 間隔秒数の環境変数名。
 */
private const val FUKUROU_LLM_HOLDING_CHECK_SECONDS_ENV = "FUKUROU_LLM_HOLDING_CHECK_SECONDS"

/**
 * 価格急変 trigger 有効化の環境変数名。
 */
private const val FUKUROU_LLM_TRIGGER_PRICE_MOVE_ENABLED_ENV = "FUKUROU_LLM_TRIGGER_PRICE_MOVE_ENABLED"

/**
 * 価格急変 trigger の window 秒数の環境変数名。
 */
private const val FUKUROU_LLM_TRIGGER_PRICE_MOVE_WINDOW_SECONDS_ENV =
    "FUKUROU_LLM_TRIGGER_PRICE_MOVE_WINDOW_SECONDS"

/**
 * 価格急変 trigger の絶対変化率しきい値の環境変数名。
 */
private const val FUKUROU_LLM_TRIGGER_PRICE_MOVE_THRESHOLD_RATIO_ENV =
    "FUKUROU_LLM_TRIGGER_PRICE_MOVE_THRESHOLD_RATIO"

/**
 * 価格急変 trigger の cooldown 秒数の環境変数名。
 */
private const val FUKUROU_LLM_TRIGGER_PRICE_MOVE_COOLDOWN_SECONDS_ENV =
    "FUKUROU_LLM_TRIGGER_PRICE_MOVE_COOLDOWN_SECONDS"

/**
 * entry fill trigger 有効化の環境変数名。
 */
private const val FUKUROU_LLM_TRIGGER_ENTRY_FILL_ENABLED_ENV = "FUKUROU_LLM_TRIGGER_ENTRY_FILL_ENABLED"

/**
 * entry fill trigger の cooldown 秒数の環境変数名。
 */
private const val FUKUROU_LLM_TRIGGER_ENTRY_FILL_COOLDOWN_SECONDS_ENV =
    "FUKUROU_LLM_TRIGGER_ENTRY_FILL_COOLDOWN_SECONDS"

/**
 * 軽量 pre-filter 有効化の環境変数名。
 */
private const val FUKUROU_LLM_PRE_FILTER_ENABLED_ENV = "FUKUROU_LLM_PRE_FILTER_ENABLED"

/**
 * STOP 接近 trigger 有効化の環境変数名。
 */
private const val FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_ENABLED_ENV =
    "FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_ENABLED"

/**
 * STOP 接近 trigger の残り R しきい値の環境変数名。
 */
private const val FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_REMAINING_R_THRESHOLD_ENV =
    "FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_REMAINING_R_THRESHOLD"

/**
 * STOP 接近 trigger の cooldown 秒数の環境変数名。
 */
private const val FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_COOLDOWN_SECONDS_ENV =
    "FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_COOLDOWN_SECONDS"

/**
 * Obsidian writer 有効化の環境変数名。
 */
private const val FUKUROU_OBSIDIAN_ENABLED_ENV = "FUKUROU_OBSIDIAN_ENABLED"

/**
 * Obsidian vault path の環境変数名。
 */
private const val FUKUROU_OBSIDIAN_VAULT_PATH_ENV = "FUKUROU_OBSIDIAN_VAULT_PATH"

/**
 * Obsidian writer 間隔秒数の環境変数名。
 */
private const val FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS_ENV = "FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS"

/**
 * deterministic Reflection Runner 最小間隔秒数の環境変数名。
 */
internal const val FUKUROU_REFLECTION_MIN_INTERVAL_SECONDS_ENV = "FUKUROU_REFLECTION_MIN_INTERVAL_SECONDS"

/**
 * deterministic Reflection Runner の 1 tick 読み取り上限の環境変数名。
 */
internal const val FUKUROU_REFLECTION_QUERY_LIMIT_ENV = "FUKUROU_REFLECTION_QUERY_LIMIT"

/**
 * confidence calibration lookback 日数の環境変数名。
 */
internal const val FUKUROU_REFLECTION_CALIBRATION_LOOKBACK_DAYS_ENV =
    "FUKUROU_REFLECTION_CALIBRATION_LOOKBACK_DAYS"

/**
 * Recent Decisions 表示上限の環境変数名。
 */
internal const val FUKUROU_REFLECTION_RECENT_DECISION_LIMIT_ENV =
    "FUKUROU_REFLECTION_RECENT_DECISION_LIMIT"

/**
 * sample size warning の closed trade 件数しきい値の環境変数名。
 */
internal const val FUKUROU_REFLECTION_SAMPLE_WARNING_TRADE_COUNT_ENV =
    "FUKUROU_REFLECTION_SAMPLE_WARNING_TRADE_COUNT"

/**
 * PromptCandidates 生成 LLM provider の環境変数名。
 */
internal const val FUKUROU_REFLECTION_PROMPT_CANDIDATE_PROVIDER_ENV =
    "FUKUROU_REFLECTION_PROMPT_CANDIDATE_PROVIDER"

/**
 * PromptCandidates 生成 LLM timeout 秒数の環境変数名。
 */
internal const val FUKUROU_REFLECTION_PROMPT_CANDIDATE_TIMEOUT_SECONDS_ENV =
    "FUKUROU_REFLECTION_PROMPT_CANDIDATE_TIMEOUT_SECONDS"

/**
 * 週ごとの PromptCandidates 生成最大試行回数の環境変数名。
 */
internal const val FUKUROU_REFLECTION_PROMPT_CANDIDATE_MAX_ATTEMPTS_ENV =
    "FUKUROU_REFLECTION_PROMPT_CANDIDATE_MAX_ATTEMPTS"

/**
 * kill 基準の最小 closed trade 数の環境変数名。
 */
private const val FUKUROU_KILL_MIN_CLOSED_TRADES_ENV = "FUKUROU_KILL_MIN_CLOSED_TRADES"

/**
 * kill 基準の最小 PF の環境変数名。
 */
private const val FUKUROU_KILL_MIN_PROFIT_FACTOR_ENV = "FUKUROU_KILL_MIN_PROFIT_FACTOR"

/** GMO Public WebSocket endpoint の環境変数名。 */
private const val FUKUROU_GMO_PUBLIC_WEBSOCKET_URL_ENV = "FUKUROU_GMO_PUBLIC_WEBSOCKET_URL"

/** GMO Public WebSocket connect timeout の環境変数名。 */
private const val FUKUROU_GMO_WEBSOCKET_CONNECT_TIMEOUT_MS_ENV = "FUKUROU_GMO_WEBSOCKET_CONNECT_TIMEOUT_MS"

/** GMO Public WebSocket transport liveness timeout の環境変数名。 */
private const val FUKUROU_GMO_WEBSOCKET_TRANSPORT_LIVENESS_TIMEOUT_SECONDS_ENV =
    "FUKUROU_GMO_WEBSOCKET_TRANSPORT_LIVENESS_TIMEOUT_SECONDS"

/** GMO Public WebSocket reconnect backoff の環境変数名。 */
private const val FUKUROU_GMO_WEBSOCKET_RECONNECT_BACKOFF_MS_ENV = "FUKUROU_GMO_WEBSOCKET_RECONNECT_BACKOFF_MS"

/**
 * paper 初期残高の既定値。
 */
private val DEFAULT_INITIAL_CASH_JPY = BigDecimal("1000000")

/**
 * MARKET / STOP の既定 slippage bps。
 */
private val DEFAULT_MARKET_SLIPPAGE_BPS = BigDecimal("5")

/**
 * ATR 由来 slippage 係数の既定値。
 */
private val DEFAULT_VOLATILITY_SLIPPAGE_MULTIPLIER = BigDecimal("0.1")

/**
 * fresh falsification の既定 window。
 */
private val DEFAULT_FALSIFICATION_FRESHNESS_WINDOW = Duration.ofSeconds(120)

/**
 * resting entry order の既定 TTL。
 */
val DEFAULT_RESTING_ENTRY_ORDER_TTL: Duration = Duration.ofMinutes(30)

/**
 * 1 MCP server instance あたりの既定総 tool call 上限。
 */
const val DEFAULT_MAX_TOOL_CALLS_PER_RUN = 48

/**
 * 1 MCP server instance あたりの既定 act 系 tool call 上限。
 */
const val DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN = 3

/**
 * LLM CLI 1 起動の既定 timeout。
 */
val DEFAULT_LLM_PER_RUN_TIMEOUT: Duration = Duration.ofSeconds(180)

/**
 * LLM CLI 1 起動 timeout の許容上限。
 *
 * ENTER 経路の実測 4〜5 分 + 余裕として、暴走防止を残したまま 600 秒まで許可する。
 */
val MAX_LLM_PER_RUN_TIMEOUT: Duration = Duration.ofSeconds(600)

/** process tree の既定 TERM grace。 */
val DEFAULT_LLM_PROCESS_TERMINATION_GRACE: Duration = Duration.ofSeconds(10)

/** process tree の最小 TERM grace。 */
val MIN_LLM_PROCESS_TERMINATION_GRACE: Duration = Duration.ofSeconds(1)

/** process tree の最大 TERM grace。 */
val MAX_LLM_PROCESS_TERMINATION_GRACE: Duration = Duration.ofSeconds(30)

/** terminal persistence の既定 timeout。 */
val DEFAULT_LLM_PERSISTENCE_TERMINAL_TIMEOUT: Duration = Duration.ofSeconds(10)

/** terminal persistence の最小 timeout。 */
val MIN_LLM_PERSISTENCE_TERMINAL_TIMEOUT: Duration = Duration.ofSeconds(1)

/** terminal persistence の最大 timeout。 */
val MAX_LLM_PERSISTENCE_TERMINAL_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * 直近 1 時間の既定 runner 起動上限。
 */
const val DEFAULT_MAX_INVOCATIONS_PER_HOUR = 7

/**
 * 直近 24 時間の既定 runner 起動上限。
 */
const val DEFAULT_MAX_INVOCATIONS_PER_DAY = 120
const val DEFAULT_ENTRY_FILL_RESERVE_PER_HOUR = 1
const val DEFAULT_ENTRY_FILL_RESERVE_PER_DAY = 4
const val DEFAULT_STOP_PROXIMITY_RESERVE_PER_HOUR = 1
const val DEFAULT_STOP_PROXIMITY_RESERVE_PER_DAY = 4

/**
 * LLM daemon scheduler 有効化の既定値。
 */
const val DEFAULT_LLM_DAEMON_ENABLED = false
const val DEFAULT_LLM_LAUNCH_ENABLED = false

/**
 * LLM daemon loop poll 間隔の既定値。
 */
val DEFAULT_LLM_DAEMON_POLL_INTERVAL: Duration = Duration.ofMinutes(1)

/**
 * flat 状態 heartbeat 間隔の既定値。
 */
val DEFAULT_LLM_FLAT_HEARTBEAT_INTERVAL: Duration = Duration.ofMinutes(15)

/**
 * holding 状態 LLM 確認間隔の既定値。
 */
val DEFAULT_LLM_HOLDING_CHECK_INTERVAL: Duration = Duration.ofMinutes(15)

/**
 * LLM 起動予約を stale とみなす既定時間。
 */
val DEFAULT_LLM_LAUNCH_RESERVATION_STALE_AFTER: Duration = Duration.ofMinutes(30)

/**
 * 価格急変 trigger 有効化の既定値。
 */
const val DEFAULT_LLM_PRICE_MOVE_TRIGGER_ENABLED = true

/**
 * 価格急変 trigger の既定 window。
 */
val DEFAULT_LLM_PRICE_MOVE_WINDOW: Duration = Duration.ofSeconds(300)

/**
 * 価格急変 trigger の既定絶対変化率しきい値。
 */
val DEFAULT_LLM_PRICE_MOVE_THRESHOLD_RATIO: BigDecimal = BigDecimal("0.01")

/**
 * 価格急変 trigger の既定 cooldown。
 */
val DEFAULT_LLM_PRICE_MOVE_COOLDOWN: Duration = Duration.ofSeconds(600)

/**
 * entry fill trigger 有効化の既定値。
 */
const val DEFAULT_LLM_ENTRY_FILL_TRIGGER_ENABLED = true

/**
 * entry fill trigger の既定 cooldown。
 */
val DEFAULT_LLM_ENTRY_FILL_COOLDOWN: Duration = Duration.ofSeconds(600)

/**
 * 軽量 pre-filter 有効化の既定値。
 */
const val DEFAULT_LLM_PRE_FILTER_ENABLED = false

/**
 * STOP 接近 trigger 有効化の既定値。
 */
const val DEFAULT_LLM_STOP_PROXIMITY_TRIGGER_ENABLED = true

/**
 * STOP 接近 trigger の既定残り R しきい値。
 */
val DEFAULT_LLM_STOP_PROXIMITY_REMAINING_R_THRESHOLD: BigDecimal = BigDecimal("0.3")

/**
 * STOP 接近 trigger の既定 cooldown。
 */
val DEFAULT_LLM_STOP_PROXIMITY_COOLDOWN: Duration = Duration.ofSeconds(900)

/**
 * Obsidian writer 有効化の既定値。
 */
const val DEFAULT_OBSIDIAN_ENABLED = false

/**
 * Obsidian vault path の既定値。
 */
const val DEFAULT_OBSIDIAN_VAULT_PATH = "/vault"

/**
 * Obsidian writer loop 間隔の既定値。
 */
val DEFAULT_OBSIDIAN_WRITE_INTERVAL: Duration = Duration.ofMinutes(5)

/**
 * Obsidian writer loop 間隔の最小値。
 */
val MIN_OBSIDIAN_WRITE_INTERVAL: Duration = Duration.ofMinutes(1)

/**
 * kill 基準の既定最小 closed trade 数。
 */
const val DEFAULT_KILL_MIN_CLOSED_TRADES = 100

/**
 * kill 基準の既定最小 PF。
 */
val DEFAULT_KILL_MIN_PROFIT_FACTOR: BigDecimal = BigDecimal("0.8")

/**
 * fallback 最小発注数量の既定値。
 */
private val DEFAULT_FALLBACK_MIN_ORDER_SIZE = BigDecimal("0.0001")

/**
 * fallback 数量刻みの既定値。
 */
private val DEFAULT_FALLBACK_SIZE_STEP = BigDecimal("0.0001")

/**
 * fallback tick size の既定値。
 */
private val DEFAULT_FALLBACK_TICK_SIZE = BigDecimal("1")

/**
 * fallback maker 手数料率の既定値。
 */
private val DEFAULT_FALLBACK_MAKER_FEE_RATE = BigDecimal("-0.0001")

/**
 * fallback taker 手数料率の既定値。
 */
private val DEFAULT_FALLBACK_TAKER_FEE_RATE = BigDecimal("0.0005")

/**
 * fallback spread bps の既定値。
 */
private val DEFAULT_FALLBACK_SPREAD_BPS = BigDecimal("2.0")

private fun Map<String, String>.readTradingMode(): TradingMode {
    val rawMode = readOptional(FUKUROU_TRADING_MODE_ENV) ?: return TradingMode.PAPER

    val mode = TradingMode.valueOf(rawMode.uppercase())

    require(mode == TradingMode.PAPER) {
        "$FUKUROU_TRADING_MODE_ENV=LIVE is reserved until live broker is implemented."
    }

    return mode
}

private fun Map<String, String>.readTradingSymbol(): TradingSymbol {
    val rawSymbol = readOptional(FUKUROU_TRADING_SYMBOL_ENV) ?: return TradingSymbol.BTC
    val symbol = TradingSymbol.entries.firstOrNull { candidate ->
        candidate.name == rawSymbol.uppercase() || candidate.apiSymbol == rawSymbol.uppercase()
    }

    return requireNotNull(symbol) {
        "$FUKUROU_TRADING_SYMBOL_ENV must be one of ${TradingSymbol.entries.joinToString { candidate -> candidate.apiSymbol }}."
    }
}

private fun Map<String, String>.readPaperMarketConfig(): PaperMarketConfig {
    return PaperMarketConfig(
        fallbackMakerFeeRate = readDecimal(
            name = FUKUROU_FALLBACK_MAKER_FEE_RATE_ENV,
            defaultValue = DEFAULT_FALLBACK_MAKER_FEE_RATE,
        ),
        fallbackTakerFeeRate = readDecimal(
            name = FUKUROU_FALLBACK_TAKER_FEE_RATE_ENV,
            defaultValue = DEFAULT_FALLBACK_TAKER_FEE_RATE,
        ),
        fallbackSpreadBps = readDecimal(
            name = FUKUROU_FALLBACK_SPREAD_BPS_ENV,
            defaultValue = DEFAULT_FALLBACK_SPREAD_BPS,
        ),
    )
}

private fun Map<String, String>.readSafetyFloorConfig(
    tolerateEconomicEventCalendarCorruption: Boolean,
): SafetyFloorConfig {
    val economicEventBlackoutsRaw = readOptional(FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC_ENV)
    val economicEventBlackouts = readEconomicEventBlackouts(
        rawValue = economicEventBlackoutsRaw,
        tolerateCorruption = tolerateEconomicEventCalendarCorruption,
    )

    return SafetyFloorConfig(
        maxRiskPerTradeRatio = readDecimal(
            name = FUKUROU_MAX_RISK_PER_TRADE_RATIO_ENV,
            defaultValue = BigDecimal("0.02"),
        ),
        maxDrawdownRatio = readDecimal(
            name = FUKUROU_MAX_DRAWDOWN_RATIO_ENV,
            defaultValue = BigDecimal("-0.15"),
        ),
        maxTotalExposureRatio = readDecimal(
            name = FUKUROU_MAX_TOTAL_EXPOSURE_RATIO_ENV,
            defaultValue = BigDecimal("0.80"),
        ),
        minExpectedValueR = readDecimal(
            name = FUKUROU_MIN_EXPECTED_VALUE_R_ENV,
            defaultValue = BigDecimal("0.10"),
        ),
        minExpectedMoveToCostRatio = readDecimal(
            name = FUKUROU_MIN_EXPECTED_MOVE_TO_COST_RATIO_ENV,
            defaultValue = SafetyFloorDefaults.minExpectedMoveToCostRatio,
        ),
        dataQualityCap = readDataQualityCapConfig(),
        maxTakerFeeRatio = readDecimal(
            name = FUKUROU_MAX_TAKER_FEE_RATIO_ENV,
            defaultValue = BigDecimal("0.0010"),
        ),
        economicEventBlackouts = economicEventBlackouts,
        economicEventBlackoutsRaw = economicEventBlackoutsRaw,
        fomcBlackoutCalendar = if (economicEventBlackoutsRaw == null) {
            FomcBlackoutCalendar.fromEvents(economicEventBlackouts)
        } else {
            FomcBlackoutCalendar.fromRaw(economicEventBlackoutsRaw)
        },
        marketSlippageReserveBps = readDecimal(
            name = FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS_ENV,
            defaultValue = DEFAULT_MARKET_SLIPPAGE_BPS,
        ),
    )
}

private fun Map<String, String>.readDataQualityCapConfig(): DataQualityCapConfig {
    return DataQualityCapConfig(
        staleAfter = Duration.ofSeconds(
            readOptional(FUKUROU_DATA_QUALITY_STALE_AFTER_SECONDS_ENV)
                ?.toLong()
                ?: DataQualityCapConfig().staleAfter.seconds,
        ),
        cappedProbability = readDecimal(
            name = FUKUROU_DATA_QUALITY_CAPPED_PROBABILITY_ENV,
            defaultValue = DataQualityCapConfig().cappedProbability,
        ),
    )
}

private fun Map<String, String>.readDecisionProtocolConfig(): DecisionProtocolConfig {
    return DecisionProtocolConfig(
        falsificationFreshnessWindow = Duration.ofSeconds(
            readOptional(FUKUROU_FALSIFICATION_FRESHNESS_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_FALSIFICATION_FRESHNESS_WINDOW.seconds,
        ),
        restingEntryOrderTtl = Duration.ofSeconds(
            readOptional(FUKUROU_RESTING_ENTRY_ORDER_TTL_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_RESTING_ENTRY_ORDER_TTL.seconds,
        ),
    )
}

private fun Map<String, String>.readLlmRunnerConfig(): LlmRunnerConfig {
    return LlmRunnerConfig(
        maxToolCallsPerRun = readOptional(FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT_ENV)
            ?.toInt()
            ?: DEFAULT_MAX_TOOL_CALLS_PER_RUN,
        maxActToolCallsPerRun = readOptional(FUKUROU_MCP_ACT_TOOL_CALL_LIMIT_ENV)
            ?.toInt()
            ?: DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN,
        perRunTimeout = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_RUN_TIMEOUT_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_LLM_PER_RUN_TIMEOUT.seconds,
        ),
        processTerminationGrace = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_PROCESS_TERMINATION_GRACE_SECONDS_ENV)?.toLong()
                ?: DEFAULT_LLM_PROCESS_TERMINATION_GRACE.seconds,
        ),
        persistenceTerminalTimeout = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_PERSISTENCE_TERMINAL_TIMEOUT_SECONDS_ENV)?.toLong()
                ?: DEFAULT_LLM_PERSISTENCE_TERMINAL_TIMEOUT.seconds,
        ),
        maxInvocationsPerHour = readOptional(FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR_ENV)
            ?.toInt()
            ?: DEFAULT_MAX_INVOCATIONS_PER_HOUR,
        maxInvocationsPerDay = readOptional(FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY_ENV)
            ?.toInt()
            ?: DEFAULT_MAX_INVOCATIONS_PER_DAY,
        entryFillReservePerHour = readOptional(FUKUROU_LLM_ENTRY_FILL_RESERVE_PER_HOUR_ENV)
            ?.toInt() ?: DEFAULT_ENTRY_FILL_RESERVE_PER_HOUR,
        entryFillReservePerDay = readOptional(FUKUROU_LLM_ENTRY_FILL_RESERVE_PER_DAY_ENV)
            ?.toInt() ?: DEFAULT_ENTRY_FILL_RESERVE_PER_DAY,
        stopProximityReservePerHour = readOptional(FUKUROU_LLM_STOP_PROXIMITY_RESERVE_PER_HOUR_ENV)
            ?.toInt() ?: DEFAULT_STOP_PROXIMITY_RESERVE_PER_HOUR,
        stopProximityReservePerDay = readOptional(FUKUROU_LLM_STOP_PROXIMITY_RESERVE_PER_DAY_ENV)
            ?.toInt() ?: DEFAULT_STOP_PROXIMITY_RESERVE_PER_DAY,
    )
}

@Suppress("LongMethod")
private fun Map<String, String>.readLlmDaemonConfig(): LlmDaemonConfig {
    return LlmDaemonConfig(
        enabled = readOptional(FUKUROU_LLM_DAEMON_ENABLED_ENV)?.toBooleanStrictOrNull()
            ?: DEFAULT_LLM_DAEMON_ENABLED,
        launchEnabled = readOptional(FUKUROU_LLM_LAUNCH_ENABLED_ENV)?.toBooleanStrictOrNull()
            ?: DEFAULT_LLM_LAUNCH_ENABLED,
        pollInterval = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_DAEMON_POLL_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_LLM_DAEMON_POLL_INTERVAL.seconds,
        ),
        flatHeartbeatInterval = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_FLAT_HEARTBEAT_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_LLM_FLAT_HEARTBEAT_INTERVAL.seconds,
        ),
        holdingCheckInterval = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_HOLDING_CHECK_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_LLM_HOLDING_CHECK_INTERVAL.seconds,
        ),
        priceMoveTriggerEnabled = readOptional(FUKUROU_LLM_TRIGGER_PRICE_MOVE_ENABLED_ENV)?.toBooleanStrictOrNull()
            ?: DEFAULT_LLM_PRICE_MOVE_TRIGGER_ENABLED,
        priceMoveWindow = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_TRIGGER_PRICE_MOVE_WINDOW_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_LLM_PRICE_MOVE_WINDOW.seconds,
        ),
        priceMoveThresholdRatio = readDecimal(
            name = FUKUROU_LLM_TRIGGER_PRICE_MOVE_THRESHOLD_RATIO_ENV,
            defaultValue = DEFAULT_LLM_PRICE_MOVE_THRESHOLD_RATIO,
        ),
        priceMoveCooldown = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_TRIGGER_PRICE_MOVE_COOLDOWN_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_LLM_PRICE_MOVE_COOLDOWN.seconds,
        ),
        entryFillTriggerEnabled = readOptional(FUKUROU_LLM_TRIGGER_ENTRY_FILL_ENABLED_ENV)
            ?.toBooleanStrictOrNull()
            ?: DEFAULT_LLM_ENTRY_FILL_TRIGGER_ENABLED,
        entryFillCooldown = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_TRIGGER_ENTRY_FILL_COOLDOWN_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_LLM_ENTRY_FILL_COOLDOWN.seconds,
        ),
        preFilterEnabled = readOptional(FUKUROU_LLM_PRE_FILTER_ENABLED_ENV)?.toBooleanStrictOrNull()
            ?: DEFAULT_LLM_PRE_FILTER_ENABLED,
        stopProximityTriggerEnabled = readOptional(FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_ENABLED_ENV)
            ?.toBooleanStrictOrNull()
            ?: DEFAULT_LLM_STOP_PROXIMITY_TRIGGER_ENABLED,
        stopProximityRemainingRThreshold = readDecimal(
            name = FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_REMAINING_R_THRESHOLD_ENV,
            defaultValue = DEFAULT_LLM_STOP_PROXIMITY_REMAINING_R_THRESHOLD,
        ),
        stopProximityCooldown = Duration.ofSeconds(
            readOptional(FUKUROU_LLM_TRIGGER_STOP_PROXIMITY_COOLDOWN_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_LLM_STOP_PROXIMITY_COOLDOWN.seconds,
        ),
    )
}

private fun Map<String, String>.readObsidianConfig(): ObsidianConfig {
    return ObsidianConfig(
        enabled = readOptional(FUKUROU_OBSIDIAN_ENABLED_ENV)?.toBooleanStrictOrNull()
            ?: DEFAULT_OBSIDIAN_ENABLED,
        vaultPath = readOptional(FUKUROU_OBSIDIAN_VAULT_PATH_ENV)
            ?: DEFAULT_OBSIDIAN_VAULT_PATH,
        writeInterval = Duration.ofSeconds(
            readOptional(FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS_ENV)
                ?.toLong()
                ?: DEFAULT_OBSIDIAN_WRITE_INTERVAL.seconds,
        ),
    )
}

private fun Map<String, String>.readLlmModelConfig(): LlmModelConfig {
    return LlmModelConfig(
        claudeModel = readOptional(FUKUROU_CLAUDE_MODEL_ENV),
        codexModel = readOptional(FUKUROU_CODEX_MODEL_ENV),
    )
}

private fun Map<String, String>.readLlmRoleAssignments(): LlmRoleAssignments {
    return LlmRoleAssignments(
        proposer = readLlmRoleAssignment(
            providerEnv = FUKUROU_PROPOSER_PROVIDER_ENV,
            modelEnv = FUKUROU_PROPOSER_MODEL_ENV,
            effortEnv = FUKUROU_PROPOSER_EFFORT_ENV,
            defaultProvider = LlmProvider.CLAUDE,
        ),
        falsifier = readLlmRoleAssignment(
            providerEnv = FUKUROU_FALSIFIER_PROVIDER_ENV,
            modelEnv = FUKUROU_FALSIFIER_MODEL_ENV,
            effortEnv = FUKUROU_FALSIFIER_EFFORT_ENV,
            defaultProvider = LlmProvider.CODEX,
        ),
    )
}

private fun Map<String, String>.readLlmRoleAssignment(
    providerEnv: String,
    modelEnv: String,
    effortEnv: String,
    defaultProvider: LlmProvider,
): LlmRoleAssignment {
    val provider = readOptional(providerEnv)?.let(LlmProvider::valueOf) ?: defaultProvider
    val effort = readOptional(effortEnv)?.let(LlmEffort::valueOf) ?: LlmEffort.DEFAULT

    return LlmRoleAssignment(
        provider = provider,
        model = readOptional(modelEnv),
        effort = effort,
    )
}

const val FUKUROU_PROPOSER_PROVIDER_ENV = "FUKUROU_PROPOSER_PROVIDER"
const val FUKUROU_PROPOSER_MODEL_ENV = "FUKUROU_PROPOSER_MODEL"
const val FUKUROU_PROPOSER_EFFORT_ENV = "FUKUROU_PROPOSER_EFFORT"
const val FUKUROU_FALSIFIER_PROVIDER_ENV = "FUKUROU_FALSIFIER_PROVIDER"
const val FUKUROU_FALSIFIER_MODEL_ENV = "FUKUROU_FALSIFIER_MODEL"
const val FUKUROU_FALSIFIER_EFFORT_ENV = "FUKUROU_FALSIFIER_EFFORT"

private fun Map<String, String>.readReflectionConfig(): ReflectionConfig {
    val defaults = ReflectionConfig()

    return ReflectionConfig(
        minInterval = Duration.ofSeconds(
            readOptional(FUKUROU_REFLECTION_MIN_INTERVAL_SECONDS_ENV)
                ?.toLong()
                ?: defaults.minInterval.seconds,
        ),
        queryLimit = readOptional(FUKUROU_REFLECTION_QUERY_LIMIT_ENV)
            ?.toInt()
            ?: defaults.queryLimit,
        calibrationLookbackDays = readOptional(FUKUROU_REFLECTION_CALIBRATION_LOOKBACK_DAYS_ENV)
            ?.toInt()
            ?: defaults.calibrationLookbackDays,
        recentDecisionLimit = readOptional(FUKUROU_REFLECTION_RECENT_DECISION_LIMIT_ENV)
            ?.toInt()
            ?: defaults.recentDecisionLimit,
        sampleWarningTradeCount = readOptional(FUKUROU_REFLECTION_SAMPLE_WARNING_TRADE_COUNT_ENV)
            ?.toInt()
            ?: defaults.sampleWarningTradeCount,
        promptCandidateProvider = readOptional(FUKUROU_REFLECTION_PROMPT_CANDIDATE_PROVIDER_ENV)
            ?.let { value -> LlmProvider.valueOf(value.uppercase()) }
            ?: defaults.promptCandidateProvider,
        promptCandidateTimeout = Duration.ofSeconds(
            readOptional(FUKUROU_REFLECTION_PROMPT_CANDIDATE_TIMEOUT_SECONDS_ENV)
                ?.toLong()
                ?: defaults.promptCandidateTimeout.seconds,
        ),
        promptCandidateMaxAttemptsPerPeriod = readOptional(
            FUKUROU_REFLECTION_PROMPT_CANDIDATE_MAX_ATTEMPTS_ENV,
        )?.toInt() ?: defaults.promptCandidateMaxAttemptsPerPeriod,
    )
}

private fun Map<String, String>.readKillCriterionConfig(): KillCriterionConfig {
    return KillCriterionConfig(
        minClosedTrades = readOptional(FUKUROU_KILL_MIN_CLOSED_TRADES_ENV)
            ?.toInt()
            ?: DEFAULT_KILL_MIN_CLOSED_TRADES,
        minProfitFactor = readDecimal(
            name = FUKUROU_KILL_MIN_PROFIT_FACTOR_ENV,
            defaultValue = DEFAULT_KILL_MIN_PROFIT_FACTOR,
        ),
    )
}

private fun Map<String, String>.readGmoPublicClientConfig(): GmoPublicClientConfig {
    return GmoPublicClientConfig.fromEnvironment(this)
}

private fun Map<String, String>.readGmoPublicWebSocketConfig(): GmoPublicWebSocketConfig {
    val defaults = GmoPublicWebSocketConfig()

    return GmoPublicWebSocketConfig(
        endpoint = readOptional(FUKUROU_GMO_PUBLIC_WEBSOCKET_URL_ENV) ?: defaults.endpoint,
        connectTimeout = readOptional(FUKUROU_GMO_WEBSOCKET_CONNECT_TIMEOUT_MS_ENV)
            ?.toLong()
            ?.let { millis -> Duration.ofMillis(millis) }
            ?: defaults.connectTimeout,
        transportLivenessTimeout = readOptional(FUKUROU_GMO_WEBSOCKET_TRANSPORT_LIVENESS_TIMEOUT_SECONDS_ENV)
            ?.toLong()
            ?.let { seconds -> Duration.ofSeconds(seconds) }
            ?: defaults.transportLivenessTimeout,
        reconnectBackoff = readOptional(FUKUROU_GMO_WEBSOCKET_RECONNECT_BACKOFF_MS_ENV)
            ?.toLong()
            ?.let { millis -> Duration.ofMillis(millis) }
            ?: defaults.reconnectBackoff,
    )
}

private fun readEconomicEventBlackouts(rawValue: String?, tolerateCorruption: Boolean): List<EconomicEventBlackout> {
    if (rawValue == null) return FomcBlackoutCalendar.candidateEvents()

    if (rawValue.startsWith("[")) {
        val result = decodeEconomicEventBlackouts(rawValue)

        return if (tolerateCorruption) result.getOrDefault(emptyList()) else result.getOrThrow()
    }

    val result = runCatching {
        rawValue
            .split(";")
            .map { entry -> entry.trim() }
            .filter { entry -> entry.isNotBlank() }
            .map { entry -> entry.toEconomicEventBlackout() }
    }

    return if (tolerateCorruption) result.getOrDefault(emptyList()) else result.getOrThrow()
}

private fun String.toEconomicEventBlackout(): EconomicEventBlackout {
    val parts = split("|")

    require(parts.size == ECONOMIC_EVENT_BLACKOUT_PART_COUNT) {
        "$FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC_ENV entry must be id|name|eventAtUtc|beforeMinutes|afterMinutes."
    }

    val eventAtRaw = parts[2].trim()

    require(eventAtRaw.endsWith("Z")) {
        "$FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC_ENV eventAtUtc must use UTC Z timestamp."
    }

    return EconomicEventBlackout(
        eventId = parts[0].trim(),
        eventName = parts[1].trim(),
        eventAt = Instant.parse(eventAtRaw),
        blackoutBefore = Duration.ofMinutes(parts[3].trim().toLong()),
        blackoutAfter = Duration.ofMinutes(parts[4].trim().toLong()),
    )
}

private fun Map<String, String>.readDecimal(name: String, defaultValue: BigDecimal): BigDecimal {
    return readOptional(name)?.toBigDecimal() ?: defaultValue
}

private fun Map<String, String>.readOptional(name: String): String? {
    return this[name]
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
}

/**
 * 経済イベント blackout 1 件を構成する field 数。
 */
private const val ECONOMIC_EVENT_BLACKOUT_PART_COUNT = 5
