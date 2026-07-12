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
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
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
private const val SELECT_CLOSED_TRADE_FACTS_SQL = """
    WITH closed_positions AS MATERIALIZED (
        SELECT
            p.id,
            p.opened_at,
            p.closed_at,
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
        ORDER BY e.position_id, e.executed_at ASC, e.id ASC
    ),
    entry_orders AS (
        SELECT
            initial.position_id,
            o.intent_id,
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
        CASE WHEN d.id IS NULL THEN 'MISSING' ELSE 'ATTRIBUTED' END AS attribution_status
    FROM closed_positions p
    JOIN scoped_positions scoped ON scoped.id = p.id
    LEFT JOIN entry_orders eo ON eo.position_id = p.id
    LEFT JOIN entry_fills ef ON ef.position_id = p.id
    LEFT JOIN position_pnl ON position_pnl.position_id = p.id
    LEFT JOIN execution_lineage el ON el.position_id = p.id
    LEFT JOIN trade_intents ti ON ti.id = eo.intent_id
    LEFT JOIN decisions d ON d.id = ti.decision_id
    LEFT JOIN trade_plans tp ON tp.id = ti.trade_plan_id
    ORDER BY p.closed_at ASC
"""

/**
 * decision run 数を集計する SQL。
 */
private const val COUNT_EVALUATION_DECISION_RUNS_SQL = """
    SELECT COUNT(DISTINCT decision_run_id)
    FROM command_event_log
    WHERE decision_run_id IS NOT NULL
        AND event_type IN ('RUNNER_PHASE_COMPLETED', 'NO_TRADE_EXIT')
        AND ts >= ?
        AND ts < ?
        AND NOT EXISTS (
            SELECT 1 FROM evaluation_exclusions x
            WHERE x.entity_type = 'DECISION_RUN' AND x.entity_id = command_event_log.decision_run_id
        )
"""

/**
 * decision action 別件数を集計する SQL。
 */
private const val COUNT_DECISIONS_BY_ACTION_SQL = """
    SELECT action, COUNT(*)
    FROM decisions
    WHERE created_at >= ?
        AND created_at < ?
        AND NOT EXISTS (
            SELECT 1 FROM evaluation_exclusions x
            WHERE x.entity_type = 'DECISION_RUN' AND x.entity_id = decisions.invocation_id
        )
    GROUP BY action
    ORDER BY action ASC
"""

/**
 * benchmark 用の trade PnL を取得する SQL。
 */
private const val SELECT_DAILY_TRADE_PNL_SQL = """
    WITH closed_positions AS (
        SELECT id, closed_at
        FROM positions
        WHERE status = 'CLOSED'
            AND mode = (
                SELECT mode
                FROM paper_account
                WHERE id = ?
            )
            AND closed_at >= ?
            AND closed_at < ?
            AND NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = positions.id::text
            )
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
private const val SUM_TRADE_PNL_BEFORE_SQL = """
    WITH closed_positions AS (
        SELECT id
        FROM positions
        WHERE status = 'CLOSED'
            AND mode = (
                SELECT mode
                FROM paper_account
                WHERE id = ?
            )
            AND closed_at < ?
            AND NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = positions.id::text
            )
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
private const val SELECT_LLM_PHASE_USAGE_SQL = """
    SELECT
        decision_run_id,
        llm_provider,
        payload,
        ts
    FROM command_event_log
    WHERE event_type = 'RUNNER_PHASE_COMPLETED'
        AND ts >= ?
        AND ts < ?
        AND payload::jsonb->>'phase' IN ('pre_filter', 'proposer', 'falsifier', 'reflection')
    ORDER BY ts ASC
    LIMIT ?
"""

/** active epoch + CURRENT の kill criterion を DB 内で完全集計する SQL。 */
private const val SELECT_KILL_CRITERION_STATS_SQL = """
    WITH current_scope AS (
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
        JOIN executions e ON e.position_id = p.id
        LEFT JOIN orders o ON o.id = e.order_id
        WHERE p.status = 'CLOSED'
            AND p.mode = (SELECT mode FROM paper_account WHERE id = ?)
            AND NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = p.id::text
            )
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
private const val SELECT_SCOPED_PNL_BEFORE_SQL = """
    WITH trade_stats AS (
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
        JOIN executions e ON e.position_id = p.id
        LEFT JOIN orders o ON o.id = e.order_id
        WHERE p.status = 'CLOSED'
            AND p.mode = (SELECT mode FROM paper_account WHERE id = ?)
            AND p.closed_at < ?
            AND NOT EXISTS (
                SELECT 1 FROM evaluation_exclusions x
                WHERE x.entity_type = 'POSITION' AND x.entity_id = p.id::text
            )
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
                        dailyPnl = trades.trades.map { trade ->
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

    override suspend fun fetchKillCriterionStats(): Result<KillCriterionStats> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    requireCurrentAccountBaselineMatchesRuntimeConfig()
                    selectKillCriterionStats()
                }
            }
        }
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
                        trade.attributionStatus == EvaluationAttributionStatus.ATTRIBUTED
                    },
                    missing = page.count { trade ->
                        trade.attributionStatus == EvaluationAttributionStatus.MISSING
                    },
                    total = page.size,
                ),
            )
        }
    }
}

private fun JdbcTransaction.countDecisionRunsForPeriod(period: EvaluationPeriod): Int {
    return jdbcConnection().prepareStatement(COUNT_EVALUATION_DECISION_RUNS_SQL).use { statement ->
        statement.setLong(1, period.from.toEpochMilli())
        statement.setLong(2, period.toExclusive.toEpochMilli())
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getInt(1) else 0
        }
    }
}

private fun JdbcTransaction.countDecisionsByActionForPeriod(period: EvaluationPeriod): List<DecisionActionCount> {
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

private fun JdbcTransaction.selectKillCriterionStats(): KillCriterionStats {
    return jdbcConnection().prepareStatement(SELECT_KILL_CRITERION_STATS_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.executeQuery().use { resultSet ->
            require(resultSet.next()) { "kill criterion stats aggregate did not return a row." }

            val closedTrades = resultSet.getInt(1)
            val winningPnl = resultSet.getBigDecimal(2) ?: BigDecimal.ZERO
            val losingPnl = resultSet.getBigDecimal(3) ?: BigDecimal.ZERO
            val profitFactor = if (losingPnl < BigDecimal.ZERO) {
                winningPnl.divide(losingPnl.abs(), EVALUATION_SQL_SCALE, java.math.RoundingMode.HALF_UP)
            } else {
                null
            }

            KillCriterionStats(
                closedTrades = closedTrades,
                profitFactor = profitFactor,
            )
        }
    }
}

private fun JdbcTransaction.selectDeduplicationMetrics(period: EvaluationPeriod): DeduplicationMetrics {
    val sql = """SELECT
        (SELECT COUNT(*) FROM decisions WHERE action IN ('ENTER','ADD_LONG') AND created_at>=? AND created_at<?),
        (SELECT COUNT(*) FROM decisions WHERE action IN ('ENTER','ADD_LONG') AND created_at>=? AND created_at<? AND opportunity_episode_id IS NOT NULL AND thesis_id IS NOT NULL AND geometry_hash IS NOT NULL AND material_state_hash IS NOT NULL AND identity_schema_version IS NOT NULL),
        (SELECT COUNT(*) FROM trade_intents WHERE created_at>=? AND created_at<?),
        (SELECT COUNT(*) FROM trade_intents WHERE created_at>=? AND created_at<? AND opportunity_episode_id IS NOT NULL AND thesis_id IS NOT NULL AND geometry_hash IS NOT NULL AND material_state_hash IS NOT NULL AND identity_schema_version IS NOT NULL),
        (SELECT COUNT(*) FROM dedupe_shadow_observations WHERE observed_at>=? AND observed_at<?),
        (SELECT COUNT(*) FROM dedupe_shadow_observations WHERE observed_at>=? AND observed_at<? AND classification IS NOT NULL),
        (SELECT COUNT(DISTINCT opportunity_episode_id) FROM dedupe_shadow_observations WHERE observed_at>=? AND observed_at<?),
        (SELECT COUNT(*) FROM dedupe_shadow_observations WHERE observation_kind='RESTING_MAINTENANCE' AND observed_at>=? AND observed_at<?)
    """.trimIndent()
    return jdbcConnection().prepareStatement(sql).use { statement ->
        var index = 1
        repeat(8) {
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
          MAX(e.closed_at) AS closed_at
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
        statement.setLong(1, period.from.toEpochMilli())
        statement.setLong(2, period.toExclusive.toEpochMilli())
        statement.executeQuery().use { result ->
            buildMap { while (result.next()) put(result.getString(1), result.getInt(2)) }
        }
    }
}

private data class DedupeLaunchCounts(val restingOnlyDaemon: Int, val manual: Int)

private fun JdbcTransaction.selectDedupeLaunchCounts(period: EvaluationPeriod): DedupeLaunchCounts {
    val sql = """SELECT
      COUNT(*) FILTER (WHERE payload LIKE '%\"triggerKind\":\"MANUAL\"%'),
      COUNT(*) FILTER (
        WHERE payload NOT LIKE '%\"triggerKind\":\"MANUAL\"%'
        AND EXISTS (
          SELECT 1 FROM dedupe_shadow_observations o
          WHERE o.observation_kind = 'RESTING_MAINTENANCE'
          AND ABS(o.observed_at - command_event_log.occurred_at) <= 1000
        )
      ) FROM command_event_log
      WHERE event_type = 'DAEMON_TRIGGER_LAUNCHED' AND occurred_at >= ? AND occurred_at < ?
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
