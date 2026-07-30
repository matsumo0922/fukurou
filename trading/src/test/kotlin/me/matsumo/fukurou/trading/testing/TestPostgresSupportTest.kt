package me.matsumo.fukurou.trading.testing

import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Testcontainers PostgreSQL connection timeout contract。 */
class TestPostgresSupportTest {
    @Test
    fun boundedContainerConfiguresDriverTimeoutParameters() {
        val parameters = InspectablePostgresContainer().configuredUrlParameters()

        assertEquals(TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_CONNECT_TIMEOUT_KEY])
        assertEquals(TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_LOGIN_TIMEOUT_KEY])
        assertEquals(TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_SOCKET_TIMEOUT_KEY])
    }

    // Docker 不在を success として集計させないための契約。silent pass（無条件 return）への回帰を防ぐ。
    @Test
    fun dockerGuardRaisesAssumptionFailureWhenDaemonIsUnavailable() {
        val failure = assertFailsWith<AssumptionViolatedException> {
            assumeTrue("Docker daemon is unavailable; skipping the Testcontainers test.", false)
        }

        assertTrue(failure.message.orEmpty().contains("Docker daemon is unavailable"))
    }

    // requireTestDocker() の分岐が isTestDockerAvailable() の結果に一致することを、実行環境の両側で確認する。
    @Test
    fun dockerGuardMatchesDaemonAvailability() {
        val available = isTestDockerAvailable()

        if (available) {
            requireTestDocker()
            return
        }

        assertFailsWith<AssumptionViolatedException> { requireTestDocker() }
    }

    @Test
    fun queryParameterOverrideReplacesTimeoutsWithoutBreakingUrlStructure() {
        val url = "jdbc:postgresql://localhost:5432/test" +
            "?connectTimeout=10&loginTimeout=30&socketTimeout=300&applicationName=fukurou%20test"

        val overridden = url.withJdbcQueryParameters(
            mapOf(
                TEST_POSTGRES_CONNECT_TIMEOUT_KEY to "2",
                TEST_POSTGRES_SOCKET_TIMEOUT_KEY to "2",
            ),
        )

        assertEquals(1, overridden.count { character -> character == '?' })
        assertEquals(1, overridden.split('&').count { parameter -> parameter.contains("connectTimeout=2") })
        assertEquals(1, overridden.split('&').count { parameter -> parameter.contains("socketTimeout=2") })
        assertEquals(1, overridden.split('&').count { parameter -> parameter.contains("loginTimeout=30") })
        assertEquals(true, overridden.endsWith("applicationName=fukurou%20test"))
    }

    @Test
    fun silentAuthenticationSocketFailsWithinConfiguredReadTimeout() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { serverSocket ->
            val releaseServer = CountDownLatch(1)
            thread(isDaemon = true, name = "silent-postgres-auth-server") {
                serverSocket.accept().use {
                    releaseServer.await(SILENT_SERVER_RELEASE_SECONDS, TimeUnit.SECONDS)
                }
            }
            val jdbcUrl = "jdbc:postgresql://${serverSocket.inetAddress.hostAddress}:${serverSocket.localPort}/test" +
                "?connectTimeout=$DRIVER_CONTRACT_TIMEOUT_SECONDS&loginTimeout=$DRIVER_CONTRACT_TIMEOUT_SECONDS" +
                "&socketTimeout=$TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS"
            val startedAt = System.nanoTime()

            val failure = try {
                assertFailsWith<SQLException> {
                    DriverManager.getConnection(jdbcUrl, "test", "test")
                }
            } finally {
                releaseServer.countDown()
            }
            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

            assertEquals(POSTGRES_CONNECTION_UNABLE_SQL_STATE, failure.sqlState)
            assertTrue(elapsed < Duration.ofSeconds(DRIVER_CONTRACT_MAX_ELAPSED_SECONDS), "elapsed=$elapsed")
        }
    }

    @Test
    fun transientSocketFailureRetriesWithinMaximumBeforeReturningConnection() {
        var attempts = 0

        val connection = retryTransientTestPostgresConnection {
            attempts += 1
            if (attempts < TEST_POSTGRES_CONNECTION_MAX_ATTEMPTS) {
                throw SQLException("transient", SocketTimeoutException("stale socket"))
            }
            "connected"
        }

        assertEquals("connected", connection)
        assertEquals(TEST_POSTGRES_CONNECTION_MAX_ATTEMPTS, attempts)
    }

    @Test
    fun nonNetworkFailureIsNotRetried() {
        var attempts = 0

        assertFailsWith<SQLException> {
            retryTransientTestPostgresConnection {
                attempts += 1
                throw SQLException("authentication failed")
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun connectionSqlStateFailureIsRetried() {
        var attempts = 0

        val connection = retryTransientTestPostgresConnection {
            attempts += 1
            if (attempts == 1) throw SQLException("connection unavailable", POSTGRES_CONNECTION_UNABLE_SQL_STATE)
            "connected"
        }

        assertEquals("connected", connection)
        assertEquals(2, attempts)
    }

    @Test
    fun connectionRejectedSqlStateIsNotRetried() {
        var attempts = 0

        assertFailsWith<SQLException> {
            retryTransientTestPostgresConnection {
                attempts += 1
                throw SQLException(
                    "connection rejected",
                    POSTGRES_CONNECTION_REJECTED_SQL_STATE,
                    ConnectException("nested transport detail"),
                )
            }
        }

        assertEquals(1, attempts)
    }
}

/** URL parameter を container 起動なしで観測する test double。 */
private class InspectablePostgresContainer :
    BoundedTestPostgresContainer<InspectablePostgresContainer>("postgres:16-alpine") {
    fun configuredUrlParameters(): Map<String, String> = urlParameters.toMap()
}

private const val DRIVER_CONTRACT_TIMEOUT_SECONDS = 1
private const val DRIVER_CONTRACT_MAX_ELAPSED_SECONDS = 5L
private const val SILENT_SERVER_RELEASE_SECONDS = 5L
private const val POSTGRES_CONNECTION_UNABLE_SQL_STATE = "08001"
private const val POSTGRES_CONNECTION_REJECTED_SQL_STATE = "08004"
