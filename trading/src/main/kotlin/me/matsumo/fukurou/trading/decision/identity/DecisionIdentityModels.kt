package me.matsumo.fukurou.trading.decision.identity

import java.util.UUID

/** decision identity contract の schema version。 */
const val DECISION_IDENTITY_SCHEMA_VERSION = 1

/** server が生成し decision / intent へ同時保存する identity。 */
data class DecisionIdentity(
    val opportunityEpisodeId: UUID,
    val thesisId: String,
    val geometryHash: String,
    val materialStateHash: String,
    val schemaVersion: Int = DECISION_IDENTITY_SCHEMA_VERSION,
)

/** proposal と直前 episode の関係を表す shadow 分類。 */
enum class DedupeShadowClassification {
    MAINTAIN_PENDING,
    REVISE,
    CANCEL_REPLACE,
    NEW_EPISODE,
}

/** identity を付与する action 名か。 */
fun String.isDecisionIdentityEligible(): Boolean = this == "ENTER" || this == "ADD_LONG"

/** 直前 identity と新しい identity を execution へ影響させず分類する。 */
fun classifyShadow(
    previous: DecisionIdentity?,
    current: DecisionIdentity,
    previousEpisodeOpen: Boolean,
): DedupeShadowClassification {
    val previousEpisodeUnavailable = previous == null || !previousEpisodeOpen
    val thesisChanged = previous?.thesisId != current.thesisId
    val startsNewEpisode = previousEpisodeUnavailable || thesisChanged
    if (startsNewEpisode) {
        return DedupeShadowClassification.NEW_EPISODE
    }
    if (previous.geometryHash != current.geometryHash) {
        return DedupeShadowClassification.CANCEL_REPLACE
    }

    return if (previous.materialStateHash == current.materialStateHash) {
        DedupeShadowClassification.MAINTAIN_PENDING
    } else {
        DedupeShadowClassification.REVISE
    }
}
