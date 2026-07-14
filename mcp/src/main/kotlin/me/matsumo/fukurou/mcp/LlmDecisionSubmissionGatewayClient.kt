package me.matsumo.fukurou.mcp

import kotlinx.serialization.json.JsonObject
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundle
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.runner.LlmSubmissionGatewayCodec
import me.matsumo.fukurou.trading.runner.OPERATION_SUBMIT_DECISION
import me.matsumo.fukurou.trading.runner.OPERATION_SUBMIT_FALSIFICATION
import java.io.FileDescriptor
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.channels.spi.SelectorProvider

/** launcher が接続済み FD 5 として渡す app-owned submission gateway client。 */
class LlmDecisionSubmissionGatewayClient private constructor(
    private val channel: SocketChannel,
    private val binding: McpSubmissionGatewayBinding,
) : AutoCloseable {
    private var terminalEvidenceProvider: () -> TerminalToolEvidenceBundle = TerminalToolEvidenceBundle::disabled

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
        val request = LlmSubmissionGatewayCodec.request(
            operation = operation,
            invocationId = binding.invocationId,
            phase = binding.phase,
            phaseManifestId = binding.phaseManifestId,
            effectiveInvocationHash = binding.effectiveInvocationHash,
            payload = payload,
            terminalEvidence = terminalEvidenceProvider(),
        )
        val boundedRequest = if (LlmSubmissionGatewayCodec.fitsFrame(request)) {
            request
        } else {
            LlmSubmissionGatewayCodec.request(
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

        return LlmSubmissionGatewayCodec.readFrame(channel).also { response ->
            require(response["accepted"]?.toString() == "true") { "App-owned submission gateway rejected request." }
        }
    }

    companion object {
        fun fromConnectedDescriptor(binding: McpSubmissionGatewayBinding): LlmDecisionSubmissionGatewayClient {
            return LlmDecisionSubmissionGatewayClient(openConnectedDescriptor(SUBMISSION_GATEWAY_FD), binding)
        }

        internal fun fromChannel(
            channel: SocketChannel,
            binding: McpSubmissionGatewayBinding,
        ): LlmDecisionSubmissionGatewayClient = LlmDecisionSubmissionGatewayClient(channel, binding)

        private fun openConnectedDescriptor(descriptor: Int): SocketChannel {
            val fileDescriptorConstructor = FileDescriptor::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .also { constructor -> constructor.isAccessible = true }
            val fileDescriptor = fileDescriptorConstructor.newInstance(descriptor)
            val implementation = Class.forName("sun.nio.ch.SocketChannelImpl")
            val constructor = implementation.getDeclaredConstructor(
                SelectorProvider::class.java,
                java.net.ProtocolFamily::class.java,
                FileDescriptor::class.java,
                java.net.SocketAddress::class.java,
            ).also { value -> value.isAccessible = true }

            return constructor.newInstance(
                SelectorProvider.provider(),
                StandardProtocolFamily.UNIX,
                fileDescriptor,
                UnixDomainSocketAddress.of("/run/fukurou/connected-submission-gateway"),
            ) as SocketChannel
        }
    }
}

/** manifest と FD 5 の相互 binding。 */
data class McpSubmissionGatewayBinding(
    val invocationId: String,
    val phase: LlmInvocationPhase,
    val phaseManifestId: String,
    val effectiveInvocationHash: String,
)

private const val SUBMISSION_GATEWAY_FD = 5
