@file:Suppress("LongMethod", "TooManyFunctions")

package me.matsumo.fukurou.trading.persistence

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** gap population の作成・終端 writer が transaction 入口で取得する capability。 */
sealed interface GapPopulationGenerationToken {
    /** control row の単調増加 version。 */
    val version: Long
}

private data class PersistedGapPopulationGenerationToken(
    override val version: Long,
) : GapPopulationGenerationToken

/** C0 の gap work source を一意に識別する canonical key。 */
data class GapSourceWorkIdentity(
    val provider: String,
    val symbol: String,
    val channel: String,
    val sessionId: UUID,
    val sourceKind: String,
    val sourceEpisode: String,
)

/** bounded recovery の終了状態。 */
data class GapPopulationRecoverySummary(
    val applied: Int,
    val unknown: Int,
    val remaining: Int,
)

internal const val GAP_POPULATION_PAGE_SIZE = 100
internal const val GAP_POPULATION_PASS_SIZE = 1_000
internal const val GAP_POPULATION_PENDING_WORK_LIMIT = 1_000
internal const val GAP_POPULATION_EVIDENCE_LIMIT = 32
internal const val GAP_POPULATION_MEMBER_LIMIT = 100_000
internal const val GAP_POPULATION_JOURNAL_LIMIT = 100_000
internal const val GAP_POPULATION_JOURNAL_BYTES_LIMIT = 256L * 1024L * 1024L

private val GAP_EXCLUSION_ENTITY_TYPES = setOf(
    "ORDER",
    "POSITION",
    "DECISION_RUN",
    "LLM_RUN",
    "LLM_RESERVATION",
    "OPPORTUNITY_EPISODE",
    "EVALUATION_REPORT_JOB",
)

private val GAP_POPULATION_REQUIRED_RELATIONS = setOf(
    "market_data_sessions",
    "market_data_gaps",
    "evaluation_exclusions",
    "orders",
    "positions",
    "llm_runs",
    "llm_launch_reservations",
    "opportunity_episodes",
)

/**
 * control row を最初に lock し、現在 transaction だけで有効な token を発行する。
 *
 * trigger は lock を取得せず、この token と txid/version の一致だけを検証する。
 */
fun JdbcTransaction.acquireGapPopulationGenerationToken(): GapPopulationGenerationToken {
    prepare("SELECT acquire_gap_population_generation_token()").use { statement ->
        statement.executeQuery().use { rows -> require(rows.next()) }
    }
    val configured = prepare("SELECT current_setting('fukurou.gap_population_token', true)").use { statement ->
        statement.executeQuery().use { rows ->
            require(rows.next())
            rows.getString(1)
        }
    }
    val version = configured.substringAfter(':').toLong()

    return PersistedGapPopulationGenerationToken(version)
}

/** transaction entry token を持つことを Kotlin 側でも明示する。 */
fun JdbcTransaction.requireGapPopulationGenerationToken(token: GapPopulationGenerationToken) {
    val current = prepare("SELECT current_setting('fukurou.gap_population_token', true)").use { statement ->
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
    require(current == "${currentTransactionId()}:${token.version}") {
        "gap population generation token is missing or stale."
    }
}

private fun JdbcTransaction.currentTransactionId(): Long {
    return prepare("SELECT txid_current()").use { statement ->
        statement.executeQuery().use { rows ->
            require(rows.next())
            rows.getLong(1)
        }
    }
}

/** C0 の additive schema、private evidence、enforcement trigger を作成する。 */
internal fun JdbcTransaction.ensureGapPopulationLifecycleSchema() {
    GAP_POPULATION_SCHEMA_SQL.forEach(::executeUpdate)
}

/** evaluation report の route-local schema 作成後に同じ lifecycle fence を接続する。 */
fun JdbcTransaction.ensureEvaluationReportGapPopulationLifecycleSchema(): Boolean {
    val lifecycleSchemaAvailable = GAP_POPULATION_REQUIRED_RELATIONS.all(::relationExistsForGapPopulation)
    if (!lifecycleSchemaAvailable) return false

    val tokenFunctionExists = prepare(
        "SELECT to_regprocedure('acquire_gap_population_generation_token()') IS NOT NULL",
    ).use { statement -> statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) } }
    if (!tokenFunctionExists) ensureGapPopulationLifecycleSchema()

    val evaluationTriggerExists = prepare(
        "SELECT 1 FROM pg_trigger WHERE NOT tgisinternal AND tgname='evaluation_report_jobs_gap_population_create'",
    ).use { statement -> statement.executeQuery().use { rows -> rows.next() } }
    if (!evaluationTriggerExists) EVALUATION_REPORT_GAP_POPULATION_SCHEMA_SQL.forEach(::executeUpdate)

    return true
}

/** C0 schema と enforcement trigger の存在を検証する。 */
internal fun JdbcTransaction.verifyGapPopulationLifecycleSchema() {
    val missing = prepare(
        """
        SELECT expected.name
        FROM (VALUES
            ('gap_population_control'),
            ('market_data_gap_work'),
            ('market_data_gap_work_evidence'),
            ('market_data_gap_population_generations'),
            ('market_data_gap_population_members'),
            ('market_data_gap_terminal_journal'),
            ('market_data_gap_recovery_progress')
        ) expected(name)
        WHERE to_regclass(expected.name) IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }
    check(missing.isEmpty()) { "gap population lifecycle schema is incomplete: ${missing.joinToString()}" }
    val missingBirthColumns = prepare(
        """
        SELECT expected.table_name
        FROM (VALUES ('orders'),('positions'),('llm_runs'),('llm_launch_reservations'),('opportunity_episodes'))
            expected(table_name)
        WHERE NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema='public' AND table_name=expected.table_name AND column_name='birth_sequence'
        )
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }
    check(missingBirthColumns.isEmpty()) {
        "gap population birth sequence columns are incomplete: ${missingBirthColumns.joinToString()}"
    }
    val triggerCount = prepare(
        """
        SELECT COUNT(*) FROM pg_trigger
        WHERE NOT tgisinternal AND tgname LIKE '%_gap_population_%'
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }
    check(triggerCount >= 10) { "gap population lifecycle triggers were not initialized." }
    val acquireFunctionExists = prepare("SELECT to_regprocedure('acquire_gap_population_generation_token()') IS NOT NULL").use {
            statement ->
        statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
    }
    check(acquireFunctionExists) { "gap population token function was not initialized." }
}

/** canonical source を同じ work ID へ coalesce し、active generation または FIFO queue へ載せる。 */
internal fun JdbcTransaction.enqueueGapPopulationWork(
    identity: GapSourceWorkIdentity,
    gapId: UUID,
    reason: String,
    detail: String?,
    detectedAt: Instant,
): UUID {
    acquireGapPopulationGenerationToken()

    selectExactWork(identity)?.let { workId ->
        appendGapWorkEvidence(workId, reason, detail, detectedAt)
        return workId
    }

    val pendingCount = countPendingGapWork(identity)
    if (pendingCount >= GAP_POPULATION_PENDING_WORK_LIMIT) {
        return insertOverflowWork(identity, gapId, detectedAt, pendingCount)
    }

    val active = hasActiveGapGeneration(identity)
    val workId = UUID.randomUUID()
    val upper = currentBirthSequenceUpper()
    val journalLower = currentJournalSequenceUpper()
    val populationAsOf = currentDatabaseTimeMillis()
    prepare(
        """
        INSERT INTO market_data_gap_work (
            id, gap_id, provider, symbol, channel, session_id, source_kind, source_episode,
            identity_hash, state, population_as_of, birth_sequence_upper, journal_sequence_lower,
            created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, workId)
        statement.setObject(2, gapId)
        statement.setString(3, identity.provider)
        statement.setString(4, identity.symbol)
        statement.setString(5, identity.channel)
        statement.setObject(6, identity.sessionId)
        statement.setString(7, identity.sourceKind)
        statement.setString(8, identity.sourceEpisode)
        statement.setString(9, identity.canonicalHash())
        statement.setString(10, if (active) "QUEUED" else "CAPTURING")
        statement.setLong(11, populationAsOf)
        statement.setLong(12, upper)
        statement.setLong(13, journalLower)
        statement.setLong(14, populationAsOf)
        statement.setLong(15, populationAsOf)
        statement.executeUpdate()
    }
    prepare(
        """
        INSERT INTO market_data_gap_population_generations
            (id, work_id, state, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, workId)
        statement.setString(3, if (active) "QUEUED" else "CAPTURING")
        statement.setLong(4, populationAsOf)
        statement.setLong(5, populationAsOf)
        statement.executeUpdate()
    }
    insertRecoveryProgress(
        workId = workId,
        phase = if (active) "QUEUED" else "CAPTURING",
        updatedAt = populationAsOf,
    )
    appendGapWorkEvidence(workId, reason, detail, detectedAt)

    return workId
}

/** active/queued work を page/pass 境界で前進させる。 */
internal fun JdbcTransaction.recoverGapPopulationPass(now: Instant): GapPopulationRecoverySummary {
    acquireGapPopulationGenerationToken()
    activateNextQueuedWork(now)

    val activeWorkIds = selectActiveWorkIds()
    var applied = 0
    var unknown = 0
    activeWorkIds.forEach { workId ->
        when (advanceGapWork(workId, now)) {
            "APPLIED" -> applied += 1
            "UNKNOWN" -> unknown += 1
        }
    }

    return GapPopulationRecoverySummary(
        applied = applied,
        unknown = unknown,
        remaining = countRecoverableGapWork(),
    )
}

private fun JdbcTransaction.advanceGapWork(workId: UUID, now: Instant): String {
    val work = selectWorkForUpdate(workId) ?: return "APPLIED"
    return when (work.state) {
        "CAPTURING" -> captureWorkPage(work, now)
        "SEALED", "APPLYING" -> applyWorkPage(work, now)
        else -> work.state
    }
}

private fun JdbcTransaction.captureWorkPage(work: GapWorkRow, now: Instant): String {
    val inserted = captureCurrentPopulation(work) + captureJournalPopulation(work)
    val memberCount = countWorkMembers(work.id)
    updateRecoveryProgress(work.id, "CAPTURING", memberCount, null, now)
    if (hasPopulationHashMismatch(work.id)) {
        markWorkUnknown(work.id, "UNKNOWN_DATA_CONFLICT", now)
        return "UNKNOWN"
    }
    if (memberCount > GAP_POPULATION_MEMBER_LIMIT || journalCapacityExceeded()) {
        markWorkUnknown(work.id, "UNKNOWN_OVERFLOW", now)
        return "UNKNOWN"
    }
    if (inserted >= GAP_POPULATION_PASS_SIZE) return "CAPTURING"

    prepare(
        "UPDATE market_data_gap_work SET state='SEALED', journal_sequence_upper=?, updated_at=? WHERE id=?",
    ).use { statement ->
        statement.setLong(1, currentJournalSequenceUpper())
        statement.setLong(2, now.toEpochMilli())
        statement.setObject(3, work.id)
        statement.executeUpdate()
    }
    updateGenerationState(work.id, "SEALED", now)
    updateRecoveryProgress(work.id, "SEALED", memberCount, null, now)
    return applyWorkPage(work.copy(state = "SEALED"), now)
}

private fun JdbcTransaction.applyWorkPage(work: GapWorkRow, now: Instant): String {
    prepare("UPDATE market_data_gap_work SET state='APPLYING', updated_at=? WHERE id=?").use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setObject(2, work.id)
        statement.executeUpdate()
    }
    updateGenerationState(work.id, "APPLYING", now)

    val members = selectUnappliedMembers(work.id)
    members.forEach { member -> applyMemberImpact(work, member, now) }
    updateRecoveryProgress(
        workId = work.id,
        phase = "APPLYING",
        processedCount = countAppliedMembers(work.id),
        lastEntityId = members.lastOrNull()?.entityId,
        now = now,
    )
    if (members.size >= GAP_POPULATION_PASS_SIZE) return "APPLYING"

    prepare(
        "UPDATE market_data_gap_work SET state='APPLIED', updated_at=?, completed_at=? WHERE id=?",
    ).use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setLong(2, now.toEpochMilli())
        statement.setObject(3, work.id)
        statement.executeUpdate()
    }
    updateGenerationState(work.id, "APPLIED", now)
    updateRecoveryProgress(work.id, "APPLIED", countWorkMembers(work.id), null, now)
    prepare("UPDATE market_data_gaps SET impact_applied_at=COALESCE(impact_applied_at, ?) WHERE id=?").use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setObject(2, work.gapId)
        statement.executeUpdate()
    }
    activateNextQueuedWork(now)
    return "APPLIED"
}

private fun JdbcTransaction.applyMemberImpact(
    work: GapWorkRow,
    member: GapMemberRow,
    now: Instant,
) {
    if (member.entityType in GAP_EXCLUSION_ENTITY_TYPES) {
        insertEvaluationExclusion(work.gapId, member, work.reason, now)
    }
    if (member.entityType == "ORDER") cancelGapOrder(member.entityId, work.reason, now)
    if (member.entityType == "EVALUATION_REPORT_JOB") failGapEvaluationReport(member.entityId, now)

    prepare(
        "UPDATE market_data_gap_population_members SET applied_at=? WHERE work_id=? AND entity_type=? AND entity_id=?",
    ).use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setObject(2, work.id)
        statement.setString(3, member.entityType)
        statement.setString(4, member.entityId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.captureCurrentPopulation(work: GapWorkRow): Int {
    var total = 0
    do {
        var roundInserted = 0
        CURRENT_POPULATION_QUERIES.forEach { population ->
            if (total >= GAP_POPULATION_PASS_SIZE) return@forEach
            if (!relationExistsForGapPopulation(population.table)) return@forEach
            val limit = minOf(GAP_POPULATION_PAGE_SIZE, GAP_POPULATION_PASS_SIZE - total)
            val inserted = prepare(
                """
                INSERT INTO market_data_gap_population_members
                    (work_id, entity_type, entity_id, birth_sequence, projection_hash, source, captured_at)
                SELECT ?, ?, entity_id, birth_sequence,
                    encode(digest(? || ':' || entity_id || ':' || COALESCE(birth_sequence::text, 'PRE_C'), 'sha256'), 'hex'),
                    'CURRENT', ?
                FROM (${population.query}) population
                WHERE (birth_sequence IS NULL OR birth_sequence <= ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM market_data_gap_population_members existing
                      WHERE existing.work_id=? AND existing.entity_type=? AND existing.entity_id=population.entity_id
                        AND existing.projection_hash=encode(
                            digest(? || ':' || population.entity_id || ':' || COALESCE(population.birth_sequence::text, 'PRE_C'), 'sha256'),
                            'hex'
                        )
                  )
                ORDER BY birth_sequence NULLS FIRST, entity_id
                LIMIT ?
                ON CONFLICT (work_id, entity_type, entity_id) DO UPDATE SET projection_hash='HASH_MISMATCH'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, work.id)
                statement.setString(2, population.type)
                statement.setString(3, population.type)
                statement.setLong(4, work.populationAsOf)
                statement.setLong(5, work.birthUpper)
                statement.setObject(6, work.id)
                statement.setString(7, population.type)
                statement.setString(8, population.type)
                statement.setInt(9, limit)
                statement.executeUpdate()
            }
            total += inserted
            roundInserted += inserted
        }
        val shouldContinue = roundInserted > 0 && total < GAP_POPULATION_PASS_SIZE
        val hasNoHashMismatch = !hasPopulationHashMismatch(work.id)
    } while (shouldContinue && hasNoHashMismatch)
    return total
}

private fun JdbcTransaction.captureJournalPopulation(work: GapWorkRow): Int {
    var total = 0
    do {
        val inserted = prepare(
            """
            INSERT INTO market_data_gap_population_members
                (work_id, entity_type, entity_id, birth_sequence, projection_hash, source, captured_at)
            SELECT ?, entity_type, entity_id, birth_sequence, projection_hash, 'JOURNAL', ?
            FROM market_data_gap_terminal_journal journal
            WHERE journal_sequence > ?
                AND journal_sequence <= COALESCE(?, journal_sequence)
                AND (birth_sequence IS NULL OR birth_sequence <= ?)
                AND NOT EXISTS (
                    SELECT 1 FROM market_data_gap_population_members existing
                    WHERE existing.work_id=? AND existing.entity_type=journal.entity_type
                        AND existing.entity_id=journal.entity_id AND existing.projection_hash=journal.projection_hash
                )
            ORDER BY journal_sequence
            LIMIT ?
            ON CONFLICT (work_id, entity_type, entity_id) DO UPDATE SET projection_hash='HASH_MISMATCH'
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, work.id)
            statement.setLong(2, work.populationAsOf)
            statement.setLong(3, work.journalLower)
            statement.setObject(4, work.journalUpper)
            statement.setLong(5, work.birthUpper)
            statement.setObject(6, work.id)
            statement.setInt(7, minOf(GAP_POPULATION_PAGE_SIZE, GAP_POPULATION_PASS_SIZE - total))
            statement.executeUpdate()
        }
        total += inserted
        val shouldContinue = inserted > 0 && total < GAP_POPULATION_PASS_SIZE
        val hasNoHashMismatch = !hasPopulationHashMismatch(work.id)
    } while (shouldContinue && hasNoHashMismatch)
    return total
}

private data class PopulationQuery(val table: String, val type: String, val query: String)

private val CURRENT_POPULATION_QUERIES = listOf(
    PopulationQuery("orders", "ORDER", "SELECT id::text entity_id, birth_sequence FROM orders WHERE status IN ('OPEN','PENDING_CANCEL')"),
    PopulationQuery("positions", "POSITION", "SELECT id::text entity_id, birth_sequence FROM positions WHERE status='OPEN'"),
    PopulationQuery(
        "orders",
        "DECISION_RUN",
        """
        SELECT entity_id,
            CASE WHEN BOOL_OR(birth_sequence IS NULL) THEN NULL ELSE MIN(birth_sequence) END birth_sequence
        FROM (
            SELECT decision_run_id entity_id, birth_sequence FROM orders
            WHERE status IN ('OPEN','PENDING_CANCEL') AND side='BUY' AND position_id IS NULL
                AND decision_run_id IS NOT NULL
            UNION ALL
            SELECT decision_run_id entity_id, birth_sequence FROM positions
            WHERE status='OPEN' AND decision_run_id IS NOT NULL
            UNION ALL
            SELECT entry.decision_run_id entity_id, entry.birth_sequence
            FROM orders entry INNER JOIN positions affected ON affected.trade_group_id=entry.trade_group_id
            WHERE affected.status='OPEN' AND entry.status='FILLED' AND entry.side='BUY'
                AND entry.decision_run_id IS NOT NULL
        ) related_runs
        GROUP BY entity_id
        """.trimIndent(),
    ),
    PopulationQuery("llm_runs", "LLM_RUN", "SELECT invocation_id entity_id, birth_sequence FROM llm_runs WHERE status='RUNNING'"),
    PopulationQuery(
        "llm_launch_reservations",
        "LLM_RESERVATION",
        "SELECT id::text entity_id, birth_sequence FROM llm_launch_reservations WHERE status='RUNNING'",
    ),
    PopulationQuery(
        "opportunity_episodes",
        "OPPORTUNITY_EPISODE",
        "SELECT id::text entity_id, birth_sequence FROM opportunity_episodes WHERE closed_at IS NULL",
    ),
    PopulationQuery(
        "evaluation_report_jobs",
        "EVALUATION_REPORT_JOB",
        "SELECT job_id::text entity_id, birth_sequence FROM evaluation_report_jobs WHERE status IN ('REQUESTED','RUNNING')",
    ),
)

private fun JdbcTransaction.relationExistsForGapPopulation(table: String): Boolean {
    return prepare("SELECT to_regclass(?) IS NOT NULL").use { statement ->
        statement.setString(1, table)
        statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
    }
}

private data class GapWorkRow(
    val id: UUID,
    val gapId: UUID,
    val state: String,
    val populationAsOf: Long,
    val birthUpper: Long,
    val journalLower: Long,
    val journalUpper: Long?,
    val reason: String,
)

private data class GapMemberRow(val entityType: String, val entityId: String)

private fun JdbcTransaction.selectWorkForUpdate(workId: UUID): GapWorkRow? {
    return prepare(
        """
        SELECT w.id, w.gap_id, w.state, w.population_as_of, w.birth_sequence_upper,
            w.journal_sequence_lower, w.journal_sequence_upper,
            COALESCE((SELECT reason FROM market_data_gap_work_evidence e WHERE e.work_id=w.id ORDER BY created_at LIMIT 1), 'DATABASE_FAILURE')
        FROM market_data_gap_work w WHERE w.id=? FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, workId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            GapWorkRow(
                id = rows.getObject(1, UUID::class.java),
                gapId = rows.getObject(2, UUID::class.java),
                state = rows.getString(3),
                populationAsOf = rows.getLong(4),
                birthUpper = rows.getLong(5),
                journalLower = rows.getLong(6),
                journalUpper = rows.getLong(7).takeUnless { rows.wasNull() },
                reason = rows.getString(8),
            )
        }
    }
}

private fun JdbcTransaction.selectUnappliedMembers(workId: UUID): List<GapMemberRow> {
    return prepare(
        "SELECT entity_type, entity_id FROM market_data_gap_population_members " +
            "WHERE work_id=? AND applied_at IS NULL ORDER BY entity_type, entity_id LIMIT ?",
    ).use { statement ->
        statement.setObject(1, workId)
        statement.setInt(2, GAP_POPULATION_PASS_SIZE)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(GapMemberRow(rows.getString(1), rows.getString(2))) }
        }
    }
}

private fun JdbcTransaction.insertEvaluationExclusion(
    gapId: UUID,
    member: GapMemberRow,
    reason: String,
    now: Instant,
) {
    prepare(
        """
        INSERT INTO evaluation_exclusions (id, gap_id, entity_type, entity_id, reason, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (gap_id, entity_type, entity_id) DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, gapId)
        statement.setString(3, member.entityType)
        statement.setString(4, member.entityId)
        statement.setString(5, reason)
        statement.setLong(6, now.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.cancelGapOrder(
    orderId: String,
    reason: String,
    now: Instant,
) {
    prepare(
        """
        UPDATE orders SET status='CANCELED', reason_ja=?, canceled_at=?, cancel_reason='market_data_gap', updated_at=?
        WHERE id=?::uuid AND status IN ('OPEN','PENDING_CANCEL') AND side='BUY' AND position_id IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, "market-data gap: $reason")
        statement.setLong(2, now.toEpochMilli())
        statement.setLong(3, now.toEpochMilli())
        statement.setString(4, orderId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.failGapEvaluationReport(jobId: String, now: Instant) {
    if (!relationExistsForGapPopulation("evaluation_report_jobs")) return
    prepare(
        """
        UPDATE evaluation_report_jobs
        SET status='FAILED', stage='FAILED', failure_code='MARKET_DATA_GAP',
            failure_message='Infrastructure gap made this report population unobservable.', updated_at=?
        WHERE job_id=?::uuid AND status IN ('REQUESTED','RUNNING')
        """.trimIndent(),
    ).use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setString(2, jobId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.selectExactWork(identity: GapSourceWorkIdentity): UUID? {
    return prepare(
        """
        SELECT id FROM market_data_gap_work
        WHERE provider=? AND symbol=? AND channel=? AND session_id=? AND source_kind=? AND source_episode=?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, identity.provider)
        statement.setString(2, identity.symbol)
        statement.setString(3, identity.channel)
        statement.setObject(4, identity.sessionId)
        statement.setString(5, identity.sourceKind)
        statement.setString(6, identity.sourceEpisode)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }
}

private fun JdbcTransaction.appendGapWorkEvidence(
    workId: UUID,
    reason: String,
    detail: String?,
    at: Instant,
) {
    val count = prepare("SELECT COUNT(*) FROM market_data_gap_work_evidence WHERE work_id=?").use { statement ->
        statement.setObject(1, workId)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }
    if (count >= GAP_POPULATION_EVIDENCE_LIMIT) return
    prepare(
        """
        INSERT INTO market_data_gap_work_evidence (id, work_id, reason, detail_hash, observed_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (work_id, reason, detail_hash) DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, workId)
        statement.setString(3, reason)
        statement.setString(4, sha256(detail.orEmpty()))
        statement.setLong(5, at.toEpochMilli())
        statement.setLong(6, at.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.hasActiveGapGeneration(identity: GapSourceWorkIdentity): Boolean {
    return prepare(
        "SELECT 1 FROM market_data_gap_work WHERE provider=? AND symbol=? AND channel=? " +
            "AND state IN ('CAPTURING','SEALED','APPLYING') LIMIT 1",
    ).use { statement ->
        statement.setString(1, identity.provider)
        statement.setString(2, identity.symbol)
        statement.setString(3, identity.channel)
        statement.executeQuery().use { it.next() }
    }
}

private fun JdbcTransaction.countPendingGapWork(identity: GapSourceWorkIdentity): Int {
    return prepare(
        "SELECT COUNT(*) FROM market_data_gap_work WHERE provider=? AND symbol=? AND channel=? AND state='QUEUED'",
    ).use { statement ->
        statement.setString(1, identity.provider)
        statement.setString(2, identity.symbol)
        statement.setString(3, identity.channel)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }
}

private fun JdbcTransaction.insertOverflowWork(
    identity: GapSourceWorkIdentity,
    gapId: UUID,
    now: Instant,
    pendingCount: Int,
): UUID {
    val overflowIdentity = identity.copy(sourceKind = "OVERFLOW", sourceEpisode = "QUEUE")
    selectExactWork(overflowIdentity)?.let { return it }
    val workId = UUID.randomUUID()
    prepare(
        """
        INSERT INTO market_data_gap_work (
            id, gap_id, provider, symbol, channel, session_id, source_kind, source_episode,
            identity_hash, state, population_as_of, birth_sequence_upper, journal_sequence_lower,
            unknown_code, overflow_count, created_at, updated_at, completed_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'OVERFLOW', 'QUEUE', ?, 'UNKNOWN', ?, ?, ?, 'UNKNOWN_OVERFLOW', ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, workId)
        statement.setObject(2, gapId)
        statement.setString(3, identity.provider)
        statement.setString(4, identity.symbol)
        statement.setString(5, identity.channel)
        statement.setObject(6, identity.sessionId)
        statement.setString(7, overflowIdentity.canonicalHash())
        statement.setLong(8, now.toEpochMilli())
        statement.setLong(9, currentBirthSequenceUpper())
        statement.setLong(10, currentJournalSequenceUpper())
        statement.setInt(11, pendingCount)
        statement.setLong(12, now.toEpochMilli())
        statement.setLong(13, now.toEpochMilli())
        statement.setLong(14, now.toEpochMilli())
        statement.executeUpdate()
    }
    prepare(
        """
        INSERT INTO market_data_gap_population_generations (id, work_id, state, created_at, updated_at)
        VALUES (?, ?, 'UNKNOWN', ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, workId)
        statement.setLong(3, now.toEpochMilli())
        statement.setLong(4, now.toEpochMilli())
        statement.executeUpdate()
    }
    insertRecoveryProgress(workId, "UNKNOWN", now.toEpochMilli())
    return workId
}

private fun JdbcTransaction.activateNextQueuedWork(now: Instant) {
    val next = prepare(
        """
        SELECT queued.id FROM market_data_gap_work queued
        WHERE queued.state='QUEUED'
          AND NOT EXISTS (
              SELECT 1 FROM market_data_gap_work active
              WHERE active.provider=queued.provider AND active.symbol=queued.symbol AND active.channel=queued.channel
                AND active.state IN ('CAPTURING','SEALED','APPLYING')
          )
        ORDER BY queued.created_at, queued.id LIMIT 1 FOR UPDATE SKIP LOCKED
        """.trimIndent(),
    ).use { statement -> statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null } }
        ?: return
    prepare(
        "UPDATE market_data_gap_work SET state='CAPTURING', journal_sequence_upper=?, updated_at=? WHERE id=?",
    ).use { statement ->
        statement.setLong(1, currentJournalSequenceUpper())
        statement.setLong(2, now.toEpochMilli())
        statement.setObject(3, next)
        statement.executeUpdate()
    }
    updateGenerationState(next, "CAPTURING", now)
    updateRecoveryProgress(next, "CAPTURING", 0, null, now)
}

private fun JdbcTransaction.selectActiveWorkIds(): List<UUID> {
    return prepare(
        "SELECT id FROM market_data_gap_work WHERE state IN ('CAPTURING','SEALED','APPLYING') ORDER BY created_at LIMIT ?",
    ).use { statement ->
        statement.setInt(1, GAP_POPULATION_PASS_SIZE)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
    }
}

private fun JdbcTransaction.updateGenerationState(
    workId: UUID,
    state: String,
    now: Instant,
) {
    prepare("UPDATE market_data_gap_population_generations SET state=?, updated_at=? WHERE work_id=?").use { statement ->
        statement.setString(1, state)
        statement.setLong(2, now.toEpochMilli())
        statement.setObject(3, workId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.markWorkUnknown(
    workId: UUID,
    code: String,
    now: Instant,
) {
    prepare(
        "UPDATE market_data_gap_work SET state='UNKNOWN', unknown_code=?, updated_at=?, completed_at=? WHERE id=?",
    ).use { statement ->
        statement.setString(1, code)
        statement.setLong(2, now.toEpochMilli())
        statement.setLong(3, now.toEpochMilli())
        statement.setObject(4, workId)
        statement.executeUpdate()
    }
    updateGenerationState(workId, "UNKNOWN", now)
    updateRecoveryProgress(workId, "UNKNOWN", countWorkMembers(workId), null, now)
}

private fun JdbcTransaction.insertRecoveryProgress(
    workId: UUID,
    phase: String,
    updatedAt: Long,
) {
    prepare(
        """
        INSERT INTO market_data_gap_recovery_progress (work_id, phase, processed_count, updated_at)
        VALUES (?, ?, 0, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, workId)
        statement.setString(2, phase)
        statement.setLong(3, updatedAt)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.updateRecoveryProgress(
    workId: UUID,
    phase: String,
    processedCount: Int,
    lastEntityId: String?,
    now: Instant,
) {
    prepare(
        """
        UPDATE market_data_gap_recovery_progress
        SET phase=?, processed_count=?, last_entity_id=COALESCE(?, last_entity_id), updated_at=?
        WHERE work_id=?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, phase)
        statement.setInt(2, processedCount)
        statement.setString(3, lastEntityId)
        statement.setLong(4, now.toEpochMilli())
        statement.setObject(5, workId)
        statement.executeUpdate()
    }
}

private fun JdbcTransaction.countWorkMembers(workId: UUID): Int {
    return prepare("SELECT COUNT(*) FROM market_data_gap_population_members WHERE work_id=?").use { statement ->
        statement.setObject(1, workId)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }
}

private fun JdbcTransaction.countAppliedMembers(workId: UUID): Int {
    return prepare(
        "SELECT COUNT(*) FROM market_data_gap_population_members WHERE work_id=? AND applied_at IS NOT NULL",
    ).use { statement ->
        statement.setObject(1, workId)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }
}

private fun JdbcTransaction.hasPopulationHashMismatch(workId: UUID): Boolean {
    return prepare(
        "SELECT 1 FROM market_data_gap_population_members WHERE work_id=? AND projection_hash='HASH_MISMATCH' LIMIT 1",
    ).use { statement ->
        statement.setObject(1, workId)
        statement.executeQuery().use { it.next() }
    }
}

private fun JdbcTransaction.journalCapacityExceeded(): Boolean {
    return prepare(
        "SELECT COUNT(*) > ? OR COALESCE(SUM(octet_length(projection_hash) + octet_length(entity_id)), 0) > ? " +
            "FROM market_data_gap_terminal_journal",
    ).use { statement ->
        statement.setInt(1, GAP_POPULATION_JOURNAL_LIMIT)
        statement.setLong(2, GAP_POPULATION_JOURNAL_BYTES_LIMIT)
        statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
    }
}

private fun JdbcTransaction.currentBirthSequenceUpper(): Long {
    return prepare("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM gap_population_birth_sequence").use { statement ->
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }
}

private fun JdbcTransaction.currentJournalSequenceUpper(): Long {
    return prepare("SELECT COALESCE(MAX(journal_sequence), 0) FROM market_data_gap_terminal_journal").use { statement ->
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }
}

private fun JdbcTransaction.currentDatabaseTimeMillis(): Long {
    return prepare("SELECT (extract(epoch from clock_timestamp()) * 1000)::bigint").use { statement ->
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }
}

private fun JdbcTransaction.countRecoverableGapWork(): Int {
    return prepare(
        "SELECT COUNT(*) FROM market_data_gap_work WHERE state IN ('QUEUED','CAPTURING','SEALED','APPLYING')",
    ).use { statement ->
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }
}

private fun GapSourceWorkIdentity.canonicalHash(): String = sha256(
    listOf(provider, symbol, channel, sessionId.toString(), sourceKind, sourceEpisode).joinToString("\u0000"),
)

private fun sha256(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

private val GAP_POPULATION_SCHEMA_SQL: List<String>
    get() = listOf(
        "CREATE EXTENSION IF NOT EXISTS pgcrypto",
        """
        CREATE TABLE IF NOT EXISTS gap_population_control (
            id SMALLINT PRIMARY KEY CHECK (id = 1),
            token_version BIGINT NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "INSERT INTO gap_population_control (id) VALUES (1) ON CONFLICT (id) DO NOTHING",
        GAP_POPULATION_ACQUIRE_FUNCTION_SQL,
        "CREATE SEQUENCE IF NOT EXISTS gap_population_birth_sequence START WITH 1",
        "CREATE SEQUENCE IF NOT EXISTS gap_population_journal_sequence START WITH 1",
        """
        CREATE TABLE IF NOT EXISTS market_data_gap_work (
        id UUID PRIMARY KEY,
        gap_id UUID NOT NULL REFERENCES market_data_gaps(id),
        provider VARCHAR(64) NOT NULL,
        symbol VARCHAR(32) NOT NULL,
        channel VARCHAR(64) NOT NULL,
        session_id UUID NOT NULL REFERENCES market_data_sessions(id),
        source_kind VARCHAR(64) NOT NULL,
        source_episode VARCHAR(128) NOT NULL,
        identity_hash CHAR(64) NOT NULL,
        state VARCHAR(32) NOT NULL,
        population_as_of BIGINT NOT NULL,
        birth_sequence_upper BIGINT NOT NULL,
        journal_sequence_lower BIGINT NOT NULL,
        journal_sequence_upper BIGINT,
        unknown_code VARCHAR(64),
        overflow_count BIGINT,
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL,
        completed_at BIGINT,
        UNIQUE(provider, symbol, channel, session_id, source_kind, source_episode)
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS market_data_gap_work_active_stream_uq
        ON market_data_gap_work(provider, symbol, channel)
        WHERE state IN ('CAPTURING','SEALED','APPLYING')
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS market_data_gap_work_evidence (
        id UUID PRIMARY KEY,
        work_id UUID NOT NULL REFERENCES market_data_gap_work(id),
        reason VARCHAR(64) NOT NULL,
        detail_hash CHAR(64) NOT NULL,
        observed_at BIGINT NOT NULL,
        created_at BIGINT NOT NULL,
        UNIQUE(work_id, reason, detail_hash)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS market_data_gap_population_generations (
        id UUID PRIMARY KEY,
        work_id UUID NOT NULL UNIQUE REFERENCES market_data_gap_work(id),
        state VARCHAR(32) NOT NULL,
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS market_data_gap_population_members (
        work_id UUID NOT NULL REFERENCES market_data_gap_work(id),
        entity_type VARCHAR(32) NOT NULL,
        entity_id VARCHAR(128) NOT NULL,
        birth_sequence BIGINT,
        projection_hash VARCHAR(64) NOT NULL,
        source VARCHAR(16) NOT NULL,
        captured_at BIGINT NOT NULL,
        applied_at BIGINT,
        PRIMARY KEY(work_id, entity_type, entity_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS market_data_gap_terminal_journal (
        journal_sequence BIGINT PRIMARY KEY DEFAULT nextval('gap_population_journal_sequence'),
        entity_type VARCHAR(32) NOT NULL,
        entity_id VARCHAR(128) NOT NULL,
        birth_sequence BIGINT,
        projection_hash CHAR(64) NOT NULL,
        terminal_at BIGINT NOT NULL,
        UNIQUE(entity_type, entity_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS market_data_gap_recovery_progress (
        work_id UUID PRIMARY KEY REFERENCES market_data_gap_work(id),
        phase VARCHAR(32) NOT NULL,
        entity_type VARCHAR(32),
        last_entity_id VARCHAR(128),
        processed_count BIGINT NOT NULL DEFAULT 0,
        updated_at BIGINT NOT NULL
        )
        """.trimIndent(),
        "ALTER TABLE orders ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
        "ALTER TABLE positions ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
        "ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
        "ALTER TABLE llm_launch_reservations ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
        "ALTER TABLE opportunity_episodes ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
        "CREATE INDEX IF NOT EXISTS orders_gap_birth_idx ON orders(birth_sequence, id)",
        "CREATE INDEX IF NOT EXISTS positions_gap_birth_idx ON positions(birth_sequence, id)",
        "CREATE INDEX IF NOT EXISTS llm_runs_gap_birth_idx ON llm_runs(birth_sequence, invocation_id)",
        "CREATE INDEX IF NOT EXISTS llm_reservations_gap_birth_idx ON llm_launch_reservations(birth_sequence, id)",
        "CREATE INDEX IF NOT EXISTS opportunity_episodes_gap_birth_idx ON opportunity_episodes(birth_sequence, id)",
        GAP_POPULATION_TOKEN_TRIGGER_FUNCTION_SQL,
        GAP_POPULATION_TERMINAL_TRIGGER_FUNCTION_SQL,
    ) + GAP_POPULATION_TRIGGER_SQL

private val EVALUATION_REPORT_GAP_POPULATION_SCHEMA_SQL = listOf(
    "ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
    "CREATE INDEX IF NOT EXISTS evaluation_report_jobs_gap_birth_idx ON evaluation_report_jobs(birth_sequence, job_id)",
    "DROP TRIGGER IF EXISTS evaluation_report_jobs_gap_population_create ON evaluation_report_jobs",
    "CREATE TRIGGER evaluation_report_jobs_gap_population_create BEFORE INSERT ON evaluation_report_jobs " +
        "FOR EACH ROW EXECUTE FUNCTION enforce_gap_population_creation_token()",
    "DROP TRIGGER IF EXISTS evaluation_report_jobs_gap_population_terminal ON evaluation_report_jobs",
    "CREATE TRIGGER evaluation_report_jobs_gap_population_terminal BEFORE UPDATE ON evaluation_report_jobs FOR EACH ROW " +
        "EXECUTE FUNCTION fence_gap_population_terminal_mutation('job_id','STATUS','SUCCEEDED,FAILED,REJECTED','EVALUATION_REPORT_JOB')",
)

private const val GAP_POPULATION_TOKEN_TRIGGER_FUNCTION_SQL = """
CREATE OR REPLACE FUNCTION enforce_gap_population_creation_token() RETURNS trigger AS ${'$'}fn${'$'}
DECLARE
    configured TEXT;
    expected TEXT;
BEGIN
    configured := current_setting('fukurou.gap_population_token', true);
    SELECT txid_current()::text || ':' || token_version::text INTO expected
    FROM gap_population_control WHERE id=1;
    IF configured IS NULL OR configured = '' OR configured <> expected THEN
        RAISE EXCEPTION 'gap population generation token is missing or stale';
    END IF;
    IF NEW.birth_sequence IS NULL THEN
        NEW.birth_sequence := nextval('gap_population_birth_sequence');
    END IF;
    RETURN NEW;
END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public
"""

private const val GAP_POPULATION_ACQUIRE_FUNCTION_SQL = """
CREATE OR REPLACE FUNCTION acquire_gap_population_generation_token() RETURNS void AS ${'$'}fn${'$'}
DECLARE
    current_version BIGINT;
BEGIN
    UPDATE public.gap_population_control
    SET token_version = token_version + 1
    WHERE id = 1
    RETURNING token_version INTO current_version;
    IF current_version IS NULL THEN
        RAISE EXCEPTION 'gap population control row was not initialized';
    END IF;
    PERFORM set_config('fukurou.gap_population_token', txid_current()::text || ':' || current_version::text, true);
END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION acquire_gap_population_generation_token() FROM PUBLIC
"""

private const val GAP_POPULATION_TERMINAL_TRIGGER_FUNCTION_SQL = """
CREATE OR REPLACE FUNCTION fence_gap_population_terminal_mutation() RETURNS trigger AS ${'$'}fn${'$'}
DECLARE
    configured TEXT;
    expected TEXT;
    entity_id_value TEXT;
    projection_hash_value TEXT;
    terminal BOOLEAN;
    related_decision_run_id TEXT;
    related_birth_sequence BIGINT;
    related_projection_hash TEXT;
BEGIN
    IF TG_ARGV[1] = 'STATUS' THEN
        terminal := (to_jsonb(OLD)->>'status') IS DISTINCT FROM (to_jsonb(NEW)->>'status')
            AND (to_jsonb(NEW)->>'status') = ANY(string_to_array(TG_ARGV[2], ','));
    ELSIF TG_ARGV[1] = 'CLOSED_AT' THEN
        terminal := (to_jsonb(OLD)->>'closed_at') IS NULL AND (to_jsonb(NEW)->>'closed_at') IS NOT NULL;
    ELSE
        terminal := FALSE;
    END IF;
    IF NOT terminal THEN RETURN NEW; END IF;

    configured := current_setting('fukurou.gap_population_token', true);
    SELECT txid_current()::text || ':' || token_version::text INTO expected
    FROM gap_population_control WHERE id=1;
    IF configured IS NULL OR configured = '' OR configured <> expected THEN
        RAISE EXCEPTION 'gap population generation token is missing or stale';
    END IF;

    entity_id_value := to_jsonb(NEW)->>TG_ARGV[0];
    projection_hash_value := encode(
        digest(TG_ARGV[3] || ':' || entity_id_value || ':' || COALESCE(NEW.birth_sequence::text, 'PRE_C'), 'sha256'),
        'hex'
    );
    INSERT INTO market_data_gap_population_members
        (work_id, entity_type, entity_id, birth_sequence, projection_hash, source, captured_at)
    SELECT work.id, TG_ARGV[3], entity_id_value, NEW.birth_sequence, projection_hash_value, 'TERMINAL',
        (extract(epoch from clock_timestamp()) * 1000)::bigint
    FROM market_data_gap_work work
    WHERE work.state='CAPTURING'
      AND (NEW.birth_sequence IS NULL OR NEW.birth_sequence <= work.birth_sequence_upper)
    ON CONFLICT (work_id, entity_type, entity_id) DO UPDATE
    SET projection_hash = CASE
        WHEN market_data_gap_population_members.projection_hash=EXCLUDED.projection_hash
            THEN market_data_gap_population_members.projection_hash
        ELSE 'HASH_MISMATCH'
    END;

    IF EXISTS (SELECT 1 FROM market_data_gap_work WHERE state='QUEUED') THEN
        INSERT INTO market_data_gap_terminal_journal
            (entity_type, entity_id, birth_sequence, projection_hash, terminal_at)
        VALUES (TG_ARGV[3], entity_id_value, NEW.birth_sequence, projection_hash_value,
            (extract(epoch from clock_timestamp()) * 1000)::bigint)
        ON CONFLICT (entity_type, entity_id) DO UPDATE
        SET projection_hash = CASE
            WHEN market_data_gap_terminal_journal.projection_hash=EXCLUDED.projection_hash
                THEN market_data_gap_terminal_journal.projection_hash
            ELSE 'HASH_MISMATCH'
        END;
    END IF;

    IF TG_ARGV[3] IN ('ORDER', 'POSITION') THEN
        FOR related_decision_run_id, related_birth_sequence IN
            SELECT related.entity_id, related.birth_sequence
            FROM (
                SELECT to_jsonb(OLD)->>'decision_run_id' entity_id, NEW.birth_sequence birth_sequence
                UNION ALL
                SELECT entry.decision_run_id entity_id, entry.birth_sequence
                FROM orders entry
                WHERE TG_ARGV[3]='POSITION'
                  AND entry.trade_group_id::text=to_jsonb(OLD)->>'trade_group_id'
                  AND entry.status='FILLED' AND entry.side='BUY'
            ) related
            WHERE related.entity_id IS NOT NULL
        LOOP
            related_projection_hash := encode(
                digest('DECISION_RUN:' || related_decision_run_id || ':' || COALESCE(related_birth_sequence::text, 'PRE_C'), 'sha256'),
                'hex'
            );
            INSERT INTO market_data_gap_population_members
                (work_id, entity_type, entity_id, birth_sequence, projection_hash, source, captured_at)
            SELECT work.id, 'DECISION_RUN', related_decision_run_id, related_birth_sequence,
                related_projection_hash, 'TERMINAL', (extract(epoch from clock_timestamp()) * 1000)::bigint
            FROM market_data_gap_work work
            WHERE work.state='CAPTURING'
              AND (related_birth_sequence IS NULL OR related_birth_sequence <= work.birth_sequence_upper)
            ON CONFLICT (work_id, entity_type, entity_id) DO UPDATE
            SET projection_hash = CASE
                WHEN market_data_gap_population_members.projection_hash=EXCLUDED.projection_hash
                    THEN market_data_gap_population_members.projection_hash
                ELSE 'HASH_MISMATCH'
            END;

            IF EXISTS (SELECT 1 FROM market_data_gap_work WHERE state='QUEUED') THEN
                INSERT INTO market_data_gap_terminal_journal
                    (entity_type, entity_id, birth_sequence, projection_hash, terminal_at)
                VALUES ('DECISION_RUN', related_decision_run_id, related_birth_sequence, related_projection_hash,
                    (extract(epoch from clock_timestamp()) * 1000)::bigint)
                ON CONFLICT (entity_type, entity_id) DO UPDATE
                SET projection_hash = CASE
                    WHEN market_data_gap_terminal_journal.projection_hash=EXCLUDED.projection_hash
                        THEN market_data_gap_terminal_journal.projection_hash
                    ELSE 'HASH_MISMATCH'
                END;
            END IF;
        END LOOP;
    END IF;
    RETURN NEW;
END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public
"""

private val GAP_POPULATION_TRIGGER_SQL = listOf(
    "orders:id:STATUS:FILLED,CANCELED,REJECTED,EXPIRED:ORDER",
    "positions:id:STATUS:CLOSED:POSITION",
    "llm_runs:invocation_id:STATUS:SUCCEEDED,FAILED,TIMED_OUT,CANCELLED,NO_TRADE:LLM_RUN",
    "llm_launch_reservations:id:STATUS:FINISHED,FAILED,REJECTED:LLM_RESERVATION",
    "opportunity_episodes:id:CLOSED_AT::OPPORTUNITY_EPISODE",
).flatMap { encoded ->
    val (table, idColumn, terminalKind, terminalValues, entityType) = encoded.split(':')
    listOf(
        "DROP TRIGGER IF EXISTS ${table}_gap_population_create ON $table",
        "CREATE TRIGGER ${table}_gap_population_create BEFORE INSERT ON $table " +
            "FOR EACH ROW EXECUTE FUNCTION enforce_gap_population_creation_token()",
        "DROP TRIGGER IF EXISTS ${table}_gap_population_terminal ON $table",
        "CREATE TRIGGER ${table}_gap_population_terminal BEFORE UPDATE ON $table FOR EACH ROW " +
            "EXECUTE FUNCTION fence_gap_population_terminal_mutation('$idColumn','$terminalKind','$terminalValues','$entityType')",
    )
}
