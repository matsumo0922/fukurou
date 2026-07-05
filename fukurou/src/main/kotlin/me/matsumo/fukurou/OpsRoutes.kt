package me.matsumo.fukurou

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventFeedReader
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.broker.AccountSnapshotWithUpdatedAt
import me.matsumo.fukurou.trading.broker.PaperLedgerRepository
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchResult
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchService
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.knowledge.DecisionJournalRecord
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import me.matsumo.fukurou.trading.risk.RiskStateRepository
import me.matsumo.fukurou.trading.risk.SoftHaltDowngradeRejectedException
import java.time.Clock
import java.time.Instant

/**
 * ops API の OpenAPI tag。
 */
private const val OPS_TAG = "ops"

/**
 * halt request の level。
 */
@Serializable
enum class OpsHaltLevel {
    /**
     * 新規 entry だけを停止する。
     */
    SOFT,

    /**
     * 全 trade 系操作と daemon 起動を停止する。
     */
    HARD,
}

/**
 * halt API の request body。
 *
 * @param level 停止 level
 * @param reason 停止理由
 */
@Serializable
data class OpsHaltRequest(
    val level: OpsHaltLevel,
    val reason: String,
)

/**
 * resume API の request body。
 *
 * @param reason 再開理由
 */
@Serializable
data class OpsResumeRequest(
    val reason: String,
)

/**
 * manual trigger API の request body。
 *
 * @param reason 手動起動理由
 */
@Serializable
data class OpsTriggerRequest(
    val reason: String,
)

/**
 * manual trigger API の response body。
 *
 * @param invocationId 予約した runner 起動 ID
 * @param triggerKind 起動 trigger 種別
 */
@Serializable
data class OpsTriggerResponse(
    val invocationId: String,
    val triggerKind: String,
)

/**
 * risk_state API の response body。
 *
 * @param state 現在の halt state
 * @param haltReason 最後に halt した理由
 * @param haltAt 最後に halt した時刻
 * @param resumedAt 最後に resume した時刻
 * @param resumedReason 最後に resume した理由
 * @param drawdownRatio 現在の drawdown ratio
 */
@Serializable
data class OpsRiskStateResponse(
    val state: String,
    val haltReason: String?,
    val haltAt: String?,
    val resumedAt: String?,
    val resumedReason: String?,
    val drawdownRatio: String,
)

/**
 * decisions raw feed API の response body。
 *
 * @param decisions 新しい順の decision 一覧
 */
@Serializable
data class OpsDecisionsResponse(
    val decisions: List<OpsDecisionResponse>,
)

/**
 * decisions raw feed API の decision 要素。
 *
 * @param id decision ID
 * @param action LLM が提出した action
 * @param setupTags setup tag 一覧
 * @param estimatedWinProbability LLM 申告の推定勝率
 * @param reasonJa 判断理由
 * @param noTradeConditionsJa 見送り条件
 * @param createdAt 作成時刻
 */
@Serializable
data class OpsDecisionResponse(
    val id: String,
    val action: String,
    val setupTags: List<String>,
    val estimatedWinProbability: String,
    val reasonJa: String,
    val noTradeConditionsJa: List<String>,
    val createdAt: String,
)

/**
 * positions raw feed API の response body。
 *
 * @param positions open position 一覧
 * @param openOrders open order 一覧
 */
@Serializable
data class OpsPositionsResponse(
    val positions: List<Position>,
    val openOrders: List<Order>,
)

/**
 * audit raw feed API の response body。
 *
 * @param events 新しい順の audit event 一覧
 */
@Serializable
data class OpsAuditResponse(
    val events: List<OpsAuditEventResponse>,
)

/**
 * audit raw feed API の event 要素。
 *
 * @param id event ID
 * @param eventType event 種別
 * @param toolName tool または worker の論理名
 * @param payload JSON payload
 * @param occurredAt 発生時刻
 */
@Serializable
data class OpsAuditEventResponse(
    val id: String,
    val eventType: String,
    val toolName: String,
    val payload: String,
    val occurredAt: String,
)

/**
 * account snapshot API の response body。
 *
 * @param mode 取引 mode
 * @param cashJpy JPY 現金残高
 * @param initialCashJpy 初期 JPY 残高
 * @param btcQuantity BTC 保有数量
 * @param btcMarkPriceJpy BTC 評価価格
 * @param totalEquityJpy 総評価額
 * @param equityPeakJpy 総評価額の過去ピーク
 * @param drawdownRatio equityPeakJpy からの下落率
 * @param updatedAt paper account 更新時刻
 */
@Serializable
data class OpsAccountResponse(
    val mode: String,
    val cashJpy: String,
    val initialCashJpy: String,
    val btcQuantity: String,
    val btcMarkPriceJpy: String,
    val totalEquityJpy: String,
    val equityPeakJpy: String,
    val drawdownRatio: String,
    val updatedAt: String,
)

/**
 * executions raw feed API の response body。
 *
 * @param executions 新しい順の execution 一覧
 */
@Serializable
data class OpsExecutionsResponse(
    val executions: List<OpsExecutionResponse>,
)

/**
 * executions raw feed API の execution 要素。
 *
 * @param executionId execution ID
 * @param orderId 関連 order ID
 * @param positionId 関連 position ID
 * @param mode 取引 mode
 * @param symbol 取引対象 symbol
 * @param side execution side
 * @param priceJpy 約定価格
 * @param sizeBtc 約定数量
 * @param feeJpy 手数料
 * @param realizedPnlJpy 実現損益
 * @param liquidity maker / taker 区分
 * @param executedAt 約定時刻
 */
@Serializable
data class OpsExecutionResponse(
    val executionId: String,
    val orderId: String?,
    val positionId: String?,
    val mode: String,
    val symbol: String,
    val side: String,
    val priceJpy: String,
    val sizeBtc: String,
    val feeJpy: String,
    val realizedPnlJpy: String,
    val liquidity: String,
    val executedAt: String,
)

/**
 * 運用系 route を定義する。
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.opsRoutes(
    riskStateRepository: RiskStateRepository?,
    riskStateCommandService: RiskStateCommandService?,
    manualLlmLaunchService: ManualLlmLaunchService?,
    decisionRepository: DecisionRepository?,
    paperLedgerRepository: PaperLedgerRepository?,
    commandEventFeedReader: CommandEventFeedReader?,
    clock: Clock = Clock.systemUTC(),
) {
    post("/ops/halt") {
        val request = call.receiveBodyOrBadRequest<OpsHaltRequest>() ?: return@post
        val reason = call.requireReason(request.reason) ?: return@post
        val commandService = call.requireRiskStateCommandService(riskStateCommandService) ?: return@post
        val result = when (request.level) {
            OpsHaltLevel.SOFT -> commandService.setSoftHalt(reason, DecisionRunContext.EMPTY)
            OpsHaltLevel.HARD -> commandService.setHardHalt(reason, DecisionRunContext.EMPTY)
        }
        val riskState = call.respondConflictOrThrow(result) ?: return@post

        call.respond(riskState.toOpsRiskStateResponse())
    }.describe {
        summary = "取引停止状態を設定する"
        description = "SOFT_HALT または HARD_HALT を reason 付きで設定し、command_event_log に監査イベントを残します。"
        tag(OPS_TAG)
        requestBody {
            description = "停止 level と停止理由です。"
            required = true
            schema = jsonSchema<OpsHaltRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "更新後の risk_state です。"
                schema = jsonSchema<OpsRiskStateResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "request body または reason が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.Conflict {
                description = "HARD_HALT 中に SOFT_HALT へ downgrade しようとしました。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "risk_state command service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    post("/ops/resume") {
        val request = call.receiveBodyOrBadRequest<OpsResumeRequest>() ?: return@post
        val reason = call.requireReason(request.reason) ?: return@post
        val commandService = call.requireRiskStateCommandService(riskStateCommandService) ?: return@post
        val riskState = commandService.resume(reason, DecisionRunContext.EMPTY).getOrThrow()

        call.respond(riskState.toOpsRiskStateResponse())
    }.describe {
        summary = "取引停止状態を解除する"
        description = "SOFT_HALT または HARD_HALT を RUNNING へ戻し、手動再開理由を監査イベントへ残します。"
        tag(OPS_TAG)
        requestBody {
            description = "再開理由です。"
            required = true
            schema = jsonSchema<OpsResumeRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "更新後の risk_state です。"
                schema = jsonSchema<OpsRiskStateResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "request body または reason が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "risk_state command service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/ops/risk-state") {
        val repository = call.requireRiskStateRepository(riskStateRepository) ?: return@get
        val riskState = repository.current().getOrThrow()

        call.respond(riskState.toOpsRiskStateResponse())
    }.describe {
        summary = "取引停止状態を取得する"
        description = "現在の halt state、停止理由、再開時刻、drawdown ratio を返します。"
        tag(OPS_TAG)
        responses {
            HttpStatusCode.OK {
                description = "現在の risk_state です。"
                schema = jsonSchema<OpsRiskStateResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "risk_state repository が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    post("/ops/trigger") {
        val request = call.receiveBodyOrBadRequest<OpsTriggerRequest>() ?: return@post
        val reason = call.requireReason(request.reason) ?: return@post
        val service = call.requireManualLlmLaunchService(manualLlmLaunchService) ?: return@post
        val result = service.launch(reason).getOrThrow()

        when (result) {
            is ManualLlmLaunchResult.Accepted -> call.respond(
                HttpStatusCode.Accepted,
                OpsTriggerResponse(
                    invocationId = result.invocationId,
                    triggerKind = result.triggerKind.name,
                ),
            )
            is ManualLlmLaunchResult.Rejected -> call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(result.reason),
            )
        }
    }.describe {
        summary = "LLM one-shot を手動起動する"
        description = "reason 付きで MANUAL trigger の起動予約を取得し、runner を HTTP 応答後に非同期実行します。"
        tag(OPS_TAG)
        requestBody {
            description = "手動起動理由です。"
            required = true
            schema = jsonSchema<OpsTriggerRequest>()
        }
        responses {
            HttpStatusCode.Accepted {
                description = "起動予約を取得し、runner を非同期開始しました。"
                schema = jsonSchema<OpsTriggerResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "request body または reason が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.Conflict {
                description = "起動予約または停止状態により手動起動を拒否しました。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "manual LLM launch service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/ops/account") {
        val repository = call.requirePaperLedgerRepository(paperLedgerRepository) ?: return@get
        val accountSnapshot = repository.getAccountSnapshotWithUpdatedAt().getOrThrow()

        call.respond(accountSnapshot.toOpsAccountResponse())
    }.describe {
        summary = "paper account snapshot を取得する"
        description = "paper ledger の account snapshot と更新時刻を返します。fake/demo 値は返しません。"
        tag(OPS_TAG)
        responses {
            HttpStatusCode.OK {
                description = "paper account snapshot です。"
                schema = jsonSchema<OpsAccountResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "paper ledger repository が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/ops/decisions") {
        val limit = call.requireLimit(
            defaultLimit = DEFAULT_DECISIONS_LIMIT,
            maxLimit = MAX_DECISIONS_LIMIT,
        ) ?: return@get
        val repository = call.requireDecisionRepository(decisionRepository) ?: return@get
        val decisions = repository.findDecisionsCreatedBetween(
            from = Instant.EPOCH,
            toExclusive = Instant.now(clock),
            limit = limit,
        )
            .getOrThrow()
            .sortedByDescending { record -> record.decision.createdAt }

        call.respond(
            OpsDecisionsResponse(
                decisions = decisions.map { record -> record.toOpsDecisionResponse() },
            ),
        )
    }.describe {
        summary = "LLM decision の raw feed を取得する"
        description = "最新 decision を集計せずに新しい順で返します。limit は既定 20、最大 100 です。"
        tag(OPS_TAG)
        responses {
            HttpStatusCode.OK {
                description = "decision raw feed です。"
                schema = jsonSchema<OpsDecisionsResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "limit が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "decision repository が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/ops/executions") {
        val limit = call.requireLimit(
            defaultLimit = DEFAULT_EXECUTIONS_LIMIT,
            maxLimit = MAX_EXECUTIONS_LIMIT,
        ) ?: return@get
        val repository = call.requirePaperLedgerRepository(paperLedgerRepository) ?: return@get
        val executions = repository.getExecutions()
            .getOrThrow()
            .sortedByDescending { execution -> Instant.parse(execution.executedAt) }
            .take(limit)

        call.respond(
            OpsExecutionsResponse(
                executions = executions.map { execution -> execution.toOpsExecutionResponse() },
            ),
        )
    }.describe {
        summary = "paper execution の raw feed を取得する"
        description = "paper ledger の execution を新しい順で返します。limit は既定 20、最大 100 です。"
        tag(OPS_TAG)
        responses {
            HttpStatusCode.OK {
                description = "paper execution raw feed です。"
                schema = jsonSchema<OpsExecutionsResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "limit が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "paper ledger repository が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/ops/positions") {
        val repository = call.requirePaperLedgerRepository(paperLedgerRepository) ?: return@get
        val positions = repository.getOpenPositions().getOrThrow()
        val openOrders = repository.getOpenOrders().getOrThrow()

        call.respond(
            OpsPositionsResponse(
                positions = positions,
                openOrders = openOrders,
            ),
        )
    }.describe {
        summary = "open position と open order の raw feed を取得する"
        description = "paper ledger の open position と open order を集計せずに返します。"
        tag(OPS_TAG)
        responses {
            HttpStatusCode.OK {
                description = "open position と open order の raw feed です。"
                schema = jsonSchema<OpsPositionsResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "paper ledger repository が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    get("/ops/audit") {
        val limit = call.requireLimit(
            defaultLimit = DEFAULT_AUDIT_LIMIT,
            maxLimit = MAX_AUDIT_LIMIT,
        ) ?: return@get
        val eventTypeParameter = call.request.queryParameters["eventType"]
        val eventType = if (eventTypeParameter == null) {
            null
        } else {
            call.requireCommandEventType(eventTypeParameter) ?: return@get
        }
        val reader = call.requireCommandEventFeedReader(commandEventFeedReader) ?: return@get
        val events = reader.findEvents(limit, eventType).getOrThrow()

        call.respond(
            OpsAuditResponse(
                events = events.map { event -> event.toOpsAuditEventResponse() },
            ),
        )
    }.describe {
        summary = "command_event_log の raw feed を取得する"
        description = "監査イベントを新しい順で返します。limit は既定 50、最大 200、eventType で任意に絞り込めます。"
        tag(OPS_TAG)
        responses {
            HttpStatusCode.OK {
                description = "audit raw feed です。"
                schema = jsonSchema<OpsAuditResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "limit または eventType が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "command event feed reader が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveBodyOrBadRequest(): T? {
    return try {
        receive<T>()
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (_: Throwable) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("request body is invalid"))

        null
    }
}

private suspend fun ApplicationCall.requireReason(reason: String): String? {
    val trimmedReason = reason.trim()

    if (trimmedReason.isNotEmpty()) {
        return trimmedReason
    }

    respond(HttpStatusCode.BadRequest, ErrorResponse("reason is required"))

    return null
}

private suspend fun ApplicationCall.requireRiskStateRepository(repository: RiskStateRepository?): RiskStateRepository? {
    if (repository != null) {
        return repository
    }

    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("risk state repository is not configured"))

    return null
}

private suspend fun ApplicationCall.requireRiskStateCommandService(
    service: RiskStateCommandService?,
): RiskStateCommandService? {
    if (service != null) {
        return service
    }

    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("risk state command service is not configured"))

    return null
}

private suspend fun ApplicationCall.requireManualLlmLaunchService(
    service: ManualLlmLaunchService?,
): ManualLlmLaunchService? {
    if (service != null) {
        return service
    }

    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("manual LLM launch service is not configured"))

    return null
}

private suspend fun ApplicationCall.requireDecisionRepository(repository: DecisionRepository?): DecisionRepository? {
    if (repository != null) {
        return repository
    }

    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("decision repository is not configured"))

    return null
}

private suspend fun ApplicationCall.requirePaperLedgerRepository(
    repository: PaperLedgerRepository?,
): PaperLedgerRepository? {
    if (repository != null) {
        return repository
    }

    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("paper ledger repository is not configured"))

    return null
}

private suspend fun ApplicationCall.requireCommandEventFeedReader(
    reader: CommandEventFeedReader?,
): CommandEventFeedReader? {
    if (reader != null) {
        return reader
    }

    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("command event feed reader is not configured"))

    return null
}

private suspend fun ApplicationCall.requireLimit(defaultLimit: Int, maxLimit: Int): Int? {
    val rawLimit = request.queryParameters["limit"]?.trim() ?: return defaultLimit
    val parsedLimit = rawLimit.toIntOrNull()
    val limitIsValid = parsedLimit != null && parsedLimit > 0

    if (limitIsValid) {
        return requireNotNull(parsedLimit).coerceAtMost(maxLimit)
    }

    respond(HttpStatusCode.BadRequest, ErrorResponse("limit must be a positive integer"))

    return null
}

private suspend fun ApplicationCall.requireCommandEventType(rawEventType: String): CommandEventType? {
    val eventTypeName = rawEventType.trim()
    val eventType = CommandEventType.entries.firstOrNull { candidate -> candidate.name == eventTypeName }

    if (eventType != null) {
        return eventType
    }

    respond(HttpStatusCode.BadRequest, ErrorResponse("eventType is invalid"))

    return null
}

private suspend fun ApplicationCall.respondConflictOrThrow(result: Result<RiskState>): RiskState? {
    val riskState = result.getOrNull()

    if (riskState != null) {
        return riskState
    }

    val throwable = requireNotNull(result.exceptionOrNull())

    if (throwable is SoftHaltDowngradeRejectedException) {
        val errorMessage = requireNotNull(throwable.message)

        respond(HttpStatusCode.Conflict, ErrorResponse(errorMessage))

        return null
    }

    throw throwable
}

private fun RiskState.toOpsRiskStateResponse(): OpsRiskStateResponse {
    return OpsRiskStateResponse(
        state = state.name,
        haltReason = haltReason,
        haltAt = haltAt?.toString(),
        resumedAt = resumedAt?.toString(),
        resumedReason = resumedReason,
        drawdownRatio = drawdownRatio.toPlainString(),
    )
}

private fun DecisionJournalRecord.toOpsDecisionResponse(): OpsDecisionResponse {
    val submission = decision.submission

    return OpsDecisionResponse(
        id = decision.decisionId.toString(),
        action = submission.action.name,
        setupTags = submission.setupTags,
        estimatedWinProbability = submission.estimatedWinProbability.toPlainString(),
        reasonJa = submission.reasonJa,
        noTradeConditionsJa = submission.noTradeConditionsJa,
        createdAt = decision.createdAt.toString(),
    )
}

private fun CommandEvent.toOpsAuditEventResponse(): OpsAuditEventResponse {
    return OpsAuditEventResponse(
        id = id.toString(),
        eventType = eventType.name,
        toolName = toolName,
        payload = payload,
        occurredAt = occurredAt.toString(),
    )
}

private fun AccountSnapshotWithUpdatedAt.toOpsAccountResponse(): OpsAccountResponse {
    val snapshot = accountSnapshot

    return OpsAccountResponse(
        mode = snapshot.mode.name,
        cashJpy = snapshot.cashJpy,
        initialCashJpy = snapshot.initialCashJpy,
        btcQuantity = snapshot.btcQuantity,
        btcMarkPriceJpy = snapshot.btcMarkPriceJpy,
        totalEquityJpy = snapshot.totalEquityJpy,
        equityPeakJpy = snapshot.equityPeakJpy,
        drawdownRatio = snapshot.drawdownRatio,
        updatedAt = updatedAt.toString(),
    )
}

private fun Execution.toOpsExecutionResponse(): OpsExecutionResponse {
    return OpsExecutionResponse(
        executionId = executionId,
        orderId = orderId,
        positionId = positionId,
        mode = mode.name,
        symbol = symbol,
        side = side.name,
        priceJpy = priceJpy,
        sizeBtc = sizeBtc,
        feeJpy = feeJpy,
        realizedPnlJpy = realizedPnlJpy,
        liquidity = liquidity.name,
        executedAt = executedAt,
    )
}

/**
 * decisions feed の既定 limit。
 */
private const val DEFAULT_DECISIONS_LIMIT = 20

/**
 * decisions feed の最大 limit。
 */
private const val MAX_DECISIONS_LIMIT = 100

/**
 * audit feed の既定 limit。
 */
private const val DEFAULT_AUDIT_LIMIT = 50

/**
 * audit feed の最大 limit。
 */
private const val MAX_AUDIT_LIMIT = 200

/**
 * executions feed の既定 limit。
 */
private const val DEFAULT_EXECUTIONS_LIMIT = 20

/**
 * executions feed の最大 limit。
 */
private const val MAX_EXECUTIONS_LIMIT = 100
