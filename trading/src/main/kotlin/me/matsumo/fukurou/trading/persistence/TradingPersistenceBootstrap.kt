package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.broker.PaperAccountConfig
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.evaluation.EQUITY_SNAPSHOT_TRADING_DATE_ZONE
import me.matsumo.fukurou.trading.evaluation.EquitySnapshotReason
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_RUNNING
import me.matsumo.fukurou.trading.reflection.MAX_REFLECTION_LLM_TIMEOUT
import me.matsumo.fukurou.trading.risk.RiskHaltState
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.math.BigDecimal
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * stale な llm_runs を回収するまでの per-run timeout 乗数。
 */
private const val STALE_LLM_RUN_RECOVERY_TIMEOUT_MULTIPLIER = 3L

/**
 * persistence bootstrap で stale な llm_runs を FAILED へ回収するときの固定 error_message。
 */
internal const val STALE_LLM_RUN_RECOVERY_ERROR_MESSAGE =
    "LLM run was interrupted by a previous process or container shutdown and recovered during persistence bootstrap."

/**
 * risk_state 初期行を作る SQL。
 */
private const val INSERT_DEFAULT_RISK_STATE_SQL = """
    INSERT INTO risk_state (
        id,
        state,
        hard_halt,
        drawdown_ratio,
        equity_peak,
        updated_at
    )
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT (id) DO NOTHING
"""

/**
 * rollback 用 hard_halt から state を補正する SQL。
 */
private const val BACKFILL_RISK_STATE_HARD_HALT_SQL = """
    UPDATE risk_state
    SET state = ?
    WHERE hard_halt = TRUE
        AND state = ?
"""

/**
 * risk_state の equity peak 初期値を補正する SQL。
 */
private const val UPDATE_EMPTY_RISK_STATE_EQUITY_PEAK_SQL = """
    UPDATE risk_state
    SET
        equity_peak = ?,
        hard_halt = CASE
            WHEN state = 'HARD_HALT' THEN TRUE
            ELSE FALSE
        END,
        updated_at = ?
    WHERE id = ?
        AND equity_peak = 0
"""

/**
 * paper_account 初期行を作る SQL。
 */
private const val INSERT_DEFAULT_PAPER_ACCOUNT_SQL = """
    INSERT INTO paper_account (
        id,
        mode,
        initial_cash_jpy,
        cash_jpy,
        btc_quantity,
        btc_mark_price_jpy,
        total_equity_jpy,
        equity_peak_jpy,
        drawdown_ratio,
        created_at,
        updated_at
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (id) DO NOTHING
"""

/**
 * bootstrap equity snapshot を初回だけ作る SQL。
 */
private const val INSERT_BOOTSTRAP_EQUITY_SNAPSHOT_SQL = """
    INSERT INTO equity_snapshots (
        id,
        mode,
        reason,
        trading_date,
        captured_at,
        cash_jpy,
        btc_quantity,
        btc_mark_price_jpy,
        total_equity_jpy,
        equity_peak_jpy,
        drawdown_ratio
    )
    SELECT
        ?,
        mode,
        ?,
        ?,
        ?,
        cash_jpy,
        btc_quantity,
        btc_mark_price_jpy,
        total_equity_jpy,
        equity_peak_jpy,
        drawdown_ratio
    FROM paper_account
    WHERE id = ?
        AND NOT EXISTS (
            SELECT 1
            FROM equity_snapshots
        )
    ON CONFLICT (mode, reason) WHERE reason = 'BOOTSTRAP' DO NOTHING
"""

/**
 * 既存 OPEN position の lowest watermark を記録開始時点の保守値で埋める SQL。
 */
private const val BACKFILL_OPEN_POSITION_LOWEST_PRICE_SQL = """
    UPDATE positions
    SET lowest_price_since_entry_jpy = LEAST(average_entry_price_jpy, current_price_jpy)
    WHERE status = 'OPEN'
        AND lowest_price_since_entry_jpy IS NULL
"""

/**
 * stale な RUNNING llm_runs を FAILED へ回収する SQL。
 */
private const val RECOVER_STALE_LLM_RUNS_SQL = """
    UPDATE llm_runs
    SET
        status = ?,
        finished_at = ?,
        error_message = ?
    WHERE status = ?
        AND finished_at IS NULL
        AND started_at < ?
"""

/**
 * command_event_log schema の存在を確認する SQL。
 */
private const val VERIFY_COMMAND_EVENT_LOG_SCHEMA_SQL = """
    SELECT
        id,
        decision_run_id,
        tool_call_id,
        client_request_id,
        tool_name,
        event_type,
        payload,
        ts,
        llm_provider,
        prompt_hash,
        system_prompt_version,
        market_snapshot_id,
        runtime_config_version_id,
        runtime_config_hash
    FROM command_event_log
    LIMIT 0
"""

/**
 * risk_state schema の存在を確認する SQL。
 */
private const val VERIFY_RISK_STATE_SCHEMA_SQL = """
    SELECT
        id,
        state,
        hard_halt,
        drawdown_ratio,
        equity_peak,
        halt_reason,
        halt_at,
        resumed_at,
        resumed_reason,
        updated_at
    FROM risk_state
    LIMIT 0
"""

/**
 * llm_runs schema の存在を確認する SQL。
 */
private const val VERIFY_LLM_RUNS_SCHEMA_SQL = """
    SELECT
        invocation_id,
        mode,
        symbol,
        trigger_kind,
        status,
        started_at,
        finished_at,
        error_message,
        runtime_config_version_id,
        runtime_config_hash
    FROM llm_runs
    LIMIT 0
"""

/**
 * equity_snapshots schema の存在を確認する SQL。
 */
private const val VERIFY_EQUITY_SNAPSHOTS_SCHEMA_SQL = """
    SELECT
        id,
        mode,
        reason,
        trading_date,
        captured_at,
        cash_jpy,
        btc_quantity,
        btc_mark_price_jpy,
        total_equity_jpy,
        equity_peak_jpy,
        drawdown_ratio
    FROM equity_snapshots
    LIMIT 0
"""

/**
 * command_event_log の起動回数集計 index を作る SQL。
 */
private const val ENSURE_COMMAND_EVENT_LOG_TS_DECISION_RUN_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_command_event_log_ts_decision_run
    ON command_event_log (ts, decision_run_id)
    WHERE decision_run_id IS NOT NULL
"""

/**
 * command_event_log の tool call 集計 index を作る SQL。
 */
private const val ENSURE_COMMAND_EVENT_LOG_RUN_EVENT_TOOL_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_command_event_log_run_event_tool
    ON command_event_log (decision_run_id, event_type, tool_name)
"""

/**
 * orders.client_request_id の冪等化 unique index を作る SQL。
 */
private const val ENSURE_ORDERS_CLIENT_REQUEST_ID_UNIQUE_INDEX_SQL = """
    CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_client_request_id_unique
    ON orders (client_request_id)
    WHERE client_request_id IS NOT NULL
"""

/**
 * Activity execution context join 用の entry order index を作る SQL。
 */
private const val ENSURE_ORDERS_ACTIVITY_CONTEXT_ENTRY_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_orders_activity_context_entry
    ON orders (mode, side, trade_group_id, created_at, id)
    WHERE decision_run_id IS NOT NULL
        AND trade_group_id IS NOT NULL
"""

/**
 * Activity execution context join 用の decision lookup index を作る SQL。
 */
private const val ENSURE_DECISIONS_INVOCATION_ID_CREATED_AT_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_decisions_invocation_id_created_at
    ON decisions (invocation_id, created_at DESC)
    WHERE invocation_id IS NOT NULL
"""

/**
 * LLM 起動予約の invocation_id unique index を作る SQL。
 */
private const val ENSURE_LLM_LAUNCH_INVOCATION_UNIQUE_INDEX_SQL = """
    CREATE UNIQUE INDEX IF NOT EXISTS idx_llm_launch_reservations_invocation_id_unique
    ON llm_launch_reservations (invocation_id)
"""

/**
 * LLM 起動予約の trigger key cadence index を作る SQL。
 */
private const val ENSURE_LLM_LAUNCH_TRIGGER_KEY_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_llm_launch_reservations_trigger_key_reserved_at
    ON llm_launch_reservations (trigger_key, reserved_at)
"""

/**
 * LLM 起動予約の status / reserved_at index を作る SQL。
 */
private const val ENSURE_LLM_LAUNCH_STATUS_RESERVED_AT_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_llm_launch_reservations_status_reserved_at
    ON llm_launch_reservations (status, reserved_at)
"""

/**
 * llm_runs.started_at index を作る SQL。
 */
private const val ENSURE_LLM_RUNS_STARTED_AT_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_llm_runs_started_at
    ON llm_runs (started_at)
"""

/**
 * equity_snapshots.captured_at index を作る SQL。
 */
private const val ENSURE_EQUITY_SNAPSHOTS_CAPTURED_AT_INDEX_SQL = """
    CREATE INDEX IF NOT EXISTS idx_equity_snapshots_captured_at
    ON equity_snapshots (captured_at)
"""

/**
 * equity_snapshots の DAILY 日次一意 index を作る SQL。
 */
private const val ENSURE_EQUITY_SNAPSHOTS_DAILY_UNIQUE_INDEX_SQL = """
    CREATE UNIQUE INDEX IF NOT EXISTS idx_equity_snapshots_daily_unique
    ON equity_snapshots (mode, trading_date)
    WHERE reason = 'DAILY'
"""

/**
 * equity_snapshots の BOOTSTRAP 一意 index を作る SQL。
 */
private const val ENSURE_EQUITY_SNAPSHOTS_BOOTSTRAP_UNIQUE_INDEX_SQL = """
    CREATE UNIQUE INDEX IF NOT EXISTS idx_equity_snapshots_bootstrap_unique
    ON equity_snapshots (mode, reason)
    WHERE reason = 'BOOTSTRAP'
"""

/**
 * command_event_log の起動回数集計 index 存在を確認する SQL。
 */
private const val VERIFY_COMMAND_EVENT_LOG_TS_DECISION_RUN_INDEX_SQL = """
    SELECT 1
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'command_event_log'
        AND indexname = 'idx_command_event_log_ts_decision_run'
"""

/**
 * command_event_log の tool call 集計 index 存在を確認する SQL。
 */
private const val VERIFY_COMMAND_EVENT_LOG_RUN_EVENT_TOOL_INDEX_SQL = """
    SELECT 1
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'command_event_log'
        AND indexname = 'idx_command_event_log_run_event_tool'
"""

/**
 * orders.client_request_id unique index 存在を確認する SQL。
 */
private const val VERIFY_ORDERS_CLIENT_REQUEST_ID_UNIQUE_INDEX_SQL = """
    SELECT 1
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'orders'
        AND indexname = 'idx_orders_client_request_id_unique'
"""

/**
 * Activity execution context join 用 entry order index 存在を確認する SQL。
 */
private const val VERIFY_ORDERS_ACTIVITY_CONTEXT_ENTRY_INDEX_SQL = """
    SELECT 1
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'orders'
        AND indexname = 'idx_orders_activity_context_entry'
"""

/**
 * Activity execution context join 用 decision lookup index 存在を確認する SQL。
 */
private const val VERIFY_DECISIONS_INVOCATION_ID_CREATED_AT_INDEX_SQL = """
    SELECT 1
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'decisions'
        AND indexname = 'idx_decisions_invocation_id_created_at'
"""

/**
 * LLM 起動予約 index 存在を確認する SQL。
 */
private const val VERIFY_LLM_LAUNCH_INDEX_COUNT_SQL = """
    SELECT 1
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'llm_launch_reservations'
        AND indexname IN (
            'idx_llm_launch_reservations_invocation_id_unique',
            'idx_llm_launch_reservations_trigger_key_reserved_at',
            'idx_llm_launch_reservations_status_reserved_at'
        )
    HAVING COUNT(*) = 3
"""

/**
 * llm_runs index 存在を確認する SQL。
 */
private const val VERIFY_LLM_RUNS_INDEX_COUNT_SQL = """
    SELECT 1
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'llm_runs'
        AND indexname = 'idx_llm_runs_started_at'
"""

/**
 * equity_snapshots index 存在を確認する SQL。
 */
private const val VERIFY_EQUITY_SNAPSHOTS_INDEX_COUNT_SQL = """
    SELECT 1
    FROM pg_indexes
    WHERE schemaname = current_schema()
        AND tablename = 'equity_snapshots'
        AND indexname IN (
            'idx_equity_snapshots_captured_at',
            'idx_equity_snapshots_daily_unique',
            'idx_equity_snapshots_bootstrap_unique'
        )
    HAVING COUNT(*) = 3
"""

/**
 * paper_account schema の存在を確認する SQL。
 */
private const val VERIFY_PAPER_ACCOUNT_SCHEMA_SQL = """
    SELECT
        id,
        mode,
        initial_cash_jpy,
        cash_jpy,
        btc_quantity,
        btc_mark_price_jpy,
        total_equity_jpy,
        equity_peak_jpy,
        drawdown_ratio,
        created_at,
        updated_at
    FROM paper_account
    LIMIT 0
"""

/**
 * positions schema の存在を確認する SQL。
 */
private const val VERIFY_POSITIONS_SCHEMA_SQL = """
    SELECT
        id,
        trade_group_id,
        mode,
        symbol,
        side,
        status,
        opened_at,
        closed_at,
        size_btc,
        average_entry_price_jpy,
        current_price_jpy,
        current_stop_loss_jpy,
        current_take_profit_jpy,
        unrealized_pnl_jpy,
        unrealized_r,
        pyramid_add_count,
        highest_price_since_entry_jpy,
        lowest_price_since_entry_jpy,
        decision_run_id,
        tool_call_id,
        client_request_id,
        llm_provider,
        prompt_hash,
        system_prompt_version,
        market_snapshot_id
    FROM positions
    LIMIT 0
"""

/**
 * orders schema の存在を確認する SQL。
 */
private const val VERIFY_ORDERS_SCHEMA_SQL = """
    SELECT
        id,
        intent_id,
        position_id,
        trade_group_id,
        mode,
        symbol,
        side,
        order_type,
        status,
        size_btc,
        limit_price_jpy,
        trigger_price_jpy,
        protective_stop_price_jpy,
        take_profit_price_jpy,
        estimated_win_probability,
        reason_ja,
        decision_run_id,
        tool_call_id,
        client_request_id,
        llm_provider,
        prompt_hash,
        system_prompt_version,
        market_snapshot_id,
        created_at,
        updated_at
    FROM orders
    LIMIT 0
"""

/**
 * llm_launch_reservations schema の存在を確認する SQL。
 */
private const val VERIFY_LLM_LAUNCH_RESERVATIONS_SCHEMA_SQL = """
    SELECT
        id,
        invocation_id,
        trigger_kind,
        trigger_key,
        status,
        reserved_at,
        finished_at,
        reason
    FROM llm_launch_reservations
    LIMIT 0
"""

/**
 * executions schema の存在を確認する SQL。
 */
private const val VERIFY_EXECUTIONS_SCHEMA_SQL = """
    SELECT
        id,
        order_id,
        position_id,
        mode,
        symbol,
        side,
        price_jpy,
        size_btc,
        fee_jpy,
        realized_pnl_jpy,
        liquidity,
        executed_at,
        decision_run_id,
        tool_call_id,
        client_request_id,
        llm_provider,
        prompt_hash,
        system_prompt_version,
        market_snapshot_id
    FROM executions
    LIMIT 0
"""

/**
 * safety_violations schema の存在を確認する SQL。
 */
private const val VERIFY_SAFETY_VIOLATIONS_SCHEMA_SQL = """
    SELECT
        id,
        decision_run_id,
        tool_call_id,
        client_request_id,
        tool_name,
        command_id,
        order_id,
        rule,
        message_ja,
        measured_value,
        limit_value,
        hard_halt_required,
        payload,
        created_at
    FROM safety_violations
    LIMIT 0
"""

/**
 * decisions schema の存在を確認する SQL。
 */
private const val VERIFY_DECISIONS_SCHEMA_SQL = """
    SELECT
        id,
        invocation_id,
        llm_provider,
        prompt_hash,
        system_prompt_version,
        market_snapshot_id,
        action,
        close_ratio,
        setup_tags,
        estimated_win_probability,
        expected_r_multiple,
        round_trip_cost_r,
        tool_evidence_ids,
        fact_check,
        self_review,
        reason_ja,
        missing_data_ja,
        no_trade_conditions_ja,
        created_at
    FROM decisions
    LIMIT 0
"""

/**
 * trade_plans schema の存在を確認する SQL。
 */
private const val VERIFY_TRADE_PLANS_SCHEMA_SQL = """
    SELECT
        id,
        decision_id,
        parent_trade_plan_id,
        revision_count,
        symbol,
        thesis_ja,
        invalidation_conditions_ja,
        target_price_jpy,
        time_stop_at,
        setup_tags,
        created_at
    FROM trade_plans
    LIMIT 0
"""

/**
 * trade_intents schema の存在を確認する SQL。
 */
private const val VERIFY_TRADE_INTENTS_SCHEMA_SQL = """
    SELECT
        id,
        decision_id,
        trade_plan_id,
        symbol,
        side,
        order_type,
        size_btc,
        price_jpy,
        protective_stop_price_jpy,
        take_profit_price_jpy,
        estimated_win_probability,
        created_at
    FROM trade_intents
    LIMIT 0
"""

/**
 * falsifications schema の存在を確認する SQL。
 */
private const val VERIFY_FALSIFICATIONS_SCHEMA_SQL = """
    SELECT
        id,
        intent_id,
        verdict,
        llm_provider,
        reason_ja,
        created_at
    FROM falsifications
    LIMIT 0
"""

/**
 * trade_intent_consumptions schema の存在を確認する SQL。
 */
private const val VERIFY_TRADE_INTENT_CONSUMPTIONS_SCHEMA_SQL = """
    SELECT
        id,
        intent_id,
        order_id,
        consumed_at
    FROM trade_intent_consumptions
    LIMIT 0
"""

/**
 * equity snapshot の取引日判定に使う timezone。
 */
/**
 * trading persistence の最小 schema を起動時に用意する bootstrapper。
 *
 * @param database Exposed database
 * @param clock 初期 risk_state の updatedAt に使う clock
 * @param paperAccountConfig paper account 初期化設定
 * @param staleLlmRunRecoveryThreshold stale な RUNNING llm_runs と判定する経過時間
 * @param onStaleLlmRunsRecovered stale llm_runs 回収件数の通知
 */
class TradingPersistenceBootstrap(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val paperAccountConfig: PaperAccountConfig = TradingBotConfig.fromEnvironment().paperAccount,
    private val staleLlmRunRecoveryThreshold: Duration =
        TradingBotConfig.fromEnvironment().staleLlmRunRecoveryThreshold(),
    private val onStaleLlmRunsRecovered: (Int) -> Unit = {},
) {

    init {
        val thresholdIsPositive = !staleLlmRunRecoveryThreshold.isNegative && !staleLlmRunRecoveryThreshold.isZero

        require(thresholdIsPositive) { "staleLlmRunRecoveryThreshold must be greater than 0." }
    }

    /**
     * Exposed table と risk_state single row を用意する。
     */
    fun ensureSchema(): Result<Unit> {
        return runCatching {
            val recoveredCount = exposedTransaction(database) {
                @Suppress("DEPRECATION")
                SchemaUtils.createMissingTablesAndColumns(
                    RuntimeConfigVersionsTable,
                    RuntimeConfigValuesTable,
                    RiskStateTable,
                    PaperAccountTable,
                    LlmRunsTable,
                    EquitySnapshotsTable,
                    PositionsTable,
                    OrdersTable,
                    ExecutionsTable,
                    CommandEventLogTable,
                    LlmLaunchReservationsTable,
                    SafetyViolationsTable,
                    DecisionsTable,
                    TradePlansTable,
                    TradeIntentsTable,
                    FalsificationsTable,
                    TradeIntentConsumptionsTable,
                    withLogs = false,
                )
                ensureRuntimeSchemaObjects()
                val now = Instant.now(clock)

                ensureActiveRuntimeConfigVersion(now)
                ensureRiskStateRow(now, paperAccountConfig.initialCashJpy)
                jdbcConnection().prepareStatement(BACKFILL_RISK_STATE_HARD_HALT_SQL).use { statement ->
                    statement.setString(1, RiskHaltState.HARD_HALT.name)
                    statement.setString(2, RiskHaltState.RUNNING.name)
                    statement.executeUpdate()
                }
                ensurePaperAccountRow(now, paperAccountConfig)
                ensureRiskStateEquityPeak(now, paperAccountConfig.initialCashJpy)
                ensureBootstrapEquitySnapshot(now)
                recoverStaleLlmRuns(now, staleLlmRunRecoveryThreshold)
            }

            if (recoveredCount > 0) {
                onStaleLlmRunsRecovered(recoveredCount)
            }
        }
    }

    /**
     * schema と risk_state single row が backend bootstrap 済みであることだけを確認する。
     */
    fun verifySchema(): Result<Unit> {
        return runCatching {
            exposedTransaction(database) {
                verifyRuntimeSchemaObjects()
                selectRiskState(forUpdate = false)
                selectPaperAccount()
            }
        }
    }
}

/**
 * stale な RUNNING llm_runs の回収閾値を取引設定から算出する。
 */
fun TradingBotConfig.staleLlmRunRecoveryThreshold(): Duration {
    val runnerThreshold = runner.perRunTimeout.multipliedBy(STALE_LLM_RUN_RECOVERY_TIMEOUT_MULTIPLIER)
    val reflectionThreshold = MAX_REFLECTION_LLM_TIMEOUT.multipliedBy(STALE_LLM_RUN_RECOVERY_TIMEOUT_MULTIPLIER)

    return runnerThreshold.coerceAtLeast(reflectionThreshold)
}

/**
 * runtime schema の補助 index / backfill を適用する。
 */
private fun JdbcTransaction.ensureRuntimeSchemaObjects() {
    ensureRuntimeConfigIndexes()
    executeUpdate(ENSURE_COMMAND_EVENT_LOG_TS_DECISION_RUN_INDEX_SQL)
    executeUpdate(ENSURE_COMMAND_EVENT_LOG_RUN_EVENT_TOOL_INDEX_SQL)
    executeUpdate(ENSURE_ORDERS_CLIENT_REQUEST_ID_UNIQUE_INDEX_SQL)
    executeUpdate(ENSURE_ORDERS_ACTIVITY_CONTEXT_ENTRY_INDEX_SQL)
    executeUpdate(ENSURE_DECISIONS_INVOCATION_ID_CREATED_AT_INDEX_SQL)
    executeUpdate(ENSURE_LLM_LAUNCH_INVOCATION_UNIQUE_INDEX_SQL)
    executeUpdate(ENSURE_LLM_LAUNCH_TRIGGER_KEY_INDEX_SQL)
    executeUpdate(ENSURE_LLM_LAUNCH_STATUS_RESERVED_AT_INDEX_SQL)
    executeUpdate(ENSURE_LLM_RUNS_STARTED_AT_INDEX_SQL)
    executeUpdate(ENSURE_EQUITY_SNAPSHOTS_CAPTURED_AT_INDEX_SQL)
    executeUpdate(ENSURE_EQUITY_SNAPSHOTS_DAILY_UNIQUE_INDEX_SQL)
    executeUpdate(ENSURE_EQUITY_SNAPSHOTS_BOOTSTRAP_UNIQUE_INDEX_SQL)
    executeUpdate(BACKFILL_OPEN_POSITION_LOWEST_PRICE_SQL)
}

/**
 * runtime schema と補助 index が存在することを確認する。
 */
private fun JdbcTransaction.verifyRuntimeSchemaObjects() {
    verifyRuntimeConfigSchema()
    verifyAccountRuntimeSchemaObjects()
    verifyLedgerRuntimeSchemaObjects()
    verifyDecisionRuntimeSchemaObjects()
}

private fun JdbcTransaction.verifyAccountRuntimeSchemaObjects() {
    verifySchemaBySql(
        sql = VERIFY_RISK_STATE_SCHEMA_SQL,
        missingMessage = "risk_state schema was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_COMMAND_EVENT_LOG_SCHEMA_SQL,
        missingMessage = "command_event_log schema was not initialized.",
    )
    verifyExistsBySql(
        sql = VERIFY_COMMAND_EVENT_LOG_TS_DECISION_RUN_INDEX_SQL,
        missingMessage = "command_event_log ts/decision_run_id index was not initialized.",
    )
    verifyExistsBySql(
        sql = VERIFY_COMMAND_EVENT_LOG_RUN_EVENT_TOOL_INDEX_SQL,
        missingMessage = "command_event_log run/event/tool index was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_PAPER_ACCOUNT_SCHEMA_SQL,
        missingMessage = "paper_account schema was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_LLM_RUNS_SCHEMA_SQL,
        missingMessage = "llm_runs schema was not initialized.",
    )
    verifyExistsBySql(
        sql = VERIFY_LLM_RUNS_INDEX_COUNT_SQL,
        missingMessage = "llm_runs indexes were not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_EQUITY_SNAPSHOTS_SCHEMA_SQL,
        missingMessage = "equity_snapshots schema was not initialized.",
    )
    verifyExistsBySql(
        sql = VERIFY_EQUITY_SNAPSHOTS_INDEX_COUNT_SQL,
        missingMessage = "equity_snapshots indexes were not initialized.",
    )
}

private fun JdbcTransaction.verifyLedgerRuntimeSchemaObjects() {
    verifySchemaBySql(
        sql = VERIFY_POSITIONS_SCHEMA_SQL,
        missingMessage = "positions schema was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_ORDERS_SCHEMA_SQL,
        missingMessage = "orders schema was not initialized.",
    )
    verifyExistsBySql(
        sql = VERIFY_ORDERS_CLIENT_REQUEST_ID_UNIQUE_INDEX_SQL,
        missingMessage = "orders client_request_id unique index was not initialized.",
    )
    verifyExistsBySql(
        sql = VERIFY_ORDERS_ACTIVITY_CONTEXT_ENTRY_INDEX_SQL,
        missingMessage = "orders activity context entry index was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_LLM_LAUNCH_RESERVATIONS_SCHEMA_SQL,
        missingMessage = "llm_launch_reservations schema was not initialized.",
    )
    verifyExistsBySql(
        sql = VERIFY_LLM_LAUNCH_INDEX_COUNT_SQL,
        missingMessage = "llm_launch_reservations indexes were not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_EXECUTIONS_SCHEMA_SQL,
        missingMessage = "executions schema was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_SAFETY_VIOLATIONS_SCHEMA_SQL,
        missingMessage = "safety_violations schema was not initialized.",
    )
}

private fun JdbcTransaction.verifyDecisionRuntimeSchemaObjects() {
    verifySchemaBySql(
        sql = VERIFY_DECISIONS_SCHEMA_SQL,
        missingMessage = "decisions schema was not initialized.",
    )
    verifyExistsBySql(
        sql = VERIFY_DECISIONS_INVOCATION_ID_CREATED_AT_INDEX_SQL,
        missingMessage = "decisions invocation_id/created_at index was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_TRADE_PLANS_SCHEMA_SQL,
        missingMessage = "trade_plans schema was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_TRADE_INTENTS_SCHEMA_SQL,
        missingMessage = "trade_intents schema was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_FALSIFICATIONS_SCHEMA_SQL,
        missingMessage = "falsifications schema was not initialized.",
    )
    verifySchemaBySql(
        sql = VERIFY_TRADE_INTENT_CONSUMPTIONS_SCHEMA_SQL,
        missingMessage = "trade_intent_consumptions schema was not initialized.",
    )
}

/**
 * コードが所有する schema verification SQL を実行する。
 */
@Suppress("SqlSourceToSinkFlow")
internal fun JdbcTransaction.verifySchemaBySql(sql: String, missingMessage: String) {
    jdbcConnection().prepareStatement(sql).use { statement ->
        statement.executeQuery().use { resultSet ->
            requireNotNull(resultSet.metaData) { missingMessage }
        }
    }
}

/**
 * コードが所有する existence verification SQL を実行する。
 */
@Suppress("SqlSourceToSinkFlow")
internal fun JdbcTransaction.verifyExistsBySql(sql: String, missingMessage: String) {
    jdbcConnection().prepareStatement(sql).use { statement ->
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { missingMessage }
        }
    }
}

/**
 * コードが所有する migration SQL を実行する。
 */
@Suppress("SqlSourceToSinkFlow")
internal fun JdbcTransaction.executeUpdate(sql: String) {
    jdbcConnection().prepareStatement(sql).use { statement ->
        statement.executeUpdate()
    }
}

/**
 * risk_state single row がなければ作成する。
 */
internal fun JdbcTransaction.ensureRiskStateRow(now: Instant, initialEquityPeak: BigDecimal = BigDecimal.ZERO) {
    jdbcConnection().prepareStatement(INSERT_DEFAULT_RISK_STATE_SQL).use { statement ->
        statement.setInt(1, RISK_STATE_SINGLE_ROW_ID)
        statement.setString(2, RiskHaltState.RUNNING.name)
        statement.setBoolean(3, false)
        statement.setBigDecimal(4, BigDecimal.ZERO)
        statement.setBigDecimal(5, initialEquityPeak)
        statement.setLong(6, now.toEpochMilli())
        statement.executeUpdate()
    }
}

/**
 * paper_account single row がなければ作成する。
 */
internal fun JdbcTransaction.ensurePaperAccountRow(now: Instant, config: PaperAccountConfig) {
    jdbcConnection().prepareStatement(INSERT_DEFAULT_PAPER_ACCOUNT_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setString(2, config.mode.name)
        statement.setBigDecimal(3, config.initialCashJpy)
        statement.setBigDecimal(4, config.initialCashJpy)
        statement.setBigDecimal(5, BigDecimal.ZERO)
        statement.setBigDecimal(6, BigDecimal.ZERO)
        statement.setBigDecimal(7, config.initialCashJpy)
        statement.setBigDecimal(8, config.initialCashJpy)
        statement.setBigDecimal(9, BigDecimal.ZERO)
        statement.setLong(10, now.toEpochMilli())
        statement.setLong(11, now.toEpochMilli())
        statement.executeUpdate()
    }
}

/**
 * 既存 risk_state の equity peak が空なら paper 初期 equity と揃える。
 */
internal fun JdbcTransaction.ensureRiskStateEquityPeak(now: Instant, initialEquityPeak: BigDecimal) {
    jdbcConnection().prepareStatement(UPDATE_EMPTY_RISK_STATE_EQUITY_PEAK_SQL).use { statement ->
        statement.setBigDecimal(1, initialEquityPeak)
        statement.setLong(2, now.toEpochMilli())
        statement.setInt(3, RISK_STATE_SINGLE_ROW_ID)
        statement.executeUpdate()
    }
}

/**
 * equity_snapshots が空なら bootstrap snapshot を作成する。
 */
internal fun JdbcTransaction.ensureBootstrapEquitySnapshot(now: Instant) {
    jdbcConnection().prepareStatement(INSERT_BOOTSTRAP_EQUITY_SNAPSHOT_SQL).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setString(2, EquitySnapshotReason.BOOTSTRAP.name)
        statement.setString(3, now.atZone(EQUITY_SNAPSHOT_TRADING_DATE_ZONE).toLocalDate().toString())
        statement.setLong(4, now.toEpochMilli())
        statement.setInt(5, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeUpdate()
    }
}

/**
 * stale な RUNNING llm_runs を FAILED へ回収する。
 */
internal fun JdbcTransaction.recoverStaleLlmRuns(now: Instant, threshold: Duration): Int {
    val cutoff = now.minus(threshold)

    jdbcConnection().prepareStatement(RECOVER_STALE_LLM_RUNS_SQL).use { statement ->
        statement.setString(1, LLM_RUN_STATUS_FAILED)
        statement.setLong(2, now.toEpochMilli())
        statement.setString(3, STALE_LLM_RUN_RECOVERY_ERROR_MESSAGE)
        statement.setString(4, LLM_RUN_STATUS_RUNNING)
        statement.setLong(5, cutoff.toEpochMilli())

        return statement.executeUpdate()
    }
}

/**
 * Exposed transaction が持つ JDBC connection を返す。
 */
internal fun JdbcTransaction.jdbcConnection(): Connection {
    return connection.connection as Connection
}
