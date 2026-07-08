package me.matsumo.fukurou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.invoker.CODEX_HOME_ENV
import me.matsumo.fukurou.trading.invoker.FUKUROU_CODEX_PERSISTENT_HOME_ENV
import me.matsumo.fukurou.trading.invoker.HOME_ENV
import java.io.ByteArrayInputStream
import java.io.OutputStream
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
    ): DefaultLlmAuthService {
        return DefaultLlmAuthService(
            config = LlmAuthServiceConfig(
                claudeCommandTemplate = listOf("claude"),
                codexCommandTemplate = listOf("codex"),
                cliHome = cliHome,
                codexHome = cliHome.resolve(".codex"),
                inheritedLoginEnvironment = mapOf(TEST_PATH_ENV to TEST_PATH_VALUE),
                startupCaptureTimeout = Duration.ZERO,
            ),
            processStarter = processStarter,
            idGenerator = SequentialUuidGenerator(),
        )
    }
}

private class RecordingLlmAuthProcessStarter : LlmAuthProcessStarter {

    val commands = mutableListOf<List<String>>()
    val environments = mutableListOf<Map<String, String>>()

    override fun start(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: Path,
    ): Process {
        commands += command
        environments += environment

        return RunningLlmAuthProcess()
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

private class RunningLlmAuthProcess : Process() {

    private val destroyed = CountDownLatch(1)

    @Volatile
    private var alive = true

    override fun getOutputStream(): OutputStream {
        return OutputStream.nullOutputStream()
    }

    override fun getInputStream(): ByteArrayInputStream {
        return ByteArrayInputStream(ByteArray(0))
    }

    override fun getErrorStream(): ByteArrayInputStream {
        return ByteArrayInputStream(ByteArray(0))
    }

    override fun waitFor(): Int {
        destroyed.await()

        return EXIT_CODE_DESTROYED
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        return destroyed.await(timeout, unit)
    }

    override fun exitValue(): Int {
        if (alive) {
            throw IllegalThreadStateException("process is still running")
        }

        return EXIT_CODE_DESTROYED
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
}

private class SequentialUuidGenerator : () -> UUID {

    private var index = 0

    override fun invoke(): UUID {
        index += 1

        return UUID(0L, index.toLong())
    }
}

private const val EXIT_CODE_DESTROYED = 143
private const val TEST_PATH_ENV = "PATH"
private const val TEST_PATH_VALUE = "/opt/fukurou/bin:/usr/bin"
private const val SECRET_ENV = "DB_PASSWORD"
