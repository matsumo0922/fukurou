package me.matsumo.fukurou

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.evaluation.DailyTradePnlFact
import me.matsumo.fukurou.trading.evaluation.DecisionActionCount
import me.matsumo.fukurou.trading.evaluation.EvaluationLlmUsageQueryResult
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationTradeQueryResult
import me.matsumo.fukurou.trading.evaluation.InMemoryLlmRunRepository
import me.matsumo.fukurou.trading.evaluation.KillCriterionStats
import me.matsumo.fukurou.trading.reflection.ReflectionDataCollector
import me.matsumo.fukurou.trading.reflection.ReflectionReportBuilder
import me.matsumo.fukurou.trading.reflection.ReflectionRunner
import me.matsumo.fukurou.trading.reflection.ReflectionVaultWriter
import me.matsumo.fukurou.trading.runner.SecretRedactor
import org.jetbrains.exposed.v1.jdbc.Database
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ReflectionRunnerWorker の起動 gate と loop 継続を検証するテスト。
 */
class ReflectionRunnerWorkerTest {

    @Test
    fun startReflectionRunnerWorker_returnsNullWhenEnvironmentDoesNotEnableObsidian() {
        val database = unreachableDatabase()

        val unsetWorker = startReflectionRunnerWorker(
            database = database,
            environment = emptyMap(),
            clock = FIXED_CLOCK,
        )
        val explicitlyDisabledWorker = startReflectionRunnerWorker(
            database = database,
            environment = mapOf("FUKUROU_OBSIDIAN_ENABLED" to "false"),
            clock = FIXED_CLOCK,
        )

        assertNull(unsetWorker)
        assertNull(explicitlyDisabledWorker)
    }

    @Test
    fun startReflectionRunnerWorker_startsWhenObsidianEnabled() {
        val vaultPath = Files.createTempDirectory("fukurou-reflection-worker-enabled")
        val worker = startReflectionRunnerWorker(
            database = unreachableDatabase(),
            environment = reflectionEnvironment(vaultPath),
            clock = FIXED_CLOCK,
            bootstrap = { Result.success(Unit) },
        )

        try {
            assertTrue(worker != null)
        } finally {
            worker?.close()
            deleteRecursively(vaultPath)
        }
    }

    @Test
    fun workerLogsNonFatalRunFailureAndContinuesNextTick() = runBlocking {
        val vaultPath = Files.createTempDirectory("fukurou-reflection-worker-retry")
        val attempts = AtomicInteger()
        val secondAttemptCompleted = CompletableDeferred<Unit>()
        val evaluationRepository = FlakyEvaluationRepository(
            attempts = attempts,
            secondAttemptCompleted = secondAttemptCompleted,
        )
        val worker = ReflectionRunnerWorker(
            runnerFactory = {
                Result.success(
                    emptyReflectionRunner(
                        vaultPath = vaultPath,
                        evaluationRepository = evaluationRepository,
                    ),
                )
            },
            interval = Duration.ofMillis(10),
            bootstrap = { Result.success(Unit) },
            clock = FIXED_CLOCK,
        )

        try {
            worker.start()

            withTimeout(1_000) {
                secondAttemptCompleted.await()
            }
        } finally {
            worker.close()
            deleteRecursively(vaultPath)
        }

        assertTrue(attempts.get() >= 2)
    }
}

private fun emptyReflectionRunner(
    vaultPath: Path,
    evaluationRepository: EvaluationRepository = EmptyEvaluationRepository(),
): ReflectionRunner {
    return ReflectionRunner(
        dataCollector = ReflectionDataCollector(
            decisionRepository = InMemoryDecisionRepository(FIXED_CLOCK),
            llmRunRepository = InMemoryLlmRunRepository(),
            evaluationRepository = evaluationRepository,
            clock = FIXED_CLOCK,
        ),
        reportBuilder = ReflectionReportBuilder(TradingBotConfig()),
        vaultWriter = ReflectionVaultWriter(
            vaultPath = vaultPath,
            redactor = SecretRedactor(emptySet()),
        ),
    )
}

private fun reflectionEnvironment(vaultPath: Path): Map<String, String> {
    return mapOf(
        "FUKUROU_OBSIDIAN_ENABLED" to "true",
        "FUKUROU_OBSIDIAN_VAULT_PATH" to vaultPath.toString(),
        "FUKUROU_OBSIDIAN_WRITE_INTERVAL_SECONDS" to "60",
    )
}

private fun unreachableDatabase(): Database {
    return Database.connect(
        url = "jdbc:postgresql://localhost:1/fukurou",
        driver = "org.postgresql.Driver",
        user = "fukurou",
    )
}

private open class EmptyEvaluationRepository : EvaluationRepository {

    override suspend fun fetchClosedTrades(period: EvaluationPeriod, limit: Int): Result<EvaluationTradeQueryResult> {
        return Result.success(
            EvaluationTradeQueryResult(
                trades = emptyList(),
                truncated = false,
            ),
        )
    }

    override suspend fun countDecisionRuns(period: EvaluationPeriod): Result<Int> {
        return Result.success(0)
    }

    override suspend fun countDecisionsByAction(period: EvaluationPeriod): Result<List<DecisionActionCount>> {
        return Result.success(emptyList())
    }

    override suspend fun fetchDailyTradePnl(period: EvaluationPeriod): Result<List<DailyTradePnlFact>> {
        return Result.success(emptyList())
    }

    override suspend fun sumTradePnlBefore(instant: Instant): Result<BigDecimal> {
        return Result.success(BigDecimal.ZERO)
    }

    override suspend fun fetchInitialCashJpy(): Result<BigDecimal> {
        return Result.success(BigDecimal.ZERO)
    }

    override suspend fun fetchLlmPhaseUsages(
        period: EvaluationPeriod,
        limit: Int,
    ): Result<EvaluationLlmUsageQueryResult> {
        return Result.success(
            EvaluationLlmUsageQueryResult(
                facts = emptyList(),
                truncated = false,
            ),
        )
    }

    override suspend fun fetchKillCriterionStats(): Result<KillCriterionStats> {
        return Result.success(
            KillCriterionStats(
                closedTrades = 0,
                profitFactor = null,
            ),
        )
    }
}

private class FlakyEvaluationRepository(
    private val attempts: AtomicInteger,
    private val secondAttemptCompleted: CompletableDeferred<Unit>,
) : EmptyEvaluationRepository() {

    override suspend fun fetchClosedTrades(period: EvaluationPeriod, limit: Int): Result<EvaluationTradeQueryResult> {
        if (attempts.incrementAndGet() == 1) {
            return Result.failure(IllegalStateException("reflection vault is temporarily unavailable"))
        }

        secondAttemptCompleted.complete(Unit)

        return super.fetchClosedTrades(period, limit)
    }
}

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) {
        return
    }

    Files.walk(path).use { paths ->
        paths
            .sorted(Comparator.reverseOrder())
            .forEach { currentPath -> Files.deleteIfExists(currentPath) }
    }
}

/**
 * worker test の固定 clock。
 */
private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
