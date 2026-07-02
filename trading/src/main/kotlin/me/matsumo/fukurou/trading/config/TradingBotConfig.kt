package me.matsumo.fukurou.trading.config

import me.matsumo.fukurou.trading.broker.PaperAccountConfig
import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientConfig
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
import java.math.BigDecimal

/**
 * 取引 bot 全体で共有する typed config。
 *
 * @param symbol 取引対象 symbol
 * @param mode 取引 mode
 * @param paperAccount paper 口座初期化設定
 * @param paperExecution paper 約定近似設定
 * @param paperMarket paper fallback 取引ルール設定
 * @param safetyFloor override 不可の安全床設定
 * @param gmoPublicClient GMO Public API client 設定
 */
data class TradingBotConfig(
    val symbol: TradingSymbol = TradingSymbol.BTC,
    val mode: TradingMode = TradingMode.PAPER,
    val paperAccount: PaperAccountConfig = PaperAccountConfig(mode = mode),
    val paperExecution: PaperExecutionConfig = PaperExecutionConfig(),
    val paperMarket: PaperMarketConfig = PaperMarketConfig(),
    val safetyFloor: SafetyFloorConfig = SafetyFloorConfig(),
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
 * 安全床の片道 slippage reserve bps の環境変数名。
 */
private const val FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS_ENV = "FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS"

/**
 * paper 初期残高の既定値。
 */
private val DEFAULT_INITIAL_CASH_JPY = BigDecimal("100000")

/**
 * MARKET / STOP の既定 slippage bps。
 */
private val DEFAULT_MARKET_SLIPPAGE_BPS = BigDecimal("5")

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
        marketSlippageReserveBps = readDecimal(
            name = FUKUROU_MARKET_SLIPPAGE_RESERVE_BPS_ENV,
            defaultValue = DEFAULT_MARKET_SLIPPAGE_BPS,
        ),
    )
}

private fun Map<String, String>.readGmoPublicClientConfig(): GmoPublicClientConfig {
    return GmoPublicClientConfig.fromEnvironment(this)
}

private fun Map<String, String>.readDecimal(name: String, defaultValue: BigDecimal): BigDecimal {
    return readOptional(name)?.toBigDecimal() ?: defaultValue
}

private fun Map<String, String>.readOptional(name: String): String? {
    return this[name]
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
}
