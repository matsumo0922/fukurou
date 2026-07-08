package me.matsumo.fukurou

/**
 * CLI auth token/code の最大文字数。
 */
internal const val MAX_LLM_AUTH_TOKEN_CODE_LENGTH = 4096

internal fun String.containsLlmAuthTokenCodeLineBreak(): Boolean {
    return any { character -> character == '\n' || character == '\r' }
}
