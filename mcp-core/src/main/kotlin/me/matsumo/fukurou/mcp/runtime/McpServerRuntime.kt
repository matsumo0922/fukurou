package me.matsumo.fukurou.mcp.runtime

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP server を stdio transport で起動し、session close まで待機する。
 */
fun Server.runStdioMcpServer(onClose: () -> Unit = {}) {
    val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    ) {
        scope = transportScope
        handlerDispatcher = Dispatchers.Default
        ioDispatcher = Dispatchers.IO
    }

    runBlocking {
        try {
            val session = createSession(transport)
            val done = Job()
            session.onClose {
                done.complete()
            }
            done.join()
        } finally {
            transportScope.cancel()
            onClose()
        }
    }
}

/**
 * MCP tool の標準 error result を作る。
 */
fun mcpErrorResult(
    type: String,
    message: String,
    executed: Boolean? = null,
    failureKind: String? = null,
): CallToolResult {
    val resolvedMessage = message.ifBlank { "unknown error" }

    return CallToolResult(
        content = listOf(TextContent(resolvedMessage)),
        structuredContent = buildJsonObject {
            put("error", true)
            put("type", type)
            put("message", resolvedMessage)
            if (executed != null) {
                put("executed", executed)
            }
            if (failureKind != null) {
                put("failure_kind", failureKind)
            }
        },
        isError = true,
    )
}
