package me.matsumo.fukurou

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.GmoLlmDaemonLaunchAvailability
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationOutcome
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.OpportunityEpisodeLifecycleObserver
import me.matsumo.fukurou.trading.daemon.RestingOrderMaintenanceService
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatus
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatusReader
import me.matsumo.fukurou.trading.invoker.LlmCliVersionProbe
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** production daemon composition が placeholder maintenance を使わないことを検証する。 */
class LlmDaemonSchedulerCompositionTest {
    @Test
    fun productionCompositionUsesPersistentMaintenanceAndLifecycleObserver() {
        val runtime = TradingRuntimeFactory.inMemory(
            tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
        )
        val service = createRestingOrderMaintenanceService(
            database = Database.connect("jdbc:postgresql://127.0.0.1:1/not-used"),
            tradingRuntime = runtime,
            latestMarketQuoteStore = LatestMarketQuoteStore(),
        )

        assertIs<RestingOrderMaintenanceService>(service)
        assertIs<OpportunityEpisodeLifecycleObserver>(service)
    }

    @Test
    fun productionSchedulerCompositionUsesGmoAvailabilityWithoutAddingGlobalGate() {
        val availability = createLlmDaemonLaunchAvailability(
            GmoExchangeStatusReader { Result.success(GmoExchangeStatus.OPEN) },
        )

        assertIs<GmoLlmDaemonLaunchAvailability>(availability)
    }

    @Test
    fun disabledDaemonReturnsBeforeSchedulerAvailabilityConstructionOrChildStart() {
        val dataSource = HikariDataSource()

        val worker = startLlmDaemonSchedulerWorker(
            dataSource = dataSource,
            database = Database.connect("jdbc:postgresql://127.0.0.1:1/not-used"),
            tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
        )

        assertNull(worker)
        assertNull(dataSource.hikariPoolMXBean)
        dataSource.close()
    }

    @Test
    fun productionRunnerFactoryUsesOrderbookSnapshotForStandardProposer() = runBlocking {
        restoreAdmissionHealthFlags()

        try {
            assertProductionRunnerFactoryUsesOrderbookSnapshot()
        } finally {
            restoreAdmissionHealthFlags()
        }
    }

    private fun restoreAdmissionHealthFlags() {
        LlmExecutionAdmissionHealth.setHeartbeatHealthy(true)
        LlmExecutionAdmissionHealth.setRecoveryScanHealthy(true)
    }

    private suspend fun assertProductionRunnerFactoryUsesOrderbookSnapshot() {
        val config = TradingBotConfig.fromEnvironment(emptyMap())
        val clock = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC)
        val runtime = TradingRuntimeFactory.inMemory(
            clock = clock,
            marketDataSource = CompositionMarketDataSource,
            tradingConfig = config,
        )
        val phases = mutableListOf<LlmInvocationPhase>()
        val runner = createProductionOneShotLlmRunner(
            tradingRuntime = runtime,
            tradingConfig = config,
            materialMarketDataSource = CompositionMarketDataSource,
            llmInvoker = object : LlmInvoker {
                override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
                    phases += request.phase

                    return Result.failure(IllegalStateException("fixture invocation stopped"))
                }
            },
            runtimeConfigSnapshot = null,
            parentEnvironment = mapOf(
                "DB_URL" to "jdbc:postgresql://127.0.0.1:5432/fukurou",
                "DB_USER" to "fukurou",
                "DB_PASSWORD" to "fixture-password",
                "FUKUROU_LLM_ACCESS_TOKEN" to "fixture-token",
            ),
            clock = clock,
            commandRendererConfig = LlmCommandRendererConfig(),
            cliVersionProbe = LlmCliVersionProbe { Result.success("fixture-cli 1.0") },
        )
        val invocationId = "production-composition-standard-snapshot"
        val reservation = runtime.launchReservationRepository.tryReserve(
            LlmLaunchReservationRequest(
                invocationId = invocationId,
                triggerKind = LlmDaemonTriggerKind.MANUAL,
                triggerKey = "test:$invocationId",
                reservedAt = clock.instant(),
                runnerConfig = LlmRunnerConfig(),
                hourlyWindow = Duration.ofHours(1),
                dailyWindow = Duration.ofDays(1),
                activeReservationStaleAfter = Duration.ofMinutes(30),
            ),
        ).getOrThrow()
        assertIs<LlmLaunchReservationOutcome.Reserved>(reservation)

        val result = runner.runOneShot(
            OneShotRunnerRequest(
                repositoryRoot = Path.of("..").toAbsolutePath().normalize(),
                workingDirectory = Path.of(".").toAbsolutePath().normalize(),
                mcpJarPath = "mcp/build/libs/fukurou-mcp-all.jar",
                invocationId = invocationId,
                triggerKind = LlmDaemonTriggerKind.MANUAL,
            ),
        )

        val audit = (runtime.commandEventLog as InMemoryCommandEventLog).events()
            .joinToString { event -> event.payload }
        val material = assertNotNull(runtime.decisionMaterialStateRepository.find(invocationId).getOrThrow())
        val runManifest = assertNotNull(runtime.llmInputManifestRepository.findRun(invocationId).getOrThrow())
        assertEquals(
            LlmInvocationPhase.PROPOSER,
            phases.firstOrNull(),
            result.exceptionOrNull()?.toString() ?: audit,
        )
        assertEquals(material.snapshotContentHash, runManifest.materialContentHash)
    }
}

private object CompositionMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> = Result.success(
        Ticker(
            symbol = symbol.apiSymbol,
            last = "10000000",
            bid = "9990000",
            ask = "10000000",
            high = "10100000",
            low = "9900000",
            volume = "1",
            timestamp = "2026-07-16T00:00:00Z",
        ),
    )

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> = Result.success(
        (0 until 64).map { index ->
            Candle(
                symbol = symbol.apiSymbol,
                interval = interval,
                openTime = Instant.parse("2026-07-15T18:00:00Z").plusSeconds(index * 300L).toString(),
                open = "10000000",
                high = "10100000",
                low = "9900000",
                close = "10000000",
                volume = "1",
            )
        },
    )

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> = Result.success(
        Orderbook(
            symbol = symbol.apiSymbol,
            bids = listOf(OrderbookLevel("9990000", "0.1")),
            asks = listOf(OrderbookLevel("10000000", "0.2")),
        ),
    )

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> =
        Result.success(emptyList())

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> = Result.success(
        SymbolRules(
            symbol = symbol.apiSymbol,
            minOrderSize = "0.0001",
            sizeStep = "0.0001",
            tickSize = "1",
            takerFee = "0.0005",
            makerFee = "-0.0001",
        ),
    )
}
