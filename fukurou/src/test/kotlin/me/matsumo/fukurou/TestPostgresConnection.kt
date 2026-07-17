package me.matsumo.fukurou

import org.testcontainers.containers.PostgreSQLContainer

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

internal const val TEST_POSTGRES_CONNECT_TIMEOUT_KEY = "connectTimeout"
internal const val TEST_POSTGRES_LOGIN_TIMEOUT_KEY = "loginTimeout"
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_KEY = "socketTimeout"
internal const val TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS = 10
internal const val TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS = 30
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS = 300
