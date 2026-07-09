package me.matsumo.fukurou.trading.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.FUKUROU_INVOCATION_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_LLM_PROVIDER_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_MARKET_SNAPSHOT_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_PROMPT_HASH_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_RUNTIME_CONFIG_HASH_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_RUNTIME_CONFIG_VERSION_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_SYSTEM_PROMPT_VERSION_ENV
import me.matsumo.fukurou.trading.config.LlmDaemonConfig
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.SystemPromptV1
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.knowledge.DecisionJournalRecord
import me.matsumo.fukurou.trading.logging.RateLimitedWarnLogger
import me.matsumo.fukurou.trading.market.IndicatorCalculator
import me.matsumo.fukurou.trading.market.IndicatorParams
import me.matsumo.fukurou.trading.market.IndicatorType
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.runner.CHILD_ENV_ALLOWLIST
import me.matsumo.fukurou.trading.runner.LlmInvocationAuditor
import me.matsumo.fukurou.trading.runner.OneShotRunnerRequest
import me.matsumo.fukurou.trading.runner.isForbiddenSecretEnvKey
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * daemon heartbeat 系 trigger の full run 要否を判定する軽量 pre-filter。
 */
fun interface LlmDaemonPreFilter {
    /**
     * full LLM 起動を続行するかを返す。
     */
    suspend fun evaluate(request: LlmDaemonPreFilterRequest): Result<LlmDaemonPreFilterDecision>
}

/**
 * pre-filter の入力。
 *
 * @param invocationId 予約済み invocation ID
 * @param triggerKind 対象 trigger 種別
 * @param triggerKey 対象 trigger key
 * @param observedAt scheduler が観測した時刻
 * @param runnerRequest full run と同じ one-shot runner request
 */
data class LlmDaemonPreFilterRequest(
    val invocationId: String,
    val triggerKind: LlmDaemonTriggerKind,
    val triggerKey: String,
    val observedAt: Instant,
    val runnerRequest: OneShotRunnerRequest,
)

/**
 * pre-filter の判定結果。
 */
enum class LlmDaemonPreFilterDecision {
    /**
     * full LLM run を起動する。
     */
    RUN_FULL,

    /**
     * 有意な変化がないため full LLM run を省略する。
     */
    SKIP_NO_CHANGE,
}

/**
 * market snapshot を Claude Haiku へ渡す daemon pre-filter。
 *
 * @param tradingConfig 取引 bot 設定
 * @param runtimeConfigSnapshot daemon 起動時の runtime config snapshot
 * @param dependencies pre-filter が使う外部依存
 * @param parentEnvironment 親 process environment
 */
class DefaultLlmDaemonPreFilter(
    private val tradingConfig: TradingBotConfig,
    private val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    private val dependencies: DefaultLlmDaemonPreFilterDependencies,
    private val parentEnvironment: Map<String, String>,
) : LlmDaemonPreFilter {

    override suspend fun evaluate(request: LlmDaemonPreFilterRequest): Result<LlmDaemonPreFilterDecision> {
        return try {
            Result.success(evaluateUnsafe(request))
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private suspend fun evaluateUnsafe(request: LlmDaemonPreFilterRequest): LlmDaemonPreFilterDecision {
        val snapshot = readSnapshot(request.observedAt)
        val prompt = buildPrompt(request, snapshot)
        val context = decisionRunContext(
            invocationId = request.invocationId,
            promptHash = SystemPromptV1.calculateContentHash(prompt),
            marketSnapshotId = "daemon-pre-filter-${request.triggerKey}-${request.invocationId}",
        )
        val llmRequest = LlmInvocationRequest(
            invocationId = request.invocationId,
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PRE_FILTER,
            prompt = prompt,
            timeout = tradingConfig.runner.perRunTimeout,
            workingDirectory = request.runnerRequest.workingDirectory,
            decisionRunContext = context,
            mcpServer = null,
            environment = childEnvironment(context),
            allowedTools = emptyList(),
        )
        val auditResult = dependencies.invocationAuditor.invokeAndAudit(
            phaseName = PRE_FILTER_PHASE_NAME,
            context = context,
            request = llmRequest,
            llmInvoker = dependencies.llmInvoker,
        ).getOrThrow()

        return parsePreFilterDecision(auditResult.invocationResult.processResult.stdout)
    }

    private suspend fun readSnapshot(observedAt: Instant): LlmDaemonPreFilterSnapshot {
        val ticker = dependencies.marketDataSource.getTicker(tradingConfig.symbol).getOrThrow()
        val candles = dependencies.marketDataSource.getCandles(
            symbol = tradingConfig.symbol,
            interval = CandleInterval.FIVE_MINUTES,
            limit = PRE_FILTER_CANDLE_LIMIT,
        ).getOrThrow()
        val previousDecision = dependencies.decisionRepository.findDecisionsCreatedBetween(
            from = observedAt.minus(PRE_FILTER_DECISION_LOOKBACK),
            toExclusive = observedAt.plusMillis(1),
            limit = PRE_FILTER_DECISION_LIMIT,
        ).getOrThrow()
            .maxByOrNull { record -> record.decision.createdAt }
            ?.toPreFilterPreviousDecision()

        return LlmDaemonPreFilterSnapshot(
            ticker = ticker,
            latestCandle = candles.lastOrNull(),
            atr14Jpy = candles.latestAtr14JpyOrNull(),
            previousDecision = previousDecision,
        )
    }

    private fun buildPrompt(request: LlmDaemonPreFilterRequest, snapshot: LlmDaemonPreFilterSnapshot): String {
        return """
            |You are the Fukurou daemon pre-filter running on Claude Haiku.
            |Decide whether a full trading LLM run is needed for this heartbeat-family trigger.
            |Return exactly one token: YES or NO.
            |
            |YES means the current deterministic snapshot has significant change that can invalidate or update
            |the previous decision assumptions.
            |NO means there is no significant change and the full run can be skipped safely for this heartbeat.
            |
            |Do not include explanation, markdown, JSON, or punctuation.
            |
            |Trigger:
            |${request.triggerKind.name}
            |
            |Snapshot JSON:
            |${snapshot.toJsonObject()}
        """.trimMargin()
    }

    private fun decisionRunContext(
        invocationId: String,
        promptHash: String,
        marketSnapshotId: String,
    ): DecisionRunContext {
        return DecisionRunContext(
            decisionRunId = invocationId,
            llmProvider = LlmProvider.CLAUDE.name.lowercase(),
            promptHash = promptHash,
            systemPromptVersion = SystemPromptV1.VERSION,
            marketSnapshotId = marketSnapshotId,
            runtimeConfigVersionId = runtimeConfigSnapshot?.versionId,
            runtimeConfigHash = runtimeConfigSnapshot?.hash,
        )
    }

    private fun childEnvironment(context: DecisionRunContext): Map<String, String> {
        val baseEnvironment = parentEnvironment
            .filterKeys { key -> key in CHILD_ENV_ALLOWLIST }
            .filterKeys { key -> !isForbiddenSecretEnvKey(key) }
        val runEnvironment = buildMap {
            put(FUKUROU_INVOCATION_ID_ENV, requireNotNull(context.decisionRunId))
            put(FUKUROU_LLM_PROVIDER_ENV, requireNotNull(context.llmProvider))
            put(FUKUROU_PROMPT_HASH_ENV, requireNotNull(context.promptHash))
            put(FUKUROU_SYSTEM_PROMPT_VERSION_ENV, requireNotNull(context.systemPromptVersion))
            put(FUKUROU_MARKET_SNAPSHOT_ID_ENV, requireNotNull(context.marketSnapshotId))
            context.runtimeConfigVersionId?.let { versionId ->
                put(FUKUROU_RUNTIME_CONFIG_VERSION_ID_ENV, versionId)
            }
            context.runtimeConfigHash?.let { hash ->
                put(FUKUROU_RUNTIME_CONFIG_HASH_ENV, hash)
            }
        }

        return baseEnvironment + runEnvironment
    }
}

/**
 * DefaultLlmDaemonPreFilter の外部依存。
 *
 * @param marketDataSource market snapshot 取得元
 * @param decisionRepository previous decision 取得元
 * @param llmInvoker LLM invocation 境界
 * @param invocationAuditor LLM phase audit helper
 */
data class DefaultLlmDaemonPreFilterDependencies(
    val marketDataSource: MarketDataSource,
    val decisionRepository: DecisionRepository,
    val llmInvoker: LlmInvoker,
    val invocationAuditor: LlmInvocationAuditor,
)

/**
 * daemon scheduler から pre-filter 適用可否と fail-open を分離する gate。
 *
 * @param daemonConfig daemon 設定
 * @param preFilter pre-filter 実行境界
 * @param requestBase one-shot runner request
 * @param warnLogger failure warning logger
 */
class LlmDaemonPreFilterGate(
    private val daemonConfig: LlmDaemonConfig,
    private val preFilter: LlmDaemonPreFilter,
    private val requestBase: OneShotRunnerRequest,
    private val warnLogger: RateLimitedWarnLogger,
) {
    /**
     * pre-filter が必要なら実行し、full run を続けるかを返す。
     */
    suspend fun decisionIfNeeded(
        triggerKind: LlmDaemonTriggerKind,
        triggerKey: String,
        invocationId: String,
        observedAt: Instant,
    ): LlmDaemonPreFilterDecision {
        if (!daemonConfig.preFilterEnabled || !triggerKind.shouldRunPreFilter()) {
            return LlmDaemonPreFilterDecision.RUN_FULL
        }

        return preFilter.evaluate(
            LlmDaemonPreFilterRequest(
                invocationId = invocationId,
                triggerKind = triggerKind,
                triggerKey = triggerKey,
                observedAt = observedAt,
                runnerRequest = requestBase,
            ),
        ).getOrElse { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }

            warnLogger.warn(
                key = PRE_FILTER_FAILURE_LOG_KEY,
                message = "LlmDaemonScheduler pre-filter failed open.",
                throwable = throwable,
            )

            LlmDaemonPreFilterDecision.RUN_FULL
        }
    }
}

private fun LlmDaemonTriggerKind.shouldRunPreFilter(): Boolean {
    return this == LlmDaemonTriggerKind.FLAT_HEARTBEAT || this == LlmDaemonTriggerKind.HOLDING_DENSE_CHECK
}

/**
 * pre-filter へ渡す deterministic snapshot。
 *
 * @param ticker 最新 ticker
 * @param latestCandle 最新 5 分足
 * @param atr14Jpy 5 分足 ATR(14)
 * @param previousDecision 比較対象の previous decision
 */
private data class LlmDaemonPreFilterSnapshot(
    val ticker: Ticker,
    val latestCandle: Candle?,
    val atr14Jpy: BigDecimal?,
    val previousDecision: LlmDaemonPreFilterPreviousDecision?,
) {
    /**
     * prompt に埋め込む JSON へ変換する。
     */
    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("ticker", ticker.toJsonObject())
            put("latestCandle", latestCandle?.toJsonObject() ?: JsonNull)
            putNullableDecimal("atr14Jpy", atr14Jpy)
            put("previousDecision", previousDecision?.toJsonObject() ?: JsonNull)
        }
    }
}

/**
 * pre-filter 比較用の previous decision summary。
 *
 * @param createdAt decision 作成時刻
 * @param action decision action
 * @param estimatedWinProbability 推定勝率
 * @param expectedRMultiple 期待 R 倍率
 * @param roundTripCostR 往復 cost R
 * @param setupTags setup tags
 * @param thesisJa TradePlan thesis
 * @param invalidationConditionsJa TradePlan invalidation 条件
 * @param reasonJa decision 理由
 * @param noTradeConditionsJa NO_TRADE 条件
 */
private data class LlmDaemonPreFilterPreviousDecision(
    val createdAt: Instant,
    val action: String,
    val estimatedWinProbability: BigDecimal,
    val expectedRMultiple: BigDecimal?,
    val roundTripCostR: BigDecimal?,
    val setupTags: List<String>,
    val thesisJa: String?,
    val invalidationConditionsJa: List<String>,
    val reasonJa: String,
    val noTradeConditionsJa: List<String>,
) {
    /**
     * prompt に埋め込む JSON へ変換する。
     */
    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("createdAt", createdAt.toString())
            put("action", action)
            putNullableDecimal("estimatedWinProbability", estimatedWinProbability)
            putNullableDecimal("expectedRMultiple", expectedRMultiple)
            putNullableDecimal("roundTripCostR", roundTripCostR)
            put("setupTags", setupTags.joinToString(","))
            putNullableString("thesisJa", thesisJa?.take(PRE_FILTER_TEXT_LIMIT))
            put("invalidationConditionsJa", invalidationConditionsJa.joinToString(" | ").take(PRE_FILTER_TEXT_LIMIT))
            put("reasonJa", reasonJa.take(PRE_FILTER_TEXT_LIMIT))
            put("noTradeConditionsJa", noTradeConditionsJa.joinToString(" | ").take(PRE_FILTER_TEXT_LIMIT))
        }
    }
}

private fun DecisionJournalRecord.toPreFilterPreviousDecision(): LlmDaemonPreFilterPreviousDecision {
    val submission = decision.submission

    return LlmDaemonPreFilterPreviousDecision(
        createdAt = decision.createdAt,
        action = submission.action.name,
        estimatedWinProbability = submission.estimatedWinProbability,
        expectedRMultiple = submission.expectedRMultiple,
        roundTripCostR = submission.roundTripCostR,
        setupTags = submission.setupTags,
        thesisJa = tradePlan?.draft?.thesisJa,
        invalidationConditionsJa = tradePlan?.draft?.invalidationConditionsJa.orEmpty(),
        reasonJa = submission.reasonJa,
        noTradeConditionsJa = submission.noTradeConditionsJa,
    )
}

private fun Ticker.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("symbol", symbol)
        put("last", last)
        put("bid", bid)
        put("ask", ask)
        put("high", high)
        put("low", low)
        put("volume", volume)
        put("timestamp", timestamp)
    }
}

private fun Candle.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("symbol", symbol)
        put("interval", interval.apiValue)
        put("openTime", openTime)
        put("open", open)
        put("high", high)
        put("low", low)
        put("close", close)
        put("volume", volume)
    }
}

private fun List<Candle>.latestAtr14JpyOrNull(): BigDecimal? {
    if (isEmpty()) {
        return null
    }

    val result = IndicatorCalculator.calculate(
        candles = this,
        indicator = IndicatorType.ATR,
        params = IndicatorParams(period = PRE_FILTER_ATR_PERIOD),
    ).getOrNull() ?: return null

    return result.values
        .lastOrNull()
        ?.value
        ?.let { value -> BigDecimal.valueOf(value).setScale(PRE_FILTER_DECIMAL_SCALE, RoundingMode.HALF_UP) }
}

private fun parsePreFilterDecision(stdout: String): LlmDaemonPreFilterDecision {
    val decisionText = stdout.extractPreFilterDecisionText()
    val firstToken = decisionText
        .lineSequence()
        .firstOrNull { line -> line.isNotBlank() }
        ?.trim()
        ?.uppercase()

    return when (firstToken) {
        "YES" -> LlmDaemonPreFilterDecision.RUN_FULL
        "NO" -> LlmDaemonPreFilterDecision.SKIP_NO_CHANGE
        else -> error("Pre-filter output must be exactly YES or NO.")
    }
}

private fun String.extractPreFilterDecisionText(): String {
    return runCatching {
        PreFilterStdoutJson.parseToJsonElement(this).preFilterDecisionTextOrNull()
    }.getOrNull() ?: this
}

private fun JsonElement.preFilterDecisionTextOrNull(): String? {
    return when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonArray -> firstNotNullOfOrNull { element -> element.preFilterDecisionTextOrNull() }
        is JsonObject -> preFilterDecisionTextOrNull()
    }
}

private fun JsonObject.preFilterDecisionTextOrNull(): String? {
    val value = this["result"]
        ?: this["text"]
        ?: this["content"]
        ?: this["message"]
        ?: return null

    return value.preFilterDecisionTextOrNull()
}

private fun JsonObjectBuilder.putNullableString(name: String, value: String?) {
    if (value == null) {
        put(name, JsonNull)
    } else {
        put(name, value)
    }
}

private fun JsonObjectBuilder.putNullableDecimal(name: String, value: BigDecimal?) {
    if (value == null) {
        put(name, JsonNull)
    } else {
        put(name, value.toPlainString())
    }
}

private const val PRE_FILTER_PHASE_NAME = "pre_filter"
private const val PRE_FILTER_CANDLE_LIMIT = 64
private const val PRE_FILTER_ATR_PERIOD = 14
private const val PRE_FILTER_DECISION_LIMIT = 200
private const val PRE_FILTER_TEXT_LIMIT = 480
private const val PRE_FILTER_DECIMAL_SCALE = 8
private const val PRE_FILTER_FAILURE_LOG_KEY = "llm-daemon-pre-filter-failure"
private val PRE_FILTER_DECISION_LOOKBACK: Duration = Duration.ofDays(7)

private val PreFilterStdoutJson = Json {
    ignoreUnknownKeys = true
}
