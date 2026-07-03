package me.matsumo.fukurou.trading.runner

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
        val redacted = secretValues.fold(value) { currentValue, secretValue ->
            currentValue.replace(secretValue, REDACTION_PLACEHOLDER)
        }

        if (redacted.length <= maxOutputLength) {
            return redacted
        }

        return redacted.take(maxOutputLength) + TRUNCATED_SUFFIX
    }

    companion object {
        /**
         * 環境変数名から secret らしい値を集めて redactor を構築する。
         */
        fun fromEnvironment(environment: Map<String, String>): SecretRedactor {
            val secretValues = environment
                .filterKeys { key -> key.looksSensitiveEnvironmentKey() }
                .values
                .map { value -> value.trim() }
                .filter { value -> value.length >= MIN_SECRET_VALUE_LENGTH }
                .toSet()

            return SecretRedactor(secretValues)
        }
    }
}

private fun String.looksSensitiveEnvironmentKey(): Boolean {
    val upperKey = uppercase()

    return SENSITIVE_ENV_KEY_PATTERNS.any { pattern -> upperKey.contains(pattern) }
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
