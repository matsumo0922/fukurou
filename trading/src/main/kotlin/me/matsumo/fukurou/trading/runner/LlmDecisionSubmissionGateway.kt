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
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import me.matsumo.fukurou.trading.decision.submitTerminalDecision
import me.matsumo.fukurou.trading.decision.submitTerminalFalsification
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
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
) : AutoCloseable {

    /** canary 等の process boundary で1 requestの完了を待つ。 */
    fun awaitCompletion() = completion.await()

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
                val gateway = LlmDecisionSubmissionGateway(socketPath, boundServer, gatewayExecutor, completion)
                val task = Runnable {
                    try {
                        runCatching {
                            boundServer.accept().use { channel ->
                                val response = runCatching {
                                    val request = LlmSubmissionGatewayCodec.readFrame(channel)
                                    runBlocking {
                                        handleRequest(
                                            request = request,
                                            repository = repository,
                                            invocationId = invocationId,
                                            phase = phase,
                                            phaseManifestId = phaseManifestId,
                                            effectiveInvocationHash = effectiveInvocationHash,
                                            terminalEvidenceCaptureEnabled = terminalEvidenceCaptureEnabled,
                                        )
                                    }
                                }.getOrElse {
                                    buildJsonObject {
                                        put("accepted", false)
                                        put("error", "SUBMISSION_REJECTED")
                                    }
                                }
                                LlmSubmissionGatewayCodec.writeFrame(channel, response)
                            }
                        }
                    } finally {
                        completion.countDown()
                    }
                }
                hooks.execute(gatewayExecutor, task)

                return gateway
            } catch (throwable: Throwable) {
                cleanupFailedStart(socketPath, server, executor, hooks)
                    ?.let(throwable::addSuppressed)
                throw throwable
            }
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
        ): JsonObject {
            val terminalEvidence = decodeTerminalEvidenceBundle(request, terminalEvidenceCaptureEnabled)
            require(request.requiredString("invocationId") == invocationId) { "Gateway invocation binding mismatch." }
            require(request.requiredString("phase") == phase.name) { "Gateway phase binding mismatch." }
            require(request.requiredString("phaseManifestId") == phaseManifestId) { "Gateway manifest binding mismatch." }
            require(request.requiredString("effectiveInvocationHash") == effectiveInvocationHash) {
                "Gateway effective invocation binding mismatch."
            }
            val payload = request.getValue("payload").jsonObject
            val trustedTerminalEvidence = TrustedTerminalToolEvidenceBundle(
                invocationId = invocationId,
                phaseManifestId = phaseManifestId,
                phase = phase,
                captureEnabled = terminalEvidenceCaptureEnabled,
                bundle = terminalEvidence,
            )

            return when (request.requiredString("operation")) {
                OPERATION_SUBMIT_DECISION -> {
                    require(phase == LlmInvocationPhase.PROPOSER || phase == LlmInvocationPhase.RISK_REDUCTION_ONLY) {
                        "submit_decision is not authorized for this phase."
                    }
                    val submission = LlmSubmissionGatewayCodec.decodeDecision(payload)
                    require(submission.invocationId == invocationId) { "Decision invocation binding mismatch." }
                    if (phase == LlmInvocationPhase.RISK_REDUCTION_ONLY) {
                        require(submission.action in RISK_REDUCTION_ONLY_ACTIONS) {
                            "RISK_REDUCTION_ONLY rejects risk-increasing decisions."
                        }
                    }
                    LlmSubmissionGatewayCodec.decisionResult(
                        repository.submitTerminalDecision(submission, trustedTerminalEvidence).getOrThrow(),
                    )
                }
                OPERATION_SUBMIT_FALSIFICATION -> {
                    require(phase == LlmInvocationPhase.FALSIFIER) {
                        "submit_falsification is not authorized for this phase."
                    }
                    LlmSubmissionGatewayCodec.falsificationResult(
                        repository.submitTerminalFalsification(
                            LlmSubmissionGatewayCodec.decodeFalsification(payload),
                            trustedTerminalEvidence,
                        ).getOrThrow(),
                    )
                }
                else -> error("Unknown submission gateway operation.")
            }
        }
    }
}

private fun Throwable?.combineCleanupFailure(next: Throwable): Throwable {
    val primary = this ?: return next
    primary.addSuppressed(next)

    return primary
}

/** bounded length-prefixed gateway protocol codec。 */
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
        val sizeBuffer = ByteBuffer.allocate(Int.SIZE_BYTES)
        readFully(channel, sizeBuffer)
        sizeBuffer.flip()
        val size = sizeBuffer.int
        require(size in 1..MAX_GATEWAY_FRAME_BYTES) { "Submission gateway frame size rejected." }
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

    private fun readFully(channel: SocketChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) check(channel.read(buffer) >= 0) { "Submission gateway frame ended early." }
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
    val version = request.requiredString("version").toInt()
    val terminalEvidence = request["terminalEvidence"]
    if (!captureEnabled) {
        require(version == LEGACY_GATEWAY_PROTOCOL_VERSION && terminalEvidence == null) {
            "Disabled terminal evidence requires the legacy gateway request."
        }

        return TerminalToolEvidenceBundle.disabled()
    }

    require(version == TERMINAL_EVIDENCE_GATEWAY_PROTOCOL_VERSION && terminalEvidence != null) {
        "Enabled terminal evidence requires the versioned evidence request."
    }

    return LlmTerminalEvidenceCodec.decodeBundle(terminalEvidence.jsonObject)
}

/** serialized gateway payloadが既存単一frame上限内かを返す。 */
fun gatewayFrameFits(payload: JsonObject): Boolean = payload.toString().encodeToByteArray().size in
    1..MAX_GATEWAY_FRAME_BYTES

const val OPERATION_SUBMIT_DECISION = "SUBMIT_DECISION"
const val OPERATION_SUBMIT_FALSIFICATION = "SUBMIT_FALSIFICATION"
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
