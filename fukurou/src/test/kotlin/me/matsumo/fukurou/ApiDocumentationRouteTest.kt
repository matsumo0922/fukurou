package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * API documentation route の挙動を検証するテスト。
 */
class ApiDocumentationRouteTest {

    @Test
    fun swagger_returns_ui() = testApplication {
        application {
            module(readinessProbe = { true })
        }

        val response = client.get("/swagger")
        val responseBody = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(responseBody.contains("Swagger UI"))
    }

    @Test
    fun openapi_json_includes_placeholder_metadata() = testApplication {
        application {
            module(readinessProbe = { true })
        }

        val response = client.get("/openapi.json")
        val responseBody = response.bodyAsText()
        val openApiDocument = Json.parseToJsonElement(responseBody).jsonObject
        val info = openApiDocument.getValue("info").jsonObject
        val paths = openApiDocument.getValue("paths").jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Fukurou API", info.getValue("title").jsonPrimitive.content)
        assertOperation(
            paths = paths,
            path = "/health/live",
            summary = "生存状態を取得する",
            tag = "ヘルスチェック",
        )
        assertOperation(
            paths = paths,
            path = "/health/ready",
            summary = "受入可能状態を取得する",
            tag = "ヘルスチェック",
        )
        assertOperation(
            paths = paths,
            path = "/revision",
            summary = "稼働中サーバーのリビジョンを取得する",
            tag = "リビジョン",
        )
    }

    private fun assertOperation(
        paths: JsonObject,
        path: String,
        summary: String,
        tag: String,
    ) {
        val operation = paths
            .getValue(path)
            .jsonObject
            .getValue("get")
            .jsonObject
        val tags = operation
            .getValue("tags")
            .jsonArray
            .map { tagElement -> tagElement.jsonPrimitive.content }

        assertEquals(summary, operation.getValue("summary").jsonPrimitive.content)
        assertTrue(tags.contains(tag))
    }
}
