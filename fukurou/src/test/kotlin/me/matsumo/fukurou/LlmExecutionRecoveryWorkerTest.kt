package me.matsumo.fukurou

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.daemon.InMemoryLlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.runner.OneShotExecutionPolicy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** startup recovery audit の secret-free policy snapshot を検証する。 */
class LlmExecutionRecoveryWorkerTest {
    @BeforeTest
    fun setUp() {
        resetAdmissionHealthForTest()
    }

    @AfterTest
    fun tearDown() {
        resetAdmissionHealthForTest()
    }

    @Test
    fun close_waitsForInFlightAuditCancellation() = runBlocking {
        val appendEntered = CompletableDeferred<Unit>()
        val appendCancelled = CompletableDeferred<Unit>()
        val eventLog = BlockingCommandEventLog {
            appendEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                appendCancelled.complete(Unit)
            }
        }
        val worker = recoveryWorker(eventLog, Duration.ofSeconds(1)).start()
        appendEntered.await()

        worker.close()

        assertTrue(appendCancelled.isCompleted)
    }

    @Test
    fun close_throwsDedicatedTimeoutForNonCooperativeAudit() = runBlocking {
        val appendEntered = CompletableDeferred<Unit>()
        val appendReleased = CompletableDeferred<Unit>()
        val appendCompleted = CompletableDeferred<Unit>()
        val eventLog = BlockingCommandEventLog {
            withContext(NonCancellable) {
                appendEntered.complete(Unit)
                try {
                    appendReleased.await()
                    Result.success(Unit)
                } finally {
                    appendCompleted.complete(Unit)
                }
            }
        }
        val timeout = Duration.ofMillis(50)
        val worker = recoveryWorker(eventLog, timeout).start()
        appendEntered.await()

        val failure = assertFailsWith<LlmExecutionRecoveryShutdownTimeoutException> { worker.close() }

        assertTrue(failure.timeout == timeout)
        appendReleased.complete(Unit)
        withTimeout(1_000) { appendCompleted.await() }
    }

    @Test
    fun startupPayload_containsPhaseAndDerivedPolicyComponentsWithoutSecrets() {
        val payload = startupPayload(OneShotExecutionPolicy.from(LlmRunnerConfig()))

        assertContains(payload, "\"phaseId\":\"PRE_FILTER\"")
        assertContains(payload, "\"phaseId\":\"PROPOSER\"")
        assertContains(payload, "\"phaseId\":\"FALSIFIER\"")
        assertContains(payload, "\"hardTimeoutSeconds\":570")
        assertContains(payload, "\"heartbeatIntervalMillis\":28500")
        assertContains(payload, "\"processTerminationGraceSeconds\":10")
        assertContains(payload, "\"persistenceTerminalTimeoutSeconds\":10")
        assertFalse(payload.contains("password", ignoreCase = true))
        assertFalse(payload.contains("token", ignoreCase = true))
    }

    @Test
    fun productionWorker_resolvesTerminalBlockerAndRestoresReadiness() = runBlocking {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val policy = OneShotExecutionPolicy.from(LlmRunnerConfig())
        val quietPeriodNanos = policy.hardTimeout.plus(policy.processTerminationGrace).toNanos()
        val repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository()) {
            quietPeriodNanos
        }
        repository.tryReserve(
            LlmLaunchReservationRequest(
                invocationId = "worker-auto-resolve",
                triggerKind = LlmDaemonTriggerKind.FLAT_HEARTBEAT,
                triggerKey = "worker-auto-resolve",
                reservedAt = now,
                runnerConfig = LlmRunnerConfig(),
                hourlyWindow = Duration.ofHours(1),
                dailyWindow = Duration.ofDays(1),
                activeReservationStaleAfter = Duration.ofMinutes(30),
            ),
        ).getOrThrow()
        LlmExecutionAdmissionHealth.registerRecoveryBlocker(
            invocationId = "worker-auto-resolve",
            claimantToken = "worker-token",
            registeredAt = now,
            registeredAtNanos = 0L,
        )
        repository.finish(
            LlmLaunchReservationFinish(
                invocationId = "worker-auto-resolve",
                status = LlmLaunchReservationStatus.FINISHED,
                reason = null,
                finishedAt = now.plusSeconds(1),
            ),
        ).getOrThrow()
        val readiness = ReadinessProbe { LlmExecutionAdmissionHealth.isHealthy() }
        assertFalse(readiness.isReady())
        val worker = LlmExecutionRecoveryWorker(
            repository = repository,
            commandEventLog = InMemoryCommandEventLog(),
            policy = policy,
            clock = Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC),
            interval = Duration.ofMillis(10),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            nanoTime = { quietPeriodNanos },
        ).start()

        try {
            awaitMonotonicObservation(
                description = "production recovery worker readiness",
                observe = readiness::isReady,
                completed = { ready -> ready },
            )
            assertTrue(readiness.isReady())
        } finally {
            worker.close()
        }
    }
}

private fun recoveryWorker(eventLog: CommandEventLog, terminationTimeout: Duration): LlmExecutionRecoveryWorker {
    return LlmExecutionRecoveryWorker(
        repository = InMemoryLlmLaunchReservationRepository(InMemoryRiskStateRepository()),
        commandEventLog = eventLog,
        policy = OneShotExecutionPolicy.from(LlmRunnerConfig()),
        clock = Clock.systemUTC(),
        interval = Duration.ofDays(1),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        terminationTimeout = terminationTimeout,
    )
}

private class BlockingCommandEventLog(
    private val appendBlock: suspend (CommandEvent) -> Result<Unit>,
) : CommandEventLog by InMemoryCommandEventLog() {
    override suspend fun append(event: CommandEvent): Result<Unit> = appendBlock(event)
}
