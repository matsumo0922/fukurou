package me.matsumo.fukurou

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.knowledge.ObsidianWriteSummary
import me.matsumo.fukurou.trading.knowledge.ObsidianWriter
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ObsidianWriterWorker の起動 gate と loop 継続を検証するテスト。
 */
class ObsidianWriterWorkerTest {

    @Test
    fun startObsidianWriterWorker_returnsNullWhenEnvironmentDoesNotEnableWriter() {
        val database = Database.connect(
            url = "jdbc:postgresql://localhost:1/fukurou",
            driver = "org.postgresql.Driver",
            user = "fukurou",
        )

        val unsetWorker = startObsidianWriterWorker(
            database = database,
            environment = emptyMap(),
            clock = FIXED_CLOCK,
        )
        val explicitlyDisabledWorker = startObsidianWriterWorker(
            database = database,
            environment = mapOf("FUKUROU_OBSIDIAN_ENABLED" to "false"),
            clock = FIXED_CLOCK,
        )

        assertNull(unsetWorker)
        assertNull(explicitlyDisabledWorker)
    }

    @Test
    fun workerLogsNonFatalWriteFailureAndContinuesNextTick() = runBlocking {
        val attempts = AtomicInteger()
        val secondAttemptCompleted = CompletableDeferred<Unit>()
        val worker = ObsidianWriterWorker(
            writerFactory = {
                Result.success(
                    ObsidianWriter {
                        if (attempts.incrementAndGet() == 1) {
                            Result.failure(IllegalStateException("vault path is unwritable"))
                        } else {
                            secondAttemptCompleted.complete(Unit)

                            Result.success(ObsidianWriteSummary(writtenFiles = 0, unchangedFiles = 0))
                        }
                    },
                )
            },
            interval = Duration.ofMillis(10),
            clock = FIXED_CLOCK,
        )

        try {
            worker.start()

            withTimeout(1_000) {
                secondAttemptCompleted.await()
            }
        } finally {
            worker.close()
        }

        assertTrue(attempts.get() >= 2)
    }

    @Test
    fun workerRunsBootstrapOnlyUntilSuccessBeforeWriteLoop() = runBlocking {
        val bootstrapAttempts = AtomicInteger()
        val writeAttempts = AtomicInteger()
        val secondWriteCompleted = CompletableDeferred<Unit>()
        val worker = ObsidianWriterWorker(
            writerFactory = {
                Result.success(
                    ObsidianWriter {
                        if (writeAttempts.incrementAndGet() >= 2) {
                            secondWriteCompleted.complete(Unit)
                        }

                        Result.success(ObsidianWriteSummary(writtenFiles = 0, unchangedFiles = 0))
                    },
                )
            },
            interval = Duration.ofMillis(10),
            bootstrap = {
                bootstrapAttempts.incrementAndGet()

                Result.success(Unit)
            },
            clock = FIXED_CLOCK,
        )

        try {
            worker.start()

            withTimeout(1_000) {
                secondWriteCompleted.await()
            }
        } finally {
            worker.close()
        }

        assertEquals(1, bootstrapAttempts.get())
        assertTrue(writeAttempts.get() >= 2)
    }
}

/**
 * worker test の固定 instant。
 */
private val FIXED_INSTANT: Instant = Instant.parse("2026-07-02T00:00:00Z")

/**
 * worker test の固定 clock。
 */
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
