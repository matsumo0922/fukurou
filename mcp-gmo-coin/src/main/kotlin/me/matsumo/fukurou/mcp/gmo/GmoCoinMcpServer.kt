package me.matsumo.fukurou.mcp.gmo

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import me.matsumo.fukurou.mcp.runStdioMcpServer
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.exchange.gmo.GmoUnlimitedDailyKlineRequestBudget
import me.matsumo.fukurou.trading.market.MarketDataSource
import java.time.Clock

/**
 * standalone GMO Coin MCP server 名。
 */
private const val GMO_COIN_MCP_SERVER_NAME = "gmo-coin-mcp"

/**
 * standalone GMO Coin MCP server version。
 */
private const val GMO_COIN_MCP_SERVER_VERSION = "0.1.0"

/**
 * GMO Coin market tools だけを公開する standalone MCP server のエントリポイント。
 */
fun main() {
    GmoCoinMcpServer().run()
}

/**
 * GMO Coin Public API の read-only tools だけを公開する MCP stdio server。
 *
 * @param marketDataSource 市場データ取得元
 * @param clock response 鮮度 metadata を作る clock
 */
class GmoCoinMcpServer(
    private val marketDataSource: MarketDataSource = defaultStandaloneMarketDataSource(),
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * 標準入出力に MCP server を接続し、client から閉じられるまで待機する。
     */
    fun run() {
        createServer().runStdioMcpServer()
    }

    internal fun createServer(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = GMO_COIN_MCP_SERVER_NAME,
                version = GMO_COIN_MCP_SERVER_VERSION,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
            ),
        )

        server.registerGmoCoinMarketTools(
            marketDataSource = marketDataSource,
            clock = clock,
        )

        return server
    }
}

private fun defaultStandaloneMarketDataSource(): MarketDataSource {
    val gmoPublicClientConfig = GmoPublicClientConfig.fromEnvironment()

    return GmoPublicMarketDataSource.fromConfig(
        config = gmoPublicClientConfig,
        dailyKlineRequestBudget = GmoUnlimitedDailyKlineRequestBudget,
    )
}
