package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.runner.LlmInvocationAuditor
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LlmDaemonPreFilter の Claude stdout parsing を検証するテスト。
 */
class LlmDaemonPreFilterTest {

    @Test
    fun defaultPreFilterParsesClaudeJsonResultYes() = runBlocking {
        val invoker = StaticPreFilterLlmInvoker(
            stdout = """
                {
                  "type": "result",
                  "subtype": "success",
                  "is_error": false,
                  "result": "YES",
                  "total_cost_usd": 0.001,
                  "num_turns": 1,
                  "duration_ms": 120
                }
            """.trimIndent(),
        )

        val decision = preFilter(invoker)
            .evaluate(preFilterRequest())
            .getOrThrow()

        assertEquals(LlmDaemonPreFilterDecision.RUN_FULL, decision)
        assertEquals(LlmInvocationPhase.PRE_FILTER, invoker.requests.single().phase)
        assertEquals("daemon-pre-filter-v1", invoker.requests.single().decisionRunContext.systemPromptVersion)
        assertFalse(invoker.requests.single().prompt.contains("Claude Haiku"))
    }

    @Test
    fun defaultPreFilterParsesClaudeJsonResultNo() = runBlocking {
        val invoker = StaticPreFilterLlmInvoker(
            stdout = """
                {
                  "type": "result",
                  "subtype": "success",
                  "is_error": false,
                  "result": "NO",
                  "total_cost_usd": 0.001,
                  "num_turns": 1,
                  "duration_ms": 120
                }
            """.trimIndent(),
        )

        val decision = preFilter(invoker)
            .evaluate(preFilterRequest())
            .getOrThrow()

        assertEquals(LlmDaemonPreFilterDecision.SKIP_NO_CHANGE, decision)
        assertEquals(LlmInvocationPhase.PRE_FILTER, invoker.requests.single().phase)
    }

    @Test
    fun defaultPreFilterKeepsRawYesCompatibility() = runBlocking {
        val invoker = StaticPreFilterLlmInvoker(stdout = "YES")

        val decision = preFilter(invoker)
            .evaluate(preFilterRequest())
            .getOrThrow()

        assertEquals(LlmDaemonPreFilterDecision.RUN_FULL, decision)
    }

    @Test
    fun defaultPreFilterFailureIncludesOnlyOutputMetadata() = runBlocking {
        val invoker = StaticPreFilterLlmInvoker(
            stdout = """
                MAYBE
                previous decision thesis should stay private
            """.trimIndent(),
        )

        val throwable = preFilter(invoker)
            .evaluate(preFilterRequest())
            .exceptionOrNull()
        val message = assertNotNull(throwable).message.orEmpty()

        assertTrue(message.contains("lineCount=2"))
        assertTrue(message.contains("nonBlankLineCount=2"))
        assertTrue(message.contains("firstTokenLength=5"))
        assertFalse(message.contains("MAYBE"))
        assertFalse(message.contains("previous decision thesis"))
    }
}

private fun preFilter(invoker: LlmInvoker): DefaultLlmDaemonPreFilter {
    val clock = Clock.fixed(FIXED_PRE_FILTER_INSTANT, ZoneOffset.UTC)

    return DefaultLlmDaemonPreFilter(
        tradingConfig = TradingBotConfig(),
        runtimeConfigSnapshot = null,
        dependencies = DefaultLlmDaemonPreFilterDependencies(
            marketDataSource = PreFilterFakeMarketDataSource,
            decisionRepository = InMemoryDecisionRepository(clock),
            llmInvoker = invoker,
            invocationAuditor = LlmInvocationAuditor(
                commandEventLog = InMemoryCommandEventLog(),
                redactor = SecretRedactor(emptySet()),
                clock = clock,
            ),
        ),
        parentEnvironment = emptyMap(),
    )
}

private fun preFilterRequest(): LlmDaemonPreFilterRequest {
    val repositoryRoot = Path.of(".").toAbsolutePath().normalize()

    return LlmDaemonPreFilterRequest(
        invocationId = "pre-filter-test",
        triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
        triggerKey = "flat-heartbeat",
        observedAt = FIXED_PRE_FILTER_INSTANT,
        runnerRequest = OneShotRunnerRequest(
            repositoryRoot = repositoryRoot,
            workingDirectory = repositoryRoot,
            mcpJarPath = "mcp/build/libs/fukurou-mcp-all.jar",
        ),
    )
}

/**
 * pre-filter test 用の固定 LLM invoker。
 */
private class StaticPreFilterLlmInvoker(
    private val stdout: String,
) : LlmInvoker {

    val requests: MutableList<LlmInvocationRequest> = mutableListOf()

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        requests += request

        return Result.success(
            LlmInvocationResult(
                request = request,
                processResult = ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = 0,
                    stdout = stdout,
                    stderr = "",
                ),
            ),
        )
    }
}

/**
 * pre-filter test 用の固定 market data source。
 */
private object PreFilterFakeMarketDataSource : MarketDataSource {

    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.success(
            Ticker(
                symbol = symbol.apiSymbol,
                last = "10000000",
                bid = "9990000",
                ask = "10010000",
                high = "10100000",
                low = "9900000",
                volume = "1.0",
                timestamp = FIXED_PRE_FILTER_INSTANT.toString(),
            ),
        )
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(emptyList())
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        return Result.success(emptyList())
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.failure(UnsupportedOperationException("not used"))
    }
}

private val FIXED_PRE_FILTER_INSTANT: Instant = Instant.parse("2026-07-09T00:00:00Z")
