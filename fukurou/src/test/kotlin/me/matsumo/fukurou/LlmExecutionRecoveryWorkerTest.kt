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
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import me.matsumo.fukurou.trading.runner.OneShotExecutionPolicy
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** startup recovery audit の secret-free policy snapshot を検証する。 */
class LlmExecutionRecoveryWorkerTest {
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
