package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialProjectionContext
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateRepository
import me.matsumo.fukurou.trading.decision.identity.DecisionTriggerKind
import me.matsumo.fukurou.trading.decision.identity.MarketFeatureBundle
import me.matsumo.fukurou.trading.decision.identity.MaterialAccountSnapshot
import me.matsumo.fukurou.trading.decision.identity.MaterialCandleSummary
import me.matsumo.fukurou.trading.decision.identity.MaterialFreshness
import me.matsumo.fukurou.trading.decision.identity.MaterialIndicatorSnapshot
import me.matsumo.fukurou.trading.decision.identity.MaterialLedgerFact
import me.matsumo.fukurou.trading.decision.identity.MaterialMissingSource
import me.matsumo.fukurou.trading.decision.identity.MaterialOrderbookSummary
import me.matsumo.fukurou.trading.decision.identity.MaterialSourceMetadata
import me.matsumo.fukurou.trading.decision.identity.MaterialTickerSnapshot
import me.matsumo.fukurou.trading.runner.SecretRedactor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** PostgreSQL immutable material-state manifest repository。 */
class ExposedDecisionMaterialStateRepository(
    private val database: Database,
    private val knownSecretValues: Set<String> = SecretRedactor.knownSecretValuesFromEnvironment(System.getenv()),
) : DecisionMaterialStateRepository {
    override suspend fun append(manifest: DecisionMaterialStateManifest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            manifest.requireValidSnapshotHash()
            ManifestPersistencePolicy.validateMaterial(manifest, knownSecretValues)
            transaction(database) {
                jdbcConnection().prepareStatement(
                    "INSERT INTO decision_material_state_manifests " +
                        "(invocation_id, captured_at, schema_version, content_hash, material_projection, manifest_json) " +
                        "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (invocation_id) DO NOTHING",
                ).use { statement ->
                    statement.setString(1, manifest.invocationId)
                    statement.setLong(2, manifest.capturedAt.toEpochMilli())
                    statement.setInt(3, manifest.schemaVersion)
                    statement.setString(4, manifest.persistedSnapshotHash())
                    statement.setString(5, manifest.materialProjection)
                    statement.setString(6, manifest.toJson())
                    val inserted = statement.executeUpdate()
                    if (inserted == 0) {
                        val existingHash = jdbcConnection().prepareStatement(
                            "SELECT content_hash FROM decision_material_state_manifests WHERE invocation_id = ?",
                        ).use { existingStatement ->
                            existingStatement.setString(1, manifest.invocationId)
                            existingStatement.executeQuery().use { result ->
                                require(result.next()) { "material manifest conflict row disappeared." }
                                result.getString(1)
                            }
                        }
                        require(existingHash == manifest.persistedSnapshotHash()) { "material manifest content mismatch." }
                    }
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

    override suspend fun findOpenEpisodeContext(symbol: String): Result<DecisionMaterialProjectionContext?> =
        withContext(Dispatchers.IO) {
            runCatching {
                transaction(database) {
                    jdbcConnection().prepareStatement(SELECT_OPEN_EPISODE_CONTEXT_SQL).use { statement ->
                        statement.setString(1, symbol)
                        statement.executeQuery().use { result ->
                            if (!result.next()) return@use null
                            DecisionMaterialProjectionContext(
                                anchorPriceJpy = result.getBigDecimal(2),
                                priceMoveThresholdRatio = result.getBigDecimal(1),
                                invalidationPredicates = TradePlanInvalidationPredicateCodec.decode(result.getString(3)),
                            )
                        }
                    }
                }
            }
        }
}

private val SELECT_OPEN_EPISODE_CONTEXT_SQL = """SELECT e.price_move_threshold_ratio,
    (SELECT ti.price_jpy FROM trade_intents ti JOIN decisions d ON d.id=ti.decision_id
     WHERE ti.opportunity_episode_id=e.id AND ti.price_jpy IS NOT NULL
     ORDER BY d.created_at, d.id LIMIT 1),
    (SELECT tp.invalidation_predicates FROM trade_intents ti
     JOIN trade_plans tp ON tp.id=ti.trade_plan_id JOIN decisions d ON d.id=ti.decision_id
     WHERE ti.opportunity_episode_id=e.id ORDER BY d.created_at DESC, d.id DESC LIMIT 1)
    FROM opportunity_episodes e WHERE e.symbol=? AND e.closed_at IS NULL
""".trimIndent()

internal fun String.toMaterialManifest(): DecisionMaterialStateManifest {
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
        priceMoveThresholdRatio = decimal("priceMoveThresholdRatio") ?: java.math.BigDecimal("0.01"),
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
        snapshotContentHash = text("snapshotContentHash") ?: requireNotNull(text("canonicalContentHash")),
        canonicalContentHash = requireNotNull(text("canonicalContentHash")),
        materialProjection = text("materialProjection").orEmpty(),
        marketFeatureBundle = value["marketFeatureBundle"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.jsonObject?.toMarketFeatureBundle(),
    )
}

internal fun DecisionMaterialStateManifest.toJson(): String = buildJsonObject {
    put("invocationId", invocationId)
    put("capturedAt", capturedAt.toString())
    put("triggerKind", triggerKind.name)
    put("symbol", symbol)
    put("runtimeConfigVersion", runtimeConfigVersion)
    put("runtimeConfigHash", runtimeConfigHash)
    put("riskState", riskState)
    put("priceMoveThresholdRatio", priceMoveThresholdRatio.toPlainString())
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
    put("snapshotContentHash", snapshotContentHash)
    put("canonicalContentHash", canonicalContentHash)
    put("materialProjection", materialProjection)
    put("marketFeatureBundle", marketFeatureBundle?.toJson() ?: JsonNull)
}.toString()

internal fun DecisionMaterialStateManifest.withSnapshotContentHash(): DecisionMaterialStateManifest {
    if (schemaVersion < 2) return this

    return copy(snapshotContentHash = computeSnapshotContentHash())
}

internal fun DecisionMaterialStateManifest.requireValidSnapshotHash() {
    if (schemaVersion >= 2) {
        require(snapshotContentHash == computeSnapshotContentHash()) { "material manifest snapshot hash mismatch." }
    }
}

internal fun DecisionMaterialStateManifest.persistedSnapshotHash(): String =
    if (schemaVersion >= 2) snapshotContentHash else canonicalContentHash

private fun DecisionMaterialStateManifest.computeSnapshotContentHash(): String =
    me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy.sha256(copy(snapshotContentHash = "").toJson())

private fun MarketFeatureBundle.toJson() = buildJsonObject {
    put("ticker", ticker?.toJson() ?: JsonNull)
    put("candleSummaries", buildJsonArray { candleSummaries.forEach { add(it.toJson()) } })
    put(
        "indicators",
        buildJsonArray {
            indicators.forEach { indicator ->
                add(
                    buildJsonObject {
                        put("name", indicator.name)
                        put("value", indicator.value?.toPlainString())
                        put("sampleCount", indicator.sampleCount)
                    },
                )
            }
        },
    )
    put("orderbookSummary", orderbookSummary?.toJson() ?: JsonNull)
    put("account", account.toJson())
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
}

private fun MaterialTickerSnapshot.toJson() = buildJsonObject {
    put("bestBidJpy", bestBidJpy?.toPlainString())
    put("bestAskJpy", bestAskJpy?.toPlainString())
    put("lastPriceJpy", lastPriceJpy?.toPlainString())
    put("metadata", metadata.toJson())
}

private fun MaterialCandleSummary.toJson() = buildJsonObject {
    put("openTime", openTime.toString())
    put("openJpy", openJpy.toPlainString())
    put("highJpy", highJpy.toPlainString())
    put("lowJpy", lowJpy.toPlainString())
    put("closeJpy", closeJpy.toPlainString())
    put("volumeBtc", volumeBtc?.toPlainString())
}

private fun MaterialOrderbookSummary.toJson() = buildJsonObject {
    put("bestBidJpy", bestBidJpy?.toPlainString())
    put("bestAskJpy", bestAskJpy?.toPlainString())
    put("midJpy", midJpy?.toPlainString())
    put("spreadBps", spreadBps?.toPlainString())
    put("topBidQuantityBtc", topBidQuantityBtc.toPlainString())
    put("topAskQuantityBtc", topAskQuantityBtc.toPlainString())
    put("topBidNotionalJpy", topBidNotionalJpy.toPlainString())
    put("topAskNotionalJpy", topAskNotionalJpy.toPlainString())
    put("imbalance", imbalance?.toPlainString())
    put("levelLimit", levelLimit)
    put("metadata", metadata.toJson())
}

private fun MaterialAccountSnapshot.toJson() = buildJsonObject {
    put("riskState", riskState)
    put("availableJpy", availableJpy?.toPlainString())
    put("equityJpy", equityJpy?.toPlainString())
    put("positions", buildJsonArray { positions.forEach { add(it.toJson()) } })
    put("openOrders", buildJsonArray { openOrders.forEach { add(it.toJson()) } })
    put("positionMetadata", positionMetadata.toJson())
    put("orderMetadata", orderMetadata.toJson())
}

private fun MaterialLedgerFact.toJson() = buildJsonObject {
    put("id", id)
    put("status", status)
    put("side", side)
    put("type", type)
}

private fun MaterialSourceMetadata.toJson() = buildJsonObject {
    put("observedAt", observedAt?.toString())
    put("provenance", provenance)
    put("truncated", truncated)
    put("totalCount", totalCount)
}

private fun kotlinx.serialization.json.JsonObject.toMarketFeatureBundle(): MarketFeatureBundle {
    return MarketFeatureBundle(
        ticker = this["ticker"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonObject?.toTicker(),
        candleSummaries = getValue("candleSummaries").jsonArray.map { it.jsonObject.toCandle() },
        indicators = getValue("indicators").jsonArray.map { value ->
            val fields = value.jsonObject
            MaterialIndicatorSnapshot(
                name = fields.text("name"),
                value = fields.decimal("value"),
                sampleCount = fields.text("sampleCount").toInt(),
            )
        },
        orderbookSummary = this["orderbookSummary"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.jsonObject?.toOrderbook(),
        account = getValue("account").jsonObject.toAccount(),
        missingSources = getValue("missingSources").jsonArray.map { value ->
            val fields = value.jsonObject
            MaterialMissingSource(fields.text("source"), fields.text("reason"))
        },
    )
}

private fun kotlinx.serialization.json.JsonObject.toTicker() = MaterialTickerSnapshot(
    bestBidJpy = decimal("bestBidJpy"),
    bestAskJpy = decimal("bestAskJpy"),
    lastPriceJpy = decimal("lastPriceJpy"),
    metadata = getValue("metadata").jsonObject.toMetadata(),
)

private fun kotlinx.serialization.json.JsonObject.toCandle() = MaterialCandleSummary(
    openTime = java.time.Instant.parse(text("openTime")),
    openJpy = requireNotNull(decimal("openJpy")),
    highJpy = requireNotNull(decimal("highJpy")),
    lowJpy = requireNotNull(decimal("lowJpy")),
    closeJpy = requireNotNull(decimal("closeJpy")),
    volumeBtc = decimal("volumeBtc"),
)

private fun kotlinx.serialization.json.JsonObject.toOrderbook() = MaterialOrderbookSummary(
    bestBidJpy = decimal("bestBidJpy"),
    bestAskJpy = decimal("bestAskJpy"),
    midJpy = decimal("midJpy"),
    spreadBps = decimal("spreadBps"),
    topBidQuantityBtc = requireNotNull(decimal("topBidQuantityBtc")),
    topAskQuantityBtc = requireNotNull(decimal("topAskQuantityBtc")),
    topBidNotionalJpy = requireNotNull(decimal("topBidNotionalJpy")),
    topAskNotionalJpy = requireNotNull(decimal("topAskNotionalJpy")),
    imbalance = decimal("imbalance"),
    levelLimit = text("levelLimit").toInt(),
    metadata = getValue("metadata").jsonObject.toMetadata(),
)

private fun kotlinx.serialization.json.JsonObject.toAccount() = MaterialAccountSnapshot(
    riskState = text("riskState"),
    availableJpy = decimal("availableJpy"),
    equityJpy = decimal("equityJpy"),
    positions = getValue("positions").jsonArray.map { it.jsonObject.toLedgerFact() },
    openOrders = getValue("openOrders").jsonArray.map { it.jsonObject.toLedgerFact() },
    positionMetadata = getValue("positionMetadata").jsonObject.toMetadata(),
    orderMetadata = getValue("orderMetadata").jsonObject.toMetadata(),
)

private fun kotlinx.serialization.json.JsonObject.toLedgerFact() = MaterialLedgerFact(
    id = text("id"),
    status = text("status"),
    side = optionalText("side"),
    type = optionalText("type"),
)

private fun kotlinx.serialization.json.JsonObject.toMetadata() = MaterialSourceMetadata(
    observedAt = optionalText("observedAt")?.let(java.time.Instant::parse),
    provenance = text("provenance"),
    truncated = text("truncated").toBooleanStrict(),
    totalCount = optionalText("totalCount")?.toInt(),
)

private fun kotlinx.serialization.json.JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
private fun kotlinx.serialization.json.JsonObject.optionalText(name: String) = get(name)?.jsonPrimitive?.contentOrNull
private fun kotlinx.serialization.json.JsonObject.decimal(name: String) = optionalText(name)?.toBigDecimalOrNull()
