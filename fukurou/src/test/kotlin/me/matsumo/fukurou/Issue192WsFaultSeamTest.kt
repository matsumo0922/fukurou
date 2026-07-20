package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventByIdReader
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy
import me.matsumo.fukurou.trading.market.InjectedWebSocketDisconnectOutcome
import me.matsumo.fukurou.trading.market.InjectedWebSocketDisconnector
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #192 の一時的な WebSocket 切断 seam を検証するテスト。
 */
class Issue192WsFaultSeamTest {

    private val clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC)
    private val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000192")
    private val injectionId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `flag_が既定の false なら production entrypoint は route を構築しない`() = testApplication {
        application {
            module(readinessProbe = { true })
        }

        val response = client.post(ISSUE_192_WS_DISCONNECT_PATH) {
            contentType(ContentType.Application.Json)
            setBody(disconnectRequestBody())
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `flag_が true でも生成 OpenAPI に route を露出しない`() = testApplication {
        application {
            module(readinessProbe = { true }, issue192WsFaultEnabled = true)
        }

        val openApiDocument = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject
        val paths = openApiDocument.getValue("paths").jsonObject

        assertTrue(!paths.containsKey(ISSUE_192_WS_DISCONNECT_PATH))
    }

    @Test
    fun `flag_が true なら production entrypoint が route を構築し DB 未構成では fail closed にする`() = testApplication {
        application {
            module(readinessProbe = { true }, issue192WsFaultEnabled = true)
        }

        val response = client.post(ISSUE_192_WS_DISCONNECT_PATH) {
            contentType(ContentType.Application.Json)
            setBody(disconnectRequestBody())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `固定 purpose と nonblank reason 以外の request 形は command へ変換しない`() {
        assertNull(command(purpose = "OTHER_PURPOSE"))
        assertNull(command(reason = "   "))
        assertNull(command(reason = "r".repeat(513)))
        assertNull(command(injectionId = "not-a-uuid"))
        assertNull(command(expectedSessionId = "not-a-uuid"))
        assertEquals(injectionId, command()?.injectionId)
    }

    @Test
    fun `正常系は requested と executed の固定 audit を append して切断する`() = runBlocking {
        val eventLog = RecordingCommandEventLog()
        val disconnector = RecordingDisconnector(InjectedWebSocketDisconnectOutcome.DISCONNECTED)
        val controller = controller(eventLog, disconnector)

        val result = controller.disconnect(requireNotNull(command()))

        assertTrue(result is Issue192WsDisconnectResult.Executed)
        assertEquals(
            listOf(ISSUE_192_WS_DISCONNECT_REQUESTED_EVENT_ID, ISSUE_192_WS_DISCONNECT_EXECUTED_EVENT_ID),
            eventLog.appended.map(CommandEvent::id),
        )
        assertEquals(1, disconnector.calls.get())
        assertEquals(listOf(sessionId), disconnector.requestedSessionIds)
    }

    @Test
    fun `固定 audit identity は ManifestPersistencePolicy を通る`() = runBlocking {
        val eventLog = RecordingCommandEventLog()
        val controller = controller(eventLog, RecordingDisconnector(InjectedWebSocketDisconnectOutcome.DISCONNECTED))

        controller.disconnect(requireNotNull(command()))

        eventLog.appended.forEach { event ->
            assertEquals(ISSUE_192_WS_TOOL_NAME, event.toolName)
            assertEquals(event.id.toString(), event.clientRequestId)
            ManifestPersistencePolicy.validateCommandEvent(
                context = event.decisionRunContext,
                toolName = event.toolName,
                clientRequestId = event.clientRequestId,
                payload = event.payload,
            )
        }
    }

    @Test
    fun `固定 PK がどちらか存在すれば新しい injection ID でも arm を消費済みとして拒否する`() = runBlocking {
        listOf(
            ISSUE_192_WS_DISCONNECT_REQUESTED_EVENT_ID to CommandEventType.ISSUE_192_WS_DISCONNECT_REQUESTED,
            ISSUE_192_WS_DISCONNECT_EXECUTED_EVENT_ID to CommandEventType.ISSUE_192_WS_DISCONNECT_EXECUTED,
        ).forEach { (eventId, eventType) ->
            val eventLog = RecordingCommandEventLog()
            eventLog.seed(fixedAuditEvent(eventId, eventType))
            val disconnector = RecordingDisconnector(InjectedWebSocketDisconnectOutcome.DISCONNECTED)

            val result = controller(eventLog, disconnector)
                .disconnect(requireNotNull(command(injectionId = UUID.randomUUID().toString())))

            assertEquals(Issue192WsDisconnectResult.Conflict("ARM_ALREADY_CONSUMED"), result)
            assertEquals(0, disconnector.calls.get())
            assertTrue(eventLog.appended.isEmpty())
        }
    }

    @Test
    fun `lookup 失敗と event type 不一致と payload 不整合は abort せず fail closed にする`() = runBlocking {
        val failingLookup = RecordingCommandEventLog(lookupFailure = IllegalStateException("db down"))
        val wrongType = RecordingCommandEventLog().apply {
            seed(fixedAuditEvent(ISSUE_192_WS_DISCONNECT_REQUESTED_EVENT_ID, CommandEventType.NO_TRADE_EXIT))
        }
        val wrongPayload = RecordingCommandEventLog().apply {
            seed(
                fixedAuditEvent(
                    eventId = ISSUE_192_WS_DISCONNECT_REQUESTED_EVENT_ID,
                    eventType = CommandEventType.ISSUE_192_WS_DISCONNECT_REQUESTED,
                    payload = """{"purpose":"SOMETHING_ELSE"}""",
                ),
            )
        }

        listOf(
            failingLookup to "AUDIT_LOOKUP_FAILED",
            wrongType to "AUDIT_PAYLOAD_CONFLICT",
            wrongPayload to "AUDIT_PAYLOAD_CONFLICT",
        ).forEach { (eventLog, expectedCode) ->
            val disconnector = RecordingDisconnector(InjectedWebSocketDisconnectOutcome.DISCONNECTED)

            val result = controller(eventLog, disconnector).disconnect(requireNotNull(command()))

            assertEquals(Issue192WsDisconnectResult.Unavailable(expectedCode), result)
            assertEquals(0, disconnector.calls.get())
            assertTrue(eventLog.appended.isEmpty())
        }
    }

    @Test
    fun `preflight 不成立と session 不一致は requested audit の前に拒否する`() = runBlocking {
        val rejectingStates = mapOf(
            "PREFLIGHT_EPOCH_OR_BASELINE_REJECTED" to admittedPreflight().copy(paperMode = false),
            "PREFLIGHT_MARKET_DATA_REJECTED" to admittedPreflight().copy(unresolvedMarketDataGapCount = 1),
            "PREFLIGHT_INVENTORY_REJECTED" to admittedPreflight().copy(openPositionCount = 1),
            "PREFLIGHT_ACTIVE_WORK_REJECTED" to admittedPreflight().copy(activeLlmWorkPresent = true),
            "SESSION_MISMATCH" to admittedPreflight().copy(activeSessionId = UUID.randomUUID()),
        )

        rejectingStates.forEach { (expectedCode, state) ->
            val eventLog = RecordingCommandEventLog()
            val disconnector = RecordingDisconnector(InjectedWebSocketDisconnectOutcome.DISCONNECTED)

            val result = controller(eventLog, disconnector, preflight = { Result.success(state) })
                .disconnect(requireNotNull(command()))

            assertEquals(Issue192WsDisconnectResult.Conflict(expectedCode), result)
            assertEquals(0, disconnector.calls.get())
            assertTrue(eventLog.appended.isEmpty())
        }
    }

    @Test
    fun `preflight 読み取り失敗は abort せず fail closed にする`() = runBlocking {
        val eventLog = RecordingCommandEventLog()
        val disconnector = RecordingDisconnector(InjectedWebSocketDisconnectOutcome.DISCONNECTED)

        val result = controller(
            eventLog = eventLog,
            disconnector = disconnector,
            preflight = { Result.failure(IllegalStateException("query failed")) },
        ).disconnect(requireNotNull(command()))

        assertEquals(Issue192WsDisconnectResult.Unavailable("PREFLIGHT_READ_FAILED"), result)
        assertEquals(0, disconnector.calls.get())
        assertTrue(eventLog.appended.isEmpty())
    }

    @Test
    fun `requested audit の append 失敗では abort しない`() = runBlocking {
        val eventLog = RecordingCommandEventLog(appendFailure = IllegalStateException("append failed"))
        val disconnector = RecordingDisconnector(InjectedWebSocketDisconnectOutcome.DISCONNECTED)

        val result = controller(eventLog, disconnector).disconnect(requireNotNull(command()))

        assertEquals(Issue192WsDisconnectResult.Unavailable("REQUESTED_AUDIT_FAILED"), result)
        assertEquals(0, disconnector.calls.get())
    }

    @Test
    fun `abort 失敗は requested audit だけを残し arm を恒久的に焼き切る`() = runBlocking {
        val eventLog = RecordingCommandEventLog()
        val disconnector = RecordingDisconnector(InjectedWebSocketDisconnectOutcome.SESSION_MISMATCH)
        val controller = controller(eventLog, disconnector)

        val firstResult = controller.disconnect(requireNotNull(command()))

        assertEquals(Issue192WsDisconnectResult.Unavailable("DISCONNECT_SESSION_MISMATCH"), firstResult)
        assertEquals(
            listOf(ISSUE_192_WS_DISCONNECT_REQUESTED_EVENT_ID),
            eventLog.appended.map(CommandEvent::id),
        )

        val retryResult = controller.disconnect(requireNotNull(command(injectionId = UUID.randomUUID().toString())))

        assertEquals(Issue192WsDisconnectResult.Conflict("ARM_ALREADY_CONSUMED"), retryResult)
        assertEquals(1, disconnector.calls.get())
    }

    @Test
    fun `response 喪失後の retry と restart 後の新 ID は再実行せず既存状態を返す`() = runBlocking {
        val eventLog = RecordingCommandEventLog()
        val disconnector = RecordingDisconnector(InjectedWebSocketDisconnectOutcome.DISCONNECTED)

        controller(eventLog, disconnector).disconnect(requireNotNull(command()))

        val retryResult = controller(eventLog, disconnector).disconnect(requireNotNull(command()))
        val restartedResult = controller(eventLog, disconnector)
            .disconnect(requireNotNull(command(injectionId = UUID.randomUUID().toString())))

        assertEquals(Issue192WsDisconnectResult.Conflict("ARM_ALREADY_CONSUMED"), retryResult)
        assertEquals(Issue192WsDisconnectResult.Conflict("ARM_ALREADY_CONSUMED"), restartedResult)
        assertEquals(1, disconnector.calls.get())
    }

    @Test
    fun `controller は global trading lock を依存に持たない`() {
        val lockFields = Issue192WsFaultController::class.java.declaredFields
            .filter { field -> field.type.simpleName.contains("TradingLock") }

        assertTrue(lockFields.isEmpty())
    }

    @Test
    fun `holder は worker が publish するまで切断 interface を公開しない`() {
        val holder = Issue192WsFaultHolder()

        assertNull(holder.current())

        val disconnector = RecordingDisconnector(InjectedWebSocketDisconnectOutcome.DISCONNECTED)
        holder.publish(disconnector)

        assertEquals(disconnector, holder.current())
    }

    private fun disconnectRequestBody(): String {
        return buildJsonObject {
            put("injectionId", injectionId.toString())
            put("expectedSessionId", sessionId.toString())
            put("purpose", ISSUE_192_WS_DISCONNECT_PURPOSE)
            put("reason", "owner approved")
        }.toString()
    }

    private fun command(
        injectionId: String = this.injectionId.toString(),
        expectedSessionId: String = sessionId.toString(),
        purpose: String = ISSUE_192_WS_DISCONNECT_PURPOSE,
        reason: String = "owner approved arm 1",
    ): Issue192WsDisconnectCommand? {
        return issue192WsDisconnectCommand(injectionId, expectedSessionId, purpose, reason)
    }

    private fun controller(
        eventLog: RecordingCommandEventLog,
        disconnector: InjectedWebSocketDisconnector,
        preflight: Issue192WsFaultPreflight = Issue192WsFaultPreflight { Result.success(admittedPreflight()) },
    ): Issue192WsFaultController {
        return Issue192WsFaultController(
            disconnectorProvider = { disconnector },
            commandEventLog = eventLog,
            commandEventByIdReader = eventLog,
            preflight = preflight,
            clock = clock,
        )
    }

    private fun admittedPreflight(): Issue192WsFaultPreflightState {
        return Issue192WsFaultPreflightState(
            paperMode = true,
            accountEpochId = "epoch-1",
            runtimeConfigVersionId = "version-1",
            accountBaselineMatchesRuntimeConfig = true,
            activeSessionId = sessionId,
            unresolvedMarketDataGapCount = 0,
            restingBuyEntryOrderCount = 1,
            openPositionCount = 0,
            activeLlmWorkPresent = false,
        )
    }

    private fun fixedAuditEvent(
        eventId: UUID,
        eventType: CommandEventType,
        payload: String = """{"purpose":"$ISSUE_192_WS_DISCONNECT_PURPOSE"}""",
    ): CommandEvent {
        return CommandEvent(
            id = eventId,
            decisionRunContext = DecisionRunContext.EMPTY,
            toolName = ISSUE_192_WS_TOOL_NAME,
            toolCallId = null,
            clientRequestId = eventId.toString(),
            eventType = eventType,
            payload = payload,
            occurredAt = clock.instant(),
        )
    }
}

/** append 済み event を保持し、primary-key lookup だけを提供する test double。 */
private class RecordingCommandEventLog(
    private val lookupFailure: Throwable? = null,
    private val appendFailure: Throwable? = null,
) : CommandEventLog, CommandEventByIdReader {
    private val events = mutableMapOf<UUID, CommandEvent>()

    val appended = mutableListOf<CommandEvent>()

    fun seed(event: CommandEvent) {
        events[event.id] = event
    }

    override suspend fun findEventById(id: UUID): Result<CommandEvent?> {
        lookupFailure?.let { failure -> return Result.failure(failure) }

        return Result.success(events[id])
    }

    override suspend fun append(event: CommandEvent): Result<Unit> {
        appendFailure?.let { failure -> return Result.failure(failure) }

        appended += event
        events[event.id] = event

        return Result.success(Unit)
    }

    override suspend fun countDistinctLlmLaunchesSince(since: Instant, excludedInvocationId: String?): Result<Int> {
        return Result.success(0)
    }

    override suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
        return Result.success(0)
    }
}

/** 切断要求の回数と対象 session ID を記録する test double。 */
private class RecordingDisconnector(
    private val outcome: InjectedWebSocketDisconnectOutcome,
) : InjectedWebSocketDisconnector {
    val calls = AtomicInteger(0)
    val requestedSessionIds = mutableListOf<UUID>()

    override fun disconnectActiveSession(expectedSessionId: UUID): InjectedWebSocketDisconnectOutcome {
        calls.incrementAndGet()
        requestedSessionIds += expectedSessionId

        return outcome
    }
}
