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
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.persistence.ExposedEvaluationRepository
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateRepository
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.risk.RiskStateRepository
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
 * @param tradingConfig trading runtime config
 */
fun Application.module(
    readinessProbe: ReadinessProbe? = null,
    revision: String = currentRevisionFromEnv(),
    reconcilerStatus: MutableReconcilerStatus = MutableReconcilerStatus(),
    clock: Clock = Clock.systemUTC(),
    evaluationRepository: EvaluationRepository? = null,
    evaluationRiskStateRepository: RiskStateRepository? = null,
    evaluationMarketDataSource: MarketDataSource? = null,
    tradingConfig: TradingBotConfig = TradingBotConfig.fromEnvironment(),
) {
    val databaseDataSource = createDataSourceIfConfigured(readinessProbe)
    val database = databaseDataSource?.let { dataSource -> ExposedDatabase.connect(dataSource) }
    val resolvedEvaluationRepository = evaluationRepository ?: database?.let { connectedDatabase ->
        ExposedEvaluationRepository(connectedDatabase)
    }
    val resolvedEvaluationRiskStateRepository = evaluationRiskStateRepository ?: database?.let { connectedDatabase ->
        ExposedRiskStateRepository(connectedDatabase)
    }
    val resolvedEvaluationMarketDataSource = evaluationMarketDataSource ?: database?.let {
        GmoPublicMarketDataSource.fromConfig(
            config = tradingConfig.gmoPublicClient,
            clock = clock,
        )
    }
    val baseReadinessProbe = readinessProbe ?: databaseReadinessProbe(database)
    val resolvedReadinessProbe = if (database == null || readinessProbe != null) {
        baseReadinessProbe
    } else {
        ReconcilerFreshnessReadinessProbe(
            delegate = baseReadinessProbe,
            reconcilerStatusProvider = reconcilerStatus,
            clock = clock,
        )
    }

    install(CallLogging)
    install(ContentNegotiation) {
        json(ApiJson)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception while processing request", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal server error"))
        }
    }

    routing {
        healthRoutes(resolvedReadinessProbe, reconcilerStatus)
        revisionRoute(revision)
        evaluationRoutes(
            repository = resolvedEvaluationRepository,
            riskStateRepository = resolvedEvaluationRiskStateRepository,
            marketDataSource = resolvedEvaluationMarketDataSource,
            tradingConfig = tradingConfig,
            clock = clock,
        )
        apiDocumentationRoutes()
    }

    val reconcilerWorker = if (databaseDataSource != null && database != null) {
        startProtectionReconcilerWorker(
            dataSource = databaseDataSource,
            database = database,
            status = reconcilerStatus,
            clock = clock,
        )
    } else {
        null
    }
    val llmDaemonWorker = if (databaseDataSource != null && database != null) {
        startLlmDaemonSchedulerWorker(
            dataSource = databaseDataSource,
            database = database,
            clock = clock,
        )
    } else {
        null
    }
    val obsidianWriterWorker = if (databaseDataSource != null && database != null) {
        startObsidianWriterWorker(
            database = database,
            clock = clock,
        )
    } else {
        null
    }

    if (databaseDataSource != null || reconcilerWorker != null || llmDaemonWorker != null || obsidianWriterWorker != null) {
        monitor.subscribe(ApplicationStopped) {
            obsidianWriterWorker?.close()
            llmDaemonWorker?.close()
            reconcilerWorker?.close()
            databaseDataSource?.close()
        }
    }
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
