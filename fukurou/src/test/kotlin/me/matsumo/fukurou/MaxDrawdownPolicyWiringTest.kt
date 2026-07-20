package me.matsumo.fukurou

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MaxDrawdownPolicy の production wiring inventory を固定するテスト。 */
class MaxDrawdownPolicyWiringTest {

    @Test
    fun max_drawdown_policy_production_root_and_default_constructor_inventory_is_closed() {
        val root = repositoryRoot()
        val tradingRuntime = root.read("trading/src/main/kotlin/me/matsumo/fukurou/trading/runtime/TradingRuntime.kt")
        val reconcilerWorker = root.read("fukurou/src/main/kotlin/me/matsumo/fukurou/ProtectionReconcilerWorker.kt")
        val application = root.read("fukurou/src/main/kotlin/me/matsumo/fukurou/Application.kt")
        val daemon = root.read("fukurou/src/main/kotlin/me/matsumo/fukurou/LlmDaemonSchedulerWorker.kt")
        val obsidian = root.read("fukurou/src/main/kotlin/me/matsumo/fukurou/ObsidianWriterWorker.kt")
        val opsRoutes = root.read("fukurou/src/main/kotlin/me/matsumo/fukurou/OpsRoutes.kt")
        val obsidianWriter = root.read(
            "trading/src/main/kotlin/me/matsumo/fukurou/trading/knowledge/ObsidianVaultWriter.kt",
        )
        val productionSources = root.productionKotlinSources()

        assertEquals(2, tradingRuntime.count("MaxDrawdownPolicy(tradingConfig.safetyFloor.maxDrawdownRatio)"))
        assertEquals(1, reconcilerWorker.count("MaxDrawdownPolicy(tradingConfig.safetyFloor.maxDrawdownRatio)"))
        assertTrue(tradingRuntime.contains("maxDrawdownPolicy = context.maxDrawdownPolicy"))
        assertTrue(reconcilerWorker.contains("maxDrawdownPolicy = maxDrawdownPolicy"))

        assertEquals(6, productionSources.sumOf { source -> source.count("ExposedPaperLedgerRepository(") })
        assertTrue(application.contains("ExposedPaperLedgerRepository(connectedDatabase)"))
        assertTrue(obsidian.contains("ExposedPaperLedgerRepository(database)"))
        assertFalse(
            daemon.substringAfter("val paperLedgerRepository = ExposedPaperLedgerRepository(")
                .substringBefore("val preFilter")
                .contains("maxDrawdownPolicy"),
        )
        val defaultConstructionConsumers = listOf(application, daemon, obsidian, opsRoutes, obsidianWriter)
        val mutationMethods = listOf(
            "fillMarketEntry",
            "createRestingEntryOrder",
            "closePosition",
            "updateProtection",
            "cancelOrder",
            "reconcile",
            "applyMarketEvent",
        )
        mutationMethods.forEach { method ->
            assertEquals(0, defaultConstructionConsumers.sumOf { source -> source.count(".$method(") }, method)
        }
        assertEquals(1, daemon.count(".paperLedgerRepository.entryFillReader()"))
        assertEquals(1, obsidianWriter.count("paperLedgerRepository.findClosedPositionsClosedBetween("))
        assertEquals(1, opsRoutes.count("repository.getAccountSnapshotWithUpdatedAt()"))
        assertEquals(1, opsRoutes.count("repository.getRecentExecutions(limit)"))
        assertEquals(1, opsRoutes.count("repository.getOpenPositions()"))
        assertEquals(1, opsRoutes.count("repository.getOpenOrders()"))
        assertEquals(1, opsRoutes.count("repository.findSellExecutionsByPositionIds(openPositionIds)"))
        assertEquals(1, opsRoutes.count("repository.findExecutionActivitiesForStableFeed("))

        val runtimeDecisionSources = productionSources.filterNot { source ->
            source.contains("object SafetyFloorDefaults") || source.contains("class MaxDrawdownPolicy")
        }
        assertEquals(
            0,
            runtimeDecisionSources.sumOf { source -> source.count("SafetyFloorDefaults.maxDrawdownRatio") },
        )
    }

    private fun repositoryRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

        while (!candidate.resolve("settings.gradle.kts").exists()) {
            candidate = candidate.parent ?: error("repository root was not found")
        }

        return candidate
    }

    private fun Path.read(relativePath: String): String {
        return Files.readString(resolve(relativePath))
    }

    private fun Path.productionKotlinSources(): List<String> {
        val roots = listOf(resolve("trading/src/main/kotlin"), resolve("fukurou/src/main/kotlin"))

        return roots.flatMap { sourceRoot ->
            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { path -> path.toString().endsWith(".kt") }
                    .map(Files::readString)
                    .toList()
            }
        }
    }

    private fun String.count(needle: String): Int {
        return windowed(needle.length).count { candidate -> candidate == needle }
    }
}
