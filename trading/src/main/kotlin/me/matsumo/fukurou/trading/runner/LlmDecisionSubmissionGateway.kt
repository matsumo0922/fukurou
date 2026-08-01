package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.matsumo.fukurou.trading.audit.TerminalToolEvidence
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundle
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundleStatus
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceIncompleteReason
import me.matsumo.fukurou.trading.audit.ToolEvidenceSourceTimestampStatus
import me.matsumo.fukurou.trading.audit.TrustedTerminalToolEvidenceBundle
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.DecisionSubmissionAuthority
import me.matsumo.fukurou.trading.decision.DecisionSubmissionConflictException
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.decision.DecisionSubmissionUnknownException
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.SubmissionRejectedException
import me.matsumo.fukurou.trading.decision.SubmissionRejectionCode
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import me.matsumo.fukurou.trading.decision.gatewayRejectionCode
import me.matsumo.fukurou.trading.decision.submitTerminalDecision
import me.matsumo.fukurou.trading.decision.submitTerminalFalsification
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmProcessTreeTerminationRegistry
import me.matsumo.fukurou.trading.invoker.LlmSemanticSubmissionState
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** gateway construction のresource操作をfailure injection可能にする境界。 */
internal data class LlmDecisionSubmissionGatewayStartHooks(
    val setSocketPermissions: (Path) -> Unit = { path ->
        Files.setPosixFilePermissions(path, OWNER_ONLY_SOCKET_PERMISSIONS)
    },
    val createExecutor: () -> java.util.concurrent.ExecutorService = {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "llm-submission-gateway").apply { isDaemon = true }
        }
    },
    val execute: (java.util.concurrent.ExecutorService, Runnable) -> Unit = { executor, task -> executor.execute(task) },
    val shutdownExecutor: (java.util.concurrent.ExecutorService) -> Unit = { executor -> executor.shutdownNow() },
    val closeServer: (ServerSocketChannel) -> Unit = { server -> server.close() },
    val deleteSocket: (Path) -> Unit = { path -> Files.deleteIfExists(path) },
)

/** app process が所有する decision protocol の phase-scoped submission 境界。 */
class LlmDecisionSubmissionGateway private constructor(
    val socketPath: Path,
    private val server: ServerSocketChannel,
    private val executor: java.util.concurrent.ExecutorService,
    private val completion: java.util.concurrent.CountDownLatch,
    private val submissionState: AtomicReference<LlmSemanticSubmissionState>,
) : AutoCloseable {

    /** canary 等の process boundary で1 requestの完了を待つ。 */
    fun awaitCompletion() = completion.await()

    /** repository submission の確定済み phase-local state を返す。 */
    fun semanticSubmissionState(): LlmSemanticSubmissionState = submissionState.get()

    override fun close() {
        var cleanupFailure = runCatching { server.close() }.exceptionOrNull()
        executor.shutdownNow()
        runCatching {
            check(executor.awaitTermination(GATEWAY_CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Submission gateway executor did not terminate."
            }
        }.exceptionOrNull()?.let { failure -> cleanupFailure = cleanupFailure.combineCleanupFailure(failure) }
        runCatching { Files.deleteIfExists(socketPath) }
            .exceptionOrNull()
            ?.let { failure -> cleanupFailure = cleanupFailure.combineCleanupFailure(failure) }
        cleanupFailure?.let { failure -> throw failure }
    }

    companion object {
        @Suppress("LongParameterList")
        fun start(
            socketPath: Path,
            repository: DecisionRepository,
            invocationId: String,
            phase: LlmInvocationPhase,
            phaseManifestId: String,
            effectiveInvocationHash: String,
            terminalEvidenceCaptureEnabled: Boolean = false,
        ): LlmDecisionSubmissionGateway = startWithHooks(
            socketPath = socketPath,
            repository = repository,
            invocationId = invocationId,
            phase = phase,
            phaseManifestId = phaseManifestId,
            effectiveInvocationHash = effectiveInvocationHash,
            terminalEvidenceCaptureEnabled = terminalEvidenceCaptureEnabled,
            hooks = LlmDecisionSubmissionGatewayStartHooks(),
        )

        @Suppress("LongParameterList")
        internal fun startWithHooks(
            socketPath: Path,
            repository: DecisionRepository,
            invocationId: String,
            phase: LlmInvocationPhase,
            phaseManifestId: String,
            effectiveInvocationHash: String,
            hooks: LlmDecisionSubmissionGatewayStartHooks,
            terminalEvidenceCaptureEnabled: Boolean = false,
        ): LlmDecisionSubmissionGateway {
            require(socketPath.toString().toByteArray().size <= MAX_UNIX_SOCKET_PATH_BYTES) {
                "Submission socket path is too long."
            }
            hooks.deleteSocket(socketPath)
            var server: ServerSocketChannel? = null
            var executor: java.util.concurrent.ExecutorService? = null
            try {
                val boundServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                server = boundServer
                boundServer.bind(UnixDomainSocketAddress.of(socketPath))
                hooks.setSocketPermissions(socketPath)
                val gatewayExecutor = hooks.createExecutor()
                executor = gatewayExecutor
                val completion = java.util.concurrent.CountDownLatch(1)
                val submissionState = AtomicReference(LlmSemanticSubmissionState.NOT_ATTEMPTED)
                val gateway = LlmDecisionSubmissionGateway(
                    socketPath,
                    boundServer,
                    gatewayExecutor,
                    completion,
                    submissionState,
                )
                val task = submissionTask(
                    server = boundServer,
                    repository = repository,
                    invocationId = invocationId,
                    phase = phase,
                    phaseManifestId = phaseManifestId,
                    effectiveInvocationHash = effectiveInvocationHash,
                    terminalEvidenceCaptureEnabled = terminalEvidenceCaptureEnabled,
                    completion = completion,
                    submissionState = submissionState,
                )
                hooks.execute(gatewayExecutor, task)

                return gateway
            } catch (throwable: Throwable) {
                cleanupFailedStart(socketPath, server, executor, hooks)
                    ?.let(throwable::addSuppressed)
                throw throwable
            }
        }

        @Suppress("LongParameterList")
        private fun submissionTask(
            server: ServerSocketChannel,
            repository: DecisionRepository,
            invocationId: String,
            phase: LlmInvocationPhase,
            phaseManifestId: String,
            effectiveInvocationHash: String,
            terminalEvidenceCaptureEnabled: Boolean,
            completion: java.util.concurrent.CountDownLatch,
            submissionState: AtomicReference<LlmSemanticSubmissionState>,
        ) = Runnable {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val channel = runCatching { server.accept() }.getOrNull() ?: break

                    runCatching {
                        channel.use {
                            processConnection(
                                channel = channel,
                                repository = repository,
                                invocationId = invocationId,
                                phase = phase,
                                phaseManifestId = phaseManifestId,
                                effectiveInvocationHash = effectiveInvocationHash,
                                terminalEvidenceCaptureEnabled = terminalEvidenceCaptureEnabled,
                                completion = completion,
                                submissionState = submissionState,
                            )
                        }
                    }
                }
            } finally {
                completion.countDown()
            }
        }

        @Suppress("LongParameterList")
        private fun processConnection(
            channel: SocketChannel,
            repository: DecisionRepository,
            invocationId: String,
            phase: LlmInvocationPhase,
            phaseManifestId: String,
            effectiveInvocationHash: String,
            terminalEvidenceCaptureEnabled: Boolean,
            completion: java.util.concurrent.CountDownLatch,
            submissionState: AtomicReference<LlmSemanticSubmissionState>,
        ) {
            while (true) {
                val request = try {
                    LlmSubmissionGatewayCodec.readFrameOrNull(channel) ?: return
                } catch (_: SubmissionGatewayFrameContractException) {
                    return
                } catch (_: Throwable) {
                    val response = rejectSubmission(
                        submissionState,
                        SubmissionRejectedException(SubmissionRejectionCode.FRAME_DECODE_FAILED),
                    )
                    val responseWritten = runCatching {
                        LlmSubmissionGatewayCodec.writeFrame(channel, response)
                    }.isSuccess
                    if (!responseWritten) return

                    completion.countDown()
                    continue
                }
                val response = runCatching {
                    runBlocking {
                        handleRequest(
                            request = request,
                            repository = repository,
                            invocationId = invocationId,
                            phase = phase,
                            phaseManifestId = phaseManifestId,
                            effectiveInvocationHash = effectiveInvocationHash,
                            terminalEvidenceCaptureEnabled = terminalEvidenceCaptureEnabled,
                            submissionState = submissionState,
                        )
                    }
                }.onSuccess {
                    submissionState.set(LlmSemanticSubmissionState.COMMITTED)
                }.getOrElse { throwable ->
                    rejectSubmission(submissionState, throwable)
                }
                val responseWritten = runCatching {
                    LlmSubmissionGatewayCodec.writeFrame(channel, response)
                }.isSuccess
                if (!responseWritten) return

                completion.countDown()
            }
        }

        private fun rejectSubmission(
            submissionState: AtomicReference<LlmSemanticSubmissionState>,
            throwable: Throwable,
        ): JsonObject {
            submissionState.compareAndSet(
                LlmSemanticSubmissionState.NOT_ATTEMPTED,
                LlmSemanticSubmissionState.REJECTED,
            )

            return gatewayErrorResponse(throwable)
        }

        private fun cleanupFailedStart(
            socketPath: Path,
            server: ServerSocketChannel?,
            executor: java.util.concurrent.ExecutorService?,
            hooks: LlmDecisionSubmissionGatewayStartHooks,
        ): Throwable? {
            var cleanupFailure: Throwable? = null
            executor?.let { resource ->
                runCatching { hooks.shutdownExecutor(resource) }
                    .exceptionOrNull()
                    ?.let { failure -> cleanupFailure = cleanupFailure.combineCleanupFailure(failure) }
            }
            server?.let { resource ->
                runCatching { hooks.closeServer(resource) }
                    .exceptionOrNull()
                    ?.let { failure -> cleanupFailure = cleanupFailure.combineCleanupFailure(failure) }
            }
            runCatching { hooks.deleteSocket(socketPath) }
                .exceptionOrNull()
                ?.let { failure -> cleanupFailure = cleanupFailure.combineCleanupFailure(failure) }

            return cleanupFailure
        }

        @Suppress("LongParameterList")
        private suspend fun handleRequest(
            request: JsonObject,
            repository: DecisionRepository,
            invocationId: String,
            phase: LlmInvocationPhase,
            phaseManifestId: String,
            effectiveInvocationHash: String,
            terminalEvidenceCaptureEnabled: Boolean,
            submissionState: AtomicReference<LlmSemanticSubmissionState>,
        ): JsonObject {
            val trustedTerminalEvidence = request.trustedTerminalEvidence(
                invocationId = invocationId,
                phaseManifestId = phaseManifestId,
                phase = phase,
                captureEnabled = terminalEvidenceCaptureEnabled,
            )
            request.validateGatewayBinding(
                invocationId = invocationId,
                phase = phase,
                phaseManifestId = phaseManifestId,
                effectiveInvocationHash = effectiveInvocationHash,
            )

            return when (request.gatewayString("operation")) {
                OPERATION_SUBMIT_DECISION -> handleDecisionRequest(
                    request = request,
                    repository = repository,
                    invocationId = invocationId,
                    phase = phase,
                    trustedTerminalEvidence = trustedTerminalEvidence,
                    submissionState = submissionState,
                )
                OPERATION_SUBMIT_FALSIFICATION -> {
                    rejectUnless(
                        phase == LlmInvocationPhase.FALSIFIER,
                        SubmissionRejectionCode.FALSIFICATION_PHASE_NOT_AUTHORIZED,
                    )
                    rejectUnless(
                        terminalSubmissionAdmitted(invocationId),
                        SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE,
                    )
                    val submission = request.decodeFalsificationPayload()
                    LlmSubmissionGatewayCodec.falsificationResult(
                        submitRepositoryRequest(submissionState) {
                            repository.submitTerminalFalsification(
                                submission,
                                trustedTerminalEvidence,
                            )
                        },
                    )
                }
                else -> throw SubmissionRejectedException(SubmissionRejectionCode.UNKNOWN_OPERATION)
            }
        }

        @Suppress("LongParameterList")
        private suspend fun handleDecisionRequest(
            request: JsonObject,
            repository: DecisionRepository,
            invocationId: String,
            phase: LlmInvocationPhase,
            trustedTerminalEvidence: TrustedTerminalToolEvidenceBundle,
            submissionState: AtomicReference<LlmSemanticSubmissionState>,
        ): JsonObject {
            val phaseAuthorized = phase == LlmInvocationPhase.PROPOSER ||
                phase == LlmInvocationPhase.RISK_REDUCTION_ONLY
            rejectUnless(phaseAuthorized, SubmissionRejectionCode.DECISION_PHASE_NOT_AUTHORIZED)
            val submission = request.decodeDecisionPayload()
            rejectUnless(
                submission.invocationId == invocationId,
                SubmissionRejectionCode.DECISION_INVOCATION_MISMATCH,
            )
            if (phase == LlmInvocationPhase.RISK_REDUCTION_ONLY) {
                rejectUnless(
                    submission.action in RISK_REDUCTION_ONLY_ACTIONS,
                    SubmissionRejectionCode.RISK_INCREASING_ACTION_REJECTED,
                )
            }
            if (submission.action !in ADMISSION_EXEMPT_ACTIONS) {
                rejectUnless(
                    terminalSubmissionAdmitted(invocationId),
                    SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE,
                )
            }

            return LlmSubmissionGatewayCodec.decisionResult(
                submitRepositoryRequest(submissionState) {
                    repository.submitTerminalDecision(
                        authority = DecisionSubmissionAuthority(invocationId, phase),
                        submission = submission,
                        evidence = trustedTerminalEvidence,
                    )
                },
            )
        }

        /**
         * terminal submission を確定させてよいかを、admission と process tree proof の両面から判定する。
         *
         * admission blocker は process 全体の健全性を、UNCERTAIN 履歴は同一 run の過去 phase が
         * 終了を証明できなかったことを表す。後者は admission へ伝播するのが one-shot 全体の終了時
         * なので、run の途中では registry を直接見ないと後続 phase を止められない。
         */
        private fun terminalSubmissionAdmitted(invocationId: String): Boolean {
            if (LlmProcessTreeTerminationRegistry.hasCompletedUncertainChild(invocationId)) return false

            return LlmExecutionAdmissionHealth.allowsTerminalSubmission()
        }

        private suspend fun <T> submitRepositoryRequest(
            submissionState: AtomicReference<LlmSemanticSubmissionState>,
            request: suspend () -> Result<T>,
        ): T {
            submissionState.updateAndGet { currentState ->
                if (currentState == LlmSemanticSubmissionState.COMMITTED) currentState else LlmSemanticSubmissionState.IN_FLIGHT
            }

            return request().getOrThrow()
        }
    }
}

private fun gatewayErrorResponse(throwable: Throwable): JsonObject = buildJsonObject {
    val errorCode = when (throwable) {
        is DecisionSubmissionConflictException -> DECISION_SUBMISSION_CONFLICT_CODE
        is DecisionSubmissionUnknownException -> DECISION_SUBMISSION_UNKNOWN_CODE
        else -> SUBMISSION_REJECTED_CODE
    }
    put("accepted", false)
    put("error", errorCode)
    put("reason", throwable.gatewayRejectionCode().wireValue)
}

private fun rejectUnless(condition: Boolean, code: SubmissionRejectionCode) {
    if (!condition) throw SubmissionRejectedException(code)
}

private fun JsonObject.validateGatewayBinding(
    invocationId: String,
    phase: LlmInvocationPhase,
    phaseManifestId: String,
    effectiveInvocationHash: String,
) {
    rejectUnless(gatewayString("invocationId") == invocationId, SubmissionRejectionCode.INVOCATION_BINDING_MISMATCH)
    rejectUnless(gatewayString("phase") == phase.name, SubmissionRejectionCode.PHASE_BINDING_MISMATCH)
    rejectUnless(gatewayString("phaseManifestId") == phaseManifestId, SubmissionRejectionCode.MANIFEST_BINDING_MISMATCH)
    rejectUnless(
        gatewayString("effectiveInvocationHash") == effectiveInvocationHash,
        SubmissionRejectionCode.EFFECTIVE_HASH_MISMATCH,
    )
}

private fun JsonObject.gatewayPayload(): JsonObject {
    return runCatching { getValue("payload").jsonObject }
        .getOrElse { throw SubmissionRejectedException(SubmissionRejectionCode.PAYLOAD_MISSING_OR_INVALID) }
}

private fun JsonObject.decodeDecisionPayload(): DecisionSubmission {
    val payload = gatewayPayload()

    return runCatching { LlmSubmissionGatewayCodec.decodeDecision(payload) }
        .getOrElse { throw SubmissionRejectedException(SubmissionRejectionCode.DECISION_PAYLOAD_DECODE_FAILED) }
}

private fun JsonObject.decodeFalsificationPayload(): FalsificationSubmission {
    val payload = gatewayPayload()

    return runCatching { LlmSubmissionGatewayCodec.decodeFalsification(payload) }
        .getOrElse { throw SubmissionRejectedException(SubmissionRejectionCode.FALSIFICATION_PAYLOAD_DECODE_FAILED) }
}

private fun JsonObject.trustedTerminalEvidence(
    invocationId: String,
    phaseManifestId: String,
    phase: LlmInvocationPhase,
    captureEnabled: Boolean,
): TrustedTerminalToolEvidenceBundle {
    return TrustedTerminalToolEvidenceBundle(
        invocationId = invocationId,
        phaseManifestId = phaseManifestId,
        phase = phase,
        captureEnabled = captureEnabled,
        bundle = decodeTerminalEvidenceBundle(this, captureEnabled),
    )
}

private fun JsonObject.gatewayString(name: String): String {
    return runCatching {
        val value = getValue(name).jsonPrimitive
        require(value.isString)

        value.content
    }.getOrElse { throw SubmissionRejectedException(SubmissionRejectionCode.REQUIRED_STRING_FIELD_MISSING) }
}

private fun Throwable?.combineCleanupFailure(next: Throwable): Throwable {
    val primary = this ?: return next
    primary.addSuppressed(next)

    return primary
}

/** 接続を閉じるべき gateway frame 契約違反。 */
private open class SubmissionGatewayFrameContractException(message: String) : IllegalStateException(message)

/** public codec では IllegalArgumentException へ戻す frame size 契約違反。 */
private class SubmissionGatewayFrameSizeException(message: String) : SubmissionGatewayFrameContractException(message)

/** bounded length-prefixed gateway protocol codec。 */
@Suppress("TooManyFunctions")
object LlmSubmissionGatewayCodec {
    @Suppress("LongParameterList")
    fun request(
        operation: String,
        invocationId: String,
        phase: LlmInvocationPhase,
        phaseManifestId: String,
        effectiveInvocationHash: String,
        payload: JsonObject,
    ): JsonObject = buildJsonObject {
        put("version", LEGACY_GATEWAY_PROTOCOL_VERSION)
        put("operation", operation)
        put("invocationId", invocationId)
        put("phase", phase.name)
        put("phaseManifestId", phaseManifestId)
        put("effectiveInvocationHash", effectiveInvocationHash)
        put("payload", payload)
    }

    @Suppress("LongParameterList")
    fun requestWithTerminalEvidence(
        operation: String,
        invocationId: String,
        phase: LlmInvocationPhase,
        phaseManifestId: String,
        effectiveInvocationHash: String,
        payload: JsonObject,
        terminalEvidence: TerminalToolEvidenceBundle,
    ): JsonObject = buildJsonObject {
        put("version", TERMINAL_EVIDENCE_GATEWAY_PROTOCOL_VERSION)
        put("operation", operation)
        put("invocationId", invocationId)
        put("phase", phase.name)
        put("phaseManifestId", phaseManifestId)
        put("effectiveInvocationHash", effectiveInvocationHash)
        put("payload", payload)
        put("terminalEvidence", LlmTerminalEvidenceCodec.encodeBundle(terminalEvidence))
    }

    fun encodeDecision(submission: DecisionSubmission): JsonObject = buildJsonObject {
        putNullableString("invocationId", submission.invocationId)
        putNullableString("llmProvider", submission.llmProvider)
        putNullableString("promptHash", submission.promptHash)
        putNullableString("systemPromptVersion", submission.systemPromptVersion)
        putNullableString("marketSnapshotId", submission.marketSnapshotId)
        put("action", submission.action.name)
        putNullableString("closeRatio", submission.closeRatio?.toPlainString())
        putStringList("setupTags", submission.setupTags)
        put("estimatedWinProbability", submission.estimatedWinProbability.toPlainString())
        putNullableString("expectedRMultiple", submission.expectedRMultiple?.toPlainString())
        putNullableString("roundTripCostR", submission.roundTripCostR?.toPlainString())
        putStringList("toolEvidenceIds", submission.toolEvidenceIds)
        put("factCheckJson", submission.factCheckJson)
        put("selfReviewJson", submission.selfReviewJson)
        put("reasonJa", submission.reasonJa)
        putStringList("missingDataJa", submission.missingDataJa)
        putStringList("noTradeConditionsJa", submission.noTradeConditionsJa)
        submission.entryIntent?.let { draft -> put("entryIntent", encodeEntryIntent(draft)) }
        submission.tradePlan?.let { draft -> put("tradePlan", encodeTradePlan(draft)) }
    }

    fun decodeDecision(payload: JsonObject): DecisionSubmission = DecisionSubmission(
        invocationId = payload.optionalString("invocationId"),
        llmProvider = payload.optionalString("llmProvider"),
        promptHash = payload.optionalString("promptHash"),
        systemPromptVersion = payload.optionalString("systemPromptVersion"),
        marketSnapshotId = payload.optionalString("marketSnapshotId"),
        action = DecisionAction.valueOf(payload.requiredString("action")),
        closeRatio = payload.optionalString("closeRatio")?.toBigDecimal(),
        setupTags = payload.stringList("setupTags"),
        estimatedWinProbability = payload.requiredString("estimatedWinProbability").toBigDecimal(),
        expectedRMultiple = payload.optionalString("expectedRMultiple")?.toBigDecimal(),
        roundTripCostR = payload.optionalString("roundTripCostR")?.toBigDecimal(),
        toolEvidenceIds = payload.stringList("toolEvidenceIds"),
        factCheckJson = payload.requiredString("factCheckJson"),
        selfReviewJson = payload.requiredString("selfReviewJson"),
        reasonJa = payload.requiredString("reasonJa"),
        missingDataJa = payload.stringList("missingDataJa"),
        noTradeConditionsJa = payload.stringList("noTradeConditionsJa"),
        entryIntent = payload["entryIntent"]?.jsonObject?.let(::decodeEntryIntent),
        tradePlan = payload["tradePlan"]?.jsonObject?.let(::decodeTradePlan),
    )

    fun encodeFalsification(submission: FalsificationSubmission): JsonObject = buildJsonObject {
        putNullableString("intentId", submission.intentId?.toString())
        put("verdict", submission.verdict.name)
        putNullableString("llmProvider", submission.llmProvider)
        put("reasonJa", submission.reasonJa)
    }

    fun decodeFalsification(payload: JsonObject): FalsificationSubmission = FalsificationSubmission(
        intentId = payload.optionalString("intentId")?.let(UUID::fromString),
        verdict = FalsificationVerdict.valueOf(payload.requiredString("verdict")),
        llmProvider = payload.optionalString("llmProvider"),
        reasonJa = payload.requiredString("reasonJa"),
    )

    fun decisionResult(result: DecisionSubmissionResult): JsonObject = buildJsonObject {
        put("accepted", true)
        put("decision_id", result.decision.decisionId.toString())
        put("action", result.decision.submission.action.name)
        result.decision.submission.closeRatio?.let { put("close_ratio", it.toPlainString()) }
        result.tradeIntent?.let { put("intent_id", it.intentId.toString()) }
        result.tradePlan?.let {
            put("trade_plan_id", it.tradePlanId.toString())
            put("revision_count", it.draft.revisionCount)
        }
    }

    fun falsificationResult(result: FalsificationRecord): JsonObject = buildJsonObject {
        put("accepted", true)
        put("falsification_id", result.falsificationId.toString())
        put("intent_id", result.intentId.toString())
        put("verdict", result.verdict.name)
    }

    fun readFrame(channel: SocketChannel): JsonObject {
        return try {
            readFrameOrNull(channel)
                ?: throw SubmissionGatewayFrameContractException("Submission gateway frame ended early.")
        } catch (exception: SubmissionGatewayFrameSizeException) {
            throw IllegalArgumentException(exception.message, exception)
        }
    }

    fun readFrameOrNull(channel: SocketChannel): JsonObject? {
        val sizeBuffer = ByteBuffer.allocate(Int.SIZE_BYTES)
        if (!readFullyAllowingInitialEof(channel, sizeBuffer)) return null
        sizeBuffer.flip()
        val size = sizeBuffer.int
        if (size !in 1..MAX_GATEWAY_FRAME_BYTES) {
            throw SubmissionGatewayFrameSizeException("Submission gateway frame size rejected.")
        }
        val payload = ByteBuffer.allocate(size)
        readFully(channel, payload)

        return JSON.parseToJsonElement(payload.array().decodeToString()).jsonObject
    }

    fun writeFrame(channel: SocketChannel, payload: JsonObject) {
        val bytes = payload.toString().encodeToByteArray()
        require(bytes.size in 1..MAX_GATEWAY_FRAME_BYTES) { "Submission gateway frame size rejected." }
        val frame = ByteBuffer.allocate(Int.SIZE_BYTES + bytes.size).putInt(bytes.size).put(bytes)
        frame.flip()
        while (frame.hasRemaining()) channel.write(frame)
    }

    private fun readFullyAllowingInitialEof(channel: SocketChannel, buffer: ByteBuffer): Boolean {
        val firstRead = channel.read(buffer)
        if (firstRead < 0) return false
        readFully(channel, buffer)

        return true
    }

    private fun readFully(channel: SocketChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw SubmissionGatewayFrameContractException("Submission gateway frame ended early.")
            }
        }
    }

    private fun encodeEntryIntent(draft: EntryIntentDraft): JsonObject = buildJsonObject {
        put("symbol", draft.symbol.name)
        put("side", draft.side.name)
        put("orderType", draft.orderType.name)
        put("sizeBtc", draft.sizeBtc.toPlainString())
        putNullableString("priceJpy", draft.priceJpy?.toPlainString())
        put("protectiveStopPriceJpy", draft.protectiveStopPriceJpy.toPlainString())
        putNullableString("takeProfitPriceJpy", draft.takeProfitPriceJpy?.toPlainString())
    }

    private fun decodeEntryIntent(value: JsonObject): EntryIntentDraft = EntryIntentDraft(
        symbol = TradingSymbol.valueOf(value.requiredString("symbol")),
        side = OrderSide.valueOf(value.requiredString("side")),
        orderType = OrderType.valueOf(value.requiredString("orderType")),
        sizeBtc = value.requiredString("sizeBtc").toBigDecimal(),
        priceJpy = value.optionalString("priceJpy")?.toBigDecimal(),
        protectiveStopPriceJpy = value.requiredString("protectiveStopPriceJpy").toBigDecimal(),
        takeProfitPriceJpy = value.optionalString("takeProfitPriceJpy")?.toBigDecimal(),
    )

    private fun encodeTradePlan(draft: TradePlanDraft): JsonObject = buildJsonObject {
        putNullableString("parentTradePlanId", draft.parentTradePlanId?.toString())
        put("revisionCount", draft.revisionCount)
        put("symbol", draft.symbol.name)
        put("thesisJa", draft.thesisJa)
        putStringList("invalidationConditionsJa", draft.invalidationConditionsJa)
        putNullableString("targetPriceJpy", draft.targetPriceJpy?.toPlainString())
        putNullableString("timeStopAt", draft.timeStopAt?.toString())
        putStringList("setupTags", draft.setupTags)
        putJsonArray("invalidationPredicates") {
            draft.invalidationPredicates.forEach { predicate ->
                add(
                    buildJsonObject {
                        put("type", predicate.type.name)
                        putNullableString("decimalThresholdJpy", predicate.decimalThresholdJpy?.toPlainString())
                        putNullableString("instantThreshold", predicate.instantThreshold?.toString())
                    },
                )
            }
        }
    }

    private fun decodeTradePlan(value: JsonObject): TradePlanDraft = TradePlanDraft(
        parentTradePlanId = value.optionalString("parentTradePlanId")?.let(UUID::fromString),
        revisionCount = value.requiredString("revisionCount").toInt(),
        symbol = TradingSymbol.valueOf(value.requiredString("symbol")),
        thesisJa = value.requiredString("thesisJa"),
        invalidationConditionsJa = value.stringList("invalidationConditionsJa"),
        targetPriceJpy = value.optionalString("targetPriceJpy")?.toBigDecimal(),
        timeStopAt = value.optionalString("timeStopAt")?.let(Instant::parse),
        setupTags = value.stringList("setupTags"),
        invalidationPredicates = value["invalidationPredicates"]?.jsonArray.orEmpty().map { element ->
            val predicate = element.jsonObject
            TradePlanInvalidationPredicate(
                type = TradePlanInvalidationType.valueOf(predicate.requiredString("type")),
                decimalThresholdJpy = predicate.optionalString("decimalThresholdJpy")?.toBigDecimal(),
                instantThreshold = predicate.optionalString("instantThreshold")?.let(Instant::parse),
            )
        },
    )
}

/** terminal evidence bundle の versioned wire codec。 */
object LlmTerminalEvidenceCodec {
    fun encodeBundle(bundle: TerminalToolEvidenceBundle): JsonObject = buildJsonObject {
        put("version", bundle.version)
        put("status", bundle.status.name)
        bundle.incompleteReason?.let { reason -> put("incompleteReason", reason.name) }
        putJsonArray("entries") {
            bundle.entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("version", entry.version)
                        put("ordinal", entry.ordinal)
                        put("toolName", entry.toolName)
                        put("responseJson", entry.responseJson)
                        put("responseHash", entry.responseHash)
                        entry.sourceTimestamp?.let { timestamp -> put("sourceTimestamp", timestamp.toString()) }
                        put("sourceTimestampStatus", entry.sourceTimestampStatus.name)
                        put("isError", entry.isError)
                    },
                )
            }
        }
    }

    fun decodeBundle(value: JsonObject): TerminalToolEvidenceBundle = TerminalToolEvidenceBundle(
        version = value.requiredString("version").toInt(),
        status = TerminalToolEvidenceBundleStatus.valueOf(value.requiredString("status")),
        incompleteReason = value.optionalString("incompleteReason")?.let(TerminalToolEvidenceIncompleteReason::valueOf),
        entries = value.getValue("entries").jsonArray.map { element ->
            val entry = element.jsonObject
            TerminalToolEvidence(
                version = entry.requiredString("version").toInt(),
                ordinal = entry.requiredString("ordinal").toInt(),
                toolName = entry.requiredString("toolName"),
                responseJson = entry.requiredString("responseJson"),
                responseHash = entry.requiredString("responseHash"),
                sourceTimestamp = entry.optionalString("sourceTimestamp")?.let(Instant::parse),
                sourceTimestampStatus = ToolEvidenceSourceTimestampStatus.valueOf(
                    entry.requiredString("sourceTimestampStatus"),
                ),
                isError = entry.requiredString("isError").toBooleanStrict(),
            )
        },
    )
}

private fun JsonObject.requiredString(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.content
private fun JsonObject.stringList(name: String): List<String> = getValue(name).jsonArray.map {
    it.jsonPrimitive.content
}
private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableString(name: String, value: String?) {
    if (value != null) put(name, value)
}
private fun kotlinx.serialization.json.JsonObjectBuilder.putStringList(name: String, values: List<String>) {
    putJsonArray(name) { values.forEach { value -> add(kotlinx.serialization.json.JsonPrimitive(value)) } }
}

/** activationとwire versionの組み合わせを検証し、trusted bundle候補へ正規化する。 */
internal fun decodeTerminalEvidenceBundle(request: JsonObject, captureEnabled: Boolean): TerminalToolEvidenceBundle {
    return runCatching {
        val version = request.requiredString("version").toInt()
        val terminalEvidence = request["terminalEvidence"]
        if (!captureEnabled) {
            require(version == LEGACY_GATEWAY_PROTOCOL_VERSION && terminalEvidence == null)

            return@runCatching TerminalToolEvidenceBundle.disabled()
        }

        require(version == TERMINAL_EVIDENCE_GATEWAY_PROTOCOL_VERSION && terminalEvidence != null)

        LlmTerminalEvidenceCodec.decodeBundle(terminalEvidence.jsonObject)
    }.getOrElse {
        throw SubmissionRejectedException(SubmissionRejectionCode.TERMINAL_EVIDENCE_CONTRACT_VIOLATION)
    }
}

/** serialized gateway payloadが既存単一frame上限内かを返す。 */
fun gatewayFrameFits(payload: JsonObject): Boolean = payload.toString().encodeToByteArray().size in
    1..MAX_GATEWAY_FRAME_BYTES

const val OPERATION_SUBMIT_DECISION = "SUBMIT_DECISION"
const val OPERATION_SUBMIT_FALSIFICATION = "SUBMIT_FALSIFICATION"
const val DECISION_SUBMISSION_CONFLICT_CODE = "DECISION_SUBMISSION_CONFLICT"
const val DECISION_SUBMISSION_UNKNOWN_CODE = "DECISION_SUBMISSION_UNKNOWN"
private const val SUBMISSION_REJECTED_CODE = "SUBMISSION_REJECTED"
private const val LEGACY_GATEWAY_PROTOCOL_VERSION = 1
private const val TERMINAL_EVIDENCE_GATEWAY_PROTOCOL_VERSION = 2
const val MAX_GATEWAY_FRAME_BYTES = 128 * 1024
private const val MAX_UNIX_SOCKET_PATH_BYTES = 103
private const val GATEWAY_CLOSE_WAIT_MILLIS = 500L
private val JSON = Json {
    isLenient = false
    ignoreUnknownKeys = false
}
private val OWNER_ONLY_SOCKET_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)
private val RISK_REDUCTION_ONLY_ACTIONS = setOf(
    DecisionAction.EXIT,
    DecisionAction.REDUCE,
    DecisionAction.ADJUST_PROTECTION,
    DecisionAction.NO_TRADE,
)

/**
 * admission gate を免除する decision action。
 *
 * exposure を増やさないことが実装で保証できるものだけを入れる。EXIT は position の全 close
 * または resting entry の cancel、REDUCE は close ratio 上限つきの部分 close、NO_TRADE は
 * lifecycle を実行しない。ADJUST_PROTECTION は take-profit だけを動かし、既存 TP との単調性も
 * 上限も課されないため、exposure を延ばせる。よってここには含めない。
 */
private val ADMISSION_EXEMPT_ACTIONS = setOf(
    DecisionAction.EXIT,
    DecisionAction.REDUCE,
    DecisionAction.NO_TRADE,
)
