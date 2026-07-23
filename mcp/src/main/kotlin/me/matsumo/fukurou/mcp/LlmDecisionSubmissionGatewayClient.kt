package me.matsumo.fukurou.mcp

import kotlinx.serialization.json.JsonObject
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundle
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.DecisionSubmissionConflictException
import me.matsumo.fukurou.trading.decision.DecisionSubmissionUnknownException
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.runner.DECISION_SUBMISSION_CONFLICT_CODE
import me.matsumo.fukurou.trading.runner.DECISION_SUBMISSION_UNKNOWN_CODE
import me.matsumo.fukurou.trading.runner.LlmSubmissionGatewayCodec
import me.matsumo.fukurou.trading.runner.OPERATION_SUBMIT_DECISION
import me.matsumo.fukurou.trading.runner.OPERATION_SUBMIT_FALSIFICATION
import me.matsumo.fukurou.trading.runner.gatewayFrameFits
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel

/** manifest の submission gateway socket path へ connect する app-owned client。 */
class LlmDecisionSubmissionGatewayClient private constructor(
    private val channel: SocketChannel,
    private val binding: McpSubmissionGatewayBinding,
) : AutoCloseable {
    private var terminalEvidenceProvider: (() -> TerminalToolEvidenceBundle)? = null

    internal fun bindTerminalEvidenceProvider(provider: () -> TerminalToolEvidenceBundle) {
        terminalEvidenceProvider = provider
    }

    fun submitDecision(submission: DecisionSubmission): JsonObject = submit(
        operation = OPERATION_SUBMIT_DECISION,
        payload = LlmSubmissionGatewayCodec.encodeDecision(submission),
    )

    fun submitFalsification(submission: FalsificationSubmission): JsonObject = submit(
        operation = OPERATION_SUBMIT_FALSIFICATION,
        payload = LlmSubmissionGatewayCodec.encodeFalsification(submission),
    )

    override fun close() = channel.close()

    private fun submit(operation: String, payload: JsonObject): JsonObject {
        val provider = terminalEvidenceProvider
        val request = if (provider == null) {
            LlmSubmissionGatewayCodec.request(
                operation = operation,
                invocationId = binding.invocationId,
                phase = binding.phase,
                phaseManifestId = binding.phaseManifestId,
                effectiveInvocationHash = binding.effectiveInvocationHash,
                payload = payload,
            )
        } else {
            LlmSubmissionGatewayCodec.requestWithTerminalEvidence(
                operation = operation,
                invocationId = binding.invocationId,
                phase = binding.phase,
                phaseManifestId = binding.phaseManifestId,
                effectiveInvocationHash = binding.effectiveInvocationHash,
                payload = payload,
                terminalEvidence = provider(),
            )
        }
        val boundedRequest = if (provider == null || gatewayFrameFits(request)) {
            request
        } else {
            LlmSubmissionGatewayCodec.requestWithTerminalEvidence(
                operation = operation,
                invocationId = binding.invocationId,
                phase = binding.phase,
                phaseManifestId = binding.phaseManifestId,
                effectiveInvocationHash = binding.effectiveInvocationHash,
                payload = payload,
                terminalEvidence = TerminalToolEvidenceBundle.frameLimit(),
            )
        }
        LlmSubmissionGatewayCodec.writeFrame(
            channel = channel,
            payload = boundedRequest,
        )

        val response = LlmSubmissionGatewayCodec.readFrame(channel)
        if (response["accepted"]?.toString() == "true") return response

        when (response["error"]?.toString()?.trim('"')) {
            DECISION_SUBMISSION_CONFLICT_CODE -> throw DecisionSubmissionConflictException()
            DECISION_SUBMISSION_UNKNOWN_CODE -> throw DecisionSubmissionUnknownException()
            else -> error("App-owned submission gateway rejected request.")
        }
    }

    companion object {
        /** manifest の `submissionSocketPath` へ直接 connect する。app と同一 UID で動く前提。 */
        fun fromSocketPath(binding: McpSubmissionGatewayBinding): LlmDecisionSubmissionGatewayClient {
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            channel.connect(UnixDomainSocketAddress.of(binding.submissionSocketPath))

            return LlmDecisionSubmissionGatewayClient(channel, binding)
        }

        internal fun fromChannel(
            channel: SocketChannel,
            binding: McpSubmissionGatewayBinding,
        ): LlmDecisionSubmissionGatewayClient = LlmDecisionSubmissionGatewayClient(channel, binding)
    }
}

/** manifest と submission gateway の相互 binding。 */
data class McpSubmissionGatewayBinding(
    val invocationId: String,
    val phase: LlmInvocationPhase,
    val phaseManifestId: String,
    val effectiveInvocationHash: String,
    val submissionSocketPath: String,
)
