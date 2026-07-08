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
import me.matsumo.fukurou.trading.evaluation.EvaluationLlmUsageQueryResult
import me.matsumo.fukurou.trading.evaluation.EvaluationPeriod
import me.matsumo.fukurou.trading.evaluation.EvaluationRepository
import me.matsumo.fukurou.trading.evaluation.EvaluationTradeQueryResult
import me.matsumo.fukurou.trading.evaluation.KillCriterionStats
import me.matsumo.fukurou.trading.evaluation.LlmPhaseUsageFact
import me.matsumo.fukurou.trading.evaluation.LlmUsageDetails
import me.matsumo.fukurou.trading.evaluation.LlmUsageParser
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * closed trade fact を取得する SQL。
 */
private const val SELECT_CLOSED_TRADE_FACTS_SQL = """
    WITH closed_positions AS (
        SELECT
            p.id,
            p.opened_at,
            p.closed_at,
            p.size_btc,
            p.average_entry_price_jpy,
            p.highest_price_since_entry_jpy,
            p.lowest_price_since_entry_jpy
        FROM positions p
        WHERE p.status = 'CLOSED'
            AND p.mode = (
                SELECT mode
                FROM paper_account
                WHERE id = ?
            )
            AND p.closed_at >= ?
            AND p.closed_at < ?
        ORDER BY p.closed_at ASC
        LIMIT ?
    ),
    entry_orders AS (
        SELECT DISTINCT ON (o.position_id)
            o.position_id,
            o.intent_id,
            o.protective_stop_price_jpy
        FROM orders o
        JOIN closed_positions p ON p.id = o.position_id
        WHERE o.side = 'BUY'
            AND o.intent_id IS NOT NULL
        ORDER BY o.position_id, o.created_at ASC
    ),
    position_pnl AS (
        SELECT
            e.position_id,
            COALESCE(SUM(CASE WHEN e.side = 'SELL' THEN e.realized_pnl_jpy ELSE 0 END), 0) AS sell_realized_pnl_jpy,
            COALESCE(SUM(CASE WHEN e.side = 'BUY' THEN e.fee_jpy ELSE 0 END), 0) AS entry_fee_jpy
        FROM executions e
        JOIN closed_positions p ON p.id = e.position_id
        GROUP BY e.position_id
    )
    SELECT
        p.id AS position_id,
        p.opened_at,
        p.closed_at,
        p.size_btc,
        p.average_entry_price_jpy,
        eo.protective_stop_price_jpy,
        p.highest_price_since_entry_jpy,
        p.lowest_price_since_entry_jpy,
        COALESCE(position_pnl.sell_realized_pnl_jpy, 0) - COALESCE(position_pnl.entry_fee_jpy, 0) AS trade_pnl_jpy,
        d.estimated_win_probability,
        COALESCE(d.setup_tags, tp.setup_tags, '[]') AS setup_tags,
        d.llm_provider
    FROM closed_positions p
    LEFT JOIN entry_orders eo ON eo.position_id = p.id
    LEFT JOIN position_pnl ON position_pnl.position_id = p.id
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
"""

/**
 * decision action 別件数を集計する SQL。
 */
private const val COUNT_DECISIONS_BY_ACTION_SQL = """
    SELECT action, COUNT(*)
    FROM decisions
    WHERE created_at >= ?
        AND created_at < ?
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
        AND payload::jsonb->>'phase' IN ('proposer', 'falsifier')
    ORDER BY ts ASC
    LIMIT ?
"""

/**
 * kill 基準用の closed trade 数と PnL を取得する SQL。
 */
private const val SELECT_KILL_CRITERION_STATS_SQL = """
    WITH closed_positions AS (
        SELECT id
        FROM positions
        WHERE status = 'CLOSED'
            AND mode = (
                SELECT mode
                FROM paper_account
                WHERE id = ?
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
    SELECT
        COUNT(*),
        COALESCE(SUM(CASE WHEN trade_pnl_jpy > 0 THEN trade_pnl_jpy ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN trade_pnl_jpy < 0 THEN trade_pnl_jpy ELSE 0 END), 0)
    FROM position_pnl
"""

/**
 * Exposed/JDBC で評価系読み取りを行う repository。
 *
 * @param database Exposed database
 */
class ExposedEvaluationRepository(
    private val database: ExposedDatabase,
) : EvaluationRepository {

    override suspend fun fetchClosedTrades(period: EvaluationPeriod, limit: Int): Result<EvaluationTradeQueryResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectClosedTrades(period, limit)
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
                    selectKillCriterionStats()
                }
            }
        }
    }
}

private fun JdbcTransaction.selectClosedTrades(period: EvaluationPeriod, limit: Int): EvaluationTradeQueryResult {
    val fetchLimit = limit + 1

    return jdbcConnection().prepareStatement(SELECT_CLOSED_TRADE_FACTS_SQL).use { statement ->
        statement.setInt(1, PAPER_ACCOUNT_SINGLE_ROW_ID)
        statement.setLong(2, period.from.toEpochMilli())
        statement.setLong(3, period.toExclusive.toEpochMilli())
        statement.setInt(4, fetchLimit)
        statement.executeQuery().use { resultSet ->
            val trades = buildList {
                while (resultSet.next()) {
                    add(resultSet.toClosedTradeFact())
                }
            }
            val truncated = trades.size > limit

            EvaluationTradeQueryResult(
                trades = trades.take(limit),
                truncated = truncated,
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

private fun ResultSet.toClosedTradeFact(): ClosedTradeFact {
    return ClosedTradeFact(
        positionId = getUuid("position_id"),
        openedAt = getInstant("opened_at"),
        closedAt = getInstant("closed_at"),
        sizeBtc = getBigDecimal("size_btc"),
        averageEntryPriceJpy = getBigDecimal("average_entry_price_jpy"),
        initialProtectiveStopPriceJpy = getNullableBigDecimal("protective_stop_price_jpy"),
        highestPriceSinceEntryJpy = getBigDecimal("highest_price_since_entry_jpy"),
        lowestPriceSinceEntryJpy = getNullableBigDecimal("lowest_price_since_entry_jpy"),
        tradePnlJpy = getBigDecimal("trade_pnl_jpy"),
        estimatedWinProbability = getNullableBigDecimal("estimated_win_probability"),
        setupTags = parseSetupTags(getString("setup_tags")),
        llmProvider = getNullableString("llm_provider"),
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
