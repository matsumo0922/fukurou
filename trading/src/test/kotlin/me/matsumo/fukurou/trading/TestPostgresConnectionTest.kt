package me.matsumo.fukurou.trading

import org.testcontainers.containers.PostgreSQLContainer
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
class TestPostgresConnectionTest {
    @Test
    fun boundedContainerConfiguresDriverTimeoutParameters() {
        val parameters = InspectablePostgresContainer().configuredUrlParameters()

        assertEquals(TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_CONNECT_TIMEOUT_KEY])
        assertEquals(TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_SOCKET_TIMEOUT_KEY])
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
                "?connectTimeout=$DRIVER_CONTRACT_TIMEOUT_SECONDS&socketTimeout=$DRIVER_CONTRACT_TIMEOUT_SECONDS"
            val startedAt = System.nanoTime()

            val failure = try {
                assertFailsWith<SQLException> {
                    DriverManager.getConnection(jdbcUrl, "test", "test")
                }
            } finally {
                releaseServer.countDown()
            }
            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

            assertTrue(failure.hasCause<SocketTimeoutException>())
            assertTrue(elapsed < Duration.ofSeconds(DRIVER_CONTRACT_MAX_ELAPSED_SECONDS), "elapsed=$elapsed")
        }
    }

    @Test
    fun transientSocketFailureRetriesOnceBeforeReturningConnection() {
        var attempts = 0

        val connection = retryTransientTestPostgresConnection {
            attempts += 1
            if (attempts == 1) throw SQLException("transient", SocketTimeoutException("stale socket"))
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
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    return generateSequence(this) { throwable -> throwable.cause }.any { throwable -> throwable is T }
}

/** URL parameter を container 起動なしで観測する test double。 */
private class InspectablePostgresContainer : PostgreSQLContainer<InspectablePostgresContainer>("postgres:16-alpine") {
    init {
        configureBoundedTestJdbcConnections()
    }

    fun configuredUrlParameters(): Map<String, String> = urlParameters.toMap()
}

private const val DRIVER_CONTRACT_TIMEOUT_SECONDS = 1
private const val DRIVER_CONTRACT_MAX_ELAPSED_SECONDS = 5L
private const val SILENT_SERVER_RELEASE_SECONDS = 5L
