package me.matsumo.fukurou

import com.zaxxer.hikari.HikariDataSource
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.GmoLlmDaemonLaunchAvailability
import me.matsumo.fukurou.trading.daemon.OpportunityEpisodeLifecycleObserver
import me.matsumo.fukurou.trading.daemon.RestingOrderMaintenanceService
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatus
import me.matsumo.fukurou.trading.exchange.gmo.GmoExchangeStatusReader
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertIs
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
}
