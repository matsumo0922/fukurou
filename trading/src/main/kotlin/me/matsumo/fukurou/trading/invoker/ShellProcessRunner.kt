package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * JVM の ProcessBuilder を使う ProcessRunner。
 */
class ShellProcessRunner(
    private val terminationGrace: Duration = Duration.ofSeconds(10),
) : ProcessRunner {

    init {
        require(!terminationGrace.isNegative && !terminationGrace.isZero) { "terminationGrace must be positive." }
    }

    override suspend fun run(command: RenderedLlmCommand): Result<ProcessRunResult> {
        return try {
            Result.success(runProcess(command))
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    override suspend fun cleanup(command: RenderedLlmCommand): Result<Unit> {
        return deleteCleanupPathsNonCancellable(command.cleanupPaths)
    }

    private suspend fun runProcess(command: RenderedLlmCommand): ProcessRunResult {
        return coroutineScope {
            val process = startProcess(command)

            try {
                val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }

                writeStdin(process, command.stdin)

                val exitCode = withTimeoutOrNull(command.timeout.toMillis().toDuration(DurationUnit.MILLISECONDS)) {
                    runInterruptible(Dispatchers.IO) {
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
            val descendants = process.descendantsDeepestFirst()

            descendants.forEach { descendant -> runCatching { descendant.destroy() } }
            awaitProcessHandles(descendants, terminationGrace)

            val remainingDescendants = (descendants + process.descendantsDeepestFirst())
                .distinctBy(ProcessHandle::pidSafely)
            remainingDescendants.filter(ProcessHandle::isAliveSafely)
                .forEach { descendant -> runCatching { descendant.destroyForcibly() } }
            remainingDescendants.forEach { descendant -> descendant.awaitExitQuietly() }

            process.destroy()
            val processExitedGracefully = process.waitFor(terminationGrace.toMillis(), TimeUnit.MILLISECONDS)
            if (!processExitedGracefully && process.isAlive) process.destroyForcibly()
            val processExited = process.waitFor(PROCESS_TREE_KILL_WAIT_SECONDS, TimeUnit.SECONDS)
            val processTreeExited = remainingDescendants.none { descendant ->
                descendant.pidSafely()?.isProcessAlive() ?: true
            }
            check(processExited && processTreeExited) {
                "LLM process tree did not exit after TERM/KILL sequence."
            }
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
            var firstFailure: Throwable? = null

            paths.forEach { path ->
                runCatching { deleteCleanupPath(path) }
                    .onFailure { throwable ->
                        val existingFailure = firstFailure
                        if (existingFailure == null) {
                            firstFailure = throwable
                        } else {
                            existingFailure.addSuppressed(throwable)
                        }
                    }
            }

            firstFailure?.let { throwable -> Result.failure(throwable) } ?: Result.success(Unit)
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
        runCatching { deleteCleanupPathAsCurrentUser(path) }
            .recoverCatching { failure ->
                if (!path.isProductionPerRunHome()) throw failure

                deleteProductionPerRunHome(path)
            }
            .getOrThrow()
    }

    private fun deleteCleanupPathAsCurrentUser(path: Path) {
        if (Files.isDirectory(path)) {
            Files.walk(path).use { paths ->
                paths
                    .sorted(Comparator.reverseOrder())
                    .forEach { candidate -> Files.deleteIfExists(candidate) }
            }
        } else {
            Files.deleteIfExists(path)
        }
    }

    private fun deleteProductionPerRunHome(path: Path) {
        val builder = ProcessBuilder(PRODUCTION_LLM_LAUNCHER, CLEANUP_MODE, path.toString())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
        builder.environment().clear()
        val process = builder.start()
        process.outputStream.close()
        val stderr = process.errorStream.bufferedReader().use { reader -> reader.readText() }
        val exitCode = process.waitFor()

        check(exitCode == 0) {
            "Production LLM per-run home cleanup failed: ${stderr.trim()}"
        }
    }
}

private fun Process.descendantsDeepestFirst(): List<ProcessHandle> {
    var lastFailure: Throwable? = null
    repeat(PROCESS_TREE_ENUMERATION_ATTEMPTS) {
        val descendants = runCatching { toHandle().descendants().toList().asReversed() }
            .onFailure { throwable -> lastFailure = throwable }
            .getOrNull()
        if (descendants != null) return descendants

        Thread.sleep(PROCESS_TREE_EXIT_POLL_MILLIS)
    }
    if (isAlive) throw requireNotNull(lastFailure)

    return emptyList()
}

private fun awaitProcessHandles(processHandles: List<ProcessHandle>, timeout: Duration) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (processHandles.any(ProcessHandle::isAliveSafely) && System.nanoTime() < deadline) {
        Thread.sleep(PROCESS_TREE_EXIT_POLL_MILLIS)
    }
}

private fun Long.isProcessAlive(): Boolean {
    return runCatching { ProcessHandle.of(this).map(ProcessHandle::isAliveSafely).orElse(false) }
        .getOrDefault(true)
}

private fun ProcessHandle.isAliveSafely(): Boolean {
    return runCatching { isAlive }.getOrDefault(true)
}

private fun ProcessHandle.pidSafely(): Long? {
    return runCatching { pid() }.getOrNull()
}

private fun Path.isProductionPerRunHome(): Boolean {
    val normalized = toAbsolutePath().normalize()
    val parent = normalized.parent ?: return false
    val name = normalized.fileName.toString()

    return parent == PRODUCTION_LLM_HOME_ROOT &&
        (name.startsWith(CODEX_HOME_PREFIX) || name.startsWith(CLAUDE_HOME_PREFIX))
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
private const val PROCESS_TREE_ENUMERATION_ATTEMPTS = 20
private const val PROCESS_TREE_EXIT_POLL_MILLIS = 10L
private const val PRODUCTION_LLM_LAUNCHER = "/usr/local/libexec/fukurou-llm-agent-launcher"
private const val CLEANUP_MODE = "cleanup"
private const val CODEX_HOME_PREFIX = "fukurou-codex-home-"
private const val CLAUDE_HOME_PREFIX = "fukurou-llm-config-"
private val PRODUCTION_LLM_HOME_ROOT = Path.of("/run/fukurou/llm-homes")
