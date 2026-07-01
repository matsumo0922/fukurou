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
 */
fun Application.module(
    readinessProbe: ReadinessProbe? = null,
    revision: String = currentRevisionFromEnv(),
) {
    val databaseDataSource = createDataSourceIfConfigured(readinessProbe)
    val resolvedReadinessProbe = readinessProbe ?: databaseReadinessProbe(databaseDataSource)

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
        healthRoutes(resolvedReadinessProbe)
        revisionRoute(revision)
        apiDocumentationRoutes()
    }

    if (databaseDataSource != null) {
        monitor.subscribe(ApplicationStopped) {
            databaseDataSource.close()
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
