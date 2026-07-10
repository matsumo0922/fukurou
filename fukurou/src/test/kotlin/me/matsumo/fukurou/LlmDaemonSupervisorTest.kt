package me.matsumo.fukurou

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.RuntimeConfigActivationResult
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.RuntimeConfigValidationResult
import me.matsumo.fukurou.trading.config.RuntimeConfigVersionSummary
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.config.calculateRuntimeConfigHash
import me.matsumo.fukurou.trading.daemon.LlmDaemonInvocationMetadata
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * daemon desired/observed state と hot apply の本番 supervisor 配線を検証するテスト。
 */
class LlmDaemonSupervisorTest {

    @Test
    fun supervisorHotAppliesOnlyDaemonConfigAndKeepsProcessRunnerConfig() = runBlocking {
        val processConfig = TradingBotConfig()
        val activeConfig = processConfig.copy(daemon = processConfig.daemon.copy(enabled = true))
        val snapshotState = MutableRuntimeSnapshot(activeConfig)
        val workers = CopyOnWriteArrayList<FakeWorker>()
        val supervisor = createSupervisor(processConfig, snapshotState) { request ->
            FakeWorker(request).also(workers::add)
        }.start()

        awaitState(supervisor, LlmDaemonObservedState.RUNNING)
        val nextConfig = activeConfig.copy(
            runner = activeConfig.runner.copy(
                maxInvocationsPerHour = activeConfig.runner.maxInvocationsPerHour - 1,
                perRunTimeout = activeConfig.runner.perRunTimeout.plusSeconds(60),
            ),
            daemon = activeConfig.daemon.copy(pollInterval = activeConfig.daemon.pollInterval.multipliedBy(2)),
        )
        snapshotState.update(nextConfig)
        supervisor.notifyConfigChanged()
        awaitWorkerCount(workers, 2)
        awaitState(supervisor, LlmDaemonObservedState.RUNNING)

        assertEquals(processConfig.runner, workers.last().request.tradingConfig.runner)
        assertEquals(nextConfig.daemon, workers.last().request.tradingConfig.daemon)
        assertEquals(LlmDaemonWorkerStopResult.DRAINED, workers.first().stopResult)
        assertTrue(supervisor.status().restartRequired)
        assertNotEquals(supervisor.status().activeConfig.hash, supervisor.status().appliedConfig.hash)
        assertEquals(
            supervisor.status().activeConfig.versionId,
            supervisor.status().daemonAppliedConfig.sourceVersionId,
        )
        assertEquals("daemon", supervisor.status().daemonAppliedConfig.component)
        assertNotEquals(supervisor.status().activeConfig.hash, supervisor.status().daemonAppliedConfig.hash)
        assertEquals(processConfig.runner.perRunTimeout.plusMillis(10), workers.first().stopTimeout)

        supervisor.close()
    }

    @Test
    fun stopShowsStoppingWithInFlightMetadataUntilDrainCompletes() = runBlocking {
        val processConfig = TradingBotConfig()
        val snapshotState = MutableRuntimeSnapshot(
            processConfig.copy(daemon = processConfig.daemon.copy(enabled = true)),
        )
        val stopGate = CompletableDeferred<Unit>()
        lateinit var worker: FakeWorker
        val supervisor = createSupervisor(processConfig, snapshotState) { request ->
            FakeWorker(request, stopGate = stopGate).also { created -> worker = created }
        }.start()

        awaitState(supervisor, LlmDaemonObservedState.RUNNING)
        worker.emitInvocation("invocation-1", Instant.parse("2026-07-10T00:00:00Z"))
        val stopRequest = launch {
            supervisor.setDesiredEnabled(false, "operator stop").getOrThrow()
        }
        awaitState(supervisor, LlmDaemonObservedState.STOPPING)

        assertFalse(supervisor.status().desiredEnabled)
        assertEquals("invocation-1", supervisor.status().inFlightRun?.invocationId)
        assertEquals(LlmDaemonStatusReason.INTENTIONAL_STOP, supervisor.status().reason)

        stopGate.complete(Unit)
        stopRequest.join()
        assertEquals(LlmDaemonObservedState.STOPPED, supervisor.status().observedState)
        assertEquals(LlmDaemonStatusReason.INTENTIONAL_STOP, supervisor.status().reason)
        assertEquals(null, supervisor.status().inFlightRun)

        supervisor.close()
    }

    @Test
    fun hardHaltRejectsStartBeforeVersionedDesiredStateChanges() = runBlocking {
        val clock = SupervisorMutableClock(Instant.parse("2026-07-10T00:00:00Z"))
        val processConfig = TradingBotConfig()
        val snapshotState = MutableRuntimeSnapshot(processConfig)
        val riskStateRepository = InMemoryRiskStateRepository(clock)
        riskStateRepository.setHardHalt("incident", clock.instant()).getOrThrow()
        var activationCount = 0
        val supervisor = createSupervisor(
            processConfig = processConfig,
            snapshotState = snapshotState,
            riskStateRepository = riskStateRepository,
            activator = LlmDaemonDesiredStateActivator { _, _ ->
                activationCount += 1
                error("must not activate")
            },
        ) { request -> FakeWorker(request) }

        val result = supervisor.setDesiredEnabled(true, "operator start")

        assertTrue(result.exceptionOrNull() is LlmDaemonHardHaltRejectedException)
        assertEquals(0, activationCount)
        assertFalse(snapshotState.snapshot.tradingConfig.daemon.enabled)

        supervisor.close()
    }

    @Test
    fun failedWorkerKeepsDesiredEnabledAndRetriesWithBackoff() = runBlocking {
        val processConfig = TradingBotConfig()
        val snapshotState = MutableRuntimeSnapshot(
            processConfig.copy(daemon = processConfig.daemon.copy(enabled = true)),
        )
        var attempts = 0
        val supervisor = createSupervisor(
            processConfig = processConfig,
            snapshotState = snapshotState,
            initialRetryDelay = Duration.ofMillis(10),
            maxRetryDelay = Duration.ofMillis(20),
        ) { request ->
            attempts += 1
            FakeWorker(request, failOnStart = attempts == 1)
        }.start()

        awaitState(supervisor, LlmDaemonObservedState.RUNNING)

        assertTrue(supervisor.status().desiredEnabled)
        assertEquals(2, attempts)
        assertEquals(null, supervisor.status().nextRetryAt)

        supervisor.close()
    }

    @Test
    fun silenceWarningUsesHeartbeatPlusPollAndIgnoresIntentionalStop() = runBlocking {
        val clock = SupervisorMutableClock(Instant.parse("2026-07-10T00:00:00Z"))
        val processConfig = TradingBotConfig()
        val activeConfig = processConfig.copy(daemon = processConfig.daemon.copy(enabled = true))
        val snapshotState = MutableRuntimeSnapshot(activeConfig)
        lateinit var worker: FakeWorker
        val supervisor = createSupervisor(
            processConfig = processConfig,
            snapshotState = snapshotState,
            clock = clock,
        ) { request -> FakeWorker(request).also { created -> worker = created } }.start()

        awaitState(supervisor, LlmDaemonObservedState.RUNNING)
        worker.emitSignal(clock.instant())
        clock.advance(activeConfig.daemon.flatHeartbeatInterval.plus(activeConfig.daemon.pollInterval).plusSeconds(1))

        assertTrue(supervisor.status().silenceWarning)
        assertEquals(LlmDaemonStatusReason.SILENCE_DETECTED, supervisor.status().reason)

        supervisor.setDesiredEnabled(false, "planned stop").getOrThrow()
        assertFalse(supervisor.status().silenceWarning)

        supervisor.close()
    }

    @Test
    fun drainTimeoutCancelsWorkerAndLeavesTimeoutAudit() = runBlocking {
        val processConfig = TradingBotConfig()
        val snapshotState = MutableRuntimeSnapshot(
            processConfig.copy(daemon = processConfig.daemon.copy(enabled = true)),
        )
        val eventLog = InMemoryCommandEventLog()
        lateinit var worker: FakeWorker
        val supervisor = createSupervisor(
            processConfig = processConfig,
            snapshotState = snapshotState,
            eventLog = eventLog,
        ) { request ->
            FakeWorker(request, configuredStopResult = LlmDaemonWorkerStopResult.TIMED_OUT)
                .also { created -> worker = created }
        }.start()

        awaitState(supervisor, LlmDaemonObservedState.RUNNING)
        worker.emitInvocation("hung-run", Instant.now())
        supervisor.setDesiredEnabled(false, "bounded stop").getOrThrow()

        assertEquals(LlmDaemonObservedState.STOPPED, supervisor.status().observedState)
        assertEquals(LlmDaemonStatusReason.DRAIN_TIMED_OUT, supervisor.status().reason)
        assertTrue(eventLog.events().any { event -> event.eventType == CommandEventType.DAEMON_DRAIN_TIMED_OUT })

        supervisor.close()
    }

    @Test
    fun stopSignalIsRaisedBeforeStoppingAuditCompletes() = runBlocking {
        val processConfig = TradingBotConfig()
        val snapshotState = MutableRuntimeSnapshot(
            processConfig.copy(daemon = processConfig.daemon.copy(enabled = true)),
        )
        val auditBlocked = CompletableDeferred<Unit>()
        val releaseAudit = CompletableDeferred<Unit>()
        val eventLog = BlockingStoppingEventLog(auditBlocked, releaseAudit)
        lateinit var worker: FakeWorker
        val supervisor = createSupervisor(
            processConfig = processConfig,
            snapshotState = snapshotState,
            eventLog = eventLog,
        ) { request -> FakeWorker(request).also { created -> worker = created } }.start()
        awaitState(supervisor, LlmDaemonObservedState.RUNNING)

        val stopRequest = launch {
            supervisor.setDesiredEnabled(false, "operator stop").getOrThrow()
        }
        auditBlocked.await()

        assertTrue(worker.stopRequested)

        releaseAudit.complete(Unit)
        stopRequest.join()
        supervisor.close()
    }

    @Test
    fun unavailableRuntimeSnapshotRecoversByBoundedRetryWithoutNotification() = runBlocking {
        val processConfig = TradingBotConfig()
        val enabledConfig = processConfig.copy(daemon = processConfig.daemon.copy(enabled = true))
        val snapshotState = MutableRuntimeSnapshot(enabledConfig)
        snapshotState.markUnavailable()
        val supervisor = createSupervisor(
            processConfig = processConfig,
            snapshotState = snapshotState,
            initialRetryDelay = Duration.ofMillis(10),
        ) { request -> FakeWorker(request) }.start()

        awaitState(supervisor, LlmDaemonObservedState.DEGRADED)
        assertEquals(LlmDaemonStatusReason.RUNTIME_CONFIG_UNAVAILABLE, supervisor.status().reason)
        assertTrue(supervisor.status().nextRetryAt != null)

        snapshotState.update(enabledConfig)
        awaitState(supervisor, LlmDaemonObservedState.RUNNING)

        supervisor.close()
    }

    @Test
    fun intentionalStopReasonIsRestoredFromCompletedAuditAfterRestart() = runBlocking {
        val processConfig = TradingBotConfig()
        val snapshotState = MutableRuntimeSnapshot(processConfig)
        val stoppedAt = Instant.parse("2026-07-10T00:00:00Z")
        val supervisor = createSupervisor(
            processConfig = processConfig,
            snapshotState = snapshotState,
            desiredStateReasonProvider = LlmDaemonDesiredStateReasonProvider {
                Result.success(LlmDaemonDesiredStateChange(false, "maintenance window", stoppedAt))
            },
        ) { request -> FakeWorker(request) }.start()

        awaitReason(supervisor, LlmDaemonStatusReason.INTENTIONAL_STOP)

        assertEquals("maintenance window", supervisor.status().detail)

        snapshotState.update(processConfig.copy(runner = processConfig.runner.copy(maxInvocationsPerHour = 1)))
        supervisor.notifyConfigChanged()
        awaitReason(supervisor, LlmDaemonStatusReason.INTENTIONAL_STOP)
        assertEquals("maintenance window", supervisor.status().detail)

        supervisor.close()
    }

    private fun createSupervisor(
        processConfig: TradingBotConfig,
        snapshotState: MutableRuntimeSnapshot,
        riskStateRepository: InMemoryRiskStateRepository = InMemoryRiskStateRepository(Clock.systemUTC()),
        eventLog: CommandEventLog = InMemoryCommandEventLog(),
        activator: LlmDaemonDesiredStateActivator = snapshotState.activator(),
        desiredStateReasonProvider: LlmDaemonDesiredStateReasonProvider =
            LlmDaemonDesiredStateReasonProvider { Result.success(null) },
        clock: Clock = Clock.systemUTC(),
        initialRetryDelay: Duration = Duration.ofMillis(10),
        maxRetryDelay: Duration = Duration.ofMillis(40),
        workerFactory: (LlmDaemonWorkerRequest) -> LlmDaemonWorkerHandle,
    ): LlmDaemonSupervisor {
        val processSnapshot = MutableRuntimeSnapshot.snapshotFor(processConfig, version = 1)

        return LlmDaemonSupervisor(
            processSnapshot = processSnapshot,
            snapshotProvider = LlmDaemonRuntimeSnapshotProvider { snapshotState.snapshot },
            desiredStateActivator = activator,
            riskStateRepository = riskStateRepository,
            commandEventLog = eventLog,
            desiredStateReasonProvider = desiredStateReasonProvider,
            workerFactory = LlmDaemonWorkerFactory(workerFactory),
            clock = clock,
            drainGrace = Duration.ofMillis(10),
            initialRetryDelay = initialRetryDelay,
            maxRetryDelay = maxRetryDelay,
        )
    }

    private suspend fun awaitState(supervisor: LlmDaemonSupervisor, state: LlmDaemonObservedState) {
        repeat(200) {
            if (supervisor.status().observedState == state) {
                return
            }

            delay(5)
        }

        error("daemon did not reach $state: ${supervisor.status()}")
    }

    private suspend fun awaitWorkerCount(workers: List<FakeWorker>, expected: Int) {
        repeat(200) {
            if (workers.size >= expected) {
                return
            }

            delay(5)
        }

        error("worker count did not reach $expected")
    }

    private suspend fun awaitReason(supervisor: LlmDaemonSupervisor, reason: LlmDaemonStatusReason) {
        repeat(200) {
            if (supervisor.status().reason == reason) {
                return
            }

            delay(5)
        }

        error("daemon did not reach reason $reason: ${supervisor.status()}")
    }
}

private class MutableRuntimeSnapshot(initialConfig: TradingBotConfig) {
    var snapshot = snapshotFor(initialConfig, version = 2)
        private set

    fun update(config: TradingBotConfig) {
        val version = snapshot.configIdentity?.versionId?.toIntOrNull()?.plus(1) ?: 3
        snapshot = snapshotFor(config, version)
    }

    fun markUnavailable() {
        snapshot = snapshot.copy(available = false)
    }

    fun activator(): LlmDaemonDesiredStateActivator {
        return LlmDaemonDesiredStateActivator { enabled, _ ->
            update(snapshot.tradingConfig.copy(daemon = snapshot.tradingConfig.daemon.copy(enabled = enabled)))
            Result.success(
                RuntimeConfigActivationResult(
                    activeVersion = RuntimeConfigVersionSummary(
                        id = requireNotNull(requireNotNull(snapshot.configIdentity).versionId),
                        status = "ACTIVE",
                        createdAt = Instant.EPOCH.toString(),
                        activatedAt = Instant.EPOCH.toString(),
                        createdBy = "test",
                        note = null,
                        hash = requireNotNull(snapshot.configIdentity).hash,
                    ),
                    previousActiveVersionId = null,
                    validation = RuntimeConfigValidationResult(valid = true),
                ),
            )
        }
    }

    companion object {
        fun snapshotFor(config: TradingBotConfig, version: Int): LlmDaemonRuntimeSnapshot {
            val values = RuntimeConfigCatalog.runtimeValues(config)

            return LlmDaemonRuntimeSnapshot(
                tradingConfig = config,
                configIdentity = RuntimeConfigAuditSnapshot(
                    versionId = version.toString(),
                    hash = calculateRuntimeConfigHash(values),
                ),
                values = values,
                available = true,
            )
        }
    }
}

private class FakeWorker(
    val request: LlmDaemonWorkerRequest,
    private val stopGate: CompletableDeferred<Unit>? = null,
    private val configuredStopResult: LlmDaemonWorkerStopResult = LlmDaemonWorkerStopResult.DRAINED,
    private val failOnStart: Boolean = false,
) : LlmDaemonWorkerHandle {
    var stopResult: LlmDaemonWorkerStopResult? = null
        private set
    var stopRequested = false
        private set
    var stopTimeout: Duration? = null
        private set

    override fun start(): LlmDaemonWorkerHandle {
        if (failOnStart) {
            request.lifecycleListener.onFailed(IllegalStateException("start failed"))
        } else {
            request.lifecycleListener.onStarted()
        }

        return this
    }

    override fun requestStop() {
        stopRequested = true
    }

    override suspend fun stopGracefully(timeout: Duration): LlmDaemonWorkerStopResult {
        requestStop()
        stopTimeout = timeout
        stopGate?.await()
        stopResult = configuredStopResult

        return configuredStopResult
    }

    fun emitInvocation(invocationId: String, startedAt: Instant) {
        request.observer.onInvocationStarted(
            LlmDaemonInvocationMetadata(
                invocationId = invocationId,
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                startedAt = startedAt,
            ),
        )
    }

    fun emitSignal(at: Instant) {
        request.observer.onSchedulerSignal(at)
    }
}

private class BlockingStoppingEventLog(
    private val blocked: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
    private val delegate: InMemoryCommandEventLog = InMemoryCommandEventLog(),
) : CommandEventLog by delegate {
    override suspend fun append(event: CommandEvent): Result<Unit> {
        if (event.eventType == CommandEventType.DAEMON_STATE_CHANGED &&
            event.payload.contains("\"observedState\":\"STOPPING\"")
        ) {
            blocked.complete(Unit)
            release.await()
        }

        return delegate.append(event)
    }
}

private class SupervisorMutableClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
