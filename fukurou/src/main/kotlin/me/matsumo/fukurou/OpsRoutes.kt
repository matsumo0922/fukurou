@file:Suppress("ImportOrdering")

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
import me.matsumo.fukurou.trading.activity.DecisionRunProjectionRepository
import me.matsumo.fukurou.trading.broker.AccountSnapshotWithUpdatedAt
import me.matsumo.fukurou.trading.broker.ExecutionActivityOrderContext
import me.matsumo.fukurou.trading.broker.ExecutionActivityRecord
import me.matsumo.fukurou.trading.broker.PaperLedgerRepository
import me.matsumo.fukurou.trading.config.RuntimeConfigActivationResult
import me.matsumo.fukurou.trading.config.PaperAccountEpochSwitchRejectedException
import me.matsumo.fukurou.trading.config.RuntimeConfigAdminService
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.RuntimeConfigDraftCreation
import me.matsumo.fukurou.trading.config.RuntimeConfigSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigSnapshotWarning
import me.matsumo.fukurou.trading.config.RuntimeConfigValidationRejectedException
import me.matsumo.fukurou.trading.config.RuntimeConfigValidationError
import me.matsumo.fukurou.trading.config.RuntimeConfigVersionDetail
import me.matsumo.fukurou.trading.config.RuntimeConfigVersionSummary
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchResult
import me.matsumo.fukurou.trading.daemon.ManualLlmLaunchService
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.domain.Execution
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.reconciler.LatestMarketQuoteStore
import me.matsumo.fukurou.trading.feed.StableFeedCursor
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
 * CLI auth status API の response body。
 *
 * @param providers provider 別 login 状態
 * @param checkedAt 状態を確認した時刻
 */
@Serializable
data class OpsLlmAuthResponse(
    val providers: List<OpsLlmAuthProviderResponse>,
    val checkedAt: String,
)

/**
 * CLI auth status API の provider 要素。
 *
 * @param provider provider wire name
 * @param displayName UI 表示名
 * @param status login 状態
 * @param detail secret を含まない補足
 * @param homePath login state の home path
 * @param checkedAt 状態を確認した時刻
 */
@Serializable
data class OpsLlmAuthProviderResponse(
    val provider: String,
    val displayName: String,
    val status: String,
    val detail: String?,
    val homePath: String,
    val checkedAt: String,
)

/**
 * CLI auth login API の request body。
 *
 * @param reason login flow を開始する理由
 */
@Serializable
data class OpsLlmAuthLoginRequest(
    val reason: String,
)

/**
 * CLI auth login API の response body。
 *
 * @param provider provider wire name
 * @param sessionId login session ID
 * @param status login process 状態
 * @param authorizationUrl browser 承認用 URL
 * @param userCode device auth code
 * @param tokenSubmitAvailable token/code submit を受け付けるか
 * @param tokenSubmitted token/code submit 済みか
 * @param detail secret を含まない補足
 * @param startedAt 開始時刻
 * @param expiresAt timeout 時刻
 * @param completedAt 完了時刻
 */
@Serializable
data class OpsLlmAuthLoginResponse(
    val provider: String,
    val sessionId: String,
    val status: String,
    val authorizationUrl: String?,
    val userCode: String?,
    val tokenSubmitAvailable: Boolean,
    val tokenSubmitted: Boolean,
    val detail: String?,
    val startedAt: String,
    val expiresAt: String,
    val completedAt: String?,
)

/**
 * Claude Code CLI auth token/code submit API の request body。
 *
 * @param token ブラウザ認可後に CLI へ渡す token。API は値を保存・返却しない
 * @param code ブラウザ認可後に CLI へ渡す code。API は値を保存・返却しない
 */
@Serializable
data class OpsLlmAuthTokenSubmitRequest(
    val token: String? = null,
    val code: String? = null,
)

/**
 * Claude Code CLI auth token/code submit API の response body。
 *
 * @param provider provider wire name
 * @param sessionId login session ID
 * @param status login process 状態
 * @param tokenSubmitted token/code submit 済みか
 * @param detail secret を含まない補足
 */
@Serializable
data class OpsLlmAuthTokenSubmitResponse(
    val provider: String,
    val sessionId: String,
    val status: String,
    val tokenSubmitted: Boolean,
    val detail: String?,
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
 * @param noTradeConditionsJa NO_TRADE 時に次回評価へ残した entry trigger / invalidation 条件
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
 * @param sellExecutions open position に紐づく SELL execution 一覧
 */
@Serializable
data class OpsPositionsResponse(
    val positions: List<Position>,
    val openOrders: List<Order>,
    val sellExecutions: List<OpsExecutionResponse>,
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
    val accountEpochId: String? = null,
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
    val accountEpochId: String? = null,
    val executionSemanticsVersion: String? = null,
    val runtimeConfigHash: String? = null,
)

/**
 * Activity timeline API の source。
 */
private enum class OpsActivitySource(
    val wireName: String,
) {
    /**
     * LLM decision。
     */
    DECISION("decision"),

    /**
     * command_event_log audit event。
     */
    AUDIT("audit"),

    /**
     * paper ledger execution。
     */
    EXECUTION("execution"),
}

/**
 * Activity timeline の cursor。
 *
 * @param occurredAt cursor 境界の発生時刻
 * @param source 同一時刻 tie-break 用 source。null の場合は同一 timestamp を含めない
 * timestamp-only cursor として扱う
 * @param eventId 同一時刻 tie-break 用 event ID。null の場合は同一 timestamp を含めない
 * timestamp-only cursor として扱う
 * @param sourceEventId source reader に渡す prefix なしの event ID。null の場合は同一 timestamp を含めない
 * timestamp-only cursor として扱う
 */
private data class OpsActivityCursor(
    val occurredAt: Instant,
    val source: OpsActivitySource?,
    val eventId: String?,
    val sourceEventId: String?,
) {
    fun hasTieBreaker(): Boolean {
        val hasSource = source != null
        val hasEventId = eventId != null
        val hasSourceEventId = sourceEventId != null

        return hasSource && hasEventId && hasSourceEventId
    }

    fun toStableFeedCursor(targetSource: OpsActivitySource): StableFeedCursor {
        if (!hasTieBreaker()) {
            return StableFeedCursor(
                occurredAt = occurredAt,
                includesSameTimestamp = false,
                afterId = null,
            )
        }

        val cursorSource = requireNotNull(this.source)
        val sourceComparison = targetSource.wireName.compareTo(cursorSource.wireName)

        if (sourceComparison < 0) {
            return StableFeedCursor(
                occurredAt = occurredAt,
                includesSameTimestamp = false,
                afterId = null,
            )
        }

        if (sourceComparison > 0) {
            return StableFeedCursor(
                occurredAt = occurredAt,
                includesSameTimestamp = true,
                afterId = null,
            )
        }

        return StableFeedCursor(
            occurredAt = occurredAt,
            includesSameTimestamp = true,
            afterId = requireNotNull(sourceEventId),
        )
    }
}

/**
 * Activity timeline event と parse 済み timestamp をまとめた sort key。
 *
 * @param event Activity timeline に返す event
 * @param occurredAt sort に使う発生時刻
 */
private data class OpsActivitySortableEvent(
    val event: OpsActivityEventResponse,
    val occurredAt: Instant,
)

/**
 * execution Activity event の分類 label。
 *
 * @param kind API kind
 * @param title UI 表示用 title
 */
private data class ExecutionActivityLabel(
    val kind: String,
    val title: String,
)

/**
 * Activity timeline API の response body。
 *
 * @param events 新しい順の timeline event 一覧
 * @param nextBefore 次の古いページ取得に使う opaque cursor。これ以上古い event がない場合は null
 * @param limit API が適用したページ上限
 */
@Serializable
data class OpsActivityResponse(
    val events: List<OpsActivityEventResponse>,
    val nextBefore: String?,
    val limit: Int,
)

/**
 * Activity timeline API の event 要素。
 *
 * @param id source prefix 付きの timeline event ID
 * @param source decision / audit / execution の source
 * @param kind source ごとの event 種別
 * @param title UI 表示用 title
 * @param detail UI 表示用 detail
 * @param occurredAt 発生時刻
 * @param metadata timeline list に表示する短い metadata
 * @param details click-open dialog に表示する詳細 metadata
 */
@Serializable
data class OpsActivityEventResponse(
    val id: String,
    val source: String,
    val kind: String,
    val title: String,
    val detail: String,
    val occurredAt: String,
    val metadata: List<OpsActivityMetadataResponse>,
    val details: OpsActivityDetailsResponse? = null,
)

/**
 * Activity timeline API の詳細 payload。
 *
 * @param title dialog title
 * @param metadata click-open dialog に表示する詳細 metadata
 */
@Serializable
data class OpsActivityDetailsResponse(
    val title: String,
    val metadata: List<OpsActivityMetadataResponse>,
)

/**
 * Activity timeline API の metadata 要素。
 *
 * @param label metadata label
 * @param value metadata value
 */
@Serializable
data class OpsActivityMetadataResponse(
    val label: String,
    val value: String,
)

/**
 * Activity 表示用 catalog API の response body。
 *
 * @param sourceFilters Activity source filter の表示定義
 * @param auditEventTypes audit event_type の表示定義
 * @param decisionActions decision action の表示定義
 * @param defaultExcludedAuditEventTypes auditEventType 未指定時に activity timeline から既定除外する event_type
 */
@Serializable
data class OpsActivityCatalogResponse(
    val sourceFilters: List<OpsActivityCatalogItemResponse>,
    val auditEventTypes: List<OpsActivityCatalogItemResponse>,
    val decisionActions: List<OpsActivityCatalogItemResponse>,
    val defaultExcludedAuditEventTypes: List<String>,
)

/**
 * Activity 表示用 catalog の項目。
 *
 * @param value API filter / kind / localStorage で使う raw wire value
 * @param labelKey WebUI i18n label key
 * @param descriptionKey WebUI i18n description key
 */
@Serializable
data class OpsActivityCatalogItemResponse(
    val value: String,
    val labelKey: String,
    val descriptionKey: String,
)

/**
 * runtime config catalog route の依存関係。
 *
 * @param snapshotProvider active runtime config の現在状態 provider
 * @param adminService runtime config の draft / validate / activate 操作用 service
 */
internal data class OpsRuntimeConfigRouteDependencies(
    val snapshotProvider: OpsRuntimeConfigSnapshotProvider,
    val adminService: RuntimeConfigAdminService? = null,
)

/**
 * runtime config catalog route が参照する現在状態。
 *
 * @param tradingConfig 取引 bot 全体の typed config
 * @param environment runtime config catalog API で参照する環境変数 map
 * @param warnings active runtime config の warning
 */
internal data class OpsRuntimeConfigRouteSnapshot(
    val tradingConfig: TradingBotConfig,
    val environment: Map<String, String>,
    val warnings: List<RuntimeConfigSnapshotWarning> = emptyList(),
)

/**
 * runtime config catalog route が現在状態を読む境界。
 */
internal fun interface OpsRuntimeConfigSnapshotProvider {
    fun snapshot(): OpsRuntimeConfigRouteSnapshot
}

/**
 * runtime config draft 作成 API の request body。
 *
 * @param baseVersionId draft の基準にする version ID。null の場合は active version
 * @param values 変更する runtime config key と候補値
 * @param note secret を含まない補足
 */
@Serializable
data class OpsRuntimeConfigDraftRequest(
    val baseVersionId: String? = null,
    val values: Map<String, String>,
    val note: String? = null,
)

/**
 * runtime config version 操作 API の request body。
 *
 * @param reason secret を含まない操作理由
 */
@Serializable
data class OpsRuntimeConfigVersionActionRequest(
    val reason: String? = null,
)

/** account epoch switch の zero-open-risk rejection。 */
@Serializable
data class OpsPaperAccountEpochSwitchConflictResponse(
    val valid: Boolean = false,
    val errors: List<RuntimeConfigValidationError> = emptyList(),
    val code: String = "PAPER_ACCOUNT_EPOCH_SWITCH_REJECTED",
    val openPositionCount: Int,
    val openOrderCount: Int,
    val btcQuantity: String,
    val type: String = "me.matsumo.fukurou.OpsPaperAccountEpochSwitchConflictResponse",
) : OpsRuntimeConfigConflictResponse

/** runtime config activation の validation / epoch gate 競合 union contract。 */
@Serializable
sealed interface OpsRuntimeConfigConflictResponse

/** validation rejection の実レスポンスと同形状の OpenAPI contract。 */
@Serializable
data class OpsRuntimeConfigValidationConflictResponse(
    val valid: Boolean,
    val errors: List<RuntimeConfigValidationError>,
    val type: String = "me.matsumo.fukurou.OpsRuntimeConfigValidationConflictResponse",
) : OpsRuntimeConfigConflictResponse

/**
 * ops risk 操作用 route の依存関係。
 *
 * @param riskStateRepository risk_state repository
 * @param riskStateCommandService risk_state command service
 * @param manualLlmLaunchService manual LLM launch service
 * @param runtimeAvailabilityProvider 取引 runtime が利用可能かを読む境界
 */
internal data class OpsRiskRouteDependencies(
    val riskStateRepository: RiskStateRepository?,
    val riskStateCommandService: RiskStateCommandService?,
    val manualLlmLaunchService: ManualLlmLaunchService?,
    val runtimeAvailabilityProvider: OpsRuntimeAvailabilityProvider = OpsRuntimeAvailabilityProvider { true },
)

/**
 * 取引 runtime が利用可能かを読む境界。
 */
internal fun interface OpsRuntimeAvailabilityProvider {
    fun isAvailable(): Boolean
}

/**
 * ops CLI auth route の依存関係。
 *
 * @param llmAuthService CLI auth service
 */
internal data class OpsAuthRouteDependencies(
    val llmAuthService: LlmAuthService?,
)

/**
 * ops feed 取得 route の依存関係。
 *
 * @param decisionRepository decision repository
 * @param paperLedgerRepository paper ledger repository
 * @param commandEventFeedReader command_event_log feed reader
 * @param latestMarketQuoteStore reconciler が更新する最新気配値 store
 */
internal data class OpsFeedRouteDependencies(
    val decisionRepository: DecisionRepository?,
    val paperLedgerRepository: PaperLedgerRepository?,
    val commandEventFeedReader: CommandEventFeedReader?,
    val decisionRunProjectionRepository: DecisionRunProjectionRepository? = null,
    val latestMarketQuoteStore: LatestMarketQuoteStore = LatestMarketQuoteStore(),
)

/**
 * ops route 全体の依存関係。
 *
 * @param runtimeConfig runtime config catalog route の依存関係
 * @param risk risk 操作用 route の依存関係
 * @param auth CLI auth route の依存関係
 * @param feed feed 取得 route の依存関係
 * @param clock 既定時刻と cursor 検証に使う clock
 */
internal data class OpsRouteDependencies(
    val runtimeConfig: OpsRuntimeConfigRouteDependencies,
    val risk: OpsRiskRouteDependencies,
    val auth: OpsAuthRouteDependencies,
    val feed: OpsFeedRouteDependencies,
    val clock: Clock = Clock.systemUTC(),
)

/**
 * 運用系 route を定義する。
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.opsRoutes(dependencies: OpsRouteDependencies) {
    registerOpsRuntimeConfigRoute(dependencies)
    registerOpsHaltRoute(dependencies)
    registerOpsResumeRoute(dependencies)
    registerOpsRiskStateRoute(dependencies)
    registerOpsTriggerRoute(dependencies)
    registerOpsLlmAuthRoutes(dependencies)
    registerOpsAccountRoute(dependencies)
    registerOpsDecisionsRoute(dependencies)
    registerOpsExecutionsRoute(dependencies)
    registerOpsActivityRoute(dependencies)
    registerOpsDecisionRunRoutes(dependencies)
    registerOpsPositionsRoute(dependencies)
    registerOpsAuditRoute(dependencies)
}

@OptIn(ExperimentalKtorApi::class)
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun Route.registerOpsRuntimeConfigRoute(dependencies: OpsRouteDependencies) {
    val runtimeConfig = dependencies.runtimeConfig
    val adminService = runtimeConfig.adminService

    get("/ops/runtime-config") {
        val runtimeConfigSnapshot = runtimeConfig.snapshotProvider.snapshot()
        val versionsResult = adminService?.listVersions()
        val versions = versionsResult?.getOrNull().orEmpty()
        val warnings = runtimeConfigSnapshot.warnings + versionHistoryWarning(versionsResult)

        call.respond(
            RuntimeConfigCatalog.snapshot(
                tradingConfig = runtimeConfigSnapshot.tradingConfig,
                environment = runtimeConfigSnapshot.environment,
            ).copy(
                activeVersion = versions.firstOrNull { version -> version.status == "ACTIVE" },
                versions = versions,
                warnings = warnings,
            ),
        )
    }.describe {
        summary = "runtime config catalog を取得する"
        description = "code-owned catalog から実効 runtime config と version 履歴を返します。version 履歴が一時的に取得できない場合も catalog と warning を返します。secret は設定有無だけを返し、値は返しません。"
        tag(OPS_TAG)
        responses {
            HttpStatusCode.OK {
                description = "runtime config catalog と version 履歴です。"
                schema = jsonSchema<RuntimeConfigSnapshot>()
            }
        }
    }

    post("/ops/runtime-config/drafts") {
        val request = call.receiveBodyOrBadRequest<OpsRuntimeConfigDraftRequest>() ?: return@post
        val service = call.requireRuntimeConfigAdminService(adminService) ?: return@post
        val result = service.createDraft(
            RuntimeConfigDraftCreation(
                baseVersionId = request.baseVersionId,
                values = request.values,
                note = request.note,
                createdBy = "webui",
            ),
        )
        val response = call.respondRuntimeConfigResult(result) ?: return@post

        call.respond(HttpStatusCode.Created, response)
    }.describe {
        summary = "runtime config draft を作成する"
        description = "active または指定 version を基準に runtime config draft を作成し、現在の catalog / typed config で検証した結果を返します。"
        tag(OPS_TAG)
        requestBody {
            description = "draft の基準 version と変更値です。"
            required = true
            schema = jsonSchema<OpsRuntimeConfigDraftRequest>()
        }
        responses {
            HttpStatusCode.Created {
                description = "作成した draft と validation 結果です。"
                schema = jsonSchema<RuntimeConfigVersionDetail>()
            }
            HttpStatusCode.BadRequest {
                description = "request body、version ID、または変更対象 key が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "runtime config admin service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    post("/ops/runtime-config/drafts/{versionId}/validate") {
        call.receiveBodyOrBadRequest<OpsRuntimeConfigVersionActionRequest>() ?: return@post
        val versionId = call.requirePathValue(call.parameters["versionId"], "versionId is required") ?: return@post
        val service = call.requireRuntimeConfigAdminService(adminService) ?: return@post
        val response = call.respondRuntimeConfigResult(service.validateVersion(versionId)) ?: return@post

        call.respond(response)
    }.describe {
        summary = "runtime config draft を検証する"
        description = "保存済み draft を現在の catalog / typed config に対して再検証します。"
        tag(OPS_TAG)
        parameters {
            path("versionId") {
                description = "検証対象 draft の version ID です。"
                schema = jsonSchema<String>()
            }
        }
        requestBody {
            description = "操作理由です。省略できます。"
            required = true
            schema = jsonSchema<OpsRuntimeConfigVersionActionRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "draft と validation 結果です。"
                schema = jsonSchema<RuntimeConfigVersionDetail>()
            }
            HttpStatusCode.BadRequest {
                description = "version ID が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "runtime config admin service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    post("/ops/runtime-config/drafts/{versionId}/activate") {
        val request = call.receiveBodyOrBadRequest<OpsRuntimeConfigVersionActionRequest>() ?: return@post
        val versionId = call.requirePathValue(call.parameters["versionId"], "versionId is required") ?: return@post
        val service = call.requireRuntimeConfigAdminService(adminService) ?: return@post
        val result = service.activateDraftWithContext(
            versionId = versionId,
            reason = request.reason?.takeIf(String::isNotBlank) ?: "runtime config activation",
            actor = "webui",
        )
        val response = call.respondRuntimeConfigResult(result) ?: return@post

        call.respond(response)
    }.describe {
        summary = "runtime config draft を active 化する"
        description = "保存済み draft を現在の catalog / typed config で再検証してから active 化します。"
        tag(OPS_TAG)
        parameters {
            path("versionId") {
                description = "active 化する draft の version ID です。"
                schema = jsonSchema<String>()
            }
        }
        requestBody {
            description = "操作理由です。省略できます。"
            required = true
            schema = jsonSchema<OpsRuntimeConfigVersionActionRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "active 化した version と validation 結果です。"
                schema = jsonSchema<RuntimeConfigActivationResult>()
            }
            HttpStatusCode.BadRequest {
                description = "version ID または version 状態が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.Conflict {
                description = "validation または account epoch の zero-open-risk gate により拒否されました。"
                schema = jsonSchema<OpsRuntimeConfigConflictResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "runtime config admin service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }

    post("/ops/runtime-config/versions/{versionId}/rollback") {
        val request = call.receiveBodyOrBadRequest<OpsRuntimeConfigVersionActionRequest>() ?: return@post
        val versionId = call.requirePathValue(call.parameters["versionId"], "versionId is required") ?: return@post
        val service = call.requireRuntimeConfigAdminService(adminService) ?: return@post
        val result = service.rollbackToVersionWithContext(
            versionId = versionId,
            reason = request.reason?.takeIf(String::isNotBlank) ?: "runtime config rollback",
            actor = "webui",
        )
        val response = call.respondRuntimeConfigResult(result) ?: return@post

        call.respond(response)
    }.describe {
        summary = "runtime config version へ rollback する"
        description = "保存済み inactive version を現在の catalog / typed config で再検証してから active 化します。"
        tag(OPS_TAG)
        parameters {
            path("versionId") {
                description = "rollback 先の version ID です。"
                schema = jsonSchema<String>()
            }
        }
        requestBody {
            description = "操作理由です。省略できます。"
            required = true
            schema = jsonSchema<OpsRuntimeConfigVersionActionRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "rollback 後の active version と validation 結果です。"
                schema = jsonSchema<RuntimeConfigActivationResult>()
            }
            HttpStatusCode.BadRequest {
                description = "version ID または version 状態が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.Conflict {
                description = "validation または account epoch の zero-open-risk gate により拒否されました。"
                schema = jsonSchema<OpsRuntimeConfigConflictResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "runtime config admin service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

private fun versionHistoryWarning(
    result: Result<List<RuntimeConfigVersionSummary>>?,
): List<RuntimeConfigSnapshotWarning> {
    if (result == null || result.isSuccess) {
        return emptyList()
    }

    return listOf(RuntimeConfigSnapshotWarning(code = "runtimeConfig.warning.versionHistoryUnavailable"))
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsHaltRoute(dependencies: OpsRouteDependencies) {
    val riskStateCommandService = dependencies.risk.riskStateCommandService

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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsResumeRoute(dependencies: OpsRouteDependencies) {
    val riskStateCommandService = dependencies.risk.riskStateCommandService

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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsRiskStateRoute(dependencies: OpsRouteDependencies) {
    val riskStateRepository = dependencies.risk.riskStateRepository

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

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsTriggerRoute(dependencies: OpsRouteDependencies) {
    val manualLlmLaunchService = dependencies.risk.manualLlmLaunchService
    val runtimeAvailabilityProvider = dependencies.risk.runtimeAvailabilityProvider

    post("/ops/trigger") {
        val request = call.receiveBodyOrBadRequest<OpsTriggerRequest>() ?: return@post
        val reason = call.requireReason(request.reason) ?: return@post

        if (!runtimeAvailabilityProvider.isAvailable()) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("runtime config is unavailable"))

            return@post
        }

        val service = call.requireManualLlmLaunchService(manualLlmLaunchService) ?: return@post

        when (val result = service.launch(reason).getOrThrow()) {
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
        description = "reason 付きで MANUAL trigger の起動予約を取得し、runner を HTTP 応答後に非同期実行します。llm.launchEnabled=false の場合は LLM_LAUNCH_DISABLED で拒否します。"
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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsLlmAuthRoutes(dependencies: OpsRouteDependencies) {
    val llmAuthService = dependencies.auth.llmAuthService

    registerOpsLlmAuthStatusRoute(llmAuthService)
    registerOpsLlmAuthLoginStartRoute(llmAuthService)
    registerOpsLlmAuthLoginSessionRoute(llmAuthService)
    registerOpsLlmAuthTokenSubmitRoute(llmAuthService)
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsLlmAuthStatusRoute(llmAuthService: LlmAuthService?) {
    get("/ops/llm-auth") {
        val service = call.requireLlmAuthService(llmAuthService) ?: return@get
        val snapshot = service.snapshot().getOrThrow()

        call.respond(snapshot.toOpsLlmAuthResponse())
    }.describe {
        summary = "CLI auth 状態を取得する"
        description = "Claude Code / Codex CLI の login state を専用 endpoint で返します。token や credential file の内容は返しません。"
        tag(OPS_TAG)
        responses {
            HttpStatusCode.OK {
                description = "CLI auth provider 別状態です。"
                schema = jsonSchema<OpsLlmAuthResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "CLI auth service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsLlmAuthLoginStartRoute(llmAuthService: LlmAuthService?) {
    post("/ops/llm-auth/{provider}/login") {
        val provider = call.requireLlmAuthProvider(call.parameters["provider"]) ?: return@post
        val request = call.receiveBodyOrBadRequest<OpsLlmAuthLoginRequest>() ?: return@post
        val reason = call.requireReason(request.reason) ?: return@post
        val service = call.requireLlmAuthService(llmAuthService) ?: return@post

        when (val result = service.startLogin(provider, reason).getOrThrow()) {
            is LlmAuthLoginStartResult.Accepted -> call.respond(
                HttpStatusCode.Accepted,
                result.session.toOpsLlmAuthLoginResponse(),
            )
            is LlmAuthLoginStartResult.Rejected -> call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(result.reason),
            )
        }
    }.describe {
        summary = "CLI auth login flow を開始する"
        description = "Claude Code / Codex CLI の login flow を reason 付きで開始します。応答には token や credential file の内容を含めません。"
        tag(OPS_TAG)
        parameters {
            path("provider") {
                description = "claude または codex です。"
                schema = jsonSchema<String>()
            }
        }
        requestBody {
            description = "login flow を開始する理由です。"
            required = true
            schema = jsonSchema<OpsLlmAuthLoginRequest>()
        }
        responses {
            HttpStatusCode.Accepted {
                description = "login process を開始しました。"
                schema = jsonSchema<OpsLlmAuthLoginResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "provider、request body、または reason が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.Conflict {
                description = "同じ provider の login process がすでに実行中です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "CLI auth service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsLlmAuthLoginSessionRoute(llmAuthService: LlmAuthService?) {
    get("/ops/llm-auth/{provider}/login/{sessionId}") {
        val provider = call.requireLlmAuthProvider(call.parameters["provider"]) ?: return@get
        val sessionId = call.requirePathValue(call.parameters["sessionId"], "sessionId is required") ?: return@get
        val service = call.requireLlmAuthService(llmAuthService) ?: return@get
        val session = service.loginSession(provider, sessionId).getOrThrow()

        if (session == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("login session not found"))

            return@get
        }

        call.respond(session.toOpsLlmAuthLoginResponse())
    }.describe {
        summary = "CLI auth login session を取得する"
        description = "開始済み login flow の現在状態と、CLI が出した authorization URL / user code を返します。token や credential file の内容は返しません。"
        tag(OPS_TAG)
        parameters {
            path("provider") {
                description = "claude または codex です。"
                schema = jsonSchema<String>()
            }
            path("sessionId") {
                description = "login start 応答の sessionId です。"
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "login session の現在状態です。"
                schema = jsonSchema<OpsLlmAuthLoginResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "provider または sessionId が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.NotFound {
                description = "login session が見つかりません。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "CLI auth service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsLlmAuthTokenSubmitRoute(llmAuthService: LlmAuthService?) {
    post("/ops/llm-auth/{provider}/login/{sessionId}/token") {
        call.respondOpsLlmAuthTokenSubmit(llmAuthService)
    }.describe {
        summary = "Claude Code CLI auth token/code を送信する"
        description = "active な Claude Code login session の stdin へ token/code を 1 回だけ送信します。token/code の値は応答、audit payload、ログへ含めません。"
        tag(OPS_TAG)
        parameters {
            path("provider") {
                description = "claude です。codex は device auth のため token/code submit を受け付けません。"
                schema = jsonSchema<String>()
            }
            path("sessionId") {
                description = "login start 応答の sessionId です。"
                schema = jsonSchema<String>()
            }
        }
        requestBody {
            description = "token または code の片方だけを指定します。値は保存・返却しません。"
            required = true
            schema = jsonSchema<OpsLlmAuthTokenSubmitRequest>()
        }
        responses {
            HttpStatusCode.Accepted {
                description = "token/code を CLI process へ送信しました。"
                schema = jsonSchema<OpsLlmAuthTokenSubmitResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "provider、sessionId、request body、または token/code が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.NotFound {
                description = "login session が見つかりません。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.Conflict {
                description = "login session が active でない、または token/code はすでに送信済みです。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "CLI auth service が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

private suspend fun ApplicationCall.respondOpsLlmAuthTokenSubmit(llmAuthService: LlmAuthService?) {
    val provider = requireLlmAuthProvider(parameters["provider"]) ?: return
    val sessionId = requirePathValue(parameters["sessionId"], "sessionId is required") ?: return
    val request = receiveBodyOrBadRequest<OpsLlmAuthTokenSubmitRequest>() ?: return
    val tokenCode = requireLoginTokenCode(request) ?: return
    val service = requireLlmAuthService(llmAuthService) ?: return

    when (val result = service.submitLoginTokenCode(provider, sessionId, tokenCode).getOrThrow()) {
        is LlmAuthLoginTokenSubmitResult.Accepted -> respond(
            HttpStatusCode.Accepted,
            result.session.toOpsLlmAuthTokenSubmitResponse(),
        )
        is LlmAuthLoginTokenSubmitResult.Rejected -> respond(
            result.rejection.toHttpStatusCode(),
            ErrorResponse(result.reason),
        )
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsAccountRoute(dependencies: OpsRouteDependencies) {
    val paperLedgerRepository = dependencies.feed.paperLedgerRepository

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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsDecisionsRoute(dependencies: OpsRouteDependencies) {
    val decisionRepository = dependencies.feed.decisionRepository
    val clock = dependencies.clock

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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsExecutionsRoute(dependencies: OpsRouteDependencies) {
    val paperLedgerRepository = dependencies.feed.paperLedgerRepository

    get("/ops/executions") {
        val limit = call.requireLimit(
            defaultLimit = DEFAULT_EXECUTIONS_LIMIT,
            maxLimit = MAX_EXECUTIONS_LIMIT,
        ) ?: return@get
        val repository = call.requirePaperLedgerRepository(paperLedgerRepository) ?: return@get
        val executions = repository.getRecentExecutions(limit).getOrThrow()

        call.respond(
            OpsExecutionsResponse(
                executions = executions.map { execution -> execution.toOpsExecutionResponse() },
            ),
        )
    }.describe {
        summary = "paper execution の raw feed を取得する"
        description = "paper ledger の execution を新しい順で返します。limit は既定 20、最大 100 です。"
        tag(OPS_TAG)
        parameters {
            query("limit") {
                description = "取得件数です。既定 20、最大 100 です。"
                schema = jsonSchema<Int>()
            }
        }
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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsActivityRoute(dependencies: OpsRouteDependencies) {
    get("/ops/activity/catalog") {
        call.respond(opsActivityCatalogResponse())
    }.describe {
        summary = "Activity 表示用 catalog を取得する"
        description = "Activity 画面で source、audit event_type、decision action を人間向け文言へ解決するための code-owned catalog です。" +
            "値は filter / kind / localStorage と同じ raw wire value のまま返し、人間向け文言は WebUI i18n key で返します。"
        tag(OPS_TAG)
        responses {
            HttpStatusCode.OK {
                description = "Activity 表示用 catalog です。"
                schema = jsonSchema<OpsActivityCatalogResponse>()
            }
        }
    }

    get("/ops/activity") {
        call.respondOpsActivity(dependencies)
    }.describe {
        summary = "Activity timeline を取得する"
        description = "decision、audit、paper execution を backend で統合し、cursor paging と source / audit eventType filter を適用して新しい順で返します。" +
            "audit payload は返しません。timeline list 用 metadata は短い項目だけを返し、長い理由文や関連 context は details に返します。"
        tag(OPS_TAG)
        parameters {
            query("limit") {
                description = "取得件数です。既定 50、最大 100 です。"
                schema = jsonSchema<Int>()
            }
            query("before") {
                description = "前回応答の nextBefore をそのまま指定する opaque cursor です。ISO-8601 時刻だけの旧形式も受け付けます。未指定の場合は現在時刻を使います。"
                schema = jsonSchema<String>()
            }
            query("source") {
                description = "decision / audit / execution のいずれかに絞り込みます。未指定の場合は全 source を返します。"
                schema = jsonSchema<String>()
            }
            query("auditEventType") {
                description = "audit event_type の許可リストです。複数指定可。未指定の場合は RECONCILER_PASS_COMPLETED と GMO_PUBLIC_REST_REQUEST_COMPLETED を既定除外します。"
                schema = jsonSchema<List<String>>()
                style = "form"
                explode = true
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "Activity timeline です。"
                schema = jsonSchema<OpsActivityResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "limit、before、source、または auditEventType が不正です。"
                schema = jsonSchema<ErrorResponse>()
            }
            HttpStatusCode.ServiceUnavailable {
                description = "Activity timeline に必要な repository が利用できません。"
                schema = jsonSchema<ErrorResponse>()
            }
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsPositionsRoute(dependencies: OpsRouteDependencies) {
    val paperLedgerRepository = dependencies.feed.paperLedgerRepository

    get("/ops/positions") {
        val repository = call.requirePaperLedgerRepository(paperLedgerRepository) ?: return@get
        val positions = repository.getOpenPositions().getOrThrow()
        val openOrders = repository.getOpenOrders().getOrThrow()
        val openPositionIds = positions.map { position -> position.positionId }
        val sellExecutions = repository.findSellExecutionsByPositionIds(openPositionIds)
            .getOrThrow()

        call.respond(
            OpsPositionsResponse(
                positions = positions,
                openOrders = openOrders,
                sellExecutions = sellExecutions.map { execution -> execution.toOpsExecutionResponse() },
            ),
        )
    }.describe {
        summary = "open position と open order の raw feed を取得する"
        description = "paper ledger の open position、open order、open position に紐づく SELL execution を集計せずに返します。"
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
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.registerOpsAuditRoute(dependencies: OpsRouteDependencies) {
    val commandEventFeedReader = dependencies.feed.commandEventFeedReader

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
        val excludeEventTypes = call.requireExcludeEventTypes() ?: return@get
        val reader = call.requireCommandEventFeedReader(commandEventFeedReader) ?: return@get
        val events = reader.findEvents(limit, eventType, excludeEventTypes).getOrThrow()

        call.respond(
            OpsAuditResponse(
                events = events.map { event -> event.toOpsAuditEventResponse() },
            ),
        )
    }.describe {
        summary = "command_event_log の raw feed を取得する"
        description = "監査イベントを新しい順で返します。limit は既定 50、最大 200、eventType で任意に絞り込めます。excludeEventType（複数指定可）で高頻度な heartbeat などを除外できます。"
        tag(OPS_TAG)
        parameters {
            query("limit") {
                description = "取得件数です。既定 50、最大 200 です。"
                schema = jsonSchema<Int>()
            }
            query("eventType") {
                description = "指定した event_type だけに絞り込みます。値は CommandEventType の名前です。"
                schema = jsonSchema<String>()
            }
            query("excludeEventType") {
                description = "除外する event_type です。複数指定可（例: excludeEventType=RECONCILER_PASS_COMPLETED）。値は CommandEventType の名前です。"
                schema = jsonSchema<List<String>>()
                style = "form"
                explode = true
            }
        }
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

private suspend fun ApplicationCall.respondOpsActivity(dependencies: OpsRouteDependencies) {
    val activityRequest = requireOpsActivityRequest(dependencies.clock) ?: return
    val events = collectOpsActivityEvents(
        activityRequest = activityRequest,
        dependencies = dependencies,
    ) ?: return
    val sortedEvents = newestFirstOpsActivityEvents(events)
        .filter { event -> event.isOlderThan(activityRequest.cursor) }
    val pageEvents = sortedEvents.take(activityRequest.limit)
    val nextBefore = if (sortedEvents.size > activityRequest.limit) {
        pageEvents.lastOrNull()?.toActivityCursorValue()
    } else {
        null
    }

    respond(
        OpsActivityResponse(
            events = pageEvents,
            nextBefore = nextBefore,
            limit = activityRequest.limit,
        ),
    )
}

private suspend fun ApplicationCall.requireOpsActivityRequest(clock: Clock): OpsActivityRequest? {
    val limit = requireLimit(
        defaultLimit = DEFAULT_ACTIVITY_LIMIT,
        maxLimit = MAX_ACTIVITY_LIMIT,
    ) ?: return null
    val cursor = requireBeforeCursor(clock) ?: return null
    val sourceParameter = request.queryParameters["source"]?.trim()
    val source = if (sourceParameter.isNullOrEmpty()) {
        null
    } else {
        requireActivitySource(sourceParameter) ?: return null
    }
    val auditEventTypes = requireAuditEventTypes() ?: return null

    return OpsActivityRequest(
        limit = limit,
        cursor = cursor,
        source = source,
        auditEventTypes = auditEventTypes,
    )
}

private suspend fun ApplicationCall.collectOpsActivityEvents(
    activityRequest: OpsActivityRequest,
    dependencies: OpsRouteDependencies,
): List<OpsActivityEventResponse>? {
    val events = mutableListOf<OpsActivityEventResponse>()

    if (activityRequest.source.matchesActivitySource(OpsActivitySource.DECISION)) {
        events += fetchDecisionActivityEvents(activityRequest, dependencies.feed.decisionRepository) ?: return null
    }

    if (activityRequest.source.matchesActivitySource(OpsActivitySource.AUDIT)) {
        events += fetchAuditActivityEvents(activityRequest, dependencies.feed.commandEventFeedReader) ?: return null
    }

    if (activityRequest.source.matchesActivitySource(OpsActivitySource.EXECUTION)) {
        events += fetchExecutionActivityEvents(activityRequest, dependencies.feed.paperLedgerRepository) ?: return null
    }

    return events
}

private suspend fun ApplicationCall.fetchDecisionActivityEvents(
    activityRequest: OpsActivityRequest,
    decisionRepository: DecisionRepository?,
): List<OpsActivityEventResponse>? {
    val repository = requireDecisionRepository(decisionRepository) ?: return null
    val decisions = repository.findDecisionsForStableFeed(
        cursor = activityRequest.cursor.toStableFeedCursor(OpsActivitySource.DECISION),
        limit = activityRequest.fetchLimit,
    ).getOrThrow()

    return decisions.map { record -> record.toOpsActivityEventResponse() }
}

private suspend fun ApplicationCall.fetchAuditActivityEvents(
    activityRequest: OpsActivityRequest,
    commandEventFeedReader: CommandEventFeedReader?,
): List<OpsActivityEventResponse>? {
    val reader = requireCommandEventFeedReader(commandEventFeedReader) ?: return null
    val excludeEventTypes = if (activityRequest.auditEventTypes.isEmpty()) {
        DEFAULT_ACTIVITY_EXCLUDED_AUDIT_EVENT_TYPES
    } else {
        emptySet()
    }
    val auditEventTypesFilter = activityRequest.auditEventTypes
        .takeIf { eventTypes -> eventTypes.isNotEmpty() }
    val auditEvents = reader.findEventsForStableFeed(
        cursor = activityRequest.cursor.toStableFeedCursor(OpsActivitySource.AUDIT),
        limit = activityRequest.fetchLimit,
        eventTypes = auditEventTypesFilter,
        excludeEventTypes = excludeEventTypes,
    ).getOrThrow()

    return auditEvents.map { event -> event.toOpsActivityEventResponse() }
}

private suspend fun ApplicationCall.fetchExecutionActivityEvents(
    activityRequest: OpsActivityRequest,
    paperLedgerRepository: PaperLedgerRepository?,
): List<OpsActivityEventResponse>? {
    val repository = requirePaperLedgerRepository(paperLedgerRepository) ?: return null
    val executions = repository.findExecutionActivitiesForStableFeed(
        cursor = activityRequest.cursor.toStableFeedCursor(OpsActivitySource.EXECUTION),
        limit = activityRequest.fetchLimit,
    ).getOrThrow()

    return executions.map { execution -> execution.toOpsActivityEventResponse() }
}

/**
 * Activity timeline request の解釈済み入力。
 *
 * @param limit 応答に含める最大件数
 * @param cursor before cursor
 * @param source 絞り込み対象 source
 * @param auditEventTypes audit event_type の許可リスト
 */
private data class OpsActivityRequest(
    val limit: Int,
    val cursor: OpsActivityCursor,
    val source: OpsActivitySource?,
    val auditEventTypes: Set<CommandEventType>,
) {
    val fetchLimit: Int = limit + 1
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

private suspend fun ApplicationCall.requireLlmAuthService(service: LlmAuthService?): LlmAuthService? {
    if (service != null) {
        return service
    }

    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("CLI auth service is not configured"))

    return null
}

private suspend fun ApplicationCall.requireRuntimeConfigAdminService(
    service: RuntimeConfigAdminService?,
): RuntimeConfigAdminService? {
    if (service != null) {
        return service
    }

    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("runtime config admin service is not configured"))

    return null
}

private suspend fun ApplicationCall.requireLlmAuthProvider(rawProvider: String?): LlmAuthProvider? {
    val provider = rawProvider
        ?.trim()
        ?.let { value -> LlmAuthProvider.entries.firstOrNull { candidate -> candidate.wireName == value } }

    if (provider != null) {
        return provider
    }

    respond(HttpStatusCode.BadRequest, ErrorResponse("provider is invalid"))

    return null
}

private suspend fun ApplicationCall.requirePathValue(rawValue: String?, errorMessage: String): String? {
    val value = rawValue?.trim()

    if (!value.isNullOrEmpty()) {
        return value
    }

    respond(HttpStatusCode.BadRequest, ErrorResponse(errorMessage))

    return null
}

private suspend fun ApplicationCall.requireLoginTokenCode(request: OpsLlmAuthTokenSubmitRequest): String? {
    val tokenCodeCandidates = listOf(request.token, request.code)
        .mapNotNull { value -> value?.trim()?.takeIf { candidate -> candidate.isNotEmpty() } }

    if (tokenCodeCandidates.size != 1) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("token or code must be provided once"))

        return null
    }

    val tokenCode = tokenCodeCandidates.single()

    if (tokenCode.containsLlmAuthTokenCodeLineBreak()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("token or code must be a single line"))

        return null
    }

    if (tokenCode.length > MAX_LLM_AUTH_TOKEN_CODE_LENGTH) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("token or code is too long"))

        return null
    }

    return tokenCode
}

private suspend fun ApplicationCall.requireDecisionRepository(repository: DecisionRepository?): DecisionRepository? {
    if (repository != null) {
        return repository
    }

    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("decision repository is not configured"))

    return null
}

private fun LlmAuthLoginTokenSubmitRejection.toHttpStatusCode(): HttpStatusCode {
    return when (this) {
        LlmAuthLoginTokenSubmitRejection.UNSUPPORTED_PROVIDER -> HttpStatusCode.BadRequest
        LlmAuthLoginTokenSubmitRejection.SESSION_NOT_FOUND -> HttpStatusCode.NotFound
        LlmAuthLoginTokenSubmitRejection.SESSION_NOT_RUNNING -> HttpStatusCode.Conflict
        LlmAuthLoginTokenSubmitRejection.ALREADY_SUBMITTED -> HttpStatusCode.Conflict
        LlmAuthLoginTokenSubmitRejection.STDIN_UNAVAILABLE -> HttpStatusCode.Conflict
    }
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
        return parsedLimit.coerceAtMost(maxLimit)
    }

    respond(HttpStatusCode.BadRequest, ErrorResponse("limit must be a positive integer"))

    return null
}

private suspend fun ApplicationCall.requireBeforeCursor(clock: Clock): OpsActivityCursor? {
    val rawBefore = request.queryParameters["before"]?.trim()
        ?: return OpsActivityCursor(
            occurredAt = Instant.now(clock),
            source = null,
            eventId = null,
            sourceEventId = null,
        )

    if (rawBefore.isEmpty()) {
        respondInvalidBeforeCursor()

        return null
    }

    val timestampOnlyCursor = runCatching { Instant.parse(rawBefore) }.getOrNull()

    if (timestampOnlyCursor != null) {
        return OpsActivityCursor(
            occurredAt = timestampOnlyCursor,
            source = null,
            eventId = null,
            sourceEventId = null,
        )
    }

    val cursorParts = rawBefore.split(ACTIVITY_CURSOR_SEPARATOR, limit = ACTIVITY_CURSOR_PART_COUNT)
    val cursorHasThreeParts = cursorParts.size == ACTIVITY_CURSOR_PART_COUNT

    if (!cursorHasThreeParts) {
        respondInvalidBeforeCursor()

        return null
    }

    val occurredAt = runCatching { Instant.parse(cursorParts[0]) }.getOrNull()
    val source = OpsActivitySource.entries.firstOrNull { candidate -> candidate.wireName == cursorParts[1] }
    val eventId = cursorParts[2].takeIf { value -> value.isNotBlank() }
    val sourceEventId = if (source != null && eventId != null) {
        parseActivityCursorSourceEventId(source, eventId)
    } else {
        null
    }
    val cursorHasOccurredAt = occurredAt != null
    val cursorHasSource = source != null
    val cursorHasEventId = eventId != null
    val cursorHasSourceEventId = sourceEventId != null
    val cursorIsValid = cursorHasOccurredAt && cursorHasSource && cursorHasEventId && cursorHasSourceEventId

    if (cursorIsValid) {
        return OpsActivityCursor(
            occurredAt = occurredAt,
            source = source,
            eventId = eventId,
            sourceEventId = sourceEventId,
        )
    }

    respondInvalidBeforeCursor()

    return null
}

private suspend fun ApplicationCall.respondInvalidBeforeCursor() {
    respond(HttpStatusCode.BadRequest, ErrorResponse("before cursor is invalid"))
}

private fun parseActivityCursorSourceEventId(source: OpsActivitySource, eventId: String): String? {
    val expectedPrefix = "${source.wireName}:"

    if (!eventId.startsWith(expectedPrefix)) {
        return null
    }

    return eventId
        .removePrefix(expectedPrefix)
        .takeIf { rawEventId -> rawEventId.isNotBlank() }
}

private suspend fun ApplicationCall.requireActivitySource(rawSource: String): OpsActivitySource? {
    val source = OpsActivitySource.entries.firstOrNull { candidate -> candidate.wireName == rawSource }

    if (source != null) {
        return source
    }

    respond(HttpStatusCode.BadRequest, ErrorResponse("source is invalid"))

    return null
}

private suspend fun ApplicationCall.requireAuditEventTypes(): Set<CommandEventType>? {
    val rawAuditEventTypes = request.queryParameters.getAll("auditEventType") ?: return emptySet()
    val auditEventTypes = mutableSetOf<CommandEventType>()

    for (rawAuditEventType in rawAuditEventTypes) {
        val auditEventType = requireCommandEventType(rawAuditEventType) ?: return null
        auditEventTypes.add(auditEventType)
    }

    return auditEventTypes
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

private suspend fun ApplicationCall.requireExcludeEventTypes(): Set<CommandEventType>? {
    val rawExcludeEventTypes = request.queryParameters.getAll("excludeEventType") ?: return emptySet()

    val excludeEventTypes = mutableSetOf<CommandEventType>()

    for (rawExcludeEventType in rawExcludeEventTypes) {
        val excludeEventType = requireCommandEventType(rawExcludeEventType) ?: return null
        excludeEventTypes.add(excludeEventType)
    }

    return excludeEventTypes
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

private suspend fun <T : Any> ApplicationCall.respondRuntimeConfigResult(result: Result<T>): T? {
    val value = result.getOrNull()

    if (value != null) {
        return value
    }

    val throwable = requireNotNull(result.exceptionOrNull())

    if (throwable is RuntimeConfigValidationRejectedException) {
        respond(
            HttpStatusCode.Conflict,
            OpsRuntimeConfigValidationConflictResponse(
                valid = throwable.validation.valid,
                errors = throwable.validation.errors,
            ),
        )

        return null
    }

    if (throwable is PaperAccountEpochSwitchRejectedException) {
        respond(
            HttpStatusCode.Conflict,
            OpsPaperAccountEpochSwitchConflictResponse(
                openPositionCount = throwable.openPositionCount,
                openOrderCount = throwable.openOrderCount,
                btcQuantity = throwable.btcQuantity,
            ),
        )

        return null
    }

    if (throwable is IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(throwable.message ?: "runtime config request is invalid"))

        return null
    }

    throw throwable
}

private fun OpsActivitySource?.matchesActivitySource(source: OpsActivitySource): Boolean {
    return this == null || this == source
}

private fun newestFirstOpsActivityEvents(events: List<OpsActivityEventResponse>): List<OpsActivityEventResponse> {
    return events
        .map { event ->
            OpsActivitySortableEvent(
                event = event,
                occurredAt = Instant.parse(event.occurredAt),
            )
        }
        .sortedWith(
            compareByDescending<OpsActivitySortableEvent> { sortableEvent -> sortableEvent.occurredAt }
                .thenBy { sortableEvent -> sortableEvent.event.source }
                .thenBy { sortableEvent -> sortableEvent.event.id },
        )
        .map { sortableEvent -> sortableEvent.event }
}

private fun OpsActivityEventResponse.isOlderThan(cursor: OpsActivityCursor): Boolean {
    val eventOccurredAt = Instant.parse(occurredAt)

    if (eventOccurredAt.isBefore(cursor.occurredAt)) {
        return true
    }

    val eventHasCursorTimestamp = eventOccurredAt == cursor.occurredAt

    if (!eventHasCursorTimestamp) {
        return false
    }

    if (!cursor.hasTieBreaker()) {
        return false
    }

    val cursorSource = requireNotNull(cursor.source)
    val sourceComparison = source.compareTo(cursorSource.wireName)

    if (sourceComparison != 0) {
        return sourceComparison > 0
    }

    val cursorEventId = requireNotNull(cursor.eventId)

    return id > cursorEventId
}

private fun OpsActivityEventResponse.toActivityCursorValue(): String {
    return listOf(occurredAt, source, id).joinToString(ACTIVITY_CURSOR_SEPARATOR)
}

private fun opsActivityCatalogResponse(): OpsActivityCatalogResponse {
    return OpsActivityCatalogResponse(
        sourceFilters = listOf(activityCatalogItem(ACTIVITY_SOURCE_ALL, "activity.catalog.source.all")) +
            OpsActivitySource.entries.map { source -> source.toActivitySourceDefinition() },
        auditEventTypes = CommandEventType.entries.map { eventType -> eventType.toActivityAuditEventDefinition() },
        decisionActions = DecisionAction.entries.map { action -> action.toActivityDecisionActionDefinition() },
        defaultExcludedAuditEventTypes = DEFAULT_ACTIVITY_EXCLUDED_AUDIT_EVENT_TYPES.map { eventType -> eventType.name },
    )
}

private fun OpsActivitySource.toActivitySourceDefinition(): OpsActivityCatalogItemResponse {
    val keySuffix = when (this) {
        OpsActivitySource.DECISION -> "decision"
        OpsActivitySource.AUDIT -> "audit"
        OpsActivitySource.EXECUTION -> "execution"
    }

    return activityCatalogItem(wireName, "activity.catalog.source.$keySuffix")
}

@Suppress("CyclomaticComplexMethod")
private fun CommandEventType.toActivityAuditEventDefinition(): OpsActivityCatalogItemResponse {
    val keySuffix = when (this) {
        CommandEventType.GMO_PUBLIC_REST_REQUEST_COMPLETED -> "gmoPublicRestRequestCompleted"
        CommandEventType.TOOL_CALL_COMPLETED -> "toolCallCompleted"
        CommandEventType.TOOL_CALL_REJECTED_BY_HARD_HALT -> "toolCallRejectedByHardHalt"
        CommandEventType.NO_TRADE_EXIT -> "noTradeExit"
        CommandEventType.RECONCILER_STARTED -> "reconcilerStarted"
        CommandEventType.RECONCILER_PASS_COMPLETED -> "reconcilerPassCompleted"
        CommandEventType.RECONCILER_PASS_FAILED -> "reconcilerPassFailed"
        CommandEventType.RECONCILER_PASS_RECOVERED -> "reconcilerPassRecovered"
        CommandEventType.HARD_HALT_SET -> "hardHaltSet"
        CommandEventType.SOFT_HALT_SET -> "softHaltSet"
        CommandEventType.KILL_CRITERION_BREACHED -> "killCriterionBreached"
        CommandEventType.MANUAL_RESUME_REQUESTED -> "manualResumeRequested"
        CommandEventType.RUNNER_PHASE_COMPLETED -> "runnerPhaseCompleted"
        CommandEventType.DECISION_LIFECYCLE_COMPLETED -> "decisionLifecycleCompleted"
        CommandEventType.DAEMON_STARTED -> "daemonStarted"
        CommandEventType.DAEMON_TRIGGER_SKIPPED -> "daemonTriggerSkipped"
        CommandEventType.DAEMON_TRIGGER_LAUNCHED -> "daemonTriggerLaunched"
        CommandEventType.DAEMON_INVOCATION_COMPLETED -> "daemonInvocationCompleted"
        CommandEventType.LLM_INVOCATION_RECOVERED -> "llmInvocationRecovered"
        CommandEventType.LLM_EXECUTION_RECOVERY_STARTED -> "llmExecutionRecoveryStarted"
        CommandEventType.CLI_AUTH_LOGIN_STARTED -> "cliAuthLoginStarted"
        CommandEventType.CLI_AUTH_LOGIN_TOKEN_SUBMITTED -> "cliAuthLoginTokenSubmitted"
        CommandEventType.CLI_AUTH_LOGIN_COMPLETED -> "cliAuthLoginCompleted"
        CommandEventType.CLI_AUTH_LOGIN_FAILED -> "cliAuthLoginFailed"
        CommandEventType.CLI_AUTH_LOGIN_TIMED_OUT -> "cliAuthLoginTimedOut"
        CommandEventType.CLI_AUTH_CLOSE_WAIT_TIMED_OUT -> "cliAuthCloseWaitTimedOut"
        CommandEventType.PAPER_ACCOUNT_EPOCH_IMPORTED -> "paperAccountEpochImported"
        CommandEventType.PAPER_ACCOUNT_EPOCH_SWITCHED -> "paperAccountEpochSwitched"
        CommandEventType.PAPER_ACCOUNT_EPOCH_SWITCH_REJECTED -> "paperAccountEpochSwitchRejected"
    }

    return activityCatalogItem(name, "activity.catalog.audit.$keySuffix")
}

private fun DecisionAction.toActivityDecisionActionDefinition(): OpsActivityCatalogItemResponse {
    val keySuffix = when (this) {
        DecisionAction.ENTER -> "enter"
        DecisionAction.EXIT -> "exit"
        DecisionAction.REDUCE -> "reduce"
        DecisionAction.ADD_LONG -> "addLong"
        DecisionAction.ADJUST_PROTECTION -> "adjustProtection"
        DecisionAction.NO_TRADE -> "noTrade"
    }

    return activityCatalogItem(name, "activity.catalog.decision.$keySuffix")
}

private fun activityCatalogItem(value: String, keyPrefix: String): OpsActivityCatalogItemResponse {
    return OpsActivityCatalogItemResponse(
        value = value,
        labelKey = "$keyPrefix.label",
        descriptionKey = "$keyPrefix.description",
    )
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

private fun LlmAuthSnapshot.toOpsLlmAuthResponse(): OpsLlmAuthResponse {
    return OpsLlmAuthResponse(
        providers = providers.map { provider -> provider.toOpsLlmAuthProviderResponse() },
        checkedAt = checkedAt.toString(),
    )
}

private fun LlmAuthProviderStatus.toOpsLlmAuthProviderResponse(): OpsLlmAuthProviderResponse {
    return OpsLlmAuthProviderResponse(
        provider = provider.wireName,
        displayName = provider.displayName,
        status = status.wireName,
        detail = detail,
        homePath = homePath,
        checkedAt = checkedAt.toString(),
    )
}

private fun LlmAuthLoginSessionSnapshot.toOpsLlmAuthLoginResponse(): OpsLlmAuthLoginResponse {
    return OpsLlmAuthLoginResponse(
        provider = provider.wireName,
        sessionId = sessionId,
        status = status.wireName,
        authorizationUrl = authorizationUrl,
        userCode = userCode,
        tokenSubmitAvailable = tokenSubmitAvailable,
        tokenSubmitted = tokenSubmitted,
        detail = detail,
        startedAt = startedAt.toString(),
        expiresAt = expiresAt.toString(),
        completedAt = completedAt?.toString(),
    )
}

private fun LlmAuthLoginSessionSnapshot.toOpsLlmAuthTokenSubmitResponse(): OpsLlmAuthTokenSubmitResponse {
    return OpsLlmAuthTokenSubmitResponse(
        provider = provider.wireName,
        sessionId = sessionId,
        status = status.wireName,
        tokenSubmitted = tokenSubmitted,
        detail = detail,
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

private fun DecisionJournalRecord.toOpsActivityEventResponse(): OpsActivityEventResponse {
    val response = toOpsDecisionResponse()

    return OpsActivityEventResponse(
        id = "decision:${response.id}",
        source = OpsActivitySource.DECISION.wireName,
        kind = response.action,
        title = "${response.action} decision",
        detail = response.reasonJa,
        occurredAt = response.createdAt,
        metadata = listOf(
            OpsActivityMetadataResponse(
                label = "estimated p",
                value = response.estimatedWinProbability,
            ),
            OpsActivityMetadataResponse(
                label = "setup tags",
                value = response.setupTags.takeIf { tags -> tags.isNotEmpty() }?.joinToString(", ") ?: "none",
            ),
            OpsActivityMetadataResponse(
                label = "no-trade conditions",
                value = response.noTradeConditionsJa.takeIf { conditions -> conditions.isNotEmpty() }?.joinToString(" / ") ?: "none",
            ),
        ),
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

private fun CommandEvent.toOpsActivityEventResponse(): OpsActivityEventResponse {
    return OpsActivityEventResponse(
        id = "audit:$id",
        source = OpsActivitySource.AUDIT.wireName,
        kind = eventType.name,
        title = eventType.name,
        detail = toolName,
        occurredAt = occurredAt.toString(),
        metadata = listOf(
            OpsActivityMetadataResponse(
                label = "tool",
                value = toolName,
            ),
        ),
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
        accountEpochId = snapshot.accountEpochId,
    )
}

private fun ExecutionActivityRecord.toOpsActivityEventResponse(): OpsActivityEventResponse {
    val response = execution.toOpsExecutionResponse()
    val activityLabel = toExecutionActivityLabel(response)

    return OpsActivityEventResponse(
        id = "execution:${response.executionId}",
        source = OpsActivitySource.EXECUTION.wireName,
        kind = activityLabel.kind,
        title = activityLabel.title,
        detail = "${response.sizeBtc} BTC at ${response.priceJpy} JPY",
        occurredAt = response.executedAt,
        metadata = listOf(
            OpsActivityMetadataResponse(
                label = "evaluation",
                value = evaluationExclusionReason?.let { reason -> "excluded: $reason" } ?: "eligible",
            ),
            OpsActivityMetadataResponse(
                label = "realized pnl",
                value = response.realizedPnlJpy,
            ),
            OpsActivityMetadataResponse(
                label = "fee",
                value = response.feeJpy,
            ),
            OpsActivityMetadataResponse(
                label = "liquidity",
                value = response.liquidity,
            ),
            OpsActivityMetadataResponse(
                label = "order",
                value = response.orderId ?: ACTIVITY_NOT_LINKED_VALUE,
            ),
            OpsActivityMetadataResponse(
                label = "account epoch",
                value = response.accountEpochId ?: ACTIVITY_NOT_LINKED_VALUE,
            ),
            OpsActivityMetadataResponse(
                label = "execution semantics",
                value = response.executionSemanticsVersion ?: "LEGACY_PRE_WS",
            ),
            OpsActivityMetadataResponse(
                label = "runtime config hash",
                value = response.runtimeConfigHash ?: ACTIVITY_NOT_LINKED_VALUE,
            ),
        ),
        details = OpsActivityDetailsResponse(
            title = activityLabel.title,
            metadata = toExecutionActivityDetailsMetadata(response),
        ),
    )
}

private fun ExecutionActivityRecord.toExecutionActivityLabel(response: OpsExecutionResponse): ExecutionActivityLabel {
    if (execution.side == OrderSide.BUY) {
        return ExecutionActivityLabel(
            kind = ACTIVITY_EXECUTION_KIND_ENTRY_FILL,
            title = "${response.symbol} entry fill",
        )
    }

    if (execution.side != OrderSide.SELL) {
        return response.toGenericExecutionActivityLabel()
    }

    val directOrder = order ?: return response.toGenericExecutionActivityLabel()

    return when {
        directOrder.orderType == OrderType.STOP -> ExecutionActivityLabel(
            kind = ACTIVITY_EXECUTION_KIND_STOP_TRIGGER,
            title = "${response.symbol} STOP trigger",
        )
        directOrder.indicatesTakeProfitClose() -> ExecutionActivityLabel(
            kind = ACTIVITY_EXECUTION_KIND_TAKE_PROFIT_CLOSE,
            title = "${response.symbol} take-profit close",
        )
        directOrder.orderType == OrderType.LIMIT -> ExecutionActivityLabel(
            kind = ACTIVITY_EXECUTION_KIND_LIMIT_CLOSE,
            title = "${response.symbol} limit close",
        )
        directOrder.orderType == OrderType.MARKET -> ExecutionActivityLabel(
            kind = ACTIVITY_EXECUTION_KIND_MARKET_CLOSE,
            title = "${response.symbol} market close",
        )
        else -> response.toGenericExecutionActivityLabel()
    }
}

private fun ExecutionActivityRecord.toExecutionActivityDetailsMetadata(
    response: OpsExecutionResponse,
): List<OpsActivityMetadataResponse> {
    return response.toExecutionSummaryMetadata() +
        toExecutionIntegrityMetadata() +
        toOrderContextMetadata() +
        toPositionContextMetadata() +
        toDecisionContextMetadata()
}

private fun ExecutionActivityRecord.toExecutionIntegrityMetadata(): List<OpsActivityMetadataResponse> {
    val source = sourceEvidence

    return listOf(
        OpsActivityMetadataResponse(
            label = "evaluation",
            value = evaluationExclusionReason?.let { reason -> "excluded: $reason" } ?: "eligible",
        ),
        OpsActivityMetadataResponse(
            label = "market session",
            value = source?.sessionId ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "market sequence",
            value = source?.sequence?.toString() ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "exchange / received",
            value = source?.let { evidence -> "${evidence.exchangeAt} / ${evidence.receivedAt}" }
                ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "source trade",
            value = source?.let { evidence ->
                "${evidence.side} ${evidence.sizeBtc} BTC at ${evidence.priceJpy} JPY"
            } ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
    )
}

private fun OpsExecutionResponse.toExecutionSummaryMetadata(): List<OpsActivityMetadataResponse> {
    return listOf(
        OpsActivityMetadataResponse(
            label = "execution",
            value = executionId,
        ),
        OpsActivityMetadataResponse(
            label = "side",
            value = side,
        ),
        OpsActivityMetadataResponse(
            label = "size",
            value = sizeBtc,
        ),
        OpsActivityMetadataResponse(
            label = "price",
            value = priceJpy,
        ),
        OpsActivityMetadataResponse(
            label = "realized pnl",
            value = realizedPnlJpy,
        ),
        OpsActivityMetadataResponse(
            label = "fee",
            value = feeJpy,
        ),
        OpsActivityMetadataResponse(
            label = "liquidity",
            value = liquidity,
        ),
    )
}

private fun ExecutionActivityRecord.toOrderContextMetadata(): List<OpsActivityMetadataResponse> {
    return listOf(
        OpsActivityMetadataResponse(
            label = "order",
            value = order?.orderId ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "order type",
            value = order?.orderType?.name ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "trigger price",
            value = order?.triggerPriceJpy ?: ACTIVITY_NO_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "take-profit price",
            value = order?.takeProfitPriceJpy ?: ACTIVITY_NO_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "order reason",
            value = order?.reasonJa ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
    )
}

private fun ExecutionActivityRecord.toPositionContextMetadata(): List<OpsActivityMetadataResponse> {
    return listOf(
        OpsActivityMetadataResponse(
            label = "position",
            value = position?.positionId ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "trade group",
            value = position?.tradeGroupId ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
    )
}

private fun ExecutionActivityRecord.toDecisionContextMetadata(): List<OpsActivityMetadataResponse> {
    return listOf(
        OpsActivityMetadataResponse(
            label = "decision action",
            value = entryDecision?.action?.name ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "decision",
            value = entryDecision?.decisionId ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "decision run",
            value = entryDecision?.decisionRunId ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
        OpsActivityMetadataResponse(
            label = "decision reason",
            value = entryDecision?.reasonJa ?: ACTIVITY_NOT_LINKED_VALUE,
        ),
    )
}

private fun OpsExecutionResponse.toGenericExecutionActivityLabel(): ExecutionActivityLabel {
    return ExecutionActivityLabel(
        kind = side,
        title = "$side $symbol execution",
    )
}

private fun ExecutionActivityOrderContext.indicatesTakeProfitClose(): Boolean {
    val hasTakeProfitPrice = takeProfitPriceJpy != null
    val lowerReason = reasonJa?.lowercase().orEmpty()
    val reasonMentionsTakeProfit = lowerReason.contains("take profit") ||
        lowerReason.contains("take-profit")

    return hasTakeProfitPrice || reasonMentionsTakeProfit
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
        accountEpochId = accountEpochId,
        executionSemanticsVersion = executionSemanticsVersion,
        runtimeConfigHash = runtimeConfigHash,
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

/**
 * activity timeline feed の既定 limit。
 */
private const val DEFAULT_ACTIVITY_LIMIT = 50

/**
 * activity timeline feed の最大 limit。
 */
private const val MAX_ACTIVITY_LIMIT = 100

/**
 * Activity cursor 内の区切り文字。
 */
private const val ACTIVITY_CURSOR_SEPARATOR = "|"

/**
 * Activity source filter の all pseudo source。
 */
private const val ACTIVITY_SOURCE_ALL = "all"

/**
 * Activity cursor の要素数。
 */
private const val ACTIVITY_CURSOR_PART_COUNT = 3

/**
 * Activity execution kind: entry fill。
 */
private const val ACTIVITY_EXECUTION_KIND_ENTRY_FILL = "ENTRY_FILL"

/**
 * Activity execution kind: STOP trigger。
 */
private const val ACTIVITY_EXECUTION_KIND_STOP_TRIGGER = "STOP_TRIGGER"

/**
 * Activity execution kind: take-profit close。
 */
private const val ACTIVITY_EXECUTION_KIND_TAKE_PROFIT_CLOSE = "TAKE_PROFIT_CLOSE"

/**
 * Activity execution kind: limit close。
 */
private const val ACTIVITY_EXECUTION_KIND_LIMIT_CLOSE = "LIMIT_CLOSE"

/**
 * Activity execution kind: market close。
 */
private const val ACTIVITY_EXECUTION_KIND_MARKET_CLOSE = "MARKET_CLOSE"

/**
 * Activity metadata の missing link 表示値。
 *
 * WebUI はこの wire 値を i18n sentinel として扱う。
 */
private const val ACTIVITY_NOT_LINKED_VALUE = "not linked"

/**
 * Activity metadata の空値表示値。
 *
 * WebUI はこの wire 値を i18n sentinel として扱う。
 */
private const val ACTIVITY_NO_VALUE = "none"

/**
 * audit eventType 未指定時に activity timeline から除外する event_type。
 */
private val DEFAULT_ACTIVITY_EXCLUDED_AUDIT_EVENT_TYPES = setOf(
    CommandEventType.RECONCILER_PASS_COMPLETED,
    CommandEventType.GMO_PUBLIC_REST_REQUEST_COMPLETED,
)
