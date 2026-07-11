package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.activity.DecisionRunCursor
import me.matsumo.fukurou.trading.activity.DecisionRunDecision
import me.matsumo.fukurou.trading.activity.DecisionRunDetail
import me.matsumo.fukurou.trading.activity.DecisionRunExecution
import me.matsumo.fukurou.trading.activity.DecisionRunFalsification
import me.matsumo.fukurou.trading.activity.DecisionRunFilter
import me.matsumo.fukurou.trading.activity.DecisionRunIntent
import me.matsumo.fukurou.trading.activity.DecisionRunOrder
import me.matsumo.fukurou.trading.activity.DecisionRunOutcomeEvidence
import me.matsumo.fukurou.trading.activity.DecisionRunPage
import me.matsumo.fukurou.trading.activity.DecisionRunProjectionRepository
import me.matsumo.fukurou.trading.activity.DecisionRunRawRecord
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenial
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialPage
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialQuery
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialReader
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyViolation
import me.matsumo.fukurou.trading.activity.DecisionRunSummary
import me.matsumo.fukurou.trading.activity.DecisionRunTradeLifecycle
import me.matsumo.fukurou.trading.activity.classifyDecisionRunOutcome
import me.matsumo.fukurou.trading.activity.matches
import me.matsumo.fukurou.trading.activity.safeDecisionRunFinalReason
import me.matsumo.fukurou.trading.activity.withStrategyEvaluation
import me.matsumo.fukurou.trading.broker.VIRTUAL_TAKE_PROFIT_TRIGGER_REASON
import me.matsumo.fukurou.trading.domain.OrderStatus
import me.matsumo.fukurou.trading.domain.PaperOrderCancelReason
import me.matsumo.fukurou.trading.domain.PaperOrderLifecyclePolicy
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_CANCELLED
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunTerminalCause
import me.matsumo.fukurou.trading.safety.SafetyFloorRule
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** domain policy から生成する resting entry role SQL。 */
private val RESTING_ENTRY_ROLE_SQL = buildString {
    append("side = '")
    append(PaperOrderLifecyclePolicy.restingEntrySide.name)
    append("' AND position_id IS NULL AND order_type IN (")
    append(PaperOrderLifecyclePolicy.restingEntryTypes.joinToString { type -> "'${type.name}'" })
    append(")")
}

/** domain policy から生成する resting entry lifecycle status SQL。 */
private val RESTING_ENTRY_LIFECYCLE_STATUS_SQL = PaperOrderLifecyclePolicy.lifecycleStatuses
    .joinToString(prefix = "status IN (", postfix = ")") { status -> "'${status.name}'" }

/** outcome filter が 1 query で走査する raw run 件数。 */
internal const val FILTER_SCAN_BATCH_SIZE = 100

/** outcome filter が 1 request で走査する最大 batch 数。 */
internal const val MAX_FILTER_SCAN_BATCHES = 10

/** TEXT payload が valid JSON の場合だけ reason を抽出する PostgreSQL 式。 */
private const val SAFE_NO_TRADE_REASON_EXPRESSION =
    "CASE WHEN pg_input_is_valid(payload, 'jsonb') THEN payload::jsonb ->> 'reason' ELSE NULL END"

private val LIST_RUNS_SQL = """
    WITH candidate_runs AS (
        SELECT invocation_id, mode, symbol, trigger_kind, status, started_at, finished_at, error_message, terminal_cause
        FROM llm_runs
        WHERE trigger_kind IS DISTINCT FROM 'REFLECTION'
            AND (CAST(? AS BIGINT) IS NULL OR started_at < ? OR (started_at = ? AND invocation_id < ?))
        ORDER BY started_at DESC, invocation_id DESC
        LIMIT ?
    )
    SELECT
        run.invocation_id,
        run.mode,
        run.symbol,
        run.trigger_kind,
        run.status,
        run.started_at,
        run.finished_at,
        run.error_message,
        run.terminal_cause,
        decision.action,
        decision.reason_ja,
        falsification.verdict,
        safety.rule,
        safety.message_ja,
        order_count.value AS order_count,
        order_count.filled_value AS filled_order_count,
        order_count.open_value AS open_order_count,
        order_count.expiring_value AS expiring_open_order_count,
        order_count.overdue_value AS overdue_open_order_count,
        order_count.ttl_canceled_value AS ttl_canceled_order_count,
        order_count.canceled_value AS canceled_entry_order_count,
        cancellation_actor.value AS actor_canceled_order_count,
        execution_count.value AS execution_count,
        no_trade.reason AS no_trade_reason,
        no_trade.present AS has_no_trade_exit,
        entry_order.id AS entry_order_id,
        entry_order.intent_id AS entry_intent_id,
        entry_order.position_id AS entry_position_id,
        entry_order.trade_group_id AS entry_trade_group_id,
        entry_order.side AS entry_side,
        entry_order.order_type AS entry_order_type,
        entry_order.status AS entry_status,
        entry_order.size_btc AS entry_size_btc,
        entry_order.limit_price_jpy AS entry_limit_price_jpy,
        entry_order.reason_ja AS entry_reason_ja,
        entry_order.expires_at AS entry_expires_at,
        entry_order.expiry_source AS entry_expiry_source,
        entry_order.effective_ttl_seconds AS entry_effective_ttl_seconds,
        entry_order.expired_at AS entry_expired_at,
        entry_order.canceled_at AS entry_canceled_at,
        entry_order.cancel_reason AS entry_cancel_reason,
        entry_order.canceled_by_decision_run_id AS entry_canceled_by_decision_run_id,
        entry_order.created_at AS entry_created_at
    FROM candidate_runs run
    LEFT JOIN LATERAL (
        SELECT id, action, reason_ja
        FROM decisions
        WHERE invocation_id = run.invocation_id
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    ) decision ON TRUE
    LEFT JOIN LATERAL (
        SELECT f.verdict
        FROM trade_intents intent
        JOIN falsifications f ON f.intent_id = intent.id
        WHERE intent.decision_id = decision.id
        ORDER BY f.created_at DESC, f.id DESC
        LIMIT 1
    ) falsification ON TRUE
    LEFT JOIN LATERAL (
        SELECT rule, message_ja
        FROM safety_violations
        WHERE decision_run_id = run.invocation_id
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    ) safety ON TRUE
    LEFT JOIN LATERAL (
        SELECT
            COUNT(*)::INT AS value,
            COUNT(*) FILTER (WHERE status = ?)::INT AS filled_value,
            COUNT(*) FILTER (WHERE $RESTING_ENTRY_ROLE_SQL AND status = ?)::INT AS open_value,
            COUNT(*) FILTER (
                WHERE $RESTING_ENTRY_ROLE_SQL AND $RESTING_ENTRY_LIFECYCLE_STATUS_SQL
                    AND expires_at IS NOT NULL AND expires_at <= ?
            )::INT AS expiring_value,
            COUNT(*) FILTER (
                WHERE $RESTING_ENTRY_ROLE_SQL
                    AND status = ? AND expires_at IS NOT NULL AND expires_at < ?
            )::INT AS overdue_value,
            COUNT(*) FILTER (
                WHERE $RESTING_ENTRY_ROLE_SQL
                    AND status = ? AND cancel_reason = ?
            )::INT AS ttl_canceled_value,
            COUNT(*) FILTER (
                WHERE $RESTING_ENTRY_ROLE_SQL
                    AND status = ? AND cancel_reason IS DISTINCT FROM ?
            )::INT AS canceled_value
        FROM orders
        WHERE decision_run_id = run.invocation_id
    ) order_count ON TRUE
    LEFT JOIN LATERAL (
        SELECT COUNT(*)::INT AS value
        FROM orders
        WHERE canceled_by_decision_run_id = run.invocation_id
            AND status = ?
            AND cancel_reason IS DISTINCT FROM ?
    ) cancellation_actor ON TRUE
    LEFT JOIN LATERAL (
        SELECT *
        FROM orders
        WHERE decision_run_id = run.invocation_id
            AND side = 'BUY'
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    ) entry_order ON TRUE
    LEFT JOIN LATERAL (
        SELECT COUNT(*)::INT AS value
        FROM executions
        WHERE decision_run_id = run.invocation_id
    ) execution_count ON TRUE
    LEFT JOIN LATERAL (
        SELECT $SAFE_NO_TRADE_REASON_EXPRESSION AS reason, TRUE AS present
        FROM command_event_log
        WHERE decision_run_id = run.invocation_id
            AND event_type = 'NO_TRADE_EXIT'
        ORDER BY ts DESC, id DESC
        LIMIT 1
    ) no_trade ON TRUE
    ORDER BY run.started_at DESC, run.invocation_id DESC
"""

private val FIND_RUN_SQL = """
    SELECT
        run.invocation_id,
        run.mode,
        run.symbol,
        run.trigger_kind,
        run.status,
        run.started_at,
        run.finished_at,
        run.error_message,
        run.terminal_cause,
        decision.id AS decision_id,
        decision.action,
        decision.llm_provider,
        decision.estimated_win_probability,
        decision.expected_r_multiple,
        decision.round_trip_cost_r,
        decision.reason_ja,
        decision.setup_tags,
        decision.missing_data_ja,
        decision.no_trade_conditions_ja,
        decision.created_at AS decision_created_at,
        intent.id AS intent_id,
        intent.trade_plan_id,
        intent.side AS intent_side,
        intent.order_type AS intent_order_type,
        intent.size_btc AS intent_size_btc,
        intent.price_jpy AS intent_price_jpy,
        intent.protective_stop_price_jpy,
        intent.take_profit_price_jpy,
        plan.parent_trade_plan_id,
        plan.revision_count,
        plan.thesis_ja,
        plan.invalidation_conditions_ja,
        plan.target_price_jpy,
        plan.time_stop_at,
        plan.setup_tags AS plan_setup_tags,
        falsification.verdict,
        falsification.llm_provider AS falsification_provider,
        falsification.reason_ja AS falsification_reason_ja,
        falsification.created_at AS falsification_created_at,
        safety.rule,
        safety.measured_value,
        safety.limit_value,
        safety.message_ja,
        safety.created_at AS safety_created_at,
        (SELECT COUNT(*) FROM orders WHERE decision_run_id = run.invocation_id) AS order_count,
        (
            SELECT COUNT(*)
            FROM orders
            WHERE decision_run_id = run.invocation_id
                AND status = ?
        ) AS filled_order_count,
        (SELECT COUNT(*) FROM executions WHERE decision_run_id = run.invocation_id) AS execution_count,
        (
            SELECT COUNT(*) FROM orders
            WHERE decision_run_id = run.invocation_id
                AND $RESTING_ENTRY_ROLE_SQL AND status = ?
        ) AS open_order_count,
        (
            SELECT COUNT(*) FROM orders
            WHERE decision_run_id = run.invocation_id
                AND $RESTING_ENTRY_ROLE_SQL AND $RESTING_ENTRY_LIFECYCLE_STATUS_SQL
                AND expires_at IS NOT NULL AND expires_at <= ?
        ) AS expiring_open_order_count,
        (
            SELECT COUNT(*) FROM orders
            WHERE decision_run_id = run.invocation_id
                AND $RESTING_ENTRY_ROLE_SQL AND status = ?
                AND expires_at IS NOT NULL AND expires_at < ?
        ) AS overdue_open_order_count,
        (
            SELECT COUNT(*) FROM orders
            WHERE decision_run_id = run.invocation_id
                AND $RESTING_ENTRY_ROLE_SQL
                AND status = ? AND cancel_reason = ?
        ) AS ttl_canceled_order_count,
        (
            SELECT COUNT(*) FROM orders
            WHERE decision_run_id = run.invocation_id
                AND $RESTING_ENTRY_ROLE_SQL
                AND status = ? AND cancel_reason IS DISTINCT FROM ?
        ) AS canceled_entry_order_count,
        (
            SELECT COUNT(*) FROM orders
            WHERE canceled_by_decision_run_id = run.invocation_id
                AND status = ? AND cancel_reason IS DISTINCT FROM ?
        ) AS actor_canceled_order_count,
        no_trade.reason AS no_trade_reason,
        no_trade.present AS has_no_trade_exit
    FROM llm_runs run
    LEFT JOIN LATERAL (
        SELECT * FROM decisions
        WHERE invocation_id = run.invocation_id
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    ) decision ON TRUE
    LEFT JOIN LATERAL (
        SELECT * FROM trade_intents
        WHERE decision_id = decision.id
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    ) intent ON TRUE
    LEFT JOIN trade_plans plan ON plan.id = intent.trade_plan_id
    LEFT JOIN LATERAL (
        SELECT * FROM falsifications
        WHERE intent_id = intent.id
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    ) falsification ON TRUE
    LEFT JOIN LATERAL (
        SELECT * FROM safety_violations
        WHERE decision_run_id = run.invocation_id
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    ) safety ON TRUE
    LEFT JOIN LATERAL (
        SELECT $SAFE_NO_TRADE_REASON_EXPRESSION AS reason, TRUE AS present
        FROM command_event_log
        WHERE decision_run_id = run.invocation_id
            AND event_type = 'NO_TRADE_EXIT'
        ORDER BY ts DESC, id DESC
        LIMIT 1
    ) no_trade ON TRUE
    WHERE run.invocation_id = ?
        AND run.trigger_kind IS DISTINCT FROM 'REFLECTION'
"""

private const val FIND_ORDERS_SQL = """
    SELECT id, intent_id, position_id, trade_group_id, side, order_type, status, size_btc,
        limit_price_jpy, reason_ja, expires_at, expiry_source, effective_ttl_seconds,
        expired_at, canceled_at, cancel_reason, canceled_by_decision_run_id, created_at
    FROM orders
    WHERE decision_run_id = ?
    ORDER BY created_at ASC, id ASC
"""

private const val FIND_EXECUTIONS_SQL = """
    SELECT execution.id, execution.order_id, execution.position_id, execution.side, execution.price_jpy,
        execution.size_btc, execution.fee_jpy, execution.realized_pnl_jpy, execution.liquidity,
        execution.executed_at, "order".order_type
    FROM executions execution
    LEFT JOIN orders "order" ON "order".id = execution.order_id
    WHERE execution.decision_run_id = ?
    ORDER BY execution.executed_at ASC, execution.id ASC
"""

private const val FIND_TRADE_LIFECYCLES_SQL = """
    -- intent_id is persisted only for ENTER / ADD_LONG orders; STOP, EXIT, and direct executions do not anchor a lifecycle.
    WITH entry_executions AS (
        SELECT execution.id, execution.position_id, execution.executed_at
        FROM executions execution
        JOIN orders entry_order ON entry_order.id = execution.order_id
        WHERE entry_order.decision_run_id = ?
            AND entry_order.intent_id IS NOT NULL
            AND execution.position_id IS NOT NULL
    ), entry_anchors AS (
        SELECT DISTINCT ON (position_id) id, position_id, executed_at
        FROM entry_executions
        ORDER BY position_id, executed_at ASC, id ASC
    )
    SELECT execution.id, execution.order_id, execution.position_id, execution.side, execution.price_jpy,
        execution.size_btc, execution.fee_jpy, execution.realized_pnl_jpy, execution.liquidity,
        execution.executed_at, "order".order_type, position.status AS position_status,
        CASE
            WHEN entry_executions.id IS NOT NULL THEN 'ENTRY'
            WHEN execution.side = 'BUY' THEN 'POSITION_ENTRY'
            WHEN execution.side = 'SELL' AND "order".order_type = 'STOP' THEN 'STOP'
            WHEN execution.side = 'SELL' AND "order".order_type = 'LIMIT' THEN 'TAKE_PROFIT'
            WHEN execution.side = 'SELL' AND "order".order_type = 'MARKET'
                AND "order".reason_ja = ? THEN 'TAKE_PROFIT'
            WHEN execution.side = 'SELL' AND "order".order_type = 'MARKET' THEN 'MANUAL_CLOSE'
            ELSE 'POSITION_EXECUTION'
        END AS execution_kind
    FROM executions execution
    JOIN entry_anchors anchor ON anchor.position_id = execution.position_id
        AND (
            execution.executed_at > anchor.executed_at
            OR (execution.executed_at = anchor.executed_at AND execution.id >= anchor.id)
        )
    LEFT JOIN entry_executions ON entry_executions.id = execution.id
    LEFT JOIN orders "order" ON "order".id = execution.order_id
    LEFT JOIN positions position ON position.id = execution.position_id
    ORDER BY execution.executed_at ASC, execution.id ASC
"""

private const val FIND_SAFE_AUDIT_SQL = """
    SELECT event_type, tool_name, ts
    FROM command_event_log
    WHERE decision_run_id = ?
    ORDER BY ts ASC, id ASC
"""

private const val FIND_SAFETY_DENIAL_INVOCATIONS_SQL = """
    WITH ranked_denials AS (
        SELECT safety.decision_run_id, safety.created_at, safety.id,
            ROW_NUMBER() OVER (
                PARTITION BY safety.decision_run_id
                ORDER BY safety.created_at DESC, safety.id DESC
            ) AS row_number
        FROM safety_violations safety
        JOIN llm_runs run ON run.invocation_id = safety.decision_run_id
        WHERE safety.decision_run_id IS NOT NULL
            AND run.symbol = ?
            AND run.trigger_kind IS DISTINCT FROM 'REFLECTION'
            AND safety.created_at >= ?
            AND safety.created_at < ?
    )
    SELECT decision_run_id
    FROM ranked_denials
    WHERE row_number = 1
    ORDER BY created_at DESC, id DESC
    LIMIT ? OFFSET ?
"""

/** SafetyFloor denial reader が一度に outcome 判定する候補数。 */
private const val SAFETY_DENIAL_SCAN_BATCH_SIZE = 100

/** SafetyFloor denial reader が1 requestで走査する最大 batch 数。 */
private const val MAX_SAFETY_DENIAL_SCAN_BATCHES = 10

/**
 * PostgreSQL の append-only 台帳から decision run projection を構築する repository。
 *
 * runner の 1 run = 1 decision = 最大 1 entry intent という保存 invariant に従い最新 intent を選び、
 * order / execution は decision_run_id に紐づく全件を監査 projection として返す。
 */
class ExposedDecisionRunProjectionRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : DecisionRunProjectionRepository, DecisionRunSafetyDenialReader {

    override suspend fun listRuns(
        cursor: DecisionRunCursor?,
        limit: Int,
        filter: DecisionRunFilter?,
    ): Result<DecisionRunPage> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(limit > 0) { "limit must be greater than 0." }
                exposedTransaction(database) { selectRuns(cursor, limit, filter, clock.instant()) }
            }
        }
    }

    override suspend fun findRun(invocationId: String): Result<DecisionRunDetail?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) { selectRunDetail(invocationId, clock.instant()) }
            }
        }
    }

    override suspend fun readSafetyDenials(query: DecisionRunSafetyDenialQuery): Result<DecisionRunSafetyDenialPage> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(query.limit > 0) { "limit must be greater than 0." }
                exposedTransaction(database) { selectSafetyDenials(query, clock.instant()) }
            }
        }
    }
}

private fun JdbcTransaction.selectSafetyDenials(
    query: DecisionRunSafetyDenialQuery,
    observedAt: Instant,
): DecisionRunSafetyDenialPage {
    val selected = mutableListOf<DecisionRunSafetyDenial>()

    repeat(MAX_SAFETY_DENIAL_SCAN_BATCHES) { batchIndex ->
        val candidates = selectSafetyDenialCandidates(query, batchIndex)

        for (invocationId in candidates) {
            val denial = selectRunDetailBase(invocationId, observedAt).toSafetyDenialOrNull() ?: continue
            selected += denial

            if (selected.size > query.limit) {
                return DecisionRunSafetyDenialPage(selected.take(query.limit), truncated = true)
            }
        }

        if (candidates.size < SAFETY_DENIAL_SCAN_BATCH_SIZE) {
            return DecisionRunSafetyDenialPage(selected, truncated = false)
        }
    }

    return DecisionRunSafetyDenialPage(selected, truncated = true)
}

private fun JdbcTransaction.selectSafetyDenialCandidates(
    query: DecisionRunSafetyDenialQuery,
    batchIndex: Int,
): List<String> {
    return jdbcConnection().prepareStatement(FIND_SAFETY_DENIAL_INVOCATIONS_SQL).use { statement ->
        statement.setString(1, query.symbol.apiSymbol)
        statement.setLong(2, query.from.toEpochMilli())
        statement.setLong(3, query.toExclusive.toEpochMilli())
        statement.setInt(4, SAFETY_DENIAL_SCAN_BATCH_SIZE)
        statement.setInt(5, batchIndex * SAFETY_DENIAL_SCAN_BATCH_SIZE)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) add(resultSet.getString("decision_run_id"))
            }
        }
    }
}

private fun DecisionRunDetail?.toSafetyDenialOrNull(): DecisionRunSafetyDenial? {
    val detail = this ?: return null
    val violation = detail.safetyViolation ?: return null
    val safeRule = violation.rule.takeIf { rule -> SafetyFloorRule.entries.any { candidate -> candidate.name == rule } }

    if (detail.summary.outcome != me.matsumo.fukurou.trading.activity.DecisionRunOutcome.DENIED || safeRule == null) return null

    return DecisionRunSafetyDenial(
        invocationId = detail.summary.invocationId,
        deniedAt = violation.createdAt,
        finalReason = detail.summary.finalReason,
        decision = detail.decision,
        intent = detail.intent,
        falsification = detail.falsification,
        safetyViolation = violation.copy(rule = safeRule),
    )
}

private fun JdbcTransaction.selectRuns(
    cursor: DecisionRunCursor?,
    limit: Int,
    filter: DecisionRunFilter?,
    observedAt: Instant,
): DecisionRunPage {
    if (filter == null) {
        return DecisionRunPage(
            runs = selectRunBatch(cursor, limit, observedAt),
            scanContinuation = null,
        )
    }

    val selected = mutableListOf<DecisionRunSummary>()
    var scanCursor = cursor

    repeat(MAX_FILTER_SCAN_BATCHES) {
        val remaining = limit - selected.size
        val batch = selectRunBatch(scanCursor, FILTER_SCAN_BATCH_SIZE, observedAt)
        selected += batch
            .asSequence()
            .filter { summary -> summary.matches(filter) }
            .take(remaining)
            .toList()

        if (selected.size >= limit || batch.size < FILTER_SCAN_BATCH_SIZE) {
            return DecisionRunPage(selected, scanContinuation = null)
        }

        val last = batch.last()
        scanCursor = DecisionRunCursor(last.startedAt, last.invocationId)
    }

    return DecisionRunPage(selected, scanContinuation = scanCursor)
}

private fun JdbcTransaction.selectRunBatch(
    cursor: DecisionRunCursor?,
    limit: Int,
    observedAt: Instant,
): List<DecisionRunSummary> {
    return jdbcConnection().prepareStatement(LIST_RUNS_SQL).use { statement ->
        val cursorMillis = cursor?.startedAt?.toEpochMilli()
        val overdueCutoff = observedAt.minus(PaperOrderLifecyclePolicy.cancellationGrace).toEpochMilli()
        val waitingStatus = PaperOrderLifecyclePolicy.waitingStatuses.single().name
        statement.setObject(1, cursorMillis)
        statement.setObject(2, cursorMillis)
        statement.setObject(3, cursorMillis)
        statement.setString(4, cursor?.invocationId)
        statement.setInt(5, limit)
        statement.setString(6, OrderStatus.FILLED.name)
        statement.setString(7, waitingStatus)
        statement.setLong(8, observedAt.toEpochMilli())
        statement.setString(9, waitingStatus)
        statement.setLong(10, overdueCutoff)
        statement.setString(11, OrderStatus.CANCELED.name)
        statement.setString(12, PaperOrderCancelReason.TTL_EXPIRY.wireCode)
        statement.setString(13, OrderStatus.CANCELED.name)
        statement.setString(14, PaperOrderCancelReason.TTL_EXPIRY.wireCode)
        statement.setString(15, OrderStatus.CANCELED.name)
        statement.setString(16, PaperOrderCancelReason.TTL_EXPIRY.wireCode)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) add(resultSet.toSummary())
            }
        }
    }
}

private fun JdbcTransaction.selectRunDetail(invocationId: String, observedAt: Instant): DecisionRunDetail? {
    val base = selectRunDetailBase(invocationId, observedAt) ?: return null
    val orders = selectOrders(invocationId)
    val executions = selectExecutions(invocationId)
    val tradeLifecycles = selectTradeLifecycles(invocationId)
    val raw = buildList {
        addAll(base.raw)
        addAll(selectSafeAudit(invocationId))
        addAll(orders.map { order -> order.toRawRecord() })
        addAll(executions.map { execution -> execution.toRawRecord() })
    }.sortedBy { record -> record.occurredAt }

    return base.copy(
        summary = base.summary.copy(order = orders.lastOrNull { order -> order.side == "BUY" }),
        orders = orders,
        executions = executions,
        tradeLifecycles = tradeLifecycles,
        raw = raw,
    )
}

private fun JdbcTransaction.selectRunDetailBase(invocationId: String, observedAt: Instant): DecisionRunDetail? {
    return jdbcConnection().prepareStatement(FIND_RUN_SQL).use { statement ->
        val overdueCutoff = observedAt.minus(PaperOrderLifecyclePolicy.cancellationGrace).toEpochMilli()
        val waitingStatus = PaperOrderLifecyclePolicy.waitingStatuses.single().name
        statement.setString(1, OrderStatus.FILLED.name)
        statement.setString(2, waitingStatus)
        statement.setLong(3, observedAt.toEpochMilli())
        statement.setString(4, waitingStatus)
        statement.setLong(5, overdueCutoff)
        statement.setString(6, OrderStatus.CANCELED.name)
        statement.setString(7, PaperOrderCancelReason.TTL_EXPIRY.wireCode)
        statement.setString(8, OrderStatus.CANCELED.name)
        statement.setString(9, PaperOrderCancelReason.TTL_EXPIRY.wireCode)
        statement.setString(10, OrderStatus.CANCELED.name)
        statement.setString(11, PaperOrderCancelReason.TTL_EXPIRY.wireCode)
        statement.setString(12, invocationId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toDetailBase() else null
        }
    }
}

private fun JdbcTransaction.selectOrders(invocationId: String): List<DecisionRunOrder> {
    return jdbcConnection().prepareStatement(FIND_ORDERS_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        DecisionRunOrder(
                            orderId = resultSet.getString("id"),
                            intentId = resultSet.getString("intent_id"),
                            positionId = resultSet.getString("position_id"),
                            tradeGroupId = resultSet.getString("trade_group_id"),
                            side = resultSet.getString("side"),
                            orderType = resultSet.getString("order_type"),
                            status = resultSet.getString("status"),
                            sizeBtc = resultSet.getString("size_btc"),
                            limitPriceJpy = resultSet.getString("limit_price_jpy"),
                            reasonJa = resultSet.getString("reason_ja"),
                            expiresAt = resultSet.nullableInstant("expires_at"),
                            expirySource = resultSet.getString("expiry_source"),
                            effectiveTtlSeconds = resultSet.nullableLong("effective_ttl_seconds"),
                            expiredAt = resultSet.nullableInstant("expired_at"),
                            canceledAt = resultSet.nullableInstant("canceled_at"),
                            cancelReason = resultSet.getString("cancel_reason")?.let(PaperOrderCancelReason::fromWireCode),
                            canceledByDecisionRunId = resultSet.getString("canceled_by_decision_run_id"),
                            createdAt = Instant.ofEpochMilli(resultSet.getLong("created_at")),
                        ).withStrategyEvaluation(),
                    )
                }
            }
        }
    }
}

private fun JdbcTransaction.selectExecutions(invocationId: String): List<DecisionRunExecution> {
    return jdbcConnection().prepareStatement(FIND_EXECUTIONS_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        DecisionRunExecution(
                            executionId = resultSet.getString("id"),
                            orderId = resultSet.getString("order_id"),
                            positionId = resultSet.getString("position_id"),
                            side = resultSet.getString("side"),
                            priceJpy = resultSet.getString("price_jpy"),
                            sizeBtc = resultSet.getString("size_btc"),
                            feeJpy = resultSet.getString("fee_jpy"),
                            realizedPnlJpy = resultSet.getString("realized_pnl_jpy"),
                            liquidity = resultSet.getString("liquidity"),
                            orderType = resultSet.getString("order_type"),
                            kind = "DIRECT_RUN",
                            executedAt = Instant.ofEpochMilli(resultSet.getLong("executed_at")),
                        ),
                    )
                }
            }
        }
    }
}

private fun JdbcTransaction.selectTradeLifecycles(invocationId: String): List<DecisionRunTradeLifecycle> {
    return jdbcConnection().prepareStatement(FIND_TRADE_LIFECYCLES_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.setString(2, VIRTUAL_TAKE_PROFIT_TRIGGER_REASON)
        statement.executeQuery().use { resultSet ->
            val lifecycleRows = buildList {
                while (resultSet.next()) {
                    add(
                        Triple(
                            resultSet.getString("position_id"),
                            DecisionRunExecution(
                                executionId = resultSet.getString("id"),
                                orderId = resultSet.getString("order_id"),
                                positionId = resultSet.getString("position_id"),
                                side = resultSet.getString("side"),
                                priceJpy = resultSet.getString("price_jpy"),
                                sizeBtc = resultSet.getString("size_btc"),
                                feeJpy = resultSet.getString("fee_jpy"),
                                realizedPnlJpy = resultSet.getString("realized_pnl_jpy"),
                                liquidity = resultSet.getString("liquidity"),
                                orderType = resultSet.getString("order_type"),
                                kind = resultSet.getString("execution_kind"),
                                executedAt = Instant.ofEpochMilli(resultSet.getLong("executed_at")),
                            ),
                            resultSet.getString("position_status"),
                        ),
                    )
                }
            }

            lifecycleRows.groupBy { row -> row.first }
                .map { (positionId, rows) ->
                    DecisionRunTradeLifecycle(
                        positionId = positionId,
                        status = rows.first().third ?: "UNKNOWN",
                        executions = rows.map { row -> row.second },
                    )
                }
        }
    }
}

private fun JdbcTransaction.selectSafeAudit(invocationId: String): List<DecisionRunRawRecord> {
    return jdbcConnection().prepareStatement(FIND_SAFE_AUDIT_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        DecisionRunRawRecord(
                            source = "audit",
                            occurredAt = Instant.ofEpochMilli(resultSet.getLong("ts")),
                            values = mapOf(
                                "eventType" to resultSet.getString("event_type"),
                                "toolName" to resultSet.getString("tool_name"),
                            ),
                        ),
                    )
                }
            }
        }
    }
}

@Suppress("LongMethod")
private fun ResultSet.toSummary(includeOrder: Boolean = true): DecisionRunSummary {
    val action = getString("action")
    val safetyRule = getString("rule")
    val orderCount = getInt("order_count")
    val filledOrderCount = getInt("filled_order_count")
    val executionCount = getInt("execution_count")
    val status = getString("status")
    val errorMessage = getString("error_message")
    val terminalCause = getString("terminal_cause")?.let(LlmRunTerminalCause::valueOf)
    val noTradeReason = getString("no_trade_reason")
    val hasNoTradeExit = getBoolean("has_no_trade_exit")
    val openOrderCount = getInt("open_order_count")
    val expiringOpenOrderCount = getInt("expiring_open_order_count")
    val overdueOpenOrderCount = getInt("overdue_open_order_count")
    val ttlCanceledOrderCount = getInt("ttl_canceled_order_count")
    val canceledEntryOrderCount = getInt("canceled_entry_order_count")
    val actorCanceledOrderCount = getInt("actor_canceled_order_count")

    return DecisionRunSummary(
        invocationId = getString("invocation_id"),
        mode = getString("mode"),
        symbol = getString("symbol"),
        triggerKind = getString("trigger_kind"),
        status = status,
        startedAt = Instant.ofEpochMilli(getLong("started_at")),
        finishedAt = nullableInstant("finished_at"),
        errorMessage = errorMessage,
        terminalCause = terminalCause,
        action = action,
        reasonJa = getString("reason_ja"),
        falsificationVerdict = getString("verdict"),
        safetyRule = safetyRule,
        safetyMessageJa = getString("message_ja"),
        finalReason = noTradeReason.safeDecisionRunFinalReason(),
        orderCount = orderCount,
        executionCount = executionCount,
        hasProcessFailure = terminalCause in setOf(
            LlmRunTerminalCause.RESTART_INTERRUPTED,
            LlmRunTerminalCause.CALLER_CANCELLED,
            LlmRunTerminalCause.TIMED_OUT,
            LlmRunTerminalCause.RUNNER_FAILED,
        ) || errorMessage != null ||
            status == LLM_RUN_STATUS_FAILED ||
            status == LLM_RUN_STATUS_CANCELLED,
        order = if (includeOrder) toSummaryOrder() else null,
        outcome = classifyDecisionRunOutcome(
            DecisionRunOutcomeEvidence(
                status = status,
                errorMessage = errorMessage,
                terminalCause = terminalCause,
                action = action,
                safetyRule = safetyRule,
                orderCount = orderCount,
                filledOrderCount = filledOrderCount,
                executionCount = executionCount,
                hasNoTradeExit = hasNoTradeExit,
                openOrderCount = openOrderCount,
                expiringOpenOrderCount = expiringOpenOrderCount,
                overdueOpenOrderCount = overdueOpenOrderCount,
                ttlCanceledOrderCount = ttlCanceledOrderCount,
                canceledEntryOrderCount = canceledEntryOrderCount,
                actorCanceledOrderCount = actorCanceledOrderCount,
            ),
        ),
    )
}

private fun ResultSet.toDetailBase(): DecisionRunDetail {
    val summary = toSummary(includeOrder = false)
    val runRaw = DecisionRunRawRecord(
        source = "llm_run",
        occurredAt = summary.startedAt,
        values = mapOf(
            "invocationId" to summary.invocationId,
            "status" to summary.status,
            "mode" to summary.mode,
            "symbol" to summary.symbol,
            "triggerKind" to summary.triggerKind,
        ),
    )

    return DecisionRunDetail(
        summary = summary,
        decision = toDecision(),
        intent = toIntent(),
        falsification = toFalsification(),
        safetyViolation = toSafetyViolation(),
        orders = emptyList(),
        executions = emptyList(),
        tradeLifecycles = emptyList(),
        raw = listOf(runRaw),
    )
}

private fun ResultSet.toSummaryOrder(): DecisionRunOrder? {
    val orderId = getString("entry_order_id") ?: return null

    return DecisionRunOrder(
        orderId = orderId,
        intentId = getString("entry_intent_id"),
        positionId = getString("entry_position_id"),
        tradeGroupId = getString("entry_trade_group_id"),
        side = getString("entry_side"),
        orderType = getString("entry_order_type"),
        status = getString("entry_status"),
        sizeBtc = getString("entry_size_btc"),
        limitPriceJpy = getString("entry_limit_price_jpy"),
        reasonJa = getString("entry_reason_ja"),
        expiresAt = nullableInstant("entry_expires_at"),
        expirySource = getString("entry_expiry_source"),
        effectiveTtlSeconds = nullableLong("entry_effective_ttl_seconds"),
        expiredAt = nullableInstant("entry_expired_at"),
        canceledAt = nullableInstant("entry_canceled_at"),
        cancelReason = getString("entry_cancel_reason")?.let(PaperOrderCancelReason::fromWireCode),
        canceledByDecisionRunId = getString("entry_canceled_by_decision_run_id"),
        createdAt = Instant.ofEpochMilli(getLong("entry_created_at")),
    ).withStrategyEvaluation()
}

private fun ResultSet.toDecision(): DecisionRunDecision? {
    val decisionId = getString("decision_id")
    return decisionId?.let {
        DecisionRunDecision(
            decisionId = it,
            action = requireNotNull(getString("action")),
            provider = getString("llm_provider"),
            estimatedWinProbability = getString("estimated_win_probability"),
            expectedRMultiple = getString("expected_r_multiple"),
            roundTripCostR = getString("round_trip_cost_r"),
            reasonJa = getString("reason_ja"),
            setupTagsJson = getString("setup_tags"),
            missingDataJaJson = getString("missing_data_ja"),
            noTradeConditionsJaJson = getString("no_trade_conditions_ja"),
            createdAt = Instant.ofEpochMilli(getLong("decision_created_at")),
        )
    }
}

private fun ResultSet.toIntent(): DecisionRunIntent? {
    val intentId = getString("intent_id")
    return intentId?.let {
        DecisionRunIntent(
            intentId = it,
            tradePlanId = getString("trade_plan_id"),
            parentTradePlanId = getString("parent_trade_plan_id"),
            revisionCount = getInt("revision_count"),
            side = getString("intent_side"),
            orderType = getString("intent_order_type"),
            sizeBtc = getString("intent_size_btc"),
            priceJpy = getString("intent_price_jpy"),
            protectiveStopPriceJpy = getString("protective_stop_price_jpy"),
            takeProfitPriceJpy = getString("take_profit_price_jpy"),
            thesisJa = getString("thesis_ja"),
            invalidationConditionsJaJson = getString("invalidation_conditions_ja"),
            targetPriceJpy = getString("target_price_jpy"),
            timeStopAt = nullableInstant("time_stop_at"),
            setupTagsJson = getString("plan_setup_tags"),
        )
    }
}

private fun ResultSet.toFalsification(): DecisionRunFalsification? {
    val verdict = getString("verdict")
    return verdict?.let {
        DecisionRunFalsification(
            verdict = it,
            provider = getString("falsification_provider"),
            reasonJa = getString("falsification_reason_ja"),
            createdAt = Instant.ofEpochMilli(getLong("falsification_created_at")),
        )
    }
}

private fun ResultSet.toSafetyViolation(): DecisionRunSafetyViolation? {
    val safetyRule = getString("rule")
    return safetyRule?.let {
        DecisionRunSafetyViolation(
            rule = it,
            measuredValue = getString("measured_value"),
            limitValue = getString("limit_value"),
            messageJa = getString("message_ja"),
            createdAt = Instant.ofEpochMilli(getLong("safety_created_at")),
        )
    }
}

private fun ResultSet.nullableInstant(column: String): Instant? {
    val millis = getLong(column)
    return if (wasNull()) null else Instant.ofEpochMilli(millis)
}

private fun ResultSet.nullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private fun DecisionRunOrder.toRawRecord(): DecisionRunRawRecord {
    return DecisionRunRawRecord(
        source = "order",
        occurredAt = createdAt,
        values = mapOf(
            "orderId" to orderId,
            "intentId" to intentId,
            "positionId" to positionId,
            "tradeGroupId" to tradeGroupId,
            "side" to side,
            "orderType" to orderType,
            "status" to status,
            "expiresAt" to expiresAt?.toString(),
            "expirySource" to expirySource,
            "expiredAt" to expiredAt?.toString(),
            "canceledAt" to canceledAt?.toString(),
            "cancelReason" to cancelReason?.wireCode,
            "canceledByDecisionRunId" to canceledByDecisionRunId,
        ),
    )
}

private fun DecisionRunExecution.toRawRecord(): DecisionRunRawRecord {
    return DecisionRunRawRecord(
        source = "execution",
        occurredAt = executedAt,
        values = mapOf(
            "executionId" to executionId,
            "orderId" to orderId,
            "positionId" to positionId,
            "side" to side,
        ),
    )
}
