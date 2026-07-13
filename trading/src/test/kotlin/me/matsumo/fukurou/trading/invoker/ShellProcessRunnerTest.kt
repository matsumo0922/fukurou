package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * ShellProcessRunner の process tree timeout contract を検証するテスト。
 */
class ShellProcessRunnerTest {

    @Test
    fun run_createsMissingNestedWorkingDirectoryBeforeLaunch() = runBlocking {
        val echoPath = Path.of("/bin/echo")
        if (!Files.isExecutable(echoPath)) {
            return@runBlocking
        }

        val tempDirectory = Files.createTempDirectory("fukurou-process-runner-working-dir-test")
        val workingDirectory = tempDirectory.resolve("a/b/c")
        val command = RenderedLlmCommand(
            executable = echoPath.toString(),
            args = listOf("created"),
            environment = emptyMap(),
            workingDirectory = workingDirectory,
            timeout = Duration.ofSeconds(1),
            stdin = null,
        )

        val result = ShellProcessRunner().run(command).getOrThrow()

        assertEquals(ProcessRunStatus.EXITED, result.status)
        assertEquals(0, result.exitCode)
        assertEquals("created\n", result.stdout)
        assertTrue(Files.isDirectory(workingDirectory))
    }

    @Test
    fun run_usesExistingWorkingDirectoryAsBefore() = runBlocking {
        val echoPath = Path.of("/bin/echo")
        if (!Files.isExecutable(echoPath)) {
            return@runBlocking
        }

        val workingDirectory = Files.createTempDirectory("fukurou-process-runner-existing-dir-test")
        val command = RenderedLlmCommand(
            executable = echoPath.toString(),
            args = listOf("existing"),
            environment = emptyMap(),
            workingDirectory = workingDirectory,
            timeout = Duration.ofSeconds(1),
            stdin = null,
        )

        val result = ShellProcessRunner().run(command).getOrThrow()

        assertEquals(ProcessRunStatus.EXITED, result.status)
        assertEquals(0, result.exitCode)
        assertEquals("existing\n", result.stdout)
        assertTrue(Files.isDirectory(workingDirectory))
    }

    @Test
    fun run_timeoutKillsDescendantProcess() = runBlocking {
        val shellPath = Path.of("/bin/sh")
        if (!Files.isExecutable(shellPath)) {
            return@runBlocking
        }

        val tempDirectory = Files.createTempDirectory("fukurou-process-runner-test")
        val childPidFile = tempDirectory.resolve("child.pid")
        val script = $$"(/bin/sleep 30) & child_pid=$!; echo $child_pid > $${childPidFile.shellQuoted()}; wait $child_pid"
        val command = RenderedLlmCommand(
            executable = shellPath.toString(),
            args = listOf("-c", script),
            environment = emptyMap(),
            workingDirectory = tempDirectory,
            timeout = Duration.ofMillis(200),
            stdin = null,
        )

        val result = ShellProcessRunner().run(command).getOrThrow()
        val childPid = waitForChildPid(childPidFile)

        assertEquals(ProcessRunStatus.TIMED_OUT, result.status)
        assertFalse(waitForProcessExit(childPid))
    }

    @Test
    fun run_timeoutForceKillsProcessTreeThatIgnoresTerm() = runBlocking {
        val shellPath = Path.of("/bin/sh")
        if (!Files.isExecutable(shellPath)) return@runBlocking

        val tempDirectory = Files.createTempDirectory("fukurou-process-runner-term-ignore-test")
        val childPidFile = tempDirectory.resolve("child.pid")
        val script = $$"(/bin/sh -c 'trap \"\" TERM; exec /bin/sleep 30') >/dev/null 2>&1 & child_pid=$!; echo $child_pid > $${childPidFile.shellQuoted()}; wait $child_pid"
        val command = RenderedLlmCommand(
            executable = shellPath.toString(),
            args = listOf("-c", script),
            environment = emptyMap(),
            workingDirectory = tempDirectory,
            timeout = Duration.ofMillis(200),
            stdin = null,
        )

        val result = ShellProcessRunner(Duration.ofMillis(50)).run(command).getOrThrow()
        val childPid = waitForChildPid(childPidFile)

        assertEquals(ProcessRunStatus.TIMED_OUT, result.status)
        assertFalse(waitForProcessExit(childPid))
    }

    @Test
    fun cleanup_deletesPathsAfterCallerFinishesParsing() = runBlocking {
        val shellPath = Path.of("/bin/sh")
        if (!Files.isExecutable(shellPath)) {
            return@runBlocking
        }

        val tempDirectory = Files.createTempDirectory("fukurou-process-runner-cleanup-test")
        val cleanupFile = Files.createTempFile(tempDirectory, "secret-config", ".json")
        val cacheFile = Files.createTempFile(tempDirectory, "codex-cache", ".json")
        val command = RenderedLlmCommand(
            executable = shellPath.toString(),
            args = listOf("-c", "true"),
            environment = emptyMap(),
            workingDirectory = tempDirectory,
            timeout = Duration.ofSeconds(1),
            stdin = null,
            cleanupPaths = listOf(cleanupFile, tempDirectory),
        )

        val processRunner = ShellProcessRunner()
        val result = processRunner.run(command).getOrThrow()

        assertEquals(ProcessRunStatus.EXITED, result.status)
        assertTrue(Files.exists(cleanupFile))
        assertTrue(Files.exists(cacheFile))

        processRunner.cleanup(command).getOrThrow()

        assertFalse(Files.exists(cleanupFile))
        assertFalse(Files.exists(cacheFile))
        assertFalse(Files.exists(tempDirectory))
    }

    @Test
    fun run_cancellationKillsDescendantProcessAndCleanupRemainsExplicit() = runBlocking {
        val shellPath = Path.of("/bin/sh")
        if (!Files.isExecutable(shellPath)) {
            return@runBlocking
        }

        val tempDirectory = Files.createTempDirectory("fukurou-process-runner-cancel-test")
        val childPidFile = tempDirectory.resolve("child.pid")
        val cleanupFile = Files.createTempFile(tempDirectory, "secret-config", ".json")
        val script = $$"(/bin/sleep 30) & child_pid=$!; echo $child_pid > $${childPidFile.shellQuoted()}; wait $child_pid"
        val command = RenderedLlmCommand(
            executable = shellPath.toString(),
            args = listOf("-c", script),
            environment = emptyMap(),
            workingDirectory = tempDirectory,
            timeout = Duration.ofSeconds(30),
            stdin = null,
            cleanupPaths = listOf(cleanupFile, tempDirectory),
        )
        val processRunner = ShellProcessRunner()
        val runnerDeferred = async {
            processRunner.run(command).getOrThrow()
        }

        val childPid = waitForChildPid(childPidFile)
        runnerDeferred.cancel()

        assertFailsWith<CancellationException> {
            runnerDeferred.await()
        }
        assertFalse(waitForProcessExit(childPid))
        assertTrue(Files.exists(cleanupFile))

        processRunner.cleanup(command).getOrThrow()

        assertFalse(Files.exists(cleanupFile))
        assertFalse(Files.exists(tempDirectory))
    }

    private suspend fun waitForChildPid(childPidFile: Path): Long {
        repeat(CHILD_PID_FILE_WAIT_ATTEMPTS) {
            if (Files.exists(childPidFile)) {
                return Files.readString(childPidFile).trim().toLong()
            }

            delay(CHILD_PID_FILE_WAIT_DELAY_MS.toDuration(DurationUnit.MILLISECONDS))
        }

        error("child pid file was not written: $childPidFile")
    }

    private suspend fun waitForProcessExit(processId: Long): Boolean {
        repeat(CHILD_PID_FILE_WAIT_ATTEMPTS) {
            val processIsAlive = isProcessAlive(processId)
            if (!processIsAlive) return false

            delay(CHILD_PID_FILE_WAIT_DELAY_MS.toDuration(DurationUnit.MILLISECONDS))
        }

        return isProcessAlive(processId)
    }

    private fun isProcessAlive(processId: Long): Boolean {
        return runCatching {
            ProcessHandle.of(processId).map { handle -> handle.isAlive }.orElse(false)
        }.getOrDefault(true)
    }
}

private fun Path.shellQuoted(): String {
    return "'" + toString().replace("'", "'\\''") + "'"
}

/**
 * child PID file を待つ試行回数。
 */
private const val CHILD_PID_FILE_WAIT_ATTEMPTS = 20

/**
 * child PID file の試行間隔。
 */
private const val CHILD_PID_FILE_WAIT_DELAY_MS = 50L
