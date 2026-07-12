package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateRepository
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
