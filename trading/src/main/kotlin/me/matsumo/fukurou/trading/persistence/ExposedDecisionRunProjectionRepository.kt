package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.matsumo.fukurou.trading.activity.DecisionRunCursor
import me.matsumo.fukurou.trading.activity.DecisionRunDecision
import me.matsumo.fukurou.trading.activity.DecisionRunDetail
import me.matsumo.fukurou.trading.activity.DecisionRunExecution
import me.matsumo.fukurou.trading.activity.DecisionRunFalsification
import me.matsumo.fukurou.trading.activity.DecisionRunIntent
import me.matsumo.fukurou.trading.activity.DecisionRunOrder
import me.matsumo.fukurou.trading.activity.DecisionRunOutcomeEvidence
import me.matsumo.fukurou.trading.activity.DecisionRunProjectionRepository
import me.matsumo.fukurou.trading.activity.DecisionRunRawRecord
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyViolation
import me.matsumo.fukurou.trading.activity.DecisionRunSummary
import me.matsumo.fukurou.trading.activity.classifyDecisionRunOutcome
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.ResultSet
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

private const val LIST_RUNS_SQL = """
    SELECT
        run.invocation_id,
        run.mode,
        run.symbol,
        run.trigger_kind,
        run.status,
        run.started_at,
        run.finished_at,
        run.error_message,
        decision.action,
        decision.reason_ja,
        falsification.verdict,
        safety.rule,
        safety.message_ja,
        (SELECT COUNT(*) FROM orders WHERE decision_run_id = run.invocation_id) AS order_count,
        (SELECT COUNT(*) FROM executions WHERE decision_run_id = run.invocation_id) AS execution_count,
        EXISTS (
            SELECT 1 FROM command_event_log
            WHERE decision_run_id = run.invocation_id AND event_type = 'NO_TRADE_EXIT'
        ) AS has_no_trade_exit
    FROM llm_runs run
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
    WHERE (CAST(? AS BIGINT) IS NULL OR run.started_at < ? OR (run.started_at = ? AND run.invocation_id < ?))
    ORDER BY run.started_at DESC, run.invocation_id DESC
    LIMIT ?
"""

private const val FIND_RUN_SQL = """
    SELECT
        run.invocation_id,
        run.mode,
        run.symbol,
        run.trigger_kind,
        run.status,
        run.started_at,
        run.finished_at,
        run.error_message,
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
        plan.thesis_ja,
        plan.invalidation_conditions_ja,
        plan.target_price_jpy,
        plan.time_stop_at,
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
        (SELECT COUNT(*) FROM executions WHERE decision_run_id = run.invocation_id) AS execution_count,
        EXISTS (
            SELECT 1 FROM command_event_log
            WHERE decision_run_id = run.invocation_id AND event_type = 'NO_TRADE_EXIT'
        ) AS has_no_trade_exit
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
    WHERE run.invocation_id = ?
"""

private const val FIND_ORDERS_SQL = """
    SELECT id, intent_id, position_id, trade_group_id, side, order_type, status, size_btc,
        limit_price_jpy, reason_ja, created_at
    FROM orders
    WHERE decision_run_id = ?
    ORDER BY created_at ASC, id ASC
"""

private const val FIND_EXECUTIONS_SQL = """
    SELECT id, order_id, position_id, side, price_jpy, size_btc, realized_pnl_jpy, executed_at
    FROM executions
    WHERE decision_run_id = ?
    ORDER BY executed_at ASC, id ASC
"""

private const val FIND_SAFE_AUDIT_SQL = """
    SELECT event_type, tool_name, ts
    FROM command_event_log
    WHERE decision_run_id = ?
    ORDER BY ts ASC, id ASC
"""

/** PostgreSQL の append-only 台帳から decision run projection を構築する repository。 */
class ExposedDecisionRunProjectionRepository(
    private val database: ExposedDatabase,
) : DecisionRunProjectionRepository {

    override suspend fun listRuns(cursor: DecisionRunCursor?, limit: Int): Result<List<DecisionRunSummary>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(limit > 0) { "limit must be greater than 0." }
                exposedTransaction(database) { selectRuns(cursor, limit) }
            }
        }
    }

    override suspend fun findRun(invocationId: String): Result<DecisionRunDetail?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) { selectRunDetail(invocationId) }
            }
        }
    }
}

private fun JdbcTransaction.selectRuns(cursor: DecisionRunCursor?, limit: Int): List<DecisionRunSummary> {
    return jdbcConnection().prepareStatement(LIST_RUNS_SQL).use { statement ->
        val cursorMillis = cursor?.startedAt?.toEpochMilli()
        statement.setObject(1, cursorMillis)
        statement.setObject(2, cursorMillis)
        statement.setObject(3, cursorMillis)
        statement.setString(4, cursor?.invocationId)
        statement.setInt(5, limit)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) add(resultSet.toSummary())
            }
        }
    }
}

private fun JdbcTransaction.selectRunDetail(invocationId: String): DecisionRunDetail? {
    val base = jdbcConnection().prepareStatement(FIND_RUN_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toDetailBase() else null
        }
    } ?: return null
    val orders = selectOrders(invocationId)
    val executions = selectExecutions(invocationId)
    val raw = buildList {
        addAll(base.raw)
        addAll(selectSafeAudit(invocationId))
        addAll(orders.map { order -> order.toRawRecord() })
        addAll(executions.map { execution -> execution.toRawRecord() })
    }.sortedBy { record -> record.occurredAt }

    return base.copy(orders = orders, executions = executions, raw = raw)
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
                            createdAt = Instant.ofEpochMilli(resultSet.getLong("created_at")),
                        ),
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
                            realizedPnlJpy = resultSet.getString("realized_pnl_jpy"),
                            executedAt = Instant.ofEpochMilli(resultSet.getLong("executed_at")),
                        ),
                    )
                }
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

private fun ResultSet.toSummary(): DecisionRunSummary {
    val action = getString("action")
    val safetyRule = getString("rule")
    val orderCount = getInt("order_count")
    val executionCount = getInt("execution_count")
    val status = getString("status")
    val errorMessage = getString("error_message")
    val hasNoTradeExit = getBoolean("has_no_trade_exit")

    return DecisionRunSummary(
        invocationId = getString("invocation_id"),
        mode = getString("mode"),
        symbol = getString("symbol"),
        triggerKind = getString("trigger_kind"),
        status = status,
        startedAt = Instant.ofEpochMilli(getLong("started_at")),
        finishedAt = nullableInstant("finished_at"),
        errorMessage = errorMessage,
        action = action,
        reasonJa = getString("reason_ja"),
        falsificationVerdict = getString("verdict"),
        safetyRule = safetyRule,
        safetyMessageJa = getString("message_ja"),
        orderCount = orderCount,
        executionCount = executionCount,
        outcome = classifyDecisionRunOutcome(
            DecisionRunOutcomeEvidence(
                status = status,
                errorMessage = errorMessage,
                action = action,
                safetyRule = safetyRule,
                orderCount = orderCount,
                executionCount = executionCount,
                hasNoTradeExit = hasNoTradeExit,
            ),
        ),
    )
}

private fun ResultSet.toDetailBase(): DecisionRunDetail {
    val summary = toSummary()
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
        raw = listOf(runRaw),
    )
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
