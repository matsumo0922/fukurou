package me.matsumo.fukurou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.invoker.CODEX_HOME_ENV
import me.matsumo.fukurou.trading.invoker.FUKUROU_CODEX_PERSISTENT_HOME_ENV
import me.matsumo.fukurou.trading.invoker.HOME_ENV
import java.io.ByteArrayInputStream
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

class LlmAuthServiceTest {

    @Test
    fun startLoginUsesClaudeAuthCommandAndSafeEnvironment() = runBlocking {
        val cliHome = Files.createTempDirectory("fukurou-llm-auth-home")
        val processStarter = RecordingLlmAuthProcessStarter()
        val service = createService(
            cliHome = cliHome,
            processStarter = processStarter,
        )

        try {
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
        } finally {
            service.close()
        }
    }

    @Test
    fun fromEnvironmentPreservesPathAndDropsSecretEnvironment() = runBlocking {
        val cliHome = Files.createTempDirectory("fukurou-llm-auth-home")
        val codexHome = cliHome.resolve(".codex")
        val processStarter = RecordingLlmAuthProcessStarter()
        val service = DefaultLlmAuthService(
            config = LlmAuthServiceConfig.fromEnvironment(
                mapOf(
                    HOME_ENV to cliHome.toString(),
                    TEST_PATH_ENV to TEST_PATH_VALUE,
                    SECRET_ENV to "secret-value",
                    FUKUROU_CODEX_PERSISTENT_HOME_ENV to codexHome.toString(),
                ),
            ),
            processStarter = processStarter,
            idGenerator = SequentialUuidGenerator(),
        )

        try {
            val result = service.startLogin(LlmAuthProvider.CODEX, "operator re-auth").getOrThrow()

            assertIs<LlmAuthLoginStartResult.Accepted>(result)
            assertEquals(
                mapOf(
                    TEST_PATH_ENV to TEST_PATH_VALUE,
                    HOME_ENV to cliHome.toString(),
                    CODEX_HOME_ENV to codexHome.toString(),
                    FUKUROU_CODEX_PERSISTENT_HOME_ENV to codexHome.toString(),
                ),
                processStarter.environments.single(),
            )
            assertFalse(processStarter.environments.single().containsKey(SECRET_ENV))
        } finally {
            service.close()
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
        val service = createService(processStarter = processStarter)

        try {
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
        } finally {
            service.close()
        }
    }

    @Test
    fun startLoginClosesCodexStdinAndRejectsTokenCodeSubmit() = runBlocking {
        val processStarter = RecordingLlmAuthProcessStarter()
        val service = createService(processStarter = processStarter)

        try {
            val startResult = service.startLogin(LlmAuthProvider.CODEX, "operator re-auth").getOrThrow()
            val accepted = assertIs<LlmAuthLoginStartResult.Accepted>(startResult)
            val process = processStarter.processes.single()
            val submitResult = service
                .submitLoginTokenCode(
                    provider = LlmAuthProvider.CODEX,
                    sessionId = accepted.session.sessionId,
                    tokenCode = DUMMY_AUTH_CODE,
                )
                .getOrThrow()
            val rejected = assertIs<LlmAuthLoginTokenSubmitResult.Rejected>(submitResult)

            assertTrue(process.stdinClosed())
            assertEquals("", process.stdinText())
            assertFalse(accepted.session.tokenSubmitAvailable)
            assertFalse(accepted.session.tokenSubmitted)
            assertEquals(LlmAuthLoginTokenSubmitRejection.UNSUPPORTED_PROVIDER, rejected.rejection)
        } finally {
            service.close()
        }
    }

    @Test
    fun submitLoginTokenCodeAuditsWithoutSubmittedValue() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val processStarter = RecordingLlmAuthProcessStarter()
        val service = createService(
            processStarter = processStarter,
            commandEventLog = eventLog,
        )

        try {
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
        } finally {
            service.close()
        }
    }

    @Test
    fun startLoginRejectsAuthorizationUrlWithSecretFragment() = runBlocking {
        val processStarter = RecordingLlmAuthProcessStarter(
            stdout = "Open https://auth.example/device#access_token=secret-value\n",
        )
        val service = createService(
            processStarter = processStarter,
            startupCaptureTimeout = Duration.ofMillis(100),
        )

        try {
            val result = service.startLogin(LlmAuthProvider.CODEX, "operator re-auth").getOrThrow()
            val accepted = assertIs<LlmAuthLoginStartResult.Accepted>(result)

            assertNull(accepted.session.authorizationUrl)
        } finally {
            service.close()
        }
    }

    @Test
    fun completedLoginSessionIsEvictedAfterRetention() = runBlocking {
        val service = createService(
            processStarter = RecordingLlmAuthProcessStarter(completed = true),
            terminalSessionRetention = Duration.ZERO,
        )

        try {
            val result = service.startLogin(LlmAuthProvider.CLAUDE, "operator re-auth").getOrThrow()
            val accepted = assertIs<LlmAuthLoginStartResult.Accepted>(result)

            repeat(20) {
                val session = service.loginSession(LlmAuthProvider.CLAUDE, accepted.session.sessionId).getOrThrow()

                if (session == null) {
                    return@runBlocking
                }

                delay(10)
            }

            assertNull(service.loginSession(LlmAuthProvider.CLAUDE, accepted.session.sessionId).getOrThrow())
        } finally {
            service.close()
        }
    }

    @Test
    fun startLoginRejectsConcurrentProviderRequestBeforeProcessReturns() = runBlocking {
        val processStarter = BlockingLlmAuthProcessStarter()
        val service = createService(processStarter = processStarter)

        try {
            val firstResult = async(Dispatchers.IO) {
                service.startLogin(LlmAuthProvider.CLAUDE, "first reason").getOrThrow()
            }
            assertTrue(processStarter.awaitFirstStart())

            val secondResult = async(Dispatchers.IO) {
                service.startLogin(LlmAuthProvider.CLAUDE, "second reason").getOrThrow()
            }.await()
            processStarter.release()

            assertIs<LlmAuthLoginStartResult.Rejected>(secondResult)
            assertEquals("login already in progress", secondResult.reason)
            assertIs<LlmAuthLoginStartResult.Accepted>(firstResult.await())
            assertEquals(1, processStarter.startCount())
        } finally {
            service.close()
        }
    }

    private fun createService(
        cliHome: Path = Files.createTempDirectory("fukurou-llm-auth-home"),
        processStarter: LlmAuthProcessStarter,
        commandEventLog: InMemoryCommandEventLog? = null,
        startupCaptureTimeout: Duration = Duration.ZERO,
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
            processStarter = processStarter,
            idGenerator = SequentialUuidGenerator(),
        )
    }
}

private class RecordingLlmAuthProcessStarter(
    private val stdout: String = "",
    private val completed: Boolean = false,
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
) : Process() {

    private val destroyed = CountDownLatch(1)
    private val stdin = RecordingProcessInputStream()

    @Volatile
    private var alive = !completed

    override fun getOutputStream(): OutputStream {
        return stdin
    }

    override fun getInputStream(): ByteArrayInputStream {
        return ByteArrayInputStream(stdout.toByteArray())
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
