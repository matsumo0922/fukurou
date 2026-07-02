package me.matsumo.fukurou.trading.tool

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.risk.HardHaltTradingRejectedException
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ToolCallGuard の no-trade / HARD_HALT / lock contract を検証するテスト。
 */
class ToolCallGuardTest {

    @Test
    fun trade_calls_are_serialized_by_global_lock() = runBlocking {
        val guard = createGuard()
        val activeCount = AtomicInteger(0)
        val maxActiveCount = AtomicInteger(0)

        coroutineScope {
            val firstResult = async {
                guard.runTradeTool(createCall(toolCallId = "first")) {
                    recordConcurrentSection(activeCount, maxActiveCount)
                    "first"
                }
            }
            val secondResult = async {
                guard.runTradeTool(createCall(toolCallId = "second")) {
                    recordConcurrentSection(activeCount, maxActiveCount)
                    "second"
                }
            }

            assertEquals("first", firstResult.await().getOrThrow())
            assertEquals("second", secondResult.await().getOrThrow())
        }

        assertEquals(1, maxActiveCount.get())
    }

    @Test
    fun hard_halt_rejects_trade_but_allows_read_tool() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val guard = createGuard(
            eventLog = eventLog,
            riskStateRepository = riskStateRepository,
        )

        riskStateRepository.setHardHalt("test halt", fixedInstant()).getOrThrow()

        val tradeResult = guard.runTradeTool(createCall(toolName = "trade.place_order")) {
            "should not trade"
        }
        val readResult = guard.runReadOnlyTool(createCall(toolName = "account.get_account_status")) {
            "read allowed"
        }
        val eventTypes = eventLog.events().map { event -> event.eventType }

        assertTrue(tradeResult.exceptionOrNull() is HardHaltTradingRejectedException)
        assertEquals("read allowed", readResult.getOrThrow())
        assertTrue(eventTypes.contains(CommandEventType.TOOL_CALL_REJECTED_BY_HARD_HALT))
        assertTrue(eventTypes.contains(CommandEventType.TOOL_CALL_COMPLETED))
    }

    @Test
    fun decision_run_id_is_logged_on_tool_call() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val guard = createGuard(eventLog = eventLog)
        val call = createCall(
            context = DecisionRunContext(
                decisionRunId = "run-123",
                llmProvider = "codex",
                promptHash = "prompt-hash",
                systemPromptVersion = "v1",
                marketSnapshotId = "snapshot-1",
            ),
        )

        guard.runReadOnlyTool(call) { "ok" }.getOrThrow()

        val event = eventLog.events().single()
        assertEquals("run-123", event.decisionRunContext.decisionRunId)
        assertEquals("codex", event.decisionRunContext.llmProvider)
        assertEquals(CommandEventType.TOOL_CALL_COMPLETED, event.eventType)
    }

    @Test
    fun failure_records_no_trade_exit() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val guard = createGuard(eventLog = eventLog)

        val result = guard.runTradeTool(createCall(toolName = "trade.place_order")) {
            throw NoTradeExitException("caller timed out before trade")
        }
        val noTradeEvent = eventLog.events().single()

        assertTrue(result.exceptionOrNull() is NoTradeExitException)
        assertEquals(CommandEventType.NO_TRADE_EXIT, noTradeEvent.eventType)
        assertTrue(noTradeEvent.payload.contains("\"noTrade\":true"))
    }
}

/**
 * 並行区間に入り、最大同時実行数を記録する。
 */
private suspend fun recordConcurrentSection(
    activeCount: AtomicInteger,
    maxActiveCount: AtomicInteger,
) {
    val currentActiveCount = activeCount.incrementAndGet()
    maxActiveCount.updateAndGet { previousMaxCount -> maxOf(previousMaxCount, currentActiveCount) }

    delay(50)

    activeCount.decrementAndGet()
}

/**
 * ToolCallGuard test 用の固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T00:00:00Z")
}

/**
 * ToolCallGuard test 用の固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

/**
 * ToolCallGuard test 用の guard を作る。
 */
private fun createGuard(
    eventLog: InMemoryCommandEventLog = InMemoryCommandEventLog(),
    riskStateRepository: InMemoryRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
): ToolCallGuard {
    return ToolCallGuard(
        riskStateRepository = riskStateRepository,
        commandEventLog = eventLog,
        tradingLock = InMemoryTradingLock(fixedClock()),
        clock = fixedClock(),
    )
}

/**
 * ToolCallGuard test 用の tool call envelope を作る。
 */
private fun createCall(
    toolName: String = "trade.place_order",
    toolCallId: String = "tool-call",
    context: DecisionRunContext = DecisionRunContext.fromEnvironment(emptyMap()),
): GuardedToolCall {
    return GuardedToolCall(
        toolName = toolName,
        toolCallId = toolCallId,
        clientRequestId = "client-request",
        decisionRunContext = context,
        payload = "{}",
    )
}
