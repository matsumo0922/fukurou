package me.matsumo.fukurou

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.invoker.CODEX_HOME_ENV
import me.matsumo.fukurou.trading.invoker.DEFAULT_CODEX_HOME_DIRECTORY
import me.matsumo.fukurou.trading.invoker.FUKUROU_CODEX_PERSISTENT_HOME_ENV
import me.matsumo.fukurou.trading.invoker.HOME_ENV
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * CLI auth provider。
 */
enum class LlmAuthProvider(
    val wireName: String,
    val displayName: String,
) {
    /**
     * Claude Code CLI。
     */
    CLAUDE("claude", "Claude Code"),

    /**
     * Codex CLI。
     */
    CODEX("codex", "Codex"),
}

/**
 * CLI auth の状態。
 */
enum class LlmAuthStatus(
    val wireName: String,
) {
    /**
     * 非 secret の login marker が存在する。
     */
    LOGGED_IN("logged_in"),

    /**
     * login marker が見つからない。
     */
    LOGGED_OUT("logged_out"),

    /**
     * 状態を判定できない。
     */
    UNKNOWN("unknown"),

    /**
     * filesystem error などで状態取得に失敗した。
     */
    ERROR("error"),
}

/**
 * CLI auth login process の状態。
 */
enum class LlmAuthLoginStatus(
    val wireName: String,
) {
    /**
     * CLI process が承認待ちで実行中。
     */
    RUNNING("running"),

    /**
     * CLI process が成功 exit した。
     */
    SUCCEEDED("succeeded"),

    /**
     * CLI process が失敗 exit した。
     */
    FAILED("failed"),

    /**
     * CLI process が timeout で停止された。
     */
    TIMED_OUT("timed_out"),
}

/**
 * CLI auth snapshot。
 *
 * @param providers provider 別状態
 * @param checkedAt 状態を読んだ時刻
 */
data class LlmAuthSnapshot(
    val providers: List<LlmAuthProviderStatus>,
    val checkedAt: Instant,
)

/**
 * CLI auth provider 別状態。
 *
 * @param provider provider
 * @param status login 状態
 * @param detail secret を含まない補足
 * @param homePath login state の home path
 * @param checkedAt 状態を読んだ時刻
 */
data class LlmAuthProviderStatus(
    val provider: LlmAuthProvider,
    val status: LlmAuthStatus,
    val detail: String?,
    val homePath: String,
    val checkedAt: Instant,
)

/**
 * CLI auth login start 結果。
 */
sealed interface LlmAuthLoginStartResult {

    /**
     * login process を開始した。
     *
     * @param session login session snapshot
     */
    data class Accepted(
        val session: LlmAuthLoginSessionSnapshot,
    ) : LlmAuthLoginStartResult

    /**
     * login process を開始できなかった。
     *
     * @param reason secret を含まない拒否理由
     */
    data class Rejected(
        val reason: String,
    ) : LlmAuthLoginStartResult
}

/**
 * CLI auth login session snapshot。
 *
 * @param provider provider
 * @param sessionId session ID
 * @param status login process 状態
 * @param authorizationUrl browser 承認用 URL
 * @param userCode device auth code
 * @param detail secret を含まない補足
 * @param startedAt 開始時刻
 * @param expiresAt timeout 時刻
 * @param completedAt 完了時刻
 */
data class LlmAuthLoginSessionSnapshot(
    val provider: LlmAuthProvider,
    val sessionId: String,
    val status: LlmAuthLoginStatus,
    val authorizationUrl: String?,
    val userCode: String?,
    val detail: String?,
    val startedAt: Instant,
    val expiresAt: Instant,
    val completedAt: Instant?,
)

/**
 * CLI auth login audit に保存する非 secret record。
 *
 * @param provider provider
 * @param sessionId session ID
 * @param eventType audit event type
 * @param reason operator reason
 * @param status login process 状態
 * @param detail secret を含まない補足
 */
private data class LlmAuthAuditRecord(
    val provider: LlmAuthProvider,
    val sessionId: String,
    val eventType: CommandEventType,
    val reason: String,
    val status: LlmAuthLoginStatus,
    val detail: String?,
)

/**
 * CLI auth を読む / login flow を開始する境界。
 */
interface LlmAuthService {
    /**
     * provider 別 CLI auth 状態を返す。
     */
    suspend fun snapshot(): Result<LlmAuthSnapshot>

    /**
     * provider の login flow を reason 付きで開始する。
     */
    suspend fun startLogin(provider: LlmAuthProvider, reason: String): Result<LlmAuthLoginStartResult>

    /**
     * login session の現在状態を返す。
     */
    suspend fun loginSession(provider: LlmAuthProvider, sessionId: String): Result<LlmAuthLoginSessionSnapshot?>
}

/**
 * CLI auth service 設定。
 *
 * @param claudeCommandTemplate Claude CLI command template
 * @param codexCommandTemplate Codex CLI command template
 * @param cliHome Claude/Codex login state を置く共通 home
 * @param codexHome Codex CLI home
 * @param loginTimeout login process timeout
 * @param startupCaptureTimeout login start 応答前に URL / code を待つ時間
 */
data class LlmAuthServiceConfig(
    val claudeCommandTemplate: List<String>,
    val codexCommandTemplate: List<String>,
    val cliHome: Path,
    val codexHome: Path,
    val loginTimeout: Duration = DEFAULT_LLM_AUTH_LOGIN_TIMEOUT,
    val startupCaptureTimeout: Duration = DEFAULT_LLM_AUTH_STARTUP_CAPTURE_TIMEOUT,
) {
    companion object {
        /**
         * 環境変数から CLI auth service 設定を構築する。
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): LlmAuthServiceConfig {
            val rendererConfig = LlmCommandRendererConfig.fromEnvironment(environment)
            val cliHome = environment[HOME_ENV]
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value -> Path.of(value) }
                ?: Path.of(DEFAULT_LLM_CLI_HOME_PATH)
            val codexHome = rendererConfig.codexPersistentHome
                ?: environment[FUKUROU_CODEX_PERSISTENT_HOME_ENV]
                    ?.takeIf { value -> value.isNotBlank() }
                    ?.let { value -> Path.of(value) }
                ?: cliHome.resolve(DEFAULT_CODEX_HOME_DIRECTORY)

            return LlmAuthServiceConfig(
                claudeCommandTemplate = rendererConfig.claudeCommandTemplate,
                codexCommandTemplate = rendererConfig.codexCommandTemplate,
                cliHome = cliHome,
                codexHome = codexHome,
            )
        }
    }
}

/**
 * filesystem と CLI process を使う既定 CLI auth service。
 *
 * @param config service 設定
 * @param commandEventLog login start / completion 監査 log。null の場合は監査だけ省略する
 * @param clock session と監査時刻に使う clock
 * @param scope process reader 用 scope
 * @param processStarter process 起動境界
 * @param idGenerator session ID generator
 */
class DefaultLlmAuthService(
    private val config: LlmAuthServiceConfig = LlmAuthServiceConfig.fromEnvironment(),
    private val commandEventLog: CommandEventLog? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val processStarter: LlmAuthProcessStarter = JvmLlmAuthProcessStarter,
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
) : LlmAuthService, Closeable {

    private val sessions = ConcurrentHashMap<String, MutableLlmAuthLoginSession>()
    private val activeSessions = ConcurrentHashMap<LlmAuthProvider, String>()

    override suspend fun snapshot(): Result<LlmAuthSnapshot> {
        return runCatching {
            val checkedAt = Instant.now(clock)

            LlmAuthSnapshot(
                providers = LlmAuthProvider.entries.map { provider ->
                    providerStatus(
                        provider = provider,
                        checkedAt = checkedAt,
                    )
                },
                checkedAt = checkedAt,
            )
        }
    }

    override suspend fun startLogin(provider: LlmAuthProvider, reason: String): Result<LlmAuthLoginStartResult> {
        return runCatching {
            val trimmedReason = reason.trim()

            require(trimmedReason.isNotEmpty()) {
                "reason must not be blank."
            }

            startLoginUnsafe(provider, trimmedReason)
        }
    }

    override suspend fun loginSession(
        provider: LlmAuthProvider,
        sessionId: String,
    ): Result<LlmAuthLoginSessionSnapshot?> {
        return runCatching {
            val session = sessions[sessionId] ?: return@runCatching null

            if (session.provider != provider) {
                return@runCatching null
            }

            session.snapshot()
        }
    }

    override fun close() {
        sessions.values.forEach { session -> session.destroyProcess() }

        val scopeJob = scope.coroutineContext[Job] ?: return

        scopeJob.cancel()

        runBlocking {
            scopeJob.join()
        }
    }

    private fun providerStatus(provider: LlmAuthProvider, checkedAt: Instant): LlmAuthProviderStatus {
        return runCatching {
            val homePath = provider.authHomePath()

            if (!Files.exists(homePath)) {
                return@runCatching LlmAuthProviderStatus(
                    provider = provider,
                    status = LlmAuthStatus.LOGGED_OUT,
                    detail = "auth home is not present",
                    homePath = homePath.toString(),
                    checkedAt = checkedAt,
                )
            }

            if (!Files.isDirectory(homePath)) {
                return@runCatching LlmAuthProviderStatus(
                    provider = provider,
                    status = LlmAuthStatus.ERROR,
                    detail = "auth home is not a directory",
                    homePath = homePath.toString(),
                    checkedAt = checkedAt,
                )
            }

            val marker = provider.authMarkerCandidates()
                .firstOrNull { markerPath -> markerPath.isPresentNonEmptyRegularFile() }
            val status = if (marker != null) LlmAuthStatus.LOGGED_IN else LlmAuthStatus.LOGGED_OUT
            val detail = if (marker != null) {
                "credential marker present"
            } else {
                "credential marker not found"
            }

            LlmAuthProviderStatus(
                provider = provider,
                status = status,
                detail = detail,
                homePath = homePath.toString(),
                checkedAt = checkedAt,
            )
        }.getOrElse { throwable ->
            LlmAuthProviderStatus(
                provider = provider,
                status = LlmAuthStatus.ERROR,
                detail = throwable.javaClass.simpleName,
                homePath = provider.authHomePath().toString(),
                checkedAt = checkedAt,
            )
        }
    }

    private suspend fun startLoginUnsafe(provider: LlmAuthProvider, reason: String): LlmAuthLoginStartResult {
        val activeSessionId = activeSessions[provider]
        val activeSession = activeSessionId?.let { sessionId -> sessions[sessionId] }

        if (activeSession?.isRunning() == true) {
            return LlmAuthLoginStartResult.Rejected("login already in progress")
        }

        val sessionId = idGenerator().toString()
        val startedAt = Instant.now(clock)
        val process = processStarter.start(
            command = provider.loginCommand(),
            environment = provider.loginEnvironment(),
            workingDirectory = config.cliHome,
        )
        val session = MutableLlmAuthLoginSession(
            provider = provider,
            sessionId = sessionId,
            process = process,
            startedAt = startedAt,
            expiresAt = startedAt.plus(config.loginTimeout),
            clock = clock,
        )

        sessions[sessionId] = session
        activeSessions[provider] = sessionId
        appendLoginAudit(
            LlmAuthAuditRecord(
                provider = provider,
                sessionId = sessionId,
                eventType = CommandEventType.CLI_AUTH_LOGIN_STARTED,
                reason = reason,
                status = LlmAuthLoginStatus.RUNNING,
                detail = "login process started",
            ),
        )
        launchReaders(session, reason)
        waitForStartupCapture(session)

        return LlmAuthLoginStartResult.Accepted(session.snapshot())
    }

    private fun launchReaders(session: MutableLlmAuthLoginSession, reason: String) {
        scope.launch {
            collectProcessOutput(session.process.inputStream, session)
        }
        scope.launch {
            collectProcessOutput(session.process.errorStream, session)
        }
        scope.launch {
            waitForProcess(session, reason)
        }
    }

    private suspend fun collectProcessOutput(inputStream: InputStream, session: MutableLlmAuthLoginSession) {
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                session.observeCliLine(line)
            }
        }
    }

    private suspend fun waitForProcess(session: MutableLlmAuthLoginSession, reason: String) {
        val completed = session.process.waitFor(config.loginTimeout.toMillis(), TimeUnit.MILLISECONDS)
        val status = if (completed) {
            if (session.process.exitValue() == 0) LlmAuthLoginStatus.SUCCEEDED else LlmAuthLoginStatus.FAILED
        } else {
            session.destroyProcess()
            LlmAuthLoginStatus.TIMED_OUT
        }
        val eventType = when (status) {
            LlmAuthLoginStatus.SUCCEEDED -> CommandEventType.CLI_AUTH_LOGIN_COMPLETED
            LlmAuthLoginStatus.FAILED -> CommandEventType.CLI_AUTH_LOGIN_FAILED
            LlmAuthLoginStatus.TIMED_OUT -> CommandEventType.CLI_AUTH_LOGIN_TIMED_OUT
            LlmAuthLoginStatus.RUNNING -> CommandEventType.CLI_AUTH_LOGIN_STARTED
        }

        session.complete(status)
        activeSessions.remove(session.provider, session.sessionId)
        appendLoginAudit(
            LlmAuthAuditRecord(
                provider = session.provider,
                sessionId = session.sessionId,
                eventType = eventType,
                reason = reason,
                status = status,
                detail = session.snapshot().detail,
            ),
        )
    }

    private suspend fun waitForStartupCapture(session: MutableLlmAuthLoginSession) {
        val deadline = Instant.now(clock).plus(config.startupCaptureTimeout)

        while (Instant.now(clock).isBefore(deadline)) {
            val snapshot = session.snapshot()
            val hasChallenge = snapshot.authorizationUrl != null || snapshot.userCode != null

            if (hasChallenge || snapshot.status != LlmAuthLoginStatus.RUNNING) {
                return
            }

            delay(STARTUP_CAPTURE_POLL_MILLIS)
        }
    }

    private suspend fun appendLoginAudit(record: LlmAuthAuditRecord) {
        val eventLog = commandEventLog ?: return
        val payload = buildJsonObject {
            put("provider", record.provider.wireName)
            put("sessionId", record.sessionId)
            put("reason", record.reason)
            put("status", record.status.wireName)
            put("detail", record.detail ?: "")
        }.toString()

        eventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
                toolName = "cli-auth",
                toolCallId = null,
                clientRequestId = record.sessionId,
                eventType = record.eventType,
                payload = payload,
                occurredAt = Instant.now(clock),
            ),
        ).getOrThrow()
    }

    private fun LlmAuthProvider.authHomePath(): Path {
        return when (this) {
            LlmAuthProvider.CLAUDE -> config.cliHome.resolve(CLAUDE_HOME_DIRECTORY)
            LlmAuthProvider.CODEX -> config.codexHome
        }
    }

    private fun LlmAuthProvider.authMarkerCandidates(): List<Path> {
        return when (this) {
            LlmAuthProvider.CLAUDE -> CLAUDE_AUTH_MARKERS.map { marker -> config.cliHome.resolve(marker) }
            LlmAuthProvider.CODEX -> listOf(config.codexHome.resolve(CODEX_AUTH_FILE_NAME))
        }
    }

    private fun LlmAuthProvider.loginCommand(): List<String> {
        return when (this) {
            LlmAuthProvider.CLAUDE -> config.claudeCommandTemplate + listOf("login")
            LlmAuthProvider.CODEX -> config.codexCommandTemplate + listOf("login", "--device-auth")
        }
    }

    private fun LlmAuthProvider.loginEnvironment(): Map<String, String> {
        return when (this) {
            LlmAuthProvider.CLAUDE -> mapOf(
                HOME_ENV to config.cliHome.toString(),
            )
            LlmAuthProvider.CODEX -> mapOf(
                HOME_ENV to config.cliHome.toString(),
                CODEX_HOME_ENV to config.codexHome.toString(),
                FUKUROU_CODEX_PERSISTENT_HOME_ENV to config.codexHome.toString(),
            )
        }
    }
}

/**
 * CLI auth process 起動境界。
 */
fun interface LlmAuthProcessStarter {
    /**
     * process を開始する。
     */
    fun start(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: Path,
    ): Process
}

private object JvmLlmAuthProcessStarter : LlmAuthProcessStarter {
    override fun start(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: Path,
    ): Process {
        Files.createDirectories(workingDirectory)

        val processBuilder = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
        processBuilder.environment().putAll(environment)

        return processBuilder.start()
    }
}

private class MutableLlmAuthLoginSession(
    val provider: LlmAuthProvider,
    val sessionId: String,
    val process: Process,
    private val startedAt: Instant,
    private val expiresAt: Instant,
    private val clock: Clock,
) {
    @Volatile
    private var status = LlmAuthLoginStatus.RUNNING

    @Volatile
    private var authorizationUrl: String? = null

    @Volatile
    private var userCode: String? = null

    @Volatile
    private var detail: String? = "waiting for CLI authorization challenge"

    @Volatile
    private var completedAt: Instant? = null

    fun isRunning(): Boolean {
        return status == LlmAuthLoginStatus.RUNNING
    }

    fun observeCliLine(line: String) {
        val extractedUrl = extractAuthorizationUrl(line)
        val extractedCode = extractUserCode(line)

        if (extractedUrl != null) {
            authorizationUrl = extractedUrl
        }

        if (extractedCode != null) {
            userCode = extractedCode
        }

        val hasChallenge = authorizationUrl != null || userCode != null
        detail = if (hasChallenge) {
            "authorization challenge emitted"
        } else {
            "waiting for CLI authorization challenge"
        }
    }

    fun complete(status: LlmAuthLoginStatus) {
        this.status = status
        completedAt = Instant.now(clock)
        detail = when (status) {
            LlmAuthLoginStatus.SUCCEEDED -> "login process completed"
            LlmAuthLoginStatus.FAILED -> "login process exited with failure"
            LlmAuthLoginStatus.TIMED_OUT -> "login process timed out"
            LlmAuthLoginStatus.RUNNING -> detail
        }
    }

    fun destroyProcess() {
        if (!process.isAlive) {
            return
        }

        process.destroy()

        runCatching {
            if (!process.waitFor(DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
        }
    }

    fun snapshot(): LlmAuthLoginSessionSnapshot {
        return LlmAuthLoginSessionSnapshot(
            provider = provider,
            sessionId = sessionId,
            status = status,
            authorizationUrl = authorizationUrl,
            userCode = userCode,
            detail = detail,
            startedAt = startedAt,
            expiresAt = expiresAt,
            completedAt = completedAt,
        )
    }
}

private fun Path.isPresentNonEmptyRegularFile(): Boolean {
    return try {
        Files.isRegularFile(this) && Files.size(this) > 0
    } catch (_: SecurityException) {
        false
    }
}

private fun extractAuthorizationUrl(line: String): String? {
    return URL_REGEX.findAll(line)
        .map { match -> match.value.trimEnd('.', ',', ')', ']') }
        .firstOrNull { url -> url.isSafeAuthorizationUrl() }
}

private fun extractUserCode(line: String): String? {
    val match = USER_CODE_REGEX.find(line) ?: return null

    return match.groupValues[1]
}

private fun String.isSafeAuthorizationUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    val schemeIsHttp = uri.scheme == "http" || uri.scheme == "https"

    if (!schemeIsHttp) {
        return false
    }

    val query = uri.rawQuery ?: return true
    val forbiddenQuery = query
        .split("&")
        .map { value -> value.substringBefore("=").lowercase() }
        .any { key ->
            SECRET_QUERY_KEY_PARTS.any { secretPart -> key.contains(secretPart) }
        }

    return !forbiddenQuery
}

private const val DEFAULT_LLM_CLI_HOME_PATH = "/tmp/fukurou-cli-home"
private const val CLAUDE_HOME_DIRECTORY = ".claude"
private const val CODEX_AUTH_FILE_NAME = "auth.json"
private const val STARTUP_CAPTURE_POLL_MILLIS = 100L
private const val DESTROY_GRACE_MILLIS = 500L
private val DEFAULT_LLM_AUTH_LOGIN_TIMEOUT: Duration = Duration.ofMinutes(10)
private val DEFAULT_LLM_AUTH_STARTUP_CAPTURE_TIMEOUT: Duration = Duration.ofSeconds(2)
private val CLAUDE_AUTH_MARKERS = listOf(
    "$CLAUDE_HOME_DIRECTORY/.credentials.json",
    "$CLAUDE_HOME_DIRECTORY/credentials.json",
)
private val URL_REGEX = Regex("""https?://\S+""")
private val USER_CODE_REGEX =
    Regex("""(?i)\b(?:user\s+code|verification\s+code|code)\b[^A-Z0-9]*([A-Z0-9]{4}(?:-[A-Z0-9]{4}){0,2})""")
private val SECRET_QUERY_KEY_PARTS = setOf(
    "token",
    "access_token",
    "refresh_token",
    "id_token",
    "api_key",
    "apikey",
    "secret",
    "password",
    "credential",
)
