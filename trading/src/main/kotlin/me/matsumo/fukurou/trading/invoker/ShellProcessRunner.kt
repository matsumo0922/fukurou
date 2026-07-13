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
    private val linuxProcRoot: Path = Path.of(LINUX_PROC_ROOT),
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
            val startedProcess = startProcess(command)
            val process = startedProcess.process

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
                    val terminationProof = destroyProcessTreeNonCancellable(startedProcess).getOrThrow()

                    return@coroutineScope ProcessRunResult(
                        status = ProcessRunStatus.TIMED_OUT,
                        exitCode = null,
                        stdout = stdout.await(),
                        stderr = stderr.await(),
                        processTreeTerminationProof = terminationProof,
                    )
                }

                ProcessRunResult(
                    status = ProcessRunStatus.EXITED,
                    exitCode = exitCode,
                    stdout = stdout.await(),
                    stderr = stderr.await(),
                    processTreeTerminationProof = ProcessTreeTerminationProof.UNCERTAIN,
                )
            } catch (throwable: CancellationException) {
                val destroyResult = destroyProcessTreeNonCancellable(startedProcess)
                destroyResult.exceptionOrNull()?.let { destroyFailure -> throwable.addSuppressed(destroyFailure) }

                if (destroyResult.getOrNull() == ProcessTreeTerminationProof.PROVEN_EXITED) {
                    throw ProcessTreeTerminationProvenCancellationException(throwable)
                }
                throw throwable
            }
        }
    }

    private fun startProcess(command: RenderedLlmCommand): StartedProcess {
        Files.createDirectories(command.workingDirectory)

        val useProcessGroup = Files.isExecutable(Path.of(LINUX_SETSID_PATH))
        val processCommand = if (useProcessGroup) {
            listOf(LINUX_SETSID_PATH, command.executable) + command.args
        } else {
            listOf(command.executable) + command.args
        }
        val builder = ProcessBuilder(processCommand)
            .directory(command.workingDirectory.toFile())
        val environment = builder.environment()

        environment.clear()
        environment.putAll(command.environment)

        val process = builder.start()
        return StartedProcess(
            process = process,
            processGroupId = process.pid().takeIf { useProcessGroup },
        )
    }

    private suspend fun destroyProcessTree(startedProcess: StartedProcess): ProcessTreeTerminationProof {
        return withContext(Dispatchers.IO) {
            val process = startedProcess.process
            val processGroupId = startedProcess.processGroupId
            if (processGroupId != null) {
                terminateLinuxProcessGroup(process, processGroupId)
                return@withContext ProcessTreeTerminationProof.PROVEN_EXITED
            }

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
            ProcessTreeTerminationProof.UNCERTAIN
        }
    }

    private suspend fun destroyProcessTreeNonCancellable(
        process: StartedProcess,
    ): Result<ProcessTreeTerminationProof> {
        return withContext(NonCancellable) {
            runCatching {
                destroyProcessTree(process)
            }
        }
    }

    private fun terminateLinuxProcessGroup(process: Process, processGroupId: Long) {
        signalProcessGroup(processGroupId, "TERM")
        val gracefulDeadline = System.nanoTime() + terminationGrace.toNanos()
        while (isLinuxProcessGroupRunning(processGroupId) && System.nanoTime() < gracefulDeadline) {
            Thread.sleep(PROCESS_TREE_EXIT_POLL_MILLIS)
        }
        if (isLinuxProcessGroupRunning(processGroupId)) signalProcessGroup(processGroupId, "KILL")

        val processExited = process.waitFor(PROCESS_TREE_KILL_WAIT_SECONDS, TimeUnit.SECONDS)
        check(processExited && !isLinuxProcessGroupRunning(processGroupId)) {
            "LLM Linux process group did not exit after TERM/KILL sequence."
        }
    }

    private fun signalProcessGroup(processGroupId: Long, signal: String) {
        val signalProcess = ProcessBuilder(
            LINUX_KILL_PATH,
            "-$signal",
            "--",
            "-$processGroupId",
        ).start()
        signalProcess.outputStream.close()
        signalProcess.waitFor(PROCESS_GROUP_SIGNAL_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun isLinuxProcessGroupRunning(processGroupId: Long): Boolean {
        return runCatching {
            Files.list(linuxProcRoot).use { entries ->
                entries.filter { entry -> entry.fileName.toString().all(Char::isDigit) }
                    .anyMatch { entry -> entry.runningProcessGroupIdOrNull() == processGroupId }
            }
        }.getOrDefault(true)
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

private data class StartedProcess(
    val process: Process,
    val processGroupId: Long?,
)

private fun Path.runningProcessGroupIdOrNull(): Long? {
    val stat = runCatching { Files.readString(resolve("stat")) }.getOrNull() ?: return null
    val fields = stat.substringAfterLast(") ", missingDelimiterValue = "").split(' ')
    if (fields.size < 3 || fields[0] == "Z") return null

    return fields[2].toLongOrNull()
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
private const val PROCESS_GROUP_SIGNAL_WAIT_SECONDS = 2L
private const val LINUX_SETSID_PATH = "/usr/bin/setsid"
private const val LINUX_KILL_PATH = "/bin/kill"
private const val LINUX_PROC_ROOT = "/proc"
private const val PRODUCTION_LLM_LAUNCHER = "/usr/local/libexec/fukurou-llm-agent-launcher"
private const val CLEANUP_MODE = "cleanup"
private const val CODEX_HOME_PREFIX = "fukurou-codex-home-"
private const val CLAUDE_HOME_PREFIX = "fukurou-llm-config-"
private val PRODUCTION_LLM_HOME_ROOT = Path.of("/run/fukurou/llm-homes")
