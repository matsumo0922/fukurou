package me.matsumo.fukurou.mcp

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.runner.LlmDecisionSubmissionGateway
import java.math.BigDecimal
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmDecisionSubmissionGatewayClientTest {
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
