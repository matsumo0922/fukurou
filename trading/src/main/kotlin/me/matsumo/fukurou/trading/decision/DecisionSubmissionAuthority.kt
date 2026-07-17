package me.matsumo.fukurou.trading.decision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import java.math.BigDecimal
import java.security.MessageDigest

/** server-owned decision submission の strict key。 */
data class DecisionSubmissionAuthority(
    val invocationId: String,
    val phase: LlmInvocationPhase,
) {
    init {
        require(invocationId.isNotBlank()) { "Decision submission authority requires an invocation ID." }
    }
}

/** versioned canonical business payload。 */
data class DecisionSubmissionCanonicalPayload(
    val schemaVersion: Int,
    val canonicalJson: String,
    val hash: String,
)

/** durable authority の状態。 */
enum class DecisionSubmissionAuthorityState {
    /** entity transaction が完了していない状態。 */
    PENDING,

    /** exact result IDs とentity transactionがcommit済みの状態。 */
    COMPLETED,
}

/** 同じ authority key に異なる business payload が提出されたことを表す。 */
class DecisionSubmissionConflictException : IllegalStateException("Decision submission payload conflicts with authority.")

/** authority から完了済み result を安全に再構成できないことを表す。 */
class DecisionSubmissionUnknownException : IllegalStateException("Decision submission result is unknown.")

/**
 * schema v1 の business payload へ投影し、SHA-256 hash を返す。
 *
 * [DecisionSubmission] に business field を追加する場合は、この投影を更新し、
 * [DECISION_SUBMISSION_PAYLOAD_SCHEMA_VERSION] を bump する。
 */
fun DecisionSubmission.canonicalBusinessPayload(): DecisionSubmissionCanonicalPayload {
    val projection = buildJsonObject {
        put("schemaVersion", DECISION_SUBMISSION_PAYLOAD_SCHEMA_VERSION)
        put("action", action.name)
        putNullableDecimal("closeRatio", closeRatio)
        putStrings("setupTags", setupTags)
        putDecimal("estimatedWinProbability", estimatedWinProbability)
        putNullableDecimal("expectedRMultiple", expectedRMultiple)
        putNullableDecimal("roundTripCostR", roundTripCostR)
        putStrings("toolEvidenceIds", toolEvidenceIds)
        put("factCheck", factCheckJson.toCanonicalJsonElement())
        put("selfReview", selfReviewJson.toCanonicalJsonElement())
        put("reasonJa", reasonJa)
        putStrings("missingDataJa", missingDataJa)
        putStrings("noTradeConditionsJa", noTradeConditionsJa)
        put("entryIntent", entryIntent?.canonicalJson() ?: JsonNull)
        put("tradePlan", tradePlan?.canonicalJson() ?: JsonNull)
    }
    val canonicalJson = projection.toString()

    return DecisionSubmissionCanonicalPayload(
        schemaVersion = DECISION_SUBMISSION_PAYLOAD_SCHEMA_VERSION,
        canonicalJson = canonicalJson,
        hash = canonicalJson.sha256(),
    )
}

private fun EntryIntentDraft.canonicalJson(): JsonObject = buildJsonObject {
    put("symbol", symbol.apiSymbol)
    put("side", side.name)
    put("orderType", orderType.name)
    putDecimal("sizeBtc", sizeBtc)
    putNullableDecimal("priceJpy", priceJpy)
    putDecimal("protectiveStopPriceJpy", protectiveStopPriceJpy)
    putNullableDecimal("takeProfitPriceJpy", takeProfitPriceJpy)
}

private fun TradePlanDraft.canonicalJson(): JsonObject = buildJsonObject {
    put("parentTradePlanId", parentTradePlanId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    put("revisionCount", revisionCount)
    put("symbol", symbol.apiSymbol)
    put("thesisJa", thesisJa)
    putStrings("invalidationConditionsJa", invalidationConditionsJa)
    putNullableDecimal("targetPriceJpy", targetPriceJpy)
    put("timeStopAt", timeStopAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    putStrings("setupTags", setupTags)
    putJsonArray("invalidationPredicates") {
        invalidationPredicates.forEach { predicate ->
            add(
                buildJsonObject {
                    put("type", predicate.type.name)
                    putNullableDecimal("decimalThresholdJpy", predicate.decimalThresholdJpy)
                    put(
                        "instantThreshold",
                        predicate.instantThreshold?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                    )
                },
            )
        }
    }
}

private fun String.toCanonicalJsonElement(): JsonElement {
    return CANONICAL_JSON.parseToJsonElement(this).sortedObjectKeys()
}

private fun JsonElement.sortedObjectKeys(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries.sortedBy { (key, _) -> key }.associate { (key, value) ->
            key to value.sortedObjectKeys()
        },
    )
    is JsonArray -> buildJsonArray { this@sortedObjectKeys.forEach { element -> add(element.sortedObjectKeys()) } }
    else -> this
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putDecimal(name: String, value: BigDecimal) {
    put(name, value.canonicalDecimal())
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableDecimal(name: String, value: BigDecimal?) {
    put(name, value?.let { JsonPrimitive(it.canonicalDecimal()) } ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putStrings(name: String, values: List<String>) {
    putJsonArray(name) { values.forEach { value -> add(JsonPrimitive(value)) } }
}

private fun BigDecimal.canonicalDecimal(): String {
    if (compareTo(BigDecimal.ZERO) == 0) return "0"

    return stripTrailingZeros().toPlainString()
}

private fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

/** decision submission canonical payload schema version。 */
const val DECISION_SUBMISSION_PAYLOAD_SCHEMA_VERSION = 1

private val CANONICAL_JSON = Json {
    isLenient = false
    ignoreUnknownKeys = false
}
