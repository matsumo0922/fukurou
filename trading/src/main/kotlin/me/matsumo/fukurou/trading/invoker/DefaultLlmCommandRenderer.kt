package me.matsumo.fukurou.trading.invoker

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * CLI command renderer の設定。
 *
 * @param claudeCommandTemplate Claude CLI 起動 command template
 * @param codexCommandTemplate Codex CLI 起動 command template
 * @param claudeModel Claude CLI model 名
 * @param codexModel Codex CLI model 名
 * @param claudeCommonArgs Claude CLI 共通引数
 * @param codexCommonArgs Codex CLI 共通引数
 * @param codexFalsifierArgs Codex Falsifier にだけ渡す強権実行引数
 */
data class LlmCommandRendererConfig(
    val claudeCommandTemplate: List<String> = DEFAULT_CLAUDE_COMMAND_TEMPLATE,
    val codexCommandTemplate: List<String> = DEFAULT_CODEX_COMMAND_TEMPLATE,
    val claudeModel: String? = null,
    val codexModel: String? = null,
    val claudeCommonArgs: List<String> = DEFAULT_CLAUDE_COMMON_ARGS,
    val codexCommonArgs: List<String> = DEFAULT_CODEX_COMMON_ARGS,
    val codexFalsifierArgs: List<String> = DEFAULT_CODEX_FALSIFIER_ARGS,
) {
    init {
        val unsafeClaudeArgs = claudeCommonArgs.filterUnsafeArgs(CLAUDE_COMMON_ARG_FORBIDDEN_FLAGS)
        val unsafeCodexCommonArgs = codexCommonArgs.filterUnsafeArgs(CODEX_COMMON_ARG_FORBIDDEN_FLAGS)
        val unsafeCodexFalsifierArgs = codexFalsifierArgs
            .filterNot { argument -> argument in CODEX_FALSIFIER_ARG_ALLOWLIST }

        require(claudeCommandTemplate.isNotEmpty()) {
            "claudeCommandTemplate must not be empty."
        }
        require(codexCommandTemplate.isNotEmpty()) {
            "codexCommandTemplate must not be empty."
        }
        require(unsafeClaudeArgs.isEmpty()) {
            "claudeCommonArgs must not override MCP or permission flags: $unsafeClaudeArgs"
        }
        require(unsafeCodexCommonArgs.isEmpty()) {
            "codexCommonArgs must not override sandbox, config, or approval flags: $unsafeCodexCommonArgs"
        }
        require(unsafeCodexFalsifierArgs.isEmpty()) {
            "codexFalsifierArgs may only include explicit Falsifier sandbox opt-in flags: $unsafeCodexFalsifierArgs"
        }
    }

    companion object {
        /**
         * 環境変数から renderer 設定を構築する。
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): LlmCommandRendererConfig {
            return LlmCommandRendererConfig(
                claudeCommandTemplate = environment.readCommandTemplate(
                    name = FUKUROU_CLAUDE_COMMAND_TEMPLATE_ENV,
                    defaultValue = DEFAULT_CLAUDE_COMMAND_TEMPLATE,
                ),
                codexCommandTemplate = environment.readCommandTemplate(
                    name = FUKUROU_CODEX_COMMAND_TEMPLATE_ENV,
                    defaultValue = DEFAULT_CODEX_COMMAND_TEMPLATE,
                ),
                claudeModel = environment.readOptional(FUKUROU_CLAUDE_MODEL_ENV),
                codexModel = environment.readOptional(FUKUROU_CODEX_MODEL_ENV),
                claudeCommonArgs = environment.readOptional(FUKUROU_CLAUDE_COMMON_ARGS_ENV)
                    ?.splitCommandTemplate()
                    ?: DEFAULT_CLAUDE_COMMON_ARGS,
                codexCommonArgs = environment.readOptional(FUKUROU_CODEX_COMMON_ARGS_ENV)
                    ?.splitCommandTemplate()
                    ?: DEFAULT_CODEX_COMMON_ARGS,
                codexFalsifierArgs = environment.readOptional(FUKUROU_CODEX_FALSIFIER_ARGS_ENV)
                    ?.splitCommandTemplate()
                    ?: DEFAULT_CODEX_FALSIFIER_ARGS,
            )
        }
    }
}

/**
 * claude / codex の headless command を生成する既定 renderer。
 *
 * @param config renderer 設定
 */
class DefaultLlmCommandRenderer(
    private val config: LlmCommandRendererConfig = LlmCommandRendererConfig(),
) : LlmCommandRenderer {

    override fun render(request: LlmInvocationRequest): Result<RenderedLlmCommand> {
        return runCatching {
            when (request.provider) {
                LlmProvider.CLAUDE -> renderClaude(request)
                LlmProvider.CODEX -> renderCodex(request)
            }
        }
    }

    private fun renderClaude(request: LlmInvocationRequest): RenderedLlmCommand {
        val allowedTools = request.allowedTools.joinToString(",")
        val mcpConfigFile = writePrivateConfigFile(
            prefix = "claude-mcp-config",
            suffix = ".json",
            content = request.mcpServer.toClaudeMcpConfigJson(),
        )
        val baseArgs = listOf(
            "-p",
            request.prompt,
        ) + config.claudeModelArgs() + config.claudeCommonArgs
        val mcpArgs = listOf(
            "--mcp-config",
            mcpConfigFile.path.toString(),
            "--strict-mcp-config",
            "--allowedTools",
            allowedTools,
        )
        val args = baseArgs + mcpArgs + ENFORCED_CLAUDE_COMMON_ARGS

        return config.claudeCommandTemplate.toRenderedCommand(
            args = args,
            environment = request.environment,
            workingDirectory = request.workingDirectory,
            timeout = request.timeout,
            stdin = null,
            cleanupPaths = mcpConfigFile.cleanupPaths,
        )
    }

    private fun renderCodex(request: LlmInvocationRequest): RenderedLlmCommand {
        val phaseArgs = if (request.phase == LlmInvocationPhase.FALSIFIER) {
            config.codexFalsifierArgs
        } else {
            emptyList()
        }
        val codexHome = writeCodexHome(request.mcpServer, request.environment)
        val commandEnvironment = request.environment + (CODEX_HOME_ENV to codexHome.path.toString())
        val args = listOf("exec") +
            config.codexModelArgs() +
            config.codexCommonArgs +
            ENFORCED_CODEX_COMMON_ARGS +
            phaseArgs +
            request.prompt

        return config.codexCommandTemplate.toRenderedCommand(
            args = args,
            environment = commandEnvironment,
            workingDirectory = request.workingDirectory,
            timeout = request.timeout,
            stdin = null,
            cleanupPaths = codexHome.cleanupPaths,
        )
    }
}

private fun LlmCommandRendererConfig.claudeModelArgs(): List<String> {
    val model = claudeModel ?: return emptyList()

    return listOf("--model", model)
}

private fun LlmCommandRendererConfig.codexModelArgs(): List<String> {
    val model = codexModel ?: return emptyList()

    return listOf("-m", model)
}

private fun List<String>.toRenderedCommand(
    args: List<String>,
    environment: Map<String, String>,
    workingDirectory: java.nio.file.Path,
    timeout: java.time.Duration,
    stdin: String?,
    cleanupPaths: List<Path>,
): RenderedLlmCommand {
    return RenderedLlmCommand(
        executable = first(),
        args = drop(1) + args,
        environment = environment,
        workingDirectory = workingDirectory,
        timeout = timeout,
        stdin = stdin,
        cleanupPaths = cleanupPaths,
    )
}

private fun LlmMcpServerConfig.toClaudeMcpConfigJson(): String {
    return buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject(name) {
                put("command", command)
                putJsonArray("args") {
                    args.forEach { argument -> add(argument) }
                }
                putJsonObject("env") {
                    environment.forEach { (key, value) -> put(key, value) }
                }
            }
        }
    }.toString()
}

private fun LlmMcpServerConfig.toCodexConfigToml(): String {
    return buildString {
        append("[mcp_servers.")
        append(name.tomlKey())
        append("]\n")
        append("command = ")
        append(command.tomlQuoted())
        append("\n")
        append("args = ")
        append(args.toTomlArray())
        append("\n")

        if (environment.isNotEmpty()) {
            append("[mcp_servers.")
            append(name.tomlKey())
            append(".env]\n")
            environment.forEach { (key, value) ->
                append(key.tomlKey())
                append(" = ")
                append(value.tomlQuoted())
                append("\n")
            }
        }
    }
}

private fun List<String>.toTomlArray(): String {
    return joinToString(
        prefix = "[",
        postfix = "]",
    ) { value -> value.tomlQuoted() }
}

private fun String.tomlQuoted(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private fun String.tomlKey(): String {
    return tomlQuoted()
}

private fun writeCodexHome(
    mcpServer: LlmMcpServerConfig,
    environment: Map<String, String>,
): PrivateConfigPath {
    val directory = Files.createTempDirectory("fukurou-codex-home-")
    directory.setOwnerOnlyPermissions(PRIVATE_DIRECTORY_PERMISSIONS)

    val configFile = directory.resolve("config.toml")
    Files.writeString(
        configFile,
        mcpServer.toCodexConfigToml(),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
    configFile.setOwnerOnlyPermissions(PRIVATE_FILE_PERMISSIONS)
    val authFile = copyCodexAuthFile(environment, directory)

    return PrivateConfigPath(
        path = directory,
        cleanupPaths = listOfNotNull(configFile, authFile, directory),
    )
}

private fun copyCodexAuthFile(
    environment: Map<String, String>,
    targetDirectory: Path,
): Path? {
    val sourcePath = environment.codexAuthFilePath() ?: return null

    if (!Files.isRegularFile(sourcePath)) {
        return null
    }

    val targetPath = targetDirectory.resolve(CODEX_AUTH_FILE_NAME)
    Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES)
    targetPath.setOwnerOnlyPermissions(PRIVATE_FILE_PERMISSIONS)

    return targetPath
}

private fun Map<String, String>.codexAuthFilePath(): Path? {
    val configuredCodexHome = readOptional(CODEX_HOME_ENV)?.let { value -> Path.of(value) }
    val fallbackCodexHome = readOptional(HOME_ENV)?.let { value ->
        Path.of(value).resolve(DEFAULT_CODEX_HOME_DIRECTORY)
    }
    val codexHome = configuredCodexHome ?: fallbackCodexHome ?: return null

    return codexHome.resolve(CODEX_AUTH_FILE_NAME)
}

private fun writePrivateConfigFile(
    prefix: String,
    suffix: String,
    content: String,
): PrivateConfigPath {
    val directory = Files.createTempDirectory("fukurou-llm-config-")
    directory.setOwnerOnlyPermissions(PRIVATE_DIRECTORY_PERMISSIONS)

    val file = Files.createTempFile(directory, prefix, suffix)
    Files.writeString(
        file,
        content,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    file.setOwnerOnlyPermissions(PRIVATE_FILE_PERMISSIONS)

    return PrivateConfigPath(
        path = file,
        cleanupPaths = listOf(file, directory),
    )
}

private fun Path.setOwnerOnlyPermissions(permissions: Set<PosixFilePermission>) {
    runCatching {
        Files.setPosixFilePermissions(this, permissions)
    }
}

/**
 * runner が生成した秘密値を含む一時設定 path。
 *
 * @param path CLI に渡す path
 * @param cleanupPaths process 終了後に削除する path
 */
private data class PrivateConfigPath(
    val path: Path,
    val cleanupPaths: List<Path>,
)

/**
 * 既定 Claude CLI command template。
 */
val DEFAULT_CLAUDE_COMMAND_TEMPLATE = listOf("claude")

/**
 * 既定 Codex CLI command template。
 */
val DEFAULT_CODEX_COMMAND_TEMPLATE = listOf("codex")

/**
 * Claude headless 実行の既定共通引数。
 */
val DEFAULT_CLAUDE_COMMON_ARGS = emptyList<String>()

/**
 * Claude headless 実行で常に付ける安全側引数。
 */
val ENFORCED_CLAUDE_COMMON_ARGS = listOf(
    "--permission-mode",
    "dontAsk",
    "--output-format",
    "json",
    "--no-session-persistence",
)

/**
 * Codex headless 実行の既定共通引数。
 */
val DEFAULT_CODEX_COMMON_ARGS = emptyList<String>()

/**
 * Codex headless 実行で常に付ける安全側引数。
 */
val ENFORCED_CODEX_COMMON_ARGS = listOf(
    "--sandbox",
    "read-only",
    "-c",
    "approval_policy=\"never\"",
)

/**
 * Falsifier Codex に追加する既定引数。
 *
 * 外部 sandbox の実体は運用設定で差し替えるため、既定では危険側の bypass 引数を付けない。
 */
val DEFAULT_CODEX_FALSIFIER_ARGS = emptyList<String>()

/**
 * Claude common args で上書きさせない安全境界 flag。
 */
val CLAUDE_COMMON_ARG_FORBIDDEN_FLAGS = setOf(
    "--mcp-config",
    "--allowedTools",
    "--permission-mode",
    "--dangerously-skip-permissions",
)

/**
 * Codex common args で上書きさせない安全境界 flag。
 */
val CODEX_COMMON_ARG_FORBIDDEN_FLAGS = setOf(
    "-c",
    "--config",
    "--sandbox",
    "--ask-for-approval",
    "--dangerously-bypass-approvals-and-sandbox",
    "--yolo",
)

/**
 * Falsifier 専用 args で許可する明示的な外部 sandbox opt-in flag。
 */
val CODEX_FALSIFIER_ARG_ALLOWLIST = setOf(
    "--dangerously-bypass-approvals-and-sandbox",
    "--yolo",
)

/**
 * Codex CLI home path を渡す環境変数名。
 */
const val CODEX_HOME_ENV = "CODEX_HOME"

/**
 * home directory path を渡す環境変数名。
 */
const val HOME_ENV = "HOME"

/**
 * Codex CLI の既定 home directory 名。
 */
const val DEFAULT_CODEX_HOME_DIRECTORY = ".codex"

/**
 * Codex CLI 認証ファイル名。
 */
const val CODEX_AUTH_FILE_NAME = "auth.json"

/**
 * 秘密値を含む一時ファイルの POSIX permission。
 */
private val PRIVATE_FILE_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)

/**
 * 秘密値を含む一時ディレクトリの POSIX permission。
 */
private val PRIVATE_DIRECTORY_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)

/**
 * Claude CLI command template の環境変数名。
 */
const val FUKUROU_CLAUDE_COMMAND_TEMPLATE_ENV = "FUKUROU_CLAUDE_COMMAND_TEMPLATE"

/**
 * Codex CLI command template の環境変数名。
 */
const val FUKUROU_CODEX_COMMAND_TEMPLATE_ENV = "FUKUROU_CODEX_COMMAND_TEMPLATE"

/**
 * Claude model 名の環境変数名。
 */
const val FUKUROU_CLAUDE_MODEL_ENV = "FUKUROU_CLAUDE_MODEL"

/**
 * Codex model 名の環境変数名。
 */
const val FUKUROU_CODEX_MODEL_ENV = "FUKUROU_CODEX_MODEL"

/**
 * Claude CLI 共通引数の環境変数名。
 */
const val FUKUROU_CLAUDE_COMMON_ARGS_ENV = "FUKUROU_CLAUDE_COMMON_ARGS"

/**
 * Codex CLI 共通引数の環境変数名。
 */
const val FUKUROU_CODEX_COMMON_ARGS_ENV = "FUKUROU_CODEX_COMMON_ARGS"

/**
 * Codex Falsifier 引数の環境変数名。
 */
const val FUKUROU_CODEX_FALSIFIER_ARGS_ENV = "FUKUROU_CODEX_FALSIFIER_ARGS"

private fun Map<String, String>.readCommandTemplate(
    name: String,
    defaultValue: List<String>,
): List<String> {
    return readOptional(name)?.splitCommandTemplate() ?: defaultValue
}

private fun Map<String, String>.readOptional(name: String): String? {
    return this[name]
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
}

private fun List<String>.filterUnsafeArgs(forbiddenFlags: Set<String>): List<String> {
    return filter { argument ->
        forbiddenFlags.any { forbiddenFlag -> argument.matchesForbiddenFlag(forbiddenFlag) }
    }
}

private fun String.matchesForbiddenFlag(forbiddenFlag: String): Boolean {
    val exactMatch = this == forbiddenFlag
    val equalsValueMatch = startsWith("$forbiddenFlag=")
    val fusedShortOption = forbiddenFlag.isShortFlag() && startsWith(forbiddenFlag)

    return exactMatch || equalsValueMatch || fusedShortOption
}

private fun String.isShortFlag(): Boolean {
    return startsWith("-") && !startsWith("--")
}

private fun String.splitCommandTemplate(): List<String> {
    val parts = mutableListOf<String>()
    val currentPart = StringBuilder()
    var quote: Char? = null
    var escaping = false

    forEach { character ->
        val quoteClosed = quoteMatches(quote, character)
        val quoteOpened = quoteOpens(quote, character)

        when {
            escaping -> {
                currentPart.append(character)
                escaping = false
            }
            character == '\\' -> escaping = true
            quoteClosed -> quote = null
            quote != null -> currentPart.append(character)
            quoteOpened -> quote = character
            character.isWhitespace() -> {
                if (currentPart.isNotEmpty()) {
                    parts += currentPart.toString()
                    currentPart.clear()
                }
            }
            else -> currentPart.append(character)
        }
    }

    require(!escaping) {
        "command template must not end with an escape character."
    }
    require(quote == null) {
        "command template quote was not closed."
    }
    if (currentPart.isNotEmpty()) {
        parts += currentPart.toString()
    }

    require(parts.isNotEmpty()) {
        "command template must not be empty."
    }

    return parts
}

private fun quoteMatches(quote: Char?, character: Char): Boolean {
    return quote != null && character == quote
}

private fun quoteOpens(quote: Char?, character: Char): Boolean {
    return quote == null && character.isCommandQuote()
}

private fun Char.isCommandQuote(): Boolean {
    return this == '"' || this == '\''
}
