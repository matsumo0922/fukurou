@file:Suppress("ImportOrdering")

package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.FUKUROU_INVOCATION_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_LLM_PROVIDER_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_MARKET_SNAPSHOT_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_PROMPT_HASH_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_RUNTIME_CONFIG_HASH_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_RUNTIME_CONFIG_VERSION_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_SYSTEM_PROMPT_VERSION_ENV
import me.matsumo.fukurou.trading.audit.LlmAuditRootKind
import me.matsumo.fukurou.trading.audit.LlmInvocationAuditRoot
import me.matsumo.fukurou.trading.audit.LlmManifestJsonCodec
import me.matsumo.fukurou.trading.audit.LlmRunInputManifest
import me.matsumo.fukurou.trading.audit.LlmRunTriggerSnapshot
import me.matsumo.fukurou.trading.audit.LlmPhaseManifestRecorder
import me.matsumo.fukurou.trading.audit.LlmPhaseInputCaptureException
import me.matsumo.fukurou.trading.audit.StandardMaterialSnapshotException
import me.matsumo.fukurou.trading.audit.StandardMaterialSnapshotStage
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.PreviewOrderResult
import me.matsumo.fukurou.trading.broker.calculatePreviewHash
import me.matsumo.fukurou.trading.broker.toJsonObject
import me.matsumo.fukurou.trading.broker.toPreviewOrderNormalizedContent
import me.matsumo.fukurou.trading.config.LlmRoleAssignment
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.daemon.LlmDaemonPreFilterDecision
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimOutcome
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimRequest
import me.matsumo.fukurou.trading.daemon.LlmExecutionClaimState
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationFinish
import me.matsumo.fukurou.trading.daemon.LlmLaunchReservationStatus
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.SystemPromptV1
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.decision.identity.DecisionIdentityGenerator
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.DecisionTriggerKind
import me.matsumo.fukurou.trading.decision.identity.MaterialFreshness
import me.matsumo.fukurou.trading.decision.identity.MaterialAccountSnapshot
import me.matsumo.fukurou.trading.decision.identity.MaterialMissingSource
import me.matsumo.fukurou.trading.decision.identity.MarketFeatureBundle
import me.matsumo.fukurou.trading.decision.identity.MaterialCandleSummary
import me.matsumo.fukurou.trading.decision.identity.MaterialIndicatorSnapshot
import me.matsumo.fukurou.trading.decision.identity.MaterialOrderbookSummary
import me.matsumo.fukurou.trading.decision.identity.MaterialSourceMetadata
import me.matsumo.fukurou.trading.decision.identity.MaterialTickerSnapshot
import me.matsumo.fukurou.trading.decision.isFreshApprovedAt
import me.matsumo.fukurou.trading.decision.requiresEntryIntent
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_CANCELLED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import me.matsumo.fukurou.trading.evaluation.terminalCauseForInvocationFailure
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientRole
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicRequestCorrelation
import me.matsumo.fukurou.trading.exchange.gmo.withGmoPublicRequestCorrelation
import me.matsumo.fukurou.trading.invoker.CODEX_FAILURE_DETAILS_OMITTED
import me.matsumo.fukurou.trading.invoker.LlmArtifactCleanupQuarantine
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProcessTreeTerminationRegistry
import me.matsumo.fukurou.trading.invoker.LlmCliVersionProbe
import me.matsumo.fukurou.trading.invoker.LlmCommandRendererConfig
import me.matsumo.fukurou.trading.invoker.ProcessScopedLlmCliVersionProbe
import me.matsumo.fukurou.trading.invoker.LlmMcpServerConfig
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.McpLaunchManifestWriter
import me.matsumo.fukurou.trading.invoker.ProcessTreeTerminationProof
import me.matsumo.fukurou.trading.invoker.McpToolContractCatalog
import me.matsumo.fukurou.trading.invoker.classifyLlmFailure
import me.matsumo.fukurou.trading.invoker.isCodexProvider
import me.matsumo.fukurou.trading.invoker.readOptionalEnv
import me.matsumo.fukurou.trading.invoker.splitCommandTemplate
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.market.IndicatorCalculator
import me.matsumo.fukurou.trading.market.IndicatorParams
import me.matsumo.fukurou.trading.market.IndicatorType
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.persistence.persistedSnapshotHash
import me.matsumo.fukurou.trading.persistence.withSnapshotContentHash
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.tool.CallerInvocation
import java.math.BigDecimal
import java.math.RoundingMode
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import me.matsumo.fukurou.trading.tool.withSuppressedFailure
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 手動 one-shot runner の実行要求。
 *
 * @param repositoryRoot repository root path
 * @param workingDirectory LLM CLI process の working directory
 * @param mcpJarPath fukurou MCP fat jar path
 * @param cliConfig CLI / MCP server 接続設定
 * @param invocationId 外部予約済みの runner 起動 ID。未指定時は runner が生成する
 * @param marketSnapshotId 判断前 market snapshot ID。未指定時は invocation ID から生成する
 * @param triggerKind daemon trigger 種別。手動起動では null
 * @param proposerProvider Proposer provider
 * @param falsifierProvider Falsifier provider
 * @param proposerAssignment Proposer の provider / model / effort snapshot
 * @param falsifierAssignment Falsifier の provider / model / effort snapshot
 * @param preFilter claim 後かつ llm_runs / material I/O 前に実行する daemon pre-filter
 */
data class OneShotRunnerRequest(
    val repositoryRoot: Path,
    val workingDirectory: Path,
    val mcpJarPath: String,
    val cliConfig: OneShotRunnerCliConfig = OneShotRunnerCliConfig(),
    val invocationId: String? = null,
    val marketSnapshotId: String? = null,
    val triggerKind: LlmDaemonTriggerKind? = null,
    val proposerProvider: LlmProvider = LlmProvider.CLAUDE,
    val falsifierProvider: LlmProvider = LlmProvider.CODEX,
    val proposerAssignment: LlmRoleAssignment? = null,
    val falsifierAssignment: LlmRoleAssignment? = null,
    val preFilter: (suspend () -> LlmDaemonPreFilterDecision)? = null,
    val triggerSnapshot: LlmRunTriggerSnapshot? = null,
)

private fun OneShotRunnerRequest.effectiveProposerAssignment(): LlmRoleAssignment {
    return proposerAssignment ?: LlmRoleAssignment(provider = proposerProvider)
}

private fun OneShotRunnerRequest.effectiveFalsifierAssignment(): LlmRoleAssignment {
    return falsifierAssignment ?: LlmRoleAssignment(provider = falsifierProvider)
}

/**
 * one-shot runner が LLM CLI に渡す MCP 接続設定。
 *
 * @param mcpServerName MCP server 名
 * @param mcpServerCommand MCP server 起動 command
 * @param mcpServerArgs MCP server 起動引数。null の場合は mcpJarPath から `java -jar` 形式にする
 * @param proposerAllowedTools Proposer に許可する MCP tool allowlist
 * @param falsifierAllowedTools Falsifier に許可する MCP tool allowlist
 */
data class OneShotRunnerCliConfig(
    val mcpServerName: String = DEFAULT_RUNNER_MCP_SERVER_NAME,
    val mcpServerCommand: String = DEFAULT_RUNNER_MCP_SERVER_COMMAND,
    val mcpServerArgs: List<String>? = null,
    val proposerAllowedTools: List<String> = defaultProposerAllowedTools(DEFAULT_RUNNER_MCP_SERVER_NAME),
    val falsifierAllowedTools: List<String> = defaultFalsifierAllowedTools(DEFAULT_RUNNER_MCP_SERVER_NAME),
    val riskReductionAllowedTools: List<String> = defaultRiskReductionAllowedTools(DEFAULT_RUNNER_MCP_SERVER_NAME),
) {
    init {
        require(mcpServerName.isNotBlank()) {
            "mcpServerName must not be blank."
        }
        require(mcpServerCommand.isNotBlank()) {
            "mcpServerCommand must not be blank."
        }
        require(proposerAllowedTools.isNotEmpty()) {
            "proposerAllowedTools must not be empty."
        }
        require(falsifierAllowedTools.isNotEmpty()) {
            "falsifierAllowedTools must not be empty."
        }
        require(riskReductionAllowedTools.isNotEmpty()) { "riskReductionAllowedTools must not be empty." }

        val proposerNonMcpTools = proposerAllowedTools
            .filterNot { toolName -> toolName.isMcpToolNameFor(mcpServerName) }
        val falsifierNonMcpTools = falsifierAllowedTools
            .filterNot { toolName -> toolName.isMcpToolNameFor(mcpServerName) }
        val proposerForbiddenTools = shortMcpToolNames(proposerAllowedTools)
            .filter { toolName -> toolName in PROPOSER_FORBIDDEN_TOOL_NAMES }
        val falsifierForbiddenTools = shortMcpToolNames(falsifierAllowedTools)
            .filter { toolName -> toolName in FALSIFIER_FORBIDDEN_TOOL_NAMES }

        require(proposerNonMcpTools.isEmpty()) {
            "proposerAllowedTools must include only tools from MCP server $mcpServerName: $proposerNonMcpTools"
        }
        require(falsifierNonMcpTools.isEmpty()) {
            "falsifierAllowedTools must include only tools from MCP server $mcpServerName: $falsifierNonMcpTools"
        }
        require(proposerForbiddenTools.isEmpty()) {
            "proposerAllowedTools must not include falsifier or trade tools: $proposerForbiddenTools"
        }
        require(falsifierForbiddenTools.isEmpty()) {
            "falsifierAllowedTools must not include trade or proposer tools: $falsifierForbiddenTools"
        }
        require(shortMcpToolNames(proposerAllowedTools).toSet() == CANONICAL_PROPOSER_MCP_TOOL_NAMES) {
            "proposerAllowedTools must match the canonical Proposer policy."
        }
        require(shortMcpToolNames(falsifierAllowedTools).toSet() == CANONICAL_FALSIFIER_MCP_TOOL_NAMES) {
            "falsifierAllowedTools must match the canonical Falsifier policy."
        }
        McpToolContractCatalog.requireCanonical(LlmInvocationPhase.RISK_REDUCTION_ONLY, riskReductionAllowedTools)
    }

    companion object {
        /**
         * 環境変数から CLI 設定を構築する。
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): OneShotRunnerCliConfig {
            val serverName = environment.readOptionalEnv(FUKUROU_MCP_SERVER_NAME_ENV)
                ?: DEFAULT_RUNNER_MCP_SERVER_NAME
            val mcpArgs = environment.readOptionalEnv(FUKUROU_MCP_SERVER_ARGS_ENV)
                ?.splitCommandTemplate()

            return OneShotRunnerCliConfig(
                mcpServerName = serverName,
                mcpServerCommand = environment.readOptionalEnv(FUKUROU_MCP_SERVER_COMMAND_ENV)
                    ?: DEFAULT_RUNNER_MCP_SERVER_COMMAND,
                mcpServerArgs = mcpArgs,
                proposerAllowedTools = environment.readToolAllowlist(
                    name = FUKUROU_PROPOSER_ALLOWED_TOOLS_ENV,
                    defaultValue = defaultProposerAllowedTools(serverName),
                ),
                falsifierAllowedTools = environment.readToolAllowlist(
                    name = FUKUROU_FALSIFIER_ALLOWED_TOOLS_ENV,
                    defaultValue = defaultFalsifierAllowedTools(serverName),
                ),
                riskReductionAllowedTools = defaultRiskReductionAllowedTools(serverName),
            )
        }
    }
}

/**
 * one-shot runner の最終状態。
 */
enum class OneShotRunnerStatus {
    /**
     * NO_TRADE decision が DB に保存された。
     */
    NO_TRADE_DECISION,

    /**
     * Falsifier 承認後に paper entry まで到達した。
     */
    PAPER_ENTRY_PLACED,

    /**
     * EXIT decision を runner が決定論的に実行した。
     */
    PAPER_EXIT_EXECUTED,

    /**
     * REDUCE decision を runner が決定論的に実行した。
     */
    PAPER_REDUCE_EXECUTED,

    /**
     * ADJUST_PROTECTION decision を runner が決定論的に実行した。
     */
    PAPER_PROTECTION_UPDATED,

    /**
     * fail-closed no-trade audit を記録して終了した。
     */
    NO_TRADE_AUDITED,

    /**
     * 直近 1 時間の起動上限で launch を拒否した。
     */
    LAUNCH_REJECTED,

    /** daemon pre-filter が full one-shot を不要と判定した。 */
    PRE_FILTER_SKIPPED,
}

/**
 * one-shot runner の実行結果。
 *
 * @param invocationId runner 起動 ID
 * @param status 最終状態
 * @param decision 提出済み decision
 * @param intent entry intent
 * @param tradeResult paper trade result
 */
data class OneShotRunnerResult(
    val invocationId: String,
    val status: OneShotRunnerStatus,
    val decision: DecisionSubmissionResult?,
    val intent: TradeIntentRecord?,
    val tradeResult: PaperTradeResult?,
    val terminalCause: LlmRunTerminalCause = classifyOneShotTerminalCause(status, tradeResult),
)

/**
 * LLM 起動から Falsifier と deterministic paper entry までを 1 回だけ進める runner。
 *
 * @param tradingRuntime trading runtime
 * @param tradingConfig trading config
 * @param llmInvoker LLM 起動境界
 * @param runtimeConfigSnapshot 起動開始時に固定する runtime config snapshot
 * @param parentEnvironment 親 process environment
 * @param clock audit timestamp 用 clock
 * @param idGenerator invocation / tool call ID generator
 * @param logger 人間向け runner log 出力
 */
@Suppress("LargeClass")
class OneShotLlmRunner(
    private val tradingRuntime: TradingRuntime,
    private val tradingConfig: TradingBotConfig,
    private val llmInvoker: LlmInvoker,
    private val materialMarketDataSource: MarketDataSource? = null,
    private val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    private val parentEnvironment: Map<String, String> = System.getenv(),
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
    private val logger: (String) -> Unit = { message -> println(message) },
    private val cliVersionProbe: LlmCliVersionProbe = ProcessScopedLlmCliVersionProbe,
    private val commandRendererConfig: LlmCommandRendererConfig = LlmCommandRendererConfig(),
) {
    private val processOutputRedactor = SecretRedactor.fromEnvironment(parentEnvironment)
    private val invocationAuditor = LlmInvocationAuditor(
        commandEventLog = tradingRuntime.commandEventLog,
        redactor = processOutputRedactor,
        clock = clock,
        humanLogger = { message -> logHuman(message) },
        authFailureMessage = LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE,
        phaseManifestRecorder = LlmPhaseManifestRecorder(
            repository = tradingRuntime.llmInputManifestRepository,
            cliVersionProbe = cliVersionProbe,
            runtimeConfigSnapshot = runtimeConfigSnapshot,
            runtimeEnvironmentSnapshot = RuntimeConfigCatalog.runtimeEnvironment(tradingConfig)
                .toSortedMap()
                .entries
                .joinToString("\n") { entry -> "${entry.key}=${entry.value}" },
            clock = clock,
            commandRendererConfig = commandRendererConfig,
        ),
        decisionRepository = tradingRuntime.decisionRepository,
    )
    private val decisionExecutionLifecycle = DecisionExecutionLifecycle(
        tradingRuntime = tradingRuntime,
        tradingConfig = tradingConfig,
        clock = clock,
        idGenerator = idGenerator,
    )
    private val requestFactory = OneShotLlmRequestFactory(
        tradingConfig = tradingConfig,
        runtimeConfigSnapshot = runtimeConfigSnapshot,
        parentEnvironment = parentEnvironment,
    )
    private val runAuditRecorder = OneShotRunAuditRecorder(
        tradingRuntime = tradingRuntime,
        processOutputRedactor = processOutputRedactor,
        clock = clock,
        logHuman = ::logHuman,
    )
    private val phaseInvoker = OneShotPhaseInvoker(
        llmInvoker = llmInvoker,
        invocationAuditor = invocationAuditor,
        beforeInvoke = { request ->
            val claimantToken = requireNotNull(activeClaimantTokens[request.invocationId])
            LlmExecutionTerminationFenceRegistry.withClaimTransition(request.invocationId, claimantToken) {
                requireLiveClaim(request.invocationId, claimantToken)
                LlmExecutionAdmissionHealth.withHealthyAdmission {
                    LlmExecutionTerminationFenceRegistry.markChildMayBeRunning(request.invocationId, claimantToken)
                }
            }
        },
    )
    private val executionPolicy = OneShotExecutionPolicy.from(tradingConfig.runner)
    private val claimSupervisor = LlmExecutionClaimSupervisor(
        repository = tradingRuntime.launchReservationRepository,
        clock = clock,
        interval = executionPolicy.heartbeatInterval,
    )
    private val activeClaimantTokens = ConcurrentHashMap<String, String>()

    /**
     * one-shot runner を 1 回実行する。
     */
    @Suppress("LongMethod")
    suspend fun runOneShot(request: OneShotRunnerRequest): Result<OneShotRunnerResult> {
        val invocationId = request.invocationId ?: idGenerator().toString()
        val triggerKind = request.triggerKind
            ?: return Result.failure(IllegalStateException(LAUNCH_RESERVATION_MISSING))
        val claimantToken = idGenerator().toString()
        val claimedAt = clock.instant()
        val claimOutcome = withContext(NonCancellable) {
            tradingRuntime.launchReservationRepository.claimForExecution(
                LlmExecutionClaimRequest(
                    invocationId = invocationId,
                    triggerKind = triggerKind,
                    claimantToken = claimantToken,
                    claimedAt = claimedAt,
                    activeSince = claimedAt.minus(tradingConfig.daemon.launchReservationStaleAfter),
                ),
            ).also { outcome ->
                when (outcome) {
                    is LlmExecutionClaimOutcome.OutcomeUnknown -> claimSupervisor.register(invocationId, claimantToken)
                    is LlmExecutionClaimOutcome.Claimed -> LlmExecutionTerminationFenceRegistry.registerNoChildStarted(
                        invocationId = invocationId,
                        claimantToken = claimantToken,
                        observedAt = outcome.claimedAt,
                    )
                    is LlmExecutionClaimOutcome.Rejected -> Unit
                }
            }
        }
        when (claimOutcome) {
            is LlmExecutionClaimOutcome.Rejected -> {
                appendClaimPreflightAudit(
                    invocationId = invocationId,
                    triggerKind = triggerKind,
                    reason = claimOutcome.reason.wireCode,
                    canLaunch = false,
                )
                return Result.failure(IllegalStateException(claimOutcome.reason.wireCode))
            }
            is LlmExecutionClaimOutcome.OutcomeUnknown -> {
                return handleClaimOutcomeUnknown(
                    invocationId = invocationId,
                    triggerKind = triggerKind,
                    claimantToken = claimantToken,
                    activeSince = claimedAt.minus(tradingConfig.daemon.launchReservationStaleAfter),
                    cause = claimOutcome.cause,
                )
            }
            is LlmExecutionClaimOutcome.Claimed -> {
                runCatching {
                    currentCoroutineContext().ensureActive()
                    appendClaimPreflightAudit(
                        invocationId = invocationId,
                        triggerKind = triggerKind,
                        reason = "claimed",
                        canLaunch = true,
                    )
                }
                    .getOrElse { auditFailure ->
                        withContext(NonCancellable) {
                            withTimeout(executionPolicy.persistenceTerminalTimeout.toMillis()) {
                                tradingRuntime.launchReservationRepository.finish(
                                    LlmLaunchReservationFinish(
                                        invocationId = invocationId,
                                        status = LlmLaunchReservationStatus.FAILED,
                                        reason = RUN_START_AUDIT_FAILED_AFTER_CLAIM,
                                        finishedAt = clock.instant(),
                                        claimantToken = claimantToken,
                                    ),
                                ).getOrThrow()
                            }
                        }
                        LlmExecutionAdmissionHealth.resolveClaim(invocationId, claimantToken)
                        LlmExecutionTerminationFenceRegistry.resolve(invocationId, claimantToken)
                        return Result.failure(auditFailure)
                    }
            }
        }

        activeClaimantTokens[invocationId] = claimantToken
        return runClaimedOneShot(
            request = request,
            invocationId = invocationId,
            triggerKind = triggerKind,
            claimantToken = claimantToken,
            claimedAt = claimOutcome.claimedAt,
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun runClaimedOneShot(
        request: OneShotRunnerRequest,
        invocationId: String,
        triggerKind: LlmDaemonTriggerKind,
        claimantToken: String,
        claimedAt: java.time.Instant,
    ): Result<OneShotRunnerResult> {
        val marketSnapshotId = request.marketSnapshotId ?: "manual-$invocationId"
        val llmRunStart = LlmRunStart(
            invocationId = invocationId,
            mode = tradingConfig.mode,
            symbol = tradingConfig.symbol,
            triggerKind = request.triggerKind,
            startedAt = clock.instant(),
            runtimeConfigVersionId = runtimeConfigSnapshot?.versionId,
            runtimeConfigHash = runtimeConfigSnapshot?.hash,
        )
        var failureContext = requestFactory.decisionRunContext(
            invocationId = invocationId,
            provider = request.effectiveProposerAssignment().provider,
            promptHash = PROMPT_HASH_UNAVAILABLE,
            marketSnapshotId = marketSnapshotId,
        )

        val heartbeatJob = CoroutineScope(currentCoroutineContext()).launch {
            heartbeatClaim(invocationId, claimantToken)
        }
        var reservationStatus = LlmLaunchReservationStatus.FAILED
        var reservationReason = LlmRunTerminalCause.RUNNER_FAILED.name
        var llmRunStarted = false

        return try {
            requireLiveClaim(invocationId, claimantToken)
            runAuditRecorder.recordLlmRunStarted(llmRunStart).getOrThrow()
            llmRunStarted = true
            val deadlineRemaining = java.time.Duration.between(clock.instant(), claimedAt.plus(executionPolicy.hardTimeout))
            val bodyResult = try {
                check(!deadlineRemaining.isNegative && !deadlineRemaining.isZero) { "LLM execution deadline expired." }
                withTimeout(deadlineRemaining.toMillis()) {
                    runCatching {
                        val preFilterDecision = request.preFilter?.let { preFilter ->
                            LlmExecutionTerminationFenceRegistry.withClaimTransition(invocationId, claimantToken) {
                                requireLiveClaim(invocationId, claimantToken)
                                LlmExecutionAdmissionHealth.withHealthyAdmission {
                                    LlmExecutionTerminationFenceRegistry.markChildMayBeRunning(invocationId, claimantToken)
                                }
                            }
                            preFilter.invoke()
                        }
                        if (preFilterDecision == LlmDaemonPreFilterDecision.SKIP_NO_CHANGE) {
                            OneShotRunnerResult(
                                invocationId = invocationId,
                                status = OneShotRunnerStatus.PRE_FILTER_SKIPPED,
                                decision = null,
                                intent = null,
                                tradeResult = null,
                            )
                        } else {
                            runOneShotBody(
                                OneShotRunBodyInput(
                                    request = request,
                                    invocationId = invocationId,
                                    marketSnapshotId = marketSnapshotId,
                                    llmRunStart = llmRunStart,
                                    failureContextUpdated = { context -> failureContext = context },
                                ),
                            )
                        }
                    }
                }
            } catch (throwable: CancellationException) {
                Result.failure(throwable)
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }
            val throwable = bodyResult.exceptionOrNull()
            if (throwable is CancellationException) {
                if (llmRunStarted) handleCancelledRun(failureContext, llmRunStart, throwable)
                reservationReason = terminalCauseForInvocationFailure(throwable).name
                val classified = throwable.classifyLlmFailure(failureContext.llmProvider)
                if (classified !== throwable) {
                    throwable.suppressed.forEach(classified::addSuppressed)
                }
                throw classified
            }
            if (throwable != null) {
                reservationReason = terminalCauseForInvocationFailure(throwable).name
                val persistedFailure = if (llmRunStarted) {
                    handleFailedRun(failureContext, llmRunStart, throwable)
                } else {
                    throwable
                }
                return Result.failure(persistedFailure)
            }
            val result = bodyResult.getOrThrow()

            if (llmRunStarted) {
                runAuditRecorder.finalizeLlmRun(
                    start = llmRunStart,
                    status = result.status.name,
                    cause = null,
                    terminalCause = result.terminalCause,
                ).getOrThrow()
            }

            reservationStatus = LlmLaunchReservationStatus.FINISHED
            reservationReason = result.terminalCause.name
            Result.success(result)
        } finally {
            heartbeatJob.cancelAndJoin()
            val processTreeTerminationProof = LlmProcessTreeTerminationRegistry.find(invocationId)
            when (processTreeTerminationProof) {
                ProcessTreeTerminationProof.PROVEN_EXITED ->
                    LlmExecutionTerminationFenceRegistry.markProcessTreeExited(
                        invocationId = invocationId,
                        claimantToken = claimantToken,
                        observedAt = clock.instant(),
                    )
                ProcessTreeTerminationProof.UNCERTAIN ->
                    LlmExecutionAdmissionHealth.registerRecoveryBlocker(invocationId, claimantToken)
                null -> Unit
            }
            withContext(NonCancellable) {
                withTimeout(executionPolicy.persistenceTerminalTimeout.toMillis()) {
                    if (llmRunStarted) requireTerminalLlmRun(invocationId)
                    tradingRuntime.launchReservationRepository.finish(
                        LlmLaunchReservationFinish(
                            invocationId = invocationId,
                            status = reservationStatus,
                            reason = reservationReason,
                            finishedAt = clock.instant(),
                            claimantToken = claimantToken,
                        ),
                    ).getOrThrow()
                }
            }
            if (processTreeTerminationProof != ProcessTreeTerminationProof.UNCERTAIN) {
                LlmExecutionAdmissionHealth.resolveClaim(invocationId, claimantToken)
                LlmExecutionTerminationFenceRegistry.resolve(invocationId, claimantToken)
                LlmProcessTreeTerminationRegistry.resolve(invocationId)
            }
            activeClaimantTokens.remove(invocationId, claimantToken)
        }
    }

    private suspend fun requireLiveClaim(invocationId: String, claimantToken: String) {
        val admitted = tradingRuntime.launchReservationRepository.validateExecutionAdmission(invocationId, claimantToken)
            .getOrThrow()
        if (!admitted) throw LlmExecutionClaimLostException()
    }

    private suspend fun requireTerminalLlmRun(invocationId: String) {
        val run = tradingRuntime.llmRunRepository.findByInvocationId(invocationId).getOrThrow()
        check(run != null && run.status != me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_RUNNING) {
            "LLM run terminal persistence is incomplete."
        }
    }

    private suspend fun heartbeatClaim(invocationId: String, claimantToken: String) {
        while (currentCoroutineContext().isActive) {
            delay(executionPolicy.heartbeatInterval.toMillis())
            val heartbeatResult = tradingRuntime.launchReservationRepository.heartbeatExecutionClaim(
                invocationId = invocationId,
                claimantToken = claimantToken,
                heartbeatAt = clock.instant(),
            )
            val healthy = heartbeatResult.getOrNull() == true
            LlmExecutionAdmissionHealth.recordHeartbeatResult(invocationId, claimantToken, healthy)
            if (!healthy) return
        }
    }

    private suspend fun handleClaimOutcomeUnknown(
        invocationId: String,
        triggerKind: LlmDaemonTriggerKind,
        claimantToken: String,
        activeSince: java.time.Instant,
        cause: Throwable,
    ): Result<OneShotRunnerResult> {
        registerNoChildStartedFence(invocationId, claimantToken, clock.instant())
        runCatching {
            appendClaimPreflightAudit(
                invocationId = invocationId,
                triggerKind = triggerKind,
                reason = LAUNCH_RESERVATION_CLAIM_OUTCOME_UNKNOWN,
                canLaunch = false,
            )
        }
        val snapshotResult = tradingRuntime.launchReservationRepository.findExecutionClaim(invocationId)
        var snapshot = snapshotResult.getOrNull()
        var reconciliationRequired = snapshotResult.isFailure
        if (snapshot?.claimState == LlmExecutionClaimState.AVAILABLE) {
            when (
                tradingRuntime.launchReservationRepository.claimForExecution(
                    LlmExecutionClaimRequest(
                        invocationId = invocationId,
                        triggerKind = triggerKind,
                        claimantToken = claimantToken,
                        claimedAt = clock.instant(),
                        activeSince = activeSince,
                    ),
                )
            ) {
                is LlmExecutionClaimOutcome.Claimed -> {
                    val updatedSnapshot = tradingRuntime.launchReservationRepository.findExecutionClaim(invocationId)
                    snapshot = updatedSnapshot.getOrNull()
                    reconciliationRequired = updatedSnapshot.isFailure
                }
                is LlmExecutionClaimOutcome.OutcomeUnknown -> reconciliationRequired = true
                is LlmExecutionClaimOutcome.Rejected -> Unit
            }
        }
        val sameTokenClaim = snapshot?.claimState == LlmExecutionClaimState.CLAIMED &&
            snapshot.claimantToken == claimantToken
        if (sameTokenClaim && snapshot.status == LlmLaunchReservationStatus.RUNNING) {
            LlmExecutionTerminationFenceRegistry.registerNoChildStarted(
                invocationId = invocationId,
                claimantToken = claimantToken,
                observedAt = snapshot.claimedAt ?: clock.instant(),
            )
            withContext(NonCancellable) {
                tradingRuntime.launchReservationRepository.finish(
                    LlmLaunchReservationFinish(
                        invocationId = invocationId,
                        status = LlmLaunchReservationStatus.FAILED,
                        reason = LAUNCH_RESERVATION_CLAIM_OUTCOME_UNKNOWN,
                        finishedAt = clock.instant(),
                        claimantToken = claimantToken,
                        observedHeartbeatAt = snapshot.heartbeatAt,
                    ),
                ).getOrThrow()
            }
        } else if (!reconciliationRequired && snapshot?.status != LlmLaunchReservationStatus.RUNNING) {
            LlmExecutionAdmissionHealth.resolveClaim(invocationId, claimantToken)
        }
        return Result.failure(IllegalStateException(LAUNCH_RESERVATION_CLAIM_OUTCOME_UNKNOWN, cause))
    }

    private fun registerNoChildStartedFence(
        invocationId: String,
        claimantToken: String,
        observedAt: java.time.Instant,
    ) {
        LlmExecutionTerminationFenceRegistry.registerNoChildStarted(
            invocationId = invocationId,
            claimantToken = claimantToken,
            observedAt = observedAt,
        )
    }

    private suspend fun appendClaimPreflightAudit(
        invocationId: String,
        triggerKind: LlmDaemonTriggerKind,
        reason: String,
        canLaunch: Boolean,
    ) {
        runAuditRecorder.appendRunnerPhase(
            context = DecisionRunContext(
                decisionRunId = invocationId,
                llmProvider = null,
                promptHash = null,
                systemPromptVersion = null,
                marketSnapshotId = null,
                runtimeConfigVersionId = runtimeConfigSnapshot?.versionId,
                runtimeConfigHash = runtimeConfigSnapshot?.hash,
            ),
            phase = "reservation_claim",
            duration = Duration.ZERO,
            details = buildJsonObject {
                put("invocationId", invocationId)
                put("requestTriggerKind", triggerKind.name)
                put("reason", reason)
                put("canLaunch", canLaunch)
                put("hardTimeoutSeconds", executionPolicy.hardTimeout.seconds)
                put("heartbeatIntervalSeconds", executionPolicy.heartbeatInterval.seconds)
                put("heartbeatMissAllowanceSeconds", executionPolicy.heartbeatMissAllowance.seconds)
                put("processTerminationGraceSeconds", executionPolicy.processTerminationGrace.seconds)
                put("persistenceTerminalTimeoutSeconds", executionPolicy.persistenceTerminalTimeout.seconds)
                put("claimedPhaseCount", 3)
            },
        ).getOrThrow()
    }

    private suspend fun handleCancelledRun(
        failureContext: DecisionRunContext,
        llmRunStart: LlmRunStart,
        throwable: CancellationException,
    ) {
        val auditResult = withContext(NonCancellable) {
            runAuditRecorder.recordNoTrade(
                context = failureContext,
                reason = "caller_cancelled",
                cause = throwable,
            )
        }
        val finishResult = withContext(NonCancellable) {
            runAuditRecorder.finalizeLlmRun(
                start = llmRunStart,
                status = LLM_RUN_STATUS_CANCELLED,
                cause = throwable,
                llmProvider = failureContext.llmProvider,
            )
        }
        throwable.withSuppressedFailure(auditResult)
        throwable.withSuppressedFailure(finishResult)
    }

    private suspend fun handleFailedRun(
        failureContext: DecisionRunContext,
        llmRunStart: LlmRunStart,
        throwable: Throwable,
    ): Throwable {
        val auditResult = runAuditRecorder.recordNoTrade(
            context = failureContext,
            reason = "caller_failed",
            cause = throwable,
        )
        val finishResult = runAuditRecorder.finalizeLlmRun(
            start = llmRunStart,
            status = LLM_RUN_STATUS_FAILED,
            cause = throwable,
            llmProvider = failureContext.llmProvider,
        )

        return throwable
            .withSuppressedFailure(auditResult)
            .withSuppressedFailure(finishResult)
    }

    @Suppress("LongMethod")
    private suspend fun runOneShotBody(input: OneShotRunBodyInput): OneShotRunnerResult {
        requireLiveClaim(
            input.invocationId,
            requireNotNull(activeClaimantTokens[input.invocationId]),
        )
        val triggerSnapshot = input.request.triggerSnapshot ?: LlmRunTriggerSnapshot(
            kind = input.request.triggerKind?.name ?: "MANUAL",
            observedAt = input.llmRunStart.startedAt,
            measurements = emptyList(),
            entities = emptyList(),
            notApplicableReason = if (input.request.triggerKind == null) "MANUAL_TRIGGER_HAS_NO_MEASUREMENTS" else null,
        )
        val existingRoot = tradingRuntime.llmInputManifestRepository.findRoot(input.invocationId).getOrThrow()
        if (existingRoot == null) {
            tradingRuntime.llmInputManifestRepository.appendRoot(
                LlmInvocationAuditRoot(
                    rootId = input.invocationId,
                    kind = LlmAuditRootKind.DECISION_ATTEMPT,
                    capturedAt = input.llmRunStart.startedAt,
                ),
            ).getOrThrow()
        } else {
            require(existingRoot.kind == LlmAuditRootKind.DECISION_ATTEMPT) { "decision audit root kind mismatch." }
        }

        requireLiveClaimForInvocation(input.invocationId)
        val promptContent = requestFactory.readSystemPrompt(input.request.repositoryRoot)
        val promptHash = SystemPromptV1.calculateContentHash(promptContent)
        val proposerContext = requestFactory.decisionRunContext(
            invocationId = input.invocationId,
            provider = input.request.effectiveProposerAssignment().provider,
            promptHash = promptHash,
            marketSnapshotId = input.marketSnapshotId,
        )
        input.failureContextUpdated(proposerContext)

        requireLiveClaimForInvocation(input.invocationId)
        val ttlSweepResult = decisionExecutionLifecycle.cancelExpiredRestingEntryOrders(proposerContext)
        if (ttlSweepResult.isFailure) {
            return recordTtlSweepFailure(input.invocationId, proposerContext, ttlSweepResult.exceptionOrNull())
        }

        val materialResult = captureStandardMaterialSnapshot(input, triggerSnapshot)
        if (materialResult.isFailure) {
            val standardFailure = requireNotNull(materialResult.exceptionOrNull())
            recordStandardSnapshotFailure(proposerContext, standardFailure)

            return runRiskReductionOnly(
                input = input,
                triggerSnapshot = triggerSnapshot,
                standardFailure = standardFailure,
                persistedStandardMaterial = null,
            ).withStandardSnapshotFailureTerminalCause()
        }
        val materialManifest = materialResult.getOrThrow()

        val afterPreflightInput = OneShotAfterPreflightRequest(
            request = input.request,
            invocationId = input.invocationId,
            promptContent = promptContent,
            promptHash = promptHash,
            marketSnapshotId = input.marketSnapshotId,
            proposerContext = proposerContext,
            failureContextUpdated = input.failureContextUpdated,
        )
        return try {
            runOneShotAfterPreflight(afterPreflightInput)
        } catch (throwable: LlmPhaseInputCaptureException) {
            runRiskReductionOnly(input, triggerSnapshot, throwable, materialManifest)
        }
    }

    private suspend fun appendRunInputManifest(
        input: OneShotRunBodyInput,
        triggerSnapshot: LlmRunTriggerSnapshot,
        materialManifest: DecisionMaterialStateManifest,
    ): Result<Unit> {
        val runManifestWithoutHash = LlmRunInputManifest(
            invocationId = input.invocationId,
            rootId = input.invocationId,
            trigger = triggerSnapshot,
            runtimeConfigVersion = runtimeConfigSnapshot?.versionId,
            runtimeConfigHash = runtimeConfigSnapshot?.hash,
            runtimeConfigSnapshot = RuntimeConfigCatalog.runtimeEnvironment(tradingConfig)
                .toSortedMap()
                .entries
                .joinToString("\n") { entry -> "${entry.key}=${entry.value}" },
            materialInvocationId = materialManifest.invocationId,
            materialContentHash = materialManifest.persistedSnapshotHash(),
            schemaVersion = materialManifest.schemaVersion,
            capturedAt = clock.instant(),
            canonicalContentHash = "",
        )
        val runManifest = runManifestWithoutHash.copy(
            canonicalContentHash = LlmManifestJsonCodec.contentHash(runManifestWithoutHash),
        )
        return tradingRuntime.llmInputManifestRepository.appendRunWithMaterial(materialManifest, runManifest)
    }

    private suspend fun captureStandardMaterialSnapshot(
        input: OneShotRunBodyInput,
        triggerSnapshot: LlmRunTriggerSnapshot,
    ): Result<DecisionMaterialStateManifest> {
        requireLiveClaimForInvocation(input.invocationId)
        val captured = runStandardSnapshotStage(StandardMaterialSnapshotStage.CAPTURE) {
            captureMaterialInputs(input)
        }.getOrElse { return Result.failure(it) }

        requireLiveClaimForInvocation(input.invocationId)
        val validated = runStandardSnapshotStage(StandardMaterialSnapshotStage.VALIDATION) {
            validateMaterialManifest(input, captured)
        }.getOrElse { return Result.failure(it) }

        requireLiveClaimForInvocation(input.invocationId)
        return runStandardSnapshotStage(StandardMaterialSnapshotStage.HASH_SERIALIZATION) {
            val hashed = validated.withSnapshotContentHash()
            appendRunInputManifest(input, triggerSnapshot, hashed).getOrThrow()
            hashed
        }
    }

    private suspend fun <T> runStandardSnapshotStage(
        stage: StandardMaterialSnapshotStage,
        block: suspend () -> T,
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: LlmExecutionClaimLostException) {
            throw throwable
        } catch (throwable: StandardMaterialSnapshotException) {
            Result.failure(throwable)
        } catch (throwable: Throwable) {
            Result.failure(StandardMaterialSnapshotException(stage, throwable))
        }
    }

    private suspend fun recordStandardSnapshotFailure(context: DecisionRunContext, standardFailure: Throwable) {
        val failure = standardFailure as? StandardMaterialSnapshotException ?: return
        val rootCauseClass = failure.cause.safeSnapshotFailureClass()
        val auditFailure = try {
            runAuditRecorder.appendRunnerPhase(
                context = context,
                phase = STANDARD_MATERIAL_SNAPSHOT_PHASE,
                duration = Duration.ZERO,
                details = buildJsonObject {
                    put("outcome", "failed")
                    put("failureStage", failure.stage.name)
                    put("failureCode", failure.failureCode())
                    put("rootCauseClass", rootCauseClass)
                },
            ).exceptionOrNull()
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            throwable
        }
        auditFailure?.let(standardFailure::addSuppressed)
    }

    @Suppress("LongMethod")
    private suspend fun runRiskReductionOnly(
        input: OneShotRunBodyInput,
        triggerSnapshot: LlmRunTriggerSnapshot,
        standardFailure: Throwable,
        persistedStandardMaterial: DecisionMaterialStateManifest?,
    ): OneShotRunnerResult {
        val manifestResult = if (persistedStandardMaterial == null) {
            createRiskReductionMaterialManifest(input, standardFailure)
                .mapCatching { manifest ->
                    appendRunInputManifest(input, triggerSnapshot, manifest).getOrThrow()
                    manifest
                }
        } else {
            Result.success(persistedStandardMaterial)
        }
        if (manifestResult.isFailure) {
            runAuditRecorder.recordNoTrade(
                context = DecisionRunContext.EMPTY,
                reason = "risk_reduction_only_manifest_unavailable",
                cause = manifestResult.exceptionOrNull(),
            ).getOrThrow()

            return OneShotRunnerResult(
                invocationId = input.invocationId,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                decision = null,
                intent = null,
                tradeResult = null,
            )
        }
        val promptHash = SystemPromptV1.calculateContentHash(RISK_REDUCTION_ONLY_PROMPT)
        val context = requestFactory.decisionRunContext(
            invocationId = input.invocationId,
            provider = input.request.effectiveProposerAssignment().provider,
            promptHash = promptHash,
            marketSnapshotId = input.marketSnapshotId,
        )
        input.failureContextUpdated(context)
        val request = requestFactory.llmRequest(
            LlmRequestInput(
                invocationId = input.invocationId,
                provider = input.request.effectiveProposerAssignment().provider,
                assignment = input.request.effectiveProposerAssignment(),
                phase = LlmInvocationPhase.RISK_REDUCTION_ONLY,
                prompt = RISK_REDUCTION_ONLY_PROMPT,
                decisionRunContext = context,
                request = input.request,
                intentId = null,
            ),
        )
        val audit = phaseInvoker.invokePhase("risk_reduction_only", context, request)
        val decision = tradingRuntime.decisionRepository.latestDecisionByInvocationId(input.invocationId).getOrThrow()
        if (decision == null) {
            runAuditRecorder.recordNoTrade(
                context = context,
                reason = "risk_reduction_only_missing_decision",
                cause = audit.exceptionOrNull() ?: standardFailure,
            ).getOrThrow()

            return OneShotRunnerResult(
                invocationId = input.invocationId,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                decision = null,
                intent = null,
                tradeResult = null,
            )
        }
        if (decision.decision.submission.action !in RISK_REDUCTION_ONLY_ACTIONS) {
            runAuditRecorder.recordNoTrade(
                context = context,
                reason = "risk_reduction_only_action_rejected",
                cause = null,
            ).getOrThrow()

            return OneShotRunnerResult(
                invocationId = input.invocationId,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                decision = decision,
                intent = null,
                tradeResult = null,
            )
        }

        return handleNonEnterDecision(
            input = OneShotAfterPreflightRequest(
                request = input.request,
                invocationId = input.invocationId,
                promptContent = RISK_REDUCTION_ONLY_PROMPT,
                promptHash = promptHash,
                marketSnapshotId = input.marketSnapshotId,
                proposerContext = context,
                failureContextUpdated = input.failureContextUpdated,
            ),
            decision = decision,
            attributionIncomplete = audit.getOrNull()?.observationAppendFailure != null,
        )
    }

    private suspend fun createRiskReductionMaterialManifest(
        input: OneShotRunBodyInput,
        standardFailure: Throwable,
    ): Result<DecisionMaterialStateManifest> = runCatching {
        val account = tradingRuntime.decisionAccountSnapshotReader.read().getOrElse { throwable ->
            standardFailure.addSuppressed(throwable)
            throw standardFailure
        }
        val missing = MaterialMissingSource("STANDARD_CONTEXT", standardFailure.toStandardContextFailureCode())
        val canonical = "schema=2\nrisk=${account.riskState}\nmissing=${missing.source}:${missing.reason}"
        DecisionMaterialStateManifest(
            invocationId = input.invocationId,
            capturedAt = clock.instant(),
            triggerKind = if (input.request.triggerKind == null) DecisionTriggerKind.MANUAL else DecisionTriggerKind.DAEMON,
            symbol = tradingConfig.symbol.apiSymbol,
            runtimeConfigVersion = runtimeConfigSnapshot?.versionId,
            runtimeConfigHash = runtimeConfigSnapshot?.hash,
            riskState = account.riskState,
            bestBidJpy = null,
            bestAskJpy = null,
            lastPriceJpy = null,
            sourceTimestamp = null,
            freshness = MaterialFreshness.UNKNOWN,
            atr14FiveMinutesJpy = null,
            latestCandleOpenJpy = null,
            latestCandleHighJpy = null,
            latestCandleLowJpy = null,
            latestCandleCloseJpy = null,
            openPositionFacts = account.positions.map { fact -> "${fact.id}|${fact.status}|${fact.side}" },
            openOrderFacts = account.openOrders.map { fact -> "${fact.id}|${fact.status}|${fact.side}|${fact.type}" },
            missingSources = listOf(missing),
            schemaVersion = 2,
            canonicalContentHash = DecisionIdentityGenerator.contentHash(canonical),
            marketFeatureBundle = MarketFeatureBundle(
                ticker = null,
                candleSummaries = emptyList(),
                indicators = emptyList(),
                orderbookSummary = null,
                account = account,
                missingSources = listOf(missing),
            ),
        ).withSnapshotContentHash()
    }

    private suspend fun captureMaterialInputs(input: OneShotRunBodyInput): CapturedMaterialInputs {
        val capturedAt = clock.instant()
        val account = tradingRuntime.decisionAccountSnapshotReader.read().getOrThrow()
        val marketDataSource = requireNotNull(materialMarketDataSource) { "material market data source is required." }
        val ticker = marketDataSource.getTicker(tradingConfig.symbol).getOrThrow()
        val candles = marketDataSource.getCandles(
            symbol = tradingConfig.symbol,
            interval = CandleInterval.FIVE_MINUTES,
            limit = 64,
        ).getOrThrow()
        val orderbookResult = withGmoPublicRequestCorrelation(
            GmoPublicRequestCorrelation(
                decisionRunContext = DecisionRunContext.EMPTY.copy(decisionRunId = input.invocationId),
                toolCallId = idGenerator().toString(),
                clientRole = GmoPublicClientRole.RUNNER,
            ),
        ) {
            marketDataSource.getOrderbook(tradingConfig.symbol, MATERIAL_ORDERBOOK_LEVEL_LIMIT)
        }
        val orderbook = orderbookResult.getOrElse { throwable ->
            if (throwable is UnsupportedOperationException) null else throw throwable
        }

        return CapturedMaterialInputs(
            capturedAt = capturedAt,
            account = account,
            ticker = ticker,
            candles = candles,
            orderbook = orderbook,
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun validateMaterialManifest(
        input: OneShotRunBodyInput,
        captured: CapturedMaterialInputs,
    ): DecisionMaterialStateManifest {
        val capturedAt = captured.capturedAt
        val account = captured.account
        val ticker = captured.ticker
        val candles = captured.candles
        val orderbook = captured.orderbook
        val candleSummaries = candles.sortedBy { candle -> candle.openTime }.map { candle ->
            MaterialCandleSummary(
                openTime = runCatching { Instant.parse(candle.openTime) }
                    .getOrElse { throw IllegalArgumentException("Required candle timestamp is malformed.") },
                openJpy = candle.open.requiredPositiveMarketDecimal("candle open"),
                highJpy = candle.high.requiredPositiveMarketDecimal("candle high"),
                lowJpy = candle.low.requiredPositiveMarketDecimal("candle low"),
                closeJpy = candle.close.requiredPositiveMarketDecimal("candle close"),
                volumeBtc = candle.volume.toBigDecimalOrNull(),
            ).also { summary ->
                require(summary.highJpy >= summary.lowJpy) { "Required candle range is malformed." }
            }
        }
        val latestCandle = candleSummaries.maxByOrNull { candle -> candle.openTime }
        val atr = IndicatorCalculator.calculate(
            candles = candles,
            indicator = IndicatorType.ATR,
            params = IndicatorParams(period = 14),
        ).getOrNull()?.values?.lastOrNull { value -> value.value != null }?.value?.let(BigDecimal::valueOf)
        val sourceTimestamp = runCatching { Instant.parse(ticker.timestamp) }.getOrNull()
        val freshness = when {
            sourceTimestamp == null -> MaterialFreshness.UNKNOWN
            Duration.between(sourceTimestamp, capturedAt) > MATERIAL_QUOTE_FRESHNESS -> MaterialFreshness.STALE
            else -> MaterialFreshness.FRESH
        }
        val missingSources = buildList {
            if (sourceTimestamp == null) add(MaterialMissingSource("SOURCE_TIMESTAMP", "MISSING_OR_INVALID"))
            if (atr == null) add(MaterialMissingSource("ATR14", "INSUFFICIENT_VALID_SAMPLES"))
            if (orderbook == null) add(MaterialMissingSource("ORDERBOOK", "SOURCE_NOT_IMPLEMENTED"))
        }
        val bid = ticker.bid.requiredPositiveMarketDecimal("ticker bid")
        val ask = ticker.ask.requiredPositiveMarketDecimal("ticker ask")
        val last = ticker.last.requiredPositiveMarketDecimal("ticker last")
        require(ask >= bid) { "Required ticker spread is malformed." }
        val positionFacts = account.positions.map { fact -> "${fact.id}|${fact.status}|${fact.side}" }
        val orderFacts = account.openOrders.map { fact -> "${fact.id}|${fact.status}|${fact.side}|${fact.type}" }
        val bundle = MarketFeatureBundle(
            ticker = MaterialTickerSnapshot(
                bestBidJpy = bid,
                bestAskJpy = ask,
                lastPriceJpy = last,
                metadata = MaterialSourceMetadata(sourceTimestamp, "GMO_PUBLIC_TICKER", false, 1),
            ),
            candleSummaries = candleSummaries,
            indicators = listOf(MaterialIndicatorSnapshot("ATR14_5M", atr, candles.size)),
            orderbookSummary = orderbook?.toMaterialSummary(sourceTimestamp, capturedAt),
            account = account,
            missingSources = missingSources,
        )
        val canonical = listOf(
            "symbol=${tradingConfig.symbol.apiSymbol}",
            "risk=${account.riskState}",
            "bid=${DecisionIdentityGenerator.canonicalDecimal(bid)}",
            "ask=${DecisionIdentityGenerator.canonicalDecimal(ask)}",
            "last=${DecisionIdentityGenerator.canonicalDecimal(last)}",
            "atr=${atr?.let(DecisionIdentityGenerator::canonicalDecimal) ?: "null"}",
            "candle=${latestCandle?.let { "${it.openJpy}|${it.highJpy}|${it.lowJpy}|${it.closeJpy}" } ?: "null"}",
            "positions=${positionFacts.joinToString(",")}",
            "orders=${orderFacts.joinToString(",")}",
            "freshness=${freshness.name}",
            "missing=${missingSources.joinToString(",") { "${it.source}:${it.reason}" }}",
        ).joinToString("\n")
        val thresholdRatio = tradingConfig.daemon.priceMoveThresholdRatio
        return DecisionMaterialStateManifest(
            invocationId = input.invocationId,
            capturedAt = capturedAt,
            triggerKind = if (input.request.triggerKind == null) DecisionTriggerKind.MANUAL else DecisionTriggerKind.DAEMON,
            symbol = tradingConfig.symbol.apiSymbol,
            runtimeConfigVersion = runtimeConfigSnapshot?.versionId,
            runtimeConfigHash = runtimeConfigSnapshot?.hash,
            riskState = account.riskState,
            priceMoveThresholdRatio = thresholdRatio,
            bestBidJpy = bid,
            bestAskJpy = ask,
            lastPriceJpy = last,
            sourceTimestamp = sourceTimestamp,
            freshness = freshness,
            atr14FiveMinutesJpy = atr,
            latestCandleOpenJpy = latestCandle?.openJpy,
            latestCandleHighJpy = latestCandle?.highJpy,
            latestCandleLowJpy = latestCandle?.lowJpy,
            latestCandleCloseJpy = latestCandle?.closeJpy,
            openPositionFacts = positionFacts,
            openOrderFacts = orderFacts,
            missingSources = missingSources,
            schemaVersion = 2,
            canonicalContentHash = DecisionIdentityGenerator.contentHash(canonical),
            materialProjection = "",
            marketFeatureBundle = bundle,
        )
    }

    private suspend fun recordTtlSweepFailure(
        invocationId: String,
        proposerContext: DecisionRunContext,
        cause: Throwable?,
    ): OneShotRunnerResult {
        runAuditRecorder.recordNoTrade(
            context = proposerContext,
            reason = "stale_entry_order_ttl_cancel_failed",
            cause = cause,
        ).getOrThrow()
        logHuman("stale entry order ttl cancellation failed invocation=$invocationId")

        return OneShotRunnerResult(
            invocationId = invocationId,
            status = OneShotRunnerStatus.NO_TRADE_AUDITED,
            decision = null,
            intent = null,
            tradeResult = null,
            terminalCause = terminalCauseForNoTrade(cause),
        )
    }

    private suspend fun runOneShotAfterPreflight(input: OneShotAfterPreflightRequest): OneShotRunnerResult {
        val invocationId = input.invocationId
        val proposerContext = input.proposerContext
        val proposerResult = proposerDecision(input)
        val decision = proposerResult.decision

        if (decision == null) {
            val noTradeReason = noDecisionAuditReason(
                input = input,
                proposerResult = proposerResult,
                commandEventLog = tradingRuntime.commandEventLog,
            )

            runAuditRecorder.recordNoTrade(
                context = proposerContext,
                reason = noTradeReason,
                cause = proposerResult.failure,
            ).getOrThrow()

            return OneShotRunnerResult(
                invocationId = invocationId,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                decision = null,
                intent = null,
                tradeResult = null,
                terminalCause = terminalCauseForNoTrade(proposerResult.failure),
            )
        }

        if (!decision.decision.submission.action.requiresEntryIntent()) {
            return handleNonEnterDecision(input, decision, proposerResult.attributionIncomplete)
        }

        if (proposerResult.attributionIncomplete || hasDecisionAttributionGap(invocationId)) {
            runAuditRecorder.recordNoTrade(
                context = proposerContext,
                reason = "phase_observation_missing_entry_rejected",
                cause = proposerResult.observationAppendFailure,
            ).getOrThrow()

            return OneShotRunnerResult(
                invocationId = invocationId,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                decision = decision,
                intent = decision.tradeIntent,
                tradeResult = null,
            )
        }

        return runApprovedEntryFlow(input, decision)
    }

    private suspend fun proposerDecision(input: OneShotAfterPreflightRequest): ProposerDecisionResult {
        val request = input.request
        val proposerContext = input.proposerContext
        val proposerRequest = requestFactory.llmRequest(
            LlmRequestInput(
                invocationId = input.invocationId,
                provider = request.effectiveProposerAssignment().provider,
                assignment = request.effectiveProposerAssignment(),
                phase = LlmInvocationPhase.PROPOSER,
                prompt = requestFactory.buildProposerPrompt(input.promptContent),
                decisionRunContext = proposerContext,
                request = request,
                intentId = null,
            ),
        )
        val proposerAudit = phaseInvoker
            .invokePhase("proposer", proposerContext, proposerRequest)
        requireLiveClaimForInvocation(input.invocationId)
        val decision = tradingRuntime.decisionRepository
            .latestDecisionByInvocationId(input.invocationId)
            .getOrThrow()

        return ProposerDecisionResult(
            decision = decision,
            failure = proposerAudit.exceptionOrNull(),
            authFailureSuspected = proposerAudit.getOrNull()?.authFailureSuspected ?: false,
            cliErrorReported = proposerAudit.getOrNull()?.cliErrorReported ?: false,
            observationAppendFailure = proposerAudit.getOrNull()?.observationAppendFailure,
        )
    }

    private suspend fun handleNonEnterDecision(
        input: OneShotAfterPreflightRequest,
        decision: DecisionSubmissionResult,
        attributionIncomplete: Boolean,
    ): OneShotRunnerResult {
        logHuman("decision saved invocation=${input.invocationId} action=${decision.decision.submission.action}")

        requireLiveClaimForInvocation(input.invocationId)
        if (attributionIncomplete) {
            runAuditRecorder.appendRunnerPhase(
                context = input.proposerContext,
                phase = "infrastructure_attribution",
                duration = Duration.ZERO,
                details = buildJsonObject { put("status", "INFRASTRUCTURE_ATTRIBUTION_INCOMPLETE") },
            ).getOrThrow()
        }
        val lifecycleResult = when (decision.decision.submission.action) {
            DecisionAction.EXIT -> decisionExecutionLifecycle.executeExitDecision(input.proposerContext, decision)
            DecisionAction.REDUCE -> decisionExecutionLifecycle.executeReduceDecision(input.proposerContext, decision)
            DecisionAction.ADJUST_PROTECTION -> decisionExecutionLifecycle.executeAdjustProtectionDecision(
                context = input.proposerContext,
                decision = decision,
            )
            DecisionAction.NO_TRADE -> null
            DecisionAction.ENTER -> null
            DecisionAction.ADD_LONG -> null
        }

        if (lifecycleResult != null) {
            return OneShotRunnerResult(
                invocationId = input.invocationId,
                status = lifecycleResult.status,
                decision = decision,
                intent = null,
                tradeResult = lifecycleResult.tradeResult,
            )
        }

        return OneShotRunnerResult(
            invocationId = input.invocationId,
            status = OneShotRunnerStatus.NO_TRADE_DECISION,
            decision = decision,
            intent = null,
            tradeResult = null,
        )
    }

    @Suppress("LongMethod")
    private suspend fun runApprovedEntryFlow(
        input: OneShotAfterPreflightRequest,
        decision: DecisionSubmissionResult,
    ): OneShotRunnerResult {
        val request = input.request
        val invocationId = input.invocationId
        val proposerContext = input.proposerContext
        val intent = requireNotNull(decision.tradeIntent) {
            "${decision.decision.submission.action.name} decision did not create trade intent."
        }
        val falsifierContext = requestFactory.decisionRunContext(
            invocationId = invocationId,
            provider = request.effectiveFalsifierAssignment().provider,
            promptHash = input.promptHash,
            marketSnapshotId = input.marketSnapshotId,
        )
        input.failureContextUpdated(falsifierContext)
        val falsifierResult = runFalsifierPhase(input, intent, falsifierContext)

        if (!falsifierResult.approved) {
            recordFalsificationNoTrade(
                context = falsifierContext,
                falsification = falsifierResult.falsification,
                cause = falsifierResult.failure,
            ).getOrThrow()

            return entryFlowResult(
                invocationId = invocationId,
                decision = decision,
                intent = intent,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                terminalCause = terminalCauseForNoTrade(falsifierResult.failure),
            )
        }

        if (falsifierResult.attributionIncomplete || hasDecisionAttributionGap(invocationId)) {
            runAuditRecorder.recordNoTrade(
                context = falsifierContext,
                reason = "phase_observation_missing_entry_rejected",
                cause = falsifierResult.observationAppendFailure,
            ).getOrThrow()

            return entryFlowResult(
                invocationId = invocationId,
                decision = decision,
                intent = intent,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
            )
        }

        input.failureContextUpdated(proposerContext)
        if (decision.decision.submission.action == DecisionAction.ADD_LONG) {
            requireLiveClaimForInvocation(invocationId)
            decisionExecutionLifecycle.ensureAddLongTargetPosition(proposerContext)?.let { lifecycleResult ->
                return entryFlowResult(invocationId, decision, intent, lifecycleResult.status, lifecycleResult.tradeResult)
            }
        }

        val placeResult = placeApprovedEntry(
            context = proposerContext,
            intent = intent,
            timeStopAt = decision.tradePlan?.draft?.timeStopAt,
        )
        val placed = placeResult.getOrNull()

        if (placed == null) {
            runAuditRecorder.recordNoTrade(
                context = proposerContext,
                reason = "place_order_failed",
                cause = placeResult.exceptionOrNull(),
            ).getOrThrow()

            return entryFlowResult(
                invocationId = invocationId,
                decision = decision,
                intent = intent,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                terminalCause = terminalCauseForNoTrade(placeResult.exceptionOrNull()),
            )
        }

        val finalStatus = if (placed.accepted) {
            OneShotRunnerStatus.PAPER_ENTRY_PLACED
        } else {
            OneShotRunnerStatus.NO_TRADE_AUDITED
        }

        return entryFlowResult(invocationId, decision, intent, finalStatus, placed)
    }

    private fun entryFlowResult(
        invocationId: String,
        decision: DecisionSubmissionResult,
        intent: TradeIntentRecord,
        status: OneShotRunnerStatus,
        tradeResult: PaperTradeResult? = null,
        terminalCause: LlmRunTerminalCause? = null,
    ): OneShotRunnerResult {
        return OneShotRunnerResult(
            invocationId = invocationId,
            status = status,
            decision = decision,
            intent = intent,
            tradeResult = tradeResult,
            terminalCause = terminalCause ?: classifyOneShotTerminalCause(status, tradeResult),
        )
    }

    private suspend fun runFalsifierPhase(
        input: OneShotAfterPreflightRequest,
        intent: TradeIntentRecord,
        falsifierContext: DecisionRunContext,
    ): FalsifierPhaseResult {
        val request = input.request
        val falsifierRequest = requestFactory.llmRequest(
            LlmRequestInput(
                invocationId = input.invocationId,
                provider = request.effectiveFalsifierAssignment().provider,
                assignment = request.effectiveFalsifierAssignment(),
                phase = LlmInvocationPhase.FALSIFIER,
                prompt = requestFactory.buildFalsifierPrompt(input.promptContent, intent.intentId),
                decisionRunContext = falsifierContext,
                request = request,
                intentId = intent.intentId,
            ),
        )
        val falsifierAudit = phaseInvoker.invokePhase("falsifier", falsifierContext, falsifierRequest)
        val falsifierFailure = falsifierAudit.exceptionOrNull()
        val falsification = tradingRuntime.decisionRepository
            .latestFalsification(intent.intentId)
            .getOrThrow()
        val approved = falsification.isFreshApprovedAt(
            clock.instant(),
            tradingConfig.decisionProtocol.falsificationFreshnessWindow,
        )

        return FalsifierPhaseResult(
            falsification = falsification,
            failure = falsifierFailure,
            approved = approved,
            observationAppendFailure = falsifierAudit.getOrNull()?.observationAppendFailure,
        )
    }

    private suspend fun hasDecisionAttributionGap(invocationId: String): Boolean {
        return ATTRIBUTION_PHASES.any { phase ->
            val phaseId = "$invocationId:${phase.name}"
            val manifest = tradingRuntime.llmInputManifestRepository.findPhase(phaseId).getOrThrow()

            manifest != null && tradingRuntime.llmInputManifestRepository.findObservation(phaseId).getOrThrow() == null
        }
    }

    private suspend fun placeApprovedEntry(
        context: DecisionRunContext,
        intent: TradeIntentRecord,
        timeStopAt: Instant?,
    ): Result<PaperTradeResult> {
        val arrivedAtPlaceOrder = clock.instant()
        val decisionToPlaceOrderDuration = Duration.between(intent.createdAt, arrivedAtPlaceOrder)
        val previewResult = previewApprovedEntry(context, intent, timeStopAt)
        val preview = previewResult.preview

        if (preview == null) {
            appendDecisionToPlaceOrderPhase(
                input = DecisionToPlaceOrderPhaseInput(
                    context = context,
                    intent = intent,
                    decisionToPlaceOrderDuration = decisionToPlaceOrderDuration,
                    previewExecutionDuration = previewResult.duration,
                    preview = null,
                    placeOrderExecutionDuration = null,
                    placeOrderHash = null,
                    hashMismatchWarning = null,
                    accepted = false,
                ),
            ).getOrThrow()

            return Result.failure(requireNotNull(previewResult.failure))
        }

        return executeApprovedEntry(
            context = context,
            intent = intent,
            timeStopAt = timeStopAt,
            decisionToPlaceOrderDuration = decisionToPlaceOrderDuration,
            previewResult = previewResult,
        )
    }

    private suspend fun previewApprovedEntry(
        context: DecisionRunContext,
        intent: TradeIntentRecord,
        timeStopAt: Instant?,
    ): EntryPreviewResult {
        requireLiveClaimForContext(context)
        val previewStartedAt = System.nanoTime()
        val previewCall = GuardedToolCall(
            toolName = "preview_order",
            toolCallId = idGenerator().toString(),
            clientRequestId = "runner-preview-order-${intent.intentId}",
            decisionRunContext = context,
            payload = runnerIntentPayload(intent),
        )
        val previewCommand = intent.toPlaceOrderCommand(
            call = previewCall,
            timeStopAt = timeStopAt,
            commandId = idGenerator(),
        )
        val previewResult = tradingRuntime.toolCallGuard.runReadOnlyTool(previewCall) {
            withGmoPublicRequestCorrelation(
                GmoPublicRequestCorrelation(
                    decisionRunContext = context,
                    toolCallId = previewCall.toolCallId,
                    clientRole = GmoPublicClientRole.RUNNER,
                ),
            ) {
                tradingRuntime.broker.previewOrder(previewCommand).getOrThrow()
            }
        }

        return EntryPreviewResult(
            preview = previewResult.getOrNull(),
            duration = Duration.ofNanos(System.nanoTime() - previewStartedAt),
            failure = previewResult.exceptionOrNull(),
        )
    }

    private suspend fun executeApprovedEntry(
        context: DecisionRunContext,
        intent: TradeIntentRecord,
        timeStopAt: Instant?,
        decisionToPlaceOrderDuration: Duration,
        previewResult: EntryPreviewResult,
    ): Result<PaperTradeResult> {
        val preview = requireNotNull(previewResult.preview)
        val executionStartedAt = System.nanoTime()
        val call = GuardedToolCall(
            toolName = "place_order",
            toolCallId = idGenerator().toString(),
            clientRequestId = "runner-place-order-${intent.intentId}",
            decisionRunContext = context,
            payload = runnerIntentPayload(intent),
        )
        val command = intent.toPlaceOrderCommand(
            call = call,
            timeStopAt = timeStopAt,
            commandId = idGenerator(),
        )
        val placeOrderHash = command.toPreviewOrderNormalizedContent().calculatePreviewHash()
        val hashMismatchWarning = if (preview.previewHash == placeOrderHash) {
            null
        } else {
            "preview_hash_mismatch"
        }
        val result = tradingRuntime.toolCallGuard.runTradeTool(call) {
            placeOrderWithCorrelation(
                context = context,
                call = call,
                command = command,
            )
        }
        val placeOrderExecutionDuration = Duration.ofNanos(System.nanoTime() - executionStartedAt)

        appendDecisionToPlaceOrderPhase(
            input = DecisionToPlaceOrderPhaseInput(
                context = context,
                intent = intent,
                decisionToPlaceOrderDuration = decisionToPlaceOrderDuration,
                previewExecutionDuration = previewResult.duration,
                preview = preview,
                placeOrderExecutionDuration = placeOrderExecutionDuration,
                placeOrderHash = placeOrderHash,
                hashMismatchWarning = hashMismatchWarning,
                accepted = result.getOrNull()?.accepted ?: false,
                tradeResult = result.getOrNull(),
            ),
        ).getOrThrow()
        if (result.getOrNull()?.accepted == false) {
            val noTradeReason = if (preview.accepted) {
                "place_order_rejected"
            } else {
                "preview_order_rejected"
            }
            runAuditRecorder.recordNoTrade(
                context = context,
                reason = noTradeReason,
                cause = null,
            ).getOrThrow()
        }

        return result
    }

    private suspend fun placeOrderWithCorrelation(
        context: DecisionRunContext,
        call: GuardedToolCall,
        command: PlaceOrderCommand,
    ): PaperTradeResult {
        requireLiveClaimForContext(context)
        return withGmoPublicRequestCorrelation(
            GmoPublicRequestCorrelation(
                decisionRunContext = context,
                toolCallId = call.toolCallId,
                clientRole = GmoPublicClientRole.RUNNER,
            ),
        ) {
            tradingRuntime.broker.placeOrder(command).getOrThrow()
        }
    }

    private suspend fun requireLiveClaimForContext(context: DecisionRunContext) {
        requireLiveClaimForInvocation(requireNotNull(context.decisionRunId))
    }

    private suspend fun requireLiveClaimForInvocation(invocationId: String) {
        requireLiveClaim(
            invocationId = invocationId,
            claimantToken = requireNotNull(activeClaimantTokens[invocationId]),
        )
    }

    private fun runnerIntentPayload(intent: TradeIntentRecord): String {
        return buildJsonObject {
            put("intentId", intent.intentId.toString())
            put("source", "one_shot_runner")
        }.toString()
    }

    private suspend fun appendDecisionToPlaceOrderPhase(input: DecisionToPlaceOrderPhaseInput): Result<Unit> {
        return runAuditRecorder.appendRunnerPhase(
            context = input.context,
            phase = "decision_to_place_order",
            duration = input.decisionToPlaceOrderDuration,
            details = buildJsonObject {
                put("intentId", input.intent.intentId.toString())
                put("previewExecutionMillis", input.previewExecutionDuration.toMillis())
                put("previewAccepted", input.preview?.accepted ?: false)
                input.preview?.let { previewResult ->
                    put("previewHash", previewResult.previewHash)
                    previewResult.safetyViolation?.let { violation ->
                        put("previewSafetyViolationRule", violation.rule.name)
                    }
                }
                if (input.placeOrderExecutionDuration != null) {
                    put("placeOrderExecutionMillis", input.placeOrderExecutionDuration.toMillis())
                }
                if (input.placeOrderHash != null) {
                    put("placeOrderHash", input.placeOrderHash)
                }
                if (input.hashMismatchWarning != null) {
                    put("previewHashMismatchWarning", input.hashMismatchWarning)
                }
                if (!input.tradeResult?.divergenceMemos.isNullOrEmpty()) {
                    put(
                        "paperExecutionDivergenceMemos",
                        JsonArray(input.tradeResult.divergenceMemos.map { memo -> memo.toJsonObject() }),
                    )
                }
                put("accepted", input.accepted)
            },
        )
    }

    private suspend fun recordFalsificationNoTrade(
        context: DecisionRunContext,
        falsification: FalsificationRecord?,
        cause: Throwable?,
    ): Result<Unit> {
        val reason = when {
            falsification == null -> "falsifier_missing_verdict"
            falsification.verdict == FalsificationVerdict.REJECTED -> "falsifier_rejected"
            else -> "falsifier_stale_verdict"
        }
        val latency = falsification?.createdAt?.let { createdAt ->
            Duration.between(createdAt, clock.instant()).toMillis()
        }

        runAuditRecorder.appendRunnerPhase(
            context = context,
            phase = "falsifier_verdict_check",
            duration = Duration.ZERO,
            details = buildJsonObject {
                put("reason", reason)
                if (latency != null) {
                    put("verdictLatencyMillis", latency)
                }
            },
        ).getOrThrow()

        return runAuditRecorder.recordNoTrade(
            context = context,
            reason = reason,
            cause = cause,
        )
    }

    private fun logHuman(message: String) {
        logger("[fukurou-runner] $message")
    }
}

internal const val LAUNCH_RESERVATION_MISSING = "launch_reservation_missing"
internal const val LAUNCH_RESERVATION_TRIGGER_MISMATCH = "launch_reservation_trigger_mismatch"
internal const val LAUNCH_RESERVATION_QUERY_FAILED = "launch_reservation_query_failed"
internal const val LAUNCH_RESERVATION_CLAIM_OUTCOME_UNKNOWN = "launch_reservation_claim_outcome_unknown"
internal const val RUN_START_AUDIT_FAILED_AFTER_CLAIM = "run_start_audit_failed_after_claim"

private fun TradeIntentRecord.toPlaceOrderCommand(
    call: GuardedToolCall,
    timeStopAt: Instant?,
    commandId: UUID,
): PlaceOrderCommand {
    return PlaceOrderCommand(
        commandId = commandId,
        intentId = intentId,
        symbol = draft.symbol,
        side = draft.side,
        orderType = draft.orderType,
        sizeBtc = draft.sizeBtc,
        priceJpy = draft.priceJpy,
        tradeGroupId = null,
        protectiveStopPriceJpy = draft.protectiveStopPriceJpy,
        takeProfitPriceJpy = draft.takeProfitPriceJpy,
        estimatedWinProbability = estimatedWinProbability,
        timeStopAt = timeStopAt,
        reasonJa = "Falsifier APPROVED 後の runner deterministic paper entry。",
        auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
        canonicalThesisId = identity?.thesisId,
    )
}

/**
 * OneShot runner の監査 record 保存を担当する helper。
 *
 * @param tradingRuntime trading runtime
 * @param processOutputRedactor process 出力の redactor
 * @param clock audit timestamp 用 clock
 * @param logHuman 人間向け runner log 出力
 */
private class OneShotRunAuditRecorder(
    private val tradingRuntime: TradingRuntime,
    private val processOutputRedactor: SecretRedactor,
    private val clock: Clock,
    private val logHuman: (String) -> Unit,
) {
    suspend fun recordLlmRunStarted(start: LlmRunStart): Result<Unit> {
        return tradingRuntime.llmRunRepository.insertRunning(start)
            .onFailure { throwable ->
                logRunRecordFailure(
                    operation = "start",
                    invocationId = start.invocationId,
                    throwable = throwable,
                )
            }
    }

    suspend fun finalizeLlmRun(
        start: LlmRunStart,
        status: String,
        cause: Throwable?,
        terminalCause: LlmRunTerminalCause? = null,
        llmProvider: String? = null,
    ): Result<Unit> {
        val finish = LlmRunFinish(
            invocationId = start.invocationId,
            mode = start.mode,
            symbol = start.symbol,
            triggerKind = start.triggerKind,
            status = status,
            startedAt = start.startedAt,
            finishedAt = clock.instant(),
            errorMessage = cause?.persistedErrorMessage(llmProvider),
            terminalCause = terminalCause ?: if (status == LLM_RUN_STATUS_FAILED && cause == null) {
                LlmRunTerminalCause.RUNNER_FAILED
            } else {
                terminalCauseForInvocationFailure(cause)
            },
            runtimeConfigVersionId = start.runtimeConfigVersionId,
            runtimeConfigHash = start.runtimeConfigHash,
        )

        return tradingRuntime.llmRunRepository.finish(finish)
            .onFailure { throwable ->
                logRunRecordFailure(
                    operation = "finish",
                    invocationId = start.invocationId,
                    throwable = throwable,
                )
            }
    }

    suspend fun recordNoTrade(
        context: DecisionRunContext,
        reason: String,
        cause: Throwable?,
    ): Result<Unit> {
        return tradingRuntime.callerNoTradeGuard.recordNoTradeExit(
            invocation = CallerInvocation(
                operationName = "one_shot_runner",
                clientRequestId = context.decisionRunId,
                decisionRunContext = context,
            ),
            reason = reason,
            cause = cause,
        )
    }

    suspend fun appendRunnerPhase(
        context: DecisionRunContext,
        phase: String,
        duration: Duration,
        details: JsonObject,
    ): Result<Unit> {
        val payload = buildJsonObject {
            put("phase", phase)
            put("durationMillis", duration.toMillis())
            context.runtimeConfigVersionId?.let { versionId -> put("runtimeConfigVersionId", versionId) }
            context.runtimeConfigHash?.let { hash -> put("runtimeConfigHash", hash) }
            put("details", details)
        }.toString()

        return tradingRuntime.commandEventLog.append(
            CommandEvent(
                decisionRunContext = context,
                toolName = "one_shot_runner",
                toolCallId = null,
                clientRequestId = context.decisionRunId,
                eventType = CommandEventType.RUNNER_PHASE_COMPLETED,
                payload = payload,
                occurredAt = clock.instant(),
            ),
        )
    }

    private fun Throwable.persistedErrorMessage(llmProvider: String?): String {
        if (isCodexProvider(llmProvider)) {
            return CODEX_FAILURE_DETAILS_OMITTED
        }

        val typeName = javaClass.simpleName
        val detail = message.orEmpty()
        val message = if (detail.isBlank()) typeName else "$typeName: $detail"

        return processOutputRedactor.redactAndTruncate(message)
    }

    private fun logRunRecordFailure(
        operation: String,
        invocationId: String,
        throwable: Throwable,
    ) {
        logHuman(
            "llm run $operation record failed invocation=$invocationId error=${throwable.javaClass.simpleName}",
        )
    }
}

/**
 * OneShot runner の LLM phase 実行を共通 auditor へ委譲する helper。
 *
 * @param llmInvoker LLM invocation 境界
 * @param invocationAuditor LLM phase audit helper
 */
private class OneShotPhaseInvoker(
    private val llmInvoker: LlmInvoker,
    private val invocationAuditor: LlmInvocationAuditor,
    private val beforeInvoke: suspend (LlmInvocationRequest) -> Unit,
) {
    suspend fun invokePhase(
        phaseName: String,
        context: DecisionRunContext,
        request: LlmInvocationRequest,
    ): Result<LlmPhaseAuditResult> {
        return try {
            beforeInvoke(request)
            invocationAuditor.invokeAndAudit(
                phaseName = phaseName,
                context = context,
                request = request,
                llmInvoker = llmInvoker,
            )
        } catch (throwable: CancellationException) {
            throw throwable.classifyLlmFailure(request.provider)
        } catch (throwable: Throwable) {
            throw throwable.classifyLlmFailure(request.provider)
        }
    }
}

/**
 * OneShot runner の LLM request / prompt / environment を組み立てる factory。
 *
 * @param tradingConfig trading config
 * @param parentEnvironment 親 process environment
 */
private class OneShotLlmRequestFactory(
    private val tradingConfig: TradingBotConfig,
    private val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot?,
    private val parentEnvironment: Map<String, String>,
) {
    fun llmRequest(input: LlmRequestInput): LlmInvocationRequest {
        val allowedTools = allowedToolsForPhase(input.phase, input.request.cliConfig)
        val mcpServer = mcpServerConfig(
            invocationId = input.invocationId,
            phase = input.phase,
            context = input.decisionRunContext,
            cliConfig = input.request.cliConfig,
            allowedTools = allowedTools,
            provider = input.provider,
        )

        return try {
            LlmInvocationRequest(
                invocationId = input.invocationId,
                provider = input.provider,
                phase = input.phase,
                prompt = input.prompt,
                timeout = tradingConfig.runner.perRunTimeout,
                workingDirectory = input.request.workingDirectory,
                decisionRunContext = input.decisionRunContext,
                mcpServer = mcpServer,
                environment = childEnvironment(input.decisionRunContext, input.intentId),
                allowedTools = allowedTools,
                model = input.assignment.model,
                effort = input.assignment.effort,
                useConfiguredModelFallback = false,
            )
        } catch (throwable: Throwable) {
            runCatching { Files.deleteIfExists(mcpServer.manifestPath) }
                .exceptionOrNull()
                ?.let { cleanupFailure ->
                    LlmArtifactCleanupQuarantine.activate(cleanupFailure)
                    throwable.addSuppressed(cleanupFailure)
                }
            throw throwable
        }
    }

    @Suppress("LongParameterList")
    private fun mcpServerConfig(
        invocationId: String,
        phase: LlmInvocationPhase,
        context: DecisionRunContext,
        cliConfig: OneShotRunnerCliConfig,
        allowedTools: List<String>,
        provider: LlmProvider,
    ): LlmMcpServerConfig {
        LlmArtifactCleanupQuarantine.requireClear().getOrThrow()

        val manifestDirectory = parentEnvironment[FUKUROU_MCP_MANIFEST_DIRECTORY_ENV]
            ?.let { value -> Path.of(value) }
            ?: Path.of(System.getProperty("java.io.tmpdir"), "fukurou-mcp-manifests")
        val capability = McpLaunchManifestWriter(manifestDirectory).write(
            invocationId = invocationId,
            phase = phase,
            context = context,
            allowedTools = allowedTools,
            databaseUrl = requireNotNull(parentEnvironment["DB_URL"]) { "DB_URL is required for MCP manifest." },
            databaseUser = parentEnvironment["FUKUROU_MCP_DB_USER"] ?: DEFAULT_MCP_DATABASE_USER,
            gmoPublicBaseUrl = tradingConfig.gmoPublicClient.baseUrl,
            runtimeEnvironment = RuntimeConfigCatalog.runtimeEnvironment(tradingConfig),
            timeout = tradingConfig.runner.perRunTimeout,
            totalToolCallLimit = tradingConfig.runner.maxToolCallsPerRun,
            actToolCallLimit = tradingConfig.runner.maxActToolCallsPerRun,
        )

        return try {
            LlmMcpServerConfig(
                name = cliConfig.mcpServerName,
                command = cliConfig.mcpServerCommand,
                manifestId = capability.id,
                manifestPath = capability.path,
                terminalEvidenceCaptureEnabled = capability.terminalEvidenceCaptureEnabled,
                autoApprovedTools = autoApprovedTools(provider, allowedTools),
            )
        } catch (throwable: Throwable) {
            runCatching { Files.deleteIfExists(capability.path) }
                .exceptionOrNull()
                ?.let { cleanupFailure ->
                    LlmArtifactCleanupQuarantine.activate(cleanupFailure)
                    throwable.addSuppressed(cleanupFailure)
                }
            throw throwable
        }
    }

    private fun childEnvironment(context: DecisionRunContext, intentId: UUID?): Map<String, String> {
        val baseEnvironment = parentEnvironment
            .filterKeys { key -> key in CHILD_ENV_ALLOWLIST }
            .filterKeys { key -> !isForbiddenSecretEnvKey(key) }
        val intentEnvironment = if (intentId == null) {
            emptyMap()
        } else {
            mapOf(FUKUROU_FALSIFIER_INTENT_ID_ENV to intentId.toString())
        }

        return baseEnvironment + runEnvironment(context) + intentEnvironment
    }

    private fun runEnvironment(context: DecisionRunContext): Map<String, String> {
        val requiredEnvironment = mapOf(
            FUKUROU_INVOCATION_ID_ENV to requireNotNull(context.decisionRunId),
            FUKUROU_LLM_PROVIDER_ENV to requireNotNull(context.llmProvider),
            FUKUROU_PROMPT_HASH_ENV to requireNotNull(context.promptHash),
            FUKUROU_SYSTEM_PROMPT_VERSION_ENV to requireNotNull(context.systemPromptVersion),
            FUKUROU_MARKET_SNAPSHOT_ID_ENV to requireNotNull(context.marketSnapshotId),
        )
        val runtimeConfigEnvironment = buildMap {
            context.runtimeConfigVersionId?.let { versionId ->
                put(FUKUROU_RUNTIME_CONFIG_VERSION_ID_ENV, versionId)
            }
            context.runtimeConfigHash?.let { hash ->
                put(FUKUROU_RUNTIME_CONFIG_HASH_ENV, hash)
            }
        }

        return requiredEnvironment + runtimeConfigEnvironment
    }

    fun decisionRunContext(
        invocationId: String,
        provider: LlmProvider,
        promptHash: String,
        marketSnapshotId: String,
    ): DecisionRunContext {
        return DecisionRunContext(
            decisionRunId = invocationId,
            llmProvider = provider.name.lowercase(),
            promptHash = promptHash,
            systemPromptVersion = SystemPromptV1.VERSION,
            marketSnapshotId = marketSnapshotId,
            runtimeConfigVersionId = runtimeConfigSnapshot?.versionId,
            runtimeConfigHash = runtimeConfigSnapshot?.hash,
        )
    }

    fun readSystemPrompt(repositoryRoot: Path): String {
        return Files.readString(repositoryRoot.resolve(SystemPromptV1.RELATIVE_PATH))
    }

    fun buildProposerPrompt(systemPrompt: String): String {
        return """
            |$systemPrompt
            |
            |Run one Proposer session. Use only fukurou MCP tools for market/account data.
            |Submit exactly one final decision with submit_decision. Do not call place_order.
        """.trimMargin()
    }

    fun buildFalsifierPrompt(systemPrompt: String, intentId: UUID): String {
        return """
            |$systemPrompt
            |
            |Run one Falsifier session for intent_id=$intentId.
            |Use only the intent_id and fukurou MCP read/preview tools, then call submit_falsification.
            |Do not call place_order and do not request proposer narrative from the caller.
        """.trimMargin()
    }

    private fun allowedToolsForPhase(phase: LlmInvocationPhase, cliConfig: OneShotRunnerCliConfig): List<String> {
        return when (phase) {
            LlmInvocationPhase.PRE_FILTER -> emptyList()
            LlmInvocationPhase.PROPOSER -> cliConfig.proposerAllowedTools
            LlmInvocationPhase.FALSIFIER -> cliConfig.falsifierAllowedTools
            LlmInvocationPhase.RISK_REDUCTION_ONLY -> cliConfig.riskReductionAllowedTools
            LlmInvocationPhase.REFLECTION -> emptyList()
            LlmInvocationPhase.EVALUATION_REPORT -> emptyList()
        }
    }

    private fun autoApprovedTools(provider: LlmProvider, allowedTools: List<String>): List<String> {
        if (provider != LlmProvider.CODEX) {
            return emptyList()
        }

        return shortMcpToolNames(allowedTools)
            .filter { toolName -> toolName in CODEX_AUTO_APPROVED_WRITE_TOOL_NAMES }
            .distinct()
    }
}

/**
 * Falsifier へ intent ID だけを渡す環境変数名。
 */
const val FUKUROU_FALSIFIER_INTENT_ID_ENV = "FUKUROU_FALSIFIER_INTENT_ID"

/**
 * runner が既定で使う MCP server 名。
 */
const val DEFAULT_RUNNER_MCP_SERVER_NAME = "fukurou-mcp"

/**
 * runner が既定で使う MCP server 起動 command。
 */
const val DEFAULT_RUNNER_MCP_SERVER_COMMAND = "/usr/local/libexec/fukurou-mcp-launcher"

/**
 * MCP jar path placeholder。
 */
const val MCP_JAR_PATH_PLACEHOLDER = $$"${mcpJarPath}"
private const val DEFAULT_MCP_DATABASE_USER = "fukurou_mcp"
private const val FUKUROU_MCP_MANIFEST_DIRECTORY_ENV = "FUKUROU_MCP_MANIFEST_DIRECTORY"

/**
 * prompt 読み込み前の caller failure audit に使う placeholder。
 */
private const val PROMPT_HASH_UNAVAILABLE = "unavailable"

/**
 * proposer が判断を保存できなかった no-trade 監査 reason。
 */
private const val PROPOSER_MISSING_DECISION_REASON = "proposer_missing_decision"

/**
 * proposer が tool call なしで判断未保存になった no-trade 監査 reason。
 */
private const val PROPOSER_NO_TOOL_CALLS_REASON = "proposer_no_tool_calls"

/**
 * max invocations/hour の集計 window。
 */
val MAX_INVOCATION_COUNT_WINDOW: Duration = Duration.ofHours(1)

/**
 * daily invocation cap の集計 window。
 */
val MAX_DAILY_INVOCATION_COUNT_WINDOW: Duration = Duration.ofDays(1)

/**
 * one-shot 実行本体の入力。
 *
 * @param request runner 実行要求
 * @param invocationId runner 起動 ID
 * @param marketSnapshotId market snapshot ID
 * @param llmRunStart LLM run 開始 record
 * @param failureContextUpdated failure audit context 更新 callback
 */
private data class OneShotRunBodyInput(
    val request: OneShotRunnerRequest,
    val invocationId: String,
    val marketSnapshotId: String,
    val llmRunStart: LlmRunStart,
    val failureContextUpdated: (DecisionRunContext) -> Unit,
)

/** standard material snapshot の capture 済み入力。 */
private data class CapturedMaterialInputs(
    val capturedAt: Instant,
    val account: MaterialAccountSnapshot,
    val ticker: Ticker,
    val candles: List<Candle>,
    val orderbook: Orderbook?,
)

/** claim 失効を snapshot failure から分離する terminal exception。 */
private class LlmExecutionClaimLostException : IllegalStateException()

/**
 * preflight 後の one-shot 実行入力。
 *
 * @param request runner 実行要求
 * @param invocationId runner 起動 ID
 * @param promptContent system prompt 本文
 * @param promptHash system prompt hash
 * @param marketSnapshotId market snapshot ID
 * @param proposerContext proposer decision run context
 * @param failureContextUpdated failure audit context 更新 callback
 */
private data class OneShotAfterPreflightRequest(
    val request: OneShotRunnerRequest,
    val invocationId: String,
    val promptContent: String,
    val promptHash: String,
    val marketSnapshotId: String,
    val proposerContext: DecisionRunContext,
    val failureContextUpdated: (DecisionRunContext) -> Unit,
)

/**
 * decision から place_order までの runner phase 監査入力。
 *
 * @param context decision run context
 * @param intent entry intent
 * @param decisionToPlaceOrderDuration decision 作成から place_order 終了までの経過
 * @param previewExecutionDuration preview_order 実行時間
 * @param preview preview_order 結果
 * @param placeOrderExecutionDuration place_order 実行時間
 * @param placeOrderHash place_order normalized hash
 * @param hashMismatchWarning preview / place_order hash mismatch warning
 * @param accepted place_order が受理されたか
 * @param tradeResult place_order 実行結果
 */
private data class DecisionToPlaceOrderPhaseInput(
    val context: DecisionRunContext,
    val intent: TradeIntentRecord,
    val decisionToPlaceOrderDuration: Duration,
    val previewExecutionDuration: Duration,
    val preview: PreviewOrderResult?,
    val placeOrderExecutionDuration: Duration?,
    val placeOrderHash: String?,
    val hashMismatchWarning: String?,
    val accepted: Boolean,
    val tradeResult: PaperTradeResult? = null,
)

/**
 * LLM invocation request 作成入力。
 *
 * @param invocationId runner 起動 ID
 * @param provider LLM provider
 * @param phase LLM phase
 * @param prompt LLM に渡す prompt
 * @param decisionRunContext decision run context
 * @param request runner 実行要求
 * @param intentId falsifier 対象 intent ID
 */
private data class LlmRequestInput(
    val invocationId: String,
    val provider: LlmProvider,
    val assignment: LlmRoleAssignment,
    val phase: LlmInvocationPhase,
    val prompt: String,
    val decisionRunContext: DecisionRunContext,
    val request: OneShotRunnerRequest,
    val intentId: UUID?,
)

/**
 * proposer phase 後に runner が参照する decision と phase audit result。
 *
 * @param decision 保存済み decision
 * @param failure proposer phase の失敗
 * @param authFailureSuspected CLI 認証失敗らしい出力を検出したか
 * @param cliErrorReported CLI が error 終了を報告する出力を検出したか
 */
private data class ProposerDecisionResult(
    val decision: DecisionSubmissionResult?,
    val failure: Throwable?,
    val authFailureSuspected: Boolean,
    val cliErrorReported: Boolean,
    val observationAppendFailure: Throwable?,
) {
    val attributionIncomplete: Boolean get() = observationAppendFailure != null
}

private suspend fun noDecisionAuditReason(
    input: OneShotAfterPreflightRequest,
    proposerResult: ProposerDecisionResult,
    commandEventLog: CommandEventLog,
): String {
    if (proposerResult.failure != null) {
        return PROPOSER_MISSING_DECISION_REASON
    }
    if (proposerResult.authFailureSuspected) {
        return PROPOSER_MISSING_DECISION_REASON
    }
    if (proposerResult.cliErrorReported) {
        return PROPOSER_MISSING_DECISION_REASON
    }

    val proposerToolNames = shortMcpToolNames(input.request.cliConfig.proposerAllowedTools).toSet()
    if (proposerToolNames.isEmpty()) {
        return PROPOSER_MISSING_DECISION_REASON
    }

    val proposerDecisionRunId = input.proposerContext.decisionRunId
        ?: return PROPOSER_MISSING_DECISION_REASON

    val toolCallCount = commandEventLog
        .countToolCallEvents(
            decisionRunId = proposerDecisionRunId,
            toolNames = proposerToolNames,
        ).getOrElse {
            return PROPOSER_MISSING_DECISION_REASON
        }

    return if (toolCallCount > 0) {
        PROPOSER_MISSING_DECISION_REASON
    } else {
        PROPOSER_NO_TOOL_CALLS_REASON
    }
}

/**
 * falsifier phase 後の verdict 判定結果。
 *
 * @param falsification 保存済み falsification
 * @param failure falsifier phase の失敗
 * @param approved fresh APPROVED verdict なら true
 */
private data class FalsifierPhaseResult(
    val falsification: FalsificationRecord?,
    val failure: Throwable?,
    val approved: Boolean,
    val observationAppendFailure: Throwable?,
) {
    val attributionIncomplete: Boolean get() = observationAppendFailure != null
}

/**
 * runner deterministic entry の preview 結果。
 *
 * @param preview preview_order 結果
 * @param duration preview_order 実行時間
 * @param failure preview_order 失敗
 */
private data class EntryPreviewResult(
    val preview: PreviewOrderResult?,
    val duration: Duration,
    val failure: Throwable?,
)

/**
 * runner 起動可否の判定結果。
 *
 * @param canLaunch 起動可能なら true
 * @param rejectionReason 起動できない場合に no-trade audit へ保存する理由
 */
/**
 * LLM child process へ渡せる非 secret env 名。
 */
val CHILD_ENV_ALLOWLIST = setOf(
    "PATH",
    "HOME",
    "USER",
    "LOGNAME",
    "SHELL",
    "TMPDIR",
    "TEMP",
    "TMP",
    "JAVA_HOME",
    "LANG",
    "LC_ALL",
    "TERM",
    "XDG_CACHE_HOME",
    "CODEX_HOME",
    "CLAUDE_CONFIG_DIR",
)

/**
 * runner が子プロセスへ渡してはいけない secret env 名。
 */
val SECRET_ENV_KEYS = setOf(
    "GMO_API_KEY",
    "GMO_SECRET_KEY",
    "ANTHROPIC_API_KEY",
    "OPENAI_API_KEY",
    "CLAUDE_API_KEY",
    "CODEX_API_KEY",
    "CLOUDFLARED_TUNNEL_TOKEN",
    "CLOUDFLARED_TUNNEL_CLIENT_SECRET",
)

/**
 * MCP server にだけ渡す DB 接続 env 名。
 */
val DB_ENV_KEYS = setOf(
    "DB_URL",
    "DB_USER",
    "DB_PASSWORD",
)

/**
 * MCP server 名の環境変数名。
 */
const val FUKUROU_MCP_SERVER_NAME_ENV = "FUKUROU_MCP_SERVER_NAME"

/**
 * MCP server 起動 command の環境変数名。
 */
const val FUKUROU_MCP_SERVER_COMMAND_ENV = "FUKUROU_MCP_SERVER_COMMAND"

/**
 * MCP server 起動引数の環境変数名。
 */
const val FUKUROU_MCP_SERVER_ARGS_ENV = "FUKUROU_MCP_SERVER_ARGS"

/**
 * MCP jar path の環境変数名。
 */
const val FUKUROU_MCP_JAR_PATH_ENV = "FUKUROU_MCP_JAR_PATH"

/**
 * MCP server instance 内で許可する tool 名 allowlist の環境変数名。
 */
const val FUKUROU_MCP_ALLOWED_TOOLS_ENV = "FUKUROU_MCP_ALLOWED_TOOLS"

/**
 * Proposer tool allowlist の環境変数名。
 */
const val FUKUROU_PROPOSER_ALLOWED_TOOLS_ENV = "FUKUROU_PROPOSER_ALLOWED_TOOLS"

/**
 * Falsifier tool allowlist の環境変数名。
 */
const val FUKUROU_FALSIFIER_ALLOWED_TOOLS_ENV = "FUKUROU_FALSIFIER_ALLOWED_TOOLS"

/**
 * Proposer が既定で呼べる MCP tool allowlist を作る。
 */
fun defaultProposerAllowedTools(serverName: String): List<String> {
    return CANONICAL_PROPOSER_MCP_TOOL_NAMES.map { toolName ->
        mcpToolName(serverName, toolName)
    }
}

/**
 * Falsifier が既定で呼べる MCP tool allowlist を作る。
 */
fun defaultFalsifierAllowedTools(serverName: String): List<String> {
    return CANONICAL_FALSIFIER_MCP_TOOL_NAMES.map { toolName ->
        mcpToolName(serverName, toolName)
    }
}

/** reduction-only phase の canonical MCP allowlist。 */
fun defaultRiskReductionAllowedTools(serverName: String): List<String> {
    return McpToolContractCatalog.riskReductionTools.map { toolName -> mcpToolName(serverName, toolName) }
}

/**
 * Proposer が既定で呼べる MCP tool の短い名前。
 */
val CANONICAL_PROPOSER_MCP_TOOL_NAMES = McpToolContractCatalog.proposerTools

/**
 * Falsifier が既定で呼べる MCP tool の短い名前。
 */
val CANONICAL_FALSIFIER_MCP_TOOL_NAMES = McpToolContractCatalog.falsifierTools

/**
 * recent lessons を読む read-only Knowledge tool 名。
 */

/**
 * similar setups を読む read-only Knowledge tool 名。
 */

/**
 * Proposer の最終判断を保存する write tool 名。
 */
private const val SUBMIT_DECISION_TOOL_NAME = "submit_decision"

/**
 * Falsifier の反証結果を保存する write tool 名。
 */
private const val SUBMIT_FALSIFICATION_TOOL_NAME = "submit_falsification"

/**
 * Codex の tool 単位承認免除を許可する write tool 名。
 */
private val CODEX_AUTO_APPROVED_WRITE_TOOL_NAMES = setOf(
    SUBMIT_DECISION_TOOL_NAME,
    SUBMIT_FALSIFICATION_TOOL_NAME,
)

/**
 * Proposer に許可してはいけない tool の短い名前。
 */
private val PROPOSER_FORBIDDEN_TOOL_NAMES = setOf(
    SUBMIT_FALSIFICATION_TOOL_NAME,
    "place_order",
    "close_position",
    "update_protection",
    "cancel_order",
    "reject_dummy_trade",
)

/**
 * Falsifier に許可してはいけない tool の短い名前。
 */
private val FALSIFIER_FORBIDDEN_TOOL_NAMES = setOf(
    SUBMIT_DECISION_TOOL_NAME,
    "place_order",
    "close_position",
    "update_protection",
    "cancel_order",
    "reject_dummy_trade",
)

private fun mcpToolName(serverName: String, toolName: String): String {
    return "mcp__${serverName}__$toolName"
}

private fun classifyOneShotTerminalCause(
    status: OneShotRunnerStatus,
    tradeResult: PaperTradeResult?,
): LlmRunTerminalCause {
    if (tradeResult?.safetyViolation != null) return LlmRunTerminalCause.SAFETY_DENIED

    return when (status) {
        OneShotRunnerStatus.NO_TRADE_DECISION,
        OneShotRunnerStatus.NO_TRADE_AUDITED,
        OneShotRunnerStatus.LAUNCH_REJECTED,
        OneShotRunnerStatus.PRE_FILTER_SKIPPED,
        -> LlmRunTerminalCause.NO_TRADE
        else -> LlmRunTerminalCause.NORMAL_COMPLETION
    }
}

private fun terminalCauseForNoTrade(cause: Throwable?): LlmRunTerminalCause {
    return cause?.let(::terminalCauseForInvocationFailure) ?: LlmRunTerminalCause.NO_TRADE
}

private fun String.isMcpToolNameFor(serverName: String): Boolean {
    val prefix = "mcp__${serverName}__"
    val serverMatched = startsWith(prefix)
    val shortName = removePrefix(prefix)

    return serverMatched && shortName.isNotBlank()
}

private fun shortMcpToolNames(allowedTools: List<String>): List<String> {
    return allowedTools.map { toolName ->
        toolName.substringAfterLast("__")
    }
}

private fun Map<String, String>.readToolAllowlist(name: String, defaultValue: List<String>): List<String> {
    return readOptionalEnv(name)
        ?.split(",")
        ?.map { value -> value.trim() }
        ?.filter { value -> value.isNotBlank() }
        ?.takeIf { values -> values.isNotEmpty() }
        ?: defaultValue
}

/**
 * LLM child process へ渡さない secret 風 env 名なら true を返す。
 */
fun isForbiddenSecretEnvKey(key: String): Boolean {
    val upperKey = key.uppercase()
    val databaseCredential = key in DB_ENV_KEYS
    val explicitlyForbidden = upperKey in SECRET_ENV_KEYS
    val looksSecret = SECRET_KEY_PATTERNS.any { pattern -> upperKey.contains(pattern) }
    val nonDatabaseCredential = !databaseCredential
    val secretLike = when {
        explicitlyForbidden -> true
        looksSecret -> true
        else -> false
    }
    val forbiddenNonDatabaseSecret = when {
        nonDatabaseCredential -> secretLike
        else -> false
    }

    return forbiddenNonDatabaseSecret
}

/**
 * env 名から secret を推定するための pattern。
 */
private val SECRET_KEY_PATTERNS = listOf(
    "API_KEY",
    "SECRET",
    "TOKEN",
    "PASSWORD",
    "CREDENTIAL",
)

private val MATERIAL_QUOTE_FRESHNESS: Duration = Duration.ofMinutes(1)
private const val MATERIAL_ORDERBOOK_LEVEL_LIMIT = 10
private val RISK_REDUCTION_ONLY_ACTIONS = setOf(
    DecisionAction.EXIT,
    DecisionAction.REDUCE,
    DecisionAction.ADJUST_PROTECTION,
    DecisionAction.NO_TRADE,
)

private const val STANDARD_MATERIAL_SNAPSHOT_PHASE = "standard_material_snapshot"

private val ATTRIBUTION_PHASES = setOf(
    LlmInvocationPhase.PRE_FILTER,
    LlmInvocationPhase.PROPOSER,
    LlmInvocationPhase.FALSIFIER,
)

private val RISK_REDUCTION_ONLY_PROMPT = """
    |The standard decision context is unavailable. Run a risk-reduction-only session.
    |Use only the bounded account, position, and open-order tools.
    |Submit exactly one EXIT, REDUCE, ADJUST_PROTECTION, or NO_TRADE decision.
    |ENTER and ADD_LONG are forbidden.
""".trimMargin()

private fun Throwable.toStandardContextFailureCode(): String {
    if (this is StandardMaterialSnapshotException) return failureCode()

    val simpleName = javaClass.simpleName.uppercase()

    return when {
        simpleName.contains("MARKET") || simpleName.contains("GMO") -> "MARKET_DATA_UNAVAILABLE"
        simpleName.contains("CLI") || simpleName.contains("PROCESS") -> "CLI_VERSION_UNAVAILABLE"
        simpleName.contains("CANONICAL") || simpleName.contains("MANIFEST") -> "CANONICALIZATION_UNAVAILABLE"
        else -> "STANDARD_SNAPSHOT_UNAVAILABLE"
    }
}

private fun StandardMaterialSnapshotException.failureCode(): String = "STANDARD_SNAPSHOT_${stage.name}_FAILED"

private fun Throwable?.safeSnapshotFailureClass(): String {
    val candidate = this?.javaClass?.simpleName.orEmpty()

    return candidate.takeIf { value -> value.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) } ?: "Throwable"
}

internal fun OneShotRunnerResult.withStandardSnapshotFailureTerminalCause(): OneShotRunnerResult {
    if (terminalCause != LlmRunTerminalCause.NO_TRADE) return this

    return copy(terminalCause = LlmRunTerminalCause.RUNNER_FAILED)
}

private fun String.requiredPositiveMarketDecimal(label: String): BigDecimal {
    val value = toBigDecimalOrNull()
    require(value != null && value.signum() > 0) { "Required $label is malformed." }

    return value
}

private fun Orderbook.toMaterialSummary(sourceTimestamp: Instant?, capturedAt: Instant): MaterialOrderbookSummary {
    val bids = bids.take(MATERIAL_ORDERBOOK_LEVEL_LIMIT).map { level ->
        val price = level.price.requiredPositiveMarketDecimal("orderbook bid price")
        val size = level.size.requiredPositiveMarketDecimal("orderbook bid size")
        price to size
    }
    val asks = asks.take(MATERIAL_ORDERBOOK_LEVEL_LIMIT).map { level ->
        val price = level.price.requiredPositiveMarketDecimal("orderbook ask price")
        val size = level.size.requiredPositiveMarketDecimal("orderbook ask size")
        price to size
    }
    require(bids.isNotEmpty() && asks.isNotEmpty()) { "Required orderbook sides are missing." }
    val bestBid = bids.maxOf { it.first }
    val bestAsk = asks.minOf { it.first }
    require(bestAsk >= bestBid) { "Required orderbook spread is malformed." }
    val mid = bestBid.add(bestAsk).divide(BigDecimal(2))
    val spreadBps = bestAsk.subtract(bestBid).multiply(BigDecimal(10_000)).divide(mid, 8, RoundingMode.HALF_UP)
    val bidQuantity = bids.fold(BigDecimal.ZERO) { total, level -> total.add(level.second) }
    val askQuantity = asks.fold(BigDecimal.ZERO) { total, level -> total.add(level.second) }
    val totalQuantity = bidQuantity.add(askQuantity)

    return MaterialOrderbookSummary(
        bestBidJpy = bestBid,
        bestAskJpy = bestAsk,
        midJpy = mid,
        spreadBps = spreadBps,
        topBidQuantityBtc = bidQuantity,
        topAskQuantityBtc = askQuantity,
        topBidNotionalJpy = bids.fold(BigDecimal.ZERO) { total, level -> total.add(level.first.multiply(level.second)) },
        topAskNotionalJpy = asks.fold(BigDecimal.ZERO) { total, level -> total.add(level.first.multiply(level.second)) },
        imbalance = if (totalQuantity.signum() == 0) {
            null
        } else {
            bidQuantity.subtract(askQuantity).divide(totalQuantity, 8, RoundingMode.HALF_UP)
        },
        metadata = MaterialSourceMetadata(
            observedAt = sourceTimestamp ?: capturedAt,
            provenance = "GMO_PUBLIC_ORDERBOOK_TOP10",
            truncated = this.bids.size > MATERIAL_ORDERBOOK_LEVEL_LIMIT || this.asks.size > MATERIAL_ORDERBOOK_LEVEL_LIMIT,
            totalCount = null,
        ),
    )
}
