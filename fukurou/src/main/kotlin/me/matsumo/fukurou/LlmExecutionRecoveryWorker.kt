package me.matsumo.fukurou

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.runner.LlmExecutionRecoveryService
import me.matsumo.fukurou.trading.runner.OneShotExecutionPolicy
import java.time.Clock
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger

/** DB-backed stale claim scan を current process で継続する worker。 */
class LlmExecutionRecoveryWorker(
    repository: LlmLaunchReservationRepository,
    private val commandEventLog: CommandEventLog,
    private val policy: OneShotExecutionPolicy,
    private val clock: Clock,
    private val interval: Duration = policy.heartbeatInterval,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val recoveryService = LlmExecutionRecoveryService(
        repository = repository,
        policy = policy,
        clock = clock,
    )
    private var job: Job? = null

    /** startup audit 成功後に bounded periodic recovery を開始する。 */
    fun start(): LlmExecutionRecoveryWorker {
        require(job == null) { "LlmExecutionRecoveryWorker is already started." }

        job = scope.launch {
            var startupAudited = false
            while (currentCoroutineContext().isActive) {
                val result = runCatching {
                    if (!startupAudited) {
                        appendStartupAudit()
                        startupAudited = true
                    }
                    recoveryService.tick().getOrThrow()
                }
                if (result.isFailure) {
                    val throwable = requireNotNull(result.exceptionOrNull())
                    if (throwable is CancellationException) throw throwable

                    LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
                    RECOVERY_WORKER_LOGGER.log(Level.WARNING, "LLM execution recovery tick failed.", throwable)
                }
                delay(interval.toMillis())
            }
        }
        return this
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }

    private suspend fun appendStartupAudit() {
        commandEventLog.append(
            CommandEvent(
                toolName = RECOVERY_TOOL_NAME,
                toolCallId = null,
                clientRequestId = null,
                eventType = CommandEventType.LLM_EXECUTION_RECOVERY_STARTED,
                payload = startupPayload(policy),
                occurredAt = clock.instant(),
            ),
        ).getOrThrow()
    }
}

/** secret を含まない one-shot execution policy の startup audit payload。 */
internal fun startupPayload(policy: OneShotExecutionPolicy): String = buildJsonObject {
    put("timeoutSource", "runner.perRunTimeout")
    put("hardTimeoutSeconds", policy.hardTimeout.seconds)
    put("heartbeatIntervalMillis", policy.heartbeatInterval.toMillis())
    put("heartbeatMissAllowanceMillis", policy.heartbeatMissAllowance.toMillis())
    put("processTerminationGraceSeconds", policy.processTerminationGrace.seconds)
    put("persistenceTerminalTimeoutSeconds", policy.persistenceTerminalTimeout.seconds)
    put("finalizationGraceSeconds", policy.finalizationGrace.seconds)
    put("phaseSubtotalSeconds", policy.phaseSubtotal.seconds)
    put("phases", startupPhasePayload(policy))
    putJsonObject("limits") {
        put("perRunTimeoutDefaultSeconds", 180)
        put("perRunTimeoutMaxSeconds", 600)
        put("processTerminationGraceDefaultSeconds", 10)
        put("processTerminationGraceMaxSeconds", 30)
        put("persistenceTerminalTimeoutDefaultSeconds", 10)
        put("persistenceTerminalTimeoutMaxSeconds", 30)
    }
}.toString()

private fun startupPhasePayload(policy: OneShotExecutionPolicy) = buildJsonArray {
    listOf("PRE_FILTER", "PROPOSER", "FALSIFIER").forEach { phase ->
        add(
            buildJsonObject {
                put("phaseId", phase)
                put("attemptLimit", 1)
                put("timeoutSource", "runner.perRunTimeout")
                put("timeoutSeconds", policy.phaseTimeout.seconds)
            },
        )
    }
}

private const val RECOVERY_TOOL_NAME = "llm_execution_recovery"
private val RECOVERY_WORKER_LOGGER = Logger.getLogger(LlmExecutionRecoveryWorker::class.java.name)
