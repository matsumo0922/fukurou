package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateRepository
import me.matsumo.fukurou.trading.decision.identity.DecisionTriggerKind
import me.matsumo.fukurou.trading.decision.identity.MaterialFreshness
import me.matsumo.fukurou.trading.decision.identity.MaterialMissingSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** PostgreSQL immutable material-state manifest repository。 */
class ExposedDecisionMaterialStateRepository(private val database: Database) : DecisionMaterialStateRepository {
    override suspend fun append(manifest: DecisionMaterialStateManifest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            transaction(database) {
                jdbcConnection().prepareStatement(
                    "INSERT INTO decision_material_state_manifests " +
                        "(invocation_id, captured_at, schema_version, content_hash, material_projection, manifest_json) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, manifest.invocationId)
                    statement.setLong(2, manifest.capturedAt.toEpochMilli())
                    statement.setInt(3, manifest.schemaVersion)
                    statement.setString(4, manifest.canonicalContentHash)
                    statement.setString(5, manifest.materialProjection)
                    statement.setString(6, manifest.toJson())
                    statement.executeUpdate()
                }
                Unit
            }
        }
    }

    override suspend fun find(invocationId: String): Result<DecisionMaterialStateManifest?> =
        withContext(Dispatchers.IO) {
            runCatching {
                transaction(database) {
                    jdbcConnection().prepareStatement(
                        "SELECT manifest_json FROM decision_material_state_manifests WHERE invocation_id = ?",
                    ).use { statement ->
                        statement.setString(1, invocationId)
                        statement.executeQuery().use { result ->
                            if (result.next()) result.getString(1).toMaterialManifest() else null
                        }
                    }
                }
            }
        }
}

private fun String.toMaterialManifest(): DecisionMaterialStateManifest {
    val value = Json.parseToJsonElement(this).jsonObject
    fun text(name: String): String? = value[name]?.jsonPrimitive?.contentOrNull
    fun decimal(name: String) = text(name)?.toBigDecimalOrNull()

    return DecisionMaterialStateManifest(
        invocationId = requireNotNull(text("invocationId")),
        capturedAt = java.time.Instant.parse(requireNotNull(text("capturedAt"))),
        triggerKind = DecisionTriggerKind.valueOf(requireNotNull(text("triggerKind"))),
        symbol = requireNotNull(text("symbol")),
        runtimeConfigVersion = text("runtimeConfigVersion"),
        runtimeConfigHash = text("runtimeConfigHash"),
        riskState = requireNotNull(text("riskState")),
        bestBidJpy = decimal("bestBidJpy"),
        bestAskJpy = decimal("bestAskJpy"),
        lastPriceJpy = decimal("lastPriceJpy"),
        sourceTimestamp = text("sourceTimestamp")?.let(java.time.Instant::parse),
        freshness = MaterialFreshness.valueOf(requireNotNull(text("freshness"))),
        atr14FiveMinutesJpy = decimal("atr14FiveMinutesJpy"),
        latestCandleOpenJpy = decimal("latestCandleOpenJpy"),
        latestCandleHighJpy = decimal("latestCandleHighJpy"),
        latestCandleLowJpy = decimal("latestCandleLowJpy"),
        latestCandleCloseJpy = decimal("latestCandleCloseJpy"),
        openPositionFacts = value.getValue("openPositionFacts").jsonArray.map { it.jsonPrimitive.content },
        openOrderFacts = value.getValue("openOrderFacts").jsonArray.map { it.jsonPrimitive.content },
        missingSources = value.getValue("missingSources").jsonArray.map { missing ->
            val fields = missing.jsonObject
            MaterialMissingSource(
                source = fields.getValue("source").jsonPrimitive.content,
                reason = fields.getValue("reason").jsonPrimitive.content,
            )
        },
        schemaVersion = requireNotNull(text("schemaVersion")).toInt(),
        canonicalContentHash = requireNotNull(text("canonicalContentHash")),
        materialProjection = text("materialProjection").orEmpty(),
    )
}

private fun DecisionMaterialStateManifest.toJson(): String = buildJsonObject {
    put("invocationId", invocationId)
    put("capturedAt", capturedAt.toString())
    put("triggerKind", triggerKind.name)
    put("symbol", symbol)
    put("runtimeConfigVersion", runtimeConfigVersion)
    put("runtimeConfigHash", runtimeConfigHash)
    put("riskState", riskState)
    put("bestBidJpy", bestBidJpy?.toPlainString())
    put("bestAskJpy", bestAskJpy?.toPlainString())
    put("lastPriceJpy", lastPriceJpy?.toPlainString())
    put("sourceTimestamp", sourceTimestamp?.toString())
    put("freshness", freshness.name)
    put("atr14FiveMinutesJpy", atr14FiveMinutesJpy?.toPlainString())
    put("latestCandleOpenJpy", latestCandleOpenJpy?.toPlainString())
    put("latestCandleHighJpy", latestCandleHighJpy?.toPlainString())
    put("latestCandleLowJpy", latestCandleLowJpy?.toPlainString())
    put("latestCandleCloseJpy", latestCandleCloseJpy?.toPlainString())
    put("openPositionFacts", buildJsonArray { openPositionFacts.forEach { add(JsonPrimitive(it)) } })
    put("openOrderFacts", buildJsonArray { openOrderFacts.forEach { add(JsonPrimitive(it)) } })
    put(
        "missingSources",
        buildJsonArray {
            missingSources.forEach { missing ->
                add(
                    buildJsonObject {
                        put("source", missing.source)
                        put("reason", missing.reason)
                    },
                )
            }
        },
    )
    put("schemaVersion", schemaVersion)
    put("canonicalContentHash", canonicalContentHash)
    put("materialProjection", materialProjection)
}.toString()
