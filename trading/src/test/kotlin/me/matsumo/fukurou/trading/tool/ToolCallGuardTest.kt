package me.matsumo.fukurou.trading.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.lock.InMemoryTradingLock
import me.matsumo.fukurou.trading.lock.TradingLock
import me.matsumo.fukurou.trading.lock.TradingLockLease
import me.matsumo.fukurou.trading.risk.HardHaltTradingRejectedException
import me.matsumo.fukurou.trading.risk.InMemoryRiskStateRepository
import java.sql.SQLTimeoutException
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
    fun hard_halt_allows_decision_tool_with_global_lock_and_audit() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val guard = createGuard(
            eventLog = eventLog,
            riskStateRepository = riskStateRepository,
        )

        riskStateRepository.setHardHalt("test halt", fixedInstant()).getOrThrow()

        val result = guard.runDecisionTool(createCall(toolName = "submit_decision")) {
            "decision recorded"
        }
        val eventTypes = eventLog.events().map { event -> event.eventType }

        assertEquals("decision recorded", result.getOrThrow())
        assertEquals(listOf(CommandEventType.TOOL_CALL_COMPLETED), eventTypes)
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

    @Test
    fun cancellation_records_no_trade_exit_with_context_switching_audit_log() = runBlocking {
        val eventLog = ToolContextSwitchingCommandEventLog()
        val guard = createGuard(eventLog = eventLog)
        val blockStarted = CompletableDeferred<Unit>()

        val job = launch {
            guard.runReadOnlyTool(createCall(toolName = "read.slow")) {
                blockStarted.complete(Unit)

                awaitCancellation()
            }
        }

        blockStarted.await()
        job.cancelAndJoin()

        val event = eventLog.events().single()

        assertEquals(CommandEventType.NO_TRADE_EXIT, event.eventType)
        assertTrue(event.payload.contains("tool_call_cancelled"))
    }

    @Test
    fun no_trade_audit_failure_preserves_original_tool_failure() = runBlocking {
        val guard = createGuard(eventLog = FailingCommandEventLog)

        val result = guard.runTradeTool(createCall(toolName = "trade.place_order")) {
            throw NoTradeExitException("caller timed out before trade")
        }
        val throwable = requireNotNull(result.exceptionOrNull())

        assertTrue(throwable is NoTradeExitException)
        assertEquals("caller timed out before trade", throwable.message)
        assertTrue(throwable.suppressed.any { suppressed -> suppressed.message == "audit append failed" })
    }

    @Test
    fun completion_audit_failure_reports_executed_true() = runBlocking {
        val guard = createGuard(eventLog = FailingCommandEventLog)

        val result = guard.runTradeTool(createCall(toolName = "trade.place_order")) {
            "executed"
        }
        val throwable = requireNotNull(result.exceptionOrNull())

        assertTrue(throwable is ToolCompletionAuditFailedException)
        assertTrue(throwable.executed)
        assertTrue(throwable.message.orEmpty().contains("executed=true"))
    }

    @Test
    fun lock_timeout_records_no_trade_exit() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val guard = createGuard(
            eventLog = eventLog,
            tradingLock = TimeoutTradingLock,
        )

        val result = guard.runTradeTool(createCall(toolName = "trade.place_order")) {
            "should not run"
        }
        val event = eventLog.events().single()

        assertTrue(result.exceptionOrNull() is SQLTimeoutException)
        assertEquals(CommandEventType.NO_TRADE_EXIT, event.eventType)
        assertTrue(event.payload.contains("trading_lock_unavailable"))
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
    eventLog: CommandEventLog = InMemoryCommandEventLog(),
    riskStateRepository: InMemoryRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
    tradingLock: TradingLock = InMemoryTradingLock(fixedClock()),
): ToolCallGuard {
    return ToolCallGuard(
        riskStateRepository = riskStateRepository,
        commandEventLog = eventLog,
        tradingLock = tradingLock,
        clock = fixedClock(),
    )
}

/**
 * ToolCallGuard test 用の tool call envelope を作る。
 */
private fun createCall(
    toolName: String = "trade.place_order",
    toolCallId: String = "tool-call",
    context: DecisionRunContext = DecisionRunContext.EMPTY,
): GuardedToolCall {
    return GuardedToolCall(
        toolName = toolName,
        toolCallId = toolCallId,
        clientRequestId = "client-request",
        decisionRunContext = context,
        payload = "{}",
    )
}

/**
 * DB-backed append と同じく dispatcher を切り替える command_event_log。
 */
private class ToolContextSwitchingCommandEventLog : CommandEventLog {

    private val storedEvents = mutableListOf<CommandEvent>()

    override suspend fun append(event: CommandEvent): Result<Unit> {
        return withContext(Dispatchers.IO) {
            storedEvents += event

            Result.success(Unit)
        }
    }

    override suspend fun countDistinctDecisionRunsSince(since: Instant): Result<Int> {
        return Result.success(0)
    }

    /**
     * 保存済みイベントの snapshot を返す。
     */
    fun events(): List<CommandEvent> {
        return storedEvents.toList()
    }
}

/**
 * append に必ず失敗する command_event_log。
 */
private object FailingCommandEventLog : CommandEventLog {
    override suspend fun append(event: CommandEvent): Result<Unit> {
        return Result.failure(IllegalStateException("audit append failed"))
    }

    override suspend fun countDistinctDecisionRunsSince(since: Instant): Result<Int> {
        return Result.failure(IllegalStateException("audit count failed"))
    }
}

/**
 * lock timeout を再現する test lock。
 */
private object TimeoutTradingLock : TradingLock {
    override suspend fun <T> withLock(owner: String, block: suspend (TradingLockLease) -> T): T {
        throw SQLTimeoutException("lock timeout")
    }
}
