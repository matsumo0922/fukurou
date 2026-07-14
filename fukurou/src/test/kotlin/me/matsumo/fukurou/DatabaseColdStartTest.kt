package me.matsumo.fukurou

import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/** application poolのcold initialization contractを検証する。 */
class DatabaseColdStartTest {
    @Test
    fun coldPoolWaitsPastAcquisitionTimeoutAndBootstrapsSchema() {
        if (!DockerClientFactory.instance().isDockerAvailable) return

        ColdStartSocketFactory.reset()
        ColdStartPostgresContainer().use { container ->
            container.start()
            val delayedUrl = container.jdbcUrl.withSocketFactory(ColdStartSocketFactory::class.java.name)
            val startedAt = System.nanoTime()

            createDataSource(DatabaseConfig(delayedUrl, container.username, container.password)).use { dataSource ->
                val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

                assertTrue(elapsed > Duration.ofMillis(DATABASE_CONNECTION_TIMEOUT_MILLIS))
                assertEquals(DATABASE_INITIALIZATION_TIMEOUT_MILLIS, dataSource.initializationFailTimeout)
                assertEquals(DATABASE_CONNECTION_TIMEOUT_MILLIS, dataSource.connectionTimeout)
                TradingPersistenceBootstrap(ExposedDatabase.connect(dataSource), Clock.systemUTC())
                    .ensureSchema()
                    .getOrThrow()
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

private class ColdStartPostgresContainer : PostgreSQLContainer<ColdStartPostgresContainer>("postgres:16-alpine")

private const val COLD_CONNECT_DELAY_MILLIS = 750L
