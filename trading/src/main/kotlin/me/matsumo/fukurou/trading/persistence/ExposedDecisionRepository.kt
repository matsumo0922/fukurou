package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import me.matsumo.fukurou.trading.decision.TradePlanRecord
import me.matsumo.fukurou.trading.decision.validateDecisionSubmission
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
import java.sql.PreparedStatement
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
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
    ORDER BY created_at ASC
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
                "ENTER decision requires trade_plan."
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
        statement.setString(8, submission.setupTags.toJsonText())
        statement.setBigDecimal(9, submission.estimatedWinProbability)
        statement.setNullableBigDecimal(10, submission.expectedRMultiple)
        statement.setNullableBigDecimal(11, submission.roundTripCostR)
        statement.setString(12, submission.toolEvidenceIds.toJsonText())
        statement.setString(13, submission.factCheckJson)
        statement.setString(14, submission.selfReviewJson)
        statement.setString(15, submission.reasonJa)
        statement.setString(16, submission.missingDataJa.toJsonText())
        statement.setString(17, submission.noTradeConditionsJa.toJsonText())
        statement.setLong(18, record.createdAt.toEpochMilli())
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

private fun JdbcTransaction.selectFalsification(intentId: UUID): FalsificationRecord? {
    return jdbcConnection().prepareStatement(SELECT_FALSIFICATION_BY_INTENT_ID_SQL).use { statement ->
        statement.setObject(1, intentId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toFalsificationRecord() else null
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

private fun FalsificationRecord?.isFreshApprovedAt(observedAt: Instant, freshnessWindow: Duration): Boolean {
    if (this == null) {
        return false
    }
    if (verdict != FalsificationVerdict.APPROVED) {
        return false
    }

    return !createdAt.plus(freshnessWindow).isBefore(observedAt)
}

private fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) {
        setString(index, null)
        return
    }

    setString(index, value)
}

private fun PreparedStatement.setNullableBigDecimal(index: Int, value: BigDecimal?) {
    if (value == null) {
        setObject(index, null)
        return
    }

    setBigDecimal(index, value)
}

private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) {
        setObject(index, null)
        return
    }

    setLong(index, value)
}

private fun ResultSet.getNullableBigDecimal(columnName: String): BigDecimal? {
    val value = getBigDecimal(columnName)

    return if (wasNull()) null else value
}

private fun ResultSet.getNullableUuid(columnName: String): UUID? {
    val value = getObject(columnName, UUID::class.java)

    return if (wasNull()) null else value
}
