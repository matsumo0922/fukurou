package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * ShellProcessRunner の process tree timeout contract を検証するテスト。
 */
class ShellProcessRunnerTest {

    @Test
    fun run_timeoutKillsDescendantProcess() = runBlocking {
        val shellPath = Path.of("/bin/sh")
        if (!Files.isExecutable(shellPath)) {
            return@runBlocking
        }

        val tempDirectory = Files.createTempDirectory("fukurou-process-runner-test")
        val childPidFile = tempDirectory.resolve("child.pid")
        val script = "(/bin/sleep 30) & child_pid=$!; echo \$child_pid > ${childPidFile.shellQuoted()}; wait \$child_pid"
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
        assertFalse(ProcessHandle.of(childPid).map { processHandle -> processHandle.isAlive }.orElse(false))
    }

    private suspend fun waitForChildPid(childPidFile: Path): Long {
        repeat(CHILD_PID_FILE_WAIT_ATTEMPTS) {
            if (Files.exists(childPidFile)) {
                return Files.readString(childPidFile).trim().toLong()
            }

            delay(CHILD_PID_FILE_WAIT_DELAY_MS)
        }

        error("child pid file was not written: $childPidFile")
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
