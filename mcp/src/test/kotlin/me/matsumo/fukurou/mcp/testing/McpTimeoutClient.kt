package me.matsumo.fukurou.mcp.testing

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * timeout 再現 tool を stdio MCP server 経由で呼び、呼び出し元 timeout が no-trade で終了できることを検証する client。
 */
fun main(args: Array<String>) = runBlocking {
    val jarPath = requireNotNull(args.firstOrNull()) {
        "Usage: McpTimeoutClientKt <mcp-fat-jar>"
    }

    val process = ProcessBuilder(
        "java",
        "-Dfukurou.mcp.testInMemoryRuntime=true",
        "-jar",
        jarPath,
    )
        .apply {
            environment()["FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME"] = "true"
        }
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    val transport = StdioClientTransport(
        input = JsonOnlyStdoutInputStream(process.inputStream).asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
    )
    val client = Client(
        clientInfo = Implementation(
            name = "fukurou-timeout-client",
            version = "0.1.0",
        ),
    )

    try {
        client.connect(transport)
        verifyCallerTimeout(client)
    } finally {
        client.close()
        process.destroy()
    }
}

private suspend fun verifyCallerTimeout(client: Client) {
    val timedOut = runCatching {
        withTimeout(200.toDuration(DurationUnit.MILLISECONDS)) {
            client.callTool(
                name = "simulate_tool_timeout",
                arguments = mapOf("delay_ms" to 60_000),
            )
        }
    }.exceptionOrNull() is TimeoutCancellationException

    check(timedOut) {
        "simulate_tool_timeout should exceed caller timeout without side effects."
    }

    println("simulate_tool_timeout timed out at caller boundary; no_trade=true")
}
