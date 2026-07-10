package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.classifyLlmFailure
import java.nio.file.FileSystemException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * standalone one-shot runner の human-facing failure boundary を検証するテスト。
 */
class OneShotRunnerMainTest {

    @Test
    fun runOneShotRunnerMain_successWritesSummaryAndReturnsSuccessExitCode() = runBlocking {
        val stdout = mutableListOf<String>()
        val result = OneShotRunnerResult(
            invocationId = "main-success",
            status = OneShotRunnerStatus.NO_TRADE_AUDITED,
            decision = null,
            intent = null,
            tradeResult = null,
        )

        val exitCode = runOneShotRunnerMain(
            environment = emptyMap(),
            launch = { result },
            stdout = stdout::add,
            stderr = {},
        )

        assertEquals(0, exitCode)
        assertEquals("one-shot runner finished invocation=main-success status=NO_TRADE_AUDITED", stdout.single())
    }

    @Test
    fun runOneShotRunnerMain_codexFailureWritesSafeErrorAndReturnsFailureExitCode() = runBlocking {
        val originalFailure = FileSystemException(
            "/temporary/codex-home/auth-path-marker.json",
            null,
            "cleanup path-message-marker",
        )
        val cleanupFailure = IllegalStateException("suppressed cleanup path-message-marker")
        originalFailure.addSuppressed(cleanupFailure)
        val boundaryFailure = originalFailure.classifyLlmFailure(LlmProvider.CODEX)
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        val exitCode = runOneShotRunnerMain(
            environment = emptyMap(),
            launch = { throw boundaryFailure },
            stdout = stdout::add,
            stderr = stderr::add,
        )
        val output = stdout.joinToString("\n") + stderr.joinToString("\n")
        assertEquals(1, exitCode)
        assertTrue(stdout.isEmpty())
        assertTrue(stderr.single().contains("category=INVOCATION_RESULT_UNAVAILABLE"))
        assertTrue(stderr.single().contains("type=FileSystemException"))
        assertFalse(output.contains("auth-path-marker"))
        assertFalse(output.contains("path-message-marker"))
        assertFalse(output.contains("at me.matsumo"))
        assertEquals(originalFailure, boundaryFailure)
        assertTrue(originalFailure.suppressed.contains(cleanupFailure))
    }

    @Test
    fun runOneShotRunnerMain_claudeFailureKeepsExistingThrowablePropagation() = runBlocking {
        val failure = IllegalStateException("synthetic claude failure")
        val stderr = mutableListOf<String>()

        val result = runCatching {
            runOneShotRunnerMain(
                environment = emptyMap(),
                launch = { throw failure },
                stdout = {},
                stderr = stderr::add,
            )
        }

        assertEquals(failure, result.exceptionOrNull())
        assertTrue(stderr.isEmpty())
    }

    @Test
    fun runOneShotRunnerMain_codexCancellationOmitsSuppressedCleanupPathAndReturnsFailureExitCode() = runBlocking {
        val cancellation = CancellationException("cancellation path-message-marker")
        val cleanupFailure = FileSystemException(
            "/temporary/codex-home/auth-path-marker.json",
            null,
            "cleanup path-message-marker",
        )
        cancellation.addSuppressed(cleanupFailure)
        val classifiedCancellation = cancellation.classifyLlmFailure(LlmProvider.CODEX)
        val stderr = mutableListOf<String>()

        val exitCode = runOneShotRunnerMain(
            environment = emptyMap(),
            launch = { throw classifiedCancellation },
            stdout = {},
            stderr = stderr::add,
        )
        val output = stderr.single()

        assertEquals(1, exitCode)
        assertSame(cancellation, classifiedCancellation)
        assertTrue(cancellation.suppressed.contains(cleanupFailure))
        assertTrue(output.contains("category=INVOCATION_RESULT_UNAVAILABLE"))
        assertTrue(output.contains("type=CancellationException"))
        assertFalse(output.contains("auth-path-marker"))
        assertFalse(output.contains("path-message-marker"))
        assertFalse(output.contains("at me.matsumo"))
    }
}
