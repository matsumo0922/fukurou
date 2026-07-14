package me.matsumo.fukurou.trading.decision.identity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.broker.InMemoryPaperLedgerRepository
import me.matsumo.fukurou.trading.broker.PaperAccountConfig
import me.matsumo.fukurou.trading.broker.toInitialAccountSnapshot
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.risk.InMemoryAccountStateBoundary
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionAccountSnapshotReaderTest {
    @Test
    fun `in memory capture uses one bounded ledger snapshot`() = runBlocking {
        val observedAt = Instant.parse("2026-07-13T00:00:00Z")
        val boundary = InMemoryAccountStateBoundary()
        val ledger = InMemoryPaperLedgerRepository(
            accountSnapshot = PaperAccountConfig().toInitialAccountSnapshot(),
            accountUpdatedAt = observedAt,
            positions = List(MAX_POSITION_QUERY_ROWS) { index -> position(index) },
            openOrders = List(MAX_ORDER_QUERY_ROWS) { index -> order(index) },
            accountStateBoundary = boundary,
        )
        val reader = InMemoryDecisionAccountSnapshotReader(
            ledgerRepository = ledger,
            riskStateRepository = InMemoryRiskStateRepository(accountStateBoundary = boundary),
        )

        val snapshot = reader.read().getOrThrow()

        assertEquals(MAX_MATERIAL_POSITIONS, snapshot.positions.size)
        assertEquals(MAX_MATERIAL_ORDERS, snapshot.openOrders.size)
        assertTrue(snapshot.positionMetadata.truncated)
        assertTrue(snapshot.orderMetadata.truncated)
        assertEquals(observedAt, snapshot.positionMetadata.observedAt)
        assertEquals(observedAt, snapshot.orderMetadata.observedAt)
        assertEquals("IN_MEMORY_LEDGER_MUTEX", snapshot.positionMetadata.provenance)
    }

    @Test
    fun `postgres capture SQL bounds sentinel rows before materialization`() {
        assertTrue(POSITIONS_SQL.endsWith("LIMIT 33"))
        assertTrue(ORDERS_SQL.endsWith("LIMIT 65"))
        assertEquals(MAX_MATERIAL_POSITIONS + 1, MAX_POSITION_QUERY_ROWS)
        assertEquals(MAX_MATERIAL_ORDERS + 1, MAX_ORDER_QUERY_ROWS)
    }

    @Test
    fun `in memory capture waits for shared writer boundary and never reads a split snapshot`() = runBlocking {
        val boundary = InMemoryAccountStateBoundary()
        val ledger = InMemoryPaperLedgerRepository(accountStateBoundary = boundary)
        val risk = InMemoryRiskStateRepository(accountStateBoundary = boundary)
        val reader = InMemoryDecisionAccountSnapshotReader(ledger, risk)
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.Default) {
            boundary.write {
                writerEntered.complete(Unit)
                runBlocking { releaseWriter.await() }
            }
        }
        writerEntered.await()

        val read = async(Dispatchers.Default) { reader.read().getOrThrow() }
        delay(50)

        assertFalse(read.isCompleted)
        releaseWriter.complete(Unit)
        writer.await()
        assertEquals("RUNNING", read.await().riskState)
    }

    @Test
    fun `in memory capture fails closed when writers do not share a coherence boundary`() = runBlocking {
        val ledger = InMemoryPaperLedgerRepository()
        val risk = InMemoryRiskStateRepository()
        val reader = InMemoryDecisionAccountSnapshotReader(ledger, risk)

        assertTrue(reader.read().isFailure)
    }

    private fun position(index: Int) = Position(
        positionId = "position-$index",
        tradeGroupId = "group-$index",
        symbol = "BTC_JPY",
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.OPEN,
        openedAt = "2026-07-13T00:00:00Z",
        closedAt = null,
        sizeBtc = "0.001",
        averageEntryPriceJpy = "10000000",
        currentPriceJpy = "10000000",
        currentStopLossJpy = "9900000",
        currentTakeProfitJpy = "10200000",
        unrealizedPnlJpy = "0",
        unrealizedR = "0",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "10000000",
        lowestPriceSinceEntryJpy = "10000000",
    )

    private fun order(index: Int) = Order(
        orderId = "order-$index",
        intentId = null,
        positionId = null,
        tradeGroupId = "group-$index",
        symbol = "BTC_JPY",
        mode = TradingMode.PAPER,
        side = OrderSide.BUY,
        orderType = OrderType.LIMIT,
        status = OrderStatus.OPEN,
        sizeBtc = "0.001",
        limitPriceJpy = "9900000",
        triggerPriceJpy = null,
        protectiveStopPriceJpy = "9800000",
        takeProfitPriceJpy = "10200000",
        reasonJa = "fixture",
        clientRequestId = null,
        createdAt = "2026-07-13T00:00:00Z",
        updatedAt = "2026-07-13T00:00:00Z",
    )
}
