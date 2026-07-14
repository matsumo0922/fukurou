package me.matsumo.fukurou.trading.audit

import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.MaterialAccountSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.ln

/** manifest へ secret と unbounded text を入れない共通 policy。 */
object ManifestPersistencePolicy {
    const val MAX_PHASE_PROMPT_BYTES = 256 * 1024
    const val MAX_SCHEMA_BYTES = 256 * 1024
    const val MAX_RUNTIME_SNAPSHOT_BYTES = 256 * 1024
    const val MAX_MANIFEST_BYTES = 512 * 1024
    const val MAX_OBSERVED_IDENTITY_BYTES = 1024
    const val MAX_OBSERVED_MODEL_COUNT = 64
    const val MAX_OBSERVED_IDENTITIES_TOTAL_BYTES = 16 * 1024
    const val MAX_USAGE_DETAILS_BYTES = 64 * 1024
    const val MAX_AUDIT_DETAILS_BYTES = 256 * 1024
    const val MAX_COMMAND_EVENT_PAYLOAD_BYTES = 512 * 1024

    fun validateRun(manifest: LlmRunInputManifest, knownSecretValues: Set<String> = emptySet()) {
        validateBounded("run manifest", LlmManifestJsonCodec.encode(manifest), MAX_MANIFEST_BYTES)
        validateStrings(
            sequenceOf(
                manifest.invocationId,
                manifest.rootId,
                manifest.trigger.kind,
                manifest.trigger.notApplicableReason,
                manifest.runtimeConfigVersion,
                manifest.runtimeConfigHash,
                manifest.runtimeConfigSnapshot,
                manifest.materialInvocationId,
                manifest.materialContentHash,
                manifest.canonicalContentHash,
            ) + manifest.trigger.measurements.asSequence().flatMap { measurement ->
                sequenceOf(
                    measurement.metric,
                    measurement.measuredValue,
                    measurement.comparator,
                    measurement.threshold,
                    measurement.signedMargin,
                    measurement.unit,
                )
            } + manifest.trigger.entities.asSequence().flatMap { entity -> sequenceOf(entity.type, entity.id) },
            knownSecretValues,
        )
    }

    fun validatePhase(manifest: LlmPhaseInputManifest, knownSecretValues: Set<String> = emptySet()) {
        validateBounded("phase prompt", manifest.prompt, MAX_PHASE_PROMPT_BYTES)
        validateBounded("tool schema", manifest.canonicalToolSchema, MAX_SCHEMA_BYTES)
        validateBounded("runtime config snapshot", manifest.runtimeConfigSnapshot, MAX_RUNTIME_SNAPSHOT_BYTES)
        validateBounded("phase manifest", LlmManifestJsonCodec.encode(manifest), MAX_MANIFEST_BYTES)
        validateStrings(
            sequenceOf(
                manifest.phaseManifestId,
                manifest.rootId,
                manifest.invocationId,
                manifest.prompt,
                manifest.role,
                manifest.configuredModel,
                manifest.renderedEffort,
                manifest.cliVersion,
                manifest.canonicalToolSchema,
                manifest.runtimeConfigHash,
                manifest.runtimeConfigSnapshot,
                manifest.runManifestInvocationId,
                manifest.runManifestContentHash,
                manifest.materialInvocationId,
                manifest.materialContentHash,
                manifest.effectiveInvocationHash,
            ) + manifest.toolAllowlist.asSequence(),
            knownSecretValues,
        )
    }

    fun validateMaterial(manifest: DecisionMaterialStateManifest, knownSecretValues: Set<String> = emptySet()) {
        val bundle = manifest.marketFeatureBundle
        val values = sequenceOf(
            manifest.invocationId,
            manifest.symbol,
            manifest.runtimeConfigVersion,
            manifest.runtimeConfigHash,
            manifest.riskState,
            manifest.canonicalContentHash,
            manifest.snapshotContentHash,
            manifest.materialProjection,
        ) + manifest.openPositionFacts.asSequence() + manifest.openOrderFacts.asSequence() +
            manifest.missingSources.asSequence().flatMap { source -> sequenceOf(source.source, source.reason) } +
            (bundle?.indicators?.asSequence()?.map { indicator -> indicator.name } ?: emptySequence()) +
            (
                bundle?.missingSources?.asSequence()?.flatMap { source -> sequenceOf(source.source, source.reason) }
                    ?: emptySequence()
                ) +
            bundle.accountStrings()
        validateStrings(values, knownSecretValues)
        val totalBytes = values.filterNotNull().sumOf { value -> value.toByteArray(StandardCharsets.UTF_8).size.toLong() }
        require(totalBytes <= MAX_MANIFEST_BYTES) { "material manifest exceeds persistence limit." }
    }

    fun validateObservation(observation: LlmPhaseObservation, knownSecretValues: Set<String> = emptySet()) {
        validateBounded("phase observation", LlmManifestJsonCodec.encode(observation), MAX_MANIFEST_BYTES)
        val values = sequenceOf(observation.phaseManifestId, observation.observedEffort) +
            observation.observedModels.asSequence()
        require(observation.observedModels.size <= MAX_OBSERVED_MODEL_COUNT) {
            "Observed model count exceeds persistence limit."
        }
        validateAggregateBytes(
            label = "observed identities",
            values = values,
            maximumBytes = MAX_OBSERVED_IDENTITIES_TOTAL_BYTES,
        )
        values.filterNotNull().forEach { value ->
            validateBounded("observed identity", value, MAX_OBSERVED_IDENTITY_BYTES)
        }
        validateStrings(values, knownSecretValues)
    }

    /** command event の固定列と payload を field ごとの DB contract で検証する。 */
    fun validateCommandEvent(
        context: DecisionRunContext,
        toolName: String,
        clientRequestId: String?,
        payload: String,
        knownSecretValues: Set<String> = emptySet(),
    ) {
        validateCommandEventField(
            label = "decision run ID",
            value = context.decisionRunId,
            maximumBytes = 128,
            grammar = EVENT_IDENTITY,
            knownSecretValues = knownSecretValues,
            highEntropyExemption = SAFE_PHASE_RUN_ID,
        )
        validateCommandEventField("LLM provider", context.llmProvider, 64, PROVIDER_IDENTITY, knownSecretValues)
        validateCommandEventField("prompt hash", context.promptHash, 128, EVENT_IDENTITY, knownSecretValues)
        validateCommandEventField(
            label = "system prompt version",
            value = context.systemPromptVersion,
            maximumBytes = 128,
            grammar = EVENT_IDENTITY,
            knownSecretValues = knownSecretValues,
            highEntropyExemption = SAFE_SYSTEM_PROMPT_VERSION,
        )
        validateCommandEventField(
            label = "market snapshot ID",
            value = context.marketSnapshotId,
            maximumBytes = 128,
            grammar = EVENT_IDENTITY,
            knownSecretValues = knownSecretValues,
            highEntropyExemption = SAFE_REFLECTION_SNAPSHOT_ID,
        )
        validateCommandEventField(
            "runtime config version ID",
            context.runtimeConfigVersionId,
            64,
            EVENT_IDENTITY,
            knownSecretValues,
        )
        validateCommandEventField("runtime config hash", context.runtimeConfigHash, 64, EVENT_IDENTITY, knownSecretValues)
        validateCommandEventField("tool name", toolName, 128, TOOL_IDENTITY, knownSecretValues)
        validateCommandEventField("client request ID", clientRequestId, 128, EVENT_IDENTITY, knownSecretValues)
        validateBounded("command event payload", payload, MAX_COMMAND_EVENT_PAYLOAD_BYTES)
    }

    /** usage と audit details の collection/serialized size contract を検証する。 */
    fun validateUsageDetails(
        observedModels: List<String>,
        serializedUsage: String?,
        serializedDetails: String,
    ) {
        require(observedModels.size <= MAX_OBSERVED_MODEL_COUNT) {
            "Observed model count exceeds persistence limit."
        }
        validateAggregateBytes(
            label = "observed model identities",
            values = observedModels.asSequence(),
            maximumBytes = MAX_OBSERVED_IDENTITIES_TOTAL_BYTES,
        )
        serializedUsage?.let { usage -> validateBounded("serialized usage", usage, MAX_USAGE_DETAILS_BYTES) }
        validateBounded("audit details", serializedDetails, MAX_AUDIT_DETAILS_BYTES)
    }

    fun validatePersistedStrings(vararg values: String?) {
        values.forEachIndexed { index, value ->
            try {
                validateStrings(sequenceOf(value), emptySet())
            } catch (throwable: IllegalArgumentException) {
                throw IllegalArgumentException("Persisted string policy rejected field index $index.", throwable)
            }
        }
    }

    fun validateObservedIdentityStrings(vararg values: String?) {
        values.filterNotNull().forEach { value ->
            validateBounded("observed identity", value, MAX_OBSERVED_IDENTITY_BYTES)
        }
        validatePersistedStrings(*values)
    }

    fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun validateBounded(
        label: String,
        value: String,
        maximumBytes: Int,
    ) {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes) { "$label exceeds persistence limit." }
    }

    private fun validateAggregateBytes(
        label: String,
        values: Sequence<String?>,
        maximumBytes: Int,
    ) {
        val totalBytes = values.filterNotNull()
            .sumOf { value -> value.toByteArray(StandardCharsets.UTF_8).size.toLong() }
        require(totalBytes <= maximumBytes) { "$label exceed persistence limit." }
    }

    private fun validateCommandEventField(
        label: String,
        value: String?,
        maximumBytes: Int,
        grammar: Regex,
        knownSecretValues: Set<String>,
        highEntropyExemption: Regex? = null,
    ) {
        if (value == null) return
        validateBounded(label, value, maximumBytes)
        require(grammar.matches(value)) { "$label grammar rejected a value." }
        validateSecretFree(
            value = value,
            knownSecretValues = knownSecretValues,
            highEntropyExempt = highEntropyExemption?.matches(value) == true,
        )
    }

    private fun validateStrings(values: Sequence<String?>, knownSecretValues: Set<String>) {
        values.filterNotNull().forEach { value ->
            validateBounded("manifest string", value, MAX_MANIFEST_BYTES)
            validateSecretFree(value, knownSecretValues)
        }
    }

    private fun validateSecretFree(
        value: String,
        knownSecretValues: Set<String>,
        highEntropyExempt: Boolean = false,
    ) {
        val containsKnownSecret = knownSecretValues.any { secret -> secret.isNotEmpty() && value.contains(secret) }
        val containsPatternSecret = FORBIDDEN_SECRET_PATTERNS.any { pattern -> pattern.containsMatchIn(value) }
        val containsHighEntropySecret = !highEntropyExempt && HIGH_ENTROPY_TOKEN.findAll(value).any { match ->
            match.value.looksLikeUnstructuredSecret()
        }
        require(!containsKnownSecret) { "Manifest persistence known-secret policy rejected a value." }
        require(!containsPatternSecret) { "Manifest persistence secret-pattern policy rejected a value." }
        require(!containsHighEntropySecret) { "Manifest persistence entropy policy rejected a value." }
    }
}

private val FORBIDDEN_SECRET_PATTERNS = listOf(
    Regex("(?i)authorization\\s*[:=]\\s*bearer\\s+[^\\s]+"),
    Regex(
        "(?i)(password|api[_-]?(?:key|secret)|access[_-]?token|refresh[_-]?token|private[_-]?key)" +
            "\\s*[:=]\\s*[^,}\\s]+",
    ),
    Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
    Regex("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
    Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
)

private val HIGH_ENTROPY_TOKEN = Regex("[A-Za-z0-9_+/=-]{24,}")
private const val MIN_HIGH_ENTROPY_TOKEN_LENGTH = 24
private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val PREFIXED_UUID_TEXT = Regex("[A-Za-z0-9_-]+-${UUID_TEXT.pattern}")

private fun String.looksLikeUnstructuredSecret(): Boolean {
    if (isCanonicalIdentity()) return false
    if (isSafeTypedIdentity()) return false
    if (matches(SAFE_CLI_REVISION) || matches(SAFE_CLI_FINGERPRINT)) return false
    if (isSafeStructuredConfigIdentity()) return false
    val classes = listOf(any(Char::isLowerCase), any(Char::isUpperCase), any(Char::isDigit), any { it in "_+/=-" })
        .count { present -> present }
    if (classes < 3) return false
    val entropy = groupingBy { character -> character }.eachCount().values.sumOf { count ->
        val probability = count.toDouble() / length
        -probability * (ln(probability) / ln(2.0))
    }

    return entropy >= 3.5
}

private fun String.isCanonicalIdentity(): Boolean {
    return matches(SHA256_HEX) || matches(UUID_TEXT) || matches(PREFIXED_UUID_TEXT)
}

private fun String.isSafeTypedIdentity(): Boolean {
    val prefix = SAFE_TYPED_IDENTITY_PREFIXES.firstOrNull(::startsWith) ?: return false
    val suffix = removePrefix(prefix)
    if (suffix.isBlank()) return false
    if (suffix.length < MIN_HIGH_ENTROPY_TOKEN_LENGTH) return true

    return !suffix.looksLikeUnstructuredSecret()
}

private fun String.isSafeStructuredConfigIdentity(): Boolean {
    val structuredValue = substringAfter('=', missingDelimiterValue = "")
    if (structuredValue.matches(SHA256_HEX) || structuredValue.matches(UUID_TEXT)) return true
    if (matches(SAFE_FUKUROU_CONFIG_KEY)) return true
    if (endsWith("=") && count { character -> character == '_' } >= 2) return true
    if (!substringBefore('=').matches(SAFE_FUKUROU_CONFIG_KEY)) return false
    if (structuredValue.isEmpty()) return false

    return !structuredValue.looksLikeUnstructuredSecret()
}

private val SAFE_CLI_REVISION = Regex("revision=llm-cli-command-v[0-9]+")
private val SAFE_CLI_FINGERPRINT = Regex("fingerprint=(?:UNKNOWN|(?:sha256:)?[0-9a-f]{64})")
private val SAFE_FUKUROU_CONFIG_KEY = Regex("FUKUROU_[A-Z0-9_]{1,96}")
private val SAFE_TYPED_IDENTITY_PREFIXES = listOf("manual-")
private val EVENT_IDENTITY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val TOOL_IDENTITY = Regex("[A-Za-z][A-Za-z0-9._:-]{0,127}")
private val PROVIDER_IDENTITY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
private val SAFE_SYSTEM_PROMPT_VERSION = Regex(
    "[A-Za-z][A-Za-z0-9]*(?:[-.][A-Za-z0-9]+)*-v[0-9]+(?:\\.[0-9]+)*",
)
private val SAFE_REFLECTION_SNAPSHOT_ID = Regex("reflection-[0-9]{4}-W[0-9]{2}")
private val SAFE_PHASE_RUN_ID = Regex("run-[A-Za-z][A-Za-z0-9_]{0,31}-[0-9]{10,17}")

private fun me.matsumo.fukurou.trading.decision.identity.MarketFeatureBundle?.accountStrings(): Sequence<String?> {
    val account = this?.account ?: return emptySequence()

    return account.strings() + sequenceOf(
        ticker?.metadata?.provenance,
        orderbookSummary?.metadata?.provenance,
    )
}

private fun MaterialAccountSnapshot.strings(): Sequence<String?> = sequenceOf(riskState) +
    positions.asSequence().flatMap { fact -> sequenceOf(fact.id, fact.status, fact.side, fact.type) } +
    openOrders.asSequence().flatMap { fact -> sequenceOf(fact.id, fact.status, fact.side, fact.type) } +
    sequenceOf(positionMetadata.provenance, orderMetadata.provenance)
