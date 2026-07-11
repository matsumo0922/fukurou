package me.matsumo.fukurou.trading.domain

import kotlinx.serialization.Serializable

/** paper 口座 epoch の作成種別。 */
@Serializable
enum class PaperAccountEpochKind {
    /** 既存 ledger を変更せず導入した epoch。 */
    LEGACY_IMPORTED,

    /** 監査済みの runtime config activation により開始した epoch。 */
    CONFIG_ACTIVATED,
}

/** evaluation trade の execution lineage cohort。 */
@Serializable
enum class EvaluationCohort {
    /** 現行の execution semantics が全 lineage に付く trade。 */
    CURRENT,

    /** WebSocket execution semantics 導入前または mixed lineage の trade。 */
    LEGACY_PRE_WS,

    /** code が解釈できない execution semantics を含む trade。 */
    UNSUPPORTED_EXECUTION_SEMANTICS,
}

/** immutable な paper 口座 epoch。 */
@Serializable
data class PaperAccountEpoch(
    val id: String,
    val kind: PaperAccountEpochKind,
    val initialCashJpy: String,
    val runtimeConfigHash: String,
    val reason: String,
    val actor: String,
    val createdAt: String,
)
