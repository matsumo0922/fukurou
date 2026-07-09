package me.matsumo.fukurou.mcp.gmo.testing

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import me.matsumo.fukurou.mcp.testing.JsonOnlyStdoutInputStream
import me.matsumo.fukurou.mcp.testing.verifyBtcTickerTool

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
        verifyBtcTickerTool(client)
    } finally {
        client.close()
        process.destroy()
    }
}
