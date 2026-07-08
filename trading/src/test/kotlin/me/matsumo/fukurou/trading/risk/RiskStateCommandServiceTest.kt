package me.matsumo.fukurou.trading.risk

import kotlinx.coroutines.runBlocking
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

        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
        assertEquals("max drawdown exceeded", riskState.haltReason)
        assertEquals(fixedInstant(), riskState.haltAt)
        assertEquals(CommandEventType.HARD_HALT_SET, event.eventType)
        assertEquals("risk_state", event.toolName)
        assertEquals("run-456", event.decisionRunContext.decisionRunId)
        assertTrue(event.payload.contains("max drawdown exceeded"))
    }

    @Test
    fun set_soft_halt_updates_state_and_logs_audit_event() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val service = createService(eventLog)

        val riskState = service.setSoftHalt(
            reason = "operator pause",
            decisionRunContext = createDecisionRunContext(),
        ).getOrThrow()
        val event = eventLog.events().single()

        assertEquals(RiskHaltState.SOFT_HALT, riskState.state)
        assertEquals("operator pause", riskState.haltReason)
        assertEquals(fixedInstant(), riskState.haltAt)
        assertEquals(CommandEventType.SOFT_HALT_SET, event.eventType)
        assertEquals("risk_state", event.toolName)
        assertTrue(event.payload.contains("operator pause"))
        assertTrue(event.payload.contains(RiskHaltState.RUNNING.name))
    }

    @Test
    fun set_soft_halt_fails_when_current_state_is_hard_halt() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val service = createService(eventLog, riskStateRepository)

        service.setHardHalt(
            reason = "max drawdown exceeded",
            decisionRunContext = createDecisionRunContext(),
        ).getOrThrow()

        val result = service.setSoftHalt(
            reason = "operator pause",
            decisionRunContext = createDecisionRunContext(),
        )
        val riskState = riskStateRepository.current().getOrThrow()

        assertTrue(result.isFailure)
        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
        assertEquals(listOf(CommandEventType.HARD_HALT_SET), eventLog.events().map { event -> event.eventType })
    }

    @Test
    fun resume_updates_state_and_logs_audit_event() = runBlocking {
        val eventLog = InMemoryCommandEventLog()
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val service = createService(eventLog, riskStateRepository)

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

        assertEquals(RiskHaltState.RUNNING, riskState.state)
        assertEquals("operator confirmed recovery", riskState.resumedReason)
        assertEquals(fixedInstant(), riskState.resumedAt)
        assertEquals(CommandEventType.MANUAL_RESUME_REQUESTED, resumeEvent.eventType)
        assertEquals("risk_state", resumeEvent.toolName)
        assertEquals(2, events.size)
        assertTrue(resumeEvent.payload.contains("operator confirmed recovery"))
        assertTrue(resumeEvent.payload.contains(RiskHaltState.HARD_HALT.name))
    }

    @Test
    fun resume_rolls_back_state_when_audit_append_fails() = runBlocking {
        val riskStateRepository = InMemoryRiskStateRepository(clock = fixedClock())
        val service = createService(FailingCommandEventLog, riskStateRepository)

        riskStateRepository.setHardHalt("max drawdown exceeded", fixedInstant()).getOrThrow()

        val result = service.resume(
            reason = "operator confirmed recovery",
            decisionRunContext = createDecisionRunContext(),
        )
        val riskState = riskStateRepository.current().getOrThrow()

        assertTrue(result.isFailure)
        assertEquals(RiskHaltState.HARD_HALT, riskState.state)
        assertEquals("max drawdown exceeded", riskState.haltReason)
    }
}

/**
 * RiskStateCommandService test 用の service を作る。
 */
private fun createService(
    eventLog: CommandEventLog,
    riskStateRepository: InMemoryRiskStateRepository = InMemoryRiskStateRepository(clock = fixedClock()),
): RiskStateCommandService {
    return InMemoryRiskStateCommandService(
        riskStateRepository = riskStateRepository,
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

    override suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
        return Result.failure(IllegalStateException("audit count failed"))
    }
}
