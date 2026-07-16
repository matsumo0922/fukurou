package me.matsumo.fukurou

import org.testcontainers.containers.PostgreSQLContainer

/** Testcontainers PostgreSQL の JDBC 接続を有限時間に制限する。 */
internal fun PostgreSQLContainer<*>.configureBoundedTestJdbcConnections() {
    withUrlParam(TEST_POSTGRES_CONNECT_TIMEOUT_KEY, TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS.toString())
    withUrlParam(TEST_POSTGRES_SOCKET_TIMEOUT_KEY, TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS.toString())
}

internal const val TEST_POSTGRES_CONNECT_TIMEOUT_KEY = "connectTimeout"
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_KEY = "socketTimeout"
internal const val TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS = 10
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS = 30
