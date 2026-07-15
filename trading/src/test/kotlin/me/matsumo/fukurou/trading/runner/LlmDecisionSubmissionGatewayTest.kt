package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundle
import me.matsumo.fukurou.trading.audit.requiresCompleteTerminalEvidence
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import java.math.BigDecimal
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmDecisionSubmissionGatewayTest {
    @Test
    fun `incomplete evidence risk matrix covers every action and verdict`() {
        DecisionAction.entries.forEach { action ->
            assertEquals(action in setOf(DecisionAction.ENTER, DecisionAction.ADD_LONG), action.requiresCompleteTerminalEvidence())
        }
        FalsificationVerdict.entries.forEach { verdict ->
            assertEquals(verdict == FalsificationVerdict.APPROVED, verdict.requiresCompleteTerminalEvidence())
        }
    }

    @Test
    fun `gateway accepts enabled terminal evidence activation`() {
        val path = Path.of("/tmp/fukurou-gateway-activation-${System.nanoTime()}.sock")

        val gateway = LlmDecisionSubmissionGateway.start(
            socketPath = path,
            repository = InMemoryDecisionRepository(),
            invocationId = INVOCATION_ID,
            phase = LlmInvocationPhase.PROPOSER,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
            terminalEvidenceCaptureEnabled = true,
        )

        assertTrue(Files.exists(path))
        gateway.close()
        assertFalse(Files.exists(path))
    }

    @Test
    fun `disabled terminal request preserves legacy version and field set`() {
        val request = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))

        assertEquals("1", request.getValue("version").toString())
        assertFalse("terminalEvidence" in request)
        assertEquals(TerminalToolEvidenceBundle.disabled(), decodeTerminalEvidenceBundle(request, false))
        assertTrue(gatewayFrameFits(request))
    }

    @Test
    fun `enabled terminal request requires version two and evidence field`() {
        val request = LlmSubmissionGatewayCodec.requestWithTerminalEvidence(
            operation = OPERATION_SUBMIT_DECISION,
            invocationId = INVOCATION_ID,
            phase = LlmInvocationPhase.PROPOSER,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
            payload = LlmSubmissionGatewayCodec.encodeDecision(decision(DecisionAction.NO_TRADE)),
            terminalEvidence = TerminalToolEvidenceBundle.disabled(),
        )

        assertEquals("2", request.getValue("version").toString())
        assertEquals(TerminalToolEvidenceBundle.disabled(), decodeTerminalEvidenceBundle(request, true))
        assertFailsWith<IllegalArgumentException> { decodeTerminalEvidenceBundle(request, false) }
    }

    @Test
    fun `activation and protocol version mismatch is rejected`() {
        val legacy = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))
        val versionTwoWithoutEvidence = legacy.toMutableMap()
            .also { request -> request["version"] = JsonPrimitive(2) }
            .let(::JsonObject)

        assertFailsWith<IllegalArgumentException> { decodeTerminalEvidenceBundle(legacy, true) }
        assertFailsWith<IllegalArgumentException> { decodeTerminalEvidenceBundle(versionTwoWithoutEvidence, true) }
    }

    @Test
    fun `permission failure closes bound channel and aggregates socket cleanup failure`() {
        val path = Path.of("/tmp/fukurou-gateway-permission-${System.nanoTime()}.sock")
        val permissionFailure = IllegalStateException("synthetic permission failure")
        val channelCleanupFailure = IllegalStateException("synthetic channel cleanup failure")
        val socketCleanupFailure = IllegalStateException("synthetic socket cleanup failure")
        val failure = assertFailsWith<IllegalStateException> {
            gatewayWithHooks(
                path = path,
                hooks = LlmDecisionSubmissionGatewayStartHooks(
                    setSocketPermissions = { throw permissionFailure },
                    closeServer = { server ->
                        server.close()
                        throw channelCleanupFailure
                    },
                    deleteSocket = { socketPath ->
                        if (Files.deleteIfExists(socketPath)) throw socketCleanupFailure
                    },
                ),
            )
        }

        assertTrue(failure === permissionFailure)
        assertTrue(failure.suppressed.single() === channelCleanupFailure)
        assertTrue(channelCleanupFailure.suppressed.single() === socketCleanupFailure)
        assertFalse(Files.exists(path))
    }

    @Test
    fun `executor setup failure cleans executor channel and socket with aggregated failures`() {
        val path = Path.of("/tmp/fukurou-gateway-executor-${System.nanoTime()}.sock")
        val setupFailure = IllegalStateException("synthetic executor setup failure")
        val executorCleanupFailure = IllegalStateException("synthetic executor cleanup failure")
        val socketCleanupFailure = IllegalStateException("synthetic socket cleanup failure")
        val failure = assertFailsWith<IllegalStateException> {
            gatewayWithHooks(
                path = path,
                hooks = LlmDecisionSubmissionGatewayStartHooks(
                    execute = { _, _ -> throw setupFailure },
                    shutdownExecutor = { executor ->
                        executor.shutdownNow()
                        throw executorCleanupFailure
                    },
                    deleteSocket = { socketPath ->
                        if (Files.deleteIfExists(socketPath)) throw socketCleanupFailure
                    },
                ),
            )
        }

        assertTrue(failure === setupFailure)
        assertTrue(failure.suppressed.single() === executorCleanupFailure)
        assertTrue(executorCleanupFailure.suppressed.single() === socketCleanupFailure)
        assertFalse(Files.exists(path))
    }

    @Test
    fun `gateway close reports socket cleanup failure`() {
        val path = Path.of("/tmp/fukurou-gateway-cleanup-${System.nanoTime()}.sock")
        val gateway = gateway(path, InMemoryDecisionRepository(), LlmInvocationPhase.PROPOSER)
        Files.delete(path)
        Files.createDirectory(path)
        Files.writeString(path.resolve("child"), "prevent directory removal")

        assertFailsWith<java.nio.file.DirectoryNotEmptyException> { gateway.close() }

        Files.delete(path.resolve("child"))
        Files.delete(path)
    }

    @Test
    fun `app owned gateway persists bound decision and removes socket on close`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path),
        )

        val response = connect(path).use { channel ->
            LlmSubmissionGatewayCodec.writeFrame(
                channel,
                request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE)),
            )
            LlmSubmissionGatewayCodec.readFrame(channel)
        }

        assertEquals("true", response.getValue("accepted").toString())
        assertEquals(DecisionAction.NO_TRADE, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow()?.decision?.submission?.action)
        gateway.close()
        assertFalse(Files.exists(path))
    }

    @Test
    fun `terminal evidence extension preserves caller tool evidence ids order and duplicates`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-caller-evidence-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)
        val callerIds = listOf("tool-2", "tool-1", "tool-2")

        connect(path).use { channel ->
            LlmSubmissionGatewayCodec.writeFrame(
                channel,
                request(
                    LlmInvocationPhase.PROPOSER,
                    decision(DecisionAction.NO_TRADE).copy(toolEvidenceIds = callerIds),
                ),
            )
            LlmSubmissionGatewayCodec.readFrame(channel)
        }

        assertEquals(
            callerIds,
            repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow()?.decision?.submission?.toolEvidenceIds,
        )
        gateway.close()
    }

    @Test
    fun `effective invocation hash mismatch rejects cross manifest request`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)
        val mismatchedRequest = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE)).toMutableMap()
            .also { request -> request["effectiveInvocationHash"] = JsonPrimitive("cross-manifest") }
            .let(::JsonObject)

        val response = connect(path).use { channel ->
            LlmSubmissionGatewayCodec.writeFrame(channel, mismatchedRequest)
            LlmSubmissionGatewayCodec.readFrame(channel)
        }

        assertEquals("false", response.getValue("accepted").toString())
        assertEquals(null, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow())
        gateway.close()
    }

    @Test
    fun `phase and invocation binding reject before repository write`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)

        val response = connect(path).use { channel ->
            LlmSubmissionGatewayCodec.writeFrame(
                channel,
                request(LlmInvocationPhase.FALSIFIER, decision(DecisionAction.NO_TRADE)),
            )
            LlmSubmissionGatewayCodec.readFrame(channel)
        }

        assertEquals("false", response.getValue("accepted").toString())
        assertEquals(null, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow())
        gateway.close()
    }

    @Test
    fun `risk reduction gateway denies entry without stage two dependency`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.RISK_REDUCTION_ONLY)

        val response = connect(path).use { channel ->
            LlmSubmissionGatewayCodec.writeFrame(
                channel,
                request(LlmInvocationPhase.RISK_REDUCTION_ONLY, decision(DecisionAction.ENTER)),
            )
            LlmSubmissionGatewayCodec.readFrame(channel)
        }

        assertEquals("false", response.getValue("accepted").toString())
        assertEquals(null, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow())
        gateway.close()
    }

    @Test
    fun `risk reduction gateway accepts only bounded safety reducing action set`() = runBlocking {
        val allowedActions = listOf(
            DecisionAction.EXIT,
            DecisionAction.REDUCE,
            DecisionAction.ADJUST_PROTECTION,
            DecisionAction.NO_TRADE,
        )

        allowedActions.forEach { action ->
            val repository = InMemoryDecisionRepository()
            val path = Path.of("/tmp/fukurou-gateway-${action.name.lowercase()}-${System.nanoTime()}.sock")
            val gateway = gateway(path, repository, LlmInvocationPhase.RISK_REDUCTION_ONLY)
            val response = connect(path).use { channel ->
                LlmSubmissionGatewayCodec.writeFrame(
                    channel,
                    request(LlmInvocationPhase.RISK_REDUCTION_ONLY, decision(action)),
                )
                LlmSubmissionGatewayCodec.readFrame(channel)
            }

            assertEquals("true", response.getValue("accepted").toString(), action.name)
            assertEquals(action, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow()?.decision?.submission?.action)
            gateway.close()
        }
    }

    private fun gateway(
        path: Path,
        repository: InMemoryDecisionRepository,
        phase: LlmInvocationPhase,
    ) = LlmDecisionSubmissionGateway.start(
        socketPath = path,
        repository = repository,
        invocationId = INVOCATION_ID,
        phase = phase,
        phaseManifestId = PHASE_MANIFEST_ID,
        effectiveInvocationHash = EFFECTIVE_HASH,
    )

    private fun gatewayWithHooks(path: Path, hooks: LlmDecisionSubmissionGatewayStartHooks) =
        LlmDecisionSubmissionGateway.startWithHooks(
            socketPath = path,
            repository = InMemoryDecisionRepository(),
            invocationId = INVOCATION_ID,
            phase = LlmInvocationPhase.PROPOSER,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
            hooks = hooks,
        )

    private fun request(
        phase: LlmInvocationPhase,
        decision: DecisionSubmission,
    ): kotlinx.serialization.json.JsonObject {
        return LlmSubmissionGatewayCodec.request(
            operation = OPERATION_SUBMIT_DECISION,
            invocationId = INVOCATION_ID,
            phase = phase,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
            payload = LlmSubmissionGatewayCodec.encodeDecision(decision),
        )
    }

    private fun decision(action: DecisionAction) = DecisionSubmission(
        invocationId = INVOCATION_ID,
        llmProvider = "fixture",
        promptHash = "fixture",
        systemPromptVersion = "fixture-v1",
        marketSnapshotId = "fixture",
        action = action,
        closeRatio = if (action == DecisionAction.REDUCE) BigDecimal("0.5") else null,
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

    private fun connect(path: Path): SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
        connect(UnixDomainSocketAddress.of(path))
    }
}

private const val INVOCATION_ID = "gateway-test-invocation"
private const val PHASE_MANIFEST_ID = "gateway-test-invocation:PROPOSER"
private const val EFFECTIVE_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
