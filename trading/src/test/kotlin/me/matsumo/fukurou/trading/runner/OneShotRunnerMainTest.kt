package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimOutcome
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRequest
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunRecord
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.classifyLlmFailure
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import java.nio.file.FileSystemException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
    fun runOneShotRunnerMain_launchGateStopsBeforeChildLaunch() = runBlocking {
        var launched = false

        val result = runCatching {
            runOneShotRunnerMain(
                environment = emptyMap(),
                requireLaunchAllowed = { error("LLM_LAUNCH_DISABLED") },
                launch = {
                    launched = true
                    error("must not launch")
                },
            )
        }

        assertFalse(launched)
        assertTrue(result.exceptionOrNull()?.message?.contains("LLM_LAUNCH_DISABLED") == true)
    }

    @Test
    fun productionCompositionReservesManualIdentityAndPassesItToActualRunnerClaim() = runBlocking {
        val invocationId = "production-composition-manual"
        val config = TradingBotConfig()
        val baseRuntime = TradingRuntimeFactory.inMemory(tradingConfig = config)
        val reservationRepository = CapturingReservationRepository(baseRuntime.launchReservationRepository)
        val invoker = RecordingFailureLlmInvoker()
        val runtime = baseRuntime.copy(
            launchReservationRepository = reservationRepository,
            llmRunRepository = StartFailingLlmRunRepository,
        )

        val result = runCatching {
            launchOneShotRunnerWithRuntime(
                environment = emptyMap(),
                tradingConfig = config,
                tradingRuntime = runtime,
                runtimeConfigSnapshot = null,
                llmInvoker = invoker,
                invocationId = invocationId,
                clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC),
            )
        }

        assertTrue(result.isFailure)
        assertEquals(invocationId, reservationRepository.reserveRequests.single().invocationId)
        assertEquals(LlmDaemonTriggerKind.MANUAL, reservationRepository.reserveRequests.single().triggerKind)
        assertEquals(invocationId, reservationRepository.claimRequests.single().invocationId)
        assertEquals(LlmDaemonTriggerKind.MANUAL, reservationRepository.claimRequests.single().triggerKind)
        assertEquals(
            LlmLaunchReservationStatus.FAILED,
            reservationRepository.findExecutionClaim(invocationId).getOrThrow()?.status,
        )
        assertEquals(0, invoker.invocationCount)
    }

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
            requireLaunchAllowed = {},
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
            requireLaunchAllowed = {},
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
                requireLaunchAllowed = {},
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
            requireLaunchAllowed = {},
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

private class CapturingReservationRepository(
    private val delegate: LlmLaunchReservationRepository,
) : LlmLaunchReservationRepository by delegate {
    val reserveRequests = mutableListOf<LlmLaunchReservationRequest>()
    val claimRequests = mutableListOf<LlmExecutionClaimRequest>()

    override suspend fun tryReserve(request: LlmLaunchReservationRequest) = delegate.tryReserve(request).also {
        reserveRequests += request
    }

    override suspend fun claimForExecution(request: LlmExecutionClaimRequest): LlmExecutionClaimOutcome {
        claimRequests += request

        return delegate.claimForExecution(request)
    }
}

private object StartFailingLlmRunRepository : LlmRunRepository {
    override suspend fun insertRunning(start: LlmRunStart): Result<Unit> {
        return Result.failure(IllegalStateException("start persistence failed"))
    }

    override suspend fun finish(finish: LlmRunFinish): Result<Unit> = Result.failure(IllegalStateException("unused"))

    override suspend fun findByInvocationId(invocationId: String): Result<LlmRunRecord?> = Result.success(null)

    override suspend fun findRunsStartedBetween(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): Result<List<LlmRunRecord>> = Result.success(emptyList())
}

private class RecordingFailureLlmInvoker : LlmInvoker {
    var invocationCount: Int = 0
        private set

    override suspend fun invoke(request: LlmInvocationRequest): Result<LlmInvocationResult> {
        invocationCount += 1
        return Result.failure(IllegalStateException("must not invoke"))
    }
}
