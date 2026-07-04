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
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.risk.RiskState
import me.matsumo.fukurou.trading.risk.RiskStateCommandService
import me.matsumo.fukurou.trading.risk.RiskStateRepository

/**
 * ops API の OpenAPI tag。
 */
private const val OPS_TAG = "ops"

/**
 * SOFT_HALT が HARD_HALT を downgrade しようとした時の内部エラー文。
 */
private const val SOFT_HALT_DOWNGRADE_ERROR = "SOFT_HALT cannot downgrade HARD_HALT."

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
 * risk_state API の response body。
 *
 * @param state 現在の halt state
 * @param haltReason 最後に halt した理由
 * @param haltAt 最後に halt した時刻
 * @param resumedAt 最後に resume した時刻
 * @param drawdownRatio 現在の drawdown ratio
 */
@Serializable
data class OpsRiskStateResponse(
    val state: String,
    val haltReason: String?,
    val haltAt: String?,
    val resumedAt: String?,
    val drawdownRatio: String,
)

/**
 * 運用系 route を定義する。
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.opsRoutes(
    riskStateRepository: RiskStateRepository?,
    riskStateCommandService: RiskStateCommandService?,
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

private suspend fun ApplicationCall.respondConflictOrThrow(result: Result<RiskState>): RiskState? {
    val riskState = result.getOrNull()

    if (riskState != null) {
        return riskState
    }

    val throwable = requireNotNull(result.exceptionOrNull())

    if (throwable.message == SOFT_HALT_DOWNGRADE_ERROR) {
        respond(HttpStatusCode.Conflict, ErrorResponse(SOFT_HALT_DOWNGRADE_ERROR))

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
        drawdownRatio = drawdownRatio.toPlainString(),
    )
}
