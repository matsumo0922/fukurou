package me.matsumo.fukurou.trading.decision

import java.security.MessageDigest

/**
 * system-prompt-v1 の参照情報と hash 計算をまとめる object。
 */
object SystemPromptV1 {

    /**
     * decision protocol v1 の system prompt version。
     */
    const val VERSION = "system-prompt-v1.8"

    /**
     * repository root から見た system prompt file path。
     */
    const val RELATIVE_PATH = "prompts/system-prompt-v1.md"

    /**
     * system prompt 本文の SHA-256 hex hash を計算する。
     */
    fun calculateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
