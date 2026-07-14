package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.market.IngressOperationDeadline
import me.matsumo.fukurou.trading.market.MarketStreamIdentity
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class ExposedDurableMarketEventIngressTest {
    @Test
    fun deadlineCoversPoolStatementAuthorityCommitRollbackUnlockAndClose() {
        DeadlineStage.entries.forEach { stage ->
            val controller = DeadlineJdbcController(stage)
            val repository = ExposedDurableMarketEventIngress(controller.dataSource())

            val elapsed = measureTime {
                val result = kotlinx.coroutines.runBlocking {
                    repository.begin(
                        UUID.randomUUID(),
                        MarketStreamIdentity("GMO_COIN", "BTC_JPY", "TRADES"),
                        IngressOperationDeadline.start(Duration.ofMillis(350)),
                    )
                }
                assertTrue(result.isFailure, stage.name)
            }

            assertTrue(controller.stageEntered.await(1, TimeUnit.SECONDS), stage.name)
            assertTrue(elapsed < kotlin.time.Duration.parse("1s"), stage.name)
            assertEquals(0, controller.activeWorkers.get(), stage.name)
            assertEquals(stage != DeadlineStage.POOL, controller.abortCalled.get(), stage.name)
        }
    }
}

/** deadline faultを注入するJDBC lifecycle位置。 */
private enum class DeadlineStage { POOL, STATEMENT, AUTHORITY, COMMIT, ROLLBACK, UNLOCK, CLOSE }

private class DeadlineJdbcController(private val stage: DeadlineStage) {
    val stageEntered = CountDownLatch(1)
    val activeWorkers = AtomicInteger(0)
    val abortCalled = AtomicBoolean(false)
    private val aborted = CountDownLatch(1)

    fun dataSource(): DataSource {
        val connection = connection()
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getConnection" -> {
                    activeWorkers.incrementAndGet()
                    try {
                        if (stage == DeadlineStage.POOL) blockUntilInterrupted() else connection
                    } finally {
                        if (stage == DeadlineStage.POOL) activeWorkers.decrementAndGet()
                    }
                }
                else -> defaultValue(method.returnType)
            }
        } as DataSource
    }

    private fun connection(): Connection {
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> statement(args?.first() as String)
                "setAutoCommit", "setNetworkTimeout" -> Unit
                "commit" -> if (stage == DeadlineStage.COMMIT) blockUntilAbort() else Unit
                "rollback" -> if (stage == DeadlineStage.ROLLBACK) blockUntilAbort() else Unit
                "abort" -> {
                    abortCalled.set(true)
                    aborted.countDown()
                }
                "close" -> {
                    try {
                        if (stage == DeadlineStage.CLOSE) blockUntilAbort()
                    } finally {
                        activeWorkers.decrementAndGet()
                    }
                }
                "isClosed" -> false
                else -> defaultValue(method.returnType)
            }
        } as Connection
    }

    private fun statement(sql: String): PreparedStatement {
        return Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "executeQuery" -> {
                    when {
                        stage == DeadlineStage.STATEMENT && sql.contains("set_config") -> blockUntilAbort()
                        stage == DeadlineStage.AUTHORITY && sql.contains("pg_try_advisory_lock") -> blockUntilAbort()
                        stage == DeadlineStage.UNLOCK && sql.contains("pg_advisory_unlock") -> blockUntilAbort()
                    }
                    resultSet()
                }
                "executeUpdate" -> if (stage == DeadlineStage.ROLLBACK) throw SQLException("mutation fault") else 1
                "close", "setObject", "setLong", "setString" -> Unit
                else -> defaultValue(method.returnType)
            }
        } as PreparedStatement
    }

    private fun resultSet(): ResultSet {
        val next = AtomicBoolean(true)
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, _ ->
            when (method.name) {
                "next" -> next.getAndSet(false)
                "getBoolean" -> true
                "close" -> Unit
                else -> defaultValue(method.returnType)
            }
        } as ResultSet
    }

    private fun blockUntilAbort() {
        stageEntered.countDown()
        aborted.await(2, TimeUnit.SECONDS)
    }

    private fun blockUntilInterrupted(): Connection {
        stageEntered.countDown()
        try {
            CountDownLatch(1).await()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SQLException("pool acquisition interrupted", failure)
        }
        error("unreachable")
    }
}

private fun defaultValue(type: Class<*>): Any? = when (type) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    else -> null
}
