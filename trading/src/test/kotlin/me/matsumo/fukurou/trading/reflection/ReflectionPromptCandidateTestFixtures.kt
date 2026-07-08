package me.matsumo.fukurou.trading.reflection

import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.InMemoryLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.evaluation.DecisionActionCount
import me.matsumo.fukurou.trading.evaluation.InMemoryLlmRunRepository
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.runner.LlmInvocationAuditor
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal data class ReflectionPromptCandidateGeneratorFixture(
    val clock: MutableReflectionClock,
    val riskStateRepository: InMemoryRiskStateRepository,
    val reservationRepository: InMemoryLlmLaunchReservationRepository,
    val llmRunRepository: InMemoryLlmRunRepository,
    val commandEventLog: InMemoryCommandEventLog,
    val invoker: RecordingReflectionLlmInvoker,
    val generator: ReflectionPromptCandidateGenerator,
)

internal class RecordingReflectionLlmInvoker(
    private val result: Result<ProcessRunResult>,
) : LlmInvoker {

    private val mutableRequests = mutableListOf<LlmInvocationRequest>()

    val requests: List<LlmInvocationRequest>
        get() = mutableRequests.toList()

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        mutableRequests += request

        return result.map { processResult ->
            LlmInvocationResult(
                request = request,
                processResult = processResult,
            )
        }
    }
}

internal class MutableReflectionClock(
    initialInstant: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    private var currentInstant = initialInstant

    override fun instant(): Instant = currentInstant

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock {
        return MutableReflectionClock(currentInstant, zone)
    }

    fun advanceBy(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}

internal fun reflectionPromptCandidateGeneratorFixture(
    processResult: Result<ProcessRunResult> = Result.success(cleanReflectionProcess(validPromptCandidateJson())),
    tradingConfig: TradingBotConfig = promptCandidateTradingConfig(),
    clock: MutableReflectionClock = MutableReflectionClock(REFLECTION_TEST_INSTANT),
): ReflectionPromptCandidateGeneratorFixture {
    val riskStateRepository = InMemoryRiskStateRepository(clock)
    val reservationRepository = InMemoryLlmLaunchReservationRepository(riskStateRepository)
    val llmRunRepository = InMemoryLlmRunRepository()
    val commandEventLog = InMemoryCommandEventLog()
    val invoker = RecordingReflectionLlmInvoker(processResult)

    return ReflectionPromptCandidateGeneratorFixture(
        clock = clock,
        riskStateRepository = riskStateRepository,
        reservationRepository = reservationRepository,
        llmRunRepository = llmRunRepository,
        commandEventLog = commandEventLog,
        invoker = invoker,
        generator = ReflectionPromptCandidateGenerator(
            tradingConfig = tradingConfig,
            runtime = ReflectionPromptCandidateGeneratorRuntime(
                llm = ReflectionPromptCandidateLlmRuntime(
                    llmInvoker = invoker,
                    auditor = LlmInvocationAuditor(
                        commandEventLog = commandEventLog,
                        redactor = SecretRedactor(setOf("reflection-secret-token")),
                        clock = clock,
                    ),
                    workingDirectory = Path.of(".").toAbsolutePath().normalize(),
                    parentEnvironment = emptyMap(),
                    redactor = SecretRedactor(setOf("reflection-secret-token")),
                ),
                persistence = ReflectionPromptCandidatePersistence(
                    llmRunRepository = llmRunRepository,
                    launchReservationRepository = reservationRepository,
                ),
                clock = clock,
                logger = {},
            ),
        ),
    )
}

internal fun promptCandidateTradingConfig(
    runnerConfig: LlmRunnerConfig = LlmRunnerConfig(),
    reflectionConfig: ReflectionConfig = ReflectionConfig(),
): TradingBotConfig {
    return TradingBotConfig(
        runner = runnerConfig,
        reflection = reflectionConfig,
    )
}

internal fun reflectionDataset(weeklyTruncated: Boolean = false): ReflectionDataset {
    val daily = reflectionWindow(
        id = "2026-07-02",
        from = Instant.parse("2026-07-02T00:00:00Z"),
        toExclusive = Instant.parse("2026-07-03T00:00:00Z"),
    )
    val previousDaily = reflectionWindow(
        id = "2026-07-01",
        from = Instant.parse("2026-07-01T00:00:00Z"),
        toExclusive = Instant.parse("2026-07-02T00:00:00Z"),
    )
    val weekly = reflectionWindow(
        id = "2026-W27",
        from = Instant.parse("2026-06-29T00:00:00Z"),
        toExclusive = Instant.parse("2026-07-06T00:00:00Z"),
        truncated = weeklyTruncated,
    )
    val previousWeekly = reflectionWindow(
        id = "2026-W26",
        from = Instant.parse("2026-06-22T00:00:00Z"),
        toExclusive = Instant.parse("2026-06-29T00:00:00Z"),
    )

    return ReflectionDataset(
        tradingDate = LocalDate.parse("2026-07-02"),
        weekId = "2026-W27",
        daily = daily,
        previousTradingDate = LocalDate.parse("2026-07-01"),
        previousDaily = previousDaily,
        weekly = weekly,
        previousWeekId = "2026-W26",
        previousWeekly = previousWeekly,
        calibration = weekly,
    )
}

internal fun validPromptCandidateJson(): String {
    return """
        |{
        |  "candidates": [
        |    {
        |      "id": "prompt-candidate-2026-W27-001",
        |      "title": "entry 根拠の明確化",
        |      "target": "SystemPromptV1",
        |      "problem": "decision_runs の観測に対して entry 根拠が曖昧です。",
        |      "evidence": ["decision_runs and closed_trades evidence from 2026-W27"],
        |      "proposedChangeJa": "entry 前に closed_trades と setup tag の根拠を明記する。",
        |      "expectedImpact": "根拠の薄い entry を減らします。",
        |      "risk": "候補のため人間確認まで適用しません。",
        |      "requiresHumanApproval": true
        |    }
        |  ],
        |  "narrativeDrift": [],
        |  "tagTaxonomySuggestions": []
        |}
    """.trimMargin()
}

internal fun cleanReflectionProcess(stdout: String): ProcessRunResult {
    return ProcessRunResult(
        status = ProcessRunStatus.EXITED,
        exitCode = 0,
        stdout = stdout,
        stderr = "",
    )
}

internal fun failingReflectionProcess(stderr: String = "failed"): ProcessRunResult {
    return ProcessRunResult(
        status = ProcessRunStatus.EXITED,
        exitCode = 1,
        stdout = "",
        stderr = stderr,
    )
}

internal fun ReflectionPromptCandidateGeneration.generatedStatus(): ReflectionPromptCandidateGenerationStatus {
    val file = files.single()
    val state = requireNotNull(parseReflectionPromptCandidateNoteState(file.content))

    return state.status
}

internal fun ReflectionPromptCandidateGeneration.attemptCount(): Int {
    val file = files.single()
    val state = requireNotNull(parseReflectionPromptCandidateNoteState(file.content))

    return state.attemptCount
}

private fun reflectionWindow(
    id: String,
    from: Instant,
    toExclusive: Instant,
    truncated: Boolean = false,
): ReflectionWindowData {
    return ReflectionWindowData(
        period = ReflectionPeriod(id, from, toExclusive),
        decisions = emptyList(),
        llmRuns = emptyList(),
        closedTrades = emptyList(),
        decisionRunCount = 3,
        actionCounts = listOf(
            DecisionActionCount(action = DecisionAction.ENTER.name, count = 1),
            DecisionActionCount(action = DecisionAction.NO_TRADE.name, count = 2),
        ),
        llmPhaseUsages = emptyList(),
        truncation = ReflectionTruncationFlags(
            decisions = truncated,
            llmRuns = false,
            closedTrades = false,
            llmUsages = false,
        ),
    )
}

internal val REFLECTION_TEST_INSTANT: Instant = Instant.parse("2026-07-02T12:00:00Z")
