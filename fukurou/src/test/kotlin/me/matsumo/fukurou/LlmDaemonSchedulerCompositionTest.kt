package me.matsumo.fukurou

import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.OpportunityEpisodeLifecycleObserver
import me.matsumo.fukurou.trading.daemon.RestingOrderMaintenanceService
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertIs

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
            tradingConfig = TradingBotConfig.fromEnvironment(emptyMap()),
        )

        assertIs<RestingOrderMaintenanceService>(service)
        assertIs<OpportunityEpisodeLifecycleObserver>(service)
    }
}
