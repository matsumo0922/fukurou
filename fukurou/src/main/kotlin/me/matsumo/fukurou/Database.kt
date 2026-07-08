package me.matsumo.fukurou

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * DB_URL 環境変数名。
 */
private const val DB_URL_ENV = "DB_URL"

/**
 * DB_USER 環境変数名。
 */
private const val DB_USER_ENV = "DB_USER"

/**
 * DB_PASSWORD 環境変数名。
 */
private const val DB_PASSWORD_ENV = "DB_PASSWORD"

/**
 * readiness で実行する最小 SQL。
 */
private const val READINESS_QUERY = "SELECT 1"

/**
 * readiness SQL の期待値。
 */
private const val READINESS_EXPECTED_VALUE = 1

/**
 * データベース接続設定。環境変数から組み立てる。
 *
 * @param url JDBC URL
 * @param user 接続ユーザ
 * @param password 接続パスワード
 */
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    companion object {
        /**
         * 環境変数 DB_URL / DB_USER / DB_PASSWORD から設定を読む。
         * いずれかが未設定なら null を返し、DB 未構成として扱う。
         */
        fun fromEnv(): DatabaseConfig? {
            val url = System.getenv(DB_URL_ENV)
            val user = System.getenv(DB_USER_ENV)
            val password = System.getenv(DB_PASSWORD_ENV)

            val hasMissingDatabaseConfig = listOf(url, user, password).any { value -> value.isNullOrBlank() }

            if (hasMissingDatabaseConfig) {
                return null
            }

            return DatabaseConfig(url, user, password)
        }
    }
}

/**
 * HikariCP の接続プールを生成する。
 * DB 起動前でも例外を投げないよう初期接続検証を無効化し、接続は遅延確立とする。
 */
fun createDataSource(config: DatabaseConfig): HikariDataSource {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = config.url
        username = config.user
        password = config.password
        maximumPoolSize = 4
        initializationFailTimeout = -1L
    }

    return HikariDataSource(hikariConfig)
}

/**
 * 共有 DataSource から readiness probe を構築する。
 * DB 未構成なら常に not-ready を返す。
 */
fun databaseReadinessProbe(dataSource: HikariDataSource?): ReadinessProbe {
    if (dataSource == null) {
        return ReadinessProbe { false }
    }

    val database = ExposedDatabase.connect(dataSource)

    return databaseReadinessProbe(database)
}

/**
 * 共有 Exposed database から readiness probe を構築する。
 */
fun databaseReadinessProbe(database: ExposedDatabase?): ReadinessProbe {
    if (database == null) {
        return ReadinessProbe { false }
    }

    return ReadinessProbe { database.isReachableByExposed() }
}

/**
 * Exposed transaction 経由で DB への到達性を確認する。
 */
private suspend fun ExposedDatabase.isReachableByExposed(): Boolean = withContext(Dispatchers.IO) {
    runCatching { pingOnce() }.isSuccess
}

/**
 * Exposed transaction 内で `SELECT 1` を 1 回実行する。
 */
private fun ExposedDatabase.pingOnce() {
    exposedTransaction(this) {
        val selectedOne = jdbcConnection().createStatement().use { statement ->
            statement.executeQuery(READINESS_QUERY).use { resultSet ->
                resultSet.next() && resultSet.getInt(1) == READINESS_EXPECTED_VALUE
            }
        }

        require(selectedOne) { "readiness query did not return 1" }
    }
}

/**
 * Exposed transaction が持つ JDBC connection を返す。
 */
private fun JdbcTransaction.jdbcConnection(): java.sql.Connection {
    return connection.connection as java.sql.Connection
}
