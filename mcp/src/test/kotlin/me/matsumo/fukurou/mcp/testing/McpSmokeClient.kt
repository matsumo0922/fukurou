package me.matsumo.fukurou.mcp.testing

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * fat jar を stdio MCP server として起動し、Step1 の最小 tool を呼ぶ smoke client。
 */
fun main(args: Array<String>) = runBlocking {
    val jarPath = requireNotNull(args.firstOrNull()) {
        "Usage: McpSmokeClientKt <mcp-fat-jar>"
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
            name = "fukurou-smoke-client",
            version = "0.1.0",
        ),
    )

    try {
        client.connect(transport)
        verifyTicker(client)
        verifyDummyTradeReject(client)
        verifySimulatedTimeoutTool(client)
    } finally {
        client.close()
        process.destroy()
    }
}

private suspend fun verifyTicker(client: Client) {
    val result = client.callTool(
        name = "get_ticker",
        arguments = mapOf("symbol" to "BTC"),
    )
    val text = result.content.joinToString(separator = "\n") { content ->
        if (content is TextContent) content.text else content.toString()
    }

    check(result.isError != true) {
        "get_ticker returned MCP error: $text"
    }
    check(text.contains("\"symbol\":\"BTC\"")) {
        "get_ticker response did not include BTC ticker JSON: $text"
    }

    println("get_ticker ok: $text")
}

private suspend fun verifyDummyTradeReject(client: Client) {
    val result = client.callTool(
        name = "reject_dummy_trade",
        arguments = mapOf("reason" to "headless reject smoke"),
    )
    val text = result.content.joinToString(separator = "\n") { content ->
        if (content is TextContent) content.text else content.toString()
    }

    check(result.isError == true) {
        "reject_dummy_trade should return an MCP error without side effects: $text"
    }

    println("reject_dummy_trade rejected as expected: $text")
}

private suspend fun verifySimulatedTimeoutTool(client: Client) {
    val result = client.callTool(
        name = "simulate_tool_timeout",
        arguments = mapOf("delay_ms" to 10),
    )
    val text = result.content.joinToString(separator = "\n") { content ->
        if (content is TextContent) content.text else content.toString()
    }

    check(result.isError != true) {
        "simulate_tool_timeout should complete without MCP error for short smoke delay: $text"
    }
    check(text.contains("without side effects")) {
        "simulate_tool_timeout response did not prove no-side-effect completion: $text"
    }

    println("simulate_tool_timeout completed as expected: $text")
}
