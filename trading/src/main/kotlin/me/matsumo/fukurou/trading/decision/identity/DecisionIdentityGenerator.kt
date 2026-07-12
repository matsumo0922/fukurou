package me.matsumo.fukurou.trading.decision.identity

import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

/** versioned canonicalization から server-owned identity を生成する。 */
object DecisionIdentityGenerator {
    /** typed thesis facts だけから thesis ID を生成する。 */
    fun thesisId(tradePlan: TradePlanDraft): String = "ths_v1_${sha256(thesisCanonical(tradePlan))}"

    /**
     * canonical content の lowercase SHA-256。
     */
    fun contentHash(value: String): String = sha256(value)

    /**
     * thesis、geometry、material projection から identity を生成する。
     */
    fun generate(
        episodeId: UUID,
        tradePlan: TradePlanDraft,
        intent: EntryIntentDraft,
        materialProjection: String,
    ): DecisionIdentity {
        return DecisionIdentity(
            opportunityEpisodeId = episodeId,
            thesisId = thesisId(tradePlan),
            geometryHash = "geo_v1_${sha256(geometryCanonical(intent))}",
            materialStateHash = "mat_v1_${sha256(materialProjection)}",
        )
    }

    /** decimal の意味を保った canonical string。 */
    fun canonicalDecimal(value: BigDecimal): String {
        if (value.compareTo(BigDecimal.ZERO) == 0) return "0"

        return value.stripTrailingZeros().toPlainString()
    }

    /** NFKC、trim、連続 whitespace collapse を適用する。 */
    fun canonicalText(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun thesisCanonical(tradePlan: TradePlanDraft): String {
        val tags = tradePlan.setupTags.map(::canonicalText).distinct().sorted()
        val invalidations = tradePlan.invalidationPredicates.map { predicate ->
            listOf(
                predicate.type.name,
                predicate.decimalThresholdJpy?.let(::canonicalDecimal).orEmpty(),
                predicate.instantThreshold?.toString().orEmpty(),
            ).joinToString("|")
        }.distinct().sorted()

        return listOf(
            "symbol=${tradePlan.symbol.apiSymbol}",
            "thesis=${canonicalText(tradePlan.thesisJa)}",
            "tags=${tags.joinToString("|")}",
            "invalidations=${invalidations.joinToString("|")}",
        ).joinToString("\n")
    }

    private fun geometryCanonical(intent: EntryIntentDraft): String {
        return listOf(
            "symbol=${intent.symbol.apiSymbol}",
            "side=${intent.side.name}",
            "type=${intent.orderType.name}",
            "size=${canonicalDecimal(intent.sizeBtc)}",
            "entry=${intent.priceJpy?.let(::canonicalDecimal) ?: "null"}",
            "stop=${canonicalDecimal(intent.protectiveStopPriceJpy)}",
            "tp=${intent.takeProfitPriceJpy?.let(::canonicalDecimal) ?: "null"}",
        ).joinToString("\n")
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
