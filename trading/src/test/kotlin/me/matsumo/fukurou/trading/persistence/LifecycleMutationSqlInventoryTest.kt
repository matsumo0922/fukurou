package me.matsumo.fukurou.trading.persistence

import java.nio.file.Files
import java.nio.file.Path
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
            assertEquals(entry.mutations, normalizedMutationTuples(source), "Unreviewed lifecycle mutation tuple: ${entry.file}")
            entry.callers.forEach { caller ->
                assertTrue(caller in source, "Missing lifecycle caller ${entry.file}#$caller")
            }
            if (entry.requiresToken) {
                assertTrue(
                    "acquireGapPopulationGenerationToken" in source ||
                        "acquireOpportunityEpisodeGapPopulationToken" in source ||
                        "acquireEvaluationGapPopulationToken" in source ||
                        "acquire_gap_population_generation_token" in source,
                    "Lifecycle mutation source has no reviewed token acquisition: ${entry.file}",
                )
            }
        }
    }

    @Test
    fun normalizedInventoryRejectsUnregisteredPopulationAndMutationKind() {
        assertEquals(setOf(LifecycleMutationTuple("INSERT", "orders")), normalizedMutationTuples("INSERT INTO orders(id) VALUES (?)"))
        assertTrue(LifecycleMutationTuple("INSERT", "orders") !in normalizedMutationTuples("UPDATE orders SET status='FILLED'"))
        assertTrue(LifecycleMutationTuple("INSERT", "executions") !in APPROVED_LIFECYCLE_MUTATIONS)
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
    val mutations: Set<LifecycleMutationTuple>,
    val callers: Set<String>,
    val requiresToken: Boolean = true,
)

private data class LifecycleMutationTuple(val kind: String, val population: String)

private val APPROVED_LIFECYCLE_MUTATIONS = setOf(
    LifecycleMutationTuple("INSERT", "orders"),
    LifecycleMutationTuple("UPDATE", "orders"),
    LifecycleMutationTuple("INSERT", "positions"),
    LifecycleMutationTuple("UPDATE", "positions"),
    LifecycleMutationTuple("INSERT", "llm_runs"),
    LifecycleMutationTuple("UPDATE", "llm_runs"),
    LifecycleMutationTuple("INSERT", "llm_launch_reservations"),
    LifecycleMutationTuple("UPDATE", "llm_launch_reservations"),
    LifecycleMutationTuple("INSERT", "opportunity_episodes"),
    LifecycleMutationTuple("UPDATE", "opportunity_episodes"),
    LifecycleMutationTuple("INSERT", "evaluation_report_jobs"),
    LifecycleMutationTuple("UPDATE", "evaluation_report_jobs"),
)

private fun normalizedMutationTuples(source: String): Set<LifecycleMutationTuple> {
    return LIFECYCLE_MUTATION_SQL.findAll(source).map { match ->
        LifecycleMutationTuple(
            kind = match.groupValues[1].uppercase().substringBefore(' '),
            population = match.groupValues[2].lowercase(),
        )
    }.toSet()
}

private val LIFECYCLE_SCAN_ROOTS = listOf(
    "trading/src/main",
    "fukurou/src/main",
    "mcp/src/main",
)

private val LIFECYCLE_MUTATION_SQL = Regex(
    pattern = """(?is)\b(INSERT\s+INTO|UPDATE)\s+(orders|positions|llm_runs|llm_launch_reservations|opportunity_episodes|evaluation_report_jobs)\b""",
)

private val LIFECYCLE_MUTATION_SOURCE_ALLOWLIST = listOf(
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedPaperLedgerWriter.kt",
        mutations = setOf(
            LifecycleMutationTuple("INSERT", "orders"), LifecycleMutationTuple("UPDATE", "orders"),
            LifecycleMutationTuple("INSERT", "positions"), LifecycleMutationTuple("UPDATE", "positions"),
        ),
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
        mutations = setOf(LifecycleMutationTuple("INSERT", "llm_runs")),
        callers = setOf("insertRunning", "finish", "INSERT_LLM_RUN_RUNNING_SQL", "UPSERT_LLM_RUN_FINISH_SQL"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedLlmLaunchReservationRepository.kt",
        mutations = setOf(
            LifecycleMutationTuple("INSERT", "llm_launch_reservations"),
            LifecycleMutationTuple("UPDATE", "llm_launch_reservations"),
        ),
        callers = setOf("tryReserve", "finish", "INSERT_LLM_LAUNCH_RESERVATION_SQL", "FINISH_LLM_LAUNCH_RESERVATION_SQL"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedDecisionRepository.kt",
        mutations = setOf(
            LifecycleMutationTuple("INSERT", "opportunity_episodes"),
            LifecycleMutationTuple("UPDATE", "opportunity_episodes"),
        ),
        callers = setOf("submitDecision", "insertOpportunityEpisode", "closeOpportunityEpisode"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedRestingOrderMaintenanceService.kt",
        mutations = setOf(LifecycleMutationTuple("UPDATE", "opportunity_episodes")),
        callers = setOf("observeTerminalLifecycle", "closePersistedTerminalEpisodes"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingPersistenceBootstrap.kt",
        mutations = setOf(
            LifecycleMutationTuple("UPDATE", "orders"), LifecycleMutationTuple("UPDATE", "positions"),
            LifecycleMutationTuple("UPDATE", "llm_runs"), LifecycleMutationTuple("UPDATE", "llm_launch_reservations"),
        ),
        callers = setOf("ensureSchema", "recoverStaleLlmRunLifecycle"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedMarketDataIntegrityRepository.kt",
        mutations = emptySet(),
        callers = setOf("recordGap", "applyGapImpact", "recoverStaleSession"),
    ),
    LifecycleMutationSourceEntry(
        file = "trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/GapPopulationLifecycle.kt",
        mutations = setOf(
            LifecycleMutationTuple("UPDATE", "orders"),
            LifecycleMutationTuple("UPDATE", "llm_runs"),
            LifecycleMutationTuple("UPDATE", "llm_launch_reservations"),
            LifecycleMutationTuple("UPDATE", "opportunity_episodes"),
            LifecycleMutationTuple("UPDATE", "evaluation_report_jobs"),
        ),
        callers = setOf("enqueueGapPopulationWork", "recoverGapPopulationPass", "fence_gap_population_terminal_mutation"),
    ),
    LifecycleMutationSourceEntry(
        file = "fukurou/src/main/kotlin/me/matsumo/fukurou/EvaluationReportPersistence.kt",
        mutations = setOf(
            LifecycleMutationTuple("INSERT", "evaluation_report_jobs"),
            LifecycleMutationTuple("UPDATE", "evaluation_report_jobs"),
            LifecycleMutationTuple("UPDATE", "llm_launch_reservations"),
        ),
        callers = setOf("admit", "updateJob", "complete", "fail", "recoverInterruptedJobs"),
    ),
    LifecycleMutationSourceEntry(
        file = "scripts/mcp-credential-isolation-check",
        mutations = setOf(LifecycleMutationTuple("INSERT", "llm_runs")),
        callers = setOf("FIXTURE_CREATE", "raw app INSERT bypassed lifecycle token", "MCP role gained llm_runs INSERT"),
        requiresToken = false,
    ),
)

private fun repositoryRoot(): Path {
    return generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { path -> path.parent }
        .first { path -> Files.exists(path.resolve("settings.gradle.kts")) }
}
