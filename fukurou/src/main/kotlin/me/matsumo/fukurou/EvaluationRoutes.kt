@file:Suppress("ImportOrdering")

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
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.EvaluationCohort
import me.matsumo.fukurou.trading.evaluation.BenchmarkCalculationRequest
import me.matsumo.fukurou.trading.evaluation.BenchmarkPoint
import me.matsumo.fukurou.trading.evaluation.BenchmarkResult
import me.matsumo.fukurou.trading.evaluation.CalibrationBinStats
import me.matsumo.fukurou.trading.evaluation.CalibrationGroupStats
import me.matsumo.fukurou.trading.evaluation.DecisionRunRateStats
import me.matsumo.fukurou.trading.evaluation.DailyTradePnlFact
import me.matsumo.fukurou.trading.evaluation.EvaluationAttributionCoverage
import me.matsumo.fukurou.trading.evaluation.EvaluationExclusionSummary
import me.matsumo.fukurou.trading.evaluation.EvaluationMath
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationScope
import me.matsumo.fukurou.trading.evaluation.KillCriterionStats
import me.matsumo.fukurou.trading.evaluation.LlmModelTokenStats
import me.matsumo.fukurou.trading.evaluation.LlmProviderCostStats
import me.matsumo.fukurou.trading.evaluation.MarketRegimePerformance
import me.matsumo.fukurou.trading.evaluation.SetupPerformance
import me.matsumo.fukurou.trading.evaluation.TradePerformanceStats
import me.matsumo.fukurou.trading.evaluation.intersectLifecycle
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import me.matsumo.fukurou.trading.risk.RiskHaltState
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.runner.LlmInvocationAuditor
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

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
 * 評価系 route の依存関係。
 *
 * @param repository 評価 repository
 * @param riskStateRepository risk_state repository
 * @param marketDataSource 評価用 market data source
 * @param tradingConfig 取引 bot 全体の typed config
 * @param clock 日付既定値に使う clock
 */
internal data class EvaluationRouteDependencies(
    val repository: EvaluationRepository?,
    val riskStateRepository: RiskStateRepository?,
    val marketDataSource: MarketDataSource?,
    val tradingConfig: TradingBotConfig,
    val llmInvoker: LlmInvoker? = null,
    val llmInvocationAuditor: LlmInvocationAuditor? = null,
    val environment: Map<String, String> = emptyMap(),
    val database: ExposedDatabase? = null,
    val latestMarketQuoteStore: LatestMarketQuoteStore = LatestMarketQuoteStore(),
    val clock: Clock = Clock.systemUTC(),
    val currentContextSendTimeoutMillis: Long = 1_000,
    val currentContextSendOverride: (suspend (String) -> Unit)? = null,
    val currentContextPublicOrigin: String? = environment["FUKUROU_PUBLIC_ORIGIN"],
)

private suspend fun ApplicationCall.resolveEvaluationScope(repository: EvaluationRepository): EvaluationScope? {
    val result = repository.resolveScope(
        epochId = request.queryParameters["epochId"],
        cohort = request.queryParameters["cohort"],
    )
    return result.getOrElse { throwable ->
        respond(HttpStatusCode.BadRequest, ErrorResponse(throwable.message ?: "evaluation scope is invalid"))
        null
    }
}

private fun EvaluationScope.toResponse(): EvaluationScopeResponse = EvaluationScopeResponse(
    epochId = accountEpochId.toString(),
    cohort = cohort.name,
    executionSemanticsVersion = executionSemanticsVersion,
    initialCashJpy = initialCashJpy.toPlainString(),
    populationState = if (cohort == EvaluationCohort.CURRENT) {
        "AVAILABLE"
    } else {
        "NOT_ATTRIBUTABLE"
    },
)

private fun EvaluationAttributionCoverage.toResponse() =
    EvaluationAttributionCoverageResponse(attributed, missing, total)

/**
 * 評価系 route を定義する。
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.evaluationRoutes(dependencies: EvaluationRouteDependencies) {
    registerEvaluationEpochsRoute(dependencies)
    registerEvaluationSummaryRoute(dependencies)
    registerEvaluationSetupsRoute(dependencies)
    registerEvaluationCalibrationRoute(dependencies)
    registerEvaluationBenchmarkRoute(dependencies)
    registerEvaluationCostsRoute(dependencies)
    evaluationReportRoutes(dependencies)
    currentContextWebSocketRoutes(dependencies)
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerEvaluationEpochsRoute(dependencies: EvaluationRouteDependencies) {
    get("/evaluation/epochs") {
        val repository = call.requireEvaluationRepository(dependencies.repository) ?: return@get
        call.respond(
            EvaluationEpochsResponse(
                epochs = repository.listEpochs().getOrThrow().map { epoch ->
                    EvaluationEpochResponse(
                        epochId = epoch.epochId.toString(),
                        kind = epoch.kind,
                        initialCashJpy = epoch.initialCashJpy.toPlainString(),
                        createdAt = epoch.createdAt.toString(),
                        active = epoch.active,
                    )
                },
            ),
        )
    }.describe {
        summary = "評価 account epoch 一覧を取得する"
        description = "current と legacy を含む immutable account epoch selector の候補を返します。"
        tag(EVALUATION_TAG)
        responses { HttpStatusCode.OK { schema = jsonSchema<EvaluationEpochsResponse>() } }
    }
}

@OptIn(ExperimentalKtorApi::class)
@Suppress("LongMethod")
private fun Route.registerEvaluationSummaryRoute(dependencies: EvaluationRouteDependencies) {
    get("/evaluation/summary") {
        val dateRange = call.parseEvaluationDateRange(dependencies.clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(dependencies.repository) ?: return@get
        val scope = call.resolveEvaluationScope(evaluationRepository) ?: return@get
        val evaluationRiskStateRepository = call.requireRiskStateRepository(dependencies.riskStateRepository) ?: return@get
        val period = dateRange.toPeriod()
        val tradeResult = evaluationRepository.fetchClosedTrades(period, scope = scope).getOrThrow()
        val runCount = evaluationRepository.countDecisionRuns(period, scope).getOrThrow()
        val actionCounts = evaluationRepository.countDecisionsByAction(period, scope).getOrThrow()
        val exclusionSummary = evaluationRepository.fetchExclusionSummary(period, scope).getOrThrow()
        val performance = EvaluationMath.summarizeTrades(tradeResult.trades)
        val deduplication = evaluationRepository.fetchDeduplicationMetrics(period).getOrThrow()
        val killStats = evaluationRepository.fetchKillCriterionStats().getOrThrow()
        val riskState = evaluationRiskStateRepository.current().getOrThrow()
        val candles = call.fetchDailyCandlesOrEmpty(
            marketDataSource = dependencies.marketDataSource,
            tradingConfig = dependencies.tradingConfig,
            dateRange = dateRange,
        ) ?: return@get
        val regimes = EvaluationMath.classifyMarketRegimes(candles, EvaluationZone)

        call.respond(
            EvaluationSummaryResponse(
                period = dateRange.toResponsePeriod(scope),
                scope = scope.toResponse(),
                attributionCoverage = tradeResult.attributionCoverage.toResponse(),
                truncated = tradeResult.truncated,
                performance = EvaluationPerformanceResponse.fromStats(performance),
                killCriterion = EvaluationKillCriterionResponse.fromStats(
                    stats = killStats,
                    minClosedTrades = dependencies.tradingConfig.killCriterion.minClosedTrades,
                    minProfitFactor = dependencies.tradingConfig.killCriterion.minProfitFactor,
                    hardHalt = riskState.state == RiskHaltState.HARD_HALT,
                ),
                runRates = EvaluationRunRatesResponse.fromStats(EvaluationMath.decisionRunRates(runCount, actionCounts)),
                exclusions = exclusionSummary.toResponse(),
                deduplication = DeduplicationResponse.from(deduplication),
                marketRegimes = EvaluationMath.summarizeByMarketRegime(
                    trades = tradeResult.trades,
                    regimes = regimes,
                    zoneId = EvaluationZone,
                ).map { performance -> EvaluationMarketRegimeResponse.fromPerformance(performance) },
            ),
        )
    }.describe {
        summary = "評価サマリーを取得する"
        description = "market-data gap の評価除外を適用した成績と、episode 単位の決定論的 false-suppression proxy を返します。proxy の率は false / (false + valid) で、pending / unknown は率から除外します。"
        tag(EVALUATION_TAG)
        parameters {
            query("epochId") {
                description = "評価対象 account epoch ID。省略時は active epoch です。"
                schema = jsonSchema<String>()
            }
            query("cohort") {
                description = "CURRENT または LEGACY_PRE_WS。省略時は CURRENT です。"
                schema = jsonSchema<String>()
            }
        }
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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerEvaluationSetupsRoute(dependencies: EvaluationRouteDependencies) {
    get("/evaluation/setups") {
        val dateRange = call.parseEvaluationDateRange(dependencies.clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(dependencies.repository) ?: return@get
        val scope = call.resolveEvaluationScope(evaluationRepository) ?: return@get
        val period = dateRange.toPeriod()
        val tradeResult = evaluationRepository.fetchClosedTrades(period, scope = scope).getOrThrow()
        val candles = call.fetchDailyCandlesOrEmpty(
            marketDataSource = dependencies.marketDataSource,
            tradingConfig = dependencies.tradingConfig,
            dateRange = dateRange,
        ) ?: return@get
        val regimes = EvaluationMath.classifyMarketRegimes(candles, EvaluationZone)

        call.respond(
            EvaluationSetupsResponse(
                period = dateRange.toResponsePeriod(scope),
                scope = scope.toResponse(),
                attributionCoverage = tradeResult.attributionCoverage.toResponse(),
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
        parameters {
            query("epochId") {
                description = "評価対象 account epoch ID。省略時は active epoch です。"
                schema = jsonSchema<String>()
            }
            query("cohort") {
                description = "CURRENT または LEGACY_PRE_WS。省略時は CURRENT です。"
                schema = jsonSchema<String>()
            }
        }
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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerEvaluationCalibrationRoute(dependencies: EvaluationRouteDependencies) {
    get("/evaluation/calibration") {
        val dateRange = call.parseEvaluationDateRange(dependencies.clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(dependencies.repository) ?: return@get
        val scope = call.resolveEvaluationScope(evaluationRepository) ?: return@get
        val tradeResult = evaluationRepository.fetchClosedTrades(dateRange.toPeriod(), scope = scope).getOrThrow()

        call.respond(
            EvaluationCalibrationResponse(
                period = dateRange.toResponsePeriod(scope),
                scope = scope.toResponse(),
                attributionCoverage = tradeResult.attributionCoverage.toResponse(),
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
        parameters {
            query("epochId") {
                description = "評価対象 account epoch ID。省略時は active epoch です。"
                schema = jsonSchema<String>()
            }
            query("cohort") {
                description = "CURRENT または LEGACY_PRE_WS。省略時は CURRENT です。"
                schema = jsonSchema<String>()
            }
        }
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
}

@OptIn(ExperimentalKtorApi::class)
@Suppress("LongMethod")
private fun Route.registerEvaluationBenchmarkRoute(dependencies: EvaluationRouteDependencies) {
    get("/evaluation/benchmark") {
        val dateRange = call.parseEvaluationDateRange(dependencies.clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(dependencies.repository) ?: return@get
        val scope = call.resolveEvaluationScope(evaluationRepository) ?: return@get
        val period = dateRange.toPeriod()
        val effectivePeriod = period.intersectLifecycle(scope)
        val periodResponse = dateRange.toResponsePeriod(scope)
        if (effectivePeriod.from == effectivePeriod.toExclusive) {
            call.respond(
                EvaluationBenchmarkResponse(
                    period = periodResponse,
                    scope = scope.toResponse(),
                    attributionCoverage = EvaluationAttributionCoverageResponse(0, 0, 0),
                    truncated = false,
                    assumptionsJa = "epoch lifecycle と requested period の積集合が空のため benchmark は計算しません。",
                    baselineEquityJpy = null,
                    points = emptyList(),
                    returns = null,
                    state = "EMPTY_LIFECYCLE",
                ),
            )
            return@get
        }
        val evaluationMarketDataSource = call.requireMarketDataSource(dependencies.marketDataSource) ?: return@get
        val effectiveFromDate = effectivePeriod.from.atZone(EvaluationZone).toLocalDate()
        val effectiveToDate = effectivePeriod.toExclusive.minusMillis(1).atZone(EvaluationZone).toLocalDate()
        val effectiveDateRange = EvaluationDateRange(effectiveFromDate, effectiveToDate, dateRange.referenceDate)
        val initialCashJpy = scope.initialCashJpy
        val priorPnlJpy = evaluationRepository.sumTradePnlBefore(effectivePeriod.from, scope).getOrThrow()
        val baselineEquityJpy = initialCashJpy.add(priorPnlJpy)
        val tradeResult = evaluationRepository.fetchClosedTrades(effectivePeriod, scope = scope).getOrThrow()
        if (tradeResult.truncated) {
            call.respond(
                EvaluationBenchmarkResponse(
                    period = periodResponse,
                    scope = scope.toResponse(),
                    attributionCoverage = tradeResult.attributionCoverage.toResponse(),
                    truncated = true,
                    assumptionsJa = "取引母集団が取得上限を超えたため benchmark は計算しません。",
                    baselineEquityJpy = null,
                    points = emptyList(),
                    returns = null,
                    state = "TRUNCATED_POPULATION",
                ),
            )
            return@get
        }
        val dailyPnl = tradeResult.trades.map { trade ->
            DailyTradePnlFact(trade.closedAt, trade.tradePnlJpy)
        }
        val dailyCandleLimit = call.requireDailyCandleLimit(effectiveDateRange) ?: return@get
        val candles = evaluationMarketDataSource.getCandles(
            symbol = dependencies.tradingConfig.symbol,
            interval = CandleInterval.ONE_DAY,
            limit = dailyCandleLimit,
        ).getOrThrow()
        val benchmark = EvaluationMath.benchmark(
            BenchmarkCalculationRequest(
                candles = candles,
                dailyPnlFacts = dailyPnl,
                baselineEquityJpy = baselineEquityJpy,
                fromDate = effectiveFromDate,
                toDateInclusive = effectiveToDate,
                zoneId = EvaluationZone,
            ),
        )
        val baselineComparable = scope.cohort !=
            EvaluationCohort.LEGACY_PRE_WS

        call.respond(
            EvaluationBenchmarkResponse(
                period = periodResponse,
                scope = scope.toResponse(),
                attributionCoverage = tradeResult.attributionCoverage.toResponse(),
                truncated = false,
                assumptionsJa = "buy & hold は開始日 close で全額 BTC を買い、手数料・スリッページを無視します。bot equity は realized PnL のみを close 日に計上し、未実現損益は含めません。",
                baselineEquityJpy = baselineEquityJpy.takeIf { baselineComparable }?.toDecimalString(),
                points = benchmark.points.takeIf { baselineComparable }.orEmpty()
                    .map { point -> EvaluationBenchmarkPointResponse.fromPoint(point) },
                returns = EvaluationBenchmarkReturnResponse.fromResult(benchmark).takeIf { baselineComparable },
                state = if (baselineComparable) "AVAILABLE" else "BASELINE_NOT_COMPARABLE",
            ),
        )
    }.describe {
        summary = "benchmark 系列を取得する"
        description = "buy & hold、no-trade、bot realized equity の日次系列と期間 return を返します。取引母集団が取得上限を超えた場合は TRUNCATED_POPULATION と coverage を返します。"
        tag(EVALUATION_TAG)
        parameters {
            query("epochId") {
                description = "評価対象 account epoch ID。省略時は active epoch です。"
                schema = jsonSchema<String>()
            }
            query("cohort") {
                description = "CURRENT または LEGACY_PRE_WS。省略時は CURRENT です。"
                schema = jsonSchema<String>()
            }
        }
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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerEvaluationCostsRoute(dependencies: EvaluationRouteDependencies) {
    get("/evaluation/costs") {
        val dateRange = call.parseEvaluationDateRange(dependencies.clock) ?: return@get
        val evaluationRepository = call.requireEvaluationRepository(dependencies.repository) ?: return@get
        val scope = call.resolveEvaluationScope(evaluationRepository) ?: return@get
        val usageResult = evaluationRepository.fetchLlmPhaseUsages(dateRange.toPeriod(), scope = scope).getOrThrow()
        val costs = EvaluationMath.summarizeLlmCosts(usageResult.facts)

        call.respond(
            EvaluationCostsResponse(
                period = dateRange.toResponsePeriod(scope),
                scope = scope.toResponse(),
                truncated = usageResult.truncated,
                phaseCount = costs.phaseCount,
                missingUsagePhaseCount = costs.missingUsagePhaseCount,
                unpricedPhaseCount = costs.unpricedPhaseCount,
                unattributedTokenPhaseCount = costs.unattributedTokenPhaseCount,
                knownCostUsd = costs.knownCostUsd?.toDecimalString(),
                byProvider = costs.byProvider.map { stats -> EvaluationProviderCostResponse.fromStats(stats) },
                byModel = costs.byModel.map { stats -> EvaluationModelTokenResponse.fromStats(stats) },
            ),
        )
    }.describe {
        summary = "LLM cost と usage を取得する"
        description = "runner phase audit に保存された provider usage と取得済み cost を集計し、usage・cost・model attribution の coverage を返します。"
        tag(EVALUATION_TAG)
        parameters {
            query("epochId") {
                description = "評価対象 account epoch ID。省略時は active epoch です。"
                schema = jsonSchema<String>()
            }
            query("cohort") {
                description = "CURRENT または LEGACY_PRE_WS。省略時は CURRENT です。"
                schema = jsonSchema<String>()
            }
        }
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

private suspend fun ApplicationCall.requireEvaluationRepository(
    repository: EvaluationRepository?,
): EvaluationRepository? {
    if (repository != null) {
        return repository
    }

    respond(HttpStatusCode.InternalServerError, ErrorResponse("evaluation repository is not configured"))

    return null
}

private suspend fun ApplicationCall.requireRiskStateRepository(repository: RiskStateRepository?): RiskStateRepository? {
    if (repository != null) {
        return repository
    }

    respond(HttpStatusCode.InternalServerError, ErrorResponse("risk state repository is not configured"))

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
    val defaultFromDate = today.minusDays(DEFAULT_EVALUATION_DAYS)
    val fromResult = parseDateParameter("from", defaultFromDate)
    val toResult = parseDateParameter("to", today)

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
        referenceDate = today,
    )
}

private fun ApplicationCall.parseDateParameter(name: String, defaultValue: LocalDate): Result<LocalDate> {
    val rawValue = request.queryParameters[name]?.trim() ?: return Result.success(defaultValue)

    return runCatching { LocalDate.parse(rawValue) }
}

private suspend fun ApplicationCall.fetchDailyCandlesOrEmpty(
    marketDataSource: MarketDataSource?,
    tradingConfig: TradingBotConfig,
    dateRange: EvaluationDateRange,
): List<Candle>? {
    val evaluationMarketDataSource = marketDataSource ?: return emptyList()
    val dailyCandleLimit = requireDailyCandleLimit(dateRange) ?: return null

    return evaluationMarketDataSource.getCandles(
        symbol = tradingConfig.symbol,
        interval = CandleInterval.ONE_DAY,
        limit = dailyCandleLimit,
    )
        .getOrDefault(emptyList())
}

private suspend fun ApplicationCall.requireDailyCandleLimit(dateRange: EvaluationDateRange): Int? {
    val dailyCandleLimit = dateRange.dailyCandleLimit()
    val withinLimit = dailyCandleLimit <= MAX_DAILY_CANDLE_LIMIT.toLong()

    if (withinLimit) {
        return dailyCandleLimit.toInt()
    }

    val errorResponse = ErrorResponse(
        "from/to window requires $dailyCandleLimit daily candles; maximum is $MAX_DAILY_CANDLE_LIMIT",
    )

    respond(HttpStatusCode.BadRequest, errorResponse)

    return null
}

/**
 * 評価 API の日付範囲。
 *
 * @param fromDate 開始日
 * @param toDate 終了日
 * @param referenceDate 最新 N 本の日足取得で到達すべき基準日
 */
private data class EvaluationDateRange(
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val referenceDate: LocalDate,
) {
    fun toPeriod(): EvaluationPeriod {
        return EvaluationPeriod(
            from = fromDate.atStartOfDay(EvaluationZone).toInstant(),
            toExclusive = toDate
                .plusDays(1)
                .atStartOfDay(EvaluationZone)
                .toInstant(),
        )
    }

    fun toResponsePeriod(scope: EvaluationScope): EvaluationPeriodResponse {
        val requested = toPeriod()
        val effective = requested.intersectLifecycle(scope)
        val empty = effective.from == effective.toExclusive
        val effectiveFromDate = effective.from.atZone(EvaluationZone).toLocalDate().takeUnless { empty }
        val effectiveToDate = effective.toExclusive.minusMillis(1)
            .atZone(EvaluationZone).toLocalDate().takeUnless { empty }
        val state = if (empty) {
            "EMPTY_LIFECYCLE"
        } else if (effective.from == requested.from && effective.toExclusive == requested.toExclusive) {
            "FULL_REQUESTED_PERIOD"
        } else {
            "PARTIAL_LIFECYCLE"
        }
        return EvaluationPeriodResponse(
            from = fromDate.toString(),
            to = toDate.toString(),
            timezone = EvaluationZone.id,
            effectiveFrom = effectiveFromDate?.toString(),
            effectiveTo = effectiveToDate?.toString(),
            populationState = state,
            effectiveDays = Duration.between(effective.from, effective.toExclusive).toDays(),
        )
    }

    fun dailyCandleLimit(): Long {
        val lookbackStartDate = fromDate.minusDays(DAILY_CANDLE_LOOKBACK_PADDING.toLong())
        val latestNeededDate = maxOf(toDate, referenceDate)
        val days = Duration.between(
            lookbackStartDate.atStartOfDay(EvaluationZone),
            latestNeededDate.plusDays(1).atStartOfDay(EvaluationZone),
        ).toDays()

        return days
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
    val effectiveFrom: String?,
    val effectiveTo: String?,
    val populationState: String,
    val effectiveDays: Long,
)

/** evaluation response が使用した immutable epoch/cohort scope。 */
@Serializable
data class EvaluationScopeResponse(
    val epochId: String,
    val cohort: String,
    val executionSemanticsVersion: String?,
    val initialCashJpy: String,
    val populationState: String = "AVAILABLE",
)

/** immutable epoch selector response。 */
@Serializable
data class EvaluationEpochsResponse(val epochs: List<EvaluationEpochResponse>)

/** immutable epoch selector item。 */
@Serializable
data class EvaluationEpochResponse(
    val epochId: String,
    val kind: String,
    val initialCashJpy: String,
    val createdAt: String,
    val active: Boolean,
)

/** execution-based attribution coverage。 */
@Serializable
data class EvaluationAttributionCoverageResponse(
    val attributed: Int,
    val missing: Int,
    val total: Int,
)

/**
 * 評価サマリーレスポンス。
 *
 * @param period 評価対象期間
 * @param truncated closed trade fact が取得上限で切り詰められたか
 * @param performance 全体成績
 * @param killCriterion kill 基準への近接度
 * @param runRates 起動数に対する action rate
 * @param exclusions market-data gap による評価除外
 * @param marketRegimes 相場局面別成績
 */
@Serializable
data class EvaluationSummaryResponse(
    val period: EvaluationPeriodResponse,
    val scope: EvaluationScopeResponse,
    val attributionCoverage: EvaluationAttributionCoverageResponse,
    val truncated: Boolean,
    val performance: EvaluationPerformanceResponse,
    val killCriterion: EvaluationKillCriterionResponse,
    val runRates: EvaluationRunRatesResponse,
    val exclusions: EvaluationExclusionSummaryResponse = EvaluationExclusionSummaryResponse(),
    val deduplication: DeduplicationResponse = DeduplicationResponse(),
    val marketRegimes: List<EvaluationMarketRegimeResponse>,
)

/** Phase 1 decision deduplication telemetry。 */
@Serializable
data class DeduplicationResponse(
    val decisionIdentityCoverage: Double? = null,
    val intentIdentityCoverage: Double? = null,
    val shadowClassificationCoverage: Double? = null,
    val classificationCounts: Map<String, Int> = emptyMap(),
    val rawSuppressedHeartbeatCount: Int = 0,
    val uniqueEpisodeCount: Int = 0,
    val falseSuppressionRate: Double? = null,
    val falseSuppressionCount: Int = 0,
    val validSuppressionCount: Int = 0,
    val resolvedCount: Int = 0,
    val pendingCount: Int = 0,
    val unknownCount: Int = 0,
    val restingOnlyDaemonFullRunCount: Int = 0,
    val manualFullRunCount: Int = 0,
) {
    companion object {
        fun from(metrics: me.matsumo.fukurou.trading.evaluation.DeduplicationMetrics): DeduplicationResponse {
            return DeduplicationResponse(
                decisionIdentityCoverage = coverage(metrics.decisionComplete, metrics.decisionEligible),
                intentIdentityCoverage = coverage(metrics.intentComplete, metrics.intentEligible),
                shadowClassificationCoverage = coverage(metrics.shadowComplete, metrics.shadowEligible),
                classificationCounts = metrics.classificationCounts,
                rawSuppressedHeartbeatCount = metrics.rawSuppressedHeartbeatCount,
                uniqueEpisodeCount = metrics.uniqueEpisodeCount,
                falseSuppressionRate = coverage(
                    metrics.falseSuppressionCount,
                    metrics.falseSuppressionCount + metrics.validSuppressionCount,
                ),
                falseSuppressionCount = metrics.falseSuppressionCount,
                validSuppressionCount = metrics.validSuppressionCount,
                resolvedCount = metrics.falseSuppressionCount + metrics.validSuppressionCount,
                pendingCount = metrics.pendingCount,
                unknownCount = metrics.unknownCount,
                restingOnlyDaemonFullRunCount = metrics.restingOnlyDaemonFullRunCount,
                manualFullRunCount = metrics.manualFullRunCount,
            )
        }

        private fun coverage(complete: Int, eligible: Int): Double? {
            return if (eligible == 0) null else complete.toDouble() / eligible
        }
    }
}

/** market-data gap による評価除外 summary。 */
@Serializable
data class EvaluationExclusionSummaryResponse(
    val orderCount: Int = 0,
    val decisionRunCount: Int = 0,
    val tradeCount: Int = 0,
    val reasons: Map<String, Int> = emptyMap(),
)

private fun EvaluationExclusionSummary.toResponse(): EvaluationExclusionSummaryResponse {
    return EvaluationExclusionSummaryResponse(
        orderCount = orderCount,
        decisionRunCount = decisionRunCount,
        tradeCount = positionCount,
        reasons = reasons,
    )
}

/**
 * kill 基準への近接度レスポンス。
 *
 * @param closedTrades closed trade 数
 * @param currentProfitFactor 現在の PF
 * @param minClosedTrades kill 判定に必要な最小 closed trade 数
 * @param minProfitFactor kill 判定の PF 下限
 * @param remainingTrades minClosedTrades までの残り trade 数
 * @param breached 現在の stats が kill 基準へ到達しているか
 * @param hardHalt 現在 sticky HARD_HALT 中か
 */
@Serializable
data class EvaluationKillCriterionResponse(
    val closedTrades: Int,
    val currentProfitFactor: String?,
    val minClosedTrades: Int,
    val minProfitFactor: String,
    val remainingTrades: Int,
    val breached: Boolean,
    val hardHalt: Boolean,
) {
    companion object {
        fun fromStats(
            stats: KillCriterionStats,
            minClosedTrades: Int,
            minProfitFactor: BigDecimal,
            hardHalt: Boolean,
        ): EvaluationKillCriterionResponse {
            val remainingTrades = (minClosedTrades - stats.closedTrades).coerceAtLeast(0)
            val profitFactor = stats.profitFactor
            val enoughTrades = stats.closedTrades >= minClosedTrades
            val breached = profitFactor != null && enoughTrades && profitFactor < minProfitFactor

            return EvaluationKillCriterionResponse(
                closedTrades = stats.closedTrades,
                currentProfitFactor = profitFactor?.toDecimalString(),
                minClosedTrades = minClosedTrades,
                minProfitFactor = minProfitFactor.toDecimalString(),
                remainingTrades = remainingTrades,
                breached = breached,
                hardHalt = hardHalt,
            )
        }
    }
}

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
    val scope: EvaluationScopeResponse,
    val attributionCoverage: EvaluationAttributionCoverageResponse,
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
    val scope: EvaluationScopeResponse,
    val attributionCoverage: EvaluationAttributionCoverageResponse,
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
                upperBoundInclusive = stats.upperBound.toDecimalString(),
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
    val scope: EvaluationScopeResponse,
    val attributionCoverage: EvaluationAttributionCoverageResponse,
    val truncated: Boolean,
    val assumptionsJa: String,
    val baselineEquityJpy: String?,
    val points: List<EvaluationBenchmarkPointResponse>,
    val returns: EvaluationBenchmarkReturnResponse?,
    val state: String,
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
 * @param truncated phase usage fact が取得上限で切り詰められたか
 * @param phaseCount phase 数
 * @param missingUsagePhaseCount usage 欠落 phase 数
 * @param unpricedPhaseCount monetary cost 未取得 phase 数。usage 欠落 phase を含む
 * @param unattributedTokenPhaseCount model attribution 欠落 phase 数
 * @param knownCostUsd 取得済み cost の合計 USD。全 phase で未取得なら null
 * @param byProvider provider 別 cost
 * @param byModel model 別 token
 */
@Serializable
data class EvaluationCostsResponse(
    val period: EvaluationPeriodResponse,
    val scope: EvaluationScopeResponse,
    val truncated: Boolean,
    val phaseCount: Int,
    val missingUsagePhaseCount: Int,
    val unpricedPhaseCount: Int,
    val unattributedTokenPhaseCount: Int,
    val knownCostUsd: String?,
    val byProvider: List<EvaluationProviderCostResponse>,
    val byModel: List<EvaluationModelTokenResponse>,
)

/**
 * provider 別 cost レスポンス。
 *
 * @param provider provider 名
 * @param knownCostUsd 取得済み cost の合計 USD。全 phase で未取得なら null
 * @param phaseCount phase 数
 * @param missingUsagePhaseCount usage 欠落 phase 数
 * @param unpricedPhaseCount monetary cost 未取得 phase 数。usage 欠落 phase を含む
 * @param unattributedTokenPhaseCount model attribution 欠落 phase 数
 */
@Serializable
data class EvaluationProviderCostResponse(
    val provider: String,
    val knownCostUsd: String?,
    val phaseCount: Int,
    val missingUsagePhaseCount: Int,
    val unpricedPhaseCount: Int,
    val unattributedTokenPhaseCount: Int,
) {
    companion object {
        fun fromStats(stats: LlmProviderCostStats): EvaluationProviderCostResponse {
            return EvaluationProviderCostResponse(
                provider = stats.provider,
                knownCostUsd = stats.knownCostUsd?.toDecimalString(),
                phaseCount = stats.phaseCount,
                missingUsagePhaseCount = stats.missingUsagePhaseCount,
                unpricedPhaseCount = stats.unpricedPhaseCount,
                unattributedTokenPhaseCount = stats.unattributedTokenPhaseCount,
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
 * @param reasoningOutputTokens output token のうち reasoning token 数
 * @param cacheCreationInputTokens cache 作成 input token 数
 * @param cacheReadInputTokens cache read input token 数
 */
@Serializable
data class EvaluationModelTokenResponse(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningOutputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
) {
    companion object {
        fun fromStats(stats: LlmModelTokenStats): EvaluationModelTokenResponse {
            return EvaluationModelTokenResponse(
                model = stats.model,
                inputTokens = stats.inputTokens,
                outputTokens = stats.outputTokens,
                reasoningOutputTokens = stats.reasoningOutputTokens,
                cacheCreationInputTokens = stats.cacheCreationInputTokens,
                cacheReadInputTokens = stats.cacheReadInputTokens,
            )
        }
    }
}

private fun BigDecimal.toDecimalString(): String {
    return stripTrailingZeros().toPlainString()
}
