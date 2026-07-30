package me.matsumo.fukurou.trading.testing

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 共有 container + database per test の貸し出しと破棄の契約。 */
class SharedTestPostgresTest {
    private val shared: SharedTestPostgres
        get() = checkNotNull(sharedOrNull) { "shared container was not started." }

    @Test
    fun eachLeaseGetsAnEmptyDatabaseThatDoesNotSeePriorWrites() = runBlocking {
        shared.withDatabase { database ->
            execute(database, "CREATE TABLE leaked (id integer)")
        }

        shared.withDatabase { database ->
            val exists = queryBoolean(database, "SELECT to_regclass('public.leaked') IS NOT NULL")

            assertEquals(false, exists, "前の test の table が次の database に見えてはならない")
        }
    }

    @Test
    fun leasesUseDistinctDatabaseNames() = runBlocking {
        val first = shared.withDatabase { database -> database.jdbcUrl }
        val second = shared.withDatabase { database -> database.jdbcUrl }

        assertNotEquals(first, second)
    }

    @Test
    fun databaseIsDroppedAfterTheLeaseCompletes() = runBlocking {
        val leased = shared.withDatabase { database -> databaseNameOf(database.jdbcUrl) }

        assertTrue(
            leased !in shared.listTestDatabases(),
            "lease 終了後に database が残っている: $leased",
        )
    }

    // 例外で中断しても cleanup が走ることを保証する。テスト本体の行儀に cleanup を依存させないため。
    @Test
    fun databaseIsDroppedEvenWhenTheLeaseBodyThrows() = runBlocking {
        var leased: String? = null

        assertFailsWith<IllegalStateException> {
            shared.withDatabase { database ->
                leased = databaseNameOf(database.jdbcUrl)
                error("intentional failure inside the lease")
            }
        }

        assertTrue(
            leased != null && leased !in shared.listTestDatabases(),
            "例外で中断した lease の database が残っている: $leased",
        )
    }

    // production factory が内部で持つ pool のように、test から閉じられない接続が残っても DROP できること。
    @Test
    fun databaseIsDroppedWhileAConnectionIsStillOpen() = runBlocking {
        var leased: String? = null

        shared.withDatabase { database ->
            leased = databaseNameOf(database.jdbcUrl)
            // 意図的に close せず lease を抜ける。WITH (FORCE) が強制切断する。
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password)
        }

        assertTrue(
            leased != null && leased !in shared.listTestDatabases(),
            "接続が残っている database を破棄できていない: $leased",
        )
    }

    // spec は「正の整数値をそれぞれちょうど 1 個含む」を要求する。存在確認だけでは重複や値の逸脱を見逃す。
    @Test
    fun leasedUrlKeepsExactlyOneBoundedValuePerTimeoutParameter() = runBlocking {
        shared.withDatabase { database ->
            val parameters = database.jdbcUrl.jdbcQueryParameters()

            assertEquals(
                listOf(TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS.toString()),
                parameters[TEST_POSTGRES_CONNECT_TIMEOUT_KEY],
            )
            assertEquals(
                listOf(TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS.toString()),
                parameters[TEST_POSTGRES_LOGIN_TIMEOUT_KEY],
            )
            assertEquals(
                listOf(TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS.toString()),
                parameters[TEST_POSTGRES_SOCKET_TIMEOUT_KEY],
            )
        }
    }

    @Test
    fun leasedUrlKeepsASingleQuerySeparator() = runBlocking {
        shared.withDatabase { database ->
            assertEquals(1, database.jdbcUrl.count { character -> character == '?' })
        }
    }

    private fun execute(database: TestPostgresDatabase, sql: String) {
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun queryBoolean(database: TestPostgresDatabase, sql: String): Boolean {
        return DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next()) { "boolean query returned no rows." }
                    rows.getBoolean(1)
                }
            }
        }
    }

    private fun databaseNameOf(jdbcUrl: String): String {
        return jdbcUrl.substringBefore('?').substringAfterLast('/')
    }

    // container は class 単位で 1 個だけ起動する。test method ごとに起動すると、
    // 起動回数削減を検証する test 自身が起動回数を増やしてしまう。
    companion object {
        private var container: SharedTestPostgresProbeContainer? = null
        private var sharedOrNull: SharedTestPostgres? = null

        @BeforeClass
        @JvmStatic
        fun startSharedContainer() {
            requireTestDocker()
            container = SharedTestPostgresProbeContainer().also { probe ->
                probe.start()
                sharedOrNull = SharedTestPostgres(probe)
            }
        }

        // stop() の失敗で class 全体を failure にしないよう包む。test の結果を cleanup が上書きしない。
        @AfterClass
        @JvmStatic
        fun stopSharedContainer() {
            val probe = container

            container = null
            sharedOrNull = null

            runCatching { probe?.stop() }
        }
    }
}

/** 共有 container 契約の検証用 container。 */
private class SharedTestPostgresProbeContainer :
    BoundedTestPostgresContainer<SharedTestPostgresProbeContainer>(TEST_POSTGRES_IMAGE)
