package me.matsumo.fukurou.trading.risk

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RiskStateCommandService の risk_state 更新と audit contract を検証するテスト。
 */
class RiskStateCommandServiceTest {

    @Test
    fun set_hard_halt_updates_state_and_logs_audit_event() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val service = createService(eventLog)

        val riskState = service.setHardHalt(
            reason = "max drawdown exceeded",
            decisionRunContext = createDecisionRunContext(),
        ).getOrThrow()
        val event = eventLog.events().single()

        assertTrue(riskState.hardHalt)
        assertEquals("max drawdown exceeded", riskState.haltReason)
        assertEquals(fixedInstant(), riskState.haltAt)
        assertEquals(CommandEventType.HARD_HALT_SET, event.eventType)
        assertEquals("risk_state", event.toolName)
        assertEquals("run-456", event.decisionRunContext.decisionRunId)
        assertTrue(event.payload.contains("max drawdown exceeded"))
    }

    @Test
    fun resume_updates_state_and_logs_audit_event() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val service = createService(eventLog)

        service.setHardHalt(
            reason = "max drawdown exceeded",
            decisionRunContext = createDecisionRunContext(),
        ).getOrThrow()

        val riskState = service.resume(
            reason = "operator confirmed recovery",
            decisionRunContext = createDecisionRunContext(),
        ).getOrThrow()
        val events = eventLog.events()
        val resumeEvent = events.last()

        assertFalse(riskState.hardHalt)
        assertEquals("operator confirmed recovery", riskState.resumedReason)
        assertEquals(fixedInstant(), riskState.resumedAt)
        assertEquals(CommandEventType.MANUAL_RESUME_REQUESTED, resumeEvent.eventType)
        assertEquals("risk_state", resumeEvent.toolName)
        assertEquals(2, events.size)
        assertTrue(resumeEvent.payload.contains("operator confirmed recovery"))
    }
}

/**
 * RiskStateCommandService test 用の service を作る。
 */
private fun createService(eventLog: InMemoryCommandEventLog): RiskStateCommandService {
    return RiskStateCommandService(
        riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
        commandEventLog = eventLog,
        clock = fixedClock(),
    )
}

/**
 * RiskStateCommandService test 用の decision run context を作る。
 */
private fun createDecisionRunContext(): DecisionRunContext {
    return DecisionRunContext(
        decisionRunId = "run-456",
        llmProvider = "codex",
        promptHash = "prompt-hash",
        systemPromptVersion = "v1",
        marketSnapshotId = "snapshot-1",
    )
}

/**
 * RiskStateCommandService test 用の固定時刻を返す。
 */
private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-02T01:00:00Z")
}

/**
 * RiskStateCommandService test 用の固定 clock を返す。
 */
private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}
