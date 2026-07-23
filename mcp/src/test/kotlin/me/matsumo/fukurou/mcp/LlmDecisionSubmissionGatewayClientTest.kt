package me.matsumo.fukurou.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy
import me.matsumo.fukurou.trading.audit.TerminalToolEvidence
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundle
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundleStatus
import me.matsumo.fukurou.trading.audit.ToolEvidenceSourceTimestampStatus
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.DecisionSubmissionConflictException
import me.matsumo.fukurou.trading.decision.DecisionSubmissionUnknownException
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.runner.DECISION_SUBMISSION_CONFLICT_CODE
import me.matsumo.fukurou.trading.runner.DECISION_SUBMISSION_UNKNOWN_CODE
import me.matsumo.fukurou.trading.runner.LlmDecisionSubmissionGateway
import me.matsumo.fukurou.trading.runner.LlmSubmissionGatewayCodec
import me.matsumo.fukurou.trading.runner.MAX_GATEWAY_FRAME_BYTES
import me.matsumo.fukurou.trading.runner.OPERATION_SUBMIT_DECISION
import me.matsumo.fukurou.trading.runner.OPERATION_SUBMIT_FALSIFICATION
import java.math.BigDecimal
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LlmDecisionSubmissionGatewayClientTest {
    @Test
    fun `FRAME-1 proposer preserves legacy exact cap boundary`() {
        assertLegacyFrameBoundary(LlmInvocationPhase.PROPOSER)
    }

    @Test
    fun `FRAME-2 risk reduction preserves legacy exact cap boundary`() {
        assertLegacyFrameBoundary(LlmInvocationPhase.RISK_REDUCTION_ONLY)
    }

    @Test
    fun `FRAME-3 falsifier preserves legacy exact cap boundary`() {
        assertLegacyFrameBoundary(LlmInvocationPhase.FALSIFIER)
    }

    @Test
    fun `client shrinks an oversized evidence bundle to one typed incomplete frame`() {
        val path = Path.of("/tmp/fukurou-mcp-client-frame-${System.nanoTime()}.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(path))
        }
        val executor = Executors.newSingleThreadExecutor()
        val requestFuture = executor.submit<kotlinx.serialization.json.JsonObject> {
            server.accept().use { accepted ->
                val request = me.matsumo.fukurou.trading.runner.LlmSubmissionGatewayCodec.readFrame(accepted)
                me.matsumo.fukurou.trading.runner.LlmSubmissionGatewayCodec.writeFrame(
                    accepted,
                    buildJsonObject { put("accepted", true) },
                )
                request
            }
        }
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            connect(UnixDomainSocketAddress.of(path))
        }
        val client = LlmDecisionSubmissionGatewayClient.fromChannel(
            channel,
            McpSubmissionGatewayBinding(
                invocationId = INVOCATION_ID,
                phase = LlmInvocationPhase.PROPOSER,
                phaseManifestId = PHASE_MANIFEST_ID,
                effectiveInvocationHash = EFFECTIVE_HASH,
                submissionSocketPath = path.toString(),
            ),
        )
        val responseJson = "{\"value\":\"${"x".repeat(90_000)}\"}"
        client.bindTerminalEvidenceProvider {
            TerminalToolEvidenceBundle(
                status = TerminalToolEvidenceBundleStatus.COMPLETE,
                incompleteReason = null,
                entries = listOf(
                    TerminalToolEvidence(
                        ordinal = 0,
                        toolName = "get_ticker",
                        responseJson = responseJson,
                        responseHash = ManifestPersistencePolicy.sha256(responseJson),
                        sourceTimestamp = null,
                        sourceTimestampStatus = ToolEvidenceSourceTimestampStatus.MISSING,
                        isError = false,
                    ),
                ),
            )
        }

        client.submitDecision(noTradeDecision().copy(reasonJa = "r".repeat(50_000)))
        val request = requestFuture.get(5, TimeUnit.SECONDS)
        val bundle = me.matsumo.fukurou.trading.runner.LlmTerminalEvidenceCodec.decodeBundle(
            request.getValue("terminalEvidence") as kotlinx.serialization.json.JsonObject,
        )

        assertEquals(TerminalToolEvidenceBundle.frameLimit(), bundle)
        client.close()
        server.close()
        executor.shutdownNow()
        Files.deleteIfExists(path)
    }

    @Test
    fun `mcp client submits through app owned gateway`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-mcp-client-${System.nanoTime()}.sock")
        val gateway = LlmDecisionSubmissionGateway.start(
            socketPath = path,
            repository = repository,
            invocationId = INVOCATION_ID,
            phase = LlmInvocationPhase.PROPOSER,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
        )
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            connect(UnixDomainSocketAddress.of(path))
        }
        val client = LlmDecisionSubmissionGatewayClient.fromChannel(
            channel,
            McpSubmissionGatewayBinding(
                invocationId = INVOCATION_ID,
                phase = LlmInvocationPhase.PROPOSER,
                phaseManifestId = PHASE_MANIFEST_ID,
                effectiveInvocationHash = EFFECTIVE_HASH,
                submissionSocketPath = path.toString(),
            ),
        )

        val response = client.submitDecision(noTradeDecision())

        assertEquals("true", response.getValue("accepted").toString())
        assertEquals(DecisionAction.NO_TRADE, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow()?.decision?.submission?.action)
        client.close()
        gateway.close()
    }

    @Test
    fun `fromSocketPath connects to the manifest socket path and persists through the gateway`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-mcp-client-socketpath-${System.nanoTime()}.sock")
        val gateway = LlmDecisionSubmissionGateway.start(
            socketPath = path,
            repository = repository,
            invocationId = INVOCATION_ID,
            phase = LlmInvocationPhase.PROPOSER,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
        )
        val client = LlmDecisionSubmissionGatewayClient.fromSocketPath(
            McpSubmissionGatewayBinding(
                invocationId = INVOCATION_ID,
                phase = LlmInvocationPhase.PROPOSER,
                phaseManifestId = PHASE_MANIFEST_ID,
                effectiveInvocationHash = EFFECTIVE_HASH,
                submissionSocketPath = path.toString(),
            ),
        )

        val response = client.submitDecision(noTradeDecision())

        assertEquals("true", response.getValue("accepted").toString())
        assertEquals(DecisionAction.NO_TRADE, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow()?.decision?.submission?.action)
        client.close()
        gateway.close()
    }

    @Test
    fun `mcp client restores typed decision authority errors`() {
        assertTypedGatewayError<DecisionSubmissionConflictException>(DECISION_SUBMISSION_CONFLICT_CODE)
        assertTypedGatewayError<DecisionSubmissionUnknownException>(DECISION_SUBMISSION_UNKNOWN_CODE)
    }

    private fun noTradeDecision() = DecisionSubmission(
        invocationId = INVOCATION_ID,
        llmProvider = "fixture",
        promptHash = "fixture",
        systemPromptVersion = "fixture-v1",
        marketSnapshotId = "fixture",
        action = DecisionAction.NO_TRADE,
        setupTags = emptyList(),
        estimatedWinProbability = BigDecimal("0.5"),
        expectedRMultiple = BigDecimal.ZERO,
        roundTripCostR = null,
        toolEvidenceIds = emptyList(),
        factCheckJson = "{}",
        selfReviewJson = "{}",
        reasonJa = "fixture",
        missingDataJa = emptyList(),
        noTradeConditionsJa = emptyList(),
        entryIntent = null,
        tradePlan = null,
    )

    private fun assertLegacyFrameBoundary(phase: LlmInvocationPhase) {
        val path = Path.of("/tmp/fukurou-mcp-legacy-frame-${phase.name.lowercase()}-${System.nanoTime()}.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(path))
        }
        val executor = Executors.newSingleThreadExecutor()
        val requestFuture = executor.submit<kotlinx.serialization.json.JsonObject> {
            server.accept().use { accepted ->
                val request = LlmSubmissionGatewayCodec.readFrame(accepted)
                LlmSubmissionGatewayCodec.writeFrame(accepted, buildJsonObject { put("accepted", true) })
                request
            }
        }
        val paddingSize = MAX_GATEWAY_FRAME_BYTES - legacyRequest(phase, "").toString().encodeToByteArray().size
        require(paddingSize > 0)

        connectedClient(path, phase).use { client -> submitForPhase(client, phase, "x".repeat(paddingSize)) }
        val exactRequest = requestFuture.get(5, TimeUnit.SECONDS)

        assertEquals(MAX_GATEWAY_FRAME_BYTES, exactRequest.toString().encodeToByteArray().size)
        assertEquals("1", exactRequest.getValue("version").toString())
        assertEquals(
            setOf("version", "operation", "invocationId", "phase", "phaseManifestId", "effectiveInvocationHash", "payload"),
            exactRequest.keys,
        )
        assertFalse("terminalEvidence" in exactRequest)

        connectedClient(path, phase).use { client ->
            assertFailsWith<IllegalArgumentException> {
                submitForPhase(client, phase, "x".repeat(paddingSize + 1))
            }
        }
        server.close()
        executor.shutdownNow()
        Files.deleteIfExists(path)
    }

    private inline fun <reified T : Throwable> assertTypedGatewayError(code: String) {
        val path = Path.of("/tmp/fukurou-mcp-error-${System.nanoTime()}.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(path))
        }
        val executor = Executors.newSingleThreadExecutor()
        val response = executor.submit {
            server.accept().use { accepted ->
                LlmSubmissionGatewayCodec.readFrame(accepted)
                LlmSubmissionGatewayCodec.writeFrame(
                    accepted,
                    buildJsonObject {
                        put("accepted", false)
                        put("error", code)
                    },
                )
            }
        }

        connectedClient(path, LlmInvocationPhase.PROPOSER).use { client ->
            assertFailsWith<T> { client.submitDecision(noTradeDecision()) }
        }
        response.get(5, TimeUnit.SECONDS)
        server.close()
        executor.shutdownNow()
        Files.deleteIfExists(path)
    }

    private fun connectedClient(path: Path, phase: LlmInvocationPhase): LlmDecisionSubmissionGatewayClient {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            connect(UnixDomainSocketAddress.of(path))
        }

        return LlmDecisionSubmissionGatewayClient.fromChannel(
            channel,
            McpSubmissionGatewayBinding(INVOCATION_ID, phase, PHASE_MANIFEST_ID, EFFECTIVE_HASH, path.toString()),
        )
    }

    private fun legacyRequest(phase: LlmInvocationPhase, reason: String): kotlinx.serialization.json.JsonObject {
        val operation = if (phase == LlmInvocationPhase.FALSIFIER) {
            OPERATION_SUBMIT_FALSIFICATION
        } else {
            OPERATION_SUBMIT_DECISION
        }
        val payload = if (phase == LlmInvocationPhase.FALSIFIER) {
            LlmSubmissionGatewayCodec.encodeFalsification(falsification(reason))
        } else {
            LlmSubmissionGatewayCodec.encodeDecision(noTradeDecision().copy(reasonJa = reason))
        }

        return LlmSubmissionGatewayCodec.request(
            operation = operation,
            invocationId = INVOCATION_ID,
            phase = phase,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
            payload = payload,
        )
    }

    private fun submitForPhase(
        client: LlmDecisionSubmissionGatewayClient,
        phase: LlmInvocationPhase,
        reason: String,
    ) {
        if (phase == LlmInvocationPhase.FALSIFIER) {
            client.submitFalsification(falsification(reason))
        } else {
            client.submitDecision(noTradeDecision().copy(reasonJa = reason))
        }
    }

    private fun falsification(reason: String) = FalsificationSubmission(
        intentId = null,
        verdict = FalsificationVerdict.REJECTED,
        llmProvider = "fixture",
        reasonJa = reason,
    )
}

private const val INVOCATION_ID = "mcp-gateway-client-test"
private const val PHASE_MANIFEST_ID = "mcp-gateway-client-test:PROPOSER"
private const val EFFECTIVE_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
