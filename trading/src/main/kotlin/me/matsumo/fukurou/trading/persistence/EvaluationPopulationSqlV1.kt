package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/** immutable gap fact を同じ transaction clockでintervalへ投影する共通 SQL。 */
internal const val EVALUATION_GAP_INTERVAL_CTE_V1 = """
    query_clock AS MATERIALIZED (
        SELECT transaction_timestamp() AS query_now
    ),
    evaluation_request_bounds AS MATERIALIZED (
        SELECT
            current_setting('fukurou.evaluation_from_ms')::bigint AS from_ms,
            current_setting('fukurou.evaluation_to_ms')::bigint AS to_ms
    ),
    opened_gap_candidates AS MATERIALIZED (
        SELECT opened.event_id, opened.gap_id, opened.deployment_id, opened.reason,
            opened.occurred_at, closed.occurred_at AS closed_at
        FROM infrastructure_gap_events opened
        CROSS JOIN query_clock
        CROSS JOIN evaluation_request_bounds bounds
        LEFT JOIN LATERAL (
            SELECT event.occurred_at
            FROM infrastructure_gap_events event
            WHERE event.gap_id = opened.gap_id AND event.boundary = 'CLOSE'
            ORDER BY event.occurred_at, event.event_id
            LIMIT 1
        ) closed ON TRUE
        WHERE opened.boundary = 'OPEN'
            AND (extract(epoch FROM opened.occurred_at) * 1000)::bigint < bounds.to_ms
            AND (extract(epoch FROM COALESCE(closed.occurred_at, query_clock.query_now)) * 1000)::bigint > bounds.from_ms
        ORDER BY opened.occurred_at, opened.gap_id
        LIMIT 1001
    ),
    gap_population_bound AS MATERIALIZED (
        SELECT 1 / CASE WHEN COUNT(*) > 1000 THEN 0 ELSE 1 END AS verified
        FROM opened_gap_candidates
    ),
    infrastructure_gap_intervals AS MATERIALIZED (
        SELECT
            opened.gap_id,
            opened.deployment_id,
            opened.reason,
            opened.occurred_at AS opened_at,
            opened.closed_at,
            (extract(epoch FROM opened.occurred_at) * 1000)::bigint AS opened_at_ms,
            (extract(epoch FROM COALESCE(opened.closed_at, query_clock.query_now)) * 1000)::bigint AS closed_at_ms
        FROM opened_gap_candidates opened
        CROSS JOIN query_clock
        CROSS JOIN gap_population_bound
    )
"""

/** 全 evaluation query が同じ causal request window を gap projection に渡す。 */
internal fun JdbcTransaction.setEvaluationRequestBounds(period: EvaluationPeriod) {
    prepare("SELECT set_config('fukurou.evaluation_from_ms', ?, true), set_config('fukurou.evaluation_to_ms', ?, true)").use { statement ->
        statement.setString(1, period.from.toEpochMilli().toString())
        statement.setString(2, period.toExclusive.toEpochMilli().toString())
        statement.executeQuery().use { result -> check(result.next()) }
    }
}

/** millis causal interval と gap の交差をentity rowを増やさず判定する。 */
internal fun evaluationGapExistsSql(causeMillisSql: String, terminalMillisSql: String): String = """
    EXISTS (
        SELECT 1 FROM infrastructure_gap_intervals gap
        WHERE $causeMillisSql < gap.closed_at_ms AND gap.opened_at_ms < $terminalMillisSql
    )
""".trimIndent()

/** run row の存在と status / finished_at terminal invariant を同じ missing 判定へ揃える。 */
internal fun evaluationRunMissingSql(runAlias: String): String = """
    ($runAlias.invocation_id IS NULL OR
        ($runAlias.status = 'RUNNING' AND $runAlias.finished_at IS NOT NULL) OR
        ($runAlias.status <> 'RUNNING' AND $runAlias.finished_at IS NULL))
""".trimIndent()

/** order から intent / decision / run へ辿る exact lineage の共通 missing 判定。 */
internal fun evaluationOrderLineageMissingSql(
    orderAlias: String,
    intentAlias: String,
    decisionAlias: String,
    runAlias: String,
): String = """
    ($orderAlias.id IS NULL OR $orderAlias.intent_id IS NULL OR $intentAlias.id IS NULL OR
        $decisionAlias.id IS NULL OR $decisionAlias.invocation_id IS NULL OR
        $orderAlias.decision_run_id IS NULL OR
        $orderAlias.decision_run_id IS DISTINCT FROM $decisionAlias.invocation_id OR
        ${evaluationRunMissingSql(runAlias)})
""".trimIndent()

/** intentを持つentryとdirect runだけを持つsystem/close orderを同じstatusへ投影する。 */
internal fun evaluationOrderCausalMissingSql(
    orderAlias: String,
    intentAlias: String,
    decisionAlias: String,
    runAlias: String,
): String = """
    ($orderAlias.id IS NULL OR $orderAlias.decision_run_id IS NULL OR
        ${evaluationRunMissingSql(runAlias)} OR
        ($orderAlias.intent_id IS NOT NULL AND (
            $intentAlias.id IS NULL OR $decisionAlias.id IS NULL OR
            $decisionAlias.invocation_id IS NULL OR
            $orderAlias.decision_run_id IS DISTINCT FROM $decisionAlias.invocation_id
        )))
""".trimIndent()

/** execution direct run、order direct run、任意entry intentのchainをexactに照合する。 */
internal fun evaluationExecutionCausalMissingSql(
    executionAlias: String,
    orderAlias: String,
    intentAlias: String,
    decisionAlias: String,
    runAlias: String,
): String = """
    ($executionAlias.id IS NULL OR $executionAlias.decision_run_id IS NULL OR
        $executionAlias.decision_run_id IS DISTINCT FROM $orderAlias.decision_run_id OR
        ${evaluationOrderCausalMissingSql(orderAlias, intentAlias, decisionAlias, runAlias)})
""".trimIndent()

/** position direct runと全execution/orderのcausal chainを一つのmissing predicateへ畳み込む。 */
internal fun evaluationPositionCausalMissingSql(
    positionAlias: String,
    entryOrderAlias: String,
    intentAlias: String,
    decisionAlias: String,
    runAlias: String,
): String {
    val orderMissing = evaluationOrderCausalMissingSql(entryOrderAlias, intentAlias, decisionAlias, runAlias)
    val executionMissing = evaluationExecutionCausalMissingSql(
        executionAlias = "position_execution",
        orderAlias = "execution_order",
        intentAlias = "execution_intent",
        decisionAlias = "execution_decision",
        runAlias = "execution_run",
    )

    return """
        ($positionAlias.decision_run_id IS NULL OR
            $positionAlias.decision_run_id IS DISTINCT FROM $entryOrderAlias.decision_run_id OR
            $orderMissing OR
            EXISTS (
                SELECT 1 FROM executions position_execution
                LEFT JOIN orders execution_order ON execution_order.id=position_execution.order_id
                LEFT JOIN trade_intents execution_intent ON execution_intent.id=execution_order.intent_id
                LEFT JOIN decisions execution_decision ON execution_decision.id=execution_intent.decision_id
                LEFT JOIN llm_runs execution_run ON execution_run.invocation_id=execution_order.decision_run_id
                WHERE position_execution.position_id=$positionAlias.id AND $executionMissing
            ))
    """.trimIndent()
}
