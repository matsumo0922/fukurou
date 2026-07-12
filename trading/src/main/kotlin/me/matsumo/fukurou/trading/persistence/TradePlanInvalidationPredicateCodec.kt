package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationType
import java.time.Instant

/** trade plan invalidation predicate の DB storage codec。 */
internal object TradePlanInvalidationPredicateCodec {
    private const val STORAGE_FIELD_COUNT = 3

    fun toStorageText(predicates: List<TradePlanInvalidationPredicate>): String {
        return predicates.joinToString(";") { predicate ->
            require(predicate.hasValidThresholdShape()) {
                "Malformed invalidation predicate thresholds cannot be encoded."
            }
            listOf(
                predicate.type.name,
                predicate.decimalThresholdJpy?.toPlainString().orEmpty(),
                predicate.instantThreshold?.toString().orEmpty(),
            ).joinToString("|")
        }
    }

    fun decode(storageText: String?): List<TradePlanInvalidationPredicate> {
        if (storageText.isNullOrBlank()) return emptyList()

        return storageText.split(';').mapIndexed { index, encoded -> decodePredicate(encoded, index) }
    }

    private fun decodePredicate(encoded: String, index: Int): TradePlanInvalidationPredicate {
        val fields = encoded.split('|')
        require(fields.size == STORAGE_FIELD_COUNT) {
            "Malformed invalidation predicate at index $index: expected $STORAGE_FIELD_COUNT fields."
        }

        val predicate = TradePlanInvalidationPredicate(
            type = runCatching { TradePlanInvalidationType.valueOf(fields[0]) }.getOrElse { throwable ->
                throw IllegalArgumentException("Malformed invalidation predicate type at index $index.", throwable)
            },
            decimalThresholdJpy = fields[1].takeIf(String::isNotBlank)?.let { threshold ->
                threshold.toBigDecimalOrNull()
                    ?: throw IllegalArgumentException("Malformed decimal threshold at index $index.")
            },
            instantThreshold = fields[2].takeIf(String::isNotBlank)?.let { threshold ->
                runCatching { Instant.parse(threshold) }.getOrElse { throwable ->
                    throw IllegalArgumentException("Malformed instant threshold at index $index.", throwable)
                }
            },
        )
        require(predicate.hasValidThresholdShape()) {
            "Malformed invalidation predicate thresholds at index $index."
        }

        return predicate
    }

    private fun TradePlanInvalidationPredicate.hasValidThresholdShape(): Boolean = when (type) {
        TradePlanInvalidationType.LAST_PRICE_AT_OR_BELOW,
        TradePlanInvalidationType.LAST_PRICE_AT_OR_ABOVE,
        TradePlanInvalidationType.BEST_BID_AT_OR_BELOW,
        TradePlanInvalidationType.BEST_ASK_AT_OR_ABOVE,
        -> decimalThresholdJpy != null && instantThreshold == null
        TradePlanInvalidationType.TIME_AT_OR_AFTER -> decimalThresholdJpy == null && instantThreshold != null
        TradePlanInvalidationType.MATERIAL_STATE_CHANGED -> decimalThresholdJpy == null && instantThreshold == null
    }
}
