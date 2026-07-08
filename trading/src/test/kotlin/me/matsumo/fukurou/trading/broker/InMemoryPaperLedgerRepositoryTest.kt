package me.matsumo.fukurou.trading.broker

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.ExecutionLiquidity
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.PositionSide
import me.matsumo.fukurou.trading.domain.PositionStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * InMemoryPaperLedgerRepository の read model contract を検証するテスト。
 */
class InMemoryPaperLedgerRepositoryTest {

    @Test
    fun findExecutionActivitiesForStableFeed_returnsSeededEntryDecisionDetails() = runBlocking {
        val repository = InMemoryPaperLedgerRepository(
            positions = listOf(activityPosition()),
            openOrders = listOf(activityStopOrder()),
            executions = listOf(activityExecution()),
            decisionRunIdsByPositionId = mapOf(ACTIVITY_POSITION_ID to ACTIVITY_DECISION_RUN_ID),
            decisionContextsByRunId = mapOf(
                ACTIVITY_DECISION_RUN_ID to ExecutionActivityDecisionContext(
                    decisionId = ACTIVITY_DECISION_ID,
                    decisionRunId = ACTIVITY_DECISION_RUN_ID,
                    action = DecisionAction.ENTER,
                    reasonJa = ACTIVITY_DECISION_REASON_JA,
                ),
            ),
        )

        val activities = repository.findExecutionActivitiesForStableFeed(
            cursor = StableFeedCursor(
                occurredAt = Instant.MAX,
                includesSameTimestamp = false,
                afterId = null,
            ),
            limit = 10,
        ).getOrThrow()
        val entryDecision = requireNotNull(activities.single().entryDecision)

        assertEquals(ACTIVITY_DECISION_ID, entryDecision.decisionId)
        assertEquals(ACTIVITY_DECISION_RUN_ID, entryDecision.decisionRunId)
        assertEquals(DecisionAction.ENTER, entryDecision.action)
        assertEquals(ACTIVITY_DECISION_REASON_JA, entryDecision.reasonJa)
    }
}

private const val ACTIVITY_POSITION_ID = "position-activity-context"
private const val ACTIVITY_TRADE_GROUP_ID = "trade-group-activity-context"
private const val ACTIVITY_STOP_ORDER_ID = "order-activity-stop"
private const val ACTIVITY_EXECUTION_ID = "execution-activity-stop"
private const val ACTIVITY_DECISION_ID = "decision-activity-entry"
private const val ACTIVITY_DECISION_RUN_ID = "run-activity-entry"
private const val ACTIVITY_DECISION_REASON_JA = "entry 判断を Activity 詳細に表示するための理由。"
private const val ACTIVITY_EXECUTED_AT = "2026-07-08T03:46:35Z"

/**
 * Activity context 用の position seed を作る。
 */
private fun activityPosition(): Position {
    return Position(
        positionId = ACTIVITY_POSITION_ID,
        tradeGroupId = ACTIVITY_TRADE_GROUP_ID,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = PositionSide.LONG,
        status = PositionStatus.CLOSED,
        openedAt = "2026-07-08T03:40:00Z",
        closedAt = ACTIVITY_EXECUTED_AT,
        sizeBtc = "0.001",
        averageEntryPriceJpy = "16000000",
        currentPriceJpy = "16100000",
        currentStopLossJpy = "15900000",
        currentTakeProfitJpy = "16200000",
        unrealizedPnlJpy = "0",
        unrealizedR = "0",
        pyramidAddCount = 0,
        highestPriceSinceEntryJpy = "16150000",
        lowestPriceSinceEntryJpy = "15980000",
    )
}

/**
 * Activity context 用の STOP order seed を作る。
 */
private fun activityStopOrder(): Order {
    return Order(
        orderId = ACTIVITY_STOP_ORDER_ID,
        positionId = ACTIVITY_POSITION_ID,
        tradeGroupId = ACTIVITY_TRADE_GROUP_ID,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.SELL,
        orderType = OrderType.STOP,
        status = OrderStatus.FILLED,
        sizeBtc = "0.001",
        limitPriceJpy = null,
        triggerPriceJpy = "15900000",
        protectiveStopPriceJpy = null,
        takeProfitPriceJpy = null,
        reasonJa = "STOP が約定したため。",
        clientRequestId = null,
        createdAt = "2026-07-08T03:41:00Z",
        updatedAt = ACTIVITY_EXECUTED_AT,
    )
}

/**
 * Activity context 用の execution seed を作る。
 */
private fun activityExecution(): Execution {
    return Execution(
        executionId = ACTIVITY_EXECUTION_ID,
        orderId = ACTIVITY_STOP_ORDER_ID,
        positionId = ACTIVITY_POSITION_ID,
        symbol = TradingSymbol.BTC.apiSymbol,
        mode = TradingMode.PAPER,
        side = OrderSide.SELL,
        priceJpy = "15900000",
        sizeBtc = "0.001",
        feeJpy = "0",
        realizedPnlJpy = "-1000",
        liquidity = ExecutionLiquidity.TAKER,
        executedAt = ACTIVITY_EXECUTED_AT,
    )
}
