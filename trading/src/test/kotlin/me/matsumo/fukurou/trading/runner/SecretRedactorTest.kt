package me.matsumo.fukurou.trading.runner

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SecretRedactor の redaction source を検証するテスト。
 */
class SecretRedactorTest {

    @Test
    fun fromEnvironment_redactsMountedCliAuthJsonTokens() {
        val codexHome = Files.createTempDirectory("fukurou-redactor-codex-home")
        val claudeConfig = Files.createTempDirectory("fukurou-redactor-claude-config")
        Files.writeString(
            codexHome.resolve("auth.json"),
            """{"tokens":{"accessToken":"codex-access-token-value","refreshToken":"codex-refresh-token-value"}}""",
        )
        Files.writeString(
            claudeConfig.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"claude-access-token-value","refreshToken":"claude-refresh-token-value"}}""",
        )
        val redactor = SecretRedactor.fromEnvironment(
            mapOf(
                "CODEX_HOME" to codexHome.toString(),
                "CLAUDE_CONFIG_DIR" to claudeConfig.toString(),
            ),
        )

        val redacted = redactor.redactAndTruncate(
            "codex-access-token-value claude-refresh-token-value visible-text",
        )

        assertFalse(redacted.contains("codex-access-token-value"))
        assertFalse(redacted.contains("claude-refresh-token-value"))
        assertTrue(redacted.contains("visible-text"))

        Files.deleteIfExists(codexHome.resolve("auth.json"))
        Files.deleteIfExists(claudeConfig.resolve(".credentials.json"))
        Files.deleteIfExists(codexHome)
        Files.deleteIfExists(claudeConfig)
    }
}
