package me.matsumo.fukurou.mcp.gmo.testing

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import me.matsumo.fukurou.mcp.testing.JsonOnlyStdoutInputStream

/**
 * standalone GMO Coin MCP fat jar を stdio server として起動し、Public market tool を呼ぶ smoke client。
 */
fun main(args: Array<String>) = runBlocking {
    val jarPath = requireNotNull(args.firstOrNull()) {
        "Usage: GmoCoinMcpSmokeClientKt <gmo-coin-mcp-fat-jar>"
    }

    val process = ProcessBuilder(
        "java",
        "-jar",
        jarPath,
    )
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    val transport = StdioClientTransport(
        input = JsonOnlyStdoutInputStream(process.inputStream).asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
    )
    val client = Client(
        clientInfo = Implementation(
            name = "gmo-coin-smoke-client",
            version = "0.1.0",
        ),
    )

    try {
        client.connect(transport)
        verifyTicker(client)
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
