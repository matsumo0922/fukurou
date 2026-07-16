package me.matsumo.fukurou.trading

import org.testcontainers.containers.PostgreSQLContainer
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Testcontainers PostgreSQL の JDBC 接続を有限時間に制限する。 */
internal fun PostgreSQLContainer<*>.configureBoundedTestJdbcConnections() {
    withUrlParam(TEST_POSTGRES_CONNECT_TIMEOUT_KEY, TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS.toString())
    withUrlParam(TEST_POSTGRES_SOCKET_TIMEOUT_KEY, TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS.toString())
}

/** test body 開始前の一時的な PostgreSQL 接続失敗だけを 1 回再試行する。 */
internal fun <T> retryTransientTestPostgresConnection(connect: () -> T): T {
    repeat(TEST_POSTGRES_CONNECTION_MAX_ATTEMPTS - 1) {
        try {
            return connect()
        } catch (failure: Exception) {
            if (!failure.hasTransientPostgresConnectionCause()) throw failure
        }
    }

    return connect()
}

private fun Throwable.hasTransientPostgresConnectionCause(): Boolean {
    return generateSequence(this) { throwable -> throwable.cause }.any { throwable ->
        throwable is SocketTimeoutException || throwable is ConnectException
    }
}

internal const val TEST_POSTGRES_CONNECT_TIMEOUT_KEY = "connectTimeout"
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_KEY = "socketTimeout"
internal const val TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS = 10
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS = 30
internal const val TEST_POSTGRES_CONNECTION_MAX_ATTEMPTS = 2
