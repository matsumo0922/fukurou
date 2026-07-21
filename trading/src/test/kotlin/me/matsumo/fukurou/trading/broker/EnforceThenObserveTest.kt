package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * enforceThenObserve の例外伝播の順序を検証するテスト。
 *
 * 掃引（enforce）の例外を観測（observe）の失敗で覆い隠さないことを固定する。
 */
class EnforceThenObserveTest {

    @Test
    fun `returns the enforce result and observes once on success`() = runBlocking {
        var observed = 0

        val result = enforceThenObserve(enforce = { "ok" }, observe = { observed += 1 })

        assertEquals("ok", result)
        assertEquals(1, observed)
    }

    @Test
    fun `propagates the enforce error and suppresses the observe error`() = runBlocking {
        val enforceError = IllegalStateException("sweep failed")
        val observeError = RuntimeException("observation failed")

        val thrown = assertFailsWith<IllegalStateException> {
            enforceThenObserve(
                enforce = { throw enforceError },
                observe = { throw observeError },
            )
        }

        assertSame(enforceError, thrown, "The enforce error must not be masked by the observe error.")
        assertTrue(thrown.suppressed.contains(observeError), "The observe error must be suppressed onto the enforce error.")
    }

    @Test
    fun `still observes when enforce fails`() = runBlocking {
        var observed = 0

        assertFailsWith<IllegalStateException> {
            enforceThenObserve(
                enforce = { throw IllegalStateException("sweep failed") },
                observe = { observed += 1 },
            )
        }

        assertEquals(1, observed, "Observation must be attempted even when the sweep fails.")
    }

    @Test
    fun `does not observe when enforce is cancelled`() = runBlocking {
        var observed = 0

        assertFailsWith<CancellationException> {
            enforceThenObserve(
                enforce = { throw CancellationException("cancelled") },
                observe = { observed += 1 },
            )
        }

        assertEquals(0, observed, "Cancellation must propagate without attempting further work.")
    }

    @Test
    fun `propagates the observe error on the success path`() = runBlocking {
        val observeError = RuntimeException("observation failed")

        val thrown = assertFailsWith<RuntimeException> {
            enforceThenObserve(enforce = { "ok" }, observe = { throw observeError })
        }

        assertSame(observeError, thrown)
    }
}
