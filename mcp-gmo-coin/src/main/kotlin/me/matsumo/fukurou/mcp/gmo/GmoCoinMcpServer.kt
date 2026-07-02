package me.matsumo.fukurou.mcp.gmo

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.exchange.gmo.GmoUnlimitedDailyKlineRequestBudget
import me.matsumo.fukurou.trading.market.MarketDataSource

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
 */
class GmoCoinMcpServer(
    private val marketDataSource: MarketDataSource = defaultStandaloneMarketDataSource(),
) {

    /**
     * 標準入出力に MCP server を接続し、client から閉じられるまで待機する。
     */
    fun run() {
        val server = createServer()
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
                val session = server.createSession(transport)
                val done = Job()
                session.onClose {
                    done.complete()
                }
                done.join()
            } finally {
                transportScope.cancel()
            }
        }
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

        server.registerGmoCoinMarketTools(marketDataSource)

        return server
    }
}

private fun defaultStandaloneMarketDataSource(): MarketDataSource {
    val tradingConfig = TradingBotConfig.fromEnvironment()

    return GmoPublicMarketDataSource.fromConfig(
        config = tradingConfig.gmoPublicClient,
        dailyKlineRequestBudget = GmoUnlimitedDailyKlineRequestBudget,
    )
}
