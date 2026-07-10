package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ShellLlmInvoker の parse-before-cleanup lifecycle を検証するテスト。
 */
class ShellLlmInvokerTest {

    @Test
    fun invoke_parsesArtifactsBeforeCleanupAndReturnsSemanticOutput() = runBlocking {
        val artifact = Files.createTempFile("shell-llm-invoker", ".jsonl")
        val command = renderedCommand(artifact)
        val processRunner = RecordingProcessRunner(
            result = Result.success(cleanProcess()),
            cleanupAction = { Files.deleteIfExists(artifact) },
        )
        val outputParser = LlmOutputParser { _, _, _, _, _ ->
            assertTrue(Files.exists(artifact))
            ParsedLlmOutput(responseText = "semantic response", usage = null)
        }
        val invoker = ShellLlmInvoker(
            commandRenderer = StaticCommandRenderer(command),
            processRunner = processRunner,
            outputParser = outputParser,
            clock = java.time.Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )

        val result = invoker.invoke(request()).getOrThrow()

        assertEquals("semantic response", result.responseText)
        assertTrue(processRunner.cleanupCalled)
        assertFalse(Files.exists(artifact))
    }

    @Test
    fun invoke_cleansArtifactsWhenProcessStartFails() = runBlocking {
        val artifact = Files.createTempFile("shell-llm-invoker-failure", ".jsonl")
        val processRunner = RecordingProcessRunner(
            result = Result.failure(IllegalStateException("synthetic start failure")),
            cleanupAction = { Files.deleteIfExists(artifact) },
        )
        val invoker = ShellLlmInvoker(
            commandRenderer = StaticCommandRenderer(renderedCommand(artifact)),
            processRunner = processRunner,
        )

        val result = invoker.invoke(request())

        assertTrue(result.isFailure)
        assertTrue(processRunner.cleanupCalled)
        assertFalse(Files.exists(artifact))
    }

    @Test
    fun invoke_propagatesCommandRenderFailure() = runBlocking {
        val failure = FileSystemException(
            "/temporary/codex-home/auth-path-marker.json",
            null,
            "render path-message-marker",
        )
        val processRunner = RecordingProcessRunner(
            result = Result.success(cleanProcess()),
            cleanupAction = {},
        )
        val invoker = ShellLlmInvoker(
            commandRenderer = object : LlmCommandRenderer {
                override fun render(request: LlmInvocationRequest): Result<RenderedLlmCommand> {
                    return Result.failure(failure)
                }
            },
            processRunner = processRunner,
        )

        val result = invoker.invoke(request())

        assertEquals(failure, result.exceptionOrNull())
        assertEquals("FileSystemException", result.exceptionOrNull()?.safeCodexFailureOrNull()?.type)
        assertFalse(processRunner.runCalled)
        assertFalse(processRunner.cleanupCalled)
    }

    @Test
    fun invoke_propagatesCleanupFailureAfterSuccessfulProcess() = runBlocking {
        val artifact = Files.createTempFile("shell-llm-invoker-cleanup-failure", ".jsonl")
        val failure = FileSystemException(
            "/temporary/codex-home/auth-path-marker.json",
            null,
            "cleanup path-message-marker",
        )
        val processRunner = RecordingProcessRunner(
            result = Result.success(cleanProcess()),
            cleanupAction = { throw failure },
        )
        val invoker = ShellLlmInvoker(
            commandRenderer = StaticCommandRenderer(renderedCommand(artifact)),
            processRunner = processRunner,
        )

        val result = invoker.invoke(request())

        assertEquals(failure, result.exceptionOrNull())
        assertEquals("FileSystemException", result.exceptionOrNull()?.safeCodexFailureOrNull()?.type)
        assertTrue(processRunner.runCalled)
        assertTrue(processRunner.cleanupCalled)
    }

    @Test
    fun invoke_preservesPrimaryAndSuppressedCleanupFailuresWithCodexClassification() = runBlocking {
        val artifact = Files.createTempFile("shell-llm-invoker-suppressed-cleanup", ".jsonl")
        val primaryFailure = FileSystemException(
            "/temporary/codex-home/auth-path-marker.json",
            null,
            "start path-message-marker",
        )
        val cleanupFailure = IllegalStateException("suppressed cleanup path-message-marker")
        val processRunner = RecordingProcessRunner(
            result = Result.failure(primaryFailure),
            cleanupAction = { throw cleanupFailure },
        )
        val invoker = ShellLlmInvoker(
            commandRenderer = StaticCommandRenderer(renderedCommand(artifact)),
            processRunner = processRunner,
        )

        val result = invoker.invoke(request())
        val propagatedFailure = requireNotNull(result.exceptionOrNull())

        assertEquals(primaryFailure, propagatedFailure)
        assertTrue(propagatedFailure.suppressed.contains(cleanupFailure))
        assertEquals("FileSystemException", propagatedFailure.safeCodexFailureOrNull()?.type)
        assertTrue(processRunner.cleanupCalled)
    }

    @Test
    fun invoke_cleansArtifactsAfterTimedOutProcess() = runBlocking {
        val artifact = Files.createTempFile("shell-llm-invoker-timeout", ".jsonl")
        val processRunner = RecordingProcessRunner(
            result = Result.success(
                ProcessRunResult(
                    status = ProcessRunStatus.TIMED_OUT,
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                ),
            ),
            cleanupAction = { Files.deleteIfExists(artifact) },
        )
        val invoker = ShellLlmInvoker(
            commandRenderer = StaticCommandRenderer(renderedCommand(artifact)),
            processRunner = processRunner,
        )

        val result = invoker.invoke(request()).getOrThrow()

        assertEquals(ProcessRunStatus.TIMED_OUT, result.processResult.status)
        assertTrue(processRunner.cleanupCalled)
        assertFalse(Files.exists(artifact))
    }

    @Test
    fun invoke_cleansArtifactsAndRethrowsCancellation() = runBlocking {
        val artifact = Files.createTempFile("shell-llm-invoker-cancel", ".jsonl")
        val processRunner = RecordingProcessRunner(
            result = Result.failure(CancellationException("synthetic cancellation")),
            cleanupAction = { Files.deleteIfExists(artifact) },
        )
        val invoker = ShellLlmInvoker(
            commandRenderer = StaticCommandRenderer(renderedCommand(artifact)),
            processRunner = processRunner,
        )

        assertFailsWith<CancellationException> {
            invoker.invoke(request())
        }
        assertTrue(processRunner.cleanupCalled)
        assertFalse(Files.exists(artifact))
    }
}

private fun LlmOutputParser(
    block: (
        LlmInvocationRequest,
        RenderedLlmCommand,
        ProcessRunResult,
        Instant,
        Instant,
    ) -> ParsedLlmOutput,
): LlmOutputParser {
    return object : LlmOutputParser {
        override fun parse(
            request: LlmInvocationRequest,
            command: RenderedLlmCommand,
            processResult: ProcessRunResult,
            startedAt: Instant,
            completedAt: Instant,
        ): ParsedLlmOutput {
            return block(request, command, processResult, startedAt, completedAt)
        }
    }
}

private class StaticCommandRenderer(
    private val command: RenderedLlmCommand,
) : LlmCommandRenderer {
    override fun render(request: LlmInvocationRequest): Result<RenderedLlmCommand> {
        return Result.success(command)
    }
}

private class RecordingProcessRunner(
    private val result: Result<ProcessRunResult>,
    private val cleanupAction: () -> Unit,
) : ProcessRunner {
    var runCalled: Boolean = false
        private set

    var cleanupCalled: Boolean = false
        private set

    override suspend fun run(command: RenderedLlmCommand): Result<ProcessRunResult> {
        runCalled = true

        return result
    }

    override suspend fun cleanup(command: RenderedLlmCommand): Result<Unit> {
        cleanupCalled = true

        return runCatching(cleanupAction)
    }
}

private fun renderedCommand(artifact: Path): RenderedLlmCommand {
    return RenderedLlmCommand(
        executable = "synthetic",
        args = emptyList(),
        environment = emptyMap(),
        workingDirectory = Path.of("."),
        timeout = Duration.ofSeconds(1),
        stdin = null,
        cleanupPaths = listOf(artifact),
    )
}

private fun request(): LlmInvocationRequest {
    return LlmInvocationRequest(
        invocationId = "invocation-157",
        provider = LlmProvider.CODEX,
        phase = LlmInvocationPhase.FALSIFIER,
        prompt = "synthetic prompt",
        timeout = Duration.ofSeconds(1),
        workingDirectory = Path.of("."),
        decisionRunContext = DecisionRunContext.EMPTY,
        mcpServer = null,
        environment = emptyMap(),
        allowedTools = emptyList(),
    )
}

private fun cleanProcess(): ProcessRunResult {
    return ProcessRunResult(
        status = ProcessRunStatus.EXITED,
        exitCode = 0,
        stdout = "synthetic output",
        stderr = "",
    )
}
