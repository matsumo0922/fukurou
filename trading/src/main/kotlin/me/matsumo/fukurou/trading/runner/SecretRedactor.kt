package me.matsumo.fukurou.trading.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * LLM child process の出力を監査ログへ保存する前に秘密値を伏せる helper。
 *
 * @param secretValues 伏せ字対象にする実値一覧
 * @param maxOutputLength 監査 payload に残す最大文字数
 */
class SecretRedactor(
    private val secretValues: Set<String>,
    private val maxOutputLength: Int = DEFAULT_REDACTED_OUTPUT_LENGTH,
) {

    /**
     * 秘密値を `[REDACTED]` に置換し、長すぎる出力を切り詰める。
     */
    fun redactAndTruncate(value: String): String {
        val redacted = redact(value)

        if (redacted.length <= maxOutputLength) {
            return redacted
        }

        return redacted.take(maxOutputLength) + TRUNCATED_SUFFIX
    }

    /**
     * 秘密値を `[REDACTED]` に置換し、文字数は維持する。
     */
    fun redact(value: String): String {
        return secretValues.fold(value) { currentValue, secretValue ->
            currentValue.replace(secretValue, REDACTION_PLACEHOLDER)
        }
    }

    companion object {
        /**
         * 環境変数名から secret らしい値を集めて redactor を構築する。
         */
        fun fromEnvironment(environment: Map<String, String>): SecretRedactor {
            val secretValues = environment.secretValuesFromEnvironment() + environment.secretValuesFromAuthFiles()

            return SecretRedactor(secretValues)
        }
    }
}

private fun Map<String, String>.secretValuesFromEnvironment(): Set<String> {
    return filterKeys { key -> key.looksSensitiveEnvironmentKey() }
        .values
        .map { value -> value.trim() }
        .filter { value -> value.length >= MIN_SECRET_VALUE_LENGTH }
        .toSet()
}

private fun Map<String, String>.secretValuesFromAuthFiles(): Set<String> {
    return authFilePaths()
        .flatMap { path -> path.secretValuesFromJsonFile() }
        .toSet()
}

private fun Map<String, String>.authFilePaths(): Set<Path> {
    val paths = mutableSetOf<Path>()

    this[CODEX_HOME_ENV]?.let { value ->
        paths.add(Path.of(value).resolve(CODEX_AUTH_FILE_NAME))
    }
    this[CLAUDE_CONFIG_DIR_ENV]?.let { value ->
        paths.add(Path.of(value).resolve(CLAUDE_CREDENTIALS_FILE_NAME))
    }
    this[HOME_ENV]?.let { value ->
        val home = Path.of(value)
        paths.add(home.resolve(DEFAULT_CODEX_AUTH_FILE))
        paths.add(home.resolve(DEFAULT_CLAUDE_CREDENTIALS_FILE))
    }

    return paths
}

private fun Path.secretValuesFromJsonFile(): Set<String> {
    if (!Files.isRegularFile(this) || !Files.isReadable(this)) {
        return emptySet()
    }

    return runCatching {
        Json.parseToJsonElement(Files.readString(this))
            .collectSensitiveJsonValues(parentKey = null)
            .toSet()
    }.getOrDefault(emptySet())
}

private fun JsonElement.collectSensitiveJsonValues(parentKey: String?): List<String> {
    return when (this) {
        is JsonObject -> entries.flatMap { (key, value) ->
            value.collectSensitiveJsonValues(parentKey = key)
        }
        is JsonPrimitive -> listOfNotNull(contentOrNull?.sensitiveValueFor(parentKey))
        else -> emptyList()
    }
}

private fun String.sensitiveValueFor(parentKey: String?): String? {
    val key = parentKey ?: return null
    val value = trim()

    if (!key.looksSensitiveJsonKey() || value.length < MIN_SECRET_VALUE_LENGTH) {
        return null
    }

    return value
}

private fun String.looksSensitiveEnvironmentKey(): Boolean {
    val upperKey = uppercase()

    return SENSITIVE_ENV_KEY_PATTERNS.any { pattern -> upperKey.contains(pattern) }
}

private fun String.looksSensitiveJsonKey(): Boolean {
    val upperKey = uppercase()

    return SENSITIVE_JSON_KEY_PATTERNS.any { pattern -> upperKey.contains(pattern) }
}

/**
 * 伏せ字後の placeholder。
 */
private const val REDACTION_PLACEHOLDER = "[REDACTED]"

/**
 * 切り詰めた出力に付ける suffix。
 */
private const val TRUNCATED_SUFFIX = "...[TRUNCATED]"

/**
 * 監査 payload に保存する stdout / stderr の最大文字数。
 */
private const val DEFAULT_REDACTED_OUTPUT_LENGTH = 8_000

/**
 * 短すぎる値を過剰に redaction しないための最小長。
 */
private const val MIN_SECRET_VALUE_LENGTH = 4

/**
 * secret 値を持つ可能性が高い環境変数名 pattern。
 */
private val SENSITIVE_ENV_KEY_PATTERNS = listOf(
    "API_KEY",
    "SECRET",
    "TOKEN",
    "PASSWORD",
    "CREDENTIAL",
)

/**
 * auth JSON で token 値を持つ可能性が高い key pattern。
 */
private val SENSITIVE_JSON_KEY_PATTERNS = listOf(
    "API_KEY",
    "APIKEY",
    "ACCESS_TOKEN",
    "ACCESSTOKEN",
    "REFRESH_TOKEN",
    "REFRESHTOKEN",
    "TOKEN",
    "SECRET",
    "PASSWORD",
    "CREDENTIAL",
)

/**
 * Codex CLI home path を渡す環境変数名。
 */
private const val CODEX_HOME_ENV = "CODEX_HOME"

/**
 * Claude CLI config path を渡す環境変数名。
 */
private const val CLAUDE_CONFIG_DIR_ENV = "CLAUDE_CONFIG_DIR"

/**
 * home directory path を渡す環境変数名。
 */
private const val HOME_ENV = "HOME"

/**
 * Codex auth file 名。
 */
private const val CODEX_AUTH_FILE_NAME = "auth.json"

/**
 * Claude credentials file 名。
 */
private const val CLAUDE_CREDENTIALS_FILE_NAME = ".credentials.json"

/**
 * HOME 配下の既定 Codex auth file path。
 */
private val DEFAULT_CODEX_AUTH_FILE = Path.of(".codex", CODEX_AUTH_FILE_NAME)

/**
 * HOME 配下の既定 Claude credentials file path。
 */
private val DEFAULT_CLAUDE_CREDENTIALS_FILE = Path.of(".claude", CLAUDE_CREDENTIALS_FILE_NAME)
