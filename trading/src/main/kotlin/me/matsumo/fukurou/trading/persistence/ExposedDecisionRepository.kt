package me.matsumo.fukurou.trading.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.matsumo.fukurou.trading.audit.MAX_TERMINAL_TOOL_EVIDENCE_BUNDLE_BYTES
import me.matsumo.fukurou.trading.audit.MAX_TERMINAL_TOOL_EVIDENCE_COUNT
import me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundle
import me.matsumo.fukurou.trading.audit.TerminalToolEvidenceBundleStatus
import me.matsumo.fukurou.trading.audit.TrustedTerminalToolEvidenceBundle
import me.matsumo.fukurou.trading.audit.toTerminalEvidenceCanonicalString
import me.matsumo.fukurou.trading.decision.DecisionRecord
import me.matsumo.fukurou.trading.decision.DecisionSubmission
import me.matsumo.fukurou.trading.decision.DecisionSubmissionResult
import me.matsumo.fukurou.trading.decision.EntryIntentDraft
import me.matsumo.fukurou.trading.decision.EntryIntentSafetySnapshot
import me.matsumo.fukurou.trading.decision.FalsificationRecord
import me.matsumo.fukurou.trading.decision.FalsificationSubmission
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.MAX_TRADE_PLAN_REVISIONS
import me.matsumo.fukurou.trading.decision.TerminalEvidenceDecisionRepository
import me.matsumo.fukurou.trading.decision.TradeIntentConsumptionRecord
import me.matsumo.fukurou.trading.decision.TradeIntentRecord
import me.matsumo.fukurou.trading.decision.TradeIntentReviewSnapshot
import me.matsumo.fukurou.trading.decision.TradePlanDraft
import me.matsumo.fukurou.trading.decision.TradePlanInvalidationPredicate
import me.matsumo.fukurou.trading.decision.TradePlanRecord
import me.matsumo.fukurou.trading.decision.identity.DecisionIdentity
import me.matsumo.fukurou.trading.decision.identity.DecisionIdentityGenerator
import me.matsumo.fukurou.trading.decision.identity.canonicalProjection
import me.matsumo.fukurou.trading.decision.identity.classifyShadow
import me.matsumo.fukurou.trading.decision.isFreshApprovedAt
import me.matsumo.fukurou.trading.decision.validateDecisionSubmission
import me.matsumo.fukurou.trading.decision.validateTradePlanLineage
import me.matsumo.fukurou.trading.domain.OrderSide
import me.matsumo.fukurou.trading.domain.OrderType
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.feed.StableFeedCursor
import me.matsumo.fukurou.trading.knowledge.DecisionJournalRecord
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
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
        opportunity_episode_id,
        thesis_id,
        geometry_hash,
        material_state_hash,
        identity_schema_version,
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
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        invalidation_predicates,
        target_price_jpy,
        time_stop_at,
        setup_tags,
        created_at
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * trade_intents へ intent を append する SQL。
 */
private const val INSERT_TRADE_INTENT_SQL = """
    INSERT INTO trade_intents (
        id,
        opportunity_episode_id,
        thesis_id,
        geometry_hash,
        material_state_hash,
        identity_schema_version,
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
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        opportunity_episode_id,
        thesis_id,
        geometry_hash,
        material_state_hash,
        identity_schema_version,
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
        opportunity_episode_id,
        thesis_id,
        geometry_hash,
        material_state_hash,
        identity_schema_version,
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
        invalidation_predicates,
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
        invalidation_predicates,
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
        opportunity_episode_id,
        thesis_id,
        geometry_hash,
        material_state_hash,
        identity_schema_version,
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
        latest_decisions.opportunity_episode_id,
        latest_decisions.thesis_id,
        latest_decisions.geometry_hash,
        latest_decisions.material_state_hash,
        latest_decisions.identity_schema_version,
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
            opportunity_episode_id,
            thesis_id,
            geometry_hash,
            material_state_hash,
            identity_schema_version,
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
        opportunity_episode_id,
        thesis_id,
        geometry_hash,
        material_state_hash,
        identity_schema_version,
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
@Suppress("TooManyFunctions")
class ExposedDecisionRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val maxTradePlanRevisions: Int = MAX_TRADE_PLAN_REVISIONS,
) : TerminalEvidenceDecisionRepository {

    override suspend fun submitDecision(submission: DecisionSubmission): Result<DecisionSubmissionResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    submission.entryIntent?.let { intent ->
                        acquireOpportunityEpisodeGapPopulationToken(intent.symbol.apiSymbol)
                    }
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

    override suspend fun submitTerminalDecision(
        submission: DecisionSubmission,
        evidence: TrustedTerminalToolEvidenceBundle,
    ): Result<DecisionSubmissionResult> {
        if (!evidence.captureEnabled) return submitWithoutTerminalEvidence(evidence) { submitDecision(submission) }

        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    validateTrustedTerminalEvidence(evidence)
                    submission.entryIntent?.let { intent ->
                        acquireOpportunityEpisodeGapPopulationToken(intent.symbol.apiSymbol)
                    }
                    val now = clock.instant()
                    val result = insertDecisionSubmission(submission, now, maxTradePlanRevisions)
                    insertTerminalEvidence("DECISION", result.decision.decisionId, evidence, now)

                    result
                }
            }
        }
    }

    override suspend fun submitTerminalFalsification(
        submission: FalsificationSubmission,
        evidence: TrustedTerminalToolEvidenceBundle,
    ): Result<FalsificationRecord> {
        if (!evidence.captureEnabled) {
            return submitWithoutTerminalEvidence(evidence) { submitFalsification(submission) }
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                exposedTransaction(database) {
                    validateTrustedTerminalEvidence(evidence)
                    val now = clock.instant()
                    val result = insertFalsificationSubmission(submission, now)
                    insertTerminalEvidence("FALSIFICATION", result.falsificationId, evidence, now)

                    result
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

private suspend fun <T> submitWithoutTerminalEvidence(
    evidence: TrustedTerminalToolEvidenceBundle,
    submission: suspend () -> Result<T>,
): Result<T> = runCatching {
    require(evidence.bundle == TerminalToolEvidenceBundle.disabled()) {
        "Disabled terminal evidence must use the canonical disabled bundle."
    }

    submission().getOrThrow()
}

private fun JdbcTransaction.validateTrustedTerminalEvidence(evidence: TrustedTerminalToolEvidenceBundle) {
    require(evidence.captureEnabled) { "Terminal evidence capture must be enabled explicitly." }
    require(evidence.bundle.status == TerminalToolEvidenceBundleStatus.COMPLETE) {
        "Only complete terminal evidence bundles can be persisted by the Stage 1 foundation."
    }
    require(evidence.bundle.incompleteReason == null) { "Complete terminal evidence cannot have an incomplete reason." }
    require(evidence.bundle.entries.size <= MAX_TERMINAL_TOOL_EVIDENCE_COUNT) {
        "Terminal evidence count exceeds the canonical limit."
    }
    require(evidence.bundle.entries.map { entry -> entry.ordinal } == evidence.bundle.entries.indices.toList()) {
        "Terminal evidence ordinals must be contiguous."
    }
    val totalBytes = evidence.bundle.entries.sumOf { entry -> entry.responseJson.encodeToByteArray().size.toLong() }
    require(totalBytes <= MAX_TERMINAL_TOOL_EVIDENCE_BUNDLE_BYTES) {
        "Terminal evidence bytes exceed the canonical limit."
    }
    evidence.bundle.entries.forEach { entry ->
        require(entry.toolName !in TERMINAL_EVIDENCE_SUBMISSION_TOOLS) { "Terminal submission responses are not evidence." }
        val canonical = Json.parseToJsonElement(entry.responseJson).toTerminalEvidenceCanonicalString()
        require(canonical == entry.responseJson) { "Terminal evidence response is not canonical." }
        require(ManifestPersistencePolicy.sha256(canonical) == entry.responseHash) {
            "Terminal evidence response hash mismatch."
        }
        ManifestPersistencePolicy.validatePersistedStrings(entry.toolName, canonical)
    }
    jdbcConnection().prepareStatement(
        "SELECT invocation_id, phase FROM llm_phase_input_manifests WHERE phase_manifest_id = ?",
    ).use { statement ->
        statement.setString(1, evidence.phaseManifestId)
        statement.executeQuery().use { result ->
            require(result.next()) { "Terminal evidence phase manifest was not found." }
            require(result.getString(1) == evidence.invocationId && result.getString(2) == evidence.phase.name) {
                "Terminal evidence phase binding mismatch."
            }
        }
    }
}

private fun JdbcTransaction.insertTerminalEvidence(
    entityKind: String,
    entityId: UUID,
    evidence: TrustedTerminalToolEvidenceBundle,
    now: Instant,
) {
    evidence.bundle.entries.forEach { entry ->
        val evidenceId = UUID.randomUUID()
        jdbcConnection().prepareStatement(
            """INSERT INTO llm_tool_evidence
                (id, phase_manifest_id, ordinal, tool_name, source_timestamp, source_timestamp_status,
                 response_json, response_hash, is_error, captured_at, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TERMINAL_BUNDLE_CAPTURED')
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, evidenceId)
            statement.setString(2, evidence.phaseManifestId)
            statement.setInt(3, entry.ordinal)
            statement.setString(4, entry.toolName)
            statement.setObject(5, entry.sourceTimestamp?.toEpochMilli())
            statement.setString(6, entry.sourceTimestampStatus.name)
            statement.setString(7, entry.responseJson)
            statement.setString(8, entry.responseHash)
            statement.setBoolean(9, entry.isError)
            statement.setLong(10, now.toEpochMilli())
            statement.executeUpdate()
        }
        jdbcConnection().prepareStatement(
            "INSERT INTO llm_terminal_evidence_links (entity_kind, entity_id, evidence_id, ordinal) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, entityKind)
            statement.setObject(2, entityId)
            statement.setObject(3, evidenceId)
            statement.setInt(4, entry.ordinal)
            statement.executeUpdate()
        }
    }
    jdbcConnection().prepareStatement(
        """INSERT INTO llm_decision_phase_evidence_coverage
            (entity_kind, entity_id, phase_manifest_id, status, incomplete_reason, captured_at)
            VALUES (?, ?, ?, 'TERMINAL_BUNDLE_CAPTURED', NULL, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, entityKind)
        statement.setObject(2, entityId)
        statement.setString(3, evidence.phaseManifestId)
        statement.setLong(4, now.toEpochMilli())
        statement.executeUpdate()
    }
}

private val TERMINAL_EVIDENCE_SUBMISSION_TOOLS = setOf("submit_decision", "submit_falsification")

@Suppress("CyclomaticComplexMethod", "LongMethod")
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

    val materialLookup = selectMaterialManifest(submission.invocationId)
    submission.entryIntent?.let { intent -> lockOpportunityEpisodeSymbol(intent.symbol.apiSymbol) }
    val thesisId = submission.tradePlan?.let(DecisionIdentityGenerator::thesisId)
    val symbol = submission.entryIntent?.symbol?.apiSymbol
    val latestSameThesis = if (thesisId != null && symbol != null) selectOpenIdentity(thesisId, symbol) else null
    val episodeContext = latestSameThesis?.let { previous -> selectEpisodeContext(previous.opportunityEpisodeId) }
    val materialProjection = materialLookup.manifest?.let { manifest ->
        submission.tradePlan?.let { plan ->
            manifest.canonicalProjection(
                anchorPriceJpy = episodeContext?.anchorPriceJpy ?: submission.entryIntent?.priceJpy,
                thresholdRatio = episodeContext?.priceMoveThresholdRatio ?: manifest.priceMoveThresholdRatio,
                predicates = episodeContext?.predicates ?: plan.invalidationPredicates,
                observedAt = now,
            )
        }
    }
    val candidateIdentity = submission.entryIntent?.let { intent ->
        submission.tradePlan?.let { plan ->
            materialProjection?.let { canonical ->
                DecisionIdentityGenerator.generate(
                    episodeId = latestSameThesis?.opportunityEpisodeId ?: UUID.randomUUID(),
                    tradePlan = plan,
                    intent = intent,
                    materialProjection = canonical,
                )
            }
        }
    }
    val materialChangedAfterTtl = candidateIdentity != null && latestSameThesis != null &&
        candidateIdentity.materialStateHash != latestSameThesis.materialStateHash &&
        episodeHasTtlCancellation(latestSameThesis.opportunityEpisodeId)
    if (materialChangedAfterTtl) {
        closeOpportunityEpisode(
            episodeId = requireNotNull(latestSameThesis).opportunityEpisodeId,
            reason = "MATERIAL_STATE_CHANGED_AFTER_TTL",
            now = now,
        )
    }
    candidateIdentity?.let { candidate ->
        closeOtherOpenEpisodes(candidate.thesisId, requireNotNull(submission.entryIntent).symbol.apiSymbol, now)
    }
    val previousIdentity = latestSameThesis?.takeIf { previous ->
        !materialChangedAfterTtl && isOpportunityEpisodeOpen(previous.opportunityEpisodeId)
    }
    val identity = candidateIdentity?.let { candidate ->
        candidate.copy(
            opportunityEpisodeId = previousIdentity?.opportunityEpisodeId
                ?: if (materialChangedAfterTtl) UUID.randomUUID() else candidate.opportunityEpisodeId,
        )
    }
    val decision = DecisionRecord(
        decisionId = UUID.randomUUID(),
        submission = submission,
        createdAt = now,
        identity = identity,
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
            identity = identity,
        )
    }

    insertDecision(decision)
    if (submission.entryIntent != null && identity == null) {
        appendIdentityGenerationFailure(submission.invocationId, "DECISION", materialLookup.failureReason, now)
        appendIdentityGenerationFailure(submission.invocationId, "INTENT", materialLookup.failureReason, now)
    }
    if (identity != null && previousIdentity == null) {
        val threshold = episodeContext?.priceMoveThresholdRatio ?: materialLookup.manifest?.priceMoveThresholdRatio
        insertOpportunityEpisode(identity, submission, threshold, now)
    }
    tradePlan?.let { record -> insertTradePlan(record) }
    tradeIntent?.let { record -> insertTradeIntent(record) }
    identity?.let { current -> insertShadowObservation(decision.decisionId, previousIdentity, current, now) }

    return DecisionSubmissionResult(
        decision = decision,
        tradeIntent = tradeIntent,
        tradePlan = tradePlan,
    )
}

private fun JdbcTransaction.lockOpportunityEpisodeSymbol(symbol: String) {
    jdbcConnection().prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").use { statement ->
        statement.setString(1, symbol)
        statement.executeQuery().use { result -> check(result.next()) }
    }
}

private fun JdbcTransaction.isOpportunityEpisodeOpen(episodeId: UUID): Boolean {
    return jdbcConnection().prepareStatement(
        "SELECT closed_at IS NULL FROM opportunity_episodes WHERE id = ?",
    ).use { statement ->
        statement.setObject(1, episodeId)
        statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
    }
}

private fun JdbcTransaction.episodeHasTtlCancellation(episodeId: UUID): Boolean {
    val sql = """SELECT 1 FROM orders o JOIN trade_intents ti ON ti.id = o.intent_id
        WHERE ti.opportunity_episode_id = ? AND o.status = 'CANCELED'
        AND o.cancel_reason IN ('resting_entry_order_ttl_expired','legacy_ttl_sweep') LIMIT 1
    """.trimIndent()
    return jdbcConnection().prepareStatement(sql).use { statement ->
        statement.setObject(1, episodeId)
        statement.executeQuery().use { result -> result.next() }
    }
}

private fun JdbcTransaction.closeOtherOpenEpisodes(
    thesisId: String,
    symbol: String,
    now: Instant,
) {
    jdbcConnection().prepareStatement(
        """UPDATE opportunity_episodes SET closed_at = ?, close_reason = 'THESIS_CHANGED'
            WHERE symbol = ? AND thesis_id <> ? AND closed_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setString(2, symbol)
        statement.setString(3, thesisId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.closeOpportunityEpisode(
    episodeId: UUID,
    reason: String,
    now: Instant,
) {
    jdbcConnection().prepareStatement(
        "UPDATE opportunity_episodes SET closed_at = ?, close_reason = ? WHERE id = ? AND closed_at IS NULL",
    ).use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setString(2, reason)
        statement.setObject(3, episodeId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.selectOpenIdentity(thesisId: String, symbol: String): DecisionIdentity? {
    return jdbcConnection().prepareStatement(
        """SELECT d.opportunity_episode_id, d.thesis_id, d.geometry_hash, d.material_state_hash,
            d.identity_schema_version FROM decisions d JOIN opportunity_episodes e ON e.id=d.opportunity_episode_id
            WHERE d.thesis_id=? AND e.symbol=? AND e.closed_at IS NULL
            ORDER BY d.created_at DESC, d.id DESC LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, thesisId)
        statement.setString(2, symbol)
        statement.executeQuery().use { result -> if (result.next()) result.toDecisionIdentity() else null }
    }
}

private fun JdbcTransaction.selectEpisodeContext(episodeId: UUID): EpisodeIdentityContext? {
    return jdbcConnection().prepareStatement(
        """SELECT e.price_move_threshold_ratio,
            (SELECT ti.price_jpy FROM trade_intents ti JOIN decisions d ON d.id=ti.decision_id
             WHERE ti.opportunity_episode_id=e.id AND ti.price_jpy IS NOT NULL
             ORDER BY d.created_at, d.id LIMIT 1),
            (SELECT tp.invalidation_predicates FROM trade_intents ti JOIN trade_plans tp ON tp.id=ti.trade_plan_id
             JOIN decisions d ON d.id=ti.decision_id WHERE ti.opportunity_episode_id=e.id
             ORDER BY d.created_at, d.id LIMIT 1)
            FROM opportunity_episodes e WHERE e.id=?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, episodeId)
        statement.executeQuery().use { result ->
            if (!result.next()) return@use null
            EpisodeIdentityContext(
                priceMoveThresholdRatio = result.getBigDecimal(1),
                anchorPriceJpy = result.getBigDecimal(2),
                predicates = TradePlanInvalidationPredicateCodec.decode(result.getString(3)),
            )
        }
    }
}

private fun JdbcTransaction.insertOpportunityEpisode(
    identity: DecisionIdentity,
    submission: DecisionSubmission,
    priceMoveThresholdRatio: BigDecimal?,
    now: Instant,
) {
    jdbcConnection().prepareStatement(
        """INSERT INTO opportunity_episodes
            (id, symbol, thesis_id, price_move_threshold_ratio, opened_at) VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, identity.opportunityEpisodeId)
        statement.setString(2, requireNotNull(submission.entryIntent).symbol.apiSymbol)
        statement.setString(3, identity.thesisId)
        statement.setBigDecimal(4, requireNotNull(priceMoveThresholdRatio))
        statement.setLong(5, now.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertShadowObservation(
    decisionId: UUID,
    previous: DecisionIdentity?,
    current: DecisionIdentity,
    now: Instant,
) {
    val classification = classifyShadow(previous, current, previousEpisodeOpen = previous != null)
    jdbcConnection().prepareStatement(
        "INSERT INTO dedupe_shadow_observations " +
            "(id, observation_kind, decision_id, opportunity_episode_id, classification, data_quality, observed_at) " +
            "VALUES (?, 'FULL_PROPOSAL', ?, ?, ?, 'COMPLETE', ?)",
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, decisionId)
        statement.setObject(3, current.opportunityEpisodeId)
        statement.setString(4, classification.name)
        statement.setLong(5, now.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.selectMaterialManifest(invocationId: String?): MaterialManifestLookup {
    if (invocationId == null) return MaterialManifestLookup(null, "INVOCATION_ID_MISSING")
    return jdbcConnection().prepareStatement(
        "SELECT material_projection, manifest_json FROM decision_material_state_manifests WHERE invocation_id = ?",
    ).use { statement ->
        statement.setString(1, invocationId)
        statement.executeQuery().use { result ->
            if (!result.next()) return@use MaterialManifestLookup(null, "MANIFEST_MISSING")
            MaterialManifestLookup(result.getString(2).toMaterialManifest(), null)
        }
    }
}

private fun JdbcTransaction.appendIdentityGenerationFailure(
    invocationId: String?,
    entityKind: String,
    reason: String?,
    now: Instant,
) {
    jdbcConnection().prepareStatement(
        "INSERT INTO decision_identity_generation_failures " +
            "(id, invocation_id, entity_kind, reason, occurred_at) VALUES (?, ?, ?, ?, ?)",
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setNullableString(2, invocationId)
        statement.setString(3, entityKind)
        statement.setString(4, reason ?: "GENERATION_FAILED")
        statement.setLong(5, now.toEpochMilli())
        statement.executeUpdate()
    }
}

private data class MaterialManifestLookup(
    val manifest: me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest?,
    val failureReason: String?,
)

private data class EpisodeIdentityContext(
    val priceMoveThresholdRatio: BigDecimal,
    val anchorPriceJpy: BigDecimal?,
    val predicates: List<TradePlanInvalidationPredicate>,
)

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
        val identity = record.identity

        statement.setObject(1, record.decisionId)
        statement.setObject(2, identity?.opportunityEpisodeId)
        statement.setNullableString(3, identity?.thesisId)
        statement.setNullableString(4, identity?.geometryHash)
        statement.setNullableString(5, identity?.materialStateHash)
        statement.setObject(6, identity?.schemaVersion)
        statement.setNullableString(7, submission.invocationId)
        statement.setNullableString(8, submission.llmProvider)
        statement.setNullableString(9, submission.promptHash)
        statement.setNullableString(10, submission.systemPromptVersion)
        statement.setNullableString(11, submission.marketSnapshotId)
        statement.setString(12, submission.action.name)
        statement.setNullableBigDecimal(13, submission.closeRatio)
        statement.setString(14, submission.setupTags.toJsonText())
        statement.setBigDecimal(15, submission.estimatedWinProbability)
        statement.setNullableBigDecimal(16, submission.expectedRMultiple)
        statement.setNullableBigDecimal(17, submission.roundTripCostR)
        statement.setString(18, submission.toolEvidenceIds.toJsonText())
        statement.setString(19, submission.factCheckJson)
        statement.setString(20, submission.selfReviewJson)
        statement.setString(21, submission.reasonJa)
        statement.setString(22, submission.missingDataJa.toJsonText())
        statement.setString(23, submission.noTradeConditionsJa.toJsonText())
        statement.setLong(24, record.createdAt.toEpochMilli())
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
        statement.setString(8, TradePlanInvalidationPredicateCodec.toStorageText(draft.invalidationPredicates))
        statement.setNullableBigDecimal(9, draft.targetPriceJpy)
        statement.setNullableLong(10, draft.timeStopAt?.toEpochMilli())
        statement.setString(11, draft.setupTags.toJsonText())
        statement.setLong(12, record.createdAt.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.insertTradeIntent(record: TradeIntentRecord) {
    jdbcConnection().prepareStatement(INSERT_TRADE_INTENT_SQL).use { statement ->
        val draft = record.draft
        val identity = record.identity

        statement.setObject(1, record.intentId)
        statement.setObject(2, identity?.opportunityEpisodeId)
        statement.setNullableString(3, identity?.thesisId)
        statement.setNullableString(4, identity?.geometryHash)
        statement.setNullableString(5, identity?.materialStateHash)
        statement.setObject(6, identity?.schemaVersion)
        statement.setObject(7, record.decisionId)
        statement.setObject(8, record.tradePlanId)
        statement.setString(9, draft.symbol.apiSymbol)
        statement.setString(10, draft.side.name)
        statement.setString(11, draft.orderType.name)
        statement.setBigDecimal(12, draft.sizeBtc)
        statement.setNullableBigDecimal(13, draft.priceJpy)
        statement.setBigDecimal(14, draft.protectiveStopPriceJpy)
        statement.setNullableBigDecimal(15, draft.takeProfitPriceJpy)
        statement.setBigDecimal(16, record.estimatedWinProbability)
        statement.setLong(17, record.createdAt.toEpochMilli())
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
        identity = toDecisionIdentity(),
    )
}

private fun String.toInvalidationPredicates(): List<TradePlanInvalidationPredicate> {
    return TradePlanInvalidationPredicateCodec.decode(this)
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
            invalidationPredicates = getString("invalidation_predicates").toInvalidationPredicates(),
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
        identity = toDecisionIdentity(),
    )
}

private fun ResultSet.toDecisionIdentity(): DecisionIdentity? {
    val episodeId = getNullableUuid("opportunity_episode_id") ?: return null
    val thesisId = getString("thesis_id") ?: return null
    val geometryHash = getString("geometry_hash") ?: return null
    val materialStateHash = getString("material_state_hash") ?: return null
    val schemaVersion = getObject("identity_schema_version") as? Int ?: return null

    return DecisionIdentity(episodeId, thesisId, geometryHash, materialStateHash, schemaVersion)
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
