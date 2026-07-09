package me.matsumo.fukurou

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.DurationUnit
import kotlin.time.toDuration

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
 * CLI auth login token/code submit 結果。
 */
sealed interface LlmAuthLoginTokenSubmitResult {

    /**
     * token/code を CLI process へ送信した。
     *
     * @param session login session snapshot
     */
    data class Accepted(
        val session: LlmAuthLoginSessionSnapshot,
    ) : LlmAuthLoginTokenSubmitResult

    /**
     * token/code を CLI process へ送信しなかった。
     *
     * @param rejection 拒否分類
     * @param reason secret を含まない拒否理由
     */
    data class Rejected(
        val rejection: LlmAuthLoginTokenSubmitRejection,
        val reason: String,
    ) : LlmAuthLoginTokenSubmitResult
}

/**
 * CLI auth login token/code submit の拒否分類。
 */
enum class LlmAuthLoginTokenSubmitRejection {
    /**
     * provider が token/code submit を受け付けない。
     */
    UNSUPPORTED_PROVIDER,

    /**
     * session が存在しない。
     */
    SESSION_NOT_FOUND,

    /**
     * session が実行中ではない。
     */
    SESSION_NOT_RUNNING,

    /**
     * session はすでに token/code を受け付けた。
     */
    ALREADY_SUBMITTED,

    /**
     * CLI process の stdin へ書き込めない。
     */
    STDIN_UNAVAILABLE,
}

/**
 * CLI auth login session snapshot。
 *
 * @param provider provider
 * @param sessionId session ID
 * @param status login process 状態
 * @param authorizationUrl browser 承認用 URL
 * @param userCode device auth code
 * @param tokenSubmitAvailable token/code submit を受け付けるか
 * @param tokenSubmitted token/code submit 済みか
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
    val tokenSubmitAvailable: Boolean,
    val tokenSubmitted: Boolean,
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

    /**
     * active Claude login session へ token/code を 1 回だけ送信する。
     */
    suspend fun submitLoginTokenCode(
        provider: LlmAuthProvider,
        sessionId: String,
        tokenCode: String,
    ): Result<LlmAuthLoginTokenSubmitResult>
}

/**
 * CLI auth service 設定。
 *
 * @param claudeCommandTemplate Claude CLI command template
 * @param codexCommandTemplate Codex CLI command template
 * @param cliHome Claude/Codex login state を置く共通 home
 * @param codexHome Codex CLI home
 * @param inheritedLoginEnvironment login CLI 起動に必要な非 secret env
 * @param loginTimeout login process timeout
 * @param startupCaptureTimeout login start 応答前に URL / code を待つ時間
 * @param terminalSessionRetention 完了済み login session を polling 用に保持する時間
 */
data class LlmAuthServiceConfig(
    val claudeCommandTemplate: List<String>,
    val codexCommandTemplate: List<String>,
    val cliHome: Path,
    val codexHome: Path,
    val inheritedLoginEnvironment: Map<String, String> = System.getenv().safeLlmAuthEnvironment(),
    val loginTimeout: Duration = DEFAULT_LLM_AUTH_LOGIN_TIMEOUT,
    val startupCaptureTimeout: Duration = DEFAULT_LLM_AUTH_STARTUP_CAPTURE_TIMEOUT,
    val terminalSessionRetention: Duration = DEFAULT_LLM_AUTH_TERMINAL_SESSION_RETENTION,
) {
    init {
        require(!terminalSessionRetention.isNegative) {
            "terminalSessionRetention must not be negative."
        }
    }

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
                inheritedLoginEnvironment = environment.safeLlmAuthEnvironment(),
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
    private val processJobs = ConcurrentHashMap<String, List<Job>>()
    private val lifecycleLock = Any()
    private val closing = AtomicBoolean(false)

    override suspend fun snapshot(): Result<LlmAuthSnapshot> {
        return runCatching {
            evictExpiredLoginSessions()

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
            evictExpiredLoginSessions()

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
            evictExpiredLoginSessions()

            val session = sessions[sessionId] ?: return@runCatching null

            if (session.provider != provider) {
                return@runCatching null
            }

            session.snapshot()
        }
    }

    override suspend fun submitLoginTokenCode(
        provider: LlmAuthProvider,
        sessionId: String,
        tokenCode: String,
    ): Result<LlmAuthLoginTokenSubmitResult> {
        return runCatching {
            evictExpiredLoginSessions()

            if (provider != LlmAuthProvider.CLAUDE) {
                return@runCatching LlmAuthLoginTokenSubmitResult.Rejected(
                    rejection = LlmAuthLoginTokenSubmitRejection.UNSUPPORTED_PROVIDER,
                    reason = "provider does not accept token/code submit",
                )
            }

            val session = sessions[sessionId]

            if (session == null || session.provider != provider) {
                return@runCatching LlmAuthLoginTokenSubmitResult.Rejected(
                    rejection = LlmAuthLoginTokenSubmitRejection.SESSION_NOT_FOUND,
                    reason = "login session not found",
                )
            }

            val trimmedTokenCode = tokenCode.trim()

            require(trimmedTokenCode.isNotEmpty()) {
                "tokenCode must not be blank."
            }
            require(!trimmedTokenCode.containsLlmAuthTokenCodeLineBreak()) {
                "tokenCode must be a single line."
            }
            require(trimmedTokenCode.length <= MAX_LLM_AUTH_TOKEN_CODE_LENGTH) {
                "tokenCode is too long."
            }

            val result = session.submitTokenCode(trimmedTokenCode)

            if (result is LlmAuthLoginTokenSubmitResult.Accepted) {
                appendTokenSubmitAudit(result.session)
            }

            result
        }
    }

    override fun close() {
        val jobs = synchronized(lifecycleLock) {
            closing.set(true)
            sessions.values.forEach { session ->
                runCatching { session.destroyProcess() }
            }

            processJobs.values.flatten()
        }

        awaitJobs(jobs)
        scope.cancel()
        scope.coroutineContext[Job]?.let { scopeJob -> awaitJobs(listOf(scopeJob)) }
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
        if (closing.get()) {
            return LlmAuthLoginStartResult.Rejected("service is closing")
        }

        val sessionId = idGenerator().toString()
        val sessionReserved = reserveLoginSession(provider, sessionId)

        if (!sessionReserved) {
            return LlmAuthLoginStartResult.Rejected("login already in progress")
        }

        var session: MutableLlmAuthLoginSession? = null

        try {
            val startedAt = Instant.now(clock)
            val process = processStarter.start(
                command = provider.loginCommand(),
                environment = provider.loginEnvironment(),
                workingDirectory = config.cliHome,
            )
            session = MutableLlmAuthLoginSession(
                provider = provider,
                sessionId = sessionId,
                process = process,
                startedAt = startedAt,
                expiresAt = startedAt.plus(config.loginTimeout),
                clock = clock,
            )

            sessions[sessionId] = session
            session.prepareStdin()
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
            val readersLaunched = launchReaders(session, reason)

            if (!readersLaunched) {
                activeSessions.remove(provider, sessionId)
                sessions.remove(sessionId, session)
                session.destroyProcess()

                return LlmAuthLoginStartResult.Rejected("service is closing")
            }

            waitForStartupCapture(session)

            return LlmAuthLoginStartResult.Accepted(session.snapshot())
        } catch (throwable: Throwable) {
            activeSessions.remove(provider, sessionId)
            sessions.remove(sessionId)
            session?.destroyProcess()

            throw throwable
        }
    }

    private fun reserveLoginSession(provider: LlmAuthProvider, sessionId: String): Boolean {
        var reserved = false

        activeSessions.compute(provider) { _, activeSessionId ->
            val activeSession = activeSessionId?.let { existingSessionId -> sessions[existingSessionId] }
            val loginIsReserved = activeSessionId != null && activeSession == null

            if (loginIsReserved || activeSession?.isRunning() == true) {
                activeSessionId
            } else {
                reserved = true
                sessionId
            }
        }

        return reserved
    }

    private fun launchReaders(session: MutableLlmAuthLoginSession, reason: String): Boolean {
        val jobs = listOf(
            scope.launch(start = CoroutineStart.LAZY) {
                collectProcessOutput(session.process.inputStream, session)
            },
            scope.launch(start = CoroutineStart.LAZY) {
                collectProcessOutput(session.process.errorStream, session)
            },
            scope.launch(start = CoroutineStart.LAZY) {
                waitForProcess(session, reason)
            },
        )
        val completionHandles = mutableListOf<DisposableHandle>()

        synchronized(lifecycleLock) {
            if (closing.get()) {
                jobs.forEach { job -> job.cancel() }

                return false
            }

            processJobs[session.sessionId] = jobs
            jobs.forEach { job ->
                val completionHandle = job.invokeOnCompletion {
                    if (jobs.all { processJob -> processJob.isCompleted }) {
                        processJobs.remove(session.sessionId, jobs)
                        completionHandles.forEach { handle -> handle.dispose() }
                    }
                }
                completionHandles += completionHandle
            }
            jobs.forEach { job -> job.start() }
        }

        return true
    }

    private suspend fun collectProcessOutput(inputStream: InputStream, session: MutableLlmAuthLoginSession) {
        withContext(Dispatchers.IO) {
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    session.observeCliLine(line)
                }
            }
        }
    }

    private suspend fun waitForProcess(session: MutableLlmAuthLoginSession, reason: String) {
        val completed = withContext(Dispatchers.IO) {
            session.process.waitFor(config.loginTimeout.toMillis(), TimeUnit.MILLISECONDS)
        }
        val (status, eventType) = if (completed) {
            if (session.process.exitValue() == 0) {
                LlmAuthLoginStatus.SUCCEEDED to CommandEventType.CLI_AUTH_LOGIN_COMPLETED
            } else {
                LlmAuthLoginStatus.FAILED to CommandEventType.CLI_AUTH_LOGIN_FAILED
            }
        } else {
            session.destroyProcess()

            LlmAuthLoginStatus.TIMED_OUT to CommandEventType.CLI_AUTH_LOGIN_TIMED_OUT
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
        scheduleTerminalSessionCleanup(session)
    }

    private fun awaitJobs(jobs: Collection<Job>) {
        if (jobs.isEmpty()) return

        val completed = CountDownLatch(jobs.size)
        val completionHandles = jobs.map { job ->
            job.invokeOnCompletion {
                completed.countDown()
            }
        }

        try {
            completed.await(CLOSE_AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            completionHandles.forEach { handle -> handle.dispose() }
        }
    }

    private fun scheduleTerminalSessionCleanup(session: MutableLlmAuthLoginSession) {
        scope.launch {
            val retentionMillis = config.terminalSessionRetention.toMillis()

            if (retentionMillis > 0) {
                delay(retentionMillis.toDuration(DurationUnit.MILLISECONDS))
            }

            sessions.remove(session.sessionId, session)
        }
    }

    private fun evictExpiredLoginSessions() {
        val now = Instant.now(clock)

        sessions.forEach { (sessionId, session) ->
            if (session.isTerminalExpired(now, config.terminalSessionRetention)) {
                sessions.remove(sessionId, session)
            }
        }
    }

    private suspend fun waitForStartupCapture(session: MutableLlmAuthLoginSession) {
        val deadline = Instant.now(clock).plus(config.startupCaptureTimeout)

        while (Instant.now(clock).isBefore(deadline)) {
            val snapshot = session.snapshot()
            val hasChallenge = snapshot.authorizationUrl != null || snapshot.userCode != null

            if (hasChallenge || snapshot.status != LlmAuthLoginStatus.RUNNING) {
                return
            }

            delay(STARTUP_CAPTURE_POLL_MILLIS.toDuration(DurationUnit.MILLISECONDS))
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

    private suspend fun appendTokenSubmitAudit(session: LlmAuthLoginSessionSnapshot) {
        val eventLog = commandEventLog ?: return
        val payload = buildJsonObject {
            put("provider", session.provider.wireName)
            put("sessionId", session.sessionId)
            put("status", session.status.wireName)
            put("detail", session.detail ?: "")
        }.toString()

        eventLog.append(
            CommandEvent(
                decisionRunContext = DecisionRunContext.EMPTY,
                toolName = "cli-auth",
                toolCallId = null,
                clientRequestId = session.sessionId,
                eventType = CommandEventType.CLI_AUTH_LOGIN_TOKEN_SUBMITTED,
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
            LlmAuthProvider.CLAUDE -> config.claudeCommandTemplate + listOf("auth", "login")
            LlmAuthProvider.CODEX -> config.codexCommandTemplate + listOf("login", "--device-auth")
        }
    }

    private fun LlmAuthProvider.loginEnvironment(): Map<String, String> {
        val providerEnvironment = when (this) {
            LlmAuthProvider.CLAUDE -> mapOf(
                HOME_ENV to config.cliHome.toString(),
            )
            LlmAuthProvider.CODEX -> mapOf(
                HOME_ENV to config.cliHome.toString(),
                CODEX_HOME_ENV to config.codexHome.toString(),
                FUKUROU_CODEX_PERSISTENT_HOME_ENV to config.codexHome.toString(),
            )
        }

        return config.inheritedLoginEnvironment + providerEnvironment
    }
}

private fun Map<String, String>.safeLlmAuthEnvironment(): Map<String, String> {
    return LLM_AUTH_INHERITED_ENV_NAMES
        .mapNotNull { name ->
            val value = this[name]?.takeIf { candidate -> candidate.isNotBlank() }

            value?.let { safeValue -> name to safeValue }
        }
        .toMap()
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

internal object JvmLlmAuthProcessStarter : LlmAuthProcessStarter {
    override fun start(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: Path,
    ): Process {
        Files.createDirectories(workingDirectory)

        val processBuilder = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
        processBuilder.environment().apply {
            clear()
            putAll(environment)
        }

        val process = processBuilder.start()

        return process
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

    @Volatile
    private var tokenSubmittedAt: Instant? = null

    @Synchronized
    fun prepareStdin() {
        if (provider == LlmAuthProvider.CODEX) {
            closeProcessInput()
        }
    }

    @Synchronized
    fun isRunning(): Boolean {
        return status == LlmAuthLoginStatus.RUNNING
    }

    @Synchronized
    fun isTerminalExpired(now: Instant, retention: Duration): Boolean {
        val completed = completedAt ?: return false

        return !completed.plus(retention).isAfter(now)
    }

    @Synchronized
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

    @Synchronized
    fun submitTokenCode(tokenCode: String): LlmAuthLoginTokenSubmitResult {
        if (status != LlmAuthLoginStatus.RUNNING) {
            return LlmAuthLoginTokenSubmitResult.Rejected(
                rejection = LlmAuthLoginTokenSubmitRejection.SESSION_NOT_RUNNING,
                reason = "login session is not running",
            )
        }

        if (tokenSubmittedAt != null) {
            return LlmAuthLoginTokenSubmitResult.Rejected(
                rejection = LlmAuthLoginTokenSubmitRejection.ALREADY_SUBMITTED,
                reason = "login token/code already submitted",
            )
        }

        val tokenCodeLine = "$tokenCode\n".toByteArray(StandardCharsets.UTF_8)
        val writeSucceeded = runCatching {
            process.outputStream.write(tokenCodeLine)
            process.outputStream.flush()
        }.isSuccess

        if (!writeSucceeded) {
            return LlmAuthLoginTokenSubmitResult.Rejected(
                rejection = LlmAuthLoginTokenSubmitRejection.STDIN_UNAVAILABLE,
                reason = "login process stdin is unavailable",
            )
        }

        tokenSubmittedAt = Instant.now(clock)
        detail = "authorization token/code submitted; waiting for CLI completion"
        closeProcessInputQuietly()

        return LlmAuthLoginTokenSubmitResult.Accepted(snapshot())
    }

    @Synchronized
    fun complete(status: LlmAuthLoginStatus) {
        closeProcessInputQuietly()
        this.status = status
        completedAt = Instant.now(clock)
        detail = when (status) {
            LlmAuthLoginStatus.SUCCEEDED -> "login process completed"
            LlmAuthLoginStatus.FAILED -> "login process exited with failure"
            LlmAuthLoginStatus.TIMED_OUT -> "login process timed out"
            LlmAuthLoginStatus.RUNNING -> detail
        }
    }

    @Synchronized
    fun destroyProcess() {
        closeProcessInputQuietly()

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

    @Synchronized
    fun snapshot(): LlmAuthLoginSessionSnapshot {
        val tokenSubmitted = tokenSubmittedAt != null
        val tokenSubmitAvailable = provider == LlmAuthProvider.CLAUDE &&
            status == LlmAuthLoginStatus.RUNNING &&
            !tokenSubmitted

        return LlmAuthLoginSessionSnapshot(
            provider = provider,
            sessionId = sessionId,
            status = status,
            authorizationUrl = authorizationUrl,
            userCode = userCode,
            tokenSubmitAvailable = tokenSubmitAvailable,
            tokenSubmitted = tokenSubmitted,
            detail = detail,
            startedAt = startedAt,
            expiresAt = expiresAt,
            completedAt = completedAt,
        )
    }

    private fun closeProcessInput() {
        process.outputStream.close()
    }

    private fun closeProcessInputQuietly() {
        runCatching { closeProcessInput() }
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

    if (uri.rawFragment != null) {
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
private const val CLOSE_AWAIT_TIMEOUT_MILLIS = 5_000L
private const val PATH_ENV = "PATH"
private val DEFAULT_LLM_AUTH_LOGIN_TIMEOUT: Duration = Duration.ofMinutes(10)
private val DEFAULT_LLM_AUTH_STARTUP_CAPTURE_TIMEOUT: Duration = Duration.ofSeconds(2)
private val DEFAULT_LLM_AUTH_TERMINAL_SESSION_RETENTION: Duration = Duration.ofMinutes(30)
private val CLAUDE_AUTH_MARKERS = listOf(
    "$CLAUDE_HOME_DIRECTORY/.credentials.json",
    "$CLAUDE_HOME_DIRECTORY/credentials.json",
)
private val LLM_AUTH_INHERITED_ENV_NAMES = setOf(PATH_ENV)
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
