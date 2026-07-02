package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.broker.PaperAccountConfig
import me.matsumo.fukurou.trading.config.TradingBotConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.math.BigDecimal
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * risk_state 初期行を作る SQL。
 */
private const val INSERT_DEFAULT_RISK_STATE_SQL = """
    INSERT INTO risk_state (
        id,
        hard_halt,
        drawdown_ratio,
        equity_peak,
        updated_at
    )
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT (id) DO NOTHING
"""

/**
 * risk_state の equity peak 初期値を補正する SQL。
 */
private const val UPDATE_EMPTY_RISK_STATE_EQUITY_PEAK_SQL = """
    UPDATE risk_state
    SET
        equity_peak = ?,
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
        market_snapshot_id
    FROM command_event_log
    LIMIT 0
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
 * trading persistence の最小 schema を起動時に用意する bootstrapper。
 *
 * @param database Exposed database
 * @param clock 初期 risk_state の updatedAt に使う clock
 * @param paperAccountConfig paper account 初期化設定
 */
class TradingPersistenceBootstrap(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val paperAccountConfig: PaperAccountConfig = TradingBotConfig.fromEnvironment().paperAccount,
) {

    /**
     * Exposed table と risk_state single row を用意する。
     */
    fun ensureSchema(): Result<Unit> {
        return runCatching {
            exposedTransaction(database) {
                @Suppress("DEPRECATION")
                SchemaUtils.createMissingTablesAndColumns(
                    RiskStateTable,
                    PaperAccountTable,
                    PositionsTable,
                    OrdersTable,
                    ExecutionsTable,
                    CommandEventLogTable,
                    SafetyViolationsTable,
                    DecisionsTable,
                    TradePlansTable,
                    TradeIntentsTable,
                    FalsificationsTable,
                    TradeIntentConsumptionsTable,
                    withLogs = false,
                )
                val now = Instant.now(clock)

                ensureRiskStateRow(now, paperAccountConfig.initialCashJpy)
                ensurePaperAccountRow(now, paperAccountConfig)
                ensureRiskStateEquityPeak(now, paperAccountConfig.initialCashJpy)
            }
        }
    }

    /**
     * schema と risk_state single row が backend bootstrap 済みであることだけを確認する。
     */
    fun verifySchema(): Result<Unit> {
        return runCatching {
            exposedTransaction(database) {
                verifyCommandEventLogSchema()
                verifyPaperAccountSchema()
                verifyPositionsSchema()
                verifyOrdersSchema()
                verifyExecutionsSchema()
                verifySafetyViolationsSchema()
                verifyDecisionsSchema()
                verifyTradePlansSchema()
                verifyTradeIntentsSchema()
                verifyFalsificationsSchema()
                verifyTradeIntentConsumptionsSchema()
                selectRiskState(forUpdate = false)
                selectPaperAccount()
            }
        }
    }
}

/**
 * command_event_log schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyCommandEventLogSchema() {
    jdbcConnection().prepareStatement(VERIFY_COMMAND_EVENT_LOG_SCHEMA_SQL).use { statement ->
        statement.executeQuery().use { resultSet ->
            requireNotNull(resultSet.metaData) { "command_event_log schema was not initialized." }
        }
    }
}

/**
 * paper_account schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyPaperAccountSchema() {
    verifySchemaBySql(
        sql = VERIFY_PAPER_ACCOUNT_SCHEMA_SQL,
        missingMessage = "paper_account schema was not initialized.",
    )
}

/**
 * positions schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyPositionsSchema() {
    verifySchemaBySql(
        sql = VERIFY_POSITIONS_SCHEMA_SQL,
        missingMessage = "positions schema was not initialized.",
    )
}

/**
 * orders schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyOrdersSchema() {
    verifySchemaBySql(
        sql = VERIFY_ORDERS_SCHEMA_SQL,
        missingMessage = "orders schema was not initialized.",
    )
}

/**
 * executions schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyExecutionsSchema() {
    verifySchemaBySql(
        sql = VERIFY_EXECUTIONS_SCHEMA_SQL,
        missingMessage = "executions schema was not initialized.",
    )
}

/**
 * safety_violations schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifySafetyViolationsSchema() {
    verifySchemaBySql(
        sql = VERIFY_SAFETY_VIOLATIONS_SCHEMA_SQL,
        missingMessage = "safety_violations schema was not initialized.",
    )
}

/**
 * decisions schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyDecisionsSchema() {
    verifySchemaBySql(
        sql = VERIFY_DECISIONS_SCHEMA_SQL,
        missingMessage = "decisions schema was not initialized.",
    )
}

/**
 * trade_plans schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyTradePlansSchema() {
    verifySchemaBySql(
        sql = VERIFY_TRADE_PLANS_SCHEMA_SQL,
        missingMessage = "trade_plans schema was not initialized.",
    )
}

/**
 * trade_intents schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyTradeIntentsSchema() {
    verifySchemaBySql(
        sql = VERIFY_TRADE_INTENTS_SCHEMA_SQL,
        missingMessage = "trade_intents schema was not initialized.",
    )
}

/**
 * falsifications schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyFalsificationsSchema() {
    verifySchemaBySql(
        sql = VERIFY_FALSIFICATIONS_SCHEMA_SQL,
        missingMessage = "falsifications schema was not initialized.",
    )
}

/**
 * trade_intent_consumptions schema が存在することを確認する。
 */
internal fun JdbcTransaction.verifyTradeIntentConsumptionsSchema() {
    verifySchemaBySql(
        sql = VERIFY_TRADE_INTENT_CONSUMPTIONS_SCHEMA_SQL,
        missingMessage = "trade_intent_consumptions schema was not initialized.",
    )
}

private fun JdbcTransaction.verifySchemaBySql(sql: String, missingMessage: String) {
    jdbcConnection().prepareStatement(sql).use { statement ->
        statement.executeQuery().use { resultSet ->
            requireNotNull(resultSet.metaData) { missingMessage }
        }
    }
}

/**
 * risk_state single row がなければ作成する。
 */
internal fun JdbcTransaction.ensureRiskStateRow(now: Instant, initialEquityPeak: BigDecimal = BigDecimal.ZERO) {
    jdbcConnection().prepareStatement(INSERT_DEFAULT_RISK_STATE_SQL).use { statement ->
        statement.setInt(1, RISK_STATE_SINGLE_ROW_ID)
        statement.setBoolean(2, false)
        statement.setBigDecimal(3, BigDecimal.ZERO)
        statement.setBigDecimal(4, initialEquityPeak)
        statement.setLong(5, now.toEpochMilli())
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
 * Exposed transaction が持つ JDBC connection を返す。
 */
internal fun JdbcTransaction.jdbcConnection(): Connection {
    return connection.connection as Connection
}
