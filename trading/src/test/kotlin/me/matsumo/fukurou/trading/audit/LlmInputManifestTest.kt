package me.matsumo.fukurou.trading.audit

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.DecisionTriggerKind
import me.matsumo.fukurou.trading.decision.identity.MarketFeatureBundle
import me.matsumo.fukurou.trading.decision.identity.MaterialAccountSnapshot
import me.matsumo.fukurou.trading.decision.identity.MaterialFreshness
import me.matsumo.fukurou.trading.decision.identity.MaterialMissingSource
import me.matsumo.fukurou.trading.decision.identity.MaterialSourceMetadata
import me.matsumo.fukurou.trading.decision.identity.MaterialTickerSnapshot
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmInvocationRequest
import me.matsumo.fukurou.trading.invoker.LlmProvider
import me.matsumo.fukurou.trading.invoker.McpToolContractCatalog
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LlmInputManifestTest {
    @Test
    fun append_isIdempotentForSameContentAndRejectsMismatch() = runBlocking {
        val repository = InMemoryLlmInputManifestRepository()
        val capturedAt = Instant.parse("2026-07-13T00:00:00Z")
        val root = LlmInvocationAuditRoot("run-1", LlmAuditRootKind.DECISION_ATTEMPT, capturedAt)

        repository.appendRoot(root).getOrThrow()
        repository.appendRoot(root).getOrThrow()

        assertFailsWith<IllegalArgumentException> {
            repository.appendRoot(root.copy(kind = LlmAuditRootKind.REFLECTION)).getOrThrow()
        }
        assertEquals(root, repository.findRoot("run-1").getOrThrow())
    }

    @Test
    fun phaseHash_bindsEffectiveInputAndSecretPolicyRejectsCredentialLikePrompt() {
        val phase = phaseManifest(prompt = "bounded prompt")
        val hashed = phase.copy(effectiveInvocationHash = LlmManifestJsonCodec.effectiveInvocationHash(phase))

        ManifestPersistencePolicy.validatePhase(hashed)
        assertEquals(hashed.effectiveInvocationHash, LlmManifestJsonCodec.effectiveInvocationHash(hashed))
        assertFailsWith<IllegalArgumentException> {
            ManifestPersistencePolicy.validatePhase(
                hashed.copy(prompt = runtimeFixture("Authoriz", "ation: B", "earer se", "cret-val", "ue")),
            )
        }
    }

    @Test
    fun secretPolicy_rejectsKnownSecretAcrossRunMaterialAndPhaseWithoutEchoingValue() {
        val secret = runtimeFixture("DbCreden", "tial_7kN", "2pQ9xV4m", "Z8rT6")
        val failures = listOf(
            runCatching {
                ManifestPersistencePolicy.validateRun(runManifest(triggerKind = secret), setOf(secret))
            }.exceptionOrNull(),
            runCatching {
                ManifestPersistencePolicy.validateMaterial(materialManifest(secret), setOf(secret))
            }.exceptionOrNull(),
            runCatching {
                ManifestPersistencePolicy.validatePhase(phaseManifest(prompt = secret), setOf(secret))
            }.exceptionOrNull(),
        )

        failures.forEach { failure ->
            assertNotNull(failure)
            assertFalse(failure.message.orEmpty().contains(secret))
        }
    }

    @Test
    fun secretPolicy_rejectsJwtApiKeyAndHighEntropyTokens() {
        val unsafeValues = listOf(
            jwtDetectorFixture(),
            apiKeyDetectorFixture(),
            highEntropyDetectorFixture(),
        )

        unsafeValues.forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) {
                ManifestPersistencePolicy.validatePhase(phaseManifest(prompt = value))
            }
        }
    }

    @Test
    fun materialProvenance_allowsOnlyExactCodeOwnedEntropyValues() {
        val allowed = listOf(
            "GMO_PUBLIC_TICKER",
            "GMO_PUBLIC_ORDERBOOK_TOP10",
            "IN_MEMORY_LEDGER_MUTEX",
            "POSTGRES_REPEATABLE_READ_READ_ONLY",
        )
        allowed.forEach { provenance ->
            ManifestPersistencePolicy.validateMaterial(materialManifestWithProvenance(provenance))
        }

        val unsafeValues = listOf(
            highEntropyDetectorFixture(),
            apiKeyDetectorFixture(),
        )
        unsafeValues.forEach { provenance ->
            assertFailsWith<IllegalArgumentException> {
                ManifestPersistencePolicy.validateMaterial(materialManifestWithProvenance(provenance))
            }
        }

        val knownSecret = runtimeFixture("KnownCre", "dential_", "7kN2pQ9x", "V4mZ8rT6")
        assertFailsWith<IllegalArgumentException> {
            ManifestPersistencePolicy.validateMaterial(
                materialManifestWithProvenance(knownSecret),
                setOf(knownSecret),
            )
        }
    }

    @Test
    fun standardSnapshotStageCodesPassMaterialPolicyAndUnknownCodeIsRejected() {
        val reasons = listOf(
            "STANDARD_SNAPSHOT_CAPTURE_FAILED",
            "STANDARD_SNAPSHOT_VALIDATION_FAILED",
            "STANDARD_SNAPSHOT_HASH_SERIALIZATION_FAILED",
            "STANDARD_SNAPSHOT_PERSISTENCE_FAILED",
        )
        reasons.forEach { reason ->
            val missing = MaterialMissingSource("STANDARD_CONTEXT", reason)
            val manifest = materialManifestWithProvenance("GMO_PUBLIC_TICKER").copy(
                missingSources = listOf(missing),
                marketFeatureBundle = materialManifestWithProvenance("GMO_PUBLIC_TICKER")
                    .marketFeatureBundle
                    ?.copy(missingSources = listOf(missing)),
            )

            ManifestPersistencePolicy.validateMaterial(manifest)
        }

        val unknown = MaterialMissingSource("STANDARD_CONTEXT", "STANDARD_SNAPSHOT_UNKNOWN_FAILED")
        assertFailsWith<IllegalArgumentException> {
            ManifestPersistencePolicy.validateMaterial(
                materialManifestWithProvenance("GMO_PUBLIC_TICKER").copy(missingSources = listOf(unknown)),
            )
        }
    }

    @Test
    fun secretPolicy_acceptsCanonicalHashesUuidsConfigKeysAndWrapperIdentity() {
        val safeValues = listOf(
            "a".repeat(64),
            runtimeFixture("123e4567", "-e89b-12", "d3-a456-", "42661417", "4000"),
            runtimeFixture("run-entr", "y-123e45", "67-e89b-", "12d3-a45", "6-426614", "174000"),
            runtimeFixture("FUKUROU_", "LLM_PROV", "IDER"),
            runtimeFixture("revision", "=llm-cli", "-command", "-v1;temp", "late=/ap", "p/bin/fu", "kurou-cl", "aude --v", "ersion;") +
                runtimeFixture("fingerpr", "int=sha2", "56:") + "b".repeat(64) + runtimeFixture(";version", "=2.3.4"),
        )

        safeValues.forEach { value ->
            ManifestPersistencePolicy.validatePhase(phaseManifest(prompt = value))
        }
    }

    @Test
    fun observation_roundTripsTypedCoverageWithoutConfiguredIdentityInference() {
        val observation = LlmPhaseObservation(
            phaseManifestId = "run-1:PROPOSER",
            observedModels = emptyList(),
            observedEffort = null,
            modelCoverageStatus = LlmIdentityCoverageStatus.NOT_REPORTED_BY_PROVIDER,
            effortCoverageStatus = LlmIdentityCoverageStatus.NOT_REPORTED_BY_PROVIDER,
            terminatedAt = Instant.parse("2026-07-13T00:00:01Z"),
        )

        val decoded = LlmManifestJsonCodec.decodeObservation(LlmManifestJsonCodec.encode(observation))

        assertNotNull(decoded)
        assertEquals(observation, decoded)
    }

    @Test
    fun observationPolicy_rejectsKnownEntropyPatternAndOversizeWithoutEchoingValues() {
        val known = runtimeFixture("KnownCre", "dential_", "7kN2pQ9x", "V4mZ8rT6")
        val unsafeValues = listOf(
            known,
            jwtDetectorFixture(),
            apiKeyDetectorFixture(),
            highEntropyDetectorFixture(),
            "x".repeat(ManifestPersistencePolicy.MAX_OBSERVED_IDENTITY_BYTES + 1),
        )

        unsafeValues.forEach { value ->
            val failure = runCatching {
                ManifestPersistencePolicy.validateObservation(
                    observation(value),
                    setOf(known),
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertFalse(failure.message.orEmpty().contains(value))
        }
    }

    @Test
    fun persistencePolicy_boundsModelCollectionsUsageDetailsAndCommandPayload() {
        val tooManyModels = (0..ManifestPersistencePolicy.MAX_OBSERVED_MODEL_COUNT)
            .map { index -> "model-$index" }
        val aggregateOversizeModels = (0 until ManifestPersistencePolicy.MAX_OBSERVED_MODEL_COUNT)
            .map { index -> "model-$index-${"a".repeat(260)}" }

        listOf(tooManyModels, aggregateOversizeModels).forEach { models ->
            assertFailsWith<IllegalArgumentException> {
                ManifestPersistencePolicy.validateObservation(
                    LlmPhaseObservation(
                        phaseManifestId = "run-1:PROPOSER",
                        observedModels = models,
                        observedEffort = null,
                        modelCoverageStatus = LlmIdentityCoverageStatus.OBSERVED,
                        effortCoverageStatus = LlmIdentityCoverageStatus.NOT_REPORTED_BY_PROVIDER,
                        terminatedAt = Instant.EPOCH,
                    ),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ManifestPersistencePolicy.validateUsageDetails(
                observedModels = emptyList(),
                serializedUsage = "x".repeat(ManifestPersistencePolicy.MAX_USAGE_DETAILS_BYTES + 1),
                serializedDetails = "{}",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ManifestPersistencePolicy.validateUsageDetails(
                observedModels = emptyList(),
                serializedUsage = null,
                serializedDetails = "x".repeat(ManifestPersistencePolicy.MAX_AUDIT_DETAILS_BYTES + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ManifestPersistencePolicy.validateCommandEvent(
                context = DecisionRunContext.EMPTY,
                toolName = "one_shot_runner",
                clientRequestId = null,
                payload = "x".repeat(ManifestPersistencePolicy.MAX_COMMAND_EVENT_PAYLOAD_BYTES + 1),
            )
        }
    }

    @Test
    fun structuredIdentityExemptions_validateTheirValueFormats() {
        val unsafeValues = listOf(
            runtimeFixture("revision", "=aB3dE5f", "G7hJ9kL2", "mN4pQ6rS", "8tV0xY2z"),
            runtimeFixture("fingerpr", "int=aB3d", "E5fG7hJ9", "kL2mN4pQ", "6rS8tV0x", "Y2z"),
            runtimeFixture("FUKUROU_", "LLM_PROV", "IDER=aB3", "dE5fG7hJ", "9kL2mN4p", "Q6rS8tV0", "xY2z"),
        )

        unsafeValues.forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                ManifestPersistencePolicy.validatePhase(phaseManifest(prompt = value))
            }
        }
        assertTrue(
            runCatching {
                ManifestPersistencePolicy.validatePhase(
                    phaseManifest(prompt = runtimeFixture("revision", "=llm-cli", "-command", "-v1;fing", "erprint=", "sha256:") + "b".repeat(64) + runtimeFixture()),
                )
            }.isSuccess,
        )
    }

    @Test
    fun phaseRecorder_classifiesCatalogMismatchAsTypedPreLaunchFailure() = runBlocking {
        val recorder = LlmPhaseManifestRecorder(
            repository = InMemoryLlmInputManifestRepository(),
            cliVersionProbe = { Result.success("fixture-cli 1.0") },
            runtimeConfigSnapshot = null,
            runtimeEnvironmentSnapshot = "MODE=PAPER",
            clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC),
        )
        val request = LlmInvocationRequest(
            invocationId = "catalog-mismatch",
            provider = LlmProvider.CLAUDE,
            phase = LlmInvocationPhase.PRE_FILTER,
            prompt = "bounded prompt",
            timeout = Duration.ofSeconds(10),
            workingDirectory = Path.of(".").toAbsolutePath(),
            decisionRunContext = DecisionRunContext.EMPTY,
            mcpServer = null,
            environment = emptyMap(),
            toolPolicy = me.matsumo.fukurou.trading.invoker.ToolPolicy(emptySet(), listOf("unknown_tool")),
        )

        assertFailsWith<LlmPhaseInputCaptureException> { recorder.appendInput(request) }
        Unit
    }

    private fun observation(model: String): LlmPhaseObservation {
        return LlmPhaseObservation(
            phaseManifestId = "run-1:PROPOSER",
            observedModels = listOf(model),
            observedEffort = null,
            modelCoverageStatus = LlmIdentityCoverageStatus.OBSERVED,
            effortCoverageStatus = LlmIdentityCoverageStatus.NOT_REPORTED_BY_PROVIDER,
            terminatedAt = Instant.parse("2026-07-13T00:00:01Z"),
        )
    }

    @Test
    fun toolCatalogHash_changesWhenRealRegistrationSchemaChanges() {
        val originalSchemas = McpToolContractCatalog.schemaJsonByTool()
        val changedSchemas = originalSchemas + (
            "get_balance" to """{"properties":{"revision":{"type":"string"}},"type":"object"}"""
            )

        val original = McpToolContractCatalog.canonicalSchemaBundle(LlmInvocationPhase.PROPOSER, originalSchemas)
        val changed = McpToolContractCatalog.canonicalSchemaBundle(LlmInvocationPhase.PROPOSER, changedSchemas)

        assertNotEquals(original, changed)
    }

    private fun phaseManifest(prompt: String): LlmPhaseInputManifest {
        return LlmPhaseInputManifest(
            phaseManifestId = "run-1:PROPOSER",
            rootId = "run-1",
            invocationId = "run-1",
            phase = LlmInvocationPhase.PROPOSER,
            prompt = prompt,
            role = "PROPOSER",
            provider = LlmProvider.CLAUDE,
            configuredModel = "fixture-model",
            configuredEffort = LlmEffort.DEFAULT,
            renderedEffort = null,
            cliVersion = "fixture 1.0",
            toolAllowlist = listOf("submit_decision"),
            canonicalToolSchema = "fixture-schema",
            runtimeConfigHash = null,
            runtimeConfigSnapshot = "MODE=PAPER",
            runManifestInvocationId = "run-1",
            runManifestContentHash = "a".repeat(64),
            materialInvocationId = "run-1",
            materialContentHash = "b".repeat(64),
            notApplicableReason = null,
            capturedAt = Instant.parse("2026-07-13T00:00:00Z"),
            effectiveInvocationHash = "",
        )
    }

    private fun runManifest(triggerKind: String): LlmRunInputManifest {
        return LlmRunInputManifest(
            invocationId = "run-1",
            rootId = "run-1",
            trigger = LlmRunTriggerSnapshot(triggerKind, Instant.EPOCH, emptyList(), emptyList(), null),
            runtimeConfigVersion = null,
            runtimeConfigHash = null,
            runtimeConfigSnapshot = "MODE=PAPER",
            materialInvocationId = "run-1",
            materialContentHash = "b".repeat(64),
            schemaVersion = 2,
            capturedAt = Instant.EPOCH,
            canonicalContentHash = "a".repeat(64),
        )
    }

    private fun materialManifest(materialProjection: String): DecisionMaterialStateManifest {
        return DecisionMaterialStateManifest(
            invocationId = "run-1",
            capturedAt = Instant.EPOCH,
            triggerKind = DecisionTriggerKind.MANUAL,
            symbol = "BTC_JPY",
            runtimeConfigVersion = null,
            runtimeConfigHash = null,
            riskState = "RUNNING",
            bestBidJpy = BigDecimal.ONE,
            bestAskJpy = BigDecimal.ONE,
            lastPriceJpy = BigDecimal.ONE,
            sourceTimestamp = Instant.EPOCH,
            freshness = MaterialFreshness.FRESH,
            atr14FiveMinutesJpy = null,
            latestCandleOpenJpy = null,
            latestCandleHighJpy = null,
            latestCandleLowJpy = null,
            latestCandleCloseJpy = null,
            openPositionFacts = emptyList(),
            openOrderFacts = emptyList(),
            missingSources = emptyList(),
            canonicalContentHash = "b".repeat(64),
            materialProjection = materialProjection,
        )
    }

    private fun materialManifestWithProvenance(provenance: String): DecisionMaterialStateManifest {
        val metadata = MaterialSourceMetadata(
            observedAt = Instant.EPOCH,
            provenance = provenance,
            truncated = false,
            totalCount = 1,
        )
        val account = MaterialAccountSnapshot(
            riskState = "RUNNING",
            availableJpy = BigDecimal.ONE,
            equityJpy = BigDecimal.ONE,
            positions = emptyList(),
            openOrders = emptyList(),
            positionMetadata = metadata.copy(provenance = "IN_MEMORY_LEDGER_MUTEX"),
            orderMetadata = metadata.copy(provenance = "IN_MEMORY_LEDGER_MUTEX"),
        )

        return materialManifest("").copy(
            schemaVersion = 2,
            marketFeatureBundle = MarketFeatureBundle(
                ticker = MaterialTickerSnapshot(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, metadata),
                candleSummaries = emptyList(),
                indicators = emptyList(),
                orderbookSummary = null,
                account = account,
                missingSources = emptyList(),
            ),
        )
    }
}

private fun jwtDetectorFixture(): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
    val payload = encoder.encodeToString("""{"sub":"fixture-subject","iat":1700000000}""".encodeToByteArray())
    val signature = encoder.encodeToString(ByteArray(32) { index -> (index * 7 + 3).toByte() })

    return listOf(header, payload, signature).joinToString(".")
}

private fun apiKeyDetectorFixture(): String {
    val prefix = charArrayOf('s', 'k', '-', 'p', 'r', 'o', 'j', '-').concatToString()

    return prefix + highEntropyDetectorFixture()
}

private fun highEntropyDetectorFixture(): String {
    val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_')

    return buildString {
        repeat(48) { index -> append(alphabet[(index * 17 + 11) % alphabet.size]) }
    }
}

private fun runtimeFixture(vararg parts: String): String {
    return parts.joinToString("")
}
