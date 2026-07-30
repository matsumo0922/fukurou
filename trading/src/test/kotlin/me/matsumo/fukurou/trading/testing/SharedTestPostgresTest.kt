package me.matsumo.fukurou.trading.testing

import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 共有 container + database per test の貸し出しと破棄の契約。 */
class SharedTestPostgresTest {
    private lateinit var container: SharedTestPostgresProbeContainer
    private lateinit var shared: SharedTestPostgres<SharedTestPostgresProbeContainer>

    @BeforeTest
    fun startSharedContainer() {
        requireTestDocker()
        container = SharedTestPostgresProbeContainer()
        container.start()
        shared = SharedTestPostgres(container)
    }

    @AfterTest
    fun stopSharedContainer() {
        if (::container.isInitialized) container.stop()
    }

    @Test
    fun eachLeaseGetsAnEmptyDatabaseThatDoesNotSeePriorWrites() {
        shared.withDatabase { database ->
            execute(database, "CREATE TABLE leaked (id integer)")
        }

        shared.withDatabase { database ->
            val exists = queryBoolean(database, "SELECT to_regclass('public.leaked') IS NOT NULL")

            assertEquals(false, exists, "前の test の table が次の database に見えてはならない")
        }
    }

    @Test
    fun leasesUseDistinctDatabaseNames() {
        val first = shared.withDatabase { database -> database.jdbcUrl }
        val second = shared.withDatabase { database -> database.jdbcUrl }

        assertNotEquals(first, second)
    }

    @Test
    fun databaseIsDroppedAfterTheLeaseCompletes() {
        val leased = shared.withDatabase { database -> databaseNameOf(database.jdbcUrl) }

        assertTrue(
            leased !in shared.listTestDatabases(),
            "lease 終了後に database が残っている: $leased",
        )
    }

    // 例外で中断しても cleanup が走ることを保証する。テスト本体の行儀に cleanup を依存させないため。
    @Test
    fun databaseIsDroppedEvenWhenTheLeaseBodyThrows() {
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
    fun databaseIsDroppedWhileAConnectionIsStillOpen() {
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

    @Test
    fun leasedUrlKeepsTheBoundedTimeoutParameters() {
        shared.withDatabase { database ->
            assertTrue(database.jdbcUrl.contains("$TEST_POSTGRES_CONNECT_TIMEOUT_KEY="))
            assertTrue(database.jdbcUrl.contains("$TEST_POSTGRES_LOGIN_TIMEOUT_KEY="))
            assertTrue(database.jdbcUrl.contains("$TEST_POSTGRES_SOCKET_TIMEOUT_KEY="))
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
}

/** 共有 container 契約の検証用 container。 */
private class SharedTestPostgresProbeContainer :
    BoundedTestPostgresContainer<SharedTestPostgresProbeContainer>("postgres:16-alpine")
