package me.matsumo.fukurou.trading.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * CallerNoTradeGuard の caller boundary failure audit を検証するテスト。
 */
class CallerNoTradeGuardTest {

    @Test
    fun caller_timeout_is_logged_as_no_trade_exit() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val guard = CallerNoTradeGuard(eventLog, fixedClock())

        val result = runCatching {
            guard.run(createInvocation()) {
                withTimeout(10.toDuration(DurationUnit.MILLISECONDS)) {
                    delay(1_000.toDuration(DurationUnit.MILLISECONDS))
                }
            }
        }
        val event = eventLog.events().single()

        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(CommandEventType.NO_TRADE_EXIT, event.eventType)
        assertEquals("mcp.process", event.toolName)
        assertEquals("client-request", event.clientRequestId)
        assertEquals("run-789", event.decisionRunContext.decisionRunId)
        assertTrue(event.payload.contains("\"noTrade\":true"))
        assertTrue(event.payload.contains("caller_cancelled"))
    }

    @Test
    fun startup_failure_is_logged_as_no_trade_exit() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val guard = CallerNoTradeGuard(eventLog, fixedClock())

        val result = guard.run(createInvocation()) {
            error("MCP process failed before connect")
        }
        val event = eventLog.events().single()

        assertTrue(result.isFailure)
        assertEquals(CommandEventType.NO_TRADE_EXIT, event.eventType)
        assertTrue(event.payload.contains("caller_failed"))
        assertTrue(event.payload.contains("MCP process failed before connect"))
    }

    @Test
    fun caller_cancellation_records_no_trade_exit_with_context_switching_audit_log() = runBlocking {
        val eventLog = CallerContextSwitchingCommandEventLog()
        val guard = CallerNoTradeGuard(eventLog, fixedClock())
        val blockStarted = CompletableDeferred<Unit>()

        val job = launch {
            guard.run(createInvocation()) {
                blockStarted.complete(Unit)

                awaitCancellation()
            }
        }

        blockStarted.await()
        job.cancelAndJoin()

        val event = eventLog.events().single()

        assertEquals(CommandEventType.NO_TRADE_EXIT, event.eventType)
        assertTrue(event.payload.contains("caller_cancelled"))
    }
}

/**
 * CallerNoTradeGuard test 用の caller invocation を作る。
 */
private fun createInvocation(): CallerInvocation {
    return CallerInvocation(
        operationName = "mcp.process",
        clientRequestId = "client-request",
        decisionRunContext = DecisionRunContext(
            decisionRunId = "run-789",
            llmProvider = "codex",
            promptHash = "prompt-hash",
            systemPromptVersion = "v1",
            marketSnapshotId = "snapshot-1",
        ),
    )
}

/**
 * CallerNoTradeGuard test 用の固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T02:00:00Z")
}

/**
 * CallerNoTradeGuard test 用の固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

/**
 * DB-backed append と同じく dispatcher を切り替える command_event_log。
 */
private class CallerContextSwitchingCommandEventLog : CommandEventLog {

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

    override suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
        return Result.success(0)
    }

    /**
     * 保存済みイベントの snapshot を返す。
     */
    fun events(): List<CommandEvent> {
        return storedEvents.toList()
    }
}
