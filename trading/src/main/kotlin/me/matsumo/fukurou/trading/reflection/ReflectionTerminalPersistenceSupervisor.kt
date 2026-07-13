package me.matsumo.fukurou.trading.reflection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import java.util.concurrent.ConcurrentHashMap

/** Reflection の run/reservation terminal persistence を restart なしで再試行する。 */
internal object ReflectionTerminalPersistenceSupervisor {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** unresolved lifecycle を admission blocker として登録して retry loop を開始する。 */
    fun register(invocationId: String, terminalize: suspend () -> Unit) {
        val blockerToken = blockerToken(invocationId)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (currentCoroutineContext().isActive) {
                if (runCatching { terminalize() }.isSuccess) {
                    LlmExecutionAdmissionHealth.resolveRecoveryBlocker(invocationId, blockerToken)
                    jobs.remove(invocationId)
                    return@launch
                }
                delay(REFLECTION_TERMINAL_RETRY_DELAY_MILLIS)
            }
        }

        val existingJob = jobs.putIfAbsent(invocationId, job)
        if (existingJob != null) {
            job.cancel()
            return
        }

        LlmExecutionAdmissionHealth.registerRecoveryBlocker(invocationId, blockerToken)
        job.start()
    }
}

private fun blockerToken(invocationId: String): String = "reflection-terminal:$invocationId"
private const val REFLECTION_TERMINAL_RETRY_DELAY_MILLIS = 100L
