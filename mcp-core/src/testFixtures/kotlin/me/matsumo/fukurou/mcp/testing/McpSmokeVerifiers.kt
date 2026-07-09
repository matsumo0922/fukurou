package me.matsumo.fukurou.mcp.testing

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/** Public get_ticker tool が BTC ticker JSON を返すことを検証する。 */
suspend fun verifyBtcTickerTool(client: Client) {
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
