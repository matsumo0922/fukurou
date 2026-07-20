package me.matsumo.fukurou

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.matsumo.fukurou.trading.broker.PaperLedgerRepository
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationRepository
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.market.InjectedWebSocketDisconnector
import me.matsumo.fukurou.trading.market.MarketDataConnectionState
import me.matsumo.fukurou.trading.market.MarketDataIntegrityRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * 一時的な fault-injection seam を有効化する deployment flag。既定は false。
 */
internal const val ISSUE_192_WS_FAULT_ENABLED_ENV = "FUKUROU_ISSUE_192_WS_FAULT_ENABLED"

/**
 * Issue #192 の一時的な WebSocket 切断 route の path。
 */
internal const val ISSUE_192_WS_DISCONNECT_PATH = "/ops/issue-192/ws-disconnect"

/**
 * deployment flag を読む。明示的に `true` の場合だけ seam を構築する。
 */
internal fun issue192WsFaultEnabledFromEnv(environment: Map<String, String> = System.getenv()): Boolean {
    return environment[ISSUE_192_WS_FAULT_ENABLED_ENV]?.trim()?.equals("true", ignoreCase = true) == true
}

/**
 * 先に構築する routing と後で起動する worker を橋渡しする application-scoped holder。
 *
 * process-global mutable singleton や companion object は使わず、`Application.module` の 1 instance に閉じる。
 */
internal class Issue192WsFaultHolder {
    private val disconnector = AtomicReference<InjectedWebSocketDisconnector?>()

    fun publish(value: InjectedWebSocketDisconnector) {
        disconnector.set(value)
    }

    fun current(): InjectedWebSocketDisconnector? = disconnector.get()
}

/**
 * 切断 request の body。
 *
 * @param injectionId 1 回の注入ごとに operator が新規発行する ID
 * @param expectedSessionId 最終 preflight で固定した active market-data session ID
 * @param targetOrderId owner が承認した resting BUY entry order ID
 * @param expectedOrderExpiresAt owner 承認時に固定した order expiry
 * @param minimumRemainingTtlSeconds 実行時に必要な最小 remaining TTL 秒数
 * @param purpose 固定値 `ISSUE_192_WS_DISCONNECT`
 * @param reason owner 承認を結びつけた実行理由
 */
@Serializable
internal data class Issue192WsDisconnectRequest(
    val injectionId: String,
    val expectedSessionId: String,
    val targetOrderId: String,
    val expectedOrderExpiresAt: String,
    val minimumRemainingTtlSeconds: Long,
    val purpose: String,
    val reason: String,
)

/**
 * 切断 request の成功 response。
 *
 * @param injectionId 実行した注入 ID
 * @param sessionId 切断した market-data session ID
 */
@Serializable
internal data class Issue192WsDisconnectResponse(
    val injectionId: String,
    val sessionId: String,
)

/**
 * 一時的な切断 route を登録する。deployment flag が true の revision だけが呼び出す。
 *
 * 外部入口は ops route 配下を保護する既存の Cloudflare Access policy に委ね、専用 token は追加しない。
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.issue192WsFaultRoute(controller: Issue192WsFaultController) {
    post(ISSUE_192_WS_DISCONNECT_PATH) {
        val request = try {
            call.receive<Issue192WsDisconnectRequest>()
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (_: Throwable) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("request body is invalid"))
            return@post
        }
        val command = issue192WsDisconnectCommand(request)

        if (command == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("request is invalid"))
            return@post
        }

        when (val result = controller.disconnect(command)) {
            is Issue192WsDisconnectResult.Executed -> call.respond(
                Issue192WsDisconnectResponse(
                    injectionId = result.injectionId.toString(),
                    sessionId = result.sessionId.toString(),
                ),
            )

            is Issue192WsDisconnectResult.Conflict -> call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(result.code),
            )

            is Issue192WsDisconnectResult.Unavailable -> call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(result.code),
            )
        }
    }.hide()
}

/**
 * preflight が使う既存 read repository のまとまり。
 */
internal data class Issue192WsFaultPreflightRepositories(
    val ledger: PaperLedgerRepository,
    val marketDataIntegrity: MarketDataIntegrityRepository,
    val monitoring: MonitoringRepository,
    val launchReservation: LlmLaunchReservationRepository,
)

/**
 * production repository から `WS-DISCONNECT` arm の preflight 事実を bounded read で組み立てる。
 *
 * @param tradingConfig active runtime config から解決した trading config
 * @param runtimeConfigSnapshot active runtime config の監査 snapshot
 * @param repositories 注入前に参照する既存 read repository
 * @param clock reservation の鮮度判定に使う clock
 */
internal class DefaultIssue192WsFaultPreflight(
    private val tradingConfig: TradingBotConfig,
    private val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    private val repositories: Issue192WsFaultPreflightRepositories,
    private val clock: Clock,
) : Issue192WsFaultPreflight {

    override suspend fun read(): Result<Issue192WsFaultPreflightState> {
        return runCatching {
            val account = repositories.ledger.getAccountSnapshot().getOrThrow()
            val integrity = repositories.marketDataIntegrity.snapshot().getOrThrow()
            val gaps = repositories.monitoring.unresolvedGaps().getOrThrow()
            val openPositions = repositories.ledger.getOpenPositions().getOrThrow()
            val reservationActiveSince = clock.instant().minus(tradingConfig.daemon.launchReservationStaleAfter)
            val hasActiveReservation = repositories.launchReservation
                .hasFreshRunningReservation(reservationActiveSince)
                .getOrThrow()
            val openOrders = repositories.ledger.getOpenOrders().getOrThrow()
            val ordersObservedAt = clock.instant()
            val connected = integrity.state == MarketDataConnectionState.CONNECTED
            val restingBuyEntries = openOrders.count { order ->
                order.side == OrderSide.BUY && order.status == OrderStatus.OPEN && order.positionId == null
            }
            val pendingCancelRestingBuyEntries = openOrders.count { order ->
                order.side == OrderSide.BUY && order.status == OrderStatus.PENDING_CANCEL && order.positionId == null
            }
            val orderSnapshots = openOrders.map { order ->
                Issue192OrderPreflightState(
                    orderId = order.orderId,
                    side = order.side,
                    status = order.status,
                    positionId = order.positionId,
                    expiresAt = order.expiresAt?.let { expiresAt ->
                        runCatching { Instant.parse(expiresAt) }.getOrNull()
                    },
                )
            }

            Issue192WsFaultPreflightState(
                paperMode = tradingConfig.mode == TradingMode.PAPER && account.mode == TradingMode.PAPER,
                accountEpochId = account.accountEpochId,
                runtimeConfigVersionId = runtimeConfigSnapshot?.versionId,
                accountBaselineMatchesRuntimeConfig = account.initialCashJpy.matchesBaseline(
                    tradingConfig.paperAccount.initialCashJpy,
                ),
                activeSessionId = integrity.sessionId?.takeIf { connected },
                unresolvedMarketDataGapCount = gaps.marketDataCount,
                restingBuyEntryOrderCount = restingBuyEntries,
                pendingCancelRestingBuyEntryOrderCount = pendingCancelRestingBuyEntries,
                orderSnapshots = orderSnapshots,
                observedAt = ordersObservedAt,
                openPositionCount = openPositions.size,
                activeLlmWorkPresent = hasActiveReservation,
            )
        }
    }
}

/** 表記揺れを含めて baseline 金額が一致するかを判定する。 */
private fun String.matchesBaseline(expected: BigDecimal): Boolean {
    val actual = runCatching { BigDecimal(this) }.getOrNull() ?: return false

    return actual.compareTo(expected) == 0
}
