package me.matsumo.fukurou

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi

/**
 * revision 情報を分類する OpenAPI タグ。
 */
private const val REVISION_TAG = "リビジョン"

/**
 * 稼働中 image の revision を焼き込む環境変数名。
 */
private const val FUKUROU_REVISION_ENV = "FUKUROU_REVISION"

/**
 * revision が未指定の場合に返す固定値。
 */
private const val UNKNOWN_REVISION = "unknown"

/**
 * 環境変数から稼働中 image の revision を取得する。
 */
internal fun currentRevisionFromEnv(): String {
    return System.getenv(FUKUROU_REVISION_ENV).asRevisionOrUnknown()
}

/**
 * 稼働中 image の revision を返すエンドポイントを定義する。
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.revisionRoute(revision: String) {
    get("/revision") {
        call.respondText(revision.asRevisionOrUnknown(), ContentType.Text.Plain)
    }.describe {
        summary = "稼働中サーバーのリビジョンを取得する"
        description = "Docker image build 時に FUKUROU_REVISION として焼き込まれた commit hash を text/plain で返します。"
        tag(REVISION_TAG)
        responses {
            HttpStatusCode.OK {
                description = "稼働中 image の commit hash です。未指定の場合は unknown を返します。"
                ContentType.Text.Plain {
                    schema = JsonSchema(type = JsonType.STRING)
                }
            }
            HttpStatusCode.InternalServerError {
                description = "予期しないサーバーエラーが発生しました。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

/**
 * 空白のみの revision を unknown に正規化する。
 */
private fun String?.asRevisionOrUnknown(): String {
    return this?.trim()?.ifEmpty { UNKNOWN_REVISION } ?: UNKNOWN_REVISION
}
