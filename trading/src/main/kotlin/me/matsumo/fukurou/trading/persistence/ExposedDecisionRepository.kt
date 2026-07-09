package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.decision.DecisionRecord
import me.matsumo.fukurou.trading.decision.DecisionRepository
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.EntryIntentSafetySnapshot
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.MAX_TRADE_PLAN_REVISIONS
import me.matsumo.fukurou.trading.decision.TradeIntentConsumptionRecord
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.decision.TradeIntentReviewSnapshot
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.TradePlanRecord
import me.matsumo.fukurou.trading.decision.isFreshApprovedAt
import me.matsumo.fukurou.trading.decision.validateDecisionSubmission
import me.matsumo.fukurou.trading.decision.validateTradePlanLineage
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.knowledge.DecisionJournalRecord
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction

/**
 * decisions へ decision を append する SQL。
 */
private const val INSERT_DECISION_SQL = """
    INSERT INTO decisions (
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
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * trade_plans へ TradePlan を append する SQL。
 */
private const val INSERT_TRADE_PLAN_SQL = """
    INSERT INTO trade_plans (
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
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * trade_intents へ intent を append する SQL。
 */
private const val INSERT_TRADE_INTENT_SQL = """
    INSERT INTO trade_intents (
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
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * falsifications へ verdict を append する SQL。
 */
private const val INSERT_FALSIFICATION_SQL = """
    INSERT INTO falsifications (
        id,
        intent_id,
        verdict,
        llm_provider,
        reason_ja,
        created_at
    )
    VALUES (?, ?, ?, ?, ?, ?)
"""

/**
 * trade_intent_consumptions へ消費記録を append する SQL。
 */
private const val INSERT_TRADE_INTENT_CONSUMPTION_SQL = """
    INSERT INTO trade_intent_consumptions (
        id,
        intent_id,
        order_id,
        consumed_at
    )
    VALUES (?, ?, ?, ?)
"""

/**
 * intent ID で trade_intents を読む SQL。
 */
private const val SELECT_TRADE_INTENT_BY_ID_SQL = """
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
    WHERE id = ?
"""

/**
 * decision ID で trade_intents を読む SQL。
 */
private const val SELECT_TRADE_INTENT_BY_DECISION_ID_SQL = """
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
    WHERE decision_id = ?
    ORDER BY created_at DESC
    LIMIT 1
"""

/**
 * TradePlan ID で trade_plans を読む SQL。
 */
private const val SELECT_TRADE_PLAN_BY_ID_SQL = """
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
    WHERE id = ?
"""

/**
 * decision ID で trade_plans を読む SQL。
 */
private const val SELECT_TRADE_PLAN_BY_DECISION_ID_SQL = """
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
    WHERE decision_id = ?
    ORDER BY created_at DESC
    LIMIT 1
"""

/**
 * invocation ID で latest decision を読む SQL。
 */
private const val SELECT_LATEST_DECISION_BY_INVOCATION_ID_SQL = """
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
    WHERE invocation_id = ?
    ORDER BY created_at DESC
    LIMIT 1
"""

/**
 * 作成時刻範囲で decisions を読む SQL。
 */
private const val SELECT_DECISIONS_CREATED_BETWEEN_SQL = """
    SELECT
        latest_decisions.id,
        latest_decisions.invocation_id,
        latest_decisions.llm_provider,
        latest_decisions.prompt_hash,
        latest_decisions.system_prompt_version,
        latest_decisions.market_snapshot_id,
        latest_decisions.action,
        latest_decisions.close_ratio,
        latest_decisions.setup_tags,
        latest_decisions.estimated_win_probability,
        latest_decisions.expected_r_multiple,
        latest_decisions.round_trip_cost_r,
        latest_decisions.tool_evidence_ids,
        latest_decisions.fact_check,
        latest_decisions.self_review,
        latest_decisions.reason_ja,
        latest_decisions.missing_data_ja,
        latest_decisions.no_trade_conditions_ja,
        latest_decisions.created_at
    FROM (
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
        WHERE created_at >= ?
            AND created_at < ?
        ORDER BY created_at DESC
        LIMIT ?
    ) latest_decisions
    ORDER BY latest_decisions.created_at ASC
"""

/**
 * Activity timeline 用 decision stable feed SELECT の列部分。
 */
private const val SELECT_DECISIONS_FOR_STABLE_FEED_SQL_PREFIX = """
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
    WHERE 
"""

/**
 * Activity timeline 用 decision stable feed SELECT の並び順と件数制限。
 */
private const val SELECT_DECISIONS_FOR_STABLE_FEED_SQL_SUFFIX = """
    ORDER BY created_at DESC, CAST(id AS TEXT) ASC
    LIMIT ?
"""

/**
 * intent ID で falsifications を読む SQL。
 */
private const val SELECT_FALSIFICATION_BY_INTENT_ID_SQL = """
    SELECT
        id,
        intent_id,
        verdict,
        llm_provider,
        reason_ja,
        created_at
    FROM falsifications
    WHERE intent_id = ?
    ORDER BY created_at DESC
    LIMIT 1
"""

/**
 * intent ID で trade_intent_consumptions を読む SQL。
 */
private const val SELECT_TRADE_INTENT_CONSUMPTION_BY_INTENT_ID_SQL = """
    SELECT
        id,
        intent_id,
        order_id,
        consumed_at
    FROM trade_intent_consumptions
    WHERE intent_id = ?
    ORDER BY consumed_at ASC
"""

/**
 * decision protocol 用 JSON codec。
 */
private val DecisionProtocolJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Exposed/JDBC で decision protocol を扱う repository。
 *
 * @param database Exposed database
 * @param clock 保存時刻に使う clock
 * @param maxTradePlanRevisions TradePlan 正式修正の上限
 */
class ExposedDecisionRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val maxTradePlanRevisions: Int = MAX_TRADE_PLAN_REVISIONS,
) : DecisionRepository {

    override suspend fun submitDecision(submission: DecisionSubmission): Result<DecisionSubmissionResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertDecisionSubmission(submission, clock.instant(), maxTradePlanRevisions)
                }
            }
        }
    }

    override suspend fun submitFalsification(submission: FalsificationSubmission): Result<FalsificationRecord> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    insertFalsificationSubmission(submission, clock.instant())
                }
            }
        }
    }

    override suspend fun latestDecisionByInvocationId(invocationId: String): Result<DecisionSubmissionResult?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val decision = selectLatestDecisionByInvocationId(invocationId) ?: return@exposedTransaction null
                    val tradeIntent = selectTradeIntentByDecisionId(decision.decisionId)
                    val tradePlan = selectTradePlanByDecisionId(decision.decisionId)

                    DecisionSubmissionResult(
                        decision = decision,
                        tradeIntent = tradeIntent,
                        tradePlan = tradePlan,
                    )
                }
            }
        }
    }

    override suspend fun findDecisionsCreatedBetween(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): Result<List<DecisionJournalRecord>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(limit > 0) {
                    "limit must be greater than 0."
                }

                exposedTransaction(database) {
                    selectDecisionsCreatedBetween(from, toExclusive, limit)
                        .map { decision -> toDecisionJournalRecord(decision) }
                }
            }
        }
    }

    override suspend fun findDecisionsForStableFeed(
        cursor: StableFeedCursor,
        limit: Int,
    ): Result<List<DecisionJournalRecord>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(limit > 0) {
                    "limit must be greater than 0."
                }

                exposedTransaction(database) {
                    selectDecisionsForStableFeed(cursor, limit)
                        .map { decision -> toDecisionJournalRecord(decision) }
                }
            }
        }
    }

    override suspend fun latestFalsification(intentId: UUID): Result<FalsificationRecord?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    selectFalsification(intentId)
                }
            }
        }
    }

    override suspend fun tradeIntentReviewSnapshot(intentId: UUID): Result<TradeIntentReviewSnapshot?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val intent = selectTradeIntent(intentId) ?: return@exposedTransaction null
                    val tradePlan = selectTradePlan(intent.tradePlanId)

                    TradeIntentReviewSnapshot(
                        tradeIntent = intent,
                        tradePlan = tradePlan,
                    )
                }
            }
        }
    }

    override suspend fun entryIntentSafetySnapshot(
        intentId: UUID,
        observedAt: Instant,
        freshnessWindow: Duration,
    ): Result<EntryIntentSafetySnapshot?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    val intent = selectTradeIntent(intentId) ?: return@exposedTransaction null
                    val falsification = selectFalsification(intentId)
                    val consumption = selectTradeIntentConsumption(intentId)

                    EntryIntentSafetySnapshot(
                        tradeIntent = intent,
                        falsification = falsification,
                        consumed = consumption != null,
                        freshApproved = falsification.isFreshApprovedAt(observedAt, freshnessWindow),
                    )
                }
            }
        }
    }

    override suspend fun appendIntentConsumption(
        intentId: UUID,
        orderId: UUID?,
        consumedAt: Instant,
    ): Result<TradeIntentConsumptionRecord> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    require(selectTradeIntent(intentId) != null) {
                        "trade intent was not found."
                    }
                    require(selectTradeIntentConsumption(intentId) == null) {
                        "trade intent was already consumed."
                    }

                    val record = TradeIntentConsumptionRecord(
                        consumptionId = UUID.randomUUID(),
                        intentId = intentId,
                        orderId = orderId,
                        consumedAt = consumedAt,
                    )

                    insertTradeIntentConsumption(record)

                    record
                }
            }
        }
    }
}

private fun JdbcTransaction.insertDecisionSubmission(
    submission: DecisionSubmission,
    now: Instant,
    maxTradePlanRevisions: Int,
): DecisionSubmissionResult {
    validateDecisionSubmission(submission, maxTradePlanRevisions)

    val parentTradePlan = submission.tradePlan
        ?.parentTradePlanId
        ?.let { parentTradePlanId -> selectTradePlan(parentTradePlanId) }

    validateTradePlanLineage(submission, parentTradePlan, maxTradePlanRevisions)

    val decision = DecisionRecord(
        decisionId = UUID.randomUUID(),
        submission = submission,
        createdAt = now,
    )
    val tradePlan = submission.tradePlan?.let { draft ->
        TradePlanRecord(
            tradePlanId = UUID.randomUUID(),
            decisionId = decision.decisionId,
            draft = draft,
            createdAt = now,
        )
    }
    val tradeIntent = submission.entryIntent?.let { draft ->
        TradeIntentRecord(
            intentId = UUID.randomUUID(),
            decisionId = decision.decisionId,
            tradePlanId = requireNotNull(tradePlan?.tradePlanId) {
                "${submission.action.name} decision requires trade_plan."
            },
            draft = draft,
            estimatedWinProbability = submission.estimatedWinProbability,
            createdAt = now,
        )
    }

    insertDecision(decision)
    tradePlan?.let { record -> insertTradePlan(record) }
    tradeIntent?.let { record -> insertTradeIntent(record) }

    return DecisionSubmissionResult(
        decision = decision,
        tradeIntent = tradeIntent,
        tradePlan = tradePlan,
    )
}

private fun JdbcTransaction.insertFalsificationSubmission(
    submission: FalsificationSubmission,
    now: Instant,
): FalsificationRecord {
    val intentId = requireNotNull(submission.intentId) {
        "intent_id is required."
    }

    require(submission.reasonJa.isNotBlank()) {
        "reason_ja is required."
    }
    require(selectTradeIntent(intentId) != null) {
        "trade intent was not found."
    }
    require(selectTradeIntentConsumption(intentId) == null) {
        "trade intent was already consumed."
    }
    require(selectFalsification(intentId) == null) {
        "falsification verdict already exists for intent."
    }

    val record = FalsificationRecord(
        falsificationId = UUID.randomUUID(),
        intentId = intentId,
        verdict = submission.verdict,
        llmProvider = submission.llmProvider,
        reasonJa = submission.reasonJa,
        createdAt = now,
    )

    insertFalsification(record)

    return record
}

private fun JdbcTransaction.insertDecision(record: DecisionRecord) {
    jdbcConnection().prepareStatement(INSERT_DECISION_SQL).use { statement ->
        val submission = record.submission

        statement.setObject(1, record.decisionId)
        statement.setNullableString(2, submission.invocationId)
        statement.setNullableString(3, submission.llmProvider)
        statement.setNullableString(4, submission.promptHash)
        statement.setNullableString(5, submission.systemPromptVersion)
        statement.setNullableString(6, submission.marketSnapshotId)
        statement.setString(7, submission.action.name)
        statement.setNullableBigDecimal(8, submission.closeRatio)
        statement.setString(9, submission.setupTags.toJsonText())
        statement.setBigDecimal(10, submission.estimatedWinProbability)
        statement.setNullableBigDecimal(11, submission.expectedRMultiple)
        statement.setNullableBigDecimal(12, submission.roundTripCostR)
        statement.setString(13, submission.toolEvidenceIds.toJsonText())
        statement.setString(14, submission.factCheckJson)
        statement.setString(15, submission.selfReviewJson)
        statement.setString(16, submission.reasonJa)
        statement.setString(17, submission.missingDataJa.toJsonText())
        statement.setString(18, submission.noTradeConditionsJa.toJsonText())
        statement.setLong(19, record.createdAt.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertTradePlan(record: TradePlanRecord) {
    jdbcConnection().prepareStatement(INSERT_TRADE_PLAN_SQL).use { statement ->
        val draft = record.draft

        statement.setObject(1, record.tradePlanId)
        statement.setObject(2, record.decisionId)
        statement.setObject(3, draft.parentTradePlanId)
        statement.setInt(4, draft.revisionCount)
        statement.setString(5, draft.symbol.apiSymbol)
        statement.setString(6, draft.thesisJa)
        statement.setString(7, draft.invalidationConditionsJa.toJsonText())
        statement.setNullableBigDecimal(8, draft.targetPriceJpy)
        statement.setNullableLong(9, draft.timeStopAt?.toEpochMilli())
        statement.setString(10, draft.setupTags.toJsonText())
        statement.setLong(11, record.createdAt.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertTradeIntent(record: TradeIntentRecord) {
    jdbcConnection().prepareStatement(INSERT_TRADE_INTENT_SQL).use { statement ->
        val draft = record.draft

        statement.setObject(1, record.intentId)
        statement.setObject(2, record.decisionId)
        statement.setObject(3, record.tradePlanId)
        statement.setString(4, draft.symbol.apiSymbol)
        statement.setString(5, draft.side.name)
        statement.setString(6, draft.orderType.name)
        statement.setBigDecimal(7, draft.sizeBtc)
        statement.setNullableBigDecimal(8, draft.priceJpy)
        statement.setBigDecimal(9, draft.protectiveStopPriceJpy)
        statement.setNullableBigDecimal(10, draft.takeProfitPriceJpy)
        statement.setBigDecimal(11, record.estimatedWinProbability)
        statement.setLong(12, record.createdAt.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertFalsification(record: FalsificationRecord) {
    jdbcConnection().prepareStatement(INSERT_FALSIFICATION_SQL).use { statement ->
        statement.setObject(1, record.falsificationId)
        statement.setObject(2, record.intentId)
        statement.setString(3, record.verdict.name)
        statement.setNullableString(4, record.llmProvider)
        statement.setString(5, record.reasonJa)
        statement.setLong(6, record.createdAt.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertTradeIntentConsumption(record: TradeIntentConsumptionRecord) {
    jdbcConnection().prepareStatement(INSERT_TRADE_INTENT_CONSUMPTION_SQL).use { statement ->
        statement.setObject(1, record.consumptionId)
        statement.setObject(2, record.intentId)
        statement.setObject(3, record.orderId)
        statement.setLong(4, record.consumedAt.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.selectTradeIntent(intentId: UUID): TradeIntentRecord? {
    return jdbcConnection().prepareStatement(SELECT_TRADE_INTENT_BY_ID_SQL).use { statement ->
        statement.setObject(1, intentId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toTradeIntentRecord() else null
        }
    }
}

private fun JdbcTransaction.selectTradeIntentByDecisionId(decisionId: UUID): TradeIntentRecord? {
    return jdbcConnection().prepareStatement(SELECT_TRADE_INTENT_BY_DECISION_ID_SQL).use { statement ->
        statement.setObject(1, decisionId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toTradeIntentRecord() else null
        }
    }
}

private fun JdbcTransaction.selectFalsification(intentId: UUID): FalsificationRecord? {
    return jdbcConnection().prepareStatement(SELECT_FALSIFICATION_BY_INTENT_ID_SQL).use { statement ->
        statement.setObject(1, intentId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toFalsificationRecord() else null
        }
    }
}

private fun JdbcTransaction.selectLatestDecisionByInvocationId(invocationId: String): DecisionRecord? {
    return jdbcConnection().prepareStatement(SELECT_LATEST_DECISION_BY_INVOCATION_ID_SQL).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toDecisionRecord() else null
        }
    }
}

private fun JdbcTransaction.selectDecisionsCreatedBetween(
    from: Instant,
    toExclusive: Instant,
    limit: Int,
): List<DecisionRecord> {
    return jdbcConnection().prepareStatement(SELECT_DECISIONS_CREATED_BETWEEN_SQL).use { statement ->
        statement.setLong(1, from.toEpochMilli())
        statement.setLong(2, toExclusive.toEpochMilli())
        statement.setInt(3, limit)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toDecisionRecord())
                }
            }
        }
    }
}

private fun JdbcTransaction.selectDecisionsForStableFeed(cursor: StableFeedCursor, limit: Int): List<DecisionRecord> {
    val sql = SELECT_DECISIONS_FOR_STABLE_FEED_SQL_PREFIX +
        stableFeedCursorCondition("created_at", "id", cursor) +
        SELECT_DECISIONS_FOR_STABLE_FEED_SQL_SUFFIX

    return jdbcConnection().prepareStatement(sql).use { statement ->
        val limitParameterIndex = statement.bindStableFeedCursor(
            startIndex = 1,
            cursor = cursor,
        )
        statement.setInt(limitParameterIndex, limit)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toDecisionRecord())
                }
            }
        }
    }
}

private fun JdbcTransaction.toDecisionJournalRecord(decision: DecisionRecord): DecisionJournalRecord {
    val tradeIntent = selectTradeIntentByDecisionId(decision.decisionId)
    val tradePlan = selectTradePlanByDecisionId(decision.decisionId)
    val falsification = tradeIntent?.let { intent -> selectFalsification(intent.intentId) }

    return DecisionJournalRecord(
        decision = decision,
        tradeIntent = tradeIntent,
        tradePlan = tradePlan,
        falsification = falsification,
    )
}

private fun JdbcTransaction.selectTradePlan(tradePlanId: UUID): TradePlanRecord? {
    return jdbcConnection().prepareStatement(SELECT_TRADE_PLAN_BY_ID_SQL).use { statement ->
        statement.setObject(1, tradePlanId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toTradePlanRecord() else null
        }
    }
}

private fun JdbcTransaction.selectTradePlanByDecisionId(decisionId: UUID): TradePlanRecord? {
    return jdbcConnection().prepareStatement(SELECT_TRADE_PLAN_BY_DECISION_ID_SQL).use { statement ->
        statement.setObject(1, decisionId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toTradePlanRecord() else null
        }
    }
}

private fun JdbcTransaction.selectTradeIntentConsumption(intentId: UUID): TradeIntentConsumptionRecord? {
    return jdbcConnection().prepareStatement(SELECT_TRADE_INTENT_CONSUMPTION_BY_INTENT_ID_SQL).use { statement ->
        statement.setObject(1, intentId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toTradeIntentConsumptionRecord() else null
        }
    }
}

private fun ResultSet.toDecisionRecord(): DecisionRecord {
    return DecisionRecord(
        decisionId = getObject("id", UUID::class.java),
        submission = DecisionSubmission(
            invocationId = getString("invocation_id"),
            llmProvider = getString("llm_provider"),
            promptHash = getString("prompt_hash"),
            systemPromptVersion = getString("system_prompt_version"),
            marketSnapshotId = getString("market_snapshot_id"),
            action = me.matsumo.fukurou.trading.decision.DecisionAction.valueOf(getString("action")),
            closeRatio = getNullableBigDecimal("close_ratio"),
            setupTags = getString("setup_tags").toStringList(),
            estimatedWinProbability = getBigDecimal("estimated_win_probability"),
            expectedRMultiple = getNullableBigDecimal("expected_r_multiple"),
            roundTripCostR = getNullableBigDecimal("round_trip_cost_r"),
            toolEvidenceIds = getString("tool_evidence_ids").toStringList(),
            factCheckJson = getString("fact_check"),
            selfReviewJson = getString("self_review"),
            reasonJa = getString("reason_ja"),
            missingDataJa = getString("missing_data_ja").toStringList(),
            noTradeConditionsJa = getString("no_trade_conditions_ja").toStringList(),
            entryIntent = null,
            tradePlan = null,
        ),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
    )
}

private fun ResultSet.toTradePlanRecord(): TradePlanRecord {
    return TradePlanRecord(
        tradePlanId = getObject("id", UUID::class.java),
        decisionId = getObject("decision_id", UUID::class.java),
        draft = TradePlanDraft(
            parentTradePlanId = getNullableUuid("parent_trade_plan_id"),
            revisionCount = getInt("revision_count"),
            symbol = TradingSymbol.entries.first { symbol -> symbol.apiSymbol == getString("symbol") },
            thesisJa = getString("thesis_ja"),
            invalidationConditionsJa = getString("invalidation_conditions_ja").toStringList(),
            targetPriceJpy = getNullableBigDecimal("target_price_jpy"),
            timeStopAt = getNullableLong("time_stop_at")?.let { millis -> Instant.ofEpochMilli(millis) },
            setupTags = getString("setup_tags").toStringList(),
        ),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
    )
}

private fun ResultSet.toTradeIntentRecord(): TradeIntentRecord {
    return TradeIntentRecord(
        intentId = getObject("id", UUID::class.java),
        decisionId = getObject("decision_id", UUID::class.java),
        tradePlanId = getObject("trade_plan_id", UUID::class.java),
        draft = EntryIntentDraft(
            symbol = TradingSymbol.entries.first { symbol -> symbol.apiSymbol == getString("symbol") },
            side = OrderSide.valueOf(getString("side")),
            orderType = OrderType.valueOf(getString("order_type")),
            sizeBtc = getBigDecimal("size_btc"),
            priceJpy = getNullableBigDecimal("price_jpy"),
            protectiveStopPriceJpy = getBigDecimal("protective_stop_price_jpy"),
            takeProfitPriceJpy = getNullableBigDecimal("take_profit_price_jpy"),
        ),
        estimatedWinProbability = getBigDecimal("estimated_win_probability"),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
    )
}

private fun ResultSet.toFalsificationRecord(): FalsificationRecord {
    return FalsificationRecord(
        falsificationId = getObject("id", UUID::class.java),
        intentId = getObject("intent_id", UUID::class.java),
        verdict = FalsificationVerdict.valueOf(getString("verdict")),
        llmProvider = getString("llm_provider"),
        reasonJa = getString("reason_ja"),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
    )
}

private fun ResultSet.toTradeIntentConsumptionRecord(): TradeIntentConsumptionRecord {
    return TradeIntentConsumptionRecord(
        consumptionId = getObject("id", UUID::class.java),
        intentId = getObject("intent_id", UUID::class.java),
        orderId = getNullableUuid("order_id"),
        consumedAt = Instant.ofEpochMilli(getLong("consumed_at")),
    )
}

private fun List<String>.toJsonText(): String {
    return DecisionProtocolJson.encodeToString(this)
}

private fun String.toStringList(): List<String> {
    return DecisionProtocolJson.decodeFromString(this)
}
