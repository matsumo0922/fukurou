package me.matsumo.fukurou.trading.persistence

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Savepoint
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** gate-shadow capture の savepoint failure contract を固定するテスト。 */
class GateShadowSavepointTest {

    @Test
    fun capture_read_failure_is_skipped_after_successful_rollback() {
        val rollbackCalled = AtomicBoolean()
        val connection = savepointConnection(onRollback = { rollbackCalled.set(true) })

        val result = connection.captureGateShadowReadWithSavepoint("test capture") {
            throw SQLException("synthetic capture failure")
        }

        assertNull(result)
        assertTrue(rollbackCalled.get())
    }

    @Test
    fun rollback_failure_rethrows_capture_failure_with_suppressed_failure() {
        val captureFailure = SQLException("synthetic capture failure")
        val rollbackFailure = SQLException("synthetic rollback failure")
        val connection = savepointConnection(onRollback = { throw rollbackFailure })

        val thrown = assertFailsWith<SQLException> {
            connection.captureGateShadowReadWithSavepoint("test capture") {
                throw captureFailure
            }
        }

        assertSame(captureFailure, thrown)
        assertSame(rollbackFailure, thrown.suppressed.single())
    }

    @Test
    fun savepoint_creation_failure_is_rethrown() {
        val savepointFailure = SQLException("synthetic savepoint failure")
        val connection = savepointConnection(onSetSavepoint = { throw savepointFailure })

        val thrown = assertFailsWith<SQLException> {
            connection.captureGateShadowReadWithSavepoint("test capture") { "unreachable" }
        }

        assertSame(savepointFailure, thrown)
    }

    @Test
    fun release_failure_keeps_observation_and_does_not_rollback_cancel() {
        val connection = savepointConnection(onReleaseSavepoint = { throw SQLException("synthetic release failure") })

        val result = connection.captureGateShadowReadWithSavepoint("test capture") { "captured" }

        assertSame("captured", result)
    }

    private fun savepointConnection(
        onSetSavepoint: () -> Unit = {},
        onRollback: () -> Unit = {},
        onReleaseSavepoint: () -> Unit = {},
    ): Connection {
        val savepoint = TestSavepoint

        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "setSavepoint" -> {
                    onSetSavepoint()
                    savepoint
                }

                "rollback" -> {
                    onRollback()
                    Unit
                }

                "releaseSavepoint" -> {
                    onReleaseSavepoint()
                    Unit
                }

                else -> error("Unexpected Connection method: ${method.name}")
            }
        } as Connection
    }

    private data object TestSavepoint : Savepoint {
        override fun getSavepointId(): Int = 1

        override fun getSavepointName(): String = "gate_shadow_test"
    }
}
