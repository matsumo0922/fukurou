package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.FUKUROU_INVOCATION_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_LLM_PROVIDER_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_MARKET_SNAPSHOT_ID_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_PROMPT_HASH_ENV
import me.matsumo.fukurou.trading.audit.FUKUROU_SYSTEM_PROMPT_VERSION_ENV
import me.matsumo.fukurou.trading.broker.PaperTradeAuditContext
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.PreviewOrderResult
import me.matsumo.fukurou.trading.broker.calculatePreviewHash
import me.matsumo.fukurou.trading.broker.toPreviewOrderNormalizedContent
import me.matsumo.fukurou.trading.config.FUKUROU_MCP_ACT_TOOL_CALL_LIMIT_ENV
import me.matsumo.fukurou.trading.config.FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT_ENV
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.SystemPromptV1
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.decision.isFreshApprovedAt
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_CANCELLED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmMcpServerConfig
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
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
 * @param parentEnvironment 親 process environment
 * @param clock audit timestamp 用 clock
 * @param idGenerator invocation / tool call ID generator
 * @param logger 人間向け runner log 出力
 */
class OneShotLlmRunner(
    private val tradingRuntime: TradingRuntime,
    private val tradingConfig: TradingBotConfig,
    private val llmInvoker: LlmInvoker,
    private val parentEnvironment: Map<String, String> = System.getenv(),
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
    private val logger: (String) -> Unit = { message -> println(message) },
) {
    private val processOutputRedactor = SecretRedactor.fromEnvironment(parentEnvironment)

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
        )
        var failureContext = decisionRunContext(
            invocationId = invocationId,
            provider = request.proposerProvider,
            promptHash = PROMPT_HASH_UNAVAILABLE,
            marketSnapshotId = marketSnapshotId,
        )

        return try {
            recordLlmRunStarted(llmRunStart)

            val promptContent = readSystemPrompt(request.repositoryRoot)
            val promptHash = SystemPromptV1.calculateContentHash(promptContent)
            val proposerContext = decisionRunContext(
                invocationId = invocationId,
                provider = request.proposerProvider,
                promptHash = promptHash,
                marketSnapshotId = marketSnapshotId,
            )
            failureContext = proposerContext

            val launchEligibility = launchEligibility(invocationId, proposerContext)
            val result = if (!launchEligibility.canLaunch) {
                recordNoTrade(proposerContext, launchEligibility.rejectionReason, null).getOrThrow()
                logHuman("launch rejected invocation=$invocationId reason=${launchEligibility.rejectionReason}")

                OneShotRunnerResult(
                    invocationId = invocationId,
                    status = OneShotRunnerStatus.LAUNCH_REJECTED,
                    decision = null,
                    intent = null,
                    tradeResult = null,
                )
            } else {
                runOneShotAfterPreflight(
                    request = request,
                    invocationId = invocationId,
                    promptContent = promptContent,
                    promptHash = promptHash,
                    marketSnapshotId = marketSnapshotId,
                    proposerContext = proposerContext,
                    failureContextUpdated = { context -> failureContext = context },
                )
            }

            finalizeLlmRun(
                start = llmRunStart,
                status = result.status.name,
                cause = null,
            )

            Result.success(result)
        } catch (throwable: CancellationException) {
            val auditResult = withContext(NonCancellable) {
                recordNoTrade(failureContext, "caller_cancelled", throwable)
            }
            val finishResult = withContext(NonCancellable) {
                finalizeLlmRun(
                    start = llmRunStart,
                    status = LLM_RUN_STATUS_CANCELLED,
                    cause = throwable,
                )
            }
            throwable.withSuppressedFailure(auditResult)
            throwable.withSuppressedFailure(finishResult)

            throw throwable
        } catch (throwable: Throwable) {
            val auditResult = recordNoTrade(failureContext, "caller_failed", throwable)
            val finishResult = finalizeLlmRun(
                start = llmRunStart,
                status = LLM_RUN_STATUS_FAILED,
                cause = throwable,
            )

            Result.failure(
                throwable
                    .withSuppressedFailure(auditResult)
                    .withSuppressedFailure(finishResult),
            )
        }
    }

    private suspend fun recordLlmRunStarted(start: LlmRunStart) {
        tradingRuntime.llmRunRepository.insertRunning(start)
            .onFailure { throwable ->
                logRunRecordFailure(
                    operation = "start",
                    invocationId = start.invocationId,
                    throwable = throwable,
                )
            }
    }

    private suspend fun finalizeLlmRun(
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

    private suspend fun runOneShotAfterPreflight(
        request: OneShotRunnerRequest,
        invocationId: String,
        promptContent: String,
        promptHash: String,
        marketSnapshotId: String,
        proposerContext: DecisionRunContext,
        failureContextUpdated: (DecisionRunContext) -> Unit,
    ): OneShotRunnerResult {
        val proposerRequest = llmRequest(
            invocationId = invocationId,
            provider = request.proposerProvider,
            phase = LlmInvocationPhase.PROPOSER,
            prompt = buildProposerPrompt(promptContent),
            decisionRunContext = proposerContext,
            request = request,
            intentId = null,
        )
        val proposerResult = invokePhase("proposer", proposerContext, proposerRequest).exceptionOrNull()
        val decision = tradingRuntime.decisionRepository
            .latestDecisionByInvocationId(invocationId)
            .getOrThrow()

        if (decision == null) {
            recordNoTrade(proposerContext, "proposer_missing_decision", proposerResult).getOrThrow()

            return OneShotRunnerResult(
                invocationId = invocationId,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                decision = null,
                intent = null,
                tradeResult = null,
            )
        }

        if (decision.decision.submission.action != DecisionAction.ENTER) {
            logHuman("decision saved invocation=$invocationId action=${decision.decision.submission.action}")

            return OneShotRunnerResult(
                invocationId = invocationId,
                status = OneShotRunnerStatus.NO_TRADE_DECISION,
                decision = decision,
                intent = null,
                tradeResult = null,
            )
        }

        val intent = requireNotNull(decision.tradeIntent) {
            "ENTER decision did not create trade intent."
        }
        val falsifierContext = decisionRunContext(
            invocationId = invocationId,
            provider = request.falsifierProvider,
            promptHash = promptHash,
            marketSnapshotId = marketSnapshotId,
        )
        failureContextUpdated(falsifierContext)
        val falsifierRequest = llmRequest(
            invocationId = invocationId,
            provider = request.falsifierProvider,
            phase = LlmInvocationPhase.FALSIFIER,
            prompt = buildFalsifierPrompt(promptContent, intent.intentId),
            decisionRunContext = falsifierContext,
            request = request,
            intentId = intent.intentId,
        )

        val falsifierFailure = invokePhase("falsifier", falsifierContext, falsifierRequest).exceptionOrNull()
        val falsification = tradingRuntime.decisionRepository
            .latestFalsification(intent.intentId)
            .getOrThrow()
        val approved = falsification.isFreshApprovedAt(clock.instant(), tradingConfig.decisionProtocol.falsificationFreshnessWindow)

        if (!approved) {
            recordFalsificationNoTrade(falsifierContext, falsification, falsifierFailure).getOrThrow()

            return OneShotRunnerResult(
                invocationId = invocationId,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                decision = decision,
                intent = intent,
                tradeResult = null,
            )
        }

        failureContextUpdated(proposerContext)
        val placeResult = placeApprovedEntry(proposerContext, intent)
        val placed = placeResult.getOrNull()

        if (placed == null) {
            recordNoTrade(proposerContext, "place_order_failed", placeResult.exceptionOrNull()).getOrThrow()

            return OneShotRunnerResult(
                invocationId = invocationId,
                status = OneShotRunnerStatus.NO_TRADE_AUDITED,
                decision = decision,
                intent = intent,
                tradeResult = null,
            )
        }

        val finalStatus = if (placed.accepted) {
            OneShotRunnerStatus.PAPER_ENTRY_PLACED
        } else {
            OneShotRunnerStatus.NO_TRADE_AUDITED
        }

        return OneShotRunnerResult(
            invocationId = invocationId,
            status = finalStatus,
            decision = decision,
            intent = intent,
            tradeResult = placed,
        )
    }

    private suspend fun launchEligibility(invocationId: String, context: DecisionRunContext): RunnerLaunchEligibility {
        val hourlySince = clock.instant().minus(MAX_INVOCATION_COUNT_WINDOW)
        val dailySince = clock.instant().minus(MAX_DAILY_INVOCATION_COUNT_WINDOW)
        val currentHourlyCount = tradingRuntime.commandEventLog.countDistinctDecisionRunsSince(hourlySince).getOrThrow()
        val currentDailyCount = tradingRuntime.commandEventLog.countDistinctDecisionRunsSince(dailySince).getOrThrow()
        val hourlyLimitExceeded = currentHourlyCount >= tradingConfig.runner.maxInvocationsPerHour
        val dailyLimitExceeded = currentDailyCount >= tradingConfig.runner.maxInvocationsPerDay
        val canLaunch = !hourlyLimitExceeded && !dailyLimitExceeded
        val rejectionReason = when {
            hourlyLimitExceeded -> "max_invocations_per_hour_exceeded"
            dailyLimitExceeded -> "max_invocations_per_day_exceeded"
            else -> ""
        }

        appendRunnerPhase(
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

    private suspend fun invokePhase(
        phaseName: String,
        context: DecisionRunContext,
        request: LlmInvocationRequest,
    ): Result<Unit> {
        val startedAt = System.nanoTime()
        val result = llmInvoker.invoke(request)
        val duration = Duration.ofNanos(System.nanoTime() - startedAt)
        val invocationResult = result.getOrNull()
        val processResult = invocationResult?.processResult
        val startFailureError = result.exceptionOrNull()
            ?.takeIf { processResult == null }
            ?.redactedQualifiedErrorMessage()
        val usage = processResult?.let { completedProcess ->
            usageForAudit(request.provider, completedProcess.stdout)
        }
        val authFailureSuspected = processResult?.authFailureSuspected() ?: false

        appendRunnerPhase(
            context = context,
            phase = phaseName,
            duration = duration,
            details = buildJsonObject {
                put("provider", request.provider.name.lowercase())
                put("status", processResult?.status?.name ?: "FAILED_TO_START")
                put("exitCode", processResult?.exitCode?.toString() ?: "null")
                startFailureError?.let { error -> put("error", error) }
                processResult?.let { completedProcess ->
                    put("stdout", processOutputRedactor.redactAndTruncate(completedProcess.stdout))
                    put("stderr", processOutputRedactor.redactAndTruncate(completedProcess.stderr))
                }
                if (authFailureSuspected) {
                    put("authFailureSuspected", "true")
                }
                usage?.let { parsedUsage ->
                    put("usage", LlmUsageParser.toJsonObject(parsedUsage))
                }
            },
        ).getOrThrow()
        logHuman("$phaseName completed invocation=${request.invocationId} duration=${duration.toMillis()}ms")
        if (authFailureSuspected) {
            logHuman(LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE)
        }

        val timedOut = processResult?.status == ProcessRunStatus.TIMED_OUT
        val nonZeroExit = processResult?.exitCode?.let { exitCode -> exitCode != 0 } ?: false
        val processFailed = when {
            timedOut -> true
            nonZeroExit -> true
            else -> false
        }

        if (processFailed) {
            return Result.failure(IllegalStateException("$phaseName process did not exit cleanly."))
        }

        return result.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { throwable -> Result.failure(throwable) },
        )
    }

    private suspend fun placeApprovedEntry(
        context: DecisionRunContext,
        intent: TradeIntentRecord,
    ): Result<PaperTradeResult> {
        val arrivedAtPlaceOrder = clock.instant()
        val decisionToPlaceOrderDuration = Duration.between(intent.createdAt, arrivedAtPlaceOrder)
        val previewStartedAt = System.nanoTime()
        val previewCall = GuardedToolCall(
            toolName = "preview_order",
            toolCallId = idGenerator().toString(),
            clientRequestId = "runner-preview-order-${intent.intentId}",
            decisionRunContext = context,
            payload = buildJsonObject {
                put("intentId", intent.intentId.toString())
                put("source", "one_shot_runner")
            }.toString(),
        )
        val previewCommand = intent.toPlaceOrderCommand(previewCall)
        val previewResult = tradingRuntime.toolCallGuard.runReadOnlyTool(previewCall) {
            tradingRuntime.broker.previewOrder(previewCommand).getOrThrow()
        }
        val previewExecutionDuration = Duration.ofNanos(System.nanoTime() - previewStartedAt)
        val preview = previewResult.getOrNull()

        if (preview == null) {
            appendDecisionToPlaceOrderPhase(
                context = context,
                intent = intent,
                decisionToPlaceOrderDuration = decisionToPlaceOrderDuration,
                previewExecutionDuration = previewExecutionDuration,
                preview = null,
                placeOrderExecutionDuration = null,
                placeOrderHash = null,
                hashMismatchWarning = null,
                accepted = false,
            ).getOrThrow()

            return Result.failure(requireNotNull(previewResult.exceptionOrNull()))
        }

        val executionStartedAt = System.nanoTime()
        val call = GuardedToolCall(
            toolName = "place_order",
            toolCallId = idGenerator().toString(),
            clientRequestId = "runner-place-order-${intent.intentId}",
            decisionRunContext = context,
            payload = buildJsonObject {
                put("intentId", intent.intentId.toString())
                put("source", "one_shot_runner")
            }.toString(),
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
            context = context,
            intent = intent,
            decisionToPlaceOrderDuration = decisionToPlaceOrderDuration,
            previewExecutionDuration = previewExecutionDuration,
            preview = preview,
            placeOrderExecutionDuration = placeOrderExecutionDuration,
            placeOrderHash = placeOrderHash,
            hashMismatchWarning = hashMismatchWarning,
            accepted = result.getOrNull()?.accepted ?: false,
        ).getOrThrow()
        if (result.getOrNull()?.accepted == false) {
            val noTradeReason = if (preview.accepted) {
                "place_order_rejected"
            } else {
                "preview_order_rejected"
            }
            recordNoTrade(context, noTradeReason, null).getOrThrow()
        }

        return result
    }

    private suspend fun appendDecisionToPlaceOrderPhase(
        context: DecisionRunContext,
        intent: TradeIntentRecord,
        decisionToPlaceOrderDuration: Duration,
        previewExecutionDuration: Duration,
        preview: PreviewOrderResult?,
        placeOrderExecutionDuration: Duration?,
        placeOrderHash: String?,
        hashMismatchWarning: String?,
        accepted: Boolean,
    ): Result<Unit> {
        return appendRunnerPhase(
            context = context,
            phase = "decision_to_place_order",
            duration = decisionToPlaceOrderDuration,
            details = buildJsonObject {
                put("intentId", intent.intentId.toString())
                put("previewExecutionMillis", previewExecutionDuration.toMillis())
                put("previewAccepted", preview?.accepted ?: false)
                preview?.let { previewResult ->
                    put("previewHash", previewResult.previewHash)
                    previewResult.safetyViolation?.let { violation ->
                        put("previewSafetyViolationRule", violation.rule.name)
                    }
                }
                if (placeOrderExecutionDuration != null) {
                    put("placeOrderExecutionMillis", placeOrderExecutionDuration.toMillis())
                }
                if (placeOrderHash != null) {
                    put("placeOrderHash", placeOrderHash)
                }
                if (hashMismatchWarning != null) {
                    put("previewHashMismatchWarning", hashMismatchWarning)
                }
                put("accepted", accepted)
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

        appendRunnerPhase(
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

        return recordNoTrade(context, reason, cause)
    }

    private suspend fun recordNoTrade(
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

    private suspend fun appendRunnerPhase(
        context: DecisionRunContext,
        phase: String,
        duration: Duration,
        details: JsonObject,
    ): Result<Unit> {
        val payload = buildJsonObject {
            put("phase", phase)
            put("durationMillis", duration.toMillis())
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

    private fun Throwable.redactedQualifiedErrorMessage(): String {
        val detail = message.orEmpty()
        val auditMessage = "${javaClass.name}: $detail"

        return processOutputRedactor.redactAndTruncate(auditMessage)
    }

    private fun llmRequest(
        invocationId: String,
        provider: LlmProvider,
        phase: LlmInvocationPhase,
        prompt: String,
        decisionRunContext: DecisionRunContext,
        request: OneShotRunnerRequest,
        intentId: UUID?,
    ): LlmInvocationRequest {
        val allowedTools = allowedToolsForPhase(phase, request.cliConfig)

        return LlmInvocationRequest(
            invocationId = invocationId,
            provider = provider,
            phase = phase,
            prompt = prompt,
            timeout = tradingConfig.runner.perRunTimeout,
            workingDirectory = request.workingDirectory,
            decisionRunContext = decisionRunContext,
            mcpServer = mcpServerConfig(
                mcpJarPath = request.mcpJarPath,
                context = decisionRunContext,
                cliConfig = request.cliConfig,
                allowedTools = allowedTools,
                provider = provider,
            ),
            environment = childEnvironment(decisionRunContext, intentId),
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

    private fun mcpEnvironment(
        context: DecisionRunContext,
        allowedTools: List<String>,
    ): Map<String, String> {
        val runEnvironment = runEnvironment(context)
        val databaseEnvironment = DB_ENV_KEYS.mapNotNull { key ->
            parentEnvironment[key]?.let { value -> key to value }
        }.toMap()
        val configEnvironment = parentEnvironment
            .filterKeys { key -> key.startsWith("FUKUROU_") }
            .filterKeys { key -> !isForbiddenSecretEnvKey(key) }
        val mcpAllowedTools = shortMcpToolNames(allowedTools).joinToString(",")

        return configEnvironment +
            databaseEnvironment +
            runEnvironment +
            mapOf(
                FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT_ENV to tradingConfig.runner.maxToolCallsPerRun.toString(),
                FUKUROU_MCP_ACT_TOOL_CALL_LIMIT_ENV to tradingConfig.runner.maxActToolCallsPerRun.toString(),
                FUKUROU_MCP_ALLOWED_TOOLS_ENV to mcpAllowedTools,
            )
    }

    private fun childEnvironment(
        context: DecisionRunContext,
        intentId: UUID?,
    ): Map<String, String> {
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
        return mapOf(
            FUKUROU_INVOCATION_ID_ENV to requireNotNull(context.decisionRunId),
            FUKUROU_LLM_PROVIDER_ENV to requireNotNull(context.llmProvider),
            FUKUROU_PROMPT_HASH_ENV to requireNotNull(context.promptHash),
            FUKUROU_SYSTEM_PROMPT_VERSION_ENV to requireNotNull(context.systemPromptVersion),
            FUKUROU_MARKET_SNAPSHOT_ID_ENV to requireNotNull(context.marketSnapshotId),
        )
    }

    private fun decisionRunContext(
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
        )
    }

    private fun readSystemPrompt(repositoryRoot: Path): String {
        return Files.readString(repositoryRoot.resolve(SystemPromptV1.RELATIVE_PATH))
    }

    private fun buildProposerPrompt(systemPrompt: String): String {
        return """
            |$systemPrompt
            |
            |Run one Proposer session. Use only fukurou MCP tools for market/account data.
            |Submit exactly one final decision with submit_decision. Do not call place_order.
        """.trimMargin()
    }

    private fun buildFalsifierPrompt(systemPrompt: String, intentId: UUID): String {
        return """
            |$systemPrompt
            |
            |Run one Falsifier session for intent_id=$intentId.
            |Use only the intent_id and fukurou MCP read/preview tools, then call submit_falsification.
            |Do not call place_order and do not request proposer narrative from the caller.
        """.trimMargin()
    }

    private fun allowedToolsForPhase(
        phase: LlmInvocationPhase,
        cliConfig: OneShotRunnerCliConfig,
    ): List<String> {
        return when (phase) {
            LlmInvocationPhase.PROPOSER -> cliConfig.proposerAllowedTools
            LlmInvocationPhase.FALSIFIER -> cliConfig.falsifierAllowedTools
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

    private fun logHuman(message: String) {
        logger("[fukurou-runner] $message")
    }

    private fun usageForAudit(provider: LlmProvider, stdout: String) = when (provider) {
        LlmProvider.CLAUDE -> LlmUsageParser.parseClaudeStdout(stdout)
        LlmProvider.CODEX -> null
    }

    private fun ProcessRunResult.authFailureSuspected(): Boolean {
        val exitFailed = exitCode?.let { completedExitCode -> completedExitCode != 0 } ?: false
        val combinedOutput = "$stdout\n$stderr"
        val outputContainsAuthFailure = LLM_CLI_AUTH_FAILURE_PATTERNS.any { pattern ->
            combinedOutput.contains(pattern, ignoreCase = true)
        }

        return exitFailed && outputContainsAuthFailure
    }
}

/**
 * LLM CLI 認証失敗の可能性を運用ログへ伝える案内。
 */
private const val LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE =
    "LLM CLI authentication failure suspected. Login runbook: " +
        "docs/llm-obsidian-production-setup.md" +
        "（claude: docker exec -it fukurou-ktor claude → /login、" +
        "codex: docker exec -it fukurou-ktor codex login --device-auth）"

/**
 * LLM CLI 認証失敗を疑うための出力断片。
 *
 * stdout / stderr の raw output に対する推定シグナルであり、provider や CLI version によっては
 * false positive を含みうる。fail-closed の挙動は変えず、運用上の気づきだけを追加する。
 */
private val LLM_CLI_AUTH_FAILURE_PATTERNS = listOf(
    "invalid authentication",
    "401",
    "unauthorized",
    "not logged in",
    "token expired",
    "please run /login",
)

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
const val MCP_JAR_PATH_PLACEHOLDER = "\${mcpJarPath}"

/**
 * prompt 読み込み前の caller failure audit に使う placeholder。
 */
private const val PROMPT_HASH_UNAVAILABLE = "unavailable"

/**
 * max invocations/hour の集計 window。
 */
val MAX_INVOCATION_COUNT_WINDOW: Duration = Duration.ofHours(1)

/**
 * daily invocation cap の集計 window。
 */
val MAX_DAILY_INVOCATION_COUNT_WINDOW: Duration = Duration.ofDays(1)

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

private fun isForbiddenSecretEnvKey(key: String): Boolean {
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
