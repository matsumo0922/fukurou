package me.matsumo.fukurou

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import me.matsumo.fukurou.trading.reconciler.NoReconcilerStatusProvider
import me.matsumo.fukurou.trading.reconciler.ReconcilerStatusProvider

/**
 * ヘルスチェックエンドポイントを分類する OpenAPI タグ。
 */
private const val HEALTH_TAG = "ヘルスチェック"

/**
 * ヘルスチェック系エンドポイントを定義する。
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.healthRoutes(
    readinessProbe: ReadinessProbe,
    reconcilerStatusProvider: ReconcilerStatusProvider = NoReconcilerStatusProvider,
) {
    get("/health") {
        call.respond(HealthResponse(status = "ok"))
    }.describe {
        summary = "サービスヘルスを取得する"
        tag(HEALTH_TAG)
        responses {
            HttpStatusCode.OK {
                description = "サービスプロセスが稼働しています。"
                schema = jsonSchema<HealthResponse>()
            }
            HttpStatusCode.InternalServerError {
                description = "予期しないサーバーエラーが発生しました。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/health/live") {
        call.respond(HealthResponse(status = "ok"))
    }.describe {
        summary = "生存状態を取得する"
        tag(HEALTH_TAG)
        responses {
            HttpStatusCode.OK {
                description = "サービスプロセスが生存しています。"
                schema = jsonSchema<HealthResponse>()
            }
            HttpStatusCode.InternalServerError {
                description = "予期しないサーバーエラーが発生しました。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/health/ready") {
        respondReadiness(call, readinessProbe, reconcilerStatusProvider)
    }.describe {
        summary = "受入可能状態を取得する"
        tag(HEALTH_TAG)
        responses {
            HttpStatusCode.OK {
                description = "サービスはリクエストを処理できます。"
                schema = jsonSchema<ReadinessResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "サービスはまだリクエストを処理できません。"
                schema = jsonSchema<ReadinessResponse>()
            }
            HttpStatusCode.InternalServerError {
                description = "予期しないサーバーエラーが発生しました。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

/**
 * readiness 判定結果に応じて応答する。準備完了は 200、未準備は 503 を返す。
 */
internal suspend fun respondReadiness(
    call: ApplicationCall,
    readinessProbe: ReadinessProbe,
    reconcilerStatusProvider: ReconcilerStatusProvider = NoReconcilerStatusProvider,
) {
    val ready = readinessProbe.isReady()
    val reconcilerStatus = reconcilerStatusProvider.snapshot()
    val readinessResponse = ReadinessResponse(
        status = if (ready) "ready" else "not_ready",
        lastTransportActivityAt = reconcilerStatus.lastTransportActivityAt?.toString(),
        lastTradeAt = reconcilerStatus.lastTradeAt?.toString(),
        lastMaintenanceAt = reconcilerStatus.lastMaintenanceAt?.toString(),
        marketDataState = reconcilerStatus.marketDataState.name,
        gapStartedAt = reconcilerStatus.gapStartedAt?.toString(),
        recoveredAt = reconcilerStatus.recoveredAt?.toString(),
        gapReason = reconcilerStatus.gapReason?.name,
    )

    if (ready) {
        call.respond(readinessResponse)
        return
    }

    call.respond(HttpStatusCode.ServiceUnavailable, readinessResponse)
}
