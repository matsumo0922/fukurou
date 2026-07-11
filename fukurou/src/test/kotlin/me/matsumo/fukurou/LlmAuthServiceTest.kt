package me.matsumo.fukurou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.invoker.CODEX_HOME_ENV
import me.matsumo.fukurou.trading.invoker.HOME_ENV
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class LlmAuthServiceTest {

    @Test
    fun startLoginUsesClaudeAuthCommandAndSafeEnvironment() = runBlocking {
        val cliHome = Files.createTempDirectory("fukurou-llm-auth-home")
        val processStarter = RecordingLlmAuthProcessStarter()
        createService(
            cliHome = cliHome,
            processStarter = processStarter,
        ).use { service ->
            val result = service.startLogin(LlmAuthProvider.CLAUDE, "operator re-auth").getOrThrow()

            assertIs<LlmAuthLoginStartResult.Accepted>(result)
            assertEquals(listOf("claude", "auth", "login"), processStarter.commands.single())
            assertEquals(
                mapOf(
                    TEST_PATH_ENV to TEST_PATH_VALUE,
                    HOME_ENV to cliHome.toString(),
                ),
                processStarter.environments.single(),
            )
        }
    }

    @Test
    fun fromEnvironmentPreservesPathAndDropsSecretEnvironment() = runBlocking {
        val cliHome = Files.createTempDirectory("fukurou-llm-auth-home")
        val codexHome = cliHome.resolve(".codex")
        val processStarter = RecordingLlmAuthProcessStarter()
        DefaultLlmAuthService(
            config = LlmAuthServiceConfig.fromEnvironment(
                mapOf(
                    HOME_ENV to cliHome.toString(),
                    TEST_PATH_ENV to TEST_PATH_VALUE,
                    SECRET_ENV to "secret-value",
                ),
            ),
            processStarter = processStarter,
            idGenerator = SequentialUuidGenerator(),
        ).use { service ->
            val result = service.startLogin(LlmAuthProvider.CODEX, "operator re-auth").getOrThrow()

            assertIs<LlmAuthLoginStartResult.Accepted>(result)
            assertEquals(
                mapOf(
                    TEST_PATH_ENV to TEST_PATH_VALUE,
                    HOME_ENV to cliHome.toString(),
                    CODEX_HOME_ENV to codexHome.toString(),
                ),
                processStarter.environments.single(),
            )
            assertFalse(processStarter.environments.single().containsKey(SECRET_ENV))
        }
    }

    @Test
    fun jvmProcessStarterClearsParentEnvironment() {
        val workingDirectory = Files.createTempDirectory("fukurou-llm-auth-process")
        val environment = mapOf(
            TEST_PATH_ENV to TEST_PATH_VALUE,
            HOME_ENV to workingDirectory.toString(),
            "FUKUROU_SAFE_AUTH_ENV" to "safe",
        )

        val process = JvmLlmAuthProcessStarter.start(
            command = listOf("/usr/bin/env"),
            environment = environment,
            workingDirectory = workingDirectory,
        )
        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        val environmentLines = stdout
            .lineSequence()
            .filter { line -> line.isNotBlank() }
            .toSet()
        val expectedLines = environment
            .map { (key, value) -> "$key=$value" }
            .toSet()

        assertEquals(0, exitCode)
        assertEquals(expectedLines, environmentLines)
    }

    @Test
    fun jvmProcessStarterExposesPipeForCallerManagedEof() {
        val workingDirectory = Files.createTempDirectory("fukurou-llm-auth-process")
        val process = JvmLlmAuthProcessStarter.start(
            command = listOf(
                "/bin/sh",
                "-c",
                "if read line; then echo HAS_INPUT; else echo EOF; fi",
            ),
            environment = mapOf(
                TEST_PATH_ENV to TEST_PATH_VALUE,
                HOME_ENV to workingDirectory.toString(),
            ),
            workingDirectory = workingDirectory,
        )
        process.outputStream.close()
        val completed = process.waitFor(2, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
        }

        assertTrue(completed)
        assertEquals(0, process.exitValue())
        assertEquals("EOF", process.inputStream.bufferedReader().readText().trim())
    }

    @Test
    fun startLoginKeepsClaudeStdinOpenAndSubmitsTokenCodeOnce() = runBlocking {
        val processStarter = RecordingLlmAuthProcessStarter()
        createService(processStarter = processStarter).use { service ->
            val startResult = service.startLogin(LlmAuthProvider.CLAUDE, "operator re-auth").getOrThrow()
            val accepted = assertIs<LlmAuthLoginStartResult.Accepted>(startResult)
            val process = processStarter.processes.single()

            assertFalse(process.stdinClosed())
            assertTrue(accepted.session.tokenSubmitAvailable)
            assertFalse(accepted.session.tokenSubmitted)

            val submitResult = service
                .submitLoginTokenCode(
                    provider = LlmAuthProvider.CLAUDE,
                    sessionId = accepted.session.sessionId,
                    tokenCode = DUMMY_AUTH_CODE,
                )
                .getOrThrow()
            val submitted = assertIs<LlmAuthLoginTokenSubmitResult.Accepted>(submitResult)
            val duplicateSubmitResult = service
                .submitLoginTokenCode(
                    provider = LlmAuthProvider.CLAUDE,
                    sessionId = accepted.session.sessionId,
                    tokenCode = DUMMY_AUTH_CODE,
                )
                .getOrThrow()
            val duplicateRejected = assertIs<LlmAuthLoginTokenSubmitResult.Rejected>(duplicateSubmitResult)

            assertEquals("$DUMMY_AUTH_CODE\n", process.stdinText())
            assertTrue(process.stdinClosed())
            assertFalse(submitted.session.tokenSubmitAvailable)
            assertTrue(submitted.session.tokenSubmitted)
            assertEquals(LlmAuthLoginTokenSubmitRejection.ALREADY_SUBMITTED, duplicateRejected.rejection)
            assertEquals("$DUMMY_AUTH_CODE\n", process.stdinText())
        }
    }

    @Test
    fun startLoginClosesCodexStdinAndRejectsTokenCodeSubmit() = runBlocking {
        val processStarter = RecordingLlmAuthProcessStarter()
        createService(processStarter = processStarter).use { service ->
            val startResult = service.startLogin(LlmAuthProvider.CODEX, "operator re-auth").getOrThrow()
            val accepted = assertIs<LlmAuthLoginStartResult.Accepted>(startResult)
            val process = processStarter.processes.single()
            val submitResult = service
                .submitLoginTokenCode(
                    provider = LlmAuthProvider.CODEX,
                    sessionId = accepted.session.sessionId,
                    tokenCode = "$DUMMY_AUTH_CODE\nignored",
                )
                .getOrThrow()
            val rejected = assertIs<LlmAuthLoginTokenSubmitResult.Rejected>(submitResult)

            assertTrue(process.stdinClosed())
            assertEquals("", process.stdinText())
            assertFalse(accepted.session.tokenSubmitAvailable)
            assertFalse(accepted.session.tokenSubmitted)
            assertEquals(LlmAuthLoginTokenSubmitRejection.UNSUPPORTED_PROVIDER, rejected.rejection)
        }
    }

    @Test
    fun submitLoginTokenCodeAuditsWithoutSubmittedValue() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val processStarter = RecordingLlmAuthProcessStarter()
        createService(
            processStarter = processStarter,
            commandEventLog = eventLog,
        ).use { service ->
            val startResult = service.startLogin(LlmAuthProvider.CLAUDE, "operator re-auth").getOrThrow()
            val accepted = assertIs<LlmAuthLoginStartResult.Accepted>(startResult)

            service
                .submitLoginTokenCode(
                    provider = LlmAuthProvider.CLAUDE,
                    sessionId = accepted.session.sessionId,
                    tokenCode = DUMMY_AUTH_CODE,
                )
                .getOrThrow()

            val events = eventLog.events()
            val payloads = events.joinToString(separator = "\n") { event -> event.payload }

            assertTrue(events.any { event -> event.eventType == CommandEventType.CLI_AUTH_LOGIN_TOKEN_SUBMITTED })
            assertFalse(payloads.contains(DUMMY_AUTH_CODE))
        }
    }

    @Test
    fun startLoginRejectsAuthorizationUrlWithSecretFragment() = runBlocking {
        val processStarter = RecordingLlmAuthProcessStarter(
            stdout = "Open https://auth.example/device#access_token=secret-value\n",
        )
        createService(
            processStarter = processStarter,
            startupCaptureTimeout = Duration.ofMillis(100),
        ).use { service ->
            val result = service.startLogin(LlmAuthProvider.CODEX, "operator re-auth").getOrThrow()
            val accepted = assertIs<LlmAuthLoginStartResult.Accepted>(result)

            assertNull(accepted.session.authorizationUrl)
        }
    }

    @Test
    fun completedLoginSessionIsEvictedAfterRetention() = runBlocking {
        createService(
            processStarter = RecordingLlmAuthProcessStarter(completed = true),
            terminalSessionRetention = Duration.ZERO,
        ).use { service ->
            val result = service.startLogin(LlmAuthProvider.CLAUDE, "operator re-auth").getOrThrow()
            val accepted = assertIs<LlmAuthLoginStartResult.Accepted>(result)

            repeat(20) {
                service.loginSession(LlmAuthProvider.CLAUDE, accepted.session.sessionId).getOrThrow()
                    ?: return@runBlocking

                delay(10.toDuration(DurationUnit.MILLISECONDS))
            }

            assertNull(service.loginSession(LlmAuthProvider.CLAUDE, accepted.session.sessionId).getOrThrow())
        }
    }

    @Test
    fun startLoginRejectsConcurrentProviderRequestBeforeProcessReturns() = runBlocking {
        val processStarter = BlockingLlmAuthProcessStarter()
        createService(processStarter = processStarter).use { service ->
            val firstResult = async(Dispatchers.IO) {
                service.startLogin(LlmAuthProvider.CLAUDE, "first reason").getOrThrow()
            }
            assertTrue(processStarter.awaitFirstStart())

            val secondResult = async(Dispatchers.IO) {
                service.startLogin(LlmAuthProvider.CLAUDE, "second reason").getOrThrow()
            }.await()
            processStarter.release()

            assertIs<LlmAuthLoginStartResult.Rejected>(secondResult)
            assertEquals(LOGIN_ALREADY_IN_PROGRESS_REASON, secondResult.reason)
            assertIs<LlmAuthLoginStartResult.Accepted>(firstResult.await())
            assertEquals(1, processStarter.startCount())
        }
    }

    @Test
    fun closeWaitsForProcessCompletionAudit() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val processStarter = RecordingLlmAuthProcessStarter()
        createService(
            processStarter = processStarter,
            commandEventLog = eventLog,
        ).use { service ->
            service.startLogin(LlmAuthProvider.CLAUDE, "operator re-auth").getOrThrow()
            service.close()

            val eventTypes = eventLog.events().map { event -> event.eventType }

            assertFalse(processStarter.processes.single().isAlive())
            assertTrue(CommandEventType.CLI_AUTH_LOGIN_FAILED in eventTypes)
        }
    }

    @Test
    fun closeAuditsWhenProcessJobsDoNotFinishBeforeTimeout() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val processStarter = RecordingLlmAuthProcessStarter(blockingStdout = true)
        createService(
            processStarter = processStarter,
            commandEventLog = eventLog,
            closeAwaitTimeout = Duration.ofMillis(50),
        ).use { service ->
            service.startLogin(LlmAuthProvider.CLAUDE, "operator re-auth").getOrThrow()
            service.close()
            processStarter.processes.single().releaseStdout()

            val timeoutEvent = eventLog.events().single { event ->
                event.clientRequestId == CLOSE_AWAIT_AUDIT_REQUEST_ID &&
                    event.eventType == CommandEventType.CLI_AUTH_CLOSE_WAIT_TIMED_OUT &&
                    event.payload.contains("\"stage\":\"$CLOSE_AWAIT_STAGE_PROCESS_JOBS\"")
            }

            assertTrue(timeoutEvent.payload.contains("\"pendingJobCount\":"))
        }
    }

    @Test
    fun startLoginRejectsAfterClose() = runBlocking {
        val processStarter = RecordingLlmAuthProcessStarter()
        createService(processStarter = processStarter).use { service ->
            service.close()

            val result = service.startLogin(LlmAuthProvider.CLAUDE, "operator re-auth").getOrThrow()
            val rejected = assertIs<LlmAuthLoginStartResult.Rejected>(result)

            assertEquals(SERVICE_CLOSING_REASON, rejected.reason)
            assertTrue(processStarter.processes.isEmpty())
        }
    }

    private fun createService(
        cliHome: Path = Files.createTempDirectory("fukurou-llm-auth-home"),
        processStarter: LlmAuthProcessStarter,
        commandEventLog: InMemoryCommandEventLog? = null,
        startupCaptureTimeout: Duration = Duration.ZERO,
        closeAwaitTimeout: Duration = Duration.ofSeconds(5),
        terminalSessionRetention: Duration = Duration.ofMinutes(30),
    ): DefaultLlmAuthService {
        return DefaultLlmAuthService(
            config = LlmAuthServiceConfig(
                claudeCommandTemplate = listOf("claude"),
                codexCommandTemplate = listOf("codex"),
                cliHome = cliHome,
                codexHome = cliHome.resolve(".codex"),
                inheritedLoginEnvironment = mapOf(TEST_PATH_ENV to TEST_PATH_VALUE),
                startupCaptureTimeout = startupCaptureTimeout,
                terminalSessionRetention = terminalSessionRetention,
            ),
            commandEventLog = commandEventLog,
            closeAwaitTimeout = closeAwaitTimeout,
            processStarter = processStarter,
            idGenerator = SequentialUuidGenerator(),
        )
    }
}

private class RecordingLlmAuthProcessStarter(
    private val stdout: String = "",
    private val completed: Boolean = false,
    private val blockingStdout: Boolean = false,
) : LlmAuthProcessStarter {

    val commands = mutableListOf<List<String>>()
    val environments = mutableListOf<Map<String, String>>()
    val processes = mutableListOf<RunningLlmAuthProcess>()

    override fun start(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: Path,
    ): Process {
        commands += command
        environments += environment

        val process = RunningLlmAuthProcess(
            stdout = stdout,
            completed = completed,
            blockingStdout = blockingStdout,
        )
        processes += process

        return process
    }
}

private class BlockingLlmAuthProcessStarter : LlmAuthProcessStarter {

    private val startCount = AtomicInteger()
    private val firstStart = CountDownLatch(1)
    private val release = CountDownLatch(1)

    override fun start(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: Path,
    ): Process {
        startCount.incrementAndGet()
        firstStart.countDown()
        release.await(2, TimeUnit.SECONDS)

        return RunningLlmAuthProcess()
    }

    fun awaitFirstStart(): Boolean {
        return firstStart.await(2, TimeUnit.SECONDS)
    }

    fun release() {
        release.countDown()
    }

    fun startCount(): Int {
        return startCount.get()
    }
}

private class RunningLlmAuthProcess(
    private val stdout: String = "",
    completed: Boolean = false,
    blockingStdout: Boolean = false,
) : Process() {

    private val destroyed = CountDownLatch(1)
    private val stdin = RecordingProcessInputStream()
    private val stdoutStream = if (blockingStdout) {
        BlockingProcessInputStream()
    } else {
        ByteArrayInputStream(stdout.toByteArray())
    }

    @Volatile
    private var alive = !completed

    override fun getOutputStream(): OutputStream {
        return stdin
    }

    override fun getInputStream(): InputStream {
        return stdoutStream
    }

    override fun getErrorStream(): ByteArrayInputStream {
        return ByteArrayInputStream(ByteArray(0))
    }

    override fun waitFor(): Int {
        if (!alive) {
            return EXIT_CODE_SUCCESS
        }

        destroyed.await()

        return EXIT_CODE_DESTROYED
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        if (!alive) {
            return true
        }

        return destroyed.await(timeout, unit)
    }

    override fun exitValue(): Int {
        if (alive) {
            throw IllegalThreadStateException("process is still running")
        }

        return if (destroyed.count == 0L) EXIT_CODE_DESTROYED else EXIT_CODE_SUCCESS
    }

    override fun destroy() {
        alive = false
        destroyed.countDown()
    }

    override fun destroyForcibly(): Process {
        destroy()

        return this
    }

    override fun isAlive(): Boolean {
        return alive
    }

    fun stdinClosed(): Boolean {
        return stdin.closed
    }

    fun stdinText(): String {
        return stdin.text()
    }

    fun releaseStdout() {
        (stdoutStream as? BlockingProcessInputStream)?.release()
    }
}

private class BlockingProcessInputStream : InputStream() {

    private val released = CountDownLatch(1)

    override fun read(): Int {
        released.await()

        return -1
    }

    fun release() {
        released.countDown()
    }
}

private class RecordingProcessInputStream : OutputStream() {

    private val bytes = mutableListOf<Byte>()

    @Volatile
    var closed = false
        private set

    override fun write(value: Int) {
        check(!closed) {
            "stdin is closed"
        }

        bytes += value.toByte()
    }

    override fun close() {
        closed = true
    }

    fun text(): String {
        return bytes.toByteArray().toString(StandardCharsets.UTF_8)
    }
}

private class SequentialUuidGenerator : () -> UUID {

    private var index = 0

    override fun invoke(): UUID {
        index += 1

        return UUID(0L, index.toLong())
    }
}

private const val EXIT_CODE_SUCCESS = 0
private const val EXIT_CODE_DESTROYED = 143
private const val TEST_PATH_ENV = "PATH"
private const val TEST_PATH_VALUE = "/opt/fukurou/bin:/usr/bin"
private const val SECRET_ENV = "DB_PASSWORD"
private const val DUMMY_AUTH_CODE = "DUMMY-CODE"
private const val CLOSE_AWAIT_STAGE_PROCESS_JOBS = "process_jobs"
private const val CLOSE_AWAIT_AUDIT_REQUEST_ID = "llm-auth-close"
private const val LOGIN_ALREADY_IN_PROGRESS_REASON = "login already in progress"
private const val SERVICE_CLOSING_REASON = "service is closing"
