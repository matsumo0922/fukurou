package me.matsumo.fukurou

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventByIdReader
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.market.InjectedWebSocketDisconnectOutcome
import me.matsumo.fukurou.trading.market.InjectedWebSocketDisconnector
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Issue #192 の WebSocket 切断注入で使う固定 purpose。
 */
internal const val ISSUE_192_WS_DISCONNECT_PURPOSE = "ISSUE_192_WS_DISCONNECT"

/**
 * requested / executed 両 audit event が使う固定 tool 名。
 */
internal const val ISSUE_192_WS_TOOL_NAME = "issue192-ws"

/**
 * requested audit の固定 primary key。この行が存在するだけで arm を global に消費する。
 */
internal val ISSUE_192_WS_DISCONNECT_REQUESTED_EVENT_ID: UUID =
    UUID.fromString("588ce39f-90ec-4479-9430-f22a6d0356a9")

/**
 * executed audit の固定 primary key。この行が存在するだけで arm を global に消費する。
 */
internal val ISSUE_192_WS_DISCONNECT_EXECUTED_EVENT_ID: UUID =
    UUID.fromString("0367f844-595a-4ed7-8480-43a1d3e5df6c")

/**
 * owner 承認 reason の最大長。payload を bounded に保つ。
 */
private const val MAX_REASON_LENGTH = 512

/** operator-supplied TTL 境界の最大値。一時 seam の payload と時刻計算を bounded に保つ。 */
private const val MAX_MINIMUM_REMAINING_TTL_SECONDS = 86_400L

/**
 * 固定 audit payload を読むための JSON parser。
 */
private val Issue192AuditPayloadJson = Json { ignoreUnknownKeys = true }

/**
 * 注入 request の検証済み入力。
 *
 * @param injectionId operator が 1 回ごとに新規発行する注入 ID
 * @param expectedSessionId 最終 preflight で固定した active market-data session ID
 * @param targetOrderId owner が承認した resting BUY entry order ID
 * @param expectedOrderExpiresAt owner 承認時に固定した order expiry
 * @param minimumRemainingTtlSeconds 実行時に必要な最小 remaining TTL 秒数
 * @param reason owner 承認を結びつけた実行理由
 */
internal data class Issue192WsDisconnectCommand(
    val injectionId: UUID,
    val expectedSessionId: UUID,
    val targetOrderId: String,
    val expectedOrderExpiresAt: Instant,
    val minimumRemainingTtlSeconds: Long,
    val reason: String,
)

/**
 * 注入 request の結果。route 側で HTTP status へ写像する。
 */
internal sealed interface Issue192WsDisconnectResult {
    /** expected session の socket を abort し、executed audit まで durable に確定した。 */
    data class Executed(
        val injectionId: UUID,
        val sessionId: UUID,
    ) : Issue192WsDisconnectResult

    /** mutation なしで拒否した。 */
    data class Conflict(val code: String) : Issue192WsDisconnectResult

    /** fail closed で拒否した。abort は呼んでいないか、arm が焼き切れている。 */
    data class Unavailable(val code: String) : Issue192WsDisconnectResult
}

/**
 * 注入前に読み取る production 事実。production 固有 state は narrow dependency として注入する。
 *
 * @param paperMode `PAPER` mode で稼働しているか
 * @param accountEpochId active account epoch ID
 * @param runtimeConfigVersionId active runtime config version ID
 * @param accountBaselineMatchesRuntimeConfig paper account baseline と runtime config baseline が一致するか
 * @param activeSessionId `CONNECTED` な market-data session ID。未接続なら null
 * @param unresolvedMarketDataGapCount 未解決 market-data gap 件数
 * @param restingBuyEntryOrderCount `OPEN` な resting BUY entry 件数
 * @param pendingCancelRestingBuyEntryOrderCount `PENDING_CANCEL` な resting BUY entry 件数
 * @param orderSnapshots target order identity を最終照合する order snapshot
 * @param observedAt order snapshot を読み終えた時刻
 * @param openPositionCount open position 件数
 * @param activeLlmWorkPresent fresh な trading launch reservation が存在するか
 */
internal data class Issue192WsFaultPreflightState(
    val paperMode: Boolean,
    val accountEpochId: String?,
    val runtimeConfigVersionId: String?,
    val accountBaselineMatchesRuntimeConfig: Boolean,
    val activeSessionId: UUID?,
    val unresolvedMarketDataGapCount: Int,
    val restingBuyEntryOrderCount: Int,
    val pendingCancelRestingBuyEntryOrderCount: Int,
    val orderSnapshots: List<Issue192OrderPreflightState>,
    val observedAt: Instant,
    val openPositionCount: Int,
    val activeLlmWorkPresent: Boolean,
)

/**
 * 注入直前の bounded read-only preflight 境界。
 */
internal fun interface Issue192WsFaultPreflight {
    suspend fun read(): Result<Issue192WsFaultPreflightState>
}

/**
 * controller が owner-approved order identity を照合するための bounded snapshot。
 *
 * @param orderId order identity
 * @param side order side
 * @param status order lifecycle status
 * @param positionId 関連 position ID。resting entry なら null
 * @param expiresAt order に永続化された expiry。解析不能または未設定なら null
 */
internal data class Issue192OrderPreflightState(
    val orderId: String,
    val side: OrderSide,
    val status: OrderStatus,
    val positionId: String?,
    val expiresAt: Instant?,
)

/**
 * Issue #192 の一時的な WebSocket 切断注入を直列化する controller。
 *
 * global trading lock は取得せず、controller-local `Mutex` だけで自身の request を直列化する。
 *
 * @param disconnectorProvider worker が構築した stream の切断 interface
 * @param commandEventLog 固定 audit を append する writer
 * @param commandEventByIdReader 固定 audit の primary-key lookup
 * @param preflight 注入直前の read-only preflight
 * @param clock audit の occurredAt に使う clock
 */
internal class Issue192WsFaultController(
    private val disconnectorProvider: () -> InjectedWebSocketDisconnector?,
    private val commandEventLog: CommandEventLog?,
    private val commandEventByIdReader: CommandEventByIdReader?,
    private val preflight: Issue192WsFaultPreflight?,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun disconnect(command: Issue192WsDisconnectCommand): Issue192WsDisconnectResult {
        return mutex.withLock { disconnectSerialized(command) }
    }

    private suspend fun disconnectSerialized(command: Issue192WsDisconnectCommand): Issue192WsDisconnectResult {
        val eventLog = commandEventLog ?: return Issue192WsDisconnectResult.Unavailable("AUDIT_LOG_UNAVAILABLE")
        val eventReader = commandEventByIdReader
            ?: return Issue192WsDisconnectResult.Unavailable("AUDIT_LOOKUP_UNAVAILABLE")
        val preflightBoundary = preflight ?: return Issue192WsDisconnectResult.Unavailable("PREFLIGHT_UNAVAILABLE")
        val disconnector = disconnectorProvider() ?: return Issue192WsDisconnectResult.Unavailable("STREAM_UNAVAILABLE")

        gateRejection(eventReader)?.let { rejection -> return rejection }

        val preflightState = preflightBoundary.read().getOrElse {
            return Issue192WsDisconnectResult.Unavailable("PREFLIGHT_READ_FAILED")
        }

        preflightRejection(preflightState, command)?.let { rejection -> return rejection }

        val requestedAppend = eventLog.append(auditEvent(ISSUE_192_WS_DISCONNECT_REQUESTED_EVENT_ID, command))
        if (requestedAppend.isFailure) return Issue192WsDisconnectResult.Unavailable("REQUESTED_AUDIT_FAILED")

        val outcome = runCatching { disconnector.disconnectActiveSession(command.expectedSessionId) }
            .getOrElse { return Issue192WsDisconnectResult.Unavailable("DISCONNECT_FAILED") }

        if (outcome != InjectedWebSocketDisconnectOutcome.DISCONNECTED) {
            return Issue192WsDisconnectResult.Unavailable("DISCONNECT_${outcome.name}")
        }

        val executedAppend = eventLog.append(auditEvent(ISSUE_192_WS_DISCONNECT_EXECUTED_EVENT_ID, command))
        if (executedAppend.isFailure) return Issue192WsDisconnectResult.Unavailable("EXECUTED_AUDIT_FAILED")

        return Issue192WsDisconnectResult.Executed(
            injectionId = command.injectionId,
            sessionId = command.expectedSessionId,
        )
    }

    /**
     * 固定 primary key 2件までの lookup で global one-shot gate を判定する。
     *
     * lookup 不確実性、event type 不一致、payload 不整合は fail closed とし、event_type の table scan は行わない。
     */
    private suspend fun gateRejection(reader: CommandEventByIdReader): Issue192WsDisconnectResult? {
        val fixedEvents = listOf(
            ISSUE_192_WS_DISCONNECT_REQUESTED_EVENT_ID to CommandEventType.ISSUE_192_WS_DISCONNECT_REQUESTED,
            ISSUE_192_WS_DISCONNECT_EXECUTED_EVENT_ID to CommandEventType.ISSUE_192_WS_DISCONNECT_EXECUTED,
        )

        fixedEvents.forEach { (eventId, expectedType) ->
            val event = reader.findEventById(eventId).getOrElse {
                return Issue192WsDisconnectResult.Unavailable("AUDIT_LOOKUP_FAILED")
            } ?: return@forEach

            if (!event.matchesFixedAudit(eventId, expectedType)) {
                return Issue192WsDisconnectResult.Unavailable("AUDIT_PAYLOAD_CONFLICT")
            }

            return Issue192WsDisconnectResult.Conflict("ARM_ALREADY_CONSUMED")
        }

        return null
    }

    private fun preflightRejection(
        state: Issue192WsFaultPreflightState,
        command: Issue192WsDisconnectCommand,
    ): Issue192WsDisconnectResult? {
        val armAdmitted = state.paperMode &&
            state.accountEpochId != null &&
            state.runtimeConfigVersionId != null &&
            state.accountBaselineMatchesRuntimeConfig
        val marketStateAdmitted = state.unresolvedMarketDataGapCount == 0 && state.activeSessionId != null
        val inventoryAdmitted = state.restingBuyEntryOrderCount >= 1 &&
            state.pendingCancelRestingBuyEntryOrderCount == 0 &&
            state.openPositionCount == 0

        if (!armAdmitted) return Issue192WsDisconnectResult.Conflict("PREFLIGHT_EPOCH_OR_BASELINE_REJECTED")
        if (!marketStateAdmitted) return Issue192WsDisconnectResult.Conflict("PREFLIGHT_MARKET_DATA_REJECTED")
        if (!inventoryAdmitted) return Issue192WsDisconnectResult.Conflict("PREFLIGHT_INVENTORY_REJECTED")
        if (state.activeLlmWorkPresent) return Issue192WsDisconnectResult.Conflict("PREFLIGHT_ACTIVE_WORK_REJECTED")
        if (state.activeSessionId != command.expectedSessionId) {
            return Issue192WsDisconnectResult.Conflict("SESSION_MISMATCH")
        }

        return targetOrderRejection(state, command)
    }

    private fun targetOrderRejection(
        state: Issue192WsFaultPreflightState,
        command: Issue192WsDisconnectCommand,
    ): Issue192WsDisconnectResult? {
        val targetOrder = state.orderSnapshots.singleOrNull { order -> order.orderId == command.targetOrderId }
            ?: return Issue192WsDisconnectResult.Conflict("TARGET_ORDER_NOT_FOUND")
        val targetOrderAdmitted = targetOrder.side == OrderSide.BUY &&
            targetOrder.status == OrderStatus.OPEN &&
            targetOrder.positionId == null

        if (!targetOrderAdmitted) return Issue192WsDisconnectResult.Conflict("TARGET_ORDER_STATE_REJECTED")
        if (targetOrder.expiresAt != command.expectedOrderExpiresAt) {
            return Issue192WsDisconnectResult.Conflict("TARGET_ORDER_EXPIRY_MISMATCH")
        }

        val minimumExpiry = state.observedAt.plusSeconds(command.minimumRemainingTtlSeconds)
        if (targetOrder.expiresAt.isBefore(minimumExpiry)) {
            return Issue192WsDisconnectResult.Conflict("TARGET_ORDER_TTL_INSUFFICIENT")
        }

        return null
    }

    private fun auditEvent(eventId: UUID, command: Issue192WsDisconnectCommand): CommandEvent {
        return CommandEvent(
            id = eventId,
            decisionRunContext = DecisionRunContext.EMPTY,
            toolName = ISSUE_192_WS_TOOL_NAME,
            toolCallId = null,
            clientRequestId = eventId.toString(),
            eventType = if (eventId == ISSUE_192_WS_DISCONNECT_REQUESTED_EVENT_ID) {
                CommandEventType.ISSUE_192_WS_DISCONNECT_REQUESTED
            } else {
                CommandEventType.ISSUE_192_WS_DISCONNECT_EXECUTED
            },
            payload = buildJsonObject {
                put("purpose", ISSUE_192_WS_DISCONNECT_PURPOSE)
                put("injectionId", command.injectionId.toString())
                put("expectedSessionId", command.expectedSessionId.toString())
                put("targetOrderId", command.targetOrderId)
                put("expectedOrderExpiresAt", command.expectedOrderExpiresAt.toString())
                put("minimumRemainingTtlSeconds", command.minimumRemainingTtlSeconds)
                put("reason", command.reason)
            }.toString(),
            occurredAt = clock.instant(),
        )
    }
}

/**
 * request 本体を検証済み command へ変換する。不正な形なら null を返す。
 */
internal fun issue192WsDisconnectCommand(request: Issue192WsDisconnectRequest): Issue192WsDisconnectCommand? {
    if (request.purpose != ISSUE_192_WS_DISCONNECT_PURPOSE) return null

    val trimmedReason = request.reason.trim()

    if (trimmedReason.isEmpty() || trimmedReason.length > MAX_REASON_LENGTH) return null

    val parsedInjectionId = runCatching { UUID.fromString(request.injectionId) }.getOrNull() ?: return null
    val parsedSessionId = runCatching { UUID.fromString(request.expectedSessionId) }.getOrNull() ?: return null
    val parsedTargetOrderId = runCatching { UUID.fromString(request.targetOrderId) }.getOrNull() ?: return null
    val parsedOrderExpiresAt = runCatching {
        Instant.parse(request.expectedOrderExpiresAt.trim())
    }.getOrNull() ?: return null
    if (request.minimumRemainingTtlSeconds !in 1..MAX_MINIMUM_REMAINING_TTL_SECONDS) return null

    return Issue192WsDisconnectCommand(
        injectionId = parsedInjectionId,
        expectedSessionId = parsedSessionId,
        targetOrderId = parsedTargetOrderId.toString(),
        expectedOrderExpiresAt = parsedOrderExpiresAt,
        minimumRemainingTtlSeconds = request.minimumRemainingTtlSeconds,
        reason = trimmedReason,
    )
}

/** 保存済み固定 audit が期待した identity と purpose を持つかを判定する。 */
private fun CommandEvent.matchesFixedAudit(eventId: UUID, expectedType: CommandEventType): Boolean {
    if (id != eventId || eventType != expectedType) return false
    if (toolName != ISSUE_192_WS_TOOL_NAME || clientRequestId != eventId.toString()) return false

    val purpose = runCatching {
        (Issue192AuditPayloadJson.parseToJsonElement(payload) as? JsonObject)
            ?.get("purpose")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()

    return purpose == ISSUE_192_WS_DISCONNECT_PURPOSE
}
