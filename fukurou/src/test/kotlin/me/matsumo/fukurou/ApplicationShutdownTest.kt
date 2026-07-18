package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Application resource cleanup と shutdown observer の契約を検証する。 */
class ApplicationShutdownTest {
    @Test
    fun cleanup_attemptsEveryCloseAndPreservesFirstFailure() {
        val closeOrder = mutableListOf<String>()
        val firstFailure = IllegalStateException("first")
        val laterFailure = IllegalArgumentException("later")

        val result = closeApplicationResources(
            listOf(
                {
                    closeOrder += "first"
                    throw firstFailure
                },
                { closeOrder += "middle" },
                {
                    closeOrder += "last"
                    throw laterFailure
                },
            ),
        )

        assertEquals(listOf("first", "middle", "last"), closeOrder)
        assertSame(firstFailure, result.exceptionOrNull())
        assertEquals(listOf(laterFailure), firstFailure.suppressed.toList())
    }

    @Test
    fun report_forwardsExactCleanupFailureAndReportsIt() {
        val cleanupFailure = IllegalStateException("cleanup")
        val cleanupResult = Result.failure<Unit>(cleanupFailure)
        val observedResult = AtomicReference<Result<Unit>>()
        val reportedFailure = AtomicReference<Throwable>()

        reportApplicationShutdown(
            cleanupResult = cleanupResult,
            shutdownResultObserver = observedResult::set,
            reportError = { _, throwable -> reportedFailure.set(throwable) },
        )

        assertSame(cleanupFailure, observedResult.get().exceptionOrNull())
        assertSame(cleanupFailure, reportedFailure.get())
    }

    @Test
    fun module_forwardsSuccessfulCleanupAfterApplicationStops() {
        val observedResult = AtomicReference<Result<Unit>>()

        testApplication {
            application {
                module(
                    readinessProbe = { true },
                    databaseConfig = null,
                    shutdownResultObserver = observedResult::set,
                )
            }

            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        }

        assertTrue(observedResult.get().isSuccess)
    }
}
