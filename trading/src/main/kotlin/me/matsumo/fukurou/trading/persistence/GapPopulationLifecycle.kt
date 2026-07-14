@file:Suppress("LongMethod", "TooManyFunctions")

package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.daemon.LlmExecutionRecoveryDeadline
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

/** gap populationのimmutableな帰属scope。 */
data class GapPopulationScope(
    val kind: String,
    val mode: String,
    val symbol: String?,
    val accountEpochId: UUID,
    val cohort: String,
    val executionSemanticsVersion: String?,
) {
    /** DBに保存するscope hash。 */
    val hash: String
        get() = sha256(
            listOf(kind, mode, symbol.orEmpty(), accountEpochId.toString(), cohort, executionSemanticsVersion.orEmpty())
                .joinToString(":"),
        )
}

/** transport-native sourceをdomain-native population symbolへexact変換する。 */
object GapPopulationSymbolCanonicalizer {
    private val mappings = mapOf(Triple("GMO_COIN", "BTC_JPY", "TRADES") to "BTC")

    /** 未登録tupleを推定せずnullにする。 */
    fun canonicalize(
        provider: String,
        transportSymbol: String,
        channel: String,
    ): String? {
        val hasBlankTransportTuple = provider.isBlank() || transportSymbol.isBlank() || channel.isBlank()
        if (hasBlankTransportTuple) return null

        return mappings[Triple(provider, transportSymbol, channel)]
    }

    /** schema verifierとtestが照合するexact inventory。 */
    fun inventory(): Map<Triple<String, String, String>, String> = mappings.toMap()
}

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
    val processed: Int = 0,
    val state: String = if (remaining == 0 && unknown == 0) "ALL_APPLIED" else "INCOMPLETE_OR_UNKNOWN",
)

internal const val GAP_POPULATION_PAGE_SIZE = 100
internal const val GAP_POPULATION_PASS_SIZE = 1_000
internal const val GAP_POPULATION_PENDING_WORK_LIMIT = 1_000
internal const val GAP_POPULATION_EVIDENCE_LIMIT = 32
internal const val GAP_POPULATION_MEMBER_LIMIT = 100_000
internal const val GAP_POPULATION_JOURNAL_LIMIT = 100_000
internal const val GAP_POPULATION_JOURNAL_BYTES_LIMIT = 256L * 1024L * 1024L

internal fun gapPopulationQueueCapacityExceeded(
    pendingCount: Int,
    limit: Int = GAP_POPULATION_PENDING_WORK_LIMIT,
): Boolean = pendingCount >= limit

internal fun gapPopulationEvidenceCapacityExceeded(
    currentCount: Int,
    limit: Int = GAP_POPULATION_EVIDENCE_LIMIT,
): Boolean = currentCount >= limit

internal fun gapPopulationJournalCapacityExceeded(
    unconsumedRows: Long,
    unconsumedBytes: Long,
    rowLimit: Long = GAP_POPULATION_JOURNAL_LIMIT.toLong(),
    byteLimit: Long = GAP_POPULATION_JOURNAL_BYTES_LIMIT,
): Boolean = unconsumedRows > rowLimit || unconsumedBytes > byteLimit

/** unattributed containmentに基づくruntime admission mode。 */
enum class GapPopulationResumeMode { FULL, PROTECTION_ONLY, STOPPED }

/** risk-increasing admissionはFULLだけで許可する。 */
fun JdbcTransaction.requireFullGapPopulationAdmission(operation: String) {
    val mode = selectGapPopulationResumeMode()
    require(mode == GapPopulationResumeMode.FULL) {
        "gap population containment blocks $operation in ${mode.name} mode."
    }
}

/** containment owner/attachから現在のresume modeを導出する。 */
fun JdbcTransaction.selectGapPopulationResumeMode(): GapPopulationResumeMode {
    if (!relationExistsForGapPopulation("gap_population_unattributed_containments")) return GapPopulationResumeMode.FULL
    return prepare(
        "SELECT EXISTS(SELECT 1 FROM market_data_gap_recovery_progress " +
            "WHERE phase IN ('CAPTURING','UNATTRIBUTED_SCANNING','UNATTRIBUTED_TERMINATING'))," +
            "EXISTS(SELECT 1 FROM gap_population_unattributed_containments WHERE state='QUARANTINED')," +
            "EXISTS(SELECT 1 FROM gap_population_unattributed_containments WHERE state IN ('DISCOVERED','TERMINALIZING'))," +
            "EXISTS(SELECT 1 FROM gap_population_unattributed_containment_works WHERE consumed_at IS NULL)",
    ).use { statement ->
        statement.executeQuery().use { rows ->
            require(rows.next())
            when {
                rows.getBoolean(1) || rows.getBoolean(3) || rows.getBoolean(4) -> GapPopulationResumeMode.STOPPED
                rows.getBoolean(2) -> GapPopulationResumeMode.PROTECTION_ONLY
                else -> GapPopulationResumeMode.FULL
            }
        }
    }
}

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
    val existing = prepare("SELECT current_setting('fukurou.gap_population_token', true)").use { statement ->
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
    if (!existing.isNullOrBlank()) {
        require(configuredGapPopulationTokenMatches(allowedPopulation = "ALL", scope = null)) {
            "gap population token cannot be reacquired with another capability."
        }
        return PersistedGapPopulationGenerationToken(existing.substringAfter(':').toLong())
    }

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

/** typed provenanceを持つreport/reservation向けapp-only tokenを取得する。 */
fun JdbcTransaction.acquireGapPopulationGenerationToken(scope: GapPopulationScope): GapPopulationGenerationToken {
    val existing = prepare("SELECT current_setting('fukurou.gap_population_token', true)").use { statement ->
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
    if (!existing.isNullOrBlank()) {
        require(configuredGapPopulationTokenMatches(allowedPopulation = "ALL", scope = scope)) {
            "gap population token cannot be reacquired with another scope."
        }
        return PersistedGapPopulationGenerationToken(existing.substringAfter(':').toLong())
    }

    prepare("SELECT acquire_gap_population_generation_token(?,?,?,?,?,?)").use { statement ->
        statement.setString(1, scope.kind)
        statement.setString(2, scope.mode)
        statement.setString(3, scope.symbol)
        statement.setObject(4, scope.accountEpochId)
        statement.setString(5, scope.cohort)
        statement.setString(6, scope.executionSemanticsVersion)
        statement.executeQuery().use { rows -> require(rows.next()) }
    }
    return readConfiguredGapPopulationToken()
}

private fun JdbcTransaction.configuredGapPopulationTokenMatches(
    allowedPopulation: String,
    scope: GapPopulationScope?,
): Boolean {
    return prepare(
        "SELECT allowed_population,scope_kind,scope_mode,scope_symbol,scope_account_epoch_id,scope_cohort," +
            "scope_execution_semantics_version FROM gap_population_control WHERE id=1 AND token_txid=txid_current()",
    ).use { statement ->
        statement.executeQuery().use { rows ->
            if (!rows.next() || rows.getString(1) != allowedPopulation) {
                false
            } else {
                scope == null || (
                    rows.getString(2) == scope.kind && rows.getString(3) == scope.mode &&
                        rows.getString(4) == scope.symbol && rows.getObject(5, UUID::class.java) == scope.accountEpochId &&
                        rows.getString(6) == scope.cohort && rows.getString(7) == scope.executionSemanticsVersion
                    )
            }
        }
    }
}

/** MCP submitDecision専用のOPPORTUNITY_EPISODE限定tokenを取得する。 */
fun JdbcTransaction.acquireOpportunityEpisodeGapPopulationToken(symbol: String): GapPopulationGenerationToken {
    prepare("SELECT acquire_opportunity_episode_gap_population_token(?)").use { statement ->
        statement.setString(1, symbol)
        statement.executeQuery().use { rows -> require(rows.next()) }
    }
    return readConfiguredGapPopulationToken()
}

/** persisted entityのimmutable scopeを使ってterminal tokenを取得する。 */
fun JdbcTransaction.acquireGapPopulationGenerationTokenForEntity(
    entityType: String,
    entityId: String,
): GapPopulationGenerationToken {
    val scope = prepare(
        "SELECT scope_kind,mode,symbol,account_epoch_id,cohort,execution_semantics_version " +
            "FROM gap_population_entity_scopes WHERE entity_type=? AND entity_id=?",
    ).use { statement ->
        statement.setString(1, entityType)
        statement.setString(2, entityId)
        statement.executeQuery().use { rows ->
            require(rows.next()) { "gap population entity scope is missing." }
            GapPopulationScope(
                kind = rows.getString(1),
                mode = rows.getString(2),
                symbol = rows.getString(3),
                accountEpochId = rows.getObject(4, UUID::class.java),
                cohort = rows.getString(5),
                executionSemanticsVersion = rows.getString(6),
            )
        }
    }
    return acquireGapPopulationGenerationToken(scope)
}

/** persisted entity scope tokenをrecovery pageのabsolute deadline内で取得する。 */
fun JdbcTransaction.acquireGapPopulationGenerationTokenForEntity(
    entityType: String,
    entityId: String,
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
): GapPopulationGenerationToken {
    val scope = prepareRecoveryStatement(
        sql = "SELECT scope_kind,mode,symbol,account_epoch_id,cohort,execution_semantics_version " +
            "FROM gap_population_entity_scopes WHERE entity_type=? AND entity_id=?",
        deadline = deadline,
        nanoTime = nanoTime,
    ).use { statement ->
        statement.setString(1, entityType)
        statement.setString(2, entityId)
        statement.executeQuery().use { rows ->
            require(rows.next()) { "gap population entity scope is missing." }
            GapPopulationScope(
                kind = rows.getString(1),
                mode = rows.getString(2),
                symbol = rows.getString(3),
                accountEpochId = rows.getObject(4, UUID::class.java),
                cohort = rows.getString(5),
                executionSemanticsVersion = rows.getString(6),
            )
        }
    }

    return acquireGapPopulationGenerationToken(scope, deadline, nanoTime)
}

private fun JdbcTransaction.acquireGapPopulationGenerationToken(
    scope: GapPopulationScope,
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
): GapPopulationGenerationToken {
    val existing = prepareRecoveryStatement(
        sql = "SELECT current_setting('fukurou.gap_population_token', true)",
        deadline = deadline,
        nanoTime = nanoTime,
    ).use { statement ->
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
    if (!existing.isNullOrBlank()) {
        require(configuredGapPopulationTokenMatches("ALL", scope, deadline, nanoTime)) {
            "gap population token cannot be reacquired with another scope."
        }
        return PersistedGapPopulationGenerationToken(existing.substringAfter(':').toLong())
    }

    prepareRecoveryStatement(
        sql = "SELECT acquire_gap_population_generation_token(?,?,?,?,?,?)",
        deadline = deadline,
        nanoTime = nanoTime,
    ).use { statement ->
        statement.setString(1, scope.kind)
        statement.setString(2, scope.mode)
        statement.setString(3, scope.symbol)
        statement.setObject(4, scope.accountEpochId)
        statement.setString(5, scope.cohort)
        statement.setString(6, scope.executionSemanticsVersion)
        statement.executeQuery().use { rows -> require(rows.next()) }
    }

    return prepareRecoveryStatement(
        sql = "SELECT current_setting('fukurou.gap_population_token', true)",
        deadline = deadline,
        nanoTime = nanoTime,
    ).use { statement ->
        statement.executeQuery().use { rows ->
            require(rows.next())
            PersistedGapPopulationGenerationToken(rows.getString(1).substringAfter(':').toLong())
        }
    }
}

private fun JdbcTransaction.configuredGapPopulationTokenMatches(
    allowedPopulation: String,
    scope: GapPopulationScope,
    deadline: LlmExecutionRecoveryDeadline,
    nanoTime: () -> Long,
): Boolean {
    return prepareRecoveryStatement(
        sql = "SELECT allowed_population,scope_kind,scope_mode,scope_symbol,scope_account_epoch_id,scope_cohort," +
            "scope_execution_semantics_version FROM gap_population_control WHERE id=1 AND token_txid=txid_current()",
        deadline = deadline,
        nanoTime = nanoTime,
    ).use { statement ->
        statement.executeQuery().use { rows ->
            rows.next() && rows.getString(1) == allowedPopulation &&
                rows.getString(2) == scope.kind && rows.getString(3) == scope.mode &&
                rows.getString(4) == scope.symbol && rows.getObject(5, UUID::class.java) == scope.accountEpochId &&
                rows.getString(6) == scope.cohort && rows.getString(7) == scope.executionSemanticsVersion
        }
    }
}

private fun JdbcTransaction.readConfiguredGapPopulationToken(): GapPopulationGenerationToken {
    val configured = prepare("SELECT current_setting('fukurou.gap_population_token', true)").use { statement ->
        statement.executeQuery().use { rows ->
            require(rows.next())
            rows.getString(1)
        }
    }
    return PersistedGapPopulationGenerationToken(configured.substringAfter(':').toLong())
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
            ('market_data_gap_recovery_progress'),
            ('gap_population_entity_scopes'),
            ('gap_population_unattributed_containments'),
            ('gap_population_unattributed_containment_works')
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
    verifyGapPopulationSchemaContract()
}

private fun JdbcTransaction.verifyGapPopulationSchemaContract() {
    val missingColumns = selectStringList(
        """
        SELECT expected.table_name || '.' || expected.column_name
        FROM (VALUES
            ('gap_population_control','token_txid','bigint'),
            ('gap_population_control','allowed_population','character varying'),
            ('market_data_gap_work','enqueue_sequence','bigint'),
            ('market_data_gap_work','transport_symbol','character varying'),
            ('market_data_gap_work','population_symbol','character varying'),
            ('market_data_gap_work','scope_hash','character'),
            ('gap_population_entity_scopes','scope_hash','character'),
            ('market_data_gap_population_members','scope_hash','character'),
            ('market_data_gap_terminal_journal','scope_hash','character')
        ) expected(table_name,column_name,data_type)
        LEFT JOIN information_schema.columns actual
          ON actual.table_schema='public' AND actual.table_name=expected.table_name
         AND actual.column_name=expected.column_name AND actual.data_type=expected.data_type
        WHERE actual.column_name IS NULL
        """.trimIndent(),
    )
    check(missingColumns.isEmpty()) { "gap population lifecycle columns differ: ${missingColumns.joinToString()}" }

    val missingObjects = selectStringList(
        """
        SELECT expected.name FROM (VALUES
            ('gap_population_entity_scope_scan_idx'),
            ('market_data_gap_work_fifo_idx'),
            ('gap_population_entity_scope_immutable'),
            ('market_data_gap_work_transport_mapping'),
            ('orders_gap_population_create'),
            ('orders_gap_population_terminal'),
            ('positions_gap_population_create'),
            ('positions_gap_population_terminal'),
            ('llm_runs_gap_population_create'),
            ('llm_runs_gap_population_terminal'),
            ('llm_launch_reservations_gap_population_create'),
            ('llm_launch_reservations_gap_population_terminal'),
            ('opportunity_episodes_gap_population_create'),
            ('opportunity_episodes_gap_population_terminal')
        ) expected(name)
        WHERE NOT EXISTS (SELECT 1 FROM pg_class WHERE relname=expected.name)
          AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname=expected.name AND NOT tgisinternal)
        """.trimIndent(),
    )
    check(missingObjects.isEmpty()) { "gap population lifecycle objects differ: ${missingObjects.joinToString()}" }

    val invalidIndexes = selectStringList(
        """
        SELECT expected.name FROM (VALUES
            ('gap_population_entity_scope_scan_idx','scope_kind, mode, symbol, account_epoch_id, cohort, execution_semantics_version, birth_sequence, entity_type, entity_id'),
            ('market_data_gap_work_fifo_idx','provider, transport_symbol, channel, enqueue_sequence')
        ) expected(name,columns)
        LEFT JOIN pg_class index_relation ON index_relation.relname=expected.name
        LEFT JOIN pg_index index_metadata ON index_metadata.indexrelid=index_relation.oid
        WHERE index_relation.oid IS NULL OR NOT index_metadata.indisvalid OR NOT index_metadata.indisready
          OR position(expected.columns in pg_get_indexdef(index_relation.oid))=0
        """.trimIndent(),
    )
    check(invalidIndexes.isEmpty()) { "gap population lifecycle indexes differ: ${invalidIndexes.joinToString()}" }

    val invalidTriggers = selectStringList(
        """
        SELECT expected.name FROM (VALUES
            ('gap_population_entity_scope_immutable','reject_gap_population_entity_scope_mutation'),
            ('market_data_gap_work_transport_mapping','enforce_gap_population_transport_mapping'),
            ('orders_gap_population_create','enforce_gap_population_creation_token'),
            ('orders_gap_population_terminal','fence_gap_population_terminal_mutation'),
            ('positions_gap_population_create','enforce_gap_population_creation_token'),
            ('positions_gap_population_terminal','fence_gap_population_terminal_mutation'),
            ('llm_runs_gap_population_create','enforce_gap_population_creation_token'),
            ('llm_runs_gap_population_terminal','fence_gap_population_terminal_mutation'),
            ('llm_launch_reservations_gap_population_create','enforce_gap_population_creation_token'),
            ('llm_launch_reservations_gap_population_terminal','fence_gap_population_terminal_mutation'),
            ('opportunity_episodes_gap_population_create','enforce_gap_population_creation_token'),
            ('opportunity_episodes_gap_population_terminal','fence_gap_population_terminal_mutation')
        ) expected(name,function_name)
        LEFT JOIN pg_trigger trigger ON trigger.tgname=expected.name AND NOT trigger.tgisinternal
        LEFT JOIN pg_proc function ON function.oid=trigger.tgfoid
        WHERE trigger.oid IS NULL OR trigger.tgenabled<>'O' OR function.proname<>expected.function_name
        """.trimIndent(),
    )
    check(invalidTriggers.isEmpty()) { "gap population lifecycle triggers differ: ${invalidTriggers.joinToString()}" }

    val invalidFunctions = selectStringList(
        """
        SELECT expected.signature FROM (VALUES
            ('acquire_gap_population_generation_token()'),
            ('acquire_gap_population_generation_token(text,text,text,uuid,text,text)'),
            ('acquire_opportunity_episode_gap_population_token(text)'),
            ('enforce_gap_population_creation_token()'),
            ('fence_gap_population_terminal_mutation()'),
            ('enforce_gap_population_transport_mapping()'),
            ('gap_population_projection_hash(text,text,text)'),
            ('reject_gap_population_entity_scope_mutation()')
        ) expected(signature)
        LEFT JOIN pg_proc function ON function.oid=to_regprocedure(expected.signature)
        WHERE function.oid IS NULL OR NOT function.prosecdef
          OR function.proowner <> (SELECT oid FROM pg_roles WHERE rolname=current_user)
          OR NOT ('search_path=pg_catalog, public'=ANY(function.proconfig))
          OR has_function_privilege('public',expected.signature,'EXECUTE')
        """.trimIndent(),
    )
    check(invalidFunctions.isEmpty()) { "gap population lifecycle functions differ: ${invalidFunctions.joinToString()}" }
}

private fun JdbcTransaction.selectStringList(sql: String): List<String> = prepare(sql).use { statement ->
    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
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
    val populationSymbol = requireNotNull(
        GapPopulationSymbolCanonicalizer.canonicalize(identity.provider, identity.symbol, identity.channel),
    ) { "gap population transport tuple is not registered." }
    val scope = selectCurrentPaperGapPopulationScope(populationSymbol)

    selectExactWork(identity, scope)?.let { workId ->
        appendGapWorkEvidence(workId, reason, detail, detectedAt)
        return workId
    }

    val pendingCount = countPendingGapWork(identity)
    if (gapPopulationQueueCapacityExceeded(pendingCount)) {
        return insertOverflowWork(identity, scope, gapId, detectedAt, pendingCount)
    }

    val active = hasActiveGapGeneration(identity)
    val workId = UUID.randomUUID()
    val enqueueSequence = nextGapWorkEnqueueSequence()
    val upper = currentBirthSequenceUpper()
    val journalLower = currentJournalSequenceUpper()
    val populationAsOf = currentDatabaseTimeMillis()
    prepare(
        """
        INSERT INTO market_data_gap_work (
            id, gap_id, provider, transport_symbol, population_symbol, symbol, channel, session_id,
            source_kind, source_episode, enqueue_sequence,
            identity_hash, state, population_as_of, birth_sequence_upper, journal_sequence_lower,
            scope_kind, scope_mode, scope_symbol, scope_account_epoch_id, scope_cohort,
            scope_execution_semantics_version, scope_hash,
            created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, workId)
        statement.setObject(2, gapId)
        statement.setString(3, identity.provider)
        statement.setString(4, identity.symbol)
        statement.setString(5, populationSymbol)
        statement.setString(6, identity.symbol)
        statement.setString(7, identity.channel)
        statement.setObject(8, identity.sessionId)
        statement.setString(9, identity.sourceKind)
        statement.setString(10, identity.sourceEpisode)
        statement.setLong(11, enqueueSequence)
        statement.setString(12, identity.canonicalHash())
        statement.setString(13, if (active) "QUEUED" else "CAPTURING")
        statement.setLong(14, populationAsOf)
        statement.setLong(15, upper)
        statement.setLong(16, journalLower)
        statement.setString(17, scope.kind)
        statement.setString(18, scope.mode)
        statement.setString(19, scope.symbol)
        statement.setObject(20, scope.accountEpochId)
        statement.setString(21, scope.cohort)
        statement.setString(22, scope.executionSemanticsVersion)
        statement.setString(23, scope.hash)
        statement.setLong(24, populationAsOf)
        statement.setLong(25, populationAsOf)
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
    applyTransactionTimeouts(
        lockTimeoutSeconds = 2,
        statementTimeoutSeconds = 5,
    )
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

    val remaining = countRecoverableGapWork()
    return GapPopulationRecoverySummary(
        applied = applied,
        unknown = unknown,
        remaining = remaining,
        processed = applied + unknown,
        state = when {
            unknown > 0 -> "EXPLICIT_UNKNOWN"
            remaining > 0 -> "MORE"
            else -> "ALL_APPLIED"
        },
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
    val unattributedProgress = selectUnattributedProgress(work.id)
    if (unattributedProgress.phase == "UNATTRIBUTED_TERMINATING") {
        val contained = containUnattributedPopulation(work.id, now, GAP_POPULATION_PASS_SIZE)
        if (contained >= GAP_POPULATION_PASS_SIZE) return "CAPTURING"

        markWorkUnknown(work.id, "UNKNOWN_SCOPE_UNATTRIBUTED", now)
        return "UNKNOWN"
    }

    val unattributedScan = discoverUnattributedPopulation(work, unattributedProgress, now)
    if (!unattributedScan.exhausted) return "CAPTURING"
    if (hasUnconsumedUnattributedAttachments(work.id)) {
        updateUnattributedProgress(
            workId = work.id,
            progress = UnattributedProgress(
                phase = "UNATTRIBUTED_TERMINATING",
                entityType = null,
                lastEntityId = null,
                processedCount = unattributedScan.processedCount,
            ),
            now = now,
        )
        return "CAPTURING"
    }
    val currentInserted = captureCurrentPopulation(work)
    val inserted = currentInserted + captureJournalPopulation(work, GAP_POPULATION_PASS_SIZE - currentInserted)
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
    if (inserted > 0) return "CAPTURING"

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

private data class UnattributedProgress(
    val phase: String,
    val entityType: String?,
    val lastEntityId: String?,
    val processedCount: Int,
)

private data class UnattributedScanResult(val exhausted: Boolean, val processedCount: Int)

private fun JdbcTransaction.discoverUnattributedPopulation(
    work: GapWorkRow,
    progress: UnattributedProgress,
    now: Instant,
): UnattributedScanResult {
    var discovered = 0
    var processedCount = progress.processedCount
    val initialIndex = progress.entityType?.let { type ->
        CURRENT_POPULATION_QUERIES.indexOfFirst { population -> population.type == type }
            .takeIf { index -> index >= 0 }
    } ?: 0
    var populationIndex = initialIndex
    var lastEntityId = progress.lastEntityId

    while (populationIndex < CURRENT_POPULATION_QUERIES.size) {
        val population = CURRENT_POPULATION_QUERIES[populationIndex]
        if (!relationExistsForGapPopulation(population.table)) {
            populationIndex += 1
            lastEntityId = null
            continue
        }
        val remaining = GAP_POPULATION_PASS_SIZE - discovered
        val entityIds = prepare(
            "SELECT population.entity_id FROM (${population.query}) population " +
                "WHERE (population.birth_sequence IS NULL OR population.birth_sequence <= ?) " +
                "AND (? IS NULL OR population.entity_id > ?) " +
                "AND NOT EXISTS (SELECT 1 FROM gap_population_entity_scopes scope " +
                "WHERE scope.entity_type=? AND scope.entity_id=population.entity_id) " +
                "ORDER BY population.entity_id LIMIT ?",
        ).use { statement ->
            statement.setLong(1, work.birthUpper)
            statement.setString(2, lastEntityId)
            statement.setString(3, lastEntityId)
            statement.setString(4, population.type)
            statement.setInt(5, remaining)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        entityIds.forEach { entityId -> attachUnattributedContainment(work.id, population.type, entityId, now) }
        discovered += entityIds.size
        processedCount += entityIds.size

        if (entityIds.size >= remaining) {
            updateUnattributedProgress(
                workId = work.id,
                progress = UnattributedProgress(
                    phase = "UNATTRIBUTED_SCANNING",
                    entityType = population.type,
                    lastEntityId = entityIds.last(),
                    processedCount = processedCount,
                ),
                now = now,
            )
            return UnattributedScanResult(exhausted = false, processedCount = processedCount)
        }
        populationIndex += 1
        lastEntityId = null
    }
    updateUnattributedProgress(
        workId = work.id,
        progress = UnattributedProgress(
            phase = "UNATTRIBUTED_EXHAUSTED",
            entityType = null,
            lastEntityId = null,
            processedCount = processedCount,
        ),
        now = now,
    )
    return UnattributedScanResult(exhausted = true, processedCount = processedCount)
}

private fun JdbcTransaction.selectUnattributedProgress(workId: UUID): UnattributedProgress = prepare(
    "SELECT phase,entity_type,last_entity_id,processed_count FROM market_data_gap_recovery_progress WHERE work_id=?",
).use { statement ->
    statement.setObject(1, workId)
    statement.executeQuery().use { rows ->
        require(rows.next())
        UnattributedProgress(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4))
    }
}

private fun JdbcTransaction.updateUnattributedProgress(
    workId: UUID,
    progress: UnattributedProgress,
    now: Instant,
) {
    prepare(
        "UPDATE market_data_gap_recovery_progress SET phase=?,entity_type=?,last_entity_id=?," +
            "processed_count=?,updated_at=? WHERE work_id=?",
    ).use { statement ->
        statement.setString(1, progress.phase)
        statement.setString(2, progress.entityType)
        statement.setString(3, progress.lastEntityId)
        statement.setInt(4, progress.processedCount)
        statement.setLong(5, now.toEpochMilli())
        statement.setObject(6, workId)
        require(statement.executeUpdate() == 1)
    }
}

private fun JdbcTransaction.hasUnconsumedUnattributedAttachments(workId: UUID): Boolean = prepare(
    "SELECT EXISTS(SELECT 1 FROM gap_population_unattributed_containment_works WHERE work_id=? AND consumed_at IS NULL)",
).use { statement ->
    statement.setObject(1, workId)
    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
}

internal fun JdbcTransaction.attachUnattributedContainment(
    workId: UUID,
    entityType: String,
    entityId: String,
    now: Instant,
) {
    var ownerId = UUID.randomUUID()
    val state = if (entityType == "POSITION" || entityType == "DECISION_RUN") "QUARANTINED" else "DISCOVERED"
    prepare(
        "INSERT INTO gap_population_unattributed_containments " +
            "(entity_type,entity_id,owner_id,state,allowed_transition,created_at,updated_at) " +
            "VALUES (?,?,?,?,?,?,?) ON CONFLICT(entity_type,entity_id) DO UPDATE SET updated_at=EXCLUDED.updated_at " +
            "RETURNING owner_id",
    ).use { statement ->
        statement.setString(1, entityType)
        statement.setString(2, entityId)
        statement.setObject(3, ownerId)
        statement.setString(4, state)
        statement.setString(5, unattributedContainmentTransition(entityType))
        statement.setLong(6, now.toEpochMilli())
        statement.setLong(7, now.toEpochMilli())
        statement.executeQuery().use { rows ->
            require(rows.next())
            ownerId = rows.getObject(1, UUID::class.java)
        }
    }
    prepare(
        "INSERT INTO gap_population_unattributed_containment_works(owner_id,work_id,attached_at) " +
            "VALUES (?,?,?) ON CONFLICT(owner_id,work_id) DO NOTHING",
    ).use { statement ->
        statement.setObject(1, ownerId)
        statement.setObject(2, workId)
        statement.setLong(3, now.toEpochMilli())
        statement.executeUpdate()
    }
}

private fun unattributedContainmentTransition(entityType: String): String = when (entityType) {
    "ORDER" -> "CANCELED"
    "POSITION", "DECISION_RUN" -> "QUARANTINED"
    "LLM_RUN", "LLM_RESERVATION", "EVALUATION_REPORT_JOB" -> "FAILED"
    "OPPORTUNITY_EPISODE" -> "GAP_SCOPE_UNATTRIBUTED"
    else -> error("Unsupported unattributed population: $entityType")
}

internal fun JdbcTransaction.containUnattributedPopulation(
    workId: UUID,
    now: Instant,
    limit: Int = GAP_POPULATION_PASS_SIZE,
): Int {
    val owners = selectUnattributedOwners(workId, limit)
    owners.forEach { owner -> containUnattributedOwner(owner, now) }

    return owners.size
}

private fun JdbcTransaction.selectUnattributedOwners(workId: UUID, limit: Int): List<UnattributedOwner> {
    return prepare(
        "SELECT containment.owner_id,containment.entity_type,containment.entity_id,containment.state " +
            "FROM gap_population_unattributed_containments containment " +
            "JOIN gap_population_unattributed_containment_works attached ON attached.owner_id=containment.owner_id " +
            "WHERE attached.work_id=? AND attached.consumed_at IS NULL ORDER BY containment.entity_type,containment.entity_id " +
            "LIMIT ? FOR UPDATE OF containment,attached",
    ).use { statement ->
        statement.setObject(1, workId)
        statement.setInt(2, limit)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(UnattributedOwner(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getString(4)))
                }
            }
        }
    }
}

private fun JdbcTransaction.containUnattributedOwner(owner: UnattributedOwner, now: Instant) {
    val isAlreadyContained = owner.state in setOf("QUARANTINED", "CONTAINED")
    if (isAlreadyContained) {
        completeUnattributedAttachments(owner, owner.state, now)
        return
    }

    prepare("SELECT set_config('fukurou.gap_population_unattributed_owner',?,true)").use { statement ->
        statement.setString(1, owner.ownerId.toString())
        statement.executeQuery().use { rows -> require(rows.next()) }
    }
    val changed = terminalizeUnattributedOwner(owner, now)
    require(changed == 1) { "unattributed entity is not in its allowed active state." }

    markUnattributedOwnerContained(owner.ownerId, now)
    completeUnattributedAttachments(owner, "CONTAINED", now)
}

private fun JdbcTransaction.markUnattributedOwnerContained(ownerId: UUID, now: Instant) {
    prepare(
        "UPDATE gap_population_unattributed_containments SET state='CONTAINED',terminal_at=?,updated_at=?," +
            "attempt_count=attempt_count+1 WHERE owner_id=? AND state IN ('DISCOVERED','TERMINALIZING')",
    ).use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setLong(2, now.toEpochMilli())
        statement.setObject(3, ownerId)
        require(statement.executeUpdate() == 1)
    }
}

private data class UnattributedOwner(
    val ownerId: UUID,
    val entityType: String,
    val entityId: String,
    val state: String,
)

private fun JdbcTransaction.terminalizeUnattributedOwner(owner: UnattributedOwner, now: Instant): Int =
    when (owner.entityType) {
        "ORDER" -> prepare(
            "UPDATE orders SET status='CANCELED',reason_ja='market-data gap: scope unattributed'," +
                "canceled_at=?,cancel_reason='gap_scope_unattributed',updated_at=? " +
                "WHERE id=?::uuid AND status IN ('OPEN','PENDING_CANCEL') AND side='BUY' AND position_id IS NULL",
        ).use { statement ->
            statement.setLong(1, now.toEpochMilli())
            statement.setLong(2, now.toEpochMilli())
            statement.setString(3, owner.entityId)
            statement.executeUpdate()
        }
        "LLM_RUN" -> prepare(
            "UPDATE llm_runs SET status='FAILED',finished_at=?,error_message='GAP_SCOPE_UNATTRIBUTED'," +
                "terminal_cause='GAP_SCOPE_UNATTRIBUTED' WHERE invocation_id=? AND status='RUNNING'",
        ).use { statement ->
            statement.setLong(1, now.toEpochMilli())
            statement.setString(2, owner.entityId)
            statement.executeUpdate()
        }
        "LLM_RESERVATION" -> prepare(
            "UPDATE llm_launch_reservations SET status='FAILED',reason='GAP_SCOPE_UNATTRIBUTED',finished_at=? " +
                "WHERE id=?::uuid AND status='RUNNING'",
        ).use { statement ->
            statement.setLong(1, now.toEpochMilli())
            statement.setString(2, owner.entityId)
            statement.executeUpdate()
        }
        "OPPORTUNITY_EPISODE" -> prepare(
            "UPDATE opportunity_episodes SET closed_at=?,close_reason='GAP_SCOPE_UNATTRIBUTED' " +
                "WHERE id=?::uuid AND closed_at IS NULL",
        ).use { statement ->
            statement.setLong(1, now.toEpochMilli())
            statement.setString(2, owner.entityId)
            statement.executeUpdate()
        }
        "EVALUATION_REPORT_JOB" -> prepare(
            "UPDATE evaluation_report_jobs SET status='FAILED',stage='FAILED',failure_code='GAP_SCOPE_UNATTRIBUTED'," +
                "failure_message='Population scope is unattributed.',updated_at=? " +
                "WHERE job_id=?::uuid AND status IN ('REQUESTED','RUNNING')",
        ).use { statement ->
            statement.setLong(1, now.toEpochMilli())
            statement.setString(2, owner.entityId)
            statement.executeUpdate()
        }
        else -> error("Unsupported terminalizable unattributed population: ${owner.entityType}")
    }

private fun JdbcTransaction.completeUnattributedAttachments(
    owner: UnattributedOwner,
    result: String,
    now: Instant,
) {
    val workIds = prepare(
        "UPDATE gap_population_unattributed_containment_works SET consumed_at=?,result=? " +
            "WHERE owner_id=? AND consumed_at IS NULL RETURNING work_id",
    ).use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setString(2, result)
        statement.setObject(3, owner.ownerId)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
    }
    workIds.forEach { attachedWorkId ->
        appendGapWorkEvidence(
            attachedWorkId,
            "UNATTRIBUTED_TERMINAL_$result",
            sha256("${owner.entityType}:$result"),
            now,
        )
    }
}

private fun JdbcTransaction.applyWorkPage(work: GapWorkRow, now: Instant): String {
    prepare("UPDATE market_data_gap_work SET state='APPLYING', updated_at=? WHERE id=?").use { statement ->
        statement.setLong(1, now.toEpochMilli())
        statement.setObject(2, work.id)
        statement.executeUpdate()
    }
    updateGenerationState(work.id, "APPLYING", now)

    val members = selectUnappliedMembers(work.id)
    if (members.any { member -> !applyMemberImpact(work, member, now) }) {
        markWorkUnknown(work.id, "UNKNOWN_SCOPE_CONFLICT", now)
        return "UNKNOWN"
    }
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
): Boolean {
    if (member.scopeHash != work.scope.hash) return false
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
    return true
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
                    (work_id, entity_type, entity_id, birth_sequence, projection_hash, source, captured_at,
                    scope_kind,scope_mode,scope_symbol,scope_account_epoch_id,scope_cohort,
                    scope_execution_semantics_version,scope_hash)
                SELECT ?, ?, population.entity_id, population.birth_sequence,
                    gap_population_projection_hash(?,population.entity_id,scope.scope_hash),
                    'CURRENT', ?, scope.scope_kind,scope.mode,scope.symbol,scope.account_epoch_id,scope.cohort,
                    scope.execution_semantics_version,scope.scope_hash
                FROM (${population.query}) population
                JOIN gap_population_entity_scopes scope ON scope.entity_type=? AND scope.entity_id=population.entity_id
                WHERE (population.birth_sequence IS NULL OR population.birth_sequence <= ?)
                  AND scope.scope_kind=? AND scope.mode=? AND scope.symbol IS NOT DISTINCT FROM ?
                  AND scope.account_epoch_id=? AND scope.cohort=?
                  AND scope.execution_semantics_version IS NOT DISTINCT FROM ? AND scope.scope_hash=?
                  AND NOT EXISTS (
                      SELECT 1 FROM market_data_gap_population_members existing
                      WHERE existing.work_id=? AND existing.entity_type=? AND existing.entity_id=population.entity_id
                        AND existing.projection_hash=gap_population_projection_hash(
                            ?,population.entity_id,scope.scope_hash
                        )
                  )
                ORDER BY population.birth_sequence NULLS FIRST, population.entity_id
                LIMIT ?
                ON CONFLICT (work_id, entity_type, entity_id) DO UPDATE SET projection_hash='HASH_MISMATCH'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, work.id)
                statement.setString(2, population.type)
                statement.setString(3, population.type)
                statement.setLong(4, work.populationAsOf)
                statement.setString(5, population.type)
                statement.setLong(6, work.birthUpper)
                statement.setString(7, work.scope.kind)
                statement.setString(8, work.scope.mode)
                statement.setString(9, work.scope.symbol)
                statement.setObject(10, work.scope.accountEpochId)
                statement.setString(11, work.scope.cohort)
                statement.setString(12, work.scope.executionSemanticsVersion)
                statement.setString(13, work.scope.hash)
                statement.setObject(14, work.id)
                statement.setString(15, population.type)
                statement.setString(16, population.type)
                statement.setInt(17, limit)
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

private fun JdbcTransaction.captureJournalPopulation(work: GapWorkRow, passBudget: Int): Int {
    if (passBudget == 0) return 0
    var total = 0
    do {
        val inserted = prepare(
            """
            INSERT INTO market_data_gap_population_members
                (work_id, entity_type, entity_id, birth_sequence, projection_hash, source, captured_at,
                scope_kind,scope_mode,scope_symbol,scope_account_epoch_id,scope_cohort,
                scope_execution_semantics_version,scope_hash)
            SELECT ?, entity_type, entity_id, birth_sequence, projection_hash, 'JOURNAL', ?,
                scope_kind,scope_mode,scope_symbol,scope_account_epoch_id,scope_cohort,
                scope_execution_semantics_version,scope_hash
            FROM market_data_gap_terminal_journal journal
            WHERE journal_sequence > ?
                AND journal_sequence <= COALESCE(?, journal_sequence)
                AND (birth_sequence IS NULL OR birth_sequence <= ?)
                AND scope_kind=? AND scope_mode=? AND scope_symbol IS NOT DISTINCT FROM ?
                AND scope_account_epoch_id=? AND scope_cohort=?
                AND scope_execution_semantics_version IS NOT DISTINCT FROM ? AND scope_hash=?
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
            statement.setString(6, work.scope.kind)
            statement.setString(7, work.scope.mode)
            statement.setString(8, work.scope.symbol)
            statement.setObject(9, work.scope.accountEpochId)
            statement.setString(10, work.scope.cohort)
            statement.setString(11, work.scope.executionSemanticsVersion)
            statement.setString(12, work.scope.hash)
            statement.setObject(13, work.id)
            statement.setInt(14, minOf(GAP_POPULATION_PAGE_SIZE, passBudget - total))
            statement.executeUpdate()
        }
        total += inserted
        val shouldContinue = inserted > 0 && total < passBudget
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
    val scope: GapPopulationScope,
)

private data class GapMemberRow(val entityType: String, val entityId: String, val scopeHash: String)

private fun JdbcTransaction.selectWorkForUpdate(workId: UUID): GapWorkRow? {
    return prepare(
        """
        SELECT w.id, w.gap_id, w.state, w.population_as_of, w.birth_sequence_upper,
            w.journal_sequence_lower, w.journal_sequence_upper,
            COALESCE((SELECT reason FROM market_data_gap_work_evidence e WHERE e.work_id=w.id ORDER BY created_at LIMIT 1), 'DATABASE_FAILURE'),
            w.scope_kind, w.scope_mode, w.scope_symbol, w.scope_account_epoch_id,
            w.scope_cohort, w.scope_execution_semantics_version
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
                scope = GapPopulationScope(
                    kind = rows.getString(9),
                    mode = rows.getString(10),
                    symbol = rows.getString(11),
                    accountEpochId = rows.getObject(12, UUID::class.java),
                    cohort = rows.getString(13),
                    executionSemanticsVersion = rows.getString(14),
                ),
            )
        }
    }
}

private fun JdbcTransaction.selectUnappliedMembers(workId: UUID): List<GapMemberRow> {
    return prepare(
        "SELECT entity_type, entity_id, scope_hash FROM market_data_gap_population_members " +
            "WHERE work_id=? AND applied_at IS NULL ORDER BY entity_type, entity_id LIMIT ?",
    ).use { statement ->
        statement.setObject(1, workId)
        statement.setInt(2, GAP_POPULATION_PASS_SIZE)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(GapMemberRow(rows.getString(1), rows.getString(2), rows.getString(3))) }
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
    val failed = prepare(
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
    if (failed == 1 && relationExistsForGapPopulation("llm_launch_reservations")) {
        prepare(
            """
            UPDATE llm_launch_reservations SET status='FAILED',reason='MARKET_DATA_GAP',finished_at=?
            WHERE invocation_id=? AND status='RUNNING'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, now.toEpochMilli())
            statement.setString(2, jobId)
            statement.executeUpdate()
        }
    }
}

private fun JdbcTransaction.selectExactWork(identity: GapSourceWorkIdentity, scope: GapPopulationScope): UUID? {
    return prepare(
        """
        SELECT id FROM market_data_gap_work
        WHERE provider=? AND transport_symbol=? AND channel=? AND session_id=? AND source_kind=? AND source_episode=?
          AND scope_hash=?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, identity.provider)
        statement.setString(2, identity.symbol)
        statement.setString(3, identity.channel)
        statement.setObject(4, identity.sessionId)
        statement.setString(5, identity.sourceKind)
        statement.setString(6, identity.sourceEpisode)
        statement.setString(7, scope.hash)
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
    if (gapPopulationEvidenceCapacityExceeded(count)) {
        prepare(
            """
            INSERT INTO market_data_gap_work_evidence (id,work_id,reason,detail_hash,observed_at,created_at)
            VALUES (?,?,'UNKNOWN_OVERFLOW',?, ?, ?)
            ON CONFLICT (work_id,reason,detail_hash) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.nameUUIDFromBytes("$workId:evidence-overflow".toByteArray()))
            statement.setObject(2, workId)
            statement.setString(3, sha256("EVIDENCE_LIMIT:$GAP_POPULATION_EVIDENCE_LIMIT"))
            statement.setLong(4, at.toEpochMilli())
            statement.setLong(5, at.toEpochMilli())
            statement.executeUpdate()
        }
        markWorkUnknown(workId, "UNKNOWN_OVERFLOW", at)
        return
    }
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
    scope: GapPopulationScope,
    gapId: UUID,
    now: Instant,
    pendingCount: Int,
): UUID {
    val overflowIdentity = identity.copy(sourceKind = "OVERFLOW", sourceEpisode = "QUEUE")
    selectExactWork(overflowIdentity, scope)?.let { return it }
    val workId = UUID.randomUUID()
    prepare(
        """
        INSERT INTO market_data_gap_work (
            id, gap_id, provider, transport_symbol, population_symbol, symbol, channel, session_id,
            source_kind, source_episode, enqueue_sequence,
            identity_hash, state, population_as_of, birth_sequence_upper, journal_sequence_lower,
            scope_kind, scope_mode, scope_symbol, scope_account_epoch_id, scope_cohort,
            scope_execution_semantics_version, scope_hash,
            unknown_code, overflow_count, created_at, updated_at, completed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OVERFLOW', 'QUEUE', ?, ?, 'UNKNOWN', ?, ?, ?, ?, ?, ?, ?, ?, ?,
            'UNKNOWN_OVERFLOW', ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, workId)
        statement.setObject(2, gapId)
        statement.setString(3, identity.provider)
        statement.setString(4, identity.symbol)
        statement.setString(5, requireNotNull(scope.symbol))
        statement.setString(6, identity.symbol)
        statement.setString(7, identity.channel)
        statement.setObject(8, identity.sessionId)
        statement.setLong(9, nextGapWorkEnqueueSequence())
        statement.setString(10, overflowIdentity.canonicalHash())
        statement.setLong(11, now.toEpochMilli())
        statement.setLong(12, currentBirthSequenceUpper())
        statement.setLong(13, currentJournalSequenceUpper())
        statement.setString(14, scope.kind)
        statement.setString(15, scope.mode)
        statement.setString(16, scope.symbol)
        statement.setObject(17, scope.accountEpochId)
        statement.setString(18, scope.cohort)
        statement.setString(19, scope.executionSemanticsVersion)
        statement.setString(20, scope.hash)
        statement.setInt(21, pendingCount)
        statement.setLong(22, now.toEpochMilli())
        statement.setLong(23, now.toEpochMilli())
        statement.setLong(24, now.toEpochMilli())
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
    appendGapWorkEvidence(workId, "UNKNOWN_OVERFLOW", "QUEUE_LIMIT:$pendingCount", now)
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
        ORDER BY queued.enqueue_sequence LIMIT 1 FOR UPDATE SKIP LOCKED
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
        "SELECT id FROM market_data_gap_work WHERE state IN ('CAPTURING','SEALED','APPLYING') " +
            "ORDER BY enqueue_sequence LIMIT 1",
    ).use { statement ->
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

internal fun JdbcTransaction.journalCapacityExceeded(
    rowLimit: Long = GAP_POPULATION_JOURNAL_LIMIT.toLong(),
    byteLimit: Long = GAP_POPULATION_JOURNAL_BYTES_LIMIT,
): Boolean {
    return prepare(
        "SELECT COUNT(*),COALESCE(SUM(octet_length(projection_hash) + octet_length(entity_id)), 0) " +
            "FROM market_data_gap_terminal_journal journal WHERE journal_sequence > COALESCE(" +
            "(SELECT MIN(journal_sequence_lower) FROM market_data_gap_work WHERE state='QUEUED')," +
            "(SELECT COALESCE(MAX(journal_sequence),0) FROM market_data_gap_terminal_journal))",
    ).use { statement ->
        statement.executeQuery().use { rows ->
            rows.next() && gapPopulationJournalCapacityExceeded(rows.getLong(1), rows.getLong(2), rowLimit, byteLimit)
        }
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

private fun JdbcTransaction.nextGapWorkEnqueueSequence(): Long {
    return prepare(
        "UPDATE gap_population_control SET enqueue_sequence=enqueue_sequence+1 WHERE id=1 RETURNING enqueue_sequence",
    ).use { statement ->
        statement.executeQuery().use { rows ->
            require(rows.next())
            rows.getLong(1)
        }
    }
}

private fun JdbcTransaction.selectCurrentPaperGapPopulationScope(populationSymbol: String): GapPopulationScope {
    return prepare(
        "SELECT mode, current_epoch_id FROM paper_account WHERE id=1 FOR SHARE",
    ).use { statement ->
        statement.executeQuery().use { rows ->
            require(rows.next()) { "paper account scope is unavailable." }
            val mode = rows.getString(1)
            val epochId = rows.getObject(2, UUID::class.java)
            require(mode == "PAPER" && epochId != null) { "current paper account scope is incomplete." }
            GapPopulationScope(
                kind = "SYMBOL",
                mode = mode,
                symbol = populationSymbol,
                accountEpochId = epochId,
                cohort = "CURRENT",
                executionSemanticsVersion = "PAPER_WS_V1",
            )
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
            token_version BIGINT NOT NULL DEFAULT 0,
            enqueue_sequence BIGINT NOT NULL DEFAULT 0,
            token_txid BIGINT,
            allowed_population VARCHAR(32),
            scope_kind VARCHAR(16),
            scope_mode VARCHAR(16),
            scope_symbol VARCHAR(32),
            scope_account_epoch_id UUID,
            scope_cohort VARCHAR(32),
            scope_execution_semantics_version VARCHAR(64)
        )
        """.trimIndent(),
        "INSERT INTO gap_population_control (id) VALUES (1) ON CONFLICT (id) DO NOTHING",
        "ALTER TABLE gap_population_control ADD COLUMN IF NOT EXISTS enqueue_sequence BIGINT NOT NULL DEFAULT 0",
        "ALTER TABLE gap_population_control ADD COLUMN IF NOT EXISTS token_txid BIGINT",
        "ALTER TABLE gap_population_control ADD COLUMN IF NOT EXISTS allowed_population VARCHAR(32)",
        "ALTER TABLE gap_population_control ADD COLUMN IF NOT EXISTS scope_kind VARCHAR(16)",
        "ALTER TABLE gap_population_control ADD COLUMN IF NOT EXISTS scope_mode VARCHAR(16)",
        "ALTER TABLE gap_population_control ADD COLUMN IF NOT EXISTS scope_symbol VARCHAR(32)",
        "ALTER TABLE gap_population_control ADD COLUMN IF NOT EXISTS scope_account_epoch_id UUID",
        "ALTER TABLE gap_population_control ADD COLUMN IF NOT EXISTS scope_cohort VARCHAR(32)",
        "ALTER TABLE gap_population_control ADD COLUMN IF NOT EXISTS scope_execution_semantics_version VARCHAR(64)",
        GAP_POPULATION_ACQUIRE_FUNCTION_SQL,
        GAP_POPULATION_SCOPED_ACQUIRE_FUNCTION_SQL,
        GAP_POPULATION_RESTRICTED_ACQUIRE_FUNCTION_SQL,
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
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS transport_symbol VARCHAR(32)",
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS population_symbol VARCHAR(32)",
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS enqueue_sequence BIGINT",
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS scope_kind VARCHAR(16)",
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS scope_mode VARCHAR(16)",
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS scope_symbol VARCHAR(32)",
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS scope_account_epoch_id UUID",
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS scope_cohort VARCHAR(32)",
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS scope_execution_semantics_version VARCHAR(64)",
        "ALTER TABLE market_data_gap_work ADD COLUMN IF NOT EXISTS scope_hash CHAR(64)",
        GAP_POPULATION_TRANSPORT_MAPPING_SQL,
        "CREATE INDEX IF NOT EXISTS market_data_gap_work_fifo_idx ON market_data_gap_work(provider, transport_symbol, channel, enqueue_sequence)",
        "ALTER TABLE market_data_gap_population_members ADD COLUMN IF NOT EXISTS scope_kind VARCHAR(16)",
        "ALTER TABLE market_data_gap_population_members ADD COLUMN IF NOT EXISTS scope_mode VARCHAR(16)",
        "ALTER TABLE market_data_gap_population_members ADD COLUMN IF NOT EXISTS scope_symbol VARCHAR(32)",
        "ALTER TABLE market_data_gap_population_members ADD COLUMN IF NOT EXISTS scope_account_epoch_id UUID",
        "ALTER TABLE market_data_gap_population_members ADD COLUMN IF NOT EXISTS scope_cohort VARCHAR(32)",
        "ALTER TABLE market_data_gap_population_members ADD COLUMN IF NOT EXISTS scope_execution_semantics_version VARCHAR(64)",
        "ALTER TABLE market_data_gap_population_members ADD COLUMN IF NOT EXISTS scope_hash CHAR(64)",
        "ALTER TABLE market_data_gap_terminal_journal ADD COLUMN IF NOT EXISTS scope_kind VARCHAR(16)",
        "ALTER TABLE market_data_gap_terminal_journal ADD COLUMN IF NOT EXISTS scope_mode VARCHAR(16)",
        "ALTER TABLE market_data_gap_terminal_journal ADD COLUMN IF NOT EXISTS scope_symbol VARCHAR(32)",
        "ALTER TABLE market_data_gap_terminal_journal ADD COLUMN IF NOT EXISTS scope_account_epoch_id UUID",
        "ALTER TABLE market_data_gap_terminal_journal ADD COLUMN IF NOT EXISTS scope_cohort VARCHAR(32)",
        "ALTER TABLE market_data_gap_terminal_journal ADD COLUMN IF NOT EXISTS scope_execution_semantics_version VARCHAR(64)",
        "ALTER TABLE market_data_gap_terminal_journal ADD COLUMN IF NOT EXISTS scope_hash CHAR(64)",
        """
        CREATE TABLE IF NOT EXISTS gap_population_entity_scopes (
            entity_type VARCHAR(32) NOT NULL,
            entity_id VARCHAR(128) NOT NULL,
            birth_sequence BIGINT NOT NULL,
            scope_kind VARCHAR(16) NOT NULL CHECK (scope_kind IN ('SYMBOL','GLOBAL')),
            mode VARCHAR(16) NOT NULL,
            symbol VARCHAR(32),
            account_epoch_id UUID NOT NULL,
            cohort VARCHAR(32) NOT NULL,
            execution_semantics_version VARCHAR(64),
            scope_hash CHAR(64) NOT NULL,
            created_at BIGINT NOT NULL,
            PRIMARY KEY(entity_type, entity_id)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS gap_population_entity_scope_scan_idx ON gap_population_entity_scopes " +
            "(scope_kind,mode,symbol,account_epoch_id,cohort,execution_semantics_version,birth_sequence,entity_type,entity_id)",
        GAP_POPULATION_ENTITY_SCOPE_IMMUTABLE_SQL,
        GAP_POPULATION_PROJECTION_HASH_SQL,
        """
        CREATE TABLE IF NOT EXISTS gap_population_unattributed_containments (
            entity_type VARCHAR(32) NOT NULL,
            entity_id VARCHAR(128) NOT NULL,
            owner_id UUID NOT NULL UNIQUE,
            state VARCHAR(32) NOT NULL CHECK (state IN ('DISCOVERED','TERMINALIZING','CONTAINED','QUARANTINED')),
            allowed_transition VARCHAR(64) NOT NULL,
            created_at BIGINT NOT NULL,
            updated_at BIGINT NOT NULL,
            terminal_at BIGINT,
            attempt_count BIGINT NOT NULL DEFAULT 0,
            PRIMARY KEY(entity_type, entity_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS gap_population_unattributed_containment_works (
            owner_id UUID NOT NULL REFERENCES gap_population_unattributed_containments(owner_id),
            work_id UUID NOT NULL REFERENCES market_data_gap_work(id),
            attached_at BIGINT NOT NULL,
            consumed_at BIGINT,
            result VARCHAR(32),
            PRIMARY KEY(owner_id, work_id)
        )
        """.trimIndent(),
        "ALTER TABLE orders ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
        "ALTER TABLE positions ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
        "ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
        "ALTER TABLE llm_launch_reservations ADD COLUMN IF NOT EXISTS birth_sequence BIGINT",
        "ALTER TABLE llm_launch_reservations ADD COLUMN IF NOT EXISTS population_scope_kind VARCHAR(16)",
        "ALTER TABLE llm_launch_reservations ADD COLUMN IF NOT EXISTS population_mode VARCHAR(16)",
        "ALTER TABLE llm_launch_reservations ADD COLUMN IF NOT EXISTS population_symbol VARCHAR(32)",
        "ALTER TABLE llm_launch_reservations ADD COLUMN IF NOT EXISTS population_account_epoch_id UUID",
        "ALTER TABLE llm_launch_reservations ADD COLUMN IF NOT EXISTS population_cohort VARCHAR(32)",
        "ALTER TABLE llm_launch_reservations ADD COLUMN IF NOT EXISTS population_execution_semantics_version VARCHAR(64)",
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
    "ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS population_scope_kind VARCHAR(16)",
    "ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS population_mode VARCHAR(16)",
    "ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS population_symbol VARCHAR(32)",
    "ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS population_account_epoch_id UUID",
    "ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS population_cohort VARCHAR(32)",
    "ALTER TABLE evaluation_report_jobs ADD COLUMN IF NOT EXISTS population_execution_semantics_version VARCHAR(64)",
    "CREATE INDEX IF NOT EXISTS evaluation_report_jobs_gap_birth_idx ON evaluation_report_jobs(birth_sequence, job_id)",
    "DROP TRIGGER IF EXISTS evaluation_report_jobs_gap_population_create ON evaluation_report_jobs",
    "CREATE TRIGGER evaluation_report_jobs_gap_population_create BEFORE INSERT ON evaluation_report_jobs " +
        "FOR EACH ROW EXECUTE FUNCTION enforce_gap_population_creation_token('job_id','EVALUATION_REPORT_JOB')",
    "DROP TRIGGER IF EXISTS evaluation_report_jobs_gap_population_terminal ON evaluation_report_jobs",
    "CREATE TRIGGER evaluation_report_jobs_gap_population_terminal BEFORE UPDATE ON evaluation_report_jobs FOR EACH ROW " +
        "EXECUTE FUNCTION fence_gap_population_terminal_mutation('job_id','STATUS','SUCCEEDED,FAILED,REJECTED','EVALUATION_REPORT_JOB')",
)

private const val GAP_POPULATION_TRANSPORT_MAPPING_SQL = """
CREATE OR REPLACE FUNCTION enforce_gap_population_transport_mapping() RETURNS trigger AS ${'$'}fn${'$'}
BEGIN
    IF NEW.transport_symbol IS NOT NULL AND NEW.population_symbol IS NOT NULL
       AND (NEW.provider,NEW.transport_symbol,NEW.channel,NEW.population_symbol)
           IS DISTINCT FROM ('GMO_COIN','BTC_JPY','TRADES','BTC') THEN
        RAISE EXCEPTION 'gap population transport mapping is not registered';
    END IF;
    RETURN NEW;
END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public;
REVOKE ALL ON FUNCTION enforce_gap_population_transport_mapping() FROM PUBLIC;
DROP TRIGGER IF EXISTS market_data_gap_work_transport_mapping ON market_data_gap_work;
CREATE TRIGGER market_data_gap_work_transport_mapping BEFORE INSERT OR UPDATE
ON market_data_gap_work FOR EACH ROW EXECUTE FUNCTION enforce_gap_population_transport_mapping()
"""

private const val GAP_POPULATION_PROJECTION_HASH_SQL = """
CREATE OR REPLACE FUNCTION gap_population_projection_hash(entity_type TEXT,entity_id TEXT,scope_hash TEXT)
RETURNS TEXT AS ${'$'}fn${'$'}
    SELECT encode(digest(entity_type || ':' || entity_id || ':' || scope_hash,'sha256'),'hex')
${'$'}fn${'$'} LANGUAGE sql IMMUTABLE STRICT SECURITY DEFINER SET search_path=pg_catalog,public;
REVOKE ALL ON FUNCTION gap_population_projection_hash(TEXT,TEXT,TEXT) FROM PUBLIC
"""

private const val GAP_POPULATION_TOKEN_TRIGGER_FUNCTION_SQL = """
CREATE OR REPLACE FUNCTION enforce_gap_population_creation_token() RETURNS trigger AS ${'$'}fn${'$'}
DECLARE
    configured TEXT;
    expected TEXT;
    control RECORD;
    entity_type_value TEXT;
    entity_id_value TEXT;
    row_json JSONB;
    entity_scope RECORD;
BEGIN
    configured := current_setting('fukurou.gap_population_token', true);
    SELECT *, txid_current()::text || ':' || token_version::text AS expected_token INTO control
    FROM gap_population_control WHERE id=1;
    expected := control.expected_token;
    IF configured IS NULL OR configured = '' OR configured <> expected THEN
        RAISE EXCEPTION 'gap population generation token is missing or stale';
    END IF;
    entity_type_value := TG_ARGV[1];
    IF control.allowed_population NOT IN ('ALL', entity_type_value) THEN
        RAISE EXCEPTION 'gap population token is not valid for %', entity_type_value;
    END IF;
    IF NEW.birth_sequence IS NOT NULL THEN RAISE EXCEPTION 'birth sequence is database assigned'; END IF;
    row_json := to_jsonb(NEW);
    IF row_json ? 'mode' AND row_json->>'mode' IS DISTINCT FROM control.scope_mode THEN
        RAISE EXCEPTION 'gap population mode does not match token scope';
    END IF;
    IF row_json ? 'symbol' AND row_json->>'symbol' IS DISTINCT FROM control.scope_symbol THEN
        RAISE EXCEPTION 'gap population symbol does not match token scope';
    END IF;
    IF row_json ? 'account_epoch_id' AND row_json->>'account_epoch_id' IS DISTINCT FROM control.scope_account_epoch_id::text THEN
        RAISE EXCEPTION 'gap population epoch does not match token scope';
    END IF;
    IF row_json ? 'execution_semantics_version'
        AND row_json->>'execution_semantics_version' IS DISTINCT FROM control.scope_execution_semantics_version THEN
        RAISE EXCEPTION 'gap population semantics does not match token scope';
    END IF;
    IF row_json ? 'population_scope_kind' AND row_json->>'population_scope_kind' IS DISTINCT FROM control.scope_kind THEN
        RAISE EXCEPTION 'explicit population scope kind does not match token';
    END IF;
    IF row_json ? 'population_mode' AND row_json->>'population_mode' IS DISTINCT FROM control.scope_mode THEN
        RAISE EXCEPTION 'explicit population mode does not match token';
    END IF;
    IF row_json ? 'population_symbol' AND row_json->>'population_symbol' IS DISTINCT FROM control.scope_symbol THEN
        RAISE EXCEPTION 'explicit population symbol does not match token';
    END IF;
    IF row_json ? 'population_account_epoch_id'
        AND row_json->>'population_account_epoch_id' IS DISTINCT FROM control.scope_account_epoch_id::text THEN
        RAISE EXCEPTION 'explicit population epoch does not match token';
    END IF;
    IF row_json ? 'population_cohort' AND row_json->>'population_cohort' IS DISTINCT FROM control.scope_cohort THEN
        RAISE EXCEPTION 'explicit population cohort does not match token';
    END IF;
    IF row_json ? 'population_execution_semantics_version'
        AND row_json->>'population_execution_semantics_version' IS DISTINCT FROM control.scope_execution_semantics_version THEN
        RAISE EXCEPTION 'explicit population semantics does not match token';
    END IF;
    entity_id_value := row_json->>TG_ARGV[0];
    SELECT * INTO entity_scope FROM gap_population_entity_scopes
    WHERE entity_type=entity_type_value AND entity_id=entity_id_value;
    IF entity_scope IS NOT NULL THEN
        IF entity_scope.scope_kind IS DISTINCT FROM control.scope_kind
            OR entity_scope.mode IS DISTINCT FROM control.scope_mode
            OR entity_scope.symbol IS DISTINCT FROM control.scope_symbol
            OR entity_scope.account_epoch_id IS DISTINCT FROM control.scope_account_epoch_id
            OR entity_scope.cohort IS DISTINCT FROM control.scope_cohort
            OR entity_scope.execution_semantics_version IS DISTINCT FROM control.scope_execution_semantics_version THEN
            RAISE EXCEPTION 'existing entity scope does not match token';
        END IF;
        NEW.birth_sequence := entity_scope.birth_sequence;
        RETURN NEW;
    END IF;
    NEW.birth_sequence := nextval('gap_population_birth_sequence');
    INSERT INTO gap_population_entity_scopes (
        entity_type,entity_id,birth_sequence,scope_kind,mode,symbol,account_epoch_id,cohort,
        execution_semantics_version,scope_hash,created_at
    ) VALUES (
        entity_type_value,entity_id_value,NEW.birth_sequence,control.scope_kind,control.scope_mode,
        control.scope_symbol,control.scope_account_epoch_id,control.scope_cohort,
        control.scope_execution_semantics_version,
        encode(digest(control.scope_kind || ':' || control.scope_mode || ':' || COALESCE(control.scope_symbol,'') || ':' ||
            control.scope_account_epoch_id::text || ':' || control.scope_cohort || ':' ||
            COALESCE(control.scope_execution_semantics_version,''),'sha256'),'hex'),
        (extract(epoch from clock_timestamp()) * 1000)::bigint
    );
    IF entity_type_value IN ('ORDER','POSITION') AND row_json->>'decision_run_id' IS NOT NULL THEN
        INSERT INTO gap_population_entity_scopes (
            entity_type,entity_id,birth_sequence,scope_kind,mode,symbol,account_epoch_id,cohort,
            execution_semantics_version,scope_hash,created_at
        ) SELECT 'DECISION_RUN',row_json->>'decision_run_id',NEW.birth_sequence,scope_kind,mode,symbol,
            account_epoch_id,cohort,execution_semantics_version,scope_hash,created_at
          FROM gap_population_entity_scopes
         WHERE entity_type=entity_type_value AND entity_id=entity_id_value
        ON CONFLICT (entity_type,entity_id) DO NOTHING;
        IF EXISTS (
            SELECT 1 FROM gap_population_entity_scopes
            WHERE entity_type='DECISION_RUN' AND entity_id=row_json->>'decision_run_id'
              AND scope_hash IS DISTINCT FROM encode(digest(control.scope_kind || ':' || control.scope_mode || ':' ||
                COALESCE(control.scope_symbol,'') || ':' || control.scope_account_epoch_id::text || ':' ||
                control.scope_cohort || ':' || COALESCE(control.scope_execution_semantics_version,''),'sha256'),'hex')
        ) THEN RAISE EXCEPTION 'derived decision run scope conflict'; END IF;
    END IF;
    RETURN NEW;
END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION enforce_gap_population_creation_token() FROM PUBLIC
"""

private const val GAP_POPULATION_ACQUIRE_FUNCTION_SQL = """
CREATE OR REPLACE FUNCTION acquire_gap_population_generation_token() RETURNS void AS ${'$'}fn${'$'}
DECLARE
    current_version BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM public.gap_population_control WHERE id=1 AND token_txid=txid_current()) THEN
        RAISE EXCEPTION 'gap population token was already acquired in this transaction';
    END IF;
    UPDATE public.gap_population_control
    SET token_version = token_version + 1, token_txid=txid_current(), allowed_population='ALL',
        scope_kind='SYMBOL', scope_mode='PAPER', scope_symbol='BTC',
        scope_account_epoch_id=(SELECT current_epoch_id FROM public.paper_account WHERE id=1 AND mode='PAPER'),
        scope_cohort='CURRENT', scope_execution_semantics_version='PAPER_WS_V1'
    WHERE id = 1 AND (SELECT current_epoch_id FROM public.paper_account WHERE id=1 AND mode='PAPER') IS NOT NULL
    RETURNING token_version INTO current_version;
    IF current_version IS NULL THEN
        RAISE EXCEPTION 'gap population control row was not initialized';
    END IF;
    PERFORM set_config('fukurou.gap_population_token', txid_current()::text || ':' || current_version::text, true);
END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION acquire_gap_population_generation_token() FROM PUBLIC
"""

private const val GAP_POPULATION_RESTRICTED_ACQUIRE_FUNCTION_SQL = """
CREATE OR REPLACE FUNCTION acquire_opportunity_episode_gap_population_token(population_symbol TEXT) RETURNS void AS ${'$'}fn${'$'}
DECLARE current_version BIGINT;
BEGIN
    IF population_symbol <> 'BTC' THEN RAISE EXCEPTION 'population symbol is not registered'; END IF;
    IF EXISTS (SELECT 1 FROM public.gap_population_control WHERE id=1 AND token_txid=txid_current()) THEN
        RAISE EXCEPTION 'gap population token was already acquired in this transaction';
    END IF;
    UPDATE public.gap_population_control
    SET token_version=token_version+1,token_txid=txid_current(),allowed_population='OPPORTUNITY_EPISODE',
        scope_kind='SYMBOL',scope_mode='PAPER',scope_symbol=population_symbol,
        scope_account_epoch_id=(SELECT current_epoch_id FROM public.paper_account WHERE id=1 AND mode='PAPER'),
        scope_cohort='CURRENT',scope_execution_semantics_version='PAPER_WS_V1'
    WHERE id=1 AND (SELECT current_epoch_id FROM public.paper_account WHERE id=1 AND mode='PAPER') IS NOT NULL
    RETURNING token_version INTO current_version;
    IF current_version IS NULL THEN RAISE EXCEPTION 'current paper scope is unavailable'; END IF;
    IF EXISTS (
        SELECT 1 FROM public.gap_population_unattributed_containments containment
        WHERE containment.state IN ('DISCOVERED','TERMINALIZING','QUARANTINED')
           OR EXISTS (
               SELECT 1 FROM public.gap_population_unattributed_containment_works attached
               WHERE attached.owner_id=containment.owner_id AND attached.consumed_at IS NULL
           )
    ) THEN
        RAISE EXCEPTION 'decision entry intent is blocked by gap population recovery';
    END IF;
    PERFORM set_config('fukurou.gap_population_token',txid_current()::text || ':' || current_version::text,true);
END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public;
REVOKE ALL ON FUNCTION acquire_opportunity_episode_gap_population_token(TEXT) FROM PUBLIC
"""

private const val GAP_POPULATION_SCOPED_ACQUIRE_FUNCTION_SQL = """
CREATE OR REPLACE FUNCTION acquire_gap_population_generation_token(
    requested_kind TEXT,requested_mode TEXT,requested_symbol TEXT,requested_epoch UUID,
    requested_cohort TEXT,requested_semantics TEXT
) RETURNS void AS ${'$'}fn${'$'}
DECLARE current_version BIGINT;
BEGIN
    IF requested_kind NOT IN ('SYMBOL','GLOBAL') OR requested_mode <> 'PAPER'
        OR (requested_kind='SYMBOL' AND requested_symbol <> 'BTC')
        OR requested_cohort NOT IN ('CURRENT','LEGACY_PRE_WS')
        OR (requested_cohort='CURRENT' AND requested_semantics IS DISTINCT FROM 'PAPER_WS_V1')
        OR (requested_cohort='LEGACY_PRE_WS' AND requested_semantics IS NOT NULL)
        OR NOT EXISTS (SELECT 1 FROM public.paper_account_epochs WHERE id=requested_epoch) THEN
        RAISE EXCEPTION 'requested gap population scope is invalid';
    END IF;
    IF EXISTS (SELECT 1 FROM public.gap_population_control WHERE id=1 AND token_txid=txid_current()) THEN
        RAISE EXCEPTION 'gap population token was already acquired in this transaction';
    END IF;
    UPDATE public.gap_population_control SET token_version=token_version+1,token_txid=txid_current(),
        allowed_population='ALL',scope_kind=requested_kind,scope_mode=requested_mode,scope_symbol=requested_symbol,
        scope_account_epoch_id=requested_epoch,scope_cohort=requested_cohort,
        scope_execution_semantics_version=requested_semantics
    WHERE id=1 RETURNING token_version INTO current_version;
    IF current_version IS NULL THEN RAISE EXCEPTION 'gap population control row was not initialized'; END IF;
    PERFORM set_config('fukurou.gap_population_token',txid_current()::text || ':' || current_version::text,true);
END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public;
REVOKE ALL ON FUNCTION acquire_gap_population_generation_token(TEXT,TEXT,TEXT,UUID,TEXT,TEXT) FROM PUBLIC
"""

private const val GAP_POPULATION_ENTITY_SCOPE_IMMUTABLE_SQL = """
CREATE OR REPLACE FUNCTION reject_gap_population_entity_scope_mutation() RETURNS trigger AS ${'$'}fn${'$'}
BEGIN RAISE EXCEPTION 'gap population entity scope is immutable'; END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public;
REVOKE ALL ON FUNCTION reject_gap_population_entity_scope_mutation() FROM PUBLIC;
DROP TRIGGER IF EXISTS gap_population_entity_scope_immutable ON gap_population_entity_scopes;
CREATE TRIGGER gap_population_entity_scope_immutable BEFORE UPDATE OR DELETE ON gap_population_entity_scopes
FOR EACH ROW EXECUTE FUNCTION reject_gap_population_entity_scope_mutation()
"""

private const val GAP_POPULATION_TERMINAL_TRIGGER_FUNCTION_SQL = """
CREATE OR REPLACE FUNCTION fence_gap_population_terminal_mutation() RETURNS trigger AS ${'$'}fn${'$'}
DECLARE
    configured TEXT;
    expected TEXT;
    entity_id_value TEXT;
    projection_hash_value TEXT;
    terminal BOOLEAN;
    control RECORD;
    entity_scope RECORD;
    unattributed_owner TEXT;
    containment RECORD;
    decision_run_id_value TEXT;
    decision_run_scope RECORD;
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
    SELECT *,txid_current()::text || ':' || token_version::text AS expected_token INTO control
    FROM gap_population_control WHERE id=1;
    expected := control.expected_token;
    IF configured IS NULL OR configured = '' OR configured <> expected THEN
        RAISE EXCEPTION 'gap population generation token is missing or stale';
    END IF;

    entity_id_value := to_jsonb(NEW)->>TG_ARGV[0];
    IF control.allowed_population NOT IN ('ALL',TG_ARGV[3]) THEN
        RAISE EXCEPTION 'gap population token is not valid for terminal mutation';
    END IF;
    SELECT * INTO entity_scope FROM gap_population_entity_scopes
    WHERE entity_type=TG_ARGV[3] AND entity_id=entity_id_value;
    IF entity_scope IS NULL THEN
        unattributed_owner := current_setting('fukurou.gap_population_unattributed_owner',true);
        SELECT * INTO containment FROM gap_population_unattributed_containments
        WHERE owner_id::text=unattributed_owner AND entity_type=TG_ARGV[3] AND entity_id=entity_id_value
          AND state IN ('DISCOVERED','TERMINALIZING');
        IF containment IS NULL OR NOT EXISTS (
            SELECT 1 FROM gap_population_unattributed_containment_works attached
            JOIN market_data_gap_work work ON work.id=attached.work_id
            LEFT JOIN market_data_gap_recovery_progress progress ON progress.work_id=work.id
            WHERE attached.owner_id=containment.owner_id AND attached.consumed_at IS NULL
              AND (
                  (work.state='UNKNOWN' AND work.unknown_code='UNKNOWN_SCOPE_UNATTRIBUTED')
                  OR progress.phase='UNATTRIBUTED_TERMINATING'
              )
        ) THEN RAISE EXCEPTION 'gap population entity scope is missing'; END IF;
        IF TG_ARGV[3]='ORDER' AND NOT (
            to_jsonb(OLD)->>'status' IN ('OPEN','PENDING_CANCEL')
            AND to_jsonb(NEW)->>'status' IN ('CANCELED','REJECTED','EXPIRED')
            AND to_jsonb(OLD)->>'symbol' IS NOT DISTINCT FROM to_jsonb(NEW)->>'symbol'
            AND to_jsonb(OLD)->>'side' IS NOT DISTINCT FROM to_jsonb(NEW)->>'side'
            AND to_jsonb(OLD)->>'size_btc' IS NOT DISTINCT FROM to_jsonb(NEW)->>'size_btc'
        ) THEN RAISE EXCEPTION 'unattributed ORDER transition is not risk reducing';
        ELSIF TG_ARGV[3]='LLM_RUN' AND NOT (
            to_jsonb(OLD)->>'status'='RUNNING' AND to_jsonb(NEW)->>'status' IN ('FAILED','TIMED_OUT','CANCELLED')
        ) THEN RAISE EXCEPTION 'unattributed LLM_RUN transition is not allowed';
        ELSIF TG_ARGV[3]='LLM_RESERVATION' AND NOT (
            to_jsonb(OLD)->>'status'='RUNNING' AND to_jsonb(NEW)->>'status' IN ('FAILED','REJECTED')
        ) THEN RAISE EXCEPTION 'unattributed LLM_RESERVATION transition is not allowed';
        ELSIF TG_ARGV[3]='OPPORTUNITY_EPISODE' AND NOT (
            to_jsonb(OLD)->>'closed_at' IS NULL AND to_jsonb(NEW)->>'closed_at' IS NOT NULL
            AND to_jsonb(NEW)->>'close_reason'='GAP_SCOPE_UNATTRIBUTED'
            AND to_jsonb(OLD)->>'symbol' IS NOT DISTINCT FROM to_jsonb(NEW)->>'symbol'
        ) THEN RAISE EXCEPTION 'unattributed OPPORTUNITY_EPISODE transition is not allowed';
        ELSIF TG_ARGV[3]='EVALUATION_REPORT_JOB' AND NOT (
            to_jsonb(OLD)->>'status' IN ('REQUESTED','RUNNING') AND to_jsonb(NEW)->>'status' IN ('FAILED','REJECTED')
            AND to_jsonb(NEW)->>'failure_code'='GAP_SCOPE_UNATTRIBUTED'
        ) THEN RAISE EXCEPTION 'unattributed EVALUATION_REPORT_JOB transition is not allowed';
        END IF;
        RETURN NEW;
    END IF;
    IF entity_scope.scope_kind IS DISTINCT FROM control.scope_kind
        OR entity_scope.mode IS DISTINCT FROM control.scope_mode
        OR entity_scope.symbol IS DISTINCT FROM control.scope_symbol
        OR entity_scope.account_epoch_id IS DISTINCT FROM control.scope_account_epoch_id
        OR entity_scope.cohort IS DISTINCT FROM control.scope_cohort
        OR entity_scope.execution_semantics_version IS DISTINCT FROM control.scope_execution_semantics_version THEN
        RAISE EXCEPTION 'gap population terminal scope does not match token';
    END IF;
    IF (to_jsonb(OLD)->>'symbol') IS DISTINCT FROM (to_jsonb(NEW)->>'symbol') THEN
        RAISE EXCEPTION 'gap population terminal cannot change symbol';
    END IF;
    projection_hash_value := gap_population_projection_hash(TG_ARGV[3],entity_id_value,entity_scope.scope_hash);
    INSERT INTO market_data_gap_population_members
        (work_id,entity_type,entity_id,birth_sequence,projection_hash,source,captured_at,
        scope_kind,scope_mode,scope_symbol,scope_account_epoch_id,scope_cohort,scope_execution_semantics_version,scope_hash)
    SELECT work.id, TG_ARGV[3], entity_id_value, NEW.birth_sequence, projection_hash_value, 'TERMINAL',
        (extract(epoch from clock_timestamp()) * 1000)::bigint,entity_scope.scope_kind,entity_scope.mode,
        entity_scope.symbol,entity_scope.account_epoch_id,entity_scope.cohort,
        entity_scope.execution_semantics_version,entity_scope.scope_hash
    FROM market_data_gap_work work
    WHERE work.state='CAPTURING'
      AND (NEW.birth_sequence IS NULL OR NEW.birth_sequence <= work.birth_sequence_upper)
      AND work.scope_hash=entity_scope.scope_hash
    ON CONFLICT (work_id, entity_type, entity_id) DO UPDATE
    SET projection_hash = CASE
        WHEN market_data_gap_population_members.projection_hash=EXCLUDED.projection_hash
            THEN market_data_gap_population_members.projection_hash
        ELSE 'HASH_MISMATCH'
    END;

    IF EXISTS (SELECT 1 FROM market_data_gap_work WHERE state='QUEUED') THEN
        INSERT INTO market_data_gap_terminal_journal
            (entity_type,entity_id,birth_sequence,projection_hash,terminal_at,
            scope_kind,scope_mode,scope_symbol,scope_account_epoch_id,scope_cohort,scope_execution_semantics_version,scope_hash)
        VALUES (TG_ARGV[3], entity_id_value, NEW.birth_sequence, projection_hash_value,
            (extract(epoch from clock_timestamp()) * 1000)::bigint,entity_scope.scope_kind,entity_scope.mode,
            entity_scope.symbol,entity_scope.account_epoch_id,entity_scope.cohort,
            entity_scope.execution_semantics_version,entity_scope.scope_hash)
        ON CONFLICT (entity_type, entity_id) DO UPDATE
        SET projection_hash = CASE
            WHEN market_data_gap_terminal_journal.projection_hash=EXCLUDED.projection_hash
                THEN market_data_gap_terminal_journal.projection_hash
            ELSE 'HASH_MISMATCH'
        END;
    END IF;

    IF TG_ARGV[3] IN ('ORDER','POSITION') THEN
        decision_run_id_value := to_jsonb(NEW)->>'decision_run_id';
        IF decision_run_id_value IS NULL AND TG_ARGV[3]='POSITION' THEN
            SELECT entry.decision_run_id INTO decision_run_id_value
            FROM orders entry
            WHERE entry.trade_group_id=(to_jsonb(NEW)->>'trade_group_id')::uuid
              AND entry.status='FILLED' AND entry.side='BUY' AND entry.decision_run_id IS NOT NULL
            ORDER BY entry.created_at,entry.id LIMIT 1;
        END IF;
        IF decision_run_id_value IS NOT NULL THEN
            SELECT * INTO decision_run_scope FROM gap_population_entity_scopes
            WHERE entity_type='DECISION_RUN' AND entity_id=decision_run_id_value;
            IF decision_run_scope IS NULL OR decision_run_scope.scope_hash IS DISTINCT FROM entity_scope.scope_hash THEN
                RAISE EXCEPTION 'derived decision run scope is missing or inconsistent';
            END IF;
            projection_hash_value := gap_population_projection_hash(
                'DECISION_RUN',decision_run_id_value,decision_run_scope.scope_hash
            );
            INSERT INTO market_data_gap_population_members
                (work_id,entity_type,entity_id,birth_sequence,projection_hash,source,captured_at,
                scope_kind,scope_mode,scope_symbol,scope_account_epoch_id,scope_cohort,
                scope_execution_semantics_version,scope_hash)
            SELECT work.id,'DECISION_RUN',decision_run_id_value,decision_run_scope.birth_sequence,
                projection_hash_value,'TERMINAL',(extract(epoch from clock_timestamp()) * 1000)::bigint,
                decision_run_scope.scope_kind,decision_run_scope.mode,decision_run_scope.symbol,
                decision_run_scope.account_epoch_id,decision_run_scope.cohort,
                decision_run_scope.execution_semantics_version,decision_run_scope.scope_hash
            FROM market_data_gap_work work
            WHERE work.state='CAPTURING'
              AND decision_run_scope.birth_sequence <= work.birth_sequence_upper
              AND work.scope_hash=decision_run_scope.scope_hash
            ON CONFLICT (work_id,entity_type,entity_id) DO UPDATE SET projection_hash=CASE
                WHEN market_data_gap_population_members.projection_hash=EXCLUDED.projection_hash
                    THEN market_data_gap_population_members.projection_hash
                ELSE 'HASH_MISMATCH'
            END;
            IF EXISTS (SELECT 1 FROM market_data_gap_work WHERE state='QUEUED') THEN
                INSERT INTO market_data_gap_terminal_journal
                    (entity_type,entity_id,birth_sequence,projection_hash,terminal_at,
                    scope_kind,scope_mode,scope_symbol,scope_account_epoch_id,scope_cohort,
                    scope_execution_semantics_version,scope_hash)
                VALUES ('DECISION_RUN',decision_run_id_value,decision_run_scope.birth_sequence,
                    projection_hash_value,(extract(epoch from clock_timestamp()) * 1000)::bigint,
                    decision_run_scope.scope_kind,decision_run_scope.mode,decision_run_scope.symbol,
                    decision_run_scope.account_epoch_id,decision_run_scope.cohort,
                    decision_run_scope.execution_semantics_version,decision_run_scope.scope_hash)
                ON CONFLICT (entity_type,entity_id) DO UPDATE SET projection_hash=CASE
                    WHEN market_data_gap_terminal_journal.projection_hash=EXCLUDED.projection_hash
                        THEN market_data_gap_terminal_journal.projection_hash
                    ELSE 'HASH_MISMATCH'
                END;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END
${'$'}fn${'$'} LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION fence_gap_population_terminal_mutation() FROM PUBLIC
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
            "FOR EACH ROW EXECUTE FUNCTION enforce_gap_population_creation_token('$idColumn','$entityType')",
        "DROP TRIGGER IF EXISTS ${table}_gap_population_terminal ON $table",
        "CREATE TRIGGER ${table}_gap_population_terminal BEFORE UPDATE ON $table FOR EACH ROW " +
            "EXECUTE FUNCTION fence_gap_population_terminal_mutation('$idColumn','$terminalKind','$terminalValues','$entityType')",
    )
}
