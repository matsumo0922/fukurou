package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * JVM の ProcessBuilder を使う ProcessRunner。
 */
class ShellProcessRunner : ProcessRunner {

    override suspend fun run(command: RenderedLlmCommand): Result<ProcessRunResult> {
        return try {
            val result = runProcess(command)
            deleteCleanupPathsNonCancellable(command.cleanupPaths).map { result }
        } catch (throwable: CancellationException) {
            val cleanupResult = deleteCleanupPathsNonCancellable(command.cleanupPaths)
            cleanupResult.exceptionOrNull()?.let { cleanupFailure -> throwable.addSuppressed(cleanupFailure) }

            throw throwable
        } catch (throwable: Throwable) {
            val cleanupResult = deleteCleanupPathsNonCancellable(command.cleanupPaths)
            cleanupResult.exceptionOrNull()?.let { cleanupFailure -> throwable.addSuppressed(cleanupFailure) }

            Result.failure(throwable)
        }
    }

    private suspend fun runProcess(command: RenderedLlmCommand): ProcessRunResult {
        return coroutineScope {
            val process = startProcess(command)

            try {
                val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }

                writeStdin(process, command.stdin)

                val exitCode = withTimeoutOrNull(command.timeout.toMillis().toDuration(DurationUnit.MILLISECONDS)) {
                    withContext(Dispatchers.IO) {
                        process.waitFor()
                    }
                }

                if (exitCode == null) {
                    destroyProcessTreeNonCancellable(process).getOrThrow()

                    return@coroutineScope ProcessRunResult(
                        status = ProcessRunStatus.TIMED_OUT,
                        exitCode = null,
                        stdout = stdout.await(),
                        stderr = stderr.await(),
                    )
                }

                ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = exitCode,
                    stdout = stdout.await(),
                    stderr = stderr.await(),
                )
            } catch (throwable: CancellationException) {
                val destroyResult = destroyProcessTreeNonCancellable(process)
                destroyResult.exceptionOrNull()?.let { destroyFailure -> throwable.addSuppressed(destroyFailure) }

                throw throwable
            }
        }
    }

    private fun startProcess(command: RenderedLlmCommand): Process {
        Files.createDirectories(command.workingDirectory)

        val builder = ProcessBuilder(listOf(command.executable) + command.args)
            .directory(command.workingDirectory.toFile())
        val environment = builder.environment()

        environment.clear()
        environment.putAll(command.environment)

        return builder.start()
    }

    private suspend fun destroyProcessTree(process: Process) {
        withContext(Dispatchers.IO) {
            val descendants = process.toHandle()
                .descendants()
                .toList()
                .asReversed()

            descendants.forEach { descendant -> descendant.destroyForcibly() }
            process.destroyForcibly()

            descendants.forEach { descendant -> descendant.awaitExitQuietly() }
            process.waitFor(PROCESS_TREE_KILL_WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private suspend fun destroyProcessTreeNonCancellable(process: Process): Result<Unit> {
        return withContext(NonCancellable) {
            runCatching {
                destroyProcessTree(process)
            }
        }
    }

    private suspend fun writeStdin(process: Process, stdin: String?) {
        withContext(Dispatchers.IO) {
            process.outputStream.bufferedWriter().use { writer ->
                if (stdin != null) {
                    writer.write(stdin)
                }
            }
        }
    }

    private suspend fun deleteCleanupPaths(paths: List<Path>): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                paths.forEach { path -> deleteCleanupPath(path) }
            }
        }
    }

    private suspend fun deleteCleanupPathsNonCancellable(paths: List<Path>): Result<Unit> {
        if (paths.isEmpty()) {
            return Result.success(Unit)
        }

        return withContext(NonCancellable) {
            deleteCleanupPaths(paths)
        }
    }

    private fun deleteCleanupPath(path: Path) {
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path)

            return
        }

        Files.walk(path).use { paths ->
            paths
                .sorted(Comparator.reverseOrder())
                .forEach { candidate -> Files.deleteIfExists(candidate) }
        }
    }
}

private fun ProcessHandle.awaitExitQuietly() {
    runCatching {
        onExit().get(PROCESS_TREE_KILL_WAIT_SECONDS, TimeUnit.SECONDS)
    }
}

/**
 * process tree kill 後に exit を待つ秒数。
 */
private const val PROCESS_TREE_KILL_WAIT_SECONDS = 2L
