package me.matsumo.fukurou.trading.persistence

import org.jetbrains.exposed.v1.core.Table
import java.math.BigDecimal

/**
 * risk_state single row の固定 ID。
 */
internal const val RISK_STATE_SINGLE_ROW_ID = 1

/**
 * risk_state single row を表す Exposed table。
 */
object RiskStateTable : Table("risk_state") {
    /**
     * single row を固定する primary key。
     */
    val id = integer("id")

    /**
     * sticky HARD_HALT flag。
     */
    val hardHalt = bool("hard_halt").default(false)

    /**
     * drawdown ratio。
     */
    val drawdownRatio = decimal("drawdown_ratio", precision = 20, scale = 10).default(BigDecimal.ZERO)

    /**
     * equity peak。
     */
    val equityPeak = decimal("equity_peak", precision = 24, scale = 8).default(BigDecimal.ZERO)

    /**
     * HARD_HALT 理由。
     */
    val haltReason = text("halt_reason").nullable()

    /**
     * HARD_HALT 発生時刻。epoch millis で保存する。
     */
    val haltAt = long("halt_at").nullable()

    /**
     * 手動再開時刻。epoch millis で保存する。
     */
    val resumedAt = long("resumed_at").nullable()

    /**
     * 手動再開理由。
     */
    val resumedReason = text("resumed_reason").nullable()

    /**
     * 最終更新時刻。epoch millis で保存する。
     */
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * command_event_log / audit を表す Exposed table。
 */
object CommandEventLogTable : Table("command_event_log") {
    /**
     * event log 行 ID。
     */
    val id = uuid("id")

    /**
     * daemon 起動単位の decision run ID。
     */
    val decisionRunId = varchar("decision_run_id", length = 128).nullable()

    /**
     * tool call 単位の ID。
     */
    val toolCallId = varchar("tool_call_id", length = 128).nullable()

    /**
     * 呼び出し元 request ID。
     */
    val clientRequestId = varchar("client_request_id", length = 128).nullable()

    /**
     * tool または worker の論理名。
     */
    val toolName = varchar("tool_name", length = 128)

    /**
     * 監査イベント種別。
     */
    val eventType = varchar("event_type", length = 96)

    /**
     * JSON payload。
     */
    val payload = text("payload")

    /**
     * event 発生時刻。epoch millis で保存する。
     */
    val timestamp = long("ts")

    /**
     * LLM provider 名。
     */
    val llmProvider = varchar("llm_provider", length = 64).nullable()

    /**
     * prompt hash。
     */
    val promptHash = varchar("prompt_hash", length = 128).nullable()

    /**
     * system prompt version。
     */
    val systemPromptVersion = varchar("system_prompt_version", length = 128).nullable()

    /**
     * market snapshot ID。
     */
    val marketSnapshotId = varchar("market_snapshot_id", length = 128).nullable()

    override val primaryKey = PrimaryKey(id)
}
