@file:Suppress("ImportOrdering")

package me.matsumo.fukurou

import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import me.matsumo.fukurou.trading.audit.CommandEventFeedReader
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.broker.PaperLedgerRepository
import me.matsumo.fukurou.trading.config.RuntimeConfigAdminService
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigResolver
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.DefaultManualLlmLaunchService
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchService
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.persistence.ExposedCommandEventLog
import me.matsumo.fukurou.trading.persistence.ExposedDecisionRepository
import me.matsumo.fukurou.trading.persistence.ExposedEvaluationRepository
import me.matsumo.fukurou.trading.persistence.ExposedPaperLedgerRepository
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateCommandService
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateRepository
import me.matsumo.fukurou.trading.persistence.ExposedRuntimeConfigRepository
import me.matsumo.fukurou.trading.persistence.RuntimeConfigPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import java.io.File
import java.time.Clock
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * readiness 判定。外部依存が利用可能かを返す。
 */
fun interface ReadinessProbe {
    suspend fun isReady(): Boolean
}

/**
 * Ktor アプリケーションのエントリポイント。
 *
 * @param readinessProbe `/health/ready` で参照する readiness 判定。null なら環境変数の DB 設定を用いる
 * @param revision `/revision` で返す稼働中 image の revision。既定は環境変数から読む
 * @param reconcilerStatus ProtectionReconciler の状態 holder
 * @param clock Reconciler readiness の鮮度判定に使う clock
 * @param evaluationRepository 評価 API 用 repository。null なら DB 設定から構築する
 * @param evaluationRiskStateRepository 評価 API 用 risk_state repository。null なら DB 設定から構築する
 * @param evaluationMarketDataSource 評価 API 用 market data source。null なら DB 設定時だけ GMO source を構築する
 * @param opsRiskStateCommandService ops API 用 risk_state command service。null なら DB 設定から構築する
 * @param opsManualLlmLaunchService ops API 用 manual LLM launch service。null なら DB と runner env から構築する
 * @param opsLlmAuthService ops API 用 CLI auth service。null なら環境変数から構築する
 * @param opsDecisionRepository ops API 用 decision repository。null なら DB 設定から構築する
 * @param opsPaperLedgerRepository ops API 用 paper ledger repository。null なら DB 設定から構築する
 * @param opsCommandEventLog ops API 用 command_event_log writer。null なら DB 設定から構築する
 * @param opsCommandEventFeedReader ops API 用 command_event_log feed reader。null なら DB 設定から構築する
 * @param opsRuntimeConfigAdminService ops API 用 runtime config admin service。null なら DB 設定から構築する
 * @param tradingConfig trading runtime config
 * @param runtimeConfigEnvironment runtime config catalog API で参照する環境変数 map
 * @param webRoot WebUI の build output を配信する filesystem root。null なら Web 配信を無効にする
 */
fun Application.module(
    readinessProbe: ReadinessProbe? = null,
    revision: String = currentRevisionFromEnv(),
    reconcilerStatus: MutableReconcilerStatus = MutableReconcilerStatus(),
    clock: Clock = Clock.systemUTC(),
    evaluationRepository: EvaluationRepository? = null,
    evaluationRiskStateRepository: RiskStateRepository? = null,
    evaluationMarketDataSource: MarketDataSource? = null,
    opsRiskStateCommandService: RiskStateCommandService? = null,
    opsManualLlmLaunchService: ManualLlmLaunchService? = null,
    opsLlmAuthService: LlmAuthService? = null,
    opsDecisionRepository: DecisionRepository? = null,
    opsPaperLedgerRepository: PaperLedgerRepository? = null,
    opsCommandEventLog: CommandEventLog? = null,
    opsCommandEventFeedReader: CommandEventFeedReader? = null,
    opsRuntimeConfigAdminService: RuntimeConfigAdminService? = null,
    tradingConfig: TradingBotConfig = TradingBotConfig(),
    runtimeConfigEnvironment: Map<String, String> = System.getenv(),
    webRoot: File? = webRootFromEnv(),
) {
    val databaseResources = createApplicationDatabaseResources(readinessProbe)
    val runtime = createApplicationRuntimeResources(
        databaseResources = databaseResources,
        inputs = ApplicationRuntimeInputs(
            readinessProbe = readinessProbe,
            reconcilerStatus = reconcilerStatus,
            clock = clock,
            tradingConfig = tradingConfig,
            runtimeConfigEnvironment = runtimeConfigEnvironment,
        ),
    )
    val routeResources = createApplicationRouteResources(
        databaseResources = databaseResources,
        evaluationOverrides = ApplicationEvaluationOverrides(
            repository = evaluationRepository,
            riskStateRepository = evaluationRiskStateRepository,
            marketDataSource = evaluationMarketDataSource,
        ),
        opsOverrides = ApplicationOpsOverrides(
            riskStateCommandService = opsRiskStateCommandService,
            manualLlmLaunchService = opsManualLlmLaunchService,
            llmAuthService = opsLlmAuthService,
            decisionRepository = opsDecisionRepository,
            paperLedgerRepository = opsPaperLedgerRepository,
            commandEventLog = opsCommandEventLog,
            commandEventFeedReader = opsCommandEventFeedReader,
            runtimeConfigAdminService = opsRuntimeConfigAdminService,
        ),
        runtime = runtime,
    )

    installApplicationPlugins(webRoot)

    routing {
        healthRoutes(routeResources.readinessProbe, reconcilerStatus)
        revisionRoute(revision)
        evaluationRoutes(routeResources.evaluation)
        opsRoutes(routeResources.ops.dependencies)
        apiDocumentationRoutes()
    }

    val backgroundWorkers = startApplicationBackgroundWorkers(databaseResources, runtime)
    subscribeApplicationShutdown(databaseResources, routeResources.ops, backgroundWorkers)
}

private fun createApplicationDatabaseResources(readinessProbe: ReadinessProbe?): ApplicationDatabaseResources {
    val dataSource = createDataSourceIfConfigured(readinessProbe)

    return ApplicationDatabaseResources(
        environment = System.getenv(),
        dataSource = dataSource,
        database = dataSource?.let { source -> ExposedDatabase.connect(source) },
    )
}

private fun createApplicationRuntimeResources(
    databaseResources: ApplicationDatabaseResources,
    inputs: ApplicationRuntimeInputs,
): ApplicationRuntimeResources {
    val runtimeConfigResolution = databaseResources.database?.let { database ->
        RuntimeConfigPersistenceBootstrap(
            database = database,
            clock = inputs.clock,
        ).ensureSchema().getOrThrow()

        RuntimeConfigResolver(
            ExposedRuntimeConfigRepository(
                database = database,
                clock = inputs.clock,
                environment = databaseResources.environment,
            ),
        ).resolve(databaseResources.environment).getOrThrow()
    }
    val resolvedTradingConfig = runtimeConfigResolution?.tradingConfig ?: inputs.tradingConfig

    databaseResources.database?.let { database ->
        TradingPersistenceBootstrap(
            database = database,
            clock = inputs.clock,
            paperAccountConfig = resolvedTradingConfig.paperAccount,
        ).ensureSchema().getOrThrow()
    }

    return ApplicationRuntimeResources(
        readinessProbe = inputs.readinessProbe,
        reconcilerStatus = inputs.reconcilerStatus,
        clock = inputs.clock,
        tradingConfig = resolvedTradingConfig,
        runtimeConfigEnvironment = runtimeConfigResolution?.catalogEnvironment ?: inputs.runtimeConfigEnvironment,
        runtimeConfigSnapshot = runtimeConfigResolution?.auditSnapshot,
    )
}

private fun createApplicationRouteResources(
    databaseResources: ApplicationDatabaseResources,
    evaluationOverrides: ApplicationEvaluationOverrides,
    opsOverrides: ApplicationOpsOverrides,
    runtime: ApplicationRuntimeResources,
): ApplicationRouteResources {
    val riskStateRepository = evaluationOverrides.riskStateRepository ?: databaseResources.database?.let { database ->
        ExposedRiskStateRepository(database)
    }

    return ApplicationRouteResources(
        readinessProbe = createApplicationReadinessProbe(databaseResources, runtime),
        evaluation = createEvaluationRouteDependencies(
            databaseResources = databaseResources,
            overrides = evaluationOverrides,
            riskStateRepository = riskStateRepository,
            runtime = runtime,
        ),
        ops = createOpsRouteResources(
            databaseResources = databaseResources,
            opsOverrides = opsOverrides,
            riskStateRepository = riskStateRepository,
            runtime = runtime,
        ),
    )
}

private fun createEvaluationRouteDependencies(
    databaseResources: ApplicationDatabaseResources,
    overrides: ApplicationEvaluationOverrides,
    riskStateRepository: RiskStateRepository?,
    runtime: ApplicationRuntimeResources,
): EvaluationRouteDependencies {
    val database = databaseResources.database
    val evaluationRepository = overrides.repository ?: database?.let { connectedDatabase ->
        ExposedEvaluationRepository(connectedDatabase)
    }
    val marketDataSource = overrides.marketDataSource ?: database?.let {
        GmoPublicMarketDataSource.fromConfig(
            config = runtime.tradingConfig.gmoPublicClient,
            clock = runtime.clock,
        )
    }

    return EvaluationRouteDependencies(
        repository = evaluationRepository,
        riskStateRepository = riskStateRepository,
        marketDataSource = marketDataSource,
        tradingConfig = runtime.tradingConfig,
        clock = runtime.clock,
    )
}

private fun createOpsRouteResources(
    databaseResources: ApplicationDatabaseResources,
    opsOverrides: ApplicationOpsOverrides,
    riskStateRepository: RiskStateRepository?,
    runtime: ApplicationRuntimeResources,
): ApplicationOpsRouteResources {
    val database = databaseResources.database
    val createdManualLlmLaunchService = createDefaultManualLlmLaunchService(
        databaseResources = databaseResources,
        opsOverrides = opsOverrides,
        runtime = runtime,
    )
    val createdCommandEventLog = database?.let { connectedDatabase ->
        ExposedCommandEventLog(connectedDatabase)
    }
    val commandEventLog = opsOverrides.commandEventLog ?: createdCommandEventLog
    val createdLlmAuthService = createDefaultLlmAuthService(
        databaseResources = databaseResources,
        opsOverrides = opsOverrides,
        commandEventLog = commandEventLog,
        runtime = runtime,
    )
    val runtimeConfigAdminService = opsOverrides.runtimeConfigAdminService
        ?: createRuntimeConfigAdminService(databaseResources, runtime)

    return ApplicationOpsRouteResources(
        dependencies = OpsRouteDependencies(
            runtimeConfig = OpsRuntimeConfigRouteDependencies(
                tradingConfig = runtime.tradingConfig,
                environment = runtime.runtimeConfigEnvironment,
                adminService = runtimeConfigAdminService,
            ),
            risk = OpsRiskRouteDependencies(
                riskStateRepository = riskStateRepository,
                riskStateCommandService = opsOverrides.riskStateCommandService ?: database?.let { connectedDatabase ->
                    ExposedRiskStateCommandService(
                        database = connectedDatabase,
                        clock = runtime.clock,
                    )
                },
                manualLlmLaunchService = opsOverrides.manualLlmLaunchService ?: createdManualLlmLaunchService,
            ),
            auth = OpsAuthRouteDependencies(
                llmAuthService = opsOverrides.llmAuthService ?: createdLlmAuthService,
            ),
            feed = OpsFeedRouteDependencies(
                decisionRepository = opsOverrides.decisionRepository ?: database?.let { connectedDatabase ->
                    ExposedDecisionRepository(
                        database = connectedDatabase,
                        clock = runtime.clock,
                    )
                },
                paperLedgerRepository = opsOverrides.paperLedgerRepository ?: database?.let { connectedDatabase ->
                    ExposedPaperLedgerRepository(connectedDatabase)
                },
                commandEventFeedReader = opsOverrides.commandEventFeedReader ?: createdCommandEventLog,
            ),
            clock = runtime.clock,
        ),
        createdManualLlmLaunchService = createdManualLlmLaunchService,
        createdLlmAuthService = createdLlmAuthService,
    )
}

private fun createRuntimeConfigAdminService(
    databaseResources: ApplicationDatabaseResources,
    runtime: ApplicationRuntimeResources,
): RuntimeConfigAdminService? {
    val database = databaseResources.database ?: return null

    return ExposedRuntimeConfigRepository(
        database = database,
        clock = runtime.clock,
        environment = databaseResources.environment,
    )
}

private fun createDefaultManualLlmLaunchService(
    databaseResources: ApplicationDatabaseResources,
    opsOverrides: ApplicationOpsOverrides,
    runtime: ApplicationRuntimeResources,
): DefaultManualLlmLaunchService? {
    val dataSource = databaseResources.dataSource
    val database = databaseResources.database
    val hasInjectedManualLaunchService = opsOverrides.manualLlmLaunchService != null

    if (hasInjectedManualLaunchService) {
        return null
    }

    if (dataSource == null || database == null) {
        return null
    }

    return createManualLlmLaunchService(
        dataSource = dataSource,
        database = database,
        environment = databaseResources.environment,
        tradingConfig = runtime.tradingConfig,
        runtimeConfigSnapshot = runtime.runtimeConfigSnapshot,
        clock = runtime.clock,
    )
}

private fun createDefaultLlmAuthService(
    databaseResources: ApplicationDatabaseResources,
    opsOverrides: ApplicationOpsOverrides,
    commandEventLog: CommandEventLog?,
    runtime: ApplicationRuntimeResources,
): DefaultLlmAuthService? {
    if (opsOverrides.llmAuthService != null) {
        return null
    }

    return DefaultLlmAuthService(
        config = LlmAuthServiceConfig.fromEnvironment(databaseResources.environment),
        commandEventLog = commandEventLog,
        clock = runtime.clock,
    )
}

private fun createApplicationReadinessProbe(
    databaseResources: ApplicationDatabaseResources,
    runtime: ApplicationRuntimeResources,
): ReadinessProbe {
    val baseReadinessProbe = runtime.readinessProbe ?: databaseReadinessProbe(databaseResources.database)

    if (databaseResources.database == null || runtime.readinessProbe != null) {
        return baseReadinessProbe
    }

    return ReconcilerFreshnessReadinessProbe(
        delegate = baseReadinessProbe,
        reconcilerStatusProvider = runtime.reconcilerStatus,
        clock = runtime.clock,
    )
}

private fun Application.installApplicationPlugins(webRoot: File?) {
    install(CallLogging)
    install(ContentNegotiation) {
        json(ApiJson)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception while processing request", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            val respondedWithWebUi = call.respondWebStaticFallback(webRoot)

            if (respondedWithWebUi) {
                return@status
            }

            call.respond(status, ErrorResponse("not found"))
        }
    }
}

private fun startApplicationBackgroundWorkers(
    databaseResources: ApplicationDatabaseResources,
    runtime: ApplicationRuntimeResources,
): ApplicationBackgroundWorkers {
    val dataSource = databaseResources.dataSource
    val database = databaseResources.database

    if (dataSource == null || database == null) {
        return ApplicationBackgroundWorkers()
    }

    val sharedPersistenceBootstrap = sharedTradingPersistenceBootstrap(
        database = database,
        tradingConfig = runtime.tradingConfig,
        clock = runtime.clock,
    )

    return ApplicationBackgroundWorkers(
        reconcilerWorker = startProtectionReconcilerWorker(
            dataSource = dataSource,
            database = database,
            tradingConfig = runtime.tradingConfig,
            status = runtime.reconcilerStatus,
            clock = runtime.clock,
        ),
        llmDaemonWorker = startLlmDaemonSchedulerWorker(
            dataSource = dataSource,
            database = database,
            tradingConfig = runtime.tradingConfig,
            runtimeConfigSnapshot = runtime.runtimeConfigSnapshot,
            clock = runtime.clock,
        ),
        obsidianWriterWorker = startObsidianWriterWorker(
            database = database,
            tradingConfig = runtime.tradingConfig,
            clock = runtime.clock,
            bootstrap = sharedPersistenceBootstrap,
        ),
        reflectionRunnerWorker = startReflectionRunnerWorker(
            database = database,
            tradingConfig = runtime.tradingConfig,
            clock = runtime.clock,
            bootstrap = sharedPersistenceBootstrap,
        ),
    )
}

private fun Application.subscribeApplicationShutdown(
    databaseResources: ApplicationDatabaseResources,
    opsResources: ApplicationOpsRouteResources,
    backgroundWorkers: ApplicationBackgroundWorkers,
) {
    val hasClosableResource = databaseResources.dataSource != null ||
        backgroundWorkers.hasWorker ||
        opsResources.createdManualLlmLaunchService != null ||
        opsResources.createdLlmAuthService != null

    if (!hasClosableResource) {
        return
    }

    monitor.subscribe(ApplicationStopped) {
        backgroundWorkers.reflectionRunnerWorker?.close()
        backgroundWorkers.obsidianWriterWorker?.close()
        backgroundWorkers.llmDaemonWorker?.close()
        opsResources.createdLlmAuthService?.close()
        opsResources.createdManualLlmLaunchService?.close()
        backgroundWorkers.reconcilerWorker?.close()
        databaseResources.dataSource?.close()
    }
}

private fun sharedTradingPersistenceBootstrap(
    database: ExposedDatabase,
    tradingConfig: TradingBotConfig,
    clock: Clock,
): () -> Result<Unit> {
    val lock = Any()
    var completed = false

    return {
        synchronized(lock) {
            if (completed) {
                Result.success(Unit)
            } else {
                TradingPersistenceBootstrap(
                    database = database,
                    clock = clock,
                    paperAccountConfig = tradingConfig.paperAccount,
                ).ensureSchema().also { result ->
                    if (result.isSuccess) {
                        completed = true
                    }
                }
            }
        }
    }
}

/**
 * Application.module が受け取る runtime 入力。
 *
 * @param readinessProbe 外部から注入された readiness probe
 * @param reconcilerStatus ProtectionReconciler の状態 holder
 * @param clock worker と route に渡す clock
 * @param tradingConfig 取引 bot 全体の typed config
 * @param runtimeConfigEnvironment runtime config catalog API で参照する環境変数 map
 */
private data class ApplicationRuntimeInputs(
    val readinessProbe: ReadinessProbe?,
    val reconcilerStatus: MutableReconcilerStatus,
    val clock: Clock,
    val tradingConfig: TradingBotConfig,
    val runtimeConfigEnvironment: Map<String, String>,
)

/**
 * Application.module の解決済み runtime resource。
 *
 * @param readinessProbe 外部から注入された readiness probe
 * @param reconcilerStatus ProtectionReconciler の状態 holder
 * @param clock worker と route に渡す clock
 * @param tradingConfig 取引 bot 全体の typed config
 * @param runtimeConfigEnvironment runtime config catalog API で参照する環境変数 map
 * @param runtimeConfigSnapshot 起動時に解決した runtime config snapshot
 */
private data class ApplicationRuntimeResources(
    val readinessProbe: ReadinessProbe?,
    val reconcilerStatus: MutableReconcilerStatus,
    val clock: Clock,
    val tradingConfig: TradingBotConfig,
    val runtimeConfigEnvironment: Map<String, String>,
    val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
)

/**
 * Application.module が使う DB resource。
 *
 * @param environment process environment
 * @param dataSource DB 接続用 data source
 * @param database Exposed database
 */
private data class ApplicationDatabaseResources(
    val environment: Map<String, String>,
    val dataSource: HikariDataSource?,
    val database: ExposedDatabase?,
)

/**
 * 評価 API の外部注入 override。
 *
 * @param repository 評価 repository
 * @param riskStateRepository risk_state repository
 * @param marketDataSource 評価用 market data source
 */
private data class ApplicationEvaluationOverrides(
    val repository: EvaluationRepository?,
    val riskStateRepository: RiskStateRepository?,
    val marketDataSource: MarketDataSource?,
)

/**
 * ops API の外部注入 override。
 *
 * @param riskStateCommandService risk_state command service
 * @param manualLlmLaunchService manual LLM launch service
 * @param llmAuthService CLI auth service
 * @param decisionRepository decision repository
 * @param paperLedgerRepository paper ledger repository
 * @param commandEventLog command_event_log writer
 * @param commandEventFeedReader command_event_log feed reader
 * @param runtimeConfigAdminService runtime config admin service
 */
private data class ApplicationOpsOverrides(
    val riskStateCommandService: RiskStateCommandService?,
    val manualLlmLaunchService: ManualLlmLaunchService?,
    val llmAuthService: LlmAuthService?,
    val decisionRepository: DecisionRepository?,
    val paperLedgerRepository: PaperLedgerRepository?,
    val commandEventLog: CommandEventLog?,
    val commandEventFeedReader: CommandEventFeedReader?,
    val runtimeConfigAdminService: RuntimeConfigAdminService?,
)

/**
 * route 登録に渡す解決済み resource。
 *
 * @param readinessProbe health route に渡す readiness probe
 * @param evaluation 評価系 route の依存関係
 * @param ops ops route の依存関係
 */
private data class ApplicationRouteResources(
    val readinessProbe: ReadinessProbe,
    val evaluation: EvaluationRouteDependencies,
    val ops: ApplicationOpsRouteResources,
)

/**
 * ops route の解決済み resource。
 *
 * @param dependencies ops route の依存関係
 * @param createdManualLlmLaunchService module が生成した close 対象 service
 * @param createdLlmAuthService module が生成した close 対象 CLI auth service
 */
private data class ApplicationOpsRouteResources(
    val dependencies: OpsRouteDependencies,
    val createdManualLlmLaunchService: DefaultManualLlmLaunchService?,
    val createdLlmAuthService: DefaultLlmAuthService?,
)

/**
 * Application lifecycle に紐づく background worker。
 *
 * @param reconcilerWorker protection reconciler worker
 * @param llmDaemonWorker LLM daemon scheduler worker
 * @param obsidianWriterWorker Obsidian writer worker
 * @param reflectionRunnerWorker reflection report worker
 */
private data class ApplicationBackgroundWorkers(
    val reconcilerWorker: ProtectionReconcilerWorker? = null,
    val llmDaemonWorker: LlmDaemonSchedulerWorker? = null,
    val obsidianWriterWorker: ObsidianWriterWorker? = null,
    val reflectionRunnerWorker: ReflectionRunnerWorker? = null,
) {
    val hasWorker: Boolean = reconcilerWorker != null ||
        llmDaemonWorker != null ||
        obsidianWriterWorker != null ||
        reflectionRunnerWorker != null
}

/**
 * readiness 注入がない場合だけ DB DataSource を構築する。
 */
private fun createDataSourceIfConfigured(readinessProbe: ReadinessProbe?): HikariDataSource? {
    if (readinessProbe != null) {
        return null
    }

    val config = DatabaseConfig.fromEnv() ?: return null

    return createDataSource(config)
}
