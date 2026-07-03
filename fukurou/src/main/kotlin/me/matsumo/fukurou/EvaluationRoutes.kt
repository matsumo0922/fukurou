package me.matsumo.fukurou

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.Serializable
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.evaluation.BenchmarkPoint
import me.matsumo.fukurou.trading.evaluation.BenchmarkResult
import me.matsumo.fukurou.trading.evaluation.CalibrationBinStats
import me.matsumo.fukurou.trading.evaluation.CalibrationGroupStats
import me.matsumo.fukurou.trading.evaluation.DecisionRunRateStats
import me.matsumo.fukurou.trading.evaluation.EvaluationMath
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.LlmModelTokenStats
import me.matsumo.fukurou.trading.evaluation.LlmProviderCostStats
import me.matsumo.fukurou.trading.evaluation.MarketRegimePerformance
import me.matsumo.fukurou.trading.evaluation.SetupPerformance
import me.matsumo.fukurou.trading.evaluation.TradePerformanceStats
import me.matsumo.fukurou.trading.market.MarketDataSource
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min

/**
 * 評価系エンドポイントを分類する OpenAPI タグ。
 */
private const val EVALUATION_TAG = "評価"

/**
 * API 既定期間の日数。
 */
private const val DEFAULT_EVALUATION_DAYS = 30L

/**
 * benchmark と相場局面に使う日足余白。
 */
private const val DAILY_CANDLE_LOOKBACK_PADDING = 40

/**
 * GMO 日足取得の最大本数。
 */
private const val MAX_DAILY_CANDLE_LIMIT = 500

/**
 * API の日付解釈 timezone。
 */
private val EvaluationZone = ZoneId.of("Asia/Tokyo")

/**
 * 評価系 route を定義する。
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.evaluationRoutes(
    repository: EvaluationRepository?,
    marketDataSource: MarketDataSource?,
    tradingConfig: TradingBotConfig,
    clock: Clock = Clock.systemUTC(),
) {
    get("/evaluation/summary") {
        val dateRange = call.parseEvaluationDateRange(clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(repository) ?: return@get
        val period = dateRange.toPeriod()
        val tradeResult = evaluationRepository.fetchClosedTrades(period).getOrThrow()
        val runCount = evaluationRepository.countDecisionRuns(period).getOrThrow()
        val actionCounts = evaluationRepository.countDecisionsByAction(period).getOrThrow()
        val candles = marketDataSource.fetchDailyCandlesOrEmpty(tradingConfig, dateRange)
        val regimes = EvaluationMath.classifyMarketRegimes(candles, EvaluationZone)

        call.respond(
            EvaluationSummaryResponse(
                period = dateRange.toResponsePeriod(),
                truncated = tradeResult.truncated,
                performance = EvaluationPerformanceResponse.fromStats(EvaluationMath.summarizeTrades(tradeResult.trades)),
                runRates = EvaluationRunRatesResponse.fromStats(EvaluationMath.decisionRunRates(runCount, actionCounts)),
                marketRegimes = EvaluationMath.summarizeByMarketRegime(
                    trades = tradeResult.trades,
                    regimes = regimes,
                    zoneId = EvaluationZone,
                ).map { performance -> EvaluationMarketRegimeResponse.fromPerformance(performance) },
            ),
        )
    }.describe {
        summary = "評価サマリーを取得する"
        description = "closed trade の PF、勝率、期待 R、MAE/MFE、行動率、entry rate、相場局面別成績を返します。"
        tag(EVALUATION_TAG)
        responses {
            HttpStatusCode.OK {
                description = "評価結果です。"
                schema = jsonSchema<EvaluationSummaryResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "from / to の指定が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.InternalServerError {
                description = "評価に必要な repository または外部依存が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/evaluation/setups") {
        val dateRange = call.parseEvaluationDateRange(clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(repository) ?: return@get
        val period = dateRange.toPeriod()
        val tradeResult = evaluationRepository.fetchClosedTrades(period).getOrThrow()
        val candles = marketDataSource.fetchDailyCandlesOrEmpty(tradingConfig, dateRange)
        val regimes = EvaluationMath.classifyMarketRegimes(candles, EvaluationZone)

        call.respond(
            EvaluationSetupsResponse(
                period = dateRange.toResponsePeriod(),
                truncated = tradeResult.truncated,
                setups = EvaluationMath.summarizeBySetup(tradeResult.trades)
                    .map { performance -> EvaluationSetupResponse.fromPerformance(performance) },
                marketRegimes = EvaluationMath.summarizeByMarketRegime(
                    trades = tradeResult.trades,
                    regimes = regimes,
                    zoneId = EvaluationZone,
                ).map { performance -> EvaluationMarketRegimeResponse.fromPerformance(performance) },
            ),
        )
    }.describe {
        summary = "setup 別成績を取得する"
        description = "setup tag 別の件数、PF、勝率、期待 R、MAE/MFE と、相場局面別の同指標を返します。"
        tag(EVALUATION_TAG)
        responses {
            HttpStatusCode.OK {
                description = "評価結果です。"
                schema = jsonSchema<EvaluationSetupsResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "from / to の指定が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.InternalServerError {
                description = "評価に必要な repository または外部依存が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/evaluation/calibration") {
        val dateRange = call.parseEvaluationDateRange(clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(repository) ?: return@get
        val tradeResult = evaluationRepository.fetchClosedTrades(dateRange.toPeriod()).getOrThrow()

        call.respond(
            EvaluationCalibrationResponse(
                period = dateRange.toResponsePeriod(),
                truncated = tradeResult.truncated,
                bySetup = EvaluationMath.calibrationBySetup(tradeResult.trades)
                    .map { group -> EvaluationCalibrationGroupResponse.fromStats(group) },
                byProvider = EvaluationMath.calibrationByProvider(tradeResult.trades)
                    .map { group -> EvaluationCalibrationGroupResponse.fromStats(group) },
            ),
        )
    }.describe {
        summary = "申告 p の較正を取得する"
        description = "closed position に到達した ENTER decision を 0.1 幅 bin に分け、setup tag 別と LLM provider 別の実現勝率を返します。"
        tag(EVALUATION_TAG)
        responses {
            HttpStatusCode.OK {
                description = "評価結果です。"
                schema = jsonSchema<EvaluationCalibrationResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "from / to の指定が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.InternalServerError {
                description = "評価に必要な repository または外部依存が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/evaluation/benchmark") {
        val dateRange = call.parseEvaluationDateRange(clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(repository) ?: return@get
        val evaluationMarketDataSource = call.requireMarketDataSource(marketDataSource) ?: return@get
        val period = dateRange.toPeriod()
        val initialCashJpy = evaluationRepository.fetchInitialCashJpy().getOrThrow()
        val priorPnlJpy = evaluationRepository.sumTradePnlBefore(period.from).getOrThrow()
        val baselineEquityJpy = initialCashJpy.add(priorPnlJpy)
        val dailyPnl = evaluationRepository.fetchDailyTradePnl(period).getOrThrow()
        val candles = evaluationMarketDataSource.getCandles(
            symbol = tradingConfig.symbol,
            interval = CandleInterval.ONE_DAY,
            limit = dateRange.dailyCandleLimit(),
        ).getOrThrow()
        val benchmark = EvaluationMath.benchmark(
            candles = candles,
            dailyPnlFacts = dailyPnl,
            baselineEquityJpy = baselineEquityJpy,
            fromDate = dateRange.fromDate,
            toDateInclusive = dateRange.toDate,
            zoneId = EvaluationZone,
        )

        call.respond(
            EvaluationBenchmarkResponse(
                period = dateRange.toResponsePeriod(),
                assumptionsJa = "buy & hold は開始日 close で全額 BTC を買い、手数料・スリッページを無視します。bot equity は realized PnL のみを close 日に計上し、未実現損益は含めません。",
                baselineEquityJpy = baselineEquityJpy.toDecimalString(),
                points = benchmark.points.map { point -> EvaluationBenchmarkPointResponse.fromPoint(point) },
                returns = EvaluationBenchmarkReturnResponse.fromResult(benchmark),
            ),
        )
    }.describe {
        summary = "benchmark 系列を取得する"
        description = "buy & hold、no-trade、bot realized equity の日次系列と期間 return を返します。"
        tag(EVALUATION_TAG)
        responses {
            HttpStatusCode.OK {
                description = "評価結果です。"
                schema = jsonSchema<EvaluationBenchmarkResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "from / to の指定が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.InternalServerError {
                description = "評価に必要な repository または外部依存が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/evaluation/costs") {
        val dateRange = call.parseEvaluationDateRange(clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(repository) ?: return@get
        val costs = EvaluationMath.summarizeLlmCosts(
            evaluationRepository.fetchLlmPhaseUsages(dateRange.toPeriod()).getOrThrow(),
        )

        call.respond(
            EvaluationCostsResponse(
                period = dateRange.toResponsePeriod(),
                phaseCount = costs.phaseCount,
                missingUsagePhaseCount = costs.missingUsagePhaseCount,
                totalCostUsd = costs.totalCostUsd.toDecimalString(),
                byProvider = costs.byProvider.map { stats -> EvaluationProviderCostResponse.fromStats(stats) },
                byModel = costs.byModel.map { stats -> EvaluationModelTokenResponse.fromStats(stats) },
            ),
        )
    }.describe {
        summary = "LLM cost と usage を取得する"
        description = "runner phase audit に保存された Claude usage を集計し、usage 欠落 phase 数も返します。"
        tag(EVALUATION_TAG)
        responses {
            HttpStatusCode.OK {
                description = "評価結果です。"
                schema = jsonSchema<EvaluationCostsResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "from / to の指定が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.InternalServerError {
                description = "評価に必要な repository または外部依存が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

private suspend fun ApplicationCall.requireEvaluationRepository(repository: EvaluationRepository?): EvaluationRepository? {
    if (repository != null) {
        return repository
    }

    respond(HttpStatusCode.InternalServerError, ErrorResponse("evaluation repository is not configured"))

    return null
}

private suspend fun ApplicationCall.requireMarketDataSource(marketDataSource: MarketDataSource?): MarketDataSource? {
    if (marketDataSource != null) {
        return marketDataSource
    }

    respond(HttpStatusCode.InternalServerError, ErrorResponse("market data source is not configured"))

    return null
}

private suspend fun ApplicationCall.parseEvaluationDateRange(clock: Clock): EvaluationDateRange? {
    val today = LocalDate.now(clock.withZone(EvaluationZone))
    val defaultToDate = today
    val defaultFromDate = today.minusDays(DEFAULT_EVALUATION_DAYS)
    val fromResult = parseDateParameter("from", defaultFromDate)
    val toResult = parseDateParameter("to", defaultToDate)

    if (fromResult.isFailure || toResult.isFailure) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("from and to must be ISO-8601 dates"))

        return null
    }

    val fromDate = fromResult.getOrThrow()
    val toDate = toResult.getOrThrow()

    if (fromDate.isAfter(toDate)) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("from must be less than or equal to to"))

        return null
    }

    return EvaluationDateRange(
        fromDate = fromDate,
        toDate = toDate,
    )
}

private fun ApplicationCall.parseDateParameter(name: String, defaultValue: LocalDate): Result<LocalDate> {
    val rawValue = request.queryParameters[name]?.trim() ?: return Result.success(defaultValue)

    return runCatching { LocalDate.parse(rawValue) }
}

private suspend fun MarketDataSource?.fetchDailyCandlesOrEmpty(
    tradingConfig: TradingBotConfig,
    dateRange: EvaluationDateRange,
) = this
    ?.getCandles(
        symbol = tradingConfig.symbol,
        interval = CandleInterval.ONE_DAY,
        limit = dateRange.dailyCandleLimit(),
    )
    ?.getOrDefault(emptyList())
    .orEmpty()

/**
 * 評価 API の日付範囲。
 *
 * @param fromDate 開始日
 * @param toDate 終了日
 */
private data class EvaluationDateRange(
    val fromDate: LocalDate,
    val toDate: LocalDate,
) {
    fun toPeriod(): EvaluationPeriod {
        return EvaluationPeriod(
            from = fromDate.atStartOfDay(EvaluationZone).toInstant(),
            toExclusive = toDate.plusDays(1).atStartOfDay(EvaluationZone).toInstant(),
        )
    }

    fun toResponsePeriod(): EvaluationPeriodResponse {
        return EvaluationPeriodResponse(
            from = fromDate.toString(),
            to = toDate.toString(),
            timezone = EvaluationZone.id,
        )
    }

    fun dailyCandleLimit(): Int {
        val days = Duration.between(
            fromDate.atStartOfDay(EvaluationZone),
            toDate.plusDays(1).atStartOfDay(EvaluationZone),
        ).toDays()
        val requestedLimit = days.toInt() + DAILY_CANDLE_LOOKBACK_PADDING

        return min(requestedLimit, MAX_DAILY_CANDLE_LIMIT)
    }
}

/**
 * 評価対象期間のレスポンス。
 *
 * @param from 開始日
 * @param to 終了日
 * @param timezone 日付解釈 timezone
 */
@Serializable
data class EvaluationPeriodResponse(
    val from: String,
    val to: String,
    val timezone: String,
)

/**
 * 評価サマリーレスポンス。
 *
 * @param period 評価対象期間
 * @param truncated closed trade fact が取得上限で切り詰められたか
 * @param performance 全体成績
 * @param runRates 起動数に対する action rate
 * @param marketRegimes 相場局面別成績
 */
@Serializable
data class EvaluationSummaryResponse(
    val period: EvaluationPeriodResponse,
    val truncated: Boolean,
    val performance: EvaluationPerformanceResponse,
    val runRates: EvaluationRunRatesResponse,
    val marketRegimes: List<EvaluationMarketRegimeResponse>,
)

/**
 * 成績指標レスポンス。
 *
 * @param tradeCount trade 数
 * @param totalPnlJpy 合計損益
 * @param profitFactor PF。負け trade がない場合は null
 * @param winRate 勝率
 * @param expectedR 実現 R の平均
 * @param averageMaeR MAE_R 平均
 * @param averageMfeR MFE_R 平均
 * @param rUnavailableCount R 系指標の算出不能件数
 * @param maeUnavailableCount MAE_R 算出不能件数
 * @param mfeUnavailableCount MFE_R 算出不能件数
 */
@Serializable
data class EvaluationPerformanceResponse(
    val tradeCount: Int,
    val totalPnlJpy: String,
    val profitFactor: String?,
    val winRate: String?,
    val expectedR: String?,
    val averageMaeR: String?,
    val averageMfeR: String?,
    val rUnavailableCount: Int,
    val maeUnavailableCount: Int,
    val mfeUnavailableCount: Int,
) {
    companion object {
        fun fromStats(stats: TradePerformanceStats): EvaluationPerformanceResponse {
            return EvaluationPerformanceResponse(
                tradeCount = stats.tradeCount,
                totalPnlJpy = stats.totalPnlJpy.toDecimalString(),
                profitFactor = stats.profitFactor?.toDecimalString(),
                winRate = stats.winRate?.toDecimalString(),
                expectedR = stats.expectedR?.toDecimalString(),
                averageMaeR = stats.averageMaeR?.toDecimalString(),
                averageMfeR = stats.averageMfeR?.toDecimalString(),
                rUnavailableCount = stats.rUnavailableCount,
                maeUnavailableCount = stats.maeUnavailableCount,
                mfeUnavailableCount = stats.mfeUnavailableCount,
            )
        }
    }
}

/**
 * 起動数に対する action rate レスポンス。
 *
 * @param decisionRunCount distinct decision run 数
 * @param actionCounts action 別件数
 * @param entryRate ENTER 件数 / 起動数
 * @param noTradeRate NO_TRADE 件数 / 起動数
 */
@Serializable
data class EvaluationRunRatesResponse(
    val decisionRunCount: Int,
    val actionCounts: List<EvaluationActionCountResponse>,
    val entryRate: String?,
    val noTradeRate: String?,
) {
    companion object {
        fun fromStats(stats: DecisionRunRateStats): EvaluationRunRatesResponse {
            return EvaluationRunRatesResponse(
                decisionRunCount = stats.decisionRunCount,
                actionCounts = stats.actionCounts.map { actionCount ->
                    EvaluationActionCountResponse(
                        action = actionCount.action,
                        count = actionCount.count,
                    )
                },
                entryRate = stats.entryRate?.toDecimalString(),
                noTradeRate = stats.noTradeRate?.toDecimalString(),
            )
        }
    }
}

/**
 * action 別件数レスポンス。
 *
 * @param action action 名
 * @param count 件数
 */
@Serializable
data class EvaluationActionCountResponse(
    val action: String,
    val count: Int,
)

/**
 * setup 別成績レスポンス。
 *
 * @param period 評価対象期間
 * @param truncated closed trade fact が取得上限で切り詰められたか
 * @param setups setup tag 別成績
 * @param marketRegimes 相場局面別成績
 */
@Serializable
data class EvaluationSetupsResponse(
    val period: EvaluationPeriodResponse,
    val truncated: Boolean,
    val setups: List<EvaluationSetupResponse>,
    val marketRegimes: List<EvaluationMarketRegimeResponse>,
)

/**
 * setup tag 別成績。
 *
 * @param setupTag setup tag
 * @param performance 成績指標
 */
@Serializable
data class EvaluationSetupResponse(
    val setupTag: String,
    val performance: EvaluationPerformanceResponse,
) {
    companion object {
        fun fromPerformance(performance: SetupPerformance): EvaluationSetupResponse {
            return EvaluationSetupResponse(
                setupTag = performance.setupTag,
                performance = EvaluationPerformanceResponse.fromStats(performance.stats),
            )
        }
    }
}

/**
 * 相場局面別成績。
 *
 * @param trend trend 分類
 * @param volatility volatility 分類
 * @param performance 成績指標
 */
@Serializable
data class EvaluationMarketRegimeResponse(
    val trend: String,
    val volatility: String,
    val performance: EvaluationPerformanceResponse,
) {
    companion object {
        fun fromPerformance(performance: MarketRegimePerformance): EvaluationMarketRegimeResponse {
            return EvaluationMarketRegimeResponse(
                trend = performance.trend.name,
                volatility = performance.volatility.name,
                performance = EvaluationPerformanceResponse.fromStats(performance.stats),
            )
        }
    }
}

/**
 * 較正レスポンス。
 *
 * @param period 評価対象期間
 * @param truncated closed trade fact が取得上限で切り詰められたか
 * @param bySetup setup tag 別較正
 * @param byProvider LLM provider 別較正
 */
@Serializable
data class EvaluationCalibrationResponse(
    val period: EvaluationPeriodResponse,
    val truncated: Boolean,
    val bySetup: List<EvaluationCalibrationGroupResponse>,
    val byProvider: List<EvaluationCalibrationGroupResponse>,
)

/**
 * 較正 group レスポンス。
 *
 * @param groupKey group 名
 * @param bins p bin 一覧
 */
@Serializable
data class EvaluationCalibrationGroupResponse(
    val groupKey: String,
    val bins: List<EvaluationCalibrationBinResponse>,
) {
    companion object {
        fun fromStats(stats: CalibrationGroupStats): EvaluationCalibrationGroupResponse {
            return EvaluationCalibrationGroupResponse(
                groupKey = stats.groupKey,
                bins = stats.bins.map { bin -> EvaluationCalibrationBinResponse.fromStats(bin) },
            )
        }
    }
}

/**
 * 較正 bin レスポンス。
 *
 * @param binIndex bin index
 * @param lowerBoundInclusive 下限
 * @param upperBoundInclusive 上限
 * @param tradeCount trade 数
 * @param averageEstimatedProbability 平均申告 p
 * @param realizedWinRate 実現勝率
 */
@Serializable
data class EvaluationCalibrationBinResponse(
    val binIndex: Int,
    val lowerBoundInclusive: String,
    val upperBoundInclusive: String,
    val tradeCount: Int,
    val averageEstimatedProbability: String?,
    val realizedWinRate: String?,
) {
    companion object {
        fun fromStats(stats: CalibrationBinStats): EvaluationCalibrationBinResponse {
            return EvaluationCalibrationBinResponse(
                binIndex = stats.binIndex,
                lowerBoundInclusive = stats.lowerBoundInclusive.toDecimalString(),
                upperBoundInclusive = stats.upperBoundInclusive.toDecimalString(),
                tradeCount = stats.tradeCount,
                averageEstimatedProbability = stats.averageEstimatedProbability?.toDecimalString(),
                realizedWinRate = stats.realizedWinRate?.toDecimalString(),
            )
        }
    }
}

/**
 * benchmark レスポンス。
 *
 * @param period 評価対象期間
 * @param assumptionsJa benchmark の簡略化前提
 * @param baselineEquityJpy 期間開始時点の基準資金
 * @param points 日次系列
 * @param returns 期間 return
 */
@Serializable
data class EvaluationBenchmarkResponse(
    val period: EvaluationPeriodResponse,
    val assumptionsJa: String,
    val baselineEquityJpy: String,
    val points: List<EvaluationBenchmarkPointResponse>,
    val returns: EvaluationBenchmarkReturnResponse,
)

/**
 * benchmark 日次 point。
 *
 * @param date 日付
 * @param buyAndHoldEquityJpy buy and hold equity
 * @param noTradeEquityJpy no-trade equity
 * @param botEquityJpy bot realized equity
 */
@Serializable
data class EvaluationBenchmarkPointResponse(
    val date: String,
    val buyAndHoldEquityJpy: String,
    val noTradeEquityJpy: String,
    val botEquityJpy: String,
) {
    companion object {
        fun fromPoint(point: BenchmarkPoint): EvaluationBenchmarkPointResponse {
            return EvaluationBenchmarkPointResponse(
                date = point.date.toString(),
                buyAndHoldEquityJpy = point.buyAndHoldEquityJpy.toDecimalString(),
                noTradeEquityJpy = point.noTradeEquityJpy.toDecimalString(),
                botEquityJpy = point.botEquityJpy.toDecimalString(),
            )
        }
    }
}

/**
 * benchmark return レスポンス。
 *
 * @param buyAndHoldReturn buy and hold return
 * @param noTradeReturn no-trade return
 * @param botReturn bot return
 */
@Serializable
data class EvaluationBenchmarkReturnResponse(
    val buyAndHoldReturn: String?,
    val noTradeReturn: String?,
    val botReturn: String?,
) {
    companion object {
        fun fromResult(result: BenchmarkResult): EvaluationBenchmarkReturnResponse {
            return EvaluationBenchmarkReturnResponse(
                buyAndHoldReturn = result.buyAndHoldReturn?.toDecimalString(),
                noTradeReturn = result.noTradeReturn?.toDecimalString(),
                botReturn = result.botReturn?.toDecimalString(),
            )
        }
    }
}

/**
 * LLM cost レスポンス。
 *
 * @param period 評価対象期間
 * @param phaseCount phase 数
 * @param missingUsagePhaseCount usage 欠落 phase 数
 * @param totalCostUsd 合計 cost USD
 * @param byProvider provider 別 cost
 * @param byModel model 別 token
 */
@Serializable
data class EvaluationCostsResponse(
    val period: EvaluationPeriodResponse,
    val phaseCount: Int,
    val missingUsagePhaseCount: Int,
    val totalCostUsd: String,
    val byProvider: List<EvaluationProviderCostResponse>,
    val byModel: List<EvaluationModelTokenResponse>,
)

/**
 * provider 別 cost レスポンス。
 *
 * @param provider provider 名
 * @param totalCostUsd 合計 cost USD
 * @param phaseCount phase 数
 * @param missingUsagePhaseCount usage 欠落 phase 数
 */
@Serializable
data class EvaluationProviderCostResponse(
    val provider: String,
    val totalCostUsd: String,
    val phaseCount: Int,
    val missingUsagePhaseCount: Int,
) {
    companion object {
        fun fromStats(stats: LlmProviderCostStats): EvaluationProviderCostResponse {
            return EvaluationProviderCostResponse(
                provider = stats.provider,
                totalCostUsd = stats.totalCostUsd.toDecimalString(),
                phaseCount = stats.phaseCount,
                missingUsagePhaseCount = stats.missingUsagePhaseCount,
            )
        }
    }
}

/**
 * model 別 token レスポンス。
 *
 * @param model model 名
 * @param inputTokens input token 数
 * @param outputTokens output token 数
 * @param cacheCreationInputTokens cache 作成 input token 数
 * @param cacheReadInputTokens cache read input token 数
 */
@Serializable
data class EvaluationModelTokenResponse(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
) {
    companion object {
        fun fromStats(stats: LlmModelTokenStats): EvaluationModelTokenResponse {
            return EvaluationModelTokenResponse(
                model = stats.model,
                inputTokens = stats.inputTokens,
                outputTokens = stats.outputTokens,
                cacheCreationInputTokens = stats.cacheCreationInputTokens,
                cacheReadInputTokens = stats.cacheReadInputTokens,
            )
        }
    }
}

private fun BigDecimal.toDecimalString(): String {
    return stripTrailingZeros().toPlainString()
}
