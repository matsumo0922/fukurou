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
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.runner.LlmDecisionSubmissionGateway
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

class LlmDecisionSubmissionGatewayClientTest {
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
            ),
        )

        val response = client.submitDecision(noTradeDecision())

        assertEquals("true", response.getValue("accepted").toString())
        assertEquals(DecisionAction.NO_TRADE, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow()?.decision?.submission?.action)
        client.close()
        gateway.close()
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
}

private const val INVOCATION_ID = "mcp-gateway-client-test"
private const val PHASE_MANIFEST_ID = "mcp-gateway-client-test:PROPOSER"
private const val EFFECTIVE_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
