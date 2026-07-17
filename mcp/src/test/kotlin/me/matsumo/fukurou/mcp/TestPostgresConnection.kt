package me.matsumo.fukurou.mcp

import org.testcontainers.containers.PostgreSQLContainer
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** JDBC 接続が有限時間に制限された test 用 PostgreSQL container。 */
internal abstract class BoundedTestPostgresContainer<SELF : BoundedTestPostgresContainer<SELF>>(
    dockerImageName: String,
) : PostgreSQLContainer<SELF>(dockerImageName) {
    init {
        withUrlParam(TEST_POSTGRES_CONNECT_TIMEOUT_KEY, TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS.toString())
        withUrlParam(TEST_POSTGRES_LOGIN_TIMEOUT_KEY, TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS.toString())
        withUrlParam(TEST_POSTGRES_SOCKET_TIMEOUT_KEY, TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS.toString())
    }
}

/** JDBC URL の query parameter を key 単位で上書きする。 */
internal fun String.withJdbcQueryParameters(overrides: Map<String, String>): String {
    val baseUrl = substringBefore('?')
    val parameters = substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .filter(String::isNotBlank)
        .associateTo(linkedMapOf()) { parameter ->
            val key = parameter.substringBefore('=').decodeQueryComponent()
            val value = parameter.substringAfter('=', missingDelimiterValue = "").decodeQueryComponent()
            key to value
        }

    parameters.putAll(overrides)

    return parameters.entries.joinToString(
        prefix = "$baseUrl?",
        separator = "&",
    ) { (key, value) -> "${key.encodeQueryComponent()}=${value.encodeQueryComponent()}" }
}

private fun String.decodeQueryComponent(): String = URLDecoder.decode(this, StandardCharsets.UTF_8)

private fun String.encodeQueryComponent(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

internal const val TEST_POSTGRES_CONNECT_TIMEOUT_KEY = "connectTimeout"
internal const val TEST_POSTGRES_LOGIN_TIMEOUT_KEY = "loginTimeout"
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_KEY = "socketTimeout"
internal const val TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS = 10
internal const val TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS = 30
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS = 300
