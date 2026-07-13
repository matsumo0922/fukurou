package me.matsumo.fukurou.trading.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** creation/terminal SQL と caller の exact source allowlist。 */
class LifecycleMutationSqlInventoryTest {
    @Test
    fun lifecycleMutationSqlOnlyAppearsInExactSourceAllowlist() {
        val root = repositoryRoot()
        val allowlistedFiles = LIFECYCLE_MUTATION_SOURCE_ALLOWLIST.map { entry -> entry.file }.toSet()
        val unexpectedFiles = LIFECYCLE_SCAN_ROOTS.flatMap { scanRoot ->
            Files.walk(root.resolve(scanRoot)).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter { path -> path.toString().endsWith(".kt") || path.toString().endsWith(".sql") }
                    .filter { path -> LIFECYCLE_MUTATION_SQL.containsMatchIn(Files.readString(path)) }
                    .map { path -> root.relativize(path).toString() }
                    .filter { path -> path !in allowlistedFiles }
                    .toList()
            }
        } + listOf("scripts/mcp-credential-isolation-check")
            .filter { path -> LIFECYCLE_MUTATION_SQL.containsMatchIn(Files.readString(root.resolve(path))) }
            .filter { path -> path !in allowlistedFiles }

        assertEquals(emptyList(), unexpectedFiles.sorted())
    }

    @Test
    fun lifecycleMutationSourcesMatchExactAllowlist() {
        val root = repositoryRoot()

        LIFECYCLE_MUTATION_SOURCE_ALLOWLIST.forEach { entry ->
            val source = Files.readString(root.resolve(entry.file))
            assertEquals(entry.sha256, sha256(source), "Unreviewed lifecycle SQL/caller change: ${entry.file}")
            entry.callers.forEach { caller ->
                assertTrue(caller in source, "Missing lifecycle caller ${entry.file}#$caller")
            }
        }
    }

    @Test
    fun isolationCanaryUsesTokenAwareFixtureAndKeepsRawInsertAsNegativeOnly() {
        val script = Files.readString(repositoryRoot().resolve("scripts/mcp-credential-isolation-check"))

        assertTrue("PaperMarketRecoveryMainKt FIXTURE_CREATE" in script)
        assertTrue("raw app INSERT bypassed lifecycle token" in script)
        assertTrue("MCP role gained llm_runs INSERT" in script)
        assertEquals(2, Regex("INSERT INTO llm_runs").findAll(script).count())
    }
}

private data class LifecycleMutationSourceEntry(
    val file: String,
    val sha256: String,
    val callers: Set<String>,
)

private val LIFECYCLE_SCAN_ROOTS = listOf(
    "trading/src/main",
    "fukurou/src/main",
    "mcp/src/main",
)

private val LIFECYCLE_MUTATION_SQL = Regex(
    pattern = """(?is)\b(?:INSERT\s+INTO|UPDATE)\s+(?:orders|positions|llm_runs|llm_launch_reservations|opportunity_episodes|evaluation_report_jobs)\b""",
)

private val LIFECYCLE_MUTATION_SOURCE_ALLOWLIST = listOf(
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedPaperLedgerWriter.kt",
        sha256 = "4b93e3c113322abc93d03fd3537df486ed80a6239653bd24da1ea915661a31d5",
        callers = setOf(
            "fillMarketEntry",
            "createRestingEntryOrder",
            "fillMarketEntryAndConsumeIntent",
            "createRestingEntryOrderAndConsumeIntent",
            "closePosition",
            "reconcile",
            "applyMarketEvent",
        ),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedLlmRunRepository.kt",
        sha256 = "e47daa3b4fabca260f691bb441d6b4ed0aae8c99defbc9e5fa9572f852131995",
        callers = setOf("insertRunning", "finish", "INSERT_LLM_RUN_RUNNING_SQL", "UPSERT_LLM_RUN_FINISH_SQL"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedLlmLaunchReservationRepository.kt",
        sha256 = "bf39b08d43f105f39ab2e1cb822c23e3802bca15f33f19894c45487cc7cc6b18",
        callers = setOf("tryReserve", "finish", "INSERT_LLM_LAUNCH_RESERVATION_SQL", "FINISH_LLM_LAUNCH_RESERVATION_SQL"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedDecisionRepository.kt",
        sha256 = "32925d40ddaa5ef516e03ab69fa6e6b24fd4888f91f0e2b1331c05c25abb05c4",
        callers = setOf("submitDecision", "insertOpportunityEpisode", "closeOpportunityEpisode"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedRestingOrderMaintenanceService.kt",
        sha256 = "f81e5268ceeb046d33b594194736ae44d4ebad3f19abbca1de0321b012893e5b",
        callers = setOf("observeTerminalLifecycle", "closePersistedTerminalEpisodes"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingPersistenceBootstrap.kt",
        sha256 = "d3c35bad5f494d6fb0dfaf0b491702b56f4575859bdf9f335104f56b44c94758",
        callers = setOf("ensureSchema", "recoverStaleLlmRunLifecycle"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedMarketDataIntegrityRepository.kt",
        sha256 = "592cb4b06c756d6e700943039dea62c50c42d884b77b296a88eba34b0e972bca",
        callers = setOf("recordGap", "applyGapImpact", "recoverStaleSession"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/GapPopulationLifecycle.kt",
        sha256 = "72c5960021909c9f274d1dada4bc00064d71c05e01502ede47c64e2e2eeefe13",
        callers = setOf("enqueueGapPopulationWork", "recoverGapPopulationPass", "fence_gap_population_terminal_mutation"),
    ),
    LifecycleMutationSourceEntry(
        file = "fukurou/src/main/kotlin/me/matsumo/fukurou/EvaluationReportPersistence.kt",
        sha256 = "ec5d1280307300888db68b13c2b7eb6f7d6cd4a865d75ea9c210cdb4d55fc218",
        callers = setOf("admit", "updateJob", "complete", "fail", "recoverInterruptedJobs"),
    ),
    LifecycleMutationSourceEntry(
        file = "scripts/mcp-credential-isolation-check",
        sha256 = "5c8bdaa7642c177c7a01c9c40e0c0d1d4046ae91bb60513b1abb465f615f73b9",
        callers = setOf("FIXTURE_CREATE", "raw app INSERT bypassed lifecycle token", "MCP role gained llm_runs INSERT"),
    ),
)

private fun repositoryRoot(): Path {
    return generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { path -> path.parent }
        .first { path -> Files.exists(path.resolve("settings.gradle.kts")) }
}

private fun sha256(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
