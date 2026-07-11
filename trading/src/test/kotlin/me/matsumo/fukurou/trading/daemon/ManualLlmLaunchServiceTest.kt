package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.evaluation.LlmInvocationTimedOutException
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.classifyLlmFailure
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.OneShotRunnerResult
import me.matsumo.fukurou.trading.runner.OneShotRunnerStatus
import me.matsumo.fukurou.trading.safety.SafetyFloorConfig
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * ManualLlmLaunchService の reservation / audit / lifecycle contract を検証するテスト。
 */
class ManualLlmLaunchServiceTest {

    @Test
    fun manualLaunch_reservesAuditsAndStartsRunnerWithManualTrigger() = runBlocking {
        val fixture = manualFixture()

        val result = fixture.service.launch("operator requested immediate check").getOrThrow()
        val finish = fixture.reservations.nextFinish()
        val events = fixture.eventLog.events()
        val launchedPayload = events
            .single { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED }
            .payload

        assertIs<ManualLlmLaunchResult.Accepted>(result)
        assertEquals(LlmDaemonTriggerKind.MANUAL, result.triggerKind)
        assertEquals(result.invocationId, fixture.launches.single().invocationId)
        assertEquals(LlmDaemonTriggerKind.MANUAL, fixture.launches.single().triggerKind)
        assertTrue(launchedPayload.contains("operator requested immediate check"))
        assertEquals(result.invocationId, finish.invocationId)
        assertEquals(LlmLaunchReservationStatus.FINISHED, finish.status)
        assertEquals(LlmRunTerminalCause.NO_TRADE.name, finish.reason)
    }

    @Test
    fun manualLaunch_persistsStableTerminalCauseForRunnerOutcomes() = runBlocking {
        val noTradeFixture = manualFixture(
            launchHandler = { request -> successfulRunnerResult(request, OneShotRunnerStatus.NO_TRADE_AUDITED) },
        )
        val safetyDeniedFixture = manualFixture(
            launchHandler = { request ->
                successfulRunnerResult(request).copy(terminalCause = LlmRunTerminalCause.SAFETY_DENIED)
            },
        )
        val timeoutFixture = manualFixture(
            launchHandler = { throw LlmInvocationTimedOutException("proposer") },
        )
        val cancelledFixture = manualFixture(
            launchHandler = { throw CancellationException("operator stopped") },
        )
        val failedFixture = manualFixture(
            launchHandler = { error("provider unavailable") },
        )

        noTradeFixture.service.launch("no trade").getOrThrow()
        safetyDeniedFixture.service.launch("safety denied").getOrThrow()
        timeoutFixture.service.launch("timeout").getOrThrow()
        cancelledFixture.service.launch("cancelled").getOrThrow()
        failedFixture.service.launch("failed").getOrThrow()

        assertEquals(LlmRunTerminalCause.NO_TRADE.name, noTradeFixture.reservations.nextFinish().reason)
        assertEquals(LlmRunTerminalCause.SAFETY_DENIED.name, safetyDeniedFixture.reservations.nextFinish().reason)
        assertEquals(LlmRunTerminalCause.TIMED_OUT.name, timeoutFixture.reservations.nextFinish().reason)
        assertEquals(LlmRunTerminalCause.CALLER_CANCELLED.name, cancelledFixture.reservations.nextFinish().reason)
        assertEquals(LlmRunTerminalCause.RUNNER_FAILED.name, failedFixture.reservations.nextFinish().reason)
    }

    @Test
    fun manualLaunch_usesReservationBudgetAndAuditsSkipWhenDailyCapIsConsumed() = runBlocking {
        val fixture = manualFixture(
            tradingConfig = tradingConfig(
                runner = LlmRunnerConfig(maxInvocationsPerDay = 1),
            ),
        )

        val firstResult = fixture.service.launch("first manual check").getOrThrow()
        fixture.reservations.nextFinish()
        val secondResult = fixture.service.launch("second manual check").getOrThrow()
        val skipPayload = fixture.eventLog.events()
            .single { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_SKIPPED }
            .payload

        assertIs<ManualLlmLaunchResult.Accepted>(firstResult)
        assertIs<ManualLlmLaunchResult.Rejected>(secondResult)
        assertEquals("max_invocations_per_day_exceeded", secondResult.reason)
        assertEquals(1, fixture.launches.size)
        assertTrue(skipPayload.contains("max_invocations_per_day_exceeded"))
        assertTrue(skipPayload.contains("second manual check"))
    }

    @Test
    fun manualLaunch_usesReservationSingleFlightAndDoesNotStartSecondRunner() = runBlocking {
        val runnerStarted = CompletableDeferred<Unit>()
        val releaseRunner = CompletableDeferred<Unit>()
        val fixture = manualFixture(
            launchHandler = { request ->
                runnerStarted.complete(Unit)
                releaseRunner.await()
                successfulRunnerResult(request)
            },
        )

        val firstResult = fixture.service.launch("first manual check").getOrThrow()
        runnerStarted.await()
        val secondResult = fixture.service.launch("second manual check").getOrThrow()

        releaseRunner.complete(Unit)
        fixture.reservations.nextFinish()

        assertIs<ManualLlmLaunchResult.Accepted>(firstResult)
        assertIs<ManualLlmLaunchResult.Rejected>(secondResult)
        assertEquals("concurrent_invocation", secondResult.reason)
        assertEquals(1, fixture.launches.size)
    }

    @Test
    fun manualLaunch_respectsHardAndSoftHaltRules() = runBlocking {
        val hardHaltFixture = manualFixture()
        hardHaltFixture.riskStateRepository.setHardHalt("operator halt", fixedInstant()).getOrThrow()

        val hardResult = hardHaltFixture.service.launch("hard halted manual check").getOrThrow()

        val softFlatFixture = manualFixture()
        softFlatFixture.riskStateRepository.setSoftHalt("operator pause", fixedInstant()).getOrThrow()

        val softFlatResult = softFlatFixture.service.launch("soft halted flat check").getOrThrow()

        val softHoldingFixture = manualFixture(hasOpenRisk = true)
        softHoldingFixture.riskStateRepository.setSoftHalt("operator pause", fixedInstant()).getOrThrow()

        val softHoldingResult = softHoldingFixture.service.launch("soft halted holding check").getOrThrow()
        softHoldingFixture.reservations.nextFinish()

        assertIs<ManualLlmLaunchResult.Rejected>(hardResult)
        assertEquals("hard_halt", hardResult.reason)
        assertEquals(0, hardHaltFixture.launches.size)
        assertIs<ManualLlmLaunchResult.Rejected>(softFlatResult)
        assertEquals("soft_halt_flat", softFlatResult.reason)
        assertEquals(0, softFlatFixture.launches.size)
        assertIs<ManualLlmLaunchResult.Accepted>(softHoldingResult)
        assertEquals(1, softHoldingFixture.launches.size)
    }

    @Test
    fun manualLaunch_bypassesCadenceWithoutMovingFlatHeartbeatNextFireTime() = runBlocking {
        val clock = ManualTestClock(fixedInstant())
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val eventLog = InMemoryCommandEventLog()
        val reservations = RecordingLlmLaunchReservationRepository(
            InMemoryLlmLaunchReservationRepository(riskStateRepository),
        )
        val launchRequests = mutableListOf<OneShotRunnerRequest>()
        val idGenerator = deterministicIds()
        val daemonConfig = LlmDaemonConfig(
            enabled = true,
            priceMoveTriggerEnabled = false,
        )
        val config = tradingConfig(daemon = daemonConfig)
        val manualService = manualService(
            tradingConfig = config,
            clock = clock,
            riskStateRepository = riskStateRepository,
            eventLog = eventLog,
            reservations = reservations,
            launches = launchRequests,
            idGenerator = idGenerator,
        )
        val scheduler = LlmDaemonScheduler(
            tradingConfig = config,
            dependencies = LlmDaemonSchedulerDependencies(
                riskStateRepository = riskStateRepository,
                commandEventLog = eventLog,
                launchReservationRepository = reservations,
                openRiskReader = LlmDaemonOpenRiskReader { Result.success(false) },
                tickerReader = LlmDaemonTickerReader { error("ticker must not be read") },
                positionsReader = LlmDaemonPositionsReader { Result.success(emptyList()) },
                entryFillReader = LlmDaemonEntryFillReader { Result.success(null) },
            ),
            runtime = LlmDaemonSchedulerRuntime(
                requestBase = defaultRequest(),
                launchOneShot = { request ->
                    launchRequests += request
                    Result.success(successfulRunnerResult(request))
                },
                clock = clock,
                idGenerator = idGenerator,
            ),
        )

        val firstHeartbeatResult = scheduler.tick()
        reservations.nextFinish()
        clock.advance(Duration.ofMinutes(1))
        val manualResult = manualService.launch("operator immediate check").getOrThrow()
        reservations.nextFinish()
        clock.advance(Duration.ofMinutes(13))
        val beforeDueResult = scheduler.tick()
        clock.advance(Duration.ofMinutes(1))
        val dueResult = scheduler.tick()
        reservations.nextFinish()

        assertIs<LlmDaemonTickResult.Launched>(firstHeartbeatResult)
        assertIs<ManualLlmLaunchResult.Accepted>(manualResult)
        assertIs<LlmDaemonTickResult.Skipped>(beforeDueResult)
        assertEquals("no_trigger_due", beforeDueResult.reason)
        assertIs<LlmDaemonTickResult.Launched>(dueResult)
        assertEquals(LlmDaemonTriggerKind.FLAT_HEARTBEAT, dueResult.triggerKind)
    }

    @Test
    fun manualLaunch_finishesReservationWhenRunnerThrowsOrCancels() = runBlocking {
        val failureFixture = manualFixture(
            launchHandler = {
                error("cli auth expired")
            },
        )
        val cancellationFixture = manualFixture(
            launchHandler = {
                throw CancellationException("application stopped")
            },
        )

        failureFixture.service.launch("failure check").getOrThrow()
        val failureFinish = failureFixture.reservations.nextFinish()
        cancellationFixture.service.launch("cancellation check").getOrThrow()
        val cancellationFinish = cancellationFixture.reservations.nextFinish()

        assertEquals(LlmLaunchReservationStatus.FAILED, failureFinish.status)
        assertEquals(LlmRunTerminalCause.RUNNER_FAILED.name, failureFinish.reason)
        assertEquals(LlmLaunchReservationStatus.FAILED, cancellationFinish.status)
        assertEquals(LlmRunTerminalCause.CALLER_CANCELLED.name, cancellationFinish.reason)
    }

    @Test
    fun manualLaunch_codexFailureLogsOnlySafeCategoryAndType() = runBlocking {
        val logHandler = RecordingManualLogHandler()
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(logHandler)
        }
        val originalFailure = FileSystemException(
            "/temporary/codex-home/auth-path-marker.json",
            null,
            "cleanup path-message-marker",
        )
        val cleanupFailure = IllegalStateException("suppressed cleanup path-message-marker")
        originalFailure.addSuppressed(cleanupFailure)
        val fixture = manualFixture(
            warnLogger = RateLimitedWarnLogger(logger, Clock.fixed(fixedInstant(), ZoneOffset.UTC)),
            launchHandler = {
                throw originalFailure.classifyLlmFailure(LlmProvider.CODEX)
            },
        )

        fixture.service.launch("codex failure check").getOrThrow()
        val finish = fixture.reservations.nextFinish()
        val logRecord = logHandler.nextRecord()
        val logOutput = logRecord.message + logRecord.thrown?.stackTraceToString().orEmpty()

        assertEquals(LlmLaunchReservationStatus.FAILED, finish.status)
        assertEquals(LlmRunTerminalCause.RUNNER_FAILED.name, finish.reason)
        assertEquals(null, logRecord.thrown)
        assertTrue(logOutput.contains("category=INVOCATION_RESULT_UNAVAILABLE"))
        assertTrue(logOutput.contains("type=FileSystemException"))
        assertFalse(logOutput.contains("auth-path-marker"))
        assertFalse(logOutput.contains("path-message-marker"))
        assertTrue(originalFailure.suppressed.contains(cleanupFailure))
    }

    @Test
    fun manualLaunch_claudeFailureKeepsExistingThrowableLogging() = runBlocking {
        val logHandler = RecordingManualLogHandler()
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(logHandler)
        }
        val failure = IllegalStateException("synthetic claude failure")
        val fixture = manualFixture(
            warnLogger = RateLimitedWarnLogger(logger, Clock.fixed(fixedInstant(), ZoneOffset.UTC)),
            launchHandler = {
                throw failure.classifyLlmFailure(LlmProvider.CLAUDE)
            },
        )

        fixture.service.launch("claude failure check").getOrThrow()
        fixture.reservations.nextFinish()
        val logRecord = logHandler.nextRecord()

        assertEquals(failure, logRecord.thrown)
        assertEquals("synthetic claude failure", logRecord.thrown.message)
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun manualLaunch_finishesReservationWhenLaunchedAuditFails() = runBlocking {
        val clock = ManualTestClock(fixedInstant())
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val reservations = RecordingLlmLaunchReservationRepository(
            delegate = InMemoryLlmLaunchReservationRepository(riskStateRepository),
        )
        val launches = mutableListOf<OneShotRunnerRequest>()
        val service = manualService(
            tradingConfig = tradingConfig(),
            clock = clock,
            riskStateRepository = riskStateRepository,
            eventLog = FailingLaunchedCommandEventLog(),
            reservations = reservations,
            launches = launches,
            idGenerator = deterministicIds(),
        )

        val result = service.launch("audit write failure")
        val finish = reservations.nextFinish()
        val hasFreshRunningReservation = reservations
            .hasFreshRunningReservation(fixedInstant().minus(Duration.ofHours(1)))
            .getOrThrow()

        assertTrue(result.isFailure)
        assertEquals(emptyList(), launches)
        assertEquals(LlmLaunchReservationStatus.FAILED, finish.status)
        assertEquals(LlmRunTerminalCause.RUNNER_FAILED.name, finish.reason)
        assertFalse(hasFreshRunningReservation)
    }

    @Test
    fun manualLaunch_closeWaitsForCancellationFinishBeforeReturning() = runBlocking {
        val clock = ManualTestClock(fixedInstant())
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val runnerStarted = CompletableDeferred<Unit>()
        val releaseRunner = CompletableDeferred<Unit>()
        val finishStarted = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        val finishCompleted = CompletableDeferred<Unit>()
        val reservations = RecordingLlmLaunchReservationRepository(
            delegate = InMemoryLlmLaunchReservationRepository(riskStateRepository),
            beforeFinish = {
                finishStarted.complete(Unit)
                releaseFinish.await()
            },
            afterSuccessfulFinish = {
                finishCompleted.complete(Unit)
            },
        )
        val fixture = manualFixture(
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            launchHandler = { request ->
                runnerStarted.complete(Unit)
                releaseRunner.await()
                successfulRunnerResult(request)
            },
        )

        val launchResult = fixture.service.launch("shutdown cancellation").getOrThrow()
        runnerStarted.await()
        val closeResult = CompletableDeferred<Throwable?>()
        val closeThread = thread(
            start = true,
            name = "manual-llm-close-test",
        ) {
            closeResult.complete(runCatching { fixture.service.close() }.exceptionOrNull())
        }
        finishStarted.await()

        assertIs<ManualLlmLaunchResult.Accepted>(launchResult)
        assertEquals(false, closeResult.isCompleted)

        releaseFinish.complete(Unit)
        finishCompleted.await()
        closeThread.join(CLOSE_THREAD_JOIN_TIMEOUT_MILLIS)
        val finish = fixture.reservations.nextFinish()
        val hasFreshRunningReservation = fixture.reservations
            .hasFreshRunningReservation(fixedInstant().minus(Duration.ofHours(1)))
            .getOrThrow()

        assertEquals(false, closeThread.isAlive)
        assertEquals(null, closeResult.await())
        assertEquals(LlmLaunchReservationStatus.FAILED, finish.status)
        assertEquals(LlmRunTerminalCause.CALLER_CANCELLED.name, finish.reason)
        assertEquals(false, hasFreshRunningReservation)
    }

    @Test
    fun manualLaunch_finishesReservationWhenCloseCancelsBeforeChildStarts() = runBlocking {
        val clock = ManualTestClock(fixedInstant())
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        val queuedDispatcher = QueuedCoroutineDispatcher()
        val scopeJob = SupervisorJob()
        val runnerStarted = CompletableDeferred<Unit>()
        val reservations = RecordingLlmLaunchReservationRepository(
            delegate = InMemoryLlmLaunchReservationRepository(riskStateRepository),
        )
        val fixture = manualFixture(
            clock = clock,
            riskStateRepository = riskStateRepository,
            reservations = reservations,
            scope = CoroutineScope(scopeJob + queuedDispatcher),
            launchHandler = { request ->
                runnerStarted.complete(Unit)
                successfulRunnerResult(request)
            },
        )

        val launchResult = fixture.service.launch("shutdown before child starts").getOrThrow()
        val hasFreshRunningReservationBeforeClose = fixture.reservations
            .hasFreshRunningReservation(fixedInstant().minus(Duration.ofHours(1)))
            .getOrThrow()
        val closeStarted = CompletableDeferred<Unit>()
        val closeResult = CompletableDeferred<Throwable?>()
        val closeThread = thread(
            start = true,
            name = "manual-llm-close-before-start-test",
        ) {
            closeStarted.complete(Unit)
            closeResult.complete(runCatching { fixture.service.close() }.exceptionOrNull())
        }
        closeStarted.await()
        withTimeout(FINISH_TIMEOUT_MILLIS.toDuration(DurationUnit.MILLISECONDS)) {
            while (!scopeJob.isCancelled) {
                yield()
            }
        }

        assertEquals(false, closeResult.isCompleted)

        queuedDispatcher.runQueuedTasks()
        closeThread.join(CLOSE_THREAD_JOIN_TIMEOUT_MILLIS)
        val finish = withTimeout(FINISH_TIMEOUT_MILLIS.toDuration(DurationUnit.MILLISECONDS)) {
            fixture.reservations.nextFinish()
        }
        val hasFreshRunningReservationAfterClose = fixture.reservations
            .hasFreshRunningReservation(fixedInstant().minus(Duration.ofHours(1)))
            .getOrThrow()

        assertIs<ManualLlmLaunchResult.Accepted>(launchResult)
        assertEquals(true, hasFreshRunningReservationBeforeClose)
        assertEquals(false, runnerStarted.isCompleted)
        assertEquals(false, closeThread.isAlive)
        assertEquals(null, closeResult.await())
        assertEquals(LlmLaunchReservationStatus.FAILED, finish.status)
        assertEquals(LlmRunTerminalCause.CALLER_CANCELLED.name, finish.reason)
        assertEquals(false, hasFreshRunningReservationAfterClose)
    }

    @Test
    fun manualLaunch_worksWhenDaemonConfigIsDisabled() = runBlocking {
        val fixture = manualFixture(
            tradingConfig = tradingConfig(
                daemon = LlmDaemonConfig(enabled = false),
            ),
        )

        val result = fixture.service.launch("daemon disabled manual check").getOrThrow()
        fixture.reservations.nextFinish()

        assertIs<ManualLlmLaunchResult.Accepted>(result)
        assertEquals(LlmDaemonTriggerKind.MANUAL, fixture.launches.single().triggerKind)
    }

    @Test
    fun manualLaunch_auditIncludesRuntimeConfigSnapshot() = runBlocking {
        val runtimeConfigSnapshot = RuntimeConfigAuditSnapshot(
            versionId = "runtime-version-1",
            hash = "runtime-hash-1",
        )
        val fixture = manualFixture(runtimeConfigSnapshot = runtimeConfigSnapshot)

        val result = fixture.service.launch("operator check").getOrThrow()
        val launchedEvent = fixture.eventLog.events()
            .single { event -> event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED }

        assertIs<ManualLlmLaunchResult.Accepted>(result)
        assertEquals("runtime-version-1", launchedEvent.decisionRunContext.runtimeConfigVersionId)
        assertEquals("runtime-hash-1", launchedEvent.decisionRunContext.runtimeConfigHash)
    }
}

private fun manualFixture(
    tradingConfig: TradingBotConfig = tradingConfig(),
    clock: ManualTestClock = ManualTestClock(fixedInstant()),
    riskStateRepository: InMemoryRiskStateRepository = InMemoryRiskStateRepository(clock),
    eventLog: InMemoryCommandEventLog = InMemoryCommandEventLog(),
    reservations: RecordingLlmLaunchReservationRepository = RecordingLlmLaunchReservationRepository(
        InMemoryLlmLaunchReservationRepository(riskStateRepository),
    ),
    launches: MutableList<OneShotRunnerRequest> = mutableListOf(),
    idGenerator: () -> UUID = deterministicIds(),
    hasOpenRisk: Boolean = false,
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(Logger.getAnonymousLogger(), clock),
    launchHandler: suspend (OneShotRunnerRequest) -> OneShotRunnerResult = { request -> successfulRunnerResult(request) },
): ManualFixture {
    val service = manualService(
        tradingConfig = tradingConfig,
        clock = clock,
        riskStateRepository = riskStateRepository,
        eventLog = eventLog,
        reservations = reservations,
        launches = launches,
        idGenerator = idGenerator,
        hasOpenRisk = hasOpenRisk,
        runtimeConfigSnapshot = runtimeConfigSnapshot,
        scope = scope,
        warnLogger = warnLogger,
        launchHandler = launchHandler,
    )

    return ManualFixture(
        service = service,
        clock = clock,
        riskStateRepository = riskStateRepository,
        eventLog = eventLog,
        reservations = reservations,
        launches = launches,
    )
}

private fun manualService(
    tradingConfig: TradingBotConfig,
    clock: Clock,
    riskStateRepository: InMemoryRiskStateRepository,
    eventLog: CommandEventLog,
    reservations: RecordingLlmLaunchReservationRepository,
    launches: MutableList<OneShotRunnerRequest>,
    idGenerator: () -> UUID,
    hasOpenRisk: Boolean = false,
    runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    warnLogger: RateLimitedWarnLogger = RateLimitedWarnLogger(Logger.getAnonymousLogger(), clock),
    launchHandler: suspend (OneShotRunnerRequest) -> OneShotRunnerResult = { request -> successfulRunnerResult(request) },
): DefaultManualLlmLaunchService {
    return DefaultManualLlmLaunchService(
        tradingConfig = tradingConfig,
        runtimeConfigSnapshot = runtimeConfigSnapshot,
        dependencies = ManualLlmLaunchServiceDependencies(
            riskStateRepository = riskStateRepository,
            commandEventLog = eventLog,
            launchReservationRepository = reservations,
            openRiskReader = { Result.success(hasOpenRisk) },
        ),
        runtime = ManualLlmLaunchServiceRuntime(
            requestBase = defaultRequest(),
            launchOneShot = { request ->
                launches += request
                Result.success(launchHandler(request))
            },
            clock = clock,
            idGenerator = idGenerator,
            warnLogger = warnLogger,
            scope = scope,
        ),
    )
}

/**
 * manual launch test の warning log を channel へ保存する handler。
 */
private class RecordingManualLogHandler : Handler() {
    private val records = Channel<LogRecord>(Channel.UNLIMITED)

    override fun publish(record: LogRecord) {
        records.trySend(record)
    }

    override fun flush() = Unit

    override fun close() = Unit

    suspend fun nextRecord(): LogRecord {
        return records.receive()
    }
}

/**
 * launch 監査だけを失敗させる command event log。
 */
private class FailingLaunchedCommandEventLog : CommandEventLog {

    override suspend fun append(event: CommandEvent): Result<Unit> {
        if (event.eventType == CommandEventType.DAEMON_TRIGGER_LAUNCHED) {
            return Result.failure(IllegalStateException("audit write failed"))
        }

        return Result.success(Unit)
    }

    override suspend fun countDistinctLlmLaunchesSince(since: Instant, excludedInvocationId: String?): Result<Int> {
        return Result.success(0)
    }

    override suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
        return Result.success(0)
    }
}

private fun tradingConfig(
    runner: LlmRunnerConfig = LlmRunnerConfig(),
    daemon: LlmDaemonConfig = LlmDaemonConfig(enabled = true),
): TradingBotConfig {
    return TradingBotConfig(
        runner = runner,
        daemon = daemon,
        safetyFloor = SafetyFloorConfig(),
    )
}

private fun defaultRequest(): OneShotRunnerRequest {
    return OneShotRunnerRequest(
        repositoryRoot = Path.of(".").toAbsolutePath().normalize(),
        workingDirectory = Path.of(".").toAbsolutePath().normalize(),
        mcpJarPath = "mcp/build/libs/fukurou-mcp-all.jar",
    )
}

private fun successfulRunnerResult(
    request: OneShotRunnerRequest,
    status: OneShotRunnerStatus = OneShotRunnerStatus.NO_TRADE_DECISION,
): OneShotRunnerResult {
    return OneShotRunnerResult(
        invocationId = requireNotNull(request.invocationId),
        status = status,
        decision = null,
        intent = null,
        tradeResult = null,
    )
}

private fun deterministicIds(): () -> UUID {
    var nextId = 0L

    return {
        nextId += 1
        UUID(0L, nextId)
    }
}

/**
 * manual service test fixture。
 *
 * @param service test target
 * @param clock 可変 clock
 * @param riskStateRepository risk_state repository
 * @param eventLog command event log
 * @param reservations 記録付き LLM 起動予約 repository
 * @param launches one-shot 起動 request 一覧
 */
private data class ManualFixture(
    val service: DefaultManualLlmLaunchService,
    val clock: ManualTestClock,
    val riskStateRepository: InMemoryRiskStateRepository,
    val eventLog: InMemoryCommandEventLog,
    val reservations: RecordingLlmLaunchReservationRepository,
    val launches: MutableList<OneShotRunnerRequest>,
)

/**
 * finish 呼び出しを観測できる LLM 起動予約 repository。
 *
 * @param delegate 実際の reservation 判定を行う repository
 * @param beforeFinish delegate に渡す前の finish hook
 * @param afterSuccessfulFinish delegate 更新後の finish hook
 */
private class RecordingLlmLaunchReservationRepository(
    private val delegate: LlmLaunchReservationRepository,
    private val beforeFinish: suspend (LlmLaunchReservationFinish) -> Unit = {},
    private val afterSuccessfulFinish: suspend (LlmLaunchReservationFinish) -> Unit = {},
) : LlmLaunchReservationRepository {

    private val finishes = Channel<LlmLaunchReservationFinish>(Channel.UNLIMITED)

    override suspend fun tryReserve(request: LlmLaunchReservationRequest): Result<LlmLaunchReservationOutcome> {
        return delegate.tryReserve(request)
    }

    override suspend fun finish(finish: LlmLaunchReservationFinish): Result<Unit> {
        beforeFinish(finish)

        val result = delegate.finish(finish)

        if (result.isSuccess) {
            finishes.send(finish)
            afterSuccessfulFinish(finish)
        }

        return result
    }

    override suspend fun latestReservedAt(triggerKey: String): Result<Instant?> {
        return delegate.latestReservedAt(triggerKey)
    }

    override suspend fun latestFinishedReservedAt(triggerKey: String): Result<Instant?> {
        return delegate.latestFinishedReservedAt(triggerKey)
    }

    override suspend fun findBlockingRunningReservation(
        requestTriggerKind: LlmDaemonTriggerKind,
        activeSince: Instant,
    ): Result<LlmActiveLaunchReservation?> {
        return delegate.findBlockingRunningReservation(requestTriggerKind, activeSince)
    }

    /**
     * 次の finish 呼び出しを待つ。
     */
    suspend fun nextFinish(): LlmLaunchReservationFinish {
        return finishes.receive()
    }
}

/**
 * fake clock。
 *
 * @param currentInstant 現在時刻
 */
private class ManualTestClock(
    private var currentInstant: Instant,
) : Clock() {

    override fun instant(): Instant {
        return currentInstant
    }

    override fun getZone(): ZoneId {
        return ZoneOffset.UTC
    }

    override fun withZone(zone: ZoneId): Clock {
        return this
    }

    /**
     * 現在時刻を進める。
     */
    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}

/**
 * dispatch された coroutine を test が明示的に実行するまで開始しない dispatcher。
 */
private class QueuedCoroutineDispatcher : CoroutineDispatcher() {

    private val tasks = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.add(block)
    }

    /**
     * queue に残っている task をすべて実行する。
     */
    fun runQueuedTasks() {
        generateSequence { tasks.poll() }
            .forEach { task -> task.run() }
    }
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-03T00:00:00Z")
}

/**
 * finish 完了待ちの test timeout milliseconds。
 */
private const val FINISH_TIMEOUT_MILLIS = 1_000L

/**
 * close() 待機 thread の test timeout milliseconds。
 */
private const val CLOSE_THREAD_JOIN_TIMEOUT_MILLIS = 5_000L
