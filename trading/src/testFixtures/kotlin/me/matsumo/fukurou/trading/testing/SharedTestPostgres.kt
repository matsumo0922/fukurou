package me.matsumo.fukurou.trading.testing

import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicLong

/**
 * test class 全体で 1 個の PostgreSQL container を共有し、test method ごとに専用 database を貸す。
 *
 * container を per-test で start/stop すると、220 test で 220 回の起動になり実行時間と Docker 資源を消耗する。
 * database 単位で貸し出すことで、schema を破壊する test や migration 前の状態を要求する test も
 * 互いに影響せず動く。
 *
 * serial 実行を前提とする。`pg_current_wal_insert_lsn()` の WAL 位置と `pg_locks` view は
 * cluster 全体を対象とするため、database を分けても隔離されない。
 *
 * @param container 共有する container。呼び出し側が生存期間を管理する
 */
class SharedTestPostgres<SELF : BoundedTestPostgresContainer<SELF>>(
    private val container: BoundedTestPostgresContainer<SELF>,
) {
    private val databaseCounter = AtomicLong()

    /** container の administrative database を指す JDBC URL。 */
    val adminJdbcUrl: String get() = container.jdbcUrl

    /** container の接続 user。 */
    val username: String get() = container.username

    /** container の接続 password。 */
    val password: String get() = container.password

    /** container が出力した全ログ。共有 container では他 database の分も混ざる。 */
    val logs: String get() = container.logs

    /**
     * 専用 database を作り、[block] に貸してから破棄する。
     *
     * [block] が例外を投げても database は破棄される。破棄には `WITH (FORCE)` を使うため、
     * test が閉じ忘れた接続や production factory が内部で持つ pool が残っていても失敗しない。
     */
    fun <T> withDatabase(block: (TestPostgresDatabase) -> T): T {
        val databaseName = nextDatabaseName()

        executeOnAdminDatabase("CREATE DATABASE \"$databaseName\"")

        return try {
            block(TestPostgresDatabase(jdbcUrlFor(databaseName), username, password))
        } finally {
            executeOnAdminDatabase("DROP DATABASE IF EXISTS \"$databaseName\" WITH (FORCE)")
        }
    }

    /** 現在 container 上に存在する test database 名を返す。cleanup の検証に使う。 */
    fun listTestDatabases(): List<String> {
        return DriverManager.getConnection(adminJdbcUrl, username, password).use { connection ->
            connection.prepareStatement(LIST_TEST_DATABASES_SQL).use { statement ->
                statement.executeQuery().use(::readDatabaseNames)
            }
        }
    }

    private fun nextDatabaseName(): String = "$DATABASE_NAME_PREFIX${databaseCounter.incrementAndGet()}"

    private fun jdbcUrlFor(databaseName: String): String {
        val base = adminJdbcUrl.substringBefore('?')
        val query = adminJdbcUrl.substringAfter('?', missingDelimiterValue = "")
        val rebased = base.replaceAfterLast('/', databaseName)

        return if (query.isEmpty()) rebased else "$rebased?$query"
    }

    // CREATE / DROP DATABASE は transaction 内で実行できないため、admin database へ別接続を張って autocommit で流す。
    private fun executeOnAdminDatabase(sql: String) {
        DriverManager.getConnection(adminJdbcUrl, username, password).use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }
}

/**
 * test method が借りた専用 database の接続情報。
 *
 * @param jdbcUrl この database を指す JDBC URL
 * @param username 接続 user
 * @param password 接続 password
 */
class TestPostgresDatabase(
    val jdbcUrl: String,
    val username: String,
    val password: String,
)

private fun readDatabaseNames(rows: ResultSet): List<String> {
    val names = mutableListOf<String>()

    while (rows.next()) names += rows.getString(1)

    return names
}

private const val DATABASE_NAME_PREFIX = "fukurou_test_"
private const val LIST_TEST_DATABASES_SQL =
    "SELECT datname FROM pg_database WHERE datname LIKE '$DATABASE_NAME_PREFIX%' ORDER BY datname"
