@file:Suppress("ImportOrdering", "TooManyFunctions")

package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.matsumo.fukurou.trading.evaluation.ClosedTradeFact
import me.matsumo.fukurou.trading.evaluation.DailyTradePnlFact
import me.matsumo.fukurou.trading.evaluation.DecisionActionCount
import me.matsumo.fukurou.trading.evaluation.DEFAULT_EVALUATION_QUERY_LIMIT
import me.matsumo.fukurou.trading.evaluation.DeduplicationMetrics
import me.matsumo.fukurou.trading.evaluation.EvaluationExclusionSummary
import me.matsumo.fukurou.trading.evaluation.EvaluationEpochOption
import me.matsumo.fukurou.trading.evaluation.EvaluationAttributionCoverage
import me.matsumo.fukurou.trading.evaluation.EvaluationAttributionStatus
import me.matsumo.fukurou.trading.evaluation.EvaluationLlmUsageQueryResult
import me.matsumo.fukurou.trading.evaluation.EvaluationInfrastructureGap
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationPopulationCounts
import me.matsumo.fukurou.trading.evaluation.EvaluationPopulationEntityType
import me.matsumo.fukurou.trading.evaluation.EvaluationPopulationStatus
import me.matsumo.fukurou.trading.evaluation.EvaluationReportSnapshotFacts
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationScope
import me.matsumo.fukurou.trading.evaluation.EvaluationTradeQueryResult
import me.matsumo.fukurou.trading.evaluation.KillCriterionStats
import me.matsumo.fukurou.trading.evaluation.LlmPhaseUsageFact
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import me.matsumo.fukurou.trading.domain.EvaluationCohort
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/** closed-trade projection 共通の initial BUY epoch 帰属式。 */
private const val INITIAL_BUY_EPOCH_SQL = """
    (ARRAY_AGG(e.account_epoch_id::text ORDER BY e.executed_at, e.id)
        FILTER (WHERE e.side = 'BUY'))[1]
"""

/**
 * closed trade fact を取得する SQL。
 */
private val SELECT_CLOSED_TRADE_FACTS_SQL = """
    WITH $EVALUATION_GAP_INTERVAL_CTE_V1,
    closed_positions AS MATERIALIZED (
        SELECT
            p.id,
            p.opened_at,
            p.closed_at,
            p.decision_run_id,
            p.highest_price_since_entry_jpy,
            p.lowest_price_since_entry_jpy
        FROM positions p
        WHERE p.status = 'CLOSED'
            AND p.mode = (
                SELECT mode
                FROM paper_account
                WHERE id = ?
            )
            AND (
                ?::uuid IS NULL OR p.account_epoch_id = ?::uuid OR
                (p.account_epoch_id IS NULL AND ?::text = 'LEGACY_PRE_WS')
            )
            AND p.closed_at >= ?
            AND p.closed_at < ?
            AND NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = p.id::text
            )
    ),
    initial_buy_executions AS MATERIALIZED (
        SELECT DISTINCT ON (e.position_id)
            e.position_id,
            e.order_id
        FROM executions e
        JOIN closed_positions p ON p.id = e.position_id
        WHERE e.side = 'BUY'
        ORDER BY e.position_id,
            (e.decision_run_id IS DISTINCT FROM p.decision_run_id) ASC,
            e.executed_at ASC,
            e.id ASC
    ),
    entry_orders AS (
        SELECT
            initial.position_id,
            o.id,
            o.intent_id,
            o.decision_run_id,
            o.protective_stop_price_jpy
        FROM initial_buy_executions initial
        LEFT JOIN orders o ON o.id = initial.order_id
    ),
    execution_lineage AS (
        SELECT
            e.position_id,
            COALESCE(
                $INITIAL_BUY_EPOCH_SQL,
                (SELECT id::text FROM mcp_evaluation_epochs WHERE kind='LEGACY_IMPORTED' ORDER BY created_at ASC LIMIT 1)
            ) AS account_epoch_id,
            CASE
                WHEN COUNT(*) FILTER (WHERE
                    COALESCE(e.execution_semantics_version, '') NOT IN ('', 'PAPER_WS_V1') OR
                    COALESCE(o.execution_semantics_version, '') NOT IN ('', 'PAPER_WS_V1')
                ) > 0 THEN 'UNSUPPORTED_EXECUTION_SEMANTICS'
                WHEN COUNT(*) FILTER (WHERE
                    e.execution_semantics_version IS NULL OR
                    o.id IS NULL OR o.execution_semantics_version IS NULL OR
                    e.account_epoch_id IS NULL OR o.account_epoch_id IS NULL
                ) = 0
                    AND COUNT(DISTINCT e.account_epoch_id) = 1
                    AND COUNT(DISTINCT o.account_epoch_id) = 1
                    AND MIN(e.account_epoch_id::text) = MIN(o.account_epoch_id::text)
                    AND BOOL_AND(e.execution_semantics_version = 'PAPER_WS_V1')
                    AND BOOL_AND(o.execution_semantics_version = 'PAPER_WS_V1') THEN 'CURRENT'
                ELSE 'LEGACY_PRE_WS'
            END AS cohort,
            CASE
                WHEN COUNT(DISTINCT e.execution_semantics_version) = 1
                    THEN MIN(e.execution_semantics_version)
                ELSE NULL
            END AS execution_semantics_version
        FROM executions e
        LEFT JOIN orders o ON o.id = e.order_id
        JOIN closed_positions p ON p.id = e.position_id
        GROUP BY e.position_id
    ),
    scoped_positions AS MATERIALIZED (
        SELECT p.id
        FROM closed_positions p
        LEFT JOIN execution_lineage el ON el.position_id = p.id
        WHERE (?::uuid IS NULL OR el.account_epoch_id::uuid = ?::uuid)
            AND (?::text IS NULL OR el.cohort = ?::text)
        ORDER BY p.closed_at ASC, p.id ASC
        LIMIT ?
    ),
    entry_fills AS (
        SELECT
            e.position_id,
            SUM(e.size_btc) AS entry_size_btc,
            SUM(e.price_jpy * e.size_btc) / NULLIF(SUM(e.size_btc), 0) AS average_entry_price_jpy,
            SUM(o.protective_stop_price_jpy * e.size_btc) / NULLIF(SUM(e.size_btc), 0) AS protective_stop_price_jpy
        FROM executions e
        JOIN orders o ON o.id = e.order_id
        JOIN scoped_positions p ON p.id = e.position_id
        WHERE e.side = 'BUY'
        GROUP BY e.position_id
    ),
    position_pnl AS (
        SELECT
            e.position_id,
            COALESCE(SUM(CASE WHEN e.side = 'SELL' THEN e.realized_pnl_jpy ELSE 0 END), 0) AS sell_realized_pnl_jpy,
            COALESCE(SUM(CASE WHEN e.side = 'BUY' THEN e.fee_jpy ELSE 0 END), 0) AS entry_fee_jpy
        FROM executions e
        JOIN scoped_positions p ON p.id = e.position_id
        GROUP BY e.position_id
    )
    SELECT
        p.id AS position_id,
        p.opened_at,
        p.closed_at,
        COALESCE(ef.entry_size_btc, 0) AS size_btc,
        COALESCE(ef.average_entry_price_jpy, 0) AS average_entry_price_jpy,
        COALESCE(ef.protective_stop_price_jpy, eo.protective_stop_price_jpy) AS protective_stop_price_jpy,
        p.highest_price_since_entry_jpy,
        p.lowest_price_since_entry_jpy,
        COALESCE(position_pnl.sell_realized_pnl_jpy, 0) - COALESCE(position_pnl.entry_fee_jpy, 0) AS trade_pnl_jpy,
        d.estimated_win_probability,
        COALESCE(d.setup_tags, tp.setup_tags, '[]') AS setup_tags,
        d.llm_provider,
        el.account_epoch_id,
        el.cohort,
        el.execution_semantics_version,
        CASE WHEN ${evaluationPositionCausalMissingSql("p", "eo", "ti", "d", "run")}
            THEN 'MISSING' ELSE 'ATTRIBUTED' END AS attribution_status,
        ARRAY(
            SELECT gap.gap_id::text
            FROM infrastructure_gap_intervals gap
            WHERE run.started_at < gap.closed_at_ms AND gap.opened_at_ms < p.closed_at
            ORDER BY gap.opened_at, gap.gap_id
            LIMIT 1
        ) AS infrastructure_gap_ids
    FROM closed_positions p
    JOIN scoped_positions scoped ON scoped.id = p.id
    LEFT JOIN entry_orders eo ON eo.position_id = p.id
    LEFT JOIN entry_fills ef ON ef.position_id = p.id
    LEFT JOIN position_pnl ON position_pnl.position_id = p.id
    LEFT JOIN execution_lineage el ON el.position_id = p.id
    LEFT JOIN trade_intents ti ON ti.id = eo.intent_id
    LEFT JOIN decisions d ON d.id = ti.decision_id
    LEFT JOIN llm_runs run ON run.invocation_id = eo.decision_run_id
    LEFT JOIN trade_plans tp ON tp.id = ti.trade_plan_id
    ORDER BY p.closed_at ASC
"""

/**
 * decision run 数を集計する SQL。
 */
private val COUNT_EVALUATION_DECISION_RUNS_SQL = """
    WITH $EVALUATION_GAP_INTERVAL_CTE_V1
    SELECT COUNT(DISTINCT events.decision_run_id)
    FROM command_event_log events
    JOIN llm_runs run ON run.invocation_id = events.decision_run_id
    WHERE events.decision_run_id IS NOT NULL
        AND events.event_type IN ('RUNNER_PHASE_COMPLETED', 'NO_TRADE_EXIT')
        AND events.ts >= ? AND events.ts < ?
        AND NOT ${evaluationRunMissingSql("run")}
        AND NOT EXISTS (
            SELECT 1 FROM decisions decision
            JOIN trade_intents intent ON intent.decision_id=decision.id
            JOIN orders causal_order ON causal_order.intent_id=intent.id
            WHERE decision.invocation_id=events.decision_run_id
                AND causal_order.decision_run_id IS DISTINCT FROM decision.invocation_id
        )
        AND NOT EXISTS (SELECT 1 FROM infrastructure_gap_intervals gap
            WHERE run.started_at < gap.closed_at_ms AND gap.opened_at_ms < COALESCE(run.finished_at, events.ts + 1))
        AND NOT EXISTS (
            SELECT 1 FROM evaluation_exclusions x
            WHERE x.entity_type = 'DECISION_RUN' AND x.entity_id = events.decision_run_id
        )
"""

/**
 * decision action 別件数を集計する SQL。
 */
private val COUNT_DECISIONS_BY_ACTION_SQL = """
    WITH $EVALUATION_GAP_INTERVAL_CTE_V1
    SELECT decision.action, COUNT(*)
    FROM decisions decision
    JOIN llm_runs run ON run.invocation_id = decision.invocation_id
    WHERE decision.created_at >= ? AND decision.created_at < ?
        AND NOT ${evaluationRunMissingSql("run")}
        AND NOT EXISTS (
            SELECT 1 FROM trade_intents intent
            JOIN orders causal_order ON causal_order.intent_id=intent.id
            WHERE intent.decision_id=decision.id
                AND causal_order.decision_run_id IS DISTINCT FROM decision.invocation_id
        )
        AND NOT EXISTS (SELECT 1 FROM infrastructure_gap_intervals gap
            WHERE run.started_at < gap.closed_at_ms AND gap.opened_at_ms < GREATEST(decision.created_at, COALESCE(run.finished_at, decision.created_at)))
        AND NOT EXISTS (
            SELECT 1 FROM evaluation_exclusions x
            WHERE x.entity_type = 'DECISION_RUN' AND x.entity_id = decision.invocation_id
        )
    GROUP BY decision.action
    ORDER BY decision.action ASC
"""

/**
 * benchmark 用の trade PnL を取得する SQL。
 */
private val SELECT_DAILY_TRADE_PNL_SQL = """
    WITH $EVALUATION_GAP_INTERVAL_CTE_V1,
    closed_positions AS (
        SELECT p.id, p.closed_at
        FROM positions p
        JOIN LATERAL (
            SELECT entry.order_id FROM executions entry
            WHERE entry.position_id = p.id AND entry.side = 'BUY'
            ORDER BY (entry.decision_run_id IS DISTINCT FROM p.decision_run_id), entry.executed_at, entry.id LIMIT 1
        ) initial ON TRUE
        JOIN orders entry_order ON entry_order.id = initial.order_id
        JOIN trade_intents intent ON intent.id = entry_order.intent_id
        JOIN decisions decision ON decision.id = intent.decision_id
        JOIN llm_runs run ON run.invocation_id = entry_order.decision_run_id
        WHERE p.status = 'CLOSED'
            AND p.mode = (
                SELECT mode
                FROM paper_account
                WHERE id = ?
            )
            AND p.closed_at >= ?
            AND p.closed_at < ?
            AND NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = p.id::text
            )
            AND NOT ${evaluationPositionCausalMissingSql("p", "entry_order", "intent", "decision", "run")}
            AND NOT EXISTS (SELECT 1 FROM infrastructure_gap_intervals gap
                WHERE run.started_at < gap.closed_at_ms AND gap.opened_at_ms < p.closed_at)
    ),
    position_pnl AS (
        SELECT
            p.id AS position_id,
            p.closed_at,
            COALESCE(SUM(CASE WHEN e.side = 'SELL' THEN e.realized_pnl_jpy ELSE 0 END), 0) -
                COALESCE(SUM(CASE WHEN e.side = 'BUY' THEN e.fee_jpy ELSE 0 END), 0) AS trade_pnl_jpy
        FROM closed_positions p
        LEFT JOIN executions e ON e.position_id = p.id
        GROUP BY p.id, p.closed_at
    )
    SELECT closed_at, trade_pnl_jpy
    FROM position_pnl
    ORDER BY closed_at ASC
"""

/**
 * 指定時刻より前の realized PnL を合計する SQL。
 */
private val SUM_TRADE_PNL_BEFORE_SQL = """
    WITH $EVALUATION_GAP_INTERVAL_CTE_V1,
    closed_positions AS (
        SELECT p.id
        FROM positions p
        JOIN LATERAL (
            SELECT entry.order_id FROM executions entry
            WHERE entry.position_id = p.id AND entry.side = 'BUY'
            ORDER BY (entry.decision_run_id IS DISTINCT FROM p.decision_run_id), entry.executed_at, entry.id LIMIT 1
        ) initial ON TRUE
        JOIN orders entry_order ON entry_order.id = initial.order_id
        JOIN trade_intents intent ON intent.id = entry_order.intent_id
        JOIN decisions decision ON decision.id = intent.decision_id
        JOIN llm_runs run ON run.invocation_id = entry_order.decision_run_id
        WHERE p.status = 'CLOSED'
            AND p.mode = (
                SELECT mode
                FROM paper_account
                WHERE id = ?
            )
            AND p.closed_at < ?
            AND NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = p.id::text
            )
            AND NOT ${evaluationPositionCausalMissingSql("p", "entry_order", "intent", "decision", "run")}
            AND NOT EXISTS (SELECT 1 FROM infrastructure_gap_intervals gap
                WHERE run.started_at < gap.closed_at_ms AND gap.opened_at_ms < p.closed_at)
    ),
    position_pnl AS (
        SELECT
            p.id AS position_id,
            COALESCE(SUM(CASE WHEN e.side = 'SELL' THEN e.realized_pnl_jpy ELSE 0 END), 0) -
                COALESCE(SUM(CASE WHEN e.side = 'BUY' THEN e.fee_jpy ELSE 0 END), 0) AS trade_pnl_jpy
        FROM closed_positions p
        LEFT JOIN executions e ON e.position_id = p.id
        GROUP BY p.id
    )
    SELECT COALESCE(SUM(trade_pnl_jpy), 0)
    FROM position_pnl
"""

/**
 * paper account の初期資金を読む SQL。
 */
private const val SELECT_INITIAL_CASH_JPY_SQL = """
    SELECT initial_cash_jpy
    FROM paper_account
    WHERE id = ?
"""

/**
 * runner phase usage payload を読む SQL。
 */
private val SELECT_LLM_PHASE_USAGE_SQL = """
    WITH $EVALUATION_GAP_INTERVAL_CTE_V1
    SELECT
        event.decision_run_id,
        event.llm_provider,
        event.payload,
        event.ts,
        CASE WHEN ${evaluationRunMissingSql("run")} OR EXISTS (
            SELECT 1 FROM decisions decision
            JOIN trade_intents intent ON intent.decision_id=decision.id
            JOIN orders causal_order ON causal_order.intent_id=intent.id
            WHERE decision.invocation_id=event.decision_run_id
                AND causal_order.decision_run_id IS DISTINCT FROM decision.invocation_id
        ) THEN 'ATTRIBUTION_MISSING'
             WHEN gap.gap_id IS NOT NULL THEN 'INFRASTRUCTURE_GAP' ELSE 'ELIGIBLE' END population_status,
        gap.gap_id::text representative_gap_id
    FROM command_event_log event
    LEFT JOIN llm_runs run ON run.invocation_id=event.decision_run_id
    LEFT JOIN LATERAL (
        SELECT interval.gap_id FROM infrastructure_gap_intervals interval
        WHERE run.started_at < interval.closed_at_ms AND interval.opened_at_ms < COALESCE(run.finished_at,event.ts+1)
        ORDER BY interval.opened_at,interval.gap_id LIMIT 1
    ) gap ON TRUE
    WHERE event.event_type = 'RUNNER_PHASE_COMPLETED'
        AND event.ts >= ? AND event.ts < ?
        AND event.payload::jsonb->>'phase' IN ('pre_filter', 'proposer', 'falsifier', 'reflection')
    ORDER BY event.ts ASC
    LIMIT ?
"""

/** active epoch + CURRENT の kill criterion を DB 内で完全集計する SQL。 */
private val SELECT_KILL_CRITERION_STATS_SQL = """
    WITH $EVALUATION_GAP_INTERVAL_CTE_V1,
    current_scope AS (
        SELECT account.current_epoch_id
        FROM paper_account account
        WHERE account.id = ?
    ),
    trade_stats AS (
        SELECT
            p.id,
            MIN(e.account_epoch_id::text)::uuid AS account_epoch_id,
            COALESCE(SUM(CASE WHEN e.side = 'SELL' THEN e.realized_pnl_jpy ELSE 0 END), 0) -
                COALESCE(SUM(CASE WHEN e.side = 'BUY' THEN e.fee_jpy ELSE 0 END), 0) AS trade_pnl_jpy,
            COUNT(*) FILTER (WHERE
                e.execution_semantics_version IS NULL OR
                o.id IS NULL OR o.execution_semantics_version IS NULL OR
                e.account_epoch_id IS NULL OR o.account_epoch_id IS NULL
            ) = 0
                AND COUNT(DISTINCT e.account_epoch_id) = 1
                AND COUNT(DISTINCT o.account_epoch_id) = 1
                AND MIN(e.account_epoch_id::text) = MIN(o.account_epoch_id::text)
                AND BOOL_AND(e.execution_semantics_version = 'PAPER_WS_V1')
                AND BOOL_AND(o.execution_semantics_version = 'PAPER_WS_V1') AS current_semantics
        FROM positions p
        JOIN LATERAL (
            SELECT entry.order_id FROM executions entry
            WHERE entry.position_id = p.id AND entry.side = 'BUY'
            ORDER BY (entry.decision_run_id IS DISTINCT FROM p.decision_run_id), entry.executed_at, entry.id LIMIT 1
        ) initial ON TRUE
        JOIN orders entry_order ON entry_order.id = initial.order_id
        JOIN trade_intents intent ON intent.id = entry_order.intent_id
        JOIN decisions decision ON decision.id = intent.decision_id
        JOIN llm_runs run ON run.invocation_id = entry_order.decision_run_id
        JOIN executions e ON e.position_id = p.id
        LEFT JOIN orders o ON o.id = e.order_id
        WHERE p.status = 'CLOSED'
            AND p.mode = (SELECT mode FROM paper_account WHERE id = ?)
            AND NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = p.id::text
            )
            AND NOT ${evaluationPositionCausalMissingSql("p", "entry_order", "intent", "decision", "run")}
            AND NOT EXISTS (SELECT 1 FROM infrastructure_gap_intervals gap
                WHERE run.started_at < gap.closed_at_ms AND gap.opened_at_ms < p.closed_at)
        GROUP BY p.id
    )
    SELECT
        COUNT(*) AS closed_trades,
        COALESCE(SUM(trade_pnl_jpy) FILTER (WHERE trade_pnl_jpy > 0), 0) AS gross_profit,
        ABS(COALESCE(SUM(trade_pnl_jpy) FILTER (WHERE trade_pnl_jpy < 0), 0)) AS gross_loss
    FROM trade_stats
    WHERE account_epoch_id = (SELECT current_epoch_id FROM current_scope)
        AND current_semantics
"""

/** epoch/cohort に限定した期間開始前 realized PnL を DB で完全集計する SQL。 */
private val SELECT_SCOPED_PNL_BEFORE_SQL = """
    WITH $EVALUATION_GAP_INTERVAL_CTE_V1,
    trade_stats AS (
        SELECT
            p.id,
            COALESCE(
                $INITIAL_BUY_EPOCH_SQL,
                (SELECT id::text FROM mcp_evaluation_epochs WHERE kind='LEGACY_IMPORTED' ORDER BY created_at ASC LIMIT 1)
            ) AS account_epoch_id,
            CASE
                WHEN COUNT(*) FILTER (WHERE
                    COALESCE(e.execution_semantics_version, '') NOT IN ('', 'PAPER_WS_V1') OR
                    COALESCE(o.execution_semantics_version, '') NOT IN ('', 'PAPER_WS_V1')
                ) > 0 THEN 'UNSUPPORTED_EXECUTION_SEMANTICS'
                WHEN COUNT(*) FILTER (WHERE
                    e.execution_semantics_version IS NULL OR o.id IS NULL OR
                    o.execution_semantics_version IS NULL OR e.account_epoch_id IS NULL OR
                    o.account_epoch_id IS NULL
                ) = 0
                    AND COUNT(DISTINCT e.account_epoch_id) = 1
                    AND COUNT(DISTINCT o.account_epoch_id) = 1
                    AND MIN(e.account_epoch_id::text) = MIN(o.account_epoch_id::text)
                    AND BOOL_AND(e.execution_semantics_version = 'PAPER_WS_V1')
                    AND BOOL_AND(o.execution_semantics_version = 'PAPER_WS_V1') THEN 'CURRENT'
                ELSE 'LEGACY_PRE_WS'
            END AS cohort,
            COALESCE(SUM(CASE WHEN e.side = 'SELL' THEN e.realized_pnl_jpy ELSE 0 END), 0) -
                COALESCE(SUM(CASE WHEN e.side = 'BUY' THEN e.fee_jpy ELSE 0 END), 0) AS trade_pnl_jpy
        FROM positions p
        JOIN LATERAL (
            SELECT entry.order_id FROM executions entry
            WHERE entry.position_id = p.id AND entry.side = 'BUY'
            ORDER BY (entry.decision_run_id IS DISTINCT FROM p.decision_run_id), entry.executed_at, entry.id LIMIT 1
        ) initial ON TRUE
        JOIN orders entry_order ON entry_order.id = initial.order_id
        JOIN trade_intents intent ON intent.id = entry_order.intent_id
        JOIN decisions decision ON decision.id = intent.decision_id
        JOIN llm_runs run ON run.invocation_id = entry_order.decision_run_id
        JOIN executions e ON e.position_id = p.id
        LEFT JOIN orders o ON o.id = e.order_id
        WHERE p.status = 'CLOSED'
            AND p.mode = (SELECT mode FROM paper_account WHERE id = ?)
            AND p.closed_at < ?
            AND NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = p.id::text
            )
            AND NOT ${evaluationPositionCausalMissingSql("p", "entry_order", "intent", "decision", "run")}
            AND NOT EXISTS (SELECT 1 FROM infrastructure_gap_intervals gap
                WHERE run.started_at < gap.closed_at_ms AND gap.opened_at_ms < p.closed_at)
        GROUP BY p.id
    )
    SELECT COALESCE(SUM(trade_pnl_jpy), 0)
    FROM trade_stats
    WHERE account_epoch_id = ? AND cohort = ?
"""

/**
 * Exposed/JDBC で評価系読み取りを行う repository。
 *
 * @param database Exposed database
 */
@Suppress("TooManyFunctions")
class ExposedEvaluationRepository(
    private val database: ExposedDatabase,
) : EvaluationRepository {
    override suspend fun fetchDeduplicationMetrics(period: EvaluationPeriod): Result<DeduplicationMetrics> {
        return withContext(Dispatchers.IO) {
            runCatching { exposedTransaction(database) { selectDeduplicationMetrics(period) } }
        }
    }

    override suspend fun listEpochs(): Result<List<EvaluationEpochOption>> = withContext(Dispatchers.IO) {
        runCatching {
            exposedTransaction(database) {
                prepare(
                    """
                        SELECT epoch.id, epoch.kind, epoch.initial_cash_jpy, epoch.created_at,
                            epoch.id = account.current_epoch_id AS active
                        FROM mcp_evaluation_epochs epoch
                        CROSS JOIN paper_account account
                        WHERE account.id = ?
                        ORDER BY epoch.created_at DESC, epoch.id DESC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(
                                    EvaluationEpochOption(
                                        epochId = resultSet.getObject("id", UUID::class.java),
                                        kind = resultSet.getString("kind"),
                                        initialCashJpy = resultSet.getBigDecimal("initial_cash_jpy"),
                                        createdAt = Instant.ofEpochMilli(resultSet.getLong("created_at")),
                                        active = resultSet.getBoolean("active"),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun resolveScope(epochId: String?, cohort: String?): Result<EvaluationScope> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val requestedCohort = cohort?.let(EvaluationCohort::valueOf) ?: EvaluationCohort.CURRENT
                    val sql = if (epochId == null) {
                        "SELECT account_epoch_id AS id, epoch_kind AS kind, epoch_initial_cash_jpy AS initial_cash_jpy, epoch_created_at AS created_at, NULL::bigint AS next_created_at FROM mcp_current_evaluation_scope WHERE account_id=?"
                    } else {
                        "SELECT epoch.id, epoch.kind, epoch.initial_cash_jpy, epoch.created_at, (SELECT MIN(next_epoch.created_at) FROM mcp_evaluation_epochs next_epoch WHERE next_epoch.created_at > epoch.created_at) AS next_created_at FROM mcp_evaluation_epochs epoch WHERE epoch.id=?"
                    }
                    prepare(sql).use { statement ->
                        if (epochId == null) statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID) else statement.setObject(1, UUID.fromString(epochId))
                        statement.executeQuery().use { resultSet ->
                            require(resultSet.next()) { "evaluation epoch was not found" }
                            val scope = EvaluationScope(
                                accountEpochId = resultSet.getObject("id", UUID::class.java),
                                cohort = requestedCohort,
                                executionSemanticsVersion = if (requestedCohort == EvaluationCohort.CURRENT) "PAPER_WS_V1" else null,
                                initialCashJpy = resultSet.getBigDecimal("initial_cash_jpy"),
                                lifecycleFromInclusive = Instant.ofEpochMilli(resultSet.getLong("created_at")),
                                toExclusive = resultSet.getLong("next_created_at").takeUnless { resultSet.wasNull() }
                                    ?.let(Instant::ofEpochMilli),
                            )
                            val activeEpochId = selectCurrentPaperAccountEpochId()
                            if (scope.cohort == EvaluationCohort.CURRENT && scope.accountEpochId == activeEpochId) {
                                requireCurrentAccountBaselineMatchesRuntimeConfig()
                            }
                            scope
                        }
                    }
                }
            }
        }
    }

    override suspend fun fetchReportSnapshot(period: EvaluationPeriod): Result<EvaluationReportSnapshotFacts> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(
                    transactionIsolation = java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                    db = database,
                ) {
                    EvaluationReportSnapshotFacts(
                        trades = selectClosedTrades(period, DEFAULT_EVALUATION_QUERY_LIMIT),
                        dailyPnl = selectDailyTradePnl(period),
                        priorPnlJpy = sumTradePnlBeforeInstant(period.from),
                        initialCashJpy = selectInitialCashJpy(),
                        usages = selectLlmPhaseUsages(period, DEFAULT_EVALUATION_QUERY_LIMIT),
                        exclusions = selectEvaluationExclusionSummary(period),
                    )
                }
            }
        }
    }

    override suspend fun fetchReportSnapshot(
        period: EvaluationPeriod,
        scope: EvaluationScope,
    ): Result<EvaluationReportSnapshotFacts> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(
                    transactionIsolation = java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                    db = database,
                ) {
                    val scopedPeriod = EvaluationPeriod(
                        maxOf(period.from, scope.lifecycleFromInclusive),
                        minOf(period.toExclusive, scope.toExclusive ?: period.toExclusive),
                    )
                    val trades = selectClosedTrades(
                        period,
                        DEFAULT_EVALUATION_QUERY_LIMIT,
                        scope,
                    )

                    EvaluationReportSnapshotFacts(
                        trades = trades,
                        dailyPnl = trades.strategyEligibleTrades.map { trade ->
                            DailyTradePnlFact(trade.closedAt, trade.tradePnlJpy)
                        },
                        priorPnlJpy = sumScopedTradePnlBefore(period.from, scope),
                        initialCashJpy = scope.initialCashJpy,
                        usages = if (scope.cohort == EvaluationCohort.CURRENT) {
                            selectLlmPhaseUsages(
                                scopedPeriod,
                                DEFAULT_EVALUATION_QUERY_LIMIT,
                            )
                        } else {
                            EvaluationLlmUsageQueryResult(emptyList(), truncated = false)
                        },
                        exclusions = if (scope.cohort == EvaluationCohort.CURRENT) {
                            selectEvaluationExclusionSummary(scopedPeriod)
                        } else {
                            EvaluationExclusionSummary()
                        },
                    )
                }
            }
        }
    }

    override suspend fun fetchExclusionSummary(period: EvaluationPeriod): Result<EvaluationExclusionSummary> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) { selectEvaluationExclusionSummary(period) }
            }
        }
    }

    override suspend fun fetchClosedTrades(period: EvaluationPeriod, limit: Int): Result<EvaluationTradeQueryResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectClosedTrades(period, limit, selectCurrentEvaluationScope())
                }
            }
        }

    override suspend fun fetchClosedTrades(
        period: EvaluationPeriod,
        limit: Int,
        scope: EvaluationScope,
    ): Result<EvaluationTradeQueryResult> = fetchClosedTradesInternal(period, limit, scope)

    private suspend fun fetchClosedTradesInternal(
        period: EvaluationPeriod,
        limit: Int,
        scope: EvaluationScope?,
    ): Result<EvaluationTradeQueryResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectClosedTrades(period, limit, scope)
                }
            }
        }
    }

    override suspend fun countDecisionRuns(period: EvaluationPeriod): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    countDecisionRunsForPeriod(period)
                }
            }
        }
    }

    override suspend fun countDecisionsByAction(period: EvaluationPeriod): Result<List<DecisionActionCount>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    countDecisionsByActionForPeriod(period)
                }
            }
        }
    }

    override suspend fun fetchDailyTradePnl(period: EvaluationPeriod): Result<List<DailyTradePnlFact>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectDailyTradePnl(period)
                }
            }
        }
    }

    override suspend fun sumTradePnlBefore(instant: Instant): Result<BigDecimal> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    sumTradePnlBeforeInstant(instant)
                }
            }
        }
    }

    override suspend fun sumTradePnlBefore(instant: Instant, scope: EvaluationScope): Result<BigDecimal> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) { sumScopedTradePnlBefore(instant, scope) }
            }
        }
    }

    override suspend fun fetchInitialCashJpy(): Result<BigDecimal> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectInitialCashJpy()
                }
            }
        }
    }

    override suspend fun fetchLlmPhaseUsages(
        period: EvaluationPeriod,
        limit: Int,
    ): Result<EvaluationLlmUsageQueryResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectLlmPhaseUsages(period, limit)
                }
            }
        }
    }

    override suspend fun classifyPopulationEntities(
        period: EvaluationPeriod,
        entityType: EvaluationPopulationEntityType,
        entityIds: Set<String>,
    ): Result<Map<String, EvaluationPopulationStatus>> {
        if (entityIds.isEmpty()) return Result.success(emptyMap())
        require(entityIds.size <= DEFAULT_EVALUATION_QUERY_LIMIT + 1) {
            "EVALUATION_POPULATION_UNAVAILABLE:ENTITY_LIMIT"
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectPopulationEntityStatuses(period, entityType, entityIds)
                }
            }
        }
    }

    override suspend fun fetchKillCriterionStats(): Result<KillCriterionStats> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    requireCurrentAccountBaselineMatchesRuntimeConfig()
                    val scope = selectCurrentEvaluationScope()
                    setEvaluationRequestBounds(EvaluationPeriod(scope.lifecycleFromInclusive, Instant.now()))
                    selectKillCriterionStats()
                }
            }
        }
    }
}

private fun JdbcTransaction.selectPopulationEntityStatuses(
    period: EvaluationPeriod,
    entityType: EvaluationPopulationEntityType,
    entityIds: Set<String>,
): Map<String, EvaluationPopulationStatus> {
    setEvaluationRequestBounds(period)
    val entityProjection = populationEntityProjectionSql(entityType)
    return prepare(
        """
            WITH $EVALUATION_GAP_INTERVAL_CTE_V1,
            requested AS (SELECT unnest(?::text[]) AS entity_id),
            entities AS ($entityProjection)
            SELECT entity_id,
                CASE
                    WHEN missing THEN 'ATTRIBUTION_MISSING'
                    WHEN EXISTS (
                        SELECT 1 FROM infrastructure_gap_intervals gap
                        WHERE entities.cause_at < gap.closed_at_ms AND gap.opened_at_ms < entities.terminal_at
                    ) THEN 'INFRASTRUCTURE_GAP'
                    ELSE 'ELIGIBLE'
                END AS population_status
            FROM entities
        """.trimIndent(),
    ).use { statement ->
        statement.setArray(1, jdbcConnection().createArrayOf("text", entityIds.toTypedArray()))
        when (entityType) {
            EvaluationPopulationEntityType.RUN -> {
                statement.setLong(2, period.toExclusive.toEpochMilli())
                statement.setLong(3, period.toExclusive.toEpochMilli())
                statement.setLong(4, period.from.toEpochMilli())
            }

            EvaluationPopulationEntityType.DECISION -> {
                statement.setLong(2, period.from.toEpochMilli())
                statement.setLong(3, period.toExclusive.toEpochMilli())
            }
        }
        statement.executeQuery().use { resultSet ->
            buildMap {
                while (resultSet.next()) {
                    put(
                        resultSet.getString("entity_id"),
                        EvaluationPopulationStatus.valueOf(resultSet.getString("population_status")),
                    )
                }
            }
        }
    }
}

private fun populationEntityProjectionSql(entityType: EvaluationPopulationEntityType): String {
    return when (entityType) {
        EvaluationPopulationEntityType.RUN -> """
            SELECT requested.entity_id,
                run.started_at AS cause_at,
                COALESCE(run.finished_at, (extract(epoch FROM query_clock.query_now) * 1000)::bigint) AS terminal_at,
                ${evaluationRunMissingSql("run")} OR EXISTS (
                    SELECT 1 FROM decisions d
                    JOIN trade_intents i ON i.decision_id=d.id
                    JOIN orders o ON o.intent_id=i.id
                    WHERE d.invocation_id=run.invocation_id
                        AND o.decision_run_id IS DISTINCT FROM d.invocation_id
                ) AS missing
            FROM requested
            CROSS JOIN query_clock
            LEFT JOIN llm_runs run ON run.invocation_id = requested.entity_id
                AND run.started_at < ? AND COALESCE(run.finished_at, ?) >= ?
        """.trimIndent()

        EvaluationPopulationEntityType.DECISION -> """
            SELECT requested.entity_id,
                run.started_at AS cause_at,
                GREATEST(decision.created_at, COALESCE(run.finished_at, decision.created_at)) AS terminal_at,
                decision.id IS NULL OR decision.invocation_id IS NULL OR
                    ${evaluationRunMissingSql("run")} OR EXISTS (
                        SELECT 1 FROM trade_intents i
                        JOIN orders o ON o.intent_id=i.id
                        WHERE i.decision_id=decision.id
                            AND o.decision_run_id IS DISTINCT FROM decision.invocation_id
                    ) AS missing
            FROM requested
            LEFT JOIN decisions decision ON decision.id::text = requested.entity_id
                AND decision.created_at >= ? AND decision.created_at < ?
            LEFT JOIN llm_runs run ON run.invocation_id = decision.invocation_id
        """.trimIndent()
    }
}

private fun JdbcTransaction.selectKillCriterionStats(): KillCriterionStats {
    return prepare(SELECT_KILL_CRITERION_STATS_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setInt(2, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "kill criterion stats did not return a row" }
            val grossProfit = resultSet.getBigDecimal("gross_profit")
            val grossLoss = resultSet.getBigDecimal("gross_loss")
            KillCriterionStats(
                closedTrades = resultSet.getInt("closed_trades"),
                profitFactor = grossLoss.takeIf { it > BigDecimal.ZERO }?.let { loss ->
                    grossProfit.divide(loss, EVALUATION_SQL_SCALE, java.math.RoundingMode.HALF_UP)
                },
            )
        }
    }
}

private fun JdbcTransaction.sumScopedTradePnlBefore(instant: Instant, scope: EvaluationScope): BigDecimal {
    setEvaluationRequestBounds(EvaluationPeriod(scope.lifecycleFromInclusive, instant))
    return prepare(SELECT_SCOPED_PNL_BEFORE_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setLong(2, instant.toEpochMilli())
        statement.setString(3, scope.accountEpochId.toString())
        statement.setString(4, scope.cohort.name)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "scoped prior PnL did not return a row" }
            resultSet.getBigDecimal(1) ?: BigDecimal.ZERO
        }
    }
}

private fun JdbcTransaction.selectCurrentEvaluationScope(): EvaluationScope {
    requireCurrentAccountBaselineMatchesRuntimeConfig()

    return prepare(
        """
            SELECT account_epoch_id AS id, epoch_kind AS kind,
                epoch_initial_cash_jpy AS initial_cash_jpy, epoch_created_at AS created_at
            FROM mcp_current_evaluation_scope
            WHERE account_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "current evaluation epoch was not found" }
            EvaluationScope(
                accountEpochId = resultSet.getObject("id", UUID::class.java),
                cohort = EvaluationCohort.CURRENT,
                executionSemanticsVersion = "PAPER_WS_V1",
                initialCashJpy = resultSet.getBigDecimal("initial_cash_jpy"),
                lifecycleFromInclusive = Instant.ofEpochMilli(resultSet.getLong("created_at")),
            )
        }
    }
}

/** active CURRENT 評価は runtime config と account baseline の不一致を黙認しない。 */
private fun JdbcTransaction.requireCurrentAccountBaselineMatchesRuntimeConfig() {
    val baselines = prepare(
        """
            SELECT account_initial_cash_jpy AS initial_cash_jpy,
                epoch_initial_cash_jpy AS epoch_baseline,
                config_initial_cash_jpy AS config_baseline
            FROM mcp_current_evaluation_scope
            WHERE account_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "PAPER_ACCOUNT_BASELINE_MISMATCH: paper account is missing." }
            Triple(
                resultSet.getBigDecimal("initial_cash_jpy"),
                resultSet.getBigDecimal("epoch_baseline"),
                resultSet.getString("config_baseline")?.let(::BigDecimal),
            )
        }
    }
    val accountMatchesEpoch = baselines.first.compareTo(baselines.second) == 0
    val accountMatchesConfig = baselines.third != null && baselines.first.compareTo(baselines.third) == 0
    require(accountMatchesEpoch && accountMatchesConfig) {
        "PAPER_ACCOUNT_BASELINE_MISMATCH: create, validate, and activate an operator runtime-config draft."
    }
}

private fun JdbcTransaction.selectCurrentPaperAccountEpochId(): UUID {
    return prepare("SELECT current_epoch_id FROM paper_account WHERE id = ?").use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "current paper account epoch was not found" }
            requireNotNull(resultSet.getObject("current_epoch_id", UUID::class.java))
        }
    }
}

private fun JdbcTransaction.selectEvaluationExclusionSummary(period: EvaluationPeriod): EvaluationExclusionSummary {
    setEvaluationRequestBounds(period)
    exec("SET LOCAL statement_timeout = '2s'")
    val existingSummary = selectExistingEvaluationExclusionSummary(period)
    val infrastructureGaps = selectInfrastructureGaps(period)
    val affectedCounts = selectInfrastructureAffectedCounts(period)
    val populationByEntityType = selectPopulationCounts(period)

    return existingSummary.copy(
        infrastructureGaps = infrastructureGaps,
        infrastructureAffectedTradeCount = affectedCounts.first,
        infrastructureAttributionMissingCount = affectedCounts.second,
        populationByEntityType = populationByEntityType,
        reasons = existingSummary.reasons + mapOf("INFRASTRUCTURE_GAP" to affectedCounts.first),
    )
}

@Suppress("LongMethod")
private fun JdbcTransaction.selectPopulationCounts(period: EvaluationPeriod): Map<String, EvaluationPopulationCounts> {
    setEvaluationRequestBounds(period)
    return prepare(
        """
            WITH $EVALUATION_GAP_INTERVAL_CTE_V1,
            entities AS (
                (SELECT 'RUN' entity_type, r.invocation_id entity_id, r.started_at cause_at,
                    COALESCE(r.finished_at, (extract(epoch FROM (SELECT query_now FROM query_clock))*1000)::bigint) terminal_at,
                    ${evaluationRunMissingSql("r")} OR EXISTS (
                        SELECT 1 FROM decisions rd
                        JOIN trade_intents ri ON ri.decision_id=rd.id
                        JOIN orders ro ON ro.intent_id=ri.id
                        WHERE rd.invocation_id=r.invocation_id
                            AND ro.decision_run_id IS DISTINCT FROM rd.invocation_id
                    ) missing
                FROM llm_runs r
                WHERE r.started_at < ? AND COALESCE(r.finished_at, ?) >= ?
                ORDER BY r.started_at,r.invocation_id LIMIT 20001)
                UNION ALL
                (SELECT 'DECISION', d.id::text, r.started_at, GREATEST(d.created_at, COALESCE(r.finished_at, d.created_at)),
                    d.invocation_id IS NULL OR ${evaluationRunMissingSql("r")} OR EXISTS (
                        SELECT 1 FROM trade_intents di
                        JOIN orders causal_order ON causal_order.intent_id=di.id
                        WHERE di.decision_id=d.id
                            AND causal_order.decision_run_id IS DISTINCT FROM d.invocation_id
                    )
                FROM decisions d LEFT JOIN llm_runs r ON r.invocation_id=d.invocation_id
                WHERE d.created_at >= ? AND d.created_at < ?
                ORDER BY d.created_at,d.id LIMIT 20001)
                UNION ALL
                (SELECT 'ORDER', o.id::text, r.started_at, COALESCE(o.canceled_at,o.expired_at,o.updated_at,?),
                    ${evaluationOrderCausalMissingSql("o", "i", "d", "r")}
                FROM orders o
                LEFT JOIN trade_intents i ON i.id=o.intent_id
                LEFT JOIN decisions d ON d.id=i.decision_id
                LEFT JOIN llm_runs r ON r.invocation_id=o.decision_run_id
                WHERE o.created_at < ? AND o.updated_at >= ?
                ORDER BY o.created_at,o.id LIMIT 20001)
                UNION ALL
                (SELECT 'POSITION', p.id::text, r.started_at, COALESCE(p.closed_at,?),
                    entry.id IS NULL OR ${evaluationPositionCausalMissingSql("p", "o", "i", "d", "r")}
                FROM positions p
                LEFT JOIN LATERAL (
                    SELECT e.id,e.order_id FROM executions e
                    WHERE e.position_id=p.id AND e.side='BUY'
                    ORDER BY (e.decision_run_id IS DISTINCT FROM p.decision_run_id),e.executed_at,e.id LIMIT 1
                ) entry ON TRUE
                LEFT JOIN orders o ON o.id=entry.order_id
                LEFT JOIN trade_intents i ON i.id=o.intent_id
                LEFT JOIN decisions d ON d.id=i.decision_id
                LEFT JOIN llm_runs r ON r.invocation_id=o.decision_run_id
                WHERE p.opened_at < ? AND COALESCE(p.closed_at,?) >= ?
                ORDER BY p.opened_at,p.id LIMIT 20001)
                UNION ALL
                (SELECT 'EXECUTION', e.id::text, r.started_at, e.executed_at+1,
                    e.order_id IS NULL OR ${evaluationExecutionCausalMissingSql("e", "o", "i", "d", "r")}
                FROM executions e
                LEFT JOIN orders o ON o.id=e.order_id
                LEFT JOIN trade_intents i ON i.id=o.intent_id
                LEFT JOIN decisions d ON d.id=i.decision_id
                LEFT JOIN llm_runs r ON r.invocation_id=o.decision_run_id
                WHERE e.executed_at >= ? AND e.executed_at < ?
                ORDER BY e.executed_at,e.id LIMIT 20001)
                UNION ALL
                (SELECT 'TRADE', p.id::text, r.started_at, p.closed_at,
                    entry.id IS NULL OR ${evaluationPositionCausalMissingSql("p", "o", "i", "d", "r")}
                FROM positions p
                LEFT JOIN LATERAL (
                    SELECT e.id,e.order_id FROM executions e
                    WHERE e.position_id=p.id AND e.side='BUY'
                    ORDER BY (e.decision_run_id IS DISTINCT FROM p.decision_run_id),e.executed_at,e.id LIMIT 1
                ) entry ON TRUE
                LEFT JOIN orders o ON o.id=entry.order_id
                LEFT JOIN trade_intents i ON i.id=o.intent_id
                LEFT JOIN decisions d ON d.id=i.decision_id
                LEFT JOIN llm_runs r ON r.invocation_id=o.decision_run_id
                WHERE p.status='CLOSED' AND p.closed_at >= ? AND p.closed_at < ?
                ORDER BY p.closed_at,p.id LIMIT 20001)
            ), projected AS (
                SELECT entity_type, missing,
                    NOT missing AND EXISTS (SELECT 1 FROM infrastructure_gap_intervals gap WHERE entities.cause_at < gap.closed_at_ms AND gap.opened_at_ms < entities.terminal_at) affected
                FROM entities
            )
            SELECT entity_type, COUNT(*) total,
                COUNT(*) FILTER (WHERE NOT missing AND NOT affected) eligible,
                COUNT(*) FILTER (WHERE affected) infrastructure_gap,
                COUNT(*) FILTER (WHERE missing) attribution_missing
            FROM projected GROUP BY entity_type ORDER BY entity_type
        """.trimIndent(),
    ).use { statement ->
        val from = period.from.toEpochMilli()
        val to = period.toExclusive.toEpochMilli()
        var index = 1
        listOf(to, to, from, from, to, to, to, from, to, to, to, from, from, to, from, to).forEach { value ->
            statement.setLong(index++, value)
        }
        statement.executeQuery().use { result ->
            buildMap {
                while (result.next()) {
                    val total = result.getInt("total")
                    check(total <= DEFAULT_EVALUATION_QUERY_LIMIT) {
                        "EVALUATION_POPULATION_UNAVAILABLE:ENTITY_LIMIT:${result.getString("entity_type")}:$total"
                    }
                    put(
                        result.getString("entity_type"),
                        EvaluationPopulationCounts(
                            total = total,
                            eligible = result.getInt("eligible"),
                            infrastructureGap = result.getInt("infrastructure_gap"),
                            attributionMissing = result.getInt("attribution_missing"),
                        ),
                    )
                }
            }
        }
    }
}

private fun JdbcTransaction.selectExistingEvaluationExclusionSummary(
    period: EvaluationPeriod,
): EvaluationExclusionSummary {
    return prepare(
        """
            SELECT entity_type, reason, COUNT(DISTINCT entity_id) AS entity_count
            FROM evaluation_exclusions
            WHERE created_at >= ? AND created_at < ?
            GROUP BY entity_type, reason
        """,
    ).use { statement ->
        statement.setLong(1, period.from.toEpochMilli())
        statement.setLong(2, period.toExclusive.toEpochMilli())
        statement.executeQuery().use(ResultSet::toEvaluationExclusionSummary)
    }
}

private fun JdbcTransaction.selectInfrastructureGaps(period: EvaluationPeriod): List<EvaluationInfrastructureGap> {
    setEvaluationRequestBounds(period)
    return prepare(
        """
            WITH $EVALUATION_GAP_INTERVAL_CTE_V1,
            integrity AS (
                SELECT COUNT(*) AS invalid_count
                FROM infrastructure_gap_events event
                WHERE (event.boundary = 'CLOSE' AND NOT EXISTS (
                    SELECT 1 FROM infrastructure_gap_events opened
                    WHERE opened.gap_id = event.gap_id AND opened.boundary = 'OPEN'
                      AND opened.deployment_id = event.deployment_id AND opened.reason = event.reason
                      AND opened.occurred_at <= event.occurred_at
                )) OR event.payload_hash !~ '^[0-9a-f]{64}$'
            ), selected AS (
                SELECT gap_id, reason, opened_at, closed_at
                FROM infrastructure_gap_intervals
                WHERE opened_at_ms < ? AND closed_at_ms > ?
                ORDER BY opened_at, gap_id
                LIMIT 1001
            )
            SELECT selected.gap_id, selected.reason, selected.opened_at, selected.closed_at, integrity.invalid_count
            FROM integrity LEFT JOIN selected ON TRUE
            ORDER BY selected.opened_at, selected.gap_id
        """.trimIndent(),
    ).use { statement ->
        statement.setLong(1, period.toExclusive.toEpochMilli())
        statement.setLong(2, period.from.toEpochMilli())
        statement.executeQuery().use { result ->
            val gaps = buildList {
                while (result.next()) {
                    check(result.getLong("invalid_count") == 0L) { "EVALUATION_GAP_POPULATION_UNAVAILABLE:INTEGRITY" }
                    if (result.getObject("gap_id") == null) continue
                    add(
                        EvaluationInfrastructureGap(
                            id = result.getString("gap_id"),
                            reason = result.getString("reason"),
                            startedAt = result.getTimestamp("opened_at").toInstant(),
                            endedAt = result.getTimestamp("closed_at")?.toInstant(),
                        ),
                    )
                }
            }
            check(gaps.size <= 1000) { "EVALUATION_GAP_POPULATION_UNAVAILABLE:LIMIT" }
            gaps
        }
    }
}

private fun JdbcTransaction.selectInfrastructureAffectedCounts(period: EvaluationPeriod): Pair<Int, Int> {
    setEvaluationRequestBounds(period)
    return prepare(
        """
            WITH $EVALUATION_GAP_INTERVAL_CTE_V1
            SELECT
                COUNT(DISTINCT p.id) AS affected_count,
                COUNT(DISTINCT p.id) FILTER (
                    WHERE ${evaluationPositionCausalMissingSql("p", "o", "ti", "d", "run")}
                )
                    AS attribution_missing_count
            FROM positions p
            LEFT JOIN LATERAL (
                SELECT candidate.id,candidate.order_id FROM executions candidate
                WHERE candidate.position_id=p.id AND candidate.side='BUY'
                ORDER BY (candidate.decision_run_id IS DISTINCT FROM p.decision_run_id),
                    candidate.executed_at,candidate.id
                LIMIT 1
            ) e ON TRUE
            LEFT JOIN orders o ON o.id = e.order_id
            LEFT JOIN trade_intents ti ON ti.id = o.intent_id
            LEFT JOIN decisions d ON d.id = ti.decision_id
            LEFT JOIN llm_runs run ON run.invocation_id = o.decision_run_id
            WHERE p.status = 'CLOSED'
                AND p.closed_at >= ? AND p.closed_at < ?
                AND EXISTS (
                    SELECT 1 FROM infrastructure_gap_intervals gap
                    WHERE run.started_at < gap.closed_at_ms AND gap.opened_at_ms < p.closed_at
                )
        """.trimIndent(),
    ).use { statement ->
        statement.setLong(1, period.from.toEpochMilli())
        statement.setLong(2, period.toExclusive.toEpochMilli())
        statement.executeQuery().use { result ->
            check(result.next())
            result.getInt("affected_count") to result.getInt("attribution_missing_count")
        }
    }
}

private fun ResultSet.toEvaluationExclusionSummary(): EvaluationExclusionSummary {
    val counts = mutableMapOf<String, Int>()
    val reasons = mutableMapOf<String, Int>()

    while (next()) {
        val count = getInt("entity_count")
        val entityType = getString("entity_type")
        counts[entityType] = counts.getOrDefault(entityType, 0) + count
        val reason = getString("reason")
        reasons[reason] = reasons.getOrDefault(reason, 0) + count
    }

    return EvaluationExclusionSummary(
        orderCount = counts.getOrDefault("ORDER", 0),
        decisionRunCount = counts.getOrDefault("DECISION_RUN", 0),
        positionCount = counts.getOrDefault("POSITION", 0),
        reasons = reasons,
    )
}

private fun JdbcTransaction.selectClosedTrades(
    period: EvaluationPeriod,
    limit: Int,
    scope: EvaluationScope? = null,
): EvaluationTradeQueryResult {
    require(limit in 1 until Int.MAX_VALUE) { "limit must be finite and greater than zero" }
    setEvaluationRequestBounds(period)
    val fetchLimit = limit + 1

    return jdbcConnection().prepareStatement(SELECT_CLOSED_TRADE_FACTS_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setObject(2, scope?.accountEpochId)
        statement.setObject(3, scope?.accountEpochId)
        statement.setString(4, scope?.cohort?.name)
        statement.setLong(5, period.from.toEpochMilli())
        statement.setLong(6, period.toExclusive.toEpochMilli())
        statement.setObject(7, scope?.accountEpochId)
        statement.setObject(8, scope?.accountEpochId)
        statement.setString(9, scope?.cohort?.name)
        statement.setString(10, scope?.cohort?.name)
        statement.setInt(11, fetchLimit)
        statement.executeQuery().use { resultSet ->
            val trades = buildList {
                while (resultSet.next()) {
                    add(resultSet.toClosedTradeFact())
                }
            }
            val truncated = trades.size > limit
            val page = trades.take(limit)

            EvaluationTradeQueryResult(
                trades = page,
                truncated = truncated,
                attributionCoverage = EvaluationAttributionCoverage(
                    attributed = page.count { trade ->
                        trade.attributionStatus == EvaluationAttributionStatus.ATTRIBUTED &&
                            trade.infrastructureGapIds.isEmpty()
                    },
                    missing = page.count { trade ->
                        trade.attributionStatus == EvaluationAttributionStatus.MISSING ||
                            trade.infrastructureGapIds.isNotEmpty()
                    },
                    total = page.size,
                ),
            )
        }
    }
}

private fun JdbcTransaction.countDecisionRunsForPeriod(period: EvaluationPeriod): Int {
    setEvaluationRequestBounds(period)
    return jdbcConnection().prepareStatement(COUNT_EVALUATION_DECISION_RUNS_SQL).use { statement ->
        statement.setLong(1, period.from.toEpochMilli())
        statement.setLong(2, period.toExclusive.toEpochMilli())
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getInt(1) else 0
        }
    }
}

private fun JdbcTransaction.countDecisionsByActionForPeriod(period: EvaluationPeriod): List<DecisionActionCount> {
    setEvaluationRequestBounds(period)
    return jdbcConnection().prepareStatement(COUNT_DECISIONS_BY_ACTION_SQL).use { statement ->
        statement.setLong(1, period.from.toEpochMilli())
        statement.setLong(2, period.toExclusive.toEpochMilli())
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        DecisionActionCount(
                            action = resultSet.getString(1),
                            count = resultSet.getInt(2),
                        ),
                    )
                }
            }
        }
    }
}

private fun JdbcTransaction.selectDailyTradePnl(period: EvaluationPeriod): List<DailyTradePnlFact> {
    setEvaluationRequestBounds(period)
    return jdbcConnection().prepareStatement(SELECT_DAILY_TRADE_PNL_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setLong(2, period.from.toEpochMilli())
        statement.setLong(3, period.toExclusive.toEpochMilli())
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        DailyTradePnlFact(
                            closedAt = resultSet.getInstant("closed_at"),
                            pnlJpy = resultSet.getBigDecimal("trade_pnl_jpy") ?: BigDecimal.ZERO,
                        ),
                    )
                }
            }
        }
    }
}

private fun JdbcTransaction.sumTradePnlBeforeInstant(instant: Instant): BigDecimal {
    setEvaluationRequestBounds(EvaluationPeriod(Instant.EPOCH, instant))
    return jdbcConnection().prepareStatement(SUM_TRADE_PNL_BEFORE_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setLong(2, instant.toEpochMilli())
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getBigDecimal(1) ?: BigDecimal.ZERO else BigDecimal.ZERO
        }
    }
}

private fun JdbcTransaction.selectInitialCashJpy(): BigDecimal {
    return jdbcConnection().prepareStatement(SELECT_INITIAL_CASH_JPY_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "paper_account initial_cash_jpy did not return a row." }

            resultSet.getBigDecimal(1)
        }
    }
}

private fun JdbcTransaction.selectLlmPhaseUsages(period: EvaluationPeriod, limit: Int): EvaluationLlmUsageQueryResult {
    setEvaluationRequestBounds(period)
    val fetchLimit = limit + 1

    return jdbcConnection().prepareStatement(SELECT_LLM_PHASE_USAGE_SQL).use { statement ->
        statement.setLong(1, period.from.toEpochMilli())
        statement.setLong(2, period.toExclusive.toEpochMilli())
        statement.setInt(3, fetchLimit)
        statement.executeQuery().use { resultSet ->
            val facts = buildList {
                while (resultSet.next()) {
                    add(resultSet.toLlmPhaseUsageFact())
                }
            }
            val truncated = facts.size > limit

            EvaluationLlmUsageQueryResult(
                facts = facts.take(limit),
                truncated = truncated,
            )
        }
    }
}

private fun JdbcTransaction.selectDeduplicationMetrics(period: EvaluationPeriod): DeduplicationMetrics {
    val sql = """WITH boundary AS (
        SELECT activated_at FROM decision_identity_schema_boundaries WHERE schema_version = 1
      ) SELECT
        (SELECT COUNT(*) FROM decisions, boundary WHERE action IN ('ENTER','ADD_LONG') AND created_at>=? AND created_at<? AND created_at >= boundary.activated_at),
        (SELECT COUNT(*) FROM decisions, boundary WHERE action IN ('ENTER','ADD_LONG') AND created_at>=? AND created_at<? AND created_at >= boundary.activated_at AND opportunity_episode_id IS NOT NULL AND thesis_id IS NOT NULL AND geometry_hash IS NOT NULL AND material_state_hash IS NOT NULL AND identity_schema_version IS NOT NULL),
        (SELECT COUNT(*) FROM trade_intents, boundary WHERE created_at>=? AND created_at<? AND created_at >= boundary.activated_at),
        (SELECT COUNT(*) FROM trade_intents, boundary WHERE created_at>=? AND created_at<? AND created_at >= boundary.activated_at AND opportunity_episode_id IS NOT NULL AND thesis_id IS NOT NULL AND geometry_hash IS NOT NULL AND material_state_hash IS NOT NULL AND identity_schema_version IS NOT NULL),
        (SELECT COUNT(*) FROM dedupe_shadow_observations WHERE observed_at>=? AND observed_at<?),
        (SELECT COUNT(*) FROM dedupe_shadow_observations WHERE observed_at>=? AND observed_at<? AND classification IS NOT NULL AND opportunity_episode_id IS NOT NULL AND data_quality='COMPLETE'),
        (SELECT COUNT(DISTINCT opportunity_episode_id) FROM dedupe_shadow_observations WHERE observed_at>=? AND observed_at<?),
        (SELECT COUNT(DISTINCT maintenance_tick_id) FROM dedupe_shadow_observations WHERE observation_kind='RESTING_MAINTENANCE' AND observed_at>=? AND observed_at<? AND maintenance_tick_id IS NOT NULL),
        (SELECT COUNT(DISTINCT (maintenance_tick_id, reference_order_id)) FROM dedupe_shadow_observations WHERE observation_kind='RESTING_MAINTENANCE' AND observed_at>=? AND observed_at<? AND maintenance_tick_id IS NOT NULL AND reference_order_id IS NOT NULL),
        (SELECT COUNT(*) FROM decisions, boundary WHERE action IN ('ENTER','ADD_LONG') AND created_at>=? AND created_at<? AND created_at < boundary.activated_at),
        (SELECT COUNT(*) FROM trade_intents, boundary WHERE created_at>=? AND created_at<? AND created_at < boundary.activated_at)
    """.trimIndent()
    return jdbcConnection().prepareStatement(sql).use { statement ->
        var index = 1
        repeat(9) {
            statement.setLong(index++, period.from.toEpochMilli())
            statement.setLong(index++, period.toExclusive.toEpochMilli())
        }
        repeat(2) {
            statement.setLong(index++, period.from.toEpochMilli())
            statement.setLong(index++, period.toExclusive.toEpochMilli())
        }
        statement.executeQuery().use { result ->
            check(result.next())
            val classificationCounts = selectClassificationCounts(period)
            val resolutionCounts = selectResolutionCounts(period)
            val launchCounts = selectDedupeLaunchCounts(period)
            DeduplicationMetrics(
                decisionEligible = result.getInt(1),
                decisionComplete = result.getInt(2),
                intentEligible = result.getInt(3),
                intentComplete = result.getInt(4),
                shadowEligible = result.getInt(5),
                shadowComplete = result.getInt(6),
                uniqueEpisodeCount = result.getInt(7),
                rawSuppressedHeartbeatCount = result.getInt(8),
                restingMaintenanceObservationCount = result.getInt(9),
                legacyExcludedCount = result.getInt(10) + result.getInt(11),
                decisionLegacyExcludedCount = result.getInt(10),
                decisionGenerationFailureCount = result.getInt(1) - result.getInt(2),
                intentLegacyExcludedCount = result.getInt(11),
                intentGenerationFailureCount = result.getInt(3) - result.getInt(4),
                classificationCounts = classificationCounts,
                falseSuppressionCount = resolutionCounts["FALSE_SUPPRESSION_PROXY"] ?: 0,
                validSuppressionCount = resolutionCounts["VALID_SUPPRESSION_PROXY"] ?: 0,
                pendingCount = resolutionCounts["PENDING"] ?: 0,
                unknownCount = resolutionCounts["UNKNOWN_DATA"] ?: 0,
                restingOnlyDaemonFullRunCount = launchCounts.restingOnlyDaemon,
                manualFullRunCount = launchCounts.manual,
            )
        }
    }
}

private fun JdbcTransaction.selectClassificationCounts(period: EvaluationPeriod): Map<String, Int> {
    val sql = """SELECT classification, COUNT(*) FROM dedupe_shadow_observations
        WHERE observed_at >= ? AND observed_at < ? AND classification IS NOT NULL GROUP BY classification
    """.trimIndent()
    return jdbcConnection().prepareStatement(sql).use { statement ->
        statement.setLong(1, period.from.toEpochMilli())
        statement.setLong(2, period.toExclusive.toEpochMilli())
        statement.executeQuery().use { result ->
            buildMap { while (result.next()) put(result.getString(1), result.getInt(2)) }
        }
    }
}

private fun JdbcTransaction.selectResolutionCounts(period: EvaluationPeriod): Map<String, Int> {
    val sql = """WITH episode_facts AS (
        SELECT o.opportunity_episode_id,
          BOOL_OR(o.invalidation_state = 'INVALIDATED' OR o.old_material_state_hash IS DISTINCT FROM o.new_material_state_hash) AS false_proxy,
          BOOL_OR(o.data_quality <> 'COMPLETE' OR o.invalidation_state = 'UNKNOWN_DATA') AS unknown_data,
          MAX(e.closed_at) FILTER (WHERE e.closed_at < ?) AS closed_at
        FROM dedupe_shadow_observations o
        LEFT JOIN opportunity_episodes e ON e.id = o.opportunity_episode_id
        WHERE o.observed_at >= ? AND o.observed_at < ? AND o.opportunity_episode_id IS NOT NULL
        GROUP BY o.opportunity_episode_id
      ), folded AS (
        SELECT CASE
          WHEN false_proxy THEN 'FALSE_SUPPRESSION_PROXY'
          WHEN unknown_data THEN 'UNKNOWN_DATA'
          WHEN closed_at IS NULL THEN 'PENDING'
          ELSE 'VALID_SUPPRESSION_PROXY'
        END AS resolution FROM episode_facts
      ) SELECT resolution, COUNT(*) FROM folded GROUP BY resolution
    """.trimIndent()
    return jdbcConnection().prepareStatement(sql).use { statement ->
        statement.setLong(1, period.toExclusive.toEpochMilli())
        statement.setLong(2, period.from.toEpochMilli())
        statement.setLong(3, period.toExclusive.toEpochMilli())
        statement.executeQuery().use { result ->
            buildMap { while (result.next()) put(result.getString(1), result.getInt(2)) }
        }
    }
}

private data class DedupeLaunchCounts(val restingOnlyDaemon: Int, val manual: Int)

private fun JdbcTransaction.selectDedupeLaunchCounts(period: EvaluationPeriod): DedupeLaunchCounts {
    val sql = """SELECT
      COUNT(*) FILTER (
        WHERE payload LIKE '%\"triggerKind\":\"MANUAL\"%'
        AND payload ~ '\"restingEntryOrderCount\":[1-9][0-9]*'
      ),
      COUNT(*) FILTER (
        WHERE payload LIKE '%\"restingOnly\":true%'
        AND payload NOT LIKE '%\"triggerKind\":\"MANUAL\"%'
      ) FROM command_event_log
      WHERE event_type = 'DAEMON_TRIGGER_LAUNCHED' AND ts >= ? AND ts < ?
    """.trimIndent()
    return jdbcConnection().prepareStatement(sql).use { statement ->
        statement.setLong(1, period.from.toEpochMilli())
        statement.setLong(2, period.toExclusive.toEpochMilli())
        statement.executeQuery().use { result ->
            check(result.next())
            DedupeLaunchCounts(restingOnlyDaemon = result.getInt(2), manual = result.getInt(1))
        }
    }
}

private fun ResultSet.toClosedTradeFact(): ClosedTradeFact {
    return ClosedTradeFact(
        positionId = getUuid("position_id"),
        openedAt = getInstant("opened_at"),
        closedAt = getInstant("closed_at"),
        sizeBtc = getBigDecimal("size_btc"),
        averageEntryPriceJpy = getBigDecimal("average_entry_price_jpy"),
        entryWeightedProtectiveStopPriceJpy = getNullableBigDecimal("protective_stop_price_jpy"),
        highestPriceSinceEntryJpy = getBigDecimal("highest_price_since_entry_jpy"),
        lowestPriceSinceEntryJpy = getNullableBigDecimal("lowest_price_since_entry_jpy"),
        tradePnlJpy = getBigDecimal("trade_pnl_jpy"),
        estimatedWinProbability = getNullableBigDecimal("estimated_win_probability"),
        setupTags = parseSetupTags(getString("setup_tags")),
        llmProvider = getNullableString("llm_provider"),
        accountEpochId = getNullableString("account_epoch_id")?.let(UUID::fromString),
        cohort = EvaluationCohort.valueOf(getString("cohort") ?: EvaluationCohort.LEGACY_PRE_WS.name),
        executionSemanticsVersion = getNullableString("execution_semantics_version"),
        attributionStatus = EvaluationAttributionStatus.valueOf(getString("attribution_status")),
        infrastructureGapIds = getArray("infrastructure_gap_ids")
            .array
            .let { values -> (values as Array<*>).filterIsInstance<String>().toSet() },
    )
}

private fun ResultSet.toLlmPhaseUsageFact(): LlmPhaseUsageFact {
    val payload = parsePayload(getString("payload"))
    val phase = payload?.get("phase")?.jsonPrimitive?.contentOrNull
    val details = payload?.get("details") as? JsonObject
    val usage = details.parseUsageOrStdout()

    return LlmPhaseUsageFact(
        decisionRunId = getNullableString("decision_run_id"),
        provider = getNullableString("llm_provider"),
        phase = phase,
        occurredAt = getInstant("ts"),
        usage = usage,
        populationStatus = EvaluationPopulationStatus.valueOf(getString("population_status")),
        representativeGapId = getNullableString("representative_gap_id"),
    )
}

private fun JsonObject?.parseUsageOrStdout(): LlmUsageDetails? {
    if (this == null) {
        return null
    }

    val structuredUsage = get("usage")?.let { element -> LlmUsageParser.parseUsageElement(element) }
    if (structuredUsage != null) {
        return structuredUsage
    }

    val stdout = get("stdout")?.jsonPrimitive?.contentOrNull ?: return null

    return LlmUsageParser.parseClaudeStdout(stdout)
}

private fun parsePayload(payload: String): JsonObject? {
    return runCatching { EvaluationPersistenceJson.parseToJsonElement(payload).jsonObject }.getOrNull()
}

private fun parseSetupTags(rawValue: String): List<String> {
    return runCatching {
        EvaluationPersistenceJson.parseToJsonElement(rawValue)
            .jsonArray
            .mapNotNull { element -> element.jsonPrimitive.contentOrNull }
    }.getOrDefault(emptyList())
}

private fun ResultSet.getUuid(columnName: String): UUID {
    return getObject(columnName, UUID::class.java)
}

private fun ResultSet.getInstant(columnName: String): Instant {
    return Instant.ofEpochMilli(getLong(columnName))
}

private fun ResultSet.getNullableString(columnName: String): String? {
    val value = getString(columnName)

    return if (wasNull()) null else value
}

/**
 * 評価 repository 用 JSON parser。
 */
private val EvaluationPersistenceJson = Json {
    ignoreUnknownKeys = true
}

/**
 * SQL で PF を割る時の scale。
 */
private const val EVALUATION_SQL_SCALE = 10
