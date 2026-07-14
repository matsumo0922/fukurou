package me.matsumo.fukurou.trading.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.invoker.LlmEffort
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.LlmProvider
import java.time.Instant

/** Stage 1 manifest の versioned canonical JSON codec。 */
object LlmManifestJsonCodec {
    const val VERSION = 1

    fun contentHash(manifest: LlmRunInputManifest): String =
        ManifestPersistencePolicy.sha256(encode(manifest.copy(canonicalContentHash = "")))

    fun effectiveInvocationHash(manifest: LlmPhaseInputManifest): String =
        ManifestPersistencePolicy.sha256(encode(manifest.copy(effectiveInvocationHash = "")))

    fun encode(manifest: LlmRunInputManifest): String = buildJsonObject {
        put("version", VERSION)
        put("invocationId", manifest.invocationId)
        put("rootId", manifest.rootId)
        put("trigger", manifest.trigger.toJson())
        putNullable("runtimeConfigVersion", manifest.runtimeConfigVersion)
        putNullable("runtimeConfigHash", manifest.runtimeConfigHash)
        put("runtimeConfigSnapshot", manifest.runtimeConfigSnapshot)
        put("materialInvocationId", manifest.materialInvocationId)
        put("materialContentHash", manifest.materialContentHash)
        put("schemaVersion", manifest.schemaVersion)
        put("capturedAt", manifest.capturedAt.toString())
        put("canonicalContentHash", manifest.canonicalContentHash)
    }.canonicalString()

    fun decodeRun(value: String): LlmRunInputManifest {
        val fields = parse(value)
        require(fields.int("version") == VERSION) { "Unsupported run manifest version." }

        return LlmRunInputManifest(
            invocationId = fields.text("invocationId"),
            rootId = fields.text("rootId"),
            trigger = fields.getValue("trigger").jsonObject.toTrigger(),
            runtimeConfigVersion = fields.optionalText("runtimeConfigVersion"),
            runtimeConfigHash = fields.optionalText("runtimeConfigHash"),
            runtimeConfigSnapshot = fields.text("runtimeConfigSnapshot"),
            materialInvocationId = fields.text("materialInvocationId"),
            materialContentHash = fields.text("materialContentHash"),
            schemaVersion = fields.int("schemaVersion"),
            capturedAt = Instant.parse(fields.text("capturedAt")),
            canonicalContentHash = fields.text("canonicalContentHash"),
        )
    }

    fun encode(manifest: LlmPhaseInputManifest): String = buildJsonObject {
        put("version", VERSION)
        put("phaseManifestId", manifest.phaseManifestId)
        put("rootId", manifest.rootId)
        put("invocationId", manifest.invocationId)
        put("phase", manifest.phase.name)
        put("prompt", manifest.prompt)
        put("role", manifest.role)
        put("provider", manifest.provider.name)
        putNullable("configuredModel", manifest.configuredModel)
        put("configuredEffort", manifest.configuredEffort.name)
        putNullable("renderedEffort", manifest.renderedEffort)
        put("cliVersion", manifest.cliVersion)
        put("toolAllowlist", manifest.toolAllowlist.toJsonArray())
        put("canonicalToolSchema", manifest.canonicalToolSchema)
        putNullable("runtimeConfigHash", manifest.runtimeConfigHash)
        put("runtimeConfigSnapshot", manifest.runtimeConfigSnapshot)
        putNullable("runManifestInvocationId", manifest.runManifestInvocationId)
        putNullable("runManifestContentHash", manifest.runManifestContentHash)
        putNullable("materialInvocationId", manifest.materialInvocationId)
        putNullable("materialContentHash", manifest.materialContentHash)
        putNullable("notApplicableReason", manifest.notApplicableReason?.name)
        put("capturedAt", manifest.capturedAt.toString())
        put("effectiveInvocationHash", manifest.effectiveInvocationHash)
    }.canonicalString()

    fun decodePhase(value: String): LlmPhaseInputManifest {
        val fields = parse(value)
        require(fields.int("version") == VERSION) { "Unsupported phase manifest version." }

        return LlmPhaseInputManifest(
            phaseManifestId = fields.text("phaseManifestId"),
            rootId = fields.text("rootId"),
            invocationId = fields.text("invocationId"),
            phase = LlmInvocationPhase.valueOf(fields.text("phase")),
            prompt = fields.text("prompt"),
            role = fields.text("role"),
            provider = LlmProvider.valueOf(fields.text("provider")),
            configuredModel = fields.optionalText("configuredModel"),
            configuredEffort = LlmEffort.valueOf(fields.text("configuredEffort")),
            renderedEffort = fields.optionalText("renderedEffort"),
            cliVersion = fields.text("cliVersion"),
            toolAllowlist = fields.getValue("toolAllowlist").jsonArray.map { it.jsonPrimitive.content },
            canonicalToolSchema = fields.text("canonicalToolSchema"),
            runtimeConfigHash = fields.optionalText("runtimeConfigHash"),
            runtimeConfigSnapshot = fields.text("runtimeConfigSnapshot"),
            runManifestInvocationId = fields.optionalText("runManifestInvocationId"),
            runManifestContentHash = fields.optionalText("runManifestContentHash"),
            materialInvocationId = fields.optionalText("materialInvocationId"),
            materialContentHash = fields.optionalText("materialContentHash"),
            notApplicableReason = fields.optionalText("notApplicableReason")?.let(LlmManifestNotApplicableReason::valueOf),
            capturedAt = Instant.parse(fields.text("capturedAt")),
            effectiveInvocationHash = fields.text("effectiveInvocationHash"),
        )
    }

    fun encode(observation: LlmPhaseObservation): String = buildJsonObject {
        put("version", VERSION)
        put("phaseManifestId", observation.phaseManifestId)
        put("observedModels", observation.observedModels.toJsonArray())
        putNullable("observedEffort", observation.observedEffort)
        put("modelCoverageStatus", observation.modelCoverageStatus.name)
        put("effortCoverageStatus", observation.effortCoverageStatus.name)
        put("terminatedAt", observation.terminatedAt.toString())
    }.canonicalString()

    fun decodeObservation(value: String): LlmPhaseObservation {
        val fields = parse(value)
        require(fields.int("version") == VERSION) { "Unsupported observation version." }

        return LlmPhaseObservation(
            phaseManifestId = fields.text("phaseManifestId"),
            observedModels = fields.getValue("observedModels").jsonArray.map { it.jsonPrimitive.content },
            observedEffort = fields.optionalText("observedEffort"),
            modelCoverageStatus = LlmIdentityCoverageStatus.valueOf(fields.text("modelCoverageStatus")),
            effortCoverageStatus = LlmIdentityCoverageStatus.valueOf(fields.text("effortCoverageStatus")),
            terminatedAt = Instant.parse(fields.text("terminatedAt")),
        )
    }

    private fun parse(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}

private fun LlmRunTriggerSnapshot.toJson(): JsonObject = buildJsonObject {
    put("kind", kind)
    put("observedAt", observedAt.toString())
    put(
        "measurements",
        buildJsonArray {
            measurements.forEach { measurement ->
                add(
                    buildJsonObject {
                        put("metric", measurement.metric)
                        put("measuredValue", measurement.measuredValue)
                        put("comparator", measurement.comparator)
                        put("threshold", measurement.threshold)
                        put("signedMargin", measurement.signedMargin)
                        put("unit", measurement.unit)
                    },
                )
            }
        },
    )
    put(
        "entities",
        buildJsonArray {
            entities.forEach { entity ->
                add(
                    buildJsonObject {
                        put("type", entity.type)
                        put("id", entity.id)
                    },
                )
            }
        },
    )
    putNullable("notApplicableReason", notApplicableReason)
}

private fun JsonObject.toTrigger(): LlmRunTriggerSnapshot = LlmRunTriggerSnapshot(
    kind = text("kind"),
    observedAt = Instant.parse(text("observedAt")),
    measurements = getValue("measurements").jsonArray.map { value ->
        val fields = value.jsonObject
        LlmTriggerMeasurement(
            metric = fields.text("metric"),
            measuredValue = fields.text("measuredValue"),
            comparator = fields.text("comparator"),
            threshold = fields.text("threshold"),
            signedMargin = fields.text("signedMargin"),
            unit = fields.text("unit"),
        )
    },
    entities = getValue("entities").jsonArray.map { value ->
        val fields = value.jsonObject
        LlmTriggerEntity(fields.text("type"), fields.text("id"))
    },
    notApplicableReason = optionalText("notApplicableReason"),
)

private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.optionalText(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(name: String): Int = text(name).toInt()
private fun List<String>.toJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))
private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObject.canonicalString(): String {
    fun canonical(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { entry ->
            "${JsonPrimitive(entry.key)}:${canonical(entry.value)}"
        }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { element -> canonical(element) }
        else -> value.toString()
    }

    return canonical(this)
}
