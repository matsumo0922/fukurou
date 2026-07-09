package me.matsumo.fukurou.mcp.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class McpServerRuntimeTest {

    @Test
    fun mcpErrorResultBuildsStructuredErrorContent() {
        val result = mcpErrorResult(
            type = "market_data",
            message = "",
            executed = false,
            failureKind = "rate_limit",
        )
        val structuredContent = assertIs<JsonObject>(result.structuredContent)

        assertEquals(true, result.isError)
        assertEquals(JsonPrimitive(true), structuredContent["error"])
        assertEquals(JsonPrimitive("market_data"), structuredContent["type"])
        assertEquals(JsonPrimitive("unknown error"), structuredContent["message"])
        assertEquals(JsonPrimitive(false), structuredContent["executed"])
        assertEquals(JsonPrimitive("rate_limit"), structuredContent["failure_kind"])
    }
}
