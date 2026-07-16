package me.matsumo.fukurou.trading

import org.testcontainers.containers.PostgreSQLContainer
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.sql.SQLException

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

/** test body 開始前の一時的な PostgreSQL 接続失敗だけを最大 2 回再試行する。 */
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
    val causes = generateSequence(this) { throwable -> throwable.cause }.toList()
    val sqlStates = causes
        .filterIsInstance<SQLException>()
        .mapNotNull(SQLException::getSQLState)

    if (sqlStates.isNotEmpty()) return sqlStates.all { sqlState -> sqlState == POSTGRES_CONNECTION_UNABLE_SQL_STATE }

    return causes.any { throwable -> throwable is SocketTimeoutException || throwable is ConnectException }
}

internal const val TEST_POSTGRES_CONNECT_TIMEOUT_KEY = "connectTimeout"
internal const val TEST_POSTGRES_LOGIN_TIMEOUT_KEY = "loginTimeout"
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_KEY = "socketTimeout"
internal const val TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS = 10
internal const val TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS = 30
internal const val TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS = 300
internal const val TEST_POSTGRES_CONNECTION_MAX_ATTEMPTS = 3
private const val POSTGRES_CONNECTION_UNABLE_SQL_STATE = "08001"
