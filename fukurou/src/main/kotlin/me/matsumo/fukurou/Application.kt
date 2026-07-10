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
import me.matsumo.fukurou.trading.activity.DecisionRunProjectionRepository
import me.matsumo.fukurou.trading.broker.PaperLedgerRepository
import me.matsumo.fukurou.trading.config.RuntimeConfigAdminService
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.RuntimeConfigActivationResult
import me.matsumo.fukurou.trading.config.RuntimeConfigDraftCreation
import me.matsumo.fukurou.trading.config.RuntimeConfigResolver
import me.matsumo.fukurou.trading.config.RuntimeConfigSnapshotWarning
import me.matsumo.fukurou.trading.config.RuntimeConfigValidationRejectedException
import me.matsumo.fukurou.trading.config.RuntimeConfigVersionDetail
import me.matsumo.fukurou.trading.config.RuntimeConfigVersionSummary
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.DefaultManualLlmLaunchService
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchResult
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchService
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.persistence.ExposedCommandEventLog
import me.matsumo.fukurou.trading.persistence.ExposedDecisionRepository
import me.matsumo.fukurou.trading.persistence.ExposedDecisionRunProjectionRepository
import me.matsumo.fukurou.trading.persistence.ExposedEvaluationRepository
import me.matsumo.fukurou.trading.persistence.ExposedPaperLedgerRepository
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateCommandService
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateRepository
import me.matsumo.fukurou.trading.persistence.ExposedRuntimeConfigRepository
import me.matsumo.fukurou.trading.persistence.RuntimeConfigPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.persistence.staleLlmRunRecoveryThreshold
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
 * @param opsDecisionRunProjectionRepository ops decision run projection。null なら DB 設定から構築する
 * @param opsRuntimeConfigAdminService ops API 用 runtime config admin service。null なら DB 設定から構築する
 * @param opsLlmDaemonOperations ops API 用 daemon operations。null なら DB 設定から supervisor を構築する
 * @param tradingConfig trading runtime config
 * @param runtimeConfigEnvironment runtime config catalog API で参照する環境変数 map
 * @param databaseConfig DB 接続設定。null なら DB 未構成として扱う
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
    opsDecisionRunProjectionRepository: DecisionRunProjectionRepository? = null,
    opsRuntimeConfigAdminService: RuntimeConfigAdminService? = null,
    opsLlmDaemonOperations: LlmDaemonOperations? = null,
    tradingConfig: TradingBotConfig = TradingBotConfig(),
    runtimeConfigEnvironment: Map<String, String> = System.getenv(),
    databaseConfig: DatabaseConfig? = DatabaseConfig.fromEnv(),
    webRoot: File? = webRootFromEnv(),
) {
    val databaseResources = createApplicationDatabaseResources(
        readinessProbe = readinessProbe,
        databaseConfig = databaseConfig,
    )
    val runtime = createApplicationRuntimeResources(
        databaseResources = databaseResources,
        inputs = ApplicationRuntimeInputs(
            readinessProbe = readinessProbe,
            reconcilerStatus = reconcilerStatus,
            clock = clock,
            tradingConfig = tradingConfig,
            runtimeConfigEnvironment = runtimeConfigEnvironment,
            onStaleLlmRunsRecovered = { count ->
                log.warn("Recovered {} stale llm_runs rows during persistence bootstrap.", count)
            },
        ),
    )
    val daemonResources = createApplicationDaemonResources(
        databaseResources = databaseResources,
        runtime = runtime,
        operationsOverride = opsLlmDaemonOperations,
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
            decisionRunProjectionRepository = opsDecisionRunProjectionRepository,
            runtimeConfigAdminService = opsRuntimeConfigAdminService,
            llmDaemonOperations = opsLlmDaemonOperations,
        ),
        runtime = runtime,
        daemonResources = daemonResources,
    )

    installApplicationPlugins(webRoot)

    routing {
        healthRoutes(routeResources.readinessProbe, reconcilerStatus)
        revisionRoute(revision)
        evaluationRoutes(routeResources.evaluation)
        opsRoutes(routeResources.ops.dependencies)
        apiDocumentationRoutes()
    }

    val backgroundWorkers = startApplicationBackgroundWorkers(databaseResources, runtime, daemonResources)
    subscribeApplicationShutdown(databaseResources, routeResources.ops, backgroundWorkers)
}

private fun createApplicationDatabaseResources(
    readinessProbe: ReadinessProbe?,
    databaseConfig: DatabaseConfig?,
): ApplicationDatabaseResources {
    val dataSource = createDataSourceIfConfigured(
        readinessProbe = readinessProbe,
        databaseConfig = databaseConfig,
    )

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
    val runtimeConfigState = ApplicationRuntimeConfigState(
        databaseResources = databaseResources,
        inputs = inputs,
    )
    val runtimeConfigSnapshot = runtimeConfigState.snapshot()

    return ApplicationRuntimeResources(
        readinessProbe = inputs.readinessProbe,
        reconcilerStatus = inputs.reconcilerStatus,
        clock = inputs.clock,
        tradingConfig = runtimeConfigSnapshot.tradingConfig,
        tradingRuntimeAvailable = runtimeConfigSnapshot.tradingRuntimeAvailable,
        runtimeConfigSnapshot = runtimeConfigSnapshot.runtimeConfigSnapshot,
        runtimeConfigState = runtimeConfigState,
        onStaleLlmRunsRecovered = inputs.onStaleLlmRunsRecovered,
    )
}

/**
 * active runtime config の現在状態を解決する holder。
 *
 * @param databaseResources DB と process environment
 * @param inputs module の runtime 入力
 */
private class ApplicationRuntimeConfigState(
    private val databaseResources: ApplicationDatabaseResources,
    private val inputs: ApplicationRuntimeInputs,
) {
    private val lock = Any()

    @Volatile
    private var cachedSnapshot: ApplicationRuntimeConfigSnapshot? = null
    private var tradingPersistenceBootstrapped = false

    private val runtimeConfigRepository = databaseResources.database?.let { database ->
        ExposedRuntimeConfigRepository(
            database = database,
            clock = inputs.clock,
            environment = databaseResources.environment,
        )
    }

    fun snapshot(): ApplicationRuntimeConfigSnapshot {
        cachedSnapshot?.let { snapshot -> return snapshot }

        return synchronized(lock) {
            cachedSnapshot ?: resolveSnapshot().also { resolution ->
                if (resolution.cacheable) {
                    cachedSnapshot = resolution.snapshot
                }
            }.snapshot
        }
    }

    fun invalidate() {
        synchronized(lock) {
            cachedSnapshot = null
        }
    }

    fun ensureTradingPersistence(tradingConfig: TradingBotConfig): Result<Unit> {
        return synchronized(lock) {
            if (tradingPersistenceBootstrapped) {
                return@synchronized Result.success(Unit)
            }

            val database = databaseResources.database
                ?: return@synchronized Result.success(Unit)

            TradingPersistenceBootstrap(
                database = database,
                clock = inputs.clock,
                paperAccountConfig = tradingConfig.paperAccount,
                staleLlmRunRecoveryThreshold = tradingConfig.staleLlmRunRecoveryThreshold(),
                onStaleLlmRunsRecovered = inputs.onStaleLlmRunsRecovered,
            ).ensureSchema().also { result ->
                if (result.isSuccess) {
                    tradingPersistenceBootstrapped = true
                }
            }
        }
    }

    private fun resolveSnapshot(): ApplicationRuntimeConfigSnapshotResolution {
        return runCatching {
            snapshotUnsafe()
        }.fold(
            onSuccess = { snapshot ->
                ApplicationRuntimeConfigSnapshotResolution(
                    snapshot = snapshot,
                    cacheable = snapshot.isCacheable(),
                )
            },
            onFailure = { error ->
                val snapshot = ApplicationRuntimeConfigSnapshot(
                    tradingConfig = inputs.tradingConfig,
                    tradingRuntimeAvailable = false,
                    runtimeConfigEnvironment = inputs.runtimeConfigEnvironment,
                    runtimeConfigSnapshot = null,
                    runtimeConfigValues = RuntimeConfigCatalog.runtimeValues(inputs.tradingConfig),
                    runtimeConfigWarnings = listOf(runtimeConfigResolveWarning(error)),
                )

                ApplicationRuntimeConfigSnapshotResolution(
                    snapshot = snapshot,
                    cacheable = error is RuntimeConfigValidationRejectedException,
                )
            },
        )
    }

    private fun ApplicationRuntimeConfigSnapshot.isCacheable(): Boolean {
        if (tradingRuntimeAvailable) {
            return true
        }

        return runtimeConfigWarnings.any { warning ->
            warning.code == RUNTIME_CONFIG_ACTIVE_VALIDATION_FAILED_WARNING
        }
    }

    /**
     * snapshot 解決結果と cache 可否。
     *
     * @param snapshot route / runtime gate に返す snapshot
     * @param cacheable 次回以降の hot path で再利用してよいか
     */
    private data class ApplicationRuntimeConfigSnapshotResolution(
        val snapshot: ApplicationRuntimeConfigSnapshot,
        val cacheable: Boolean,
    )

    private fun snapshotUnsafe(): ApplicationRuntimeConfigSnapshot {
        val database = databaseResources.database

        if (database == null) {
            return ApplicationRuntimeConfigSnapshot(
                tradingConfig = inputs.tradingConfig,
                tradingRuntimeAvailable = true,
                runtimeConfigEnvironment = inputs.runtimeConfigEnvironment,
                runtimeConfigSnapshot = null,
                runtimeConfigValues = RuntimeConfigCatalog.runtimeValues(inputs.tradingConfig),
                runtimeConfigWarnings = emptyList(),
            )
        }

        val runtimeConfigWarnings = mutableListOf<RuntimeConfigSnapshotWarning>()
        val runtimeConfigBootstrapResult = RuntimeConfigPersistenceBootstrap(
            database = database,
            clock = inputs.clock,
        ).ensureSchema()
        val runtimeConfigResolutionResult = runtimeConfigRepository
            ?.let { repository -> RuntimeConfigResolver(repository).resolve(databaseResources.environment) }
        val runtimeConfigResolution = runtimeConfigResolutionResult?.getOrNull()

        runtimeConfigResolutionResult?.exceptionOrNull()
            ?.let { error -> runtimeConfigWarnings += runtimeConfigResolveWarning(error) }
        if (runtimeConfigBootstrapResult.isFailure && runtimeConfigResolutionResult?.isFailure != true) {
            runtimeConfigWarnings += RuntimeConfigSnapshotWarning(code = "runtimeConfig.warning.bootstrapUnavailable")
        }

        val resolvedTradingConfig = runtimeConfigResolution?.tradingConfig ?: inputs.tradingConfig
        var tradingRuntimeAvailable = runtimeConfigBootstrapResult.isSuccess && runtimeConfigResolution != null

        if (tradingRuntimeAvailable) {
            val tradingBootstrapResult = ensureTradingPersistence(resolvedTradingConfig)

            if (tradingBootstrapResult.isFailure) {
                tradingRuntimeAvailable = false
                runtimeConfigWarnings += RuntimeConfigSnapshotWarning(code = "runtimeConfig.warning.tradingSchemaUnavailable")
            }
        }

        return ApplicationRuntimeConfigSnapshot(
            tradingConfig = resolvedTradingConfig,
            tradingRuntimeAvailable = tradingRuntimeAvailable,
            runtimeConfigEnvironment = runtimeConfigResolution?.catalogEnvironment ?: inputs.runtimeConfigEnvironment,
            runtimeConfigSnapshot = runtimeConfigResolution?.auditSnapshot,
            runtimeConfigValues = RuntimeConfigCatalog.runtimeValues(resolvedTradingConfig),
            runtimeConfigWarnings = runtimeConfigWarnings,
        )
    }
}

private fun runtimeConfigResolveWarning(error: Throwable): RuntimeConfigSnapshotWarning {
    val validationError = error as? RuntimeConfigValidationRejectedException

    if (validationError != null) {
        return RuntimeConfigSnapshotWarning(
            code = RUNTIME_CONFIG_ACTIVE_VALIDATION_FAILED_WARNING,
            validation = validationError.validation,
        )
    }

    return RuntimeConfigSnapshotWarning(code = "runtimeConfig.warning.activeSnapshotUnavailable")
}

private fun ApplicationRuntimeConfigSnapshot.toOpsRuntimeConfigRouteSnapshot(): OpsRuntimeConfigRouteSnapshot {
    return OpsRuntimeConfigRouteSnapshot(
        tradingConfig = tradingConfig,
        environment = runtimeConfigEnvironment,
        warnings = runtimeConfigWarnings,
    )
}

private fun ApplicationRuntimeConfigSnapshot.toLlmDaemonRuntimeSnapshot(): LlmDaemonRuntimeSnapshot {
    return LlmDaemonRuntimeSnapshot(
        tradingConfig = tradingConfig,
        configIdentity = runtimeConfigSnapshot,
        values = runtimeConfigValues,
        available = tradingRuntimeAvailable,
    )
}

private fun ApplicationRuntimeConfigSnapshot.delegateKey(): String {
    val snapshot = runtimeConfigSnapshot ?: return "env:${tradingConfig.hashCode()}"

    return "${snapshot.versionId}:${snapshot.hash}"
}

private fun createApplicationRouteResources(
    databaseResources: ApplicationDatabaseResources,
    evaluationOverrides: ApplicationEvaluationOverrides,
    opsOverrides: ApplicationOpsOverrides,
    runtime: ApplicationRuntimeResources,
    daemonResources: ApplicationDaemonResources,
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
            daemonResources = daemonResources,
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
    val marketDataSource = overrides.marketDataSource ?: database
        ?.takeIf { runtime.tradingRuntimeAvailable }
        ?.let {
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

@Suppress("LongMethod")
private fun createOpsRouteResources(
    databaseResources: ApplicationDatabaseResources,
    opsOverrides: ApplicationOpsOverrides,
    riskStateRepository: RiskStateRepository?,
    runtime: ApplicationRuntimeResources,
    daemonResources: ApplicationDaemonResources,
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
    val invalidatingRuntimeConfigAdminService = runtimeConfigAdminService?.let { service ->
        InvalidatingRuntimeConfigAdminService(
            delegate = service,
            onActiveChanged = {
                runtime.runtimeConfigState.invalidate()
                daemonResources.supervisor?.notifyConfigChanged()
            },
        )
    }

    return ApplicationOpsRouteResources(
        dependencies = OpsRouteDependencies(
            runtimeConfig = OpsRuntimeConfigRouteDependencies(
                snapshotProvider = OpsRuntimeConfigSnapshotProvider {
                    runtime.runtimeConfigState.snapshot().toOpsRuntimeConfigRouteSnapshot()
                },
                adminService = invalidatingRuntimeConfigAdminService,
                activationService = daemonResources.activationService
                    ?: (opsOverrides.runtimeConfigAdminService as? LlmDaemonRuntimeConfigActivationService),
            ),
            risk = createOpsRiskRouteDependencies(
                database = database,
                opsOverrides = opsOverrides,
                riskStateRepository = riskStateRepository,
                createdManualLlmLaunchService = createdManualLlmLaunchService,
                runtime = runtime,
            ),
            daemon = OpsDaemonRouteDependencies(
                operations = opsOverrides.llmDaemonOperations ?: daemonResources.operations,
            ),
            auth = OpsAuthRouteDependencies(
                llmAuthService = opsOverrides.llmAuthService ?: createdLlmAuthService,
            ),
            feed = createOpsFeedRouteDependencies(
                database = database,
                opsOverrides = opsOverrides,
                createdCommandEventLog = createdCommandEventLog,
                runtime = runtime,
            ),
            clock = runtime.clock,
        ),
        createdManualLlmLaunchService = createdManualLlmLaunchService,
        createdLlmAuthService = createdLlmAuthService,
    )
}

private fun createApplicationDaemonResources(
    databaseResources: ApplicationDatabaseResources,
    runtime: ApplicationRuntimeResources,
    operationsOverride: LlmDaemonOperations?,
): ApplicationDaemonResources {
    if (operationsOverride != null) {
        return ApplicationDaemonResources(operations = operationsOverride)
    }

    val dataSource = databaseResources.dataSource ?: return ApplicationDaemonResources()
    val database = databaseResources.database ?: return ApplicationDaemonResources()
    val commandEventLog = ExposedCommandEventLog(database)
    val runtimeConfigAdminService = ExposedRuntimeConfigRepository(
        database = database,
        clock = runtime.clock,
        environment = databaseResources.environment,
    )
    val processSnapshot = runtime.runtimeConfigState.snapshot().toLlmDaemonRuntimeSnapshot()
    val supervisor = LlmDaemonSupervisor(
        processSnapshot = processSnapshot,
        snapshotProvider = LlmDaemonRuntimeSnapshotProvider {
            runtime.runtimeConfigState.snapshot().toLlmDaemonRuntimeSnapshot()
        },
        desiredStateActivator = VersionedLlmDaemonDesiredStateActivator(
            adminService = runtimeConfigAdminService,
            snapshotProvider = LlmDaemonRuntimeSnapshotProvider {
                runtime.runtimeConfigState.snapshot().toLlmDaemonRuntimeSnapshot()
            },
            onActiveChanged = runtime.runtimeConfigState::invalidate,
        ),
        runtimeConfigAdminService = runtimeConfigAdminService,
        onActiveConfigChanged = runtime.runtimeConfigState::invalidate,
        riskStateRepository = ExposedRiskStateRepository(database),
        commandEventLog = commandEventLog,
        desiredStateReasonProvider = commandEventDesiredStateReasonProvider(commandEventLog),
        workerFactory = LlmDaemonWorkerFactory { request ->
            createLlmDaemonSchedulerWorker(
                dataSource = dataSource,
                database = database,
                tradingConfig = request.tradingConfig,
                runtimeConfigSnapshot = request.runtimeConfigSnapshot,
                clock = runtime.clock,
                observer = request.observer,
                lifecycleListener = request.lifecycleListener,
            )
        },
        clock = runtime.clock,
    )

    return ApplicationDaemonResources(
        operations = supervisor,
        activationService = supervisor,
        supervisor = supervisor,
    )
}

/**
 * active runtime config の変更成功時に snapshot cache を破棄する admin service。
 *
 * @param delegate runtime config 操作の実体
 * @param onActiveChanged active version が変わった後の通知
 */
private class InvalidatingRuntimeConfigAdminService(
    private val delegate: RuntimeConfigAdminService,
    private val onActiveChanged: () -> Unit,
) : RuntimeConfigAdminService {

    override fun listVersions(limit: Int): Result<List<RuntimeConfigVersionSummary>> {
        return delegate.listVersions(limit)
    }

    override fun createDraft(request: RuntimeConfigDraftCreation): Result<RuntimeConfigVersionDetail> {
        return delegate.createDraft(request)
    }

    override fun validateVersion(versionId: String): Result<RuntimeConfigVersionDetail> {
        return delegate.validateVersion(versionId)
    }

    override fun activateDraft(versionId: String): Result<RuntimeConfigActivationResult> {
        return delegate.activateDraft(versionId).also { result ->
            if (result.isSuccess) {
                onActiveChanged()
            }
        }
    }

    override fun activateDraftIfActive(
        versionId: String,
        expectedActiveVersionId: String,
    ): Result<RuntimeConfigActivationResult> {
        return delegate.activateDraftIfActive(versionId, expectedActiveVersionId).also { result ->
            if (result.isSuccess) {
                onActiveChanged()
            }
        }
    }

    override fun discardDraft(versionId: String): Result<Unit> {
        return delegate.discardDraft(versionId)
    }

    override fun rollbackToVersion(versionId: String): Result<RuntimeConfigActivationResult> {
        return delegate.rollbackToVersion(versionId).also { result ->
            if (result.isSuccess) {
                onActiveChanged()
            }
        }
    }
}

private fun createOpsRiskRouteDependencies(
    database: ExposedDatabase?,
    opsOverrides: ApplicationOpsOverrides,
    riskStateRepository: RiskStateRepository?,
    createdManualLlmLaunchService: CreatedManualLlmLaunchService?,
    runtime: ApplicationRuntimeResources,
): OpsRiskRouteDependencies {
    return OpsRiskRouteDependencies(
        riskStateRepository = riskStateRepository,
        riskStateCommandService = opsOverrides.riskStateCommandService ?: database?.let { connectedDatabase ->
            ExposedRiskStateCommandService(
                database = connectedDatabase,
                clock = runtime.clock,
            )
        },
        manualLlmLaunchService = opsOverrides.manualLlmLaunchService ?: createdManualLlmLaunchService?.service,
        runtimeAvailabilityProvider = OpsRuntimeAvailabilityProvider {
            runtime.runtimeConfigState.snapshot().tradingRuntimeAvailable
        },
    )
}

private fun createOpsFeedRouteDependencies(
    database: ExposedDatabase?,
    opsOverrides: ApplicationOpsOverrides,
    createdCommandEventLog: ExposedCommandEventLog?,
    runtime: ApplicationRuntimeResources,
): OpsFeedRouteDependencies {
    return OpsFeedRouteDependencies(
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
        decisionRunProjectionRepository = opsOverrides.decisionRunProjectionRepository
            ?: database?.let(::ExposedDecisionRunProjectionRepository),
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
): CreatedManualLlmLaunchService? {
    val dataSource = databaseResources.dataSource
    val database = databaseResources.database
    val hasInjectedManualLaunchService = opsOverrides.manualLlmLaunchService != null

    if (hasInjectedManualLaunchService) {
        return null
    }

    if (dataSource == null || database == null) {
        return null
    }

    if (!hasManualLlmLaunchServiceEnvironment(databaseResources.environment)) {
        return null
    }

    val service = RecoveringManualLlmLaunchService(
        dataSource = dataSource,
        database = database,
        environment = databaseResources.environment,
        runtimeConfigState = runtime.runtimeConfigState,
        clock = runtime.clock,
    )

    return CreatedManualLlmLaunchService(
        service = service,
        close = service::close,
    )
}

/**
 * active runtime config が復旧した後に manual LLM launch service を遅延構築する service。
 *
 * @param dataSource PostgreSQL data source
 * @param database Exposed database
 * @param environment runner / invoker 用 environment
 * @param runtimeConfigState active runtime config の現在状態
 * @param clock service と runner に渡す clock
 */
private class RecoveringManualLlmLaunchService(
    private val dataSource: HikariDataSource,
    private val database: ExposedDatabase,
    private val environment: Map<String, String>,
    private val runtimeConfigState: ApplicationRuntimeConfigState,
    private val clock: Clock,
) : ManualLlmLaunchService, AutoCloseable {
    private val lock = Any()
    private var delegateKey: String? = null
    private var delegate: DefaultManualLlmLaunchService? = null

    override suspend fun launch(reason: String): Result<ManualLlmLaunchResult> {
        val runtimeConfigSnapshot = runtimeConfigState.snapshot()

        if (!runtimeConfigSnapshot.tradingRuntimeAvailable) {
            return Result.success(ManualLlmLaunchResult.Rejected(RUNTIME_CONFIG_UNAVAILABLE_REASON))
        }

        val service = serviceFor(runtimeConfigSnapshot)
            ?: return Result.success(ManualLlmLaunchResult.Rejected(MANUAL_LAUNCH_SERVICE_UNAVAILABLE_REASON))

        return service.launch(reason)
    }

    override fun close() {
        synchronized(lock) {
            delegate?.close()
            delegate = null
            delegateKey = null
        }
    }

    private fun serviceFor(runtimeConfigSnapshot: ApplicationRuntimeConfigSnapshot): DefaultManualLlmLaunchService? {
        val nextDelegateKey = runtimeConfigSnapshot.delegateKey()

        synchronized(lock) {
            val currentDelegate = delegate

            if (currentDelegate != null && delegateKey == nextDelegateKey) {
                return currentDelegate
            }

            currentDelegate?.close()

            val nextDelegate = createManualLlmLaunchService(
                dataSource = dataSource,
                database = database,
                environment = environment,
                tradingConfig = runtimeConfigSnapshot.tradingConfig,
                runtimeConfigSnapshot = runtimeConfigSnapshot.runtimeConfigSnapshot,
                clock = clock,
            )
            delegate = nextDelegate
            delegateKey = nextDelegate?.let { nextDelegateKey }

            return nextDelegate
        }
    }
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
        return ReadinessProbe {
            runtime.runtimeConfigState.snapshot().tradingRuntimeAvailable && baseReadinessProbe.isReady()
        }
    }

    val reconcilerReadinessProbe = ReconcilerFreshnessReadinessProbe(
        delegate = baseReadinessProbe,
        reconcilerStatusProvider = runtime.reconcilerStatus,
        clock = runtime.clock,
    )

    return ReadinessProbe {
        val runtimeAvailable = runtime.runtimeConfigState.snapshot().tradingRuntimeAvailable

        if (!runtimeAvailable) {
            return@ReadinessProbe false
        }

        if (!runtime.tradingRuntimeAvailable) {
            return@ReadinessProbe baseReadinessProbe.isReady()
        }

        reconcilerReadinessProbe.isReady()
    }
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
    daemonResources: ApplicationDaemonResources,
): ApplicationBackgroundWorkers {
    val dataSource = databaseResources.dataSource
    val database = databaseResources.database

    if (dataSource == null || database == null) {
        return ApplicationBackgroundWorkers()
    }

    val daemonSupervisor = daemonResources.supervisor?.start()

    if (!runtime.tradingRuntimeAvailable) {
        return ApplicationBackgroundWorkers(llmDaemonSupervisor = daemonSupervisor)
    }

    val sharedPersistenceBootstrap = {
        runtime.runtimeConfigState.ensureTradingPersistence(runtime.tradingConfig)
    }

    return ApplicationBackgroundWorkers(
        reconcilerWorker = startProtectionReconcilerWorker(
            dataSource = dataSource,
            database = database,
            tradingConfig = runtime.tradingConfig,
            status = runtime.reconcilerStatus,
            clock = runtime.clock,
            onStaleLlmRunsRecovered = runtime.onStaleLlmRunsRecovered,
            bootstrap = sharedPersistenceBootstrap,
        ),
        llmDaemonSupervisor = daemonSupervisor,
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
        backgroundWorkers.llmDaemonSupervisor?.close()
        opsResources.createdLlmAuthService?.close()
        opsResources.createdManualLlmLaunchService?.close?.invoke()
        backgroundWorkers.reconcilerWorker?.close()
        databaseResources.dataSource?.close()
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
 * @param onStaleLlmRunsRecovered stale llm_runs 回収件数の通知
 */
private data class ApplicationRuntimeInputs(
    val readinessProbe: ReadinessProbe?,
    val reconcilerStatus: MutableReconcilerStatus,
    val clock: Clock,
    val tradingConfig: TradingBotConfig,
    val runtimeConfigEnvironment: Map<String, String>,
    val onStaleLlmRunsRecovered: (Int) -> Unit,
)

/**
 * active runtime config から解決した現在の runtime 状態。
 *
 * @param tradingConfig 取引 bot 全体の typed config
 * @param tradingRuntimeAvailable 取引 runtime / manual trigger / daemon を起動できるか
 * @param runtimeConfigEnvironment runtime config catalog API で参照する環境変数 map
 * @param runtimeConfigSnapshot active runtime config の監査 snapshot
 * @param runtimeConfigValues active runtime config の typed key/value
 * @param runtimeConfigWarnings runtime config catalog API で返す運用者向け warning
 */
private data class ApplicationRuntimeConfigSnapshot(
    val tradingConfig: TradingBotConfig,
    val tradingRuntimeAvailable: Boolean,
    val runtimeConfigEnvironment: Map<String, String>,
    val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    val runtimeConfigValues: Map<String, String>,
    val runtimeConfigWarnings: List<RuntimeConfigSnapshotWarning>,
)

/**
 * Application.module の解決済み runtime resource。
 *
 * @param readinessProbe 外部から注入された readiness probe
 * @param reconcilerStatus ProtectionReconciler の状態 holder
 * @param clock worker と route に渡す clock
 * @param tradingConfig 取引 bot 全体の typed config
 * @param tradingRuntimeAvailable 取引 runtime / manual trigger / daemon を起動できるか
 * @param runtimeConfigSnapshot 起動時に解決した runtime config snapshot
 * @param runtimeConfigState active runtime config の現在状態 holder
 * @param onStaleLlmRunsRecovered stale llm_runs 回収件数の通知
 */
private data class ApplicationRuntimeResources(
    val readinessProbe: ReadinessProbe?,
    val reconcilerStatus: MutableReconcilerStatus,
    val clock: Clock,
    val tradingConfig: TradingBotConfig,
    val tradingRuntimeAvailable: Boolean,
    val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    val runtimeConfigState: ApplicationRuntimeConfigState,
    val onStaleLlmRunsRecovered: (Int) -> Unit,
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
 * @param llmDaemonOperations daemon status / desired state operations
 */
private data class ApplicationOpsOverrides(
    val riskStateCommandService: RiskStateCommandService?,
    val manualLlmLaunchService: ManualLlmLaunchService?,
    val llmAuthService: LlmAuthService?,
    val decisionRepository: DecisionRepository?,
    val paperLedgerRepository: PaperLedgerRepository?,
    val commandEventLog: CommandEventLog?,
    val commandEventFeedReader: CommandEventFeedReader?,
    val decisionRunProjectionRepository: DecisionRunProjectionRepository?,
    val runtimeConfigAdminService: RuntimeConfigAdminService?,
    val llmDaemonOperations: LlmDaemonOperations?,
)

/**
 * Application が構築した daemon supervisor resource。
 */
private data class ApplicationDaemonResources(
    val operations: LlmDaemonOperations? = null,
    val activationService: LlmDaemonRuntimeConfigActivationService? = null,
    val supervisor: LlmDaemonSupervisor? = null,
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
 * module が生成した manual LLM launch service と終了処理。
 *
 * @param service route に渡す manual LLM launch service
 * @param close Application shutdown 時の終了処理
 */
private data class CreatedManualLlmLaunchService(
    val service: ManualLlmLaunchService,
    val close: () -> Unit,
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
    val createdManualLlmLaunchService: CreatedManualLlmLaunchService?,
    val createdLlmAuthService: DefaultLlmAuthService?,
)

/**
 * Application lifecycle に紐づく background worker。
 *
 * @param reconcilerWorker protection reconciler worker
 * @param llmDaemonSupervisor LLM daemon supervisor
 * @param obsidianWriterWorker Obsidian writer worker
 * @param reflectionRunnerWorker reflection report worker
 */
private data class ApplicationBackgroundWorkers(
    val reconcilerWorker: ProtectionReconcilerWorker? = null,
    val llmDaemonSupervisor: LlmDaemonSupervisor? = null,
    val obsidianWriterWorker: ObsidianWriterWorker? = null,
    val reflectionRunnerWorker: ReflectionRunnerWorker? = null,
) {
    val hasWorker: Boolean = reconcilerWorker != null ||
        llmDaemonSupervisor != null ||
        obsidianWriterWorker != null ||
        reflectionRunnerWorker != null
}

/**
 * runtime config が利用できないときの manual trigger 拒否理由。
 */
private const val RUNTIME_CONFIG_UNAVAILABLE_REASON = "runtime config is unavailable"

/**
 * manual LLM launch service を構築できないときの拒否理由。
 */
private const val MANUAL_LAUNCH_SERVICE_UNAVAILABLE_REASON = "manual LLM launch service is unavailable"

/**
 * active runtime config validation failure の warning code。
 */
private const val RUNTIME_CONFIG_ACTIVE_VALIDATION_FAILED_WARNING = "runtimeConfig.warning.activeValidationFailed"

/**
 * readiness 注入がない場合だけ DB DataSource を構築する。
 */
private fun createDataSourceIfConfigured(
    readinessProbe: ReadinessProbe?,
    databaseConfig: DatabaseConfig?,
): HikariDataSource? {
    if (readinessProbe != null) {
        return null
    }

    val config = databaseConfig ?: return null

    return createDataSource(config)
}
