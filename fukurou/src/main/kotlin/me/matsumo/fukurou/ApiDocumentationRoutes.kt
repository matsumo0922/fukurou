package me.matsumo.fukurou

import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.hide
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * OpenAPI document のタイトル。
 */
private const val OPEN_API_TITLE = "Fukurou API"

/**
 * OpenAPI document のバージョン。
 */
private const val OPEN_API_VERSION = "1.0.0"

/**
 * Swagger UI と runtime OpenAPI JSON を公開する。
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.apiDocumentationRoutes() {
    get("/openapi.json") {
        val baseOpenApiDocument = OpenApiDoc(
            info = openApiInfo(),
        )
        val generatedOpenApiDocument = openApiJsonSource().read(call.application, baseOpenApiDocument)

        call.respondText(generatedOpenApiDocument.content, generatedOpenApiDocument.contentType)
    }.hide()

    swaggerUI(path = "swagger") {
        info = openApiInfo()
        source = openApiJsonSource()
    }
}

/**
 * OpenAPI document の基本情報を返す。
 */
private fun openApiInfo(): OpenApiInfo {
    return OpenApiInfo(
        title = OPEN_API_TITLE,
        version = OPEN_API_VERSION,
    )
}

/**
 * routing tree から JSON 形式の OpenAPI document を生成する source を返す。
 */
private fun openApiJsonSource(): OpenApiDocSource.Routing {
    return OpenApiDocSource.Routing(
        contentType = ContentType.Application.Json,
        serializeModel = { document -> OpenApiDocumentJson.encodeToString(document) },
    )
}

/**
 * OpenAPI document の serialize 用 JSON。
 */
private val OpenApiDocumentJson = Json
