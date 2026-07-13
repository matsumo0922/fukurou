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

    @Test
    fun productionCallersAcquireLifecycleTokenBeforeSingleBulkUpsertAndEmbeddedMutationEntrypoints() {
        val root = repositoryRoot()
        val cases = listOf(
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedPaperLedgerWriter.kt", "override suspend fun fillMarketEntry(", "acquireGapPopulationGenerationToken", "insertEntryFill("),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedPaperLedgerWriter.kt", "override suspend fun createRestingEntryOrder(", "acquireGapPopulationGenerationToken", "insertEntryOrder("),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedPaperLedgerWriter.kt", "suspend fun fillMarketEntryAndConsumeIntent(", "acquireGapPopulationGenerationToken", "insertEntryFill("),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedPaperLedgerWriter.kt", "suspend fun createRestingEntryOrderAndConsumeIntent(", "acquireGapPopulationGenerationToken", "insertEntryOrder("),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedLlmRunRepository.kt", "override suspend fun insertRunning(", "acquireGapPopulationGenerationToken", "insertRunningLlmRun("),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedLlmRunRepository.kt", "override suspend fun finish(", "acquireGapPopulationGenerationToken", "finishLlmRun("),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedLlmLaunchReservationRepository.kt", "override suspend fun tryReserve(", "acquireGapPopulationGenerationToken", "tryReserveLlmLaunchInTransaction("),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedLlmLaunchReservationRepository.kt", "override suspend fun finish(", "acquireGapPopulationGenerationTokenForEntity", "finishLlmLaunchInTransaction("),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedDecisionRepository.kt", "override suspend fun submitDecision(", "acquireOpportunityEpisodeGapPopulationToken", "insertDecisionSubmission("),
            OrderedLifecycleCall("fukurou/src/main/kotlin/me/matsumo/fukurou/EvaluationReportPersistence.kt", "fun admit(", "acquireEvaluationGapPopulationToken", "insertJob("),
            OrderedLifecycleCall("fukurou/src/main/kotlin/me/matsumo/fukurou/EvaluationReportPersistence.kt", "fun complete(", "acquireEvaluationGapPopulationToken", "UPDATE evaluation_report_jobs"),
        )

        cases.forEach { case -> assertOrderedLifecycleCall(Files.readString(root.resolve(case.file)), case) }
    }

    @Test
    fun productionAdmissionCallersCheckProtectionOnlyBeforeEntryLlmDecisionAndReportMutationTokens() {
        val root = repositoryRoot()
        val cases = listOf(
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedPaperLedgerWriter.kt", "override suspend fun fillMarketEntry(", "requireFullGapPopulationAdmission", "acquireGapPopulationGenerationToken"),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedPaperLedgerWriter.kt", "override suspend fun createRestingEntryOrder(", "requireFullGapPopulationAdmission", "acquireGapPopulationGenerationToken"),
            OrderedLifecycleCall("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/ExposedLlmLaunchReservationRepository.kt", "override suspend fun tryReserve(", "requireFullGapPopulationAdmission", "acquireGapPopulationGenerationToken"),
            OrderedLifecycleCall("fukurou/src/main/kotlin/me/matsumo/fukurou/EvaluationReportPersistence.kt", "fun admit(", "requireFullGapPopulationAdmission", "acquireEvaluationGapPopulationToken"),
        )

        cases.forEach { case -> assertOrderedLifecycleCall(Files.readString(root.resolve(case.file)), case) }

        val lifecycleSource = Files.readString(
            root.resolve("trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/GapPopulationLifecycle.kt"),
        )
        val mcpRoleSql = Files.readString(root.resolve("scripts/deploy/sql/mcp-role.sql"))
        assertTrue("decision entry intent is blocked by gap population recovery" in lifecycleSource)
        assertTrue("gap_population_unattributed_containments containment" in lifecycleSource)
        assertTrue("GRANT EXECUTE ON FUNCTION public.acquire_opportunity_episode_gap_population_token(text)" in mcpRoleSql)
        assertTrue("REVOKE ALL ON gap_population_control, gap_population_entity_scopes" in mcpRoleSql)
        assertTrue("GRANT SELECT ON gap_population_unattributed_containments" !in mcpRoleSql)
    }

    @Test
    fun normalizedInventoryRecognizesSingleBulkUpsertAndEmbeddedLifecycleMutations() {
        assertEquals(
            setOf(LifecycleMutationTuple("INSERT", "orders")),
            normalizedMutationTuples("INSERT INTO orders(id) VALUES (?),(?)"),
        )
        assertEquals(
            setOf(LifecycleMutationTuple("INSERT", "llm_runs"), LifecycleMutationTuple("UPDATE", "llm_runs")),
            normalizedMutationTuples("INSERT INTO llm_runs(id) VALUES (?) ON CONFLICT(id) DO UPDATE SET status='FAILED'"),
        )
        assertEquals(
            setOf(LifecycleMutationTuple("UPDATE", "positions")),
            normalizedMutationTuples("WITH changed AS (UPDATE positions SET status='CLOSED' RETURNING id) SELECT * FROM changed"),
        )
    }
}

/** production capacity predicatesのexact boundary。 */
class GapPopulationCapacityBoundaryTest {
    @Test
    fun evidenceThirtyTwoIsAcceptedAndThirtyThirdUsesOverflowSentinel() {
        assertEquals(false, gapPopulationEvidenceCapacityExceeded(31))
        assertEquals(true, gapPopulationEvidenceCapacityExceeded(32))
    }

    @Test
    fun queueOneThousandIsAcceptedAndOneThousandAndFirstUsesOverflowWork() {
        assertEquals(false, gapPopulationQueueCapacityExceeded(999))
        assertEquals(true, gapPopulationQueueCapacityExceeded(1_000))
    }

    @Test
    fun journalOneHundredThousandAndTwoHundredFiftySixMibAreInclusiveBoundaries() {
        assertEquals(false, gapPopulationJournalCapacityExceeded(100_000, 256L * 1024L * 1024L))
        assertEquals(true, gapPopulationJournalCapacityExceeded(100_001, 0))
        assertEquals(true, gapPopulationJournalCapacityExceeded(0, 256L * 1024L * 1024L + 1))
    }
}

private data class LifecycleMutationSourceEntry(
    val file: String,
    val mutations: Set<LifecycleMutationTuple>,
    val callers: Set<String>,
    val requiresToken: Boolean = true,
)

private data class LifecycleMutationTuple(val kind: String, val population: String)

private data class OrderedLifecycleCall(
    val file: String,
    val entrypoint: String,
    val tokenAcquisition: String,
    val mutation: String,
)

private fun assertOrderedLifecycleCall(source: String, case: OrderedLifecycleCall) {
    val entrypointIndex = source.indexOf(case.entrypoint)
    assertTrue(entrypointIndex >= 0, "Missing entrypoint ${case.file}#${case.entrypoint}")
    val tokenIndex = source.indexOf(case.tokenAcquisition, entrypointIndex)
    val mutationIndex = source.indexOf(case.mutation, entrypointIndex)
    assertTrue(tokenIndex in (entrypointIndex + 1)..<mutationIndex, "Token is not acquired before mutation: ${case.file}#${case.entrypoint}")
}

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
    return LIFECYCLE_MUTATION_SQL.findAll(source).flatMap { match ->
        val mutation = LifecycleMutationTuple(
            kind = match.groupValues[1].uppercase().substringBefore(' '),
            population = match.groupValues[2].lowercase(),
        )
        val statementTail = source.substring(match.range.first, source.indexOf(';', match.range.last).takeIf { it >= 0 } ?: source.length)
        if (mutation.kind == "INSERT" && UPSERT_UPDATE.containsMatchIn(statementTail)) {
            sequenceOf(mutation, mutation.copy(kind = "UPDATE"))
        } else {
            sequenceOf(mutation)
        }
    }.toSet()
}

private val LIFECYCLE_SCAN_ROOTS = listOf(
    "trading/src/main",
    "fukurou/src/main",
    "mcp/src/main",
    "scripts/deploy",
)

private val UPSERT_UPDATE = Regex("(?is)\\bON\\s+CONFLICT\\b.*\\bDO\\s+UPDATE\\b")

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
        mutations = setOf(
            LifecycleMutationTuple("INSERT", "llm_runs"),
            LifecycleMutationTuple("UPDATE", "llm_runs"),
        ),
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
