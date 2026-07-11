package me.matsumo.fukurou.trading.domain

import kotlinx.serialization.Serializable

/** 現行 paper execution semantics の code-owned version。 */
const val PAPER_EXECUTION_SEMANTICS_VERSION = "PAPER_WS_V1"

/** paper order/execution write に必須の lineage。 */
@Serializable
data class PaperExecutionLineage(
    val accountEpochId: String,
    val executionSemanticsVersion: String,
    val runtimeConfigHash: String,
)
