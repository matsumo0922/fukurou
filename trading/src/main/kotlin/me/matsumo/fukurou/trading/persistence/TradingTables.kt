package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.risk.RiskHaltState
import org.jetbrains.exposed.v1.core.Table
import java.math.BigDecimal

/**
 * runtime config の version 状態を表す Exposed table。
 */
object RuntimeConfigVersionsTable : Table("runtime_config_versions") {
    /**
     * version ID。
     */
    val id = uuid("id")

    /**
     * version 状態。
     */
    val status = varchar("status", length = 32)

    /**
     * version 作成時刻。epoch millis で保存する。
     */
    val createdAt = long("created_at")

    /**
     * active 化された時刻。epoch millis で保存する。
     */
    val activatedAt = long("activated_at").nullable()

    /**
     * 作成元。
     */
    val createdBy = varchar("created_by", length = 128)

    /**
     * secret を含まない補足。
     */
    val note = text("note").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * runtime config version ごとの値を表す Exposed table。
 */
object RuntimeConfigValuesTable : Table("runtime_config_values") {
    /**
     * runtime_config_versions の version ID。
     */
    val versionId = uuid("version_id").references(RuntimeConfigVersionsTable.id)

    /**
     * code-owned runtime config key。
     */
    val configKey = varchar("config_key", length = 128)

    /**
     * catalog valueType に対応する保存値。
     */
    val configValue = text("config_value")

    override val primaryKey = PrimaryKey(versionId, configKey)
}

/**
 * risk_state single row の固定 ID。
 */
internal const val RISK_STATE_SINGLE_ROW_ID = 1

/**
 * paper_account single row の固定 ID。
 */
internal const val PAPER_ACCOUNT_SINGLE_ROW_ID = 1

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
     * 現在の取引停止状態。
     */
    val state = varchar("state", length = 32).default(RiskHaltState.RUNNING.name)

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
 * paper account の現在状態を表す Exposed table。
 */
object PaperAccountTable : Table("paper_account") {
    /**
     * single row を固定する primary key。
     */
    val id = integer("id")

    /** 現在 active な immutable account epoch ID。 */
    val currentEpochId = uuid("current_epoch_id").nullable()

    /**
     * 取引 mode。
     */
    val mode = varchar("mode", length = 16)

    /**
     * 初期 JPY 残高。
     */
    val initialCashJpy = decimal("initial_cash_jpy", precision = 24, scale = 8)

    /**
     * JPY 現金残高。
     */
    val cashJpy = decimal("cash_jpy", precision = 24, scale = 8)

    /**
     * BTC 保有数量。
     */
    val btcQuantity = decimal("btc_quantity", precision = 24, scale = 12).default(BigDecimal.ZERO)

    /**
     * BTC 評価価格。
     */
    val btcMarkPriceJpy = decimal("btc_mark_price_jpy", precision = 24, scale = 8).default(BigDecimal.ZERO)

    /**
     * 総評価額。
     */
    val totalEquityJpy = decimal("total_equity_jpy", precision = 24, scale = 8)

    /**
     * 総評価額の過去ピーク。
     */
    val equityPeakJpy = decimal("equity_peak_jpy", precision = 24, scale = 8)

    /**
     * equityPeakJpy からの drawdown。
     */
    val drawdownRatio = decimal("drawdown_ratio", precision = 20, scale = 10).default(BigDecimal.ZERO)

    /**
     * 作成時刻。epoch millis で保存する。
     */
    val createdAt = long("created_at")

    /**
     * 最終更新時刻。epoch millis で保存する。
     */
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** immutable な paper account baseline epoch を表す table。 */
object PaperAccountEpochsTable : Table("paper_account_epochs") {
    /** epoch ID。 */
    val id = uuid("id")

    /** import / config activation の種別。 */
    val kind = varchar("kind", length = 32)

    /** epoch の基準資金。 */
    val initialCashJpy = decimal("initial_cash_jpy", precision = 24, scale = 8)

    /** 作成元 active runtime config の canonical hash。 */
    val runtimeConfigHash = varchar("runtime_config_hash", length = 64)

    /** secret を含まない作成理由。 */
    val reason = text("reason")

    /** 作成主体。 */
    val actor = varchar("actor", length = 128)

    /** 作成時刻。epoch millis で保存する。 */
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * LLM runner の run-level 記録を表す Exposed table。
 */
object LlmRunsTable : Table("llm_runs") {
    /**
     * runner 起動 ID。
     */
    val invocationId = varchar("invocation_id", length = 128)

    /**
     * 取引 mode。
     */
    val mode = varchar("mode", length = 16)

    /**
     * 取引対象 symbol。
     */
    val symbol = varchar("symbol", length = 32)

    /**
     * daemon trigger 種別。手動起動では null。
     */
    val triggerKind = varchar("trigger_kind", length = 64).nullable()

    /**
     * runner status。
     */
    val status = varchar("status", length = 32)

    /**
     * 起動開始時刻。epoch millis で保存する。
     */
    val startedAt = long("started_at")

    /**
     * 終了時刻。epoch millis で保存する。
     */
    val finishedAt = long("finished_at").nullable()

    /**
     * redaction / truncate 済みのエラー message。
     */
    val errorMessage = text("error_message").nullable()

    /** 終了理由の安定コード。RUNNING 行だけ null。 */
    val terminalCause = varchar("terminal_cause", length = 64).nullable()

    /**
     * 起動開始時の runtime config version ID。
     */
    val runtimeConfigVersionId = varchar("runtime_config_version_id", length = 64).nullable()

    /**
     * 起動開始時の runtime config content hash。
     */
    val runtimeConfigHash = varchar("runtime_config_hash", length = 64).nullable()

    override val primaryKey = PrimaryKey(invocationId)
}

/**
 * paper account の equity 履歴を表す Exposed table。
 */
object EquitySnapshotsTable : Table("equity_snapshots") {
    /**
     * snapshot ID。
     */
    val id = uuid("id")

    /** snapshot が属する account epoch。旧 row は null。 */
    val accountEpochId = uuid("account_epoch_id").nullable()

    /**
     * 取引 mode。
     */
    val mode = varchar("mode", length = 16)

    /**
     * snapshot 追加理由。
     */
    val reason = varchar("reason", length = 16)

    /**
     * JST 取引日。YYYY-MM-DD で保存する。
     */
    val tradingDate = varchar("trading_date", length = 10)

    /**
     * 取得時刻。epoch millis で保存する。
     */
    val capturedAt = long("captured_at")

    /**
     * JPY 現金残高。
     */
    val cashJpy = decimal("cash_jpy", precision = 24, scale = 8)

    /**
     * BTC 保有数量。
     */
    val btcQuantity = decimal("btc_quantity", precision = 24, scale = 12)

    /**
     * BTC 評価価格。
     */
    val btcMarkPriceJpy = decimal("btc_mark_price_jpy", precision = 24, scale = 8)

    /**
     * 総評価額。
     */
    val totalEquityJpy = decimal("total_equity_jpy", precision = 24, scale = 8)

    /**
     * 総評価額の過去ピーク。
     */
    val equityPeakJpy = decimal("equity_peak_jpy", precision = 24, scale = 8)

    /**
     * equityPeakJpy からの drawdown。
     */
    val drawdownRatio = decimal("drawdown_ratio", precision = 20, scale = 10)

    override val primaryKey = PrimaryKey(id)
}

/**
 * bot-managed position ledger を表す Exposed table。
 */
object PositionsTable : Table("positions") {
    /**
     * position ID。
     */
    val id = uuid("id")

    /** position が属する account epoch。旧 row は null。 */
    val accountEpochId = uuid("account_epoch_id").nullable()

    /**
     * group 単位リスク評価用 ID。
     */
    val tradeGroupId = uuid("trade_group_id")

    /**
     * 取引 mode。
     */
    val mode = varchar("mode", length = 16)

    /**
     * 取引対象 symbol。
     */
    val symbol = varchar("symbol", length = 32)

    /**
     * position side。
     */
    val side = varchar("side", length = 16)

    /**
     * position 状態。
     */
    val status = varchar("status", length = 16)

    /**
     * 開設時刻。epoch millis で保存する。
     */
    val openedAt = long("opened_at")

    /**
     * 決済時刻。epoch millis で保存する。
     */
    val closedAt = long("closed_at").nullable()

    /**
     * 未決済 BTC 残量。closed position の初期数量と決済履歴は executions から復元する。
     */
    val sizeBtc = decimal("size_btc", precision = 24, scale = 12)

    /**
     * 平均取得単価。
     */
    val averageEntryPriceJpy = decimal("average_entry_price_jpy", precision = 24, scale = 8)

    /**
     * 現在評価価格。
     */
    val currentPriceJpy = decimal("current_price_jpy", precision = 24, scale = 8)

    /**
     * 現在の保護 STOP 価格。
     */
    val currentStopLossJpy = decimal("current_stop_loss_jpy", precision = 24, scale = 8).nullable()

    /**
     * 現在の virtual TP 価格。
     */
    val currentTakeProfitJpy = decimal("current_take_profit_jpy", precision = 24, scale = 8).nullable()

    /**
     * 未実現損益。
     */
    val unrealizedPnlJpy = decimal("unrealized_pnl_jpy", precision = 24, scale = 8).default(BigDecimal.ZERO)

    /**
     * 未実現 R。
     */
    val unrealizedR = decimal("unrealized_r", precision = 12, scale = 6).default(BigDecimal.ZERO)

    /**
     * ピラミッディング追加回数。
     */
    val pyramidAddCount = integer("pyramid_add_count").default(0)

    /**
     * entry 以降の最高値。
     */
    val highestPriceSinceEntryJpy = decimal("highest_price_since_entry_jpy", precision = 24, scale = 8)

    /**
     * entry 以降の最安値。null は記録開始前で MAE 評価対象外。
     */
    val lowestPriceSinceEntryJpy = decimal("lowest_price_since_entry_jpy", precision = 24, scale = 8).nullable()

    /**
     * decision run ID。
     */
    val decisionRunId = varchar("decision_run_id", length = 128).nullable()

    /**
     * tool call ID。
     */
    val toolCallId = varchar("tool_call_id", length = 128).nullable()

    /**
     * client request ID。
     */
    val clientRequestId = varchar("client_request_id", length = 128).nullable()

    /**
     * LLM provider。
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

    /** position 管理 event の接続 session ID。 */
    val marketDataSessionId = uuid("market_data_session_id").nullable()

    /** position 管理に使える最小 event sequence の直前値。 */
    val marketEligibleAfterSequence = long("market_eligible_after_sequence").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * paper / exchange 注文 ledger を表す Exposed table。
 */
object OrdersTable : Table("orders") {
    /**
     * 注文 ID。
     */
    val id = uuid("id")

    /** order が属する account epoch。旧 row は null。 */
    val accountEpochId = uuid("account_epoch_id").nullable()

    /** order 作成時の execution semantics。旧 row は null。 */
    val executionSemanticsVersion = varchar("execution_semantics_version", length = 64).nullable()

    /** order 作成時の canonical runtime config hash。旧 row は null。 */
    val runtimeConfigHash = varchar("runtime_config_hash", length = 64).nullable()

    /**
     * entry intent ID。
     */
    val intentId = uuid("intent_id").nullable()

    /**
     * 関連 position ID。
     */
    val positionId = uuid("position_id").nullable()

    /**
     * 関連 trade group ID。
     */
    val tradeGroupId = uuid("trade_group_id").nullable()

    /**
     * 取引 mode。
     */
    val mode = varchar("mode", length = 16)

    /**
     * 取引対象 symbol。
     */
    val symbol = varchar("symbol", length = 32)

    /**
     * 注文 side。
     */
    val side = varchar("side", length = 8)

    /**
     * 注文種別。
     */
    val orderType = varchar("order_type", length = 16)

    /**
     * 注文状態。
     */
    val status = varchar("status", length = 32)

    /**
     * 注文数量。
     */
    val sizeBtc = decimal("size_btc", precision = 24, scale = 12)

    /**
     * 指値価格。
     */
    val limitPriceJpy = decimal("limit_price_jpy", precision = 24, scale = 8).nullable()

    /**
     * STOP trigger 価格。
     */
    val triggerPriceJpy = decimal("trigger_price_jpy", precision = 24, scale = 8).nullable()

    /**
     * entry intent に紐づく保護 STOP 価格。
     */
    val protectiveStopPriceJpy = decimal("protective_stop_price_jpy", precision = 24, scale = 8).nullable()

    /**
     * entry intent に紐づく virtual TP 価格。
     */
    val takeProfitPriceJpy = decimal("take_profit_price_jpy", precision = 24, scale = 8).nullable()

    /**
     * entry intent で LLM が申告した推定勝率。
     */
    val estimatedWinProbability = decimal("estimated_win_probability", precision = 20, scale = 10).nullable()

    /**
     * 判断理由。
     */
    val reasonJa = text("reason_ja").nullable()

    /**
     * decision run ID。
     */
    val decisionRunId = varchar("decision_run_id", length = 128).nullable()

    /**
     * tool call ID。
     */
    val toolCallId = varchar("tool_call_id", length = 128).nullable()

    /**
     * client request ID。
     */
    val clientRequestId = varchar("client_request_id", length = 128).nullable()

    /**
     * LLM provider。
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

    /** resting entry order の作成時に固定した実効期限。 */
    val expiresAt = long("expires_at").nullable()

    /** 実効期限を決めた入力。 */
    val expirySource = varchar("expiry_source", length = 32).nullable()

    /** 作成時刻から実効期限までの秒数。 */
    val effectiveTtlSeconds = long("effective_ttl_seconds").nullable()

    /** lifecycle が期限到達を確定した時刻。 */
    val expiredAt = long("expired_at").nullable()

    /** 取消を確定した時刻。 */
    val canceledAt = long("canceled_at").nullable()

    /** 取消理由 code。 */
    val cancelReason = varchar("cancel_reason", length = 128).nullable()

    /** 取消を実行した decision run ID。 */
    val canceledByDecisionRunId = varchar("canceled_by_decision_run_id", length = 128).nullable()

    /** LIMIT 作成時点で先行する queue 数量。 */
    val queueAheadBtc = decimal("queue_ahead_btc", precision = 24, scale = 12).nullable()

    /** realtime SELL event で消化した queue 数量。 */
    val queueConsumedBtc = decimal("queue_consumed_btc", precision = 24, scale = 12).nullable()

    /** queue snapshot の取得時刻。 */
    val queueSnapshotAt = long("queue_snapshot_at").nullable()

    /** 注文を作成した market-data session ID。 */
    val marketDataSessionId = uuid("market_data_session_id").nullable()

    /** 注文作成時点の処理済み sequence。 */
    val marketEligibleAfterSequence = long("market_eligible_after_sequence").nullable()

    /** 注文が realtime event を受理できる時刻境界。 */
    val marketEligibleFrom = long("market_eligible_from").nullable()

    /**
     * 作成時刻。epoch millis で保存する。
     */
    val createdAt = long("created_at")

    /**
     * 最終更新時刻。epoch millis で保存する。
     */
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 約定 ledger を表す Exposed table。
 */
object ExecutionsTable : Table("executions") {
    /**
     * 約定 ID。
     */
    val id = uuid("id")

    /** execution が属する account epoch。旧 row は null。 */
    val accountEpochId = uuid("account_epoch_id").nullable()

    /** execution 時の semantics。旧 row は null。 */
    val executionSemanticsVersion = varchar("execution_semantics_version", length = 64).nullable()

    /** execution 時の canonical runtime config hash。旧 row は null。 */
    val runtimeConfigHash = varchar("runtime_config_hash", length = 64).nullable()

    /**
     * 関連注文 ID。
     */
    val orderId = uuid("order_id").nullable()

    /**
     * 関連 position ID。
     */
    val positionId = uuid("position_id").nullable()

    /**
     * 取引 mode。
     */
    val mode = varchar("mode", length = 16)

    /**
     * 取引対象 symbol。
     */
    val symbol = varchar("symbol", length = 32)

    /**
     * 約定 side。
     */
    val side = varchar("side", length = 8)

    /**
     * 約定価格。
     */
    val priceJpy = decimal("price_jpy", precision = 24, scale = 8)

    /**
     * 約定数量。
     */
    val sizeBtc = decimal("size_btc", precision = 24, scale = 12)

    /**
     * 手数料。
     */
    val feeJpy = decimal("fee_jpy", precision = 24, scale = 8)

    /**
     * 実現損益。
     */
    val realizedPnlJpy = decimal("realized_pnl_jpy", precision = 24, scale = 8).default(BigDecimal.ZERO)

    /**
     * maker / taker 区分。
     */
    val liquidity = varchar("liquidity", length = 16)

    /**
     * 約定時刻。epoch millis で保存する。
     */
    val executedAt = long("executed_at")

    /**
     * decision run ID。
     */
    val decisionRunId = varchar("decision_run_id", length = 128).nullable()

    /**
     * tool call ID。
     */
    val toolCallId = varchar("tool_call_id", length = 128).nullable()

    /**
     * client request ID。
     */
    val clientRequestId = varchar("client_request_id", length = 128).nullable()

    /**
     * LLM provider。
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

    /** 約定根拠の market-data session ID。 */
    val sourceSessionId = uuid("source_session_id").nullable()

    /** 約定根拠の local sequence。 */
    val sourceSequence = long("source_sequence").nullable()

    /** 約定根拠の取引所時刻。 */
    val sourceExchangeAt = long("source_exchange_at").nullable()

    /** 約定根拠の受信時刻。 */
    val sourceReceivedAt = long("source_received_at").nullable()

    /** 約定根拠の trade side。 */
    val sourceSide = varchar("source_side", length = 8).nullable()

    /** 約定根拠の trade price。 */
    val sourcePriceJpy = decimal("source_price_jpy", precision = 24, scale = 8).nullable()

    /** 約定根拠の trade size。 */
    val sourceSizeBtc = decimal("source_size_btc", precision = 24, scale = 12).nullable()

    override val primaryKey = PrimaryKey(id)
}

/** paper execution 用 WebSocket 接続 session。 */
object MarketDataSessionsTable : Table("market_data_sessions") {
    val id = uuid("id")
    val state = varchar("state", length = 32)
    val connectedAt = long("connected_at")
    val disconnectedAt = long("disconnected_at").nullable()
    val lastProcessedSequence = long("last_processed_sequence").default(0)

    /** 旧trade timestampの互換/backfill専用列。同PRではdropせず、新規書込には使わない。 */
    val lastReceivedAt = long("last_received_at").nullable()
    val lastTransportActivityAt = long("last_transport_activity_at").nullable()
    val lastTradeAt = long("last_trade_at").nullable()
    val lastMaintenanceAt = long("last_maintenance_at").nullable()
    val disconnectReason = varchar("disconnect_reason", length = 64).nullable()

    override val primaryKey = PrimaryKey(id)
}

/** market-data gap の監査記録。 */
object MarketDataGapsTable : Table("market_data_gaps") {
    val id = uuid("id")
    val sessionId = uuid("session_id").references(MarketDataSessionsTable.id)
    val reason = varchar("reason", length = 64)
    val detail = text("detail").nullable()
    val startedAt = long("started_at")
    val impactAppliedAt = long("impact_applied_at").nullable()
    val recoveredAt = long("recovered_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** infrastructure failure により戦略評価から外す entity。 */
object EvaluationExclusionsTable : Table("evaluation_exclusions") {
    val id = uuid("id")
    val gapId = uuid("gap_id").references(MarketDataGapsTable.id)
    val entityType = varchar("entity_type", length = 32)
    val entityId = varchar("entity_id", length = 128)
    val reason = varchar("reason", length = 64)
    val createdAt = long("created_at")

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

    /**
     * 判断開始時の runtime config version ID。
     */
    val runtimeConfigVersionId = varchar("runtime_config_version_id", length = 64).nullable()

    /**
     * 判断開始時の runtime config content hash。
     */
    val runtimeConfigHash = varchar("runtime_config_hash", length = 64).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * LLM daemon scheduler の起動予約を表す Exposed table。
 */
object LlmLaunchReservationsTable : Table("llm_launch_reservations") {
    /**
     * 予約行 ID。
     */
    val id = uuid("id")

    /**
     * runner 起動 ID。
     */
    val invocationId = varchar("invocation_id", length = 128)

    /**
     * daemon trigger 種別。
     */
    val triggerKind = varchar("trigger_kind", length = 64)

    /**
     * cadence 判定に使う trigger 固有 key。
     */
    val triggerKey = varchar("trigger_key", length = 256)

    /**
     * 予約状態。
     */
    val status = varchar("status", length = 32)

    /**
     * 予約時刻。epoch millis で保存する。
     */
    val reservedAt = long("reserved_at")

    /**
     * 完了時刻。epoch millis で保存する。
     */
    val finishedAt = long("finished_at").nullable()

    /**
     * 失敗や no-trade の補助理由。
     */
    val reason = text("reason").nullable()

    /** one-shot 実行権の状態。legacy row は NULL のまま保持する。 */
    val executionClaimState = varchar("execution_claim_state", length = 32).nullable()

    /** claim owner を fence する UUID token。 */
    val executionClaimToken = varchar("execution_claim_token", length = 64).nullable()

    /** claim commit 時刻。epoch millis で保存する。 */
    val executionClaimedAt = long("execution_claimed_at").nullable()

    /** live owner が最後に更新した heartbeat。epoch millis で保存する。 */
    val executionClaimHeartbeatAt = long("execution_claim_heartbeat_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * SafetyFloor の拒否監査を表す Exposed table。
 */
object SafetyViolationsTable : Table("safety_violations") {
    /**
     * violation ID。
     */
    val id = uuid("id")

    /**
     * decision run ID。
     */
    val decisionRunId = varchar("decision_run_id", length = 128).nullable()

    /**
     * tool call ID。
     */
    val toolCallId = varchar("tool_call_id", length = 128).nullable()

    /**
     * client request ID。
     */
    val clientRequestId = varchar("client_request_id", length = 128).nullable()

    /**
     * tool / command 名。
     */
    val toolName = varchar("tool_name", length = 128)

    /**
     * command ID。
     */
    val commandId = uuid("command_id").nullable()

    /**
     * 関連 order ID。
     */
    val orderId = uuid("order_id").nullable()

    /**
     * 違反した rule。
     */
    val rule = varchar("rule", length = 96)

    /**
     * 呼び出し元向け日本語 message。
     */
    val messageJa = text("message_ja")

    /**
     * 実測または申告された値。
     */
    val measuredValue = varchar("measured_value", length = 256)

    /**
     * 安全床の上限または下限。
     */
    val limitValue = varchar("limit_value", length = 256)

    /**
     * HARD_HALT 掃引が必要な違反か。
     */
    val hardHaltRequired = bool("hard_halt_required").default(false)

    /**
     * JSON payload。
     */
    val payload = text("payload")

    /**
     * 作成時刻。epoch millis で保存する。
     */
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * LLM の最終判断を append-only で保存する Exposed table。
 */
/** full run 前に固定する immutable material-state manifest。 */
object DecisionMaterialStateManifestsTable : Table("decision_material_state_manifests") {
    val invocationId = varchar("invocation_id", 128)
    val capturedAt = long("captured_at")
    val schemaVersion = integer("schema_version")
    val contentHash = varchar("content_hash", 64)
    val materialProjection = text("material_projection").default("")
    val manifestJson = text("manifest_json")
    override val primaryKey = PrimaryKey(invocationId)
}

/** identity schema が production 集計対象になった明示 boundary。 */
object DecisionIdentitySchemaBoundariesTable : Table("decision_identity_schema_boundaries") {
    val schemaVersion = integer("schema_version")
    val activatedAt = long("activated_at")
    override val primaryKey = PrimaryKey(schemaVersion)
}

/** post-boundary identity generation の typed coverage failure。 */
object DecisionIdentityGenerationFailuresTable : Table("decision_identity_generation_failures") {
    val id = uuid("id")
    val invocationId = varchar("invocation_id", 128).nullable()
    val entityKind = varchar("entity_kind", 16)
    val reason = varchar("reason", 64)
    val occurredAt = long("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

/** opportunity episode の明示 lifecycle。 */
object OpportunityEpisodesTable : Table("opportunity_episodes") {
    val id = uuid("id")
    val symbol = varchar("symbol", 32)
    val thesisId = varchar("thesis_id", 80)
    val priceMoveThresholdRatio = decimal("price_move_threshold_ratio", 20, 12).default(BigDecimal("0.01"))
    val openedAt = long("opened_at")
    val closedAt = long("closed_at").nullable()
    val closeReason = varchar("close_reason", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** dedupe shadow classifier の append-only observation。 */
object DedupeShadowObservationsTable : Table("dedupe_shadow_observations") {
    val id = uuid("id")
    val observationKind = varchar("observation_kind", 32)
    val decisionId = uuid("decision_id").nullable()
    val opportunityEpisodeId = uuid("opportunity_episode_id").nullable()
    val classification = varchar("classification", 32).nullable()
    val suppressionReason = varchar("suppression_reason", 64).nullable()
    val maintenanceTickId = uuid("maintenance_tick_id").nullable()
    val referenceOrderId = uuid("reference_order_id").nullable()
    val oldMaterialStateHash = varchar("old_material_state_hash", 80).nullable()
    val newMaterialStateHash = varchar("new_material_state_hash", 80).nullable()
    val invalidationState = varchar("invalidation_state", 32).nullable()
    val distanceJpy = decimal("distance_jpy", 24, 8).nullable()
    val signedDistanceBps = decimal("signed_distance_bps", 24, 8).nullable()
    val atrPriceRatio = decimal("atr_price_ratio", 24, 12).nullable()
    val dataQuality = varchar("data_quality", 32)
    val observedAt = long("observed_at")
    override val primaryKey = PrimaryKey(id)
}

/** counterfactual label の append-only resolution。 */
object DedupeShadowResolutionsTable : Table("dedupe_shadow_resolutions") {
    val id = uuid("id")
    val observationId = uuid("observation_id")
    val resolution = varchar("resolution", 64)
    val resolvedAt = long("resolved_at")
    override val primaryKey = PrimaryKey(id)
}

object DecisionsTable : Table("decisions") {
    /**
     * decision ID。
     */
    val id = uuid("id")

    /** server-owned opportunity episode ID。 */
    val opportunityEpisodeId = uuid("opportunity_episode_id").nullable()

    /** canonical thesis ID。 */
    val thesisId = varchar("thesis_id", 80).nullable()

    /** canonical order geometry hash。 */
    val geometryHash = varchar("geometry_hash", 80).nullable()

    /** versioned material state hash。 */
    val materialStateHash = varchar("material_state_hash", 80).nullable()

    /** identity schema version。 */
    val identitySchemaVersion = integer("identity_schema_version").nullable()

    /**
     * daemon / CLI 起動単位の ID。
     */
    val invocationId = varchar("invocation_id", length = 128).nullable()

    /**
     * LLM provider。
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

    /**
     * 最終 action。
     */
    val action = varchar("action", length = 32)

    /**
     * REDUCE の close ratio。
     */
    val closeRatio = decimal("close_ratio", precision = 20, scale = 10).nullable()

    /**
     * setup tag 一覧 JSON。
     */
    val setupTags = text("setup_tags")

    /**
     * LLM 申告の推定勝率。
     */
    val estimatedWinProbability = decimal("estimated_win_probability", precision = 20, scale = 10)

    /**
     * 期待 R 倍率。
     */
    val expectedRMultiple = decimal("expected_r_multiple", precision = 20, scale = 10).nullable()

    /**
     * 往復 cost の R 換算。
     */
    val roundTripCostR = decimal("round_trip_cost_r", precision = 20, scale = 10).nullable()

    /**
     * 判断根拠 tool call ID 一覧 JSON。
     */
    val toolEvidenceIds = text("tool_evidence_ids")

    /**
     * fact check JSON。
     */
    val factCheck = text("fact_check")

    /**
     * self review JSON。
     */
    val selfReview = text("self_review")

    /**
     * 判断理由。
     */
    val reasonJa = text("reason_ja")

    /**
     * 不足データ一覧 JSON。
     */
    val missingDataJa = text("missing_data_ja")

    /**
     * NO_TRADE 時に次回評価へ残した entry trigger / invalidation 条件一覧 JSON。
     */
    val noTradeConditionsJa = text("no_trade_conditions_ja")

    /**
     * 作成時刻。epoch millis で保存する。
     */
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * TradePlan を append-only で保存する Exposed table。
 */
object TradePlansTable : Table("trade_plans") {
    /**
     * TradePlan ID。
     */
    val id = uuid("id")

    /**
     * 紐づく decision ID。
     */
    val decisionId = uuid("decision_id")

    /**
     * 改訂元 TradePlan ID。
     */
    val parentTradePlanId = uuid("parent_trade_plan_id").nullable()

    /**
     * 正式修正の revision count。
     */
    val revisionCount = integer("revision_count")

    /**
     * 取引対象 symbol。
     */
    val symbol = varchar("symbol", length = 32)

    /**
     * 取引仮説。
     */
    val thesisJa = text("thesis_ja")

    /**
     * 否定条件一覧 JSON。
     */
    val invalidationConditionsJa = text("invalidation_conditions_ja")
    val invalidationPredicates = text("invalidation_predicates").default("")

    /**
     * 目標価格。
     */
    val targetPriceJpy = decimal("target_price_jpy", precision = 24, scale = 8).nullable()

    /**
     * 時間切れ条件。epoch millis で保存する。
     */
    val timeStopAt = long("time_stop_at").nullable()

    /**
     * setup tag 一覧 JSON。
     */
    val setupTags = text("setup_tags")

    /**
     * 作成時刻。epoch millis で保存する。
     */
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * entry 系 decision から発行された intent を append-only で保存する Exposed table。
 */
object TradeIntentsTable : Table("trade_intents") {
    /**
     * intent ID。
     */
    val id = uuid("id")

    /** server-owned opportunity episode ID。 */
    val opportunityEpisodeId = uuid("opportunity_episode_id").nullable()

    /** canonical thesis ID。 */
    val thesisId = varchar("thesis_id", 80).nullable()

    /** canonical order geometry hash。 */
    val geometryHash = varchar("geometry_hash", 80).nullable()

    /** versioned material state hash。 */
    val materialStateHash = varchar("material_state_hash", 80).nullable()

    /** identity schema version。 */
    val identitySchemaVersion = integer("identity_schema_version").nullable()

    /**
     * 紐づく decision ID。
     */
    val decisionId = uuid("decision_id")

    /**
     * 紐づく TradePlan ID。
     */
    val tradePlanId = uuid("trade_plan_id")

    /**
     * 取引対象 symbol。
     */
    val symbol = varchar("symbol", length = 32)

    /**
     * 注文 side。
     */
    val side = varchar("side", length = 8)

    /**
     * 注文種別。
     */
    val orderType = varchar("order_type", length = 16)

    /**
     * 注文数量。
     */
    val sizeBtc = decimal("size_btc", precision = 24, scale = 12)

    /**
     * LIMIT / STOP entry 価格。
     */
    val priceJpy = decimal("price_jpy", precision = 24, scale = 8).nullable()

    /**
     * 保護 STOP 価格。
     */
    val protectiveStopPriceJpy = decimal("protective_stop_price_jpy", precision = 24, scale = 8)

    /**
     * virtual TP 価格。
     */
    val takeProfitPriceJpy = decimal("take_profit_price_jpy", precision = 24, scale = 8).nullable()

    /**
     * LLM 申告の推定勝率。
     */
    val estimatedWinProbability = decimal("estimated_win_probability", precision = 20, scale = 10)

    /**
     * 作成時刻。epoch millis で保存する。
     */
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Falsifier verdict を append-only で保存する Exposed table。
 */
object FalsificationsTable : Table("falsifications") {
    /**
     * falsification ID。
     */
    val id = uuid("id")

    /**
     * 対象 intent ID。
     */
    val intentId = uuid("intent_id").uniqueIndex()

    /**
     * verdict。
     */
    val verdict = varchar("verdict", length = 16)

    /**
     * Falsifier provider。
     */
    val llmProvider = varchar("llm_provider", length = 64).nullable()

    /**
     * 判定理由。
     */
    val reasonJa = text("reason_ja")

    /**
     * 作成時刻。epoch millis で保存する。
     */
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * trade intent の消費を append-only で保存する Exposed table。
 */
object TradeIntentConsumptionsTable : Table("trade_intent_consumptions") {
    /**
     * consumption ID。
     */
    val id = uuid("id")

    /**
     * 対象 intent ID。
     */
    val intentId = uuid("intent_id").uniqueIndex()

    /**
     * 消費元 order ID。
     */
    val orderId = uuid("order_id").nullable()

    /**
     * 消費時刻。epoch millis で保存する。
     */
    val consumedAt = long("consumed_at")

    override val primaryKey = PrimaryKey(id)
}
