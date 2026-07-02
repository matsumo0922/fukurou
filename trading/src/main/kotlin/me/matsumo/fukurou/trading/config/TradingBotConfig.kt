package me.matsumo.fukurou.trading.config

import me.matsumo.fukurou.trading.broker.PaperAccountConfig
import me.matsumo.fukurou.trading.broker.PaperExecutionConfig
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoRateLimitConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoRetryConfig
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
import java.math.BigDecimal
import java.time.Duration

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
    val fallbackMinOrderSize: BigDecimal = BigDecimal("0.0001"),
    val fallbackSizeStep: BigDecimal = BigDecimal("0.0001"),
    val fallbackTickSize: BigDecimal = BigDecimal("1"),
    val fallbackMakerFeeRate: BigDecimal = BigDecimal("-0.0001"),
    val fallbackTakerFeeRate: BigDecimal = BigDecimal("0.0005"),
    val fallbackSpreadBps: BigDecimal = BigDecimal("2.0"),
) {
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
 * GMO Public API base URL の環境変数名。
 */
private const val FUKUROU_GMO_PUBLIC_BASE_URL_ENV = "FUKUROU_GMO_PUBLIC_BASE_URL"

/**
 * GMO Public API connect timeout ms の環境変数名。
 */
private const val FUKUROU_GMO_CONNECT_TIMEOUT_MS_ENV = "FUKUROU_GMO_CONNECT_TIMEOUT_MS"

/**
 * GMO Public API request timeout ms の環境変数名。
 */
private const val FUKUROU_GMO_REQUEST_TIMEOUT_MS_ENV = "FUKUROU_GMO_REQUEST_TIMEOUT_MS"

/**
 * GMO symbol rules cache TTL seconds の環境変数名。
 */
private const val FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS_ENV = "FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS"

/**
 * GMO Public REST per-second limit の環境変数名。
 */
private const val FUKUROU_GMO_PUBLIC_REST_PER_SECOND_ENV = "FUKUROU_GMO_PUBLIC_REST_PER_SECOND"

/**
 * GMO Public REST burst limit の環境変数名。
 */
private const val FUKUROU_GMO_PUBLIC_REST_BURST_ENV = "FUKUROU_GMO_PUBLIC_REST_BURST"

/**
 * GMO retry max attempts の環境変数名。
 */
private const val FUKUROU_GMO_RETRY_MAX_ATTEMPTS_ENV = "FUKUROU_GMO_RETRY_MAX_ATTEMPTS"

/**
 * GMO retry initial backoff ms の環境変数名。
 */
private const val FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS_ENV = "FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS"

/**
 * GMO retry max backoff ms の環境変数名。
 */
private const val FUKUROU_GMO_RETRY_MAX_BACKOFF_MS_ENV = "FUKUROU_GMO_RETRY_MAX_BACKOFF_MS"

/**
 * GMO retry backoff multiplier の環境変数名。
 */
private const val FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER_ENV = "FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER"

/**
 * paper 初期残高の既定値。
 */
private val DEFAULT_INITIAL_CASH_JPY = BigDecimal("100000")

/**
 * MARKET / STOP の既定 slippage bps。
 */
private val DEFAULT_MARKET_SLIPPAGE_BPS = BigDecimal("5")

private fun Map<String, String>.readTradingMode(): TradingMode {
    val rawMode = readOptional(FUKUROU_TRADING_MODE_ENV) ?: return TradingMode.PAPER

    return TradingMode.valueOf(rawMode.uppercase())
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
            defaultValue = BigDecimal("-0.0001"),
        ),
        fallbackTakerFeeRate = readDecimal(
            name = FUKUROU_FALLBACK_TAKER_FEE_RATE_ENV,
            defaultValue = BigDecimal("0.0005"),
        ),
        fallbackSpreadBps = readDecimal(
            name = FUKUROU_FALLBACK_SPREAD_BPS_ENV,
            defaultValue = BigDecimal("2.0"),
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
    val permitsPerSecond = readInt(
        name = FUKUROU_GMO_PUBLIC_REST_PER_SECOND_ENV,
        defaultValue = 10,
    )
    val burstSize = readInt(
        name = FUKUROU_GMO_PUBLIC_REST_BURST_ENV,
        defaultValue = permitsPerSecond,
    )

    return GmoPublicClientConfig(
        baseUrl = readOptional(FUKUROU_GMO_PUBLIC_BASE_URL_ENV) ?: "https://api.coin.z.com/public",
        connectTimeout = readDurationMillis(
            name = FUKUROU_GMO_CONNECT_TIMEOUT_MS_ENV,
            defaultValue = Duration.ofSeconds(5),
        ),
        requestTimeout = readDurationMillis(
            name = FUKUROU_GMO_REQUEST_TIMEOUT_MS_ENV,
            defaultValue = Duration.ofSeconds(10),
        ),
        symbolRulesCacheTtl = readDurationSeconds(
            name = FUKUROU_GMO_SYMBOL_RULES_CACHE_TTL_SECONDS_ENV,
            defaultValue = Duration.ofMinutes(10),
        ),
        rateLimit = GmoRateLimitConfig(
            permitsPerSecond = permitsPerSecond,
            burstSize = burstSize,
        ),
        retry = GmoRetryConfig(
            maxAttempts = readInt(
                name = FUKUROU_GMO_RETRY_MAX_ATTEMPTS_ENV,
                defaultValue = 3,
            ),
            initialBackoff = readDurationMillis(
                name = FUKUROU_GMO_RETRY_INITIAL_BACKOFF_MS_ENV,
                defaultValue = Duration.ofMillis(200),
            ),
            maxBackoff = readDurationMillis(
                name = FUKUROU_GMO_RETRY_MAX_BACKOFF_MS_ENV,
                defaultValue = Duration.ofSeconds(2),
            ),
            backoffMultiplier = readLong(
                name = FUKUROU_GMO_RETRY_BACKOFF_MULTIPLIER_ENV,
                defaultValue = 2,
            ),
        ),
    )
}

private fun Map<String, String>.readDecimal(name: String, defaultValue: BigDecimal): BigDecimal {
    return readOptional(name)?.toBigDecimal() ?: defaultValue
}

private fun Map<String, String>.readInt(name: String, defaultValue: Int): Int {
    return readOptional(name)?.toInt() ?: defaultValue
}

private fun Map<String, String>.readLong(name: String, defaultValue: Long): Long {
    return readOptional(name)?.toLong() ?: defaultValue
}

private fun Map<String, String>.readDurationMillis(name: String, defaultValue: Duration): Duration {
    return readOptional(name)
        ?.toLong()
        ?.let { millis -> Duration.ofMillis(millis) }
        ?: defaultValue
}

private fun Map<String, String>.readDurationSeconds(name: String, defaultValue: Duration): Duration {
    return readOptional(name)
        ?.toLong()
        ?.let { seconds -> Duration.ofSeconds(seconds) }
        ?: defaultValue
}

private fun Map<String, String>.readOptional(name: String): String? {
    return this[name]
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
}
