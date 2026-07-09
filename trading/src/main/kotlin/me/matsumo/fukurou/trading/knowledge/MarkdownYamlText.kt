package me.matsumo.fukurou.trading.knowledge

internal fun StringBuilder.appendYamlList(key: String, values: List<String>) {
    if (values.isEmpty()) {
        appendLine("$key: []")
        return
    }

    appendLine("$key:")
    values.forEach { value ->
        appendLine("  - ${value.yamlQuoted()}")
    }
}

internal fun String.yamlQuoted(): String {
    val escaped = buildString {
        this@yamlQuoted.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> appendYamlCharacter(character)
            }
        }
    }

    return "\"$escaped\""
}

internal fun String?.yamlNullable(): String {
    return this?.yamlQuoted() ?: "null"
}

private fun StringBuilder.appendYamlCharacter(character: Char) {
    if (character.code < YAML_CONTROL_CHARACTER_BOUNDARY) {
        append(' ')
    } else {
        append(character)
    }
}

private const val YAML_CONTROL_CHARACTER_BOUNDARY = 0x20
