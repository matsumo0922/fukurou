package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.PreviewOrderResult
import me.matsumo.fukurou.trading.broker.calculatePreviewHash
import me.matsumo.fukurou.trading.broker.toJsonObject
import me.matsumo.fukurou.trading.broker.toPreviewOrderNormalizedContent
import me.matsumo.fukurou.trading.config.FUKUROU_MCP_ACT_TOOL_CALL_LIMIT_ENV
import me.matsumo.fukurou.trading.config.FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT_ENV
import me.matsumo.fukurou.trading.config.RuntimeConfigAuditSnapshot
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.SystemPromptV1
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.decision.isFreshApprovedAt
import me.matsumo.fukurou.trading.decision.requiresEntryIntent
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_CANCELLED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmMcpServerConfig
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.readOptionalEnv
import me.matsumo.fukurou.trading.invoker.splitCommandTemplate
import me.matsumo.fukurou.trading.runtime.TradingRuntime
import me.matsumo.fukurou.trading.tool.CallerInvocation
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import me.matsumo.fukurou.trading.tool.withSuppressedFailure
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
)

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
class OneShotLlmRunner(
    private val tradingRuntime: TradingRuntime,
    private val tradingConfig: TradingBotConfig,
    private val llmInvoker: LlmInvoker,
    private val runtimeConfigSnapshot: RuntimeConfigAuditSnapshot? = null,
    private val parentEnvironment: Map<String, String> = System.getenv(),
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
    private val logger: (String) -> Unit = { message -> println(message) },
) {
    private val processOutputRedactor = SecretRedactor.fromEnvironment(parentEnvironment)
    private val invocationAuditor = LlmInvocationAuditor(
        commandEventLog = tradingRuntime.commandEventLog,
        redactor = processOutputRedactor,
        clock = clock,
        humanLogger = { message -> logHuman(message) },
        authFailureMessage = LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE,
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
    )

    /**
     * one-shot runner を 1 回実行する。
     */
    suspend fun runOneShot(request: OneShotRunnerRequest): Result<OneShotRunnerResult> {
        val invocationId = request.invocationId ?: idGenerator().toString()
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
            provider = request.proposerProvider,
            promptHash = PROMPT_HASH_UNAVAILABLE,
            marketSnapshotId = marketSnapshotId,
        )

        return try {
            val result = runOneShotBody(
                OneShotRunBodyInput(
                    request = request,
                    invocationId = invocationId,
                    marketSnapshotId = marketSnapshotId,
                    llmRunStart = llmRunStart,
                    failureContextUpdated = { context -> failureContext = context },
                ),
            )

            runAuditRecorder.finalizeLlmRun(
                start = llmRunStart,
                status = result.status.name,
                cause = null,
            )

            Result.success(result)
        } catch (throwable: CancellationException) {
            handleCancelledRun(failureContext, llmRunStart, throwable)
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(handleFailedRun(failureContext, llmRunStart, throwable))
        }
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
        )

        return throwable
            .withSuppressedFailure(auditResult)
            .withSuppressedFailure(finishResult)
    }

    private suspend fun runOneShotBody(input: OneShotRunBodyInput): OneShotRunnerResult {
        runAuditRecorder.recordLlmRunStarted(input.llmRunStart)

        val promptContent = requestFactory.readSystemPrompt(input.request.repositoryRoot)
        val promptHash = SystemPromptV1.calculateContentHash(promptContent)
        val proposerContext = requestFactory.decisionRunContext(
            invocationId = input.invocationId,
            provider = input.request.proposerProvider,
            promptHash = promptHash,
            marketSnapshotId = input.marketSnapshotId,
        )
        input.failureContextUpdated(proposerContext)

        val ttlSweepResult = decisionExecutionLifecycle.cancelExpiredRestingEntryOrders(proposerContext)
        if (ttlSweepResult.isFailure) {
            return recordTtlSweepFailure(input.invocationId, proposerContext, ttlSweepResult.exceptionOrNull())
        }

        val launchEligibility = launchEligibility(input.invocationId, proposerContext)
        if (!launchEligibility.canLaunch) {
            runAuditRecorder.recordNoTrade(
                context = proposerContext,
                reason = launchEligibility.rejectionReason,
                cause = null,
            ).getOrThrow()
            logHuman("launch rejected invocation=${input.invocationId} reason=${launchEligibility.rejectionReason}")

            return OneShotRunnerResult(
                invocationId = input.invocationId,
                status = OneShotRunnerStatus.LAUNCH_REJECTED,
                decision = null,
                intent = null,
                tradeResult = null,
            )
        }

        return runOneShotAfterPreflight(
            OneShotAfterPreflightRequest(
                request = input.request,
                invocationId = input.invocationId,
                promptContent = promptContent,
                promptHash = promptHash,
                marketSnapshotId = input.marketSnapshotId,
                proposerContext = proposerContext,
                failureContextUpdated = input.failureContextUpdated,
            ),
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
            )
        }

        if (!decision.decision.submission.action.requiresEntryIntent()) {
            return handleNonEnterDecision(input, decision)
        }

        return runApprovedEntryFlow(input, decision)
    }

    private suspend fun proposerDecision(input: OneShotAfterPreflightRequest): ProposerDecisionResult {
        val request = input.request
        val proposerContext = input.proposerContext
        val proposerRequest = requestFactory.llmRequest(
            LlmRequestInput(
                invocationId = input.invocationId,
                provider = request.proposerProvider,
                phase = LlmInvocationPhase.PROPOSER,
                prompt = requestFactory.buildProposerPrompt(input.promptContent),
                decisionRunContext = proposerContext,
                request = request,
                intentId = null,
            ),
        )
        val proposerAudit = phaseInvoker
            .invokePhase("proposer", proposerContext, proposerRequest)
        val decision = tradingRuntime.decisionRepository
            .latestDecisionByInvocationId(input.invocationId)
            .getOrThrow()

        return ProposerDecisionResult(
            decision = decision,
            failure = proposerAudit.exceptionOrNull(),
            authFailureSuspected = proposerAudit.getOrNull()?.authFailureSuspected ?: false,
            cliErrorReported = proposerAudit.getOrNull()?.cliErrorReported ?: false,
        )
    }

    private suspend fun handleNonEnterDecision(
        input: OneShotAfterPreflightRequest,
        decision: DecisionSubmissionResult,
    ): OneShotRunnerResult {
        logHuman("decision saved invocation=${input.invocationId} action=${decision.decision.submission.action}")

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
            provider = request.falsifierProvider,
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

            return entryFlowResult(invocationId, decision, intent, OneShotRunnerStatus.NO_TRADE_AUDITED)
        }

        input.failureContextUpdated(proposerContext)
        if (decision.decision.submission.action == DecisionAction.ADD_LONG) {
            decisionExecutionLifecycle.ensureAddLongTargetPosition(proposerContext)?.let { lifecycleResult ->
                return entryFlowResult(invocationId, decision, intent, lifecycleResult.status, lifecycleResult.tradeResult)
            }
        }

        val placeResult = placeApprovedEntry(proposerContext, intent)
        val placed = placeResult.getOrNull()

        if (placed == null) {
            runAuditRecorder.recordNoTrade(
                context = proposerContext,
                reason = "place_order_failed",
                cause = placeResult.exceptionOrNull(),
            ).getOrThrow()

            return entryFlowResult(invocationId, decision, intent, OneShotRunnerStatus.NO_TRADE_AUDITED)
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
    ): OneShotRunnerResult {
        return OneShotRunnerResult(
            invocationId = invocationId,
            status = status,
            decision = decision,
            intent = intent,
            tradeResult = tradeResult,
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
                provider = request.falsifierProvider,
                phase = LlmInvocationPhase.FALSIFIER,
                prompt = requestFactory.buildFalsifierPrompt(input.promptContent, intent.intentId),
                decisionRunContext = falsifierContext,
                request = request,
                intentId = intent.intentId,
            ),
        )
        val falsifierFailure = phaseInvoker
            .invokePhase("falsifier", falsifierContext, falsifierRequest)
            .exceptionOrNull()
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
        )
    }

    private suspend fun launchEligibility(invocationId: String, context: DecisionRunContext): RunnerLaunchEligibility {
        val hourlySince = clock.instant().minus(MAX_INVOCATION_COUNT_WINDOW)
        val dailySince = clock.instant().minus(MAX_DAILY_INVOCATION_COUNT_WINDOW)
        val currentHourlyCount = tradingRuntime.commandEventLog.countLlmLaunchesSince(hourlySince, invocationId)
        val currentDailyCount = tradingRuntime.commandEventLog.countLlmLaunchesSince(dailySince, invocationId)
        val hourlyLimitExceeded = currentHourlyCount >= tradingConfig.runner.maxInvocationsPerHour
        val dailyLimitExceeded = currentDailyCount >= tradingConfig.runner.maxInvocationsPerDay
        val canLaunch = !hourlyLimitExceeded && !dailyLimitExceeded
        val rejectionReason = when {
            hourlyLimitExceeded -> "max_invocations_per_hour_exceeded"
            dailyLimitExceeded -> "max_invocations_per_day_exceeded"
            else -> ""
        }

        runAuditRecorder.appendRunnerPhase(
            context = context,
            phase = "preflight",
            duration = Duration.ZERO,
            details = buildJsonObject {
                put("invocationId", invocationId)
                put("currentHourlyInvocationCount", currentHourlyCount)
                put("currentDailyInvocationCount", currentDailyCount)
                put("maxInvocationsPerHour", tradingConfig.runner.maxInvocationsPerHour)
                put("maxInvocationsPerDay", tradingConfig.runner.maxInvocationsPerDay)
                put("canLaunch", canLaunch)
            },
        ).getOrThrow()

        return RunnerLaunchEligibility(
            canLaunch = canLaunch,
            rejectionReason = rejectionReason,
        )
    }

    private suspend fun placeApprovedEntry(
        context: DecisionRunContext,
        intent: TradeIntentRecord,
    ): Result<PaperTradeResult> {
        val arrivedAtPlaceOrder = clock.instant()
        val decisionToPlaceOrderDuration = Duration.between(intent.createdAt, arrivedAtPlaceOrder)
        val previewResult = previewApprovedEntry(context, intent)
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
            decisionToPlaceOrderDuration = decisionToPlaceOrderDuration,
            previewResult = previewResult,
        )
    }

    private suspend fun previewApprovedEntry(
        context: DecisionRunContext,
        intent: TradeIntentRecord,
    ): EntryPreviewResult {
        val previewStartedAt = System.nanoTime()
        val previewCall = GuardedToolCall(
            toolName = "preview_order",
            toolCallId = idGenerator().toString(),
            clientRequestId = "runner-preview-order-${intent.intentId}",
            decisionRunContext = context,
            payload = runnerIntentPayload(intent),
        )
        val previewCommand = intent.toPlaceOrderCommand(previewCall)
        val previewResult = tradingRuntime.toolCallGuard.runReadOnlyTool(previewCall) {
            tradingRuntime.broker.previewOrder(previewCommand).getOrThrow()
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
        val command = intent.toPlaceOrderCommand(call)
        val placeOrderHash = command.toPreviewOrderNormalizedContent().calculatePreviewHash()
        val hashMismatchWarning = if (preview.previewHash == placeOrderHash) {
            null
        } else {
            "preview_hash_mismatch"
        }
        val result = tradingRuntime.toolCallGuard.runTradeTool(call) {
            tradingRuntime.broker.placeOrder(command).getOrThrow()
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

    private fun TradeIntentRecord.toPlaceOrderCommand(call: GuardedToolCall): PlaceOrderCommand {
        return PlaceOrderCommand(
            commandId = idGenerator(),
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
            reasonJa = "Falsifier APPROVED 後の runner deterministic paper entry。",
            auditContext = PaperTradeAuditContext.fromGuardedToolCall(call),
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

private suspend fun CommandEventLog.countLlmLaunchesSince(since: Instant, excludedInvocationId: String): Int {
    return countDistinctLlmLaunchesSince(since, excludedInvocationId).getOrThrow()
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
    suspend fun recordLlmRunStarted(start: LlmRunStart) {
        tradingRuntime.llmRunRepository.insertRunning(start)
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
    ): Result<Unit> {
        val finish = LlmRunFinish(
            invocationId = start.invocationId,
            mode = start.mode,
            symbol = start.symbol,
            triggerKind = start.triggerKind,
            status = status,
            startedAt = start.startedAt,
            finishedAt = clock.instant(),
            errorMessage = cause?.redactedErrorMessage(),
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

    private fun Throwable.redactedErrorMessage(): String {
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
) {
    suspend fun invokePhase(
        phaseName: String,
        context: DecisionRunContext,
        request: LlmInvocationRequest,
    ): Result<LlmPhaseAuditResult> {
        return invocationAuditor.invokeAndAudit(
            phaseName = phaseName,
            context = context,
            request = request,
            llmInvoker = llmInvoker,
        )
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

        return LlmInvocationRequest(
            invocationId = input.invocationId,
            provider = input.provider,
            phase = input.phase,
            prompt = input.prompt,
            timeout = tradingConfig.runner.perRunTimeout,
            workingDirectory = input.request.workingDirectory,
            decisionRunContext = input.decisionRunContext,
            mcpServer = mcpServerConfig(
                mcpJarPath = input.request.mcpJarPath,
                context = input.decisionRunContext,
                cliConfig = input.request.cliConfig,
                allowedTools = allowedTools,
                provider = input.provider,
            ),
            environment = childEnvironment(input.decisionRunContext, input.intentId),
            allowedTools = allowedTools,
        )
    }

    private fun mcpServerConfig(
        mcpJarPath: String,
        context: DecisionRunContext,
        cliConfig: OneShotRunnerCliConfig,
        allowedTools: List<String>,
        provider: LlmProvider,
    ): LlmMcpServerConfig {
        return LlmMcpServerConfig(
            name = cliConfig.mcpServerName,
            command = cliConfig.mcpServerCommand,
            args = mcpServerArgs(mcpJarPath, cliConfig),
            environment = mcpEnvironment(
                context = context,
                allowedTools = allowedTools,
            ),
            autoApprovedTools = autoApprovedTools(provider, allowedTools),
        )
    }

    private fun mcpServerArgs(mcpJarPath: String, cliConfig: OneShotRunnerCliConfig): List<String> {
        val configuredArgs = cliConfig.mcpServerArgs
            ?: return listOf("-jar", mcpJarPath)

        return configuredArgs.map { argument ->
            argument.replace(MCP_JAR_PATH_PLACEHOLDER, mcpJarPath)
        }
    }

    private fun mcpEnvironment(context: DecisionRunContext, allowedTools: List<String>): Map<String, String> {
        val runEnvironment = runEnvironment(context)
        val databaseEnvironment = DB_ENV_KEYS.mapNotNull { key ->
            parentEnvironment[key]?.let { value -> key to value }
        }.toMap()
        val configEnvironment = parentEnvironment
            .filterKeys { key -> key.startsWith("FUKUROU_") }
            .filterKeys { key -> !isForbiddenSecretEnvKey(key) }
            .filterKeys { key -> key !in RuntimeConfigCatalog.runtimeLegacyEnvNames() }
        val runtimeEnvironment = RuntimeConfigCatalog.runtimeEnvironment(tradingConfig)
        val mcpAllowedTools = shortMcpToolNames(allowedTools).joinToString(",")

        return configEnvironment +
            runtimeEnvironment +
            databaseEnvironment +
            runEnvironment +
            mapOf(
                FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT_ENV to tradingConfig.runner.maxToolCallsPerRun.toString(),
                FUKUROU_MCP_ACT_TOOL_CALL_LIMIT_ENV to tradingConfig.runner.maxActToolCallsPerRun.toString(),
                FUKUROU_MCP_ALLOWED_TOOLS_ENV to mcpAllowedTools,
            )
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
            LlmInvocationPhase.REFLECTION -> emptyList()
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
const val DEFAULT_RUNNER_MCP_SERVER_COMMAND = "java"

/**
 * MCP jar path placeholder。
 */
const val MCP_JAR_PATH_PLACEHOLDER = $$"${mcpJarPath}"

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
)

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
)

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
private data class RunnerLaunchEligibility(
    val canLaunch: Boolean,
    val rejectionReason: String,
)

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
    return DEFAULT_PROPOSER_TOOL_NAMES.map { toolName ->
        mcpToolName(serverName, toolName)
    }
}

/**
 * Falsifier が既定で呼べる MCP tool allowlist を作る。
 */
fun defaultFalsifierAllowedTools(serverName: String): List<String> {
    return DEFAULT_FALSIFIER_TOOL_NAMES.map { toolName ->
        mcpToolName(serverName, toolName)
    }
}

/**
 * Proposer が既定で呼べる MCP tool の短い名前。
 */
private val DEFAULT_PROPOSER_TOOL_NAMES = listOf(
    "get_trade_intent",
    "get_ticker",
    "get_candles",
    "get_orderbook",
    "get_trades",
    "get_symbol_rules",
    "calc_indicator",
    "get_balance",
    "get_positions",
    "get_open_orders",
    "get_account_status",
    KNOWLEDGE_GET_RECENT_LESSONS_TOOL_NAME,
    KNOWLEDGE_SEARCH_SIMILAR_SETUPS_TOOL_NAME,
    SUBMIT_DECISION_TOOL_NAME,
)

/**
 * Falsifier が既定で呼べる MCP tool の短い名前。
 */
private val DEFAULT_FALSIFIER_TOOL_NAMES = listOf(
    "get_trade_intent",
    "preview_order",
    "get_ticker",
    "get_candles",
    "get_orderbook",
    "get_trades",
    "get_symbol_rules",
    "calc_indicator",
    "get_balance",
    "get_positions",
    "get_open_orders",
    "get_account_status",
    KNOWLEDGE_GET_RECENT_LESSONS_TOOL_NAME,
    KNOWLEDGE_SEARCH_SIMILAR_SETUPS_TOOL_NAME,
    SUBMIT_FALSIFICATION_TOOL_NAME,
)

/**
 * recent lessons を読む read-only Knowledge tool 名。
 */
private const val KNOWLEDGE_GET_RECENT_LESSONS_TOOL_NAME = "knowledge_get_recent_lessons"

/**
 * similar setups を読む read-only Knowledge tool 名。
 */
private const val KNOWLEDGE_SEARCH_SIMILAR_SETUPS_TOOL_NAME = "knowledge_search_similar_setups"

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
