package me.matsumo.fukurou.trading.persistence

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicEntryIdentity
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicEntryUnavailableException
import me.matsumo.fukurou.trading.broker.StableRequestMutexRegistry
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PostgresStableRequestExecutionScopeTest {
    @Test
    fun `timeout after local lease handoff closes lease and registry entry`() = runBlocking {
        val dataSource = HikariDataSource()
        val registry = StableRequestMutexRegistry()
        val leasePublished = CompletableDeferred<Unit>()
        val releaseHandoff = CompletableDeferred<Unit>()
        val scope = PostgresStableRequestExecutionScope(
            dataSource = dataSource,
            mutexRegistry = registry,
            acquisitionTimeout = Duration.ofMillis(100),
            connectionBorrower = { error("connection must not be borrowed") },
            afterLocalLeasePublished = {
                leasePublished.complete(Unit)
                withContext(NonCancellable) { releaseHandoff.await() }
            },
        )

        try {
            val result = async {
                runCatching {
                    scope.withScope(identity("runner-place-v2-local-handoff")) { Unit }
                }
            }
            leasePublished.await()
            delay(150)
            releaseHandoff.complete(Unit)

            assertIs<AuthorizedAtomicEntryUnavailableException>(result.await().exceptionOrNull())
            assertEquals(0, registry.entryCount())
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `poll delay expiry is always typed before delay calculation`() {
        val nanoTimes = ArrayDeque(listOf(0L, 99_000_000L, 100_000_000L))
        val deadline = MonotonicDeadline.start(Duration.ofMillis(100)) { nanoTimes.removeFirst() }

        assertEquals(1L, deadline.requirePollDelayMillis(Duration.ofMillis(50)) { nanoTimes.removeFirst() })
        assertFailsWith<StableRequestLockTimeoutException> {
            deadline.requirePollDelayMillis(Duration.ofMillis(50)) { nanoTimes.removeFirst() }
        }
    }
}

private fun identity(clientRequestId: String): AuthorizedAtomicEntryIdentity {
    return AuthorizedAtomicEntryIdentity(
        clientRequestId = clientRequestId,
        intentId = UUID.randomUUID(),
        symbol = TradingSymbol.BTC,
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        orderType = OrderType.MARKET,
        sizeBtc = BigDecimal("0.01"),
        priceJpy = null,
        protectiveStopPriceJpy = BigDecimal("9000000"),
        takeProfitPriceJpy = BigDecimal("11000000"),
        estimatedWinProbability = BigDecimal("0.7"),
        tradeGroupId = null,
    )
}
