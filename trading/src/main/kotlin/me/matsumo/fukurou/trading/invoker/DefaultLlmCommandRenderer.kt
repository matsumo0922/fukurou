package me.matsumo.fukurou.trading.invoker

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.matsumo.fukurou.trading.config.LlmModelConfig
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

    /** renderer と同じ command templateからversion probe commandを生成する。 */
    fun versionProbeRequest(provider: LlmProvider, immutableFingerprint: String?): LlmCliVersionProbeRequest {
        val template = when (provider) {
            LlmProvider.CLAUDE -> claudeCommandTemplate
            LlmProvider.CODEX -> codexCommandTemplate
        }

        return LlmCliVersionProbeRequest(
            provider = provider,
            command = template + "--version",
            templateRevision = LLM_CLI_COMMAND_TEMPLATE_REVISION,
            immutableFingerprint = immutableFingerprint,
        )
    }

    companion object {
        /**
         * 環境変数から renderer 設定を構築する。
         *
         * @param environment deployment boundary の設定を読む環境変数
         * @param runtimeModels DB runtime config から解決した model override。指定時は legacy env より優先する
         */
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            runtimeModels: LlmModelConfig? = null,
        ): LlmCommandRendererConfig {
            val claudeModel = if (runtimeModels == null) {
                environment.readOptionalEnv(FUKUROU_CLAUDE_MODEL_ENV)
            } else {
                runtimeModels.claudeModel
            }
            val codexModel = if (runtimeModels == null) {
                environment.readOptionalEnv(FUKUROU_CODEX_MODEL_ENV)
            } else {
                runtimeModels.codexModel
            }

            return LlmCommandRendererConfig(
                claudeCommandTemplate = environment.readCommandTemplateEnv(
                    name = FUKUROU_CLAUDE_COMMAND_TEMPLATE_ENV,
                    defaultValue = DEFAULT_CLAUDE_COMMAND_TEMPLATE,
                ),
                codexCommandTemplate = environment.readCommandTemplateEnv(
                    name = FUKUROU_CODEX_COMMAND_TEMPLATE_ENV,
                    defaultValue = DEFAULT_CODEX_COMMAND_TEMPLATE,
                ),
                claudeModel = claudeModel,
                codexModel = codexModel,
                claudeCommonArgs = environment.readOptionalEnv(FUKUROU_CLAUDE_COMMON_ARGS_ENV)
                    ?.splitCommandTemplate()
                    ?: DEFAULT_CLAUDE_COMMON_ARGS,
                codexCommonArgs = environment.readOptionalEnv(FUKUROU_CODEX_COMMON_ARGS_ENV)
                    ?.splitCommandTemplate()
                    ?: DEFAULT_CODEX_COMMON_ARGS,
                codexFalsifierArgs = environment.readOptionalEnv(FUKUROU_CODEX_FALSIFIER_ARGS_ENV)
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
    private val claudeAuthCopy: (Path, Path) -> Unit = ::copyClaudeAuthFile,
    private val artifactCleanup: (List<Path>) -> Unit = { paths -> paths.deleteGeneratedPaths() },
) : LlmCommandRenderer {

    override fun render(request: LlmInvocationRequest): Result<RenderedLlmCommand> {
        return runCatching {
            McpToolContractCatalog.requireCanonicalPolicy(request.phase, request.toolPolicy)
            require((request.mcpServer != null) == request.toolPolicy.enabledTools.isNotEmpty()) {
                "MCP server and enabled tool policy must be present together."
            }
            when (request.provider) {
                LlmProvider.CLAUDE -> renderClaude(request)
                LlmProvider.CODEX -> renderCodex(request)
            }
        }.onFailure {
            request.mcpServer?.manifestPath?.deleteGeneratedPath()
        }
    }

    private fun renderClaude(request: LlmInvocationRequest): RenderedLlmCommand {
        val mcpConfigFile = writePrivateConfigFile(
            prefix = "claude-mcp-config",
            suffix = ".json",
            content = request.mcpServer?.toClaudeMcpConfigJson() ?: EMPTY_CLAUDE_MCP_CONFIG_JSON,
        )
        val generatedPaths = mcpConfigFile.cleanupPaths.toMutableList()

        return try {
            renderClaudeWithArtifacts(request, mcpConfigFile, generatedPaths)
        } catch (throwable: Throwable) {
            cleanupRenderArtifacts(generatedPaths, throwable)
        }
    }

    private fun cleanupRenderArtifacts(paths: List<Path>, primaryFailure: Throwable): Nothing {
        try {
            artifactCleanup(paths)
        } catch (cleanupFailure: LlmArtifactCleanupException) {
            cleanupFailure.addSuppressed(primaryFailure)
            throw cleanupFailure
        }
        throw primaryFailure
    }

    private fun renderClaudeWithArtifacts(
        request: LlmInvocationRequest,
        mcpConfigFile: PrivateConfigPath,
        generatedPaths: MutableList<Path>,
    ): RenderedLlmCommand {
        val targetDirectory = requireNotNull(mcpConfigFile.path.parent)
        val authSourcePath = request.environment.claudeAuthSourcePath()
        if (request.mcpServer == null && authSourcePath == null) {
            throw LlmProviderContractException(
                LlmProviderFailure(
                    category = LlmProviderFailureCategory.AUTHENTICATION,
                    providerCode = "CLAUDE_AUTH_SOURCE_MISSING",
                    adapterSchemaVersion = LLM_INVOCATION_CONTRACT_VERSION,
                ),
            )
        }
        authSourcePath?.let { sourcePath ->
            val targetPath = targetDirectory.resolve(sourcePath.fileName.toString())
            generatedPaths.add(1, targetPath)
            claudeAuthCopy(sourcePath, targetPath)
        }
        val hasMcpServer = request.mcpServer != null
        val allowedTools = if (hasMcpServer) {
            request.allowedTools.joinToString(",")
        } else {
            ""
        }
        val baseArgs = listOf(
            "-p",
            request.prompt,
        ) + request.claudeModelArgs(config.claudeModel) + request.claudeEffortArgs() + config.claudeCommonArgs
        val mcpArgs = listOf(
            "--mcp-config",
            mcpConfigFile.path.toString(),
            "--strict-mcp-config",
            "--allowedTools",
            allowedTools,
        )
        val args = baseArgs + mcpArgs + claudeToolArgs(hasMcpServer) +
            ENFORCED_CLAUDE_COMMON_ARGS
        val claudeHome = targetDirectory.toString()
        val commandEnvironment = request.environment.withoutLlmSecrets().withClaudeHome(claudeHome)

        return config.claudeCommandTemplate.toRenderedCommand(
            RenderedCommandRequest(
                args = args,
                environment = commandEnvironment,
                workingDirectory = request.workingDirectory,
                timeout = request.timeout,
                stdin = null,
                cleanupPaths = generatedPaths + listOfNotNull(request.mcpServer?.manifestPath),
                configuredModelIdentity = request.configuredModelIdentity(config.claudeModel),
            ),
        )
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
            effort = request.effort,
        )
        val commandEnvironment = request.environment.withoutLlmSecrets() + mapOf(
            CODEX_HOME_ENV to codexHome.path.toString(),
            HOME_ENV to codexHome.path.toString(),
            XDG_CACHE_HOME_ENV to codexHome.path.resolve(".cache").toString(),
        )
        val codexCommonArgs = config.codexCommonArgs.withoutDuplicatedEnforcedCodexArgs()
        val args = listOf("exec") +
            request.codexModelArgs(config.codexModel) +
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
                    cleanupPaths = codexHome.cleanupPaths + listOfNotNull(request.mcpServer?.manifestPath),
                    configuredModelIdentity = request.configuredModelIdentity(config.codexModel),
                ),
            )
        }.getOrElse { throwable ->
            codexHome.cleanupPaths.deleteGeneratedPaths()

            throw throwable
        }
    }
}

private fun claudeToolArgs(hasMcpServer: Boolean) = if (hasMcpServer) CLAUDE_MCP_ONLY_TOOL_ARGS else CLAUDE_NO_TOOL_ARGS

private fun Map<String, String>.withClaudeHome(claudeHome: String): Map<String, String> {
    return this + mapOf(
        CLAUDE_CONFIG_DIR_ENV to claudeHome,
        HOME_ENV to claudeHome,
        XDG_CACHE_HOME_ENV to "$claudeHome/.cache",
    )
}

private fun LlmInvocationRequest.claudeModelArgs(fallbackModel: String?): List<String> {
    val model = model ?: fallbackModel.takeIf { useConfiguredModelFallback } ?: return emptyList()

    return listOf("--model", model)
}

private fun LlmInvocationRequest.claudeEffortArgs(): List<String> {
    val renderedEffort = effort.renderedEffortOrNull() ?: return emptyList()

    return listOf("--effort", renderedEffort)
}

private fun LlmInvocationRequest.codexModelArgs(fallbackModel: String?): List<String> {
    val model = model ?: fallbackModel.takeIf { useConfiguredModelFallback } ?: return emptyList()

    return listOf("-m", model)
}

private fun LlmInvocationRequest.configuredModelIdentity(fallbackModel: String?): LlmConfiguredModelIdentity {
    model?.let { configuredModel ->
        return LlmConfiguredModelIdentity(configuredModel, LlmConfiguredModelSource.REQUEST)
    }
    fallbackModel?.takeIf { useConfiguredModelFallback }?.let { configuredModel ->
        return LlmConfiguredModelIdentity(configuredModel, LlmConfiguredModelSource.RENDERER_CONFIG)
    }

    return LlmConfiguredModelIdentity.CLI_DEFAULT
}

internal fun LlmEffort.renderedEffortOrNull(): String? {
    return when (this) {
        LlmEffort.DEFAULT -> null
        LlmEffort.LOW -> "low"
        LlmEffort.MEDIUM -> "medium"
        LlmEffort.HIGH -> "high"
        LlmEffort.XHIGH -> "xhigh"
    }
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
        configuredModelIdentity = request.configuredModelIdentity,
    )
}

private fun Map<String, String>.withoutLlmSecrets(): Map<String, String> =
    filterKeys { key -> key !in LLM_FORBIDDEN_ENVIRONMENT_KEYS }

/**
 * CLI command template に追加する実行時情報。
 *
 * @param args template の後ろに連結する引数
 * @param environment process environment
 * @param workingDirectory process working directory
 * @param timeout process timeout
 * @param stdin process stdin
 * @param cleanupPaths provider output の解析後に削除する path
 */
private data class RenderedCommandRequest(
    val args: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
    val timeout: java.time.Duration,
    val stdin: String?,
    val cleanupPaths: List<Path>,
    val configuredModelIdentity: LlmConfiguredModelIdentity,
)

private fun LlmMcpServerConfig.toClaudeMcpConfigJson(): String {
    return buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject(name) {
                put("command", command)
                putJsonArray("args") {
                    add(manifestId)
                }
                if (literalEnvironmentVariables.isNotEmpty()) {
                    putJsonObject("env") {
                        literalEnvironmentVariables.forEach { (key, value) -> put(key, value) }
                    }
                }
            }
        }
    }.toString()
}

private const val EMPTY_CLAUDE_MCP_CONFIG_JSON = """{"mcpServers":{}}"""
private val LLM_FORBIDDEN_ENVIRONMENT_KEYS = setOf(
    "DB_PASSWORD",
    "FUKUROU_MCP_DB_PASSWORD_FILE",
    "GMO_API_KEY",
    "GMO_SECRET_KEY",
)
private val MCP_SENSITIVE_ENVIRONMENT_NAME_PARTS = setOf(
    "AUTH",
    "CREDENTIAL",
    "KEY",
    "PASSWORD",
    "SECRET",
    "TOKEN",
)

private fun LlmMcpServerConfig?.toCodexConfigToml(effort: LlmEffort, environment: Map<String, String>): String {
    return buildString {
        effort.renderedEffortOrNull()?.let { renderedEffort ->
            append("model_reasoning_effort = ")
            append(renderedEffort.tomlQuoted())
            append("\n\n")
        }
        val mcpServer = this@toCodexConfigToml ?: return@buildString

        append("[mcp_servers.")
        append(mcpServer.name.tomlKey())
        append("]\n")
        append("command = ")
        append(mcpServer.command.tomlQuoted())
        append("\n")
        append("args = ")
        append(listOf(mcpServer.manifestId).toTomlArray())
        append("\n")
        append("required = true\n")

        val forwardedEnvironmentVariables = mcpServer.forwardedEnvironmentVariables
        require(forwardedEnvironmentVariables.all(environment::containsKey)) {
            "Codex MCP forwarded environment variable is missing."
        }
        require(forwardedEnvironmentVariables.none(::isForbiddenMcpEnvironmentVariable)) {
            "Codex MCP forwarded environment variable is forbidden."
        }
        if (forwardedEnvironmentVariables.isNotEmpty()) {
            append("env_vars = ")
            append(forwardedEnvironmentVariables.toTomlArray())
            append("\n")
        }

        val literalEnvironmentVariables = mcpServer.literalEnvironmentVariables
        if (literalEnvironmentVariables.isNotEmpty()) {
            append("[mcp_servers.")
            append(mcpServer.name.tomlKey())
            append(".env]\n")
            literalEnvironmentVariables.forEach { (key, value) ->
                append(key.tomlKey())
                append(" = ")
                append(value.tomlQuoted())
                append("\n")
            }
        }

        mcpServer.autoApprovedTools.forEach { toolName ->
            append("[mcp_servers.")
            append(mcpServer.name.tomlKey())
            append(".tools.")
            append(toolName.tomlKey())
            append("]\n")
            append("approval_mode = \"approve\"\n")
        }
    }
}

private fun isForbiddenMcpEnvironmentVariable(name: String): Boolean {
    if (name in LLM_FORBIDDEN_ENVIRONMENT_KEYS) return true

    return MCP_SENSITIVE_ENVIRONMENT_NAME_PARTS.any { part -> name.contains(part, ignoreCase = true) }
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
    effort: LlmEffort,
): PrivateConfigPath {
    return writeTemporaryCodexHome(mcpServer, environment, effort)
}

private fun writeTemporaryCodexHome(
    mcpServer: LlmMcpServerConfig?,
    environment: Map<String, String>,
    effort: LlmEffort,
): PrivateConfigPath {
    val directory = Files.createTempDirectory("fukurou-codex-home-")
    directory.setOwnerOnlyPermissions(PRIVATE_DIRECTORY_PERMISSIONS)

    return runCatching {
        val configFile = directory.resolve(CODEX_CONFIG_FILE_NAME)
        Files.writeString(
            configFile,
            mcpServer.toCodexConfigToml(effort, environment),
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

private fun Map<String, String>.claudeAuthSourcePath(): Path? {
    val configuredDirectory = readOptionalEnv(CLAUDE_CONFIG_DIR_ENV)?.let(Path::of)
    val homeDirectory = readOptionalEnv(HOME_ENV)?.let { value -> Path.of(value).resolve(".claude") }
    val sourceDirectory = configuredDirectory ?: homeDirectory ?: return null
    return CLAUDE_CREDENTIAL_FILE_NAMES
        .map(sourceDirectory::resolve)
        .firstOrNull(Files::isRegularFile)
}

private fun copyClaudeAuthFile(sourcePath: Path, targetPath: Path) {
    Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES)
    targetPath.setOwnerOnlyPermissions(PRIVATE_FILE_PERMISSIONS)
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
    var firstFailure: Throwable? = null
    forEach { path ->
        runCatching { path.deleteGeneratedPath() }
            .onFailure { failure ->
                firstFailure?.addSuppressed(failure) ?: run { firstFailure = failure }
            }
    }
    firstFailure?.let { failure -> throw LlmArtifactCleanupException(failure) }
}

@Suppress("NestedBlockDepth")
private fun Path.deleteGeneratedPath() {
    try {
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
    } catch (throwable: LlmArtifactCleanupException) {
        throw throwable
    } catch (throwable: Throwable) {
        throw LlmArtifactCleanupException(throwable)
    }
}

/**
 * runner が生成した秘密値を含む一時設定 path。
 *
 * @param path CLI に渡す path
 * @param cleanupPaths provider output の解析後に削除する path
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

/** CLI command template / version probe contract のrevision。 */
const val LLM_CLI_COMMAND_TEMPLATE_REVISION = "llm-cli-command-v1"

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
 * MCP 経路の Claude built-in tool を `ToolSearch` だけに絞る引数。
 *
 * Claude CLI は stdio MCP tools を deferred tools として渡すため、モデルが MCP tool schema に到達するには
 * built-in `ToolSearch` が必要になる。`--allowedTools` は呼び出し許可の層であり、tool schema の供給・発見層ではない。
 */
val CLAUDE_MCP_ONLY_TOOL_ARGS = listOf(
    "--tools",
    "ToolSearch",
)

/**
 * MCP を使わない Claude 経路で全 built-in tool を無効化する引数。
 */
val CLAUDE_NO_TOOL_ARGS = listOf(
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
    "--json",
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
    "--json",
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
    "--ephemeral",
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
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
)

/**
 * 秘密値を含む一時ディレクトリの POSIX permission。
 */
private val PRIVATE_DIRECTORY_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.GROUP_EXECUTE,
)

private const val CLAUDE_CONFIG_DIR_ENV = "CLAUDE_CONFIG_DIR"
private const val XDG_CACHE_HOME_ENV = "XDG_CACHE_HOME"
private val CLAUDE_CREDENTIAL_FILE_NAMES = listOf(".credentials.json", "credentials.json")

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
