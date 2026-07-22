package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.LlmPhaseInputCaptureException
import me.matsumo.fukurou.trading.audit.LlmPhaseInputManifest
import me.matsumo.fukurou.trading.audit.LlmPhaseManifestRecorder
import me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.evaluation.LlmInvocationTimedOutException
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import me.matsumo.fukurou.trading.invoker.CODEX_INVOCATION_RESULT_UNAVAILABLE
import me.matsumo.fukurou.trading.invoker.DEFAULT_MCP_MANIFEST_DIRECTORY
import me.matsumo.fukurou.trading.invoker.LlmArtifactCleanupQuarantine
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmInvocationResult
import me.matsumo.fukurou.trading.invoker.LlmInvoker
import me.matsumo.fukurou.trading.invoker.LlmProcessStartedMarker
import me.matsumo.fukurou.trading.invoker.LlmProcessTreeTerminationRegistry
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.LlmProviderContractException
import me.matsumo.fukurou.trading.invoker.LlmProviderFailure
import me.matsumo.fukurou.trading.invoker.LlmProviderFailureCategory
import me.matsumo.fukurou.trading.invoker.LlmSemanticSubmissionState
import me.matsumo.fukurou.trading.invoker.McpLaunchManifestWriter
import me.matsumo.fukurou.trading.invoker.ProcessRunResult
import me.matsumo.fukurou.trading.invoker.ProcessRunStatus
import me.matsumo.fukurou.trading.invoker.ProcessTreeTerminationProof
import me.matsumo.fukurou.trading.invoker.classifyLlmFailure
import me.matsumo.fukurou.trading.invoker.renderedEffortOrNull
import me.matsumo.fukurou.trading.invoker.safeExceptionType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/**
 * LLM phase の process 実行結果を共通 event 形式で command_event_log へ保存する auditor。
 *
 * @param commandEventLog audit 保存先
 * @param redactor Claude stdout / stderr と error message を保存前に伏せる redactor
 * @param clock event 発生時刻に使う clock
 * @param toolName audit event の tool 名
 * @param humanLogger 運用ログ出力
 * @param authFailureMessage 認証失敗疑いを検出したときに出す運用ログ。null なら出さない
 * @param unstartedManifestCleanup child process 起動前に残った MCP manifest の cleanup 境界
 */
class LlmInvocationAuditor(
    private val commandEventLog: CommandEventLog,
    private val redactor: SecretRedactor,
    private val clock: Clock,
    private val toolName: String = DEFAULT_LLM_PHASE_AUDIT_TOOL_NAME,
    private val humanLogger: (String) -> Unit = {},
    private val authFailureMessage: String? = null,
    private val phaseManifestRecorder: LlmPhaseManifestRecorder? = null,
    private val decisionRepository: DecisionRepository? = null,
    private val unstartedManifestCleanup: (Path) -> Unit = { path -> Files.deleteIfExists(path) },
) {

    /**
     * LLM phase を起動し、完了または起動失敗を audit へ保存する。
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun invokeAndAudit(
        phaseName: String,
        context: DecisionRunContext,
        request: LlmInvocationRequest,
        llmInvoker: LlmInvoker,
    ): Result<LlmPhaseAuditResult> {
        val startedAt = System.nanoTime()
        var phaseManifest: LlmPhaseInputManifest? = null
        var submissionGateway: LlmDecisionSubmissionGateway? = null
        var result: Result<LlmInvocationResult>? = null
        var invocationAttempted = false
        var invocationThrowable: Throwable? = null
        try {
            phaseManifestRecorder?.let { recorder ->
                phaseManifest = recorder.appendInput(request)
                recorder.bindInput(request, requireNotNull(phaseManifest))
            }
            submissionGateway = createSubmissionGateway(request, phaseManifest)
            invocationAttempted = true
            result = llmInvoker.invoke(request)
        } catch (throwable: Throwable) {
            invocationThrowable = throwable.asPreLaunchCaptureFailure(request, invocationAttempted)
        }

        val completedResult = result
        val invocationResult = completedResult?.getOrNull()
        val processResult = invocationResult?.processResult
        val resultFailure = completedResult?.exceptionOrNull()
        val processStarted = processResult != null ||
            invocationThrowable.hasSuppressedProcessStartedMarker() ||
            resultFailure.hasSuppressedProcessStartedMarker()
        val processCleanupFailure = invocationResult?.cleanupFailure
        val gatewayCleanupFailure = withContext(NonCancellable) {
            runCatching { submissionGateway?.close() }.exceptionOrNull()
        }
        val semanticSubmissionState = submissionGateway?.semanticSubmissionState()
        val manifestCleanupFailure = cleanupUnstartedManifest(request, processStarted)
        val observationFailure = phaseManifest?.let { manifest ->
            withContext(NonCancellable) {
                runCatching {
                    requireNotNull(phaseManifestRecorder).appendObservation(
                        manifest = manifest,
                        observedModels = invocationResult.observedModels(),
                        started = processStarted,
                    )
                }.exceptionOrNull()
            }
        }
        val duration = Duration.ofNanos(System.nanoTime() - startedAt)
        val cleanupFailures = listOf(processCleanupFailure, gatewayCleanupFailure, manifestCleanupFailure)
        val startFailure = (invocationThrowable ?: resultFailure).takeIf { processResult == null }
        val usage = invocationResult?.usage
        val providerFailure = primaryProviderFailure(invocationResult, startFailure, cleanupFailures)
        val auditSignals = LlmPhaseAuditSignals(
            cliErrorReported = invocationResult?.providerFailure != null,
            authEvidenceObserved = invocationResult?.authEvidenceObserved ?: false,
            authFailureSuspected = providerFailure?.category == LlmProviderFailureCategory.AUTHENTICATION,
            cleanupFailed = cleanupFailures.any { failure -> failure != null },
            providerFailure = providerFailure,
        )
        val terminalProjection = LlmPhaseTerminalProjection(
            semanticCommit = semanticSubmissionState.toSemanticCommitTerminal(),
            processExit = processExitTerminal(request, processResult, processStarted),
            cleanup = if (cleanupFailures.any { failure -> failure != null }) {
                CleanupTerminal.FAILED
            } else {
                CleanupTerminal.COMPLETED
            },
        )
        val appendFailure = withContext(NonCancellable) {
            runCatching {
                appendPhase(
                    context = context,
                    phaseName = phaseName,
                    duration = duration,
                    details = phaseDetails(
                        request = request,
                        processResult = processResult,
                        startFailure = startFailure,
                        invocationResult = invocationResult,
                        usage = usage,
                        auditSignals = auditSignals,
                        terminalProjection = terminalProjection,
                    ),
                ).getOrThrow()
            }.exceptionOrNull()
        }
        val terminalFailures = cleanupFailures + listOf(observationFailure, appendFailure)

        invocationThrowable?.let { throwable ->
            throwable.suppressInOrder(terminalFailures)
            throw throwable.classifyLlmFailure(request.provider)
        }
        resultFailure?.let { failure ->
            failure.suppressInOrder(terminalFailures)
            return Result.failure(failure.classifyLlmFailure(request.provider))
        }
        if (auditSignals.authFailureSuspected && authFailureMessage != null) {
            humanLogger(authFailureMessage)
        }

        providerFailure?.takeIf { failure ->
            failure.category in PROVIDER_ADAPTER_FAILURE_CATEGORIES
        }?.let { failure ->
            return Result.failure(LlmProviderContractException(failure))
        }

        val processFailed = processResult?.didFail() ?: false

        if (processResult?.status == ProcessRunStatus.TIMED_OUT) {
            val failure = LlmInvocationTimedOutException(phaseName)
            failure.suppressInOrder(terminalFailures)

            return Result.failure(failure)
        }

        if (processFailed) {
            val failure = IllegalStateException("$phaseName process did not exit cleanly.")
            failure.suppressInOrder(terminalFailures)

            return Result.failure(failure.classifyLlmFailure(request.provider))
        }

        val cleanupFailure = cleanupFailures.filterNotNull().firstOrNull()
        if (cleanupFailure != null) {
            cleanupFailure.suppressInOrder(
                cleanupFailures.dropWhile { failure -> failure !== cleanupFailure } + listOf(
                    observationFailure,
                    appendFailure,
                ),
            )
            return Result.failure(cleanupFailure.classifyLlmFailure(request.provider))
        }

        appendFailure?.let { failure ->
            failure.suppressInOrder(listOf(observationFailure))
            throw failure.classifyLlmFailure(request.provider)
        }

        val completedInvocation = requireNotNull(invocationResult)
        humanLogger("$phaseName completed invocation=${request.invocationId} duration=${duration.toMillis()}ms")

        return Result.success(
            LlmPhaseAuditResult(
                invocationResult = completedInvocation,
                duration = duration,
                authFailureSuspected = auditSignals.authFailureSuspected,
                cliErrorReported = auditSignals.cliErrorReported,
                observationAppendFailure = observationFailure,
            ),
        )
    }

    private suspend fun cleanupUnstartedManifest(request: LlmInvocationRequest, processStarted: Boolean): Throwable? {
        if (processStarted) return null
        val manifestPath = request.mcpServer?.manifestPath ?: return null

        return withContext(NonCancellable) {
            runCatching { unstartedManifestCleanup(manifestPath) }
                .exceptionOrNull()
                ?.also(LlmArtifactCleanupQuarantine::activate)
        }
    }

    private fun createSubmissionGateway(
        request: LlmInvocationRequest,
        phaseManifest: me.matsumo.fukurou.trading.audit.LlmPhaseInputManifest?,
    ): LlmDecisionSubmissionGateway? {
        val server = request.mcpServer ?: return null
        require(request.invocationId == request.decisionRunContext.decisionRunId) {
            "MCP invocation does not match decision run identity."
        }
        val manifest = requireNotNull(phaseManifest) { "MCP launch requires a persisted phase manifest." }
        val repository = requireNotNull(decisionRepository) {
            "MCP launch requires an app-owned decision repository."
        }
        val siblingSocketPath = server.manifestPath.resolveSibling("${server.manifestId}.sock")
        val isProductionManifestDirectory = server.manifestPath.parent.toAbsolutePath().normalize() ==
            java.nio.file.Path.of(DEFAULT_MCP_MANIFEST_DIRECTORY).toAbsolutePath().normalize()
        val socketPath = if (!isProductionManifestDirectory && siblingSocketPath.toString().encodeToByteArray().size > 103) {
            java.nio.file.Path.of("/tmp", "fukurou-${server.manifestId.take(24)}.sock")
        } else {
            siblingSocketPath
        }
        val gateway = LlmDecisionSubmissionGateway.start(
            socketPath = socketPath,
            repository = repository,
            invocationId = request.invocationId,
            phase = request.phase,
            phaseManifestId = manifest.phaseManifestId,
            effectiveInvocationHash = manifest.effectiveInvocationHash,
            terminalEvidenceCaptureEnabled = server.terminalEvidenceCaptureEnabled,
        )
        try {
            McpLaunchManifestWriter.bindSubmissionSocket(server.manifestPath, socketPath)

            return gateway
        } catch (throwable: Throwable) {
            runCatching { gateway.close() }
                .exceptionOrNull()
                ?.let(throwable::addSuppressed)
            throw throwable
        }
    }

    /**
     * LLM 以外の deterministic phase を同じ event 形式で保存する。
     */
    suspend fun appendPhase(
        context: DecisionRunContext,
        phaseName: String,
        duration: Duration,
        details: JsonObject,
    ): Result<Unit> {
        val payload = buildJsonObject {
            put("phase", phaseName)
            put("durationMillis", duration.toMillis())
            put("details", details)
        }.toString()
        redactor.requireNoKnownSecret(
            context.decisionRunId,
            context.llmProvider,
            context.promptHash,
            context.systemPromptVersion,
            context.marketSnapshotId,
            context.runtimeConfigVersionId,
            context.runtimeConfigHash,
            toolName,
            context.decisionRunId,
        )
        ManifestPersistencePolicy.validateCommandEvent(
            context = context,
            toolName = toolName,
            clientRequestId = context.decisionRunId,
            payload = payload,
        )

        return commandEventLog.append(
            CommandEvent(
                decisionRunContext = context,
                toolName = toolName,
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

        return redactor.redactAndTruncate(auditMessage)
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    private fun phaseDetails(
        request: LlmInvocationRequest,
        processResult: ProcessRunResult?,
        startFailure: Throwable?,
        invocationResult: LlmInvocationResult?,
        usage: LlmUsageDetails?,
        auditSignals: LlmPhaseAuditSignals,
        terminalProjection: LlmPhaseTerminalProjection,
    ): JsonObject {
        val serializedUsage = usage?.let(LlmUsageParser::toJsonObject)
        val details = buildJsonObject {
            put("provider", request.provider.name.lowercase())
            putAssignmentAuditDetails(request, invocationResult)
            put("status", processResult?.status?.name ?: "FAILED_TO_START")
            put("exitCode", processResult?.exitCode?.toString() ?: "null")
            put("terminal", terminalProjection.toJson())
            if (auditSignals.cleanupFailed) {
                put("cleanupFailed", "true")
            }
            startFailure?.let { throwable ->
                when (request.provider) {
                    LlmProvider.CLAUDE -> put("error", throwable.redactedQualifiedErrorMessage())
                    LlmProvider.CODEX -> {
                        put("failureCategory", CODEX_INVOCATION_RESULT_UNAVAILABLE)
                        put("failureType", throwable.safeExceptionType())
                    }
                }
            }
            auditSignals.providerFailure?.let { failure ->
                put("failureCategory", failure.category.name)
                failure.providerCode?.let { code -> put("providerCode", code) }
                put("adapterSchemaVersion", failure.adapterSchemaVersion)
            }
            if (auditSignals.providerFailure != null) {
                processResult?.let { completedProcess ->
                    if (isSafeCodexLifecycleFailure(request, auditSignals)) {
                        put("stdout", redactor.redactAndTruncate(completedProcess.stdout))
                        put("stderr", redactor.redactAndTruncate(completedProcess.stderr))
                    }
                }
            } else {
                when (request.provider) {
                    LlmProvider.CLAUDE -> processResult?.let { completedProcess ->
                        put("stdout", redactor.redactAndTruncate(completedProcess.stdout))
                        put("stderr", redactor.redactAndTruncate(completedProcess.stderr))
                    }

                    LlmProvider.CODEX -> Unit
                }
            }
            if (auditSignals.authFailureSuspected) {
                put("authFailureSuspected", "true")
            }
            if (auditSignals.cliErrorReported) {
                put("cliErrorReported", "true")
            }
            serializedUsage?.let { parsedUsage -> put("usage", parsedUsage) }
        }
        ManifestPersistencePolicy.validateUsageDetails(
            observedModels = usage?.modelUsages?.map { modelUsage -> modelUsage.model }.orEmpty(),
            serializedUsage = serializedUsage?.toString(),
            serializedDetails = details.toString(),
        )

        return details
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAssignmentAuditDetails(
        request: LlmInvocationRequest,
        invocationResult: LlmInvocationResult?,
    ) {
        val configuredIdentity = invocationResult?.configuredModelIdentity
            ?: request.model?.let { model ->
                me.matsumo.fukurou.trading.invoker.LlmConfiguredModelIdentity(
                    model,
                    me.matsumo.fukurou.trading.invoker.LlmConfiguredModelSource.REQUEST,
                )
            }
            ?: me.matsumo.fukurou.trading.invoker.LlmConfiguredModelIdentity.CLI_DEFAULT
        val observedModels = invocationResult.observedModels()
        redactor.requireNoKnownSecret(
            configuredIdentity.name,
            configuredIdentity.source.name,
            request.effort.name,
            request.effort.renderedEffortOrNull(),
            *observedModels.toTypedArray(),
        )
        ManifestPersistencePolicy.validateObservedIdentityStrings(
            configuredIdentity.name,
            configuredIdentity.source.name,
            request.effort.name,
            request.effort.renderedEffortOrNull(),
            *observedModels.toTypedArray(),
        )
        configuredIdentity.name?.let { configuredModel -> put("configuredModel", configuredModel) }
        put("configuredModelSource", configuredIdentity.source.name)
        put("configuredEffort", request.effort.name)
        request.effort.renderedEffortOrNull()?.let { renderedEffort -> put("renderedEffort", renderedEffort) }

        observedModels.takeIf(List<String>::isNotEmpty)?.let { models -> put("observedModels", models.joinToString(",")) }
        put("modelObserved", observedModels.isNotEmpty().toString())
    }

    private fun ProcessRunResult.didFail(): Boolean {
        val timedOut = status == ProcessRunStatus.TIMED_OUT
        val nonZeroExit = exitCode?.let { completedExitCode -> completedExitCode != 0 } ?: false

        return timedOut || nonZeroExit
    }
}

private fun LlmSemanticSubmissionState?.toSemanticCommitTerminal(): SemanticCommitTerminal {
    return when (this) {
        null -> SemanticCommitTerminal.NOT_APPLICABLE
        LlmSemanticSubmissionState.NOT_ATTEMPTED,
        LlmSemanticSubmissionState.REJECTED,
        -> SemanticCommitTerminal.NOT_COMMITTED
        LlmSemanticSubmissionState.IN_FLIGHT -> SemanticCommitTerminal.UNKNOWN
        LlmSemanticSubmissionState.COMMITTED -> SemanticCommitTerminal.COMMITTED
    }
}

private fun processExitTerminal(
    request: LlmInvocationRequest,
    processResult: ProcessRunResult?,
    processStarted: Boolean,
): ProcessExitTerminal {
    val proof = processResult?.processTreeTerminationProof
        ?: LlmProcessTreeTerminationRegistry.find(request.invocationId)
    return when {
        proof == ProcessTreeTerminationProof.PROVEN_EXITED -> ProcessExitTerminal.PROVEN_EXITED
        processStarted -> ProcessExitTerminal.UNCONFIRMED
        else -> ProcessExitTerminal.NOT_STARTED
    }
}

private fun LlmPhaseTerminalProjection.toJson(): JsonObject = buildJsonObject {
    put("semanticCommit", semanticCommit.name)
    put("processExit", processExit.name)
    put("cleanup", cleanup.name)
}

private data class LlmPhaseTerminalProjection(
    val semanticCommit: SemanticCommitTerminal,
    val processExit: ProcessExitTerminal,
    val cleanup: CleanupTerminal,
)

private enum class SemanticCommitTerminal {
    COMMITTED,
    NOT_COMMITTED,
    UNKNOWN,
    NOT_APPLICABLE,
}

private enum class ProcessExitTerminal {
    PROVEN_EXITED,
    UNCONFIRMED,
    NOT_STARTED,
}

private enum class CleanupTerminal {
    COMPLETED,
    FAILED,
}

private fun Throwable?.hasSuppressedProcessStartedMarker(): Boolean {
    return this?.suppressed?.any { failure -> failure === LlmProcessStartedMarker } == true
}

private fun primaryProviderFailure(
    invocationResult: LlmInvocationResult?,
    startFailure: Throwable?,
    cleanupFailures: List<Throwable?>,
): LlmProviderFailure? {
    val adapterFailure = invocationResult?.providerFailure
    val startContractFailure = (startFailure as? LlmProviderContractException)?.providerFailure
    val structuredFailure = listOfNotNull(startContractFailure, adapterFailure)
        .firstOrNull { failure -> failure.category in STRUCTURED_PROVIDER_FAILURE_CATEGORIES }
    if (structuredFailure != null) return structuredFailure
    if (adapterFailure?.category == LlmProviderFailureCategory.OUTPUT_CONTRACT) return adapterFailure

    val processResult = invocationResult?.processResult
    if (processResult?.status == ProcessRunStatus.TIMED_OUT) {
        return lifecycleFailure(LlmProviderFailureCategory.PROCESS_TIMEOUT)
    }
    if (processResult?.exitCode?.let { exitCode -> exitCode != 0 } == true) {
        return lifecycleFailure(LlmProviderFailureCategory.PROCESS_EXIT)
    }
    if (cleanupFailures.any { failure -> failure != null }) {
        return lifecycleFailure(LlmProviderFailureCategory.CLEANUP)
    }

    return adapterFailure
        ?: startFailure?.let { lifecycleFailure(LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE) }
}

/**
 * Codex の raw output を監査 payload へ記録してよいかを判定する。
 *
 * `authEvidenceObserved`（parser が独立に追跡した、既知の認証 evidence 文言の観測有無）が
 * true の場合は、以下のどちらの経路であっても無条件で記録しない。この否定条件を
 * 両経路より先に評価する。
 *
 * 1. lifecycle 経路: primary category が process lifecycle 由来
 *    （`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`）かつ、adapter（parser）が Codex の出力を
 *    解釈した failure を一切返していない（`cliErrorReported == false`）。この条件は #296 の
 *    condition 2 を維持する。adapter が output text から `UNKNOWN_PROVIDER_FAILURE` 等を
 *    導出しつつ process が lifecycle 理由で失敗する複合ケースへの防御であり、
 *    evidence 追跡だけでは代替できない
 * 2. output-interpreted 経路（issue #295 で新設）: primary category が
 *    `OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE`。
 *    これらは定義上すべて adapter 由来（`cliErrorReported` は必然的に true）であり、
 *    `cliErrorReported` を追加条件にする意味がないため要求しない
 *
 * `authEvidenceObserved` は既知文言との一致判定に過ぎず、未知の secret の不存在を証明するものではない
 * （design.md の Risks 参照。特に `OUTPUT_CONTRACT`/`UNKNOWN_PROVIDER_FAILURE` はこの限界の影響を
 * 受けやすいカテゴリとしてユーザー確認済みで受容している）。
 */
private fun isSafeCodexLifecycleFailure(request: LlmInvocationRequest, auditSignals: LlmPhaseAuditSignals): Boolean {
    if (request.provider != LlmProvider.CODEX) return false
    if (auditSignals.authEvidenceObserved) return false

    return when (auditSignals.providerFailure?.category) {
        in CODEX_SAFE_LIFECYCLE_FAILURE_CATEGORIES -> !auditSignals.cliErrorReported
        in CODEX_SAFE_OUTPUT_INTERPRETED_FAILURE_CATEGORIES -> true
        else -> false
    }
}

private fun LlmInvocationResult?.observedModels(): List<String> {
    return (
        listOfNotNull(this?.observedModelIdentity?.name) +
            this?.usage?.modelUsages.orEmpty().map { usage -> usage.model }
        ).distinct()
}

private fun lifecycleFailure(category: LlmProviderFailureCategory): LlmProviderFailure {
    return LlmProviderFailure(category, null, me.matsumo.fukurou.trading.invoker.LLM_INVOCATION_CONTRACT_VERSION)
}

private fun Throwable.suppressInOrder(failures: List<Throwable?>) {
    failures.filterNotNull()
        .filter { failure -> failure !== this }
        .filterNot { failure -> suppressed.any { existing -> existing === failure } }
        .forEach(::addSuppressed)
}

private fun Throwable.asPreLaunchCaptureFailure(
    request: LlmInvocationRequest,
    invocationAttempted: Boolean,
): Throwable {
    val standardDecisionPhase = request.phase == me.matsumo.fukurou.trading.invoker.LlmInvocationPhase.PROPOSER ||
        request.phase == me.matsumo.fukurou.trading.invoker.LlmInvocationPhase.FALSIFIER
    if (!standardDecisionPhase) return this
    if (invocationAttempted) return this
    if (this is kotlinx.coroutines.CancellationException) return this

    return LlmPhaseInputCaptureException(this)
}

/**
 * LLM phase audit の結果。
 *
 * @param invocationResult process 実行結果を含む LLM 起動結果
 * @param duration phase 実行時間
 * @param authFailureSuspected CLI 認証失敗らしい出力を検出したか
 * @param cliErrorReported CLI が error 終了を報告する出力を検出したか
 */
data class LlmPhaseAuditResult(
    val invocationResult: LlmInvocationResult,
    val duration: Duration,
    val authFailureSuspected: Boolean,
    val cliErrorReported: Boolean,
    val observationAppendFailure: Throwable? = null,
)

/**
 * LLM phase audit へ載せる CLI 出力由来の検出シグナル。
 *
 * `cliErrorReported` と `authEvidenceObserved` は [isSafeCodexLifecycleFailure] の安全条件として
 * 直接使われるため default を持たない（fail-closed。design.md D5 参照）。`authFailureSuspected`
 * （運用ログ通知のみに使用）と `cleanupFailed`（監査 payload の情報表示にのみ使用）は
 * raw output 記録可否に関与しないため default を維持する。
 *
 * @param cliErrorReported CLI が error 終了を報告する出力を検出したか
 * @param authEvidenceObserved 既知の認証 evidence 文言を出力中に独立に観測したか
 * @param authFailureSuspected CLI 認証失敗らしい出力を検出したか
 * @param cleanupFailed 一時 artifact の cleanup に失敗したか
 */
private data class LlmPhaseAuditSignals(
    val cliErrorReported: Boolean,
    val authEvidenceObserved: Boolean,
    val authFailureSuspected: Boolean = false,
    val cleanupFailed: Boolean = false,
    val providerFailure: LlmProviderFailure? = null,
)

/**
 * LLM phase audit の既定 tool 名。
 */
const val DEFAULT_LLM_PHASE_AUDIT_TOOL_NAME = "one_shot_runner"

/**
 * LLM CLI 認証失敗の可能性を運用ログへ伝える案内。
 */
const val LLM_CLI_AUTH_FAILURE_RUNBOOK_MESSAGE =
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
private val PROVIDER_ADAPTER_FAILURE_CATEGORIES = setOf(
    LlmProviderFailureCategory.AUTHENTICATION,
    LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT,
    LlmProviderFailureCategory.QUOTA_EXHAUSTED,
    LlmProviderFailureCategory.OUTPUT_CONTRACT,
    LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE,
)

private val STRUCTURED_PROVIDER_FAILURE_CATEGORIES = setOf(
    LlmProviderFailureCategory.AUTHENTICATION,
    LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT,
    LlmProviderFailureCategory.QUOTA_EXHAUSTED,
)

/**
 * Codex の raw output 記録を許可する、process の事実だけから決まる failure category。
 *
 * Codex の出力テキストを一切解釈せずに決まるカテゴリに限定する。
 */
private val CODEX_SAFE_LIFECYCLE_FAILURE_CATEGORIES = setOf(
    LlmProviderFailureCategory.PROCESS_EXIT,
    LlmProviderFailureCategory.PROCESS_TIMEOUT,
    LlmProviderFailureCategory.CLEANUP,
)

/**
 * Codex の raw output 記録を許可する、出力テキストの解釈から決まる failure category（issue #295）。
 *
 * これらは Codex の出力テキストを解釈して決まるカテゴリであり、`authEvidenceObserved == false`
 * （既知の認証 evidence 文言が独立に観測されなかった場合）に限って安全とみなす。
 */
private val CODEX_SAFE_OUTPUT_INTERPRETED_FAILURE_CATEGORIES = setOf(
    LlmProviderFailureCategory.OUTPUT_CONTRACT,
    LlmProviderFailureCategory.RATE_OR_SESSION_LIMIT,
    LlmProviderFailureCategory.QUOTA_EXHAUSTED,
    LlmProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE,
)
