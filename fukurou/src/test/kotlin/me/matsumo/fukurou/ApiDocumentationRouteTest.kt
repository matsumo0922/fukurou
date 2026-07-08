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
        assertPostRequestBodySchema(
            paths = paths,
            path = "/ops/halt",
            schemaRef = "#/components/schemas/OpsHaltRequest",
        )
        assertPostRequestBodySchema(
            paths = paths,
            path = "/ops/resume",
            schemaRef = "#/components/schemas/OpsResumeRequest",
        )
        assertPostRequestBodySchema(
            paths = paths,
            path = "/ops/trigger",
            schemaRef = "#/components/schemas/OpsTriggerRequest",
        )
        assertOperation(
            paths = paths,
            path = "/ops/llm-auth",
            summary = "CLI auth 状態を取得する",
            tag = "ops",
        )
        assertPostRequestBodySchema(
            paths = paths,
            path = "/ops/llm-auth/{provider}/login",
            schemaRef = "#/components/schemas/OpsLlmAuthLoginRequest",
        )
        assertOperation(
            paths = paths,
            path = "/ops/llm-auth/{provider}/login/{sessionId}",
            summary = "CLI auth login session を取得する",
            tag = "ops",
        )
        assertPostRequestBodySchema(
            paths = paths,
            path = "/ops/llm-auth/{provider}/login/{sessionId}/token",
            schemaRef = "#/components/schemas/OpsLlmAuthTokenSubmitRequest",
        )
        assertOperation(
            paths = paths,
            path = "/ops/account",
            summary = "paper account snapshot を取得する",
            tag = "ops",
        )
        assertOperation(
            paths = paths,
            path = "/ops/runtime-config",
            summary = "runtime config catalog を取得する",
            tag = "ops",
        )
        assertOperation(
            paths = paths,
            path = "/ops/decisions",
            summary = "LLM decision の raw feed を取得する",
            tag = "ops",
        )
        assertOperation(
            paths = paths,
            path = "/ops/executions",
            summary = "paper execution の raw feed を取得する",
            tag = "ops",
        )
        assertGetQueryParameter(
            paths = paths,
            path = "/ops/executions",
            name = "limit",
            type = "integer",
        )
        assertOperation(
            paths = paths,
            path = "/ops/positions",
            summary = "open position と open order の raw feed を取得する",
            tag = "ops",
        )
        assertOperation(
            paths = paths,
            path = "/ops/audit",
            summary = "command_event_log の raw feed を取得する",
            tag = "ops",
        )
        assertGetQueryParameter(
            paths = paths,
            path = "/ops/audit",
            name = "limit",
            type = "integer",
        )
        assertGetQueryParameter(
            paths = paths,
            path = "/ops/audit",
            name = "eventType",
            type = "string",
        )
        assertGetArrayQueryParameter(
            paths = paths,
            path = "/ops/audit",
            name = "excludeEventType",
            itemType = "string",
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

    private fun assertPostRequestBodySchema(
        paths: JsonObject,
        path: String,
        schemaRef: String,
    ) {
        val schema = paths
            .getValue(path)
            .jsonObject
            .getValue("post")
            .jsonObject
            .getValue("requestBody")
            .jsonObject
            .getValue("content")
            .jsonObject
            .getValue("application/json")
            .jsonObject
            .getValue("schema")
            .jsonObject

        assertEquals(schemaRef, schema.getValue("\$ref").jsonPrimitive.content)
    }

    private fun assertGetQueryParameter(
        paths: JsonObject,
        path: String,
        name: String,
        type: String,
    ) {
        val parameters = paths
            .getValue(path)
            .jsonObject
            .getValue("get")
            .jsonObject
            .getValue("parameters")
            .jsonArray
        val parameter = parameters
            .map { parameterElement -> parameterElement.jsonObject }
            .single { parameterObject -> parameterObject.getValue("name").jsonPrimitive.content == name }
        val schema = parameter
            .getValue("schema")
            .jsonObject

        assertEquals("query", parameter.getValue("in").jsonPrimitive.content)
        assertEquals(type, schema.getValue("type").jsonPrimitive.content)
    }

    private fun assertGetArrayQueryParameter(
        paths: JsonObject,
        path: String,
        name: String,
        itemType: String,
    ) {
        val parameters = paths
            .getValue(path)
            .jsonObject
            .getValue("get")
            .jsonObject
            .getValue("parameters")
            .jsonArray
        val parameter = parameters
            .map { parameterElement -> parameterElement.jsonObject }
            .single { parameterObject -> parameterObject.getValue("name").jsonPrimitive.content == name }
        val schema = parameter
            .getValue("schema")
            .jsonObject
        val items = schema
            .getValue("items")
            .jsonObject

        assertEquals("query", parameter.getValue("in").jsonPrimitive.content)
        assertEquals(true, parameter.getValue("explode").jsonPrimitive.content.toBoolean())
        assertEquals("array", schema.getValue("type").jsonPrimitive.content)
        assertEquals(itemType, items.getValue("type").jsonPrimitive.content)
    }
}
