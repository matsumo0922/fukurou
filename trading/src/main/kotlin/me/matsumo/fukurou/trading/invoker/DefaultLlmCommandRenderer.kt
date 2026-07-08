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
import java.util.Comparator

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
 * @param codexPersistentHome Codex CLI の永続 home。明示設定時だけ in-place 更新する。
 */
data class LlmCommandRendererConfig(
    val claudeCommandTemplate: List<String> = DEFAULT_CLAUDE_COMMAND_TEMPLATE,
    val codexCommandTemplate: List<String> = DEFAULT_CODEX_COMMAND_TEMPLATE,
    val claudeModel: String? = null,
    val codexModel: String? = null,
    val claudeCommonArgs: List<String> = DEFAULT_CLAUDE_COMMON_ARGS,
    val codexCommonArgs: List<String> = DEFAULT_CODEX_COMMON_ARGS,
    val codexFalsifierArgs: List<String> = DEFAULT_CODEX_FALSIFIER_ARGS,
    val codexPersistentHome: Path? = null,
) {
    init {
        val unsafeClaudeArgs = claudeCommonArgs.filterUnsafeArgs(CLAUDE_COMMON_ARG_FORBIDDEN_FLAGS)
        val unsafeCodexCommonArgs = codexCommonArgs.filterUnsafeArgs(CODEX_COMMON_ARG_FORBIDDEN_FLAGS)
        val unsafeCodexFalsifierArgs = codexFalsifierArgs
            .filterNot { argument -> argument in CODEX_FALSIFIER_ARG_ALLOWLIST }
        val codexFalsifierBypassesSandbox = codexFalsifierArgs.any { argument ->
            argument in CODEX_FALSIFIER_ARG_ALLOWLIST
        }
        val codexSandboxTemplateConfigured = codexCommandTemplate.usesExternalSandboxTemplate()
        val unsafeCodexSandboxTemplateArgs = codexCommandTemplate.unsafeExternalSandboxTemplateArgs()

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
        require(!codexFalsifierBypassesSandbox || codexSandboxTemplateConfigured) {
            "codexFalsifierArgs sandbox bypass requires an external sandbox/container command template."
        }
        require(!codexFalsifierBypassesSandbox || unsafeCodexSandboxTemplateArgs.isEmpty()) {
            "codexFalsifierArgs sandbox bypass forbids unsafe sandbox template args: $unsafeCodexSandboxTemplateArgs"
        }
    }

    companion object {
        /**
         * 環境変数から renderer 設定を構築する。
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): LlmCommandRendererConfig {
            return LlmCommandRendererConfig(
                claudeCommandTemplate = environment.readCommandTemplateEnv(
                    name = FUKUROU_CLAUDE_COMMAND_TEMPLATE_ENV,
                    defaultValue = DEFAULT_CLAUDE_COMMAND_TEMPLATE,
                ),
                codexCommandTemplate = environment.readCommandTemplateEnv(
                    name = FUKUROU_CODEX_COMMAND_TEMPLATE_ENV,
                    defaultValue = DEFAULT_CODEX_COMMAND_TEMPLATE,
                ),
                claudeModel = environment.readOptionalEnv(FUKUROU_CLAUDE_MODEL_ENV),
                codexModel = environment.readOptionalEnv(FUKUROU_CODEX_MODEL_ENV),
                claudeCommonArgs = environment.readOptionalEnv(FUKUROU_CLAUDE_COMMON_ARGS_ENV)
                    ?.splitCommandTemplate()
                    ?: DEFAULT_CLAUDE_COMMON_ARGS,
                codexCommonArgs = environment.readOptionalEnv(FUKUROU_CODEX_COMMON_ARGS_ENV)
                    ?.splitCommandTemplate()
                    ?: DEFAULT_CODEX_COMMON_ARGS,
                codexFalsifierArgs = environment.readOptionalEnv(FUKUROU_CODEX_FALSIFIER_ARGS_ENV)
                    ?.splitCommandTemplate()
                    ?: DEFAULT_CODEX_FALSIFIER_ARGS,
                codexPersistentHome = environment.readOptionalEnv(FUKUROU_CODEX_PERSISTENT_HOME_ENV)
                    ?.let { value -> Path.of(value) },
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
        val mcpConfigFile = writePrivateConfigFile(
            prefix = "claude-mcp-config",
            suffix = ".json",
            content = request.mcpServer?.toClaudeMcpConfigJson() ?: EMPTY_CLAUDE_MCP_CONFIG_JSON,
        )
        val allowedTools = if (request.mcpServer == null) {
            ""
        } else {
            request.allowedTools.joinToString(",")
        }
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
        val noMcpIsolationArgs = if (request.mcpServer == null) {
            listOf(
                "--bare",
            )
        } else {
            emptyList()
        }
        val args = baseArgs + noMcpIsolationArgs + mcpArgs + CLAUDE_BUILTIN_TOOL_DISABLE_ARGS +
            ENFORCED_CLAUDE_COMMON_ARGS

        return runCatching {
            config.claudeCommandTemplate.toRenderedCommand(
                RenderedCommandRequest(
                    args = args,
                    environment = request.environment,
                    workingDirectory = request.workingDirectory,
                    timeout = request.timeout,
                    stdin = null,
                    cleanupPaths = mcpConfigFile.cleanupPaths,
                ),
            )
        }.getOrElse { throwable ->
            mcpConfigFile.cleanupPaths.deleteGeneratedPaths()

            throw throwable
        }
    }

    private fun renderCodex(request: LlmInvocationRequest): RenderedLlmCommand {
        val phaseArgs = if (request.phase == LlmInvocationPhase.FALSIFIER) {
            config.codexFalsifierArgs
        } else {
            emptyList()
        }
        val codexHome = writeCodexHome(
            mcpServer = request.mcpServer,
            environment = request.environment,
            persistentHome = config.codexPersistentHome,
        )
        val commandEnvironment = request.environment + (CODEX_HOME_ENV to codexHome.path.toString())
        val codexCommonArgs = config.codexCommonArgs.withoutDuplicatedEnforcedCodexArgs()
        val args = listOf("exec") +
            config.codexModelArgs() +
            codexCommonArgs +
            ENFORCED_CODEX_COMMON_ARGS +
            phaseArgs +
            request.prompt

        return runCatching {
            config.codexCommandTemplate.toRenderedCommand(
                RenderedCommandRequest(
                    args = args,
                    environment = commandEnvironment,
                    workingDirectory = request.workingDirectory,
                    timeout = request.timeout,
                    stdin = null,
                    cleanupPaths = codexHome.cleanupPaths,
                ),
            )
        }.getOrElse { throwable ->
            codexHome.cleanupPaths.deleteGeneratedPaths()

            throw throwable
        }
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

private fun List<String>.toRenderedCommand(request: RenderedCommandRequest): RenderedLlmCommand {
    return RenderedLlmCommand(
        executable = first(),
        args = drop(1) + request.args,
        environment = request.environment,
        workingDirectory = request.workingDirectory,
        timeout = request.timeout,
        stdin = request.stdin,
        cleanupPaths = request.cleanupPaths,
    )
}

/**
 * CLI command template に追加する実行時情報。
 *
 * @param args template の後ろに連結する引数
 * @param environment process environment
 * @param workingDirectory process working directory
 * @param timeout process timeout
 * @param stdin process stdin
 * @param cleanupPaths process 終了後に削除する path
 */
private data class RenderedCommandRequest(
    val args: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
    val timeout: java.time.Duration,
    val stdin: String?,
    val cleanupPaths: List<Path>,
)

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

private const val EMPTY_CLAUDE_MCP_CONFIG_JSON = """{"mcpServers":{}}"""

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

        autoApprovedTools.forEach { toolName ->
            append("[mcp_servers.")
            append(name.tomlKey())
            append(".tools.")
            append(toolName.tomlKey())
            append("]\n")
            append("approval_mode = \"approve\"\n")
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
    mcpServer: LlmMcpServerConfig?,
    environment: Map<String, String>,
    persistentHome: Path?,
): PrivateConfigPath {
    if (persistentHome != null) {
        return writePersistentCodexHome(mcpServer, persistentHome)
    }

    return writeTemporaryCodexHome(mcpServer, environment)
}

private fun writePersistentCodexHome(mcpServer: LlmMcpServerConfig?, directory: Path): PrivateConfigPath {
    Files.createDirectories(directory)
    directory.setOwnerOnlyPermissions(PRIVATE_DIRECTORY_PERMISSIONS)

    val configFile = directory.resolve(CODEX_CONFIG_FILE_NAME)
    Files.writeString(
        configFile,
        mcpServer?.toCodexConfigToml().orEmpty(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    configFile.setOwnerOnlyPermissions(PRIVATE_FILE_PERMISSIONS)

    return PrivateConfigPath(
        path = directory,
        cleanupPaths = emptyList(),
    )
}

private fun writeTemporaryCodexHome(
    mcpServer: LlmMcpServerConfig?,
    environment: Map<String, String>,
): PrivateConfigPath {
    val directory = Files.createTempDirectory("fukurou-codex-home-")
    directory.setOwnerOnlyPermissions(PRIVATE_DIRECTORY_PERMISSIONS)

    return runCatching {
        val configFile = directory.resolve(CODEX_CONFIG_FILE_NAME)
        Files.writeString(
            configFile,
            mcpServer?.toCodexConfigToml().orEmpty(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        configFile.setOwnerOnlyPermissions(PRIVATE_FILE_PERMISSIONS)
        val authFile = copyCodexAuthFile(environment, directory)

        PrivateConfigPath(
            path = directory,
            cleanupPaths = listOfNotNull(configFile, authFile, directory),
        )
    }.getOrElse { throwable ->
        directory.deleteGeneratedPath()

        throw throwable
    }
}

private fun copyCodexAuthFile(environment: Map<String, String>, targetDirectory: Path): Path? {
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
    val configuredCodexHome = readOptionalEnv(CODEX_HOME_ENV)?.let { value -> Path.of(value) }
    val fallbackCodexHome = readOptionalEnv(HOME_ENV)?.let { value ->
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

    return runCatching {
        val file = Files.createTempFile(directory, prefix, suffix)
        Files.writeString(
            file,
            content,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        file.setOwnerOnlyPermissions(PRIVATE_FILE_PERMISSIONS)

        PrivateConfigPath(
            path = file,
            cleanupPaths = listOf(file, directory),
        )
    }.getOrElse { throwable ->
        directory.deleteGeneratedPath()

        throw throwable
    }
}

private fun Path.setOwnerOnlyPermissions(permissions: Set<PosixFilePermission>) {
    runCatching {
        Files.setPosixFilePermissions(this, permissions)
    }
}

private fun List<Path>.deleteGeneratedPaths() {
    forEach { path -> path.deleteGeneratedPath() }
}

private fun Path.deleteGeneratedPath() {
    runCatching {
        val directory = Files.isDirectory(this)

        if (directory) {
            Files.walk(this).use { paths ->
                paths
                    .sorted(Comparator.reverseOrder())
                    .forEach { path -> Files.deleteIfExists(path) }
            }
        } else {
            Files.deleteIfExists(this)
        }
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
 * Claude built-in tool を無効化する引数。
 *
 * MCP tool は `--allowedTools` で別に明示する。
 */
val CLAUDE_BUILTIN_TOOL_DISABLE_ARGS = listOf(
    "--tools",
    "",
)

/**
 * Codex headless 実行の既定共通引数。
 */
val DEFAULT_CODEX_COMMON_ARGS = emptyList<String>()

/**
 * Codex headless 実行で常に付ける安全側引数。
 */
val ENFORCED_CODEX_COMMON_ARGS = listOf(
    "--skip-git-repo-check",
    "--sandbox",
    "read-only",
    "-c",
    "approval_policy=\"never\"",
)

/**
 * operator 設定から除外する Codex enforced 引数。
 *
 * renderer が同じ flag を強制付与するため、CLI へ二重渡ししない。
 */
val DEDUPED_ENFORCED_CODEX_COMMON_FLAGS = setOf(
    "--skip-git-repo-check",
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
    "--add-dir",
    "--agent",
    "--agents",
    "--allowed-tools",
    "--mcp-config",
    "--plugin-dir",
    "--plugin-url",
    "--setting-sources",
    "--settings",
    "--strict-mcp-config",
    "--tools",
    "--allowedTools",
    "--bare",
    "--permission-mode",
    "--allow-dangerously-skip-permissions",
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
 * Codex CLI 設定ファイル名。
 */
const val CODEX_CONFIG_FILE_NAME = "config.toml"

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

/**
 * Codex CLI の永続 home path を明示する Fukurou 専用環境変数名。
 *
 * local 開発者の実 `HOME/.codex/config.toml` を誤って上書きしないため、
 * 永続 in-place mode はこの値がある場合だけ有効にし、`HOME` からは推測しない。
 */
const val FUKUROU_CODEX_PERSISTENT_HOME_ENV = "FUKUROU_CODEX_PERSISTENT_HOME"

private fun List<String>.filterUnsafeArgs(forbiddenFlags: Set<String>): List<String> {
    return filter { argument ->
        forbiddenFlags.any { forbiddenFlag -> argument.matchesForbiddenFlag(forbiddenFlag) }
    }
}

private fun List<String>.withoutDuplicatedEnforcedCodexArgs(): List<String> {
    return filterNot { argument ->
        DEDUPED_ENFORCED_CODEX_COMMON_FLAGS.any { enforcedFlag ->
            argument.matchesForbiddenFlag(enforcedFlag)
        }
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

private fun List<String>.usesExternalSandboxTemplate(): Boolean {
    val executable = firstOrNull()?.substringAfterLast("/") ?: return false

    return executable in EXTERNAL_SANDBOX_COMMANDS
}

private fun List<String>.unsafeExternalSandboxTemplateArgs(): List<String> {
    val executable = firstOrNull()?.substringAfterLast("/") ?: return emptyList()

    if (executable !in CONTAINER_SANDBOX_COMMANDS) {
        return emptyList()
    }

    return mapIndexedNotNull { index, argument ->
        val nextArgument = getOrNull(index + 1)

        when {
            argument in UNSAFE_CONTAINER_FLAGS -> argument
            argument.expectsSeparateNetworkValue() && nextArgument.isHostNetworkMode() -> "$argument $nextArgument"
            argument.hasUnsafeNetworkMode() -> argument
            argument.hasInlineRootBindMount() -> argument
            argument.expectsSeparateMountValue() && nextArgument.hasRootBindMount() -> "$argument $nextArgument"
            else -> null
        }
    }
}

private fun String.hasUnsafeNetworkMode(): Boolean {
    return startsWith("--net=host") || startsWith("--network=host")
}

private fun String.expectsSeparateNetworkValue(): Boolean {
    return this == "--net" || this == "--network"
}

private fun String?.isHostNetworkMode(): Boolean {
    return this == "host"
}

private fun String.expectsSeparateMountValue(): Boolean {
    return this == "-v" || this == "--volume" || this == "--mount"
}

private fun String?.hasRootBindMount(): Boolean {
    val value = this ?: return false
    val rootVolumeBind = value == "/" || value.startsWith("/:")
    val rootMountBind = value.mountSourceIsRoot()

    return rootVolumeBind || rootMountBind
}

private fun String.hasInlineRootBindMount(): Boolean {
    val rootVolumeBind = startsWith("-v/:") || startsWith("--volume=/:")
    val rootMountBind = substringAfter("--mount=", missingDelimiterValue = "").mountSourceIsRoot()

    return rootVolumeBind || rootMountBind
}

private fun String.mountSourceIsRoot(): Boolean {
    val source = split(",")
        .firstNotNullOfOrNull { value -> value.mountSourceValue() }

    return source == "/"
}

private fun String.mountSourceValue(): String? {
    val key = substringBefore("=", missingDelimiterValue = "")
    val value = substringAfter("=", missingDelimiterValue = "")
    val isSourceKey = key == "source" || key == "src"

    return if (isSourceKey) value else null
}

/**
 * Codex `--yolo` opt-in を許可する外部 sandbox / container command。
 */
private val EXTERNAL_SANDBOX_COMMANDS = setOf(
    "docker",
    "podman",
    "bwrap",
    "firejail",
)

/**
 * deny-list 検査を適用する container 系 sandbox command。
 */
private val CONTAINER_SANDBOX_COMMANDS = setOf(
    "docker",
    "podman",
)

/**
 * Codex `--yolo` と併用しない container flag。
 */
private val UNSAFE_CONTAINER_FLAGS = setOf(
    "--privileged",
)
