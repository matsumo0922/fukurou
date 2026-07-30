package me.matsumo.fukurou.trading.testing

import org.junit.Assume.assumeTrue
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

/**
 * Docker daemon が無ければ test を skip として終了させる。
 *
 * skip は test report の skipped 件数に現れる。無条件 return で success として集計させないこと。
 *
 * [available] は判定を注入する seam で、既定値は実環境の Docker 可用性。
 * 契約テストが実行環境の Docker 有無に関わらず不在側の分岐を検証するために使う。
 */
fun requireTestDocker(available: Boolean = isTestDockerAvailable()) {
    assumeTrue(TEST_DOCKER_UNAVAILABLE_MESSAGE, available)
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

/**
 * JDBC URL の query parameter を decode して key ごとに集める。
 *
 * 同じ key が複数回現れた場合を検出できるよう値を list で返す。
 */
fun String.jdbcQueryParameters(): Map<String, List<String>> {
    return substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .filter(String::isNotBlank)
        .groupBy(
            keySelector = { parameter -> parameter.substringBefore('=').decodeQueryComponent() },
            valueTransform = { parameter ->
                parameter.substringAfter('=', missingDelimiterValue = "").decodeQueryComponent()
            },
        )
}

/**
 * JDBC URL の query parameter を key 単位で上書きする。
 *
 * [overrides] の値は decode 済みとして扱う（encode は本関数が行う）。
 */
fun String.withJdbcQueryParameters(overrides: Map<String, String>): String {
    val baseUrl = substringBefore('?')
    val parameters = jdbcQueryParameters()
        .mapValuesTo(linkedMapOf()) { (_, values) -> values.last() }

    parameters.putAll(overrides)

    if (parameters.isEmpty()) return baseUrl

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

/** test 用 PostgreSQL container の image。production の PostgreSQL 16 に合わせる。 */
const val TEST_POSTGRES_IMAGE = "postgres:16-alpine"
const val TEST_DOCKER_UNAVAILABLE_MESSAGE = "Docker daemon is unavailable; skipping the Testcontainers test."
const val TEST_POSTGRES_CONNECT_TIMEOUT_KEY = "connectTimeout"
const val TEST_POSTGRES_LOGIN_TIMEOUT_KEY = "loginTimeout"
const val TEST_POSTGRES_SOCKET_TIMEOUT_KEY = "socketTimeout"
const val TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS = 10
const val TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS = 30
const val TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS = 300
const val TEST_POSTGRES_CONNECTION_MAX_ATTEMPTS = 3
private const val POSTGRES_CONNECTION_UNABLE_SQL_STATE = "08001"
