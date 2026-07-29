package me.matsumo.fukurou.trading.testing

import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.SQLException

/**
 * JDBC 接続が有限時間に制限された test 用 PostgreSQL container。
 *
 * container を複数 test method で共有する場合は serial 実行を前提とする。
 * `pg_current_wal_insert_lsn()` の WAL 位置と `pg_locks` view は cluster 全体を対象とするため、
 * test ごとに database を分けても隔離されない。
 */
abstract class BoundedTestPostgresContainer<SELF : BoundedTestPostgresContainer<SELF>>(
    dockerImageName: String,
) : PostgreSQLContainer<SELF>(dockerImageName) {
    init {
        withUrlParam(TEST_POSTGRES_CONNECT_TIMEOUT_KEY, TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS.toString())
        withUrlParam(TEST_POSTGRES_LOGIN_TIMEOUT_KEY, TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS.toString())
        withUrlParam(TEST_POSTGRES_SOCKET_TIMEOUT_KEY, TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS.toString())
    }
}

/** Docker daemon が利用可能かを返す。 */
fun isTestDockerAvailable(): Boolean {
    return runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
}

/** test body 開始前の一時的な PostgreSQL 接続失敗だけを最大 2 回再試行する。 */
fun <T> retryTransientTestPostgresConnection(connect: () -> T): T {
    repeat(TEST_POSTGRES_CONNECTION_MAX_ATTEMPTS - 1) {
        try {
            return connect()
        } catch (failure: Exception) {
            if (!failure.hasTransientPostgresConnectionCause()) throw failure
        }
    }

    return connect()
}

/** JDBC URL の query parameter を key 単位で上書きする。 */
fun String.withJdbcQueryParameters(overrides: Map<String, String>): String {
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

private fun Throwable.hasTransientPostgresConnectionCause(): Boolean {
    val causes = generateSequence(this) { throwable -> throwable.cause }.toList()
    val sqlStates = causes
        .filterIsInstance<SQLException>()
        .mapNotNull(SQLException::getSQLState)

    if (sqlStates.isNotEmpty()) return sqlStates.all { sqlState -> sqlState == POSTGRES_CONNECTION_UNABLE_SQL_STATE }

    return causes.any { throwable -> throwable is SocketTimeoutException || throwable is ConnectException }
}

private fun String.decodeQueryComponent(): String = URLDecoder.decode(this, StandardCharsets.UTF_8)

private fun String.encodeQueryComponent(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

const val TEST_POSTGRES_CONNECT_TIMEOUT_KEY = "connectTimeout"
const val TEST_POSTGRES_LOGIN_TIMEOUT_KEY = "loginTimeout"
const val TEST_POSTGRES_SOCKET_TIMEOUT_KEY = "socketTimeout"
const val TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS = 10
const val TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS = 30
const val TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS = 300
const val TEST_POSTGRES_CONNECTION_MAX_ATTEMPTS = 3
private const val POSTGRES_CONNECTION_UNABLE_SQL_STATE = "08001"
