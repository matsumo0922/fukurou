package me.matsumo.fukurou

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.testcontainers.DockerClientFactory
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** application poolのcold initialization contractを検証する。 */
class DatabaseColdStartTest {
    @BeforeTest
    fun setUpAdmissionHealth() {
        resetAdmissionHealthForTest()
    }

    @AfterTest
    fun tearDownAdmissionHealth() {
        resetAdmissionHealthForTest()
    }

    @Test
    fun coldPoolStartsProductionApplicationAndBootstrapsRuntimeAndTradingSchemas() {
        if (!DockerClientFactory.instance().isDockerAvailable) return

        ColdStartSocketFactory.reset()
        ColdStartPostgresContainer().use { container ->
            container.start()
            val delayedUrl = container.jdbcUrl.withSocketFactory(ColdStartSocketFactory::class.java.name)
            val startedAt = System.nanoTime()
            val tradingConfig = me.matsumo.fukurou.trading.config.TradingBotConfig()
            val shutdownResult = ApplicationShutdownResultCapture()

            assertFalse(tradingConfig.daemon.enabled)
            assertFalse(tradingConfig.obsidian.enabled)

            testApplication {
                application {
                    module(
                        revision = COLD_START_REVISION,
                        tradingConfig = tradingConfig,
                        databaseConfig = DatabaseConfig(delayedUrl, container.username, container.password),
                        shutdownResultObserver = shutdownResult.observer,
                    )
                }

                val response = client.get("/revision")

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(COLD_START_REVISION, response.body<String>())
            }
            shutdownResult.assertSucceeded()
            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

            assertTrue(elapsed > Duration.ofMillis(DATABASE_CONNECTION_TIMEOUT_MILLIS))
            assertEquals(30_000L, DATABASE_INITIALIZATION_RETRY_WINDOW_MILLIS)
            assertEquals(1, countRows(container, "runtime_config_versions", "status = 'ACTIVE'"))
            assertEquals(1, countRows(container, "paper_account", "id = 1"))
        }
    }
}

private fun countRows(
    container: ColdStartPostgresContainer,
    table: String,
    condition: String,
): Int {
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table WHERE $condition").use { rows ->
                check(rows.next())
                return rows.getInt(1)
            }
        }
    }
}

/** PostgreSQL driverの最初のsocket connectだけを遅延させるtest factory。 */
class ColdStartSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = DelayedConnectSocket(delayNext.getAndSet(false))

    override fun createSocket(host: String, port: Int): Socket = delayedSocket(host, port)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = delayedSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress, port: Int): Socket = delayedSocket(host, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = delayedSocket(address, port, localAddress, localPort)

    private fun delayedSocket(host: String, port: Int): Socket {
        delayOnce()
        return Socket(host, port)
    }

    private fun delayedSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket {
        delayOnce()
        return Socket(host, port, localHost, localPort)
    }

    private fun delayedSocket(address: InetAddress, port: Int): Socket {
        delayOnce()
        return Socket(address, port)
    }

    private fun delayedSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket {
        delayOnce()
        return Socket(address, port, localAddress, localPort)
    }

    private fun delayOnce() {
        if (delayNext.getAndSet(false)) Thread.sleep(COLD_CONNECT_DELAY_MILLIS)
    }

    companion object {
        private val delayNext = AtomicBoolean(true)

        fun reset() {
            delayNext.set(true)
        }
    }
}

private class DelayedConnectSocket(private val delayed: Boolean) : Socket() {
    override fun connect(endpoint: SocketAddress?) {
        delay()
        super.connect(endpoint)
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        delay()
        super.connect(endpoint, timeout)
    }

    private fun delay() {
        if (delayed) Thread.sleep(COLD_CONNECT_DELAY_MILLIS)
    }
}

private fun String.withSocketFactory(factoryName: String): String {
    val separator = if ('?' in this) '&' else '?'
    return "$this${separator}socketFactory=$factoryName"
}

private class ColdStartPostgresContainer :
    BoundedTestPostgresContainer<ColdStartPostgresContainer>("postgres:16-alpine")

private const val COLD_START_REVISION = "cold-start-composition"
private const val COLD_CONNECT_DELAY_MILLIS = 750L
