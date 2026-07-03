package me.matsumo.fukurou.trading.invoker

/**
 * env value を空文字除外で読む。
 */
internal fun Map<String, String>.readOptionalEnv(name: String): String? {
    return this[name]
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
}

/**
 * env value の command template を shell 風 quote 対応で分割する。
 */
internal fun Map<String, String>.readCommandTemplateEnv(
    name: String,
    defaultValue: List<String>,
): List<String> {
    return readOptionalEnv(name)?.splitCommandTemplate() ?: defaultValue
}

/**
 * shell 風 quote と escape を解釈して command template を分割する。
 */
internal fun String.splitCommandTemplate(): List<String> {
    val parts = mutableListOf<String>()
    val currentPart = StringBuilder()
    var quote: Char? = null
    var escaping = false

    for (character in this) {
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
