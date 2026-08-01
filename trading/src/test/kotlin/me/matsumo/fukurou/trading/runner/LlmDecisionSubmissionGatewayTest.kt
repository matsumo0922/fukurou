package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundle
import me.matsumo.fukurou.trading.audit.requiresCompleteTerminalEvidence
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealthTestFixture
import me.matsumo.fukurou.trading.decision.DecisionAction
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.DecisionSubmissionAuthority
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.SubmissionRejectedException
import me.matsumo.fukurou.trading.decision.SubmissionRejectionCode
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmProcessTreeTerminationRegistry
import me.matsumo.fukurou.trading.invoker.LlmSemanticSubmissionState
import me.matsumo.fukurou.trading.invoker.ProcessTreeTerminationProof
import java.io.IOException
import java.math.BigDecimal
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmDecisionSubmissionGatewayTest {
    @BeforeTest
    fun setUpAdmissionState() {
        LlmExecutionAdmissionHealthTestFixture.reset()
        LlmProcessTreeTerminationRegistry.resolve(INVOCATION_ID)
    }

    @AfterTest
    fun tearDownAdmissionState() {
        LlmExecutionAdmissionHealthTestFixture.reset()
        LlmProcessTreeTerminationRegistry.resolve(INVOCATION_ID)
    }

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
    fun `rejection code wire values are unique closed snake case identifiers`() {
        val wireValues = SubmissionRejectionCode.entries.map { code -> code.wireValue }

        assertEquals(wireValues.size, wireValues.toSet().size)
        assertTrue(wireValues.all { value -> value.matches(Regex("[a-z][a-z0-9_]*")) })
        assertEquals(
            SubmissionRejectionCode.entries.toSet(),
            wireValues.mapNotNull(SubmissionRejectionCode::fromWireValue).toSet(),
        )
        assertEquals(null, SubmissionRejectionCode.fromWireValue("outside_vocabulary"))
        assertTrue(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE in SubmissionRejectionCode.entries)
    }

    @Test
    fun `gateway distinguishes every binding rejection point`() {
        val baseRequest = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))
        val mismatches = mapOf(
            "invocationId" to SubmissionRejectionCode.INVOCATION_BINDING_MISMATCH,
            "phase" to SubmissionRejectionCode.PHASE_BINDING_MISMATCH,
            "phaseManifestId" to SubmissionRejectionCode.MANIFEST_BINDING_MISMATCH,
            "effectiveInvocationHash" to SubmissionRejectionCode.EFFECTIVE_HASH_MISMATCH,
        )

        mismatches.forEach { (field, expectedCode) ->
            val changedRequest = baseRequest.toMutableMap()
                .also { request -> request[field] = JsonPrimitive("mismatch") }
                .let(::JsonObject)
            val response = exchangeRequest(
                repository = InMemoryDecisionRepository(),
                phase = LlmInvocationPhase.PROPOSER,
                request = changedRequest,
            )

            assertEquals("SUBMISSION_REJECTED", response.getValue("error").jsonPrimitive.content)
            assertEquals(expectedCode.wireValue, response.getValue("reason").jsonPrimitive.content)
        }
    }

    @Test
    fun `gateway classifies non string binding primitive as required string missing`() {
        val request = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))
            .withField("invocationId", JsonPrimitive(1))
        val response = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.PROPOSER,
            request = request,
        )

        assertEquals("SUBMISSION_REJECTED", response.getValue("error").jsonPrimitive.content)
        assertEquals(SubmissionRejectionCode.REQUIRED_STRING_FIELD_MISSING.wireValue, response.reason())
    }

    @Test
    fun `gateway classifies non string operation primitive as required string missing`() {
        val request = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))
            .withField("operation", JsonPrimitive(false))
        val response = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.PROPOSER,
            request = request,
        )

        assertEquals("SUBMISSION_REJECTED", response.getValue("error").jsonPrimitive.content)
        assertEquals(SubmissionRejectionCode.REQUIRED_STRING_FIELD_MISSING.wireValue, response.reason())
    }

    @Test
    fun `gateway assigns distinct reasons to every request rejection point`() {
        val baseDecisionRequest = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))
        val decisionPhaseResponse = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.FALSIFIER,
            request = request(LlmInvocationPhase.FALSIFIER, decision(DecisionAction.NO_TRADE)),
        )
        val falsificationPhaseResponse = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.PROPOSER,
            request = falsificationRequest(LlmInvocationPhase.PROPOSER),
        )
        val decisionDecodeResponse = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.PROPOSER,
            request = baseDecisionRequest.withField("payload", JsonObject(emptyMap())),
        )
        val falsificationDecodeResponse = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.FALSIFIER,
            request = falsificationRequest(LlmInvocationPhase.FALSIFIER)
                .withField("payload", JsonObject(emptyMap())),
        )
        val unknownOperationResponse = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.PROPOSER,
            request = baseDecisionRequest.withField("operation", JsonPrimitive("UNKNOWN")),
        )
        val payloadMissingResponse = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.PROPOSER,
            request = baseDecisionRequest.withoutField("payload"),
        )
        val requiredStringResponse = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.PROPOSER,
            request = baseDecisionRequest.withoutField("effectiveInvocationHash"),
        )
        val responses = mapOf(
            SubmissionRejectionCode.FRAME_DECODE_FAILED to exchangeMalformedFrame(),
            SubmissionRejectionCode.DECISION_PAYLOAD_DECODE_FAILED to decisionDecodeResponse,
            SubmissionRejectionCode.FALSIFICATION_PAYLOAD_DECODE_FAILED to falsificationDecodeResponse,
            SubmissionRejectionCode.UNKNOWN_OPERATION to unknownOperationResponse,
            SubmissionRejectionCode.PAYLOAD_MISSING_OR_INVALID to payloadMissingResponse,
            SubmissionRejectionCode.REQUIRED_STRING_FIELD_MISSING to requiredStringResponse,
            SubmissionRejectionCode.DECISION_PHASE_NOT_AUTHORIZED to decisionPhaseResponse,
            SubmissionRejectionCode.FALSIFICATION_PHASE_NOT_AUTHORIZED to falsificationPhaseResponse,
        )

        responses.forEach { (expectedCode, response) ->
            assertEquals("SUBMISSION_REJECTED", response.getValue("error").jsonPrimitive.content)
            assertEquals(expectedCode.wireValue, response.reason())
        }
        assertEquals(responses.size, responses.values.map(JsonObject::reason).toSet().size)
    }

    @Test
    fun `gateway classifies decision invocation and terminal evidence rejections`() {
        val mismatchedDecision = decision(DecisionAction.NO_TRADE).copy(invocationId = "other-invocation")
        val decisionResponse = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.PROPOSER,
            request = request(LlmInvocationPhase.PROPOSER, mismatchedDecision),
        )
        val terminalContractRequest = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))
            .withField("version", JsonPrimitive(2))
        val terminalResponse = exchangeRequest(
            repository = InMemoryDecisionRepository(),
            phase = LlmInvocationPhase.PROPOSER,
            request = terminalContractRequest,
        )

        assertEquals(SubmissionRejectionCode.DECISION_INVOCATION_MISMATCH.wireValue, decisionResponse.reason())
        assertEquals(SubmissionRejectionCode.TERMINAL_EVIDENCE_CONTRACT_VIOLATION.wireValue, terminalResponse.reason())
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
        assertEquals(LlmSemanticSubmissionState.NOT_ATTEMPTED, gateway.semanticSubmissionState())
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
        assertFailsWith<SubmissionRejectedException> { decodeTerminalEvidenceBundle(request, false) }
    }

    @Test
    fun `activation and protocol version mismatch is rejected`() {
        val legacy = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))
        val versionTwoWithoutEvidence = legacy.toMutableMap()
            .also { request -> request["version"] = JsonPrimitive(2) }
            .let(::JsonObject)

        assertFailsWith<SubmissionRejectedException> { decodeTerminalEvidenceBundle(legacy, true) }
        assertFailsWith<SubmissionRejectedException> { decodeTerminalEvidenceBundle(versionTwoWithoutEvidence, true) }
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
        assertEquals(LlmSemanticSubmissionState.COMMITTED, gateway.semanticSubmissionState())
        gateway.close()
        assertFalse(Files.exists(path))
    }

    @Test
    fun `frame reader treats initial eof as connection end and partial prefix as failure`() {
        withSocketPair("initial-eof") { client, serverSide ->
            client.shutdownOutput()

            assertEquals(null, LlmSubmissionGatewayCodec.readFrameOrNull(serverSide))
        }
        withSocketPair("partial-prefix") { client, serverSide ->
            client.write(ByteBuffer.wrap(byteArrayOf(0, 0)))
            client.shutdownOutput()

            assertFailsWith<IllegalStateException> {
                LlmSubmissionGatewayCodec.readFrameOrNull(serverSide)
            }
        }
        withSocketPair("partial-payload") { client, serverSide ->
            client.write(ByteBuffer.allocate(Int.SIZE_BYTES + 2).putInt(4).put(byteArrayOf(1, 2)).flip())
            client.shutdownOutput()

            assertFailsWith<IllegalStateException> {
                LlmSubmissionGatewayCodec.readFrameOrNull(serverSide)
            }
        }
        withSocketPair("oversized-frame") { client, serverSide ->
            client.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(MAX_GATEWAY_FRAME_BYTES + 1).flip())

            assertFailsWith<IllegalArgumentException> {
                LlmSubmissionGatewayCodec.readFrame(serverSide)
            }
        }
    }

    @Test
    fun `rejected submission can be corrected on the same connection`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-same-connection-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)
        val rejected = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))
            .withField("effectiveInvocationHash", JsonPrimitive("mismatch"))

        connect(path).use { channel ->
            val rejectedResponse = exchangeFrame(channel, rejected)
            val acceptedResponse = exchangeFrame(
                channel,
                request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE)),
            )

            assertEquals("false", rejectedResponse.getValue("accepted").toString())
            assertEquals("true", acceptedResponse.getValue("accepted").toString())
        }

        assertEquals(DecisionAction.NO_TRADE, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow()?.decision?.submission?.action)
        assertEquals(LlmSemanticSubmissionState.COMMITTED, gateway.semanticSubmissionState())
        gateway.close()
    }

    @Test
    fun `rejected submission can be corrected after reconnecting`() {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-reconnect-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)
        val rejected = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))
            .withField("effectiveInvocationHash", JsonPrimitive("mismatch"))

        val rejectedResponse = connect(path).use { channel -> exchangeFrame(channel, rejected) }
        val acceptedResponse = connect(path).use { channel ->
            exchangeFrame(channel, request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE)))
        }

        assertEquals("false", rejectedResponse.getValue("accepted").toString())
        assertEquals("true", acceptedResponse.getValue("accepted").toString())
        assertEquals(LlmSemanticSubmissionState.COMMITTED, gateway.semanticSubmissionState())
        gateway.close()
    }

    @Test
    fun `same payload retry on one connection returns committed result without duplicate row`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-idempotent-session-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)
        val submission = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE))

        connect(path).use { channel ->
            val first = exchangeFrame(channel, submission)
            val retry = exchangeFrame(channel, submission)

            assertEquals(first.getValue("decision_id"), retry.getValue("decision_id"))
        }

        assertEquals(1, repository.snapshots.decisions().size)
        gateway.close()
    }

    @Test
    fun `conflict after commit keeps semantic submission committed`() {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-committed-conflict-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)
        val firstSubmission = decision(DecisionAction.NO_TRADE)

        connect(path).use { channel ->
            val accepted = exchangeFrame(channel, request(LlmInvocationPhase.PROPOSER, firstSubmission))
            val conflict = exchangeFrame(
                channel,
                request(LlmInvocationPhase.PROPOSER, firstSubmission.copy(reasonJa = "changed")),
            )

            assertEquals("true", accepted.getValue("accepted").toString())
            assertEquals(DECISION_SUBMISSION_CONFLICT_CODE, conflict.getValue("error").jsonPrimitive.content)
        }

        assertEquals(LlmSemanticSubmissionState.COMMITTED, gateway.semanticSubmissionState())
        gateway.close()
    }

    @Test
    fun `disconnect without request preserves gateway for a later connection`() {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-empty-connection-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)

        connect(path).close()
        val response = connect(path).use { channel ->
            exchangeFrame(channel, request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE)))
        }

        assertEquals("true", response.getValue("accepted").toString())
        gateway.close()
    }

    @Test
    fun `first request releases completion wait while gateway remains open`() {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-completion-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)
        val waiter = Executors.newSingleThreadExecutor()
        val completion = waiter.submit<Boolean> {
            gateway.awaitCompletion()
            true
        }

        connect(path).use { channel ->
            exchangeFrame(channel, request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE)))
        }

        assertTrue(completion.get(1, TimeUnit.SECONDS))
        gateway.close()
        waiter.shutdownNow()
    }

    @Test
    fun `close without request releases completion and terminates worker`() {
        val path = Path.of("/tmp/fukurou-gateway-close-empty-${System.nanoTime()}.sock")
        val worker = Executors.newSingleThreadExecutor()
        val waiter = Executors.newSingleThreadExecutor()
        val gateway = gatewayWithHooks(
            path = path,
            hooks = LlmDecisionSubmissionGatewayStartHooks(createExecutor = { worker }),
        )
        val completion = waiter.submit<Boolean> {
            gateway.awaitCompletion()
            true
        }

        gateway.close()

        assertTrue(completion.get(1, TimeUnit.SECONDS))
        assertTrue(worker.isTerminated)
        assertFalse(Files.exists(path))
        assertFailsWith<IOException> { connect(path) }
        waiter.shutdownNow()
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
        assertEquals(LlmSemanticSubmissionState.REJECTED, gateway.semanticSubmissionState())
        gateway.close()
    }

    @Test
    fun `in flight repository transaction remains unknown when close times out`() = runBlocking {
        val delegate = InMemoryDecisionRepository()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = object : DecisionRepository by delegate {
            override suspend fun submitDecision(
                authority: DecisionSubmissionAuthority,
                submission: DecisionSubmission,
            ): Result<me.matsumo.fukurou.trading.decision.DecisionSubmissionResult> {
                entered.countDown()
                while (true) {
                    try {
                        if (release.await(5, TimeUnit.SECONDS)) break
                    } catch (_: InterruptedException) {
                        // A blocking repository transaction can outlive gateway shutdown interruption.
                    }
                }
                return delegate.submitDecision(authority, submission)
            }
        }
        val path = Path.of("/tmp/fukurou-gateway-race-${System.nanoTime()}.sock")
        val gateway = LlmDecisionSubmissionGateway.start(
            socketPath = path,
            repository = repository,
            invocationId = INVOCATION_ID,
            phase = LlmInvocationPhase.PROPOSER,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
        )
        val channel = connect(path)
        LlmSubmissionGatewayCodec.writeFrame(
            channel,
            request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE)),
        )
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        assertFailsWith<IllegalStateException> { gateway.close() }

        assertEquals(LlmSemanticSubmissionState.IN_FLIGHT, gateway.semanticSubmissionState())
        release.countDown()
        channel.close()
    }

    @Test
    fun `repository completion failure after commit remains unknown`() = runBlocking {
        val delegate = InMemoryDecisionRepository()
        val repository = object : DecisionRepository by delegate {
            override suspend fun submitDecision(
                authority: DecisionSubmissionAuthority,
                submission: DecisionSubmission,
            ): Result<me.matsumo.fukurou.trading.decision.DecisionSubmissionResult> {
                delegate.submitDecision(authority, submission).getOrThrow()

                return Result.failure(IOException("repository completion was lost"))
            }
        }
        val path = Path.of("/tmp/fukurou-gateway-ambiguous-${System.nanoTime()}.sock")
        val gateway = LlmDecisionSubmissionGateway.start(
            socketPath = path,
            repository = repository,
            invocationId = INVOCATION_ID,
            phase = LlmInvocationPhase.PROPOSER,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
        )

        val response = connect(path).use { channel ->
            LlmSubmissionGatewayCodec.writeFrame(
                channel,
                request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE)),
            )
            LlmSubmissionGatewayCodec.readFrame(channel)
        }

        assertEquals("false", response.getValue("accepted").toString())
        assertEquals(SubmissionRejectionCode.UNCLASSIFIED.wireValue, response.reason())
        assertFalse(response.toString().contains("repository completion was lost"))
        assertEquals(
            DecisionAction.NO_TRADE,
            delegate.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow()?.decision?.submission?.action,
        )
        assertEquals(LlmSemanticSubmissionState.IN_FLIGHT, gateway.semanticSubmissionState())
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
    fun `gateway preserves idempotent result and typed conflict unknown codes`() = runBlocking {
        val repository = InMemoryDecisionRepository()
        val submission = decision(DecisionAction.NO_TRADE)

        val first = exchangeDecision(repository, submission)
        val retry = exchangeDecision(repository, submission)
        val conflict = exchangeDecision(repository, submission.copy(reasonJa = "changed"))

        assertEquals(first.getValue("decision_id"), retry.getValue("decision_id"))
        assertEquals(DECISION_SUBMISSION_CONFLICT_CODE, conflict.getValue("error").jsonPrimitive.content)
        assertEquals(SubmissionRejectionCode.SUBMISSION_CONFLICT.wireValue, conflict.reason())
        assertEquals(1, repository.snapshots.decisions().size)

        val incompleteRepository = InMemoryDecisionRepository()
        incompleteRepository.seedIncompleteDecisionSubmissionAuthority(
            DecisionSubmissionAuthority(INVOCATION_ID, LlmInvocationPhase.PROPOSER),
            submission,
        )
        val unknown = exchangeDecision(incompleteRepository, submission)

        assertEquals(DECISION_SUBMISSION_UNKNOWN_CODE, unknown.getValue("error").jsonPrimitive.content)
        assertEquals(SubmissionRejectionCode.SUBMISSION_UNKNOWN.wireValue, unknown.reason())
        assertTrue(incompleteRepository.snapshots.decisions().isEmpty())
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
        assertEquals(SubmissionRejectionCode.RISK_INCREASING_ACTION_REJECTED.wireValue, response.reason())
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

    @Test
    fun `admission blocker rejects falsification before repository call`() {
        val repository = CountingDecisionRepository()
        LlmExecutionAdmissionHealth.registerRecoveryBlocker("blocked", "token")

        val response = exchangeRequest(
            repository = repository,
            phase = LlmInvocationPhase.FALSIFIER,
            request = falsificationRequest(LlmInvocationPhase.FALSIFIER),
        )

        assertEquals("false", response.getValue("accepted").toString())
        assertEquals(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE.wireValue, response.reason())
        assertEquals(0, repository.falsificationSubmissions)
    }

    @Test
    fun `admission blocker rejects risk increasing decision before repository call`() {
        val repository = CountingDecisionRepository()
        LlmExecutionAdmissionHealth.registerRecoveryBlocker("blocked", "token")

        val response = exchangeRequest(
            repository = repository,
            phase = LlmInvocationPhase.PROPOSER,
            request = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ENTER)),
        )

        assertEquals("false", response.getValue("accepted").toString())
        assertEquals(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE.wireValue, response.reason())
        assertEquals(0, repository.decisionSubmissions)
    }

    @Test
    fun `admission blocker permits exit reduce and no trade decisions`() = runBlocking {
        LlmExecutionAdmissionHealth.registerRecoveryBlocker("blocked", "token")

        listOf(DecisionAction.EXIT, DecisionAction.REDUCE, DecisionAction.NO_TRADE).forEach { action ->
            val repository = InMemoryDecisionRepository()
            val response = exchangeRequest(
                repository = repository,
                phase = LlmInvocationPhase.PROPOSER,
                request = request(LlmInvocationPhase.PROPOSER, decision(action)),
            )

            assertEquals("true", response.getValue("accepted").toString(), action.name)
            assertEquals(action, repository.latestDecisionByInvocationId(INVOCATION_ID).getOrThrow()?.decision?.submission?.action)
        }
    }

    @Test
    fun `admission blocker rejects adjust protection`() {
        val repository = CountingDecisionRepository()
        LlmExecutionAdmissionHealth.registerRecoveryBlocker("blocked", "token")

        val response = exchangeRequest(
            repository = repository,
            phase = LlmInvocationPhase.PROPOSER,
            request = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
        )

        assertEquals(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE.wireValue, response.reason())
        assertEquals(0, repository.decisionSubmissions)
    }

    @Test
    fun `binding mismatch wins over admission rejection`() {
        LlmExecutionAdmissionHealth.registerRecoveryBlocker("blocked", "token")
        val mismatched = request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ENTER))
            .withField("effectiveInvocationHash", JsonPrimitive("mismatch"))

        val response = exchangeRequest(InMemoryDecisionRepository(), LlmInvocationPhase.PROPOSER, mismatched)

        assertEquals(SubmissionRejectionCode.EFFECTIVE_HASH_MISMATCH.wireValue, response.reason())
    }

    @Test
    fun `admission rejection after commit preserves committed semantic state`() {
        val repository = InMemoryDecisionRepository()
        val path = Path.of("/tmp/fukurou-gateway-admission-committed-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, LlmInvocationPhase.PROPOSER)

        connect(path).use { channel ->
            assertEquals(
                "true",
                exchangeFrame(channel, request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.NO_TRADE)))
                    .getValue("accepted").toString(),
            )
            LlmExecutionAdmissionHealth.registerRecoveryBlocker("blocked", "token")
            val rejected = exchangeFrame(
                channel,
                request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
            )
            assertEquals(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE.wireValue, rejected.reason())
        }

        assertEquals(LlmSemanticSubmissionState.COMMITTED, gateway.semanticSubmissionState())
        gateway.close()
    }

    @Test
    fun `normal recovery scan in progress permits terminal submission but keeps launch health closed`() {
        LlmExecutionAdmissionHealth.beginRecoveryScan()

        val response = exchangeRequest(
            InMemoryDecisionRepository(),
            LlmInvocationPhase.PROPOSER,
            request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
        )

        assertEquals("true", response.getValue("accepted").toString())
        assertTrue(LlmExecutionAdmissionHealth.allowsTerminalSubmission())
        assertFalse(LlmExecutionAdmissionHealth.isHealthy())
    }

    @Test
    fun `failed recovery scan rejects risk increasing submission without blockers`() {
        LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)

        val response = exchangeRequest(
            InMemoryDecisionRepository(),
            LlmInvocationPhase.PROPOSER,
            request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
        )

        assertEquals(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE.wireValue, response.reason())
        assertTrue(LlmExecutionAdmissionHealth.unresolvedBlockers().isEmpty())
    }

    @Test
    fun `first successful recovery scan opens risk increasing submission`() {
        LlmExecutionAdmissionHealth.setRecoveryScanHealthy(false)
        val rejected = exchangeRequest(
            InMemoryDecisionRepository(),
            LlmInvocationPhase.PROPOSER,
            request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
        )
        LlmExecutionAdmissionHealth.beginRecoveryScan()
        LlmExecutionAdmissionHealth.completeRecoveryScan(successful = true)
        val accepted = exchangeRequest(
            InMemoryDecisionRepository(),
            LlmInvocationPhase.PROPOSER,
            request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
        )

        assertEquals(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE.wireValue, rejected.reason())
        assertEquals("true", accepted.getValue("accepted").toString())
    }

    @Test
    fun `completed uncertain proposer rejects falsifier through registry and gateway wiring`() {
        LlmProcessTreeTerminationRegistry.markChildStarted(INVOCATION_ID)
        LlmProcessTreeTerminationRegistry.record(INVOCATION_ID, ProcessTreeTerminationProof.UNCERTAIN)
        val repository = CountingDecisionRepository()

        val response = exchangeRequest(
            repository,
            LlmInvocationPhase.FALSIFIER,
            falsificationRequest(LlmInvocationPhase.FALSIFIER),
        )

        assertEquals(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE.wireValue, response.reason())
        assertEquals(0, repository.falsificationSubmissions)
    }

    @Test
    fun `resolved uncertain history does not reject a new gateway with the same invocation id`() {
        LlmProcessTreeTerminationRegistry.markChildStarted(INVOCATION_ID)
        LlmProcessTreeTerminationRegistry.record(INVOCATION_ID, ProcessTreeTerminationProof.UNCERTAIN)
        val rejected = exchangeRequest(
            InMemoryDecisionRepository(),
            LlmInvocationPhase.PROPOSER,
            request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
        )
        LlmProcessTreeTerminationRegistry.resolve(INVOCATION_ID)

        val accepted = exchangeRequest(
            InMemoryDecisionRepository(),
            LlmInvocationPhase.PROPOSER,
            request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
        )

        assertEquals(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE.wireValue, rejected.reason())
        assertEquals("true", accepted.getValue("accepted").toString())
    }

    @Test
    fun `running proposer is not rejected through registry and gateway wiring`() {
        LlmProcessTreeTerminationRegistry.markChildStarted(INVOCATION_ID)

        val response = exchangeRequest(
            InMemoryDecisionRepository(),
            LlmInvocationPhase.PROPOSER,
            request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
        )

        assertEquals("true", response.getValue("accepted").toString())
        assertFalse(LlmProcessTreeTerminationRegistry.hasCompletedUncertainChild(INVOCATION_ID))
    }

    @Test
    fun `uncertain history rejection does not mutate admission health`() {
        val blockersBefore = LlmExecutionAdmissionHealth.unresolvedBlockers()
        val submissionHealthBefore = LlmExecutionAdmissionHealth.allowsTerminalSubmission()
        LlmProcessTreeTerminationRegistry.markChildStarted(INVOCATION_ID)
        LlmProcessTreeTerminationRegistry.record(INVOCATION_ID, ProcessTreeTerminationProof.UNCERTAIN)

        val response = exchangeRequest(
            InMemoryDecisionRepository(),
            LlmInvocationPhase.PROPOSER,
            request(LlmInvocationPhase.PROPOSER, decision(DecisionAction.ADJUST_PROTECTION)),
        )

        assertEquals(SubmissionRejectionCode.EXECUTION_ADMISSION_UNAVAILABLE.wireValue, response.reason())
        assertEquals(blockersBefore, LlmExecutionAdmissionHealth.unresolvedBlockers())
        assertEquals(submissionHealthBefore, LlmExecutionAdmissionHealth.allowsTerminalSubmission())
        assertTrue(LlmExecutionAdmissionHealth.isHealthy())
    }

    private fun gateway(
        path: Path,
        repository: DecisionRepository,
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

    private fun exchangeFrame(channel: SocketChannel, request: JsonObject): JsonObject {
        LlmSubmissionGatewayCodec.writeFrame(channel, request)

        return LlmSubmissionGatewayCodec.readFrame(channel)
    }

    @Suppress("NestedBlockDepth")
    private fun withSocketPair(label: String, block: SocketPairBlock) {
        val path = Path.of("/tmp/fukurou-gateway-codec-$label-${System.nanoTime()}.sock")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(path))
                connect(path).use { client ->
                    server.accept().use { serverSide -> block(client, serverSide) }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun exchangeDecision(repository: InMemoryDecisionRepository, submission: DecisionSubmission): JsonObject {
        return exchangeRequest(
            repository = repository,
            phase = LlmInvocationPhase.PROPOSER,
            request = request(LlmInvocationPhase.PROPOSER, submission),
        )
    }

    private fun exchangeRequest(
        repository: DecisionRepository,
        phase: LlmInvocationPhase,
        request: JsonObject,
    ): JsonObject {
        val path = Path.of("/tmp/fukurou-gateway-exchange-${System.nanoTime()}.sock")
        val gateway = gateway(path, repository, phase)
        val response = connect(path).use { channel ->
            LlmSubmissionGatewayCodec.writeFrame(channel, request)
            LlmSubmissionGatewayCodec.readFrame(channel)
        }
        gateway.close()

        return response
    }

    private fun exchangeMalformedFrame(): JsonObject {
        val path = Path.of("/tmp/fukurou-gateway-malformed-frame-${System.nanoTime()}.sock")
        val gateway = gateway(path, InMemoryDecisionRepository(), LlmInvocationPhase.PROPOSER)
        val response = connect(path).use { channel ->
            val malformedJson = "{".encodeToByteArray()
            val frame = ByteBuffer.allocate(Int.SIZE_BYTES + malformedJson.size)
                .putInt(malformedJson.size)
                .put(malformedJson)
                .flip()
            while (frame.hasRemaining()) channel.write(frame)

            LlmSubmissionGatewayCodec.readFrame(channel)
        }
        gateway.close()

        return response
    }

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

    private fun falsificationRequest(phase: LlmInvocationPhase): JsonObject {
        val submission = FalsificationSubmission(
            intentId = null,
            verdict = FalsificationVerdict.REJECTED,
            llmProvider = "fixture",
            reasonJa = "fixture",
        )

        return LlmSubmissionGatewayCodec.request(
            operation = OPERATION_SUBMIT_FALSIFICATION,
            invocationId = INVOCATION_ID,
            phase = phase,
            phaseManifestId = PHASE_MANIFEST_ID,
            effectiveInvocationHash = EFFECTIVE_HASH,
            payload = LlmSubmissionGatewayCodec.encodeFalsification(submission),
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

private typealias SocketPairBlock = (client: SocketChannel, serverSide: SocketChannel) -> Unit

private fun JsonObject.withField(name: String, value: JsonPrimitive): JsonObject {
    return toMutableMap()
        .also { request -> request[name] = value }
        .let(::JsonObject)
}

private fun JsonObject.withField(name: String, value: JsonObject): JsonObject {
    return toMutableMap()
        .also { request -> request[name] = value }
        .let(::JsonObject)
}

private fun JsonObject.withoutField(name: String): JsonObject {
    return toMutableMap()
        .also { request -> request.remove(name) }
        .let(::JsonObject)
}

private fun JsonObject.reason(): String = getValue("reason").jsonPrimitive.content

private class CountingDecisionRepository(
    private val delegate: InMemoryDecisionRepository = InMemoryDecisionRepository(),
) : DecisionRepository by delegate {
    var decisionSubmissions = 0
    var falsificationSubmissions = 0

    override suspend fun submitDecision(
        authority: DecisionSubmissionAuthority,
        submission: DecisionSubmission,
    ): Result<me.matsumo.fukurou.trading.decision.DecisionSubmissionResult> {
        decisionSubmissions += 1
        return delegate.submitDecision(authority, submission)
    }

    override suspend fun submitFalsification(
        submission: FalsificationSubmission,
    ): Result<me.matsumo.fukurou.trading.decision.FalsificationRecord> {
        falsificationSubmissions += 1
        return delegate.submitFalsification(submission)
    }
}

private const val INVOCATION_ID = "gateway-test-invocation"
private const val PHASE_MANIFEST_ID = "gateway-test-invocation:PROPOSER"
private const val EFFECTIVE_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
