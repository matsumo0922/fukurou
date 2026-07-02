package me.matsumo.fukurou.trading.tool

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                withTimeout(10) {
                    delay(1_000)
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
