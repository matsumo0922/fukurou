package me.matsumo.fukurou.trading.testing

import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicLong

/**
 * 1 個の PostgreSQL container を共有し、test method ごとに専用 database を貸す。
 *
 * container を per-test で start/stop すると test 数と同じ回数の起動になり、実行時間と Docker 資源を消耗する。
 * database 単位で貸し出すことで、schema を破壊する test や migration 前の状態を要求する test も
 * 互いに影響せず動く。
 *
 * serial 実行を前提とする。`pg_current_wal_insert_lsn()` の WAL 位置と `pg_locks` view は
 * cluster 全体を対象とするため、database を分けても隔離されない。
 *
 * @param container 共有する container。起動と停止は呼び出し側が管理する
 */
class SharedTestPostgres(
    private val container: BoundedTestPostgresContainer<*>,
) {
    private val databaseCounter = AtomicLong()

    /** container が出力した全ログ。共有 container では他 database の分も混ざる。 */
    val logs: String get() = container.logs

    // 共有 container の default database を指す。lease へは渡さない（データ漏れの経路になるため private に閉じる）。
    private val adminJdbcUrl: String get() = container.jdbcUrl
    private val username: String get() = container.username
    private val password: String get() = container.password

    /**
     * 専用 database を作り、[block] に貸してから破棄する。
     *
     * [block] が例外を投げても database は破棄される。破棄には `WITH (FORCE)` を使うため、
     * test が閉じ忘れた接続や production factory が内部で持つ pool が残っていても失敗しない。
     * 破棄自体が失敗した場合は [block] の例外を優先し、破棄の失敗は suppressed として付ける。
     */
    suspend fun <T> withDatabase(block: suspend (TestPostgresDatabase) -> T): T {
        val databaseName = nextDatabaseName()

        executeOnAdminDatabase("CREATE DATABASE \"$databaseName\" TEMPLATE template0")

        var leaseFailure: Throwable? = null

        return try {
            block(TestPostgresDatabase(jdbcUrlFor(databaseName), username, password))
        } catch (failure: Throwable) {
            leaseFailure = failure
            throw failure
        } finally {
            dropDatabase(databaseName, leaseFailure)
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

    // test 本体の失敗が cleanup の失敗に置き換わると、落ちた assertion が分からなくなる。
    private fun dropDatabase(databaseName: String, leaseFailure: Throwable?) {
        val dropFailure = runCatching {
            executeOnAdminDatabase("DROP DATABASE IF EXISTS \"$databaseName\" WITH (FORCE)")
        }.exceptionOrNull() ?: return

        if (leaseFailure == null) throw dropFailure

        leaseFailure.addSuppressed(dropFailure)
    }

    // timeout などの query parameter は container 生成時に設定されるため、lease URL へそのまま引き継ぐ。
    private fun jdbcUrlFor(databaseName: String): String {
        val base = adminJdbcUrl.substringBefore('?').replaceAfterLast('/', databaseName)
        val parameters = adminJdbcUrl.jdbcQueryParameters().mapValues { (_, values) -> values.last() }

        return base.withJdbcQueryParameters(parameters)
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
 * test class 単位で共有する [SharedTestPostgres] を遅延生成して保持する。
 *
 * container の停止は shutdown hook で行うため、生存範囲は test worker JVM の終了までとなる。
 * class 終了時に停止するには `RunListener` か `@ClassRule` が必要で、既存 test の構造を変える必要があるため採らない。
 * hook を使うのは、ryuk が無効な環境（`TESTCONTAINERS_RYUK_DISABLED=true`）でも container を残さないため。
 */
class LazySharedTestPostgres {
    private val lock = Any()

    @Volatile
    private var holder: SharedTestPostgres? = null

    /** 共有 container を必要なら起動して返す。 */
    fun get(): SharedTestPostgres {
        holder?.let { started -> return started }

        return synchronized(lock) {
            holder ?: run {
                val container = SharedPostgresContainer()
                container.start()
                Runtime.getRuntime().addShutdownHook(Thread { runCatching { container.stop() } })
                SharedTestPostgres(container).also { started -> holder = started }
            }
        }
    }
}

/** 共有 test PostgreSQL container。 */
private class SharedPostgresContainer :
    BoundedTestPostgresContainer<SharedPostgresContainer>(TEST_POSTGRES_IMAGE)

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
