package me.matsumo.fukurou

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * daemon worker の新規 tick 停止と bounded drain を検証するテスト。
 */
class LlmDaemonSchedulerWorkerTest {

    @Test
    fun gracefulStopWaitsForCurrentTickAndDoesNotStartAnotherTick() = runBlocking {
        val tickStarted = CompletableDeferred<Unit>()
        val finishTick = CompletableDeferred<Unit>()
        val tickCount = AtomicInteger()
        val worker = LlmDaemonSchedulerWorker(
            schedulerFactory = {
                Result.success(
                    workerLoop {
                        tickCount.incrementAndGet()
                        tickStarted.complete(Unit)
                        finishTick.await()
                    },
                )
            },
            interval = Duration.ofMillis(10),
        ).start()
        tickStarted.await()

        val stopResult = async {
            worker.stopGracefully(Duration.ofSeconds(1))
        }
        delay(20)

        assertFalse(stopResult.isCompleted)

        finishTick.complete(Unit)
        assertEquals(LlmDaemonWorkerStopResult.DRAINED, stopResult.await())
        assertEquals(1, tickCount.get())
    }

    @Test
    fun gracefulStopCancelsHungTickAfterTimeout() = runBlocking {
        val tickStarted = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean()
        val worker = LlmDaemonSchedulerWorker(
            schedulerFactory = {
                Result.success(
                    workerLoop {
                        tickStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.set(true)
                        }
                    },
                )
            },
            interval = Duration.ofMillis(10),
        ).start()
        tickStarted.await()

        val result = worker.stopGracefully(Duration.ofMillis(20))

        assertEquals(LlmDaemonWorkerStopResult.TIMED_OUT, result)
        assertTrue(cancelled.get())
    }

    @Test
    fun cancellationWaitRemainsBoundedWhenTickDoesNotCooperate() = runBlocking {
        val tickStarted = CompletableDeferred<Unit>()
        val worker = LlmDaemonSchedulerWorker(
            schedulerFactory = {
                Result.success(
                    workerLoop {
                        tickStarted.complete(Unit)
                        Thread.sleep(200)
                    },
                )
            },
            interval = Duration.ofMillis(10),
            cancellationJoinTimeout = Duration.ofMillis(10),
        ).start()
        tickStarted.await()

        val result = worker.stopGracefully(Duration.ofMillis(10))

        assertEquals(LlmDaemonWorkerStopResult.TERMINATION_PENDING, result)

        delay(250)
        assertEquals(LlmDaemonWorkerStopResult.TIMED_OUT, worker.stopGracefully(Duration.ofMillis(10)))
        worker.shutdown()
    }

    @Test
    fun bootstrapFailureIsReportedToSupervisorWithoutInternalRetryStorm() = runBlocking {
        val failure = CompletableDeferred<Throwable>()
        val attempts = AtomicInteger()
        val worker = LlmDaemonSchedulerWorker(
            schedulerFactory = {
                attempts.incrementAndGet()
                Result.success(workerLoop {})
            },
            interval = Duration.ofMillis(10),
            bootstrap = { Result.failure(IllegalStateException("bootstrap failed")) },
            lifecycleListener = object : LlmDaemonWorkerLifecycleListener {
                override fun onFailed(error: Throwable) {
                    failure.complete(error)
                }
            },
        ).start()

        assertEquals("bootstrap failed", failure.await().message)
        assertEquals(0, attempts.get())

        worker.shutdown()
    }
}

private fun workerLoop(tick: suspend () -> Unit): LlmDaemonWorkerLoop {
    return object : LlmDaemonWorkerLoop {
        override suspend fun startSession() = Unit

        override suspend fun tick() {
            tick.invoke()
        }
    }
}
