package me.matsumo.fukurou.trading.config

import me.matsumo.fukurou.trading.broker.PaperAccountConfig
import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientConfig
import me.matsumo.fukurou.trading.safety.EconomicEventBlackout
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
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
 * @param gmoPublicClient GMO Public API client 設定
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
    val gmoPublicClient: GmoPublicClientConfig = GmoPublicClientConfig(),
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
                ),
                paperMarket = paperMarket,
                safetyFloor = environment.readSafetyFloorConfig(),
                decisionProtocol = environment.readDecisionProtocolConfig(),
                runner = environment.readLlmRunnerConfig(),
                daemon = environment.readLlmDaemonConfig(),
                gmoPublicClient = environment.readGmoPublicClientConfig(),
            )
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
 * @param maxInvocationsPerHour 直近 1 時間に許可する runner 起動数
 * @param maxInvocationsPerDay 直近 24 時間に許可する runner 起動数
 */
data class LlmRunnerConfig(
    val maxToolCallsPerRun: Int = DEFAULT_MAX_TOOL_CALLS_PER_RUN,
    val maxActToolCallsPerRun: Int = DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN,
    val perRunTimeout: Duration = DEFAULT_LLM_PER_RUN_TIMEOUT,
    val maxInvocationsPerHour: Int = DEFAULT_MAX_INVOCATIONS_PER_HOUR,
    val maxInvocationsPerDay: Int = DEFAULT_MAX_INVOCATIONS_PER_DAY,
) {
    init {
        val toolLimitIsConservative = maxToolCallsPerRun in 1..DEFAULT_MAX_TOOL_CALLS_PER_RUN
        val actLimitIsConservative = maxActToolCallsPerRun in 1..DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN
        val timeoutIsPositive = !perRunTimeout.isNegative && !perRunTimeout.isZero
        val timeoutIsConservative = timeoutIsPositive && perRunTimeout <= DEFAULT_LLM_PER_RUN_TIMEOUT
        val hourlyLimitIsConservative = maxInvocationsPerHour in 1..DEFAULT_MAX_INVOCATIONS_PER_HOUR
        val dailyLimitIsConservative = maxInvocationsPerDay in 1..DEFAULT_MAX_INVOCATIONS_PER_DAY
        val actLimitFitsTotal = maxActToolCallsPerRun <= maxToolCallsPerRun

        require(toolLimitIsConservative) {
            "maxToolCallsPerRun must be between 1 and $DEFAULT_MAX_TOOL_CALLS_PER_RUN."
        }
        require(actLimitIsConservative) {
            "maxActToolCallsPerRun must be between 1 and $DEFAULT_MAX_ACT_TOOL_CALLS_PER_RUN."
        }
        require(timeoutIsConservative) {
            "perRunTimeout must be greater than 0 and less than or equal to ${DEFAULT_LLM_PER_RUN_TIMEOUT.seconds} seconds."
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
 */
data class LlmDaemonConfig(
    val enabled: Boolean = DEFAULT_LLM_DAEMON_ENABLED,
    val pollInterval: Duration = DEFAULT_LLM_DAEMON_POLL_INTERVAL,
    val flatHeartbeatInterval: Duration = DEFAULT_LLM_FLAT_HEARTBEAT_INTERVAL,
    val holdingCheckInterval: Duration = DEFAULT_LLM_HOLDING_CHECK_INTERVAL,
    val launchReservationStaleAfter: Duration = DEFAULT_LLM_LAUNCH_RESERVATION_STALE_AFTER,
) {
    init {
        val pollIntervalIsConservative = pollInterval >= DEFAULT_LLM_DAEMON_POLL_INTERVAL
        val flatHeartbeatIsConservative = flatHeartbeatInterval >= DEFAULT_LLM_FLAT_HEARTBEAT_INTERVAL
        val holdingCheckIsConservative = holdingCheckInterval >= DEFAULT_LLM_HOLDING_CHECK_INTERVAL
        val reservationStaleIsPositive = !launchReservationStaleAfter.isNegative && !launchReservationStaleAfter.isZero

        require(pollIntervalIsConservative) {
            "pollInterval must be greater than or equal to ${DEFAULT_LLM_DAEMON_POLL_INTERVAL.seconds} seconds."
        }
        require(flatHeartbeatIsConservative) {
            "flatHeartbeatInterval must be greater than or equal to ${DEFAULT_LLM_FLAT_HEARTBEAT_INTERVAL.toHours()} hours."
        }
        require(holdingCheckIsConservative) {
            "holdingCheckInterval must be greater than or equal to ${DEFAULT_LLM_HOLDING_CHECK_INTERVAL.toHours()} hours."
        }
        require(reservationStaleIsPositive) {
            "launchReservationStaleAfter must be greater than 0."
        }
    }
}

/**
 * decision / Falsifier protocol の保守的な設定。
 *
 * @param falsificationFreshnessWindow fresh APPROVED とみなす時間窓
 */
data class DecisionProtocolConfig(
    val falsificationFreshnessWindow: Duration = DEFAULT_FALSIFICATION_FRESHNESS_WINDOW,
) {
    init {
        val windowIsPositive = !falsificationFreshnessWindow.isNegative && !falsificationFreshnessWindow.isZero
        val windowIsAtOrBelowDefault = falsificationFreshnessWindow <= DEFAULT_FALSIFICATION_FRESHNESS_WINDOW

        require(windowIsPositive && windowIsAtOrBelowDefault) {
            "falsificationFreshnessWindow must be greater than 0 and less than or equal to 120 seconds."
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
 * fresh falsification 判定 window 秒数の環境変数名。
 */
private const val FUKUROU_FALSIFICATION_FRESHNESS_SECONDS_ENV = "FUKUROU_FALSIFICATION_FRESHNESS_SECONDS"

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

/**
 * 直近 1 時間の runner 起動上限の環境変数名。
 */
private const val FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR_ENV = "FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR"

/**
 * 直近 24 時間の runner 起動上限の環境変数名。
 */
private const val FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY_ENV = "FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY"

/**
 * LLM daemon scheduler 有効化の環境変数名。
 */
private const val FUKUROU_LLM_DAEMON_ENABLED_ENV = "FUKUROU_LLM_DAEMON_ENABLED"

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
 * paper 初期残高の既定値。
 */
private val DEFAULT_INITIAL_CASH_JPY = BigDecimal("100000")

/**
 * MARKET / STOP の既定 slippage bps。
 */
private val DEFAULT_MARKET_SLIPPAGE_BPS = BigDecimal("5")

/**
 * fresh falsification の既定 window。
 */
private val DEFAULT_FALSIFICATION_FRESHNESS_WINDOW = Duration.ofSeconds(120)

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
 * 直近 1 時間の既定 runner 起動上限。
 */
const val DEFAULT_MAX_INVOCATIONS_PER_HOUR = 1

/**
 * 直近 24 時間の既定 runner 起動上限。
 */
const val DEFAULT_MAX_INVOCATIONS_PER_DAY = 4

/**
 * LLM daemon scheduler 有効化の既定値。
 */
const val DEFAULT_LLM_DAEMON_ENABLED = false

/**
 * LLM daemon loop poll 間隔の既定値。
 */
val DEFAULT_LLM_DAEMON_POLL_INTERVAL: Duration = Duration.ofMinutes(1)

/**
 * flat 状態 heartbeat 間隔の既定値。
 */
val DEFAULT_LLM_FLAT_HEARTBEAT_INTERVAL: Duration = Duration.ofHours(6)

/**
 * holding 状態 LLM 確認間隔の既定値。
 */
val DEFAULT_LLM_HOLDING_CHECK_INTERVAL: Duration = Duration.ofHours(3)

/**
 * LLM 起動予約を stale とみなす既定時間。
 */
val DEFAULT_LLM_LAUNCH_RESERVATION_STALE_AFTER: Duration = Duration.ofMinutes(30)

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

private fun Map<String, String>.readSafetyFloorConfig(): SafetyFloorConfig {
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
            defaultValue = BigDecimal("3.0"),
        ),
        maxTakerFeeRatio = readDecimal(
            name = FUKUROU_MAX_TAKER_FEE_RATIO_ENV,
            defaultValue = BigDecimal("0.0010"),
        ),
        economicEventBlackouts = readEconomicEventBlackouts(),
        marketSlippageReserveBps = readDecimal(
            name = FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS_ENV,
            defaultValue = DEFAULT_MARKET_SLIPPAGE_BPS,
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
        maxInvocationsPerHour = readOptional(FUKUROU_LLM_MAX_INVOCATIONS_PER_HOUR_ENV)
            ?.toInt()
            ?: DEFAULT_MAX_INVOCATIONS_PER_HOUR,
        maxInvocationsPerDay = readOptional(FUKUROU_LLM_MAX_INVOCATIONS_PER_DAY_ENV)
            ?.toInt()
            ?: DEFAULT_MAX_INVOCATIONS_PER_DAY,
    )
}

private fun Map<String, String>.readLlmDaemonConfig(): LlmDaemonConfig {
    return LlmDaemonConfig(
        enabled = readOptional(FUKUROU_LLM_DAEMON_ENABLED_ENV)?.toBooleanStrictOrNull()
            ?: DEFAULT_LLM_DAEMON_ENABLED,
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
    )
}

private fun Map<String, String>.readGmoPublicClientConfig(): GmoPublicClientConfig {
    return GmoPublicClientConfig.fromEnvironment(this)
}

private fun Map<String, String>.readEconomicEventBlackouts(): List<EconomicEventBlackout> {
    val rawValue = readOptional(FUKUROU_ECONOMIC_EVENT_BLACKOUTS_UTC_ENV) ?: return emptyList()

    return rawValue
        .split(";")
        .map { entry -> entry.trim() }
        .filter { entry -> entry.isNotBlank() }
        .map { entry -> entry.toEconomicEventBlackout() }
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
